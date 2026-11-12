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

import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * The lights that can show pillars, merged into one cluster per cell of 4 by 4 by 4 blocks.
 * A scan adds the lights of one chunk section at a time and finishes a pass once it has seen every section. Finishing
 * replaces all clusters of the previous pass at once, and until then the previous pass's clusters are the ones read.
 * A cluster holds the summed strength of its lights per channel and their center weighted by their summed strength.
 * Its key is the packed position of its cell, which stays the same from pass to pass.
 */
public final class LightPillarClusters {
    public static final int CELL_SIZE = 4;
    private static final int SECTION_SIZE = 16;
    private static final int CELLS_PER_EDGE = SECTION_SIZE / CELL_SIZE;
    private static final int CELLS_PER_SECTION = CELLS_PER_EDGE * CELLS_PER_EDGE * CELLS_PER_EDGE;
    /**
     * How far below the lowest surface around a section the sky light can still show a pillar. Sky light falls by one
     * per block from the open air above the surface, and a light needs 8 at itself or the block above.
     */
    static final int SKY_LIGHT_DEPTH = 8;

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
    private ClusterList read = new ClusterList();
    private ClusterList filling = new ClusterList();

    /**
     * Whether a section whose top block is at the height can hold a light with enough sky light for a pillar, when the
     * lowest surface block in its chunk and the chunks around it is at the lowest surface height.
     */
    public static boolean sectionCanShowPillars(int sectionTopY, int lowestSurfaceY) {
        return sectionTopY >= lowestSurfaceY - SKY_LIGHT_DEPTH;
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
     * Ends the section begun last and adds a cluster for each of its cells that has lights to the pass being filled.
     */
    public void endSection() {
        for (int cell = 0; cell < CELLS_PER_SECTION; cell++) {
            double weight = cellWeight[cell];
            if (!(weight > 0.0)) {
                continue;
            }
            int keyX = sectionX * CELLS_PER_EDGE + cell % CELLS_PER_EDGE;
            int keyY = sectionY * CELLS_PER_EDGE + (cell / CELLS_PER_EDGE) % CELLS_PER_EDGE;
            int keyZ = sectionZ * CELLS_PER_EDGE + cell / (CELLS_PER_EDGE * CELLS_PER_EDGE);
            filling.add(BlockPos.asLong(keyX, keyY, keyZ), cellX[cell] / weight, cellY[cell] / weight,
                    cellZ[cell] / weight, cellRed[cell], cellGreen[cell], cellBlue[cell]);
        }
        resetCells();
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
     * Makes the clusters of the pass being filled the ones read, and starts an empty pass.
     */
    public void finishPass() {
        ClusterList finished = filling;
        filling = read;
        read = finished;
        filling.clear();
    }

    /**
     * Drops what the pass being filled holds, so a new pass starts empty and the clusters read stay.
     */
    public void abandonPass() {
        filling.clear();
        resetCells();
    }

    /**
     * Removes the clusters of both passes.
     */
    public void clear() {
        read.clear();
        filling.clear();
        resetCells();
    }

    /**
     * The number of clusters in the pass being filled.
     */
    public int fillingCount() {
        return filling.size;
    }

    /**
     * The number of clusters read, from the last finished pass.
     */
    public int count() {
        return read.size;
    }

    public long key(int cluster) {
        return read.keys[cluster];
    }

    public double x(int cluster) {
        return read.x[cluster];
    }

    public double y(int cluster) {
        return read.y[cluster];
    }

    public double z(int cluster) {
        return read.z[cluster];
    }

    public float red(int cluster) {
        return read.red[cluster];
    }

    public float green(int cluster) {
        return read.green[cluster];
    }

    public float blue(int cluster) {
        return read.blue[cluster];
    }

    /**
     * Clusters in arrays that grow when full and are kept when cleared, so a filled list allocates nothing again.
     */
    private static final class ClusterList {
        private static final int INITIAL_CAPACITY = 256;

        private long[] keys = new long[INITIAL_CAPACITY];
        private double[] x = new double[INITIAL_CAPACITY];
        private double[] y = new double[INITIAL_CAPACITY];
        private double[] z = new double[INITIAL_CAPACITY];
        private float[] red = new float[INITIAL_CAPACITY];
        private float[] green = new float[INITIAL_CAPACITY];
        private float[] blue = new float[INITIAL_CAPACITY];
        private int size;

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
            size++;
        }

        private void clear() {
            size = 0;
        }
    }
}
