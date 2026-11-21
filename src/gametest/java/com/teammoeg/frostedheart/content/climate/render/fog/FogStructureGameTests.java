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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

/**
 * Checks the moving structures of the fog: the filtered read of the texture that OpenGL takes, the shape that the
 * coverage and the erosion give, the build of the texture against its seed, its bytes and its seams, the mean shape
 * against the histogram of its bytes and a dense average, the wind frame that keeps a world point and carries the
 * pattern with the wind, the density that the share, the range and the detail fade give, and that the warm calls
 * allocate nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogStructureGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEXTURE_SIZE = FogStructure.SIZE;
    private static final int LAST_TEXEL = TEXTURE_SIZE - 1;
    private static final int TEXEL_COUNT = TEXTURE_SIZE * TEXTURE_SIZE * TEXTURE_SIZE;
    private static final int TEXTURE_LENGTH = TEXEL_COUNT * FogStructure.CHANNELS;
    private static final String[] AXIS_NAMES = {"x", "y", "z"};
    private static final String[] PATTERN_NAMES = {"u", "v", "w"};
    private static final String[] CHANNEL_NAMES = {"shape", "detail"};
    private static final double TOLERANCE = 1.0e-12;
    private static final double MEAN_TOLERANCE = 1.0e-9;
    private static final double DENSITY_TOLERANCE = 1.0e-9;
    private static final double DENSE_MEAN_TOLERANCE = 0.02;
    private static final double COVERAGE_TOLERANCE = 0.01;
    private static final int DENSE_AXIS_COUNT = 80;
    private static final double DENSE_POINT_OFFSET = 0.37;
    private static final int FULL_BYTE = 255;
    private static final int SHAPE_BYTE = 153;
    private static final int DETAIL_BYTE = 102;
    private static final int WRAP_BYTE = 51;
    private static final int SMALLEST_SHAPE_INSIDE_DIFFERENCE = 2;
    private static final int LARGEST_SHAPE_INSIDE_DIFFERENCE = 128;
    private static final int SMALLEST_DETAIL_INSIDE_DIFFERENCE = 4;
    private static final int LARGEST_DETAIL_INSIDE_DIFFERENCE = 128;
    private static final double WRAP_SHARE_OF_INSIDE = 1.5;
    private static final double WRAP_MARGIN = 1.0;
    private static final double SMALLEST_DETAIL_MEAN = 64.0;
    private static final double LARGEST_DETAIL_MEAN = 192.0;
    private static final double COVERAGE_OF_THE_MEAN_CHECK = 0.35;
    private static final double EXPECTED_MEAN_OF_THE_COVERAGE_CHECK = 0.2625;
    private static final double EROSION_OF_THE_MEAN_CHECK = 0.5;
    private static final double[] MEAN_COVERAGES = {0.35, 0.6, 1.0};
    private static final double[][] DENSE_COVERAGES_AND_EROSIONS = {{0.35, 0.0}, {0.35, 0.5}, {0.6, 0.3}};
    private static final double FOLLOW_SIZE = 32.0;
    private static final double FOLLOW_STRETCH = 2.0;
    private static final double FOLLOW_TILE = FogStructure.TILE_CELLS * FOLLOW_SIZE;
    private static final double FOLLOW_ALONG = 1.0 / (FOLLOW_TILE * FOLLOW_STRETCH);
    private static final double FOLLOW_UP = 1.0 / (FOLLOW_TILE * FogStructure.VERTICAL_SQUASH);
    private static final double FOLLOW_ACROSS = 1.0 / FOLLOW_TILE;
    private static final double FIRST_EYE_X = 10.0;
    private static final double FIRST_EYE_Y = 64.0;
    private static final double FIRST_EYE_Z = -5.0;
    private static final double SECOND_EYE_X = 13.0;
    private static final double SECOND_EYE_Y = 65.0;
    private static final double SECOND_EYE_Z = -7.0;
    private static final double WIND_STEP = 6.0;
    private static final double QUARTER_TURN = Math.PI / 2.0;
    private static final double FOLLOW_POINT_X = 5.0;
    private static final double FOLLOW_POINT_Y = 0.5;
    private static final double FOLLOW_POINT_Z = 0.0;
    private static final double MOVED_POINT_X = 2.0;
    private static final double MOVED_POINT_Y = -0.5;
    private static final double MOVED_POINT_Z = 2.0;
    private static final double WIND_POINT_X = 8.0;
    private static final int TRAVEL_STEPS = 1000;
    private static final double TRAVEL_STEP_BLOCKS = 37.0;
    private static final double TRAVEL_U = 0.5703125;
    private static final double RESET_U = 0.015625;
    private static final double SMALLEST_SIZE_USED = 1.0;
    private static final double SMALLEST_STRETCH_USED = 0.5;
    private static final double LARGEST_SIZE_USED = 5000.0;
    private static final double LARGEST_STRETCH_USED = 20.0;
    private static final double STRUCTURE_SHARE = 0.75;
    private static final double STRUCTURE_COVERAGE = 0.5;
    private static final double STRUCTURE_EROSION = 0.5;
    private static final double STRUCTURE_RANGE = 100.0;
    private static final double MEAN_WITHOUT_EROSION = 0.4;
    private static final double MEAN_WITH_EROSION = 0.2;
    private static final double CONSTANT_SHAPE = 0.352;
    private static final double ERODED_SHAPE = 0.19;
    private static final double CLOSEST_DISTANCE = 20.0;
    private static final double FADING_DISTANCE = 48.0;
    private static final double PAST_FADE_DISTANCE = 70.0;
    private static final double HALF_RANGE_DISTANCE = 87.5;
    private static final double DIAGONAL_DISTANCE_X = 30.0;
    private static final double DIAGONAL_DISTANCE_Y = 40.0;
    private static final int ALLOCATION_WARMUP_ROUNDS = 100;
    private static final int ALLOCATION_ROUNDS = 1000;
    private static final int ALLOCATION_BEAN_WARMUPS = 20;
    private static final double WARM_EYE_X = 100.0;
    private static final double WARM_EYE_Y = 70.0;
    private static final double WARM_EYE_Z = -100.0;
    private static final double WARM_EYE_STEP = 0.37;
    private static final double WARM_DISPLACEMENT_X = 13.7;
    private static final double WARM_DISPLACEMENT_Z = -4.2;
    private static final double WARM_DIRECTION = 0.8;
    private static final double WARM_SIZE = 96.0;
    private static final double WARM_STRETCH = 2.0;
    private static final double WARM_SHARE = 0.75;
    private static final double WARM_COVERAGE = 0.35;
    private static final double WARM_EROSION = 0.5;
    private static final double WARM_RANGE = 96.0;
    private static final double NEW_COVERAGE_ONE = 0.45;
    private static final double NEW_COVERAGE_TWO = 0.55;
    private static final double[] WARM_SAMPLE_DISTANCES = {10.0, 50.0, 70.0, 90.0};

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sampleChannelFiltersLikeOpenGl(GameTestHelper helper) {
        byte[] texels = new byte[TEXTURE_LENGTH];
        texels[texelIndex(0, 0, 0, FogStructure.SHAPE_CHANNEL)] = (byte) FULL_BYTE;
        double center = 0.5 / TEXTURE_SIZE;
        assertChannel(helper, "the shape texel (0, 0, 0) at " + FULL_BYTE + ", at the center of that texel", texels,
                FogStructure.SHAPE_CHANNEL, 1.0, center, center, center);
        assertChannel(helper, "the empty detail channel at the center of the texel (0, 0, 0)", texels,
                FogStructure.DETAIL_CHANNEL, 0.0, center, center, center);
        assertChannel(helper, "the shape texel (0, 0, 0) at " + FULL_BYTE
                + ", halfway between the texels 63 and 0 along x", texels, FogStructure.SHAPE_CHANNEL, 0.5, 0.0,
                center, center);
        assertChannel(helper, "the shape texel (0, 0, 0) at " + FULL_BYTE
                + ", at the corner of the eight texels around the texel 0", texels, FogStructure.SHAPE_CHANNEL, 0.125,
                0.0, 0.0, 0.0);
        assertChannel(helper, "the shape texel (0, 0, 0) at " + FULL_BYTE
                + ", a whole tile along x and a whole tile back along y", texels, FogStructure.SHAPE_CHANNEL, 1.0,
                1.0 + center, -1.0 + center, center);
        texels[texelIndex(0, 0, 0, FogStructure.DETAIL_CHANNEL)] = (byte) FULL_BYTE;
        assertChannel(helper, "the detail texel (0, 0, 0) at " + FULL_BYTE + ", at the center of that texel", texels,
                FogStructure.DETAIL_CHANNEL, 1.0, center, center, center);
        assertChannel(helper, "the shape texel (0, 0, 0) at " + FULL_BYTE
                + " beside the detail texel (0, 0, 0) at " + FULL_BYTE + ", at the center of that texel", texels,
                FogStructure.SHAPE_CHANNEL, 1.0, center, center, center);
        texels[texelIndex(LAST_TEXEL, 0, 0, FogStructure.DETAIL_CHANNEL)] = (byte) WRAP_BYTE;
        assertChannel(helper, "the detail texel (0, 0, 0) at " + FULL_BYTE + " and the detail texel (63, 0, 0) at "
                + WRAP_BYTE + ", halfway between the two along x", texels, FogStructure.DETAIL_CHANNEL,
                0.5 * WRAP_BYTE / FULL_BYTE + 0.5, 0.0, center, center);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void shapeFollowsCoverageAndErosion(GameTestHelper helper) {
        double[][] cases = {
                {0.6, 0.0, 0.4, 0.0, 0.0},
                {0.5, 0.0, 0.4, 0.0, 0.0},
                {0.65, 0.0, 0.4, 0.0, 0.15625},
                {0.7, 0.0, 0.4, 0.0, 0.5},
                {0.8, 0.0, 0.4, 0.0, 1.0},
                {1.0, 0.0, 0.4, 0.0, 1.0},
                {0.7, 1.0, 0.4, 0.4, 0.1 / 0.6},
                {0.7, 0.5, 0.4, 0.4, 0.375},
                {0.8, 1.0, 0.4, 0.9, 1.0},
                {0.7, 1.0, 0.4, 0.9, 0.0},
                {0.7, 1.0, 0.4, 2.0, 0.0},
                {0.7, 0.5, 0.4, 2.0, 0.05 / 0.55},
                {0.97, 0.0, 0.0, 0.0, 0.896},
                {0.25, 0.0, 2.0, 0.0, 0.5},
                {0.97, 0.0, Double.NaN, 0.0, 0.896},
                {0.7, 1.0, 0.4, Double.NaN, 0.5},
                {0.7, Double.NaN, 0.4, 0.4, 0.5},
                {Double.NaN, 0.0, 0.4, 0.0, 0.0}
        };
        for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
            assertShape(helper, cases[caseIndex][0], cases[caseIndex][1], cases[caseIndex][2], cases[caseIndex][3],
                    cases[caseIndex][4]);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void buildIsDeterministicEqualizedAndTiles(GameTestHelper helper) {
        byte[] seeded = new byte[TEXTURE_LENGTH];
        byte[] repeated = new byte[TEXTURE_LENGTH];
        byte[] other = new byte[TEXTURE_LENGTH];
        FogStructure.build(FogStructure.SEED, seeded);
        FogStructure.build(FogStructure.SEED, repeated);
        FogStructure.build(FogStructure.SEED + 1L, other);
        helper.assertTrue(Arrays.equals(seeded, repeated), "Two builds with the seed " + FogStructure.SEED + " differ");
        int differingShapeBytes = 0;
        for (int texel = 0; texel < TEXEL_COUNT; texel++) {
            if (seeded[texel * FogStructure.CHANNELS + FogStructure.SHAPE_CHANNEL]
                    != other[texel * FogStructure.CHANNELS + FogStructure.SHAPE_CHANNEL]) {
                differingShapeBytes++;
            }
        }
        helper.assertTrue(differingShapeBytes * 2 >= TEXEL_COUNT, "The builds with the seeds " + FogStructure.SEED
                + " and " + (FogStructure.SEED + 1L) + " differ in " + differingShapeBytes + " of the " + TEXEL_COUNT
                + " shape bytes, fewer than the half");
        helper.assertTrue(rejectsBuild(new byte[100]), "The build of a byte[100] does not throw an "
                + "IllegalArgumentException");
        int[] shapeCounts = new int[FULL_BYTE + 1];
        for (int texel = 0; texel < TEXEL_COUNT; texel++) {
            shapeCounts[seeded[texel * FogStructure.CHANNELS + FogStructure.SHAPE_CHANNEL] & 0xFF]++;
        }
        for (int byteValue = 0; byteValue <= FULL_BYTE; byteValue++) {
            helper.assertTrue(shapeCounts[byteValue] == TEXEL_COUNT / (FULL_BYTE + 1), "The build with the seed "
                    + FogStructure.SEED + " holds the shape byte " + byteValue + " " + shapeCounts[byteValue]
                    + " times instead of " + (TEXEL_COUNT / (FULL_BYTE + 1)));
        }
        int smallestDetailByte = FULL_BYTE;
        int largestDetailByte = 0;
        long detailSum = 0L;
        for (int texel = 0; texel < TEXEL_COUNT; texel++) {
            int detailByte = seeded[texel * FogStructure.CHANNELS + FogStructure.DETAIL_CHANNEL] & 0xFF;
            smallestDetailByte = Math.min(smallestDetailByte, detailByte);
            largestDetailByte = Math.max(largestDetailByte, detailByte);
            detailSum += detailByte;
        }
        helper.assertTrue(smallestDetailByte == 0, "The build with the seed " + FogStructure.SEED
                + " has the smallest detail byte " + smallestDetailByte + " instead of 0");
        helper.assertTrue(largestDetailByte == FULL_BYTE, "The build with the seed " + FogStructure.SEED
                + " has the largest detail byte " + largestDetailByte + " instead of " + FULL_BYTE);
        double detailMean = (double) detailSum / TEXEL_COUNT;
        helper.assertTrue(detailMean >= SMALLEST_DETAIL_MEAN && detailMean <= LARGEST_DETAIL_MEAN, "The build with "
                + "the seed " + FogStructure.SEED + " has the mean detail byte " + detailMean + ", outside the "
                + SMALLEST_DETAIL_MEAN + " to " + LARGEST_DETAIL_MEAN);
        assertTilesWithoutASeam(helper, seeded, FogStructure.SHAPE_CHANNEL, SMALLEST_SHAPE_INSIDE_DIFFERENCE,
                LARGEST_SHAPE_INSIDE_DIFFERENCE);
        assertTilesWithoutASeam(helper, seeded, FogStructure.DETAIL_CHANNEL, SMALLEST_DETAIL_INSIDE_DIFFERENCE,
                LARGEST_DETAIL_INSIDE_DIFFERENCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void meanShapeMatchesTheHistogramAndADenseAverage(GameTestHelper helper) {
        byte[] texels = new byte[TEXTURE_LENGTH];
        FogStructure.build(FogStructure.SEED, texels);
        // Every shape byte occurs the same number of times, so the mean over the texels is the mean over the bytes.
        for (int coverageIndex = 0; coverageIndex < MEAN_COVERAGES.length; coverageIndex++) {
            double coverage = MEAN_COVERAGES[coverageIndex];
            double mean = FogStructure.meanShape(texels, coverage, 0.0);
            double histogramMean = histogramMeanShape(coverage, 0.0);
            assertCloseWithin(helper, "the mean shape at the coverage " + coverage + " without erosion against the "
                    + "histogram of the shape bytes", mean, histogramMean, MEAN_TOLERANCE);
        }
        double meanWithoutErosion = FogStructure.meanShape(texels, COVERAGE_OF_THE_MEAN_CHECK, 0.0);
        assertCloseWithin(helper, "the mean shape at the coverage " + COVERAGE_OF_THE_MEAN_CHECK + " without "
                + "erosion against the three quarters of the coverage", meanWithoutErosion,
                EXPECTED_MEAN_OF_THE_COVERAGE_CHECK, COVERAGE_TOLERANCE);
        double meanWithErosion = FogStructure.meanShape(texels, COVERAGE_OF_THE_MEAN_CHECK, EROSION_OF_THE_MEAN_CHECK);
        helper.assertTrue(meanWithErosion > 0.0, "The mean shape at the coverage " + COVERAGE_OF_THE_MEAN_CHECK
                + " and the erosion " + EROSION_OF_THE_MEAN_CHECK + " is " + meanWithErosion + " instead of above 0");
        helper.assertTrue(meanWithErosion < meanWithoutErosion, "The mean shape at the coverage "
                + COVERAGE_OF_THE_MEAN_CHECK + " and the erosion " + EROSION_OF_THE_MEAN_CHECK + " is "
                + meanWithErosion + " instead of below the " + meanWithoutErosion + " without erosion");
        for (int pairIndex = 0; pairIndex < DENSE_COVERAGES_AND_EROSIONS.length; pairIndex++) {
            double coverage = DENSE_COVERAGES_AND_EROSIONS[pairIndex][0];
            double erosion = DENSE_COVERAGES_AND_EROSIONS[pairIndex][1];
            double denseMean = denseMeanShape(texels, coverage, erosion);
            double mean = FogStructure.meanShape(texels, coverage, erosion);
            double relativeError = Math.abs(denseMean - mean) / mean;
            LOGGER.info("The dense average of the shape at the coverage " + coverage + " and the erosion " + erosion
                    + " is " + denseMean + " against the mean " + mean + ", a relative error of " + relativeError);
            helper.assertTrue(relativeError <= DENSE_MEAN_TOLERANCE, "The dense average of the shape at the coverage "
                    + coverage + " and the erosion " + erosion + " is " + denseMean + " against the mean " + mean
                    + ", a relative error of " + relativeError + " above " + DENSE_MEAN_TOLERANCE);
        }
        // At full weights the density is 1 + share * (S / mean - 1) of the shape, so a dense average of the shape
        // within 2% of the mean keeps the mean density within 2% of the share of 1.
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void followKeepsWorldPointsAndMovesWithTheWind(GameTestHelper helper) {
        FogStructure structure = new FogStructure();
        assertClose(helper, "the u of a fresh structure", structure.eyePatternU(), 0.0);
        assertClose(helper, "the v of a fresh structure", structure.eyePatternV(), 0.0);
        assertClose(helper, "the w of a fresh structure", structure.eyePatternW(), 0.0);
        assertClose(helper, "the along x of a fresh structure", structure.alongX(), 0.0);
        assertClose(helper, "the along z of a fresh structure", structure.alongZ(), 0.0);
        assertClose(helper, "the up y of a fresh structure", structure.upY(), 0.0);
        assertClose(helper, "the across x of a fresh structure", structure.acrossX(), 0.0);
        assertClose(helper, "the across z of a fresh structure", structure.acrossZ(), 0.0);
        structure.follow(FIRST_EYE_X, FIRST_EYE_Y, FIRST_EYE_Z, 0.0, 0.0, 0.0, FOLLOW_SIZE, FOLLOW_STRETCH);
        assertClose(helper, "the u of the eye at (10, 64, -5) along 0 with the size 32 and the stretch 2",
                structure.eyePatternU(), FIRST_EYE_X * FOLLOW_ALONG);
        assertClose(helper, "the v of the eye at (10, 64, -5)", structure.eyePatternV(),
                verticalPattern(FIRST_EYE_Y));
        assertClose(helper, "the w of the eye at (10, 64, -5)", structure.eyePatternW(),
                acrossPattern(FIRST_EYE_Z));
        assertClose(helper, "the along x of the tile of 128 blocks and the stretch 2", structure.alongX(),
                FOLLOW_ALONG);
        assertClose(helper, "the along z along 0", structure.alongZ(), 0.0);
        assertClose(helper, "the up y of the tile of 128 blocks and the vertical squash", structure.upY(),
                FOLLOW_UP);
        assertClose(helper, "the across x along 0", structure.acrossX(), 0.0);
        assertClose(helper, "the across z of the tile of 128 blocks", structure.acrossZ(), FOLLOW_ACROSS);
        double[] keptCoordinates = new double[AXIS_NAMES.length];
        for (int axis = 0; axis < AXIS_NAMES.length; axis++) {
            keptCoordinates[axis] = structure.patternCoordinate(axis, FOLLOW_POINT_X, FOLLOW_POINT_Y, FOLLOW_POINT_Z);
        }
        structure.follow(SECOND_EYE_X, SECOND_EYE_Y, SECOND_EYE_Z, 0.0, 0.0, 0.0, FOLLOW_SIZE, FOLLOW_STRETCH);
        assertClose(helper, "the u of the eye at (13, 65, -7) after the eye moved by the world (3, 1, -2)",
                structure.eyePatternU(), SECOND_EYE_X * FOLLOW_ALONG);
        for (int axis = 0; axis < AXIS_NAMES.length; axis++) {
            assertSamePatternCoordinate(helper, "the axis " + axis + " of the world point (5.0, 0.5, 0.0) after the "
                    + "eye moved", structure.patternCoordinate(axis, MOVED_POINT_X, MOVED_POINT_Y, MOVED_POINT_Z),
                    keptCoordinates[axis]);
        }
        structure.follow(SECOND_EYE_X, SECOND_EYE_Y, SECOND_EYE_Z, WIND_STEP, 0.0, 0.0, FOLLOW_SIZE, FOLLOW_STRETCH);
        assertClose(helper, "the u after the wind carried the pattern " + WIND_STEP + " blocks downwind",
                structure.eyePatternU(), (SECOND_EYE_X - WIND_STEP) * FOLLOW_ALONG);
        assertSamePatternCoordinate(helper, "the axis 0 of the world point (8.0, -0.5, 2.0) after the wind carried "
                + "the pattern " + WIND_STEP + " blocks downwind",
                structure.patternCoordinate(0, WIND_POINT_X, MOVED_POINT_Y, MOVED_POINT_Z), keptCoordinates[0]);
        structure.follow(SECOND_EYE_X, SECOND_EYE_Y, SECOND_EYE_Z, WIND_STEP, 0.0, QUARTER_TURN, FOLLOW_SIZE,
                FOLLOW_STRETCH);
        assertClose(helper, "the u after a quarter turn of the wind", structure.eyePatternU(),
                (SECOND_EYE_X - WIND_STEP) * FOLLOW_ALONG);
        assertClose(helper, "the v after a quarter turn of the wind", structure.eyePatternV(),
                verticalPattern(SECOND_EYE_Y));
        assertClose(helper, "the w after a quarter turn of the wind", structure.eyePatternW(),
                acrossPattern(SECOND_EYE_Z));
        assertClose(helper, "the along z after a quarter turn of the wind", structure.alongZ(), FOLLOW_ALONG);
        assertClose(helper, "the across x after a quarter turn of the wind", structure.acrossX(), -FOLLOW_ACROSS);
        assertClose(helper, "the along x after a quarter turn of the wind", structure.alongX(), 0.0);
        structure.follow(Double.NaN, SECOND_EYE_Y, SECOND_EYE_Z, WIND_STEP, 0.0, 0.0, FOLLOW_SIZE, FOLLOW_STRETCH);
        assertUnmoved(helper, "a NaN eye x", structure);
        structure.follow(SECOND_EYE_X, SECOND_EYE_Y, SECOND_EYE_Z, Double.POSITIVE_INFINITY, 0.0, 0.0, FOLLOW_SIZE,
                FOLLOW_STRETCH);
        assertUnmoved(helper, "an infinite displacement x", structure);
        structure.follow(SECOND_EYE_X, SECOND_EYE_Y, SECOND_EYE_Z, WIND_STEP, 0.0, Double.NaN, FOLLOW_SIZE,
                FOLLOW_STRETCH);
        assertClose(helper, "the along z after a NaN direction, which keeps the quarter turn", structure.alongZ(),
                FOLLOW_ALONG);
        FogStructure travellingStructure = new FogStructure();
        travellingStructure.follow(FIRST_EYE_X, FIRST_EYE_Y, FIRST_EYE_Z, 0.0, 0.0, 0.0, FOLLOW_SIZE, FOLLOW_STRETCH);
        double travelledV = travellingStructure.eyePatternV();
        double travelledW = travellingStructure.eyePatternW();
        for (int step = 1; step <= TRAVEL_STEPS; step++) {
            travellingStructure.follow(FIRST_EYE_X + TRAVEL_STEP_BLOCKS * step, FIRST_EYE_Y, FIRST_EYE_Z, 0.0, 0.0,
                    0.0, FOLLOW_SIZE, FOLLOW_STRETCH);
            assertEyePatternWraps(helper, "the step " + step + " of " + TRAVEL_STEPS, travellingStructure);
        }
        assertClose(helper, "the u after " + TRAVEL_STEPS + " steps of " + TRAVEL_STEP_BLOCKS + " blocks",
                travellingStructure.eyePatternU(), TRAVEL_U);
        assertClose(helper, "the v after " + TRAVEL_STEPS + " steps of " + TRAVEL_STEP_BLOCKS + " blocks",
                travellingStructure.eyePatternV(), travelledV);
        assertClose(helper, "the w after " + TRAVEL_STEPS + " steps of " + TRAVEL_STEP_BLOCKS + " blocks",
                travellingStructure.eyePatternW(), travelledW);
        travellingStructure.reset();
        travellingStructure.follow(FIRST_EYE_X, FIRST_EYE_Y, FIRST_EYE_Z, WIND_STEP, 0.0, 0.0, FOLLOW_SIZE,
                FOLLOW_STRETCH);
        assertClose(helper, "the u of the first follow after reset, anchored to the world",
                travellingStructure.eyePatternU(), RESET_U);
        FogStructure smallestStructure = new FogStructure();
        smallestStructure.follow(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, SMALLEST_SIZE_USED, SMALLEST_STRETCH_USED);
        assertClose(helper, "the along x of the size " + SMALLEST_SIZE_USED + " and the stretch "
                + SMALLEST_STRETCH_USED + ", clamped to the size 16 and the stretch 1", smallestStructure.alongX(),
                1.0 / 64.0);
        FogStructure largestStructure = new FogStructure();
        largestStructure.follow(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, LARGEST_SIZE_USED, LARGEST_STRETCH_USED);
        assertClose(helper, "the along x of the size " + LARGEST_SIZE_USED + " and the stretch "
                + LARGEST_STRETCH_USED + ", clamped to the size 1024 and the stretch 8", largestStructure.alongX(),
                1.0 / (4096.0 * 8.0));
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void densityFollowsShareRangeAndDetailFade(GameTestHelper helper) {
        byte[] texels = filledTexture(SHAPE_BYTE, DETAIL_BYTE);
        FogStructure structure = new FogStructure();
        structure.setTexture(texels);
        structure.setDensityWithMeans(STRUCTURE_SHARE, STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE,
                MEAN_WITHOUT_EROSION, MEAN_WITH_EROSION);
        helper.assertTrue(structure.isOn(), "The structure of the share " + STRUCTURE_SHARE + " and the range "
                + STRUCTURE_RANGE + " is off instead of on");
        assertDensity(helper, "the share " + STRUCTURE_SHARE + ", the coverage " + STRUCTURE_COVERAGE + ", the "
                + "erosion " + STRUCTURE_EROSION + " and the range " + STRUCTURE_RANGE, structure, 0.9625,
                CLOSEST_DISTANCE, 0.0, 0.0);
        assertDensity(helper, "the same structure, at the distance where the detail weight is 0.5", structure, 0.95,
                FADING_DISTANCE, 0.0, 0.0);
        assertDensity(helper, "the same structure, past the detail fade", structure, 0.91, PAST_FADE_DISTANCE, 0.0,
                0.0);
        assertDensity(helper, "the same structure, at half the range fade", structure, 0.955, HALF_RANGE_DISTANCE, 0.0,
                0.0);
        assertDensity(helper, "the same structure, at the distance 50 along the diagonal", structure, 0.9456712328767123,
                DIAGONAL_DISTANCE_X, DIAGONAL_DISTANCE_Y, 0.0);
        assertDensity(helper, "the same structure, at the distance of the range", structure, 1.0, STRUCTURE_RANGE, 0.0,
                0.0);
        assertDensity(helper, "the same structure, past the range", structure, 1.0, STRUCTURE_RANGE + 20.0, 0.0, 0.0);
        assertClose(helper, "the clear air density of the share " + STRUCTURE_SHARE, structure.clearAirDensity(),
                0.25);
        assertClose(helper, "the core density of the share " + STRUCTURE_SHARE + " and the mean with erosion "
                + MEAN_WITH_EROSION, structure.coreDensity(), 4.0);
        structure.setDensity(STRUCTURE_SHARE, STRUCTURE_COVERAGE, 0.0, STRUCTURE_RANGE);
        assertCloseWithin(helper, "the mean without erosion of the constant texture of (" + SHAPE_BYTE + ", "
                + DETAIL_BYTE + ") at the coverage " + STRUCTURE_COVERAGE, structure.meanWithoutErosion(),
                CONSTANT_SHAPE, MEAN_TOLERANCE);
        assertCloseWithin(helper, "the mean with erosion of the constant texture of (" + SHAPE_BYTE + ", "
                + DETAIL_BYTE + ") at the coverage " + STRUCTURE_COVERAGE + " without erosion",
                structure.meanWithErosion(), CONSTANT_SHAPE, MEAN_TOLERANCE);
        assertDensity(helper, "the structure without erosion", structure, 1.0, CLOSEST_DISTANCE, 0.0, 0.0);
        assertDensity(helper, "the structure without erosion, where the detail weight is 0.5", structure, 1.0,
                FADING_DISTANCE, 0.0, 0.0);
        structure.setDensity(STRUCTURE_SHARE, STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE);
        assertCloseWithin(helper, "the mean without erosion of the constant texture at the coverage "
                + STRUCTURE_COVERAGE, structure.meanWithoutErosion(), CONSTANT_SHAPE, MEAN_TOLERANCE);
        assertCloseWithin(helper, "the mean with erosion of the constant texture at the coverage " + STRUCTURE_COVERAGE
                + " and the erosion " + STRUCTURE_EROSION, structure.meanWithErosion(), ERODED_SHAPE, MEAN_TOLERANCE);
        assertDensity(helper, "the eroded structure at the full detail weight", structure, 1.0, CLOSEST_DISTANCE, 0.0,
                0.0);
        assertDensity(helper, "the eroded structure at the detail weight 0.5, where the blend of the means is not "
                + "exact", structure, 1.0249077490774908, FADING_DISTANCE, 0.0, 0.0);
        structure.setDensityWithMeans(0.0, STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE,
                MEAN_WITHOUT_EROSION, MEAN_WITH_EROSION);
        helper.assertTrue(!structure.isOn(), "The structure of the share 0 is on instead of off");
        assertDensity(helper, "the structure of the share 0", structure, 1.0, CLOSEST_DISTANCE, 0.0, 0.0);
        assertClose(helper, "the core density of the share 0", structure.coreDensity(), 1.0);
        structure.setDensityWithMeans(STRUCTURE_SHARE, STRUCTURE_COVERAGE, STRUCTURE_EROSION, 0.0,
                MEAN_WITHOUT_EROSION, MEAN_WITH_EROSION);
        helper.assertTrue(!structure.isOn(), "The structure of the range 0 is on instead of off");
        assertDensity(helper, "the structure of the range 0", structure, 1.0, CLOSEST_DISTANCE, 0.0, 0.0);
        assertClose(helper, "the core density of the range 0", structure.coreDensity(), 1.0);
        FogStructure untexturedStructure = new FogStructure();
        untexturedStructure.setDensityWithMeans(STRUCTURE_SHARE, STRUCTURE_COVERAGE, STRUCTURE_EROSION,
                STRUCTURE_RANGE, MEAN_WITHOUT_EROSION, MEAN_WITH_EROSION);
        helper.assertTrue(!untexturedStructure.isOn(), "The structure without a texture is on instead of off");
        assertDensity(helper, "the structure without a texture", untexturedStructure, 1.0, CLOSEST_DISTANCE, 0.0,
                0.0);
        structure.setDensityWithMeans(STRUCTURE_SHARE, STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE, 0.0,
                0.0);
        helper.assertTrue(!structure.isOn(), "The structure of the means 0 is on instead of off");
        assertDensity(helper, "the structure of the means 0", structure, 1.0, CLOSEST_DISTANCE, 0.0, 0.0);
        structure.setDensityWithMeans(1.5, STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE,
                MEAN_WITHOUT_EROSION, MEAN_WITH_EROSION);
        assertClose(helper, "the share of the share 1.5, clamped to 1", structure.share(), 1.0);
        structure.setDensityWithMeans(Double.NaN, STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE,
                MEAN_WITHOUT_EROSION, MEAN_WITH_EROSION);
        assertClose(helper, "the share of a NaN share, clamped to 0", structure.share(), 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmCallsAllocateNothing(GameTestHelper helper) {
        byte[] texels = new byte[TEXTURE_LENGTH];
        FogStructure.build(FogStructure.SEED, texels);
        FogStructure structure = new FogStructure();
        structure.setTexture(texels);
        warmCalls(structure, ALLOCATION_WARMUP_ROUNDS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm calls "
                    + "is not measured");
            helper.succeed();
            return;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            try {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            } catch (UnsupportedOperationException unsupported) {
                LOGGER.info("Thread allocated memory cannot be turned on", unsupported);
            }
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm calls is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        double sum = warmCalls(structure, ALLOCATION_ROUNDS);
        structure.setDensity(WARM_SHARE, NEW_COVERAGE_ONE, WARM_EROSION, WARM_RANGE);
        structure.setDensity(WARM_SHARE, NEW_COVERAGE_TWO, WARM_EROSION, WARM_RANGE);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm structure calls allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_ROUNDS + " warm rounds of the follow, the density and "
                + "the density reads, and the two new coverages that compute the means, allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * The work of the allocation test: a follow with a changing eye, the density with unchanged inputs and the four
     * density reads of distances from 10 to 90 blocks, without an assertion or a message of its own, so that it can be
     * measured.
     */
    private static double warmCalls(FogStructure structure, int roundCount) {
        double sum = 0.0;
        for (int round = 0; round < roundCount; round++) {
            structure.follow(WARM_EYE_X + round * WARM_EYE_STEP, WARM_EYE_Y, WARM_EYE_Z, WARM_DISPLACEMENT_X,
                    WARM_DISPLACEMENT_Z, WARM_DIRECTION, WARM_SIZE, WARM_STRETCH);
            structure.setDensity(WARM_SHARE, WARM_COVERAGE, WARM_EROSION, WARM_RANGE);
            for (int sampleIndex = 0; sampleIndex < WARM_SAMPLE_DISTANCES.length; sampleIndex++) {
                sum += structure.densityAt(WARM_SAMPLE_DISTANCES[sampleIndex], 0.0, 0.0);
            }
        }
        return sum;
    }

    /**
     * The v of a world y, which the up row of the tile of 32 blocks wraps into the unit interval.
     */
    private static double verticalPattern(double eyeY) {
        double coordinate = eyeY * FOLLOW_UP;
        return coordinate - Math.floor(coordinate);
    }

    /**
     * The w of a world z, which the across row of the tile of 32 blocks wraps into the unit interval.
     */
    private static double acrossPattern(double eyeZ) {
        double coordinate = eyeZ * FOLLOW_ACROSS;
        return coordinate - Math.floor(coordinate);
    }

    /**
     * The index of the byte of a channel of a texel, with x fastest, then y, then z.
     */
    private static int texelIndex(int x, int y, int z, int channel) {
        return FogStructure.CHANNELS * (x + y * TEXTURE_SIZE + z * TEXTURE_SIZE * TEXTURE_SIZE) + channel;
    }

    /**
     * The bytes per texel along an axis of the texture, in texels.
     */
    private static int axisStride(int axis) {
        if (axis == 0) {
            return 1;
        } else if (axis == 1) {
            return TEXTURE_SIZE;
        }
        return TEXTURE_SIZE * TEXTURE_SIZE;
    }

    /**
     * A texture of the given shape byte and detail byte everywhere.
     */
    private static byte[] filledTexture(int shapeByte, int detailByte) {
        byte[] texels = new byte[TEXTURE_LENGTH];
        for (int texel = 0; texel < TEXEL_COUNT; texel++) {
            texels[texel * FogStructure.CHANNELS + FogStructure.SHAPE_CHANNEL] = (byte) shapeByte;
            texels[texel * FogStructure.CHANNELS + FogStructure.DETAIL_CHANNEL] = (byte) detailByte;
        }
        return texels;
    }

    /**
     * Whether the build of a texture of the given length is rejected with an IllegalArgumentException.
     */
    private static boolean rejectsBuild(byte[] texels) {
        try {
            FogStructure.build(FogStructure.SEED, texels);
        } catch (IllegalArgumentException expectedException) {
            return true;
        }
        return false;
    }

    /**
     * Checks the largest neighbor differences of one channel along each axis inside the tile and across the wrap.
     */
    private static void assertTilesWithoutASeam(GameTestHelper helper, byte[] texels, int channel,
            int smallestInsideDifference, int largestInsideDifference) {
        int[] insideDifferences = new int[AXIS_NAMES.length];
        int[] wrapDifferences = new int[AXIS_NAMES.length];
        for (int axis = 0; axis < AXIS_NAMES.length; axis++) {
            int stride = axisStride(axis);
            for (int texel = 0; texel < TEXEL_COUNT; texel++) {
                int index = texel * FogStructure.CHANNELS + channel;
                if ((texel / stride) % TEXTURE_SIZE < LAST_TEXEL) {
                    insideDifferences[axis] = Math.max(insideDifferences[axis],
                            difference(texels, index, index + stride * FogStructure.CHANNELS));
                } else {
                    wrapDifferences[axis] = Math.max(wrapDifferences[axis],
                            difference(texels, index, index - LAST_TEXEL * stride * FogStructure.CHANNELS));
                }
            }
        }
        for (int axis = 0; axis < AXIS_NAMES.length; axis++) {
            int insideDifference = insideDifferences[axis];
            int wrapDifference = wrapDifferences[axis];
            helper.assertTrue(insideDifference >= smallestInsideDifference
                    && insideDifference <= largestInsideDifference, "The " + CHANNEL_NAMES[channel]
                    + " channel has the largest neighbor difference " + insideDifference + " along " + AXIS_NAMES[axis]
                    + " inside the tile, outside the " + smallestInsideDifference + " to " + largestInsideDifference);
            helper.assertTrue(wrapDifference <= WRAP_SHARE_OF_INSIDE * insideDifference + WRAP_MARGIN, "The "
                    + CHANNEL_NAMES[channel] + " channel jumps by " + wrapDifference + " along " + AXIS_NAMES[axis]
                    + " across the wrap, above the " + (WRAP_SHARE_OF_INSIDE * insideDifference + WRAP_MARGIN) + " of "
                    + WRAP_SHARE_OF_INSIDE + " times the " + insideDifference + " inside the tile plus " + WRAP_MARGIN);
        }
    }

    /**
     * The difference of two texels in unsigned bytes.
     */
    private static int difference(byte[] texels, int first, int second) {
        return Math.abs((texels[first] & 0xFF) - (texels[second] & 0xFF));
    }

    /**
     * The mean of the shape over the 256 equally frequent shape bytes of the built texture.
     */
    private static double histogramMeanShape(double coverage, double erosion) {
        double sum = 0.0;
        for (int byteValue = 0; byteValue <= FULL_BYTE; byteValue++) {
            sum += FogStructure.shape(byteValue / (double) FULL_BYTE, 0.0, coverage, erosion);
        }
        return sum / (FULL_BYTE + 1);
    }

    /**
     * The mean of the shape over a dense grid of points of the texture, the detail read at the detail repeat as the
     * density reads it.
     */
    private static double denseMeanShape(byte[] texels, double coverage, double erosion) {
        double sum = 0.0;
        for (int z = 0; z < DENSE_AXIS_COUNT; z++) {
            for (int y = 0; y < DENSE_AXIS_COUNT; y++) {
                for (int x = 0; x < DENSE_AXIS_COUNT; x++) {
                    double pointX = (x + DENSE_POINT_OFFSET) / DENSE_AXIS_COUNT;
                    double pointY = (y + DENSE_POINT_OFFSET) / DENSE_AXIS_COUNT;
                    double pointZ = (z + DENSE_POINT_OFFSET) / DENSE_AXIS_COUNT;
                    double base = FogStructure.sampleChannel(texels, FogStructure.SHAPE_CHANNEL, pointX, pointY,
                            pointZ);
                    double detail = FogStructure.sampleChannel(texels, FogStructure.DETAIL_CHANNEL,
                            FogStructure.DETAIL_REPEAT * pointX, FogStructure.DETAIL_REPEAT * pointY,
                            FogStructure.DETAIL_REPEAT * pointZ);
                    sum += FogStructure.shape(base, detail, coverage, erosion);
                }
            }
        }
        return sum / ((double) DENSE_AXIS_COUNT * DENSE_AXIS_COUNT * DENSE_AXIS_COUNT);
    }

    /**
     * Checks one filtered read of a channel against its expected value within TOLERANCE.
     */
    private static void assertChannel(GameTestHelper helper, String step, byte[] texels, int channel, double expected,
            double u, double v, double w) {
        double value = FogStructure.sampleChannel(texels, channel, u, v, w);
        double difference = Math.abs(value - expected);
        helper.assertTrue(difference <= TOLERANCE, "The " + CHANNEL_NAMES[channel] + " sample of " + step + " at ("
                + u + ", " + v + ", " + w + ") is " + value + " instead of " + expected + ", a difference of "
                + difference + " above " + TOLERANCE);
    }

    /**
     * Checks one shape of the given inputs against its expected value within TOLERANCE.
     */
    private static void assertShape(GameTestHelper helper, double base, double detail, double coverage,
            double erosion, double expected) {
        double shape = FogStructure.shape(base, detail, coverage, erosion);
        double difference = Math.abs(shape - expected);
        helper.assertTrue(difference <= TOLERANCE, "The shape of the base " + base + ", the detail " + detail
                + ", the coverage " + coverage + " and the erosion " + erosion + " is " + shape + " instead of "
                + expected + ", a difference of " + difference + " above " + TOLERANCE);
    }

    /**
     * Checks that the rows and the pattern of a structure are still the ones of the quarter turn.
     */
    private static void assertUnmoved(GameTestHelper helper, String step, FogStructure structure) {
        assertClose(helper, "the u after " + step, structure.eyePatternU(),
                (SECOND_EYE_X - WIND_STEP) * FOLLOW_ALONG);
        assertClose(helper, "the v after " + step, structure.eyePatternV(), verticalPattern(SECOND_EYE_Y));
        assertClose(helper, "the w after " + step, structure.eyePatternW(), acrossPattern(SECOND_EYE_Z));
        assertClose(helper, "the along x after " + step, structure.alongX(), 0.0);
        assertClose(helper, "the along z after " + step, structure.alongZ(), FOLLOW_ALONG);
        assertClose(helper, "the up y after " + step, structure.upY(), FOLLOW_UP);
        assertClose(helper, "the across x after " + step, structure.acrossX(), -FOLLOW_ACROSS);
        assertClose(helper, "the across z after " + step, structure.acrossZ(), 0.0);
    }

    /**
     * Checks that all three pattern coordinates of the eye wrap into the unit interval.
     */
    private static void assertEyePatternWraps(GameTestHelper helper, String step, FogStructure structure) {
        double[] coordinates = {structure.eyePatternU(), structure.eyePatternV(), structure.eyePatternW()};
        for (int axis = 0; axis < AXIS_NAMES.length; axis++) {
            helper.assertTrue(coordinates[axis] >= 0.0 && coordinates[axis] < 1.0, "The pattern coordinate "
                    + PATTERN_NAMES[axis] + " of " + step + " is " + coordinates[axis] + ", outside the unit interval");
        }
    }

    /**
     * Checks that a pattern coordinate is the kept one up to whole tiles.
     */
    private static void assertSamePatternCoordinate(GameTestHelper helper, String step, double actual,
            double expected) {
        double difference = actual - expected;
        difference -= Math.rint(difference);
        helper.assertTrue(Math.abs(difference) <= TOLERANCE, "The pattern coordinate of " + step + " is " + actual
                + " instead of the kept " + expected + " up to whole tiles, a difference of " + difference + " above "
                + TOLERANCE);
    }

    /**
     * Checks the density of an offset from the eye against its expected value within DENSITY_TOLERANCE.
     */
    private static void assertDensity(GameTestHelper helper, String step, FogStructure structure, double expected,
            double offsetX, double offsetY, double offsetZ) {
        double density = structure.densityAt(offsetX, offsetY, offsetZ);
        double difference = Math.abs(density - expected);
        helper.assertTrue(difference <= DENSITY_TOLERANCE, "The density of " + step + " at (" + offsetX + ", "
                + offsetY + ", " + offsetZ + ") is " + density + " instead of " + expected + ", a difference of "
                + difference + " above " + DENSITY_TOLERANCE);
    }

    /**
     * Checks a value against its expected value within TOLERANCE, naming the case.
     */
    private static void assertClose(GameTestHelper helper, String step, double actual, double expected) {
        assertCloseWithin(helper, step, actual, expected, TOLERANCE);
    }

    /**
     * Checks a value against its expected value within the given tolerance, naming the case.
     */
    private static void assertCloseWithin(GameTestHelper helper, String step, double actual, double expected,
            double tolerance) {
        double difference = Math.abs(actual - expected);
        helper.assertTrue(difference <= tolerance, "The value of " + step + " is " + actual + " instead of " + expected
                + ", a difference of " + difference + " above " + tolerance);
    }
}
