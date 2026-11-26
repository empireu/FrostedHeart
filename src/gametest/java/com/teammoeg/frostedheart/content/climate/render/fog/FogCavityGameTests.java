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
 * Checks the wind-direction-free fog cavity enclosure map (FogCavity) against an independent 4-axis brute-force
 * reference on ravines at 0, 22.5 and 45 degrees, single cliffs, wide valleys, V-valleys, thin post pairs and calm
 * wind, and verifies SnowFogWalk cavity thinning, zero warm allocation, untouched flake shelter, and FogCullingDistance
 * safety for cavities and the near shelter region.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogCavityGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEST_MAP = 128;
    private static final int TEST_HALF = TEST_MAP / 2;
    private static final float SCAN_TOLERANCE = 2.0e-3F;
    private static final double TOLERANCE = 1.0e-9;
    private static final short FLOOR = 40;
    private static final short RIM = 65;

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void cavityScanMatchesBruteForceOnRavinesCliffValleyAndPosts(GameTestHelper helper) {
        // Verify production strides cover CARDINAL_REACH = 32 and DIAGONAL_REACH = 23 with no gaps:
        int[] cardinalStrides = FogCavity.cardinalStrides();
        int cardinalCovered = 1;
        for (int stride : cardinalStrides) {
            helper.assertTrue(stride <= cardinalCovered, "cardinal stride " + stride + " exceeds covered "
                    + cardinalCovered);
            cardinalCovered += stride;
        }
        helper.assertTrue(cardinalCovered == FogCavity.CARDINAL_REACH, "cardinal strides cover " + cardinalCovered
                + " instead of " + FogCavity.CARDINAL_REACH);

        int[] diagonalStrides = FogCavity.diagonalStrides();
        int diagonalCovered = 1;
        for (int stride : diagonalStrides) {
            helper.assertTrue(stride <= diagonalCovered, "diagonal stride " + stride + " exceeds covered "
                    + diagonalCovered);
            diagonalCovered += stride;
        }
        helper.assertTrue(diagonalCovered == FogCavity.DIAGONAL_REACH, "diagonal strides cover " + diagonalCovered
                + " instead of " + FogCavity.DIAGONAL_REACH);

        float[] output = new float[TEST_MAP * TEST_MAP];
        double slope = FogCavity.ENCLOSURE_SLOPE;
        double windFloor = FogCavity.effectiveFloor(0.5, 10.0);
        checkNear(helper, "blizzard wind effectiveFloor", windFloor, 0.5);

        // 1. Ravine across 0 degrees (runs along Z, walls at |x| >= 6, width 11, depth 25):
        SnowLee.HeightLookup ravine0 = (x, z) -> Math.abs(x) >= 6 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "ravine 0 deg", ravine0, cardinalStrides, diagonalStrides, slope, output);
        float center0 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        // At x = 0, walls are 6 blocks away on both sides -> 65 - 0.65 * 6 = 61.1:
        checkFloatNear(helper, "ravine 0 deg center enclosure", center0, 61.1F, 0.05F);
        double factorBottom0 = FogCavity.cavityFactor(FLOOR + 2.0, center0, 4.0, windFloor);
        helper.assertTrue(factorBottom0 > 0.5 && factorBottom0 < 0.52,
                "ravine 0 deg bottom fog factor was " + factorBottom0 + ", expected near 0.504");
        double factorRim0 = FogCavity.cavityFactor(RIM, center0, 4.0, windFloor);
        checkNear(helper, "ravine 0 deg rim fog factor", factorRim0, 1.0);

        // 2. Ravine at 22.5 degrees (halfway between cardinal and diagonal axes):
        double angle225 = Math.toRadians(22.5);
        double cos225 = Math.cos(angle225);
        double sin225 = Math.sin(angle225);
        SnowLee.HeightLookup ravine225 = (x, z) -> Math.abs(x * cos225 + z * sin225) >= 6.0 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "ravine 22.5 deg", ravine225, cardinalStrides, diagonalStrides, slope,
                output);
        float center225 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        helper.assertTrue(center225 > 59.0F && center225 < 62.5F,
                "ravine 22.5 deg center enclosure was " + center225 + ", expected about 60.5");
        double factorBottom225 = FogCavity.cavityFactor(FLOOR + 2.0, center225, 4.0, windFloor);
        helper.assertTrue(factorBottom225 < 0.52, "ravine 22.5 deg bottom fog factor was " + factorBottom225);

        // 3. Ravine at 45 degrees (runs along X = Z, walls at |x - z| / sqrt(2) >= 6):
        double invSqrt2 = Math.sqrt(0.5);
        SnowLee.HeightLookup ravine45 = (x, z) -> Math.abs(x - z) * invSqrt2 >= 6.0 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "ravine 45 deg", ravine45, cardinalStrides, diagonalStrides, slope,
                output);
        float center45 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        helper.assertTrue(center45 > 59.5F && center45 < 62.5F,
                "ravine 45 deg center enclosure was " + center45 + ", expected about 61.3");

        // 4. Single cliff foot (wall on -X only, open plain on +X): NO thinning anywhere:
        SnowLee.HeightLookup singleCliff = (x, z) -> x <= -4 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "single cliff", singleCliff, cardinalStrides, diagonalStrides, slope,
                output);
        for (int iz = 8; iz < TEST_MAP - 8; iz++) {
            for (int ix = 8; ix < TEST_MAP - 8; ix++) {
                int wx = ix - TEST_HALF;
                int wz = iz - TEST_HALF;
                short terrain = singleCliff.heightAt(wx, wz);
                float hCav = output[iz * TEST_MAP + ix];
                if (hCav > terrain) {
                    helper.fail("single cliff enclosed open column (" + wx + ", " + wz + ") with h_cav=" + hCav
                            + " > terrain=" + terrain);
                    return;
                }
            }
        }

        // 5. Wide valley (80 blocks wide, walls at |x| >= 40, depth 25): NO thinning anywhere:
        SnowLee.HeightLookup wideValley = (x, z) -> Math.abs(x) >= 40 ? RIM : FLOOR;
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, wideValley, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output);
        for (int x = -39; x <= 39; x++) {
            float hCav = output[TEST_HALF * TEST_MAP + (x + TEST_HALF)];
            if (hCav > FLOOR) {
                helper.fail("80-wide valley enclosed column x=" + x + " with h_cav=" + hCav + " > " + FLOOR);
                return;
            }
        }

        // 6. Gentle 1:2 V-valley (slope 0.5 < ENCLOSURE_SLOPE = 0.65): NO thinning:
        SnowLee.HeightLookup gentleValley = (x, z) -> (short) (FLOOR + Math.abs(x) / 2);
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, gentleValley, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output);
        float gentleCenter = output[TEST_HALF * TEST_MAP + TEST_HALF];
        helper.assertTrue(gentleCenter <= FLOOR, "gentle 1:2 V-valley enclosed floor with h_cav=" + gentleCenter);

        // 7. Opposing 1-wide and 2-wide posts across a 4-block gap: erased by 3-texel perpendicular min:
        SnowLee.HeightLookup postPair1 = (x, z) -> ((x == -2 || x == 2) && z == 0) ? RIM : FLOOR;
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, postPair1, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output);
        helper.assertTrue(output[TEST_HALF * TEST_MAP + TEST_HALF] <= FLOOR,
                "1-wide post pair created cavity enclosure " + output[TEST_HALF * TEST_MAP + TEST_HALF]);

        SnowLee.HeightLookup postPair2 = (x, z) -> ((x == -2 || x == 2) && (z == 0 || z == 1)) ? RIM : FLOOR;
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, postPair2, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output);
        helper.assertTrue(output[TEST_HALF * TEST_MAP + TEST_HALF] <= FLOOR,
                "2-wide post pair created cavity enclosure " + output[TEST_HALF * TEST_MAP + TEST_HALF]);

        // 8. Calm wind and fogCavityShare = 1.0 give zero thinning; intermediate wind ramps smoothly:
        checkNear(helper, "calm wind 0.0 factor", FogCavity.windFactor(0.0), 0.0);
        checkNear(helper, "calm wind 0.5 factor", FogCavity.windFactor(FogCavity.CALM_WIND_SPEED), 0.0);
        checkNear(helper, "NaN wind factor", FogCavity.windFactor(Double.NaN), 0.0);
        checkNear(helper, "calm wind effectiveFloor", FogCavity.effectiveFloor(0.5, 0.0), 1.0);
        checkNear(helper, "calm wind cavityFactor in ravine", FogCavity.cavityFactor(42.0, 61.1, 4.0,
                FogCavity.effectiveFloor(0.5, 0.0)), 1.0);
        checkNear(helper, "cavityShare 1.0 effectiveFloor", FogCavity.effectiveFloor(1.0, 10.0), 1.0);
        checkNear(helper, "cavityShare 0.0 blizzard effectiveFloor", FogCavity.effectiveFloor(0.0, 10.0), 0.0);
        double midWind = FogCavity.windFactor(3.25);
        helper.assertTrue(midWind > 0.45 && midWind < 0.55, "mid-wind factor at 3.25 was " + midWind);

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void snowFogWalkAndCullingDistanceRespectCavityThinningAndShelter(GameTestHelper helper) {
        // Set up an open-sky SnowFogWalk with a 512x512 cavity map holding h_cav = 61.0 everywhere:
        int fieldSize = 128;
        byte[] distances = new byte[fieldSize * fieldSize * fieldSize];
        short[] heights = new short[FarHeights.SIZE * FarHeights.SIZE];
        Arrays.fill(heights, FLOOR);
        float[] shareTable = new float[SnowFogWalk.TABLE_SIZE];
        SnowFogWalk.buildTable(4.0, shareTable);
        float[] cavityMap = new float[FogCavity.MAP_SIZE * FogCavity.MAP_SIZE];
        Arrays.fill(cavityMap, 61.0F);

        SnowFogWalk walk = new SnowFogWalk();
        walk.setField(distances, fieldSize);
        walk.setShownBox(true, -64, 0, -64, 63, 127, 63);
        walk.setHeights(heights);
        walk.setTable(shareTable);
        walk.setFog(0.1, 0.0);
        walk.setRadius(32.0);
        walk.setFieldFade(1.0);
        walk.setEye(0, 42, 0, 0.5, 0.5, 0.5);
        walk.setCavity(cavityMap, -FogCavity.HALF_SIZE, -FogCavity.HALF_SIZE, 0.5, 4.0);

        // Near walk inside ravine (y = 42.5, depth = 18.5 blocks -> factor ~ 0.5049):
        double nearDepth = walk.opticalDepth(20.0, 0.0, 0.0);
        double expectedNearFactor = FogCavity.cavityFactor(42.5, 61.0, 4.0, 0.5);
        checkNearWithin(helper, "near walk inside ravine", nearDepth, 2.0 * expectedNearFactor, 1.0e-6);

        // Far walk inside ravine (length 100 crosses near 0..30 at y = 42.5 and far 30..100 whose FAR_CELL_BLOCKS = 2
        // cell [42, 44) samples at midpoint y = 43.0):
        double farDepth = walk.opticalDepth(100.0, 0.0, 0.0);
        double expectedFarFactor = FogCavity.cavityFactor(43.0, 61.0, 4.0, 0.5);
        double expectedTotal = 3.0 * expectedNearFactor + 7.0 * expectedFarFactor;
        checkNearWithin(helper, "near + far walk inside ravine", farDepth, expectedTotal, 1.0e-5);

        // cellShare and heightShare (used for flakes' shelter) are UNTOUCHED (still 1.0):
        checkNear(helper, "cellShare inside open-sky ravine stays 1.0", walk.cellShare(0, 0, 0), 1.0);
        checkNear(helper, "heightShare inside open-sky ravine stays 1.0", walk.heightShare(0, 0, 0), 1.0);

        // Above the cavity rim (eyeY = 66): depth is exact open-air depth (10.0):
        walk.setEye(0, 66, 0, 0.5, 0.5, 0.5);
        checkNearWithin(helper, "walk above cavity rim", walk.opticalDepth(100.0, 0.0, 0.0), 10.0, 1.0e-6);

        // Clearing cavity restores open-air depth inside the ravine:
        walk.setEye(0, 42, 0, 0.5, 0.5, 0.5);
        walk.clearCavity();
        checkNearWithin(helper, "walk with cavity cleared", walk.opticalDepth(100.0, 0.0, 0.0), 10.0, 1.0e-6);

        // Warm allocation check with cavity active:
        walk.setCavity(cavityMap, -FogCavity.HALF_SIZE, -FogCavity.HALF_SIZE, 0.5, 4.0);
        assertWarmCavityWalkAllocatesNothing(helper, walk);

        // FogCullingDistance checks:
        double extinction = 0.05;
        double baseDistance = FogCullingDistance.opaqueDistance(extinction, 64.0, 0.0, 0.0, 64.0, 1000.0, 0.8, 96.0);
        double sameDistance = FogCullingDistance.opaqueDistance(extinction, 64.0, 0.0, 0.0, 64.0, 1000.0, 0.8, 96.0,
                1.0, 0.0, 0.0);
        checkNear(helper, "11-arg culling matches 8-arg with cavity and shelter off", sameDistance, baseDistance);

        // With cavity 0.5 out to 192 and shelter 30:
        // [0, 30]: 0; [30, 96]: 66 * 0.4 * 0.05 = 1.32; [96, 192]: 96 * 0.5 * 0.05 = 2.40; total at 192 = 3.72;
        // remaining to ln(510) = 6.234410725718371: (6.234410725718371 - 3.72) / 0.05 = 50.2882145 -> D = 242.2882:
        double expectedCavDistance = 192.0 + (FogCullingDistance.OPAQUE_OPTICAL_DEPTH - 3.72) / extinction;
        double actualCavDistance = FogCullingDistance.opaqueDistance(extinction, 64.0, 0.0, 0.0, 64.0, 1000.0,
                0.8, 96.0, 0.5, 192.0, 30.0);
        checkNearWithin(helper, "uniform fog culling distance with cavity 0.5 and shelter 30", actualCavDistance,
                expectedCavDistance, 1.0e-4);

        // Verify in a height-varying blizzard (V = 80, H = 128, eyeY = 42 inside ravine) that the culling distance
        // never cuts off terrain visible through cavity thinning (floor 0.5 out to 192) + clear air (0.8 out to 96)
        // + 30 blocks of shelter:
        double blizzardEyeExt = HeightFog.extinctionAt(HeightFog.extinctionFromVisibility(80.0), 63.0, 128.0, 42.0);
        double blizzardDistance = FogCullingDistance.opaqueDistance(blizzardEyeExt, 42.0, 128.0, -64.0, 120.0, 512.0,
                0.8, 96.0, 0.5, 192.0, 30.0);
        helper.assertTrue(Double.isFinite(blizzardDistance) && blizzardDistance > 192.0,
                "blizzard cavity culling distance was " + blizzardDistance + ", expected finite > 192");
        // Check a horizontal ray at y = 42 and an upward ray to y = 120 at blizzardDistance:
        double[] verticalOffsets = {0.0, 78.0};
        for (double vy : verticalOffsets) {
            double len = Math.sqrt(blizzardDistance * blizzardDistance + vy * vy);
            double tau30 = HeightFog.opticalDepth(blizzardEyeExt, 30.0, 30.0 / len * vy, 128.0);
            double tau96 = HeightFog.opticalDepth(blizzardEyeExt, 96.0, 96.0 / len * vy, 128.0);
            double tau192 = HeightFog.opticalDepth(blizzardEyeExt, 192.0, 192.0 / len * vy, 128.0);
            double tauFull = HeightFog.opticalDepth(blizzardEyeExt, len, vy, 128.0);
            double lowerBoundDepth = 0.4 * (tau96 - tau30) + 0.5 * (tau192 - tau96) + (tauFull - tau192);
            helper.assertTrue(lowerBoundDepth >= FogCullingDistance.OPAQUE_OPTICAL_DEPTH - 1.0e-5,
                    "ray vy=" + vy + " at culling distance " + blizzardDistance + " has lower-bound depth "
                            + lowerBoundDepth + " below opaque depth " + FogCullingDistance.OPAQUE_OPTICAL_DEPTH);
        }

        helper.succeed();
    }

    private static void assertWarmCavityWalkAllocatesNothing(GameTestHelper helper, SnowFogWalk walk) {
        double sum = 0.0;
        for (int warmup = 0; warmup < 5000; warmup++) {
            sum += walk.opticalDepth(80.0, 0.0, 0.0);
        }
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            return;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            try {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            } catch (UnsupportedOperationException ignored) {
                return;
            }
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < 20; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        for (int call = 0; call < 500; call++) {
            sum += walk.opticalDepth(80.0, 0.0, 0.0);
        }
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("Warm cavity fog walk allocated {} bytes (sum {})", allocated, sum);
        helper.assertTrue(allocated == 0L, "Warm cavity fog walk allocated " + allocated + " bytes instead of 0");
    }

    private static void assertCavityMatchesBruteForce(GameTestHelper helper, String label,
            SnowLee.HeightLookup heights, int[] cardinalStrides, int[] diagonalStrides, double slope, float[] output) {
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, heights, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output);
        for (int iz = 0; iz < TEST_MAP; iz += 4) {
            for (int ix = 0; ix < TEST_MAP; ix += 4) {
                float actual = output[iz * TEST_MAP + ix];
                float expected = FogCavity.bruteForceTexel(TEST_MAP, FogCavity.CARDINAL_REACH,
                        FogCavity.DIAGONAL_REACH, heights, -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP,
                        slope, true, ix, iz);
                if (Math.abs(actual - expected) > SCAN_TOLERANCE) {
                    helper.fail(label + " cavity scan differs from brute force at (" + ix + ", " + iz + "): got "
                            + actual + " instead of " + expected);
                    return;
                }
            }
        }
    }

    private static void checkFloatNear(GameTestHelper helper, String what, float actual, float expected,
            float tolerance) {
        helper.assertTrue(Math.abs(actual - expected) <= tolerance, what + " is " + actual + " instead of "
                + expected);
    }

    private static void checkNear(GameTestHelper helper, String what, double actual, double expected) {
        helper.assertTrue(Math.abs(actual - expected) <= TOLERANCE * Math.max(1.0, Math.abs(expected)), what + " is "
                + actual + " instead of " + expected);
    }

    private static void checkNearWithin(GameTestHelper helper, String what, double actual, double expected,
            double tolerance) {
        helper.assertTrue(Math.abs(actual - expected) <= tolerance, what + " is " + actual + " instead of "
                + expected);
    }
}
