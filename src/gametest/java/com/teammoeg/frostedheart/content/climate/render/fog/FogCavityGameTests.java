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
        float[] rimOutput = new float[TEST_MAP * TEST_MAP];
        double slope = FogCavity.ENCLOSURE_SLOPE;
        double windFloor = FogCavity.effectiveFloor(0.0, 10.0);
        checkNear(helper, "blizzard wind effectiveFloor at default 0.0", windFloor, 0.0);
        checkNear(helper, "blizzard wind effectiveFloor at 0.5", FogCavity.effectiveFloor(0.5, 10.0), 0.5);

        // 1a. Flat surface across 10-block ravine (air x in [-5, 4], walls x <= -6 and x >= 5, d_- + d_+ = 11):
        SnowLee.HeightLookup ravine10 = (x, z) -> (x <= -6 || x >= 5) ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "10-block ravine", ravine10, cardinalStrides, diagonalStrides, slope,
                output, rimOutput);
        float center10 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        float expected10 = (float) (RIM - 0.5 * slope * 11.0);
        checkFloatNear(helper, "10-block ravine center enclosure", center10, expected10, 0.01F);
        for (int x = -5; x <= 4; x++) {
            int idx = TEST_HALF * TEST_MAP + (x + TEST_HALF);
            checkFloatNear(helper, "10-block ravine wall-to-wall flatness at x=" + x, output[idx], center10, 1.0e-3F);
            checkFloatNear(helper, "10-block ravine lower rim at x=" + x, rimOutput[idx], RIM, 1.0e-3F);
        }
        double factorBottom10 = FogCavity.cavityFactor(FLOOR + 2.0, center10, 4.0, windFloor);
        helper.assertTrue(factorBottom10 > 0.0 && factorBottom10 < 0.015,
                "10-block ravine bottom fog factor at share 0 was " + factorBottom10 + ", expected < 0.015");
        double factorRim10 = FogCavity.cavityFactor(RIM, center10, 4.0, windFloor);
        checkNear(helper, "10-block ravine rim fog factor", factorRim10, 1.0);

        // 1b. Flat surface across 20-block ravine (air x in [-10, 9], walls x <= -11 and x >= 10, d_- + d_+ = 21):
        SnowLee.HeightLookup ravine20 = (x, z) -> (x <= -11 || x >= 10) ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "20-block ravine", ravine20, cardinalStrides, diagonalStrides, slope,
                output, rimOutput);
        float center20 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        float expected20 = (float) (RIM - 0.5 * slope * 21.0);
        checkFloatNear(helper, "20-block ravine center enclosure", center20, expected20, 0.01F);
        helper.assertTrue(center20 < center10 - 2.0F,
                "20-block ravine enclosure " + center20 + " was not lower than 10-block " + center10);
        for (int x = -10; x <= 9; x++) {
            int idx = TEST_HALF * TEST_MAP + (x + TEST_HALF);
            checkFloatNear(helper, "20-block ravine wall-to-wall flatness at x=" + x, output[idx], center20, 1.0e-3F);
            checkFloatNear(helper, "20-block ravine lower rim at x=" + x, rimOutput[idx], RIM, 1.0e-3F);
        }

        // 1c. Lopsided ravine (left rim 80 at x <= -6, right rim 55 at x >= 6, floor 40): clamps to lower rim 55:
        short highRim = 80;
        short lowRim = 55;
        SnowLee.HeightLookup lopsidedRavine = (x, z) -> x <= -6 ? highRim : (x >= 6 ? lowRim : FLOOR);
        assertCavityMatchesBruteForce(helper, "lopsided ravine", lopsidedRavine, cardinalStrides, diagonalStrides,
                slope, output, rimOutput);
        for (int x = -5; x <= 5; x++) {
            int idx = TEST_HALF * TEST_MAP + (x + TEST_HALF);
            checkFloatNear(helper, "lopsided ravine clamped to lower rim at x=" + x, output[idx], lowRim, 1.0e-3F);
            checkFloatNear(helper, "lopsided ravine lower rim at x=" + x, rimOutput[idx], lowRim, 1.0e-3F);
        }

        // 1d. 10-block ravine with ground 5 blocks higher 12 blocks behind each lip: the lip stays the ravine's own:
        SnowLee.HeightLookup terracedRavine = (x, z) -> (x <= -18 || x >= 17) ? (short) (RIM + 5)
                : ((x <= -6 || x >= 5) ? RIM : FLOOR);
        assertCavityMatchesBruteForce(helper, "terraced ravine", terracedRavine, cardinalStrides, diagonalStrides,
                slope, output, rimOutput);
        for (int x = -5; x <= 4; x++) {
            int idx = TEST_HALF * TEST_MAP + (x + TEST_HALF);
            checkFloatNear(helper, "terraced ravine enclosure at x=" + x, output[idx], expected10, 0.01F);
            checkFloatNear(helper, "terraced ravine lower lip at x=" + x, rimOutput[idx], RIM, 1.0e-3F);
        }

        // 2. Ravine at 22.5 degrees (halfway between cardinal and diagonal axes):
        double angle225 = Math.toRadians(22.5);
        double cos225 = Math.cos(angle225);
        double sin225 = Math.sin(angle225);
        SnowLee.HeightLookup ravine225 = (x, z) -> Math.abs(x * cos225 + z * sin225) >= 6.0 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "ravine 22.5 deg", ravine225, cardinalStrides, diagonalStrides, slope,
                output, rimOutput);
        float center225 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        helper.assertTrue(center225 > 59.0F && center225 < 62.5F,
                "ravine 22.5 deg center enclosure was " + center225 + ", expected about 60.5");

        // 3. Ravine at 45 degrees (runs along X = Z, walls at |x - z| / sqrt(2) >= 6):
        double invSqrt2 = Math.sqrt(0.5);
        SnowLee.HeightLookup ravine45 = (x, z) -> Math.abs(x - z) * invSqrt2 >= 6.0 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "ravine 45 deg", ravine45, cardinalStrides, diagonalStrides, slope,
                output, rimOutput);
        float center45 = output[TEST_HALF * TEST_MAP + TEST_HALF];
        helper.assertTrue(center45 > 59.5F && center45 < 62.5F,
                "ravine 45 deg center enclosure was " + center45 + ", expected about 61.3");

        // 4. Single cliff foot (wall on -X only, open plain on +X): NO thinning anywhere:
        SnowLee.HeightLookup singleCliff = (x, z) -> x <= -4 ? RIM : FLOOR;
        assertCavityMatchesBruteForce(helper, "single cliff", singleCliff, cardinalStrides, diagonalStrides, slope,
                output, rimOutput);
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
                -TEST_HALF, TEST_MAP, slope, true, output, rimOutput);
        for (int x = -39; x <= 39; x++) {
            float hCav = output[TEST_HALF * TEST_MAP + (x + TEST_HALF)];
            if (hCav > FLOOR) {
                helper.fail("80-wide valley enclosed column x=" + x + " with h_cav=" + hCav + " > " + FLOOR);
                return;
            }
        }

        // 6. Gentle 1:2 V-valley (slope 0.5 < ENCLOSURE_SLOPE = 0.65): NO thinning at center or along slopes:
        SnowLee.HeightLookup gentleValley = (x, z) -> (short) (FLOOR + Math.abs(x) / 2);
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, gentleValley, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output, rimOutput);
        for (int x = -20; x <= 20; x++) {
            float hCav = output[TEST_HALF * TEST_MAP + (x + TEST_HALF)];
            short terrain = gentleValley.heightAt(x, 0);
            if (hCav > terrain) {
                helper.fail("gentle 1:2 V-valley enclosed column x=" + x + " with h_cav=" + hCav + " > " + terrain);
                return;
            }
        }

        // 7. Opposing 1-wide and 2-wide posts and a 1-wide post opposite a long wall: erased by 3-texel perp min:
        SnowLee.HeightLookup postPair1 = (x, z) -> ((x == -2 || x == 2) && z == 0) ? RIM : FLOOR;
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, postPair1, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output, rimOutput);
        helper.assertTrue(output[TEST_HALF * TEST_MAP + TEST_HALF] <= FLOOR,
                "1-wide post pair created cavity enclosure " + output[TEST_HALF * TEST_MAP + TEST_HALF]);

        SnowLee.HeightLookup postPair2 = (x, z) -> ((x == -2 || x == 2) && (z == 0 || z == 1)) ? RIM : FLOOR;
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, postPair2, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output, rimOutput);
        helper.assertTrue(output[TEST_HALF * TEST_MAP + TEST_HALF] <= FLOOR,
                "2-wide post pair created cavity enclosure " + output[TEST_HALF * TEST_MAP + TEST_HALF]);

        SnowLee.HeightLookup postOppositeWall = (x, z) -> (x >= 2 || (x == -2 && z == 0)) ? RIM : FLOOR;
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, postOppositeWall, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output, rimOutput);
        helper.assertTrue(output[TEST_HALF * TEST_MAP + TEST_HALF] <= FLOOR,
                "1-wide post opposite long wall created cavity enclosure " + output[TEST_HALF * TEST_MAP + TEST_HALF]);

        // 8. Calm wind and fogCavityShare = 1.0 give zero thinning; intermediate wind ramps smoothly:
        checkNear(helper, "calm wind 0.0 factor", FogCavity.windFactor(0.0), 0.0);
        checkNear(helper, "calm wind 0.5 factor", FogCavity.windFactor(FogCavity.CALM_WIND_SPEED), 0.0);
        checkNear(helper, "NaN wind factor", FogCavity.windFactor(Double.NaN), 0.0);
        checkNear(helper, "calm wind effectiveFloor", FogCavity.effectiveFloor(0.0, 0.0), 1.0);
        checkNear(helper, "calm wind cavityFactor in ravine", FogCavity.cavityFactor(42.0, 61.4, 4.0,
                FogCavity.effectiveFloor(0.0, 0.0)), 1.0);
        checkNear(helper, "calm wind wispFactor in ravine", FogCavity.wispFactor(0.01, FogCavity.windFactor(0.0)), 1.0);
        checkNear(helper, "blizzard wispFactor deep in ravine", FogCavity.wispFactor(0.01, FogCavity.windFactor(10.0)),
                0.01);
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

        // Wisps fade deep in a ravine: build real FogStructure texels and compare contrast above rim vs deep inside:
        byte[] structureTexels = new byte[FogStructure.SIZE * FogStructure.SIZE * FogStructure.SIZE
                * FogStructure.CHANNELS];
        FogStructure.build(FogStructure.SEED, structureTexels);
        FogStructure structure = new FogStructure();
        structure.setTexture(structureTexels);
        structure.setDensity(0.5, 0.25, 0.5, 96.0);

        double maxRimContrast = 0.0;
        double maxDeepContrast = 0.0;
        double[] shifts = {0.0, 7.0, 15.0, 23.0, 31.0, 39.0};
        for (double shift : shifts) {
            // Above rim (y = 66.5 > 61.0): full wisp contrast:
            walk.setEye(0, 66, 0, 0.5, 0.5, 0.5);
            walk.setCavity(cavityMap, -FogCavity.HALF_SIZE, -FogCavity.HALF_SIZE, 0.5, 1.0, 4.0);
            walk.setStructure(null);
            double rimBase = walk.opticalDepth(24.0, 0.0, 0.0);
            structure.reset();
            structure.follow(shift, 66.5, 0.5, 0.0, 0.0, 0.0, 16.0, 1.5);
            walk.setStructure(structure);
            double rimStructured = walk.opticalDepth(24.0, 0.0, 0.0);
            maxRimContrast = Math.max(maxRimContrast, Math.abs(rimStructured - rimBase) / rimBase);

            // Deep in ravine (y = 42.5, depth = 18.5, openFactor = exp(-18.5 / 4) = 0.0098) with floor = 0.5 so
            // residual fog stays visible while moving wisps fade out almost completely:
            walk.setEye(0, 42, 0, 0.5, 0.5, 0.5);
            walk.setCavity(cavityMap, -FogCavity.HALF_SIZE, -FogCavity.HALF_SIZE, 0.5, 1.0, 4.0);
            walk.setStructure(null);
            double deepBase = walk.opticalDepth(24.0, 0.0, 0.0);
            structure.reset();
            structure.follow(shift, 42.5, 0.5, 0.0, 0.0, 0.0, 16.0, 1.5);
            walk.setStructure(structure);
            double deepStructured = walk.opticalDepth(24.0, 0.0, 0.0);
            maxDeepContrast = Math.max(maxDeepContrast, Math.abs(deepStructured - deepBase) / deepBase);
        }
        helper.assertTrue(maxRimContrast > 0.08,
                "above-rim wisp contrast was only " + maxRimContrast + ", expected > 0.08");
        helper.assertTrue(maxDeepContrast < 0.02,
                "deep-ravine wisp contrast was " + maxDeepContrast + ", expected < 0.02");

        // With default fogCavityShare = 0.0 deep in a ravine, fog itself clears to < 1.5% of open air:
        walk.setEye(0, 42, 0, 0.5, 0.5, 0.5);
        walk.setCavity(cavityMap, -FogCavity.HALF_SIZE, -FogCavity.HALF_SIZE, 0.0, 1.0, 4.0);
        double defaultRavineDepth = walk.opticalDepth(24.0, 0.0, 0.0);
        helper.assertTrue(defaultRavineDepth < 2.4 * 0.015,
                "default-share deep ravine depth was " + defaultRavineDepth + ", expected < 0.036");

        // Warm allocation check with BOTH cavity AND FogStructure active:
        walk.setCavity(cavityMap, -FogCavity.HALF_SIZE, -FogCavity.HALF_SIZE, 0.0, 1.0, 4.0);
        assertWarmCavityWalkAllocatesNothing(helper, walk);
        walk.setStructure(null);

        // NearCavityQuery + FogCullingDistance checks:
        double extinction = 0.05;
        double baseDistance = FogCullingDistance.opaqueDistance(extinction, 64.0, 0.0, 0.0, 64.0, 1000.0, 0.8, 96.0);
        double sameDistance = FogCullingDistance.opaqueDistance(extinction, 64.0, 0.0, 0.0, 64.0, 1000.0, 0.8, 96.0,
                1.0, 0.0, 0.0);
        checkNear(helper, "11-arg culling matches 8-arg with cavity and shelter off", sameDistance, baseDistance);

        // NearCavityQuery matches brute-force max over the radius-8 disk and gates cavity thinning in the culling bound:
        FogCavity.NearCavityQuery query = new FogCavity.NearCavityQuery();
        SnowLee.HeightLookup plain = (x, z) -> FLOOR;
        SnowLee.HeightLookup singleCliff = (x, z) -> x <= -4 ? RIM : FLOOR;
        SnowLee.HeightLookup ravine10 = (x, z) -> (x <= -6 || x >= 5) ? RIM : FLOOR;
        float[] ravineScan = new float[TEST_MAP * TEST_MAP];
        FogCavity.scan(TEST_MAP, FogCavity.cardinalStrides(), FogCavity.diagonalStrides(), ravine10, -TEST_HALF,
                -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, FogCavity.ENCLOSURE_SLOPE, true, ravineScan);

        // Open plain and single cliff foot have no open cavity column above terrain -> returns NO_HEIGHT, and bound
        // equals the bound without the cavity map:
        float plainMax = query.maxCavityHeightNear(plain, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                TEST_MAP, 0, 0);
        helper.assertTrue(plainMax <= FogCavity.NO_HEIGHT_THRESHOLD,
                "plain near-cavity max was " + plainMax + ", expected NO_HEIGHT");
        float cliffMax = query.maxCavityHeightNear(singleCliff, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, 0, 0);
        helper.assertTrue(cliffMax <= FogCavity.NO_HEIGHT_THRESHOLD,
                "single cliff near-cavity max was " + cliffMax + ", expected NO_HEIGHT");
        boolean openNearCavity = plainMax > FLOOR + 1.6;
        helper.assertFalse(openNearCavity, "open plain triggered near-cavity culling bound");
        double blizzardOpenExt = HeightFog.extinctionAt(HeightFog.extinctionFromVisibility(80.0), 63.0, 128.0, 65.6);
        double openWithQuery = FogCullingDistance.opaqueDistance(blizzardOpenExt, 65.6, 128.0, -64.0, 120.0, 512.0,
                0.8, 96.0, openNearCavity ? 0.0 : 1.0, openNearCavity ? 192.0 : 0.0, 30.0);
        double openWithoutCavity = FogCullingDistance.opaqueDistance(blizzardOpenExt, 65.6, 128.0, -64.0, 120.0, 512.0,
                0.8, 96.0, 1.0, 0.0, 30.0);
        checkNear(helper, "open-ground bound equals bound without cavity map", openWithQuery, openWithoutCavity);

        // Plateau above the ravine rim (eyeY = 66.6 > 61.425) and plateau 12 blocks away (x = -18): NO near cavity:
        float rimPlateauMax = query.maxCavityHeightNear(ravine10, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, -7, 0);
        helper.assertFalse(rimPlateauMax > RIM + 1.6,
                "plateau above ravine rim triggered near-cavity at eyeY = 66.6 with max " + rimPlateauMax);
        float farPlateauMax = query.maxCavityHeightNear(ravine10, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, -18, 0);
        helper.assertTrue(farPlateauMax <= FogCavity.NO_HEIGHT_THRESHOLD,
                "far plateau had near-cavity max " + farPlateauMax + ", expected NO_HEIGHT");

        // Inside the ravine (x = 0, eyeY = 42 and 60) and 6 blocks outside wall at eyeY = 55: near cavity IS active,
        // matching the scan max over open-cavity columns in the radius-8 disk:
        float insideMax = query.maxCavityHeightNear(ravine10, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                TEST_MAP, 0, 0);
        float expectedInsideMax = (float) (RIM - 0.5 * FogCavity.ENCLOSURE_SLOPE * 11.0);
        checkFloatNear(helper, "inside ravine near-cavity max", insideMax, expectedInsideMax, 0.01F);
        helper.assertTrue(insideMax > 60.0, "ravine near-cavity max " + insideMax + " was not above eyeY 60");
        float sixOutMax = query.maxCavityHeightNear(ravine10, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                TEST_MAP, -11, 0);
        checkFloatNear(helper, "6 blocks from ravine wall near-cavity max", sixOutMax, expectedInsideMax, 0.01F);
        helper.assertTrue(sixOutMax > 55.0, "6 blocks from ravine at eyeY 55 did not detect cavity");

        // Compare NearCavityQuery against explicit max over open-cavity columns in radius-8 disk at (-4, 3):
        int probeX = -4;
        int probeZ = 3;
        float scanDiskMax = (float) FogCavity.NO_HEIGHT;
        for (int dz = -FogCavity.NEAR_CAVITY_RADIUS; dz <= FogCavity.NEAR_CAVITY_RADIUS; dz++) {
            for (int dx = -FogCavity.NEAR_CAVITY_RADIUS; dx <= FogCavity.NEAR_CAVITY_RADIUS; dx++) {
                if (dx * dx + dz * dz <= FogCavity.NEAR_CAVITY_RADIUS * FogCavity.NEAR_CAVITY_RADIUS) {
                    int wx = probeX + dx;
                    int wz = probeZ + dz;
                    float val = ravineScan[(wz + TEST_HALF) * TEST_MAP + (wx + TEST_HALF)];
                    if (val > ravine10.heightAt(wx, wz)) {
                        scanDiskMax = Math.max(scanDiskMax, val);
                    }
                }
            }
        }
        float queryDiskMax = query.maxCavityHeightNear(ravine10, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, probeX, probeZ);
        checkFloatNear(helper, "NearCavityQuery matches scan disk max", queryDiskMax, scanDiskMax, SCAN_TOLERANCE);

        // With cavity 0.0 (default) out to 192 and shelter 30 inside a ravine, culling distance never clips terrain:
        double expectedZeroCavDistance = 192.0 + FogCullingDistance.OPAQUE_OPTICAL_DEPTH / extinction;
        double actualZeroCavDistance = FogCullingDistance.opaqueDistance(extinction, 64.0, 0.0, 0.0, 64.0, 1000.0,
                0.8, 96.0, 0.0, 192.0, 30.0);
        checkNearWithin(helper, "uniform fog culling distance with cavity 0.0 and shelter 30", actualZeroCavDistance,
                expectedZeroCavDistance, 1.0e-4);

        double blizzardEyeExt = HeightFog.extinctionAt(HeightFog.extinctionFromVisibility(80.0), 63.0, 128.0, 42.0);
        double blizzardDistance = FogCullingDistance.opaqueDistance(blizzardEyeExt, 42.0, 128.0, -64.0, 120.0, 512.0,
                0.8, 96.0, 0.0, 192.0, 30.0);
        helper.assertTrue(Double.isFinite(blizzardDistance) && blizzardDistance > 192.0,
                "blizzard cavity 0.0 culling distance was " + blizzardDistance + ", expected finite > 192");
        double[] verticalOffsets = {0.0, 78.0};
        for (double vy : verticalOffsets) {
            double len = Math.sqrt(blizzardDistance * blizzardDistance + vy * vy);
            double tau192 = HeightFog.opticalDepth(blizzardEyeExt, 192.0, 192.0 / len * vy, 128.0);
            double tauFull = HeightFog.opticalDepth(blizzardEyeExt, len, vy, 128.0);
            double lowerBoundDepth = tauFull - tau192;
            helper.assertTrue(lowerBoundDepth >= FogCullingDistance.OPAQUE_OPTICAL_DEPTH - 1.0e-5,
                    "ray vy=" + vy + " at culling distance " + blizzardDistance + " has lower-bound depth "
                            + lowerBoundDepth + " below opaque depth " + FogCullingDistance.OPAQUE_OPTICAL_DEPTH);
        }

        // Warm allocation check for NearCavityQuery:
        assertWarmNearCavityQueryAllocatesNothing(helper, query, ravine10);

        helper.succeed();
    }

    private static void assertWarmNearCavityQueryAllocatesNothing(GameTestHelper helper,
            FogCavity.NearCavityQuery query, SnowLee.HeightLookup heights) {
        float sum = 0.0F;
        for (int warmup = 0; warmup < 50; warmup++) {
            sum += query.maxCavityHeightNear(heights, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                    TEST_MAP, 0, 0);
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
        for (int call = 0; call < 20; call++) {
            sum += query.maxCavityHeightNear(heights, TEST_MAP, -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                    TEST_MAP, 0, 0);
        }
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("Warm NearCavityQuery allocated {} bytes (sum {})", allocated, sum);
        helper.assertTrue(allocated == 0L, "Warm NearCavityQuery allocated " + allocated + " bytes instead of 0");
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
            SnowLee.HeightLookup heights, int[] cardinalStrides, int[] diagonalStrides, double slope, float[] output,
            float[] rimOutput) {
        FogCavity.scan(TEST_MAP, cardinalStrides, diagonalStrides, heights, -TEST_HALF, -TEST_HALF, -TEST_HALF,
                -TEST_HALF, TEST_MAP, slope, true, output, rimOutput);
        float[] expectedPair = new float[2];
        for (int iz = 0; iz < TEST_MAP; iz += 4) {
            for (int ix = 0; ix < TEST_MAP; ix += 4) {
                int idx = iz * TEST_MAP + ix;
                float actual = output[idx];
                float actualRim = rimOutput[idx];
                FogCavity.bruteForceTexelPair(TEST_MAP, FogCavity.CARDINAL_REACH, FogCavity.DIAGONAL_REACH, heights,
                        -TEST_HALF, -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, slope, true, ix, iz, expectedPair);
                if (Math.abs(actual - expectedPair[0]) > SCAN_TOLERANCE) {
                    helper.fail(label + " cavity scan differs from brute force at (" + ix + ", " + iz + "): got "
                            + actual + " instead of " + expectedPair[0]);
                    return;
                }
                if (Math.abs(actualRim - expectedPair[1]) > SCAN_TOLERANCE) {
                    helper.fail(label + " cavity rim differs from brute force at (" + ix + ", " + iz + "): got "
                            + actualRim + " instead of " + expectedPair[1]);
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
