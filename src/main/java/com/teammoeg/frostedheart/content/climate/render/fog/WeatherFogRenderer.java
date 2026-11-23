/*
 * Copyright (c) 2026 TeamMoeg
 *
 * This file is part of Frosted Heart.
 *
 * Frosted Heart is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Frosted Heart is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Frosted Heart. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.teammoeg.frostedheart.content.climate.render.fog;

import com.lowdragmc.lowdraglib.client.shader.management.Shader;
import com.lowdragmc.lowdraglib.client.shader.management.ShaderProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.ClientClimateData;
import com.teammoeg.frostedheart.content.climate.render.sky.CalculatedSkyRenderer;
import com.teammoeg.frostedheart.content.climate.render.sky.LightPillarSources;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyDrawTimer;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import com.teammoeg.frostedheart.infrastructure.config.FHConfig;
import com.teammoeg.frostedheart.mixin.client.LightTextureAccessor_WeatherFog;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the fog of snow and blizzards over the frame after the translucent terrain and the tripwire layer and before
 * the particles, the clouds and the weather, in frames where the calculated sky was drawn. The draw is a setup and four
 * passes: weather_fog_share.fsh fills the share volume of the field's cells around the eye, weather_fog_inject.fsh walks
 * the piece of its column's ray that each fragment stands for and writes its optical depth to the froxel atlas,
 * weather_fog_integrate.fsh sums the pieces of each column and writes the excess of the walked depth over the analytic
 * height fog at every slice boundary, and weather_fog_composite.fsh reads the depth of the main target, rebuilds the
 * scene point of each pixel from it, and blends a fog color over the pixel by the opacity that the height fog of
 * HeightFog gives along the segment from the eye to that point, together with the excess at its distance. The fog
 * shortens the visibility in the snow and the blizzard, thins out with height by the fog height scale, and stands as far
 * as the settings say at every render distance.
 * <p>
 * A pixel takes the fog of the snow along the segment from the eye to its scene point: the share of each cell near the
 * eye, which the snow field's distance to open sky gives, blended toward the share of the column's motion height over
 * the last blocks before the region's edge, and beyond the region the height share of the world-aligned far cells, as
 * weather_fog.glsl, snow_walk.glsl and weather_fog_inject.fsh walk each piece of the ray. The pieces of a column are
 * summed into the excess of the walked depth over the analytic height fog at every slice boundary, which
 * weather_fog_integrate.fsh writes and weather_fog_composite.fsh reads at each pixel's own distance between the columns
 * around it. A room therefore stays clear where the whole segment from the eye to the scene point stays under its roof,
 * and a porch holds the fog back by the first open air under it. The field's share fades in over half a second
 * after a fill filled the shown box, so after a jump partial cover gains its fog once and smoothly instead of
 * along a moving edge. The fog color is the light on a white face at full sky light, which the renderer reads from the
 * lightmap every frame, so the fog, the terrain and the sky follow one light. A pixel of the sky has no scene point and
 * takes the same snow fog along its ray to infinity, so the sky converges to the color of the snow that hides the
 * distant terrain, and nothing behind the fog can come out brighter than the fog in front of it. Vanilla's terrain fog
 * ends where the fog is already opaque, so that the terrain beyond it is culled, which it does only while the eye stands
 * in open air of the field, and vanilla's fog color turns to the same light, which the sky shader's rain mix and
 * horizon fade, the clouds and the culled terrain take. Everything that draws after the pass until the end of the
 * weather stage, which is the particles, the clouds and the weather, gets vanilla's fog fitted to the fog at the eye,
 * and the clouds get the cloud fit of fitCloudFog, which fades them by the fog from the eye to their layer.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WeatherFogRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The scene depth goes to texture unit 0 while the fog draws, the share volume to unit 1, the snow field to unit 2,
     * the far heights to unit 3, the share table to unit 4, the structures of the fog to unit 5, the inject atlas to
     * unit 6, and the excesses of the froxel grid to unit 7. Unit 1 is free because no other pass of the fog draws to
     * it, and SkyGlPass keeps the 2D binding of every unit up to 7, so the binding of the frame before comes back after
     * the passes. The excesses are bound as a 2D array, which SkyGlPass does not keep, so the draw saves and restores
     * that binding itself.
     */
    private static final int SCENE_DEPTH_UNIT = 0;
    private static final int SHARE_VOLUME_UNIT = 1;
    private static final int FIELD_UNIT = 2;
    private static final int HEIGHT_UNIT = 3;
    private static final int SHARE_TABLE_UNIT = 4;
    private static final int STRUCTURE_UNIT = 5;
    private static final int ATLAS_UNIT = 6;
    private static final int EXCESS_UNIT = 7;
    private static final int COLOR_CHANNELS = 3;
    /**
     * The sky light of the two lightmap pixels whose mean the snow fog color is, at block light 0: the rows 14 and 15 of
     * the lightmap, whose full sky light coordinate lies halfway between them.
     */
    private static final int LIGHTMAP_SKY_FOURTEEN = 14;
    private static final int LIGHTMAP_SKY_FIFTEEN = 15;
    /**
     * The seconds over which the field's share fades in once a fill has filled the shown box.
     */
    private static final double FILL_FADE_SECONDS = 0.5;
    /**
     * The seconds the eye must stand in open air before vanilla's fog is bounded, so that a jump in or out of a shelter
     * does not move the fog end every frame.
     */
    private static final double OPEN_AIR_SECONDS = 1.0;
    /**
     * The rays of the self test, the width of its target in texels, and the values it writes for each ray.
     */
    private static final int SELF_TEST_RAY_COUNT = 64;
    private static final int SELF_TEST_VALUES_PER_RAY = 4;
    private static final int SELF_TEST_SPIRAL_RAYS = 48;
    /**
     * The lengths in blocks of the rays of the self test's spiral, which it cycles through, and the angle in radians
     * between two of them.
     */
    private static final double[] SELF_TEST_SPIRAL_LENGTHS = {6.0, 25.0, 45.0, 120.0, 300.0};
    private static final double SELF_TEST_GOLDEN_ANGLE = 2.39996323;
    /**
     * The tolerance on the optical depth of the self test, absolute and relative: a boundary and a sky value may differ
     * from the walk by this much plus this share of its depth.
     */
    private static final double SELF_TEST_WALK_TOLERANCE = 1.0e-3;
    /**
     * The share of the walked depth within which the self test's rays must match the walk while the structures are on,
     * since the filtered reads of the GPU round their weights and the profile steepens the error.
     */
    private static final double SELF_TEST_STRUCTURE_TOLERANCE_SHARE = 0.02;

    private static final ClientMockWeather mockWeather = new ClientMockWeather();
    private static final SkyDrawTimer timer = new SkyDrawTimer();
    private static final FogPassTimer passTimer = new FogPassTimer();
    private static final SkyGlPass pass = new SkyGlPass(8);
    private static final ShareProgram sharePass = new ShareProgram();
    private static final InjectProgram injectPass = new InjectProgram();
    private static final IntegrateProgram integratePass = new IntegrateProgram();
    private static final CompositeProgram compositePass = new CompositeProgram();
    private static final WeatherWind wind = new WeatherWind();
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final Vector4f eyeColumn = new Vector4f();
    private static final Vector3f eyeOffset = new Vector3f();
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final float[] snowColor = new float[COLOR_CHANNELS];
    /**
     * The number of frames whose snow fog color came from the config colors because the lightmap could not be read, and
     * whether the color of the last frame came from the lightmap, for the client command.
     */
    private static long snowColorFallbacks;
    private static boolean snowColorFromLightmap = true;
    /**
     * Whether reading the lightmap threw, after which it is not read again.
     */
    private static boolean lightmapUnreadable;
    /**
     * The share of each distance byte of the field, which SnowFogWalk.buildTable fills, and the buffer that uploads it.
     */
    private static final float[] shareTable = new float[SnowFogWalk.TABLE_SIZE];
    private static final FloatBuffer shareTableBuffer = BufferUtils.createFloatBuffer(SnowFogWalk.TABLE_SIZE);
    /**
     * The offsets of the rays of the self test, x then y then z for each ray, and the buffer that uploads them.
     */
    private static final float[] selfTestOffsets = new float[SELF_TEST_RAY_COUNT * 3];
    private static final FloatBuffer selfTestOffsetBuffer = BufferUtils.createFloatBuffer(SELF_TEST_RAY_COUNT * 3);
    private static final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(16);
    /**
     * The color attachment that each of the eight outputs of the integrate holds, and the buffer that lists the draw
     * buffers of one of its framebuffers.
     */
    private static final int[] froxelOutputLocations = new int[FogFroxelGrid.TILES_ACROSS];
    private static final IntBuffer excessDrawBuffers = BufferUtils.createIntBuffer(FogFroxelGrid.TILES_ACROSS);
    /**
     * The walk that the self test compares with the shader, which keeps the references of the last frame.
     */
    private static final SnowFogWalk selfTestWalk = new SnowFogWalk();
    /**
     * Whether the settings of the draw were read once, and the froxel divisor, the slice count, the range and the debug
     * mode of the frame before, which start a new measurement of the passes when they change.
     */
    private static boolean passTimerSettingsKnown;
    private static int passTimerFroxelDivisor;
    private static int passTimerSliceCount;
    private static double passTimerRangeDistance;
    private static int passTimerDebugMode;

    @Nullable
    private static ClientLevel keptFrameLevel;
    private static int shareTableTexture;
    private static int structureTexture;
    private static boolean structureTextureBuilt;
    private static int selfTestTexture;
    private static int selfTestFramebuffer;
    /**
     * The atlas of the pieces of the self test and its framebuffer, the array of its excesses and the two framebuffers
     * that write its layers, and the framebuffer that reads the layers back, which the three stages of the self test
     * draw to.
     */
    private static int selfTestAtlasTexture;
    private static int selfTestAtlasFramebuffer;
    private static int selfTestExcessTexture;
    private static int selfTestExcessLowerFramebuffer;
    private static int selfTestExcessUpperFramebuffer;
    private static int selfTestReadFramebuffer;
    /**
     * Whether making a target of the froxel stages of the self test failed, after which the self test does not try
     * again.
     */
    private static boolean selfTestTargetFailed;
    /**
     * The structures of the fog of this frame, whose texture is set once the bytes below are built.
     */
    private static final FogStructure structure = new FogStructure();
    /**
     * The structure texture's bytes, which are built once, and the buffer that uploads them.
     */
    private static final byte[] structureTexels =
            new byte[FogStructure.SIZE * FogStructure.SIZE * FogStructure.SIZE * FogStructure.CHANNELS];
    private static final ByteBuffer structureBuffer = BufferUtils.createByteBuffer(structureTexels.length);
    /**
     * The share volume of the frame, which holds the share of every cell of the region cube, its framebuffer, and
     * whether making it failed, after which the walk takes the direct share of each cell for the rest of the session.
     */
    private static int shareVolumeTexture;
    private static int shareVolumeFramebuffer;
    private static boolean shareVolumeFailed;
    /**
     * The atlas of the pieces of the froxel grid, its framebuffer, and the width and height it was made or last sized
     * at.
     */
    private static int pieceAtlasTexture;
    private static int pieceAtlasFramebuffer;
    private static int pieceAtlasWidth;
    private static int pieceAtlasHeight;
    /**
     * The array of the excesses of the froxel grid, the two framebuffers that write its layers, and the width and height
     * it was made or last sized at.
     */
    private static int excessTexture;
    private static int excessLowerFramebuffer;
    private static int excessUpperFramebuffer;
    private static int excessWidth;
    private static int excessHeight;
    /**
     * Whether making a target of the froxel fog failed, after which the fog is not drawn for the rest of the session.
     */
    private static boolean froxelTargetFailed;
    /**
     * When the wind last advanced.
     */
    private static long lastFrameNanos;
    /**
     * The froxel grid of this frame: the pixels per side of a column, the depth slices of a ray, the range in blocks of
     * its last boundary, and its columns and rows.
     */
    private static int frameFroxelDivisor;
    private static int frameSliceCount;
    private static int frameRowCount;
    private static int frameColumnCount;
    private static double frameRangeDistance;
    /**
     * The decay length of the last share table build, so that the table is built again only when the weather changes it.
     */
    private static double shareTableDecayLength = Double.NaN;
    /**
     * The eye of the last drawn frame, as the block it is in and its place inside that block, which no world position
     * passes through a float in.
     */
    private static int eyeBlockX;
    private static int eyeBlockY;
    private static int eyeBlockZ;
    private static double eyeFractionX;
    private static double eyeFractionY;
    private static double eyeFractionZ;
    private static double frameHeightScale;
    /**
     * The height of the eye in blocks of the last drawn frame, from which the cloud fit takes the fog to the cloud
     * layer.
     */
    private static double frameEyeY;
    private static double frameRadius;
    private static double frameHalfWidth;
    private static double frameFieldFade;
    /**
     * When the shown box was filled, and whether it held a cell in the frame before, for the fade of the field.
     */
    private static long shownSinceNanos;
    private static boolean shownBefore;
    /**
     * What the debug view shows and which parts of the walk run, which the client command sets.
     */
    private static int debugMode;
    private static int partMask = 3;
    /**
     * A framebuffer that the fog draws through, holding the main target's color only while it draws. Without the main
     * target's depth attached, the fragment stage can read that depth.
     */
    private static int colorFramebuffer;
    private static double eyeExtinction;
    private static boolean failed;
    private static boolean drawnThisFrame;
    /**
     * The fog start, end and shape that were in force before the fitted fog replaced them for the rest of the frame,
     * and whether the fit is in force.
     */
    private static float savedFogStart;
    private static float savedFogEnd;
    private static FogShape savedFogShape;
    private static boolean fogFitted;
    /**
     * The fog end that the last frame fitted, and whether it fitted one, for the client command.
     */
    private static float lastFrameFogEnd;
    private static boolean fittedLastFrame;
    /**
     * The alpha of the fog color before the cloud fit, and whether the cloud fit is in force.
     */
    private static float savedCloudFogAlpha;
    private static boolean cloudFogFitted;
    /**
     * The opaque distance, the fog end, the far plane it was found for, the upper height the search took, and whether
     * vanilla's fog takes the end, for the client command.
     */
    private static double boundDistance = Double.POSITIVE_INFINITY;
    private static double boundEnd = Double.POSITIVE_INFINITY;
    private static float boundFarPlane;
    private static int boundUpperHeight = Integer.MAX_VALUE;
    private static double boundClearAirDensity = 1.0;
    private static double boundClearRange;
    private static boolean boundApplied;
    /**
     * When the eye began to stand in open air, or 0 while it does not, which the bound of vanilla's fog waits on.
     */
    private static long openAirSinceNanos;
    /**
     * The fog end of the frame before, which FogCullingDistance keeps while the distance stays close to it.
     */
    private static double previousEnd = Double.POSITIVE_INFINITY;
    /**
     * The eight inputs of the last culling distance search and its result, which is searched again only when one of them
     * differs, since the search costs about 1900 evaluations of the height fog.
     */
    private static boolean hasCachedDistance;
    private static double cachedEyeExtinction;
    private static double cachedEyeY;
    private static double cachedHeightScale;
    private static double cachedMinimumHeight;
    private static double cachedMaximumHeight;
    private static double cachedFarPlane;
    private static double cachedClearAirDensity;
    private static double cachedClearRange;
    private static double cachedDistance;
    /**
     * The bytes the fog's handlers allocated since the last weather stage ended.
     */
    private static long frameAllocatedBytes;
    /**
     * The bytes the fog's handlers allocated over the last whole frame, from its fog events to its weather stage, for
     * the client command.
     */
    private static long lastFrameAllocatedBytes;

    private WeatherFogRenderer() {
    }

    /**
     * The weather that the fog is drawn from, which the client updates from its own rain and thunder levels each frame.
     * This is the one place where a provider of the weather synced from the server replaces the mock.
     */
    public static WeatherConditions weather() {
        return mockWeather;
    }

    private static void updateWeather(ClientLevel level, float partialTick) {
        float rainLevel = level.getRainLevel(partialTick);
        // Level.getThunderLevel multiplies the thunder level by the rain level, so this divides it back out:
        float thunderLevel = rainLevel > 0.0F ? level.getThunderLevel(partialTick) / rainLevel : 0.0F;
        mockWeather.update(rainLevel, thunderLevel,
                SkySetting.FOG_SNOW_VISIBILITY.get(), SkySetting.FOG_BLIZZARD_VISIBILITY.get(),
                SkySetting.FOG_HEIGHT_SCALE.get(), SkySetting.FOG_SNOW_DECAY_LENGTH.get(),
                SkySetting.FOG_BLIZZARD_DECAY_LENGTH.get(), ClientClimateData.getWind(),
                SkySetting.FOG_WIND_SCALE.get(), level.getDayTime() + partialTick,
                SkySetting.FOG_SNOW_STRUCTURE_SHARE.get(), SkySetting.FOG_BLIZZARD_STRUCTURE_SHARE.get());
    }

    /**
     * Whether the new fog owns the snow fog in the level, which needs no earlier error, the fog setting on, the
     * calculated sky allowed there, and graphics below Fabulous. Fabulous composites the translucent layers and the
     * weather after the weather stage, which the new fog does not support yet, so the old fog of FogModification stays
     * as it is there.
     */
    public static boolean isActive(ClientLevel level) {
        return !failed && SkySetting.FOG_ENABLED.isOn() && CalculatedSkyRenderer.isAllowed(level)
                && !Minecraft.useShaderTransparency();
    }

    /**
     * Draws the fog over the frame after the translucent terrain and the tripwire layer and before the particles, the
     * clouds and the weather, in frames where the new fog owns the snow fog and the calculated sky was drawn, and fits
     * vanilla's fog to the fog at the eye for everything drawn after the pass until the end of the weather stage.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            return;
        }
        drawnThisFrame = false;
        fittedLastFrame = false;
        // The weather stage always ends after this handler, so a fit still in force comes from a frame that skipped
        // its end, and vanilla has set the fog again since. The same holds for a cloud fit whose draw threw.
        fogFitted = false;
        cloudFogFitted = false;
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || !isActive(level) || !CalculatedSkyRenderer.isDrawnThisFrame()) {
                return;
            }
            render(level, event);
            fitFogAtEye();
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        } finally {
            frameAllocatedBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
        }
    }

    /**
     * Restores the fog start, end and shape that the fitted fog replaced, and rolls the allocation counter, at the end
     * of the weather stage, after every handler of that stage, so that the pillars, the glints and the spatial weather
     * are still drawn with the fitted fog.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWeatherStageEnd(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        try {
            restoreFittedFog();
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        }
        lastFrameAllocatedBytes = frameAllocatedBytes;
        frameAllocatedBytes = 0L;
    }

    /**
     * Turns the fog off after an error, and says how to bring it back, so that the old fog of FogModification draws
     * again.
     */
    private static void stopAfterError(RuntimeException exception) {
        failed = true;
        LOGGER.error("The weather fog is not drawn after an error. It is drawn again once the calculated sky is "
                + "turned on again.", exception);
    }

    /**
     * Fits vanilla's fog to the fog at the eye for the particles, the clouds and the weather, which draw after the pass
     * until the end of the weather stage. The fog at the eye hides the scene at the visibility it gives, so the fitted
     * fog starts at the eye and ends there, as a sphere. The fog color is already the frame's, which applyFogColor set.
     * The values it replaces are restored by restoreFittedFog at the end of the weather stage.
     */
    private static void fitFogAtEye() {
        if (!drawnThisFrame || !(eyeExtinction > 0.0)) {
            return;
        }
        savedFogStart = RenderSystem.getShaderFogStart();
        savedFogEnd = RenderSystem.getShaderFogEnd();
        savedFogShape = RenderSystem.getShaderFogShape();
        float fittedEnd = (float) (HeightFog.VISIBILITY_OPTICAL_DEPTH / eyeExtinction);
        RenderSystem.setShaderFogStart(0.0F);
        RenderSystem.setShaderFogEnd(fittedEnd);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
        lastFrameFogEnd = fittedEnd;
        fittedLastFrame = true;
        fogFitted = true;
    }

    /**
     * Puts the fog start, end and shape back that the fitted fog replaced. The flag is cleared even when a call fails,
     * so that the values are not restored a second time.
     */
    private static void restoreFittedFog() {
        if (!fogFitted) {
            return;
        }
        try {
            RenderSystem.setShaderFogStart(savedFogStart);
            RenderSystem.setShaderFogEnd(savedFogEnd);
            RenderSystem.setShaderFogShape(savedFogShape);
        } finally {
            fogFitted = false;
        }
    }

    /**
     * Pushes vanilla's terrain fog out to the distance where the new fog already hides everything, so that the terrain
     * beyond it is culled. It bounds the fog only where the new fog draws and the eye stands open under the sky, since
     * the new fog leaves sheltered points clear and a fog end at that distance would fog them. The sky fog of the frame
     * is a different fog and leaves the bound as it is.
     */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            applyFogBound(event);
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        } finally {
            frameAllocatedBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
        }
    }

    private static void applyFogBound(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        Camera camera = event.getCamera();
        if (level == null || !isActive(level) || camera.getFluidInCamera() != FogType.NONE) {
            openAirSinceNanos = 0L;
            clearFogBound();
            return;
        }
        if (!isEyeInOpenAir(camera.getBlockPosition())) {
            openAirSinceNanos = 0L;
            clearFogBound();
            return;
        }
        long now = System.nanoTime();
        if (openAirSinceNanos == 0L) {
            openAirSinceNanos = now;
        }
        // The bound comes on only after the seconds of open air, and goes off as soon as the eye leaves it, so that
        // stepping through a door does not move vanilla's fog end for a frame.
        if ((now - openAirSinceNanos) * 1.0e-9 < OPEN_AIR_SECONDS) {
            clearFogBound();
            return;
        }
        BlockPos cameraBlock = camera.getBlockPosition();
        if (!level.getBiome(cameraBlock).value().coldEnoughToSnow(cameraBlock)) {
            clearFogBound();
            return;
        }
        updateWeather(level, (float) event.getPartialTick());
        WeatherConditions conditions = weather();
        if (!(conditions.referenceExtinction() > 0.0)) {
            clearFogBound();
            return;
        }
        double eyeHeight = camera.getPosition().y;
        double heightScale = conditions.heightScale();
        double farPlane = event.getFarPlaneDistance();
        double extinctionAtEye = HeightFog.extinctionAt(conditions.referenceExtinction(), level.getSeaLevel(),
                heightScale, eyeHeight);
        int highestSurface = ClientSnowField.highestSurface();
        // Without a field of this level there is no known surface, so the bound stays off:
        if (highestSurface == Integer.MAX_VALUE) {
            clearFogBound();
            return;
        }
        boundUpperHeight = Math.min(highestSurface, level.getMaxBuildHeight());
        updateStructureDensity();
        boundClearAirDensity = structure.isOn() ? structure.clearAirDensity() : 1.0;
        boundClearRange = structure.isOn() ? structure.range() : 0.0;
        boundDistance = cullingDistance(extinctionAtEye, eyeHeight, heightScale, level.getMinBuildHeight(),
                boundUpperHeight, farPlane, boundClearAirDensity, boundClearRange);
        boundFarPlane = (float) farPlane;
        boundEnd = FogCullingDistance.fogEnd(boundDistance, previousEnd);
        previousEnd = boundEnd;
        if (boundEnd < farPlane) {
            event.setFarPlaneDistance((float) boundEnd);
            event.setNearPlaneDistance((float) (boundEnd - 2.0));
            event.setFogShape(FogShape.CYLINDER);
            event.setCanceled(true);
            boundApplied = true;
        } else {
            boundApplied = false;
        }
    }

    /**
     * Whether the eye's block stands in open air: the snow field of the level holds the block in its solved box, and
     * the distance byte there is 0, which the solver gives only to cells that open sky reaches without an obstacle.
     */
    private static boolean isEyeInOpenAir(BlockPos eyeBlock) {
        SnowField field = ClientSnowField.field();
        if (field == null || !field.isInSolvedBox(eyeBlock.getX(), eyeBlock.getY(), eyeBlock.getZ())) {
            return false;
        }
        return field.distanceAt(eyeBlock.getX(), eyeBlock.getY(), eyeBlock.getZ()) == 0;
    }

    /**
     * Lets vanilla's fog alone and forgets the held fog end, so that the bound starts again from a distance rounded up
     * to FogCullingDistance.END_STEP when it comes back rather than an end held from an earlier frame.
     */
    private static void clearFogBound() {
        boundApplied = false;
        previousEnd = Double.POSITIVE_INFINITY;
    }

    /**
     * The culling distance of the fog bound, which is searched again only when one of its inputs differs from the
     * inputs of the last search. The inputs are compared exactly, so the search runs again as soon as one of them
     * changes at all.
     */
    private static double cullingDistance(double extinctionAtEye, double eyeHeight, double heightScale,
            double minimumHeight, double maximumHeight, double farPlane, double clearAirDensity,
            double clearRange) {
        if (hasCachedDistance && extinctionAtEye == cachedEyeExtinction && eyeHeight == cachedEyeY
                && heightScale == cachedHeightScale && minimumHeight == cachedMinimumHeight
                && maximumHeight == cachedMaximumHeight && farPlane == cachedFarPlane
                && clearAirDensity == cachedClearAirDensity && clearRange == cachedClearRange) {
            return cachedDistance;
        }
        double distance = FogCullingDistance.opaqueDistance(extinctionAtEye, eyeHeight, heightScale, minimumHeight,
                maximumHeight, farPlane, clearAirDensity, clearRange);
        hasCachedDistance = true;
        cachedEyeExtinction = extinctionAtEye;
        cachedEyeY = eyeHeight;
        cachedHeightScale = heightScale;
        cachedMinimumHeight = minimumHeight;
        cachedMaximumHeight = maximumHeight;
        cachedFarPlane = farPlane;
        cachedClearAirDensity = clearAirDensity;
        cachedClearRange = clearRange;
        cachedDistance = distance;
        return distance;
    }

    /**
     * Sets vanilla's fog color to the snow fog color while it snows in a cold biome, which is the color of a white face
     * at full sky light. The sky shader's mix of its own sky toward this color, its horizon fade, the clouds and the
     * culled terrain then all take the color that the fog, the terrain and the sky meet at. The color is set outright
     * rather than blended by the snowfall intensity, whose extinction already ramps, so that the sky and the fog never
     * disagree during a ramp.
     */
    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            applyFogColor(event);
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        } finally {
            frameAllocatedBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
        }
    }

    private static void applyFogColor(ViewportEvent.ComputeFogColor event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !isActive(level) || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        double snowfallIntensity = weather().snowfallIntensity();
        if (!(snowfallIntensity > 0.0)) {
            return;
        }
        BlockPos cameraBlock = event.getCamera().getBlockPosition();
        if (!level.getBiome(cameraBlock).value().coldEnoughToSnow(cameraBlock)) {
            return;
        }
        updateSnowColor(level, (float) event.getPartialTick(), false);
        event.setRed(snowColor[0]);
        event.setGreen(snowColor[1]);
        event.setBlue(snowColor[2]);
    }

    private static void render(ClientLevel level, RenderLevelStageEvent event) {
        resetPassTimerIfSettingsChanged();
        float partialTick = event.getPartialTick();
        updateWeather(level, partialTick);
        advanceWind();
        WeatherConditions conditions = weather();
        if (!(conditions.referenceExtinction() > 0.0)) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        BlockPos cameraBlock = camera.getBlockPosition();
        if (!level.getBiome(cameraBlock).value().coldEnoughToSnow(cameraBlock)) {
            return;
        }
        viewProjection.set(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        findEyeOffset();
        viewProjection.translate(eyeOffset);
        inverseViewProjection.set(viewProjection).invert();
        Vec3 cameraPosition = camera.getPosition();
        double eyeX = cameraPosition.x + eyeOffset.x;
        double eyeY = cameraPosition.y + eyeOffset.y;
        double eyeZ = cameraPosition.z + eyeOffset.z;
        eyeExtinction = HeightFog.extinctionAt(conditions.referenceExtinction(), level.getSeaLevel(),
                conditions.heightScale(), eyeY);
        // The fog draws with the calculated sky, which needs its state built for the frame, as isDrawing tells:
        if (!CalculatedSkyRenderer.isDrawing(level)) {
            return;
        }
        // The first frames after a join have no field of this level yet, so they are drawn without the fog, which errs
        // toward no fog rather than toward fog in a room.
        if (!ClientSnowField.prepareTextures()) {
            return;
        }
        updateSnowColor(level, partialTick, true);
        updateEye(eyeX, eyeY, eyeZ);
        updateStructure(eyeX, eyeY, eyeZ);
        updateShareTable();
        updateFieldFade();
        updateRadius();
        frameHeightScale = conditions.heightScale();
        frameEyeY = eyeY;
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        frameFroxelDivisor = FogFroxelGrid.divisorOf(SkySetting.FOG_FROXEL_DIVISOR.get());
        frameSliceCount = FogFroxelGrid.sliceCountOf(SkySetting.FOG_FROXEL_SLICES.get());
        frameRangeDistance = FogFroxelGrid.rangeOf(SkySetting.FOG_FROXEL_RANGE.get());
        frameColumnCount = FogFroxelGrid.columnCount(mainTarget.width, frameFroxelDivisor);
        frameRowCount = FogFroxelGrid.columnCount(mainTarget.height, frameFroxelDivisor);
        timer.begin();
        try {
            draw(mainTarget);
        } finally {
            timer.end();
        }
        drawnThisFrame = !froxelTargetFailed;
        keptFrameLevel = level;
    }

    /**
     * Clears the measured pass times when a setting that changes what the passes cost changed since the frame before,
     * since the settings have no change hook. It runs before the early returns of the frame, so that the next frame that
     * draws measures the new configuration alone. Allocates nothing.
     */
    private static void resetPassTimerIfSettingsChanged() {
        int froxelDivisor = FogFroxelGrid.divisorOf(SkySetting.FOG_FROXEL_DIVISOR.get());
        int sliceCount = FogFroxelGrid.sliceCountOf(SkySetting.FOG_FROXEL_SLICES.get());
        double rangeDistance = FogFroxelGrid.rangeOf(SkySetting.FOG_FROXEL_RANGE.get());
        if (passTimerSettingsKnown && (froxelDivisor != passTimerFroxelDivisor || sliceCount != passTimerSliceCount
                || Double.compare(rangeDistance, passTimerRangeDistance) != 0 || debugMode != passTimerDebugMode)) {
            passTimer.reset();
        }
        passTimerSettingsKnown = true;
        passTimerFroxelDivisor = froxelDivisor;
        passTimerSliceCount = sliceCount;
        passTimerRangeDistance = rangeDistance;
        passTimerDebugMode = debugMode;
    }

    /**
     * Advances the wind by this frame's seconds, which the structures of the fog follow. It runs before the early
     * returns of the frame, so that the displacement keeps moving while the fog is not drawn, and a paused game does not
     * move it.
     */
    private static void advanceWind() {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) * 1.0e-9;
        lastFrameNanos = now;
        if (Minecraft.getInstance().isPaused()) {
            seconds = 0.0;
        }
        wind.advance(seconds, weather().windSpeed(), weather().windDirection());
    }

    /**
     * Keeps the structures of this frame: the wind frame of the eye from the settings of their size and stretch, which
     * are clamped to their ranges, and their density from the weather's share and the settings of their coverage, their
     * erosion and their range. The bytes of their texture are built first, since the means of the density are computed
     * from them.
     */
    private static void updateStructure(double eyeX, double eyeY, double eyeZ) {
        buildStructureTexels();
        structure.follow(eyeX, eyeY, eyeZ, wind.displacementX(), wind.displacementZ(), wind.direction(),
                settingValue(SkySetting.FOG_STRUCTURE_SIZE), settingValue(SkySetting.FOG_STRUCTURE_STRETCH));
        updateStructureDensity();
    }

    /**
     * Sets the density of the structures from this frame's weather and the settings, which the walk and the bound of
     * vanilla's fog both read. The bytes of their texture are built first, since the means of the density are computed
     * from them.
     */
    private static void updateStructureDensity() {
        buildStructureTexels();
        structure.setDensity(weather().structureShare(), settingValue(SkySetting.FOG_STRUCTURE_COVERAGE),
                settingValue(SkySetting.FOG_STRUCTURE_EROSION), settingValue(SkySetting.FOG_STRUCTURE_RANGE));
    }

    /**
     * Builds the bytes of the structure texture of FogStructure.SEED once and gives them to the structures. It runs
     * before every setDensity, so that the means of the density are never computed from an empty texture.
     */
    private static void buildStructureTexels() {
        if (structureTextureBuilt) {
            return;
        }
        FogStructure.build(FogStructure.SEED, structureTexels);
        structure.setTexture(structureTexels);
        structureTextureBuilt = true;
    }

    /**
     * A setting's value, clamped to the range its config allows.
     */
    private static double settingValue(SkySetting setting) {
        double value = setting.get();
        if (!(value >= setting.minimum())) {
            return setting.minimum();
        }
        return Math.min(setting.maximum(), value);
    }

    /**
     * Keeps the eye of this frame as the block it stands in and its place inside that block, so that the uniforms and
     * the walk of the self test work on cell offsets and no world position passes through a float.
     */
    private static void updateEye(double eyeX, double eyeY, double eyeZ) {
        eyeBlockX = Mth.floor(eyeX);
        eyeBlockY = Mth.floor(eyeY);
        eyeBlockZ = Mth.floor(eyeZ);
        eyeFractionX = eyeX - eyeBlockX;
        eyeFractionY = eyeY - eyeBlockY;
        eyeFractionZ = eyeZ - eyeBlockZ;
    }

    /**
     * Builds the share table again when the decay length of the weather changed, and uploads it into the share table
     * texture, making it first if needed. The texture bound before stays bound.
     */
    private static void updateShareTable() {
        double decayLength = weather().decayLength();
        if (shareTableTexture != 0 && Double.compare(decayLength, shareTableDecayLength) == 0) {
            return;
        }
        SnowFogWalk.buildTable(decayLength, shareTable);
        shareTableBuffer.clear();
        shareTableBuffer.put(shareTable);
        shareTableBuffer.flip();
        shareTableDecayLength = decayLength;
        boolean allocate = shareTableTexture == 0;
        int target = allocate ? GL11.glGenTextures() : shareTableTexture;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            if (allocate) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, SnowFogWalk.TABLE_SIZE, 1, 0, GL11.GL_RED,
                        GL11.GL_FLOAT, shareTableBuffer);
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SnowFogWalk.TABLE_SIZE, 1, GL11.GL_RED,
                        GL11.GL_FLOAT, shareTableBuffer);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        shareTableTexture = target;
    }

    /**
     * Moves the field's fade toward 1 over FILL_FADE_SECONDS from the frame the shown box was filled in, and holds it at
     * 0 while there is no shown box. A fill empties the shown box, so the completed fill gains the field's share once
     * rather than along a moving edge, while edits and moves of the field take effect at once.
     */
    private static void updateFieldFade() {
        long now = System.nanoTime();
        boolean hasShownBox = SnowFieldTextures.hasShownBox();
        if (hasShownBox && !shownBefore) {
            shownSinceNanos = now;
        }
        if (hasShownBox) {
            double fade = (now - shownSinceNanos) / (FILL_FADE_SECONDS * 1.0e9);
            if (!(fade > 0.0)) {
                frameFieldFade = 0.0;
            } else if (fade > 1.0) {
                frameFieldFade = 1.0;
            } else {
                frameFieldFade = fade;
            }
        } else {
            frameFieldFade = 0.0;
        }
        shownBefore = hasShownBox;
    }

    /**
     * Reads the radius of the region around the eye within which the fog follows the field, and its half width.
     */
    private static void updateRadius() {
        double radius = SkySetting.FOG_FIELD_RADIUS.get();
        if (!(radius >= SnowFogWalk.MINIMUM_RADIUS)) {
            radius = SnowFogWalk.MINIMUM_RADIUS;
        } else if (radius > SnowFogWalk.MAXIMUM_RADIUS) {
            radius = SnowFogWalk.MAXIMUM_RADIUS;
        }
        frameRadius = radius;
        frameHalfWidth = radius - SnowFogWalk.REGION_MARGIN;
    }

    /**
     * Reads this frame's snow fog color into snowColor, which is the color a white top face at full sky light shows:
     * the mean of the lightmap pixels of block light 0 at sky light 14 and 15, as display sRGB from 0 to 1, through
     * FogLight.whiteFaceColor. Vanilla refreshes the lightmap once per tick before the level renders, so the pass and
     * applyFogColor both read the color of this tick. A level without sky light, a lightmap that gives no image, and a
     * lightmap that throws, as a closed image does, fall back to the blend of the day and night fog colors by the sun
     * angle. Only the pass counts the fallback for the client command, so the count is in frames. Allocates nothing.
     */
    private static void updateSnowColor(ClientLevel level, float partialTick, boolean countsFallback) {
        snowColorFromLightmap = level.dimensionType().hasSkyLight() && readLightmapSnowColor();
        if (snowColorFromLightmap) {
            return;
        }
        if (countsFallback) {
            snowColorFallbacks++;
        }
        updateConfigSnowColor(level, partialTick);
    }

    /**
     * Reads the snow fog color from the lightmap into snowColor, and returns whether the lightmap gave it. A lightmap
     * that throws, as a closed image does, is logged once and not read again for the rest of the session, so a broken
     * lightmap does not throw every frame. Allocates nothing while the lightmap can be read.
     */
    private static boolean readLightmapSnowColor() {
        if (lightmapUnreadable) {
            return false;
        }
        try {
            LightTextureAccessor_WeatherFog accessor =
                    (LightTextureAccessor_WeatherFog) Minecraft.getInstance().gameRenderer.lightTexture();
            NativeImage lightPixels = accessor.frostedheart$getLightPixels();
            if (lightPixels == null) {
                return false;
            }
            FogLight.whiteFaceColor(lightPixels.getPixelRGBA(0, LIGHTMAP_SKY_FOURTEEN),
                    lightPixels.getPixelRGBA(0, LIGHTMAP_SKY_FIFTEEN), snowColor);
            return true;
        } catch (RuntimeException exception) {
            lightmapUnreadable = true;
            LOGGER.warn("The lightmap cannot be read, so the snow fog takes the config colors for the rest of the "
                    + "session.", exception);
            return false;
        }
    }

    /**
     * Computes the snow fog color of this frame into snowColor as the blend of the day and night fog colors by the sun
     * angle that FogModification.renderFogColors gives, as display sRGB from 0 to 1.
     */
    private static void updateConfigSnowColor(ClientLevel level, float partialTick) {
        float height = Mth.cos(level.getSunAngle(partialTick));
        float dayShare = Mth.clamp((height + 0.4F) / 0.8F, 0.0F, 1.0F);
        int dayColor = FHConfig.CLIENT.fogColorDay.get();
        int nightColor = FHConfig.CLIENT.fogColorNight.get();
        snowColor[0] = ((dayColor >> 16 & 255) * dayShare + (nightColor >> 16 & 255) * (1.0F - dayShare)) / 255.0F;
        snowColor[1] = ((dayColor >> 8 & 255) * dayShare + (nightColor >> 8 & 255) * (1.0F - dayShare)) / 255.0F;
        snowColor[2] = ((dayColor & 255) * dayShare + (nightColor & 255) * (1.0F - dayShare)) / 255.0F;
    }

    /**
     * Sets the eye offset to the position of the eye relative to the camera, which view bobbing moves in the projection
     * matrix. The eye is the point that the view projection maps to a clip position with only a depth.
     */
    private static void findEyeOffset() {
        inverseViewProjection.set(viewProjection).invert().getColumn(2, eyeColumn);
        float x = eyeColumn.x / eyeColumn.w;
        float y = eyeColumn.y / eyeColumn.w;
        float z = eyeColumn.z / eyeColumn.w;
        if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)) {
            eyeOffset.set(x, y, z);
        } else {
            eyeOffset.zero();
        }
    }

    /**
     * Draws the fog over the frame in five stages between the timer's boundaries: the setup that binds the depth and the
     * fields, the share of every cell of the region cube into its volume, the piece of each froxel of the column grid
     * into its atlas, the excess of the walked depth over the analytic fog at every slice boundary into its array, and
     * the fog over the frame, which reads the excesses of the columns around each pixel. The depth of the main target is
     * read through a color only framebuffer, so that its depth texture is not attached to what it draws to. Every piece
     * of state the pass changes is set back after it, and the framebuffer holds no texture of the main target once it is
     * released.
     */
    private static void draw(RenderTarget mainTarget) {
        if (froxelTargetFailed) {
            return;
        }
        pass.begin();
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int sourceColor = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationColor = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int colorEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int alphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FIELD_UNIT);
        int previousFieldTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + STRUCTURE_UNIT);
        int previousStructureTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
        int previousExcessTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            passTimer.begin();
            updateStructureTexture();
            RenderSystem.disableCull();
            RenderSystem.disableScissor();
            RenderSystem.disableBlend();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_UNIT);
            RenderSystem.bindTexture(mainTarget.getDepthTextureId());
            bindFieldTextures();
            passTimer.mark(1);
            boolean shareVolumeBound = prepareShareVolume();
            if (shareVolumeBound) {
                drawShareVolume(mainTarget);
                bindShareVolume();
            }
            passTimer.mark(2);
            if (!drawInjectPass(mainTarget, shareVolumeBound)) {
                return;
            }
            passTimer.mark(3);
            if (!drawIntegratePass(mainTarget)) {
                return;
            }
            passTimer.mark(4);
            bindColorFramebuffer(mainTarget);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, excessTexture);
            compositePass.use();
            compositePass.setFrameUniforms(mainTarget);
            compositePass.setShareVolumeInUse(shareVolumeBound);
            SkyGlPass.drawFullScreenQuad();
            passTimer.end();
        } finally {
            GL20.glBlendEquationSeparate(colorEquation, alphaEquation);
            RenderSystem.blendFuncSeparate(sourceColor, destinationColor, sourceAlpha, destinationAlpha);
            if (blending) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (culling) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            if (scissor) {
                GlStateManager._enableScissorTest();
            } else {
                RenderSystem.disableScissor();
            }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FIELD_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousFieldTexture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + STRUCTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousStructureTexture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousExcessTexture);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            releaseColorFramebuffer(previousFramebuffer);
            pass.end();
        }
    }

    /**
     * Draws the piece of every froxel of the frame's grid into the inject atlas, making the atlas first if needed, and
     * leaves the framebuffer and the viewport that were bound before bound again.
     * @param shareVolumeBound whether the walk of the frame may read the share volume
     * @return whether the pieces were drawn, which is false after a failure that turns the fog off
     */
    private static boolean drawInjectPass(RenderTarget mainTarget, boolean shareVolumeBound) {
        if (!preparePieceAtlas()) {
            return false;
        }
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, pieceAtlasFramebuffer);
            RenderSystem.viewport(0, 0, FogFroxelGrid.atlasWidth(frameColumnCount),
                    FogFroxelGrid.atlasDrawHeight(frameRowCount, frameSliceCount));
            injectPass.use();
            injectPass.setFrameUniforms(mainTarget);
            injectPass.setShareVolumeInUse(shareVolumeBound);
            SkyGlPass.drawFullScreenQuad();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        }
        return true;
    }

    /**
     * Draws the excess of every boundary of the frame's slices into the excess array, making the array and its
     * framebuffers first if needed, and leaves the framebuffer and the viewport that were bound before bound again. A
     * slice count above the boundaries that eight outputs hold takes a second draw into the upper framebuffer, which
     * sums the pieces of the first draw again.
     * @return whether the excesses were drawn, which is false after a failure that turns the fog off
     */
    private static boolean drawIntegratePass(RenderTarget mainTarget) {
        if (!prepareExcesses()) {
            return false;
        }
        int boundariesPerDraw = FogFroxelGrid.TILES_ACROSS * FogFroxelGrid.SLICES_PER_LAYER;
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + ATLAS_UNIT);
            RenderSystem.bindTexture(pieceAtlasTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, excessLowerFramebuffer);
            // The draw buffers follow the output locations, which the link of the integrate assigns:
            integratePass.use();
            if (froxelTargetFailed) {
                return false;
            }
            integratePass.setFrameUniforms(mainTarget);
            integratePass.setFirstBoundary(1);
            setExcessDrawBuffers(0, frameSliceCount);
            RenderSystem.viewport(0, 0, frameColumnCount, frameRowCount);
            SkyGlPass.drawFullScreenQuad();
            if (frameSliceCount > boundariesPerDraw) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, excessUpperFramebuffer);
                integratePass.setFirstBoundary(boundariesPerDraw + 1);
                setExcessDrawBuffers(FogFroxelGrid.TILES_ACROSS, frameSliceCount);
                SkyGlPass.drawFullScreenQuad();
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        }
        return true;
    }

    /**
     * Lists the color attachments of the layers that hold the given slices as the draw buffers of the bound framebuffer,
     * at the location of the output that each slice is written to, and no other, so that the slices past them are not
     * written. Allocates nothing.
     * @param firstLayer the first layer of the bound framebuffer, 0 or FogFroxelGrid.TILES_ACROSS
     * @param sliceCount the depth slices of a ray, from FogFroxelGrid.MINIMUM_SLICES to FogFroxelGrid.MAXIMUM_SLICES
     */
    private static void setExcessDrawBuffers(int firstLayer, int sliceCount) {
        excessDrawBuffers.clear();
        for (int bufferIndex = 0; bufferIndex < FogFroxelGrid.TILES_ACROSS; bufferIndex++) {
            excessDrawBuffers.put(GL11.GL_NONE);
        }
        int lastLayer = Math.min(firstLayer + FogFroxelGrid.TILES_ACROSS, FogFroxelGrid.layerCount(sliceCount));
        for (int layer = firstLayer; layer < lastLayer; layer++) {
            excessDrawBuffers.put(froxelOutputLocations[layer - firstLayer],
                    GL30.GL_COLOR_ATTACHMENT0 + (layer - firstLayer));
        }
        excessDrawBuffers.flip();
        GL20.glDrawBuffers(excessDrawBuffers);
    }

    /**
     * Binds the snow field to unit 2 as a 3D texture, the far heights and the share table to units 3 and 4, and the
     * structures of the fog to unit 5. SkyGlPass keeps only the 2D binding of each of its units, so the caller saves and
     * restores the 3D binding of units 2 and 5 itself.
     */
    private static void bindFieldTextures() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FIELD_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, ClientSnowField.fieldTexture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + HEIGHT_UNIT);
        RenderSystem.bindTexture(ClientSnowField.heightTexture());
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SHARE_TABLE_UNIT);
        RenderSystem.bindTexture(shareTableTexture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + STRUCTURE_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, structureTexture);
    }

    /**
     * Makes the share volume of the frame, an R16F 2D texture of FogShareVolume.ATLAS_WIDTH by ATLAS_HEIGHT texels, and
     * its framebuffer, on first use, and keeps them for the session. A volume that cannot be made is deleted again and
     * logged once, and then the walk takes the direct share of each cell for the rest of the session.
     * @return whether the walk of the frame may read the volume
     */
    private static boolean prepareShareVolume() {
        if (shareVolumeFailed) {
            return false;
        }
        if (shareVolumeTexture != 0) {
            return true;
        }
        int targetTexture = GL11.glGenTextures();
        int targetFramebuffer = GL30.glGenFramebuffers();
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16F, FogShareVolume.ATLAS_WIDTH,
                        FogShareVolume.ATLAS_HEIGHT, 0, GL11.GL_RED, GL11.GL_FLOAT, (FloatBuffer) null);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }
            int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFramebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        targetTexture, 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The weather fog share volume framebuffer is incomplete with status "
                            + status);
                }
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(targetTexture);
            GL30.glDeleteFramebuffers(targetFramebuffer);
            shareVolumeFailed = true;
            LOGGER.warn("The weather fog share volume cannot be made, so the fog walks the share of every cell of the "
                    + "region cube itself for the rest of the session.", exception);
            return false;
        }
        shareVolumeTexture = targetTexture;
        shareVolumeFramebuffer = targetFramebuffer;
        return true;
    }

    /**
     * Draws the share of every cell of the region cube into the share volume, one fragment for each texel of its atlas,
     * with the uniforms the walk takes and without reading the volume, and leaves the framebuffer and the viewport that
     * were bound before bound again.
     */
    private static void drawShareVolume(RenderTarget mainTarget) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, shareVolumeFramebuffer);
            RenderSystem.viewport(0, 0, FogShareVolume.ATLAS_WIDTH, FogShareVolume.ATLAS_HEIGHT);
            sharePass.use();
            sharePass.setFrameUniforms(mainTarget);
            SkyGlPass.drawFullScreenQuad();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        }
    }

    /**
     * Binds the share volume to unit 1, which no other pass of the fog draws to. SkyGlPass keeps the 2D binding of each
     * of its units, so the binding that was there before the passes comes back after them.
     */
    private static void bindShareVolume() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SHARE_VOLUME_UNIT);
        RenderSystem.bindTexture(shareVolumeTexture);
    }

    /**
     * Uploads the bytes of the structure texture of FogStructure.SEED into a 3D texture with two channels, making it
     * once on first use. The texture bound before stays bound.
     */
    private static void updateStructureTexture() {
        if (structureTexture != 0) {
            return;
        }
        buildStructureTexels();
        structureBuffer.clear();
        structureBuffer.put(structureTexels);
        structureBuffer.flip();
        int target = GL11.glGenTextures();
        int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, target);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG8, FogStructure.SIZE, FogStructure.SIZE,
                    FogStructure.SIZE, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, structureBuffer);
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
        }
        structureTexture = target;
    }

    /**
     * Makes the atlas of the pieces of the froxel grid, an R32F 2D texture of the grid's columns by its rows of tiles,
     * and the framebuffer that holds it, on first use and again whenever the column counts changed. A slice count change
     * reallocates nothing, since the texture is allocated for the maximum slice count and the inject draw covers only
     * the rows of the frame's slices. A target that cannot be made is deleted again and logged once, after which the fog
     * is not drawn for the rest of the session.
     * @return whether the pieces of the frame may be drawn
     */
    private static boolean preparePieceAtlas() {
        if (froxelTargetFailed) {
            return false;
        }
        int width = FogFroxelGrid.atlasWidth(frameColumnCount);
        int height = FogFroxelGrid.atlasHeight(frameRowCount);
        boolean allocate = pieceAtlasFramebuffer == 0;
        boolean resize = allocate || pieceAtlasWidth != width || pieceAtlasHeight != height;
        if (allocate) {
            pieceAtlasFramebuffer = GL30.glGenFramebuffers();
            pieceAtlasTexture = GL11.glGenTextures();
        }
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, pieceAtlasTexture);
                if (allocate) {
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                }
                if (resize) {
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, width, height, 0, GL11.GL_RED,
                            GL11.GL_FLOAT, (FloatBuffer) null);
                    pieceAtlasWidth = width;
                    pieceAtlasHeight = height;
                }
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }
            if (resize) {
                int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                try {
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, pieceAtlasFramebuffer);
                    GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                            pieceAtlasTexture, 0);
                    int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                    if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                        throw new IllegalStateException("The weather fog inject atlas framebuffer is incomplete with "
                                + "status " + status);
                    }
                } finally {
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
                }
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(pieceAtlasTexture);
            GL30.glDeleteFramebuffers(pieceAtlasFramebuffer);
            pieceAtlasTexture = 0;
            pieceAtlasFramebuffer = 0;
            froxelTargetFailed = true;
            LOGGER.warn("The weather fog inject atlas cannot be made, so the fog is not drawn for the rest of the "
                    + "session.", exception);
            return false;
        }
        return true;
    }

    /**
     * Makes the array of the excesses of the froxel grid, an RGBA16F 2D array texture of the grid's columns by its rows
     * by the maximum layer count, and its two framebuffers, on first use and again whenever the column counts changed.
     * The first framebuffer writes the lower half of the layers and the second the upper half, one layer on each color
     * attachment. A slice count change reallocates nothing, since the array holds every layer that the maximum slice
     * count needs and the draw buffers cover only the layers of the frame's slices. A target that cannot be made is
     * deleted again and logged once, after which the fog is not drawn for the rest of the session.
     * @return whether the excesses of the frame may be drawn
     */
    private static boolean prepareExcesses() {
        if (froxelTargetFailed) {
            return false;
        }
        boolean allocate = excessLowerFramebuffer == 0;
        boolean resize = allocate || excessWidth != frameColumnCount || excessHeight != frameRowCount;
        if (allocate) {
            excessLowerFramebuffer = GL30.glGenFramebuffers();
            excessUpperFramebuffer = GL30.glGenFramebuffers();
            excessTexture = GL11.glGenTextures();
        }
        try {
            int previousTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            try {
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, excessTexture);
                if (allocate) {
                    GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                }
                if (resize) {
                    GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA16F, frameColumnCount, frameRowCount,
                            FogFroxelGrid.MAXIMUM_LAYERS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
                    excessWidth = frameColumnCount;
                    excessHeight = frameRowCount;
                }
            } finally {
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousTexture);
            }
            if (resize) {
                int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                try {
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, excessLowerFramebuffer);
                    for (int layer = 0; layer < FogFroxelGrid.TILES_ACROSS; layer++) {
                        GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + layer,
                                excessTexture, 0, layer);
                    }
                    int lowerStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                    if (lowerStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                        throw new IllegalStateException("The weather fog excess framebuffer of the lower layers is "
                                + "incomplete with status " + lowerStatus);
                    }
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, excessUpperFramebuffer);
                    for (int layer = 0; layer < FogFroxelGrid.TILES_ACROSS; layer++) {
                        GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + layer,
                                excessTexture, 0, layer + FogFroxelGrid.TILES_ACROSS);
                    }
                    int upperStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                    if (upperStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                        throw new IllegalStateException("The weather fog excess framebuffer of the upper layers is "
                                + "incomplete with status " + upperStatus);
                    }
                } finally {
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
                }
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(excessTexture);
            GL30.glDeleteFramebuffers(excessLowerFramebuffer);
            GL30.glDeleteFramebuffers(excessUpperFramebuffer);
            excessTexture = 0;
            excessLowerFramebuffer = 0;
            excessUpperFramebuffer = 0;
            froxelTargetFailed = true;
            LOGGER.warn("The weather fog excess array cannot be made, so the fog is not drawn for the rest of the "
                    + "session.", exception);
            return false;
        }
        return true;
    }

    /**
     * Binds the color framebuffer for drawing with the main target's color attached, making it first if needed.
     */
    private static void bindColorFramebuffer(RenderTarget mainTarget) {
        if (colorFramebuffer == 0) {
            colorFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorFramebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        } else {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorFramebuffer);
        }
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                mainTarget.getColorTextureId(), 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("The weather fog framebuffer is incomplete with status " + status);
        }
    }

    /**
     * Takes the main target's color off the color framebuffer, so it holds no texture that a resize deletes, and binds
     * the framebuffer for drawing again.
     */
    private static void releaseColorFramebuffer(int framebuffer) {
        if (colorFramebuffer != 0) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
    }

    /**
     * Whether the fog was drawn in the current frame, which needs the calculated sky drawn and the fog on.
     */
    public static boolean isDrawnThisFrame() {
        return drawnThisFrame;
    }

    /**
     * The extinction per block at the eye's height of the frame the fog was last drawn in. Only valid while
     * isDrawnThisFrame is true.
     */
    public static double eyeExtinction() {
        return eyeExtinction;
    }

    /**
     * Fits Embeddium's cloud fog to the fog at the eye for the cloud draw that is about to happen, so that the clouds
     * fade out where the fog at the eye already hides the scene, and their alpha falls by the fog from the eye to the
     * cloud layer. The fade of the cloud shader ends at FogStart + 4 * (FogEnd - FogStart), so an end of a quarter of
     * the visibility makes it end at the visibility. Embeddium restores its own fog start and end after the draw, and
     * restoreCloudFog puts the alpha back. It does nothing unless the fog drew this frame, so that the clouds keep
     * Embeddium's own fog then.
     * @param cloudHeight the height of the cloud layer in blocks
     */
    public static void fitCloudFog(double cameraY, float cloudHeight) {
        try {
            if (!drawnThisFrame || !(eyeExtinction > 0.0)) {
                return;
            }
            double visibility = HeightFog.VISIBILITY_OPTICAL_DEPTH / eyeExtinction;
            RenderSystem.setShaderFogStart(0.0F);
            RenderSystem.setShaderFogEnd((float) (visibility / 4.0));
            double verticalOffset = cloudHeight - frameEyeY;
            double cloudTransmittance = HeightFog.transmittance(HeightFog.opticalDepth(eyeExtinction,
                    Math.abs(verticalOffset), verticalOffset, weather().heightScale()));
            float[] fogColor = RenderSystem.getShaderFogColor();
            savedCloudFogAlpha = fogColor[3];
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2],
                    (float) (savedCloudFogAlpha * cloudTransmittance));
            cloudFogFitted = true;
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        }
    }

    /**
     * Puts the alpha of the fog color back that the cloud fit replaced. Embeddium restores the fog start and end of
     * its own fog itself.
     */
    public static void restoreCloudFog() {
        try {
            if (!cloudFogFitted) {
                return;
            }
            float[] fogColor = RenderSystem.getShaderFogColor();
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], savedCloudFogAlpha);
            cloudFogFitted = false;
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        }
    }

    /**
     * Clears the failure, so that the fog is drawn again from the next frame on.
     */
    public static void clearFailure() {
        failed = false;
    }

    /**
     * Sets what the fog draws for the following frames, for the debug subcommand of the client command: the debug view,
     * which part of the walk runs, or both. A mode that runs a part through the analytic fog draws the normal fog, so
     * its draw time gives the cost of the other part when it is compared with the draw time of off.
     * @param mode one of off, field, froxel, noregion, nofar, analytic, transmittance and structure
     * @return a line saying what the fog shows from now on
     */
    public static String setDebugMode(String mode) {
        switch (mode) {
            case "field":
                debugMode = 1;
                partMask = 3;
                return "Fog debug mode field: each pixel shows the snow share of the field a quarter block in front of "
                        + "its surface, and beyond the region the far rule, purple under cover and gray in the open";
            case "froxel":
                debugMode = 2;
                partMask = 3;
                return "Fog debug mode froxel: each pixel shows the piece of its column's center ray that holds its "
                        + "distance, with the near steps in red and the far steps in green as shares of the cap, blue "
                        + "where a part ran out, and the sky shows its column's last piece";
            case "noregion":
                debugMode = 0;
                partMask = 2;
                return "Fog timing mode noregion: the near part of every piece takes the analytic fog, so the "
                        + "difference of its draw time from the draw time of off is the cost of the near cell walk";
            case "nofar":
                debugMode = 0;
                partMask = 1;
                return "Fog timing mode nofar: the far part of every piece takes the analytic fog, so the difference "
                        + "of its draw time from the draw time of off is the cost of the far cell walk";
            case "analytic":
                debugMode = 0;
                partMask = 0;
                return "Fog timing mode analytic: both parts count as open air, which is the fog without the field and "
                        + "without the structures";
            case "transmittance":
                debugMode = 3;
                partMask = 3;
                return "Fog debug mode transmittance: each pixel shows the transmittance of its fog as opaque gray, "
                        + "white where nothing hides";
            case "structure":
                debugMode = 4;
                partMask = 3;
                return "Fog debug mode structure: each pixel shows the ratio of the structured fog to the mean fog "
                        + "along its ray as a color, blue for the clear air, gray for the mean and red for the "
                        + "structures";
            default:
                debugMode = 0;
                partMask = 3;
                return "Fog debug mode off: the fog draws with the field and the far walk";
        }
    }

    /**
     * A line about the draw time of the fog, whether it was drawn in the last frame, the extinction at the eye and the
     * visibility it gives, the fog end it fitted for the rest of that frame, the weather it was drawn from, and the
     * state of the field around the eye, for the client command.
     */
    public static String describe() {
        double visibility = eyeExtinction > 0.0 ? HeightFog.VISIBILITY_OPTICAL_DEPTH / eyeExtinction
                : Double.POSITIVE_INFINITY;
        String drawn = drawnThisFrame ? "drawn" : "not drawn";
        String fittedEnd = fittedLastFrame ? String.format(Locale.ROOT, "%.1f blocks", lastFrameFogEnd) : "not fitted";
        String stopped = failed ? "; stopped after an error, see the log" : "";
        boolean hasShownBox = SnowFieldTextures.hasShownBox();
        int halfWidth = Mth.floor(frameHalfWidth);
        long regionCells = (2L * halfWidth + 1) * (2L * halfWidth + 1) * (2L * halfWidth + 1);
        long insideCells = 0L;
        int lag = 0;
        if (hasShownBox) {
            insideCells = axisCount(eyeBlockX - halfWidth, eyeBlockX + halfWidth, SnowFieldTextures.shownMinX(),
                    SnowFieldTextures.shownMaxX())
                    * axisCount(eyeBlockY - halfWidth, eyeBlockY + halfWidth, SnowFieldTextures.shownMinY(),
                    SnowFieldTextures.shownMaxY())
                    * axisCount(eyeBlockZ - halfWidth, eyeBlockZ + halfWidth, SnowFieldTextures.shownMinZ(),
                    SnowFieldTextures.shownMaxZ());
            int lagHalfWidth = (int) Math.ceil(frameHalfWidth);
            lag = Math.max(axisLag(eyeBlockX, lagHalfWidth, SnowFieldTextures.shownMinX(),
                    SnowFieldTextures.shownMaxX()), Math.max(axisLag(eyeBlockY, lagHalfWidth,
                    SnowFieldTextures.shownMinY(), SnowFieldTextures.shownMaxY()), axisLag(eyeBlockZ, lagHalfWidth,
                    SnowFieldTextures.shownMinZ(), SnowFieldTextures.shownMaxZ())));
        }
        double windDegrees = Math.toDegrees(wind.direction()) % 360.0;
        if (windDegrees < 0.0) {
            windDegrees += 360.0;
        }
        return String.format(Locale.ROOT, "Weather fog %s in the last frame; %s; eye extinction %.5f per block, "
                        + "visibility %.1f blocks, fitted fog end %s; reference extinction %.5f per block, height "
                        + "scale %.1f blocks; %s; %s, decay length %.2f blocks; R %.1f blocks, region half width %.1f "
                        + "blocks, fade %.2f, %s, %d of the %d cells of the region cube inside it, lag %d blocks, %s; "
                        + "froxel divisor %d at %d by %d columns, %d slices out to %.1f blocks; %s; wind %.1f blocks "
                        + "per second toward %.0f degrees, displacement (%.1f, %.1f) blocks%s",
                drawn, timer.describe(), eyeExtinction, visibility, fittedEnd, weather().referenceExtinction(),
                weather().heightScale(), describeSnowColor(), describeEyeShare(), weather().decayLength(), frameRadius,
                frameHalfWidth, frameFieldFade, hasShownBox ? "a shown box" : "no shown box", insideCells, regionCells,
                lag, describeShareVolume(), frameFroxelDivisor, frameColumnCount, frameRowCount, frameSliceCount,
                frameRangeDistance, describeStructures(visibility), wind.speed(),
                windDegrees, wind.displacementX(), wind.displacementZ(), stopped);
    }

    /**
     * Whether the walk of the last frame read the share volume of the region cube or the direct share of each cell, and
     * why the volume is missing, for the client command.
     */
    private static String describeShareVolume() {
        if (shareVolumeFailed) {
            return "no share volume, since it cannot be made here, so the cells walk the field directly";
        }
        if (shareVolumeTexture == 0) {
            return "no share volume yet, so the cells walk the field directly";
        }
        return "the share volume of the region cube";
    }

    /**
     * The snow fog color of the last frame and where it came from, for the client command: the three display sRGB
     * values from 0 to 1, whether the lightmap or the config colors gave them, and the number of frames that fell back.
     */
    private static String describeSnowColor() {
        return String.format(Locale.ROOT, "snow color %.3f %.3f %.3f from the %s, %d fallbacks", snowColor[0],
                snowColor[1], snowColor[2], snowColorFromLightmap ? "lightmap" : "config colors", snowColorFallbacks);
    }

    /**
     * The structures of the fog, for the client command: the share of the fog that sits in them, the visibility of the
     * clear air between them and of their cores in blocks, their settings, their range, their two means and whether they
     * are on. A visibility of a density of 0 is unlimited, which is light that no fog takes away.
     */
    private static String describeStructures(double visibility) {
        boolean on = structure.isOn();
        double clearAirDensity = on ? structure.clearAirDensity() : 1.0;
        return String.format(Locale.ROOT, "structures %s, share %.2f, clear air density %.2f at %s, core density "
                        + "%.2f at %s; coverage %.2f, size %.0f blocks, stretch %.1f, erosion %.2f, range %.0f blocks, "
                        + "means %.3f and %.3f",
                on ? "on" : "off", structure.share(), clearAirDensity, describeVisibility(visibility, clearAirDensity),
                structure.coreDensity(), describeVisibility(visibility, structure.coreDensity()), structure.coverage(),
                settingValue(SkySetting.FOG_STRUCTURE_SIZE), settingValue(SkySetting.FOG_STRUCTURE_STRETCH),
                structure.erosion(), structure.range(), structure.meanWithoutErosion(), structure.meanWithErosion());
    }

    /**
     * The visibility in blocks that a density gives of the visibility at the eye, or that it takes none away when the
     * density is not positive.
     */
    private static String describeVisibility(double visibility, double density) {
        if (!(density > 0.0)) {
            return "unlimited";
        }
        if (!Double.isFinite(visibility)) {
            return "unlimited";
        }
        return String.format(Locale.ROOT, "%.1f blocks", visibility / density);
    }

    /**
     * The distance byte of the eye's block and the share it gives, for the client command, or that the eye stands
     * outside the solved box of the field.
     */
    private static String describeEyeShare() {
        SnowField field = ClientSnowField.field();
        if (field == null || !field.isInSolvedBox(eyeBlockX, eyeBlockY, eyeBlockZ)) {
            return "the eye outside the solved box";
        }
        int distance = field.distanceAt(eyeBlockX, eyeBlockY, eyeBlockZ);
        return String.format(Locale.ROOT, "the eye's d %d eighths of a block and share %.3f", distance,
                Math.exp(-distance / (8.0 * weather().decayLength())));
    }

    /**
     * The number of cells of a region axis that lie inside the same axis of the shown box, which is 0 for no overlap.
     */
    private static long axisCount(int regionMin, int regionMax, int shownMin, int shownMax) {
        int first = Math.max(regionMin, shownMin);
        int last = Math.min(regionMax, shownMax);
        return last >= first ? (long) last - first + 1 : 0L;
    }

    /**
     * The distance in blocks by which a region axis sticks out of the same axis of the shown box on either side, which
     * is 0 when the region axis lies inside it.
     */
    private static int axisLag(int center, int halfWidth, int shownMin, int shownMax) {
        int low = center - halfWidth;
        int high = center + halfWidth;
        int lag = 0;
        if (low < shownMin) {
            lag = shownMin - low;
        }
        if (high > shownMax) {
            lag = Math.max(lag, high - shownMax);
        }
        return lag;
    }

    /**
     * The draw time of the fog of the last frame, for the client command.
     */
    public static String describeDrawTime() {
        return timer.describe();
    }

    /**
     * The time of each pass of the fog's draw, for the client command: the setup, the share volume, the inject, the
     * integrate, the composite and the whole draw, each with the times that the pass timer keeps, separated by
     * semicolons and followed by the number of frames they stand for. Allocates.
     */
    public static String describePasses() {
        return passTimer.describe();
    }

    /**
     * Clears the measured pass times, for the client command, so that the following lines measure only the passes of the
     * frames drawn from now on.
     */
    public static void resetPassTimes() {
        passTimer.reset();
    }

    /**
     * A line about the bound of vanilla's fog in the last frame, for the client command: the opaque distance and the
     * fog end it gives, the far plane it was found for, whether vanilla's fog takes it, the visibility at sea level and
     * the fog's height scale, whether the eye stands in open air and for how long, the upper height the search took, and
     * the bytes the fog's handlers allocated.
     */
    public static String describeBound() {
        String distance = Double.isFinite(boundDistance)
                ? String.format(Locale.ROOT, "%.1f blocks", boundDistance) : "none";
        String end = Double.isFinite(boundEnd)
                ? String.format(Locale.ROOT, "%.1f blocks", boundEnd) : "none";
        String farPlane = boundFarPlane > 0.0F
                ? String.format(Locale.ROOT, "%.1f blocks", boundFarPlane) : "none";
        String upperHeight = boundUpperHeight == Integer.MAX_VALUE
                ? "none" : String.format(Locale.ROOT, "%d blocks", boundUpperHeight);
        double referenceExtinction = weather().referenceExtinction();
        String visibility = referenceExtinction > 0.0
                ? String.format(Locale.ROOT, "%.1f blocks", HeightFog.VISIBILITY_OPTICAL_DEPTH / referenceExtinction)
                : "none";
        long openAirNanos = openAirSinceNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - openAirSinceNanos);
        String allocated = LightPillarSources.countsAllocations()
                ? String.format(Locale.ROOT, "%d bytes allocated", lastFrameAllocatedBytes)
                : "allocations not counted by this JVM";
        return String.format(Locale.ROOT, "Fog bound: opaque distance %s, fog end %s, far plane %s, %s; "
                        + "clear air density %.2f out to %.0f blocks; visibility %s at sea level, height scale %.1f "
                        + "blocks; open air %s for %.1f s, upper height %s; %s in the last frame",
                distance, end, farPlane, boundApplied ? "applied" : "not applied", boundClearAirDensity,
                boundClearRange, visibility, weather().heightScale(), openAirSinceNanos == 0L ? "no" : "yes",
                openAirNanos * 1.0e-9, upperHeight, allocated);
    }

    /**
     * The lines of the self test of the client command: it draws 64 fixed rays through the three stages of the froxel
     * pipeline, at the slice count of the last frame and, when that is below FogFroxelGrid.MAXIMUM_SLICES, again at the
     * maximum, walks the same rays with SnowFogWalk on the same inputs, and compares the excess of every boundary and
     * the sky value of each ray. Only a command calls it, so it allocates its readback buffers and its lines.
     * @return the lines to send, which report each ray outside the tolerance with its worst boundary, a summary of each
     *         run, the sky rays outside the tolerance, and the walk counts of the twin
     */
    public static List<String> selfTest() {
        List<String> lines = new ArrayList<>();
        try {
            collectSelfTest(lines);
        } catch (RuntimeException exception) {
            lines.add("The self test stopped after an error: " + exception.getMessage());
        }
        return lines;
    }

    /**
     * Draws the rays of the self test through the three stages of the froxel pipeline, sets the twin up on the inputs
     * of the last frame, and adds the comparison of both runs to the given lines.
     */
    private static void collectSelfTest(List<String> lines) {
        ClientLevel level = Minecraft.getInstance().level;
        if (keptFrameLevel == null || keptFrameLevel != level) {
            lines.add("The self test needs a frame of the fog drawn since the last level change");
            return;
        }
        if (!ClientSnowField.prepareTextures()) {
            lines.add("The self test needs the snow field of the level, which the fog does not follow yet");
            return;
        }
        SnowField field = ClientSnowField.field();
        FarHeights farHeights = ClientSnowField.farHeights();
        if (field == null || farHeights == null) {
            lines.add("The self test needs the snow field and the far heights of the level");
            return;
        }
        if (!field.isDrained()) {
            lines.add("The self test waits for the solver of the snow field, since the bytes on the CPU and the bytes "
                    + "in its texture may differ until it is drained");
            return;
        }
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        fillSelfTestOffsets();
        selfTestWalk.setField(field.distances(), field.size());
        selfTestWalk.setShownBox(SnowFieldTextures.hasShownBox(), SnowFieldTextures.shownMinX(),
                SnowFieldTextures.shownMinY(), SnowFieldTextures.shownMinZ(), SnowFieldTextures.shownMaxX(),
                SnowFieldTextures.shownMaxY(), SnowFieldTextures.shownMaxZ());
        selfTestWalk.setHeights(farHeights.heights());
        selfTestWalk.setTable(shareTable);
        selfTestWalk.setEye(eyeBlockX, eyeBlockY, eyeBlockZ, eyeFractionX, eyeFractionY, eyeFractionZ);
        selfTestWalk.setFog(eyeExtinction, frameHeightScale);
        selfTestWalk.setRadius(frameRadius);
        selfTestWalk.setFieldFade(frameFieldFade);
        selfTestWalk.setStructure(structure);
        runSelfTest(lines, mainTarget, frameSliceCount);
        if (frameSliceCount < FogFroxelGrid.MAXIMUM_SLICES) {
            runSelfTest(lines, mainTarget, FogFroxelGrid.MAXIMUM_SLICES);
        }
    }

    /**
     * Draws one run of the self test at a slice count, compares it with the twin, and adds the lines of the run to the
     * given lines. Only a command calls it, so it allocates the readback buffers of the run.
     * @param sliceCount the depth slices of a ray of the run, from FogFroxelGrid.MINIMUM_SLICES to
     *        FogFroxelGrid.MAXIMUM_SLICES
     */
    private static void runSelfTest(List<String> lines, RenderTarget mainTarget, int sliceCount) {
        int excessValueCount = FogFroxelGrid.layerCount(sliceCount) * SELF_TEST_RAY_COUNT * SELF_TEST_VALUES_PER_RAY;
        FloatBuffer excessReadback = BufferUtils.createFloatBuffer(excessValueCount);
        FloatBuffer skyReadback = BufferUtils.createFloatBuffer(SELF_TEST_RAY_COUNT * SELF_TEST_VALUES_PER_RAY);
        drawSelfTest(mainTarget, sliceCount, excessReadback, skyReadback);
        compareSelfTest(lines, sliceCount, excessReadback, skyReadback);
    }

    /**
     * Walks the rays of a run of the self test with SnowFogWalk and compares them with the readback of the GPU: the
     * depth of every boundary of every ray and the sky value of every ray. Only a command calls it, so it allocates its
     * arrays and its lines.
     * @param sliceCount the depth slices of a ray of the run
     * @param excessReadback the excess of every boundary of every ray, four floats per texel and layer after layer
     * @param skyReadback the sky value of every ray, four floats per texel
     */
    private static void compareSelfTest(List<String> lines, int sliceCount, FloatBuffer excessReadback,
            FloatBuffer skyReadback) {
        boolean structureOn = structure.isOn();
        double[] boundaryDistances = new double[sliceCount];
        for (int boundary = 1; boundary <= sliceCount; boundary++) {
            boundaryDistances[boundary - 1] = FogFroxelGrid.boundaryDistance(boundary, sliceCount, frameRangeDistance);
        }
        double[] twinExcesses = new double[sliceCount];
        int failedRayCount = 0;
        double largestError = 0.0;
        int largestErrorRay = 0;
        int largestErrorBoundary = 1;
        int largestNearSteps = 0;
        int largestFarSteps = 0;
        int cappedRayCount = 0;
        int valuesPerLayer = SELF_TEST_RAY_COUNT * SELF_TEST_VALUES_PER_RAY;
        for (int ray = 0; ray < SELF_TEST_RAY_COUNT; ray++) {
            double unitY = selfTestUnitY(ray);
            selfTestWalk.columnExcesses(selfTestOffsets[ray * 3], selfTestOffsets[ray * 3 + 1],
                    selfTestOffsets[ray * 3 + 2], boundaryDistances, sliceCount, twinExcesses);
            largestNearSteps = Math.max(largestNearSteps, selfTestWalk.lastNearSteps());
            largestFarSteps = Math.max(largestFarSteps, selfTestWalk.lastFarSteps());
            if (selfTestWalk.lastNearCapped() || selfTestWalk.lastFarCapped()) {
                cappedRayCount++;
            }
            boolean rayFailed = false;
            double worstError = 0.0;
            int worstBoundary = 1;
            double worstGpuDepth = 0.0;
            double worstExpectedDepth = 0.0;
            double worstAllowed = 0.0;
            for (int boundary = 1; boundary <= sliceCount; boundary++) {
                double distance = boundaryDistances[boundary - 1];
                double analyticDepth = HeightFog.opticalDepth(eyeExtinction, distance, distance * unitY,
                        frameHeightScale);
                int valueIndex = FogFroxelGrid.layerOf(boundary) * valuesPerLayer + ray * SELF_TEST_VALUES_PER_RAY
                        + FogFroxelGrid.channelOf(boundary);
                double gpuDepth = analyticDepth + excessReadback.get(valueIndex);
                double expectedDepth = analyticDepth + twinExcesses[boundary - 1];
                double allowed = SELF_TEST_WALK_TOLERANCE + SELF_TEST_WALK_TOLERANCE * Math.abs(expectedDepth);
                if (structureOn) {
                    allowed = Math.max(allowed, SELF_TEST_STRUCTURE_TOLERANCE_SHARE * Math.abs(expectedDepth));
                }
                double error = Math.abs(gpuDepth - expectedDepth);
                if (error > largestError) {
                    largestError = error;
                    largestErrorRay = ray;
                    largestErrorBoundary = boundary;
                }
                if (error > worstError) {
                    worstError = error;
                    worstBoundary = boundary;
                    worstGpuDepth = gpuDepth;
                    worstExpectedDepth = expectedDepth;
                    worstAllowed = allowed;
                }
                if (error > allowed) {
                    rayFailed = true;
                }
            }
            if (rayFailed) {
                failedRayCount++;
                lines.add(String.format(Locale.ROOT, "The self test at %d slices: the ray %d at (%.2f, %.2f, %.2f) has "
                                + "the depth %.6f at the boundary %d on the GPU instead of %.6f in the walk, an error "
                                + "of %.6f above %.6f", sliceCount, ray, selfTestOffsets[ray * 3],
                        selfTestOffsets[ray * 3 + 1], selfTestOffsets[ray * 3 + 2], worstGpuDepth, worstBoundary,
                        worstExpectedDepth, worstError, worstAllowed));
            }
        }
        lines.add(String.format(Locale.ROOT, "The self test at %d slices compared %d rays over %d boundaries each, %d "
                        + "rays outside the tolerance on the depth, the largest error %.6f at the ray %d and the "
                        + "boundary %d, %s", sliceCount, SELF_TEST_RAY_COUNT, sliceCount, failedRayCount, largestError,
                largestErrorRay, largestErrorBoundary,
                structureOn ? "with the structures on" : "with the structures off"));
        // The sky value of a ray is the analytic depth at the range, the excess of the last boundary and the tail past
        // the range, as fogSkyTau sums them:
        double lastDistance = boundaryDistances[sliceCount - 1];
        int skyFailedRayCount = 0;
        double largestSkyError = 0.0;
        int largestSkyErrorRay = 0;
        for (int ray = 0; ray < SELF_TEST_RAY_COUNT; ray++) {
            double unitY = selfTestUnitY(ray);
            selfTestWalk.columnExcesses(selfTestOffsets[ray * 3], selfTestOffsets[ray * 3 + 1],
                    selfTestOffsets[ray * 3 + 2], boundaryDistances, sliceCount, twinExcesses);
            double analyticDepth = HeightFog.opticalDepth(eyeExtinction, lastDistance, lastDistance * unitY,
                    frameHeightScale);
            double tailDepth = HeightFog.skyTailOpticalDepth(eyeExtinction, frameRangeDistance, unitY,
                    frameHeightScale);
            double expectedSky = Math.min(HeightFog.MAXIMUM_OPTICAL_DEPTH,
                    Math.max(0.0, analyticDepth + twinExcesses[sliceCount - 1] + tailDepth));
            double gpuSky = skyReadback.get(ray * SELF_TEST_VALUES_PER_RAY);
            double allowed = SELF_TEST_WALK_TOLERANCE + SELF_TEST_WALK_TOLERANCE * Math.abs(expectedSky);
            if (structureOn) {
                allowed = Math.max(allowed, SELF_TEST_STRUCTURE_TOLERANCE_SHARE * Math.abs(expectedSky));
            }
            double error = Math.abs(gpuSky - expectedSky);
            if (error > largestSkyError) {
                largestSkyError = error;
                largestSkyErrorRay = ray;
            }
            if (error > allowed) {
                skyFailedRayCount++;
                lines.add(String.format(Locale.ROOT, "The sky of the self test at %d slices: the ray %d at (%.2f, %.2f, "
                                + "%.2f) has the depth %.6f on the GPU instead of %.6f in the walk, an error of %.6f "
                                + "above %.6f", sliceCount, ray, selfTestOffsets[ray * 3],
                        selfTestOffsets[ray * 3 + 1], selfTestOffsets[ray * 3 + 2], gpuSky, expectedSky, error,
                        allowed));
            }
        }
        lines.add(String.format(Locale.ROOT, "The sky of the self test at %d slices compared %d rays over the range "
                        + "%.0f blocks, %d of them outside the tolerance, the largest error %.6f at the ray %d, %s",
                sliceCount, SELF_TEST_RAY_COUNT, frameRangeDistance, skyFailedRayCount, largestSkyError,
                largestSkyErrorRay, structureOn ? "with the structures on" : "with the structures off"));
        lines.add(String.format(Locale.ROOT, "The twin over the %d rays walked at most %d near cells and %d far "
                + "cells in its last piece, and the last piece of %d rays ran out of one of its caps; the froxel "
                + "debug view shows the steps of the GPU", SELF_TEST_RAY_COUNT, largestNearSteps, largestFarSteps,
                cappedRayCount));
    }

    /**
     * The upward component of the unit direction of one ray of the self test.
     * @param ray the ray, from 0 to SELF_TEST_RAY_COUNT - 1
     * @return the component in y, from -1 to 1, which is 0 for a ray of zero length
     */
    private static double selfTestUnitY(int ray) {
        double offsetX = selfTestOffsets[ray * 3];
        double offsetY = selfTestOffsets[ray * 3 + 1];
        double offsetZ = selfTestOffsets[ray * 3 + 2];
        double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        if (!(length > 0.0) || !Double.isFinite(length)) {
            return 0.0;
        }
        return offsetY / length;
    }

    /**
     * Fills the rays of the self test: 48 along a golden angle spiral, whose lengths cycle through the 6, 25, 45, 120
     * and 300 blocks of SELF_TEST_SPIRAL_LENGTHS, and 16 fixed ones along the axes, at two mixed offsets, along the x-y
     * diagonal and through the cube diagonal, at lengths from 20 to 138.6 blocks.
     */
    private static void fillSelfTestOffsets() {
        for (int ray = 0; ray < SELF_TEST_SPIRAL_RAYS; ray++) {
            double vertical = 1.0 - 2.0 * (ray + 0.5) / SELF_TEST_SPIRAL_RAYS;
            double horizontal = Math.sqrt(Math.max(0.0, 1.0 - vertical * vertical));
            double angle = ray * SELF_TEST_GOLDEN_ANGLE;
            double length = SELF_TEST_SPIRAL_LENGTHS[ray % SELF_TEST_SPIRAL_LENGTHS.length];
            selfTestOffsets[ray * 3] = (float) (Math.cos(angle) * horizontal * length);
            selfTestOffsets[ray * 3 + 1] = (float) (vertical * length);
            selfTestOffsets[ray * 3 + 2] = (float) (Math.sin(angle) * horizontal * length);
        }
        int ray = SELF_TEST_SPIRAL_RAYS;
        ray = addSelfTestRay(ray, 35.0, 0.0, 0.0);
        ray = addSelfTestRay(ray, -35.0, 0.0, 0.0);
        ray = addSelfTestRay(ray, 0.0, 0.0, 35.0);
        ray = addSelfTestRay(ray, 0.0, 0.0, -35.0);
        ray = addSelfTestRay(ray, 80.0, 0.0, 0.0);
        ray = addSelfTestRay(ray, -80.0, 0.0, 0.0);
        ray = addSelfTestRay(ray, 0.0, 0.0, 80.0);
        ray = addSelfTestRay(ray, 0.0, 0.0, -80.0);
        ray = addSelfTestRay(ray, 0.0, -20.0, 0.0);
        ray = addSelfTestRay(ray, 0.0, -70.0, 0.0);
        ray = addSelfTestRay(ray, 0.0, 20.0, 0.0);
        ray = addSelfTestRay(ray, 0.0, 70.0, 0.0);
        ray = addSelfTestRay(ray, 80.0, -3.0, 17.0);
        ray = addSelfTestRay(ray, -60.0, -2.0, -41.0);
        ray = addSelfTestRay(ray, 35.0, 35.0, 0.0);
        ray = addSelfTestRay(ray, 80.0, 80.0, 80.0);
        selfTestOffsetBuffer.clear();
        selfTestOffsetBuffer.put(selfTestOffsets);
        selfTestOffsetBuffer.flip();
    }

    /**
     * Sets one ray of the self test and returns the index of the ray after it.
     */
    private static int addSelfTestRay(int ray, double offsetX, double offsetY, double offsetZ) {
        selfTestOffsets[ray * 3] = (float) offsetX;
        selfTestOffsets[ray * 3 + 1] = (float) offsetY;
        selfTestOffsets[ray * 3 + 2] = (float) offsetZ;
        return ray + 1;
    }

    /**
     * Draws the rays of the self test through the inject, the integrate and the composite at the given slice count, and
     * reads the excess of every boundary and the sky value back. It binds the same textures as the frame's passes and
     * fills the share volume again, so that its rays read the volume, the uniforms and the textures that the walk on the
     * CPU reads. The run draws without blending, which its outputs need since they write an alpha of 0, and no stage of
     * it samples the depth. Every piece of state it changes is set back after it.
     * @param sliceCount the depth slices of a ray of the run, from FogFroxelGrid.MINIMUM_SLICES to
     *        FogFroxelGrid.MAXIMUM_SLICES
     * @param excessReadback the buffer that receives the 64 by 1 texels of every layer of the run's slices, four floats
     *        per texel and layer after layer
     * @param skyReadback the buffer that receives the 64 by 1 texels of the composite's target
     */
    private static void drawSelfTest(RenderTarget mainTarget, int sliceCount, FloatBuffer excessReadback,
            FloatBuffer skyReadback) {
        createSelfTestFroxelTargets();
        pass.begin();
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FIELD_UNIT);
        int previousFieldTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + STRUCTURE_UNIT);
        int previousStructureTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
        int previousExcessTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            updateStructureTexture();
            RenderSystem.disableScissor();
            RenderSystem.disableCull();
            bindFieldTextures();
            boolean shareVolumeBound = prepareShareVolume();
            if (shareVolumeBound) {
                drawShareVolume(mainTarget);
                bindShareVolume();
            }
            drawSelfTestInject(mainTarget, sliceCount, shareVolumeBound);
            drawSelfTestIntegrate(mainTarget, sliceCount);
            drawSelfTestComposite(mainTarget, sliceCount, shareVolumeBound);
            readSelfTestTargets(sliceCount, excessReadback, skyReadback);
        } finally {
            if (scissor) {
                GlStateManager._enableScissorTest();
            }
            if (culling) {
                RenderSystem.enableCull();
            }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FIELD_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousFieldTexture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + STRUCTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousStructureTexture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousExcessTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            pass.end();
        }
    }

    /**
     * Draws the pieces of the 64 rays of the self test into its atlas, with its columns in place of the frame's grid.
     * @param shareVolumeBound whether the walk may read the share volume that the caller filled
     */
    private static void drawSelfTestInject(RenderTarget mainTarget, int sliceCount, boolean shareVolumeBound) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, selfTestAtlasFramebuffer);
        RenderSystem.viewport(0, 0, FogFroxelGrid.atlasWidth(SELF_TEST_RAY_COUNT),
                FogFroxelGrid.atlasDrawHeight(1, sliceCount));
        injectPass.use();
        injectPass.setFrameUniforms(mainTarget);
        injectPass.setShareVolumeInUse(shareVolumeBound);
        injectPass.setSelfTestUniforms(sliceCount);
        SkyGlPass.drawFullScreenQuad();
    }

    /**
     * Draws the excess of every boundary of the 64 rays of the self test into its array. A slice count above the 32
     * boundaries that eight outputs hold takes a second draw into the upper framebuffer, which sums the first 32 pieces
     * again.
     */
    private static void drawSelfTestIntegrate(RenderTarget mainTarget, int sliceCount) {
        int boundariesPerDraw = FogFroxelGrid.TILES_ACROSS * FogFroxelGrid.SLICES_PER_LAYER;
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + ATLAS_UNIT);
        RenderSystem.bindTexture(selfTestAtlasTexture);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, selfTestExcessLowerFramebuffer);
        integratePass.use();
        if (froxelTargetFailed) {
            throw new IllegalStateException("The weather fog integrate writes its slices to unknown outputs");
        }
        integratePass.setFrameUniforms(mainTarget);
        integratePass.setSelfTestUniforms(sliceCount);
        setExcessDrawBuffers(0, sliceCount);
        RenderSystem.viewport(0, 0, SELF_TEST_RAY_COUNT, 1);
        SkyGlPass.drawFullScreenQuad();
        if (sliceCount > boundariesPerDraw) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, selfTestExcessUpperFramebuffer);
            integratePass.setFirstBoundary(boundariesPerDraw + 1);
            setExcessDrawBuffers(FogFroxelGrid.TILES_ACROSS, sliceCount);
            SkyGlPass.drawFullScreenQuad();
        }
    }

    /**
     * Draws the sky value of the 64 rays of the self test into the composite's target, which reads the excess of the
     * last boundary of each ray from the self test array.
     * @param shareVolumeBound whether the walk may read the share volume that the caller filled
     */
    private static void drawSelfTestComposite(RenderTarget mainTarget, int sliceCount, boolean shareVolumeBound) {
        createSelfTestTarget();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, selfTestFramebuffer);
        RenderSystem.viewport(0, 0, SELF_TEST_RAY_COUNT, 1);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, selfTestExcessTexture);
        compositePass.use();
        compositePass.setFrameUniforms(mainTarget);
        compositePass.setShareVolumeInUse(shareVolumeBound);
        compositePass.setSelfTestUniforms(sliceCount);
        SkyGlPass.drawFullScreenQuad();
    }

    /**
     * Reads the excesses of the layers of the self test array and the sky value of the composite's target back into the
     * given buffers, and takes the attachment of the read framebuffer off again.
     * @param sliceCount the depth slices of the run, whose FogFroxelGrid.layerCount layers hold the excesses
     * @param excessReadback the buffer that receives the layers, four floats per texel and layer after layer
     * @param skyReadback the buffer that receives the composite's target, four floats per texel
     */
    private static void readSelfTestTargets(int sliceCount, FloatBuffer excessReadback, FloatBuffer skyReadback) {
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL12.GL_PACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL12.GL_PACK_SKIP_IMAGES, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        int valuesPerLayer = SELF_TEST_RAY_COUNT * SELF_TEST_VALUES_PER_RAY;
        int layerCount = FogFroxelGrid.layerCount(sliceCount);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, selfTestReadFramebuffer);
        for (int layer = 0; layer < layerCount; layer++) {
            GL30.glFramebufferTextureLayer(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, selfTestExcessTexture,
                    0, layer);
            // The read buffer follows each attachment, so that the read framebuffer stays complete throughout:
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            excessReadback.position(layer * valuesPerLayer);
            GL11.glReadPixels(0, 0, SELF_TEST_RAY_COUNT, 1, GL11.GL_RGBA, GL11.GL_FLOAT, excessReadback);
        }
        GL30.glFramebufferTextureLayer(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 0, 0, 0);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, selfTestFramebuffer);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        skyReadback.clear();
        GL11.glReadPixels(0, 0, SELF_TEST_RAY_COUNT, 1, GL11.GL_RGBA, GL11.GL_FLOAT, skyReadback);
    }

    /**
     * Makes the color only target of the self test, 64 texels of a single row of GL_RGBA32F, and its framebuffer, on
     * first use, and keeps them for the session. A target that fails to be made is deleted again.
     */
    private static void createSelfTestTarget() {
        if (selfTestTexture != 0) {
            return;
        }
        int targetTexture = GL11.glGenTextures();
        int targetFramebuffer = GL30.glGenFramebuffers();
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SELF_TEST_RAY_COUNT, 1, 0, GL11.GL_RGBA,
                        GL11.GL_FLOAT, (FloatBuffer) null);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }
            int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFramebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        targetTexture, 0);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The weather fog self test framebuffer is incomplete with status "
                            + status);
                }
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(targetTexture);
            GL30.glDeleteFramebuffers(targetFramebuffer);
            throw exception;
        }
        selfTestTexture = targetTexture;
        selfTestFramebuffer = targetFramebuffer;
    }

    /**
     * Makes the targets of the froxel stages of the self test on first use and keeps them for the session. A target
     * that cannot be made is deleted again, and the next call throws without trying.
     * @throws IllegalStateException when a target cannot be made, which leaves the self test undone for the session
     */
    private static void createSelfTestFroxelTargets() {
        if (selfTestTargetFailed) {
            throw new IllegalStateException("The weather fog self test targets cannot be made in this session");
        }
        if (selfTestAtlasTexture != 0) {
            return;
        }
        try {
            createSelfTestAtlas();
            createSelfTestExcessArray();
            selfTestReadFramebuffer = GL30.glGenFramebuffers();
        } catch (RuntimeException exception) {
            deleteSelfTestFroxelTargets();
            selfTestTargetFailed = true;
            throw exception;
        }
    }

    /**
     * Makes the atlas of the pieces of the self test, an R32F 2D texture of 512 by 8 texels: the 64 columns of the rays
     * times the eight tiles across, by the eight rows of the tiles. Its framebuffer holds it on the color attachment 0,
     * and the bindings that were there before stay bound.
     * @throws IllegalStateException when the framebuffer is incomplete
     */
    private static void createSelfTestAtlas() {
        int targetTexture = GL11.glGenTextures();
        int targetFramebuffer = GL30.glGenFramebuffers();
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, targetTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, FogFroxelGrid.atlasWidth(SELF_TEST_RAY_COUNT),
                        FogFroxelGrid.atlasHeight(1), 0, GL11.GL_RED, GL11.GL_FLOAT, (FloatBuffer) null);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }
            int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        targetTexture, 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The weather fog self test inject atlas framebuffer is incomplete "
                            + "with status " + status);
                }
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(targetTexture);
            GL30.glDeleteFramebuffers(targetFramebuffer);
            throw exception;
        }
        selfTestAtlasTexture = targetTexture;
        selfTestAtlasFramebuffer = targetFramebuffer;
    }

    /**
     * Makes the array of the excesses of the self test, an RGBA32F 2D array texture of the 64 rays by a single row by
     * FogFroxelGrid.MAXIMUM_LAYERS, and the two framebuffers that attach its lower and its upper eight layers to the
     * color attachments 0 to 7. The bindings that were there before stay bound.
     * @throws IllegalStateException when one of the framebuffers is incomplete
     */
    private static void createSelfTestExcessArray() {
        int targetTexture = GL11.glGenTextures();
        int lowerFramebuffer = GL30.glGenFramebuffers();
        int upperFramebuffer = GL30.glGenFramebuffers();
        try {
            int previousTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            try {
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, targetTexture);
                // The self test reads the array with texelFetch, and filtered reads of 32-bit floats are not core:
                GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA32F, SELF_TEST_RAY_COUNT, 1,
                        FogFroxelGrid.MAXIMUM_LAYERS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            } finally {
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousTexture);
            }
            int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, lowerFramebuffer);
                for (int layer = 0; layer < FogFroxelGrid.TILES_ACROSS; layer++) {
                    GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + layer,
                            targetTexture, 0, layer);
                }
                int lowerStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if (lowerStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The weather fog self test excess framebuffer of the lower layers "
                            + "is incomplete with status " + lowerStatus);
                }
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, upperFramebuffer);
                for (int layer = 0; layer < FogFroxelGrid.TILES_ACROSS; layer++) {
                    GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + layer,
                            targetTexture, 0, layer + FogFroxelGrid.TILES_ACROSS);
                }
                int upperStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
                if (upperStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The weather fog self test excess framebuffer of the upper layers "
                            + "is incomplete with status " + upperStatus);
                }
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(targetTexture);
            GL30.glDeleteFramebuffers(lowerFramebuffer);
            GL30.glDeleteFramebuffers(upperFramebuffer);
            throw exception;
        }
        selfTestExcessTexture = targetTexture;
        selfTestExcessLowerFramebuffer = lowerFramebuffer;
        selfTestExcessUpperFramebuffer = upperFramebuffer;
    }

    /**
     * Deletes the targets of the froxel stages of the self test and forgets them.
     */
    private static void deleteSelfTestFroxelTargets() {
        if (selfTestAtlasTexture != 0) {
            GL11.glDeleteTextures(selfTestAtlasTexture);
            selfTestAtlasTexture = 0;
        }
        if (selfTestAtlasFramebuffer != 0) {
            GL30.glDeleteFramebuffers(selfTestAtlasFramebuffer);
            selfTestAtlasFramebuffer = 0;
        }
        if (selfTestExcessTexture != 0) {
            GL11.glDeleteTextures(selfTestExcessTexture);
            selfTestExcessTexture = 0;
        }
        if (selfTestExcessLowerFramebuffer != 0) {
            GL30.glDeleteFramebuffers(selfTestExcessLowerFramebuffer);
            selfTestExcessLowerFramebuffer = 0;
        }
        if (selfTestExcessUpperFramebuffer != 0) {
            GL30.glDeleteFramebuffers(selfTestExcessUpperFramebuffer);
            selfTestExcessUpperFramebuffer = 0;
        }
        if (selfTestReadFramebuffer != 0) {
            GL30.glDeleteFramebuffers(selfTestReadFramebuffer);
            selfTestReadFramebuffer = 0;
        }
    }

    /**
     * Sets the uniforms of the froxel grid on the program in use: the columns and rows of the grid, the depth slices of
     * a ray, the range in blocks of its last boundary, and the pixels per side of a column.
     */
    private static void setGridUniforms(int columnCountUniform, int rowCountUniform, int sliceCountUniform,
            int rangeDistanceUniform, int divisorUniform) {
        GL20.glUniform1i(columnCountUniform, frameColumnCount);
        GL20.glUniform1i(rowCountUniform, frameRowCount);
        GL20.glUniform1i(sliceCountUniform, frameSliceCount);
        GL20.glUniform1f(rangeDistanceUniform, (float) frameRangeDistance);
        GL20.glUniform1i(divisorUniform, frameFroxelDivisor);
    }

    /**
     * The locations of the uniforms of the scene and the walk that the programs extending this class share, the program
     * itself, and the setting of them every frame. A program is made again when a resource reload replaced its stage,
     * and its samplers are set once after it links.
     */
    private abstract static class FogProgramBase {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int inverseViewProjectionUniform;
        private int screenSizeUniform;
        private int eyeExtinctionUniform;
        private int heightScaleUniform;
        private int fieldMaskUniform;
        private int hasShownBoxUniform;
        private int shownMinUniform;
        private int shownMaxUniform;
        private int eyeBlockUniform;
        private int eyeFractionUniform;
        private int regionHalfWidthUniform;
        private int fieldFadeUniform;
        private int structureShareUniform;
        private int structureCoverageUniform;
        private int structureErosionUniform;
        private int structureRangeUniform;
        private int structureMeansUniform;
        private int structureEyeUniform;
        private int structureAlongUniform;
        private int structureUpUniform;
        private int structureAcrossUniform;
        private int hasShareVolumeUniform;
        protected int partMaskUniform;

        /**
         * The fragment stage of this program, which FHShaders makes again after a resource reload.
         */
        protected abstract Shader loadFragment();

        /**
         * Sets the samplers of this program and looks up the locations of its own uniforms, once after it links.
         */
        protected abstract void onLinked(ShaderProgram linked);

        /**
         * Sets the uniforms that only this program has, once for each frame.
         */
        protected abstract void setOwnUniforms(RenderTarget mainTarget);

        /**
         * Uses the program, making it first if needed.
         */
        protected void use() {
            Shader newFragment = loadFragment();
            if (program == null || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                try {
                    created.attach(FHShaders.IMAGE_V).attach(newFragment);
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                fragment = newFragment;
                program.use();
                onLinked(program);
                inverseViewProjectionUniform = GL20.glGetUniformLocation(program.programId, "inverseViewProjection");
                screenSizeUniform = GL20.glGetUniformLocation(program.programId, "screenSize");
                eyeExtinctionUniform = GL20.glGetUniformLocation(program.programId, "eyeExtinction");
                heightScaleUniform = GL20.glGetUniformLocation(program.programId, "heightScale");
                fieldMaskUniform = GL20.glGetUniformLocation(program.programId, "fieldMask");
                hasShownBoxUniform = GL20.glGetUniformLocation(program.programId, "hasShownBox");
                shownMinUniform = GL20.glGetUniformLocation(program.programId, "shownMin");
                shownMaxUniform = GL20.glGetUniformLocation(program.programId, "shownMax");
                eyeBlockUniform = GL20.glGetUniformLocation(program.programId, "eyeBlock");
                eyeFractionUniform = GL20.glGetUniformLocation(program.programId, "eyeFraction");
                regionHalfWidthUniform = GL20.glGetUniformLocation(program.programId, "regionHalfWidth");
                fieldFadeUniform = GL20.glGetUniformLocation(program.programId, "fieldFade");
                partMaskUniform = GL20.glGetUniformLocation(program.programId, "partMask");
                structureShareUniform = GL20.glGetUniformLocation(program.programId, "structureShare");
                structureCoverageUniform = GL20.glGetUniformLocation(program.programId, "structureCoverage");
                structureErosionUniform = GL20.glGetUniformLocation(program.programId, "structureErosion");
                structureRangeUniform = GL20.glGetUniformLocation(program.programId, "structureRange");
                structureMeansUniform = GL20.glGetUniformLocation(program.programId, "structureMeans");
                structureEyeUniform = GL20.glGetUniformLocation(program.programId, "structureEye");
                structureAlongUniform = GL20.glGetUniformLocation(program.programId, "structureAlong");
                structureUpUniform = GL20.glGetUniformLocation(program.programId, "structureUp");
                structureAcrossUniform = GL20.glGetUniformLocation(program.programId, "structureAcross");
                hasShareVolumeUniform = GL20.glGetUniformLocation(program.programId, "hasShareVolume");
                // The share volume stays on its unit for every frame, so its sampler is set once after the link:
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "shareVolume"), SHARE_VOLUME_UNIT);
            }
            program.use();
        }

        /**
         * Sets the uniforms of this frame on the program in use: the inverse view projection, the size of the main
         * target, the extinction at the eye and the fog's height scale, the field and its shown box, the eye as a block
         * and a fraction, the region, the fade, which parts of the walk run, and the structures of the fog. The share is
         * 0 while the structures are off, so the guards of the CPU and the GPU twins cannot disagree. The eye's block
         * goes as three ints, so no world coordinate passes through a float.
         */
        protected void setFrameUniforms(RenderTarget mainTarget) {
            SnowField field = ClientSnowField.field();
            inverseViewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(inverseViewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(eyeExtinctionUniform, (float) eyeExtinction);
            GL20.glUniform1f(heightScaleUniform, (float) frameHeightScale);
            GL20.glUniform1i(fieldMaskUniform, field != null ? field.size() - 1 : 0);
            GL20.glUniform1i(hasShownBoxUniform, SnowFieldTextures.hasShownBox() ? 1 : 0);
            GL20.glUniform3i(shownMinUniform, SnowFieldTextures.shownMinX(), SnowFieldTextures.shownMinY(),
                    SnowFieldTextures.shownMinZ());
            GL20.glUniform3i(shownMaxUniform, SnowFieldTextures.shownMaxX(), SnowFieldTextures.shownMaxY(),
                    SnowFieldTextures.shownMaxZ());
            GL20.glUniform3i(eyeBlockUniform, eyeBlockX, eyeBlockY, eyeBlockZ);
            GL20.glUniform3f(eyeFractionUniform, (float) eyeFractionX, (float) eyeFractionY, (float) eyeFractionZ);
            GL20.glUniform1f(regionHalfWidthUniform, (float) frameHalfWidth);
            GL20.glUniform1f(fieldFadeUniform, (float) frameFieldFade);
            GL20.glUniform1i(partMaskUniform, partMask);
            GL20.glUniform1f(structureShareUniform, structure.isOn() ? (float) structure.share() : 0.0F);
            GL20.glUniform1f(structureCoverageUniform, (float) structure.coverage());
            GL20.glUniform1f(structureErosionUniform, (float) structure.erosion());
            GL20.glUniform1f(structureRangeUniform, (float) structure.range());
            GL20.glUniform2f(structureMeansUniform, (float) structure.meanWithoutErosion(),
                    (float) structure.meanWithErosion());
            GL20.glUniform3f(structureEyeUniform, (float) structure.eyePatternU(), (float) structure.eyePatternV(),
                    (float) structure.eyePatternW());
            GL20.glUniform3f(structureAlongUniform, (float) structure.alongX(), 0.0F, (float) structure.alongZ());
            GL20.glUniform3f(structureUpUniform, 0.0F, (float) structure.upY(), 0.0F);
            GL20.glUniform3f(structureAcrossUniform, (float) structure.acrossX(), 0.0F,
                    (float) structure.acrossZ());
            setOwnUniforms(mainTarget);
        }

        /**
         * Sets whether the walk of this program reads the share volume of the frame, which needs the volume bound to
         * its unit. The share pass sets it off, since it fills the volume that it would read.
         */
        protected void setShareVolumeInUse(boolean inUse) {
            GL20.glUniform1i(hasShareVolumeUniform, inUse ? 1 : 0);
        }
    }

    /**
     * The program of the inject, weather_fog.glsl and snow_walk.glsl followed by weather_fog_inject.fsh, and the
     * uniforms that only it has: the froxel grid and the self test.
     */
    private static final class InjectProgram extends FogProgramBase {
        private int columnCountUniform;
        private int rowCountUniform;
        private int sliceCountUniform;
        private int rangeDistanceUniform;
        private int divisorUniform;
        private int selfTestUniform;
        private int selfTestOffsetsUniform;

        @Override
        protected Shader loadFragment() {
            return FHShaders.getWeatherFogInject();
        }

        @Override
        protected void onLinked(ShaderProgram linked) {
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "snowField"), FIELD_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "farHeights"), HEIGHT_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "shareTable"), SHARE_TABLE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "fogStructure"), STRUCTURE_UNIT);
            columnCountUniform = GL20.glGetUniformLocation(linked.programId, "columnCount");
            rowCountUniform = GL20.glGetUniformLocation(linked.programId, "rowCount");
            sliceCountUniform = GL20.glGetUniformLocation(linked.programId, "sliceCount");
            rangeDistanceUniform = GL20.glGetUniformLocation(linked.programId, "rangeDistance");
            divisorUniform = GL20.glGetUniformLocation(linked.programId, "divisor");
            selfTestUniform = GL20.glGetUniformLocation(linked.programId, "selfTest");
            selfTestOffsetsUniform = GL20.glGetUniformLocation(linked.programId, "selfTestOffsets");
        }

        @Override
        protected void setOwnUniforms(RenderTarget mainTarget) {
            setGridUniforms(columnCountUniform, rowCountUniform, sliceCountUniform, rangeDistanceUniform,
                    divisorUniform);
            GL20.glUniform1i(selfTestUniform, 0);
        }

        /**
         * Sets the uniforms of the rays of the self test in place of the frame's grid: the 64 rays as the columns of a
         * single row, the depth slices of the run, and every part of the walk, so that no debug view leaves one out.
         * The range, the divisor and the rest of the frame's uniforms stay as setFrameUniforms set them.
         * @param sliceCount the depth slices of a ray of the run, from FogFroxelGrid.MINIMUM_SLICES to
         *        FogFroxelGrid.MAXIMUM_SLICES
         */
        private void setSelfTestUniforms(int sliceCount) {
            GL20.glUniform1i(selfTestUniform, 1);
            GL20.glUniform1i(partMaskUniform, 3);
            GL20.glUniform1i(columnCountUniform, SELF_TEST_RAY_COUNT);
            GL20.glUniform1i(rowCountUniform, 1);
            GL20.glUniform1i(sliceCountUniform, sliceCount);
            GL20.glUniform3fv(selfTestOffsetsUniform, selfTestOffsetBuffer);
        }
    }

    /**
     * The program of the integrate, weather_fog.glsl followed by weather_fog_integrate.fsh, and the uniforms that only
     * it has: the froxel grid, the inverse view projection, the size of the main target, the extinction at the eye, the
     * fog's height scale, the boundary that the first of its eight outputs holds, and the self test. The integrate stage
     * has none of the walk uniforms, so this program is its own wrapper.
     */
    private static final class IntegrateProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int columnCountUniform;
        private int rowCountUniform;
        private int sliceCountUniform;
        private int rangeDistanceUniform;
        private int divisorUniform;
        private int firstBoundaryUniform;
        private int selfTestUniform;
        private int selfTestOffsetsUniform;
        private int eyeExtinctionUniform;
        private int heightScaleUniform;
        private int screenSizeUniform;
        private int inverseViewProjectionUniform;

        /**
         * The fragment stage of this program, which FHShaders makes again after a resource reload.
         */
        private Shader loadFragment() {
            return FHShaders.getWeatherFogIntegrate();
        }

        /**
         * Uses the program, making it first if needed, and sets its sampler and the locations of its outputs once after
         * it links. A link whose outputs are not the eight distinct color attachments leaves the fog off.
         */
        private void use() {
            Shader newFragment = loadFragment();
            if (program == null || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                try {
                    created.attach(FHShaders.IMAGE_V).attach(newFragment);
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                fragment = newFragment;
                program.use();
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "pieceAtlas"), ATLAS_UNIT);
                columnCountUniform = GL20.glGetUniformLocation(program.programId, "columnCount");
                rowCountUniform = GL20.glGetUniformLocation(program.programId, "rowCount");
                sliceCountUniform = GL20.glGetUniformLocation(program.programId, "sliceCount");
                rangeDistanceUniform = GL20.glGetUniformLocation(program.programId, "rangeDistance");
                divisorUniform = GL20.glGetUniformLocation(program.programId, "divisor");
                firstBoundaryUniform = GL20.glGetUniformLocation(program.programId, "firstBoundary");
                selfTestUniform = GL20.glGetUniformLocation(program.programId, "selfTest");
                selfTestOffsetsUniform = GL20.glGetUniformLocation(program.programId, "selfTestOffsets");
                eyeExtinctionUniform = GL20.glGetUniformLocation(program.programId, "eyeExtinction");
                heightScaleUniform = GL20.glGetUniformLocation(program.programId, "heightScale");
                screenSizeUniform = GL20.glGetUniformLocation(program.programId, "screenSize");
                inverseViewProjectionUniform = GL20.glGetUniformLocation(program.programId, "inverseViewProjection");
                // The layer of each slice follows its output's assigned location, which GLSL 1.50 leaves to the
                // linker:
                boolean locationsValid = true;
                for (int output = 0; output < FogFroxelGrid.TILES_ACROSS; output++) {
                    int location = GL30.glGetFragDataLocation(program.programId, "froxelSlices" + output);
                    froxelOutputLocations[output] = location;
                    if (location < 0 || location >= FogFroxelGrid.TILES_ACROSS) {
                        locationsValid = false;
                    } else {
                        for (int earlierOutput = 0; earlierOutput < output; earlierOutput++) {
                            if (froxelOutputLocations[earlierOutput] == location) {
                                locationsValid = false;
                            }
                        }
                    }
                }
                if (!locationsValid) {
                    froxelTargetFailed = true;
                    LOGGER.warn("The weather fog integrate writes its slices to unknown outputs, so the fog is not "
                            + "drawn for the rest of the session.");
                }
            }
            program.use();
        }

        /**
         * Sets the uniforms of this frame on the program in use: the froxel grid, the inverse view projection through
         * the shared buffer, the size of the main target, the extinction at the eye and the fog's height scale, and the
         * self test off.
         */
        private void setFrameUniforms(RenderTarget mainTarget) {
            inverseViewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(inverseViewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(eyeExtinctionUniform, (float) eyeExtinction);
            GL20.glUniform1f(heightScaleUniform, (float) frameHeightScale);
            setGridUniforms(columnCountUniform, rowCountUniform, sliceCountUniform, rangeDistanceUniform,
                    divisorUniform);
            GL20.glUniform1i(selfTestUniform, 0);
        }

        /**
         * Sets the boundary that the first of the eight outputs of the following draw holds.
         */
        private void setFirstBoundary(int firstBoundary) {
            GL20.glUniform1i(firstBoundaryUniform, firstBoundary);
        }

        /**
         * Sets the uniforms of the rays of the self test in place of the frame's grid: the 64 rays as the columns of a
         * single row, the depth slices of the run, and the first boundary at 1. The range, the divisor and the rest of
         * the frame's uniforms stay as setFrameUniforms set them.
         * @param sliceCount the depth slices of a ray of the run, from FogFroxelGrid.MINIMUM_SLICES to
         *        FogFroxelGrid.MAXIMUM_SLICES
         */
        private void setSelfTestUniforms(int sliceCount) {
            GL20.glUniform1i(selfTestUniform, 1);
            GL20.glUniform1i(columnCountUniform, SELF_TEST_RAY_COUNT);
            GL20.glUniform1i(rowCountUniform, 1);
            GL20.glUniform1i(sliceCountUniform, sliceCount);
            GL20.glUniform1i(firstBoundaryUniform, 1);
            GL20.glUniform3fv(selfTestOffsetsUniform, selfTestOffsetBuffer);
        }
    }

    /**
     * The program of the composite, weather_fog.glsl and snow_walk.glsl followed by weather_fog_composite.fsh, and the
     * uniforms that only it has: the snow fog color, the debug view, the froxel grid and the self test.
     */
    private static final class CompositeProgram extends FogProgramBase {
        private int snowColorUniform;
        private int debugModeUniform;
        private int columnCountUniform;
        private int rowCountUniform;
        private int sliceCountUniform;
        private int rangeDistanceUniform;
        private int divisorUniform;
        private int selfTestUniform;
        private int selfTestOffsetsUniform;

        @Override
        protected Shader loadFragment() {
            return FHShaders.getWeatherFogComposite();
        }

        @Override
        protected void onLinked(ShaderProgram linked) {
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "sceneDepth"), SCENE_DEPTH_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "snowField"), FIELD_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "farHeights"), HEIGHT_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "shareTable"), SHARE_TABLE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "fogStructure"), STRUCTURE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "excesses"), EXCESS_UNIT);
            snowColorUniform = GL20.glGetUniformLocation(linked.programId, "snowColor");
            debugModeUniform = GL20.glGetUniformLocation(linked.programId, "debugMode");
            columnCountUniform = GL20.glGetUniformLocation(linked.programId, "columnCount");
            rowCountUniform = GL20.glGetUniformLocation(linked.programId, "rowCount");
            sliceCountUniform = GL20.glGetUniformLocation(linked.programId, "sliceCount");
            rangeDistanceUniform = GL20.glGetUniformLocation(linked.programId, "rangeDistance");
            divisorUniform = GL20.glGetUniformLocation(linked.programId, "divisor");
            selfTestUniform = GL20.glGetUniformLocation(linked.programId, "selfTest");
            selfTestOffsetsUniform = GL20.glGetUniformLocation(linked.programId, "selfTestOffsets");
        }

        @Override
        protected void setOwnUniforms(RenderTarget mainTarget) {
            GL20.glUniform3f(snowColorUniform, snowColor[0], snowColor[1], snowColor[2]);
            GL20.glUniform1i(debugModeUniform, debugMode);
            setGridUniforms(columnCountUniform, rowCountUniform, sliceCountUniform, rangeDistanceUniform,
                    divisorUniform);
            GL20.glUniform1i(selfTestUniform, 0);
        }

        /**
         * Sets the uniforms of the rays of the self test in place of the frame's grid: the 64 rays as the columns of a
         * single row and the depth slices of the run. The range, the divisor and the rest of the frame's uniforms stay
         * as setFrameUniforms set them, since the composite reads the last excess of each ray at the boundary of the
         * run's slice count.
         * @param sliceCount the depth slices of a ray of the run, from FogFroxelGrid.MINIMUM_SLICES to
         *        FogFroxelGrid.MAXIMUM_SLICES
         */
        private void setSelfTestUniforms(int sliceCount) {
            GL20.glUniform1i(selfTestUniform, 1);
            GL20.glUniform1i(columnCountUniform, SELF_TEST_RAY_COUNT);
            GL20.glUniform1i(rowCountUniform, 1);
            GL20.glUniform1i(sliceCountUniform, sliceCount);
            GL20.glUniform3fv(selfTestOffsetsUniform, selfTestOffsetBuffer);
        }
    }

    /**
     * The program of the share volume, weather_fog.glsl and snow_walk.glsl followed by weather_fog_share.fsh, which
     * writes the direct share of every cell of the region cube into the atlas that the walk of the same frame reads.
     */
    private static final class ShareProgram extends FogProgramBase {

        @Override
        protected Shader loadFragment() {
            return FHShaders.getWeatherFogShare();
        }

        @Override
        protected void onLinked(ShaderProgram linked) {
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "snowField"), FIELD_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "farHeights"), HEIGHT_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "shareTable"), SHARE_TABLE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "fogStructure"), STRUCTURE_UNIT);
        }

        @Override
        protected void setOwnUniforms(RenderTarget mainTarget) {
            setShareVolumeInUse(false);
        }
    }
}
