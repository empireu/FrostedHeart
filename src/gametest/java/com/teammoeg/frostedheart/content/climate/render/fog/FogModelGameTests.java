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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Random;

/**
 * Checks the math of the height fog: the optical depth against a numeric integral of the extinction, the height factor
 * on both sides of its series switch, the edges of the optical depth, the optical depth of the sky and the tail that
 * follows a start on the same ray, the culling distance against a brute force search, the positions the culling
 * distance hides, the culling distance below the highest surface of a blizzard, the rounding of the fog end, the mock
 * weather, and the color of a white face at full sky light.
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
    private static final double BLIZZARD_VISIBILITY = 80.0;
    private static final double SNOW_VISIBILITY = 300.0;
    private static final double BLIZZARD_EYE_HEIGHT = 70.0;
    private static final double BLIZZARD_HEIGHT_SCALE = 128.0;
    private static final double HIGHEST_SURFACE = 120.0;
    private static final double NEAR_FAR_PLANE = 256.0;
    private static final double NEAREST_BLIZZARD_DISTANCE = 140.0;
    private static final double FARTHEST_BLIZZARD_DISTANCE = 170.0;
    private static final double NEAREST_SNOW_DISTANCE = 550.0;
    private static final double FARTHEST_SNOW_DISTANCE = 650.0;
    private static final double WIND_DIRECTION_BASE = 1.3;
    private static final double WIND_DIRECTION_TURN = 2.0e-4;
    private static final double WIND_DIRECTION_LONGEST_TICKS = 200000.0;
    private static final double WIND_DIRECTION_TICK_STEP = 1000.0;
    private static final double STRUCTURE_SNOW_SHARE = 0.3;
    private static final double STRUCTURE_BLIZZARD_SHARE = 0.75;
    private static final double CLEAR_AIR_EXTINCTION = 0.05;
    private static final double CLEAR_AIR_EYE_HEIGHT = 64.0;
    private static final double CLEAR_AIR_MINIMUM_HEIGHT = 0.0;
    private static final double CLEAR_AIR_MAXIMUM_HEIGHT = 64.0;
    private static final double CLEAR_AIR_MAXIMUM_DISTANCE = 1000.0;
    private static final double CLEAR_AIR_DISTANCE_TOLERANCE = 1.0e-5;
    private static final double BLIZZARD_MAXIMUM_DISTANCE = 512.0;
    /**
     * The extinction of the blizzard's fog at the eye, which is the visibility optical depth over its visibility of 80
     * blocks.
     */
    private static final double SKY_EYE_EXTINCTION = HeightFog.VISIBILITY_OPTICAL_DEPTH / BLIZZARD_VISIBILITY;
    private static final double SKY_HEIGHT_SCALE = 128.0;
    /**
     * The optical depth of the sky at the direction 1.0, which is SKY_EYE_EXTINCTION * SKY_HEIGHT_SCALE.
     */
    private static final double FULL_SKY_OPTICAL_DEPTH = 6.2592;
    private static final double HALF_SKY_OPTICAL_DEPTH = 12.5184;
    private static final double GRAZING_SKY_DIRECTION = 0.01;
    private static final double GRAZING_SKY_OPTICAL_DEPTH = 625.92;
    private static final double SKY_TAIL_START = 96.0;
    private static final double SKY_TAIL_OPTICAL_DEPTH = 2.956636726916559;
    /**
     * The optical depth of the 96 block segment along the direction 1.0, which the tail from its end completes.
     */
    private static final double SKY_SEGMENT_OPTICAL_DEPTH = 3.302563273083441;
    private static final double FAR_SKY_TAIL_START = 1.0e6;
    private static final int WHITE_FACE_SKY_FOURTEEN = 0xFFB0A090;
    private static final int WHITE_FACE_SKY_FIFTEEN = 0xFFC0B0A0;
    private static final int TRANSPARENT_SKY_FOURTEEN = 0x00B0A090;
    private static final int TRANSPARENT_SKY_FIFTEEN = 0x12C0B0A0;
    private static final int WHITE_LIGHTMAP_PIXEL = 0xFFFFFFFF;
    private static final int BLACK_LIGHTMAP_PIXEL = 0xFF000000;
    private static final double WHITE_FACE_RED = 0.596078431372549;
    private static final double WHITE_FACE_GREEN = 0.6588235294117647;
    private static final double WHITE_FACE_BLUE = 0.7215686274509804;
    private static final double COLOR_TOLERANCE = 1.0e-6;
    private static final int COLOR_CHANNEL_COUNT = 3;
    private static final int ALLOCATION_WARMUP_CALLS = 20000;
    private static final int ALLOCATION_CALLS = 1000;
    private static final int ALLOCATION_BEAN_WARMUPS = 20;

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
    public static void cullingDistanceIsFiniteBelowTheHighestSurfaceInABlizzard(GameTestHelper helper) {
        double blizzardExtinctionAtEye = HeightFog.extinctionAt(
                HeightFog.extinctionFromVisibility(BLIZZARD_VISIBILITY), SEA_LEVEL, BLIZZARD_HEIGHT_SCALE,
                BLIZZARD_EYE_HEIGHT);
        double blizzardDistance = FogCullingDistance.opaqueDistance(blizzardExtinctionAtEye, BLIZZARD_EYE_HEIGHT,
                BLIZZARD_HEIGHT_SCALE, LOWEST_HEIGHT, HIGHEST_SURFACE, NEAR_FAR_PLANE);
        helper.assertTrue(Double.isFinite(blizzardDistance), "The culling distance of the blizzard of visibility "
                + BLIZZARD_VISIBILITY + " below the highest surface " + HIGHEST_SURFACE + " is " + blizzardDistance
                + " instead of a finite one");
        helper.assertTrue(blizzardDistance >= NEAREST_BLIZZARD_DISTANCE
                && blizzardDistance <= FARTHEST_BLIZZARD_DISTANCE, "The culling distance of the blizzard is "
                        + blizzardDistance + " instead of one from " + NEAREST_BLIZZARD_DISTANCE + " to "
                        + FARTHEST_BLIZZARD_DISTANCE);
        double snowExtinctionAtEye = HeightFog.extinctionAt(
                HeightFog.extinctionFromVisibility(SNOW_VISIBILITY), SEA_LEVEL, BLIZZARD_HEIGHT_SCALE,
                BLIZZARD_EYE_HEIGHT);
        double shortSnowDistance = FogCullingDistance.opaqueDistance(snowExtinctionAtEye, BLIZZARD_EYE_HEIGHT,
                BLIZZARD_HEIGHT_SCALE, LOWEST_HEIGHT, HIGHEST_SURFACE, NEAR_FAR_PLANE);
        helper.assertTrue(Double.isInfinite(shortSnowDistance) && shortSnowDistance > 0.0, "The culling distance of "
                + "the snow of visibility " + SNOW_VISIBILITY + " below the highest surface " + HIGHEST_SURFACE
                + " is " + shortSnowDistance + " instead of positive infinity at a far plane of " + NEAR_FAR_PLANE);
        double longSnowDistance = FogCullingDistance.opaqueDistance(snowExtinctionAtEye, BLIZZARD_EYE_HEIGHT,
                BLIZZARD_HEIGHT_SCALE, LOWEST_HEIGHT, HIGHEST_SURFACE, LONGEST_DISTANCE);
        helper.assertTrue(Double.isFinite(longSnowDistance), "The culling distance of the snow of visibility "
                + SNOW_VISIBILITY + " below the highest surface " + HIGHEST_SURFACE + " is " + longSnowDistance
                + " instead of a finite one at a far plane of " + LONGEST_DISTANCE);
        helper.assertTrue(longSnowDistance >= NEAREST_SNOW_DISTANCE && longSnowDistance <= FARTHEST_SNOW_DISTANCE,
                "The culling distance of the snow is " + longSnowDistance + " instead of one from "
                        + NEAREST_SNOW_DISTANCE + " to " + FARTHEST_SNOW_DISTANCE);
        LogUtils.getLogger().info("The culling distance below the highest surface " + HIGHEST_SURFACE + " is "
                + blizzardDistance + " in the blizzard and " + longSnowDistance + " in the snow");
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
        double snowDecayLength = 1.5;
        double blizzardDecayLength = 4.0;
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
                    heightScale, snowDecayLength, blizzardDecayLength, 50.0, 12.0, 0.0, 0.3, 0.75);
            String weatherName = weatherNames[weatherIndex];
            assertClose(helper, "the snowfall of " + weatherName, weather.snowfallIntensity(),
                    snowfalls[weatherIndex]);
            assertClose(helper, "the storm of " + weatherName, weather.stormIntensity(), storms[weatherIndex]);
            assertClose(helper, "the extinction of " + weatherName, weather.referenceExtinction(),
                    extinctions[weatherIndex]);
            assertClose(helper, "the decay length of " + weatherName, weather.decayLength(),
                    snowDecayLength + (blizzardDecayLength - snowDecayLength) * weather.stormIntensity());
        }
        weather.update(0.4, 0.4, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the snowfall at rain 0.4", weather.snowfallIntensity(), 0.5);
        assertClose(helper, "the storm at thunder 0.4", weather.stormIntensity(), 0.5);
        assertClose(helper, "the extinction at rain and thunder 0.4", weather.referenceExtinction(),
                0.5 * (snowExtinction + blizzardExtinction) / 2.0);
        assertClose(helper, "the decay length at thunder 0.4", weather.decayLength(), 1.5 + 2.5 * 0.5);
        weather.update(0.2, 0.8, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the storm at rain 0.2 and thunder 0.8", weather.stormIntensity(), 0.25);
        assertClose(helper, "the snowfall at rain 0.2", weather.snowfallIntensity(), 0.25);
        assertClose(helper, "the decay length at thunder 0.8", weather.decayLength(), 1.5 + 2.5 * 0.25);
        weather.update(Double.NaN, Double.NaN, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the snowfall at a NaN rain level", weather.snowfallIntensity(), 0.0);
        assertClose(helper, "the storm at a NaN thunder level", weather.stormIntensity(), 0.0);
        assertClose(helper, "the extinction at NaN levels", weather.referenceExtinction(), 0.0);
        assertClose(helper, "the decay length at NaN levels", weather.decayLength(), 1.5);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, 0.0, snowDecayLength, blizzardDecayLength, 50.0,
                12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the height scale after 0", weather.heightScale(), 128.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, Double.NaN, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the height scale after NaN", weather.heightScale(), 128.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, Double.NaN, Double.NaN, 50.0, 12.0,
                0.0, 0.3, 0.75);
        assertClose(helper, "the decay length after NaN lengths", weather.decayLength(), 1.5);
        ClientMockWeather freshWeather = new ClientMockWeather();
        assertClose(helper, "the snowfall before the first update", freshWeather.snowfallIntensity(), 0.0);
        assertClose(helper, "the storm before the first update", freshWeather.stormIntensity(), 0.0);
        assertClose(helper, "the extinction before the first update", freshWeather.referenceExtinction(), 0.0);
        assertClose(helper, "the height scale before the first update", freshWeather.heightScale(), 128.0);
        assertClose(helper, "the decay length before the first update", freshWeather.decayLength(), 1.5);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void mockWeatherGivesTheWind(GameTestHelper helper) {
        double snowVisibility = 60.0;
        double blizzardVisibility = 20.0;
        double heightScale = 128.0;
        double snowDecayLength = 1.5;
        double blizzardDecayLength = 4.0;
        ClientMockWeather freshWeather = new ClientMockWeather();
        assertClose(helper, "the wind speed before the first update", freshWeather.windSpeed(), 0.0);
        assertClose(helper, "the wind direction before the first update", freshWeather.windDirection(), 0.0);
        ClientMockWeather weather = new ClientMockWeather();
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the wind speed at the wind level 50 and the wind scale 12", weather.windSpeed(), 6.0);
        assertClose(helper, "the wind direction at the day time 0", weather.windDirection(),
                ClientMockWeather.windDirectionAt(0.0));
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 150.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the wind speed at the wind level 150, which counts as full", weather.windSpeed(), 12.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, -5.0, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the wind speed at the wind level -5", weather.windSpeed(), 0.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, Double.NaN, 12.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the wind speed at a NaN wind level", weather.windSpeed(), 0.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, Double.NaN, 0.0, 0.3, 0.75);
        assertClose(helper, "the wind speed at a NaN wind scale", weather.windSpeed(), 0.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, -1.0, 0.0, 0.3, 0.75);
        assertClose(helper, "the wind speed at the wind scale -1", weather.windSpeed(), 0.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 12345.5, 0.3, 0.75);
        assertClose(helper, "the wind direction at the day time 12345.5", weather.windDirection(),
                ClientMockWeather.windDirectionAt(12345.5));
        assertClose(helper, "the wind direction at the day time 0", ClientMockWeather.windDirectionAt(0.0),
                WIND_DIRECTION_BASE + 0.9 * Math.sin(0.7) + 0.5 * Math.sin(2.1));
        assertClose(helper, "the wind direction at a NaN day time", ClientMockWeather.windDirectionAt(Double.NaN),
                WIND_DIRECTION_BASE);
        for (double ticks = 0.0; ticks <= WIND_DIRECTION_LONGEST_TICKS; ticks += WIND_DIRECTION_TICK_STEP) {
            double direction = ClientMockWeather.windDirectionAt(ticks);
            double nextDirection = ClientMockWeather.windDirectionAt(ticks + 1.0);
            double turn = Math.abs(nextDirection - direction);
            helper.assertTrue(turn <= WIND_DIRECTION_TURN, "The wind direction turns by " + turn + " over the tick "
                    + "after " + ticks + ", above the " + WIND_DIRECTION_TURN + " that keeps it slow");
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void mockWeatherGivesTheStructureShare(GameTestHelper helper) {
        double snowVisibility = 60.0;
        double blizzardVisibility = 20.0;
        double heightScale = 128.0;
        double snowDecayLength = 1.5;
        double blizzardDecayLength = 4.0;
        ClientMockWeather freshWeather = new ClientMockWeather();
        assertClose(helper, "the structure share before the first update", freshWeather.structureShare(), 0.0);
        ClientMockWeather weather = new ClientMockWeather();
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, STRUCTURE_SNOW_SHARE, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at the rain 0.8 and the thunder 0", weather.structureShare(), 0.3);
        weather.update(0.8, 0.4, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, STRUCTURE_SNOW_SHARE, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at the thunder 0.4, which is halfway to the blizzard",
                weather.structureShare(), 0.525);
        weather.update(0.8, 0.8, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, STRUCTURE_SNOW_SHARE, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at the thunder 0.8, which is the blizzard", weather.structureShare(),
                0.75);
        weather.update(0.4, 0.8, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, STRUCTURE_SNOW_SHARE, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at the rain 0.4 and the thunder 0.8, whose storm is at most the "
                + "snowfall of 0.5", weather.structureShare(), 0.525);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, Double.NaN, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at a NaN snow share without a storm", weather.structureShare(), 0.0);
        weather.update(0.8, 0.8, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, Double.NaN, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at a NaN snow share and the thunder 0.8", weather.structureShare(),
                0.75);
        weather.update(0.8, 0.8, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, STRUCTURE_SNOW_SHARE, 1.5);
        assertClose(helper, "the structure share at the blizzard share 1.5, clamped to 1", weather.structureShare(),
                1.0);
        weather.update(0.8, 0.0, snowVisibility, blizzardVisibility, heightScale, snowDecayLength,
                blizzardDecayLength, 50.0, 12.0, 0.0, -0.2, STRUCTURE_BLIZZARD_SHARE);
        assertClose(helper, "the structure share at the snow share -0.2 without a storm", weather.structureShare(),
                0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void clearAirLengthensTheCullingDistance(GameTestHelper helper) {
        assertDistance(helper, "the six-argument distance of the uniform fog", FogCullingDistance.opaqueDistance(
                CLEAR_AIR_EXTINCTION, CLEAR_AIR_EYE_HEIGHT, 0.0, CLEAR_AIR_MINIMUM_HEIGHT, CLEAR_AIR_MAXIMUM_HEIGHT,
                CLEAR_AIR_MAXIMUM_DISTANCE), 124.68821451436742);
        assertClearAirDistance(helper, "the clear air 1 and the range 96", 124.68821451436742, 1.0, 96.0);
        assertClearAirDistance(helper, "the clear air 0.25 and the range 96", 196.68821451436742, 0.25, 96.0);
        assertClearAirDistance(helper, "the clear air 0 and the range 96", 220.68821451436742, 0.0, 96.0);
        assertClearAirDistance(helper, "the clear air 0.5 and the range 400, whose clear stretch is the whole "
                + "distance", 249.37642902873484, 0.5, 400.0);
        assertClearAirDistance(helper, "a NaN clear air and the range 96, whose clear air counts as 1",
                124.68821451436742, Double.NaN, 96.0);
        assertClearAirDistance(helper, "the clear air 0.25 and the range 0, which has no clear stretch",
                124.68821451436742, 0.25, 0.0);
        double blizzardExtinction = HeightFog.VISIBILITY_OPTICAL_DEPTH / BLIZZARD_VISIBILITY;
        double blizzardDistance = FogCullingDistance.opaqueDistance(blizzardExtinction, BLIZZARD_EYE_HEIGHT,
                BLIZZARD_HEIGHT_SCALE, LOWEST_HEIGHT, HIGHEST_SURFACE, BLIZZARD_MAXIMUM_DISTANCE);
        double blizzardClearAirDistance = FogCullingDistance.opaqueDistance(blizzardExtinction, BLIZZARD_EYE_HEIGHT,
                BLIZZARD_HEIGHT_SCALE, LOWEST_HEIGHT, HIGHEST_SURFACE, BLIZZARD_MAXIMUM_DISTANCE, 1.0, 0.0);
        helper.assertTrue(blizzardClearAirDistance == blizzardDistance, "The eight-argument distance of the blizzard "
                + "with the clear air 1 and the range 0 is " + blizzardClearAirDistance + " instead of the "
                + blizzardDistance + " of the six-argument one");
        double blizzardStructuredDistance = FogCullingDistance.opaqueDistance(blizzardExtinction, BLIZZARD_EYE_HEIGHT,
                BLIZZARD_HEIGHT_SCALE, LOWEST_HEIGHT, HIGHEST_SURFACE, BLIZZARD_MAXIMUM_DISTANCE, 0.25, 96.0);
        helper.assertTrue(blizzardStructuredDistance >= blizzardDistance, "The eight-argument distance of the "
                + "blizzard with the clear air 0.25 and the range 96 is " + blizzardStructuredDistance
                + ", shorter than the " + blizzardDistance + " without clear air");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyOpticalDepthFollowsTheFormula(GameTestHelper helper) {
        double fullDepth = HeightFog.skyOpticalDepth(SKY_EYE_EXTINCTION, 1.0, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky depth of the direction 1.0 at the height scale " + SKY_HEIGHT_SCALE, fullDepth,
                FULL_SKY_OPTICAL_DEPTH);
        double halfDepth = HeightFog.skyOpticalDepth(SKY_EYE_EXTINCTION, 0.5, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky depth of the direction 0.5 at the height scale " + SKY_HEIGHT_SCALE, halfDepth,
                HALF_SKY_OPTICAL_DEPTH);
        double grazingDepth = HeightFog.skyOpticalDepth(SKY_EYE_EXTINCTION, GRAZING_SKY_DIRECTION, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky depth of the direction " + GRAZING_SKY_DIRECTION + ", whose formula gives "
                + GRAZING_SKY_OPTICAL_DEPTH + ", at the height scale " + SKY_HEIGHT_SCALE, grazingDepth,
                HeightFog.MAXIMUM_OPTICAL_DEPTH);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyOpticalDepthHandlesEdges(GameTestHelper helper) {
        double[] levelDirections = {0.0, -0.1, Double.NaN};
        for (int directionIndex = 0; directionIndex < levelDirections.length; directionIndex++) {
            double depth = HeightFog.skyOpticalDepth(SKY_EYE_EXTINCTION, levelDirections[directionIndex],
                    SKY_HEIGHT_SCALE);
            assertClose(helper, "the sky depth of the direction " + levelDirections[directionIndex] + " at the height "
                    + "scale " + SKY_HEIGHT_SCALE, depth, HeightFog.MAXIMUM_OPTICAL_DEPTH);
        }
        double[] badHeightScales = {0.0, -1.0, Double.NaN};
        for (int scaleIndex = 0; scaleIndex < badHeightScales.length; scaleIndex++) {
            double depth = HeightFog.skyOpticalDepth(SKY_EYE_EXTINCTION, 1.0, badHeightScales[scaleIndex]);
            assertClose(helper, "the sky depth of the direction 1.0 at the height scale "
                    + badHeightScales[scaleIndex], depth, HeightFog.MAXIMUM_OPTICAL_DEPTH);
        }
        double[] badExtinctions = {0.0, -1.0, Double.NaN};
        for (int extinctionIndex = 0; extinctionIndex < badExtinctions.length; extinctionIndex++) {
            double upwardDepth = HeightFog.skyOpticalDepth(badExtinctions[extinctionIndex], 1.0, SKY_HEIGHT_SCALE);
            assertClose(helper, "the sky depth at the extinction " + badExtinctions[extinctionIndex]
                    + " of the direction 1.0", upwardDepth, 0.0);
            double downwardDepth = HeightFog.skyOpticalDepth(badExtinctions[extinctionIndex], -1.0, SKY_HEIGHT_SCALE);
            assertClose(helper, "the sky depth at the extinction " + badExtinctions[extinctionIndex]
                    + " of the direction -1.0", downwardDepth, 0.0);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyTailOpticalDepthCompletesTheWalkedSegment(GameTestHelper helper) {
        double tail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, SKY_TAIL_START, 1.0, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky tail from " + SKY_TAIL_START + " blocks along the direction 1.0 at the height "
                + "scale " + SKY_HEIGHT_SCALE, tail, SKY_TAIL_OPTICAL_DEPTH);
        double segment = HeightFog.opticalDepth(SKY_EYE_EXTINCTION, SKY_TAIL_START, SKY_TAIL_START, SKY_HEIGHT_SCALE);
        assertClose(helper, "the walked depth to " + SKY_TAIL_START + " blocks along the direction 1.0 at the height "
                + "scale " + SKY_HEIGHT_SCALE, segment, SKY_SEGMENT_OPTICAL_DEPTH);
        assertClose(helper, "the walked depth to " + SKY_TAIL_START + " blocks plus the sky tail from there, along "
                + "the direction 1.0 at the height scale " + SKY_HEIGHT_SCALE, segment + tail, FULL_SKY_OPTICAL_DEPTH);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyTailOpticalDepthHandlesEdges(GameTestHelper helper) {
        double fullDepth = HeightFog.skyOpticalDepth(SKY_EYE_EXTINCTION, 1.0, SKY_HEIGHT_SCALE);
        double zeroStartTail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, 0.0, 1.0, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky tail from 0 blocks along the direction 1.0", zeroStartTail, fullDepth);
        double negativeStartTail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, -5.0, 1.0, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky tail from -5 blocks, which counts as 0, along the direction 1.0",
                negativeStartTail, fullDepth);
        double nanStartTail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, Double.NaN, 1.0, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky tail from a NaN start, which counts as 0, along the direction 1.0", nanStartTail,
                fullDepth);
        double farTail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, FAR_SKY_TAIL_START, 1.0, SKY_HEIGHT_SCALE);
        assertClose(helper, "the sky tail from " + FAR_SKY_TAIL_START + " blocks along the direction 1.0, whose "
                + "exponent passes " + HeightFog.MAXIMUM_EXPONENT, farTail, 0.0);
        double[] levelDirections = {0.0, -0.1};
        for (int directionIndex = 0; directionIndex < levelDirections.length; directionIndex++) {
            double tail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, SKY_TAIL_START,
                    levelDirections[directionIndex], SKY_HEIGHT_SCALE);
            assertClose(helper, "the sky tail from " + SKY_TAIL_START + " blocks along the direction "
                    + levelDirections[directionIndex], tail, HeightFog.MAXIMUM_OPTICAL_DEPTH);
        }
        double[] badHeightScales = {0.0, -1.0, Double.NaN};
        for (int scaleIndex = 0; scaleIndex < badHeightScales.length; scaleIndex++) {
            double tail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, SKY_TAIL_START, 1.0,
                    badHeightScales[scaleIndex]);
            assertClose(helper, "the sky tail from " + SKY_TAIL_START + " blocks along the direction 1.0 at the "
                    + "height scale " + badHeightScales[scaleIndex], tail, HeightFog.MAXIMUM_OPTICAL_DEPTH);
        }
        double[] badExtinctions = {0.0, -1.0, Double.NaN};
        for (int extinctionIndex = 0; extinctionIndex < badExtinctions.length; extinctionIndex++) {
            double upwardTail = HeightFog.skyTailOpticalDepth(badExtinctions[extinctionIndex], SKY_TAIL_START, 1.0,
                    SKY_HEIGHT_SCALE);
            assertClose(helper, "the sky tail at the extinction " + badExtinctions[extinctionIndex]
                    + " of the direction 1.0", upwardTail, 0.0);
            double downwardTail = HeightFog.skyTailOpticalDepth(badExtinctions[extinctionIndex], SKY_TAIL_START, -1.0,
                    SKY_HEIGHT_SCALE);
            assertClose(helper, "the sky tail at the extinction " + badExtinctions[extinctionIndex]
                    + " of the direction -1.0", downwardTail, 0.0);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void whiteFaceColorAveragesTwoLightmapPixels(GameTestHelper helper) {
        float[] result = new float[COLOR_CHANNEL_COUNT];
        FogLight.whiteFaceColor(WHITE_FACE_SKY_FOURTEEN, WHITE_FACE_SKY_FIFTEEN, result);
        assertColor(helper, "the mean of the lightmap pixels 0xFFB0A090 and 0xFFC0B0A0", result, WHITE_FACE_RED,
                WHITE_FACE_GREEN, WHITE_FACE_BLUE);
        float[] transparentResult = new float[COLOR_CHANNEL_COUNT];
        FogLight.whiteFaceColor(TRANSPARENT_SKY_FOURTEEN, TRANSPARENT_SKY_FIFTEEN, transparentResult);
        assertColor(helper, "the mean of the pixels 0x00B0A090 and 0x12C0B0A0 of the other alpha bytes",
                transparentResult, WHITE_FACE_RED, WHITE_FACE_GREEN, WHITE_FACE_BLUE);
        FogLight.whiteFaceColor(WHITE_LIGHTMAP_PIXEL, WHITE_LIGHTMAP_PIXEL, result);
        assertColor(helper, "the mean of two 0xFFFFFFFF pixels", result, 1.0, 1.0, 1.0);
        FogLight.whiteFaceColor(BLACK_LIGHTMAP_PIXEL, BLACK_LIGHTMAP_PIXEL, result);
        assertColor(helper, "the mean of two 0xFF000000 pixels", result, 0.0, 0.0, 0.0);
        float[] longResult = {0.25F, 0.25F, 0.25F, 0.75F};
        FogLight.whiteFaceColor(WHITE_FACE_SKY_FOURTEEN, WHITE_FACE_SKY_FIFTEEN, longResult);
        assertColor(helper, "the mean of the lightmap pixels 0xFFB0A090 and 0xFFC0B0A0 in a result of length 4",
                longResult, WHITE_FACE_RED, WHITE_FACE_GREEN, WHITE_FACE_BLUE);
        helper.assertTrue(longResult[3] == 0.75F, "The result of length 4 holds " + longResult[3] + " in its fourth "
                + "entry instead of the 0.75 it held before the call");
        helper.assertTrue(rejectsShortResult(), "The white face color of a result of length 2 does not throw an "
                + "IllegalArgumentException");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void whiteFaceColorAllocatesNothing(GameTestHelper helper) {
        float[] result = new float[COLOR_CHANNEL_COUNT];
        warmWhiteFaceColor(result, ALLOCATION_WARMUP_CALLS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LogUtils.getLogger().info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of "
                    + "the warm white face color is not measured");
            helper.succeed();
            return;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            try {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            } catch (UnsupportedOperationException unsupported) {
                LogUtils.getLogger().info("Thread allocated memory cannot be turned on", unsupported);
            }
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            LogUtils.getLogger().info("Thread allocated memory is not enabled, so the allocation of the warm white "
                    + "face color is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        float sum = warmWhiteFaceColor(result, ALLOCATION_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LogUtils.getLogger().info("The warm white face color allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_CALLS + " warm white face colors allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * Checks the distance of the uniform fog with the given clear air and clear range against its expected value.
     */
    private static void assertClearAirDistance(GameTestHelper helper, String step, double expected,
            double clearAirDensity, double clearRange) {
        assertDistance(helper, step, FogCullingDistance.opaqueDistance(CLEAR_AIR_EXTINCTION, CLEAR_AIR_EYE_HEIGHT,
                0.0, CLEAR_AIR_MINIMUM_HEIGHT, CLEAR_AIR_MAXIMUM_HEIGHT, CLEAR_AIR_MAXIMUM_DISTANCE, clearAirDensity,
                clearRange), expected);
    }

    /**
     * Checks a culling distance against its expected value, which the search keeps at or above, within
     * CLEAR_AIR_DISTANCE_TOLERANCE.
     */
    private static void assertDistance(GameTestHelper helper, String step, double actual, double expected) {
        helper.assertTrue(actual >= expected - 1.0e-9 && actual <= expected + CLEAR_AIR_DISTANCE_TOLERANCE, "The "
                + "culling distance of " + step + " is " + actual + " instead of one from " + expected + " to "
                + (expected + CLEAR_AIR_DISTANCE_TOLERANCE));
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

    /**
     * Checks the red, green and blue of a white face color against their expected values within COLOR_TOLERANCE.
     */
    private static void assertColor(GameTestHelper helper, String step, float[] result, double expectedRed,
            double expectedGreen, double expectedBlue) {
        double[] expectedChannels = {expectedRed, expectedGreen, expectedBlue};
        String[] channelNames = {"red", "green", "blue"};
        for (int channel = 0; channel < COLOR_CHANNEL_COUNT; channel++) {
            double difference = Math.abs(result[channel] - expectedChannels[channel]);
            helper.assertTrue(difference <= COLOR_TOLERANCE, "In " + step + ", the " + channelNames[channel]
                    + " channel is " + result[channel] + " instead of " + expectedChannels[channel]
                    + ", a difference of " + difference + " above " + COLOR_TOLERANCE);
        }
    }

    /**
     * Whether the white face color of a result of length 2 is rejected with an IllegalArgumentException.
     */
    private static boolean rejectsShortResult() {
        try {
            FogLight.whiteFaceColor(WHITE_FACE_SKY_FOURTEEN, WHITE_FACE_SKY_FIFTEEN, new float[2]);
        } catch (IllegalArgumentException expectedException) {
            return true;
        }
        return false;
    }

    /**
     * The work of the allocation test: the colors of changing lightmap pixels, without an assertion or a message of
     * its own, so that it can be measured.
     */
    private static float warmWhiteFaceColor(float[] result, int calls) {
        float sum = 0.0F;
        for (int call = 0; call < calls; call++) {
            FogLight.whiteFaceColor(WHITE_FACE_SKY_FOURTEEN - (call & 0xF), WHITE_FACE_SKY_FIFTEEN + (call & 0xF),
                    result);
            sum += result[0] + result[1] + result[2];
        }
        return sum;
    }

}
