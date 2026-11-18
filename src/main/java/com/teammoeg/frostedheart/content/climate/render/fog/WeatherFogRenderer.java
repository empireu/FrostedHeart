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
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.CalculatedSkyRenderer;
import com.teammoeg.frostedheart.content.climate.render.sky.DustColumnWindow;
import com.teammoeg.frostedheart.content.climate.render.sky.DustColumns;
import com.teammoeg.frostedheart.content.climate.render.sky.LightPillarSources;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyDrawTimer;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import com.teammoeg.frostedheart.infrastructure.config.FHConfig;
import com.teammoeg.frostedheart.mixin.client.LevelRendererAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
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
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Draws the fog of snow and blizzards over the frame after the weather, in frames where the calculated sky was drawn. One
 * full screen pass reads the depth of the main target, rebuilds the scene point of each pixel from it, and blends a fog
 * color over the pixel by the opacity that the height fog of HeightFog gives along the segment from the eye to that
 * point, as weather_fog.glsl and weather_fog.fsh compute it. The fog shortens the visibility in the snow and the
 * blizzard, thins out with height by the fog height scale, and stands as far as the settings say at every render
 * distance.
 * <p>
 * A pixel takes the fog of the open part of the segment from the eye to its scene point, which the column heights of
 * DustColumns tell along the way, so a ray that leaves a room by a window or a door takes the fog of the open air it
 * crosses, and the fog fades in and out as the eye goes in and out. The walk along those columns runs only while the eye
 * is not fully open, so it costs nothing outdoors. The fog color is the sky's horizon color toward the pixel, mixed
 * toward the day and night snow fog color by the snowfall intensity, so the fog matches the sky it goes into. Vanilla's
 * terrain fog ends where the fog is already opaque, so that the terrain beyond it is culled, vanilla's fog color turns
 * toward the snow fog color, and vanilla's snow is drawn again after the fog so that the flakes stand in it. Render
 * thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class WeatherFogRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The scene depth goes to texture unit 0 while the fog draws, the horizon colors to unit 1, and the column heights
     * to unit 2.
     */
    private static final int SCENE_DEPTH_UNIT = 0;
    private static final int HORIZON_UNIT = 1;
    private static final int COLUMN_UNIT = 2;
    /**
     * The texels around the horizon of the horizon color texture, which CalculatedSkyRenderer.horizonColors fills.
     */
    private static final int HORIZON_TEXELS = 64;
    private static final int COLOR_CHANNELS = 3;
    /**
     * The longest step in seconds that the eye's openness moves in one frame, so that a stalled frame does not jump it.
     */
    private static final double LONGEST_OPENNESS_STEP_SECONDS = 0.1;

    private static final ClientMockWeather mockWeather = new ClientMockWeather();
    private static final SkyDrawTimer timer = new SkyDrawTimer();
    private static final SkyGlPass pass = new SkyGlPass(3);
    private static final FogProgram fogPass = new FogProgram();
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final Vector4f eyeColumn = new Vector4f();
    private static final Vector3f eyeOffset = new Vector3f();
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer horizonBuffer = BufferUtils.createFloatBuffer(HORIZON_TEXELS * COLOR_CHANNELS);
    private static final float[] horizonColors = new float[HORIZON_TEXELS * COLOR_CHANNELS];
    private static final float[] snowColor = new float[COLOR_CHANNELS];

    @Nullable
    private static ClientLevel opennessLevel;
    private static int horizonTexture;
    /**
     * A framebuffer that the fog draws through, holding the main target's color only while it draws. Without the main
     * target's depth attached, the fragment stage can read that depth.
     */
    private static int colorFramebuffer;
    private static double eyeExtinction;
    private static double eyeOpenness;
    private static long lastOpennessNanos;
    private static boolean failed;
    private static boolean drawnThisFrame;
    /**
     * The light texture and camera position of vanilla's snow of this frame, which the fog draws again after the
     * weather, and the flags that tell the mixin to keep that request and the redraw to make it.
     */
    @Nullable
    private static LightTexture deferredLightTexture;
    private static float deferredPartialTick;
    private static double deferredCameraX;
    private static double deferredCameraY;
    private static double deferredCameraZ;
    private static boolean snowDeferred;
    private static boolean redrawingSnow;
    /**
     * The opaque distance, the fog end, the far plane it was found for, and whether vanilla's fog takes the end, for
     * the client command.
     */
    private static double boundDistance = Double.POSITIVE_INFINITY;
    private static double boundEnd = Double.POSITIVE_INFINITY;
    private static float boundFarPlane;
    private static boolean boundApplied;
    /**
     * The fog end of the frame before, which FogCullingDistance keeps while the distance stays close to it.
     */
    private static double previousEnd = Double.POSITIVE_INFINITY;
    /**
     * The six inputs of the last culling distance search and its result, which is searched again only when one of them
     * differs, since the search costs about 1900 evaluations of the height fog.
     */
    private static boolean hasCachedDistance;
    private static double cachedEyeExtinction;
    private static double cachedEyeY;
    private static double cachedHeightScale;
    private static double cachedMinimumHeight;
    private static double cachedMaximumHeight;
    private static double cachedFarPlane;
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
        mockWeather.update(level.getRainLevel(partialTick), level.getThunderLevel(partialTick),
                SkySetting.FOG_SNOW_VISIBILITY.get(), SkySetting.FOG_BLIZZARD_VISIBILITY.get(),
                SkySetting.FOG_HEIGHT_SCALE.get());
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
     * Whether vanilla's snow is drawn later, after the fog, in frames where the new fog owns the snow fog. The mixin
     * then calls deferSnow with the arguments of vanilla's call instead, and the fog draws the snow again once it is
     * over.
     */
    public static boolean defersSnow() {
        if (redrawingSnow) {
            return false;
        }
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && isActive(level);
    }

    /**
     * Keeps the arguments of vanilla's snow for the fog to draw it again at the end of the weather stage.
     */
    public static void deferSnow(LightTexture lightTexture, float partialTick, double cameraX, double cameraY,
            double cameraZ) {
        deferredLightTexture = lightTexture;
        deferredPartialTick = partialTick;
        deferredCameraX = cameraX;
        deferredCameraY = cameraY;
        deferredCameraZ = cameraZ;
        snowDeferred = true;
    }

    /**
     * Draws the fog over the frame after the weather, in frames where the new fog owns the snow fog and the calculated
     * sky was drawn, and draws vanilla's snow again after it when the mixin kept it for this frame.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        drawnThisFrame = false;
        boolean redrawSnow = snowDeferred;
        snowDeferred = false;
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || !isActive(level) || !CalculatedSkyRenderer.isDrawnThisFrame()) {
                return;
            }
            render(level, event);
        } catch (RuntimeException exception) {
            stopAfterError(exception);
        } finally {
            frameAllocatedBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
            lastFrameAllocatedBytes = frameAllocatedBytes;
            frameAllocatedBytes = 0L;
            if (redrawSnow) {
                try {
                    redrawDeferredSnow();
                } catch (RuntimeException exception) {
                    stopAfterError(exception);
                }
            }
        }
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
        if (level == null || !isActive(level) || camera.getFluidInCamera() != FogType.NONE || eyeOpenness != 1.0) {
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
        boundDistance = cullingDistance(extinctionAtEye, eyeHeight, heightScale, level.getMinBuildHeight(),
                level.getMaxBuildHeight(), farPlane);
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
            double minimumHeight, double maximumHeight, double farPlane) {
        if (hasCachedDistance && extinctionAtEye == cachedEyeExtinction && eyeHeight == cachedEyeY
                && heightScale == cachedHeightScale && minimumHeight == cachedMinimumHeight
                && maximumHeight == cachedMaximumHeight && farPlane == cachedFarPlane) {
            return cachedDistance;
        }
        double distance = FogCullingDistance.opaqueDistance(extinctionAtEye, eyeHeight, heightScale, minimumHeight,
                maximumHeight, farPlane);
        hasCachedDistance = true;
        cachedEyeExtinction = extinctionAtEye;
        cachedEyeY = eyeHeight;
        cachedHeightScale = heightScale;
        cachedMinimumHeight = minimumHeight;
        cachedMaximumHeight = maximumHeight;
        cachedFarPlane = farPlane;
        cachedDistance = distance;
        return distance;
    }

    /**
     * Takes vanilla's fog color toward the day and night snow fog color by the snowfall intensity. The color that
     * FogRendererMixin set from the sky's horizon color in the look direction goes into the fog, and the sky shader's
     * horizon fade and the culled terrain then take it.
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
        double share = weather().snowfallIntensity();
        if (!(share > 0.0)) {
            return;
        }
        BlockPos cameraBlock = event.getCamera().getBlockPosition();
        if (!level.getBiome(cameraBlock).value().coldEnoughToSnow(cameraBlock)) {
            return;
        }
        updateSnowColor(level, (float) event.getPartialTick());
        event.setRed((float) Mth.lerp(share, (double) event.getRed(), snowColor[0]));
        event.setGreen((float) Mth.lerp(share, (double) event.getGreen(), snowColor[1]));
        event.setBlue((float) Mth.lerp(share, (double) event.getBlue(), snowColor[2]));
    }

    private static void render(ClientLevel level, RenderLevelStageEvent event) {
        float partialTick = event.getPartialTick();
        updateWeather(level, partialTick);
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
        updateOpenness(level, eyeX, eyeY, eyeZ);
        if (!CalculatedSkyRenderer.horizonColors(level, partialTick, horizonColors)) {
            return;
        }
        updateSnowColor(level, partialTick);
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        timer.begin();
        try {
            draw(mainTarget, eyeX, eyeY, eyeZ);
        } finally {
            timer.end();
        }
        drawnThisFrame = true;
    }

    /**
     * Moves the eye's openness toward whether the eye stands at or above the first open height of its column, by at most
     * the seconds since the last drawn frame and at most LONGEST_OPENNESS_STEP_SECONDS, so the fog fades in and out over
     * about a second. It starts at the target on the first frame and after a level change.
     */
    private static void updateOpenness(ClientLevel level, double eyeX, double eyeY, double eyeZ) {
        int firstOpenY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(eyeX), Mth.floor(eyeZ));
        double targetOpenness = eyeY >= firstOpenY ? 1.0 : 0.0;
        long now = System.nanoTime();
        if (level != opennessLevel || lastOpennessNanos == 0L) {
            opennessLevel = level;
            eyeOpenness = targetOpenness;
            lastOpennessNanos = now;
            return;
        }
        double step = Math.min(Math.max((now - lastOpennessNanos) * 1.0e-9, 0.0), LONGEST_OPENNESS_STEP_SECONDS);
        lastOpennessNanos = now;
        if (eyeOpenness < targetOpenness) {
            eyeOpenness = Math.min(targetOpenness, eyeOpenness + step);
        } else {
            eyeOpenness = Math.max(targetOpenness, eyeOpenness - step);
        }
    }

    /**
     * Computes the snow fog color of this frame into snowColor, the blend of the day and night fog colors by the sun
     * angle that FogModification.renderFogColors gives, as display sRGB from 0 to 1.
     */
    private static void updateSnowColor(ClientLevel level, float partialTick) {
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
     * Draws the fog over the frame, reading the depth of the main target through a color only framebuffer so that the
     * depth texture is not attached to what it draws to. Every piece of state the pass changes is set back after it, and
     * the framebuffer holds no texture of the main target once it is released.
     */
    private static void draw(RenderTarget mainTarget, double eyeX, double eyeY, double eyeZ) {
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
        int eyeColumnX = Mth.floor(eyeX);
        int eyeColumnZ = Mth.floor(eyeZ);
        boolean usesColumns = DustColumns.covers(eyeColumnX, eyeColumnZ);
        try {
            int columnHeights = DustColumns.texture();
            uploadHorizonColors();
            bindColorFramebuffer(mainTarget);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_UNIT);
            RenderSystem.bindTexture(mainTarget.getDepthTextureId());
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + HORIZON_UNIT);
            RenderSystem.bindTexture(horizonTexture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + COLUMN_UNIT);
            RenderSystem.bindTexture(columnHeights);
            fogPass.use();
            fogPass.setFrameUniforms(mainTarget, eyeX, eyeY, eyeZ, eyeColumnX, eyeColumnZ, usesColumns);
            SkyGlPass.drawFullScreenQuad();
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
            releaseColorFramebuffer(previousFramebuffer);
            pass.end();
        }
    }

    /**
     * Uploads the horizon colors of this frame into the horizon color texture, making it first if needed. The texture
     * bound before stays bound.
     */
    private static void uploadHorizonColors() {
        horizonBuffer.clear();
        horizonBuffer.put(horizonColors);
        horizonBuffer.flip();
        boolean allocate = horizonTexture == 0;
        int target = allocate ? GL11.glGenTextures() : horizonTexture;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            if (allocate) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F, HORIZON_TEXELS, 1, 0, GL11.GL_RGB,
                        GL11.GL_FLOAT, horizonBuffer);
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, HORIZON_TEXELS, 1, GL11.GL_RGB, GL11.GL_FLOAT,
                        horizonBuffer);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        horizonTexture = target;
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
     * Draws vanilla's snow again now that the fog is over, so that the flakes are not fogged as if they stood at the
     * scene behind them. It gives them the fog of the new fog at the eye when the fog was drawn, and sets the fog state
     * and the depth mask back after it.
     */
    private static void redrawDeferredSnow() {
        LightTexture lightTexture = deferredLightTexture;
        Minecraft minecraft = Minecraft.getInstance();
        if (lightTexture == null || minecraft.level == null) {
            return;
        }
        float previousFogStart = RenderSystem.getShaderFogStart();
        float previousFogEnd = RenderSystem.getShaderFogEnd();
        FogShape previousFogShape = RenderSystem.getShaderFogShape();
        boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        redrawingSnow = true;
        try {
            if (drawnThisFrame && eyeExtinction > 0.0) {
                RenderSystem.setShaderFogStart(0.0F);
                RenderSystem.setShaderFogEnd((float) (HeightFog.VISIBILITY_OPTICAL_DEPTH / eyeExtinction));
                RenderSystem.setShaderFogShape(FogShape.SPHERE);
            }
            RenderSystem.depthMask(false);
            ((LevelRendererAccess) minecraft.levelRenderer).frostedheart$renderSnowAndRain(lightTexture,
                    deferredPartialTick, deferredCameraX, deferredCameraY, deferredCameraZ);
        } finally {
            redrawingSnow = false;
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            RenderSystem.setShaderFogShape(previousFogShape);
            RenderSystem.depthMask(previousDepthMask);
        }
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
     * Clears the failure, so that the fog is drawn again from the next frame on.
     */
    public static void clearFailure() {
        failed = false;
    }

    /**
     * A line about the draw time of the fog, whether it was drawn in the last frame, the extinction at the eye and the
     * visibility it gives, the eye's openness, and the weather it was drawn from, for the client command.
     */
    public static String describe() {
        double visibility = eyeExtinction > 0.0 ? HeightFog.VISIBILITY_OPTICAL_DEPTH / eyeExtinction
                : Double.POSITIVE_INFINITY;
        String drawn = drawnThisFrame ? "drawn" : "not drawn";
        String stopped = failed ? "; stopped after an error, see the log" : "";
        return String.format(Locale.ROOT, "Weather fog %s in the last frame; %s; eye extinction %.5f per block, "
                + "visibility %.1f blocks, openness %.2f; reference extinction %.5f per block, height scale %.1f "
                + "blocks%s", drawn, timer.describe(), eyeExtinction, visibility, eyeOpenness,
                weather().referenceExtinction(), weather().heightScale(), stopped);
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
     * the fog's height scale, the eye's openness, and the bytes the fog's handlers allocated.
     */
    public static String describeBound() {
        String distance = Double.isFinite(boundDistance)
                ? String.format(Locale.ROOT, "%.1f blocks", boundDistance) : "none";
        String end = Double.isFinite(boundEnd)
                ? String.format(Locale.ROOT, "%.1f blocks", boundEnd) : "none";
        String farPlane = boundFarPlane > 0.0F
                ? String.format(Locale.ROOT, "%.1f blocks", boundFarPlane) : "none";
        double referenceExtinction = weather().referenceExtinction();
        String visibility = referenceExtinction > 0.0
                ? String.format(Locale.ROOT, "%.1f blocks", HeightFog.VISIBILITY_OPTICAL_DEPTH / referenceExtinction)
                : "none";
        String allocated = LightPillarSources.countsAllocations()
                ? String.format(Locale.ROOT, "%d bytes allocated", lastFrameAllocatedBytes)
                : "allocations not counted by this JVM";
        return String.format(Locale.ROOT, "Fog bound: opaque distance %s, fog end %s, far plane %s, %s; "
                        + "visibility %s at sea level, height scale %.1f blocks, openness %.2f; %s in the last frame",
                distance, end, farPlane, boundApplied ? "applied" : "not applied", visibility,
                weather().heightScale(), eyeOpenness, allocated);
    }

    /**
     * The program of FHShaders.IMAGE_V and weather_fog.fsh, and the locations of the uniforms that draw sets every
     * frame. It is made again when a resource reload replaced its stage, and its samplers are set once after it links.
     */
    private static final class FogProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int inverseViewProjectionUniform;
        private int screenSizeUniform;
        private int eyeExtinctionUniform;
        private int heightScaleUniform;
        private int snowColorUniform;
        private int snowShareUniform;
        private int windowOriginUniform;
        private int eyeInWindowUniform;
        private int eyeFractionUniform;
        private int eyeYUniform;
        private int useColumnsUniform;
        private int eyeOpennessUniform;

        /**
         * Uses the program, making it first if needed.
         */
        private void use() {
            Shader newFragment = FHShaders.getWeatherFog();
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
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "sceneDepth"), SCENE_DEPTH_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "horizonColors"), HORIZON_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "columnHeights"), COLUMN_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "windowSize"), DustColumnWindow.SIZE);
                inverseViewProjectionUniform = GL20.glGetUniformLocation(program.programId, "inverseViewProjection");
                screenSizeUniform = GL20.glGetUniformLocation(program.programId, "screenSize");
                eyeExtinctionUniform = GL20.glGetUniformLocation(program.programId, "eyeExtinction");
                heightScaleUniform = GL20.glGetUniformLocation(program.programId, "heightScale");
                snowColorUniform = GL20.glGetUniformLocation(program.programId, "snowColor");
                snowShareUniform = GL20.glGetUniformLocation(program.programId, "snowShare");
                windowOriginUniform = GL20.glGetUniformLocation(program.programId, "windowOrigin");
                eyeInWindowUniform = GL20.glGetUniformLocation(program.programId, "eyeInWindow");
                eyeFractionUniform = GL20.glGetUniformLocation(program.programId, "eyeFraction");
                eyeYUniform = GL20.glGetUniformLocation(program.programId, "eyeY");
                useColumnsUniform = GL20.glGetUniformLocation(program.programId, "useColumns");
                eyeOpennessUniform = GL20.glGetUniformLocation(program.programId, "eyeOpenness");
            }
            program.use();
        }

        /**
         * Sets the uniforms of this frame on the program in use: the inverse view projection, the size of the main
         * target, the extinction at the eye and the fog's height scale, the snow fog color and its share, the column
         * window of DustColumns, and the eye's openness.
         */
        private void setFrameUniforms(RenderTarget mainTarget, double eyeX, double eyeY, double eyeZ, int eyeColumnX,
                int eyeColumnZ, boolean usesColumns) {
            inverseViewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(inverseViewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(eyeExtinctionUniform, (float) eyeExtinction);
            GL20.glUniform1f(heightScaleUniform, (float) weather().heightScale());
            GL20.glUniform3f(snowColorUniform, snowColor[0], snowColor[1], snowColor[2]);
            GL20.glUniform1f(snowShareUniform, (float) weather().snowfallIntensity());
            int originX = DustColumns.originX();
            int originZ = DustColumns.originZ();
            GL20.glUniform2i(windowOriginUniform, DustColumnWindow.texelOfColumn(originX, 0),
                    DustColumnWindow.texelOfColumn(originZ, 0));
            GL20.glUniform2i(eyeInWindowUniform, eyeColumnX - originX, eyeColumnZ - originZ);
            GL20.glUniform2f(eyeFractionUniform, (float) (eyeX - eyeColumnX), (float) (eyeZ - eyeColumnZ));
            GL20.glUniform1f(eyeYUniform, (float) eyeY);
            GL20.glUniform1i(useColumnsUniform, usesColumns ? 1 : 0);
            GL20.glUniform1f(eyeOpennessUniform, (float) eyeOpenness);
        }
    }
}
