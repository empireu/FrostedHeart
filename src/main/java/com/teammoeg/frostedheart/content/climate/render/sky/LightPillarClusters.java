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

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The lights that can show pillars, merged into one cluster per cell of 4 by 4 by 4 blocks, kept per chunk section.
 * The lights of a section are added between beginSection and endSection, which replaces every cluster the section had.
 * A cluster holds the summed strength of its lights per channel and their center weighted by their summed strength.
 * Its key is the packed position of its cell. Removing a cluster moves the last one into its index, so indices are only
 * valid until the next change.
 */
public final class LightPillarClusters {
    public static final int CELL_SIZE = 4;
    /**
     * A light needs more sky light than this at its block or the block above to show a pillar.
     */
    public static final int SKY_LIGHT_THRESHOLD = 8;
    private static final int SECTION_SIZE = 16;
    private static final int CELLS_PER_EDGE = SECTION_SIZE / CELL_SIZE;
    private static final int CELLS_PER_SECTION = CELLS_PER_EDGE * CELLS_PER_EDGE * CELLS_PER_EDGE;
    /**
     * The bytes of a section's light data that hold its lowest layer of blocks, at two blocks per byte.
     */
    private static final int LOWEST_LAYER_BYTES = SECTION_SIZE * SECTION_SIZE / 2;
    private static final int INITIAL_SECTIONS = 1024;
    private static final int INITIAL_CLUSTERS = 4096;
    private static final int NO_INDEX = -1;

    private final double[] cellX = new double[CELLS_PER_SECTION];
    private final double[] cellY = new double[CELLS_PER_SECTION];
    private final double[] cellZ = new double[CELLS_PER_SECTION];
    private final double[] cellWeight = new double[CELLS_PER_SECTION];
    private final float[] cellRed = new float[CELLS_PER_SECTION];
    private final float[] cellGreen = new float[CELLS_PER_SECTION];
    private final float[] cellBlue = new float[CELLS_PER_SECTION];
    private int sectionX;
    private int sectionY;
    private int sectionZ;

    /**
     * For each section with clusters, a mask with one bit per cell that holds a cluster.
     */
    private final Long2LongOpenHashMap sectionCells = new Long2LongOpenHashMap(INITIAL_SECTIONS);
    private final Long2IntOpenHashMap clusterIndices = new Long2IntOpenHashMap(INITIAL_CLUSTERS);
    private long[] keys = new long[INITIAL_CLUSTERS];
    private double[] x = new double[INITIAL_CLUSTERS];
    private double[] y = new double[INITIAL_CLUSTERS];
    private double[] z = new double[INITIAL_CLUSTERS];
    private float[] red = new float[INITIAL_CLUSTERS];
    private float[] green = new float[INITIAL_CLUSTERS];
    private float[] blue = new float[INITIAL_CLUSTERS];
    private int size;

    public LightPillarClusters() {
        clusterIndices.defaultReturnValue(NO_INDEX);
    }

    /**
     * Whether a section can hold a light with enough sky light for a pillar, from the sky light data of the section
     * and of the section above, each null where the light engine stores none. A light counts by the sky light at its
     * own block or the block above, so the lowest layer of the section above counts too. Missing data counts as lit,
     * because the sky light engine takes the light of a section without data from the sections above it.
     */
    public static boolean sectionCanShowPillars(@Nullable DataLayer sectionSkyLight, @Nullable DataLayer aboveSkyLight) {
        return hasLightAboveThreshold(sectionSkyLight, DataLayer.SIZE)
                || hasLightAboveThreshold(aboveSkyLight, LOWEST_LAYER_BYTES);
    }

    /**
     * Whether the first bytes of the light data hold a value above SKY_LIGHT_THRESHOLD.
     */
    private static boolean hasLightAboveThreshold(@Nullable DataLayer light, int byteCount) {
        if (light == null) {
            return true;
        }
        if (light.isDefinitelyHomogenous()) {
            return light.get(0, 0, 0) > SKY_LIGHT_THRESHOLD;
        }
        byte[] data = light.getData();
        for (int index = 0; index < byteCount; index++) {
            int pair = data[index];
            if ((pair & 0xF) > SKY_LIGHT_THRESHOLD || ((pair >> 4) & 0xF) > SKY_LIGHT_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts adding the lights of the chunk section at the section coordinates.
     */
    public void beginSection(int sectionX, int sectionY, int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
    }

    /**
     * Adds a light at the block, which must lie in the section begun last, with its strength per channel.
     */
    public void addLight(int blockX, int blockY, int blockZ, float red, float green, float blue) {
        int cell = ((blockX & (SECTION_SIZE - 1)) / CELL_SIZE)
                + CELLS_PER_EDGE * (((blockY & (SECTION_SIZE - 1)) / CELL_SIZE)
                + CELLS_PER_EDGE * ((blockZ & (SECTION_SIZE - 1)) / CELL_SIZE));
        double weight = red + green + blue;
        cellX[cell] += weight * (blockX + 0.5);
        cellY[cell] += weight * (blockY + 0.5);
        cellZ[cell] += weight * (blockZ + 0.5);
        cellWeight[cell] += weight;
        cellRed[cell] += red;
        cellGreen[cell] += green;
        cellBlue[cell] += blue;
    }

    /**
     * Ends the section begun last and replaces its clusters with one for each of its cells that has lights.
     */
    public void endSection() {
        removeSection(sectionX, sectionY, sectionZ);
        long usedCells = 0L;
        for (int cell = 0; cell < CELLS_PER_SECTION; cell++) {
            double weight = cellWeight[cell];
            if (!(weight > 0.0)) {
                continue;
            }
            add(cellKey(sectionX, sectionY, sectionZ, cell), cellX[cell] / weight, cellY[cell] / weight,
                    cellZ[cell] / weight, cellRed[cell], cellGreen[cell], cellBlue[cell]);
            usedCells |= 1L << cell;
        }
        if (usedCells != 0L) {
            sectionCells.put(SectionPos.asLong(sectionX, sectionY, sectionZ), usedCells);
        }
        resetCells();
    }

    /**
     * Removes the clusters of the chunk section at the section coordinates.
     */
    public void removeSection(int sectionX, int sectionY, int sectionZ) {
        long usedCells = sectionCells.remove(SectionPos.asLong(sectionX, sectionY, sectionZ));
        while (usedCells != 0L) {
            int cell = Long.numberOfTrailingZeros(usedCells);
            usedCells &= usedCells - 1L;
            remove(cellKey(sectionX, sectionY, sectionZ, cell));
        }
    }

    /**
     * Removes the clusters of the sections of a chunk column, from the lowest section up through the count.
     */
    public void removeColumn(int chunkX, int chunkZ, int lowestSectionY, int sectionCount) {
        for (int sectionY = lowestSectionY; sectionY < lowestSectionY + sectionCount; sectionY++) {
            removeSection(chunkX, sectionY, chunkZ);
        }
    }

    /**
     * Removes every cluster.
     */
    public void clear() {
        sectionCells.clear();
        clusterIndices.clear();
        size = 0;
        resetCells();
    }

    private static long cellKey(int sectionX, int sectionY, int sectionZ, int cell) {
        return BlockPos.asLong(sectionX * CELLS_PER_EDGE + cell % CELLS_PER_EDGE,
                sectionY * CELLS_PER_EDGE + (cell / CELLS_PER_EDGE) % CELLS_PER_EDGE,
                sectionZ * CELLS_PER_EDGE + cell / (CELLS_PER_EDGE * CELLS_PER_EDGE));
    }

    private void add(long key, double clusterX, double clusterY, double clusterZ, float clusterRed,
            float clusterGreen, float clusterBlue) {
        if (size == keys.length) {
            int capacity = 2 * size;
            keys = Arrays.copyOf(keys, capacity);
            x = Arrays.copyOf(x, capacity);
            y = Arrays.copyOf(y, capacity);
            z = Arrays.copyOf(z, capacity);
            red = Arrays.copyOf(red, capacity);
            green = Arrays.copyOf(green, capacity);
            blue = Arrays.copyOf(blue, capacity);
        }
        keys[size] = key;
        x[size] = clusterX;
        y[size] = clusterY;
        z[size] = clusterZ;
        red[size] = clusterRed;
        green[size] = clusterGreen;
        blue[size] = clusterBlue;
        clusterIndices.put(key, size);
        size++;
    }

    private void remove(long key) {
        int index = clusterIndices.remove(key);
        if (index == NO_INDEX) {
            return;
        }
        size--;
        if (index == size) {
            return;
        }
        keys[index] = keys[size];
        x[index] = x[size];
        y[index] = y[size];
        z[index] = z[size];
        red[index] = red[size];
        green[index] = green[size];
        blue[index] = blue[size];
        clusterIndices.put(keys[index], index);
    }

    private void resetCells() {
        Arrays.fill(cellX, 0.0);
        Arrays.fill(cellY, 0.0);
        Arrays.fill(cellZ, 0.0);
        Arrays.fill(cellWeight, 0.0);
        Arrays.fill(cellRed, 0.0F);
        Arrays.fill(cellGreen, 0.0F);
        Arrays.fill(cellBlue, 0.0F);
    }

    /**
     * The number of clusters.
     */
    public int count() {
        return size;
    }

    /**
     * The number of chunk sections with clusters.
     */
    public int sectionCount() {
        return sectionCells.size();
    }

    public long key(int cluster) {
        return keys[cluster];
    }

    public double x(int cluster) {
        return x[cluster];
    }

    public double y(int cluster) {
        return y[cluster];
    }

    public double z(int cluster) {
        return z[cluster];
    }

    public float red(int cluster) {
        return red[cluster];
    }

    public float green(int cluster) {
        return green[cluster];
    }

    public float blue(int cluster) {
        return blue[cluster];
    }
}
