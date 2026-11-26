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

/**
 * Two-dimensional gradient noise from a permutation that only the seed decides, for FlakeShapeGenerator.
 */
public final class FlakeShapeNoise {
    private final int[] permutation;

    public FlakeShapeNoise(long seed) {
        this.permutation = new int[512];
        int[] base = new int[256];
        for (int index = 0; index < 256; index++) {
            base[index] = index;
        }
        long state = seed;
        for (int index = 255; index > 0; index--) {
            state = splitMix64(state);
            int swapIndex = (int) (Long.remainderUnsigned(state, index + 1));
            int swapped = base[index];
            base[index] = base[swapIndex];
            base[swapIndex] = swapped;
        }
        for (int index = 0; index < 512; index++) {
            this.permutation[index] = base[index & 255];
        }
    }

    /**
     * The noise at a point, from about -1.5 to 1.5.
     */
    public double noise2D(double coordinateX, double coordinateY) {
        int cellX = fastFloor(coordinateX) & 255;
        int cellY = fastFloor(coordinateY) & 255;
        double localX = coordinateX - fastFloor(coordinateX);
        double localY = coordinateY - fastFloor(coordinateY);

        double fadeX = fade(localX);
        double fadeY = fade(localY);

        int hashAA = permutation[permutation[cellX] + cellY];
        int hashAB = permutation[permutation[cellX] + cellY + 1];
        int hashBA = permutation[permutation[cellX + 1] + cellY];
        int hashBB = permutation[permutation[cellX + 1] + cellY + 1];

        double gradAA = gradient2D(hashAA, localX, localY);
        double gradBA = gradient2D(hashBA, localX - 1.0, localY);
        double gradAB = gradient2D(hashAB, localX, localY - 1.0);
        double gradBB = gradient2D(hashBB, localX - 1.0, localY - 1.0);

        double lerpBottom = lerp(fadeX, gradAA, gradBA);
        double lerpTop = lerp(fadeX, gradAB, gradBB);
        return lerp(fadeY, lerpBottom, lerpTop);
    }

    /**
     * The weighted mean of octaves of the noise, each at twice the frequency and half the weight of the one before.
     */
    public double fractalNoise2D(double coordinateX, double coordinateY, int octaves) {
        double total = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double weightSum = 0.0;
        for (int octave = 0; octave < octaves; octave++) {
            total += noise2D(coordinateX * frequency, coordinateY * frequency) * amplitude;
            weightSum += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        if (weightSum == 0.0) {
            return 0.0;
        }
        return total / weightSum;
    }

    private static long splitMix64(long value) {
        long mixed = value + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static int fastFloor(double value) {
        int truncated = (int) value;
        if (value < truncated) {
            return truncated - 1;
        }
        return truncated;
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double factor, double start, double end) {
        return start + factor * (end - start);
    }

    private static double gradient2D(int hash, double deltaX, double deltaY) {
        int masked = hash & 7;
        double first = masked < 4 ? deltaX : deltaY;
        double second = masked < 4 ? deltaY : deltaX;
        double signedFirst = (masked & 1) == 0 ? first : -first;
        double signedSecond = (masked & 2) == 0 ? second : -second;
        return signedFirst + 0.5 * signedSecond;
    }
}
