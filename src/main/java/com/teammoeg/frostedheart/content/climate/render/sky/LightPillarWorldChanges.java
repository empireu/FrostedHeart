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

import com.teammoeg.frostedheart.FHMain;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Collects the changes in the client world that light pillars depend on, as reported by hooks, so nothing has to poll
 * the world. Block changes come from ClientLevel.setBlocksDirty, sky light computed on the client from
 * ClientChunkCache.onLightUpdate, sky light received from the server from ClientPacketListener.readSectionList, and
 * chunk columns from the Forge chunk events. The hooks only mark sections and columns, and the client tick takes the
 * marks. Nothing is marked while the calculated sky is not allowed, or before the first client tick in a new level.
 * Client thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LightPillarWorldChanges {
    private static final int INITIAL_CAPACITY = 1024;
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Sections where a block that shows pillars appeared, went away, or changed state.
     */
    private static final LongOpenHashSet emitterSections = new LongOpenHashSet(INITIAL_CAPACITY);
    /**
     * Sections whose sky light may have changed.
     */
    private static final LongOpenHashSet skyLightSections = new LongOpenHashSet(INITIAL_CAPACITY);
    /**
     * Columns whose blocks arrived from the server.
     */
    private static final LongOpenHashSet loadedColumns = new LongOpenHashSet(INITIAL_CAPACITY);
    /**
     * Columns whose sky light arrived from the server.
     */
    private static final LongOpenHashSet litColumns = new LongOpenHashSet(INITIAL_CAPACITY);
    private static final LongOpenHashSet unloadedColumns = new LongOpenHashSet(INITIAL_CAPACITY);
    private static final Counts currentSecond = new Counts();
    private static final Counts lastSecond = new Counts();
    private static final int LISTED_BLOCKS = 3;
    private static final long NO_POSITION = Long.MIN_VALUE;

    /**
     * Block changes per block of the current and of the last second, counted for the new state or for the old one
     * when the new one is air. Light changes are counted for the block that shows pillars.
     */
    private static Reference2IntOpenHashMap<Block> currentBlocks = new Reference2IntOpenHashMap<>();
    private static Reference2IntOpenHashMap<Block> lastBlocks = new Reference2IntOpenHashMap<>();
    private static Reference2IntOpenHashMap<Block> currentEmitters = new Reference2IntOpenHashMap<>();
    private static Reference2IntOpenHashMap<Block> lastEmitters = new Reference2IntOpenHashMap<>();
    private static long currentEmitterPosition = NO_POSITION;
    private static long lastEmitterPosition = NO_POSITION;

    @Nullable
    private static ClientLevel trackedLevel;
    private static boolean listening;
    private static int ticksThisSecond;

    private LightPillarWorldChanges() {
    }

    /**
     * Notes a block state change in a client level, called whenever the old and new state differ.
     */
    public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!listening || level != trackedLevel) {
            return;
        }
        currentSecond.blocksChanged++;
        currentBlocks.addTo(newState.isAir() ? oldState.getBlock() : newState.getBlock(), 1);
        boolean oldShowsPillar = LightPillarEmitters.showsPillar(oldState);
        if (oldShowsPillar || LightPillarEmitters.showsPillar(newState)) {
            emitterSections.add(SectionPos.blockToSection(pos.asLong()));
            currentSecond.emittersChanged++;
            currentEmitters.addTo(oldShowsPillar ? oldState.getBlock() : newState.getBlock(), 1);
            currentEmitterPosition = pos.asLong();
        }
    }

    /**
     * Notes light the client computed in a section. The new values are readable when this is called.
     */
    public static void onLightUpdated(ClientLevel level, LightLayer layer, SectionPos section) {
        if (layer != LightLayer.SKY || !listening || level != trackedLevel) {
            return;
        }
        skyLightSections.add(section.asLong());
        currentSecond.skyLightSectionsComputed++;
    }

    /**
     * Notes light received from the server for a column, in the light sections whose bit is set in either mask.
     * The bits count from the lowest light section. The new values become readable later in the same frame.
     */
    public static void onLightReceived(ClientLevel level, int chunkX, int chunkZ, LightLayer layer, BitSet dataMask,
            BitSet emptyMask, int lowestLightSection, int lightSectionCount) {
        if (layer != LightLayer.SKY || !listening || level != trackedLevel) {
            return;
        }
        litColumns.add(ChunkPos.asLong(chunkX, chunkZ));
        currentSecond.columnsLit++;
        for (int index = 0; index < lightSectionCount; index++) {
            if (dataMask.get(index) || emptyMask.get(index)) {
                skyLightSections.add(SectionPos.asLong(chunkX, lowestLightSection + index, chunkZ));
                currentSecond.skyLightSectionsReceived++;
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel level) || !listening || level != trackedLevel) {
            return;
        }
        loadedColumns.add(event.getChunk().getPos().toLong());
        currentSecond.columnsLoaded++;
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel level) || !listening || level != trackedLevel) {
            return;
        }
        long column = event.getChunk().getPos().toLong();
        loadedColumns.remove(column);
        litColumns.remove(column);
        unloadedColumns.add(column);
        currentSecond.columnsUnloaded++;
    }

    /**
     * Takes the marks of the last tick, given the client level and whether the calculated sky is allowed in it.
     * Marks are dropped when the level changes or the sky is not allowed.
     */
    static void tick(@Nullable ClientLevel level, boolean allowed) {
        if (level != trackedLevel || !allowed) {
            clearMarks();
        }
        trackedLevel = level;
        listening = allowed && level != null;
        currentSecond.sectionsToRewalk += emitterSections.size();
        currentSecond.sectionsToRelight += skyLightSections.size();
        clearMarks();
        ticksThisSecond++;
        if (ticksThisSecond >= TICKS_PER_SECOND) {
            lastSecond.copy(currentSecond);
            currentSecond.clear();
            Reference2IntOpenHashMap<Block> finishedBlocks = currentBlocks;
            currentBlocks = lastBlocks;
            lastBlocks = finishedBlocks;
            currentBlocks.clear();
            Reference2IntOpenHashMap<Block> finishedEmitters = currentEmitters;
            currentEmitters = lastEmitters;
            lastEmitters = finishedEmitters;
            currentEmitters.clear();
            lastEmitterPosition = currentEmitterPosition;
            currentEmitterPosition = NO_POSITION;
            ticksThisSecond = 0;
        }
    }

    private static void clearMarks() {
        emitterSections.clear();
        skyLightSections.clear();
        loadedColumns.clear();
        litColumns.clear();
        unloadedColumns.clear();
    }

    /**
     * A line about the world changes of the last full second, for the client command.
     */
    public static String describe() {
        if (!listening) {
            return "World changes are not tracked while the calculated sky is not drawn";
        }
        return String.format(Locale.ROOT,
                "World changes in the last second: %d blocks with %d at lights, sky light in %d sections computed "
                        + "and %d received, %d chunks loaded, %d lit, %d unloaded; %d sections to rewalk, "
                        + "%d to relight",
                lastSecond.blocksChanged, lastSecond.emittersChanged, lastSecond.skyLightSectionsComputed,
                lastSecond.skyLightSectionsReceived, lastSecond.columnsLoaded, lastSecond.columnsLit,
                lastSecond.columnsUnloaded, lastSecond.sectionsToRewalk, lastSecond.sectionsToRelight);
    }

    /**
     * A line about which blocks changed most in the last full second, for the client command.
     */
    public static String describeBlocks() {
        String lastEmitter = lastEmitterPosition == NO_POSITION ? "none"
                : BlockPos.of(lastEmitterPosition).toShortString();
        return "Changed most: " + mostChanged(lastBlocks) + "; at lights: " + mostChanged(lastEmitters)
                + "; last light change at " + lastEmitter;
    }

    private static String mostChanged(Reference2IntOpenHashMap<Block> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        List<Reference2IntMap.Entry<Block>> entries = new ArrayList<>(counts.reference2IntEntrySet());
        entries.sort((first, second) -> Integer.compare(second.getIntValue(), first.getIntValue()));
        StringBuilder text = new StringBuilder();
        for (int rank = 0; rank < Math.min(LISTED_BLOCKS, entries.size()); rank++) {
            if (rank > 0) {
                text.append(", ");
            }
            Reference2IntMap.Entry<Block> entry = entries.get(rank);
            text.append(entry.getIntValue()).append(' ').append(ForgeRegistries.BLOCKS.getKey(entry.getKey()));
        }
        return text.toString();
    }

    /**
     * Counts of hook calls, and of distinct sections taken by the client tick.
     */
    private static final class Counts {
        private int blocksChanged;
        private int emittersChanged;
        private int skyLightSectionsComputed;
        private int skyLightSectionsReceived;
        private int columnsLoaded;
        private int columnsLit;
        private int columnsUnloaded;
        private int sectionsToRewalk;
        private int sectionsToRelight;

        private void copy(Counts other) {
            blocksChanged = other.blocksChanged;
            emittersChanged = other.emittersChanged;
            skyLightSectionsComputed = other.skyLightSectionsComputed;
            skyLightSectionsReceived = other.skyLightSectionsReceived;
            columnsLoaded = other.columnsLoaded;
            columnsLit = other.columnsLit;
            columnsUnloaded = other.columnsUnloaded;
            sectionsToRewalk = other.sectionsToRewalk;
            sectionsToRelight = other.sectionsToRelight;
        }

        private void clear() {
            blocksChanged = 0;
            emittersChanged = 0;
            skyLightSectionsComputed = 0;
            skyLightSectionsReceived = 0;
            columnsLoaded = 0;
            columnsLit = 0;
            columnsUnloaded = 0;
            sectionsToRewalk = 0;
            sectionsToRelight = 0;
        }
    }
}
