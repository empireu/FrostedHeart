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
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The lamp march of the weather fog, weather_fog_lamp.fsh, which draws the lamp light that the fog scatters toward the
 * eye into a target of RGBA16F at a share of the frame's resolution, set by fogLampDivisor. Each texel holds that light
 * and the distance its ray ended at, which weather_fog_composite.fsh reads around each frame pixel and adds to the fog
 * color. The pass draws between the integrate and the composite of WeatherFogRenderer, which binds the scene depth on
 * unit 0 and the froxel excesses on unit 7 for it, and reads the cascades of the lamp light field on units 9 to 13. It
 * is skipped while the field is not tracked, not faded in, or holds no lit cell, while the fog color does not come
 * from the lightmap, and while fogLampLight is 0. A target or program that cannot be made turns the pass off for the
 * session and leaves the fog as it was. The draw allocates nothing. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class LampFogPass {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The unit the composite reads the target on, and the units the march reads its inputs on.
     */
    static final int TARGET_UNIT = 6;
    private static final int SCENE_DEPTH_UNIT = 0;
    private static final int TRANSLUCENT_DEPTH_UNIT = 1;
    private static final int EXCESS_UNIT = 7;
    private static final int FIRST_CASCADE_UNIT = 9;
    private static final int CASCADES = LampLightField.CASCADE_COUNT;
    private static final int LEVELS = LampFogMarch.LIFT_LEVELS;
    /**
     * The lightmap rows of sky light 14 and 15, whose mean is the light of full sky light.
     */
    private static final int LIGHTMAP_SKY_FOURTEEN = 14;
    private static final int LIGHTMAP_SKY_FIFTEEN = 15;
    /**
     * The rays of the self test, the analytic fog it marches through, the share of its rays' lengths past the lit
     * point they aim at, its seed, and the tolerance of a channel as a share of the twin's brightest channel plus an
     * absolute part.
     */
    private static final int SELF_TEST_RAYS = 64;
    private static final float SELF_TEST_EXTINCTION = 0.02F;
    private static final float SELF_TEST_HEIGHT_SCALE = 64.0F;
    private static final double SELF_TEST_OVERSHOOT = 1.25;
    private static final long SELF_TEST_SEED = 0x1a3bL;
    private static final double SELF_TEST_RELATIVE_TOLERANCE = 0.03;
    private static final double SELF_TEST_ABSOLUTE_TOLERANCE = 2.0e-3;

    private static final LampLightBinding lamps = new LampLightBinding(FIRST_CASCADE_UNIT);
    private static final int[] skyFourteenPixels = new int[LEVELS];
    private static final int[] skyFifteenPixels = new int[LEVELS];
    private static final float[] lifts = new float[3 * LEVELS];
    private static final float[] shownLifts = new float[3 * LEVELS];
    private static final float[] litLows = new float[3 * CASCADES];
    private static final float[] litHighs = new float[3 * CASCADES];
    private static final int[] coarseMasks = new int[CASCADES * LampLightField.COARSE_WORDS_PER_CASCADE];
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer liftBuffer = BufferUtils.createFloatBuffer(3 * LEVELS);
    private static final FloatBuffer boxBuffer = BufferUtils.createFloatBuffer(3 * CASCADES);
    private static final IntBuffer coarseMaskBuffer = BufferUtils.createIntBuffer(
            CASCADES * LampLightField.COARSE_WORDS_PER_CASCADE);
    private static final FloatBuffer selfTestOffsetBuffer = BufferUtils.createFloatBuffer(3 * SELF_TEST_RAYS);
    private static final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(16);
    private static final IntBuffer drawBuffers = BufferUtils.createIntBuffer(2);
    private static final IntBuffer intQueryBuffer = BufferUtils.createIntBuffer(1);

    @Nullable
    private static ShaderProgram program;
    @Nullable
    private static Shader fragment;
    private static int opaqueOutput;
    private static int translucentOutput;
    private static int hasTranslucentUniform;
    private static int screenSizeUniform;
    private static int inverseViewProjectionUniform;
    private static int eyeExtinctionUniform;
    private static int heightScaleUniform;
    private static int columnCountUniform;
    private static int rowCountUniform;
    private static int sliceCountUniform;
    private static int rangeDistanceUniform;
    private static int divisorUniform;
    private static int lampDivisorUniform;
    private static int liftUniform;
    private static int litLowUniform;
    private static int litHighUniform;
    private static int coarseBitsUniform;
    private static int selfTestUniform;
    private static int selfTestOffsetsUniform;
    private static int targetTexture;
    private static int translucentTargetTexture;
    private static int targetFramebuffer;
    private static int targetWidth;
    private static int targetHeight;
    private static int selfTestTexture;
    private static int selfTestFramebuffer;
    /**
     * Whether the lightmap gave the lamp colors of this frame, the divisor of the last draw, and whether the pass
     * failed, after which it stays off for the session.
     */
    private static boolean liftsRead;
    private static int frameDivisor = 1;
    private static boolean failed;

    private LampFogPass() {
    }

    /**
     * Reads the lamp color of every level from the lightmap pixels at sky light 14 and 15, as FogLight.lampLifts
     * gives them. WeatherFogRenderer calls it each frame it reads the snow fog color from the lightmap. Allocates
     * nothing.
     */
    static void readLifts(NativeImage lightPixels) {
        for (int level = 0; level < LEVELS; level++) {
            skyFourteenPixels[level] = lightPixels.getPixelRGBA(level, LIGHTMAP_SKY_FOURTEEN);
            skyFifteenPixels[level] = lightPixels.getPixelRGBA(level, LIGHTMAP_SKY_FIFTEEN);
        }
        FogLight.lampLifts(skyFourteenPixels, skyFifteenPixels, 1.0F, lifts);
        liftsRead = true;
    }

    /**
     * The textures the opaque and translucent composites read and the frame pixels per side of one of their texels.
     */
    static int targetTexture() {
        return targetTexture;
    }

    static int translucentTargetTexture() {
        return translucentTargetTexture;
    }

    static int divisor() {
        return frameDivisor;
    }

    static void clearFailure() {
        failed = false;
    }

    /**
     * Marches the lamp light of the frame into the target, and into the translucent target in the same pass when a
     * translucent depth texture is supplied. The caller binds the scene depth and the excesses on their units, and sets
     * the viewport back after it. The framebuffer bound before is bound again, unit 1's 2D binding is restored, and the
     * active unit is left at 0.
     * @param snowColorFromLightmap whether the fog color of the frame came from the lightmap
     * @param translucentDepthTexture the depth texture of the translucent target, or 0 when no translucent chunks drew
     * @return whether the target holds the frame's lamp light, which the composites then read
     */
    static boolean draw(RenderTarget mainTarget, Matrix4f viewProjection, Matrix4f inverseViewProjection, double eyeX,
            double eyeY, double eyeZ, double eyeExtinction, double heightScale, int columnCount, int rowCount,
            int sliceCount, double rangeDistance, int froxelDivisor, boolean snowColorFromLightmap,
            int translucentDepthTexture) {
        if (failed || !snowColorFromLightmap || !liftsRead) {
            return false;
        }
        LampLightField field = ClientLampLight.field(Minecraft.getInstance().level);
        float fade = ClientLampLight.fade();
        float strength = (float) settingValue(SkySetting.FOG_LAMP_LIGHT);
        if (field == null || !(fade > 0.0F) || !(strength > 0.0F) || !field.shaderLitBoxes(litLows, litHighs)) {
            return false;
        }
        if (!LampFogMarch.clipLitBoxesToFrustum(viewProjection, field, eyeX, eyeY, eyeZ, litLows, litHighs)) {
            return false;
        }
        field.shaderCoarseMasks(coarseMasks);
        int divisor = divisorOf(SkySetting.FOG_LAMP_DIVISOR.get());
        int width = (mainTarget.width + divisor - 1) / divisor;
        int height = (mainTarget.height + divisor - 1) / divisor;
        int previousFramebuffer = getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TRANSLUCENT_DEPTH_UNIT);
        int previousTranslucentDepth = getInteger(GL11.GL_TEXTURE_BINDING_2D);
        lamps.save();
        try {
            if (!prepareTarget(width, height)) {
                return false;
            }
            boolean withTranslucent = translucentDepthTexture != 0;
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TRANSLUCENT_DEPTH_UNIT);
            RenderSystem.bindTexture(withTranslucent ? translucentDepthTexture : 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
            RenderSystem.viewport(0, 0, width, height);
            use();
            setDrawBuffers(withTranslucent);
            inverseViewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(inverseViewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(eyeExtinctionUniform, (float) eyeExtinction);
            GL20.glUniform1f(heightScaleUniform, (float) heightScale);
            GL20.glUniform1i(columnCountUniform, columnCount);
            GL20.glUniform1i(rowCountUniform, rowCount);
            GL20.glUniform1i(sliceCountUniform, sliceCount);
            GL20.glUniform1f(rangeDistanceUniform, (float) rangeDistance);
            GL20.glUniform1i(divisorUniform, froxelDivisor);
            GL20.glUniform1i(lampDivisorUniform, divisor);
            GL20.glUniform1i(hasTranslucentUniform, withTranslucent ? 1 : 0);
            GL20.glUniform1i(selfTestUniform, 0);
            setMarchUniforms(strength);
            lamps.bind(field, eyeX, eyeY, eyeZ, fade);
            SkyGlPass.drawFullScreenQuad();
            frameDivisor = divisor;
            return true;
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.warn("The lamp glow of the weather fog stopped after an error, so the fog is drawn without it for "
                    + "the rest of the session.", exception);
            return false;
        } finally {
            lamps.restore();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TRANSLUCENT_DEPTH_UNIT);
            RenderSystem.bindTexture(previousTranslucentDepth);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
    }

    private static int getInteger(int parameter) {
        GL11.glGetIntegerv(parameter, intQueryBuffer);
        return intQueryBuffer.get(0);
    }

    /**
     * Sets the lamp colors times a strength, the lit boxes, and the coarse occupancy masks on the program in use.
     */
    private static void setMarchUniforms(float strength) {
        for (int entry = 0; entry < shownLifts.length; entry++) {
            shownLifts[entry] = strength * lifts[entry];
        }
        liftBuffer.clear();
        liftBuffer.put(shownLifts);
        liftBuffer.flip();
        GL20.glUniform3fv(liftUniform, liftBuffer);
        boxBuffer.clear();
        boxBuffer.put(litLows);
        boxBuffer.flip();
        GL20.glUniform3fv(litLowUniform, boxBuffer);
        boxBuffer.clear();
        boxBuffer.put(litHighs);
        boxBuffer.flip();
        GL20.glUniform3fv(litHighUniform, boxBuffer);
        coarseMaskBuffer.clear();
        coarseMaskBuffer.put(coarseMasks);
        coarseMaskBuffer.flip();
        GL30.glUniform4uiv(coarseBitsUniform, coarseMaskBuffer);
    }

    /**
     * The pixels per side of a texel of the target for a setting's value, rounded and clamped to its range.
     */
    static int divisorOf(double value) {
        double clamped = Math.max(SkySetting.FOG_LAMP_DIVISOR.minimum(),
                Math.min(SkySetting.FOG_LAMP_DIVISOR.maximum(), value));
        return Double.isNaN(value) ? (int) SkySetting.FOG_LAMP_DIVISOR.defaultValue() : (int) Math.round(clamped);
    }

    private static double settingValue(SkySetting setting) {
        double value = setting.get();
        if (!(value >= setting.minimum())) {
            return setting.minimum();
        }
        return Math.min(setting.maximum(), value);
    }

    /**
     * Uses the program, making it first if needed, and points its samplers at their units once it links.
     */
    private static void use() {
        Shader newFragment = FHShaders.getWeatherFogLamp();
        if (program == null || fragment != newFragment) {
            if (program != null) {
                program.delete();
                program = null;
            }
            ShaderProgram created = new ShaderProgram();
            int createdOpaqueOutput;
            int createdTranslucentOutput;
            try {
                created.attach(FHShaders.IMAGE_V).attach(newFragment);
                created.linkProgram();
                // GLSL 1.50 leaves the locations of multiple fragment outputs to the linker:
                createdOpaqueOutput = GL30.glGetFragDataLocation(created.programId, "fragColor");
                createdTranslucentOutput = GL30.glGetFragDataLocation(created.programId, "translucentFragColor");
                if (createdOpaqueOutput < 0 || createdOpaqueOutput > 1 || createdTranslucentOutput < 0
                        || createdTranslucentOutput > 1 || createdOpaqueOutput == createdTranslucentOutput) {
                    throw new IllegalStateException("The lamp glow writes its outputs to the locations "
                            + createdOpaqueOutput + " and " + createdTranslucentOutput + " instead of 0 and 1");
                }
            } catch (RuntimeException exception) {
                created.delete();
                throw exception;
            }
            program = created;
            fragment = newFragment;
            opaqueOutput = createdOpaqueOutput;
            translucentOutput = createdTranslucentOutput;
            program.use();
            int programId = program.programId;
            GL20.glUniform1i(GL20.glGetUniformLocation(programId, "sceneDepth"), SCENE_DEPTH_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(programId, "translucentDepth"), TRANSLUCENT_DEPTH_UNIT);
            GL20.glUniform1i(GL20.glGetUniformLocation(programId, "excesses"), EXCESS_UNIT);
            lamps.locate(programId);
            hasTranslucentUniform = GL20.glGetUniformLocation(programId, "hasTranslucent");
            screenSizeUniform = GL20.glGetUniformLocation(programId, "screenSize");
            inverseViewProjectionUniform = GL20.glGetUniformLocation(programId, "inverseViewProjection");
            eyeExtinctionUniform = GL20.glGetUniformLocation(programId, "eyeExtinction");
            heightScaleUniform = GL20.glGetUniformLocation(programId, "heightScale");
            columnCountUniform = GL20.glGetUniformLocation(programId, "columnCount");
            rowCountUniform = GL20.glGetUniformLocation(programId, "rowCount");
            sliceCountUniform = GL20.glGetUniformLocation(programId, "sliceCount");
            rangeDistanceUniform = GL20.glGetUniformLocation(programId, "rangeDistance");
            divisorUniform = GL20.glGetUniformLocation(programId, "divisor");
            lampDivisorUniform = GL20.glGetUniformLocation(programId, "lampDivisor");
            liftUniform = GL20.glGetUniformLocation(programId, "lampLift");
            litLowUniform = GL20.glGetUniformLocation(programId, "lampLitLow");
            litHighUniform = GL20.glGetUniformLocation(programId, "lampLitHigh");
            coarseBitsUniform = GL20.glGetUniformLocation(programId, "lampCoarseBits");
            selfTestUniform = GL20.glGetUniformLocation(programId, "selfTest");
            selfTestOffsetsUniform = GL20.glGetUniformLocation(programId, "selfTestOffsets");
        }
        GL20.glUseProgram(program.programId);
    }

    /**
     * Makes the target and its framebuffer on first use and sizes the target again whenever its size changed. A target
     * that cannot be made is deleted and logged, and the pass stays off for the session.
     * @return whether the target may be drawn
     */
    private static boolean prepareTarget(int width, int height) {
        boolean allocate = targetFramebuffer == 0;
        boolean resize = allocate || width != targetWidth || height != targetHeight;
        if (!resize) {
            return true;
        }
        if (allocate) {
            targetFramebuffer = GL30.glGenFramebuffers();
            targetTexture = GL11.glGenTextures();
            translucentTargetTexture = GL11.glGenTextures();
        }
        try {
            makeTexture(targetTexture, GL30.GL_RGBA16F, width, height, allocate);
            makeTexture(translucentTargetTexture, GL30.GL_RGBA16F, width, height, allocate);
            attachPair(targetFramebuffer, targetTexture, translucentTargetTexture, "lamp glow");
            targetWidth = width;
            targetHeight = height;
            return true;
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(targetTexture);
            GL11.glDeleteTextures(translucentTargetTexture);
            GL30.glDeleteFramebuffers(targetFramebuffer);
            targetTexture = 0;
            translucentTargetTexture = 0;
            targetFramebuffer = 0;
            failed = true;
            LOGGER.warn("The target of the lamp glow of the weather fog cannot be made, so the fog is drawn without it "
                    + "for the rest of the session.", exception);
            return false;
        }
    }

    private static void setDrawBuffers(boolean withTranslucent) {
        drawBuffers.clear();
        drawBuffers.put(opaqueOutput, GL30.GL_COLOR_ATTACHMENT0);
        drawBuffers.put(translucentOutput, withTranslucent ? GL30.GL_COLOR_ATTACHMENT1 : GL11.GL_NONE);
        GL20.glDrawBuffers(drawBuffers);
    }

    /**
     * Gives a texture of nearest filtering, clamped at its edges, storage of a size, keeping the 2D texture bound
     * before.
     */
    private static void makeTexture(int texture, int internalFormat, int width, int height, boolean setParameters) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (setParameters) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT,
                    (FloatBuffer) null);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    private static void attachPair(int framebuffer, int opaqueTexture, int translucentTexture, String name) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    opaqueTexture, 0);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D,
                    translucentTexture, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("The " + name + " framebuffer is incomplete with status " + status);
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
    }

    /**
     * Attaches a texture to a framebuffer's first color attachment and checks that it is complete, keeping the draw
     * framebuffer bound before.
     */
    private static void attach(int framebuffer, int texture, String name) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("The " + name + " framebuffer is incomplete with status " + status);
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
    }

    /**
     * Marches 64 rays aimed at lit cells of the field through an analytic fog on the GPU and with LampFogMarch, and
     * describes how far they differ. Allocates.
     */
    public static List<String> selfTest() {
        List<String> lines = new ArrayList<>();
        try {
            collectSelfTest(lines);
        } catch (RuntimeException exception) {
            lines.add("The lamp march self test stopped after an error: " + exception.getMessage());
        }
        return lines;
    }

    private static void collectSelfTest(List<String> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        LampLightField field = ClientLampLight.field(minecraft.level);
        if (field == null) {
            lines.add("The lamp light field is not tracked, so the lamp march has nothing to test.");
            return;
        }
        if (failed) {
            lines.add("The lamp march is off for this session after an error.");
            return;
        }
        float fade = ClientLampLight.fade();
        if (!(fade > 0.0F) || !field.isDrained()) {
            lines.add("The lamp march self test waits for the field to read its queue and fade in.");
            return;
        }
        if (!liftsRead) {
            lines.add("The lamp march self test needs the lamp colors from the lightmap, which a frame of the weather "
                    + "fog reads.");
            return;
        }
        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        LampFogMarch twin = new LampFogMarch();
        if (!twin.prepare(field, eye.x, eye.y, eye.z)) {
            lines.add("No cell of the lamp light field is lit, so the lamp march has nothing to test.");
            return;
        }
        float[] offsets = new float[3 * SELF_TEST_RAYS];
        aimRays(field, eye, offsets);
        float[] gpuLight = new float[4 * SELF_TEST_RAYS];
        drawSelfTest(field, eye, fade, offsets, gpuLight);
        float[] twinLight = new float[3];
        int matching = 0;
        int lit = 0;
        int worstRay = -1;
        double worstExcess = Double.NEGATIVE_INFINITY;
        float[] worstTwin = new float[3];
        int largestSteps = 0;
        for (int ray = 0; ray < SELF_TEST_RAYS; ray++) {
            double offsetX = offsets[3 * ray];
            double offsetY = offsets[3 * ray + 1];
            double offsetZ = offsets[3 * ray + 2];
            double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
            if (!(length > 0.0)) {
                twinLight[0] = 0.0F;
                twinLight[1] = 0.0F;
                twinLight[2] = 0.0F;
            } else {
                double directionY = offsetY / length;
                twin.march(offsetX / length, directionY, offsetZ / length, length, fade, lifts,
                        distance -> HeightFog.opticalDepth(SELF_TEST_EXTINCTION, distance, distance * directionY,
                                SELF_TEST_HEIGHT_SCALE), twinLight);
                largestSteps = Math.max(largestSteps, twin.lastSteps());
            }
            double brightest = Math.max(twinLight[0], Math.max(twinLight[1], twinLight[2]));
            if (brightest > SELF_TEST_ABSOLUTE_TOLERANCE) {
                lit++;
            }
            double allowed = SELF_TEST_RELATIVE_TOLERANCE * brightest + SELF_TEST_ABSOLUTE_TOLERANCE;
            double excess = Double.NEGATIVE_INFINITY;
            for (int channel = 0; channel < 3; channel++) {
                excess = Math.max(excess, Math.abs(gpuLight[4 * ray + channel] - twinLight[channel]) - allowed);
            }
            if (excess <= 0.0) {
                matching++;
            }
            if (excess > worstExcess) {
                worstExcess = excess;
                worstRay = ray;
                System.arraycopy(twinLight, 0, worstTwin, 0, 3);
            }
        }
        lines.add(String.format(Locale.ROOT, "Lamp march self test: %d of %d rays match the twin within %.0f%% of "
                + "their brightest channel plus %.4f, %d rays lit, at most %d pieces per ray of the twin.", matching,
                SELF_TEST_RAYS, SELF_TEST_RELATIVE_TOLERANCE * 100.0, SELF_TEST_ABSOLUTE_TOLERANCE, lit,
                largestSteps));
        lines.add(String.format(Locale.ROOT, "Worst ray %d toward (%.1f, %.1f, %.1f): GPU (%.4f, %.4f, %.4f), twin "
                + "(%.4f, %.4f, %.4f).", worstRay, offsets[3 * worstRay], offsets[3 * worstRay + 1],
                offsets[3 * worstRay + 2], gpuLight[4 * worstRay], gpuLight[4 * worstRay + 1],
                gpuLight[4 * worstRay + 2], worstTwin[0], worstTwin[1], worstTwin[2]));
    }

    /**
     * Aims the rays at random points in the lit boxes of the cascades in turn, each ray reaching past its point by a
     * share of its length. A ray whose cascade has no lit cell takes the next cascade that has one.
     */
    private static void aimRays(LampLightField field, Vec3 eye, float[] offsets) {
        Random random = new Random(SELF_TEST_SEED);
        int[] box = new int[6];
        int count = field.activeCount();
        for (int ray = 0; ray < SELF_TEST_RAYS; ray++) {
            for (int attempt = 0; attempt < count; attempt++) {
                LampLightCascade cascade = field.cascade((ray + attempt) % count);
                if (!cascade.litBox(box)) {
                    continue;
                }
                double cellX = cascade.originX() + box[0] + random.nextDouble() * (box[3] - box[0]);
                double cellY = cascade.originY() + box[1] + random.nextDouble() * (box[4] - box[1]);
                double cellZ = cascade.originZ() + box[2] + random.nextDouble() * (box[5] - box[2]);
                double scale = SELF_TEST_OVERSHOOT;
                offsets[3 * ray] = (float) ((cellX * cascade.cellWidth() - eye.x) * scale);
                offsets[3 * ray + 1] = (float) ((cellY * cascade.cellHeight() - eye.y) * scale);
                offsets[3 * ray + 2] = (float) ((cellZ * cascade.cellWidth() - eye.z) * scale);
                break;
            }
        }
    }

    /**
     * Draws the rays of the self test into its 64 by 1 target of RGBA32F and reads the target back. Every piece of
     * state it changes is set back after it.
     */
    private static void drawSelfTest(LampLightField field, Vec3 eye, float fade, float[] offsets, float[] light) {
        SkyGlPass selfTestPass = new SkyGlPass(1);
        selfTestPass.begin();
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        lamps.save();
        try {
            if (selfTestFramebuffer == 0) {
                selfTestFramebuffer = GL30.glGenFramebuffers();
                selfTestTexture = GL11.glGenTextures();
                makeTexture(selfTestTexture, GL30.GL_RGBA32F, SELF_TEST_RAYS, 1, true);
                attach(selfTestFramebuffer, selfTestTexture, "lamp march self test");
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, selfTestFramebuffer);
            RenderSystem.viewport(0, 0, SELF_TEST_RAYS, 1);
            use();
            setDrawBuffers(false);
            GL20.glUniform1f(eyeExtinctionUniform, SELF_TEST_EXTINCTION);
            GL20.glUniform1f(heightScaleUniform, SELF_TEST_HEIGHT_SCALE);
            GL20.glUniform1i(hasTranslucentUniform, 0);
            GL20.glUniform1i(selfTestUniform, 1);
            selfTestOffsetBuffer.clear();
            selfTestOffsetBuffer.put(offsets);
            selfTestOffsetBuffer.flip();
            GL20.glUniform3fv(selfTestOffsetsUniform, selfTestOffsetBuffer);
            field.shaderLitBoxes(litLows, litHighs);
            field.shaderCoarseMasks(coarseMasks);
            setMarchUniforms(1.0F);
            lamps.bind(field, eye.x, eye.y, eye.z, fade);
            SkyGlPass.drawFullScreenQuad();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, selfTestFramebuffer);
            FloatBuffer readback = BufferUtils.createFloatBuffer(4 * SELF_TEST_RAYS);
            GL11.glReadPixels(0, 0, SELF_TEST_RAYS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, readback);
            readback.get(light);
        } finally {
            lamps.restore();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            selfTestPass.end();
        }
    }
}
