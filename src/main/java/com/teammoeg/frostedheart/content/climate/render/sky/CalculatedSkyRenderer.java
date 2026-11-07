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
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.infrastructure.config.FHConfig;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Draws the calculated sky over the vanilla sky dome, before vanilla draws the sun, moon, and stars.
 * Tables are built on Minecraft's background executor and uploaded on the render thread.
 * Halo maps exist for sun elevations on a grid of HALO_MAP_STEP_DEGREES, and the two around the sun are blended
 * by the exact sun elevation, so only the missing end is built when the sun leaves them.
 */
@OnlyIn(Dist.CLIENT)
public final class CalculatedSkyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double SUN_REFRESH_RADIANS = Math.toRadians(0.25);
    private static final double NIGHT_ELEVATION_RADIANS = Math.toRadians(-18.0);
    private static final double HALO_MAP_STEP_DEGREES = 0.5;
    private static final double LOWEST_HALO_SUN_DEGREES = -6.0;
    private static final double HIGHEST_HALO_SUN_DEGREES = 90.0;
    private static final int TEXTURE_UNITS = 5;
    private static final boolean OCULUS_LOADED = ModList.get().isLoaded("oculus");
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final float[] reusedFogColor = new float[SkyAtmosphere.CHANNEL_COUNT];
    private static final int[] previousBindings = new int[TEXTURE_UNITS];
    private static final int[] drawnTextures = new int[TEXTURE_UNITS];

    @Nullable
    private static SkyState state;
    @Nullable
    private static CompletableFuture<BuildResult> pendingBuild;
    @Nullable
    private static FloatBuffer uploadBuffer;
    @Nullable
    private static ShaderProgram program;
    @Nullable
    private static Shader programShader;
    private static int skyTexture;
    private static int iceTexture;
    private static int lowHaloTexture;
    private static int highHaloTexture;
    private static int haloProfileTexture;
    private static boolean failed;
    private static double lastTablesMilliseconds = Double.NaN;
    private static double lastViewMilliseconds = Double.NaN;
    private static double lastHaloProfileMilliseconds = Double.NaN;
    private static double lastHaloMapMilliseconds = Double.NaN;

    /**
     * Everything the sky is drawn from. The low halo map is for the sun elevation of the halo index on the grid,
     * and the high one for the next grid step.
     */
    private record SkyState(SkyTables tables, IceHaloProfile haloProfile, int haloIndex, IceHaloMap lowHalo,
            IceHaloMap highHalo, SkyView view) {
        @Nullable
        IceHaloMap haloAt(int index) {
            if (index == haloIndex) {
                return lowHalo;
            }
            if (index == haloIndex + 1) {
                return highHalo;
            }
            return null;
        }
    }

    private record BuildResult(SkyState state, double tablesMilliseconds, double haloProfileMilliseconds,
            double haloMapMilliseconds, double viewMilliseconds) {
    }

    private CalculatedSkyRenderer() {
    }

    public static boolean isDrawing(ClientLevel level) {
        return isAllowed(level) && state != null;
    }

    private static boolean isAllowed(ClientLevel level) {
        return FHConfig.CLIENT.calculatedSky.get() && !failed && level.dimension() == Level.OVERWORLD
                && !shaderPackInUse();
    }

    private static boolean shaderPackInUse() {
        return OCULUS_LOADED && OculusShaderPack.isInUse();
    }

    public static void render(ClientLevel level, PoseStack poseStack, Matrix4f projection, float partialTick,
            Camera camera) {
        if (!isAllowed(level)) {
            return;
        }
        try {
            double timeOfDayAngle = level.getTimeOfDay(partialTick) * Math.PI * 2.0;
            updateTables(Math.asin(Math.cos(timeOfDayAngle)));
            if (failed || state == null || camera.getFluidInCamera() != FogType.NONE) {
                return;
            }
            draw(state, level, poseStack, projection, partialTick, timeOfDayAngle);
        } catch (RuntimeException exception) {
            fail("drawing", exception);
        }
    }

    /**
     * The fog colour while the calculated sky is drawn: the sky at the horizon in the direction the camera looks.
     * Returns null when the calculated sky is not drawn. The returned array is reused by the next call.
     */
    @Nullable
    public static float[] fogColor(ClientLevel level, Camera camera, float partialTick) {
        SkyState current = state;
        if (current == null || !isAllowed(level)) {
            return null;
        }
        Vector3f look = camera.getLookVector();
        double horizontalFraction = Math.min(1.0, Math.sqrt(look.x() * look.x() + look.z() * look.z()));
        double timeOfDayAngle = level.getTimeOfDay(partialTick) * Math.PI * 2.0;
        double azimuth = Math.atan2(look.z(), look.x() * sunSide(timeOfDayAngle));
        current.view().horizonDisplayColor(azimuth, horizontalFraction, FHConfig.CLIENT.calculatedSkyExposure.get(),
                level.getRainLevel(partialTick), level.getThunderLevel(partialTick), reusedFogColor);
        return reusedFogColor;
    }

    /**
     * The sign of the sun's x direction, which turns world azimuths into azimuths from the sun as the tables use them.
     */
    private static float sunSide(double timeOfDayAngle) {
        return Math.sin(timeOfDayAngle) > 0.0 ? -1.0F : 1.0F;
    }

    /**
     * The grid index of the halo map at or below the sun, clamped so that the next index is still on the grid.
     */
    private static int haloIndex(double sunElevationRadians) {
        int lowest = (int) Math.round(LOWEST_HALO_SUN_DEGREES / HALO_MAP_STEP_DEGREES);
        int highest = (int) Math.round(HIGHEST_HALO_SUN_DEGREES / HALO_MAP_STEP_DEGREES) - 1;
        int index = (int) Math.floor(Math.toDegrees(sunElevationRadians) / HALO_MAP_STEP_DEGREES);
        return Math.max(lowest, Math.min(highest, index));
    }

    private static double haloSunElevation(int index) {
        return Math.toRadians(index * HALO_MAP_STEP_DEGREES);
    }

    private static void updateTables(double sunElevation) {
        if (pendingBuild != null) {
            if (!pendingBuild.isDone()) {
                return;
            }
            CompletableFuture<BuildResult> finished = pendingBuild;
            pendingBuild = null;
            BuildResult result;
            try {
                result = finished.join();
            } catch (CompletionException exception) {
                fail("building tables", exception.getCause());
                return;
            }
            upload(state, result.state());
            state = result.state();
            if (!Double.isNaN(result.tablesMilliseconds())) {
                lastTablesMilliseconds = result.tablesMilliseconds();
            }
            if (!Double.isNaN(result.haloProfileMilliseconds())) {
                lastHaloProfileMilliseconds = result.haloProfileMilliseconds();
            }
            if (!Double.isNaN(result.haloMapMilliseconds())) {
                lastHaloMapMilliseconds = result.haloMapMilliseconds();
            }
            if (!Double.isNaN(result.viewMilliseconds())) {
                lastViewMilliseconds = result.viewMilliseconds();
            }
        }

        SkyState current = state;
        SkySettings settings = configSettings();
        boolean settingsChanged = current == null || !current.tables().settings().equals(settings);
        int haloIndex = haloIndex(sunElevation);
        boolean haloMoved = current != null && current.haloIndex() != haloIndex;
        boolean sunMoved = current != null
                && Math.abs(sunElevation - current.view().sunElevationRadians()) > SUN_REFRESH_RADIANS
                && Math.max(sunElevation, current.view().sunElevationRadians()) > NIGHT_ELEVATION_RADIANS;
        if (!settingsChanged && !haloMoved && !sunMoved) {
            return;
        }
        SkyState previous = settingsChanged ? null : current;
        pendingBuild = CompletableFuture.supplyAsync(() -> build(previous, settings, sunElevation, haloIndex, sunMoved),
                Util.backgroundExecutor());
    }

    /**
     * Builds the next state, reusing what the previous state already has. Without a previous state everything is
     * built for the given settings.
     */
    private static BuildResult build(@Nullable SkyState previous, SkySettings settings, double sunElevation,
            int haloIndex, boolean viewNeeded) {
        long start = System.nanoTime();
        SkyTables tables = previous != null ? previous.tables() : new SkyTables(settings);
        double tablesMilliseconds = previous != null ? Double.NaN : (System.nanoTime() - start) / 1.0e6;

        start = System.nanoTime();
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(settings.actualTexelDegrees());
        IceHaloProfile haloProfile = previous != null ? previous.haloProfile() : new IceHaloProfile(data, blurSigma);
        double haloProfileMilliseconds = previous != null ? Double.NaN : (System.nanoTime() - start) / 1.0e6;

        IceHaloMap lowHalo = previous != null ? previous.haloAt(haloIndex) : null;
        IceHaloMap highHalo = previous != null ? previous.haloAt(haloIndex + 1) : null;
        double haloMapMilliseconds = Double.NaN;
        if (lowHalo == null) {
            start = System.nanoTime();
            lowHalo = new IceHaloMap(data, haloSunElevation(haloIndex), blurSigma);
            haloMapMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }
        if (highHalo == null) {
            start = System.nanoTime();
            highHalo = new IceHaloMap(data, haloSunElevation(haloIndex + 1), blurSigma);
            haloMapMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }

        SkyView view;
        double viewMilliseconds = Double.NaN;
        if (previous != null && !viewNeeded) {
            view = previous.view();
        } else {
            start = System.nanoTime();
            double lowDistance = Math.abs(sunElevation - lowHalo.sunElevationRadians());
            double highDistance = Math.abs(sunElevation - highHalo.sunElevationRadians());
            view = tables.computeView(sunElevation, haloProfile, lowDistance <= highDistance ? lowHalo : highHalo);
            viewMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }
        return new BuildResult(new SkyState(tables, haloProfile, haloIndex, lowHalo, highHalo, view),
                tablesMilliseconds, haloProfileMilliseconds, haloMapMilliseconds, viewMilliseconds);
    }

    public static SkySettings configSettings() {
        return new SkySettings(FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.get(),
                FHConfig.CLIENT.calculatedSkyNanobotSpread.get(),
                FHConfig.CLIENT.calculatedSkyIceOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyTexelDegrees.get());
    }

    private static void upload(@Nullable SkyState previous, SkyState next) {
        if (previous == null || previous.view() != next.view()) {
            skyTexture = uploadTable(skyTexture, next.view().sky(), SkyTables.VIEW_WIDTH, SkyTables.VIEW_HEIGHT,
                    GL11.GL_REPEAT);
            iceTexture = uploadTable(iceTexture, next.view().ice(), SkyTables.VIEW_WIDTH, SkyTables.VIEW_HEIGHT,
                    GL11.GL_REPEAT);
        }
        if (previous == null || previous.haloProfile() != next.haloProfile()) {
            haloProfileTexture = uploadTable(haloProfileTexture, next.haloProfile().values(),
                    IceHaloProfile.ANGLE_BINS, 1, GL12.GL_CLAMP_TO_EDGE);
        }
        if (previous == null || previous.lowHalo() != next.lowHalo()) {
            lowHaloTexture = uploadTable(lowHaloTexture, next.lowHalo().values(), IceHaloMap.AZIMUTH_BINS,
                    IceHaloMap.ELEVATION_BINS, GL14.GL_MIRRORED_REPEAT);
        }
        if (previous == null || previous.highHalo() != next.highHalo()) {
            highHaloTexture = uploadTable(highHaloTexture, next.highHalo().values(), IceHaloMap.AZIMUTH_BINS,
                    IceHaloMap.ELEVATION_BINS, GL14.GL_MIRRORED_REPEAT);
        }
    }

    /**
     * Uploads an interleaved RGB table as a half float texture with linear filtering, clamped in T.
     * A texture is allocated on its first upload and keeps its size afterwards.
     */
    private static int uploadTable(int texture, float[] values, int width, int height, int wrapS) {
        if (uploadBuffer == null || uploadBuffer.capacity() < values.length) {
            uploadBuffer = BufferUtils.createFloatBuffer(values.length);
        }
        FloatBuffer buffer = uploadBuffer;
        buffer.clear();
        buffer.put(values);
        buffer.flip();
        boolean allocate = texture == 0;
        int target = allocate ? GL11.glGenTextures() : texture;
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
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrapS);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F, width, height, 0, GL11.GL_RGB, GL11.GL_FLOAT,
                        buffer);
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGB, GL11.GL_FLOAT, buffer);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        return target;
    }

    private static void draw(SkyState current, ClientLevel level, PoseStack poseStack, Matrix4f projection,
            float partialTick, double timeOfDayAngle) {
        ShaderProgram shaderProgram = program();
        inverseViewProjection.set(projection).mul(poseStack.last().pose()).invert();
        float sunX = (float) -Math.sin(timeOfDayAngle);
        float sunY = (float) Math.cos(timeOfDayAngle);
        float sunSide = sunSide(timeOfDayAngle);
        float[] fogColor = RenderSystem.getShaderFogColor();
        float exposure = FHConfig.CLIENT.calculatedSkyExposure.get().floatValue();
        float rainLevel = level.getRainLevel(partialTick);
        float thunderLevel = level.getThunderLevel(partialTick);
        float texelFaceSize = current.tables().settings().texelFaceSize();
        double sunElevation = Math.asin(Math.cos(timeOfDayAngle));
        float haloBlend = (float) Math.max(0.0, Math.min(1.0,
                (sunElevation - current.lowHalo().sunElevationRadians())
                        / (current.highHalo().sunElevationRadians() - current.lowHalo().sunElevationRadians())));
        drawnTextures[0] = skyTexture;
        drawnTextures[1] = iceTexture;
        drawnTextures[2] = lowHaloTexture;
        drawnTextures[3] = highHaloTexture;
        drawnTextures[4] = haloProfileTexture;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
            previousBindings[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        try {
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
                RenderSystem.bindTexture(drawnTextures[unit]);
            }
            shaderProgram.use(uniforms -> {
                uniforms.glUniform1I("skyView", 0);
                uniforms.glUniform1I("iceView", 1);
                uniforms.glUniform1I("lowHaloMap", 2);
                uniforms.glUniform1I("highHaloMap", 3);
                uniforms.glUniform1I("haloProfile", 4);
                uniforms.glUniformMatrix4F("inverseViewProjection", inverseViewProjection);
                uniforms.glUniform3F("sunDirection", sunX, sunY, 0.0F);
                uniforms.glUniform1F("sunSide", sunSide);
                uniforms.glUniform1F("horizonDip", (float) SkyAtmosphere.HORIZON_DIP_RADIANS);
                uniforms.glUniform1F("exposure", exposure);
                uniforms.glUniform1F("texelFaceSize", texelFaceSize);
                uniforms.glUniform1F("haloBlend", haloBlend);
                uniforms.glUniform1F("haloLowestElevation", (float) IceHaloMap.LOWEST_ELEVATION);
                uniforms.glUniform1F("haloElevationSpan", (float) (IceHaloMap.ELEVATION_BINS * IceHaloMap.BIN_STEP));
                uniforms.glUniform3F("fogColor", fogColor[0], fogColor[1], fogColor[2]);
                uniforms.glUniform1F("rainLevel", rainLevel);
                uniforms.glUniform1F("thunderLevel", thunderLevel);
            });
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            buffer.vertex(-1, 1, 0).endVertex();
            buffer.vertex(-1, -1, 0).endVertex();
            buffer.vertex(1, -1, 0).endVertex();
            buffer.vertex(1, 1, 0).endVertex();
            BufferUploader.draw(buffer.end());
        } finally {
            // ShaderProgram binds raw GL, so restore it raw and leave the vanilla and Oculus caches unchanged:
            GL20.glUseProgram(previousProgram);
            for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
                RenderSystem.bindTexture(previousBindings[unit]);
            }
            RenderSystem.activeTexture(previousActiveTexture);
            if (blending) {
                RenderSystem.enableBlend();
            }
            if (depthTest) {
                RenderSystem.enableDepthTest();
            }
        }
    }

    private static ShaderProgram program() {
        Shader shader = FHShaders.getCalculatedSky();
        if (program == null || programShader != shader) {
            if (program != null) {
                program.delete();
            }
            program = new ShaderProgram().attach(FHShaders.IMAGE_V).attach(shader);
            programShader = shader;
        }
        return program;
    }

    private static void fail(String stage, Throwable cause) {
        failed = true;
        LOGGER.error("The calculated sky stopped after an error while {}. The vanilla sky is drawn until it is enabled again.",
                stage, cause);
    }

    public static void clearFailure() {
        failed = false;
    }

    public static String describeState(ClientLevel level) {
        if (!FHConfig.CLIENT.calculatedSky.get()) {
            return "off";
        }
        if (failed) {
            return "stopped after an error, see the log";
        }
        if (shaderPackInUse()) {
            return "not drawn while a shader pack is in use";
        }
        if (level.dimension() != Level.OVERWORLD) {
            return "only drawn in the overworld";
        }
        if (state == null) {
            return "building tables";
        }
        return "on";
    }

    public static double lastTablesMilliseconds() {
        return lastTablesMilliseconds;
    }

    public static double lastViewMilliseconds() {
        return lastViewMilliseconds;
    }

    public static double lastHaloProfileMilliseconds() {
        return lastHaloProfileMilliseconds;
    }

    public static double lastHaloMapMilliseconds() {
        return lastHaloMapMilliseconds;
    }

    private static final class OculusShaderPack {
        private static boolean isInUse() {
            return IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
