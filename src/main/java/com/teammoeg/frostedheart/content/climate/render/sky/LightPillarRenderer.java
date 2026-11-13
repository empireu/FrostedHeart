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

package com.teammoeg.frostedheart.content.climate.render.sky;

import com.lowdragmc.lowdraglib.client.shader.management.Shader;
import com.lowdragmc.lowdraglib.client.shader.management.ShaderProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Draws the light pillars of the lights that LightPillarSources chose, after the weather, in frames where the
 * calculated sky was drawn and there is diamond dust. Each pillar has one ribbon per plate kind on a static vertex
 * buffer, and light_pillars.glsl spreads the ribbons over the pillar on the screen and computes the light on them.
 * The fragment stage reads the depth of the main target and counts only the crystals in front of the scene. Each light
 * carries its horizon, ceiling, and top toward the eye from LightPillarHorizons, and crystals whose light those blocks
 * stop are left out. Crystals near the eye fade out by SkySetting.PILLAR_NEAR_FADE. Each pixel-sized cell of the sky
 * draws the count of crystals that glitter in it from the mean count, which the plates' size sets, and the counts
 * change over the twinkle clock. Pillars add to the frame with a screen blend. They
 * fade out with the brightness of the fog color, so by day, and with the rain level, so in snow. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LightPillarRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SLOTS = LightPillarSelection.SLOT_COUNT;
    /**
     * The plate kinds of crystals.csv, plates and wobbling plates, each with its own ribbon.
     */
    private static final int KINDS = 2;
    /**
     * Segments along each ribbon, as SEGMENTS in light_pillars.glsl.
     */
    private static final int SEGMENTS = 16;
    /**
     * The scene depth goes to texture unit 0 while the pillars draw.
     */
    private static final int SCENE_DEPTH_UNIT = 0;
    /**
     * Blocks the eye moves per twinkle period, since the crystals that line up change as the eye moves.
     */
    private static final double TWINKLE_DISTANCE = 0.25;
    /**
     * The twinkle clock wraps here, which keeps it precise in single precision.
     */
    private static final double TWINKLE_CLOCK_WRAP = 65536.0;
    private static final FloatBuffer lightBuffer = BufferUtils.createFloatBuffer(4 * SLOTS);
    private static final FloatBuffer overhangBuffer = BufferUtils.createFloatBuffer(2 * SLOTS);
    private static final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(4 * SLOTS);
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f viewProjection = new Matrix4f();
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final Vector4f eyeColumn = new Vector4f();
    private static final Vector3f eyeOffset = new Vector3f();
    private static final SkyDrawTimer timer = new SkyDrawTimer();

    @Nullable
    private static VertexBuffer ribbons;
    @Nullable
    private static ShaderProgram program;
    @Nullable
    private static Shader programVertex;
    @Nullable
    private static Shader programFragment;
    /**
     * The program whose constant uniforms are set and whose uniform locations are below.
     */
    @Nullable
    private static ShaderProgram uniformsProgram;
    private static int viewProjectionUniform;
    private static int lightsUniform;
    private static int overhangsUniform;
    private static int colorsUniform;
    private static int nearFadeUniform;
    private static int extinctionUniform;
    private static int dustBottomHeightUniform;
    private static int lateralBlurUniform;
    private static int marginAngleUniform;
    private static int inverseViewProjectionUniform;
    private static int screenSizeUniform;
    private static int crystalCountScaleUniform;
    private static int twinkleClockUniform;
    private static double twinkleClock;
    private static long lastTwinkleNanos;
    private static double lastTwinkleX;
    private static double lastTwinkleY;
    private static double lastTwinkleZ;
    /**
     * A framebuffer that the pillars draw through, holding the main target's color only while they draw. Without the
     * main target's depth attached, the fragment stage can read that depth.
     */
    private static int colorFramebuffer;
    private static boolean failed;
    private static int drawnPillars;

    private LightPillarRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        drawnPillars = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (failed || level == null || !CalculatedSkyRenderer.isDrawnThisFrame()) {
            return;
        }
        try {
            render(level, event);
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.error("Light pillars are not drawn after an error. They are drawn again once the calculated sky is "
                    + "turned on again.", exception);
        }
    }

    private static void render(ClientLevel level, RenderLevelStageEvent event) {
        float partialTick = event.getPartialTick();
        SkyFrame frame = SkyFrame.current(level, partialTick);
        double extinctionPerBlock = frame.dustExtinction() / 1000.0;
        double dustBottomY = LightPillarSources.dustBottomY();
        float opacity = daylightOpacity() * (1.0F - frame.rainLevel());
        if (!(extinctionPerBlock > 0.0) || Double.isNaN(dustBottomY) || !(opacity > 0.0F)) {
            return;
        }
        Matrix4f projection = event.getProjectionMatrix();
        viewProjection.set(projection).mul(event.getPoseStack().last().pose());
        findEyeOffset();
        viewProjection.translate(eyeOffset);
        inverseViewProjection.set(viewProjection).invert();
        Vec3 camera = event.getCamera().getPosition();
        double eyeX = camera.x + eyeOffset.x;
        double eyeY = camera.y + eyeOffset.y;
        double eyeZ = camera.z + eyeOffset.z;
        int pillars = fillPillars(eyeX, eyeY, eyeZ, partialTick, opacity);
        if (pillars == 0) {
            return;
        }
        advanceTwinkle(eyeX, eyeY, eyeZ);
        timer.begin();
        try {
            draw(projection, extinctionPerBlock, dustBottomY - eyeY);
        } finally {
            timer.end();
        }
        drawnPillars = pillars;
    }

    /**
     * Sets the eye offset to the position of the eye relative to the camera, which view bobbing moves in the
     * projection matrix. The eye is the point that the view projection maps to a clip position with only a depth.
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
     * Advances the twinkle clock by the time since the last drawn frame over the twinkle period and by the distance
     * the eye moved over TWINKLE_DISTANCE, by at most one period a frame. It stands still while the game is paused and
     * stays at 0 while the screenshot harness takes shots.
     */
    private static void advanceTwinkle(double eyeX, double eyeY, double eyeZ) {
        long now = System.nanoTime();
        if (CalculatedSkyScreenshots.isRunning()) {
            twinkleClock = 0.0;
        } else if (lastTwinkleNanos != 0L && !Minecraft.getInstance().isPaused()) {
            double seconds = (now - lastTwinkleNanos) * 1.0e-9;
            double moved = Math.sqrt((eyeX - lastTwinkleX) * (eyeX - lastTwinkleX)
                    + (eyeY - lastTwinkleY) * (eyeY - lastTwinkleY) + (eyeZ - lastTwinkleZ) * (eyeZ - lastTwinkleZ));
            double periods = seconds / SkySetting.PILLAR_TWINKLE_SECONDS.get() + moved / TWINKLE_DISTANCE;
            twinkleClock = (twinkleClock + Math.min(Math.max(periods, 0.0), 1.0)) % TWINKLE_CLOCK_WRAP;
        }
        lastTwinkleNanos = now;
        lastTwinkleX = eyeX;
        lastTwinkleY = eyeY;
        lastTwinkleZ = eyeZ;
    }

    /**
     * Fades pillars out as the fog color brightens, as 1 - Y to the fourth power with Y its luminance as displayed.
     */
    private static float daylightOpacity() {
        float[] fogColor = RenderSystem.getShaderFogColor();
        float luminance = 0.2126F * fogColor[0] + 0.7152F * fogColor[1] + 0.0722F * fogColor[2];
        float darkness = 1.0F - Math.max(0.0F, Math.min(1.0F, luminance));
        float squared = darkness * darkness;
        return squared * squared;
    }

    /**
     * Writes the light positions relative to the eye with their horizons, ceilings, and tops toward the eye, and the
     * colors of every slot into the uniform buffers, with the colors of slots that are faded out at 0, and returns how
     * many slots show.
     */
    private static int fillPillars(double eyeX, double eyeY, double eyeZ, float partialTick, float opacity) {
        float brightness = (float) (LightPillarOptics.BRIGHTNESS * SkySetting.PILLAR_BRIGHTNESS.get());
        if (!(brightness > 0.0F)) {
            return 0;
        }
        LightPillarSelection selection = LightPillarSources.selection();
        LightPillarHorizons horizons = LightPillarSources.horizons();
        lightBuffer.clear();
        overhangBuffer.clear();
        colorBuffer.clear();
        int pillars = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            float fade = selection.fade(slot, partialTick);
            if (fade > 0.0F) {
                lightBuffer.put((float) (selection.x(slot) - eyeX)).put((float) (selection.y(slot) - eyeY))
                        .put((float) (selection.z(slot) - eyeZ)).put(horizons.horizon(slot, eyeX, eyeZ));
                overhangBuffer.put(horizons.ceiling(slot, eyeX, eyeZ)).put(horizons.top(slot, eyeX, eyeZ));
                colorBuffer.put(selection.red(slot) * brightness).put(selection.green(slot) * brightness)
                        .put(selection.blue(slot) * brightness).put(fade * opacity);
                pillars++;
            } else {
                lightBuffer.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
                overhangBuffer.put(0.0F).put(0.0F);
                colorBuffer.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
            }
        }
        lightBuffer.flip();
        overhangBuffer.flip();
        colorBuffer.flip();
        return pillars;
    }

    /**
     * Draws every ribbon into the main target's color through the color framebuffer, with the main target's depth on
     * the scene depth unit for the fragment stage. In Fabulous graphics this stage runs with the weather target bound,
     * and either way the framebuffer bound before is bound again after. No depth test runs, since the fragment stage
     * hides the pillars behind the scene itself.
     */
    private static void draw(Matrix4f projection, double extinctionPerBlock, double dustBottomHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        ShaderProgram shaderProgram = program();
        VertexBuffer ribbonBuffer = ribbons();
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        double pixelAngle = 2.0 / (Math.abs(projection.m11()) * Math.max(1, mainTarget.height));

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        int sourceColor = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationColor = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int colorEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int alphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_UNIT);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            bindColorFramebuffer(mainTarget);
            RenderSystem.bindTexture(mainTarget.getDepthTextureId());
            shaderProgram.use();
            if (uniformsProgram != shaderProgram) {
                setFixedUniforms(shaderProgram.programId);
                uniformsProgram = shaderProgram;
            }
            viewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(viewProjectionUniform, false, matrixBuffer);
            GL20.glUniform4fv(lightsUniform, lightBuffer);
            GL20.glUniform2fv(overhangsUniform, overhangBuffer);
            GL20.glUniform4fv(colorsUniform, colorBuffer);
            GL20.glUniform1f(nearFadeUniform, (float) SkySetting.PILLAR_NEAR_FADE.get());
            GL20.glUniform1f(extinctionUniform, (float) extinctionPerBlock);
            GL20.glUniform1f(dustBottomHeightUniform, (float) dustBottomHeight);
            GL20.glUniform1f(lateralBlurUniform, (float) LightPillarOptics.lateralBlur(pixelAngle));
            GL20.glUniform1f(marginAngleUniform, (float) pixelAngle);
            inverseViewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(inverseViewProjectionUniform, false, matrixBuffer);
            GL20.glUniform2f(screenSizeUniform, mainTarget.width, mainTarget.height);
            GL20.glUniform1f(crystalCountScaleUniform, (float) LightPillarOptics.crystalCountScale(pixelAngle,
                    SkySetting.PILLAR_CRYSTAL_SIZE.get()));
            GL20.glUniform1f(twinkleClockUniform, (float) twinkleClock);
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            ribbonBuffer.bind();
            ribbonBuffer.draw();
        } finally {
            VertexBuffer.unbind();
            GL20.glUseProgram(previousProgram);
            GL20.glBlendEquationSeparate(colorEquation, alphaEquation);
            RenderSystem.blendFuncSeparate(sourceColor, destinationColor, sourceAlpha, destinationAlpha);
            if (!blending) {
                RenderSystem.disableBlend();
            }
            if (depthTest) {
                RenderSystem.enableDepthTest();
            }
            if (culling) {
                RenderSystem.enableCull();
            }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_UNIT);
            RenderSystem.bindTexture(previousTexture);
            RenderSystem.activeTexture(activeTexture);
            releaseColorFramebuffer(previousFramebuffer);
        }
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
            throw new IllegalStateException("The light pillar framebuffer is incomplete with status " + status);
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
     * Sets the uniforms that never change on the program in use, with the plate kinds of crystals.csv, and looks up
     * the locations of the uniforms that draw sets every frame.
     */
    private static void setFixedUniforms(int programId) {
        float[] tiltSigmas = new float[KINDS];
        float[] shares = new float[KINDS];
        IceCrystalData crystals = IceCrystalData.get();
        int kind = 0;
        for (IceCrystalData.Crystal crystal : crystals.crystals()) {
            if (crystal.orientation() == IceCrystalData.Orientation.PLATE && kind < KINDS) {
                tiltSigmas[kind] = (float) Math.toRadians(crystal.tiltDegrees());
                shares[kind] = (float) crystals.shareOf(crystal);
                kind++;
            }
        }
        GL20.glUniform2f(GL20.glGetUniformLocation(programId, "tiltSigmas"), tiltSigmas[0], tiltSigmas[1]);
        GL20.glUniform2f(GL20.glGetUniformLocation(programId, "shares"), shares[0], shares[1]);
        GL20.glUniform1f(GL20.glGetUniformLocation(programId, "refractiveIndex"),
                (float) IceHaloFormulas.refractiveIndex(1));
        GL20.glUniform1f(GL20.glGetUniformLocation(programId, "scaleHeight"),
                (float) LightPillarSelection.DUST_SCALE_HEIGHT);
        GL20.glUniform1f(GL20.glGetUniformLocation(programId, "visibleValue"),
                (float) LightPillarOptics.VISIBLE_VALUE);
        GL20.glUniform1f(GL20.glGetUniformLocation(programId, "highestElevation"),
                (float) LightPillarOptics.HIGHEST_RIBBON_ELEVATION);
        GL20.glUniform1i(GL20.glGetUniformLocation(programId, "sceneDepth"), SCENE_DEPTH_UNIT);
        viewProjectionUniform = GL20.glGetUniformLocation(programId, "viewProjection");
        lightsUniform = GL20.glGetUniformLocation(programId, "lights");
        overhangsUniform = GL20.glGetUniformLocation(programId, "overhangs");
        colorsUniform = GL20.glGetUniformLocation(programId, "colors");
        nearFadeUniform = GL20.glGetUniformLocation(programId, "nearFade");
        extinctionUniform = GL20.glGetUniformLocation(programId, "extinction");
        dustBottomHeightUniform = GL20.glGetUniformLocation(programId, "dustBottomHeight");
        lateralBlurUniform = GL20.glGetUniformLocation(programId, "lateralBlur");
        marginAngleUniform = GL20.glGetUniformLocation(programId, "marginAngle");
        inverseViewProjectionUniform = GL20.glGetUniformLocation(programId, "inverseViewProjection");
        screenSizeUniform = GL20.glGetUniformLocation(programId, "screenSize");
        crystalCountScaleUniform = GL20.glGetUniformLocation(programId, "crystalCountScale");
        twinkleClockUniform = GL20.glGetUniformLocation(programId, "twinkleClock");
    }

    /**
     * The program of both stages of light_pillars.glsl, made again when a resource reload replaced them. The ribbon
     * buffer feeds its Position attribute at location 0.
     */
    private static ShaderProgram program() {
        Shader vertex = FHShaders.getLightPillarsVertex();
        Shader fragment = FHShaders.getLightPillarsFragment();
        if (program == null || programVertex != vertex || programFragment != fragment) {
            if (program != null) {
                program.delete();
            }
            ShaderProgram created = new ShaderProgram();
            try {
                created.attach(vertex).attach(fragment);
                GL20.glBindAttribLocation(created.programId, 0, "Position");
            } catch (RuntimeException exception) {
                created.delete();
                throw exception;
            }
            program = created;
            programVertex = vertex;
            programFragment = fragment;
            uniformsProgram = null;
        }
        return program;
    }

    /**
     * The ribbons of every slot and plate kind as quads, each vertex holding its ribbon index, its row from the bottom,
     * and its side, -1 or 1. The vertex shader places them.
     */
    private static VertexBuffer ribbons() {
        if (ribbons == null) {
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            for (int ribbon = 0; ribbon < SLOTS * KINDS; ribbon++) {
                for (int segment = 0; segment < SEGMENTS; segment++) {
                    builder.vertex(ribbon, segment, -1.0).endVertex();
                    builder.vertex(ribbon, segment, 1.0).endVertex();
                    builder.vertex(ribbon, segment + 1, 1.0).endVertex();
                    builder.vertex(ribbon, segment + 1, -1.0).endVertex();
                }
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try {
                buffer.bind();
                buffer.upload(builder.end());
            } catch (RuntimeException exception) {
                buffer.close();
                throw exception;
            } finally {
                VertexBuffer.unbind();
            }
            ribbons = buffer;
        }
        return ribbons;
    }

    public static void clearFailure() {
        failed = false;
    }

    /**
     * A line about the slots drawn in the last frame and the time their draw takes, for the client command. A slot
     * counts when its fade is above 0, whether or not its pillar is in view.
     */
    public static String describe() {
        if (failed) {
            return "Light pillar drawing stopped after an error, see the log";
        }
        return String.format(Locale.ROOT, "Light pillar slots drawn in the last frame: %d; %s", drawnPillars,
                timer.describe());
    }
}
