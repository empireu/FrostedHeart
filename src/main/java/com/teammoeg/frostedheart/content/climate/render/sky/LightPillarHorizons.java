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

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;

/**
 * The shadow horizons of the lights in the slots of a LightPillarSelection, built from the blocks around each light.
 * For each slot, BINS directions around the light each hold SAMPLES horizons at distances from the light out to the
 * direction's reach, closer together near the light. The horizon at a distance is the steepest angle above the
 * horizontal, seen from the top of the light, of the blocks that stop light between the light and that distance, or
 * OPEN where none stands. Light that leaves the light toward the eye less steeply than the horizon at the eye's
 * distance does not get past the blocks, so light_pillars.glsl leaves out the crystals it would light.
 * A direction is built by stepping through the block columns along it from the light, leaving out the light's own
 * column. Directions are built again only where hooks reported a change: a block that stops light up to another
 * height, or a chunk that loaded or unloaded. A slot whose key is new is ready to fade in once every direction is
 * built. Angles are in radians from the x axis toward the z axis, and distances are horizontal, in blocks.
 * Not thread safe.
 */
public final class LightPillarHorizons {
    public static final int BINS = 64;
    public static final int SAMPLES = 16;
    /**
     * The horizon where no block stops light.
     */
    public static final float OPEN = (float) (-0.5 * Math.PI);
    public static final double BIN_ANGLE = 2.0 * Math.PI / BINS;
    private static final int SLOTS = LightPillarSelection.SLOT_COUNT;
    private static final long ALL_BINS = -1L;
    private static final int CHUNK_SIZE = 16;
    /**
     * Columns nearer to the light count as this far, so a column touching a light on a column edge stands at a
     * distance.
     */
    private static final double NEAREST_ENTRY = 0.25;
    /**
     * Bins whose direction passes this close to a corner of a changed column also count as passing through it.
     */
    private static final double BIN_MARGIN = 1.0e-9;
    private static final int INITIAL_CHANGES = 256;
    /**
     * The distance of each sample as a share of the reach.
     */
    private static final double[] SAMPLE_SHARES = sampleShares();

    private final LightPillarOccluders occluders = new LightPillarOccluders();
    private final float[] horizons = new float[SLOTS * BINS * SAMPLES];
    private final float[] binReaches = new float[SLOTS * BINS];
    private final long[] dirtyBins = new long[SLOTS];
    private final boolean[] tracked = new boolean[SLOTS];
    private final boolean[] built = new boolean[SLOTS];
    private final long[] slotKeys = new long[SLOTS];
    private final double[] originX = new double[SLOTS];
    private final double[] originY = new double[SLOTS];
    private final double[] originZ = new double[SLOTS];
    private final double[] slotReaches = new double[SLOTS];
    private int trackedCount;
    private int firstSlotToBuild;

    private final LongOpenHashSet changedColumnSet = new LongOpenHashSet(INITIAL_CHANGES);
    private final LongArrayList changedColumns = new LongArrayList(INITIAL_CHANGES);
    private final LongOpenHashSet changedChunkSet = new LongOpenHashSet(INITIAL_CHANGES);
    private final LongArrayList changedChunks = new LongArrayList(INITIAL_CHANGES);

    private final Counts currentSecond = new Counts();
    private final Counts lastSecond = new Counts();

    /**
     * Reads how high the blocks of a column stop light.
     */
    public interface Columns {
        /**
         * The highest point at which a block of the column at the block coordinates stops light, when it is above the
         * floor height, or LightPillarOccluders.PASSES when no such block stands above the floor or the column cannot
         * be read.
         */
        double highestStop(int blockX, int blockZ, double floorY);
    }

    private static double[] sampleShares() {
        double[] shares = new double[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            double fraction = (double) sample / (SAMPLES - 1);
            shares[sample] = fraction * fraction;
        }
        return shares;
    }

    /**
     * The horizontal distance from the light out to which the horizons reach, which covers every eye position from
     * which LightPillarSelection.choose picks the light within the chunk radius.
     */
    public static double reachForChunkRadius(int chunkRadius) {
        return CHUNK_SIZE * (Math.sqrt((double) chunkRadius * chunkRadius + chunkRadius) + Math.sqrt(2.0));
    }

    /**
     * The horizontal distance from the light of a sample, for the reach of its direction.
     */
    public static double sampleDistance(int sample, double reach) {
        return reach * SAMPLE_SHARES[sample];
    }

    /**
     * The bins whose direction from the point, at the bin's multiple of BIN_ANGLE, passes through the square of the
     * size with its lowest corner at the coordinates, or every bin when the point lies in the square or on its edge.
     */
    public static long binsAcross(double pointX, double pointZ, double minX, double minZ, double size) {
        double maxX = minX + size;
        double maxZ = minZ + size;
        if (pointX >= minX && pointX <= maxX && pointZ >= minZ && pointZ <= maxZ) {
            return ALL_BINS;
        }
        double center = Math.atan2(minZ + 0.5 * size - pointZ, minX + 0.5 * size - pointX);
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double cornerX = (corner & 1) == 0 ? minX : maxX;
            double cornerZ = (corner & 2) == 0 ? minZ : maxZ;
            double offset = wrapAngle(Math.atan2(cornerZ - pointZ, cornerX - pointX) - center);
            lowest = Math.min(lowest, offset);
            highest = Math.max(highest, offset);
        }
        int firstBin = (int) Math.ceil((center + lowest - BIN_MARGIN) / BIN_ANGLE);
        int lastBin = (int) Math.floor((center + highest + BIN_MARGIN) / BIN_ANGLE);
        long bins = 0L;
        for (int bin = firstBin; bin <= lastBin; bin++) {
            bins |= 1L << Math.floorMod(bin, BINS);
        }
        return bins;
    }

    /**
     * The angle moved into the range from -pi up to pi.
     */
    private static double wrapAngle(double angle) {
        return angle - 2.0 * Math.PI * Math.floor((angle + Math.PI) / (2.0 * Math.PI));
    }

    public LightPillarOccluders occluders() {
        return occluders;
    }

    /**
     * Notes a block change in the column at the block coordinates, unless no slot is tracked or both states stop
     * light up to the same height wherever they stand.
     */
    public void onBlockChanged(int blockX, int blockZ, BlockState oldState, BlockState newState) {
        if (trackedCount == 0) {
            return;
        }
        currentSecond.blockChanges++;
        if (occluders.stopsTheSame(oldState, newState)) {
            currentSecond.unchangedStops++;
            return;
        }
        long column = BlockPos.asLong(blockX, 0, blockZ);
        if (changedColumnSet.add(column)) {
            changedColumns.add(column);
        }
    }

    /**
     * Notes that the chunk column at the chunk coordinates loaded or unloaded, unless no slot is tracked.
     */
    public void onChunkChanged(int chunkX, int chunkZ) {
        if (trackedCount == 0) {
            return;
        }
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        if (changedChunkSet.add(chunk)) {
            changedChunks.add(chunk);
        }
    }

    /**
     * Follows the slots of the selection, marks the bins that the noted changes reach, builds marked bins until the
     * columns visited reach the budget, and tells the selection which slots are ready to fade in. A slot takes its
     * light's horizontal center and the top of its light. The reach applies to slots with a new key, and a larger
     * reach than before marks every bin of every slot.
     */
    public void update(LightPillarSelection selection, Columns columns, double reach, int columnBudget) {
        trackedCount = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            followSlot(selection, slot, reach);
        }
        for (int change = 0; change < changedColumns.size(); change++) {
            long column = changedColumns.getLong(change);
            markColumn(BlockPos.getX(column), BlockPos.getZ(column));
        }
        for (int change = 0; change < changedChunks.size(); change++) {
            long chunk = changedChunks.getLong(change);
            markSquare(SectionPos.sectionToBlockCoord(ChunkPos.getX(chunk)),
                    SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunk)), CHUNK_SIZE);
        }
        currentSecond.columnsChanged += changedColumns.size();
        currentSecond.chunksChanged += changedChunks.size();
        clearChanges();
        // Each update starts where the last ran out of budget, so a stream of changes cannot starve later slots:
        int visited = 0;
        int buildSlot = firstSlotToBuild;
        for (int step = 0; step < SLOTS && visited < columnBudget; step++) {
            buildSlot = (firstSlotToBuild + step) % SLOTS;
            while (dirtyBins[buildSlot] != 0L && visited < columnBudget) {
                int bin = Long.numberOfTrailingZeros(dirtyBins[buildSlot]);
                dirtyBins[buildSlot] &= ~(1L << bin);
                visited += buildBin(buildSlot, bin, columns);
                currentSecond.binsBuilt++;
            }
        }
        firstSlotToBuild = dirtyBins[buildSlot] != 0L ? buildSlot : (buildSlot + 1) % SLOTS;
        currentSecond.columnsVisited += visited;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (tracked[slot] && !built[slot] && dirtyBins[slot] == 0L) {
                built[slot] = true;
                selection.setReady(slot, true);
            }
        }
    }

    private void followSlot(LightPillarSelection selection, int slot, double reach) {
        if (!selection.isUsed(slot)) {
            tracked[slot] = false;
            dirtyBins[slot] = 0L;
            return;
        }
        trackedCount++;
        long key = selection.key(slot);
        double x = selection.x(slot);
        double y = selection.top(slot);
        double z = selection.z(slot);
        if (!tracked[slot] || slotKeys[slot] != key) {
            tracked[slot] = true;
            built[slot] = false;
            slotKeys[slot] = key;
            slotReaches[slot] = reach;
            setOrigin(slot, x, y, z);
            dirtyBins[slot] = ALL_BINS;
            selection.setReady(slot, false);
        } else if (x != originX[slot] || y != originY[slot] || z != originZ[slot]) {
            slotReaches[slot] = Math.max(slotReaches[slot], reach);
            setOrigin(slot, x, y, z);
            dirtyBins[slot] = ALL_BINS;
        } else if (reach > slotReaches[slot]) {
            slotReaches[slot] = reach;
            dirtyBins[slot] = ALL_BINS;
        }
    }

    private void setOrigin(int slot, double x, double y, double z) {
        originX[slot] = x;
        originY[slot] = y;
        originZ[slot] = z;
    }

    /**
     * Marks the bins of every tracked slot whose direction passes through the column, except the light's own column,
     * which no bin reads.
     */
    private void markColumn(int blockX, int blockZ) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (tracked[slot] && (Mth.floor(originX[slot]) != blockX || Mth.floor(originZ[slot]) != blockZ)) {
                markSquareForSlot(slot, blockX, blockZ, 1);
            }
        }
    }

    private void markSquare(int minX, int minZ, int size) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (tracked[slot]) {
                markSquareForSlot(slot, minX, minZ, size);
            }
        }
    }

    private void markSquareForSlot(int slot, int minX, int minZ, int size) {
        double x = originX[slot];
        double z = originZ[slot];
        double nearestX = Math.max(minX, Math.min(x, minX + size)) - x;
        double nearestZ = Math.max(minZ, Math.min(z, minZ + size)) - z;
        double reach = slotReaches[slot];
        if (nearestX * nearestX + nearestZ * nearestZ > reach * reach) {
            return;
        }
        dirtyBins[slot] |= binsAcross(x, z, minX, minZ, size);
    }

    /**
     * Builds every sample of one bin of a slot by stepping through the columns its direction crosses, from the
     * light's column out to the slot's reach. Returns the number of columns read.
     */
    private int buildBin(int slot, int bin, Columns columns) {
        double reach = slotReaches[slot];
        double angle = bin * BIN_ANGLE;
        double directionX = Math.cos(angle);
        double directionZ = Math.sin(angle);
        double x = originX[slot];
        double y = originY[slot];
        double z = originZ[slot];
        int columnX = Mth.floor(x);
        int columnZ = Mth.floor(z);
        int stepX = directionX > 0.0 ? 1 : -1;
        int stepZ = directionZ > 0.0 ? 1 : -1;
        double crossingStepX = directionX != 0.0 ? 1.0 / Math.abs(directionX) : Double.POSITIVE_INFINITY;
        double crossingStepZ = directionZ != 0.0 ? 1.0 / Math.abs(directionZ) : Double.POSITIVE_INFINITY;
        double nextCrossingX = directionX == 0.0 ? Double.POSITIVE_INFINITY
                : (directionX > 0.0 ? columnX + 1 - x : x - columnX) * crossingStepX;
        double nextCrossingZ = directionZ == 0.0 ? Double.POSITIVE_INFINITY
                : (directionZ > 0.0 ? columnZ + 1 - z : z - columnZ) * crossingStepZ;
        int binIndex = slot * BINS + bin;
        int base = binIndex * SAMPLES;
        horizons[base] = OPEN;
        int sample = 1;
        double nextSampleDistance = sampleDistance(sample, reach);
        double steepest = Double.NEGATIVE_INFINITY;
        int read = 0;
        while (true) {
            double entry;
            if (nextCrossingX < nextCrossingZ) {
                entry = nextCrossingX;
                columnX += stepX;
                nextCrossingX += crossingStepX;
            } else {
                entry = nextCrossingZ;
                columnZ += stepZ;
                nextCrossingZ += crossingStepZ;
            }
            // Each sample holds the columns entered before its distance:
            while (entry >= nextSampleDistance) {
                horizons[base + sample] = steepest == Double.NEGATIVE_INFINITY ? OPEN : (float) Math.atan(steepest);
                sample++;
                if (sample == SAMPLES) {
                    binReaches[binIndex] = (float) reach;
                    return read;
                }
                nextSampleDistance = sampleDistance(sample, reach);
            }
            read++;
            double distance = Math.max(entry, NEAREST_ENTRY);
            double floorY = y + distance * steepest;
            double top = columns.highestStop(columnX, columnZ, floorY);
            if (top > floorY) {
                steepest = (top - y) / distance;
            }
        }
    }

    /**
     * The horizon of the slot toward a point at the horizontal position, interpolated between the two nearest bins
     * and the two nearest samples, or OPEN for a slot that was not built since it took its key. A slot that the
     * selection freed keeps its last horizons, since it still fades out during the tick that freed it.
     */
    public float horizon(int slot, double pointX, double pointZ) {
        if (!built[slot]) {
            return OPEN;
        }
        double offsetX = pointX - originX[slot];
        double offsetZ = pointZ - originZ[slot];
        double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
        double position = Math.atan2(offsetZ, offsetX) / BIN_ANGLE;
        if (position < 0.0) {
            position += BINS;
        }
        int firstBin = Math.min((int) position, BINS - 1);
        double fraction = position - firstBin;
        int secondBin = (firstBin + 1) & (BINS - 1);
        return (float) ((1.0 - fraction) * binHorizon(slot, firstBin, distance)
                + fraction * binHorizon(slot, secondBin, distance));
    }

    private double binHorizon(int slot, int bin, double distance) {
        int binIndex = slot * BINS + bin;
        double reach = binReaches[binIndex];
        double position = reach > 0.0 ? (SAMPLES - 1) * Math.sqrt(Math.min(1.0, distance / reach)) : 0.0;
        int firstSample = Math.min((int) position, SAMPLES - 2);
        double fraction = position - firstSample;
        int base = binIndex * SAMPLES;
        return (1.0 - fraction) * horizons[base + firstSample] + fraction * horizons[base + firstSample + 1];
    }

    /**
     * The stored horizon of one sample of one bin of a slot.
     */
    public float sample(int slot, int bin, int sample) {
        return horizons[(slot * BINS + bin) * SAMPLES + sample];
    }

    /**
     * Whether the slot follows a used slot of the selection.
     */
    public boolean isTracked(int slot) {
        return tracked[slot];
    }

    /**
     * Whether every bin of the slot was built since the slot took its key.
     */
    public boolean isBuilt(int slot) {
        return tracked[slot] && built[slot];
    }

    /**
     * The bins of the slot that wait to be built, one bit per bin.
     */
    public long dirtyBins(int slot) {
        return dirtyBins[slot];
    }

    public double reach(int slot) {
        return slotReaches[slot];
    }

    /**
     * Marks every bin of every tracked slot, after what stops light changed.
     */
    public void markEveryBin() {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (tracked[slot]) {
                dirtyBins[slot] = ALL_BINS;
            }
        }
    }

    /**
     * Forgets every slot and every noted change.
     */
    public void clear() {
        Arrays.fill(tracked, false);
        Arrays.fill(built, false);
        Arrays.fill(dirtyBins, 0L);
        trackedCount = 0;
        clearChanges();
    }

    private void clearChanges() {
        changedColumnSet.clear();
        changedColumns.clear();
        changedChunkSet.clear();
        changedChunks.clear();
    }

    /**
     * Rolls the counts of the current second into the last second.
     */
    public void rollSecond() {
        lastSecond.copy(currentSecond);
        currentSecond.clear();
    }

    /**
     * A line about the horizons and their work in the last full second, for the client command.
     */
    public String describe(int blocksRead) {
        int builtSlots = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (isBuilt(slot)) {
                builtSlots++;
            }
        }
        return String.format(Locale.ROOT,
                "Horizons of %d slots, %d built; last second %d block changes seen with %d stopping light the same, "
                        + "%d columns and %d chunks changed, %d bins built reading %d columns and %d blocks",
                trackedCount, builtSlots, lastSecond.blockChanges, lastSecond.unchangedStops,
                lastSecond.columnsChanged, lastSecond.chunksChanged, lastSecond.binsBuilt, lastSecond.columnsVisited,
                blocksRead);
    }

    /**
     * Counts of one second of horizon work.
     */
    private static final class Counts {
        private int blockChanges;
        private int unchangedStops;
        private int columnsChanged;
        private int chunksChanged;
        private int binsBuilt;
        private int columnsVisited;

        private void copy(Counts other) {
            blockChanges = other.blockChanges;
            unchangedStops = other.unchangedStops;
            columnsChanged = other.columnsChanged;
            chunksChanged = other.chunksChanged;
            binsBuilt = other.binsBuilt;
            columnsVisited = other.columnsVisited;
        }

        private void clear() {
            blockChanges = 0;
            unchangedStops = 0;
            columnsChanged = 0;
            chunksChanged = 0;
            binsBuilt = 0;
            columnsVisited = 0;
        }
    }

    /**
     * Reads the columns of a level, in loaded chunks only, starting at the WORLD_SURFACE heightmap and walking down
     * past blocks that let light through. The chunk read last is kept, so forgetChunk must be called before any
     * chunk may have unloaded.
     */
    public static final class LevelColumns implements Columns {
        private final LightPillarOccluders occluders;
        private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        @Nullable
        private LevelReader level;
        @Nullable
        private ChunkAccess chunk;
        private int chunkX;
        private int chunkZ;
        private int blocksRead;

        public LevelColumns(LightPillarOccluders occluders) {
            this.occluders = occluders;
        }

        /**
         * Reads the level from now on, forgetting the chunk read last.
         */
        public void setLevel(@Nullable LevelReader level) {
            this.level = level;
            chunk = null;
        }

        public void forgetChunk() {
            chunk = null;
        }

        @Override
        public double highestStop(int blockX, int blockZ, double floorY) {
            ChunkAccess columnChunk = chunkAt(SectionPos.blockToSectionCoord(blockX),
                    SectionPos.blockToSectionCoord(blockZ));
            if (columnChunk == null || level == null) {
                return LightPillarOccluders.PASSES;
            }
            int surface = columnChunk.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
            int lowest = level.getMinBuildHeight();
            for (int blockY = surface; blockY >= lowest && blockY + 1 > floorY; blockY--) {
                blocksRead++;
                BlockState state = columnChunk.getBlockState(position.set(blockX, blockY, blockZ));
                double height = occluders.stoppedHeight(state, level, position);
                if (height != LightPillarOccluders.PASSES) {
                    double top = blockY + height;
                    return top > floorY ? top : LightPillarOccluders.PASSES;
                }
            }
            return LightPillarOccluders.PASSES;
        }

        @Nullable
        private ChunkAccess chunkAt(int columnChunkX, int columnChunkZ) {
            if (chunk == null || columnChunkX != chunkX || columnChunkZ != chunkZ) {
                if (level == null) {
                    return null;
                }
                chunk = level.getChunk(columnChunkX, columnChunkZ, ChunkStatus.FULL, false);
                chunkX = columnChunkX;
                chunkZ = columnChunkZ;
            }
            return chunk;
        }

        /**
         * The blocks read since the last call, which starts the count again.
         */
        public int takeBlocksRead() {
            int read = blocksRead;
            blocksRead = 0;
            return read;
        }
    }
}
