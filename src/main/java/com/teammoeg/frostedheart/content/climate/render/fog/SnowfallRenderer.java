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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.LightPillarSources;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyDrawTimer;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyFrame;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import com.teammoeg.frostedheart.mixin.client.LightTextureAccessor_WeatherFog;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/**
 * Draws the falling snow near the eye after the weather, in frames where the weather fog was drawn, in place of
 * vanilla's snow, as SnowFlakes describes it.
 * The flakes live on the GPU in two pairs of RGBA32F textures that take turns, the flakes' state and their splats: each
 * frame one full screen pass over the state moves every flake by the wind and its own fall speed into the other pair,
 * stopping it where it hits a shape of SnowShapeVolume and leaving a splat there, and precomputes the draw look
 * (shelter, lee, cavity, lamp lighting, fog transmittance and streak) of visible flakes and splats into four shared
 * draw textures. Two instanced draws of quads without vertex data (behind translucent blocks and foreground) then only
 * project those precomputed textures. The draws test against the depth of the main target, blend over it and write no
 * depth.
 * A paused game does not move the flakes, but still refreshes the precomputed draw look. Everything the passes change
 * is set back after them.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SnowfallRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STATE_UNIT = 0;
    private static final int DRAW_FLAKE_POS_UNIT = 0;
    /**
     * The unit of the fog's excess array (2D array) in update, reused as 2D flake draw color in draw.
     */
    private static final int EXCESS_UNIT = 1;
    private static final int DRAW_FLAKE_COLOR_UNIT = 1;
    private static final int SHARE_VOLUME_UNIT = 2;
    private static final int DRAW_FLAKE_STREAK_UNIT = 2;
    private static final int SPLAT_UNIT = 3;
    /**
     * The unit of the shape ids (3D) in update, reused as 2D splat draw color in draw.
     */
    private static final int SHAPE_VOLUME_UNIT = 4;
    private static final int DRAW_SPLAT_COLOR_UNIT = 4;
    /**
     * The unit of the shape rows (2D) in update, reused as 2D translucent depth in draw.
     */
    private static final int SHAPE_ROWS_UNIT = 5;
    private static final int TRANSLUCENT_DEPTH_UNIT = 5;
    static final int LEE_MAP_UNIT = 6;
    /**
     * The unit of the flake shapes (2D array) in draw, sharing index 6 on a separate target from LEE_MAP_UNIT.
     */
    private static final int FLAKE_SHAPES_UNIT = 6;
    static final int NO_LEAVES_HEIGHT_UNIT = 7;
    static final int SMOOTH_HEIGHT_UNIT = 8;
    static final int CAVITY_MAP_UNIT = 9;
    /**
     * The unit of vanilla's lightmap in update.
     */
    private static final int LIGHTMAP_UNIT = 10;
    private static final int DRAW_2D_TEXTURE_UNITS = 6;
    private static final int UPDATE_2D_TEXTURE_UNITS = 11;
    /**
     * The first of the units of the lamp light field's cascades in update, after the units above.
     */
    private static final int FIRST_LAMP_UNIT = UPDATE_2D_TEXTURE_UNITS;
    private static final int UPDATE_OUTPUT_COUNT = 6;
    private static final int CHUNK_WIDTH = 16;
    /**
     * Distance in blocks along any axis beyond which the fixed splat origin resets with a state refill to keep float
     * precision on splat coordinates.
     */
    private static final int SPLAT_ORIGIN_LIMIT = 4096;
    /**
     * The blocks before the faces of the box over which the flakes fade out.
     */
    private static final float BOX_FADE_WIDTH = 4.0F;
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(16);
    private static final IntBuffer intQueryBuffer = BufferUtils.createIntBuffer(1);
    private static final ByteBuffer booleanQueryBuffer = BufferUtils.createByteBuffer(1);
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final float[] frustumPlaneScales = new float[4];
    private static final int[] previousTextures = new int[UPDATE_2D_TEXTURE_UNITS];
    private static final int[] stateTextures = new int[2];
    private static final int[] splatTextures = new int[2];
    private static int flakeDrawColorTexture;
    private static int flakeDrawPosTexture;
    private static int flakeDrawStreakTexture;
    private static int splatDrawColorTexture;
    /**
     * The framebuffers of the two pairs, each attaching its ping-ponged state and splat textures alongside the four
     * shared precomputed draw textures.
     */
    private static final int[] stateFramebuffers = new int[2];
    private static final IntBuffer updateDrawBuffers = BufferUtils.createIntBuffer(UPDATE_OUTPUT_COUNT);
    private static final SkyDrawTimer updateTimer = new SkyDrawTimer();
    private static final SkyDrawTimer drawTimer = new SkyDrawTimer();
    private static final SkyDrawTimer behindDrawTimer = new SkyDrawTimer();
    private static final UpdateProgram updateProgram = new UpdateProgram();
    private static final CompactProgram compactProgram = new CompactProgram();
    private static final DrawProgram drawProgram = new DrawProgram();

    /**
     * The state texture that holds the flakes of the last update.
     */
    private static int currentState;
    private static boolean stateFilled;
    /**
     * The side of the box the state was filled for, since positions of another side mean other places.
     */
    private static double filledBoxSide;
    /**
     * The client level the state and splats belong to, so a level or dimension change refills state and clears splats.
     */
    @Nullable
    private static ClientLevel filledLevel;
    /**
     * The fixed world origin block that stored splat coordinates are relative to, reset on each fill.
     */
    private static int splatOriginX;
    private static int splatOriginY;
    private static int splatOriginZ;
    /**
     * The camera's position at the last update, which tells the update how far the camera moved since.
     */
    private static double lastUpdateCameraX;
    private static double lastUpdateCameraY;
    private static double lastUpdateCameraZ;
    private static boolean lastCollided;
    /**
     * Incremented on every non-paused flake update to reseed flux-weighted box-face re-entry.
     */
    private static int updateFrameCounter;
    /**
     * An empty vertex array for the compaction draw, and a vertex array, transform feedback object and buffer holding
     * the compacted visible splat and flake entries for the current frame.
     */
    private static int emptyVertexArray;
    private static int visibleVertexArray;
    private static int visibleBuffer;
    private static int visibleTransformFeedback;
    private static boolean failed;
    private static int lastInstances;
    private static double lastAskedCount;
    private static long updatedFrameNumber = -1L;
    private static long frameAllocatedBytes;
    private static long lastFrameAllocatedBytes;

    private SnowfallRenderer() {
    }

    /**
     * Whether the snowfall replaces vanilla's snow and the ground particles of the snow in this frame, which needs the
     * weather fog drawn in this frame, the snowfall setting on, and no earlier error. Between frames it answers for the
     * last frame.
     */
    public static boolean ownsPrecipitation() {
        return !failed && SkySetting.SNOW_ENABLED.isOn() && WeatherFogRenderer.isDrawnThisFrame();
    }

    /**
     * Draws flakes and splats that sit behind visible translucent terrain (and in front of opaque terrain) onto
     * mainTarget before translucent terrain is composited over it. Updates the flake simulation once for this frame so
     * the AFTER_WEATHER foreground pass reuses the updated state.
     */
    static void renderBehindTranslucent(ClientLevel level, PoseStack poseStack, Matrix4f projectionMatrix,
            Camera camera, int translucentDepthTexture) {
        if (translucentDepthTexture == 0) {
            return;
        }
        if (level != filledLevel) {
            filledLevel = null;
            stateFilled = false;
            SnowLeeMap.onLevelChanged(level);
        }
        if (!ownsPrecipitation()) {
            return;
        }
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            boolean runUpdate = updatedFrameNumber != SkyFrame.frameNumber();
            renderPass(level, poseStack, projectionMatrix, camera, translucentDepthTexture, runUpdate, behindDrawTimer);
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.error("The snowfall is not drawn after an error, so vanilla's snow draws again. It is drawn again "
                    + "once the calculated sky is turned on again.", exception);
        } finally {
            frameAllocatedBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        lastInstances = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != filledLevel) {
            filledLevel = null;
            stateFilled = false;
            SnowLeeMap.onLevelChanged(level);
        }
        if (level == null || !ownsPrecipitation()) {
            lastFrameAllocatedBytes = frameAllocatedBytes;
            frameAllocatedBytes = 0L;
            return;
        }
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            boolean runUpdate = updatedFrameNumber != SkyFrame.frameNumber();
            renderPass(level, event.getPoseStack(), event.getProjectionMatrix(), event.getCamera(), 0, runUpdate,
                    drawTimer);
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.error("The snowfall is not drawn after an error, so vanilla's snow draws again. It is drawn again "
                    + "once the calculated sky is turned on again.", exception);
        } finally {
            frameAllocatedBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
            lastFrameAllocatedBytes = frameAllocatedBytes;
            frameAllocatedBytes = 0L;
        }
    }

    private static void renderPass(ClientLevel level, PoseStack poseStack, Matrix4f projectionMatrix, Camera camera,
            int translucentDepthTexture, boolean runUpdate, SkyDrawTimer activeDrawTimer) {
        double boxSide = 2.0 * SkySetting.SNOW_NEAR_RADIUS.get();
        double flakeSize = SkySetting.SNOW_FLAKE_SIZE.get();
        double flakeShare = SkySetting.SNOW_FLAKE_SHARE.get();
        double extinction = WeatherFogRenderer.eyeExtinction();
        lastAskedCount = SnowFlakes.askedCount(extinction, flakeShare, flakeSize, boxSide);
        double drawnCount = SnowFlakes.drawnCount(extinction, flakeShare, flakeSize, boxSide);
        int instances = (int) Math.ceil(drawnCount);
        if (instances <= 0 || !prepareState()) {
            return;
        }
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        viewProjection.set(projectionMatrix).mul(poseStack.last().pose());
        float pixelsPerBlock = projectionMatrix.m11() * mainTarget.height * 0.5F;
        Vec3 cameraPosition = camera.getPosition();
        int previousProgram = getInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousVertexArray = getInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        int sourceColor = getInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationColor = getInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = getInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = getInteger(GL14.GL_BLEND_DST_ALPHA);
        int colorEquation = getInteger(GL20.GL_BLEND_EQUATION_RGB);
        int alphaEquation = getInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunction = getInteger(GL11.GL_DEPTH_FUNC);
        boolean depthWrites = getBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        int activeTexture = GlStateManager._getActiveTexture();
        int saved2DUnits = runUpdate ? UPDATE_2D_TEXTURE_UNITS : DRAW_2D_TEXTURE_UNITS;
        for (int unit = 0; unit < saved2DUnits; unit++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
            previousTextures[unit] = getInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FLAKE_SHAPES_UNIT);
        int previousFlakeShapesTexture = getInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int previousExcessTexture = 0;
        int previousShapeVolumeTexture = 0;
        int previousTransformFeedback = 0;
        boolean rasterizerDiscard = false;
        if (runUpdate) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
            previousExcessTexture = getInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SHAPE_VOLUME_UNIT);
            previousShapeVolumeTexture = getInteger(GL12.GL_TEXTURE_BINDING_3D);
            previousTransformFeedback = getInteger(GL40.GL_TRANSFORM_FEEDBACK_BINDING);
            rasterizerDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
            updateProgram.lamps.save();
        }
        try {
            RenderSystem.disableScissor();
            RenderSystem.disableCull();
            if (runUpdate) {
                FlakeShapeTexture.prepare();
                FarHeights farHeights = ClientSnowField.farHeights();
                WeatherConditions conditions = WeatherFogRenderer.weather();
                WeatherWind wind = WeatherFogRenderer.wind();
                double flakeFallSpeed = SnowFlakes.fallSpeed(SkySetting.SNOW_FALL_SPEED.get(),
                        SkySetting.SNOW_BLIZZARD_FALL_SPEED.get(), conditions.stormIntensity());
                double flakeWindSpeed = SnowFlakes.driftSpeed(
                        SnowFlakes.windSpeed(wind.speed(), SkySetting.SNOW_WIND_SHARE.get()), flakeFallSpeed,
                        SkySetting.SNOW_MAX_DRIFT.get());
                double windDirection = wind.direction();
                boolean wantsNoLeaves = SkySetting.SNOW_LEE.isOn() || FogCavityMap.isActive();
                int noLeavesTexture = wantsNoLeaves ? ClientSnowField.prepareNoLeavesHeights() : 0;
                SnowLeeMap.step(level, farHeights, noLeavesTexture, windDirection, flakeWindSpeed, flakeFallSpeed);
                boolean hasHeights = noLeavesTexture != 0 && farHeights != null && farHeights.isPlaced();
                boolean leeActive = SnowLeeMap.isActive() && hasHeights;
                boolean cavityActive = FogCavityMap.isActive() && hasHeights;
                LampLightField lampField = WeatherFogRenderer.isSnowColorFromLightmap() ? ClientLampLight.field(level)
                        : null;
                double splatSeconds = SkySetting.SNOW_SPLAT_SECONDS.get();
                updateTimer.begin();
                try {
                    update(level, mainTarget, cameraPosition, boxSide, flakeSize, drawnCount, pixelsPerBlock,
                            farHeights, noLeavesTexture, leeActive, cavityActive, windDirection, flakeWindSpeed,
                            flakeFallSpeed, lampField);
                    compact(instances, splatSeconds);
                    updatedFrameNumber = SkyFrame.frameNumber();
                } finally {
                    updateTimer.end();
                }
            }
            activeDrawTimer.begin();
            try {
                draw(mainTarget, cameraPosition, flakeSize, pixelsPerBlock, translucentDepthTexture);
            } finally {
                activeDrawTimer.end();
            }
        } finally {
            GL30.glBindVertexArray(previousVertexArray);
            BufferUploader.invalidate();
            GL20.glUseProgram(previousProgram);
            GL20.glBlendEquationSeparate(colorEquation, alphaEquation);
            RenderSystem.blendFuncSeparate(sourceColor, destinationColor, sourceAlpha, destinationAlpha);
            if (blending) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            RenderSystem.depthMask(depthWrites);
            RenderSystem.depthFunc(depthFunction);
            if (depthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
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
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FLAKE_SHAPES_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousFlakeShapesTexture);
            if (runUpdate) {
                if (rasterizerDiscard) {
                    GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                } else {
                    GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                }
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, previousTransformFeedback);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousExcessTexture);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SHAPE_VOLUME_UNIT);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousShapeVolumeTexture);
                updateProgram.lamps.restore();
            }
            for (int unit = 0; unit < saved2DUnits; unit++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
                RenderSystem.bindTexture(previousTextures[unit]);
            }
            RenderSystem.activeTexture(activeTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
        lastInstances = instances;
    }

    /**
     * Moves every flake by the seconds of this frame into the other pair of textures, colliding with the shape volume
     * when the setting is on and the volume has a position, or places every flake from its hashes when the state was not
     * filled yet, the level or the box side changed, or the camera moved far from the splat origin, and precomputes the
     * draw look of visible flakes and splats into the four shared draw textures. A paused frame copies state and splats
     * without advancing simulation while still refreshing the draw look for the current camera and weather.
     */
    private static void update(ClientLevel level, RenderTarget mainTarget, Vec3 camera, double boxSide,
            double flakeSize, double drawnCount, float pixelsPerBlock, @Nullable FarHeights farHeights,
            int noLeavesTexture, boolean leeActive, boolean cavityActive, double windDirection, double flakeWindSpeed,
            double flakeFallSpeed, @Nullable LampLightField lampField) {
        double stepSeconds = WeatherFogRenderer.frameStepSeconds();
        int blockX = Mth.floor(camera.x);
        int blockY = Mth.floor(camera.y);
        int blockZ = Mth.floor(camera.z);
        boolean originTooFar = stateFilled && (Math.abs((long) blockX - splatOriginX) > SPLAT_ORIGIN_LIMIT
                || Math.abs((long) blockY - splatOriginY) > SPLAT_ORIGIN_LIMIT
                || Math.abs((long) blockZ - splatOriginZ) > SPLAT_ORIGIN_LIMIT);
        boolean fill = !stateFilled || boxSide != filledBoxSide || level != filledLevel || originTooFar;
        boolean advance = fill || stepSeconds > 0.0;
        if (fill) {
            lastUpdateCameraX = camera.x;
            lastUpdateCameraY = camera.y;
            lastUpdateCameraZ = camera.z;
            filledBoxSide = boxSide;
            filledLevel = level;
            splatOriginX = blockX;
            splatOriginY = blockY;
            splatOriginZ = blockZ;
        }
        SnowShapeVolume volume = null;
        if (advance) {
            volume = SkySetting.SNOW_COLLISION.isOn() ? ClientSnowField.shapeVolume() : null;
            if (volume != null && !volume.hasPosition()) {
                volume = null;
            }
            if (volume != null) {
                SnowShapeTextures.upload(volume);
            }
            lastCollided = volume != null;
            updateFrameCounter++;
        }
        SnowFlakes.frustumPlaneScales(viewProjection.m00(), viewProjection.m10(), viewProjection.m20(),
                viewProjection.m01(), viewProjection.m11(), viewProjection.m21(), viewProjection.m03(),
                viewProjection.m13(), viewProjection.m23(), frustumPlaneScales);
        int next = 1 - currentState;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, stateFramebuffers[next]);
        RenderSystem.viewport(0, 0, SnowFlakes.STATE_WIDTH, SnowFlakes.STATE_HEIGHT);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        bindTexture(STATE_UNIT, stateTextures[currentState]);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, WeatherFogRenderer.excessTexture());
        int shareVolume = WeatherFogRenderer.shareVolumeTexture();
        bindTexture(SHARE_VOLUME_UNIT, shareVolume);
        bindTexture(SPLAT_UNIT, splatTextures[currentState]);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SHAPE_VOLUME_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, SnowShapeTextures.volumeTexture());
        bindTexture(SHAPE_ROWS_UNIT, SnowShapeTextures.rowsTexture());
        bindTexture(LEE_MAP_UNIT, leeActive ? SnowLeeMap.texture() : 0);
        bindTexture(NO_LEAVES_HEIGHT_UNIT, (leeActive || cavityActive) ? noLeavesTexture : 0);
        bindTexture(SMOOTH_HEIGHT_UNIT, leeActive ? SnowLeeMap.smoothTexture() : 0);
        bindTexture(CAVITY_MAP_UNIT, cavityActive ? FogCavityMap.texture() : 0);
        bindLightmap(lampField != null);
        updateProgram.use();
        updateProgram.setDrawBuffers();
        updateProgram.setUniforms(mainTarget, fill, advance ? stepSeconds : 0.0, windDirection, flakeWindSpeed,
                flakeFallSpeed, boxSide, flakeSize, drawnCount, pixelsPerBlock, shareVolume != 0, camera, blockX,
                blockY, blockZ, volume, farHeights, leeActive, cavityActive, updateFrameCounter);
        updateProgram.lamps.bind(lampField, camera, ClientLampLight.fade());
        SkyGlPass.drawFullScreenQuad();
        if (advance) {
            lastUpdateCameraX = camera.x;
            lastUpdateCameraY = camera.y;
            lastUpdateCameraZ = camera.z;
        }
        currentState = next;
        stateFilled = true;
    }

    /**
     * Compacts visible splat indices followed by visible flake indices into visibleBuffer via transform feedback,
     * keeping splats ordered ahead of flakes so both draw passes issue a single glDrawTransformFeedback call.
     */
    private static void compact(int instances, double splatSeconds) {
        int groups = (instances + SnowFlakes.COMPACT_GROUP_SIZE - 1) / SnowFlakes.COMPACT_GROUP_SIZE;
        compactProgram.use();
        compactProgram.setInstanceCount(instances);
        GL30.glBindVertexArray(emptyVertexArray);
        GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, visibleTransformFeedback);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        GL30.glBeginTransformFeedback(GL11.GL_POINTS);
        try {
            if (splatSeconds > 0.0) {
                bindTexture(DRAW_FLAKE_COLOR_UNIT, splatDrawColorTexture);
                compactProgram.setSplatPass(true);
                GL11.glDrawArrays(GL11.GL_POINTS, 0, groups);
            }
            bindTexture(DRAW_FLAKE_COLOR_UNIT, flakeDrawColorTexture);
            compactProgram.setSplatPass(false);
            GL11.glDrawArrays(GL11.GL_POINTS, 0, groups);
        } finally {
            GL30.glEndTransformFeedback();
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        }
    }

    /**
     * Projects the compacted visible splats and flakes into the main target, binding its framebuffer for drawing, with
     * the depth test of the scene and no depth writes. Face culling is off, since the quads' winding is not kept.
     */
    private static void draw(RenderTarget mainTarget, Vec3 camera, double flakeSize, float pixelsPerBlock,
            int translucentDepthTexture) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
        RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        bindTexture(DRAW_FLAKE_POS_UNIT, flakeDrawPosTexture);
        bindTexture(DRAW_FLAKE_COLOR_UNIT, flakeDrawColorTexture);
        bindTexture(DRAW_FLAKE_STREAK_UNIT, flakeDrawStreakTexture);
        bindTexture(SPLAT_UNIT, splatTextures[currentState]);
        bindTexture(DRAW_SPLAT_COLOR_UNIT, splatDrawColorTexture);
        bindTexture(TRANSLUCENT_DEPTH_UNIT, translucentDepthTexture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + FLAKE_SHAPES_UNIT);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, FlakeShapeTexture.texture());
        drawProgram.use();
        drawProgram.setUniforms(mainTarget, camera, flakeSize, pixelsPerBlock);
        drawProgram.setClipAgainstTranslucent(translucentDepthTexture != 0);
        GL30.glBindVertexArray(visibleVertexArray);
        GL40.glDrawTransformFeedback(GL11.GL_POINTS, visibleTransformFeedback);
    }

    private static int getInteger(int parameter) {
        GL11.glGetIntegerv(parameter, intQueryBuffer);
        return intQueryBuffer.get(0);
    }

    private static boolean getBoolean(int parameter) {
        GL11.glGetBooleanv(parameter, booleanQueryBuffer);
        return booleanQueryBuffer.get(0) != 0;
    }

    private static void bindTexture(int unit, int texture) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
    }

    /**
     * Binds vanilla's lightmap for the lamp light, filtered linearly as LightTexture.turnOnLightLayer filters it for
     * terrain, since each upload of the lightmap sets it back to nearest. Without lamp light no texture is bound.
     */
    private static void bindLightmap(boolean lampLight) {
        if (!lampLight) {
            bindTexture(LIGHTMAP_UNIT, 0);
            return;
        }
        LightTextureAccessor_WeatherFog accessor =
                (LightTextureAccessor_WeatherFog) Minecraft.getInstance().gameRenderer.lightTexture();
        bindTexture(LIGHTMAP_UNIT, accessor.frostedheart$getLightTexture().getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    /**
     * Makes the two pairs of state and splat textures, the four shared draw textures and their framebuffers on first
     * use and keeps them for the session. Textures that cannot be made are deleted again, and the snowfall stops with a
     * log line, so vanilla's snow draws instead.
     * @return whether the state textures exist
     */
    private static boolean prepareState() {
        if (stateTextures[0] != 0) {
            return true;
        }
        int[] textures = new int[8];
        int[] framebuffers = new int[2];
        int createdEmptyVao = 0;
        int createdVisibleVao = 0;
        int createdVisibleBuffer = 0;
        int createdTransformFeedback = 0;
        int previousTexture = getInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDrawFramebuffer = getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousVertexArray = getInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = getInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousTransformFeedback = getInteger(GL40.GL_TRANSFORM_FEEDBACK_BINDING);
        int previousTransformFeedbackBuffer = getInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
        try {
            GLCapabilities capabilities = GL.getCapabilities();
            if (!capabilities.OpenGL40 && !capabilities.GL_ARB_transform_feedback2) {
                throw new IllegalStateException("Transform feedback objects (OpenGL 4.0) are not supported");
            }
            for (int texture = 0; texture < textures.length; texture++) {
                boolean fullPrecision = texture < 4 || texture == 5;
                textures[texture] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[texture]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, fullPrecision ? GL30.GL_RGBA32F : GL30.GL_RGBA16F,
                        SnowFlakes.STATE_WIDTH, SnowFlakes.STATE_HEIGHT, 0, GL11.GL_RGBA,
                        fullPrecision ? GL11.GL_FLOAT : GL30.GL_HALF_FLOAT, (FloatBuffer) null);
            }
            for (int state = 0; state < 2; state++) {
                framebuffers[state] = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffers[state]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        textures[state], 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D,
                        textures[2 + state], 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT2, GL11.GL_TEXTURE_2D,
                        textures[4], 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT3, GL11.GL_TEXTURE_2D,
                        textures[5], 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT4, GL11.GL_TEXTURE_2D,
                        textures[6], 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT5, GL11.GL_TEXTURE_2D,
                        textures[7], 0);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The snowflake state framebuffer is incomplete with status "
                            + status);
                }
            }
            createdVisibleBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, createdVisibleBuffer);
            GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,
                    (long) 2 * SnowFlakes.CAPACITY * Integer.BYTES, GL15.GL_DYNAMIC_COPY);
            createdTransformFeedback = GL40.glGenTransformFeedbacks();
            GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, createdTransformFeedback);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, createdVisibleBuffer);
            createdEmptyVao = GL30.glGenVertexArrays();
            createdVisibleVao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(createdVisibleVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, createdVisibleBuffer);
            GL20.glEnableVertexAttribArray(0);
            GL30.glVertexAttribIPointer(0, 1, GL11.GL_UNSIGNED_INT, Integer.BYTES, 0L);
        } catch (RuntimeException exception) {
            for (int texture = 0; texture < textures.length; texture++) {
                GL11.glDeleteTextures(textures[texture]);
            }
            for (int state = 0; state < 2; state++) {
                GL30.glDeleteFramebuffers(framebuffers[state]);
            }
            if (createdTransformFeedback != 0) {
                GL40.glDeleteTransformFeedbacks(createdTransformFeedback);
            }
            if (createdVisibleBuffer != 0) {
                GL15.glDeleteBuffers(createdVisibleBuffer);
            }
            if (createdEmptyVao != 0) {
                GL30.glDeleteVertexArrays(createdEmptyVao);
            }
            if (createdVisibleVao != 0) {
                GL30.glDeleteVertexArrays(createdVisibleVao);
            }
            failed = true;
            LOGGER.error("The snowflake state cannot be made, so vanilla's snow draws instead.", exception);
            return false;
        } finally {
            GL30.glBindVertexArray(previousVertexArray);
            BufferUploader.invalidate();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, previousTransformFeedback);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, previousTransformFeedbackBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
        stateTextures[0] = textures[0];
        stateTextures[1] = textures[1];
        splatTextures[0] = textures[2];
        splatTextures[1] = textures[3];
        flakeDrawColorTexture = textures[4];
        flakeDrawPosTexture = textures[5];
        flakeDrawStreakTexture = textures[6];
        splatDrawColorTexture = textures[7];
        stateFramebuffers[0] = framebuffers[0];
        stateFramebuffers[1] = framebuffers[1];
        emptyVertexArray = createdEmptyVao;
        visibleVertexArray = createdVisibleVao;
        visibleBuffer = createdVisibleBuffer;
        visibleTransformFeedback = createdTransformFeedback;
        currentState = 0;
        stateFilled = false;
        return true;
    }

    public static void clearFailure() {
        failed = false;
        SnowLeeMap.clearFailure();
    }

    /**
     * A line about the flakes drawn in the last frame, the time their passes take, and what the frame allocated, for the
     * client command.
     */
    public static String describe() {
        if (failed) {
            return "Snowfall stopped after an error, see the log";
        }
        if (!SkySetting.SNOW_ENABLED.isOn()) {
            return "Snowfall off, vanilla's snow draws";
        }
        String allocated = LightPillarSources.countsAllocations()
                ? String.format(Locale.ROOT, "%d bytes allocated in the last frame", lastFrameAllocatedBytes)
                : "allocations not counted by this JVM";
        return String.format(Locale.ROOT, "Snowfall: %d flakes drawn in the last frame, %.0f asked by snowFlakeShare "
                        + "of %d at most%s; update %s; behind %s; draw %s; %s; %s; %s; %s", lastInstances, lastAskedCount,
                SnowFlakes.CAPACITY, lastAskedCount > SnowFlakes.CAPACITY ? " (capped)" : "", updateTimer.describe(),
                behindDrawTimer.describe(), drawTimer.describe(), allocated, FlakeShapeTexture.describe(),
                describeCollision(), SnowLeeMap.describe());
    }

    /**
     * Whether the last update collided, and the state of the shape volume.
     */
    private static String describeCollision() {
        if (!SkySetting.SNOW_COLLISION.isOn()) {
            return "collision off";
        }
        SnowShapeVolume volume = ClientSnowField.shapeVolume();
        if (volume == null || !volume.hasPosition()) {
            return "collision waits for the shape volume";
        }
        return String.format(Locale.ROOT, "collision %s with %d shapes, %d cells read in all, %d fills, %d box "
                        + "overflows, %s, %d bricks uploaded since the last report",
                lastCollided ? "on" : "not run yet", volume.shapes().shapeCount(), volume.cellsRead(),
                volume.fillCount(), volume.boxOverflowCount(),
                volume.isIdle() ? "every cell read" : volume.queuedBoxCount() + " boxes waiting",
                SnowShapeTextures.takeBrickUploads());
    }

    /**
     * The program of the update, IMAGE_V and snow_flake_update.fsh, the locations of its uniforms, and the locations of
     * its six outputs. It is made again when a resource reload replaced its stage.
     */
    private static final class UpdateProgram {
        private static final String[] OUTPUT_NAMES = {"nextState", "nextSplat", "flakeDrawColor", "flakeDrawPos",
                "flakeDrawStreak", "splatDrawColor"};
        private static final int[] OUTPUT_ATTACHMENTS = {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1,
                GL30.GL_COLOR_ATTACHMENT2, GL30.GL_COLOR_ATTACHMENT3, GL30.GL_COLOR_ATTACHMENT4,
                GL30.GL_COLOR_ATTACHMENT5};

        private final LampLightBinding lamps = new LampLightBinding(FIRST_LAMP_UNIT);
        private final int[] drawAttachments = new int[UPDATE_OUTPUT_COUNT];
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int fillStateUniform;
        private int stepSecondsUniform;
        private int windVelocityUniform;
        private int fallSpeedUniform;
        private int boxSideUniform;
        private int flutterRadiusUniform;
        private int previousCameraInBoxUniform;
        private int cameraInBoxUniform;
        private int cameraStepUniform;
        private int cameraFromSplatOriginUniform;
        private int collidesUniform;
        private int cameraBlockUniform;
        private int cameraFractionUniform;
        private int volumeCornerUniform;
        private int leafPorosityUniform;
        private int splatSecondsUniform;
        private int frameCounterUniform;
        private int viewProjectionUniform;
        private int screenSizeUniform;
        private int pixelsPerBlockUniform;
        private int frustumPlaneScalesUniform;
        private int maxShapeReachUniform;
        private int drawnCountUniform;
        private int flakeSizeUniform;
        private int shutterSecondsUniform;
        private int flakeBrightnessUniform;
        private int snowColorUniform;
        private int eyeExtinctionUniform;
        private int heightScaleUniform;
        private int fogEyeUniform;
        private int fogEyeFractionUniform;
        private int hasShareVolumeUniform;
        private int columnCountUniform;
        private int rowCountUniform;
        private int sliceCountUniform;
        private int rangeDistanceUniform;
        private int divisorUniform;
        private int hasLeeMapUniform;
        private int hasCavityMapUniform;
        private int cavityMinUniform;
        private int cavityBrakeLengthUniform;
        private int cavitySwirlUniform;
        private int cavityDowndraftUniform;
        private int cameraFromLeeAnchorUniform;
        private int cameraFromSmoothMinUniform;
        private int leeWindDirectionUniform;
        private int leeFallSpeedUniform;
        private int leeBandUniform;
        private int farHeightsMinUniform;

        private void use() {
            Shader newFragment = FHShaders.getSnowFlakeUpdate();
            if (program == null || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                int[] createdAttachments = new int[UPDATE_OUTPUT_COUNT];
                try {
                    created.attach(FHShaders.IMAGE_V).attach(newFragment);
                    created.linkProgram();
                    // GLSL 1.50 leaves the locations of multiple fragment outputs to the linker:
                    int seenMask = 0;
                    for (int index = 0; index < UPDATE_OUTPUT_COUNT; index++) {
                        int location = GL30.glGetFragDataLocation(created.programId, OUTPUT_NAMES[index]);
                        if (location < 0 || location >= UPDATE_OUTPUT_COUNT) {
                            throw new IllegalStateException("The snowflake update writes " + OUTPUT_NAMES[index]
                                    + " to location " + location + " instead of 0.." + (UPDATE_OUTPUT_COUNT - 1));
                        }
                        int bit = 1 << location;
                        if ((seenMask & bit) != 0) {
                            throw new IllegalStateException("The snowflake update writes two outputs to location "
                                    + location);
                        }
                        seenMask |= bit;
                        createdAttachments[location] = OUTPUT_ATTACHMENTS[index];
                    }
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                fragment = newFragment;
                System.arraycopy(createdAttachments, 0, drawAttachments, 0, UPDATE_OUTPUT_COUNT);
                program.use();
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "previousState"), STATE_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "excesses"), EXCESS_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "shareVolume"), SHARE_VOLUME_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "previousSplats"), SPLAT_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "shapeVolume"), SHAPE_VOLUME_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "shapeRows"), SHAPE_ROWS_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "leeMap"), LEE_MAP_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "noLeavesHeights"), NO_LEAVES_HEIGHT_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "smoothHeights"), SMOOTH_HEIGHT_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "cavityMap"), CAVITY_MAP_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "lampLightmap"), LIGHTMAP_UNIT);
                lamps.locate(programId);
                GL20.glUniform3i(GL20.glGetUniformLocation(programId, "shareLayout"), FogShareVolume.SIDE,
                        FogShareVolume.ORIGIN, FogShareVolume.TILES_ACROSS);
                GL20.glUniform1f(GL20.glGetUniformLocation(programId, "boxFadeWidth"), BOX_FADE_WIDTH);
                fillStateUniform = GL20.glGetUniformLocation(programId, "fillState");
                stepSecondsUniform = GL20.glGetUniformLocation(programId, "stepSeconds");
                windVelocityUniform = GL20.glGetUniformLocation(programId, "windVelocity");
                fallSpeedUniform = GL20.glGetUniformLocation(programId, "fallSpeed");
                boxSideUniform = GL20.glGetUniformLocation(programId, "boxSide");
                flutterRadiusUniform = GL20.glGetUniformLocation(programId, "flutterRadius");
                previousCameraInBoxUniform = GL20.glGetUniformLocation(programId, "previousCameraInBox");
                cameraInBoxUniform = GL20.glGetUniformLocation(programId, "cameraInBox");
                cameraStepUniform = GL20.glGetUniformLocation(programId, "cameraStep");
                cameraFromSplatOriginUniform = GL20.glGetUniformLocation(programId, "cameraFromSplatOrigin");
                collidesUniform = GL20.glGetUniformLocation(programId, "collides");
                cameraBlockUniform = GL20.glGetUniformLocation(programId, "cameraBlock");
                cameraFractionUniform = GL20.glGetUniformLocation(programId, "cameraFraction");
                volumeCornerUniform = GL20.glGetUniformLocation(programId, "volumeCorner");
                leafPorosityUniform = GL20.glGetUniformLocation(programId, "leafPorosity");
                splatSecondsUniform = GL20.glGetUniformLocation(programId, "splatSeconds");
                frameCounterUniform = GL20.glGetUniformLocation(programId, "frameCounter");
                viewProjectionUniform = GL20.glGetUniformLocation(programId, "viewProjection");
                screenSizeUniform = GL20.glGetUniformLocation(programId, "screenSize");
                pixelsPerBlockUniform = GL20.glGetUniformLocation(programId, "pixelsPerBlock");
                frustumPlaneScalesUniform = GL20.glGetUniformLocation(programId, "frustumPlaneScales");
                maxShapeReachUniform = GL20.glGetUniformLocation(programId, "maxShapeReach");
                drawnCountUniform = GL20.glGetUniformLocation(programId, "drawnCount");
                flakeSizeUniform = GL20.glGetUniformLocation(programId, "flakeSize");
                shutterSecondsUniform = GL20.glGetUniformLocation(programId, "shutterSeconds");
                flakeBrightnessUniform = GL20.glGetUniformLocation(programId, "flakeBrightness");
                snowColorUniform = GL20.glGetUniformLocation(programId, "snowColor");
                eyeExtinctionUniform = GL20.glGetUniformLocation(programId, "eyeExtinction");
                heightScaleUniform = GL20.glGetUniformLocation(programId, "heightScale");
                fogEyeUniform = GL20.glGetUniformLocation(programId, "fogEye");
                fogEyeFractionUniform = GL20.glGetUniformLocation(programId, "fogEyeFraction");
                hasShareVolumeUniform = GL20.glGetUniformLocation(programId, "hasShareVolume");
                columnCountUniform = GL20.glGetUniformLocation(programId, "columnCount");
                rowCountUniform = GL20.glGetUniformLocation(programId, "rowCount");
                sliceCountUniform = GL20.glGetUniformLocation(programId, "sliceCount");
                rangeDistanceUniform = GL20.glGetUniformLocation(programId, "rangeDistance");
                divisorUniform = GL20.glGetUniformLocation(programId, "divisor");
                hasLeeMapUniform = GL20.glGetUniformLocation(programId, "hasLeeMap");
                hasCavityMapUniform = GL20.glGetUniformLocation(programId, "hasCavityMap");
                cavityMinUniform = GL20.glGetUniformLocation(programId, "cavityMin");
                cavityBrakeLengthUniform = GL20.glGetUniformLocation(programId, "cavityBrakeLength");
                cavitySwirlUniform = GL20.glGetUniformLocation(programId, "cavitySwirl");
                cavityDowndraftUniform = GL20.glGetUniformLocation(programId, "cavityDowndraft");
                cameraFromLeeAnchorUniform = GL20.glGetUniformLocation(programId, "cameraFromLeeAnchor");
                cameraFromSmoothMinUniform = GL20.glGetUniformLocation(programId, "cameraFromSmoothMin");
                leeWindDirectionUniform = GL20.glGetUniformLocation(programId, "leeWindDirection");
                leeFallSpeedUniform = GL20.glGetUniformLocation(programId, "leeFallSpeed");
                leeBandUniform = GL20.glGetUniformLocation(programId, "leeBand");
                farHeightsMinUniform = GL20.glGetUniformLocation(programId, "farHeightsMin");
                return;
            }
            GL20.glUseProgram(program.programId);
        }

        /**
         * Lists the six color attachments of the bound framebuffer at the locations of the outputs that write them.
         */
        private void setDrawBuffers() {
            updateDrawBuffers.clear();
            for (int location = 0; location < UPDATE_OUTPUT_COUNT; location++) {
                updateDrawBuffers.put(location, drawAttachments[location]);
            }
            GL20.glDrawBuffers(updateDrawBuffers);
        }

        /**
         * Sets the uniforms of this update. The camera reaches the program as its place in the periodic box, wrapped in
         * double precision, as its block and the place inside it, as its offset from the fixed splat origin block, and
         * as its motion since the last update.
         * @param volume the shapes to collide with, or null for flakes that fall through blocks
         */
        private void setUniforms(RenderTarget mainTarget, boolean fill, double stepSeconds, double windDirection,
                double flakeWindSpeed, double flakeFallSpeed, double boxSide, double flakeSize, double drawnCount,
                float pixelsPerBlock, boolean hasShareVolume, Vec3 camera, int blockX, int blockY, int blockZ,
                @Nullable SnowShapeVolume volume, @Nullable FarHeights farHeights, boolean leeActive,
                boolean cavityActive, int frameCounter) {
            GL20.glUniform1i(fillStateUniform, fill ? 1 : 0);
            GL20.glUniform1f(stepSecondsUniform, (float) stepSeconds);
            GL20.glUniform2f(windVelocityUniform, (float) (flakeWindSpeed * Math.cos(windDirection)),
                    (float) (flakeWindSpeed * Math.sin(windDirection)));
            GL20.glUniform1f(fallSpeedUniform, (float) flakeFallSpeed);
            GL20.glUniform1f(boxSideUniform, (float) boxSide);
            GL20.glUniform1f(flutterRadiusUniform, (float) SkySetting.SNOW_FLUTTER.get());
            GL20.glUniform3f(previousCameraInBoxUniform, (float) SnowFlakes.wrap(lastUpdateCameraX, boxSide),
                    (float) SnowFlakes.wrap(lastUpdateCameraY, boxSide),
                    (float) SnowFlakes.wrap(lastUpdateCameraZ, boxSide));
            GL20.glUniform3f(cameraInBoxUniform, (float) SnowFlakes.wrap(camera.x, boxSide),
                    (float) SnowFlakes.wrap(camera.y, boxSide), (float) SnowFlakes.wrap(camera.z, boxSide));
            GL20.glUniform3f(cameraStepUniform, (float) (camera.x - lastUpdateCameraX),
                    (float) (camera.y - lastUpdateCameraY), (float) (camera.z - lastUpdateCameraZ));
            GL20.glUniform3f(cameraFromSplatOriginUniform, (float) (camera.x - splatOriginX),
                    (float) (camera.y - splatOriginY), (float) (camera.z - splatOriginZ));
            GL20.glUniform1i(collidesUniform, volume != null ? 1 : 0);
            GL20.glUniform3i(cameraBlockUniform, blockX, blockY, blockZ);
            GL20.glUniform3f(cameraFractionUniform, (float) (camera.x - blockX), (float) (camera.y - blockY),
                    (float) (camera.z - blockZ));
            if (volume != null) {
                GL20.glUniform3i(volumeCornerUniform, volume.originX() - blockX, volume.originY() - blockY,
                        volume.originZ() - blockZ);
            }
            GL20.glUniform1f(leafPorosityUniform, (float) SkySetting.FOG_LEAF_POROSITY.get());
            GL20.glUniform1f(splatSecondsUniform, (float) SkySetting.SNOW_SPLAT_SECONDS.get());
            GL20.glUniform1i(frameCounterUniform, frameCounter);
            viewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(viewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(pixelsPerBlockUniform, pixelsPerBlock);
            GL20.glUniform4f(frustumPlaneScalesUniform, frustumPlaneScales[0], frustumPlaneScales[1],
                    frustumPlaneScales[2], frustumPlaneScales[3]);
            GL20.glUniform1f(maxShapeReachUniform, FlakeShapeTexture.maxTileScale());
            GL20.glUniform1f(drawnCountUniform, (float) drawnCount);
            GL20.glUniform1f(flakeSizeUniform, (float) flakeSize);
            GL20.glUniform1f(shutterSecondsUniform, (float) SkySetting.SNOW_STREAK_SECONDS.get());
            GL20.glUniform1f(flakeBrightnessUniform, (float) SkySetting.SNOW_FLAKE_BRIGHTNESS.get());
            GL20.glUniform3f(snowColorUniform, WeatherFogRenderer.snowColor(0), WeatherFogRenderer.snowColor(1),
                    WeatherFogRenderer.snowColor(2));
            GL20.glUniform1f(eyeExtinctionUniform, (float) WeatherFogRenderer.eyeExtinction());
            GL20.glUniform1f(heightScaleUniform, (float) WeatherFogRenderer.heightScale());
            GL20.glUniform3f(fogEyeUniform, WeatherFogRenderer.eyeOffset(0), WeatherFogRenderer.eyeOffset(1),
                    WeatherFogRenderer.eyeOffset(2));
            GL20.glUniform3f(fogEyeFractionUniform, (float) WeatherFogRenderer.eyeFractionX(),
                    (float) WeatherFogRenderer.eyeFractionY(), (float) WeatherFogRenderer.eyeFractionZ());
            GL20.glUniform1i(hasShareVolumeUniform, hasShareVolume ? 1 : 0);
            GL20.glUniform1i(columnCountUniform, WeatherFogRenderer.froxelColumnCount());
            GL20.glUniform1i(rowCountUniform, WeatherFogRenderer.froxelRowCount());
            GL20.glUniform1i(sliceCountUniform, WeatherFogRenderer.froxelSliceCount());
            GL20.glUniform1f(rangeDistanceUniform, (float) WeatherFogRenderer.froxelRangeDistance());
            GL20.glUniform1i(divisorUniform, WeatherFogRenderer.froxelDivisor());
            GL20.glUniform1i(hasLeeMapUniform, leeActive ? 1 : 0);
            GL20.glUniform1i(hasCavityMapUniform, cavityActive ? 1 : 0);
            GL20.glUniform1f(cavityBrakeLengthUniform, (float) SkySetting.SNOW_CAVITY_BRAKE_LENGTH.get());
            GL20.glUniform1f(cavitySwirlUniform, (float) SkySetting.SNOW_CAVITY_SWIRL.get());
            GL20.glUniform1f(cavityDowndraftUniform, (float) SkySetting.SNOW_CAVITY_DOWNDRAFT.get());
            if (cavityActive) {
                GL20.glUniform2i(cavityMinUniform, FogCavityMap.publishedMinX(), FogCavityMap.publishedMinZ());
            }
            if ((leeActive || cavityActive) && farHeights != null) {
                GL20.glUniform2i(farHeightsMinUniform, farHeights.originChunkX() * CHUNK_WIDTH,
                        farHeights.originChunkZ() * CHUNK_WIDTH);
            }
            if (leeActive && farHeights != null) {
                GL20.glUniform2f(cameraFromLeeAnchorUniform, (float) (camera.x - SnowLeeMap.publishedAnchorX()),
                        (float) (camera.z - SnowLeeMap.publishedAnchorZ()));
                GL20.glUniform2f(cameraFromSmoothMinUniform, (float) (camera.x - SnowLeeMap.publishedSmoothMinX()),
                        (float) (camera.z - SnowLeeMap.publishedSmoothMinZ()));
                GL20.glUniform2f(leeWindDirectionUniform, (float) SnowLeeMap.publishedDirX(),
                        (float) SnowLeeMap.publishedDirZ());
                GL20.glUniform1f(leeFallSpeedUniform,
                        (float) SnowLee.leeFallSpeed(SnowLeeMap.publishedSlope(), flakeWindSpeed));
                GL20.glUniform1f(leeBandUniform, (float) SkySetting.SNOW_LEE_BAND.get());
            }
        }
    }

    /**
     * The program of snow_flakes_compact.glsl, compacting visible splat and flake entries into transform feedback.
     */
    private static final class CompactProgram {
        private static final CharSequence[] FEEDBACK_VARYINGS = {"visibleEntry"};

        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader vertex;
        private int geometry;
        private int splatPassUniform;
        private int instanceCountUniform;

        private void use() {
            Shader newVertex = FHShaders.getSnowFlakesCompactVertex();
            int newGeometry = FHShaders.getSnowFlakesCompactGeometry();
            if (program == null || vertex != newVertex || geometry != newGeometry) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                try {
                    created.attach(newVertex);
                    GL20.glAttachShader(created.programId, newGeometry);
                    GL30.glTransformFeedbackVaryings(created.programId, FEEDBACK_VARYINGS,
                            GL30.GL_INTERLEAVED_ATTRIBS);
                    created.linkProgram();
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                vertex = newVertex;
                geometry = newGeometry;
                program.use();
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "drawColor"), DRAW_FLAKE_COLOR_UNIT);
                splatPassUniform = GL20.glGetUniformLocation(programId, "splatPass");
                instanceCountUniform = GL20.glGetUniformLocation(programId, "instanceCount");
                return;
            }
            GL20.glUseProgram(program.programId);
        }

        private void setSplatPass(boolean splats) {
            GL20.glUniform1i(splatPassUniform, splats ? 1 : 0);
        }

        private void setInstanceCount(int instanceCount) {
            GL20.glUniform1i(instanceCountUniform, instanceCount);
        }
    }

    /**
     * The program of the three stages of snow_flakes_draw.glsl and the locations of the projection uniforms that draw
     * sets every frame. It is made again when a resource reload replaced its stages.
     */
    private static final class DrawProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader vertex;
        private int geometry;
        @Nullable
        private Shader fragment;
        private int viewProjectionUniform;
        private int screenSizeUniform;
        private int pixelsPerBlockUniform;
        private int cameraFromSplatOriginUniform;
        private int flakeSizeUniform;
        private int shapeTileScalesUniform;
        private int clipAgainstTranslucentUniform;

        private void use() {
            Shader newVertex = FHShaders.getSnowFlakesVertex();
            int newGeometry = FHShaders.getSnowFlakesGeometry();
            Shader newFragment = FHShaders.getSnowFlakesFragment();
            if (program == null || vertex != newVertex || geometry != newGeometry || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                try {
                    created.attach(newVertex).attach(newFragment);
                    GL20.glAttachShader(created.programId, newGeometry);
                    GL20.glBindAttribLocation(created.programId, 0, "visibleEntry");
                    created.linkProgram();
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                vertex = newVertex;
                geometry = newGeometry;
                fragment = newFragment;
                program.use();
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "flakeDrawPos"), DRAW_FLAKE_POS_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "flakeDrawColor"), DRAW_FLAKE_COLOR_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "flakeDrawStreak"), DRAW_FLAKE_STREAK_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "splatState"), SPLAT_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "splatDrawColor"), DRAW_SPLAT_COLOR_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "translucentDepth"), TRANSLUCENT_DEPTH_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "flakeShapes"), FLAKE_SHAPES_UNIT);
                viewProjectionUniform = GL20.glGetUniformLocation(programId, "viewProjection");
                screenSizeUniform = GL20.glGetUniformLocation(programId, "screenSize");
                pixelsPerBlockUniform = GL20.glGetUniformLocation(programId, "pixelsPerBlock");
                cameraFromSplatOriginUniform = GL20.glGetUniformLocation(programId, "cameraFromSplatOrigin");
                flakeSizeUniform = GL20.glGetUniformLocation(programId, "flakeSize");
                shapeTileScalesUniform = GL20.glGetUniformLocation(programId, "shapeTileScales");
                clipAgainstTranslucentUniform = GL20.glGetUniformLocation(programId, "clipAgainstTranslucent");
                return;
            }
            GL20.glUseProgram(program.programId);
        }

        /**
         * Makes the next draw keep only fragments behind translucentDepth, or draw all fragments in front of main
         * target depth.
         */
        private void setClipAgainstTranslucent(boolean clip) {
            GL20.glUniform1i(clipAgainstTranslucentUniform, clip ? 1 : 0);
        }

        /**
         * Sets the projection uniforms of this frame on the program in use.
         */
        private void setUniforms(RenderTarget mainTarget, Vec3 camera, double flakeSize, float pixelsPerBlock) {
            viewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(viewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(pixelsPerBlockUniform, pixelsPerBlock);
            GL20.glUniform3f(cameraFromSplatOriginUniform, (float) (camera.x - splatOriginX),
                    (float) (camera.y - splatOriginY), (float) (camera.z - splatOriginZ));
            GL20.glUniform1f(flakeSizeUniform, (float) flakeSize);
            GL20.glUniform1fv(shapeTileScalesUniform, FlakeShapeTexture.tileScales());
        }
    }
}
