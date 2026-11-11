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
 * Halo maps exist for sun elevations on a grid of HALO_MAP_STEP_DEGREES, tables for ice optical depths on
 * IceDepthGrid, and sky views for each of those depths and sun elevations on a grid of VIEW_STEP_DEGREES.
 * The two of each around the sun and the ice depth of IceWeatherDepth are blended by their exact values, so only the
 * missing ends are built when the sun or the depth leaves them.
 * Builds reuse one workspace and refill the halo maps and views that the previous state dropped, so following the sun
 * allocates no halo maps or views. Content that only moves to another slot keeps its texture and is not uploaded again.
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
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;
    /**
     * View slots are numbered by depth end times two plus sun end, so slot 0 has the low depth and low sun.
     */
    private static final int VIEW_SLOTS = 4;
    private static final int HALO_SLOTS = 2;
    /**
     * The row table texture holds the sun's radiance at the low and the high depth, then the diffraction weights at the
     * low and the high depth, one column each.
     */
    private static final int ROW_TABLE_COLUMNS = 4;
    /**
     * GlStateManager caches the bindings of 12 texture units, so the sky must not use more.
     */
    private static final int TEXTURE_UNITS = 12;
    private static final int SKY_VIEW_UNIT = 0;
    private static final int ICE_VIEW_UNIT = SKY_VIEW_UNIT + VIEW_SLOTS;
    private static final int HALO_MAP_UNIT = ICE_VIEW_UNIT + VIEW_SLOTS;
    private static final int HALO_PROFILE_UNIT = HALO_MAP_UNIT + HALO_SLOTS;
    private static final int ROW_TABLE_UNIT = HALO_PROFILE_UNIT + 1;
    private static final boolean OCULUS_LOADED = ModList.get().isLoaded("oculus");
    private static final Matrix4f inverseViewProjection = new Matrix4f();
    private static final float[] reusedFogColor = new float[CHANNELS];
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
     * Halo maps and views that no state uses any more, for the next build to fill. The render thread owns them except
     * while a build runs, which takes from them.
     */
    private static final IceHaloMap[] spareHalos = new IceHaloMap[HALO_SLOTS];
    private static int spareHaloCount;
    private static final SkyView[] spareViews = new SkyView[VIEW_SLOTS];
    private static int spareViewCount;
    /**
     * Build times of the running build, written only by the build and read after it finished.
     */
    private static double buildTablesMilliseconds;
    private static double buildHaloMapMilliseconds;
    private static double buildViewMilliseconds;
    @Nullable
    private static FloatBuffer uploadBuffer;
    @Nullable
    private static ShaderProgram program;
    @Nullable
    private static Shader programShader;
    private static final int[] viewSkyTextures = new int[VIEW_SLOTS];
    private static final int[] viewIceTextures = new int[VIEW_SLOTS];
    private static final int[] haloTextures = new int[HALO_SLOTS];
    private static int haloProfileTexture;
    private static int rowTableTexture;
    /**
     * What the textures of each slot hold, so that upload skips content already on the GPU.
     */
    private static final Object[] uploadedViews = new Object[VIEW_SLOTS];
    private static final Object[] uploadedHalos = new Object[HALO_SLOTS];
    @Nullable
    private static IceHaloProfile uploadedHaloProfile;
    @Nullable
    private static SkyTables uploadedLowDepthTables;
    @Nullable
    private static SkyTables uploadedHighDepthTables;
    private static final Object[] wantedSlots = new Object[VIEW_SLOTS];
    private static final int[] slotSources = new int[VIEW_SLOTS];
    private static final boolean[] slotTaken = new boolean[VIEW_SLOTS];
    private static final boolean[] slotNeedsUpload = new boolean[VIEW_SLOTS];
    private static final int[] reorderedTextures = new int[VIEW_SLOTS];
    private static final float[] rowTableValues = new float[ROW_TABLE_COLUMNS * SkyTables.VIEW_HEIGHT * CHANNELS];
    private static boolean failed;
    private static boolean sunDrawn;
    private static double lastTablesMilliseconds = Double.NaN;
    private static double lastViewMilliseconds = Double.NaN;
    private static double lastHaloProfileMilliseconds = Double.NaN;
    private static double lastHaloMapMilliseconds = Double.NaN;

    /**
     * Everything the sky is drawn from. The low halo map is for the sun elevation of the halo index on its grid, and
     * the high one for the next grid step. Tables and views are on their own grids in the same way, with a view for
     * each pair of depth and sun elevation. The settings are those of the config, whatever their ice optical depth.
     */
    private record SkyState(SkySettings settings, IceHaloProfile haloProfile, int haloIndex, IceHaloMap lowHalo,
            IceHaloMap highHalo, int depthIndex, SkyTables lowDepthTables, SkyTables highDepthTables, int viewIndex,
            SkyView lowDepthLowSun, SkyView lowDepthHighSun, SkyView highDepthLowSun, SkyView highDepthHighSun) {
        IceHaloMap halo(int slot) {
            return slot == 0 ? lowHalo : highHalo;
        }

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

        boolean holdsHalo(IceHaloMap halo) {
            return halo == lowHalo || halo == highHalo;
        }

        @Nullable
        SkyTables tablesAt(int index) {
            if (index == depthIndex) {
                return lowDepthTables;
            }
            if (index == depthIndex + 1) {
                return highDepthTables;
            }
            return null;
        }

        SkyView view(int slot) {
            switch (slot) {
                case 0: {
                    return lowDepthLowSun;
                }
                case 1: {
                    return lowDepthHighSun;
                }
                case 2: {
                    return highDepthLowSun;
                }
                default: {
                    return highDepthHighSun;
                }
            }
        }

        @Nullable
        SkyView viewAt(int depthGridIndex, int viewGridIndex) {
            int depthEnd = depthGridIndex - depthIndex;
            int sunEnd = viewGridIndex - viewIndex;
            if (depthEnd < 0 || depthEnd > 1 || sunEnd < 0 || sunEnd > 1) {
                return null;
            }
            return view(depthEnd * 2 + sunEnd);
        }

        boolean holdsView(SkyView view) {
            return view == lowDepthLowSun || view == lowDepthHighSun || view == highDepthLowSun
                    || view == highDepthHighSun;
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
     * Whether the sky is drawn from tables, halo maps, and views built for the current config, ice depth, and sun
     * elevation, with no build pending.
     */
    public static boolean isSettled(ClientLevel level, float partialTick) {
        SkyState current = state;
        if (current == null || pendingBuild != null || !isAllowed(level)) {
            return false;
        }
        SkyFrame frame = SkyFrame.current(level, partialTick);
        return matchesConfig(current.settings()) && current.haloIndex() == frame.haloIndex()
                && current.depthIndex() == frame.depthIndex() && current.viewIndex() == frame.viewIndex();
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
            SkyFrame frame = SkyFrame.current(level, partialTick);
            updateTables(frame);
            if (failed || state == null || camera.getFluidInCamera() != FogType.NONE) {
                return;
            }
            draw(state, frame, poseStack, projection);
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
        SkyFrame frame = SkyFrame.current(level, partialTick);
        Vector3f look = camera.getLookVector();
        double horizontalFraction = Math.min(1.0, Math.sqrt(look.x() * look.x() + look.z() * look.z()));
        double azimuth = Math.atan2(look.z(), look.x() * frame.sunSide());
        SkyView.horizonDisplayColor(current.lowDepthLowSun(), current.lowDepthHighSun(), current.highDepthLowSun(),
                current.highDepthHighSun(), viewBlend(current, frame), depthBlend(current, frame), azimuth,
                horizontalFraction, frame.exposure(), frame.rainLevel(), frame.thunderLevel(), reusedFogColor);
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
        SkyFrame frame = SkyFrame.current(level, partialTick);
        SkyView.daylightColor(current.lowDepthLowSun(), current.lowDepthHighSun(), current.highDepthLowSun(),
                current.highDepthHighSun(), viewBlend(current, frame), depthBlend(current, frame), frame.exposure(),
                frame.rainLevel(), skyDarken, result);
        return true;
    }

    static int haloGridIndex(double sunElevationRadians) {
        return gridIndex(sunElevationRadians, HALO_MAP_STEP_DEGREES, LOWEST_HALO_SUN_DEGREES,
                HIGHEST_HALO_SUN_DEGREES);
    }

    static int viewGridIndex(double sunElevationRadians) {
        return gridIndex(sunElevationRadians, VIEW_STEP_DEGREES, LOWEST_VIEW_SUN_DEGREES, HIGHEST_VIEW_SUN_DEGREES);
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

    private static float viewBlend(SkyState current, SkyFrame frame) {
        return blendWeight(frame.sunElevationRadians(), current.lowDepthLowSun().sunElevationRadians(),
                current.lowDepthHighSun().sunElevationRadians());
    }

    private static float haloBlend(SkyState current, SkyFrame frame) {
        return blendWeight(frame.sunElevationRadians(), current.lowHalo().sunElevationRadians(),
                current.highHalo().sunElevationRadians());
    }

    private static double depthBlend(SkyState current, SkyFrame frame) {
        return IceDepthGrid.blendWeight(frame.iceDepth(), current.depthIndex());
    }

    /**
     * Whether the settings have the config's values apart from the ice optical depth, without allocating.
     */
    private static boolean matchesConfig(SkySettings settings) {
        return settings.nanobotOpticalDepth() == FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.get()
                && settings.nanobotMedianNanometers() == FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.get()
                && settings.nanobotSpread() == FHConfig.CLIENT.calculatedSkyNanobotSpread.get()
                && settings.texelDegrees() == FHConfig.CLIENT.calculatedSkyTexelDegrees.get();
    }

    private static void updateTables(SkyFrame frame) {
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
            upload(result.state());
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
        boolean settingsChanged = current == null || !matchesConfig(current.settings());
        int haloIndex = frame.haloIndex();
        int depthIndex = frame.depthIndex();
        int viewIndex = frame.viewIndex();
        if (!settingsChanged && current.haloIndex() == haloIndex && current.depthIndex() == depthIndex
                && current.viewIndex() == viewIndex) {
            return;
        }
        SkyState previous = settingsChanged ? null : current;
        SkySettings settings = settingsChanged ? configSettings() : current.settings();
        pendingBuild = CompletableFuture.supplyAsync(() -> build(previous, settings, haloIndex, depthIndex, viewIndex),
                Util.backgroundExecutor());
    }

    /**
     * Keeps the halo maps and views of the previous state that the next state no longer uses, so following builds
     * fill them instead of allocating. Runs after the build finished, when nothing reads the previous state any more.
     */
    private static void keepSpares(@Nullable SkyState previous, SkyState next) {
        if (previous == null) {
            return;
        }
        for (int slot = 0; slot < HALO_SLOTS; slot++) {
            IceHaloMap halo = previous.halo(slot);
            if (!next.holdsHalo(halo) && spareHaloCount < HALO_SLOTS) {
                spareHalos[spareHaloCount++] = halo;
            }
        }
        for (int slot = 0; slot < VIEW_SLOTS; slot++) {
            SkyView view = previous.view(slot);
            if (!next.holdsView(view) && spareViewCount < VIEW_SLOTS) {
                spareViews[spareViewCount++] = view;
            }
        }
    }

    /**
     * Builds the next state, reusing what the previous state already has and filling spare halo maps and views where
     * there are any. Without a previous state everything is built for the given settings.
     */
    private static BuildResult build(@Nullable SkyState previous, SkySettings settings, int haloIndex, int depthIndex,
            int viewIndex) {
        buildTablesMilliseconds = Double.NaN;
        buildHaloMapMilliseconds = Double.NaN;
        buildViewMilliseconds = Double.NaN;
        long start = System.nanoTime();
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(settings.actualTexelDegrees());
        IceHaloProfile haloProfile = previous != null ? previous.haloProfile() : new IceHaloProfile(data, blurSigma);
        double haloProfileMilliseconds = previous != null ? Double.NaN : (System.nanoTime() - start) / 1.0e6;
        if (haloWorkspace == null) {
            haloWorkspace = new IceHaloMap.Workspace(data);
        }

        IceHaloMap lowHalo = haloFor(previous, haloIndex, blurSigma);
        IceHaloMap highHalo = haloFor(previous, haloIndex + 1, blurSigma);
        SkyTables lowDepthTables = tablesFor(previous, depthIndex, settings);
        SkyTables highDepthTables = tablesFor(previous, depthIndex + 1, settings);
        SkyView lowDepthLowSun = viewFor(previous, depthIndex, viewIndex, lowDepthTables, haloProfile, lowHalo,
                highHalo);
        SkyView lowDepthHighSun = viewFor(previous, depthIndex, viewIndex + 1, lowDepthTables, haloProfile, lowHalo,
                highHalo);
        SkyView highDepthLowSun = viewFor(previous, depthIndex + 1, viewIndex, highDepthTables, haloProfile, lowHalo,
                highHalo);
        SkyView highDepthHighSun = viewFor(previous, depthIndex + 1, viewIndex + 1, highDepthTables, haloProfile,
                lowHalo, highHalo);
        SkyState next = new SkyState(settings, haloProfile, haloIndex, lowHalo, highHalo, depthIndex, lowDepthTables,
                highDepthTables, viewIndex, lowDepthLowSun, lowDepthHighSun, highDepthLowSun, highDepthHighSun);
        return new BuildResult(next, buildTablesMilliseconds, haloProfileMilliseconds, buildHaloMapMilliseconds,
                buildViewMilliseconds);
    }

    private static IceHaloMap haloFor(@Nullable SkyState previous, int index, double blurSigma) {
        IceHaloMap halo = previous != null ? previous.haloAt(index) : null;
        if (halo != null) {
            return halo;
        }
        long start = System.nanoTime();
        if (spareHaloCount > 0) {
            halo = spareHalos[--spareHaloCount];
            spareHalos[spareHaloCount] = null;
        } else {
            halo = new IceHaloMap();
        }
        halo.build(haloWorkspace, gridSunElevation(index, HALO_MAP_STEP_DEGREES), blurSigma);
        buildHaloMapMilliseconds = (System.nanoTime() - start) / 1.0e6;
        return halo;
    }

    private static SkyTables tablesFor(@Nullable SkyState previous, int depthGridIndex, SkySettings settings) {
        SkyTables tables = previous != null ? previous.tablesAt(depthGridIndex) : null;
        if (tables != null) {
            return tables;
        }
        long start = System.nanoTime();
        tables = new SkyTables(settings.withIceOpticalDepth(IceDepthGrid.depth(depthGridIndex)));
        buildTablesMilliseconds = (System.nanoTime() - start) / 1.0e6;
        return tables;
    }

    /**
     * Returns the previous state's view for the grid indices, or fills a spare or new view for them from the tables,
     * with the light that ice scatters onto the ground from the nearer halo map.
     */
    private static SkyView viewFor(@Nullable SkyState previous, int depthGridIndex, int viewGridIndex,
            SkyTables tables, IceHaloProfile haloProfile, IceHaloMap lowHalo, IceHaloMap highHalo) {
        SkyView view = previous != null ? previous.viewAt(depthGridIndex, viewGridIndex) : null;
        if (view != null) {
            return view;
        }
        long start = System.nanoTime();
        if (spareViewCount > 0) {
            view = spareViews[--spareViewCount];
            spareViews[spareViewCount] = null;
        } else {
            view = new SkyView();
        }
        double sunElevation = gridSunElevation(viewGridIndex, VIEW_STEP_DEGREES);
        double lowDistance = Math.abs(sunElevation - lowHalo.sunElevationRadians());
        double highDistance = Math.abs(sunElevation - highHalo.sunElevationRadians());
        tables.computeView(sunElevation, haloProfile, lowDistance <= highDistance ? lowHalo : highHalo, view);
        buildViewMilliseconds = (System.nanoTime() - start) / 1.0e6;
        return view;
    }

    public static SkySettings configSettings() {
        return new SkySettings(FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.get(),
                FHConfig.CLIENT.calculatedSkyNanobotSpread.get(),
                FHConfig.CLIENT.calculatedSkyIceOpticalDepth.get(),
                FHConfig.CLIENT.calculatedSkyTexelDegrees.get());
    }

    private static void upload(SkyState next) {
        if (uploadedHaloProfile != next.haloProfile()) {
            haloProfileTexture = uploadTable(haloProfileTexture, next.haloProfile().values(),
                    IceHaloProfile.ANGLE_BINS, 2, GL12.GL_CLAMP_TO_EDGE);
            uploadedHaloProfile = next.haloProfile();
        }
        if (uploadedLowDepthTables != next.lowDepthTables() || uploadedHighDepthTables != next.highDepthTables()) {
            fillRowTable(next.lowDepthTables(), next.highDepthTables());
            rowTableTexture = uploadTable(rowTableTexture, rowTableValues, ROW_TABLE_COLUMNS, SkyTables.VIEW_HEIGHT,
                    GL12.GL_CLAMP_TO_EDGE);
            uploadedLowDepthTables = next.lowDepthTables();
            uploadedHighDepthTables = next.highDepthTables();
        }

        for (int slot = 0; slot < HALO_SLOTS; slot++) {
            wantedSlots[slot] = next.halo(slot);
        }
        reassignSlots(uploadedHalos, HALO_SLOTS, haloTextures, null);
        for (int slot = 0; slot < HALO_SLOTS; slot++) {
            if (slotNeedsUpload[slot]) {
                haloTextures[slot] = uploadTable(haloTextures[slot], next.halo(slot).values(), IceHaloMap.AZIMUTH_BINS,
                        IceHaloMap.ELEVATION_BINS, GL14.GL_MIRRORED_REPEAT);
            }
        }

        for (int slot = 0; slot < VIEW_SLOTS; slot++) {
            wantedSlots[slot] = next.view(slot);
        }
        reassignSlots(uploadedViews, VIEW_SLOTS, viewSkyTextures, viewIceTextures);
        for (int slot = 0; slot < VIEW_SLOTS; slot++) {
            if (slotNeedsUpload[slot]) {
                SkyView view = next.view(slot);
                viewSkyTextures[slot] = uploadTable(viewSkyTextures[slot], view.sky(), SkyTables.VIEW_WIDTH,
                        SkyTables.VIEW_HEIGHT, GL11.GL_REPEAT);
                viewIceTextures[slot] = uploadTable(viewIceTextures[slot], view.ice(), SkyTables.VIEW_WIDTH,
                        SkyTables.VIEW_HEIGHT, GL11.GL_REPEAT);
            }
        }
    }

    /**
     * Moves the textures of the slots so that content in wantedSlots that a texture already holds keeps that texture,
     * and sets slotNeedsUpload for the slots whose content no texture holds. Those slots get the textures of content
     * that is no longer wanted. Afterwards the uploaded array holds the wanted content.
     */
    private static void reassignSlots(Object[] uploaded, int slotCount, int[] textures,
            @Nullable int[] secondTextures) {
        for (int slot = 0; slot < slotCount; slot++) {
            slotSources[slot] = -1;
            slotTaken[slot] = false;
        }
        for (int slot = 0; slot < slotCount; slot++) {
            for (int source = 0; source < slotCount; source++) {
                if (uploaded[source] != null && uploaded[source] == wantedSlots[slot]) {
                    slotSources[slot] = source;
                    slotTaken[source] = true;
                    break;
                }
            }
        }
        for (int slot = 0; slot < slotCount; slot++) {
            slotNeedsUpload[slot] = slotSources[slot] < 0;
            if (!slotNeedsUpload[slot]) {
                continue;
            }
            for (int source = 0; source < slotCount; source++) {
                if (!slotTaken[source]) {
                    slotSources[slot] = source;
                    slotTaken[source] = true;
                    break;
                }
            }
        }
        reorder(textures, slotCount);
        if (secondTextures != null) {
            reorder(secondTextures, slotCount);
        }
        for (int slot = 0; slot < slotCount; slot++) {
            uploaded[slot] = wantedSlots[slot];
            wantedSlots[slot] = null;
        }
    }

    private static void reorder(int[] textures, int slotCount) {
        for (int slot = 0; slot < slotCount; slot++) {
            reorderedTextures[slot] = textures[slotSources[slot]];
        }
        System.arraycopy(reorderedTextures, 0, textures, 0, slotCount);
    }

    private static void fillRowTable(SkyTables lowDepthTables, SkyTables highDepthTables) {
        for (int row = 0; row < SkyTables.VIEW_HEIGHT; row++) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                int source = row * CHANNELS + channel;
                int texel = row * ROW_TABLE_COLUMNS * CHANNELS + channel;
                rowTableValues[texel] = lowDepthTables.sunRadiance()[source];
                rowTableValues[texel + CHANNELS] = highDepthTables.sunRadiance()[source];
                rowTableValues[texel + 2 * CHANNELS] = lowDepthTables.diffractionWeights()[source];
                rowTableValues[texel + 3 * CHANNELS] = highDepthTables.diffractionWeights()[source];
            }
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

    private static void draw(SkyState current, SkyFrame frame, PoseStack poseStack, Matrix4f projection) {
        ShaderProgram shaderProgram = program();
        inverseViewProjection.set(projection).mul(poseStack.last().pose()).invert();
        float sunX = frame.sunX();
        float sunY = frame.sunY();
        float sunSide = frame.sunSide();
        float[] fogColor = RenderSystem.getShaderFogColor();
        float exposure = (float) frame.exposure();
        float rainLevel = frame.rainLevel();
        float thunderLevel = frame.thunderLevel();
        float texelFaceSize = current.settings().texelFaceSize();
        float haloBlend = haloBlend(current, frame);
        float viewBlend = viewBlend(current, frame);
        float depthBlend = (float) depthBlend(current, frame);
        for (int slot = 0; slot < VIEW_SLOTS; slot++) {
            drawnTextures[SKY_VIEW_UNIT + slot] = viewSkyTextures[slot];
            drawnTextures[ICE_VIEW_UNIT + slot] = viewIceTextures[slot];
        }
        for (int slot = 0; slot < HALO_SLOTS; slot++) {
            drawnTextures[HALO_MAP_UNIT + slot] = haloTextures[slot];
        }
        drawnTextures[HALO_PROFILE_UNIT] = haloProfileTexture;
        drawnTextures[ROW_TABLE_UNIT] = rowTableTexture;

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
                uniforms.glUniform1I("lowDepthLowSunSky", SKY_VIEW_UNIT);
                uniforms.glUniform1I("lowDepthHighSunSky", SKY_VIEW_UNIT + 1);
                uniforms.glUniform1I("highDepthLowSunSky", SKY_VIEW_UNIT + 2);
                uniforms.glUniform1I("highDepthHighSunSky", SKY_VIEW_UNIT + 3);
                uniforms.glUniform1I("lowDepthLowSunIce", ICE_VIEW_UNIT);
                uniforms.glUniform1I("lowDepthHighSunIce", ICE_VIEW_UNIT + 1);
                uniforms.glUniform1I("highDepthLowSunIce", ICE_VIEW_UNIT + 2);
                uniforms.glUniform1I("highDepthHighSunIce", ICE_VIEW_UNIT + 3);
                uniforms.glUniform1I("lowHaloMap", HALO_MAP_UNIT);
                uniforms.glUniform1I("highHaloMap", HALO_MAP_UNIT + 1);
                uniforms.glUniform1I("haloProfile", HALO_PROFILE_UNIT);
                uniforms.glUniform1I("rowTables", ROW_TABLE_UNIT);
                uniforms.glUniform1F("viewBlend", viewBlend);
                uniforms.glUniform1F("depthBlend", depthBlend);
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
