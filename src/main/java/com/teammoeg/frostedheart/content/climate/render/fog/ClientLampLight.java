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

import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.content.climate.render.sky.CalculatedSkyRenderer;
import com.teammoeg.frostedheart.content.climate.render.sky.LightPillarSources;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import com.teammoeg.frostedheart.mixin.client.LightEngineAccessor_LightPillars;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the LampLightField of the client level while the weather fog or the glints of diamond dust lit by lamps can be
 * drawn. The field is made on first use and kept for the session, about 6.5 MiB. Each frame moves it to the eye, uses
 * the cascades that the view distance needs, and reads queued sections from the client's light engine until
 * SkySetting.LAMP_FIELD_BUDGET_MICROS is spent. A new level or view distance fills it again.
 * LightPillarWorldChanges passes the sections whose block or sky light the client computed or received and the chunks
 * that unload. An error turns the field off, and it comes back once it stops being wanted and is wanted again.
 * Client thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientLampLight {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TICKS_PER_SECOND = 20;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final long NOT_FILLED = Long.MIN_VALUE;
    private static final double FADE_SECONDS = 0.5;
    private static final double NANOSECONDS_PER_SECOND = 1.0e9;
    private static final double NANOSECONDS_PER_MICROSECOND = 1000.0;
    private static final double NANOSECONDS_PER_MILLISECOND = 1.0e6;

    private static final LevelLights source = new LevelLights();

    @Nullable
    private static LampLightField field;
    @Nullable
    private static ClientLevel trackedLevel;
    private static int trackedViewBlocks;
    private static long seenNearWholeFills;
    /**
     * When the window fills last drained after a whole fill, NOT_FILLED while one runs.
     */
    private static long filledNanos = NOT_FILLED;
    private static boolean failed;
    private static int ticksThisSecond;
    private static long currentSections;
    private static int currentMarks;
    private static long currentNanos;
    private static int currentFrames;
    private static long currentLargestFrameNanos;
    private static long currentBytes;
    private static long lastSections;
    private static int lastMarks;
    private static long lastNanos;
    private static int lastFrames;
    private static long lastLargestFrameNanos;
    private static long lastBytes;

    private ClientLampLight() {
    }

    /**
     * Rolls the per second statistics and stops tracking when the field is not wanted.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        rollStatistics();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !isWanted(level)) {
            failed = false;
            stopTracking();
        }
    }

    /**
     * Moves the field to the eye and reads queued sections until the frame budget is spent.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (failed || level == null || !isWanted(level)) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        long allocatedBefore = LightPillarSources.allocatedBytes();
        long start = System.nanoTime();
        try {
            LampLightField current = field;
            if (current == null) {
                current = new LampLightField();
                field = current;
            }
            int viewBlocks = minecraft.options.getEffectiveRenderDistance() * BLOCKS_PER_CHUNK;
            if (level != trackedLevel || viewBlocks != trackedViewBlocks) {
                current.reset(level.getMinSection(), level.getSectionsCount());
                current.setActiveCount(LampLightField.cascadesFor(viewBlocks));
                trackedLevel = level;
                trackedViewBlocks = viewBlocks;
            }
            Vec3 eye = camera.getPosition();
            current.follow(eye.x, eye.y, eye.z);
            if (current.nearWholeFills() != seenNearWholeFills) {
                seenNearWholeFills = current.nearWholeFills();
                filledNanos = NOT_FILLED;
            }
            long deadline = start + (long) (SkySetting.LAMP_FIELD_BUDGET_MICROS.get() * NANOSECONDS_PER_MICROSECOND);
            source.begin(level);
            try {
                currentSections += current.process(source, Integer.MAX_VALUE, deadline);
            } finally {
                source.end();
            }
            if (filledNanos == NOT_FILLED && current.isBulkDrained()) {
                filledNanos = System.nanoTime();
            }
        } catch (RuntimeException exception) {
            failed = true;
            stopTracking();
            LOGGER.error("The lamp light of the weather stopped after an error. It comes back once the weather fog and "
                    + "the lamp glints of diamond dust are turned off and on again.", exception);
        }
        long nanos = System.nanoTime() - start;
        currentNanos += nanos;
        currentLargestFrameNanos = Math.max(currentLargestFrameNanos, nanos);
        currentFrames++;
        currentBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
    }

    private static boolean isWanted(ClientLevel level) {
        boolean dustLampGlints = Math.round(SkySetting.DUST_GLITTER_COUNT.get()) > 0
                && SkySetting.DUST_LAMP_GLITTER.get() > 0.0;
        return CalculatedSkyRenderer.isAllowed(level) && (SkySetting.FOG_ENABLED.isOn() || dustLampGlints);
    }

    private static void stopTracking() {
        if (trackedLevel != null && field != null) {
            field.forget();
        }
        trackedLevel = null;
        trackedViewBlocks = 0;
        filledNanos = NOT_FILLED;
    }

    /**
     * The field while it follows a level, null otherwise.
     */
    @Nullable
    public static LampLightField field(@Nullable ClientLevel level) {
        return level != null && level == trackedLevel && !failed ? field : null;
    }

    /**
     * How far the field has faded in since its last whole fill, from 0 while the windows fill to 1 FADE_SECONDS after.
     */
    public static float fade() {
        if (filledNanos == NOT_FILLED) {
            return 0.0F;
        }
        return (float) Math.min(1.0, (System.nanoTime() - filledNanos) / (FADE_SECONDS * NANOSECONDS_PER_SECOND));
    }

    /**
     * Queues a section whose block or sky light the client computed in the tracked level.
     */
    public static void onLightUpdated(ClientLevel level, SectionPos section) {
        LampLightField current = field;
        if (current != null && level == trackedLevel) {
            current.markSections(section.x(), section.z(), section.y(), section.y());
            currentMarks++;
        }
    }

    /**
     * Queues the sections of a column whose block or sky light came from the server in the tracked level, from the
     * lowest to the highest whose bit is set in either mask, counted from the lowest light section.
     */
    public static void onLightReceived(ClientLevel level, int chunkX, int chunkZ, BitSet dataMask, BitSet emptyMask,
            int lowestLightSection, int lightSectionCount) {
        LampLightField current = field;
        if (current == null || level != trackedLevel) {
            return;
        }
        int first = -1;
        int last = -1;
        for (int index = 0; index < lightSectionCount; index++) {
            if (dataMask.get(index) || emptyMask.get(index)) {
                if (first < 0) {
                    first = index;
                }
                last = index;
            }
        }
        if (first >= 0) {
            current.markSections(chunkX, chunkZ, lowestLightSection + first, lowestLightSection + last);
            currentMarks++;
        }
    }

    /**
     * Queues every section of a column that unloaded in the tracked level, which then holds dark open air.
     */
    public static void onChunkUnloaded(ClientLevel level, int chunkX, int chunkZ) {
        LampLightField current = field;
        if (current != null && level == trackedLevel) {
            current.markColumn(chunkX, chunkZ);
            currentMarks++;
        }
    }

    /**
     * Uploads the field's textures and compares them with the field, for the client command.
     */
    public static List<String> verifyTextures() {
        LampLightField current = field(Minecraft.getInstance().level);
        if (current == null) {
            return List.of("The lamp light field is not tracked, since neither the weather fog nor the lamp glints of "
                    + "diamond dust can be drawn");
        }
        return LampLightTextures.verify(current);
    }

    private static void rollStatistics() {
        ticksThisSecond++;
        if (ticksThisSecond < TICKS_PER_SECOND) {
            return;
        }
        lastSections = currentSections;
        lastMarks = currentMarks;
        lastNanos = currentNanos;
        lastFrames = currentFrames;
        lastLargestFrameNanos = currentLargestFrameNanos;
        lastBytes = currentBytes;
        currentSections = 0L;
        currentMarks = 0;
        currentNanos = 0L;
        currentFrames = 0;
        currentLargestFrameNanos = 0L;
        currentBytes = 0L;
        ticksThisSecond = 0;
        LampLightTextures.rollStatistics();
    }

    /**
     * A line about the field and the work of the last second, for the client command.
     */
    public static String describe() {
        LampLightField current = field(Minecraft.getInstance().level);
        if (current == null) {
            return failed ? "The lamp light field stopped after an error, see the log"
                    : "The lamp light field is not tracked, since neither the weather fog nor the lamp glints of "
                    + "diamond dust can be drawn";
        }
        String allocated = LightPillarSources.countsAllocations()
                ? String.format(Locale.ROOT, "%d bytes allocated", lastBytes)
                : "allocations not counted by this JVM";
        double meanMilliseconds = lastFrames > 0 ? lastNanos / NANOSECONDS_PER_MILLISECOND / lastFrames : 0.0;
        return String.format(Locale.ROOT, "Lamp light field: %d of %d cascades for a view of %d blocks, %d columns "
                        + "waiting, faded in %.2f; in the last second %d sections read, %d light changes, %.3f ms per "
                        + "frame and %.3f ms at most, %s", current.activeCount(), LampLightField.CASCADE_COUNT,
                trackedViewBlocks, current.pendingColumns(), fade(), lastSections, lastMarks, meanMilliseconds,
                lastLargestFrameNanos / NANOSECONDS_PER_MILLISECOND, allocated);
    }

    /**
     * Reads the light the client sees through the light engine's storages, which are taken again each frame, since
     * the light engine may replace them.
     */
    private static final class LevelLights implements LampLightField.LightSource {
        @Nullable
        private ClientLevel level;
        @Nullable
        private LayerLightSectionStorage<?> blockStorage;
        @Nullable
        private LayerLightSectionStorage<?> skyStorage;

        private void begin(ClientLevel currentLevel) {
            level = currentLevel;
            blockStorage = storage(currentLevel, LightLayer.BLOCK);
            skyStorage = currentLevel.dimensionType().hasSkyLight() ? storage(currentLevel, LightLayer.SKY) : null;
        }

        @Nullable
        private static LayerLightSectionStorage<?> storage(ClientLevel currentLevel, LightLayer layer) {
            LayerLightEventListener listener = currentLevel.getLightEngine().getLayerListener(layer);
            return listener instanceof LightEngineAccessor_LightPillars accessor
                    ? accessor.frostedheart$getStorage() : null;
        }

        private void end() {
            level = null;
            blockStorage = null;
            skyStorage = null;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            ClientLevel current = level;
            return current != null && current.hasChunk(chunkX, chunkZ);
        }

        @Override
        public boolean hasSkyLight() {
            return skyStorage != null;
        }

        @Nullable
        @Override
        public DataLayer blockLight(long sectionKey) {
            LayerLightSectionStorage<?> storage = blockStorage;
            return storage != null ? storage.getDataLayerData(sectionKey) : null;
        }

        @Nullable
        @Override
        public DataLayer skyLight(long sectionKey) {
            LayerLightSectionStorage<?> storage = skyStorage;
            return storage != null ? storage.getDataLayerData(sectionKey) : null;
        }
    }
}
