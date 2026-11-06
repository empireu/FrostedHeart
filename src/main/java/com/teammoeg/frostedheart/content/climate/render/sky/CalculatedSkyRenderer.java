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
 */
@OnlyIn(Dist.CLIENT)
public final class CalculatedSkyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double SUN_REFRESH_RADIANS = Math.toRadians(0.25);
    private static final double NIGHT_ELEVATION_RADIANS = Math.toRadians(-18.0);
    private static final boolean OCULUS_LOADED = ModList.get().isLoaded("oculus");
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final float[] reusedFogColor = new float[SkyAtmosphere.CHANNEL_COUNT];

    @Nullable
    private static SkyTables tables;
    @Nullable
    private static SkyView uploadedView;
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
    private static boolean failed;
    private static double lastTablesMilliseconds = Double.NaN;
    private static double lastViewMilliseconds = Double.NaN;

    private record BuildResult(SkyTables tables, SkyView view, double tablesMilliseconds, double viewMilliseconds) {
    }

    private CalculatedSkyRenderer() {
    }

    public static boolean isDrawing(ClientLevel level) {
        return isAllowed(level) && uploadedView != null;
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
            if (failed || uploadedView == null || camera.getFluidInCamera() != FogType.NONE) {
                return;
            }
            draw(level, poseStack, projection, partialTick, timeOfDayAngle);
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
        SkyView view = uploadedView;
        if (view == null || !isAllowed(level)) {
            return null;
        }
        Vector3f look = camera.getLookVector();
        double horizontalFraction = Math.min(1.0, Math.sqrt(look.x() * look.x() + look.z() * look.z()));
        double timeOfDayAngle = level.getTimeOfDay(partialTick) * Math.PI * 2.0;
        double azimuth = Math.atan2(look.z(), look.x() * sunSide(timeOfDayAngle));
        view.horizonDisplayColor(azimuth, horizontalFraction, FHConfig.CLIENT.calculatedSkyExposure.get(),
                level.getRainLevel(partialTick), level.getThunderLevel(partialTick), reusedFogColor);
        return reusedFogColor;
    }

    /**
     * The sign of the sun's x direction, which turns world azimuths into azimuths from the sun as the tables use them.
     */
    private static float sunSide(double timeOfDayAngle) {
        return Math.sin(timeOfDayAngle) > 0.0 ? -1.0F : 1.0F;
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
            upload(result.view());
            tables = result.tables();
            uploadedView = result.view();
            if (!Double.isNaN(result.tablesMilliseconds())) {
                lastTablesMilliseconds = result.tablesMilliseconds();
            }
            lastViewMilliseconds = result.viewMilliseconds();
        }

        boolean settingsChanged = tables == null || !matchesConfig(tables.settings());
        boolean sunMoved = uploadedView == null
                || Math.abs(sunElevation - uploadedView.sunElevationRadians()) > SUN_REFRESH_RADIANS
                && Math.max(sunElevation, uploadedView.sunElevationRadians()) > NIGHT_ELEVATION_RADIANS;
        if (!settingsChanged && !sunMoved) {
            return;
        }
        SkyTables reusable = settingsChanged ? null : tables;
        SkySettings settings = settingsChanged ? configSettings() : tables.settings();
        pendingBuild = CompletableFuture.supplyAsync(() -> build(reusable, settings, sunElevation),
                Util.backgroundExecutor());
    }

    private static BuildResult build(@Nullable SkyTables reusable, SkySettings settings, double sunElevation) {
        long start = System.nanoTime();
        SkyTables built = reusable != null ? reusable : new SkyTables(settings);
        double tablesMilliseconds = reusable != null ? Double.NaN : (System.nanoTime() - start) / 1.0e6;
        start = System.nanoTime();
        SkyView view = built.computeView(sunElevation);
        return new BuildResult(built, view, tablesMilliseconds, (System.nanoTime() - start) / 1.0e6);
    }

    private static boolean matchesConfig(SkySettings settings) {
        return settings.nanobotOpticalDepth() == FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.get()
                && settings.nanobotMedianNanometers() == FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.get()
                && settings.nanobotSpread() == FHConfig.CLIENT.calculatedSkyNanobotSpread.get()
                && settings.iceOpticalDepth() == FHConfig.CLIENT.calculatedSkyIceOpticalDepth.get();
    }

    public static SkySettings configSettings() {
        return new SkySettings(FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.get(),
                FHConfig.CLIENT.calculatedSkyNanobotSpread.get(),
                FHConfig.CLIENT.calculatedSkyIceOpticalDepth.get());
    }

    private static void upload(SkyView view) {
        skyTexture = uploadTable(skyTexture, view.sky());
        iceTexture = uploadTable(iceTexture, view.ice());
    }

    private static int uploadTable(int texture, float[] values) {
        if (uploadBuffer == null) {
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
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F, SkyTables.VIEW_WIDTH, SkyTables.VIEW_HEIGHT,
                        0, GL11.GL_RGB, GL11.GL_FLOAT, buffer);
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SkyTables.VIEW_WIDTH, SkyTables.VIEW_HEIGHT,
                        GL11.GL_RGB, GL11.GL_FLOAT, buffer);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        return target;
    }

    private static void draw(ClientLevel level, PoseStack poseStack, Matrix4f projection, float partialTick,
            double timeOfDayAngle) {
        ShaderProgram shaderProgram = program();
        inverseViewProjection.set(projection).mul(poseStack.last().pose()).invert();
        float sunX = (float) -Math.sin(timeOfDayAngle);
        float sunY = (float) Math.cos(timeOfDayAngle);
        float sunSide = sunSide(timeOfDayAngle);
        float[] fogColor = RenderSystem.getShaderFogColor();
        float exposure = FHConfig.CLIENT.calculatedSkyExposure.get().floatValue();
        float rainLevel = level.getRainLevel(partialTick);
        float thunderLevel = level.getThunderLevel(partialTick);

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int previousSkyBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        int previousIceBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(skyTexture);
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            RenderSystem.bindTexture(iceTexture);
            shaderProgram.use(uniforms -> {
                uniforms.glUniform1I("skyView", 0);
                uniforms.glUniform1I("iceView", 1);
                uniforms.glUniformMatrix4F("inverseViewProjection", inverseViewProjection);
                uniforms.glUniform3F("sunDirection", sunX, sunY, 0.0F);
                uniforms.glUniform1F("sunSide", sunSide);
                uniforms.glUniform1F("horizonDip", (float) SkyAtmosphere.HORIZON_DIP_RADIANS);
                uniforms.glUniform1F("exposure", exposure);
                uniforms.glUniform1F("haloFraction", (float) SkyAtmosphere.ICE_HALO_FRACTION);
                uniforms.glUniform1F("diffractionFraction", (float) SkyAtmosphere.ICE_DIFFRACTION_FRACTION);
                uniforms.glUniform1F("diffractionAsymmetry", (float) SkyAtmosphere.ICE_DIFFRACTION_ASYMMETRY);
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
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(previousSkyBinding);
            RenderSystem.activeTexture(GL13.GL_TEXTURE1);
            RenderSystem.bindTexture(previousIceBinding);
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
        if (uploadedView == null) {
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

    private static final class OculusShaderPack {
        private static boolean isInUse() {
            return IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
