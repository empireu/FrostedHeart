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
import com.teammoeg.frostedheart.content.climate.render.sky.DustColumnWindow;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.Random;

/**
 * Checks the math of the height fog: the optical depth against a numeric integral of the extinction, the height factor
 * on both sides of its series switch, the edges of the optical depth, the culling distance against a brute force
 * search, the positions the culling distance hides, the rounding of the fog end, and the mock weather.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogModelGameTests {
    private static final double RELATIVE_TOLERANCE = 1.0e-4;
    private static final double SERIES_TOLERANCE = 1.0e-9;
    private static final double VALUE_TOLERANCE = 1.0e-12;
    private static final int INTEGRAL_STEPS = 10000;
    private static final double MAXIMUM_COMPARED_INTEGRAL = 40.0;
    private static final double BRUTE_FORCE_STEP = 0.25;
    private static final double BRUTE_FORCE_DISTANCE_TOLERANCE = 1.0;
    private static final double POSITION_TOLERANCE = 1.0e-9;
    private static final double POSITION_OPTICAL_DEPTH = 6.2344;
    private static final int BISECTION_ITERATIONS = 30;
    private static final int RANDOM_DISTANCE_COUNT = 200;
    private static final double RANDOM_DISTANCE_MAXIMUM = 1000.0;
    private static final long RANDOM_SEED = 20260101L;
    private static final double LOWEST_HEIGHT = -64.0;
    private static final double HIGHEST_HEIGHT = 320.0;
    private static final double SEA_LEVEL = 63.0;
    private static final double LONGEST_DISTANCE = 1024.0;
    private static final int BRUTE_FORCE_WALK_STEPS = 20000;
    private static final double BRUTE_FORCE_WALK_TOLERANCE = 2.0e-3;
    private static final double BRUTE_FORCE_WALK_RELATIVE_TOLERANCE = 1.0e-3;
    private static final double FULL_DEPTH_RELATIVE_TOLERANCE = 1.0e-9;
    private static final double CANOPY_FACE_TOLERANCE = 0.25;
    private static final int RANDOM_RAY_COUNT = 200;
    private static final double RANDOM_RAY_HORIZONTAL = 50.0;
    private static final double RANDOM_RAY_LOWEST_VERTICAL = -5.0;
    private static final double RANDOM_RAY_HIGHEST_VERTICAL = 10.0;
    /**
     * The first open heights of the window of the shelter tests in blocks, and the columns of its house and its canopy,
     * which the window's first column places at its own world column 0. The canopy hangs over the same z range as the
     * house.
     */
    private static final float FLOOR_HEIGHT = 60.0F;
    private static final float HOUSE_HEIGHT = 65.0F;
    private static final float CANOPY_HEIGHT = 70.0F;
    private static final int HOUSE_FIRST_X = 30;
    private static final int HOUSE_LAST_X = 34;
    private static final int CANOPY_FIRST_X = 40;
    private static final int CANOPY_LAST_X = 44;
    private static final int SHELTER_FIRST_Z = 30;
    private static final int SHELTER_LAST_Z = 34;
    private static final int WINDOW_ORIGIN_X = 0;
    private static final int WINDOW_ORIGIN_Z = 0;
    private static final int EYE_COLUMN_X = 32;
    private static final int EYE_COLUMN_Z = 32;
    private static final double EYE_FRACTION_X = 0.5;
    private static final double EYE_FRACTION_Z = 0.5;
    private static final double EYE_HEIGHT = 61.6;
    private static final double SYNTHETIC_VISIBILITY = 80.0;
    private static final double SYNTHETIC_REFERENCE_HEIGHT = 63.0;
    private static final double SYNTHETIC_HEIGHT_SCALE = 128.0;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void opticalDepthMatchesANumericIntegral(GameTestHelper helper) {
        double referenceExtinction = HeightFog.VISIBILITY_OPTICAL_DEPTH / 20.0;
        double[] eyeHeights = {40.0, 63.0, 150.0};
        double[] heightScales = {16.0, 128.0, 1024.0};
        double[] lengths = {5.0, 60.0, 300.0};
        double[] offsetShares = {-1.0, -0.5, 0.0, 0.3, 1.0};
        for (int eyeIndex = 0; eyeIndex < eyeHeights.length; eyeIndex++) {
            double eyeHeight = eyeHeights[eyeIndex];
            for (int scaleIndex = 0; scaleIndex < heightScales.length; scaleIndex++) {
                double heightScale = heightScales[scaleIndex];
                double eyeExtinction = HeightFog.extinctionAt(referenceExtinction, SEA_LEVEL, heightScale, eyeHeight);
                for (int lengthIndex = 0; lengthIndex < lengths.length; lengthIndex++) {
                    double length = lengths[lengthIndex];
                    for (int offsetIndex = 0; offsetIndex < offsetShares.length; offsetIndex++) {
                        double verticalOffset = offsetShares[offsetIndex] * length;
                        double integral = midPointIntegral(referenceExtinction, heightScale, eyeHeight, length,
                                verticalOffset);
                        if (integral > MAXIMUM_COMPARED_INTEGRAL) {
                            continue;
                        }
                        double depth = HeightFog.opticalDepth(eyeExtinction, length, verticalOffset, heightScale);
                        double relativeDifference = Math.abs(depth - integral) / Math.abs(integral);
                        helper.assertTrue(relativeDifference <= RELATIVE_TOLERANCE, "The depth at eye " + eyeHeight
                                + ", height scale " + heightScale + ", length " + length + " and vertical offset "
                                + verticalOffset + " is " + depth + " where the numeric integral is " + integral
                                + ", a relative difference of " + relativeDifference + " above " + RELATIVE_TOLERANCE);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void heightFactorIsContinuousAtTheSeriesSwitch(GameTestHelper helper) {
        double[] switchValues = {HeightFog.SERIES_LIMIT, -HeightFog.SERIES_LIMIT};
        for (int switchIndex = 0; switchIndex < switchValues.length; switchIndex++) {
            double switchValue = switchValues[switchIndex];
            double below = HeightFog.heightFactor(switchValue * (1.0 - SERIES_TOLERANCE));
            double above = HeightFog.heightFactor(switchValue * (1.0 + SERIES_TOLERANCE));
            double relativeDifference = Math.abs(below - above) / Math.max(Math.abs(below), Math.abs(above));
            helper.assertTrue(relativeDifference <= SERIES_TOLERANCE, "The height factor just below " + switchValue
                    + " is " + below + " where just above it is " + above + ", a relative difference of "
                    + relativeDifference + " above " + SERIES_TOLERANCE);
        }
        double atZero = HeightFog.heightFactor(0.0);
        helper.assertTrue(atZero == 1.0, "The height factor at 0 is " + atZero + " instead of exactly 1.0");
        double farBelow = HeightFog.heightFactor(-70.0);
        helper.assertTrue(Double.isFinite(farBelow) && farBelow > 0.0, "The height factor at -70 is " + farBelow
                + " instead of a positive finite value");
        double farAbove = HeightFog.heightFactor(70.0);
        helper.assertTrue(Double.isFinite(farAbove) && farAbove > 0.0, "The height factor at 70 is " + farAbove
                + " instead of a positive finite value");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void opticalDepthHandlesEdges(GameTestHelper helper) {
        double eyeExtinction = HeightFog.extinctionFromVisibility(20.0);
        double zeroLengthDepth = HeightFog.opticalDepth(eyeExtinction, 0.0, 0.0, 128.0);
        helper.assertTrue(zeroLengthDepth == 0.0, "The depth of a segment of length 0 is " + zeroLengthDepth
                + " instead of 0.0");
        double negativeExtinctionDepth = HeightFog.opticalDepth(-1.0, 100.0, 0.0, 128.0);
        helper.assertTrue(negativeExtinctionDepth == 0.0, "The depth at an extinction of -1 is "
                + negativeExtinctionDepth + " instead of 0.0");
        double hugeLengthDepth = HeightFog.opticalDepth(eyeExtinction, 1.0e9, 0.0, 128.0);
        helper.assertTrue(hugeLengthDepth == HeightFog.MAXIMUM_OPTICAL_DEPTH, "The depth of a segment of length 1e9 is "
                + hugeLengthDepth + " instead of " + HeightFog.MAXIMUM_OPTICAL_DEPTH);
        double uniformDepth = HeightFog.opticalDepth(eyeExtinction, 40.0, 60.0, 0.0);
        double expectedUniformDepth = eyeExtinction * 40.0;
        helper.assertTrue(uniformDepth == expectedUniformDepth, "The depth at a height scale of 0 is " + uniformDepth
                + " instead of the " + expectedUniformDepth + " of uniform fog");
        double[] badVisibilities = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY};
        for (int visibilityIndex = 0; visibilityIndex < badVisibilities.length; visibilityIndex++) {
            double badExtinction = HeightFog.extinctionFromVisibility(badVisibilities[visibilityIndex]);
            helper.assertTrue(badExtinction == 0.0, "The extinction of the visibility "
                    + badVisibilities[visibilityIndex] + " is " + badExtinction + " instead of 0.0");
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void cullingDistanceIsNeverShorterThanABruteForceSearch(GameTestHelper helper) {
        double[] eyeHeights = {-80.0, 40.0, 63.0, 100.0, 200.0, 330.0};
        double[] heightScales = {16.0, 128.0, 1024.0};
        double[] visibilities = {4.0, 20.0, 60.0, 500.0};
        int caseCount = 0;
        double largestExcess = Double.NEGATIVE_INFINITY;
        for (int eyeIndex = 0; eyeIndex < eyeHeights.length; eyeIndex++) {
            double eyeHeight = eyeHeights[eyeIndex];
            for (int scaleIndex = 0; scaleIndex < heightScales.length; scaleIndex++) {
                double heightScale = heightScales[scaleIndex];
                for (int visibilityIndex = 0; visibilityIndex < visibilities.length; visibilityIndex++) {
                    double visibility = visibilities[visibilityIndex];
                    double eyeExtinction = HeightFog.extinctionAt(HeightFog.extinctionFromVisibility(visibility),
                            SEA_LEVEL, heightScale, eyeHeight);
                    String caseName = "the eye at " + eyeHeight + " with height scale " + heightScale
                            + " and visibility " + visibility;
                    double distance = FogCullingDistance.opaqueDistance(eyeExtinction, eyeHeight, heightScale,
                            LOWEST_HEIGHT, HIGHEST_HEIGHT, LONGEST_DISTANCE);
                    double bruteDistance = bruteForceDistance(eyeExtinction, eyeHeight, heightScale);
                    if (Double.isInfinite(bruteDistance)) {
                        helper.assertTrue(Double.isInfinite(distance), "The distance of " + caseName + " is " + distance
                                + " where the brute force search finds no distance up to " + LONGEST_DISTANCE);
                    } else {
                        helper.assertTrue(distance >= bruteDistance - BRUTE_FORCE_STEP, "The distance of " + caseName
                                + " is " + distance + ", below the " + bruteDistance + " of the brute force search");
                        if (Double.isInfinite(distance)) {
                            helper.assertTrue(bruteDistance > LONGEST_DISTANCE - BRUTE_FORCE_DISTANCE_TOLERANCE,
                                    "The distance of " + caseName + " is infinite where the brute force search ends at "
                                            + bruteDistance + ", not near " + LONGEST_DISTANCE);
                        } else {
                            helper.assertTrue(distance <= bruteDistance + BRUTE_FORCE_DISTANCE_TOLERANCE,
                                    "The distance of " + caseName + " is " + distance + ", above the " + bruteDistance
                                            + " of the brute force search");
                            largestExcess = Math.max(largestExcess, distance - bruteDistance);
                        }
                    }
                    caseCount++;
                }
            }
        }
        LogUtils.getLogger().info("CullingDistance cases " + caseCount + " largest D - DBrute " + largestExcess);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void cullingDistanceHidesEveryCheckedPosition(GameTestHelper helper) {
        assertPositionsHidden(helper, 63.0, 128.0, 20.0);
        assertPositionsHidden(helper, 63.0, 128.0, 60.0);
        assertPositionsHidden(helper, 40.0, 16.0, 20.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fogEndRoundsUpAndHolds(GameTestHelper helper) {
        assertFogEnd(helper, 30.0, Double.POSITIVE_INFINITY, 32.0);
        assertFogEnd(helper, 3.0, Double.POSITIVE_INFINITY, 8.0);
        assertFogEnd(helper, 33.0, 32.0, 40.0);
        assertFogEnd(helper, 25.0, 32.0, 32.0);
        assertFogEnd(helper, 20.5, 32.0, 32.0);
        assertFogEnd(helper, 19.9, 32.0, 24.0);
        assertFogEnd(helper, Double.POSITIVE_INFINITY, 32.0, Double.POSITIVE_INFINITY);
        assertFogEnd(helper, Double.NaN, 32.0, Double.POSITIVE_INFINITY);
        double[] previousEnds = {Double.POSITIVE_INFINITY, 8.0, 64.0, 512.0};
        Random random = new Random(RANDOM_SEED);
        for (int distanceIndex = 0; distanceIndex < RANDOM_DISTANCE_COUNT; distanceIndex++) {
            double distance = random.nextDouble() * RANDOM_DISTANCE_MAXIMUM;
            for (int endIndex = 0; endIndex < previousEnds.length; endIndex++) {
                double end = FogCullingDistance.fogEnd(distance, previousEnds[endIndex]);
                helper.assertTrue(end >= distance, "The fog end of " + distance + " after " + previousEnds[endIndex]
                        + " is " + end + ", below the distance itself");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void mockWeatherFollowsTheHarnessWeathers(GameTestHelper helper) {
        double snowVisibility = 60.0;
        double blizzardVisibility = 20.0;
        double heightScale = 128.0;
        double snowExtinction = HeightFog.VISIBILITY_OPTICAL_DEPTH / snowVisibility;
        double blizzardExtinction = HeightFog.VISIBILITY_OPTICAL_DEPTH / blizzardVisibility;
        String[] weatherNames = {"clear", "snowfall", "blizzard", "snow", "storm"};
        double[] rainLevels = {0.0, 0.8, 0.8, 1.0, 1.0};
        double[] thunderLevels = {0.0, 0.0, 0.8, 0.0, 1.0};
        double[] snowfalls = {0.0, 1.0, 1.0, 1.0, 1.0};
        double[] storms = {0.0, 0.0, 1.0, 0.0, 1.0};
        double[] extinctions = {0.0, snowExtinction, blizzardExtinction, snowExtinction, blizzardExtinction};
        ClientMockWeather weather = new ClientMockWeather();
        for (int weatherIndex = 0; weatherIndex < weatherNames.length; weatherIndex++) {
            weather.update(rainLevels[weatherIndex], thunderLevels[weatherIndex], snowVisibility, blizzardVisibility,
                    heightScale);
            String weatherName = weatherNames[weatherIndex];
            assertClose(helper, "the snowfall of " + weatherName, weather.snowfallIntensity(),
                    snowfalls[weatherIndex]);
            assertClose(helper, "the storm of " + weatherName, weather.stormIntensity(), storms[weatherIndex]);
            assertClose(helper, "the extinction of " + weatherName, weather.referenceExtinction(),
                    extinctions[weatherIndex]);
        }
        weather.update(0.4, 0.4, snowVisibility, blizzardVisibility, heightScale);
        assertClose(helper, "the snowfall at rain 0.4", weather.snowfallIntensity(), 0.5);
        assertClose(helper, "the storm at thunder 0.4", weather.stormIntensity(), 0.5);
        assertClose(helper, "the extinction at rain and thunder 0.4", weather.referenceExtinction(),
                0.5 * (snowExtinction + blizzardExtinction) / 2.0);
        weather.update(0.2, 0.8, snowVisibility, blizzardVisibility, heightScale);
        assertClose(helper, "the storm at rain 0.2 and thunder 0.8", weather.stormIntensity(), 0.25);
        assertClose(helper, "the snowfall at rain 0.2", weather.snowfallIntensity(), 0.25);
        weather.update(Double.NaN, Double.NaN, snowVisibility, blizzardVisibility, heightScale);
        assertClose(helper, "the snowfall at a NaN rain level", weather.snowfallIntensity(), 0.0);
        assertClose(helper, "the storm at a NaN thunder level", weather.stormIntensity(), 0.0);
        assertClose(helper, "the extinction at NaN levels", weather.referenceExtinction(), 0.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, 0.0);
        assertClose(helper, "the height scale after 0", weather.heightScale(), 128.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, Double.NaN);
        assertClose(helper, "the height scale after NaN", weather.heightScale(), 128.0);
        ClientMockWeather freshWeather = new ClientMockWeather();
        assertClose(helper, "the snowfall before the first update", freshWeather.snowfallIntensity(), 0.0);
        assertClose(helper, "the storm before the first update", freshWeather.stormIntensity(), 0.0);
        assertClose(helper, "the extinction before the first update", freshWeather.referenceExtinction(), 0.0);
        assertClose(helper, "the height scale before the first update", freshWeather.heightScale(), 128.0);
        helper.succeed();
    }

    /**
     * Compares an optical depth with the midpoint sum of its extinction over the same segment in INTEGRAL_STEPS steps.
     */
    private static double midPointIntegral(double referenceExtinction, double heightScale, double eyeHeight,
            double length, double verticalOffset) {
        double step = length / INTEGRAL_STEPS;
        double risePerBlock = verticalOffset / length;
        double integral = 0.0;
        for (int stepIndex = 0; stepIndex < INTEGRAL_STEPS; stepIndex++) {
            double travelled = (stepIndex + 0.5) * step;
            integral += HeightFog.extinctionAt(referenceExtinction, SEA_LEVEL, heightScale,
                    eyeHeight + travelled * risePerBlock) * step;
        }
        return integral;
    }

    /**
     * Finds the culling distance by bisecting the least depth of a grid of heights in BRUTE_FORCE_STEP blocks.
     */
    private static double bruteForceDistance(double eyeExtinction, double eyeHeight, double heightScale) {
        if (!(gridLeastDepth(eyeExtinction, heightScale, eyeHeight, LONGEST_DISTANCE) >=
                FogCullingDistance.OPAQUE_OPTICAL_DEPTH)) {
            return Double.POSITIVE_INFINITY;
        }
        double nearestHiddenDistance = 0.0;
        double distance = LONGEST_DISTANCE;
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double middleDistance = 0.5 * (nearestHiddenDistance + distance);
            if (gridLeastDepth(eyeExtinction, heightScale, eyeHeight, middleDistance) >=
                    FogCullingDistance.OPAQUE_OPTICAL_DEPTH) {
                distance = middleDistance;
            } else {
                nearestHiddenDistance = middleDistance;
            }
        }
        return distance;
    }

    /**
     * The least optical depth of a height in steps of BRUTE_FORCE_STEP from the lowest to the highest build height and
     * of the same heights straight up or down at a horizontal distance of 0, at the given distance.
     */
    private static double gridLeastDepth(double eyeExtinction, double heightScale, double eyeHeight, double distance) {
        double leastVerticalOffset = LOWEST_HEIGHT - eyeHeight;
        double greatestVerticalOffset = HIGHEST_HEIGHT - eyeHeight;
        int stepCount = (int) Math.ceil((greatestVerticalOffset - leastVerticalOffset) / BRUTE_FORCE_STEP);
        double leastDepth = Double.POSITIVE_INFINITY;
        for (int stepIndex = 0; stepIndex <= stepCount; stepIndex++) {
            double verticalOffset = Math.min(greatestVerticalOffset,
                    leastVerticalOffset + stepIndex * BRUTE_FORCE_STEP);
            double wallLength = Math.sqrt(distance * distance + verticalOffset * verticalOffset);
            double wallDepth = HeightFog.opticalDepth(eyeExtinction, wallLength, verticalOffset, heightScale);
            leastDepth = Math.min(leastDepth, wallDepth);
            if (Math.abs(verticalOffset) >= distance) {
                double capDepth = HeightFog.opticalDepth(eyeExtinction, Math.abs(verticalOffset), verticalOffset,
                        heightScale);
                leastDepth = Math.min(leastDepth, capDepth);
            }
        }
        return leastDepth;
    }

    /**
     * Checks that every whole block position at a cylinder distance from the culling distance to 64 blocks past it is
     * hidden by at least POSITION_OPTICAL_DEPTH of fog.
     */
    private static void assertPositionsHidden(GameTestHelper helper, double eyeHeight, double heightScale,
            double visibility) {
        double eyeExtinction = HeightFog.extinctionAt(HeightFog.extinctionFromVisibility(visibility), SEA_LEVEL,
                heightScale, eyeHeight);
        double distance = FogCullingDistance.opaqueDistance(eyeExtinction, eyeHeight, heightScale, LOWEST_HEIGHT,
                HIGHEST_HEIGHT, LONGEST_DISTANCE);
        String caseName = "the eye at " + eyeHeight + " with height scale " + heightScale + " and visibility "
                + visibility;
        helper.assertTrue(Double.isFinite(distance), "The distance of " + caseName + " is " + distance
                + ", not a finite one to check positions past");
        int firstRadius = (int) Math.ceil(distance);
        int lastRadius = (int) Math.ceil(distance + 64.0);
        int firstOffset = (int) Math.ceil(LOWEST_HEIGHT - eyeHeight);
        int lastOffset = (int) Math.floor(HIGHEST_HEIGHT - eyeHeight);
        for (int radius = firstRadius; radius <= lastRadius; radius++) {
            for (int verticalOffset = firstOffset; verticalOffset <= lastOffset; verticalOffset++) {
                double length = Math.sqrt((double) radius * radius + (double) verticalOffset * verticalOffset);
                double depth = HeightFog.opticalDepth(eyeExtinction, length, verticalOffset, heightScale);
                helper.assertTrue(depth >= POSITION_OPTICAL_DEPTH - POSITION_TOLERANCE, "The position at radius "
                        + radius + " and vertical offset " + verticalOffset + " of " + caseName + ", at a distance of "
                        + distance + ", has a depth of " + depth + " below " + POSITION_OPTICAL_DEPTH);
            }
        }
    }

    /**
     * Checks the fog end of a distance after a previous end against the expected end.
     */
    private static void assertFogEnd(GameTestHelper helper, double distance, double previousEnd, double expectedEnd) {
        double end = FogCullingDistance.fogEnd(distance, previousEnd);
        helper.assertTrue(end == expectedEnd, "The fog end of " + distance + " after " + previousEnd + " is " + end
                + " instead of " + expectedEnd);
    }

    /**
     * Checks a value against its expected value within VALUE_TOLERANCE, naming the case.
     */
    private static void assertClose(GameTestHelper helper, String caseName, double actual, double expected) {
        double difference = Math.abs(actual - expected);
        helper.assertTrue(difference <= VALUE_TOLERANCE, "The value of " + caseName + " is " + actual
                + " instead of " + expected + ", a difference of " + difference + " above " + VALUE_TOLERANCE);
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 200)
    public static void openOpticalDepthMatchesABruteForceWalk(GameTestHelper helper) {
        float[] heights = houseWindow(HOUSE_HEIGHT);
        double[][] offsets = {
                {12.0, -1.0, 0.0},
                {10.0, 3.0, 1.0},
                {20.0, 0.5, 2.0},
                {-3.0, 0.0, 0.0},
                {0.0, 30.0, 0.0},
                {0.0, -1.0, 0.0},
                {40.0, 0.0, 40.0},
                {-45.0, 5.0, 10.0},
                {0.00001, 0.0, 6.0}};
        for (int caseIndex = 0; caseIndex < offsets.length; caseIndex++) {
            assertMatchesBruteForce(helper, describeOffset(offsets[caseIndex]), heights, offsets[caseIndex][0],
                    offsets[caseIndex][1], offsets[caseIndex][2]);
        }
        Random random = new Random(RANDOM_SEED);
        for (int rayIndex = 0; rayIndex < RANDOM_RAY_COUNT; rayIndex++) {
            double offsetX = random.nextDouble() * 2.0 * RANDOM_RAY_HORIZONTAL - RANDOM_RAY_HORIZONTAL;
            double offsetZ = random.nextDouble() * 2.0 * RANDOM_RAY_HORIZONTAL - RANDOM_RAY_HORIZONTAL;
            double offsetY = RANDOM_RAY_LOWEST_VERTICAL
                    + random.nextDouble() * (RANDOM_RAY_HIGHEST_VERTICAL - RANDOM_RAY_LOWEST_VERTICAL);
            assertMatchesBruteForce(helper, "the random offset (" + offsetX + ", " + offsetY + ", " + offsetZ + ")",
                    heights, offsetX, offsetY, offsetZ);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void openOpticalDepthIsZeroUnderOneRoof(GameTestHelper helper) {
        float[] heights = houseWindow(HOUSE_HEIGHT);
        double[][] offsets = {
                {2.0, 0.0, 0.0},
                {-2.0, 1.0, -2.0},
                {1.5, 2.0, 1.5},
                {0.0, 3.0, 0.0},
                {-2.0, -1.0, 2.0},
                {2.0, 3.0, 2.0}};
        for (int caseIndex = 0; caseIndex < offsets.length; caseIndex++) {
            double openDepth = shelterDepth(heights, offsets[caseIndex][0], offsets[caseIndex][1],
                    offsets[caseIndex][2]);
            helper.assertTrue(openDepth == 0.0, "The open optical depth of " + describeOffset(offsets[caseIndex])
                    + " under one roof is " + openDepth + " instead of exactly 0.0");
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void openOpticalDepthIsTheFullDepthInTheOpen(GameTestHelper helper) {
        double[][] offsets = {
                {10.0, 0.0, 0.0},
                {-20.0, 2.0, -20.0},
                {5.0, -1.0, 5.0},
                {0.0, 0.0, 30.0},
                {60.0, -1.0, 60.0}};
        assertOpenDepthIsFullDepth(helper, floorWindow(), offsets, "in the open");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void openOpticalDepthCountsUnreadAndOutsideColumnsAsOpen(GameTestHelper helper) {
        double[][] offsets = {
                {2.0, 0.0, 0.0},
                {-1.0, 2.0, 1.0},
                {2.0, -1.0, 2.0},
                {0.0, 0.5, 0.0}};
        assertOpenDepthIsFullDepth(helper, houseWindow(DustColumnWindow.CLOSED), offsets, "in the unread columns");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void canopyFacesTakeTheSameFogFromInside(GameTestHelper helper) {
        float[] heights = houseWindow(HOUSE_HEIGHT);
        double frontFaceDepth = shelterDepth(heights, 7.5, 5.0, 0.0);
        double deeperLeafDepth = shelterDepth(heights, 8.5, 5.2, 0.0);
        helper.assertTrue(frontFaceDepth > 0.0, "The open optical depth to the canopy's front face at the offset "
                + "(7.5, 5.0, 0.0) is " + frontFaceDepth + " instead of a positive depth");
        helper.assertTrue(deeperLeafDepth > 0.0, "The open optical depth to the leaf one block deeper at the offset "
                + "(8.5, 5.2, 0.0) is " + deeperLeafDepth + " instead of a positive depth");
        double largerDepth = Math.max(frontFaceDepth, deeperLeafDepth);
        double difference = Math.abs(frontFaceDepth - deeperLeafDepth);
        helper.assertTrue(difference < CANOPY_FACE_TOLERANCE * largerDepth, "The open optical depth to the canopy's "
                + "front face is " + frontFaceDepth + " where the leaf one block deeper is " + deeperLeafDepth
                + ", a difference of " + difference + " not below " + CANOPY_FACE_TOLERANCE * largerDepth
                + " of the larger");
        helper.succeed();
    }

    /**
     * The window of the shelter tests, SIZE by SIZE columns at the floor height.
     */
    private static float[] floorWindow() {
        float[] heights = new float[DustColumnWindow.COLUMNS];
        Arrays.fill(heights, FLOOR_HEIGHT);
        return heights;
    }

    /**
     * The window of the shelter tests with the house around the eye at the given height and the canopy to the east of
     * it.
     */
    private static float[] houseWindow(float houseHeight) {
        float[] heights = floorWindow();
        fillColumns(heights, HOUSE_FIRST_X, HOUSE_LAST_X, SHELTER_FIRST_Z, SHELTER_LAST_Z, houseHeight);
        fillColumns(heights, CANOPY_FIRST_X, CANOPY_LAST_X, SHELTER_FIRST_Z, SHELTER_LAST_Z, CANOPY_HEIGHT);
        return heights;
    }

    /**
     * Sets the first open height of every column in the given world ranges, which the window of the shelter tests holds
     * at its own texels.
     */
    private static void fillColumns(float[] heights, int firstX, int lastX, int firstZ, int lastZ, float height) {
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                heights[DustColumnWindow.texelIndex(x, z)] = height;
            }
        }
    }

    /**
     * The offset of a segment as the shelter tests name it.
     */
    private static String describeOffset(double[] offset) {
        return "the offset (" + offset[0] + ", " + offset[1] + ", " + offset[2] + ")";
    }

    /**
     * The open optical depth of a segment from the eye of the shelter tests, in optical depths.
     */
    private static double shelterDepth(float[] heights, double offsetX, double offsetY, double offsetZ) {
        return ShelterRay.openOpticalDepth(heights, WINDOW_ORIGIN_X, WINDOW_ORIGIN_Z, EYE_COLUMN_X, EYE_COLUMN_Z,
                EYE_FRACTION_X, EYE_FRACTION_Z, EYE_HEIGHT, offsetX, offsetY, offsetZ, eyeExtinction(),
                SYNTHETIC_HEIGHT_SCALE);
    }

    /**
     * The extinction per block at the eye of the shelter tests, which the visibility and height scale of the tests give
     * it at that height.
     */
    private static double eyeExtinction() {
        return HeightFog.extinctionAt(HeightFog.VISIBILITY_OPTICAL_DEPTH / SYNTHETIC_VISIBILITY,
                SYNTHETIC_REFERENCE_HEIGHT, SYNTHETIC_HEIGHT_SCALE, EYE_HEIGHT);
    }

    /**
     * The optical depth of a whole segment from the eye of the shelter tests, in optical depths.
     */
    private static double fullOpticalDepth(double offsetX, double offsetY, double offsetZ) {
        double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        return HeightFog.opticalDepth(eyeExtinction(), length, offsetY, SYNTHETIC_HEIGHT_SCALE);
    }

    /**
     * The open optical depth of a segment from the eye of the shelter tests, summed over BRUTE_FORCE_WALK_STEPS equal
     * steps of its parameter, where each step at an open middle counts the height fog between its own two ends.
     */
    private static double bruteForceOpenDepth(float[] heights, double offsetX, double offsetY, double offsetZ) {
        double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        double extinctionAtEye = eyeExtinction();
        double stepShare = 1.0 / BRUTE_FORCE_WALK_STEPS;
        double openDepth = 0.0;
        for (int stepIndex = 0; stepIndex < BRUTE_FORCE_WALK_STEPS; stepIndex++) {
            double stepStart = stepIndex * stepShare;
            double stepEnd = stepStart + stepShare;
            double middle = 0.5 * (stepStart + stepEnd);
            if (isOpenAt(heights, middle, offsetX, offsetY, offsetZ)) {
                openDepth += HeightFog.opticalDepth(extinctionAtEye, stepEnd * length, stepEnd * offsetY,
                        SYNTHETIC_HEIGHT_SCALE)
                        - HeightFog.opticalDepth(extinctionAtEye, stepStart * length, stepStart * offsetY,
                                SYNTHETIC_HEIGHT_SCALE);
            }
        }
        return openDepth;
    }

    /**
     * Whether the place at the given parameter of a segment from the eye of the shelter tests is open, from its own
     * column and height alone.
     */
    private static boolean isOpenAt(float[] heights, double s, double offsetX, double offsetY, double offsetZ) {
        double pointY = EYE_HEIGHT + s * offsetY;
        int columnX = (int) Math.floor(EYE_COLUMN_X + EYE_FRACTION_X + s * offsetX);
        int columnZ = (int) Math.floor(EYE_COLUMN_Z + EYE_FRACTION_Z + s * offsetZ);
        if (columnX < 0 || columnX >= DustColumnWindow.SIZE || columnZ < 0 || columnZ >= DustColumnWindow.SIZE) {
            return true;
        }
        float firstOpenY = heights[DustColumnWindow.texelIndex(WINDOW_ORIGIN_X + columnX, WINDOW_ORIGIN_Z + columnZ)];
        return firstOpenY >= 0.5F * DustColumnWindow.CLOSED || pointY >= firstOpenY;
    }

    /**
     * Checks the open optical depth of a segment against the brute force walk, within its absolute and relative
     * tolerances.
     */
    private static void assertMatchesBruteForce(GameTestHelper helper, String caseName, float[] heights,
            double offsetX, double offsetY, double offsetZ) {
        double openDepth = shelterDepth(heights, offsetX, offsetY, offsetZ);
        double bruteDepth = bruteForceOpenDepth(heights, offsetX, offsetY, offsetZ);
        double allowed = BRUTE_FORCE_WALK_TOLERANCE + BRUTE_FORCE_WALK_RELATIVE_TOLERANCE * Math.abs(bruteDepth);
        double difference = Math.abs(openDepth - bruteDepth);
        helper.assertTrue(difference <= allowed, "The open optical depth of " + caseName + " is " + openDepth
                + " where the brute force walk gives " + bruteDepth + ", a difference of " + difference + " above "
                + allowed);
    }

    /**
     * Checks that the open optical depth of every segment is the optical depth of the whole segment, which holds when
     * every place on it is open.
     */
    private static void assertOpenDepthIsFullDepth(GameTestHelper helper, float[] heights, double[][] offsets,
            String caseName) {
        for (int caseIndex = 0; caseIndex < offsets.length; caseIndex++) {
            double openDepth = shelterDepth(heights, offsets[caseIndex][0], offsets[caseIndex][1],
                    offsets[caseIndex][2]);
            double fullDepth = fullOpticalDepth(offsets[caseIndex][0], offsets[caseIndex][1], offsets[caseIndex][2]);
            double difference = Math.abs(openDepth - fullDepth);
            helper.assertTrue(difference <= FULL_DEPTH_RELATIVE_TOLERANCE * Math.abs(fullDepth), "The open optical "
                    + "depth of " + describeOffset(offsets[caseIndex]) + " " + caseName + " is " + openDepth
                    + " where the full depth is " + fullDepth + ", a difference of " + difference + " above "
                    + FULL_DEPTH_RELATIVE_TOLERANCE + " of the full depth");
        }
    }
}
