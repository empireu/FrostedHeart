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
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.LightPillarSources;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyDrawTimer;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/**
 * Draws the falling snow near the eye after the weather, in frames where the weather fog was drawn, in place of
 * vanilla's snow, as SnowFlakes describes it.
 * The flakes live on the GPU in two RGBA32F state textures that take turns: each frame one full screen pass over the
 * state moves every flake by the wind and its own fall speed into the other texture, and one instanced draw of quads
 * without vertex data draws the flakes from it, with the fog's transmittance to each flake and the snow share of its
 * place. The draw tests against the depth of the main target, blends over it and writes no depth.
 * A paused game does not move the flakes. Everything the passes change is set back after them.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SnowfallRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STATE_UNIT = 0;
    /**
     * The unit of the fog's excess array, a 2D array texture, while the other units hold 2D textures.
     */
    private static final int EXCESS_UNIT = 1;
    private static final int SHARE_VOLUME_UNIT = 2;
    private static final int TEXTURE_UNITS = 3;
    /**
     * The blocks before the faces of the box over which the flakes fade out.
     */
    private static final float BOX_FADE_WIDTH = 4.0F;
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(16);
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final int[] previousTextures = new int[TEXTURE_UNITS];
    private static final int[] stateTextures = new int[2];
    private static final int[] stateFramebuffers = new int[2];
    private static final SkyDrawTimer updateTimer = new SkyDrawTimer();
    private static final SkyDrawTimer drawTimer = new SkyDrawTimer();
    private static final UpdateProgram updateProgram = new UpdateProgram();
    private static final DrawProgram drawProgram = new DrawProgram();

    /**
     * The state texture that holds the flakes of the last update.
     */
    private static int currentState;
    private static boolean stateFilled;
    /**
     * An empty vertex array, which a core profile needs to draw without vertex data.
     */
    private static int vertexArray;
    private static boolean failed;
    private static int lastInstances;
    private static double lastAskedCount;
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

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        lastInstances = 0;
        if (!ownsPrecipitation()) {
            return;
        }
        long allocatedBefore = LightPillarSources.allocatedBytes();
        try {
            render(event);
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.error("The snowfall is not drawn after an error, so vanilla's snow draws again. It is drawn again "
                    + "once the calculated sky is turned on again.", exception);
        } finally {
            lastFrameAllocatedBytes = LightPillarSources.allocatedBytes() - allocatedBefore;
        }
    }

    private static void render(RenderLevelStageEvent event) {
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
        viewProjection.set(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        float pixelsPerBlock = event.getProjectionMatrix().m11()
                * Minecraft.getInstance().getMainRenderTarget().height * 0.5F;
        Vec3 camera = event.getCamera().getPosition();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        int sourceColor = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationColor = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int colorEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int alphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthWrites = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        int activeTexture = GlStateManager._getActiveTexture();
        for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
            previousTextures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
        int previousExcessTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            RenderSystem.disableScissor();
            RenderSystem.disableCull();
            updateTimer.begin();
            try {
                update(boxSide);
            } finally {
                updateTimer.end();
            }
            drawTimer.begin();
            try {
                draw(camera, boxSide, flakeSize, drawnCount, instances, pixelsPerBlock);
            } finally {
                drawTimer.end();
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
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousExcessTexture);
            for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
                RenderSystem.bindTexture(previousTextures[unit]);
            }
            RenderSystem.activeTexture(activeTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
        lastInstances = instances;
    }

    /**
     * Moves every flake by the seconds of this frame into the other state texture, or places every flake from its
     * hashes when the state was not filled yet. A paused frame moves nothing.
     */
    private static void update(double boxSide) {
        double stepSeconds = WeatherFogRenderer.frameStepSeconds();
        if (stateFilled && !(stepSeconds > 0.0)) {
            return;
        }
        int next = 1 - currentState;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, stateFramebuffers[next]);
        RenderSystem.viewport(0, 0, SnowFlakes.STATE_WIDTH, SnowFlakes.STATE_HEIGHT);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        bindTexture(STATE_UNIT, stateTextures[currentState]);
        updateProgram.use();
        WeatherWind wind = WeatherFogRenderer.wind();
        updateProgram.setUniforms(!stateFilled, stepSeconds, wind, SkySetting.SNOW_FALL_SPEED.get(), boxSide);
        SkyGlPass.drawFullScreenQuad();
        currentState = next;
        stateFilled = true;
    }

    /**
     * Draws the flakes into the main target, binding its framebuffer for drawing, with the depth test of the scene and
     * no depth writes. Face culling is off, since the quads' winding is not kept.
     */
    private static void draw(Vec3 camera, double boxSide, double flakeSize, double drawnCount, int instances,
            float pixelsPerBlock) {
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
        RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        bindTexture(STATE_UNIT, stateTextures[currentState]);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + EXCESS_UNIT);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, WeatherFogRenderer.excessTexture());
        int shareVolume = WeatherFogRenderer.shareVolumeTexture();
        bindTexture(SHARE_VOLUME_UNIT, shareVolume);
        drawProgram.use();
        drawProgram.setUniforms(mainTarget, camera, boxSide, flakeSize, drawnCount, pixelsPerBlock, shareVolume != 0);
        if (vertexArray == 0) {
            vertexArray = GL30.glGenVertexArrays();
        }
        GL30.glBindVertexArray(vertexArray);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, instances);
    }

    private static void bindTexture(int unit, int texture) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
    }

    /**
     * Makes the two state textures and their framebuffers on first use and keeps them for the session. Textures that
     * cannot be made are deleted again, and the snowfall stops with a log line, so vanilla's snow draws instead.
     * @return whether the state textures exist
     */
    private static boolean prepareState() {
        if (stateTextures[0] != 0) {
            return true;
        }
        int[] textures = new int[2];
        int[] framebuffers = new int[2];
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            for (int state = 0; state < 2; state++) {
                textures[state] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[state]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SnowFlakes.STATE_WIDTH,
                        SnowFlakes.STATE_HEIGHT, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
                framebuffers[state] = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffers[state]);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                        textures[state], 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("The snowflake state framebuffer is incomplete with status "
                            + status);
                }
            }
        } catch (RuntimeException exception) {
            for (int state = 0; state < 2; state++) {
                GL11.glDeleteTextures(textures[state]);
                GL30.glDeleteFramebuffers(framebuffers[state]);
            }
            failed = true;
            LOGGER.error("The snowflake state cannot be made, so vanilla's snow draws instead.", exception);
            return false;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
        stateTextures[0] = textures[0];
        stateTextures[1] = textures[1];
        stateFramebuffers[0] = framebuffers[0];
        stateFramebuffers[1] = framebuffers[1];
        currentState = 0;
        stateFilled = false;
        return true;
    }

    public static void clearFailure() {
        failed = false;
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
                        + "of %d at most%s; update %s; draw %s; %s", lastInstances, lastAskedCount, SnowFlakes.CAPACITY,
                lastAskedCount > SnowFlakes.CAPACITY ? " (capped)" : "", updateTimer.describe(), drawTimer.describe(),
                allocated);
    }

    /**
     * The program of the update, IMAGE_V and snow_flake_update.fsh, and the locations of its uniforms. It is made again
     * when a resource reload replaced its stage.
     */
    private static final class UpdateProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader fragment;
        private int fillStateUniform;
        private int stepSecondsUniform;
        private int windVelocityUniform;
        private int fallSpeedUniform;
        private int boxSideUniform;

        private void use() {
            Shader newFragment = FHShaders.getSnowFlakeUpdate();
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
                GL20.glUniform1i(GL20.glGetUniformLocation(program.programId, "previousState"), STATE_UNIT);
                fillStateUniform = GL20.glGetUniformLocation(program.programId, "fillState");
                stepSecondsUniform = GL20.glGetUniformLocation(program.programId, "stepSeconds");
                windVelocityUniform = GL20.glGetUniformLocation(program.programId, "windVelocity");
                fallSpeedUniform = GL20.glGetUniformLocation(program.programId, "fallSpeed");
                boxSideUniform = GL20.glGetUniformLocation(program.programId, "boxSide");
            }
            program.use();
        }

        private void setUniforms(boolean fill, double stepSeconds, WeatherWind wind, double fallSpeed,
                double boxSide) {
            GL20.glUniform1i(fillStateUniform, fill ? 1 : 0);
            GL20.glUniform1f(stepSecondsUniform, (float) stepSeconds);
            GL20.glUniform2f(windVelocityUniform, (float) (wind.speed() * Math.cos(wind.direction())),
                    (float) (wind.speed() * Math.sin(wind.direction())));
            GL20.glUniform1f(fallSpeedUniform, (float) fallSpeed);
            GL20.glUniform1f(boxSideUniform, (float) boxSide);
        }
    }

    /**
     * The program of the two stages of snow_flakes_draw.glsl and the locations of the uniforms that draw sets every
     * frame. It is made again when a resource reload replaced its stages.
     */
    private static final class DrawProgram {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader vertex;
        @Nullable
        private Shader fragment;
        private int viewProjectionUniform;
        private int screenSizeUniform;
        private int pixelsPerBlockUniform;
        private int cameraInBoxUniform;
        private int boxSideUniform;
        private int drawnCountUniform;
        private int flakeSizeUniform;
        private int flutterRadiusUniform;
        private int windVelocityUniform;
        private int fallSpeedUniform;
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

        private void use() {
            Shader newVertex = FHShaders.getSnowFlakesVertex();
            Shader newFragment = FHShaders.getSnowFlakesFragment();
            if (program == null || vertex != newVertex || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                try {
                    created.attach(newVertex).attach(newFragment);
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                vertex = newVertex;
                fragment = newFragment;
                program.use();
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "flakeState"), STATE_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "excesses"), EXCESS_UNIT);
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "shareVolume"), SHARE_VOLUME_UNIT);
                GL20.glUniform3i(GL20.glGetUniformLocation(programId, "shareLayout"), FogShareVolume.SIDE,
                        FogShareVolume.ORIGIN, FogShareVolume.TILES_ACROSS);
                GL20.glUniform1f(GL20.glGetUniformLocation(programId, "boxFadeWidth"), BOX_FADE_WIDTH);
                viewProjectionUniform = GL20.glGetUniformLocation(programId, "viewProjection");
                screenSizeUniform = GL20.glGetUniformLocation(programId, "screenSize");
                pixelsPerBlockUniform = GL20.glGetUniformLocation(programId, "pixelsPerBlock");
                cameraInBoxUniform = GL20.glGetUniformLocation(programId, "cameraInBox");
                boxSideUniform = GL20.glGetUniformLocation(programId, "boxSide");
                drawnCountUniform = GL20.glGetUniformLocation(programId, "drawnCount");
                flakeSizeUniform = GL20.glGetUniformLocation(programId, "flakeSize");
                flutterRadiusUniform = GL20.glGetUniformLocation(programId, "flutterRadius");
                windVelocityUniform = GL20.glGetUniformLocation(programId, "windVelocity");
                fallSpeedUniform = GL20.glGetUniformLocation(programId, "fallSpeed");
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
            }
            program.use();
        }

        /**
         * Sets the uniforms of this frame on the program in use. The camera reaches it as its place in the periodic
         * box, wrapped in double precision, so no world position passes through a float.
         */
        private void setUniforms(RenderTarget mainTarget, Vec3 camera, double boxSide, double flakeSize,
                double drawnCount, float pixelsPerBlock, boolean hasShareVolume) {
            viewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(viewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, (float) mainTarget.width, (float) mainTarget.height);
            GL20.glUniform1f(pixelsPerBlockUniform, pixelsPerBlock);
            GL20.glUniform3f(cameraInBoxUniform, (float) SnowFlakes.wrap(camera.x, boxSide),
                    (float) SnowFlakes.wrap(camera.y, boxSide), (float) SnowFlakes.wrap(camera.z, boxSide));
            GL20.glUniform1f(boxSideUniform, (float) boxSide);
            GL20.glUniform1f(drawnCountUniform, (float) drawnCount);
            GL20.glUniform1f(flakeSizeUniform, (float) flakeSize);
            GL20.glUniform1f(flutterRadiusUniform, (float) SkySetting.SNOW_FLUTTER.get());
            WeatherWind wind = WeatherFogRenderer.wind();
            GL20.glUniform2f(windVelocityUniform, (float) (wind.speed() * Math.cos(wind.direction())),
                    (float) (wind.speed() * Math.sin(wind.direction())));
            GL20.glUniform1f(fallSpeedUniform, (float) SkySetting.SNOW_FALL_SPEED.get());
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
        }
    }
}
