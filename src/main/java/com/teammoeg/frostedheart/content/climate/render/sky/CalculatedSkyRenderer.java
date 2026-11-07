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
 * Draws the calculated sky and its sun over the vanilla sky dome, before vanilla draws the moon and stars.
 * Tables are built on Minecraft's background executor and uploaded on the render thread.
 * Halo maps exist for sun elevations on a grid of HALO_MAP_STEP_DEGREES and sky views on a grid of VIEW_STEP_DEGREES.
 * The two of each around the sun are blended by the exact sun elevation, so only the missing end is built when the
 * sun leaves them.
 * Builds reuse one workspace and refill the halo map and view that the previous state dropped, so following the sun
 * allocates no tables.
 */
@OnlyIn(Dist.CLIENT)
public final class CalculatedSkyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double VIEW_STEP_DEGREES = 0.25;
    private static final double LOWEST_VIEW_SUN_DEGREES = -18.0;
    private static final double HIGHEST_VIEW_SUN_DEGREES = 90.0;
    private static final double HALO_MAP_STEP_DEGREES = 0.5;
    private static final double LOWEST_HALO_SUN_DEGREES = -6.0;
    private static final double HIGHEST_HALO_SUN_DEGREES = 90.0;
    private static final int TEXTURE_UNITS = 8;
    private static final boolean OCULUS_LOADED = ModList.get().isLoaded("oculus");
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final float[] reusedFogColor = new float[SkyAtmosphere.CHANNEL_COUNT];
    private static final int[] previousBindings = new int[TEXTURE_UNITS];
    private static final int[] drawnTextures = new int[TEXTURE_UNITS];

    @Nullable
    private static SkyState state;
    @Nullable
    private static CompletableFuture<BuildResult> pendingBuild;
    /**
     * Created and used only inside build, and only one build runs at a time.
     */
    @Nullable
    private static IceHaloMap.Workspace haloWorkspace;
    /**
     * A halo map and a view that no state uses any more, handed to the next build to fill. Render thread only.
     * A build that needs both ends of a grid step allocates the second one.
     */
    @Nullable
    private static IceHaloMap spareHalo;
    @Nullable
    private static SkyView spareView;
    @Nullable
    private static FloatBuffer uploadBuffer;
    @Nullable
    private static ShaderProgram program;
    @Nullable
    private static Shader programShader;
    private static int lowSkyTexture;
    private static int highSkyTexture;
    private static int lowIceTexture;
    private static int highIceTexture;
    private static int lowHaloTexture;
    private static int highHaloTexture;
    private static int haloProfileTexture;
    private static int sunTexture;
    private static boolean failed;
    private static boolean sunDrawn;
    private static double lastTablesMilliseconds = Double.NaN;
    private static double lastViewMilliseconds = Double.NaN;
    private static double lastHaloProfileMilliseconds = Double.NaN;
    private static double lastHaloMapMilliseconds = Double.NaN;

    /**
     * Everything the sky is drawn from. The low halo map is for the sun elevation of the halo index on its grid,
     * and the high one for the next grid step. The views are on their own grid in the same way.
     */
    private record SkyState(SkyTables tables, IceHaloProfile haloProfile, int haloIndex, IceHaloMap lowHalo,
            IceHaloMap highHalo, int viewIndex, SkyView lowView, SkyView highView) {
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

        @Nullable
        SkyView viewAt(int index) {
            if (index == viewIndex) {
                return lowView;
            }
            if (index == viewIndex + 1) {
                return highView;
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

    /**
     * Whether the calculated sky may be drawn in the level, which needs it enabled, the overworld, no shader pack, and
     * no earlier error.
     */
    public static boolean isAllowed(ClientLevel level) {
        return FHConfig.CLIENT.calculatedSky.get() && !failed && level.dimension() == Level.OVERWORLD
                && !shaderPackInUse();
    }

    /**
     * Whether the sky is drawn from tables, halo maps, and views built for the current config and sun elevation, with
     * no build pending.
     */
    public static boolean isSettled(ClientLevel level, float partialTick) {
        SkyState current = state;
        if (current == null || pendingBuild != null || !isAllowed(level)) {
            return false;
        }
        double sunElevation = Math.asin(Math.cos(level.getTimeOfDay(partialTick) * Math.PI * 2.0));
        return current.tables().settings().equals(configSettings())
                && current.haloIndex() == gridIndex(sunElevation, HALO_MAP_STEP_DEGREES, LOWEST_HALO_SUN_DEGREES,
                        HIGHEST_HALO_SUN_DEGREES)
                && current.viewIndex() == gridIndex(sunElevation, VIEW_STEP_DEGREES, LOWEST_VIEW_SUN_DEGREES,
                        HIGHEST_VIEW_SUN_DEGREES);
    }

    private static boolean shaderPackInUse() {
        return OCULUS_LOADED && OculusShaderPack.isInUse();
    }

    /**
     * Whether the calculated sky drew its sun in the current call of LevelRenderer.renderSky, so vanilla's is hidden.
     */
    public static boolean isSunDrawn() {
        return sunDrawn;
    }

    public static void render(ClientLevel level, PoseStack poseStack, Matrix4f projection, float partialTick,
            Camera camera) {
        sunDrawn = false;
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
            sunDrawn = true;
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
        float viewBlend = blendWeight(Math.asin(Math.cos(timeOfDayAngle)), current.lowView().sunElevationRadians(),
                current.highView().sunElevationRadians());
        SkyView.horizonDisplayColor(current.lowView(), current.highView(), viewBlend, azimuth, horizontalFraction,
                FHConfig.CLIENT.calculatedSkyExposure.get(), level.getRainLevel(partialTick),
                level.getThunderLevel(partialTick), reusedFogColor);
        return reusedFogColor;
    }

    /**
     * Writes the sky light on the terrain while the calculated sky is drawn into the result, as SkyView.daylightColor
     * gives it for vanilla's sky darkness. Returns false and leaves the result unchanged when the sky is not drawn.
     */
    public static boolean daylightColor(ClientLevel level, float partialTick, float skyDarken, float[] result) {
        SkyState current = state;
        if (current == null || !isAllowed(level)) {
            return false;
        }
        double sunElevation = Math.asin(Math.cos(level.getTimeOfDay(partialTick) * Math.PI * 2.0));
        float viewBlend = blendWeight(sunElevation, current.lowView().sunElevationRadians(),
                current.highView().sunElevationRadians());
        SkyView.daylightColor(current.lowView(), current.highView(), viewBlend,
                FHConfig.CLIENT.calculatedSkyExposure.get(), level.getRainLevel(partialTick), skyDarken, result);
        return true;
    }

    /**
     * The sign of the sun's x direction, which turns world azimuths into azimuths from the sun as the tables use them.
     */
    private static float sunSide(double timeOfDayAngle) {
        return Math.sin(timeOfDayAngle) > 0.0 ? -1.0F : 1.0F;
    }

    /**
     * The index on a grid of sun elevations at or below the sun, clamped so that the next index is still on the grid.
     */
    private static int gridIndex(double sunElevationRadians, double stepDegrees, double lowestDegrees,
            double highestDegrees) {
        int lowest = (int) Math.round(lowestDegrees / stepDegrees);
        int highest = (int) Math.round(highestDegrees / stepDegrees) - 1;
        int index = (int) Math.floor(Math.toDegrees(sunElevationRadians) / stepDegrees);
        return Math.max(lowest, Math.min(highest, index));
    }

    private static double gridSunElevation(int index, double stepDegrees) {
        return Math.toRadians(index * stepDegrees);
    }

    /**
     * How far the sun is from the low elevation toward the high one, clamped to the range from 0 to 1.
     */
    private static float blendWeight(double sunElevation, double lowElevation, double highElevation) {
        return (float) Math.max(0.0, Math.min(1.0, (sunElevation - lowElevation) / (highElevation - lowElevation)));
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
            keepSpares(state, result.state());
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
        int haloIndex = gridIndex(sunElevation, HALO_MAP_STEP_DEGREES, LOWEST_HALO_SUN_DEGREES,
                HIGHEST_HALO_SUN_DEGREES);
        int viewIndex = gridIndex(sunElevation, VIEW_STEP_DEGREES, LOWEST_VIEW_SUN_DEGREES, HIGHEST_VIEW_SUN_DEGREES);
        boolean haloMoved = current != null && current.haloIndex() != haloIndex;
        boolean viewMoved = current != null && current.viewIndex() != viewIndex;
        if (!settingsChanged && !haloMoved && !viewMoved) {
            return;
        }
        SkyState previous = settingsChanged ? null : current;
        boolean haloNeeded = previous == null || previous.haloAt(haloIndex) == null
                || previous.haloAt(haloIndex + 1) == null;
        boolean viewNeeded = previous == null || previous.viewAt(viewIndex) == null
                || previous.viewAt(viewIndex + 1) == null;
        IceHaloMap recycledHalo = haloNeeded ? spareHalo : null;
        SkyView recycledView = viewNeeded ? spareView : null;
        if (haloNeeded) {
            spareHalo = null;
        }
        if (viewNeeded) {
            spareView = null;
        }
        pendingBuild = CompletableFuture.supplyAsync(() -> build(previous, settings, haloIndex, viewIndex,
                recycledHalo, recycledView), Util.backgroundExecutor());
    }

    /**
     * Keeps a halo map and a view of the previous state that the next state no longer uses, so the following build
     * fills them instead of allocating. Runs after the build finished, when nothing reads the previous state any more.
     */
    private static void keepSpares(@Nullable SkyState previous, SkyState next) {
        if (previous == null) {
            return;
        }
        keepSpareHalo(previous.lowHalo(), next);
        keepSpareHalo(previous.highHalo(), next);
        keepSpareView(previous.lowView(), next);
        keepSpareView(previous.highView(), next);
    }

    private static void keepSpareHalo(IceHaloMap halo, SkyState next) {
        if (spareHalo == null && halo != next.lowHalo() && halo != next.highHalo()) {
            spareHalo = halo;
        }
    }

    private static void keepSpareView(SkyView view, SkyState next) {
        if (spareView == null && view != next.lowView() && view != next.highView()) {
            spareView = view;
        }
    }

    /**
     * Builds the next state, reusing what the previous state already has and filling the recycled map and view when
     * given. Without a previous state everything is built for the given settings.
     */
    private static BuildResult build(@Nullable SkyState previous, SkySettings settings, int haloIndex, int viewIndex,
            @Nullable IceHaloMap recycledHalo, @Nullable SkyView recycledView) {
        long start = System.nanoTime();
        SkyTables tables = previous != null ? previous.tables() : new SkyTables(settings);
        double tablesMilliseconds = previous != null ? Double.NaN : (System.nanoTime() - start) / 1.0e6;

        start = System.nanoTime();
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(settings.actualTexelDegrees());
        IceHaloProfile haloProfile = previous != null ? previous.haloProfile() : new IceHaloProfile(data, blurSigma);
        double haloProfileMilliseconds = previous != null ? Double.NaN : (System.nanoTime() - start) / 1.0e6;

        if (haloWorkspace == null) {
            haloWorkspace = new IceHaloMap.Workspace(data);
        }
        IceHaloMap unusedHalo = recycledHalo;
        IceHaloMap lowHalo = previous != null ? previous.haloAt(haloIndex) : null;
        IceHaloMap highHalo = previous != null ? previous.haloAt(haloIndex + 1) : null;
        double haloMapMilliseconds = Double.NaN;
        if (lowHalo == null) {
            start = System.nanoTime();
            lowHalo = unusedHalo != null ? unusedHalo : new IceHaloMap();
            unusedHalo = null;
            lowHalo.build(haloWorkspace, gridSunElevation(haloIndex, HALO_MAP_STEP_DEGREES), blurSigma);
            haloMapMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }
        if (highHalo == null) {
            start = System.nanoTime();
            highHalo = unusedHalo != null ? unusedHalo : new IceHaloMap();
            highHalo.build(haloWorkspace, gridSunElevation(haloIndex + 1, HALO_MAP_STEP_DEGREES), blurSigma);
            haloMapMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }

        SkyView unusedView = recycledView;
        SkyView lowView = previous != null ? previous.viewAt(viewIndex) : null;
        SkyView highView = previous != null ? previous.viewAt(viewIndex + 1) : null;
        double viewMilliseconds = Double.NaN;
        if (lowView == null) {
            start = System.nanoTime();
            lowView = unusedView != null ? unusedView : new SkyView();
            unusedView = null;
            computeView(tables, haloProfile, lowHalo, highHalo, gridSunElevation(viewIndex, VIEW_STEP_DEGREES),
                    lowView);
            viewMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }
        if (highView == null) {
            start = System.nanoTime();
            highView = unusedView != null ? unusedView : new SkyView();
            computeView(tables, haloProfile, lowHalo, highHalo, gridSunElevation(viewIndex + 1, VIEW_STEP_DEGREES),
                    highView);
            viewMilliseconds = (System.nanoTime() - start) / 1.0e6;
        }
        return new BuildResult(new SkyState(tables, haloProfile, haloIndex, lowHalo, highHalo, viewIndex, lowView,
                highView), tablesMilliseconds, haloProfileMilliseconds, haloMapMilliseconds, viewMilliseconds);
    }

    /**
     * Fills a view for a sun elevation, with the light that ice scatters onto the ground from the nearer halo map.
     */
    private static void computeView(SkyTables tables, IceHaloProfile haloProfile, IceHaloMap lowHalo,
            IceHaloMap highHalo, double sunElevation, SkyView view) {
        double lowDistance = Math.abs(sunElevation - lowHalo.sunElevationRadians());
        double highDistance = Math.abs(sunElevation - highHalo.sunElevationRadians());
        tables.computeView(sunElevation, haloProfile, lowDistance <= highDistance ? lowHalo : highHalo, view);
    }

    public static SkySettings configSettings() {
        return new SkySettings(FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.get(),
                FHConfig.CLIENT.calculatedSkyNanobotSpread.get(),
                FHConfig.CLIENT.calculatedSkyIceOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyTexelDegrees.get());
    }

    private static void upload(@Nullable SkyState previous, SkyState next) {
        if (previous == null || previous.tables() != next.tables()) {
            sunTexture = uploadTable(sunTexture, next.tables().sunRadiance(), 1, SkyTables.VIEW_HEIGHT,
                    GL12.GL_CLAMP_TO_EDGE);
        }
        if (previous == null || previous.lowView() != next.lowView()) {
            lowSkyTexture = uploadTable(lowSkyTexture, next.lowView().sky(), SkyTables.VIEW_WIDTH,
                    SkyTables.VIEW_HEIGHT, GL11.GL_REPEAT);
            lowIceTexture = uploadTable(lowIceTexture, next.lowView().ice(), SkyTables.VIEW_WIDTH,
                    SkyTables.VIEW_HEIGHT, GL11.GL_REPEAT);
        }
        if (previous == null || previous.highView() != next.highView()) {
            highSkyTexture = uploadTable(highSkyTexture, next.highView().sky(), SkyTables.VIEW_WIDTH,
                    SkyTables.VIEW_HEIGHT, GL11.GL_REPEAT);
            highIceTexture = uploadTable(highIceTexture, next.highView().ice(), SkyTables.VIEW_WIDTH,
                    SkyTables.VIEW_HEIGHT, GL11.GL_REPEAT);
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
        float haloBlend = blendWeight(sunElevation, current.lowHalo().sunElevationRadians(),
                current.highHalo().sunElevationRadians());
        float viewBlend = blendWeight(sunElevation, current.lowView().sunElevationRadians(),
                current.highView().sunElevationRadians());
        drawnTextures[0] = lowSkyTexture;
        drawnTextures[1] = highSkyTexture;
        drawnTextures[2] = lowIceTexture;
        drawnTextures[3] = highIceTexture;
        drawnTextures[4] = lowHaloTexture;
        drawnTextures[5] = highHaloTexture;
        drawnTextures[6] = haloProfileTexture;
        drawnTextures[7] = sunTexture;

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
                uniforms.glUniform1I("lowSkyView", 0);
                uniforms.glUniform1I("highSkyView", 1);
                uniforms.glUniform1I("lowIceView", 2);
                uniforms.glUniform1I("highIceView", 3);
                uniforms.glUniform1I("lowHaloMap", 4);
                uniforms.glUniform1I("highHaloMap", 5);
                uniforms.glUniform1I("haloProfile", 6);
                uniforms.glUniform1I("sunRadiance", 7);
                uniforms.glUniform1F("viewBlend", viewBlend);
                uniforms.glUniformMatrix4F("inverseViewProjection", inverseViewProjection);
                uniforms.glUniform3F("sunDirection", sunX, sunY, 0.0F);
                uniforms.glUniform1F("sunSide", sunSide);
                uniforms.glUniform1F("sunHalfTangent", (float) SkyTables.SUN_HALF_TANGENT);
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
