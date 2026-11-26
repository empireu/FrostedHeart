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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyDrawTimer;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Builds and publishes the wind-aligned snowfall lee map on the GPU, one pass per frame into two working R32F targets
 * while the flakes keep reading a third published R32F target. When the final pass completes, its target swaps into
 * the published slot with no copy and no flicker.
 * <p>
 * A rebuild starts whenever the wind turns past SnowLee.DIRECTION_REBUILD_RADIANS, the wind speed changes past
 * SnowLee.SPEED_REBUILD_SHARE, the fall speed or lee fill changes, the FarHeights window moves, or its no-leaves
 * heights change. Calm wind disables the lee map without running passes.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class SnowLeeMap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TARGET_COUNT = 3;
    private static final int CHUNK_WIDTH = 16;

    private static final int[] textures = new int[TARGET_COUNT];
    private static final int[] framebuffers = new int[TARGET_COUNT];
    private static final SkyDrawTimer passTimer = new SkyDrawTimer();
    private static final BuildProgram buildProgram = new BuildProgram();

    private static int publishedSlot = 0;
    private static int workSourceSlot = 1;
    private static int workTargetSlot = 2;

    @Nullable
    private static ClientLevel trackedLevel;
    private static boolean hasPublished;
    private static int publishedAnchorX;
    private static int publishedAnchorZ;
    private static double publishedDirX;
    private static double publishedDirZ;
    private static double publishedDirection;
    private static double publishedWindSpeed;
    private static double publishedFallSpeed;
    private static double publishedLeeFill;
    private static double publishedSlope;
    private static long publishedHeightsVersion;

    private static boolean rebuilding;
    private static int nextPass;
    private static int buildAnchorX;
    private static int buildAnchorZ;
    private static int buildFarHeightsMinX;
    private static int buildFarHeightsMinZ;
    private static double buildDirX;
    private static double buildDirZ;
    private static double buildDirection;
    private static double buildWindSpeed;
    private static double buildFallSpeed;
    private static double buildLeeFill;
    private static double buildSlope;
    private static long buildHeightsVersion;
    private static long buildFillCount;

    private static boolean lastCalm;
    private static boolean failed;
    private static long completedRebuilds;
    private static long passesSinceReport;

    private SnowLeeMap() {
    }

    /**
     * Clears published and in-progress state when the client level changes or is unloaded, so no old level reference
     * or old dimension's lee map survives.
     */
    static void onLevelChanged(@Nullable ClientLevel level) {
        if (level != trackedLevel) {
            trackedLevel = level;
            hasPublished = false;
            rebuilding = false;
            nextPass = 0;
        }
    }

    /**
     * Advances the lee map by at most one GPU pass for the current frame. Must be called inside SnowfallRenderer's GL
     * state save/restore block (with scissor and cull disabled and units 0..7 saved).
     */
    static void step(@Nullable ClientLevel level, @Nullable FarHeights heights, int noLeavesHeightTexture,
            WeatherWind wind) {
        onLevelChanged(level);
        if (failed || !SkySetting.SNOW_LEE.isOn() || level == null || heights == null || !heights.isPlaced()
                || noLeavesHeightTexture == 0) {
            rebuilding = false;
            nextPass = 0;
            lastCalm = false;
            return;
        }
        double windSpeed = wind.speed();
        if (SnowLee.isCalm(windSpeed)) {
            lastCalm = true;
            hasPublished = false;
            rebuilding = false;
            nextPass = 0;
            return;
        }
        lastCalm = false;

        int anchorX = SnowLee.anchorCoordinate(heights.originChunkX());
        int anchorZ = SnowLee.anchorCoordinate(heights.originChunkZ());
        if (hasPublished && (Math.abs((long) anchorX - publishedAnchorX) > SnowLee.MAX_ANCHOR_DRIFT_BLOCKS
                || Math.abs((long) anchorZ - publishedAnchorZ) > SnowLee.MAX_ANCHOR_DRIFT_BLOCKS)) {
            hasPublished = false;
        }
        if (rebuilding && heights.fillCount() != buildFillCount) {
            rebuilding = false;
            nextPass = 0;
        }

        double direction = wind.direction();
        double fallSpeed = SkySetting.SNOW_FALL_SPEED.get();
        double leeFill = SkySetting.SNOW_LEE_FILL.get();
        long heightsVersion = heights.noLeavesChangeCount();
        if (!rebuilding && SnowLee.needsRebuild(hasPublished, anchorX, anchorZ, heightsVersion, direction, windSpeed,
                fallSpeed, leeFill, publishedAnchorX, publishedAnchorZ, publishedHeightsVersion, publishedDirection,
                publishedWindSpeed, publishedFallSpeed, publishedLeeFill)) {
            double slope = SnowLee.slope(fallSpeed, windSpeed, leeFill);
            if (Double.isFinite(slope)) {
                rebuilding = true;
                nextPass = 0;
                buildAnchorX = anchorX;
                buildAnchorZ = anchorZ;
                buildFarHeightsMinX = heights.originChunkX() * CHUNK_WIDTH;
                buildFarHeightsMinZ = heights.originChunkZ() * CHUNK_WIDTH;
                buildDirection = direction;
                buildDirX = Math.cos(direction);
                buildDirZ = Math.sin(direction);
                buildWindSpeed = windSpeed;
                buildFallSpeed = fallSpeed;
                buildLeeFill = leeFill;
                buildSlope = slope;
                buildHeightsVersion = heightsVersion;
                buildFillCount = heights.fillCount();
            }
        }

        if (!rebuilding) {
            return;
        }
        if (!prepareTargets()) {
            rebuilding = false;
            return;
        }

        passTimer.begin();
        try {
            runOnePass(noLeavesHeightTexture);
        } finally {
            passTimer.end();
        }
        passesSinceReport++;

        if (nextPass == SnowLee.TOTAL_PASSES - 1) {
            int finishedSlot = workTargetSlot;
            workTargetSlot = publishedSlot;
            publishedSlot = finishedSlot;
            publishedAnchorX = buildAnchorX;
            publishedAnchorZ = buildAnchorZ;
            publishedDirX = buildDirX;
            publishedDirZ = buildDirZ;
            publishedDirection = buildDirection;
            publishedWindSpeed = buildWindSpeed;
            publishedFallSpeed = buildFallSpeed;
            publishedLeeFill = buildLeeFill;
            publishedSlope = buildSlope;
            publishedHeightsVersion = buildHeightsVersion;
            hasPublished = true;
            rebuilding = false;
            nextPass = 0;
            completedRebuilds++;
        } else {
            int writtenSlot = workTargetSlot;
            workTargetSlot = workSourceSlot;
            workSourceSlot = writtenSlot;
            nextPass++;
        }
    }

    /**
     * Whether a published lee map is ready for the flakes to read this frame.
     */
    static boolean isActive() {
        return !failed && SkySetting.SNOW_LEE.isOn() && hasPublished && !lastCalm && textures[publishedSlot] != 0;
    }

    static int texture() {
        return textures[publishedSlot];
    }

    static int publishedAnchorX() {
        return publishedAnchorX;
    }

    static int publishedAnchorZ() {
        return publishedAnchorZ;
    }

    static double publishedDirX() {
        return publishedDirX;
    }

    static double publishedDirZ() {
        return publishedDirZ;
    }

    static double publishedSlope() {
        return publishedSlope;
    }

    static void clearFailure() {
        failed = false;
    }

    /**
     * A summary of the lee map state and pass cost for /calculatedSky snow.
     */
    static String describe() {
        if (failed) {
            return "lee stopped after an error, see the log";
        }
        if (!SkySetting.SNOW_LEE.isOn()) {
            return "lee off";
        }
        if (lastCalm) {
            return "lee calm (no lee)";
        }
        long passes = passesSinceReport;
        passesSinceReport = 0L;
        String state;
        if (!hasPublished) {
            state = rebuilding ? "building initial pass " + nextPass + "/" + SnowLee.TOTAL_PASSES : "waiting";
        } else if (rebuilding) {
            state = "on, rebuilding pass " + nextPass + "/" + SnowLee.TOTAL_PASSES;
        } else {
            state = "on";
        }
        return String.format(Locale.ROOT,
                "lee %s (slope %.3f, %d rebuilds, %d passes since the last report); lee pass %s",
                state, publishedSlope, completedRebuilds, passes, passTimer.describe());
    }

    private static void runOnePass(int noLeavesHeightTexture) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffers[workTargetSlot]);
        RenderSystem.viewport(0, 0, SnowLee.MAP_SIZE, SnowLee.MAP_SIZE);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        // Bind workSourceSlot (never workTargetSlot) to LEE_MAP_UNIT so there is no FBO feedback loop:
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SnowfallRenderer.LEE_MAP_UNIT);
        RenderSystem.bindTexture(textures[workSourceSlot]);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SnowfallRenderer.NO_LEAVES_HEIGHT_UNIT);
        RenderSystem.bindTexture(noLeavesHeightTexture);
        int stride = nextPass == 0 ? 0 : SnowLee.passStride(nextPass);
        boolean applyAcrossMin = nextPass == SnowLee.TOTAL_PASSES - 1;
        buildProgram.use();
        buildProgram.setUniforms(nextPass, stride, applyAcrossMin, buildAnchorX, buildAnchorZ, buildFarHeightsMinX,
                buildFarHeightsMinZ, buildDirX, buildDirZ, buildSlope);
        SkyGlPass.drawFullScreenQuad();
    }

    private static boolean prepareTargets() {
        if (textures[0] != 0) {
            return true;
        }
        int[] createdTextures = new int[TARGET_COUNT];
        int[] createdFramebuffers = new int[TARGET_COUNT];
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            for (int slot = 0; slot < TARGET_COUNT; slot++) {
                createdTextures[slot] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, createdTextures[slot]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, SnowLee.MAP_SIZE, SnowLee.MAP_SIZE, 0,
                        GL11.GL_RED, GL11.GL_FLOAT, (FloatBuffer) null);
                createdFramebuffers[slot] = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, createdFramebuffers[slot]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        createdTextures[slot], 0);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The snowfall lee map framebuffer is incomplete with status "
                            + status);
                }
            }
        } catch (RuntimeException exception) {
            for (int slot = 0; slot < TARGET_COUNT; slot++) {
                if (createdTextures[slot] != 0) {
                    GL11.glDeleteTextures(createdTextures[slot]);
                }
                if (createdFramebuffers[slot] != 0) {
                    GL30.glDeleteFramebuffers(createdFramebuffers[slot]);
                }
            }
            failed = true;
            LOGGER.error("The snowfall lee map cannot be made, so flakes fall without a lee map.", exception);
            return false;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
        System.arraycopy(createdTextures, 0, textures, 0, TARGET_COUNT);
        System.arraycopy(createdFramebuffers, 0, framebuffers, 0, TARGET_COUNT);
        publishedSlot = 0;
        workSourceSlot = 1;
        workTargetSlot = 2;
        hasPublished = false;
        return true;
    }

    private static final class BuildProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int passIndexUniform;
        private int strideUniform;
        private int applyAcrossMinUniform;
        private int leeAnchorUniform;
        private int farHeightsMinUniform;
        private int leeWindDirectionUniform;
        private int leeSlopeUniform;

        private void use() {
            Shader newFragment = FHShaders.getSnowLeeBuild();
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
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "sourceMap"), SnowfallRenderer.LEE_MAP_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "noLeavesHeights"),
                        SnowfallRenderer.NO_LEAVES_HEIGHT_UNIT);
                passIndexUniform = GL20.glGetUniformLocation(programId, "passIndex");
                strideUniform = GL20.glGetUniformLocation(programId, "stride");
                applyAcrossMinUniform = GL20.glGetUniformLocation(programId, "applyAcrossMin");
                leeAnchorUniform = GL20.glGetUniformLocation(programId, "leeAnchor");
                farHeightsMinUniform = GL20.glGetUniformLocation(programId, "farHeightsMin");
                leeWindDirectionUniform = GL20.glGetUniformLocation(programId, "leeWindDirection");
                leeSlopeUniform = GL20.glGetUniformLocation(programId, "leeSlope");
            }
            program.use();
        }

        private void setUniforms(int passIndex, int stride, boolean applyAcrossMin, int anchorX, int anchorZ,
                int farHeightsMinX, int farHeightsMinZ, double dirX, double dirZ, double slope) {
            GL20.glUniform1i(passIndexUniform, passIndex);
            GL20.glUniform1i(strideUniform, stride);
            GL20.glUniform1i(applyAcrossMinUniform, applyAcrossMin ? 1 : 0);
            GL20.glUniform2i(leeAnchorUniform, anchorX, anchorZ);
            GL20.glUniform2i(farHeightsMinUniform, farHeightsMinX, farHeightsMinZ);
            GL20.glUniform2f(leeWindDirectionUniform, (float) dirX, (float) dirZ);
            GL20.glUniform1f(leeSlopeUniform, (float) slope);
        }
    }
}
