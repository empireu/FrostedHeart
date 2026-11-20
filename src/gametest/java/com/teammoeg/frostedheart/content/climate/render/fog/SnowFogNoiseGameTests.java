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

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/**
 * Checks the noise texture of the fog: the trilinear read with the wrap of the repeat, the tile coordinate of a world
 * position, the build of the texture from its seed against its spread of bytes, and that the texture has no seam where
 * it wraps.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFogNoiseGameTests {
    private static final double TOLERANCE = 1.0e-12;
    private static final int TEXTURE_SIZE = SnowFogNoise.SIZE;
    private static final int TEXTURE_LENGTH = TEXTURE_SIZE * TEXTURE_SIZE * TEXTURE_SIZE;
    private static final int FULL_BYTE = 255;
    private static final int WRAP_BYTE = 51;
    private static final double SMALLEST_MEAN = 96.0;
    private static final double LARGEST_MEAN = 160.0;
    private static final int SMALLEST_INSIDE_DIFFERENCE = 8;
    private static final int LARGEST_INSIDE_DIFFERENCE = 100;
    private static final double WRAP_SHARE_OF_INSIDE = 1.5;
    private static final double WRAP_MARGIN = 1.0;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sampleFiltersAndWrapsLikeOpenGl(GameTestHelper helper) {
        byte[] texels = new byte[TEXTURE_LENGTH];
        texels[0] = (byte) FULL_BYTE;
        double center = 0.5 / TEXTURE_SIZE;
        assertSample(helper, "the texture of the single texel (0, 0, 0) at " + FULL_BYTE, texels, 1.0, center, center,
                center);
        assertSample(helper, "the texture of the single texel (0, 0, 0) at " + FULL_BYTE
                + ", at the halfway point between the texels 63 and 0 along x", texels, 0.5, 0.0, center, center);
        assertSample(helper, "the texture of the single texel (0, 0, 0) at " + FULL_BYTE
                + ", at the corner of the eight texels around the texel 0", texels, 0.125, 0.0, 0.0, 0.0);
        assertSample(helper, "the texture of the single texel (0, 0, 0) at " + FULL_BYTE
                + ", a whole tile along x and a whole tile back along y", texels, 1.0, 1.0 + center, -1.0 + center,
                center);
        assertSample(helper, "the texture of the single texel (0, 0, 0) at " + FULL_BYTE
                + ", at the center of the empty texel 1", texels, 0.0, 1.5 / TEXTURE_SIZE, center, center);
        assertSample(helper, "the texture of the single texel (0, 0, 0) at " + FULL_BYTE
                + ", at the halfway point from the texel 0 toward the empty texel 1", texels, 0.5,
                1.0 / TEXTURE_SIZE, center, center);
        texels[TEXTURE_SIZE - 1] = (byte) WRAP_BYTE;
        assertSample(helper, "the texture of the texel (0, 0, 0) at " + FULL_BYTE + " and the texel (63, 0, 0) at "
                + WRAP_BYTE + ", at the halfway point between the two", texels,
                0.5 * WRAP_BYTE / FULL_BYTE + 0.5, 0.0, center, center);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void tileCoordinateWrapsIntoTheUnitInterval(GameTestHelper helper) {
        assertTileCoordinate(helper, "the world 130 at the displacement 2 on the tile 128", 0.0, 130.0, 2.0, 128.0);
        assertTileCoordinate(helper, "the world -1 at the displacement 0 on the tile 128", 0.9921875, -1.0, 0.0,
                128.0);
        assertTileCoordinate(helper, "the world 5 at the displacement 7 on the tile 4", 0.5, 5.0, 7.0, 4.0);
        assertTileCoordinate(helper, "the world 1000000.25 at the displacement 0 on the tile 1", 0.25, 1000000.25, 0.0,
                1.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void buildIsDeterministicAndSpansTheBytes(GameTestHelper helper) {
        byte[] seeded = new byte[TEXTURE_LENGTH];
        byte[] repeated = new byte[TEXTURE_LENGTH];
        byte[] other = new byte[TEXTURE_LENGTH];
        SnowFogNoise.build(SnowFogNoise.SEED, seeded);
        SnowFogNoise.build(SnowFogNoise.SEED, repeated);
        SnowFogNoise.build(SnowFogNoise.SEED + 1L, other);
        helper.assertTrue(Arrays.equals(seeded, repeated), "Two builds with the seed " + SnowFogNoise.SEED + " differ");
        int differingTexels = 0;
        int smallestByte = FULL_BYTE;
        int largestByte = 0;
        long byteSum = 0L;
        for (int texel = 0; texel < TEXTURE_LENGTH; texel++) {
            if (seeded[texel] != other[texel]) {
                differingTexels++;
            }
            int unsignedByte = seeded[texel] & 0xFF;
            smallestByte = Math.min(smallestByte, unsignedByte);
            largestByte = Math.max(largestByte, unsignedByte);
            byteSum += unsignedByte;
        }
        helper.assertTrue(differingTexels * 2 >= TEXTURE_LENGTH, "The builds with the seeds " + SnowFogNoise.SEED
                + " and " + (SnowFogNoise.SEED + 1L) + " differ in " + differingTexels + " of the " + TEXTURE_LENGTH
                + " texels, fewer than the half");
        helper.assertTrue(smallestByte == 0, "The build with the seed " + SnowFogNoise.SEED
                + " has the smallest unsigned byte " + smallestByte + " instead of 0");
        helper.assertTrue(largestByte == FULL_BYTE, "The build with the seed " + SnowFogNoise.SEED
                + " has the largest unsigned byte " + largestByte + " instead of " + FULL_BYTE);
        double mean = (double) byteSum / TEXTURE_LENGTH;
        helper.assertTrue(mean >= SMALLEST_MEAN && mean <= LARGEST_MEAN, "The build with the seed " + SnowFogNoise.SEED
                + " has the mean byte " + mean + ", outside the " + SMALLEST_MEAN + " to " + LARGEST_MEAN
                + " around the middle of the byte range");
        helper.assertTrue(rejectsBuild(new byte[100]), "The build of a byte[100] does not throw an "
                + "IllegalArgumentException");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void buildTilesWithoutASeam(GameTestHelper helper) {
        byte[] texels = new byte[TEXTURE_LENGTH];
        SnowFogNoise.build(SnowFogNoise.SEED, texels);
        int[] insideDifferences = new int[3];
        int[] wrapDifferences = new int[3];
        int lastTexel = TEXTURE_SIZE - 1;
        for (int z = 0; z < TEXTURE_SIZE; z++) {
            for (int y = 0; y < TEXTURE_SIZE; y++) {
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    int texel = texelIndex(x, y, z);
                    if (x < lastTexel) {
                        insideDifferences[0] = Math.max(insideDifferences[0], difference(texels, texel, texel + 1));
                    } else {
                        wrapDifferences[0] = Math.max(wrapDifferences[0],
                                difference(texels, texel, texelIndex(0, y, z)));
                    }
                    if (y < lastTexel) {
                        insideDifferences[1] = Math.max(insideDifferences[1],
                                difference(texels, texel, texel + TEXTURE_SIZE));
                    } else {
                        wrapDifferences[1] = Math.max(wrapDifferences[1],
                                difference(texels, texel, texelIndex(x, 0, z)));
                    }
                    if (z < lastTexel) {
                        insideDifferences[2] = Math.max(insideDifferences[2],
                                difference(texels, texel, texel + TEXTURE_SIZE * TEXTURE_SIZE));
                    } else {
                        wrapDifferences[2] = Math.max(wrapDifferences[2],
                                difference(texels, texel, texelIndex(x, y, 0)));
                    }
                }
            }
        }
        String[] axisNames = {"x", "y", "z"};
        for (int axis = 0; axis < axisNames.length; axis++) {
            int insideDifference = insideDifferences[axis];
            int wrapDifference = wrapDifferences[axis];
            helper.assertTrue(insideDifference >= SMALLEST_INSIDE_DIFFERENCE
                    && insideDifference <= LARGEST_INSIDE_DIFFERENCE, "The texture has the largest neighbor difference "
                    + insideDifference + " along " + axisNames[axis] + " inside the tile, outside the "
                    + SMALLEST_INSIDE_DIFFERENCE + " to " + LARGEST_INSIDE_DIFFERENCE + " of noise at 8 texels per "
                    + "lattice cell");
            helper.assertTrue(wrapDifference <= WRAP_SHARE_OF_INSIDE * insideDifference + WRAP_MARGIN, "The texture "
                    + "jumps by " + wrapDifference + " along " + axisNames[axis] + " across the wrap, above the "
                    + (WRAP_SHARE_OF_INSIDE * insideDifference + WRAP_MARGIN) + " of the " + WRAP_SHARE_OF_INSIDE
                    + " times the " + insideDifference + " inside the tile plus " + WRAP_MARGIN);
        }
        helper.succeed();
    }

    /**
     * Checks one filtered read of a texture against its expected value.
     */
    private static void assertSample(GameTestHelper helper, String step, byte[] texels, double expected, double u,
            double v, double w) {
        double value = SnowFogNoise.sample(texels, u, v, w);
        double difference = Math.abs(value - expected);
        helper.assertTrue(difference <= TOLERANCE, "The sample of " + step + " at (" + u + ", " + v + ", " + w
                + ") is " + value + " instead of " + expected + ", a difference of " + difference + " above "
                + TOLERANCE);
    }

    /**
     * Checks a tile coordinate against its expected value, which the unit interval holds.
     */
    private static void assertTileCoordinate(GameTestHelper helper, String step, double expected, double world,
            double displacement, double tileBlocks) {
        double coordinate = SnowFogNoise.tileCoordinate(world, displacement, tileBlocks);
        double difference = Math.abs(coordinate - expected);
        helper.assertTrue(difference <= TOLERANCE, "The tile coordinate of " + step + " is " + coordinate
                + " instead of " + expected + ", a difference of " + difference + " above " + TOLERANCE);
        helper.assertTrue(coordinate >= 0.0 && coordinate < 1.0, "The tile coordinate of " + step + " is "
                + coordinate + ", outside the unit interval");
    }

    /**
     * Whether the build of a texture of the given length is rejected with an IllegalArgumentException.
     */
    private static boolean rejectsBuild(byte[] texels) {
        try {
            SnowFogNoise.build(SnowFogNoise.SEED, texels);
        } catch (IllegalArgumentException expectedException) {
            return true;
        }
        return false;
    }

    /**
     * The index of a texel in the layout that SnowFogNoise.build fills, with x fastest, then y, then z.
     */
    private static int texelIndex(int x, int y, int z) {
        return x + y * TEXTURE_SIZE + z * TEXTURE_SIZE * TEXTURE_SIZE;
    }

    /**
     * The difference of two texels in unsigned bytes.
     */
    private static int difference(byte[] texels, int first, int second) {
        return Math.abs((texels[first] & 0xFF) - (texels[second] & 0xFF));
    }
}
