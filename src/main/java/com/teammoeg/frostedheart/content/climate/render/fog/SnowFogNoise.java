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

import java.util.Random;

/**
 * The tiling noise texture of the fog and the filtered read that the walk and the shader take of it.
 */
public final class SnowFogNoise {
    /**
     * The texels per axis of the texture, which tiles on every axis.
     */
    public static final int SIZE = 64;
    /**
     * The lattice cells of the gradient noise per axis of a tile, so one lattice cell is 8 texels.
     */
    public static final int LATTICE_CELLS = 8;
    /**
     * The weight of the fine octave of the noise, whose tile is the blocks per texel of the setting times SIZE.
     */
    public static final double FINE_WEIGHT = 0.35;
    /**
     * The weight of the coarse octave, which with FINE_WEIGHT sums to 1.
     */
    public static final double COARSE_WEIGHT = 0.65;
    /**
     * How many times the fine octave's tile the coarse octave's tile is in blocks, which is not a whole number so that
     * the two tiles do not line up.
     */
    public static final double COARSE_TILE_FACTOR = 4.3;
    /**
     * The seed of the texture that the renderer builds.
     */
    public static final long SEED = 0x5EEDF06L;
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
     * The mask of a lattice index, so that the noise tiles on every axis.
     */
    private static final int LATTICE_MASK = LATTICE_CELLS - 1;

    private SnowFogNoise() {
    }

    /**
     * Fills the bytes of a texture with the noise of a seed, normalized so that its smallest value is 0 and its largest
     * 255.
     * @param seed the seed of the permutation table, so that the same seed builds the same texture
     * @param texels the array of SIZE cubed bytes to fill, indexed by x + y * SIZE + z * SIZE * SIZE
     * @throws IllegalArgumentException when the array does not hold SIZE cubed bytes
     */
    public static void build(long seed, byte[] texels) {
        int texelCount = SIZE * SIZE * SIZE;
        if (texels.length != texelCount) {
            throw new IllegalArgumentException("The noise texture has " + texels.length + " bytes instead of "
                    + texelCount);
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
        double smallest = Double.POSITIVE_INFINITY;
        double largest = Double.NEGATIVE_INFINITY;
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double value = latticeNoise(permutation, x, y, z);
                    smallest = Math.min(smallest, value);
                    largest = Math.max(largest, value);
                }
            }
        }
        double scale = largest > smallest ? 255.0 / (largest - smallest) : 0.0;
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double normalized = (latticeNoise(permutation, x, y, z) - smallest) * scale;
                    int byteValue = (int) Math.round(normalized);
                    texels[x + y * SIZE + z * SIZE * SIZE] = (byte) Math.max(0, Math.min(255, byteValue));
                }
            }
        }
    }

    /**
     * Reads the texture at a normalized coordinate with trilinear filtering and the repeat of the wrap, as OpenGL's
     * GL_LINEAR with GL_REPEAT reads it.
     * @param texels the array of SIZE cubed bytes of the texture
     * @param u the coordinate along x in tiles, where a whole number is the wrap
     * @param v the coordinate along y in tiles
     * @param w the coordinate along z in tiles
     * @return the filtered value from 0 to 1
     */
    public static double sample(byte[] texels, double u, double v, double w) {
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
        double value000 = texel(texels, lowX, lowY, lowZ);
        double value100 = texel(texels, highX, lowY, lowZ);
        double value010 = texel(texels, lowX, highY, lowZ);
        double value110 = texel(texels, highX, highY, lowZ);
        double value001 = texel(texels, lowX, lowY, highZ);
        double value101 = texel(texels, highX, lowY, highZ);
        double value011 = texel(texels, lowX, highY, highZ);
        double value111 = texel(texels, highX, highY, highZ);
        return lerp(fractionZ, lerp(fractionY, lerp(fractionX, value000, value100),
                lerp(fractionX, value010, value110)), lerp(fractionY, lerp(fractionX, value001, value101),
                lerp(fractionX, value011, value111)));
    }

    /**
     * The tile coordinate of a world position, which wraps into [0, 1).
     * @param world the world position in blocks on an axis
     * @param displacement the blocks the texture has moved along that axis
     * @param tileBlocks the blocks per tile
     * @return the coordinate in tiles, from 0 up to but not including 1
     */
    public static double tileCoordinate(double world, double displacement, double tileBlocks) {
        double coordinate = (world - displacement) / tileBlocks;
        return coordinate - Math.floor(coordinate);
    }

    /**
     * The gradient noise at the center of a texel, whose lattice indices wrap so that the noise tiles.
     */
    private static double latticeNoise(int[] permutation, int texelX, int texelY, int texelZ) {
        return gradientNoise(permutation, (texelX + 0.5) * LATTICE_CELLS / SIZE,
                (texelY + 0.5) * LATTICE_CELLS / SIZE, (texelZ + 0.5) * LATTICE_CELLS / SIZE);
    }

    /**
     * Perlin's improved gradient noise at a point in lattice units, with the quintic fade and the gradients of the
     * permutation table, periodic on every axis with LATTICE_CELLS.
     */
    private static double gradientNoise(int[] permutation, double x, double y, double z) {
        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        double fractionX = x - cellX;
        double fractionY = y - cellY;
        double fractionZ = z - cellZ;
        int lowX = cellX & LATTICE_MASK;
        int lowY = cellY & LATTICE_MASK;
        int lowZ = cellZ & LATTICE_MASK;
        int highX = (lowX + 1) & LATTICE_MASK;
        int highY = (lowY + 1) & LATTICE_MASK;
        int highZ = (lowZ + 1) & LATTICE_MASK;
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
     * The hash of a lattice point, which is periodic on every axis with LATTICE_CELLS.
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
     * The unsigned value of a texel, from 0 to 1.
     */
    private static double texel(byte[] texels, int x, int y, int z) {
        return (texels[x + y * SIZE + z * SIZE * SIZE] & 0xFF) / 255.0;
    }
}
