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
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyDrawTimer;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/**
 * Builds and publishes the wind-direction-free flat fog cavity enclosure map (512 by 512 RG32F holding flat enclosure
 * surface h_cav in R and lower opposing rim height in G) on the GPU, one pass per frame over 12 passes while the fog
 * walk and snowfall keep reading the published map untouched. Intermediate passes track sloped walls in RGBA32F and
 * raw wall heights in RGBA16F via MRT. Rebuilds only when the FarHeights window moves or its no-leaves heights change,
 * never when the wind turns or changes speed.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class FogCavityMap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WORK_COUNT = 2;
    private static final int PUBLISHED_COUNT = 2;
    private static final int CHUNK_WIDTH = 16;
    /**
     * Texture units used during the cavity build pass, all within WeatherFogRenderer's saved SkyGlPass(9) range and
     * rebound by WeatherFogRenderer before the fog passes begin.
     */
    private static final int SOURCE_UNIT = 0;
    private static final int HEIGHTS_UNIT = 1;
    private static final int CARDINAL_UNIT = 2;
    private static final int RAW_SOURCE_UNIT = 3;

    private static final int[] workTextures = new int[WORK_COUNT];
    private static final int[] workRawTextures = new int[WORK_COUNT];
    private static final int[] workFramebuffers = new int[WORK_COUNT];
    private static final int[] publishedTextures = new int[PUBLISHED_COUNT];
    private static final int[] publishedFramebuffers = new int[PUBLISHED_COUNT];
    private static final IntBuffer workDrawBuffers = BufferUtils.createIntBuffer(2);
    private static final IntBuffer singleDrawBuffers = BufferUtils.createIntBuffer(2);
    private static int cardinalTexture;
    private static int cardinalFramebuffer;

    private static final SkyDrawTimer passTimer = new SkyDrawTimer();
    private static final BuildProgram buildProgram = new BuildProgram();

    private static int workSourceSlot = 0;
    private static int workTargetSlot = 1;
    private static int publishedSlot = 0;
    private static int backSlot = 1;

    @Nullable
    private static ClientLevel trackedLevel;
    private static boolean hasPublished;
    private static int publishedMinX;
    private static int publishedMinZ;
    private static long publishedHeightsVersion;

    private static boolean rebuilding;
    private static int nextPass;
    private static int buildMinX;
    private static int buildMinZ;
    private static int buildFarHeightsMinX;
    private static int buildFarHeightsMinZ;
    private static long buildHeightsVersion;
    private static long buildFillCount;

    private static boolean failed;
    private static long completedRebuilds;
    private static long passesSinceReport;

    private FogCavityMap() {
    }

    /**
     * Clears published and in-progress state when the client level changes or is unloaded.
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
     * Advances the cavity enclosure map by at most one GPU pass for the current frame. Must be called inside
     * WeatherFogRenderer's GL state save/restore block (with scissor, cull and blend disabled) before the fog passes
     * bind their textures.
     */
    static void step(@Nullable ClientLevel level, @Nullable FarHeights heights, int noLeavesHeightTexture) {
        onLevelChanged(level);
        if (failed || level == null || heights == null || !heights.isPlaced() || noLeavesHeightTexture == 0) {
            rebuilding = false;
            nextPass = 0;
            return;
        }
        int minX = FogCavity.minCoordinate(heights.originChunkX());
        int minZ = FogCavity.minCoordinate(heights.originChunkZ());
        long heightsVersion = heights.noLeavesChangeCount();
        long fillCount = heights.fillCount();

        if (hasPublished && (Math.abs((long) minX - publishedMinX) > FogCavity.MAX_DRIFT_BLOCKS
                || Math.abs((long) minZ - publishedMinZ) > FogCavity.MAX_DRIFT_BLOCKS)) {
            hasPublished = false;
        }
        if (rebuilding && fillCount != buildFillCount) {
            rebuilding = false;
            nextPass = 0;
        }

        if (!rebuilding && FogCavity.needsRebuild(hasPublished, minX, minZ, heightsVersion, publishedMinX,
                publishedMinZ, publishedHeightsVersion)) {
            rebuilding = true;
            nextPass = 0;
            workSourceSlot = 0;
            workTargetSlot = 1;
            buildMinX = minX;
            buildMinZ = minZ;
            buildFarHeightsMinX = heights.originChunkX() * CHUNK_WIDTH;
            buildFarHeightsMinZ = heights.originChunkZ() * CHUNK_WIDTH;
            buildHeightsVersion = heightsVersion;
            buildFillCount = fillCount;
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

        if (nextPass == FogCavity.TOTAL_PASSES - 1) {
            int finishedSlot = backSlot;
            backSlot = publishedSlot;
            publishedSlot = finishedSlot;
            publishedMinX = buildMinX;
            publishedMinZ = buildMinZ;
            publishedHeightsVersion = buildHeightsVersion;
            hasPublished = true;
            rebuilding = false;
            nextPass = 0;
            completedRebuilds++;
        } else {
            if (nextPass == 0 || nextPass == FogCavity.DIAGONAL_FIRST_PASS) {
                workSourceSlot = 0;
                workTargetSlot = 1;
            } else if (nextPass < FogCavity.DIAGONAL_FIRST_PASS - 1
                    || nextPass > FogCavity.DIAGONAL_FIRST_PASS) {
                int written = workTargetSlot;
                workTargetSlot = workSourceSlot;
                workSourceSlot = written;
            }
            nextPass++;
        }
    }

    /**
     * Whether a published cavity enclosure map is ready for the fog walk to read this frame.
     */
    static boolean isActive() {
        return !failed && hasPublished && publishedTextures[publishedSlot] != 0;
    }

    static int texture() {
        return publishedTextures[publishedSlot];
    }

    static int publishedMinX() {
        return publishedMinX;
    }

    static int publishedMinZ() {
        return publishedMinZ;
    }

    static void clearFailure() {
        failed = false;
    }

    /**
     * Reads back the published 512 by 512 cavity enclosure map for /calculatedSky fog selftest. Only called by the
     * self test command, so it allocates its readback buffer and array.
     */
    @Nullable
    static float[] readbackPublished() {
        if (!isActive()) {
            return null;
        }
        int texelCount = FogCavity.MAP_SIZE * FogCavity.MAP_SIZE;
        FloatBuffer buffer = BufferUtils.createFloatBuffer(texelCount);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL12.GL_PACK_IMAGE_HEIGHT, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_PACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, publishedTextures[publishedSlot]);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, GL11.GL_FLOAT, buffer);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        float[] result = new float[texelCount];
        buffer.get(result);
        return result;
    }

    /**
     * A summary of the cavity enclosure map state and pass cost for /calculatedSky fog.
     */
    static String describe(double effectiveFloor) {
        if (failed) {
            return "cavity stopped after an error, see the log";
        }
        if (!(effectiveFloor < 1.0)) {
            return "cavity off (effective floor 1.00)";
        }
        long passes = passesSinceReport;
        passesSinceReport = 0L;
        String state;
        if (!hasPublished) {
            state = rebuilding ? "building initial pass " + nextPass + "/" + FogCavity.TOTAL_PASSES : "waiting";
        } else if (rebuilding) {
            state = "on, rebuilding pass " + nextPass + "/" + FogCavity.TOTAL_PASSES;
        } else {
            state = "on";
        }
        return String.format(Locale.ROOT,
                "cavity %s (floor %.2f, %d rebuilds, %d passes since the last report); cavity pass %s",
                state, effectiveFloor, completedRebuilds, passes, passTimer.describe());
    }

    private static void runOnePass(int noLeavesHeightTexture) {
        int targetFramebuffer;
        int sourceTex = 0;
        int sourceRawTex = 0;
        int stride = 0;
        boolean writesWorkPair = false;
        int cardinalFinalPass = FogCavity.DIAGONAL_FIRST_PASS - 1;
        int diagonalFinalPass = FogCavity.TOTAL_PASSES - 1;
        if (nextPass == 0) {
            targetFramebuffer = workFramebuffers[0];
            writesWorkPair = true;
        } else if (nextPass < cardinalFinalPass) {
            targetFramebuffer = workFramebuffers[workTargetSlot];
            sourceTex = workTextures[workSourceSlot];
            sourceRawTex = workRawTextures[workSourceSlot];
            stride = FogCavity.cardinalStride(nextPass);
            writesWorkPair = true;
        } else if (nextPass == cardinalFinalPass) {
            targetFramebuffer = cardinalFramebuffer;
            sourceTex = workTextures[workSourceSlot];
            sourceRawTex = workRawTextures[workSourceSlot];
            stride = FogCavity.cardinalStride(nextPass);
        } else if (nextPass == FogCavity.DIAGONAL_FIRST_PASS) {
            targetFramebuffer = workFramebuffers[0];
            writesWorkPair = true;
        } else if (nextPass < diagonalFinalPass) {
            targetFramebuffer = workFramebuffers[workTargetSlot];
            sourceTex = workTextures[workSourceSlot];
            sourceRawTex = workRawTextures[workSourceSlot];
            stride = FogCavity.diagonalStride(nextPass - FogCavity.DIAGONAL_FIRST_PASS);
            writesWorkPair = true;
        } else {
            targetFramebuffer = publishedFramebuffers[backSlot];
            sourceTex = workTextures[workSourceSlot];
            sourceRawTex = workRawTextures[workSourceSlot];
            stride = FogCavity.diagonalStride(nextPass - FogCavity.DIAGONAL_FIRST_PASS);
        }

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
        RenderSystem.viewport(0, 0, FogCavity.MAP_SIZE, FogCavity.MAP_SIZE);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SOURCE_UNIT);
        RenderSystem.bindTexture(sourceTex);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + HEIGHTS_UNIT);
        RenderSystem.bindTexture(noLeavesHeightTexture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + CARDINAL_UNIT);
        RenderSystem.bindTexture(nextPass == diagonalFinalPass ? cardinalTexture : 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + RAW_SOURCE_UNIT);
        RenderSystem.bindTexture(sourceRawTex);
        buildProgram.use();
        if (writesWorkPair) {
            buildProgram.setWorkDrawBuffers();
        } else {
            buildProgram.setSingleDrawBuffers();
        }
        buildProgram.setUniforms(nextPass, stride, buildMinX, buildMinZ, buildFarHeightsMinX, buildFarHeightsMinZ);
        SkyGlPass.drawFullScreenQuad();
    }

    private static boolean prepareTargets() {
        if (publishedTextures[0] != 0) {
            return true;
        }
        int[] createdWorkTextures = new int[WORK_COUNT];
        int[] createdWorkRawTextures = new int[WORK_COUNT];
        int[] createdWorkFramebuffers = new int[WORK_COUNT];
        int[] createdPublishedTextures = new int[PUBLISHED_COUNT];
        int[] createdPublishedFramebuffers = new int[PUBLISHED_COUNT];
        int createdCardinalTexture = 0;
        int createdCardinalFramebuffer = 0;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            for (int slot = 0; slot < WORK_COUNT; slot++) {
                createdWorkTextures[slot] = createFloatTexture(GL30.GL_RGBA32F, GL11.GL_RGBA);
                createdWorkRawTextures[slot] = createFloatTexture(GL30.GL_RGBA16F, GL11.GL_RGBA);
                createdWorkFramebuffers[slot] = createPairFramebuffer(createdWorkTextures[slot],
                        createdWorkRawTextures[slot], "fog cavity work");
            }
            for (int slot = 0; slot < PUBLISHED_COUNT; slot++) {
                createdPublishedTextures[slot] = createFloatTexture(GL30.GL_RG32F, GL30.GL_RG);
                createdPublishedFramebuffers[slot] = createTargetFramebuffer(createdPublishedTextures[slot],
                        "fog cavity");
            }
            createdCardinalTexture = createFloatTexture(GL30.GL_RG32F, GL30.GL_RG);
            createdCardinalFramebuffer = createTargetFramebuffer(createdCardinalTexture, "fog cavity cardinal");
        } catch (RuntimeException exception) {
            deleteTargets(createdWorkTextures, createdWorkFramebuffers);
            deleteTexturesOnly(createdWorkRawTextures);
            deleteTargets(createdPublishedTextures, createdPublishedFramebuffers);
            if (createdCardinalTexture != 0) {
                GL11.glDeleteTextures(createdCardinalTexture);
            }
            if (createdCardinalFramebuffer != 0) {
                GL30.glDeleteFramebuffers(createdCardinalFramebuffer);
            }
            failed = true;
            LOGGER.error("The fog cavity enclosure map cannot be made, so cavities keep open-air fog density.",
                    exception);
            return false;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
        System.arraycopy(createdWorkTextures, 0, workTextures, 0, WORK_COUNT);
        System.arraycopy(createdWorkRawTextures, 0, workRawTextures, 0, WORK_COUNT);
        System.arraycopy(createdWorkFramebuffers, 0, workFramebuffers, 0, WORK_COUNT);
        System.arraycopy(createdPublishedTextures, 0, publishedTextures, 0, PUBLISHED_COUNT);
        System.arraycopy(createdPublishedFramebuffers, 0, publishedFramebuffers, 0, PUBLISHED_COUNT);
        cardinalTexture = createdCardinalTexture;
        cardinalFramebuffer = createdCardinalFramebuffer;
        workSourceSlot = 0;
        workTargetSlot = 1;
        publishedSlot = 0;
        backSlot = 1;
        hasPublished = false;
        return true;
    }

    private static int createFloatTexture(int internalFormat, int format) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, FogCavity.MAP_SIZE, FogCavity.MAP_SIZE, 0, format,
                GL11.GL_FLOAT, (FloatBuffer) null);
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

    private static int createPairFramebuffer(int slopedTexture, int rawTexture, String label) {
        int framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                slopedTexture, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D,
                rawTexture, 0);
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

    private static void deleteTexturesOnly(int[] targetTextures) {
        for (int slot = 0; slot < targetTextures.length; slot++) {
            if (targetTextures[slot] != 0) {
                GL11.glDeleteTextures(targetTextures[slot]);
            }
        }
    }

    private static final class BuildProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int passIndexUniform;
        private int strideUniform;
        private int cavityMinUniform;
        private int farHeightsMinUniform;
        private int slopedOutput;
        private int rawOutput;

        private void use() {
            Shader newFragment = FHShaders.getFogCavityBuild();
            if (program == null || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                int createdSlopedOutput;
                int createdRawOutput;
                try {
                    created.attach(FHShaders.IMAGE_V).attach(newFragment);
                    created.linkProgram();
                    createdSlopedOutput = GL30.glGetFragDataLocation(created.programId, "outSloped");
                    createdRawOutput = GL30.glGetFragDataLocation(created.programId, "outRaw");
                    if (createdSlopedOutput < 0 || createdSlopedOutput > 1 || createdRawOutput < 0
                            || createdRawOutput > 1 || createdSlopedOutput == createdRawOutput) {
                        throw new IllegalStateException("The fog cavity build writes its outputs to locations "
                                + createdSlopedOutput + " and " + createdRawOutput + " instead of 0 and 1");
                    }
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                fragment = newFragment;
                slopedOutput = createdSlopedOutput;
                rawOutput = createdRawOutput;
                program.use();
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "sourceMap"), SOURCE_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "sourceRawMap"), RAW_SOURCE_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "noLeavesHeights"), HEIGHTS_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "cardinalMap"), CARDINAL_UNIT);
                passIndexUniform = GL20.glGetUniformLocation(programId, "passIndex");
                strideUniform = GL20.glGetUniformLocation(programId, "stride");
                cavityMinUniform = GL20.glGetUniformLocation(programId, "cavityMin");
                farHeightsMinUniform = GL20.glGetUniformLocation(programId, "farHeightsMin");
            }
            program.use();
        }

        private void setWorkDrawBuffers() {
            workDrawBuffers.clear();
            workDrawBuffers.put(slopedOutput, GL30.GL_COLOR_ATTACHMENT0);
            workDrawBuffers.put(rawOutput, GL30.GL_COLOR_ATTACHMENT1);
            GL20.glDrawBuffers(workDrawBuffers);
        }

        private void setSingleDrawBuffers() {
            singleDrawBuffers.clear();
            singleDrawBuffers.put(slopedOutput, GL30.GL_COLOR_ATTACHMENT0);
            singleDrawBuffers.put(rawOutput, GL11.GL_NONE);
            GL20.glDrawBuffers(singleDrawBuffers);
        }

        private void setUniforms(int passIndex, int stride, int minX, int minZ, int farHeightsMinX,
                int farHeightsMinZ) {
            GL20.glUniform1i(passIndexUniform, passIndex);
            GL20.glUniform1i(strideUniform, stride);
            GL20.glUniform2i(cavityMinUniform, minX, minZ);
            GL20.glUniform2i(farHeightsMinUniform, farHeightsMinX, farHeightsMinZ);
        }
    }
}
