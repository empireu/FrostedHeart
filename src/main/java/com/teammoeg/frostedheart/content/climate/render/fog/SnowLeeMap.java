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
 * Builds and publishes the smoothed macro terrain surface S (512 by 512 R32F) and the wind-aligned relief lee map
 * (1024 by 1024 RG32F, holding combined lee height h in R and terrain slope w . grad S in G) on the GPU, advancing at
 * most one pass per frame while flakes keep reading the published maps without copy or flicker.
 * <p>
 * S rebuilds in 3 passes only when the FarHeights window moves or its no-leaves heights change, never on wind turns.
 * The lee map rebuilds in 9 passes whenever the wind turns past SnowLee.DIRECTION_REBUILD_RADIANS, the wind speed
 * changes past SnowLee.SPEED_REBUILD_SHARE, the fall speed or lee fill changes, or a new S is published. Calm wind
 * disables both without running passes.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class SnowLeeMap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TARGET_COUNT = 3;
    private static final int SMOOTH_PAIR_COUNT = 2;
    private static final int CHUNK_WIDTH = 16;

    private static final int[] textures = new int[TARGET_COUNT];
    private static final int[] framebuffers = new int[TARGET_COUNT];
    private static final int[] smoothWorkTextures = new int[SMOOTH_PAIR_COUNT];
    private static final int[] smoothWorkFramebuffers = new int[SMOOTH_PAIR_COUNT];
    private static final int[] smoothTextures = new int[SMOOTH_PAIR_COUNT];
    private static final int[] smoothFramebuffers = new int[SMOOTH_PAIR_COUNT];
    private static final SkyDrawTimer smoothPassTimer = new SkyDrawTimer();
    private static final SkyDrawTimer passTimer = new SkyDrawTimer();
    private static final SmoothBuildProgram smoothBuildProgram = new SmoothBuildProgram();
    private static final BuildProgram buildProgram = new BuildProgram();

    private static int publishedSlot = 0;
    private static int workSourceSlot = 1;
    private static int workTargetSlot = 2;
    private static int smoothPublishedSlot = 0;
    private static int smoothBackSlot = 1;

    @Nullable
    private static ClientLevel trackedLevel;

    private static boolean hasPublishedSmooth;
    private static int publishedSmoothMinX;
    private static int publishedSmoothMinZ;
    private static long publishedSmoothHeightsVersion;
    private static long publishedSmoothVersion;
    private static boolean rebuildingSmooth;
    private static int nextSmoothPass;
    private static int buildSmoothMinX;
    private static int buildSmoothMinZ;
    private static long buildSmoothHeightsVersion;
    private static long buildSmoothFillCount;

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
    private static long publishedLeeSmoothVersion;

    private static boolean rebuilding;
    private static int nextPass;
    private static int buildAnchorX;
    private static int buildAnchorZ;
    private static int buildFarHeightsMinX;
    private static int buildFarHeightsMinZ;
    private static int buildSmoothOriginX;
    private static int buildSmoothOriginZ;
    private static double buildDirX;
    private static double buildDirZ;
    private static double buildDirection;
    private static double buildWindSpeed;
    private static double buildFallSpeed;
    private static double buildLeeFill;
    private static double buildSlope;
    private static long buildLeeSmoothVersion;
    private static long buildFillCount;

    private static boolean lastCalm;
    private static boolean failed;
    private static long completedSmoothRebuilds;
    private static long smoothPassesSinceReport;
    private static long completedRebuilds;
    private static long passesSinceReport;

    private SnowLeeMap() {
    }

    /**
     * Clears published and in-progress state when the client level changes or is unloaded, so no old level reference
     * or old dimension's maps survive.
     */
    static void onLevelChanged(@Nullable ClientLevel level) {
        if (level != trackedLevel) {
            trackedLevel = level;
            hasPublishedSmooth = false;
            rebuildingSmooth = false;
            nextSmoothPass = 0;
            hasPublished = false;
            rebuilding = false;
            nextPass = 0;
        }
    }

    /**
     * Advances either the smoothed terrain surface S or the lee map by at most one GPU pass for the current frame.
     * Must be called inside SnowfallRenderer's GL state save/restore block (with scissor and cull disabled and units
     * 0..8 saved).
     */
    static void step(@Nullable ClientLevel level, @Nullable FarHeights heights, int noLeavesHeightTexture,
            WeatherWind wind) {
        onLevelChanged(level);
        if (failed || !SkySetting.SNOW_LEE.isOn() || level == null || heights == null || !heights.isPlaced()
                || noLeavesHeightTexture == 0) {
            rebuildingSmooth = false;
            nextSmoothPass = 0;
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
            rebuildingSmooth = false;
            nextSmoothPass = 0;
            return;
        }
        lastCalm = false;

        int smoothMinX = SnowLee.smoothMinCoordinate(heights.originChunkX());
        int smoothMinZ = SnowLee.smoothMinCoordinate(heights.originChunkZ());
        int anchorX = SnowLee.anchorCoordinate(heights.originChunkX());
        int anchorZ = SnowLee.anchorCoordinate(heights.originChunkZ());
        long heightsVersion = heights.noLeavesChangeCount();
        long fillCount = heights.fillCount();

        if (hasPublishedSmooth && (Math.abs((long) smoothMinX - publishedSmoothMinX) > SnowLee.MAX_ANCHOR_DRIFT_BLOCKS
                || Math.abs((long) smoothMinZ - publishedSmoothMinZ) > SnowLee.MAX_ANCHOR_DRIFT_BLOCKS)) {
            hasPublishedSmooth = false;
            hasPublished = false;
        }
        if (hasPublished && (Math.abs((long) anchorX - publishedAnchorX) > SnowLee.MAX_ANCHOR_DRIFT_BLOCKS
                || Math.abs((long) anchorZ - publishedAnchorZ) > SnowLee.MAX_ANCHOR_DRIFT_BLOCKS)) {
            hasPublished = false;
        }
        if (rebuildingSmooth && fillCount != buildSmoothFillCount) {
            rebuildingSmooth = false;
            nextSmoothPass = 0;
        }
        if (rebuilding && fillCount != buildFillCount) {
            rebuilding = false;
            nextPass = 0;
        }

        // Start a smooth terrain rebuild when no lee scan is running and S is stale:
        if (!rebuilding && !rebuildingSmooth && SnowLee.needsSmoothRebuild(hasPublishedSmooth, smoothMinX, smoothMinZ,
                heightsVersion, publishedSmoothMinX, publishedSmoothMinZ, publishedSmoothHeightsVersion)) {
            rebuildingSmooth = true;
            nextSmoothPass = 0;
            buildSmoothMinX = smoothMinX;
            buildSmoothMinZ = smoothMinZ;
            buildSmoothHeightsVersion = heightsVersion;
            buildSmoothFillCount = fillCount;
        }

        if (rebuildingSmooth) {
            if (!prepareTargets()) {
                rebuildingSmooth = false;
                return;
            }
            smoothPassTimer.begin();
            try {
                runOneSmoothPass(noLeavesHeightTexture);
            } finally {
                smoothPassTimer.end();
            }
            smoothPassesSinceReport++;
            if (nextSmoothPass == SnowLee.SMOOTH_PASSES - 1) {
                int finishedSlot = smoothBackSlot;
                smoothBackSlot = smoothPublishedSlot;
                smoothPublishedSlot = finishedSlot;
                publishedSmoothMinX = buildSmoothMinX;
                publishedSmoothMinZ = buildSmoothMinZ;
                publishedSmoothHeightsVersion = buildSmoothHeightsVersion;
                publishedSmoothVersion++;
                hasPublishedSmooth = true;
                rebuildingSmooth = false;
                nextSmoothPass = 0;
                completedSmoothRebuilds++;
            } else {
                nextSmoothPass++;
            }
            return;
        }

        if (!hasPublishedSmooth) {
            return;
        }

        double direction = wind.direction();
        double fallSpeed = SkySetting.SNOW_FALL_SPEED.get();
        double leeFill = SkySetting.SNOW_LEE_FILL.get();
        if (!rebuilding && SnowLee.needsRebuild(hasPublished, anchorX, anchorZ, publishedSmoothVersion, direction,
                windSpeed, fallSpeed, leeFill, publishedAnchorX, publishedAnchorZ, publishedLeeSmoothVersion,
                publishedDirection, publishedWindSpeed, publishedFallSpeed, publishedLeeFill)) {
            double slope = SnowLee.slope(fallSpeed, windSpeed, leeFill);
            if (Double.isFinite(slope)) {
                rebuilding = true;
                nextPass = 0;
                buildAnchorX = anchorX;
                buildAnchorZ = anchorZ;
                buildFarHeightsMinX = heights.originChunkX() * CHUNK_WIDTH;
                buildFarHeightsMinZ = heights.originChunkZ() * CHUNK_WIDTH;
                buildSmoothOriginX = publishedSmoothMinX;
                buildSmoothOriginZ = publishedSmoothMinZ;
                buildDirection = direction;
                buildDirX = Math.cos(direction);
                buildDirZ = Math.sin(direction);
                buildWindSpeed = windSpeed;
                buildFallSpeed = fallSpeed;
                buildLeeFill = leeFill;
                buildSlope = slope;
                buildLeeSmoothVersion = publishedSmoothVersion;
                buildFillCount = fillCount;
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
            publishedLeeSmoothVersion = buildLeeSmoothVersion;
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
     * Whether a published lee map and its smoothed terrain surface are ready for the flakes to read this frame.
     */
    static boolean isActive() {
        return !failed && SkySetting.SNOW_LEE.isOn() && hasPublished && hasPublishedSmooth && !lastCalm
                && textures[publishedSlot] != 0 && smoothTextures[smoothPublishedSlot] != 0;
    }

    static int texture() {
        return textures[publishedSlot];
    }

    static int smoothTexture() {
        return smoothTextures[smoothPublishedSlot];
    }

    static int publishedAnchorX() {
        return publishedAnchorX;
    }

    static int publishedAnchorZ() {
        return publishedAnchorZ;
    }

    static int publishedSmoothMinX() {
        return publishedSmoothMinX;
    }

    static int publishedSmoothMinZ() {
        return publishedSmoothMinZ;
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
     * A summary of the smoothed terrain and lee map state and pass cost for /calculatedSky snow.
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
        long smoothPasses = smoothPassesSinceReport;
        smoothPassesSinceReport = 0L;
        long passes = passesSinceReport;
        passesSinceReport = 0L;
        String state;
        if (!hasPublishedSmooth) {
            state = rebuildingSmooth
                    ? "building smooth pass " + nextSmoothPass + "/" + SnowLee.SMOOTH_PASSES : "waiting";
        } else if (!hasPublished) {
            state = rebuilding ? "building initial pass " + nextPass + "/" + SnowLee.TOTAL_PASSES : "waiting";
        } else if (rebuildingSmooth) {
            state = "on, rebuilding smooth pass " + nextSmoothPass + "/" + SnowLee.SMOOTH_PASSES;
        } else if (rebuilding) {
            state = "on, rebuilding pass " + nextPass + "/" + SnowLee.TOTAL_PASSES;
        } else {
            state = "on";
        }
        return String.format(Locale.ROOT,
                "lee %s (slope %.3f, reach %d, %d smooth rebuilds / %d passes, %d lee rebuilds / %d passes since the "
                        + "last report); smooth pass %s; lee pass %s",
                state, publishedSlope, SnowLee.REACH, completedSmoothRebuilds, smoothPasses, completedRebuilds, passes,
                smoothPassTimer.describe(), passTimer.describe());
    }

    private static void runOneSmoothPass(int noLeavesHeightTexture) {
        int targetFramebuffer;
        int sourceSmoothTexture;
        if (nextSmoothPass == 0) {
            targetFramebuffer = smoothWorkFramebuffers[0];
            sourceSmoothTexture = 0;
        } else if (nextSmoothPass == 1) {
            targetFramebuffer = smoothWorkFramebuffers[1];
            sourceSmoothTexture = smoothWorkTextures[0];
        } else {
            targetFramebuffer = smoothFramebuffers[smoothBackSlot];
            sourceSmoothTexture = smoothWorkTextures[1];
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
        RenderSystem.viewport(0, 0, SnowLee.SMOOTH_SIZE, SnowLee.SMOOTH_SIZE);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SnowfallRenderer.NO_LEAVES_HEIGHT_UNIT);
        RenderSystem.bindTexture(noLeavesHeightTexture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SnowfallRenderer.SMOOTH_HEIGHT_UNIT);
        RenderSystem.bindTexture(sourceSmoothTexture);
        smoothBuildProgram.use();
        smoothBuildProgram.setUniforms(nextSmoothPass, buildSmoothMinX, buildSmoothMinZ);
        SkyGlPass.drawFullScreenQuad();
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
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SnowfallRenderer.SMOOTH_HEIGHT_UNIT);
        RenderSystem.bindTexture(smoothTextures[smoothPublishedSlot]);
        int stride = nextPass == 0 ? 0 : SnowLee.passStride(nextPass);
        boolean applyAcrossMin = nextPass == SnowLee.TOTAL_PASSES - 1;
        buildProgram.use();
        buildProgram.setUniforms(nextPass, stride, applyAcrossMin, buildAnchorX, buildAnchorZ, buildFarHeightsMinX,
                buildFarHeightsMinZ, buildSmoothOriginX, buildSmoothOriginZ, buildDirX, buildDirZ, buildSlope);
        SkyGlPass.drawFullScreenQuad();
    }

    private static boolean prepareTargets() {
        if (textures[0] != 0) {
            return true;
        }
        int[] createdTextures = new int[TARGET_COUNT];
        int[] createdFramebuffers = new int[TARGET_COUNT];
        int[] createdSmoothWorkTextures = new int[SMOOTH_PAIR_COUNT];
        int[] createdSmoothWorkFramebuffers = new int[SMOOTH_PAIR_COUNT];
        int[] createdSmoothTextures = new int[SMOOTH_PAIR_COUNT];
        int[] createdSmoothFramebuffers = new int[SMOOTH_PAIR_COUNT];
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            for (int slot = 0; slot < TARGET_COUNT; slot++) {
                createdTextures[slot] = createFloatTexture(GL30.GL_RG32F, GL30.GL_RG, SnowLee.MAP_SIZE, GL11.GL_LINEAR);
                createdFramebuffers[slot] = createTargetFramebuffer(createdTextures[slot], "snowfall lee map");
            }
            for (int slot = 0; slot < SMOOTH_PAIR_COUNT; slot++) {
                createdSmoothWorkTextures[slot] = createFloatTexture(GL30.GL_RG32F, GL30.GL_RG, SnowLee.SMOOTH_SIZE,
                        GL11.GL_NEAREST);
                createdSmoothWorkFramebuffers[slot] = createTargetFramebuffer(createdSmoothWorkTextures[slot],
                        "snowfall smooth terrain work");
                createdSmoothTextures[slot] = createFloatTexture(GL30.GL_R32F, GL11.GL_RED, SnowLee.SMOOTH_SIZE,
                        GL11.GL_NEAREST);
                createdSmoothFramebuffers[slot] = createTargetFramebuffer(createdSmoothTextures[slot],
                        "snowfall smooth terrain");
            }
        } catch (RuntimeException exception) {
            deleteTargets(createdTextures, createdFramebuffers);
            deleteTargets(createdSmoothWorkTextures, createdSmoothWorkFramebuffers);
            deleteTargets(createdSmoothTextures, createdSmoothFramebuffers);
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
        System.arraycopy(createdSmoothWorkTextures, 0, smoothWorkTextures, 0, SMOOTH_PAIR_COUNT);
        System.arraycopy(createdSmoothWorkFramebuffers, 0, smoothWorkFramebuffers, 0, SMOOTH_PAIR_COUNT);
        System.arraycopy(createdSmoothTextures, 0, smoothTextures, 0, SMOOTH_PAIR_COUNT);
        System.arraycopy(createdSmoothFramebuffers, 0, smoothFramebuffers, 0, SMOOTH_PAIR_COUNT);
        publishedSlot = 0;
        workSourceSlot = 1;
        workTargetSlot = 2;
        smoothPublishedSlot = 0;
        smoothBackSlot = 1;
        hasPublished = false;
        hasPublishedSmooth = false;
        return true;
    }

    private static int createFloatTexture(int internalFormat, int format, int size, int filter) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, size, size, 0, format, GL11.GL_FLOAT,
                (FloatBuffer) null);
        return texture;
    }

    private static int createTargetFramebuffer(int texture, String label) {
        int framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(framebuffer);
            throw new IllegalStateException("The " + label + " framebuffer is incomplete with status " + status);
        }
        return framebuffer;
    }

    private static void deleteTargets(int[] targetTextures, int[] targetFramebuffers) {
        for (int slot = 0; slot < targetTextures.length; slot++) {
            if (targetTextures[slot] != 0) {
                GL11.glDeleteTextures(targetTextures[slot]);
            }
            if (targetFramebuffers[slot] != 0) {
                GL30.glDeleteFramebuffers(targetFramebuffers[slot]);
            }
        }
    }

    private static final class SmoothBuildProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int passIndexUniform;
        private int farHeightsMinUniform;

        private void use() {
            Shader newFragment = FHShaders.getSnowSmoothBuild();
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
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "noLeavesHeights"),
                        SnowfallRenderer.NO_LEAVES_HEIGHT_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "smoothSource"),
                        SnowfallRenderer.SMOOTH_HEIGHT_UNIT);
                passIndexUniform = GL20.glGetUniformLocation(programId, "passIndex");
                farHeightsMinUniform = GL20.glGetUniformLocation(programId, "farHeightsMin");
            }
            program.use();
        }

        private void setUniforms(int passIndex, int farHeightsMinX, int farHeightsMinZ) {
            GL20.glUniform1i(passIndexUniform, passIndex);
            GL20.glUniform2i(farHeightsMinUniform, farHeightsMinX, farHeightsMinZ);
        }
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
        private int smoothMinUniform;
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
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "smoothHeights"),
                        SnowfallRenderer.SMOOTH_HEIGHT_UNIT);
                passIndexUniform = GL20.glGetUniformLocation(programId, "passIndex");
                strideUniform = GL20.glGetUniformLocation(programId, "stride");
                applyAcrossMinUniform = GL20.glGetUniformLocation(programId, "applyAcrossMin");
                leeAnchorUniform = GL20.glGetUniformLocation(programId, "leeAnchor");
                farHeightsMinUniform = GL20.glGetUniformLocation(programId, "farHeightsMin");
                smoothMinUniform = GL20.glGetUniformLocation(programId, "smoothMin");
                leeWindDirectionUniform = GL20.glGetUniformLocation(programId, "leeWindDirection");
                leeSlopeUniform = GL20.glGetUniformLocation(programId, "leeSlope");
            }
            program.use();
        }

        private void setUniforms(int passIndex, int stride, boolean applyAcrossMin, int anchorX, int anchorZ,
                int farHeightsMinX, int farHeightsMinZ, int smoothMinX, int smoothMinZ, double dirX, double dirZ,
                double slope) {
            GL20.glUniform1i(passIndexUniform, passIndex);
            GL20.glUniform1i(strideUniform, stride);
            GL20.glUniform1i(applyAcrossMinUniform, applyAcrossMin ? 1 : 0);
            GL20.glUniform2i(leeAnchorUniform, anchorX, anchorZ);
            GL20.glUniform2i(farHeightsMinUniform, farHeightsMinX, farHeightsMinZ);
            GL20.glUniform2i(smoothMinUniform, smoothMinX, smoothMinZ);
            GL20.glUniform2f(leeWindDirectionUniform, (float) dirX, (float) dirZ);
            GL20.glUniform1f(leeSlopeUniform, (float) slope);
        }
    }
}
