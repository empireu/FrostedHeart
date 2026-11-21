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
 * Checks the rules that fill the fog of a full-resolution pixel in from four pixels walked at a lower resolution: the
 * divisor, the low size, the checkerboard of the two depths and the ratio, the texels and weights of a pixel's
 * footprint, the ratio a pixel resolves to with and without the fallback, the ratio a sky pixel takes from its sky
 * samples, the hysteresis of the fallback, and that the warm rules allocate nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogUpsampleGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double TOLERANCE = 1.0e-12;
    private static final int WEIGHT_COUNT = 4;
    private static final int LOW_SIZE = 10;
    private static final int SCREEN_WIDTH = 23;
    private static final int SCREEN_HEIGHT = 17;
    private static final int[] DIVISORS = {1, 2, 4};
    private static final double FALLBACK_CAP = 0.25;
    private static final double OWN_DISTANCE = 10.0;
    private static final double TOLERANCE_EDGE_DISTANCE = 20.0;
    private static final int ALLOCATION_WARMUP_CALLS = 100;
    private static final int ALLOCATION_CALLS = 1000;
    private static final int ALLOCATION_BEAN_WARMUPS = 20;
    private static final double WARM_NEAREST_DISTANCE = 8.0;
    private static final int WARM_DISTANCE_SPAN = 5;
    private static final double WARM_LIGHTEST_WEIGHT = 0.25;
    private static final double WARM_WEIGHT_STEP = 0.01;
    private static final int WARM_WEIGHT_STEPS = 10;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void divisorAndSizes(GameTestHelper helper) {
        assertDivisor(helper, 0.0, 1);
        assertDivisor(helper, 1.0, 1);
        assertDivisor(helper, 1.49, 1);
        assertDivisor(helper, 1.5, 2);
        assertDivisor(helper, 2.0, 2);
        assertDivisor(helper, 2.99, 2);
        assertDivisor(helper, 3.0, 4);
        assertDivisor(helper, 4.0, 4);
        assertDivisor(helper, 100.0, 4);
        assertDivisor(helper, Double.NaN, 2);
        assertDivisor(helper, -1.0, 1);
        assertLowResolutionSize(helper, 1920, 2, 960);
        assertLowResolutionSize(helper, 1919, 2, 960);
        assertLowResolutionSize(helper, 1080, 4, 270);
        assertLowResolutionSize(helper, 1081, 4, 271);
        assertLowResolutionSize(helper, 7, 1, 7);
        assertTakesMaximumDepth(helper, 0, 0, false);
        assertTakesMaximumDepth(helper, 1, 0, true);
        assertTakesMaximumDepth(helper, 3, 4, true);
        assertTakesMaximumDepth(helper, 5, 5, false);
        assertRatio(helper, "the walked depth 2 and the analytic depth 4", FogUpsample.ratio(2.0, 4.0), 0.5);
        assertRatio(helper, "the walked depth 0 and the analytic depth 2", FogUpsample.ratio(0.0, 2.0), 0.0);
        assertRatio(helper, "the walked depth 0 and the analytic depth 0", FogUpsample.ratio(0.0, 0.0), 1.0);
        assertRatio(helper, "the walked depth 3 and the analytic depth 1e-7", FogUpsample.ratio(3.0, 1.0e-7), 1.0);
        assertRatio(helper, "the walked depth 3 and a NaN analytic depth", FogUpsample.ratio(3.0, Double.NaN), 1.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void footprintWeights(GameTestHelper helper) {
        assertFootprint(helper, "the divisor 2 at the pixel (0, 0) of the " + LOW_SIZE + " by " + LOW_SIZE
                + " target, whose texels clamp to the edge", 0, 0, 2, LOW_SIZE, LOW_SIZE, new int[] {0, 0, 0, 0},
                new int[] {0, 0, 0, 0}, new double[] {0.0625, 0.1875, 0.1875, 0.5625});
        assertFootprint(helper, "the divisor 2 at the pixel (3, 2) of the " + LOW_SIZE + " by " + LOW_SIZE + " target",
                3, 2, 2, LOW_SIZE, LOW_SIZE, new int[] {1, 2, 1, 2}, new int[] {0, 0, 1, 1},
                new double[] {0.1875, 0.0625, 0.5625, 0.1875});
        assertFootprint(helper, "the divisor 1 at the pixel (5, 7) of the " + LOW_SIZE + " by " + LOW_SIZE + " target",
                5, 7, 1, LOW_SIZE, LOW_SIZE, new int[] {5, 6, 5, 6}, new int[] {7, 7, 8, 8},
                new double[] {1.0, 0.0, 0.0, 0.0});
        assertFootprint(helper, "the divisor 2 at the pixel (19, 19), whose texels clamp to the far edge", 19, 19, 2,
                LOW_SIZE, LOW_SIZE, new int[] {9, 9, 9, 9}, new int[] {9, 9, 9, 9},
                new double[] {0.5625, 0.1875, 0.1875, 0.0625});
        assertFootprint(helper, "the divisor 4 at the pixel (6, 1), whose low y clamps to the near edge", 6, 1, 4,
                LOW_SIZE, LOW_SIZE, new int[] {1, 2, 1, 2}, new int[] {0, 0, 0, 0},
                new double[] {0.109375, 0.015625, 0.765625, 0.109375});
        int[] texelX = new int[WEIGHT_COUNT];
        int[] texelY = new int[WEIGHT_COUNT];
        double[] weights = new double[WEIGHT_COUNT];
        for (int divisorIndex = 0; divisorIndex < DIVISORS.length; divisorIndex++) {
            int divisor = DIVISORS[divisorIndex];
            int lowWidth = FogUpsample.lowResolutionSize(SCREEN_WIDTH, divisor);
            int lowHeight = FogUpsample.lowResolutionSize(SCREEN_HEIGHT, divisor);
            for (int pixelY = 0; pixelY < SCREEN_HEIGHT; pixelY++) {
                for (int pixelX = 0; pixelX < SCREEN_WIDTH; pixelX++) {
                    FogUpsample.footprint(pixelX, pixelY, divisor, lowWidth, lowHeight, texelX, texelY, weights);
                    double weightSum = 0.0;
                    for (int texel = 0; texel < WEIGHT_COUNT; texel++) {
                        weightSum += weights[texel];
                        helper.assertTrue(texelX[texel] >= 0 && texelX[texel] < lowWidth, "The pixel (" + pixelX + ", "
                                + pixelY + ") at the divisor " + divisor + " has the texel " + texel + " at the x "
                                + texelX[texel] + ", outside the " + lowWidth + " texels of the target");
                        helper.assertTrue(texelY[texel] >= 0 && texelY[texel] < lowHeight, "The pixel (" + pixelX + ", "
                                + pixelY + ") at the divisor " + divisor + " has the texel " + texel + " at the y "
                                + texelY[texel] + ", outside the " + lowHeight + " texels of the target");
                    }
                    helper.assertTrue(Math.abs(weightSum - 1.0) <= TOLERANCE, "The pixel (" + pixelX + ", " + pixelY
                            + ") at the divisor " + divisor + " of the " + lowWidth + " by " + lowHeight
                            + " target has the weights " + Arrays.toString(weights) + ", which sum to " + weightSum
                            + " instead of 1");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void resolveFollowsTheRules(GameTestHelper helper) {
        assertResolve(helper, "the four samples that agree around the distance " + OWN_DISTANCE
                + " with the ratios 1, 1, 0.9 and 0.85", OWN_DISTANCE, new double[] {10.0, 10.2, 9.9, 10.4},
                new double[] {1.0, 1.0, 0.9, 0.85}, new double[] {0.25, 0.25, 0.25, 0.25}, 0.9375, 0.9375);
        assertResolve(helper, "the two samples that agree and the two that lie far beyond the tolerance of the "
                + OWN_DISTANCE + " block distance", OWN_DISTANCE, new double[] {10.0, 10.0, 40.0, 40.0},
                new double[] {0.5, 0.6, 1.0, 1.0}, new double[] {0.1, 0.2, 0.3, 0.4}, 0.17 / 0.3, 0.17 / 0.3);
        assertResolve(helper, "no sample that agrees, with the samples of the distances 20, 3, a sky and 30",
                OWN_DISTANCE, new double[] {20.0, 3.0, FogUpsample.SKY_DISTANCE, 30.0},
                new double[] {0.7, 0.2, 1.0, 0.9}, new double[] {0.25, 0.25, 0.25, 0.25}, Double.NaN, 0.2);
        assertResolve(helper, "the ratios 0, 1, 1 and 1 of the samples that all agree", OWN_DISTANCE,
                new double[] {10.0, 10.0, 10.0, 10.0}, new double[] {0.0, 1.0, 1.0, 1.0},
                new double[] {0.25, 0.25, 0.25, 0.25}, Double.NaN, 0.0);
        assertResolve(helper, "the three samples that agree with all of the weight on the sample that does not",
                OWN_DISTANCE, new double[] {30.0, 10.0, 10.0, 10.0}, new double[] {0.3, 0.6, 0.6, 0.6},
                new double[] {1.0, 0.0, 0.0, 0.0}, 0.6, 0.6);
        assertResolve(helper, "the four samples of the sky", OWN_DISTANCE,
                new double[] {FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE,
                        FogUpsample.SKY_DISTANCE}, new double[] {1.0, 1.0, 1.0, 1.0},
                new double[] {0.25, 0.25, 0.25, 0.25}, Double.NaN, 1.0);
        assertResolve(helper, "the sample that agrees exactly at the tolerance of the " + TOLERANCE_EDGE_DISTANCE
                + " block distance and the one just beyond it", TOLERANCE_EDGE_DISTANCE,
                new double[] {21.25, 21.3, FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE},
                new double[] {0.4, 0.9, 1.0, 1.0}, new double[] {0.5, 0.5, 0.0, 0.0}, 0.4, 0.4);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fallbackHysteresis(GameTestHelper helper) {
        assertFallback(helper, true, 0.3, false);
        assertFallback(helper, true, FALLBACK_CAP, true);
        assertFallback(helper, false, 0.21, false);
        assertFallback(helper, false, 0.19, true);
        assertFallback(helper, false, Double.NaN, false);
        assertFallback(helper, true, Double.NaN, true);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void resolveAllocatesNothing(GameTestHelper helper) {
        int[] texelX = new int[WEIGHT_COUNT];
        int[] texelY = new int[WEIGHT_COUNT];
        double[] weights = new double[WEIGHT_COUNT];
        double[] distances = {6.0, 12.0, FogUpsample.SKY_DISTANCE, 9.5};
        double[] ratios = {0.5, 1.0, 1.0, 0.75};
        Arrays.fill(weights, 0.25);
        warmResolve(texelX, texelY, weights, distances, ratios, ALLOCATION_WARMUP_CALLS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm "
                    + "resolve is not measured");
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
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm resolve is not "
                    + "measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        double sum = warmResolve(texelX, texelY, weights, distances, ratios, ALLOCATION_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm fog upsample allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_CALLS + " warm footprints and resolves allocate "
                + allocated + " bytes instead of 0");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void resolveSkyFollowsTheRules(GameTestHelper helper) {
        assertSkyRatio(helper, "the three sky samples of the distances -1, -1, 10 and -1 with the weights 0.1, 0.2, "
                + "0.3 and 0.4, whose terrain sample is left out and whose weights renormalize over 0.7",
                new double[] {-1.0, -1.0, 10.0, -1.0}, new double[] {0.8, 1.2, 5.0, 1.0},
                new double[] {0.1, 0.2, 0.3, 0.4}, 1.0285714285714285);
        assertSkyRatio(helper, "the four sky samples of the ratios 0.5, 1.5, 1.0 and 1.0",
                new double[] {FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE,
                        FogUpsample.SKY_DISTANCE}, new double[] {0.5, 1.5, 1.0, 1.0},
                new double[] {0.25, 0.25, 0.25, 0.25}, 1.0);
        assertSkyRatio(helper, "no sky sample of the distances 5, 6, 7 and 8", new double[] {5.0, 6.0, 7.0, 8.0},
                new double[] {0.8, 1.2, 5.0, 1.0}, new double[] {0.25, 0.25, 0.25, 0.25}, 1.0);
        assertSkyRatio(helper, "the sky sample of the weight 0 and the three terrain samples of the distance 10",
                new double[] {FogUpsample.SKY_DISTANCE, 10.0, 10.0, 10.0}, new double[] {0.8, 1.2, 5.0, 1.0},
                new double[] {0.0, 0.5, 0.25, 0.25}, 1.0);
        assertSkyRatio(helper, "the same samples of the distances -1, -1, 10 and -1 with FogUpsample.SKY_DISTANCE as "
                + "the sky distance", new double[] {FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE, 10.0,
                        FogUpsample.SKY_DISTANCE}, new double[] {0.8, 1.2, 5.0, 1.0},
                new double[] {0.1, 0.2, 0.3, 0.4}, 1.0285714285714285);
        assertSkyRatio(helper, "the terrain sample of the distance 0 and the three sky samples", new double[] {0.0,
                FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE, FogUpsample.SKY_DISTANCE},
                new double[] {5.0, 1.5, 1.0, 1.0}, new double[] {0.4, 0.2, 0.2, 0.2}, 1.1666666666666667);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void resolveSkyAllocatesNothing(GameTestHelper helper) {
        double[] distances = {FogUpsample.SKY_DISTANCE, 12.0, FogUpsample.SKY_DISTANCE, 9.5};
        double[] ratios = {0.5, 1.0, 1.5, 0.75};
        double[] weights = new double[WEIGHT_COUNT];
        Arrays.fill(weights, WARM_LIGHTEST_WEIGHT);
        warmResolveSky(distances, ratios, weights, ALLOCATION_WARMUP_CALLS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm sky "
                    + "resolve is not measured");
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
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm sky resolve is not "
                    + "measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        double sum = warmResolveSky(distances, ratios, weights, ALLOCATION_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm fog upsample sky allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_CALLS + " warm sky resolves allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * Checks a divisor of the setting against its expected divisor.
     */
    private static void assertDivisor(GameTestHelper helper, double setting, int expected) {
        int divisor = FogUpsample.divisorOf(setting);
        helper.assertTrue(divisor == expected, "The setting " + setting + " has the divisor " + divisor
                + " instead of " + expected);
    }

    /**
     * Checks the low-resolution size of a full size against its expected size.
     */
    private static void assertLowResolutionSize(GameTestHelper helper, int fullSize, int divisor, int expected) {
        int size = FogUpsample.lowResolutionSize(fullSize, divisor);
        helper.assertTrue(size == expected, "The full size " + fullSize + " at the divisor " + divisor
                + " has the low size " + size + " instead of " + expected);
    }

    /**
     * Checks which depth a low-resolution pixel keeps against the expected choice.
     */
    private static void assertTakesMaximumDepth(GameTestHelper helper, int lowX, int lowY, boolean expected) {
        boolean takesMaximum = FogUpsample.takesMaximumDepth(lowX, lowY);
        helper.assertTrue(takesMaximum == expected, "The low pixel (" + lowX + ", " + lowY + ") takes the maximum "
                + "depth " + takesMaximum + " instead of " + expected);
    }

    /**
     * Checks a ratio of the upsample against its expected ratio, where NaN matches only NaN.
     */
    private static void assertRatio(GameTestHelper helper, String step, double actual, double expected) {
        if (Double.isNaN(expected)) {
            helper.assertTrue(Double.isNaN(actual), "The value of " + step + " is " + actual + " instead of NaN");
            return;
        }
        double difference = Math.abs(actual - expected);
        helper.assertTrue(difference <= TOLERANCE, "The value of " + step + " is " + actual + " instead of " + expected
                + ", a difference of " + difference + " above " + TOLERANCE);
    }

    /**
     * Checks one footprint of the upsample against the texels and weights it should give.
     */
    private static void assertFootprint(GameTestHelper helper, String step, int pixelX, int pixelY, int divisor,
            int lowWidth, int lowHeight, int[] expectedTexelX, int[] expectedTexelY, double[] expectedWeights) {
        int[] texelX = new int[WEIGHT_COUNT];
        int[] texelY = new int[WEIGHT_COUNT];
        double[] weights = new double[WEIGHT_COUNT];
        FogUpsample.footprint(pixelX, pixelY, divisor, lowWidth, lowHeight, texelX, texelY, weights);
        for (int texel = 0; texel < WEIGHT_COUNT; texel++) {
            helper.assertTrue(texelX[texel] == expectedTexelX[texel], "In " + step + ", the texel " + texel
                    + " is at the x " + texelX[texel] + " instead of " + expectedTexelX[texel]);
            helper.assertTrue(texelY[texel] == expectedTexelY[texel], "In " + step + ", the texel " + texel
                    + " is at the y " + texelY[texel] + " instead of " + expectedTexelY[texel]);
            double difference = Math.abs(weights[texel] - expectedWeights[texel]);
            helper.assertTrue(difference <= TOLERANCE, "In " + step + ", the texel " + texel + " has the weight "
                    + weights[texel] + " instead of " + expectedWeights[texel] + ", a difference of " + difference
                    + " above " + TOLERANCE);
        }
    }

    /**
     * Checks the ratio that the upsample resolves for a pixel against its expected ratio with the fallback allowed and
     * with it not allowed.
     */
    private static void assertResolve(GameTestHelper helper, String step, double ownDistance, double[] distances,
            double[] ratios, double[] weights, double expectedAllowed, double expectedCapped) {
        assertRatio(helper, step + " with the fallback allowed",
                FogUpsample.resolve(ownDistance, distances, ratios, weights, true), expectedAllowed);
        assertRatio(helper, step + " with the fallback not allowed",
                FogUpsample.resolve(ownDistance, distances, ratios, weights, false), expectedCapped);
    }

    /**
     * Checks whether the fallback is allowed after a frame against the expected answer.
     */
    private static void assertFallback(GameTestHelper helper, boolean allowedBefore, double share,
            boolean expected) {
        boolean allowed = FogUpsample.fallbackAllowed(allowedBefore, share, FALLBACK_CAP);
        helper.assertTrue(allowed == expected, "The fallback after the share " + share + " with the allowance "
                + allowedBefore + " and the cap " + FALLBACK_CAP + " is " + allowed + " instead of " + expected);
    }

    /**
     * The work of the allocation test: the footprints and the resolves of the pixels with changing arguments, without
     * an assertion or a message of its own, so that it can be measured.
     */
    private static double warmResolve(int[] texelX, int[] texelY, double[] weights, double[] distances,
            double[] ratios, int calls) {
        double sum = 0.0;
        for (int call = 0; call < calls; call++) {
            int pixelX = call % SCREEN_WIDTH;
            int pixelY = call % SCREEN_HEIGHT;
            FogUpsample.footprint(pixelX, pixelY, DIVISORS[call % DIVISORS.length], LOW_SIZE, LOW_SIZE, texelX,
                    texelY, weights);
            double nearestDistance = WARM_NEAREST_DISTANCE + call % WARM_DISTANCE_SPAN;
            double ratio = FogUpsample.resolve(nearestDistance, distances, ratios, weights, (call & 1) == 0);
            sum += ratio + texelX[0] + texelY[0] + weights[0];
        }
        return sum;
    }

    /**
     * Checks the ratio of a sky pixel against its expected ratio within TOLERANCE.
     */
    private static void assertSkyRatio(GameTestHelper helper, String step, double[] distances, double[] ratios,
            double[] weights, double expected) {
        double skyRatio = FogUpsample.resolveSky(distances, ratios, weights);
        double difference = Math.abs(skyRatio - expected);
        helper.assertTrue(difference <= TOLERANCE, "The sky ratio of " + step + " is " + skyRatio + " instead of "
                + expected + ", a difference of " + difference + " above " + TOLERANCE);
    }

    /**
     * The work of the sky allocation test: the sky ratios of changing weights, without an assertion or a message of
     * its own, so that it can be measured.
     */
    private static double warmResolveSky(double[] distances, double[] ratios, double[] weights, int calls) {
        double sum = 0.0;
        for (int call = 0; call < calls; call++) {
            weights[call % WEIGHT_COUNT] = WARM_LIGHTEST_WEIGHT + WARM_WEIGHT_STEP * (call % WARM_WEIGHT_STEPS);
            sum += FogUpsample.resolveSky(distances, ratios, weights);
        }
        return sum;
    }
}
