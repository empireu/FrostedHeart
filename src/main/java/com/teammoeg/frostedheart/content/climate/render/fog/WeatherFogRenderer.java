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
import org.lwjgl.opengl.GL15;
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
 * the particles, the clouds and the weather, in frames where the calculated sky was drawn. One full screen pass reads
 * the depth of the main target, rebuilds the scene point of each pixel from it, and blends a fog color over the pixel
 * by the opacity that the height fog of HeightFog gives along the segment from the eye to that point, as
 * weather_fog.glsl and weather_fog.fsh compute it. The fog shortens the visibility in the snow and the blizzard, thins
 * out with height by the fog height scale, and stands as far as the settings say at every render distance.
 * <p>
 * A pixel takes the fog of the snow along the segment from the eye to its scene point: the share of each cell near the
 * eye, which the snow field's distance to open sky gives, blended toward the share of the column's motion height over
 * the last blocks before the region's edge, and beyond the region the fog of the open air up to the stretch that runs
 * under cover, which a backward walk over the columns under the segment finds, as weather_fog.glsl, snow_walk.glsl and
 * weather_fog.fsh walk it. A room therefore stays clear where the whole segment from the eye to the scene point stays
 * under its roof, and a porch holds the fog back by the first open air under it. The field's share fades in over half a
 * second after a fill filled the shown box, so after a jump partial cover gains its fog once and smoothly instead of
 * along a moving edge. The fog color is the light on a white face at full sky light, which the renderer reads from the
 * lightmap every frame, so the fog, the terrain and the sky follow one light. A pixel of the sky has no scene point and
 * takes the same snow fog along its ray to infinity, so the sky converges to the color of the snow that hides the
 * distant terrain, and nothing behind the fog can come out brighter than the fog in front of it. Vanilla's terrain fog
 * ends where the fog is already opaque, so that the terrain beyond it is culled, which it does only while the eye stands
 * in open air of the field, and vanilla's fog color turns to the same light, which the sky shader's rain mix and
 * horizon fade, the clouds and the culled terrain take. Everything that draws after the pass until the end of the
 * weather stage, which is the particles, the clouds and the weather, gets vanilla's fog fitted to the fog at the eye, and
 * the clouds get the cloud fit of fitCloudFog, which fades them by the fog from the eye to their layer. Render thread
 * only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WeatherFogRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The scene depth goes to texture unit 0 while the fog draws, the share volume to unit 1, the snow field to unit 2,
     * the far heights to unit 3, the share table to unit 4, the structures of the fog to unit 5, and the low-resolution
     * target of the composite to unit 6. Unit 1 is free because no other pass of the fog draws to it, and SkyGlPass
     * keeps the 2D binding of every unit up to 6, so the binding of the frame before comes back after the passes.
     */
    private static final int SCENE_DEPTH_UNIT = 0;
    private static final int SHARE_VOLUME_UNIT = 1;
    private static final int FIELD_UNIT = 2;
    private static final int HEIGHT_UNIT = 3;
    private static final int SHARE_TABLE_UNIT = 4;
    private static final int STRUCTURE_UNIT = 5;
    private static final int LOW_RESOLUTION_UNIT = 6;
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
     * The tolerance on the walk counts of the self test, within which a column or a cell may differ by float ties.
     */
    private static final double SELF_TEST_WALK_TOLERANCE = 1.0e-3;
    /**
     * The modes of the self test: 1 walks the ray of each texel to its scene point, and 2 walks its sky to infinity past
     * the structure range.
     */
    private static final int SELF_TEST_MODE_WALK = 1;
    private static final int SELF_TEST_MODE_SKY = 2;
    /**
     * The share of the walked depth within which the self test's rays must match the walk while the structures are on,
     * since the filtered reads of the GPU round their weights and the profile steepens the error.
     */
    private static final double SELF_TEST_STRUCTURE_TOLERANCE_SHARE = 0.02;

    private static final ClientMockWeather mockWeather = new ClientMockWeather();
    private static final SkyDrawTimer timer = new SkyDrawTimer();
    private static final SkyGlPass pass = new SkyGlPass(7);
    private static final FogProgram fogPass = new FogProgram();
    private static final UpsampleProgram upsamplePass = new UpsampleProgram();
    private static final ShareProgram sharePass = new ShareProgram();
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
     * The walk that the self test compares with the shader, which keeps the references of the last frame.
     */
    private static final SnowFogWalk selfTestWalk = new SnowFogWalk();

    @Nullable
    private static ClientLevel keptFrameLevel;
    private static int shareTableTexture;
    private static int structureTexture;
    private static boolean structureTextureBuilt;
    private static int selfTestTexture;
    private static int selfTestFramebuffer;
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
     * The low-resolution target of the walk, its framebuffer, and the size it was made or last sized at.
     */
    private static int lowResolutionTexture;
    private static int lowResolutionFramebuffer;
    private static int lowResolutionWidth;
    private static int lowResolutionHeight;
    /**
     * The queries that count the pixels of the fallback, used in turn, whether each has run, and the index of the query
     * that the next frame writes.
     */
    private static final int[] fallbackQueries = new int[2];
    private static final boolean[] fallbackQueryUsed = new boolean[2];
    private static int nextFallbackQuery;
    /**
     * The share of the screen that fell back in the last frame, which is NaN before the first count, and whether the
     * fallback is allowed.
     */
    private static double fallbackShare = Double.NaN;
    private static boolean fallbackAllowed = true;
    /**
     * When the wind last advanced.
     */
    private static long lastFrameNanos;
    /**
     * The divisor of the resolution that the fog was walked at in the last frame, and the size of its low-resolution
     * target.
     */
    private static int frameDivisor = 1;
    private static int frameLowWidth;
    private static int frameLowHeight;
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
        frameDivisor = FogUpsample.divisorOf(SkySetting.FOG_RESOLUTION_DIVISOR.get());
        frameLowWidth = FogUpsample.lowResolutionSize(mainTarget.width, frameDivisor);
        frameLowHeight = FogUpsample.lowResolutionSize(mainTarget.height, frameDivisor);
        timer.begin();
        try {
            draw(mainTarget);
        } finally {
            timer.end();
        }
        drawnThisFrame = true;
        keptFrameLevel = level;
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
     * Draws the fog over the frame in three passes: the share of every cell of the region cube into its volume, the walk
     * into the low-resolution target, and the composite over the frame, whose second part walks the pixels that fall back
     * at full resolution inside the query that counts them. The depth of the main target is read through a color only
     * framebuffer, so that its depth texture is not attached to what it draws to. Every piece of state the pass changes
     * is set back after it, and the framebuffer holds no texture of the main target once it is released.
     */
    private static void draw(RenderTarget mainTarget) {
        pass.begin();
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int sourceColor = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationColor = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int colorEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int alphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
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
        try {
            updateStructureTexture();
            updateFallbackShare(mainTarget);
            RenderSystem.disableCull();
            RenderSystem.disableScissor();
            RenderSystem.disableBlend();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_UNIT);
            RenderSystem.bindTexture(mainTarget.getDepthTextureId());
            bindFieldTextures();
            boolean shareVolumeBound = prepareShareVolume();
            if (shareVolumeBound) {
                drawShareVolume(mainTarget);
                bindShareVolume();
            }
            bindLowResolutionFramebuffer(mainTarget);
            fogPass.use();
            fogPass.setFrameUniforms(mainTarget);
            fogPass.setShareVolumeInUse(shareVolumeBound);
            SkyGlPass.drawFullScreenQuad();
            bindColorFramebuffer(mainTarget);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + LOW_RESOLUTION_UNIT);
            RenderSystem.bindTexture(lowResolutionTexture);
            upsamplePass.use();
            upsamplePass.setFrameUniforms(mainTarget);
            upsamplePass.setShareVolumeInUse(shareVolumeBound);
            upsamplePass.setPart(0);
            SkyGlPass.drawFullScreenQuad();
            int queryIndex = nextFallbackQuery & 1;
            int query = fallbackQuery(queryIndex);
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
            try {
                upsamplePass.setPart(1);
                SkyGlPass.drawFullScreenQuad();
            } finally {
                GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            }
            fallbackQueryUsed[queryIndex] = true;
            nextFallbackQuery++;
        } finally {
            GL20.glBlendEquationSeparate(colorEquation, alphaEquation);
            RenderSystem.blendFuncSeparate(sourceColor, destinationColor, sourceAlpha, destinationAlpha);
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
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            releaseColorFramebuffer(previousFramebuffer);
            pass.end();
        }
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
     * Reads the result of the query of the frame before, which counts the pixels that fell back, if it has one, and
     * lets the fallback follow the cap from the share it gives. The read never blocks, since the result of the frame
     * before is complete.
     */
    private static void updateFallbackShare(RenderTarget mainTarget) {
        int readIndex = (nextFallbackQuery + 1) & 1;
        if (!fallbackQueryUsed[readIndex]) {
            return;
        }
        int query = fallbackQueries[readIndex];
        if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            return;
        }
        int samples = GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT);
        fallbackShare = (double) samples / ((double) mainTarget.width * (double) mainTarget.height);
        fallbackAllowed = FogUpsample.fallbackAllowed(fallbackAllowed, fallbackShare,
                SkySetting.FOG_FALLBACK_CAP.get());
    }

    /**
     * The query of the given index, made on first use.
     */
    private static int fallbackQuery(int index) {
        if (fallbackQueries[index] == 0) {
            fallbackQueries[index] = GL15.glGenQueries();
        }
        return fallbackQueries[index];
    }

    /**
     * Binds the low-resolution target for drawing, making it first if needed and giving it the size of the frame when
     * the frame or the divisor changed, and sets its viewport.
     */
    private static void bindLowResolutionFramebuffer(RenderTarget mainTarget) {
        boolean allocate = lowResolutionFramebuffer == 0;
        if (allocate) {
            lowResolutionFramebuffer = GL30.glGenFramebuffers();
            lowResolutionTexture = GL11.glGenTextures();
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowResolutionTexture);
            if (allocate) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            if (allocate || lowResolutionWidth != frameLowWidth || lowResolutionHeight != frameLowHeight) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, frameLowWidth, frameLowHeight, 0, GL30.GL_RG,
                        GL11.GL_FLOAT, (FloatBuffer) null);
                lowResolutionWidth = frameLowWidth;
                lowResolutionHeight = frameLowHeight;
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, lowResolutionFramebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                lowResolutionTexture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("The weather fog low-resolution framebuffer is incomplete with status "
                    + status);
        }
        RenderSystem.viewport(0, 0, frameLowWidth, frameLowHeight);
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
     * @param mode one of off, field, cells, noregion, nofar, analytic, transmittance, fallback and structure
     * @return a line saying what the fog shows from now on
     */
    public static String setDebugMode(String mode) {
        switch (mode) {
            case "field":
                debugMode = 1;
                partMask = 3;
                return "Fog debug mode field: each pixel shows the snow share of the field a quarter block in front of "
                        + "its surface, and beyond the region the far rule, purple under cover and gray in the open";
            case "cells":
                debugMode = 2;
                partMask = 3;
                return String.format(Locale.ROOT, "Fog debug mode cells: red is the cells walked over %d and green "
                                + "the columns walked over %d", SnowFogWalk.MAXIMUM_CELL_STEPS,
                        SnowFogWalk.FAR_WALK_COLUMNS);
            case "noregion":
                debugMode = 0;
                partMask = 2;
                return "Fog timing mode noregion: the region part takes the analytic fog, so the difference of its "
                        + "draw time from the draw time of off is the cost of the cell walk";
            case "nofar":
                debugMode = 0;
                partMask = 1;
                return "Fog timing mode nofar: the far part counts as open, so the difference of its draw time from "
                        + "the draw time of off is the cost of the backward walk";
            case "analytic":
                debugMode = 0;
                partMask = 0;
                return "Fog timing mode analytic: both parts count as open air, which is the fog without the field";
            case "transmittance":
                debugMode = 3;
                partMask = 3;
                return "Fog debug mode transmittance: each pixel shows the transmittance of its fog as opaque gray, "
                        + "white where nothing hides, with the pixels that fell back walked at full resolution";
            case "fallback":
                debugMode = 4;
                partMask = 3;
                return "Fog debug mode fallback: the normal fog, with the pixels that fell back drawn opaque red when "
                        + "they walk at full resolution and orange when the cap takes the nearest sample instead";
            case "structure":
                debugMode = 5;
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
        String fallbackShareText = Double.isNaN(fallbackShare) ? "none"
                : String.format(Locale.ROOT, "%.3f", fallbackShare);
        double windDegrees = Math.toDegrees(wind.direction()) % 360.0;
        if (windDegrees < 0.0) {
            windDegrees += 360.0;
        }
        return String.format(Locale.ROOT, "Weather fog %s in the last frame; %s; eye extinction %.5f per block, "
                        + "visibility %.1f blocks, fitted fog end %s; reference extinction %.5f per block, height "
                        + "scale %.1f blocks; %s; %s, decay length %.2f blocks; R %.1f blocks, region half width %.1f "
                        + "blocks, fade %.2f, %s, %d of the %d cells of the region cube inside it, lag %d blocks, %s; "
                        + "divisor %d at %d by %d texels, fallback share %s %s; %s; wind %.1f blocks per second toward "
                        + "%.0f degrees, displacement (%.1f, %.1f) blocks%s",
                drawn, timer.describe(), eyeExtinction, visibility, fittedEnd, weather().referenceExtinction(),
                weather().heightScale(), describeSnowColor(), describeEyeShare(), weather().decayLength(), frameRadius,
                frameHalfWidth, frameFieldFade, hasShownBox ? "a shown box" : "no shown box", insideCells, regionCells,
                lag, describeShareVolume(), frameDivisor, frameLowWidth, frameLowHeight, fallbackShareText,
                fallbackAllowed ? "allowed" : "capped", describeStructures(visibility), wind.speed(),
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
     * The lines of the self test of the client command: it draws 64 fixed rays with the fog program in self test mode
     * into a 64 by 1 float target, walks them with SnowFogWalk on the same inputs, and compares the two, once to the
     * scene point of each ray and once along its sky to infinity. Only a command calls it, so it allocates its readback
     * buffer and its lines.
     * @return the lines to send, which report the rays outside the tolerance on the optical depth, the largest error
     *         with its ray, the number of rays whose walk counts differ, and the sky rays outside the tolerance
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
     * Walks the rays of the self test on the GPU and with SnowFogWalk and adds the comparison to the given lines.
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
        FloatBuffer readback = BufferUtils.createFloatBuffer(SELF_TEST_RAY_COUNT * SELF_TEST_VALUES_PER_RAY);
        drawSelfTest(mainTarget, readback, SELF_TEST_MODE_WALK);
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
        boolean structureOn = structure.isOn();
        int failedCount = 0;
        int walkDifferingCount = 0;
        double largestError = 0.0;
        int largestErrorRay = 0;
        for (int ray = 0; ray < SELF_TEST_RAY_COUNT; ray++) {
            float gpuDepth = readback.get(ray * SELF_TEST_VALUES_PER_RAY);
            float gpuCells = readback.get(ray * SELF_TEST_VALUES_PER_RAY + 1);
            float gpuColumns = readback.get(ray * SELF_TEST_VALUES_PER_RAY + 2);
            float gpuFarStop = readback.get(ray * SELF_TEST_VALUES_PER_RAY + 3);
            double walkDepth = selfTestWalk.opticalDepth(selfTestOffsets[ray * 3], selfTestOffsets[ray * 3 + 1],
                    selfTestOffsets[ray * 3 + 2]);
            double allowed = 1.0e-3 + 1.0e-3 * Math.abs(walkDepth);
            if (structureOn) {
                allowed = Math.max(allowed, SELF_TEST_STRUCTURE_TOLERANCE_SHARE * Math.abs(walkDepth));
            }
            double error = Math.abs(gpuDepth - walkDepth);
            if (error > largestError) {
                largestError = error;
                largestErrorRay = ray;
            }
            if (error > allowed) {
                failedCount++;
                lines.add(String.format(Locale.ROOT, "The ray %d at (%.2f, %.2f, %.2f) has the depth %.6f on the GPU "
                                + "instead of %.6f in the walk, an error of %.6f above %.6f", ray,
                        selfTestOffsets[ray * 3], selfTestOffsets[ray * 3 + 1], selfTestOffsets[ray * 3 + 2], gpuDepth,
                        walkDepth, error, allowed));
            }
            if (gpuCells != selfTestWalk.lastCellSteps() || gpuColumns != selfTestWalk.lastColumnSteps()
                    || Math.abs(gpuFarStop - selfTestWalk.lastFarStop()) > SELF_TEST_WALK_TOLERANCE) {
                walkDifferingCount++;
            }
        }
        lines.add(String.format(Locale.ROOT, "The self test compared %d rays, %d of them outside the tolerance on the "
                        + "depth, the largest error %.6f at the ray %d, %s", SELF_TEST_RAY_COUNT, failedCount,
                largestError, largestErrorRay, structureOn ? "with the structures on" : "with the structures off"));
        lines.add(String.format(Locale.ROOT, "%d rays differ in the cells walked, the columns walked or the far stop, "
                + "which a float tie can change by one without failing", walkDifferingCount));
        double structureRange = structure.range();
        drawSelfTest(mainTarget, readback, SELF_TEST_MODE_SKY);
        int skyFailedCount = 0;
        double largestSkyError = 0.0;
        int largestSkyErrorRay = 0;
        for (int ray = 0; ray < SELF_TEST_RAY_COUNT; ray++) {
            float gpuSkyDepth = readback.get(ray * SELF_TEST_VALUES_PER_RAY);
            double walkSkyDepth = selfTestWalk.skyOpticalDepth(selfTestOffsets[ray * 3], selfTestOffsets[ray * 3 + 1],
                    selfTestOffsets[ray * 3 + 2], structureRange);
            double allowed = SELF_TEST_WALK_TOLERANCE + SELF_TEST_WALK_TOLERANCE * Math.abs(walkSkyDepth);
            if (structureOn) {
                allowed = Math.max(allowed, SELF_TEST_STRUCTURE_TOLERANCE_SHARE * Math.abs(walkSkyDepth));
            }
            double error = Math.abs(gpuSkyDepth - walkSkyDepth);
            if (error > largestSkyError) {
                largestSkyError = error;
                largestSkyErrorRay = ray;
            }
            if (error > allowed) {
                skyFailedCount++;
                lines.add(String.format(Locale.ROOT, "The sky ray %d at (%.2f, %.2f, %.2f) has the depth %.6f on the "
                                + "GPU instead of %.6f in the walk, an error of %.6f above %.6f", ray,
                        selfTestOffsets[ray * 3], selfTestOffsets[ray * 3 + 1], selfTestOffsets[ray * 3 + 2],
                        gpuSkyDepth, walkSkyDepth, error, allowed));
            }
        }
        lines.add(String.format(Locale.ROOT, "The self test compared %d sky rays over the structure range %.0f blocks, "
                        + "%d sky rays outside the tolerance, the largest error %.6f at the ray %d, %s",
                SELF_TEST_RAY_COUNT, structureRange, skyFailedCount, largestSkyError, largestSkyErrorRay,
                structureOn ? "with the structures on" : "with the structures off"));
    }

    /**
     * Fills the rays of the self test: 48 of them along a golden angle spiral, whose lengths cycle through five values up to 300 blocks,
     * and 16 fixed ones along the axes and through the diagonals at lengths from 20 to 120 blocks.
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
     * Draws the rays of the self test into its target and reads them back. It binds the same textures as the normal
     * draw, and fills the share volume again, so that its rays read the volume of the uniforms and the textures that the
     * walk on the CPU reads. Every piece of state it changes is set back after it.
     * @param mode one of SELF_TEST_MODE_WALK and SELF_TEST_MODE_SKY, which the program answers in place of the fog
     */
    private static void drawSelfTest(RenderTarget mainTarget, FloatBuffer readback, int mode) {
        createSelfTestTarget();
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
        try {
            updateStructureTexture();
            RenderSystem.disableScissor();
            RenderSystem.disableCull();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, selfTestFramebuffer);
            RenderSystem.viewport(0, 0, SELF_TEST_RAY_COUNT, 1);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_UNIT);
            RenderSystem.bindTexture(mainTarget.getDepthTextureId());
            bindFieldTextures();
            boolean shareVolumeBound = prepareShareVolume();
            if (shareVolumeBound) {
                drawShareVolume(mainTarget);
                bindShareVolume();
            }
            fogPass.use();
            fogPass.setFrameUniforms(mainTarget);
            fogPass.setShareVolumeInUse(shareVolumeBound);
            fogPass.setSelfTestUniforms(mode);
            SkyGlPass.drawFullScreenQuad();
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL12.GL_PACK_IMAGE_HEIGHT, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_PACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            readback.clear();
            GL11.glReadPixels(0, 0, SELF_TEST_RAY_COUNT, 1, GL11.GL_RGBA, GL11.GL_FLOAT, readback);
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
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            pass.end();
        }
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
     * The locations of the uniforms of the scene and the walk that every fog program shares, the program itself, and
     * the setting of them every frame. A program is made again when a resource reload replaced its stage, and its
     * samplers are set once after it links.
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
         * and a fraction, the region, the fade, which parts of the walk run, and the structures of the fog, whose share
         * is 0 while they are off. The eye's block goes as three ints, so no world coordinate passes through a float.
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
     * The program of the low-resolution walk, weather_fog.glsl followed by snow_walk.glsl and weather_fog.fsh, and the
     * uniforms that only it has: the divisor, the size of the full-resolution target, and the self test.
     */
    private static final class FogProgram extends FogProgramBase {
        private int divisorUniform;
        private int fullSizeUniform;
        private int selfTestUniform;
        private int selfTestOffsetsUniform;

        @Override
        protected Shader loadFragment() {
            return FHShaders.getWeatherFog();
        }

        @Override
        protected void onLinked(ShaderProgram linked) {
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "sceneDepth"), SCENE_DEPTH_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "snowField"), FIELD_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "farHeights"), HEIGHT_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "shareTable"), SHARE_TABLE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "fogStructure"), STRUCTURE_UNIT);
            divisorUniform = GL20.glGetUniformLocation(linked.programId, "divisor");
            fullSizeUniform = GL20.glGetUniformLocation(linked.programId, "fullSize");
            selfTestUniform = GL20.glGetUniformLocation(linked.programId, "selfTest");
            selfTestOffsetsUniform = GL20.glGetUniformLocation(linked.programId, "selfTestOffsets");
        }

        @Override
        protected void setOwnUniforms(RenderTarget mainTarget) {
            GL20.glUniform1i(divisorUniform, frameDivisor);
            GL20.glUniform2i(fullSizeUniform, mainTarget.width, mainTarget.height);
            GL20.glUniform1i(selfTestUniform, 0);
        }

        /**
         * Sets the offsets of the rays of the self test and the mode that the following draw answers them in, which
         * walks every part of the segment since that is what it compares.
         */
        private void setSelfTestUniforms(int mode) {
            GL20.glUniform1i(selfTestUniform, mode);
            GL20.glUniform1i(partMaskUniform, 3);
            GL20.glUniform3fv(selfTestOffsetsUniform, selfTestOffsetBuffer);
        }
    }

    /**
     * The program of the composite, weather_fog.glsl and snow_walk.glsl followed by weather_fog_upsample.fsh, and the
     * uniforms that only it has: the snow fog color, the debug view, the low-resolution target, and which part of the
     * fog the draw covers.
     */
    private static final class UpsampleProgram extends FogProgramBase {
        private int snowColorUniform;
        private int debugModeUniform;
        private int lowSizeUniform;
        private int divisorUniform;
        private int compositePartUniform;
        private int fallbackAllowedUniform;

        @Override
        protected Shader loadFragment() {
            return FHShaders.getWeatherFogUpsample();
        }

        @Override
        protected void onLinked(ShaderProgram linked) {
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "sceneDepth"), SCENE_DEPTH_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "snowField"), FIELD_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "farHeights"), HEIGHT_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "shareTable"), SHARE_TABLE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "fogStructure"), STRUCTURE_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked.programId, "lowResolution"), LOW_RESOLUTION_UNIT);
            snowColorUniform = GL20.glGetUniformLocation(linked.programId, "snowColor");
            debugModeUniform = GL20.glGetUniformLocation(linked.programId, "debugMode");
            lowSizeUniform = GL20.glGetUniformLocation(linked.programId, "lowSize");
            divisorUniform = GL20.glGetUniformLocation(linked.programId, "divisor");
            compositePartUniform = GL20.glGetUniformLocation(linked.programId, "compositePart");
            fallbackAllowedUniform = GL20.glGetUniformLocation(linked.programId, "fallbackAllowed");
        }

        @Override
        protected void setOwnUniforms(RenderTarget mainTarget) {
            GL20.glUniform3f(snowColorUniform, snowColor[0], snowColor[1], snowColor[2]);
            GL20.glUniform1i(debugModeUniform, debugMode);
            GL20.glUniform2i(lowSizeUniform, frameLowWidth, frameLowHeight);
            GL20.glUniform1i(divisorUniform, frameDivisor);
        }

        /**
         * Sets which part of the fog the following draw covers, and whether the fallback is allowed.
         */
        private void setPart(int part) {
            GL20.glUniform1i(compositePartUniform, part);
            GL20.glUniform1i(fallbackAllowedUniform, fallbackAllowed ? 1 : 0);
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
