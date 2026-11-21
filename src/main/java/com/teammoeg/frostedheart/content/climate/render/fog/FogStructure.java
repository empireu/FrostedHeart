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

import java.util.Arrays;
import java.util.Random;

/**
 * The moving structures of the fog: the tiling texture that shapes them, the wind frame that carries them, and the density that they give the fog.
 */
public final class FogStructure {
    /**
     * The texels per axis of the texture, which tiles on every axis.
     */
    public static final int SIZE = 64;
    /**
     * The bytes per texel, interleaved.
     */
    public static final int CHANNELS = 2;
    /**
     * The channel of the shape, which is equalized so that its bytes are uniform.
     */
    public static final int SHAPE_CHANNEL = 0;
    /**
     * The channel of the erosion detail.
     */
    public static final int DETAIL_CHANNEL = 1;
    /**
     * The seed of the texture that the renderer builds.
     */
    public static final long SEED = 0x57A0C7L;
    /**
     * The shape lattice cells per tile, so that a tile is TILE_CELLS * size blocks across the wind.
     */
    public static final int TILE_CELLS = 4;
    /**
     * The detail channel is read at this many times the shape coordinate, a whole number so that both tile together.
     */
    public static final int DETAIL_REPEAT = 4;
    /**
     * The share of a structure's value range over which its edge rises to the core.
     */
    public static final double EDGE_WIDTH = 0.5;
    /**
     * The vertical tile is this share of the tile across the wind.
     */
    public static final double VERTICAL_SQUASH = 0.6;
    /**
     * The distance in blocks from the eye where the erosion starts to fade.
     */
    public static final double DETAIL_FADE_START = 32.0;
    /**
     * The distance in blocks from the eye where the erosion is gone.
     */
    public static final double DETAIL_FADE_END = 64.0;
    /**
     * The last share of the range over which the density fades to 1.
     */
    public static final double RANGE_FADE_SHARE = 0.25;
    /**
     * The smallest coverage that the structures take.
     */
    public static final double MINIMUM_COVERAGE = 0.05;
    /**
     * The largest erosion, which keeps 1 - erosion * detail at 0.1 or more.
     */
    public static final double MAXIMUM_EROSION = 0.9;
    /**
     * The shortest size in blocks of the structures.
     */
    public static final double MINIMUM_SIZE = 16.0;
    /**
     * The longest size in blocks of the structures.
     */
    public static final double MAXIMUM_SIZE = 1024.0;
    /**
     * The smallest stretch of the structures along the wind.
     */
    public static final double MINIMUM_STRETCH = 1.0;
    /**
     * The largest stretch of the structures along the wind.
     */
    public static final double MAXIMUM_STRETCH = 8.0;
    /**
     * A mean at or below this turns the structures off.
     */
    public static final double MINIMUM_MEAN = 1.0e-6;
    /**
     * The entries of the permutation table that hashes a lattice point to its gradient, a power of two so that the hash
     * wraps with a bitwise and.
     */
    private static final int PERMUTATION_SIZE = 256;
    /**
     * The mask of a texel index, so that an index wraps into the texture with a bitwise and that also holds for
     * negative indices.
     */
    private static final int SIZE_MASK = SIZE - 1;
    /**
     * The texels per tile, which the equalization spreads the bytes over.
     */
    private static final int TEXEL_COUNT = SIZE * SIZE * SIZE;

    private byte[] texels;
    private double share;
    private double coverage = MINIMUM_COVERAGE;
    private double erosion;
    private double range;
    private double meanWithoutErosion;
    private double meanWithErosion;
    private boolean hasMeans;
    private byte[] meansTexels;
    private double meansCoverage;
    private double meansErosion;
    private boolean anchored;
    private double direction;
    private double eyeU;
    private double eyeV;
    private double eyeW;
    private double alongX;
    private double alongZ;
    private double upY;
    private double acrossX;
    private double acrossZ;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private double lastDisplacementX;
    private double lastDisplacementZ;

    /**
     * Creates a structure that has no texture and no density yet, so that it stays off until both are set.
     */
    public FogStructure() {
    }

    /**
     * Fills the bytes of the texture of a seed, with the shape channel equalized and the detail channel normalized.
     * @param seed the seed of the texture, so that the same seed builds the same texture
     * @param texels the array of SIZE * SIZE * SIZE * CHANNELS bytes to fill, indexed by
     *        CHANNELS * (x + y * SIZE + z * SIZE * SIZE) + channel
     * @throws IllegalArgumentException when the array does not hold SIZE * SIZE * SIZE * CHANNELS bytes
     */
    public static void build(long seed, byte[] texels) {
        if (texels.length != TEXEL_COUNT * CHANNELS) {
            throw new IllegalArgumentException("The structure texture has " + texels.length + " bytes instead of "
                    + (TEXEL_COUNT * CHANNELS));
        }
        int[] permutation = new int[PERMUTATION_SIZE];
        for (int entry = 0; entry < PERMUTATION_SIZE; entry++) {
            permutation[entry] = entry;
        }
        Random random = new Random(seed);
        for (int entry = PERMUTATION_SIZE - 1; entry > 0; entry--) {
            int swapEntry = random.nextInt(entry + 1);
            int keptEntry = permutation[entry];
            permutation[entry] = permutation[swapEntry];
            permutation[swapEntry] = keptEntry;
        }
        double[] fourCellJitters = nextJitters(random, 4);
        double[] eightCellJitters = nextJitters(random, 8);
        double[] detailJitters = nextJitters(random, 8);
        double[] shapeValues = new double[TEXEL_COUNT];
        double[] detailValues = new double[TEXEL_COUNT];
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double pointX = (x + 0.5) / SIZE;
                    double pointY = (y + 0.5) / SIZE;
                    double pointZ = (z + 0.5) / SIZE;
                    int texel = x + y * SIZE + z * SIZE * SIZE;
                    shapeValues[texel] = shapeValue(permutation, fourCellJitters, eightCellJitters, pointX, pointY,
                            pointZ);
                    detailValues[texel] = worley(detailJitters, 8, 8.0 * pointX, 8.0 * pointY, 8.0 * pointZ);
                }
            }
        }
        equalize(shapeValues, texels);
        normalizeDetail(detailValues, texels);
    }

    /**
     * Reads one channel of the texture at a normalized coordinate with trilinear filtering and the repeat of the wrap.
     * @param texels the array of SIZE * SIZE * SIZE * CHANNELS bytes of the texture
     * @param channel the channel to read, SHAPE_CHANNEL or DETAIL_CHANNEL
     * @param u the coordinate along x in tiles, where a whole number is the wrap
     * @param v the coordinate along y in tiles
     * @param w the coordinate along z in tiles
     * @return the filtered value from 0 to 1
     */
    public static double sampleChannel(byte[] texels, int channel, double u, double v, double w) {
        double x = u * SIZE - 0.5;
        double y = v * SIZE - 0.5;
        double z = w * SIZE - 0.5;
        int lowX = (int) Math.floor(x);
        int lowY = (int) Math.floor(y);
        int lowZ = (int) Math.floor(z);
        double fractionX = x - lowX;
        double fractionY = y - lowY;
        double fractionZ = z - lowZ;
        int highX = (lowX + 1) & SIZE_MASK;
        int highY = (lowY + 1) & SIZE_MASK;
        int highZ = (lowZ + 1) & SIZE_MASK;
        lowX &= SIZE_MASK;
        lowY &= SIZE_MASK;
        lowZ &= SIZE_MASK;
        double value000 = texel(texels, channel, lowX, lowY, lowZ);
        double value100 = texel(texels, channel, highX, lowY, lowZ);
        double value010 = texel(texels, channel, lowX, highY, lowZ);
        double value110 = texel(texels, channel, highX, highY, lowZ);
        double value001 = texel(texels, channel, lowX, lowY, highZ);
        double value101 = texel(texels, channel, highX, lowY, highZ);
        double value011 = texel(texels, channel, lowX, highY, highZ);
        double value111 = texel(texels, channel, highX, highY, highZ);
        return lerp(fractionZ, lerp(fractionY, lerp(fractionX, value000, value100),
                lerp(fractionX, value010, value110)), lerp(fractionY, lerp(fractionX, value001, value101),
                lerp(fractionX, value011, value111)));
    }

    /**
     * The share of the space that a structure fills, eaten at its edges by the detail.
     * @param base the filtered shape channel at the point, from 0 to 1
     * @param detail the filtered detail channel at the point, from 0 to 1
     * @param coverage the share of space that the structures fill, clamped to MINIMUM_COVERAGE to 1, where NaN counts
     *        as the minimum
     * @param erosion how much the detail eats the edges, clamped to 0 to MAXIMUM_EROSION, where NaN counts as 0
     * @return the shape from 0 in the clear air to 1 in a core, and 0 when the base is NaN
     */
    public static double shape(double base, double detail, double coverage, double erosion) {
        if (Double.isNaN(base)) {
            return 0.0;
        }
        double clampedCoverage = clampCoverage(coverage);
        double clampedErosion = clampErosion(erosion);
        double clampedDetail = !(detail > 0.0) ? 0.0 : Math.min(1.0, detail);
        double x = (base - (1.0 - clampedCoverage)) / clampedCoverage;
        double t = clamp(x / EDGE_WIDTH, 0.0, 1.0);
        double profile = t * t * (3.0 - 2.0 * t);
        double eaten = clampedErosion * clampedDetail;
        return clamp((profile - eaten) / (1.0 - eaten), 0.0, 1.0);
    }

    /**
     * The mean of the shape over the texel centers of the texture, which the density divides by.
     * @param texels the array of SIZE * SIZE * SIZE * CHANNELS bytes of the texture
     * @param coverage the share of space that the structures fill
     * @param erosion how much the detail eats the edges
     * @return the mean shape, which is 0 for a mean at or below MINIMUM_MEAN
     */
    public static double meanShape(byte[] texels, double coverage, double erosion) {
        if (texels == null) {
            return 0.0;
        }
        double clampedCoverage = clampCoverage(coverage);
        double clampedErosion = clampErosion(erosion);
        double sum = 0.0;
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double base = texel(texels, SHAPE_CHANNEL, x, y, z);
                    double detail = 0.0;
                    if (clampedErosion > 0.0) {
                        detail = sampleChannel(texels, DETAIL_CHANNEL, DETAIL_REPEAT * (x + 0.5) / SIZE,
                                DETAIL_REPEAT * (y + 0.5) / SIZE, DETAIL_REPEAT * (z + 0.5) / SIZE);
                    }
                    sum += shape(base, detail, clampedCoverage, clampedErosion);
                }
            }
        }
        double mean = sum / TEXEL_COUNT;
        return mean > MINIMUM_MEAN ? mean : 0.0;
    }

    /**
     * Puts the pattern into the wind frame of this frame, which carries it with the eye and the wind.
     * @param eyeX the world x of the eye in blocks
     * @param eyeY the world y of the eye in blocks
     * @param eyeZ the world z of the eye in blocks
     * @param displacementX the blocks the wind has carried the pattern in x
     * @param displacementZ the blocks the wind has carried the pattern in z
     * @param direction the direction the wind blows toward in the x-z plane in radians, where NaN keeps the last one
     * @param size the spacing in blocks of the structures across the wind, clamped to MINIMUM_SIZE to MAXIMUM_SIZE
     * @param stretch how many times longer the structures are along the wind, clamped to MINIMUM_STRETCH to
     *        MAXIMUM_STRETCH
     */
    public void follow(double eyeX, double eyeY, double eyeZ, double displacementX, double displacementZ,
            double direction, double size, double stretch) {
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
                || !Double.isFinite(displacementX) || !Double.isFinite(displacementZ)) {
            return;
        }
        if (!Double.isNaN(direction)) {
            this.direction = direction;
        }
        double clampedSize = size;
        if (!(clampedSize >= MINIMUM_SIZE)) {
            clampedSize = MINIMUM_SIZE;
        } else if (clampedSize > MAXIMUM_SIZE) {
            clampedSize = MAXIMUM_SIZE;
        }
        double clampedStretch = stretch;
        if (!(clampedStretch >= MINIMUM_STRETCH)) {
            clampedStretch = MINIMUM_STRETCH;
        } else if (clampedStretch > MAXIMUM_STRETCH) {
            clampedStretch = MAXIMUM_STRETCH;
        }
        double tile = TILE_CELLS * clampedSize;
        double cosine = Math.cos(this.direction);
        double sine = Math.sin(this.direction);
        alongX = cosine / (tile * clampedStretch);
        alongZ = sine / (tile * clampedStretch);
        upY = 1.0 / (tile * VERTICAL_SQUASH);
        acrossX = -sine / tile;
        acrossZ = cosine / tile;
        if (anchored) {
            double motionX = eyeX - lastEyeX - (displacementX - lastDisplacementX);
            double motionY = eyeY - lastEyeY;
            double motionZ = eyeZ - lastEyeZ - (displacementZ - lastDisplacementZ);
            eyeU += alongX * motionX + alongZ * motionZ;
            eyeV += upY * motionY;
            eyeW += acrossX * motionX + acrossZ * motionZ;
        } else {
            double anchorX = eyeX - displacementX;
            double anchorZ = eyeZ - displacementZ;
            eyeU = alongX * anchorX + alongZ * anchorZ;
            eyeV = upY * eyeY;
            eyeW = acrossX * anchorX + acrossZ * anchorZ;
            anchored = true;
        }
        eyeU -= Math.floor(eyeU);
        eyeV -= Math.floor(eyeV);
        eyeW -= Math.floor(eyeW);
        lastEyeX = eyeX;
        lastEyeY = eyeY;
        lastEyeZ = eyeZ;
        lastDisplacementX = displacementX;
        lastDisplacementZ = displacementZ;
    }

    /**
     * Makes the next follow anchor the pattern to the world again.
     */
    public void reset() {
        anchored = false;
    }

    /**
     * The pattern coordinate of a point at an offset from the eye along one axis, in tiles and unwrapped.
     * @param axis 0 along the wind, 1 up, 2 across the wind
     * @param offsetX the offset of the point from the eye in x, in blocks
     * @param offsetY the offset of the point from the eye in y, in blocks
     * @param offsetZ the offset of the point from the eye in z, in blocks
     * @return the coordinate in tiles
     */
    public double patternCoordinate(int axis, double offsetX, double offsetY, double offsetZ) {
        if (axis == 0) {
            return eyeU + alongX * offsetX + alongZ * offsetZ;
        }
        if (axis == 1) {
            return eyeV + upY * offsetY;
        }
        return eyeW + acrossX * offsetX + acrossZ * offsetZ;
    }

    /**
     * The pattern coordinate of the eye along the wind, in tiles from 0 up to but not including 1.
     */
    public double eyePatternU() {
        return eyeU;
    }

    /**
     * The pattern coordinate of the eye upward, in tiles from 0 up to but not including 1.
     */
    public double eyePatternV() {
        return eyeV;
    }

    /**
     * The pattern coordinate of the eye across the wind, in tiles from 0 up to but not including 1.
     */
    public double eyePatternW() {
        return eyeW;
    }

    /**
     * The tiles that a block of offset along the wind covers.
     */
    public double alongX() {
        return alongX;
    }

    /**
     * The tiles that a block of offset along the wind covers in z.
     */
    public double alongZ() {
        return alongZ;
    }

    /**
     * The tiles that a block of offset upward covers.
     */
    public double upY() {
        return upY;
    }

    /**
     * The tiles that a block of offset across the wind covers in x.
     */
    public double acrossX() {
        return acrossX;
    }

    /**
     * The tiles that a block of offset across the wind covers in z.
     */
    public double acrossZ() {
        return acrossZ;
    }

    /**
     * Keeps the texture that this structure reads, which it does not copy. A null texture turns the structures off.
     * @param texels the array of SIZE * SIZE * SIZE * CHANNELS bytes of the texture, or null
     */
    public void setTexture(byte[] texels) {
        this.texels = texels;
    }

    /**
     * Sets the density of the structures, and computes its means from the texture when they changed.
     * @param share the share of the fog that sits in the structures, clamped to 0 to 1, where NaN counts as 0
     * @param coverage the share of space that the structures fill
     * @param erosion how much the detail eats the edges
     * @param range the distance in blocks out to which the structures show, where NaN or not positive turns them off
     */
    public void setDensity(double share, double coverage, double erosion, double range) {
        this.share = clampShare(share);
        this.coverage = clampCoverage(coverage);
        this.erosion = clampErosion(erosion);
        this.range = range > 0.0 ? range : 0.0;
        byte[] currentTexels = texels;
        if (currentTexels == null) {
            return;
        }
        if (hasMeans && meansTexels == currentTexels && Double.compare(meansCoverage, this.coverage) == 0
                && Double.compare(meansErosion, this.erosion) == 0) {
            return;
        }
        meansTexels = currentTexels;
        meansCoverage = this.coverage;
        meansErosion = this.erosion;
        hasMeans = true;
        meanWithoutErosion = meanShape(currentTexels, this.coverage, 0.0);
        meanWithErosion = meanShape(currentTexels, this.coverage, this.erosion);
    }

    /**
     * Sets the density of the structures with the means given, which forgets the cached ones.
     * @param share the share of the fog that sits in the structures, clamped to 0 to 1, where NaN counts as 0
     * @param coverage the share of space that the structures fill
     * @param erosion how much the detail eats the edges
     * @param range the distance in blocks out to which the structures show, where NaN or not positive turns them off
     * @param meanWithoutErosion the mean shape without erosion
     * @param meanWithErosion the mean shape with erosion
     */
    public void setDensityWithMeans(double share, double coverage, double erosion, double range,
            double meanWithoutErosion, double meanWithErosion) {
        this.share = clampShare(share);
        this.coverage = clampCoverage(coverage);
        this.erosion = clampErosion(erosion);
        this.range = range > 0.0 ? range : 0.0;
        this.meanWithoutErosion = meanWithoutErosion;
        this.meanWithErosion = meanWithErosion;
        hasMeans = false;
    }

    /**
     * Whether the structures change the fog, which needs a texture, a positive share, a positive range and both means
     * above MINIMUM_MEAN.
     */
    public boolean isOn() {
        return texels != null && share > 0.0 && range > 0.0 && meanWithoutErosion > MINIMUM_MEAN
                && meanWithErosion > MINIMUM_MEAN;
    }

    /**
     * The density of the structures at an offset from the eye, which is 1 when they are off.
     * @param offsetX the offset of the point from the eye in x, in blocks
     * @param offsetY the offset of the point from the eye in y, in blocks
     * @param offsetZ the offset of the point from the eye in z, in blocks
     * @return the density, which is never below 1 - share
     */
    public double densityAt(double offsetX, double offsetY, double offsetZ) {
        if (!isOn()) {
            return 1.0;
        }
        double distance = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        double rangeWeight = clamp((range - distance) / (RANGE_FADE_SHARE * range), 0.0, 1.0);
        if (!(rangeWeight > 0.0)) {
            return 1.0;
        }
        double detailWeight = clamp((DETAIL_FADE_END - distance) / (DETAIL_FADE_END - DETAIL_FADE_START), 0.0, 1.0);
        double localErosion = erosion * detailWeight;
        double pointU = patternCoordinate(0, offsetX, offsetY, offsetZ);
        double pointV = patternCoordinate(1, offsetX, offsetY, offsetZ);
        double pointW = patternCoordinate(2, offsetX, offsetY, offsetZ);
        double base = sampleChannel(texels, SHAPE_CHANNEL, pointU, pointV, pointW);
        double detail = 0.0;
        if (localErosion > 0.0) {
            detail = sampleChannel(texels, DETAIL_CHANNEL, DETAIL_REPEAT * pointU, DETAIL_REPEAT * pointV,
                    DETAIL_REPEAT * pointW);
        }
        double structureShape = shape(base, detail, coverage, localErosion);
        double mean = meanWithoutErosion + (meanWithErosion - meanWithoutErosion) * detailWeight;
        return 1.0 + rangeWeight * share * (structureShape / mean - 1.0);
    }

    /**
     * The share of the fog that sits in the structures.
     */
    public double share() {
        return share;
    }

    /**
     * The share of space that the structures fill.
     */
    public double coverage() {
        return coverage;
    }

    /**
     * How much the detail eats the edges of the structures.
     */
    public double erosion() {
        return erosion;
    }

    /**
     * The distance in blocks out to which the structures show.
     */
    public double range() {
        return range;
    }

    /**
     * The mean shape of the structures without erosion.
     */
    public double meanWithoutErosion() {
        return meanWithoutErosion;
    }

    /**
     * The mean shape of the structures with erosion.
     */
    public double meanWithErosion() {
        return meanWithErosion;
    }

    /**
     * The density of the clear air between the structures.
     */
    public double clearAirDensity() {
        return 1.0 - share;
    }

    /**
     * The density of the cores of the structures, which is 1 while they are off.
     */
    public double coreDensity() {
        if (!isOn()) {
            return 1.0;
        }
        return 1.0 + share * (1.0 / meanWithErosion - 1.0);
    }

    /**
     * Draws the jitters of the feature points of one Worley noise, in the order z, then y, then x, three values each for
     * x, y and z. The feature point of a cell is its corner plus its jitter, and the jitters wrap with the tile.
     */
    private static double[] nextJitters(Random random, int cells) {
        double[] jitters = new double[3 * cells * cells * cells];
        for (int z = 0; z < cells; z++) {
            for (int y = 0; y < cells; y++) {
                for (int x = 0; x < cells; x++) {
                    int jitterIndex = 3 * ((z * cells + y) * cells + x);
                    jitters[jitterIndex] = random.nextDouble();
                    jitters[jitterIndex + 1] = random.nextDouble();
                    jitters[jitterIndex + 2] = random.nextDouble();
                }
            }
        }
        return jitters;
    }

    /**
     * The raw shape of a point of the tile in tile units, before its equalization: the perlin billows, the Worley puffs
     * and the cluster noise that groups the structures into dense stretches and lulls.
     */
    private static double shapeValue(int[] permutation, double[] fourCellJitters, double[] eightCellJitters,
            double pointX, double pointY, double pointZ) {
        double perlinPart = 0.5 + 0.5 * (gradientNoise(permutation, 4, 4.0 * pointX, 4.0 * pointY, 4.0 * pointZ)
                + 0.5 * gradientNoise(permutation, 8, 8.0 * pointX, 8.0 * pointY, 8.0 * pointZ)) / 1.5;
        double worleyPart = 0.7 * (1.0 - worley(fourCellJitters, 4, 4.0 * pointX, 4.0 * pointY, 4.0 * pointZ))
                + 0.3 * (1.0 - worley(eightCellJitters, 8, 8.0 * pointX, 8.0 * pointY, 8.0 * pointZ));
        double clusterPart = 0.5 + 0.5 * gradientNoise(permutation, 2, 2.0 * pointX, 2.0 * pointY, 2.0 * pointZ);
        return 0.35 * perlinPart + 0.35 * worleyPart + 0.3 * clusterPart;
    }

    /**
     * The distance from a point in cell units to the nearest feature point of a Worley noise, capped at 1. The feature
     * point of a cell is its corner plus the jitter that wraps with the tile, while the cell itself does not wrap, so
     * the distances across the edge of the tile are right.
     */
    private static double worley(double[] jitters, int cells, double x, double y, double z) {
        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        int mask = cells - 1;
        double leastDistanceSquared = Double.POSITIVE_INFINITY;
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    int featureX = cellX + offsetX;
                    int featureY = cellY + offsetY;
                    int featureZ = cellZ + offsetZ;
                    int jitterIndex = 3 * (((featureZ & mask) * cells + (featureY & mask)) * cells + (featureX & mask));
                    double differenceX = featureX + jitters[jitterIndex] - x;
                    double differenceY = featureY + jitters[jitterIndex + 1] - y;
                    double differenceZ = featureZ + jitters[jitterIndex + 2] - z;
                    leastDistanceSquared = Math.min(leastDistanceSquared,
                            differenceX * differenceX + differenceY * differenceY + differenceZ * differenceZ);
                }
            }
        }
        return Math.min(1.0, Math.sqrt(leastDistanceSquared));
    }

    /**
     * Perlin's improved gradient noise at a point in lattice units, with the quintic fade and the gradients of the
     * permutation table, periodic on every axis with the given cell count, which is a power of two.
     */
    private static double gradientNoise(int[] permutation, int cells, double x, double y, double z) {
        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        double fractionX = x - cellX;
        double fractionY = y - cellY;
        double fractionZ = z - cellZ;
        int mask = cells - 1;
        int lowX = cellX & mask;
        int lowY = cellY & mask;
        int lowZ = cellZ & mask;
        int highX = (lowX + 1) & mask;
        int highY = (lowY + 1) & mask;
        int highZ = (lowZ + 1) & mask;
        double value000 = gradient(hash(permutation, lowX, lowY, lowZ), fractionX, fractionY, fractionZ);
        double value100 = gradient(hash(permutation, highX, lowY, lowZ), fractionX - 1.0, fractionY, fractionZ);
        double value010 = gradient(hash(permutation, lowX, highY, lowZ), fractionX, fractionY - 1.0, fractionZ);
        double value110 = gradient(hash(permutation, highX, highY, lowZ), fractionX - 1.0, fractionY - 1.0,
                fractionZ);
        double value001 = gradient(hash(permutation, lowX, lowY, highZ), fractionX, fractionY, fractionZ - 1.0);
        double value101 = gradient(hash(permutation, highX, lowY, highZ), fractionX - 1.0, fractionY,
                fractionZ - 1.0);
        double value011 = gradient(hash(permutation, lowX, highY, highZ), fractionX, fractionY - 1.0,
                fractionZ - 1.0);
        double value111 = gradient(hash(permutation, highX, highY, highZ), fractionX - 1.0, fractionY - 1.0,
                fractionZ - 1.0);
        double fadeX = fade(fractionX);
        double fadeY = fade(fractionY);
        double fadeZ = fade(fractionZ);
        return lerp(fadeZ, lerp(fadeY, lerp(fadeX, value000, value100), lerp(fadeX, value010, value110)),
                lerp(fadeY, lerp(fadeX, value001, value101), lerp(fadeX, value011, value111)));
    }

    /**
     * The hash of a lattice point, which the permutation table wraps.
     */
    private static int hash(int[] permutation, int latticeX, int latticeY, int latticeZ) {
        int horizontal = permutation[latticeX];
        int vertical = permutation[(horizontal + latticeY) & (PERMUTATION_SIZE - 1)];
        return permutation[(vertical + latticeZ) & (PERMUTATION_SIZE - 1)];
    }

    /**
     * The gradient of a hash, as Perlin's improved noise picks it from the twelve edge directions of a cube.
     */
    private static double gradient(int hash, double x, double y, double z) {
        int corner = hash & 15;
        double first = corner < 8 ? x : y;
        double second = corner < 4 ? y : (corner == 12 || corner == 14 ? x : z);
        double firstTerm = (corner & 1) == 0 ? first : -first;
        double secondTerm = (corner & 2) == 0 ? second : -second;
        return firstTerm + secondTerm;
    }

    /**
     * The quintic fade of Perlin's improved noise, which has a zero first and second derivative at both ends.
     */
    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    /**
     * The linear blend of two values, which is the second when the share is 1.
     */
    private static double lerp(double share, double first, double second) {
        return first + share * (second - first);
    }

    /**
     * The unsigned value of a texel of a channel, from 0 to 1.
     */
    private static double texel(byte[] texels, int channel, int x, int y, int z) {
        return (texels[CHANNELS * (x + y * SIZE + z * SIZE * SIZE) + channel] & 0xFF) / 255.0;
    }

    /**
     * Writes the rank of each value of the tile into the shape channel of the texture, so that every byte occurs the
     * same number of times and the coverage is the share of space that the values sort into. Ties go to the lower texel
     * index, and the low bits of each key hold that index.
     */
    private static void equalize(double[] values, byte[] texels) {
        long[] keys = new long[values.length];
        for (int texel = 0; texel < values.length; texel++) {
            double value = values[texel];
            int bits = value > 0.0 ? Float.floatToIntBits((float) value) : 0;
            keys[texel] = ((long) bits << 32) | texel;
        }
        Arrays.sort(keys);
        for (int rank = 0; rank < keys.length; rank++) {
            int texel = (int) keys[rank];
            texels[CHANNELS * texel + SHAPE_CHANNEL] = (byte) (rank * 256 / keys.length);
        }
    }

    /**
     * Writes the detail values into the detail channel of the texture normalized so that the smallest is 0 and the
     * largest is 255, rounded to nearest.
     */
    private static void normalizeDetail(double[] values, byte[] texels) {
        double smallest = Double.POSITIVE_INFINITY;
        double largest = Double.NEGATIVE_INFINITY;
        for (int texel = 0; texel < values.length; texel++) {
            smallest = Math.min(smallest, values[texel]);
            largest = Math.max(largest, values[texel]);
        }
        double scale = largest > smallest ? 255.0 / (largest - smallest) : 0.0;
        for (int texel = 0; texel < values.length; texel++) {
            double normalized = (values[texel] - smallest) * scale;
            int byteValue = (int) Math.round(normalized);
            texels[CHANNELS * texel + DETAIL_CHANNEL] = (byte) Math.max(0, Math.min(255, byteValue));
        }
    }

    private static double clampShare(double share) {
        if (!(share > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, share);
    }

    private static double clampCoverage(double coverage) {
        if (!(coverage >= MINIMUM_COVERAGE)) {
            return MINIMUM_COVERAGE;
        }
        return Math.min(1.0, coverage);
    }

    private static double clampErosion(double erosion) {
        if (!(erosion > 0.0)) {
            return 0.0;
        }
        return Math.min(MAXIMUM_EROSION, erosion);
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!(value > minimum)) {
            return minimum;
        }
        return Math.min(maximum, value);
    }
}
