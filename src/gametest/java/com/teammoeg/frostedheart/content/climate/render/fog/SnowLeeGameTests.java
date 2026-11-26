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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the smoothed macro terrain surface S and relief lee map twin (SnowLee) against independent brute-force
 * references on synthetic terrain layouts, verifies overhang behavior, terrain-following vertical velocity, sub-box
 * flake density over a hill, rebuild trigger rules, and shared GLSL constants across all snow and cavity shaders.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowLeeGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double TOLERANCE = 1.0e-9;
    private static final float SCAN_TOLERANCE = 2.0e-3F;
    private static final int TEST_MAP = 256;
    private static final int TEST_HALF = TEST_MAP / 2;
    private static final int TEST_SMOOTH = TEST_MAP / SnowLee.SMOOTH_SCALE;
    private static final int TEST_REACH = 128;
    private static final int[] TEST_STRIDES = {1, 2, 4, 8, 16, 32, 64};
    private static final short GROUND = 64;
    private static final Pattern GLSL_CONST_PATTERN = Pattern.compile(
            "^\\s*const\\s+(?:int|uint|float)\\s+([A-Z0-9_]+)\\s*=\\s*([^;]+);");

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void smoothTerrainAndReliefScanMatchBruteForceOnAllShapes(GameTestHelper helper) {
        float[] smooth = new float[TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchA = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchB = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] leeOut = new float[TEST_MAP * TEST_MAP];
        float[] slopeOut = new float[TEST_MAP * TEST_MAP];
        float[] rawA = new float[TEST_MAP * TEST_MAP];
        float[] rawB = new float[TEST_MAP * TEST_MAP];
        float[] relA = new float[TEST_MAP * TEST_MAP];
        float[] relB = new float[TEST_MAP * TEST_MAP];
        double slope = 0.21;

        // 0. Normalized Gaussian smoothing matches brute force 2D sum and does not droop next to UNKNOWN columns:
        SnowLee.HeightLookup partialFlat = (x, z) -> x < -40 ? FarHeights.UNKNOWN : GROUND;
        SnowLee.buildSmoothHeights(TEST_MAP, partialFlat, -TEST_HALF, -TEST_HALF, smooth, smoothScratchA,
                smoothScratchB);
        int[] checkCoords = {0, 10, 44, 64, 90, 127};
        for (int tz : checkCoords) {
            for (int tx : checkCoords) {
                float expected = SnowLee.bruteForceSmoothTexel(TEST_MAP, partialFlat, -TEST_HALF, -TEST_HALF, tx, tz);
                float actual = smooth[tz * TEST_SMOOTH + tx];
                checkFloatNear(helper, "partialFlat smooth at (" + tx + ", " + tz + ")", actual, expected, 1.0e-2F);
                if (tx >= 44) {
                    checkFloatNear(helper, "normalized smooth next to UNKNOWN", actual, GROUND, 1.0e-2F);
                }
            }
        }

        // 1. Plain: S = 64 everywhere, h_lee < 64, terrainSlope = 0, full snow everywhere:
        SnowLee.HeightLookup plain = (x, z) -> GROUND;
        runSmoothAndScan(plain, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA, smoothScratchB,
                leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "plain", plain, smooth, 1.0, 0.0, slope, true, leeOut);
        float plainLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, 0.5, 0.5, 1.0, 0.0);
        float plainSlope = SnowLee.sampleBilinear(slopeOut, TEST_MAP, 0.5, 0.5, 1.0, 0.0);
        helper.assertTrue(plainLee < GROUND, "plain got lee height " + plainLee + " instead of below " + GROUND);
        checkFloatNear(helper, "plain terrain slope", plainSlope, 0.0F, 1.0e-3F);

        // 2. Rounded 60-block hill centered at x = -56, z = 0 (radius 88 raised cosine, foot at x = +32):
        // leaves the plain behind it (x = 40..100, where terrain is 64) its full snow (h_lee <= 64):
        SnowLee.HeightLookup roundedHill = (x, z) -> {
            double dx = x + 56.0;
            double dz = z;
            double radius = Math.sqrt(dx * dx + dz * dz);
            if (radius >= 88.0) {
                return GROUND;
            }
            double rise = 60.0 * 0.5 * (1.0 + Math.cos(Math.PI * radius / 88.0));
            return (short) Math.round(GROUND + rise);
        };
        runSmoothAndScan(roundedHill, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "rounded 60-block hill", roundedHill, smooth, 1.0, 0.0, slope, true,
                leeOut);
        // Upwind slope has positive slope, downwind slope has negative slope:
        float hillUpwindSlope = SnowLee.sampleBilinear(slopeOut, TEST_MAP, -85.5, 0.5, 1.0, 0.0);
        float hillDownwindSlope = SnowLee.sampleBilinear(slopeOut, TEST_MAP, -25.5, 0.5, 1.0, 0.0);
        helper.assertTrue(hillUpwindSlope > 0.3F, "hill upwind slope was " + hillUpwindSlope + ", expected > 0.3");
        helper.assertTrue(hillDownwindSlope < -0.3F, "hill downwind slope was " + hillDownwindSlope
                + ", expected < -0.3");
        // Plain behind the rounded hill (x = 40..100, where terrain is back to 64) keeps full snow (h_lee <= H):
        for (int x = 40; x <= 100; x += 10) {
            float lee = SnowLee.sampleBilinear(leeOut, TEST_MAP, x + 0.5, 0.5, 1.0, 0.0);
            short terrain = roundedHill.heightAt(x, 0);
            if (lee > terrain) {
                helper.fail("rounded 60-block hill cast lee " + lee + " onto plain of height " + terrain + " at x = "
                        + x);
                return;
            }
        }

        // 3. Sharp 45-degree ridge (50 blocks tall at x = -50, foot at x = 0): lee behind crest, reattaches past foot:
        SnowLee.HeightLookup sharpRidge = (x, z) -> (short) Math.max(GROUND, 114 - Math.abs(x + 50));
        runSmoothAndScan(sharpRidge, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "45-degree ridge", sharpRidge, smooth, 1.0, 0.0, slope, true, leeOut);
        float behindRidgeCrest = SnowLee.sampleBilinear(leeOut, TEST_MAP, -44.5, 0.5, 1.0, 0.0);
        helper.assertTrue(behindRidgeCrest > sharpRidge.heightAt(-45, 0) + 1.0F,
                "45-degree ridge crest cast no separation lee, got " + behindRidgeCrest);
        // Far past foot (x = 50, 50 blocks past foot = 100 blocks from crest) has reattached (raw scan would be 93!):
        float farPastRidge = SnowLee.sampleBilinear(leeOut, TEST_MAP, 50.5, 0.5, 1.0, 0.0);
        helper.assertTrue(farPastRidge <= GROUND, "45-degree ridge failed to reattach by x = 50, got " + farPastRidge);

        // 4a. 20-block cliff downwind (plateau 84 for x <= -20, plain 64 for x > -20): lee behind rim, reattaches ~48:
        SnowLee.HeightLookup downwindCliff = (x, z) -> x <= -20 ? (short) 84 : GROUND;
        runSmoothAndScan(downwindCliff, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "downwind 20-block cliff", downwindCliff, smooth, 1.0, 0.0, slope,
                true, leeOut);
        float cliffRim = SnowLee.sampleBilinear(leeOut, TEST_MAP, -18.5, 0.5, 1.0, 0.0);
        helper.assertTrue(cliffRim > 81.0F && cliffRim <= 84.0F,
                "20-block cliff rim lee at t=1 was " + cliffRim + " instead of near 83");
        float cliffMid = SnowLee.sampleBilinear(leeOut, TEST_MAP, -9.5, 0.5, 1.0, 0.0);
        helper.assertTrue(cliffMid > 72.0F, "20-block cliff lee 10 blocks behind was " + cliffMid + ", expected > 72");
        float cliffReattached = SnowLee.sampleBilinear(leeOut, TEST_MAP, 40.5, 0.5, 1.0, 0.0);
        helper.assertTrue(cliffReattached <= GROUND,
                "20-block cliff lee did not reattach by 60 blocks behind, got " + cliffReattached);

        // 4b. 20-block cliff upwind foot (plain 64 for x < 20, plateau 84 for x >= 20): NO false lee at upwind foot:
        SnowLee.HeightLookup upwindCliff = (x, z) -> x >= 20 ? (short) 84 : GROUND;
        runSmoothAndScan(upwindCliff, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "upwind 20-block cliff", upwindCliff, smooth, 1.0, 0.0, slope,
                true, leeOut);
        for (int x = 0; x <= 19; x++) {
            float upwindLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, x + 0.5, 0.5, 1.0, 0.0);
            if (upwindLee > GROUND) {
                helper.fail("20-block cliff cast false lee " + upwindLee + " at upwind foot x = " + x);
                return;
            }
        }

        // 5. House (8x8 footprint, 5 blocks tall at x in [-24, -17], z in [-4, 3]): keeps its lee:
        SnowLee.HeightLookup house = (x, z) -> (x >= -24 && x <= -17 && z >= -4 && z <= 3) ? (short) 69 : GROUND;
        runSmoothAndScan(house, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA, smoothScratchB,
                leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "8x8 house", house, smooth, 1.0, 0.0, slope, true, leeOut);
        float behindHouse = SnowLee.sampleBilinear(leeOut, TEST_MAP, -6.5, 0.5, 1.0, 0.0);
        helper.assertTrue(behindHouse > 66.0F && behindHouse < 67.5F,
                "8x8 house lee 10 blocks behind was " + behindHouse + " instead of about 66.8");

        // 6. 1-block-thick, 7-wide wall at x = -20, z in [-3, 3], height 68: keeps its lee, wall top stays open:
        SnowLee.HeightLookup wall = (x, z) -> (x == -20 && z >= -3 && z <= 3) ? (short) 68 : GROUND;
        runSmoothAndScan(wall, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA, smoothScratchB,
                leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "7-wide wall", wall, smooth, 1.0, 0.0, slope, true, leeOut);
        float wallTop = SnowLee.sampleBilinear(leeOut, TEST_MAP, -19.5, 0.5, 1.0, 0.0);
        helper.assertTrue(wallTop < GROUND, "wall top got lee " + wallTop + " instead of below " + GROUND);
        float behindWall = SnowLee.sampleBilinear(leeOut, TEST_MAP, -9.5, 0.5, 1.0, 0.0);
        helper.assertTrue(behindWall > 65.5F && behindWall < 66.2F,
                "7-wide wall lee 10 blocks behind was " + behindWall + " instead of about 65.9");

        // Diagonal 45-degree wind over a 1-block-thick long wall: keeps its lee and matches brute force:
        double diag = Math.sqrt(0.5);
        SnowLee.HeightLookup longWall = (x, z) -> (x == -8 && z >= -40 && z <= 40) ? (short) 74 : GROUND;
        runSmoothAndScan(longWall, TEST_MAP, TEST_STRIDES, diag, diag, slope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        assertScanMatchesBruteForceSampled(helper, "diagonal 1-thick wall", longWall, smooth, diag, diag, slope, true,
                leeOut);
        float diagLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, 0.5, 0.5, diag, diag);
        helper.assertTrue(diagLee > 70.0F && diagLee < 72.5F,
                "diagonal 1-thick wall lee was " + diagLee + " instead of about 71.3");

        // 7. 1-block tower at 0, 30, 45 deg and 2-wide pillar cast NO lee; 3-wide wall keeps its lee:
        SnowLee.HeightLookup tower = (x, z) -> (x == -20 && z == 0) ? (short) 84 : GROUND;
        double[] towerAngles = {0.0, Math.toRadians(30.0), Math.toRadians(45.0)};
        for (double angle : towerAngles) {
            double dirX = Math.cos(angle);
            double dirZ = Math.sin(angle);
            runSmoothAndScan(tower, TEST_MAP, TEST_STRIDES, dirX, dirZ, slope, true, smooth, smoothScratchA,
                    smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
            for (int index = 0; index < leeOut.length; index++) {
                if (leeOut[index] > GROUND) {
                    helper.fail("1-block tower at " + Math.toDegrees(angle) + " deg cast lee " + leeOut[index]);
                    return;
                }
            }
        }
        SnowLee.HeightLookup twoWide = (x, z) -> (x == -20 && (z == 0 || z == 1)) ? (short) 84 : GROUND;
        runSmoothAndScan(twoWide, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA, smoothScratchB,
                leeOut, slopeOut, rawA, rawB, relA, relB);
        for (int index = 0; index < leeOut.length; index++) {
            if (leeOut[index] > GROUND) {
                helper.fail("2-wide pillar cast lee " + leeOut[index]);
                return;
            }
        }
        SnowLee.HeightLookup threeWide = (x, z) -> (x == -20 && z >= -1 && z <= 1) ? (short) 84 : GROUND;
        runSmoothAndScan(threeWide, TEST_MAP, TEST_STRIDES, 1.0, 0.0, slope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        float threeWideLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, -9.5, 0.5, 1.0, 0.0);
        helper.assertTrue(threeWideLee > 81.0F, "3-wide wall lee 10 blocks behind was " + threeWideLee
                + ", expected > 81");

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void fullSizeScanReaches256AndDoesNotWrapOutsideWindow(GameTestHelper helper) {
        int[] strides = SnowLee.passStrides();
        int covered = 1;
        for (int stride : strides) {
            helper.assertTrue(stride <= covered, "stride " + stride + " exceeds covered range " + covered
                    + ", leaving a gap in the doubling scan");
            covered += stride;
        }
        helper.assertTrue(covered == SnowLee.REACH, "production strides cover " + covered + " instead of "
                + SnowLee.REACH);

        float[] smooth = new float[SnowLee.SMOOTH_SIZE * SnowLee.SMOOTH_SIZE];
        float[] smoothScratchA = new float[2 * SnowLee.SMOOTH_SIZE * SnowLee.SMOOTH_SIZE];
        float[] smoothScratchB = new float[2 * SnowLee.SMOOTH_SIZE * SnowLee.SMOOTH_SIZE];
        float[] leeOut = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        float[] slopeOut = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        float[] rawA = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        float[] rawB = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        float[] relA = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        float[] relB = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        double slope = 0.2;

        // Narrow tall wall 200 blocks upwind (within REACH = 256), huge mountain 400 blocks downwind, huge wall outside:
        SnowLee.HeightLookup terrain = (x, z) -> {
            if (x == -200) {
                return (short) 200;
            }
            if (x >= 400) {
                return (short) 300;
            }
            if (x < -512) {
                return (short) 300;
            }
            return GROUND;
        };
        SnowLee.buildSmoothHeights(SnowLee.MAP_SIZE, terrain, -SnowLee.HALF_SIZE, -SnowLee.HALF_SIZE, smooth,
                smoothScratchA, smoothScratchB);
        SnowLee.scan(SnowLee.MAP_SIZE, strides, terrain, smooth, SnowLee.SMOOTH_SIZE, -SnowLee.HALF_SIZE,
                -SnowLee.HALF_SIZE, 0, 0, -SnowLee.HALF_SIZE, -SnowLee.HALF_SIZE, SnowLee.MAP_SIZE, 1.0, 0.0, slope,
                true, leeOut, slopeOut, rawA, rawB, relA, relB);

        float atAnchor = SnowLee.sampleBilinear(leeOut, SnowLee.MAP_SIZE, 0.5, 0.5, 1.0, 0.0);
        // Raw gives 200 - 40 = 160; thin 1-block wall raises S(-200) by ~3.4 blocks -> S + e_rel ~ 156.6:
        helper.assertTrue(atAnchor > 152.0F && atAnchor <= 160.1F,
                "200-block upwind tall wall at anchor was " + atAnchor + " instead of about 156");

        // Upwind of the -200 wall (at x = -220), neither the out-of-window wall nor downwind mountain may wrap in:
        float upwindOfWall = SnowLee.sampleBilinear(leeOut, SnowLee.MAP_SIZE, -219.5, 0.5, 1.0, 0.0);
        helper.assertTrue(upwindOfWall <= GROUND, "upwind of wall leaked out-of-window or wrapped terrain: "
                + upwindOfWall);

        // Diagonal out-of-window terrain must not leak in:
        double diag = Math.sqrt(0.5);
        SnowLee.HeightLookup outsideOnly = (x, z) -> (x < -512 || x >= 512 || z < -512 || z >= 512)
                ? (short) 300 : GROUND;
        SnowLee.buildSmoothHeights(SnowLee.MAP_SIZE, outsideOnly, -SnowLee.HALF_SIZE, -SnowLee.HALF_SIZE, smooth,
                smoothScratchA, smoothScratchB);
        SnowLee.scan(SnowLee.MAP_SIZE, strides, outsideOnly, smooth, SnowLee.SMOOTH_SIZE, -SnowLee.HALF_SIZE,
                -SnowLee.HALF_SIZE, 0, 0, -SnowLee.HALF_SIZE, -SnowLee.HALF_SIZE, SnowLee.MAP_SIZE, diag, diag, slope,
                true, leeOut, slopeOut, rawA, rawB, relA, relB);
        float diagCenter = SnowLee.sampleBilinear(leeOut, SnowLee.MAP_SIZE, 0.5, 0.5, diag, diag);
        helper.assertTrue(diagCenter <= GROUND, "diagonal scan leaked out-of-window terrain: " + diagCenter);

        float outOfBounds = SnowLee.sampleBilinear(leeOut, SnowLee.MAP_SIZE, 600.0, 0.0, 1.0, 0.0);
        checkFloatNear(helper, "out-of-bounds lookup", outOfBounds, (float) SnowLee.NO_HEIGHT, 0.01F);

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 60)
    public static void leeShareOverhangVerticalVelocityAndRebuildTriggersBehaveAsDesigned(GameTestHelper helper) {
        // Flat ground and slab/snow layer above columnHeight - 1.0:
        checkNear(helper, "flat ground at ground", SnowLee.leeShare(64.0, 63.85, 64.0, 64.0, 3.0), 1.0);
        checkNear(helper, "slab on flat ground", SnowLee.leeShare(63.5, 63.85, 64.0, 64.0, 3.0), 1.0);
        checkNear(helper, "snow layer on flat ground", SnowLee.leeShare(63.125, 63.85, 64.0, 64.0, 3.0), 1.0);
        checkNear(helper, "flat ground with band 0", SnowLee.leeShare(64.0, 63.85, 64.0, 64.0, 0.0), 1.0);

        // Deep lee and shallow lee tip above ground:
        checkNear(helper, "deep lee on floor", SnowLee.leeShare(64.0, 70.0, 64.0, 64.0, 3.0), 0.0);
        checkNear(helper, "deep lee at surface", SnowLee.leeShare(70.0, 70.0, 64.0, 64.0, 3.0), 0.0);
        checkNear(helper, "deep lee halfway through band", SnowLee.leeShare(71.5, 70.0, 64.0, 64.0, 3.0), 0.5);
        checkNear(helper, "deep lee above band", SnowLee.leeShare(73.0, 70.0, 64.0, 64.0, 3.0), 1.0);
        checkNear(helper, "shallow lee halfway through tip band", SnowLee.leeShare(65.5, 65.0, 64.0, 64.0, 3.0), 0.5);
        checkNear(helper, "sharp band below surface", SnowLee.leeShare(69.99, 70.0, 64.0, 64.0, 0.0), 0.0);
        checkNear(helper, "sharp band at surface", SnowLee.leeShare(70.0, 70.0, 64.0, 64.0, 0.0), 1.0);

        // Overhang cases (worldY < columnHeight - 1.0):
        // Under a 2-block ravine/cliff lip in cross-wind lee (H = 85, h = 82, S = 74, worldY = 70): hidden!
        checkNear(helper, "under cliff lip in lee", SnowLee.leeShare(70.0, 82.0, 85.0, 74.0, 3.0), 0.0);
        // Under a cave-mouth overhang where even S > h (H = 85, h = 78, S = 80, worldY = 70): still hidden!
        checkNear(helper, "under cliff overhang with S > h", SnowLee.leeShare(70.0, 78.0, 85.0, 80.0, 3.0), 0.0);
        // Under a bridge over an open plain (H = 75, h = 63.8, S = 64, worldY = 68 > h): full snow!
        checkNear(helper, "under bridge over open plain", SnowLee.leeShare(68.0, 63.8, 75.0, 64.0, 3.0), 1.0);
        // Under a high arch above a lee surface (H = 80, h = 71, S = 64, worldY = 72.5, band = 3): fades cleanly!
        checkNear(helper, "under high arch above lee", SnowLee.leeShare(72.5, 71.0, 80.0, 64.0, 3.0), 0.5);

        // Unknown heights give 1.0:
        checkNear(helper, "unknown lee height", SnowLee.leeShare(65.0, SnowLee.NO_HEIGHT, 64.0, 64.0, 3.0), 1.0);
        checkNear(helper, "unknown column height", SnowLee.leeShare(65.0, 70.0, SnowLee.NO_HEIGHT, 64.0, 3.0), 1.0);

        // Per-flake lee weight distribution and salt independence:
        int flakes = 100000;
        double weightSum = 0.0;
        int identicalCount = 0;
        for (int flake = 0; flake < flakes; flake++) {
            double open = SnowLee.leeWeight(flake, 1.0);
            double hidden = SnowLee.leeWeight(flake, 0.0);
            if (open != 1.0 || hidden != 0.0) {
                helper.fail("flake " + flake + " has leeWeight " + open + " at 1 and " + hidden + " at 0");
                return;
            }
            double halfLee = SnowLee.leeWeight(flake, 0.5);
            weightSum += halfLee;
            if (Math.abs(halfLee - SnowFlakes.shelterWeight(flake, 0.5)) < 1.0e-9) {
                identicalCount++;
            }
        }
        double meanWeight = weightSum / flakes;
        helper.assertTrue(Math.abs(meanWeight - 0.5) < 0.01, "mean leeWeight at share 0.5 is " + meanWeight);
        helper.assertTrue(identicalCount < flakes * 0.6, "leeWeight coincided with shelterWeight on " + identicalCount
                + " of " + flakes + " flakes");

        // Terrain-following vertical velocity + lee sinking:
        double ownFall = 0.6;
        double windSpeed = 10.0;
        double leeFall = 2.1;
        // Flat ground, no lee: -ownFall:
        checkNear(helper, "flat ground vy",
                SnowLee.verticalVelocity(66.0, 63.8, 64.0, 64.0, 0.0, ownFall, windSpeed, leeFall), -ownFall);
        // Upwind 1:2 slope (+0.5) at S: -0.6 + 5.0 = +4.4:
        checkNear(helper, "upwind slope at S",
                SnowLee.verticalVelocity(64.0, 63.8, 64.0, 64.0, 0.5, ownFall, windSpeed, leeFall), 4.4);
        // Upwind 1:2 slope at S + 16 (half fade): -0.6 + 2.5 = +1.9:
        checkNear(helper, "upwind slope at S + 16",
                SnowLee.verticalVelocity(80.0, 63.8, 64.0, 64.0, 0.5, ownFall, windSpeed, leeFall), 1.9);
        // Upwind 1:2 slope at S + 32 (faded out): -0.6:
        checkNear(helper, "upwind slope at S + 32",
                SnowLee.verticalVelocity(96.0, 63.8, 64.0, 64.0, 0.5, ownFall, windSpeed, leeFall), -ownFall);
        // Downwind 1:2 slope (-0.5) at S: -0.6 - 5.0 = -5.6:
        checkNear(helper, "downwind slope at S",
                SnowLee.verticalVelocity(64.0, 63.8, 64.0, 64.0, -0.5, ownFall, windSpeed, leeFall), -5.6);
        // Lee sinking on flat ground matches old sinkingFallSpeed:
        checkNear(helper, "flat lee inside",
                SnowLee.verticalVelocity(66.0, 70.0, 64.0, 64.0, 0.0, ownFall, windSpeed, leeFall), -leeFall);
        checkNear(helper, "flat lee halfway above",
                SnowLee.verticalVelocity(73.0, 70.0, 64.0, 64.0, 0.0, ownFall, windSpeed, leeFall),
                -0.5 * (ownFall + leeFall));
        checkNear(helper, "flat lee above fade zone",
                SnowLee.verticalVelocity(76.0, 70.0, 64.0, 64.0, 0.0, ownFall, windSpeed, leeFall), -ownFall);
        // Lee sinking behind cliff with downwind slope (-0.4): sinks at -(2.1 + 4.0) = -6.1:
        checkNear(helper, "cliff lee with downwind slope",
                SnowLee.verticalVelocity(70.0, 75.0, 64.0, 68.0, -0.4, ownFall, windSpeed, leeFall), -6.1);
        // Calm wind disables both terrain-following and lee:
        helper.assertTrue(SnowLee.isCalm(0.0), "wind speed 0.0 was not calm");
        helper.assertTrue(Double.isInfinite(SnowLee.slope(0.6, 0.0, 0.15)), "calm wind slope was finite");
        checkNear(helper, "calm wind vy",
                SnowLee.verticalVelocity(64.0, 70.0, 64.0, 64.0, 0.5, ownFall, 0.0, 0.0), -ownFall);

        // Smooth and lee rebuild triggers:
        helper.assertFalse(SnowLee.needsSmoothRebuild(true, 0, 0, 5L, 0, 0, 5L),
                "identical smooth inputs triggered rebuild");
        helper.assertTrue(SnowLee.needsSmoothRebuild(false, 0, 0, 5L, 0, 0, 5L),
                "missing smooth map did not trigger rebuild");
        helper.assertTrue(SnowLee.needsSmoothRebuild(true, 16, 0, 5L, 0, 0, 5L),
                "window move did not trigger smooth rebuild");
        helper.assertTrue(SnowLee.needsSmoothRebuild(true, 0, 0, 6L, 0, 0, 5L),
                "heights edit did not trigger smooth rebuild");
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "identical lee inputs triggered rebuild");
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, -Math.PI + 0.001, 10.0, 0.6, 0.15, 0, 0, 5L,
                Math.PI - 0.001, 10.0, 0.6, 0.15), "small turn across pi/-pi triggered rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0 + Math.toRadians(0.3), 10.0, 0.6, 0.15, 0, 0, 5L,
                1.0, 10.0, 0.6, 0.15), "0.3 deg turn did not trigger rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.25, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "2.5% speed change did not trigger rebuild");
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.0, 0.605, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "0.8% fall speed jitter triggered rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.0, 0.62, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "3.3% fall speed change did not trigger rebuild");

        // Calm snow keeps snowFallSpeed; blizzard blends toward snowBlizzardFallSpeed and snowWindShare:
        checkNear(helper, "calm snow keeps snowFallSpeed", SnowFlakes.fallSpeed(0.6, 1.8, 0.0), 0.6);
        checkNear(helper, "half-storm fall speed", SnowFlakes.fallSpeed(0.6, 1.8, 0.5), 1.2);
        checkNear(helper, "full blizzard fall speed", SnowFlakes.fallSpeed(0.6, 1.8, 1.0), 1.8);
        checkNear(helper, "blizzard flake wind speed", SnowFlakes.windSpeed(12.0, 0.5), 6.0);
        checkNear(helper, "calm flake wind speed", SnowFlakes.windSpeed(0.0, 0.5), 0.0);
        checkNear(helper, "blizzard lee slope at 1-in-3.3", SnowLee.slope(1.8, 6.0, 0.15), 0.45);

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void flakeCountsInSubBoxesOverHillStayWithinBand(GameTestHelper helper) {
        // Build a 1D rounded hill (slope up to ~0.55) and simulate 16384 flakes for 300 frames (10 s = 2.5 crossings)
        // on a plain, on the upwind slope, on the crest, and on the downwind slope to confirm no compounding bunching
        // or top-of-box pileup occurs.
        SnowLee.HeightLookup hill = (x, z) -> {
            double dx = x;
            double rise = 50.0 * Math.exp(-(dx * dx) / (76.0 * 76.0));
            return (short) Math.round(GROUND + rise);
        };
        float[] smooth = new float[TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchA = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchB = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] leeOut = new float[TEST_MAP * TEST_MAP];
        float[] slopeOut = new float[TEST_MAP * TEST_MAP];
        float[] rawA = new float[TEST_MAP * TEST_MAP];
        float[] rawB = new float[TEST_MAP * TEST_MAP];
        float[] relA = new float[TEST_MAP * TEST_MAP];
        float[] relB = new float[TEST_MAP * TEST_MAP];
        double windSpeed = 10.0;
        double fallSpeed = 0.6;
        double leeFill = 0.15;
        double leeSlope = SnowLee.slope(fallSpeed, windSpeed, leeFill);
        double leeFall = SnowLee.leeFallSpeed(leeSlope, windSpeed);
        runSmoothAndScan(hill, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);

        // Plain check: flat ground keeps tight [0.80, 1.25] sub-box density after 300 frames:
        SnowLee.HeightLookup plain = (x, z) -> GROUND;
        float[] plainSmooth = new float[TEST_SMOOTH * TEST_SMOOTH];
        float[] plainLee = new float[TEST_MAP * TEST_MAP];
        float[] plainSlope = new float[TEST_MAP * TEST_MAP];
        runSmoothAndScan(plain, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, plainSmooth, smoothScratchA,
                smoothScratchB, plainLee, plainSlope, rawA, rawB, relA, relB);
        double[] plainBand = simulateHillSubBoxes(plain, plainSmooth, plainLee, plainSlope, 0.0, GROUND + 18.0,
                windSpeed, fallSpeed, leeFall);
        helper.assertTrue(plainBand[0] >= 0.80 && plainBand[1] <= 1.25,
                "plain sub-box density band was [" + plainBand[0] + ", " + plainBand[1] + "] instead of [0.80, 1.25]");

        // Hill checks: upwind slope (x = -54), crest (x = 0), downwind slope (x = +54):
        double[] cameraPositions = {-54.0, 0.0, 54.0};
        String[] labels = {"upwind slope", "crest", "downwind slope"};
        for (int index = 0; index < cameraPositions.length; index++) {
            double camX = cameraPositions[index];
            double camY = hill.heightAt((int) camX, 0) + 18.0;
            double[] band = simulateHillSubBoxes(hill, smooth, leeOut, slopeOut, camX, camY, windSpeed, fallSpeed,
                    leeFall);
            LOGGER.info("Hill sub-box flake density on {} (camX={}): min={}, max={}", labels[index], camX,
                    String.format(java.util.Locale.ROOT, "%.3f", band[0]),
                    String.format(java.util.Locale.ROOT, "%.3f", band[1]));
            helper.assertTrue(band[0] >= 0.45 && band[1] <= 2.10,
                    "hill " + labels[index] + " sub-box density band was [" + band[0] + ", " + band[1]
                            + "], outside [0.45, 2.10]");
        }

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void collidingFlakesApproachingSteepHillFaceKeepEyeLevelSnow(GameTestHelper helper) {
        // 45-degree hill face: plateau 99 for x < -35, 45-degree slope down to 64 over x in [-35, 0], flat plain 64
        // for x > 0, wind blowing off the hill (+X) at full blizzard flake speeds (W = 6.0, f = 1.8, fill = 0.15):
        SnowLee.HeightLookup hillFace = (x, z) -> {
            if (x < -35) {
                return (short) (GROUND + 35);
            }
            if (x <= 0) {
                return (short) (GROUND - x);
            }
            return GROUND;
        };
        float[] smooth = new float[TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchA = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchB = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] leeOut = new float[TEST_MAP * TEST_MAP];
        float[] slopeOut = new float[TEST_MAP * TEST_MAP];
        float[] rawA = new float[TEST_MAP * TEST_MAP];
        float[] rawB = new float[TEST_MAP * TEST_MAP];
        float[] relA = new float[TEST_MAP * TEST_MAP];
        float[] relB = new float[TEST_MAP * TEST_MAP];
        double windSpeed = SnowFlakes.windSpeed(12.0, 0.5);
        double fallSpeed = SnowFlakes.fallSpeed(0.6, 1.8, 1.0);
        double leeFill = 0.15;
        double leeBand = 3.0;
        double leeSlope = SnowLee.slope(fallSpeed, windSpeed, leeFill);
        double leeFall = SnowLee.leeFallSpeed(leeSlope, windSpeed);
        runSmoothAndScan(hillFace, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);

        // Baseline on flat plain:
        SnowLee.HeightLookup plain = (x, z) -> GROUND;
        float[] plainSmooth = new float[TEST_SMOOTH * TEST_SMOOTH];
        float[] plainLee = new float[TEST_MAP * TEST_MAP];
        float[] plainSlope = new float[TEST_MAP * TEST_MAP];
        runSmoothAndScan(plain, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, plainSmooth, smoothScratchA,
                smoothScratchB, plainLee, plainSlope, rawA, rawB, relA, relB);
        double plainVisible = simulateCollidingEyeLevelVisible(plain, plainSmooth, plainLee, plainSlope, 0.0,
                GROUND + 1.6, windSpeed, fallSpeed, leeFall, leeBand);
        helper.assertTrue(plainVisible > 0.0, "plain baseline had zero visible eye-level flakes");

        double[] approachDistances = {30.0, 20.0, 14.0, 10.0, 6.0, 4.0};
        for (double dist : approachDistances) {
            double visible = simulateCollidingEyeLevelVisible(hillFace, smooth, leeOut, slopeOut, dist, GROUND + 1.6,
                    windSpeed, fallSpeed, leeFall, leeBand);
            double share = visible / plainVisible;
            LOGGER.info("45-deg hill face approach at x={}: eye-level visible snow share={}", dist,
                    String.format(java.util.Locale.ROOT, "%.3f", share));
            helper.assertTrue(share >= 0.60, "45-deg hill face approach at x=" + dist + " dropped eye-level snow "
                    + "share to " + share + ", below 0.60");
        }

        // At the steeper blizzard slope c = 0.45, walls, houses and 20-block cliffs still cast clean lees:
        SnowLee.HeightLookup wall = (x, z) -> (x == -20 && z >= -3 && z <= 3) ? (short) 68 : GROUND;
        runSmoothAndScan(wall, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        float wallLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, -15.5, 0.5, 1.0, 0.0);
        helper.assertTrue(wallLee > GROUND + 1.5F, "wall lee 4 blocks behind at c=0.45 was " + wallLee);

        SnowLee.HeightLookup house = (x, z) -> (x >= -24 && x <= -17 && z >= -4 && z <= 3) ? (short) 69 : GROUND;
        runSmoothAndScan(house, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        float houseLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, -12.5, 0.5, 1.0, 0.0);
        helper.assertTrue(houseLee > GROUND + 2.0F, "house lee 4 blocks behind at c=0.45 was " + houseLee);

        SnowLee.HeightLookup cliff = (x, z) -> x <= -20 ? (short) 84 : GROUND;
        runSmoothAndScan(cliff, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        float cliffLee = SnowLee.sampleBilinear(leeOut, TEST_MAP, -9.5, 0.5, 1.0, 0.0);
        helper.assertTrue(cliffLee > GROUND + 10.0F, "20-block cliff lee 10 blocks behind at c=0.45 was " + cliffLee);

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 120)
    public static void flakesInCrossWindRavineReachFloorAndOutsideMovesAsBefore(GameTestHelper helper) {
        short floor = 40;
        short rim = 65;
        double windSpeed = SnowFlakes.windSpeed(12.0, 0.5);
        double fallSpeed = SnowFlakes.fallSpeed(0.6, 1.8, 1.0);
        double leeFill = 0.15;
        double leeBand = 3.0;
        double leeSlope = SnowLee.slope(fallSpeed, windSpeed, leeFill);
        double leeFall = SnowLee.leeFallSpeed(leeSlope, windSpeed);

        float[] smooth = new float[TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchA = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] smoothScratchB = new float[2 * TEST_SMOOTH * TEST_SMOOTH];
        float[] leeOut = new float[TEST_MAP * TEST_MAP];
        float[] slopeOut = new float[TEST_MAP * TEST_MAP];
        float[] rawA = new float[TEST_MAP * TEST_MAP];
        float[] rawB = new float[TEST_MAP * TEST_MAP];
        float[] relA = new float[TEST_MAP * TEST_MAP];
        float[] relB = new float[TEST_MAP * TEST_MAP];
        float[] cavSurf = new float[TEST_MAP * TEST_MAP];
        float[] cavRim = new float[TEST_MAP * TEST_MAP];

        // 1. 10-block wide, 25-deep cross-wind ravine (air x in [-5, +4], floor 40, rim 65) at floor, mid and upper
        // ledge camera heights (camY = 42, 50, 58, 62):
        SnowLee.HeightLookup ravine10 = (x, z) -> (x <= -6 || x >= 5) ? rim : floor;
        runSmoothAndScan(ravine10, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        FogCavity.scan(TEST_MAP, FogCavity.cardinalStrides(), FogCavity.diagonalStrides(), ravine10, -TEST_HALF,
                -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, FogCavity.ENCLOSURE_SLOPE, true, cavSurf, cavRim);
        double[] camHeights = {42.0, 50.0, 58.0, 62.0};
        for (double camY : camHeights) {
            double[] stats = simulateRavineSnow(ravine10, smooth, leeOut, slopeOut, cavSurf, cavRim, -5, 4, floor,
                    0.0, camY, windSpeed, fallSpeed, leeFall, leeBand);
            String hitLabel = floor >= camY - 19.5 ? "visibleFloorHits"
                    : "visibleShaftCrossings(y=" + String.format(java.util.Locale.ROOT, "%.1f", camY - 19.0) + ")";
            LOGGER.info("10-wide 25-deep cross-wind ravine (camY={}): {}={} [thirds {}, {}, {}], "
                            + "meanFloorWeight={}, densityRatio={}",
                    camY, hitLabel, String.format(java.util.Locale.ROOT, "%.0f", stats[0]),
                    String.format(java.util.Locale.ROOT, "%.0f", stats[3]),
                    String.format(java.util.Locale.ROOT, "%.0f", stats[4]),
                    String.format(java.util.Locale.ROOT, "%.0f", stats[5]),
                    String.format(java.util.Locale.ROOT, "%.3f", stats[1]),
                    String.format(java.util.Locale.ROOT, "%.3f", stats[2]));
            helper.assertTrue(stats[0] >= 250.0, "10-wide ravine at camY=" + camY + " had only " + stats[0]
                    + " " + hitLabel + ", expected >= 250");
            assertEachFloorThirdHasHits(helper, "10-wide 25-deep ravine (camY=" + camY + ")", stats, 40.0);
            helper.assertTrue(stats[1] >= 0.90, "10-wide ravine at camY=" + camY + " had mean floor leeWeight "
                    + stats[1] + ", expected >= 0.90");
            helper.assertTrue(stats[2] >= 0.35 && stats[2] <= 2.20, "10-wide ravine at camY=" + camY
                    + " had air density ratio " + stats[2] + ", outside [0.35, 2.20]");
        }

        // 2. 20-block wide, 25-deep cross-wind ravine (air x in [-10, +9], floor 40, rim 65) at camY = 42, 50, 58, 62:
        SnowLee.HeightLookup ravine20 = (x, z) -> (x <= -11 || x >= 10) ? rim : floor;
        runSmoothAndScan(ravine20, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        FogCavity.scan(TEST_MAP, FogCavity.cardinalStrides(), FogCavity.diagonalStrides(), ravine20, -TEST_HALF,
                -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, FogCavity.ENCLOSURE_SLOPE, true, cavSurf, cavRim);
        for (double camY : camHeights) {
            double[] stats = simulateRavineSnow(ravine20, smooth, leeOut, slopeOut, cavSurf, cavRim, -10, 9, floor,
                    0.0, camY, windSpeed, fallSpeed, leeFall, leeBand);
            String hitLabel = floor >= camY - 19.5 ? "visibleFloorHits"
                    : "visibleShaftCrossings(y=" + String.format(java.util.Locale.ROOT, "%.1f", camY - 19.0) + ")";
            LOGGER.info("20-wide 25-deep cross-wind ravine (camY={}): {}={} [thirds {}, {}, {}], "
                            + "meanFloorWeight={}, densityRatio={}",
                    camY, hitLabel, String.format(java.util.Locale.ROOT, "%.0f", stats[0]),
                    String.format(java.util.Locale.ROOT, "%.0f", stats[3]),
                    String.format(java.util.Locale.ROOT, "%.0f", stats[4]),
                    String.format(java.util.Locale.ROOT, "%.0f", stats[5]),
                    String.format(java.util.Locale.ROOT, "%.3f", stats[1]),
                    String.format(java.util.Locale.ROOT, "%.3f", stats[2]));
            helper.assertTrue(stats[0] >= 500.0, "20-wide ravine at camY=" + camY + " had only " + stats[0]
                    + " " + hitLabel + ", expected >= 500");
            assertEachFloorThirdHasHits(helper, "20-wide 25-deep ravine (camY=" + camY + ")", stats, 100.0);
            helper.assertTrue(stats[1] >= 0.90, "20-wide ravine at camY=" + camY + " had mean floor leeWeight "
                    + stats[1] + ", expected >= 0.90");
            helper.assertTrue(stats[2] >= 0.35 && stats[2] <= 2.20, "20-wide ravine at camY=" + camY
                    + " had air density ratio " + stats[2] + ", outside [0.35, 2.20]");
        }

        // 3. 12-deep, 10-wide cross-wind ravine (floor 53, rim 65) with camera on its floor (camY = 54.6):
        short shallowFloor = 53;
        SnowLee.HeightLookup ravine12 = (x, z) -> (x <= -6 || x >= 5) ? rim : shallowFloor;
        runSmoothAndScan(ravine12, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        FogCavity.scan(TEST_MAP, FogCavity.cardinalStrides(), FogCavity.diagonalStrides(), ravine12, -TEST_HALF,
                -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, FogCavity.ENCLOSURE_SLOPE, true, cavSurf, cavRim);
        double[] shallowStats = simulateRavineSnow(ravine12, smooth, leeOut, slopeOut, cavSurf, cavRim, -5, 4,
                shallowFloor, 0.0, shallowFloor + 1.6, windSpeed, fallSpeed, leeFall, leeBand);
        LOGGER.info("10-wide 12-deep cross-wind ravine (camY={}): visibleFloorHits={} [thirds {}, {}, {}], "
                        + "meanFloorWeight={}, densityRatio={}",
                shallowFloor + 1.6, String.format(java.util.Locale.ROOT, "%.0f", shallowStats[0]),
                String.format(java.util.Locale.ROOT, "%.0f", shallowStats[3]),
                String.format(java.util.Locale.ROOT, "%.0f", shallowStats[4]),
                String.format(java.util.Locale.ROOT, "%.0f", shallowStats[5]),
                String.format(java.util.Locale.ROOT, "%.3f", shallowStats[1]),
                String.format(java.util.Locale.ROOT, "%.3f", shallowStats[2]));
        helper.assertTrue(shallowStats[0] >= 250.0, "12-deep ravine had only " + shallowStats[0]
                + " visible floor hits, expected >= 250");
        assertEachFloorThirdHasHits(helper, "10-wide 12-deep ravine", shallowStats, 40.0);
        helper.assertTrue(shallowStats[1] >= 0.90, "12-deep ravine had mean floor leeWeight " + shallowStats[1]
                + ", expected >= 0.90");
        helper.assertTrue(shallowStats[2] >= 0.35 && shallowStats[2] <= 2.20, "12-deep ravine had air density ratio "
                + shallowStats[2] + ", outside [0.35, 2.20]");

        // 4. 8-wide village street between 6-tall houses (street air x in [-4, +3], floor 64, houses roof 70 at
        // x in [-12, -5] and [4, 11]), camera at eye level (camY = 65.6):
        short streetFloor = GROUND;
        short houseRoof = (short) (GROUND + 6);
        SnowLee.HeightLookup villageStreet = (x, z) -> ((x >= -12 && x <= -5) || (x >= 4 && x <= 11))
                ? houseRoof : streetFloor;
        runSmoothAndScan(villageStreet, TEST_MAP, TEST_STRIDES, 1.0, 0.0, leeSlope, true, smooth, smoothScratchA,
                smoothScratchB, leeOut, slopeOut, rawA, rawB, relA, relB);
        FogCavity.scan(TEST_MAP, FogCavity.cardinalStrides(), FogCavity.diagonalStrides(), villageStreet, -TEST_HALF,
                -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, FogCavity.ENCLOSURE_SLOPE, true, cavSurf, cavRim);
        double[] streetStats = simulateRavineSnow(villageStreet, smooth, leeOut, slopeOut, cavSurf, cavRim, -4, 3,
                streetFloor, 0.0, streetFloor + 1.6, windSpeed, fallSpeed, leeFall, leeBand);
        LOGGER.info("8-wide street between 6-tall houses (camY={}): visibleFloorHits={} [thirds {}, {}, {}], "
                        + "meanFloorWeight={}, densityRatio={}",
                streetFloor + 1.6, String.format(java.util.Locale.ROOT, "%.0f", streetStats[0]),
                String.format(java.util.Locale.ROOT, "%.0f", streetStats[3]),
                String.format(java.util.Locale.ROOT, "%.0f", streetStats[4]),
                String.format(java.util.Locale.ROOT, "%.0f", streetStats[5]),
                String.format(java.util.Locale.ROOT, "%.3f", streetStats[1]),
                String.format(java.util.Locale.ROOT, "%.3f", streetStats[2]));
        helper.assertTrue(streetStats[0] >= 200.0, "8-wide street had only " + streetStats[0]
                + " visible floor hits, expected >= 200");
        assertEachFloorThirdHasHits(helper, "8-wide village street", streetStats, 30.0);
        helper.assertTrue(streetStats[1] >= 0.90, "8-wide street had mean floor leeWeight " + streetStats[1]
                + ", expected >= 0.90");
        helper.assertTrue(streetStats[2] >= 0.35 && streetStats[2] <= 2.20, "8-wide street had air density ratio "
                + streetStats[2] + ", outside [0.35, 2.20]");

        // 5. Overhang lip stays dry even with a deep cavity map value overhead:
        double overhangOpen = SnowLee.cavityOpenFactor(50.0, 61.4, 65.0, 60.0);
        double overhangShare = SnowLee.leeShare(50.0, 62.0, 60.0, 58.0, overhangOpen, leeBand);
        checkNear(helper, "overhang inside cavity stays dry", overhangShare, 0.0);

        // 6. Outside any cavity (plain, wall, house, single cliff), flakes move, re-enter and fade identically to before:
        SnowLee.HeightLookup[] nonCavities = {
                (x, z) -> GROUND,
                (x, z) -> (x == -20 && z >= -3 && z <= 3) ? (short) 68 : GROUND,
                (x, z) -> (x >= -24 && x <= -17 && z >= -4 && z <= 3) ? (short) 69 : GROUND,
                (x, z) -> x <= -20 ? (short) 84 : GROUND
        };
        String[] nonCavityLabels = {"plain", "wall", "house", "single cliff"};
        for (int shapeIndex = 0; shapeIndex < nonCavities.length; shapeIndex++) {
            SnowLee.HeightLookup lookup = nonCavities[shapeIndex];
            FogCavity.scan(TEST_MAP, FogCavity.cardinalStrides(), FogCavity.diagonalStrides(), lookup, -TEST_HALF,
                    -TEST_HALF, -TEST_HALF, -TEST_HALF, TEST_MAP, FogCavity.ENCLOSURE_SLOPE, true, cavSurf, cavRim);
            for (int x = -15; x <= 15; x++) {
                int idx = TEST_HALF * TEST_MAP + (x + TEST_HALF);
                short colH = lookup.heightAt(x, 0);
                double brakeH = SnowLee.cavityBrakeHeight(cavSurf[idx], cavRim[idx], colH);
                double open = SnowLee.cavityOpenFactor(colH + 1.5, cavSurf[idx], cavRim[idx], colH);
                double scale = SnowLee.cavityWindScale(open);
                helper.assertTrue(brakeH <= SnowLee.NO_HEIGHT_THRESHOLD,
                        nonCavityLabels[shapeIndex] + " got brakeHeight " + brakeH + " at x=" + x);
                checkNear(helper, nonCavityLabels[shapeIndex] + " brakeWindScaleAt at x=" + x,
                        SnowLee.brakeWindScaleAt(colH + 1.5, brakeH), 1.0);
                checkNear(helper, nonCavityLabels[shapeIndex] + " cavityOpenFactor at x=" + x, open, 1.0);
                checkNear(helper, nonCavityLabels[shapeIndex] + " cavityWindScale at x=" + x, scale, 1.0);
                double withCav = SnowLee.leeShare(colH + 1.5, colH + 5.0, colH, colH, open, leeBand);
                double withoutCav = SnowLee.leeShare(colH + 1.5, colH + 5.0, colH, colH, leeBand);
                checkNear(helper, nonCavityLabels[shapeIndex] + " leeShare unchanged at x=" + x, withCav, withoutCav);
            }
        }
        // Verify profiled reenterBoxOffset matches plain open-ground reenterBoxOffset bit-for-bit when no cavity
        // intersects the box (both brakeHeight == NO_HEIGHT and brakeHeight <= cameraWorldY - halfSide):
        double[] plainEntry = new double[3];
        double[] profiledEntry = new double[3];
        for (int flake = 0; flake < 256; flake++) {
            double wx = 0.2;
            double cx = 0.05;
            double wz = -0.12;
            double cz = 0.03;
            SnowLee.reenterBoxOffset(flake, 7, wx - cx, -0.06, -0.06, wz - cz, 40.0, plainEntry);
            SnowLee.reenterBoxOffset(flake, 7, wx, cx, -0.06, -0.06, wz, cz, 65.6, SnowLee.NO_HEIGHT, 40.0,
                    profiledEntry);
            for (int axis = 0; axis < 3; axis++) {
                checkNear(helper, "open-ground reenterBoxOffset axis " + axis + " flake " + flake,
                        profiledEntry[axis], plainEntry[axis]);
            }
            SnowLee.reenterBoxOffset(flake, 7, wx, cx, -0.06, -0.06, wz, cz, 65.6, 40.0, 40.0, profiledEntry);
            for (int axis = 0; axis < 3; axis++) {
                checkNear(helper, "below-box cavity reenterBoxOffset axis " + axis + " flake " + flake,
                        profiledEntry[axis], plainEntry[axis]);
            }
        }

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void sharedShaderConstantsMatchJavaDefinitions(GameTestHelper helper) {
        Map<String, Double> flakesGlsl = parseShaderConstants(helper, "snow_flakes.glsl");
        Map<String, Double> updateFsh = parseShaderConstants(helper, "snow_flake_update.fsh");
        Map<String, Double> drawGlsl = parseShaderConstants(helper, "snow_flakes_draw.glsl");
        Map<String, Double> leeGlsl = parseShaderConstants(helper, "snow_lee.glsl");
        Map<String, Double> leeBuildFsh = parseShaderConstants(helper, "snow_lee_build.fsh");
        Map<String, Double> smoothBuildFsh = parseShaderConstants(helper, "snow_smooth_build.fsh");
        Map<String, Double> cavityBuildFsh = parseShaderConstants(helper, "fog_cavity_build.fsh");
        Map<String, Double> snowWalkGlsl = parseShaderConstants(helper, "snow_walk.glsl");
        if (flakesGlsl == null || updateFsh == null || drawGlsl == null || leeGlsl == null || leeBuildFsh == null
                || smoothBuildFsh == null || cavityBuildFsh == null || snowWalkGlsl == null) {
            return;
        }

        // snow_flakes.glsl:
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_STATE_WIDTH", SnowFlakes.STATE_WIDTH, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_LEE", SnowFlakes.SALT_LEE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_POSITION_X",
                SnowFlakes.SALT_POSITION_X, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_POSITION_Y",
                SnowFlakes.SALT_POSITION_Y, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_POSITION_Z",
                SnowFlakes.SALT_POSITION_Z, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_FLUTTER_PHASE",
                SnowFlakes.SALT_FLUTTER_PHASE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_FALL", SnowFlakes.SALT_FALL, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_SIZE", SnowFlakes.SALT_SIZE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_FLUTTER_PERIOD",
                SnowFlakes.SALT_FLUTTER_PERIOD, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_FLUTTER_TURN",
                SnowFlakes.SALT_FLUTTER_TURN, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_FLUTTER_RADIUS",
                SnowFlakes.SALT_FLUTTER_RADIUS, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_BRIGHTNESS",
                SnowFlakes.SALT_BRIGHTNESS, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_SHELTER",
                SnowFlakes.SALT_SHELTER, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_SHAPE", SnowFlakes.SALT_SHAPE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_TURN", SnowFlakes.SALT_TURN, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_TILT", SnowFlakes.SALT_TILT, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SALT_LEAVES", SnowFlakes.SALT_LEAVES, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_STOPPED_PHASE",
                SnowFlakes.STOPPED_PHASE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SPLAT_FACE_STRIDE",
                SnowFlakes.SPLAT_FACE_STRIDE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SLOWEST_FALL_SHARE",
                SnowFlakes.SLOWEST_FALL_SHARE, 1.0e-6);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_FASTEST_FALL_SHARE",
                SnowFlakes.FASTEST_FALL_SHARE, 1.0e-6);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SMALLEST_SIZE_SHARE",
                SnowFlakes.SMALLEST_SIZE_SHARE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_LARGEST_SIZE_SHARE",
                SnowFlakes.LARGEST_SIZE_SHARE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SHORTEST_FLUTTER_PERIOD",
                SnowFlakes.SHORTEST_FLUTTER_PERIOD, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_LONGEST_FLUTTER_PERIOD",
                SnowFlakes.LONGEST_FLUTTER_PERIOD, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SMALLEST_FLUTTER_RADIUS_SHARE",
                SnowFlakes.SMALLEST_FLUTTER_RADIUS_SHARE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_LARGEST_FLUTTER_RADIUS_SHARE",
                SnowFlakes.LARGEST_FLUTTER_RADIUS_SHARE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_DIMMEST_BRIGHTNESS_SHARE",
                SnowFlakes.DIMMEST_BRIGHTNESS_SHARE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_BRIGHTEST_BRIGHTNESS_SHARE",
                SnowFlakes.BRIGHTEST_BRIGHTNESS_SHARE, 0.0);
        checkShaderConstant(helper, "snow_flakes.glsl", flakesGlsl, "SNOW_SHELTER_SOFTNESS",
                SnowFlakes.SHELTER_SOFTNESS, 0.0);

        Set<Integer> salts = new HashSet<>();
        for (Map.Entry<String, Double> entry : flakesGlsl.entrySet()) {
            if (entry.getKey().startsWith("SNOW_SALT_")) {
                int salt = entry.getValue().intValue();
                helper.assertTrue(salt >= 0 && salt < 16, entry.getKey() + " is " + salt + ", outside 0..15");
                helper.assertTrue(salts.add(salt), "duplicate flake hash salt " + salt + " on " + entry.getKey());
            }
        }
        helper.assertTrue(salts.size() == 16, "expected 16 distinct SNOW_SALT_* constants in snow_flakes.glsl, found "
                + salts.size());

        // snow_flake_update.fsh:
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_VOLUME_SIZE",
                SnowShapeVolume.SIZE, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_SHAPE_MASK",
                SnowShapes.SHAPE_MASK, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_LEAVES_FLAG",
                SnowShapes.LEAVES_FLAG, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_FLUID_FLAG",
                SnowShapes.FLUID_FLAG, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_EMPTY_SHAPE",
                SnowShapes.EMPTY_SHAPE, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_FULL_SHAPE",
                SnowShapes.FULL_SHAPE, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_SHAPES_PER_ROW",
                SnowShapes.SHAPES_PER_ROW, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_SUBSTEP_BLOCKS",
                SnowFlakes.SUBSTEP_BLOCKS, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_MOST_SUBSTEPS",
                SnowFlakes.MOST_SUBSTEPS, 0.0);
        checkShaderConstant(helper, "snow_flake_update.fsh", updateFsh, "SNOW_HIT_REFINEMENTS",
                SnowFlakes.HIT_REFINEMENTS, 0.0);

        // snow_flakes_draw.glsl:
        checkShaderConstant(helper, "snow_flakes_draw.glsl", drawGlsl, "SNOW_COUNT_FADE",
                SnowFlakes.COUNT_FADE_FLAKES, 0.0);

        // snow_lee.glsl:
        checkLeeShaderConstants(helper, "snow_lee.glsl", leeGlsl);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_SMOOTH_SCALE", SnowLee.SMOOTH_SCALE, 0.0);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_SMOOTH_SIZE", SnowLee.SMOOTH_SIZE, 0.0);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_TERRAIN_FOLLOW_HEIGHT",
                SnowLee.TERRAIN_FOLLOW_HEIGHT, 0.0);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_OVERHANG_MARGIN", SnowLee.OVERHANG_MARGIN, 0.0);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_LEE_MINIMUM_WIND_SPEED",
                SnowLee.MINIMUM_WIND_SPEED, 0.0);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_CAVITY_MAP_SIZE", SnowLee.CAVITY_MAP_SIZE, 0.0);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_CAVITY_WIND_FLOOR",
                SnowLee.CAVITY_WIND_FLOOR, 1.0e-6);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_CAVITY_WIND_DECAY",
                SnowLee.CAVITY_WIND_DECAY, 1.0e-6);
        checkShaderConstant(helper, "snow_lee.glsl", leeGlsl, "SNOW_CAVITY_RIM_BLEND_DEPTH",
                SnowLee.CAVITY_RIM_BLEND_DEPTH, 1.0e-6);

        // snow_lee_build.fsh:
        checkLeeShaderConstants(helper, "snow_lee_build.fsh", leeBuildFsh);
        checkShaderConstant(helper, "snow_lee_build.fsh", leeBuildFsh, "SNOW_LEE_SAMPLE_OFFSET",
                SnowLee.SAMPLE_OFFSET, 0.0);
        checkShaderConstant(helper, "snow_lee_build.fsh", leeBuildFsh, "SNOW_SMOOTH_SCALE",
                SnowLee.SMOOTH_SCALE, 0.0);
        checkShaderConstant(helper, "snow_lee_build.fsh", leeBuildFsh, "SNOW_SMOOTH_SIZE",
                SnowLee.SMOOTH_SIZE, 0.0);
        checkShaderConstant(helper, "snow_lee_build.fsh", leeBuildFsh, "SNOW_SMOOTH_GRADIENT_STEP",
                SnowLee.SMOOTH_GRADIENT_STEP, 0.0);

        // snow_smooth_build.fsh:
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_SMOOTH_SCALE",
                SnowLee.SMOOTH_SCALE, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_SMOOTH_SIZE",
                SnowLee.SMOOTH_SIZE, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_SMOOTH_SIGMA_TEXELS",
                SnowLee.SMOOTH_SIGMA_TEXELS, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_SMOOTH_KERNEL_RADIUS",
                SnowLee.SMOOTH_KERNEL_RADIUS, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_SMOOTH_MINIMUM_WEIGHT",
                SnowLee.SMOOTH_MINIMUM_WEIGHT, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_LEE_FAR_HEIGHTS_MASK",
                FarHeights.SIZE - 1, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_LEE_UNKNOWN_HEIGHT",
                FarHeights.UNKNOWN, 0.0);
        checkShaderConstant(helper, "snow_smooth_build.fsh", smoothBuildFsh, "SNOW_LEE_NO_HEIGHT",
                SnowLee.NO_HEIGHT, 0.0);

        // fog_cavity_build.fsh:
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_MAP_SIZE",
                FogCavity.MAP_SIZE, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_FAR_HEIGHTS_SIZE",
                FarHeights.SIZE, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_FAR_HEIGHTS_MASK",
                FarHeights.SIZE - 1, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_UNKNOWN_HEIGHT",
                FarHeights.UNKNOWN, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_NO_HEIGHT",
                FogCavity.NO_HEIGHT, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_NO_HEIGHT_THRESHOLD",
                FogCavity.NO_HEIGHT_THRESHOLD, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_ENCLOSURE_SLOPE",
                FogCavity.ENCLOSURE_SLOPE, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_DIAGONAL_STEP",
                FogCavity.DIAGONAL_STEP, 1.0e-6);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_CARDINAL_FINAL_PASS",
                FogCavity.DIAGONAL_FIRST_PASS - 1, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_DIAGONAL_FIRST_PASS",
                FogCavity.DIAGONAL_FIRST_PASS, 0.0);
        checkShaderConstant(helper, "fog_cavity_build.fsh", cavityBuildFsh, "FOG_CAVITY_DIAGONAL_FINAL_PASS",
                FogCavity.TOTAL_PASSES - 1, 0.0);

        // snow_walk.glsl cavity constants:
        checkShaderConstant(helper, "snow_walk.glsl", snowWalkGlsl, "FOG_CAVITY_MAP_SIZE", FogCavity.MAP_SIZE, 0.0);
        checkShaderConstant(helper, "snow_walk.glsl", snowWalkGlsl, "FOG_CAVITY_NO_HEIGHT_THRESHOLD",
                FogCavity.NO_HEIGHT_THRESHOLD, 0.0);

        helper.succeed();
    }

    private static double[] simulateHillSubBoxes(SnowLee.HeightLookup heights, float[] smooth, float[] leeMap,
            float[] slopeMap, double cameraX, double cameraY, double windSpeed, double fallSpeed, double leeFall) {
        int flakeCount = 16384;
        int steps = 300;
        double dt = 1.0 / 30.0;
        double boxSide = 40.0;
        double halfSide = 0.5 * boxSide;
        double[] posX = new double[flakeCount];
        double[] posY = new double[flakeCount];
        double[] posZ = new double[flakeCount];
        double[] ownFall = new double[flakeCount];
        for (int flake = 0; flake < flakeCount; flake++) {
            posX[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_X) - 0.5) * boxSide;
            posY[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_Y) - 0.5) * boxSide;
            posZ[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_Z) - 0.5) * boxSide;
            ownFall[flake] = fallSpeed * SnowFlakes.fallShare(flake);
        }
        double centerLee = SnowLee.sampleBilinear(leeMap, TEST_MAP, cameraX, 0.0, 1.0, 0.0);
        double centerSlope = SnowLee.sampleBilinear(slopeMap, TEST_MAP, cameraX, 0.0, 1.0, 0.0);
        double centerSmooth = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, cameraX + TEST_HALF, TEST_HALF);
        double centerColumn = heights.heightAt((int) Math.floor(cameraX), 0);
        double[] reentry = new double[3];
        for (int frame = 1; frame <= steps; frame++) {
            for (int flake = 0; flake < flakeCount; flake++) {
                double worldX = cameraX + posX[flake];
                double worldY = cameraY + posY[flake];
                double worldZ = posZ[flake];
                double leeHeight = SnowLee.sampleBilinear(leeMap, TEST_MAP, worldX, worldZ, 1.0, 0.0);
                double terrainSlope = SnowLee.sampleBilinear(slopeMap, TEST_MAP, worldX, worldZ, 1.0, 0.0);
                double smoothHeight = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, worldX + TEST_HALF,
                        worldZ + TEST_HALF);
                double columnHeight = heights.heightAt((int) Math.floor(worldX), (int) Math.floor(worldZ));
                double vy = SnowLee.verticalVelocity(worldY, leeHeight, columnHeight, smoothHeight, terrainSlope,
                        ownFall[flake], windSpeed, leeFall);
                double nextX = posX[flake] + windSpeed * dt;
                double nextY = posY[flake] + vy * dt;
                double nextZ = posZ[flake];
                if (Math.abs(nextX) >= halfSide || Math.abs(nextY) >= halfSide || Math.abs(nextZ) >= halfSide) {
                    double topVy = SnowLee.verticalVelocity(cameraY + halfSide, centerLee, centerColumn, centerSmooth,
                            centerSlope, ownFall[flake], windSpeed, leeFall);
                    double bottomVy = SnowLee.verticalVelocity(cameraY - halfSide, centerLee, centerColumn,
                            centerSmooth, centerSlope, ownFall[flake], windSpeed, leeFall);
                    SnowLee.reenterBoxOffset(flake, frame, windSpeed * dt, topVy * dt, bottomVy * dt, 0.0, boxSide,
                            reentry);
                    posX[flake] = reentry[0];
                    posY[flake] = reentry[1];
                    posZ[flake] = reentry[2];
                } else {
                    posX[flake] = nextX;
                    posY[flake] = nextY;
                    posZ[flake] = nextZ;
                }
            }
        }
        // Count flakes in 4x4 sub-boxes over [-16, +16] in x and [-16, +16] in y around the camera (each 8 x 8 x 40):
        int bins = 4;
        int[][] counts = new int[bins][bins];
        for (int flake = 0; flake < flakeCount; flake++) {
            double ox = posX[flake];
            double oy = posY[flake];
            if (ox < -16.0 || ox >= 16.0 || oy < -16.0 || oy >= 16.0) {
                continue;
            }
            int bx = (int) Math.floor((ox + 16.0) / 8.0);
            int by = (int) Math.floor((oy + 16.0) / 8.0);
            counts[bx][by]++;
        }
        double expectedPerBin = flakeCount * (8.0 * 8.0 * boxSide) / (boxSide * boxSide * boxSide);
        double minRatio = Double.POSITIVE_INFINITY;
        double maxRatio = 0.0;
        for (int bx = 0; bx < bins; bx++) {
            for (int by = 0; by < bins; by++) {
                double ratio = counts[bx][by] / expectedPerBin;
                minRatio = Math.min(minRatio, ratio);
                maxRatio = Math.max(maxRatio, ratio);
            }
        }
        return new double[]{minRatio, maxRatio};
    }

    private static double simulateCollidingEyeLevelVisible(SnowLee.HeightLookup heights, float[] smooth,
            float[] leeMap, float[] slopeMap, double cameraX, double cameraY, double windSpeed, double fallSpeed,
            double leeFall, double leeBand) {
        int flakeCount = 16384;
        int steps = 300;
        double dt = 1.0 / 30.0;
        double boxSide = 40.0;
        double halfSide = 0.5 * boxSide;
        double[] posX = new double[flakeCount];
        double[] posY = new double[flakeCount];
        double[] posZ = new double[flakeCount];
        boolean[] stopped = new boolean[flakeCount];
        double[] ownFall = new double[flakeCount];
        for (int flake = 0; flake < flakeCount; flake++) {
            posX[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_X) - 0.5) * boxSide;
            posY[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_Y) - 0.5) * boxSide;
            posZ[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_Z) - 0.5) * boxSide;
            ownFall[flake] = fallSpeed * SnowFlakes.fallShare(flake);
            double wx = cameraX + posX[flake];
            double wy = cameraY + posY[flake];
            double wz = posZ[flake];
            stopped[flake] = wy < heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
        }
        double centerLee = SnowLee.sampleBilinear(leeMap, TEST_MAP, cameraX, 0.0, 1.0, 0.0);
        double centerSlope = SnowLee.sampleBilinear(slopeMap, TEST_MAP, cameraX, 0.0, 1.0, 0.0);
        double centerSmooth = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, cameraX + TEST_HALF, TEST_HALF);
        double centerColumn = heights.heightAt((int) Math.floor(cameraX), 0);
        double[] reentry = new double[3];
        for (int frame = 1; frame <= steps; frame++) {
            for (int flake = 0; flake < flakeCount; flake++) {
                double vy;
                if (stopped[flake]) {
                    vy = -ownFall[flake];
                } else {
                    double wx = cameraX + posX[flake];
                    double wy = cameraY + posY[flake];
                    double wz = posZ[flake];
                    double leeHeight = SnowLee.sampleBilinear(leeMap, TEST_MAP, wx, wz, 1.0, 0.0);
                    double terrainSlope = SnowLee.sampleBilinear(slopeMap, TEST_MAP, wx, wz, 1.0, 0.0);
                    double smoothHeight = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, wx + TEST_HALF,
                            wz + TEST_HALF);
                    double columnHeight = heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
                    vy = SnowLee.verticalVelocity(wy, leeHeight, columnHeight, smoothHeight, terrainSlope,
                            ownFall[flake], windSpeed, leeFall);
                }
                double nextX = posX[flake] + windSpeed * dt;
                double nextY = posY[flake] + vy * dt;
                double nextZ = posZ[flake];
                if (Math.abs(nextX) >= halfSide || Math.abs(nextY) >= halfSide || Math.abs(nextZ) >= halfSide) {
                    double topVy = SnowLee.verticalVelocity(cameraY + halfSide, centerLee, centerColumn, centerSmooth,
                            centerSlope, ownFall[flake], windSpeed, leeFall);
                    double bottomVy = SnowLee.verticalVelocity(cameraY - halfSide, centerLee, centerColumn,
                            centerSmooth, centerSlope, ownFall[flake], windSpeed, leeFall);
                    SnowLee.reenterBoxOffset(flake, frame, windSpeed * dt, topVy * dt, bottomVy * dt, 0.0, boxSide,
                            reentry);
                    posX[flake] = reentry[0];
                    posY[flake] = reentry[1];
                    posZ[flake] = reentry[2];
                    double wx = cameraX + posX[flake];
                    double wy = cameraY + posY[flake];
                    double wz = posZ[flake];
                    stopped[flake] = wy < heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
                } else {
                    if (!stopped[flake]) {
                        double dx = nextX - posX[flake];
                        double dy = nextY - posY[flake];
                        int substeps = SnowFlakes.substepCount(Math.sqrt(dx * dx + dy * dy));
                        for (int sub = 1; sub <= substeps; sub++) {
                            double share = (double) sub / substeps;
                            double wx = cameraX + posX[flake] + dx * share;
                            double wy = cameraY + posY[flake] + dy * share;
                            double wz = posZ[flake];
                            if (wy < heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz))) {
                                stopped[flake] = true;
                                break;
                            }
                        }
                    }
                    posX[flake] = nextX;
                    posY[flake] = nextY;
                    posZ[flake] = nextZ;
                }
            }
        }
        double visibleSum = 0.0;
        for (int flake = 0; flake < flakeCount; flake++) {
            if (stopped[flake]) {
                continue;
            }
            double ox = posX[flake];
            double wy = cameraY + posY[flake];
            if (ox < -3.0 || ox >= 3.0 || wy < GROUND + 0.5 || wy >= GROUND + 4.5) {
                continue;
            }
            double wx = cameraX + ox;
            double wz = posZ[flake];
            double leeHeight = SnowLee.sampleBilinear(leeMap, TEST_MAP, wx, wz, 1.0, 0.0);
            double smoothHeight = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, wx + TEST_HALF, wz + TEST_HALF);
            double columnHeight = heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
            double share = SnowLee.leeShare(wy, leeHeight, columnHeight, smoothHeight, leeBand);
            visibleSum += SnowLee.leeWeight(flake, share);
        }
        return visibleSum;
    }

    private static void assertEachFloorThirdHasHits(GameTestHelper helper, String label, double[] stats,
            double minimumPerThird) {
        String[] thirdNames = {"upwind third", "middle third", "downwind third"};
        for (int third = 0; third < 3; third++) {
            double hits = stats[3 + third];
            helper.assertTrue(hits >= minimumPerThird, label + " had only " + hits + " visible floor hits in the "
                    + thirdNames[third] + ", expected >= " + minimumPerThird);
        }
    }

    private static double[] simulateRavineSnow(SnowLee.HeightLookup heights, float[] smooth, float[] leeMap,
            float[] slopeMap, float[] cavSurf, float[] cavRim, int airMinX, int airMaxX, short floorHeight,
            double cameraX, double cameraY, double windSpeed, double fallSpeed, double leeFall, double leeBand) {
        int flakeCount = 8192;
        int warmupSteps = 180;
        int measureSteps = 180;
        double dt = 1.0 / 30.0;
        double boxSide = 40.0;
        double halfSide = 0.5 * boxSide;
        double[] posX = new double[flakeCount];
        double[] posY = new double[flakeCount];
        double[] posZ = new double[flakeCount];
        boolean[] stopped = new boolean[flakeCount];
        double[] ownFall = new double[flakeCount];
        for (int flake = 0; flake < flakeCount; flake++) {
            posX[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_X) - 0.5) * boxSide;
            posY[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_Y) - 0.5) * boxSide;
            posZ[flake] = (SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_Z) - 0.5) * boxSide;
            ownFall[flake] = fallSpeed * SnowFlakes.fallShare(flake);
            double wx = cameraX + posX[flake];
            double wy = cameraY + posY[flake];
            double wz = posZ[flake];
            stopped[flake] = wy < heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
        }
        int camColX = (int) Math.floor(cameraX);
        int camIdx = TEST_HALF * TEST_MAP + (camColX + TEST_HALF);
        double centerColumn = heights.heightAt(camColX, 0);
        double centerLee = SnowLee.sampleBilinear(leeMap, TEST_MAP, cameraX, 0.0, 1.0, 0.0);
        double centerSlope = SnowLee.sampleBilinear(slopeMap, TEST_MAP, cameraX, 0.0, 1.0, 0.0);
        double centerSmooth = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, cameraX + TEST_HALF, TEST_HALF);
        double centerBrakeHeight = SnowLee.cavityBrakeHeight(cavSurf[camIdx], cavRim[camIdx], centerColumn);
        double topWindScale = SnowLee.brakeWindScaleAt(cameraY + halfSide, centerBrakeHeight);
        double bottomWindScale = SnowLee.brakeWindScaleAt(cameraY - halfSide, centerBrakeHeight);
        double[] reentry = new double[3];

        double floorSpan = (airMaxX - airMinX) + 1.0;
        boolean floorInBox = floorHeight >= cameraY - halfSide + 0.5;
        double measureY = floorInBox ? floorHeight : cameraY - halfSide + 1.0;
        double visibleFloorHits = 0.0;
        double rawFloorHits = 0.0;
        double[] thirdHits = new double[3];
        for (int frame = 1; frame <= warmupSteps + measureSteps; frame++) {
            boolean measuring = frame > warmupSteps;
            for (int flake = 0; flake < flakeCount; flake++) {
                double vx;
                double vy;
                if (stopped[flake]) {
                    vx = windSpeed * SnowLee.brakeWindScaleAt(cameraY + posY[flake], centerBrakeHeight);
                    vy = -ownFall[flake];
                } else {
                    double wx = cameraX + posX[flake];
                    double wy = cameraY + posY[flake];
                    double wz = posZ[flake];
                    int colX = (int) Math.floor(wx);
                    int colZ = (int) Math.floor(wz);
                    double columnHeight = heights.heightAt(colX, colZ);
                    int cIdx = Math.max(0, Math.min(TEST_MAP - 1, colZ + TEST_HALF)) * TEST_MAP
                            + Math.max(0, Math.min(TEST_MAP - 1, colX + TEST_HALF));
                    double cavOpen = SnowLee.cavityOpenFactor(wy, cavSurf[cIdx], cavRim[cIdx], columnHeight);
                    double windScale = SnowLee.cavityWindScale(cavOpen);
                    vx = windSpeed * windScale;
                    double leeHeight = SnowLee.sampleBilinear(leeMap, TEST_MAP, wx, wz, 1.0, 0.0);
                    double terrainSlope = SnowLee.sampleBilinear(slopeMap, TEST_MAP, wx, wz, 1.0, 0.0);
                    double smoothHeight = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH, wx + TEST_HALF,
                            wz + TEST_HALF);
                    vy = SnowLee.verticalVelocity(wy, leeHeight, columnHeight, smoothHeight, terrainSlope,
                            ownFall[flake], windSpeed * windScale, leeFall * windScale);
                }
                double nextX = posX[flake] + vx * dt;
                double nextY = posY[flake] + vy * dt;
                double nextZ = posZ[flake];
                if (Math.abs(nextX) >= halfSide || Math.abs(nextY) >= halfSide || Math.abs(nextZ) >= halfSide) {
                    double topVy = SnowLee.verticalVelocity(cameraY + halfSide, centerLee, centerColumn, centerSmooth,
                            centerSlope, ownFall[flake], windSpeed * topWindScale, leeFall * topWindScale);
                    double bottomVy = SnowLee.verticalVelocity(cameraY - halfSide, centerLee, centerColumn,
                            centerSmooth, centerSlope, ownFall[flake], windSpeed * bottomWindScale,
                            leeFall * bottomWindScale);
                    SnowLee.reenterBoxOffset(flake, frame, windSpeed * dt, 0.0, topVy * dt, bottomVy * dt, 0.0, 0.0,
                            cameraY, centerBrakeHeight, boxSide, reentry);
                    posX[flake] = reentry[0];
                    posY[flake] = reentry[1];
                    posZ[flake] = reentry[2];
                    double wx = cameraX + posX[flake];
                    double wy = cameraY + posY[flake];
                    double wz = posZ[flake];
                    stopped[flake] = wy < heights.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
                } else {
                    if (!stopped[flake]) {
                        double dx = nextX - posX[flake];
                        double dy = nextY - posY[flake];
                        int substeps = SnowFlakes.substepCount(Math.sqrt(dx * dx + dy * dy));
                        double prevWy = cameraY + posY[flake];
                        for (int sub = 1; sub <= substeps; sub++) {
                            double share = (double) sub / substeps;
                            double wx = cameraX + posX[flake] + dx * share;
                            double wy = cameraY + posY[flake] + dy * share;
                            double wz = posZ[flake];
                            int colX = (int) Math.floor(wx);
                            int colZ = (int) Math.floor(wz);
                            short colH = heights.heightAt(colX, colZ);
                            if (wy < colH) {
                                stopped[flake] = true;
                                if (floorInBox && measuring && colX >= airMinX && colX <= airMaxX
                                        && colH == floorHeight && prevWy >= floorHeight) {
                                    int cIdx = Math.max(0, Math.min(TEST_MAP - 1, colZ + TEST_HALF)) * TEST_MAP
                                            + Math.max(0, Math.min(TEST_MAP - 1, colX + TEST_HALF));
                                    double cavOpen = SnowLee.cavityOpenFactor(floorHeight, cavSurf[cIdx], cavRim[cIdx],
                                            floorHeight);
                                    double leeHeight = SnowLee.sampleBilinear(leeMap, TEST_MAP, wx, wz, 1.0, 0.0);
                                    double smoothHeight = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH,
                                            wx + TEST_HALF, wz + TEST_HALF);
                                    double leeShare = SnowLee.leeShare(floorHeight, leeHeight, floorHeight,
                                            smoothHeight, cavOpen, leeBand);
                                    double weight = SnowLee.leeWeight(flake, leeShare);
                                    visibleFloorHits += weight;
                                    rawFloorHits += 1.0;
                                    int third = Math.max(0, Math.min(2, (int) Math.floor(3.0 * (wx - airMinX)
                                            / floorSpan)));
                                    thirdHits[third] += weight;
                                }
                                break;
                            }
                            if (!floorInBox && measuring && colX >= airMinX && colX <= airMaxX
                                    && colH == floorHeight && prevWy >= measureY && wy < measureY) {
                                int cIdx = Math.max(0, Math.min(TEST_MAP - 1, colZ + TEST_HALF)) * TEST_MAP
                                        + Math.max(0, Math.min(TEST_MAP - 1, colX + TEST_HALF));
                                double cavOpen = SnowLee.cavityOpenFactor(measureY, cavSurf[cIdx], cavRim[cIdx],
                                        floorHeight);
                                double leeHeight = SnowLee.sampleBilinear(leeMap, TEST_MAP, wx, wz, 1.0, 0.0);
                                double smoothHeight = SnowLee.sampleSmoothBilinear(smooth, TEST_SMOOTH,
                                        wx + TEST_HALF, wz + TEST_HALF);
                                double leeShare = SnowLee.leeShare(measureY, leeHeight, floorHeight,
                                        smoothHeight, cavOpen, leeBand);
                                double weight = SnowLee.leeWeight(flake, leeShare);
                                visibleFloorHits += weight;
                                rawFloorHits += 1.0;
                                int third = Math.max(0, Math.min(2, (int) Math.floor(3.0 * (wx - airMinX)
                                        / floorSpan)));
                                thirdHits[third] += weight;
                            }
                            prevWy = wy;
                        }
                    }
                    posX[flake] = nextX;
                    posY[flake] = nextY;
                    posZ[flake] = nextZ;
                }
            }
        }
        // Measure live flake density inside the cavity air volume inside the simulation box:
        double sampleBottom = Math.max(floorHeight + 1.0, cameraY - halfSide + 1.0);
        double sampleTop = Math.min(cameraY + halfSide - 1.0, Math.min(sampleBottom + 14.0, centerBrakeHeight));
        double sampleHeight = Math.max(4.0, sampleTop - sampleBottom);
        int liveInRavine = 0;
        for (int flake = 0; flake < flakeCount; flake++) {
            if (stopped[flake]) {
                continue;
            }
            double wx = cameraX + posX[flake];
            double wy = cameraY + posY[flake];
            if (wx >= airMinX && wx < airMaxX + 1 && wy >= sampleBottom && wy < sampleBottom + sampleHeight) {
                liveInRavine++;
            }
        }
        double expectedLive = flakeCount * (floorSpan * sampleHeight * boxSide) / (boxSide * boxSide * boxSide);
        double meanWeight = rawFloorHits > 0.0 ? visibleFloorHits / rawFloorHits : 0.0;
        return new double[]{visibleFloorHits, meanWeight, liveInRavine / expectedLive, thirdHits[0], thirdHits[1],
                thirdHits[2]};
    }

    private static void runSmoothAndScan(SnowLee.HeightLookup heights, int mapSize, int[] strides, double dirX,
            double dirZ, double slope, boolean applyAcrossMin, float[] smooth, float[] smoothScratchA,
            float[] smoothScratchB, float[] leeOut, float[] slopeOut, float[] rawA, float[] rawB, float[] relA,
            float[] relB) {
        int half = mapSize / 2;
        int smoothSize = mapSize / SnowLee.SMOOTH_SCALE;
        SnowLee.buildSmoothHeights(mapSize, heights, -half, -half, smooth, smoothScratchA, smoothScratchB);
        SnowLee.scan(mapSize, strides, heights, smooth, smoothSize, -half, -half, 0, 0, -half, -half, mapSize, dirX,
                dirZ, slope, applyAcrossMin, leeOut, slopeOut, rawA, rawB, relA, relB);
    }

    private static void assertScanMatchesBruteForceSampled(GameTestHelper helper, String label,
            SnowLee.HeightLookup heights, float[] smooth, double dirX, double dirZ, double slope,
            boolean applyAcrossMin, float[] leeOut) {
        for (int iy = 0; iy < TEST_MAP; iy += 16) {
            for (int ix = 0; ix < TEST_MAP; ix += 8) {
                float actual = leeOut[iy * TEST_MAP + ix];
                float expected = SnowLee.bruteForceLeeTexel(TEST_MAP, TEST_REACH, heights, smooth, TEST_SMOOTH,
                        -TEST_HALF, -TEST_HALF, 0, 0, -TEST_HALF, -TEST_HALF, TEST_MAP, dirX, dirZ, slope,
                        applyAcrossMin, ix, iy);
                if (Math.abs(actual - expected) > SCAN_TOLERANCE) {
                    helper.fail(label + " scan differs from brute force at texel (" + ix + ", " + iy + "): got "
                            + actual + " instead of " + expected);
                    return;
                }
            }
        }
    }

    private static void checkLeeShaderConstants(GameTestHelper helper, String fileName,
            Map<String, Double> constants) {
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_MAP_SIZE", SnowLee.MAP_SIZE, 0.0);
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_HALF_SIZE", SnowLee.HALF_SIZE, 0.0);
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_FAR_HEIGHTS_SIZE", FarHeights.SIZE, 0.0);
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_FAR_HEIGHTS_MASK", FarHeights.SIZE - 1, 0.0);
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_UNKNOWN_HEIGHT", FarHeights.UNKNOWN, 0.0);
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_NO_HEIGHT", SnowLee.NO_HEIGHT, 0.0);
        checkShaderConstant(helper, fileName, constants, "SNOW_LEE_NO_HEIGHT_THRESHOLD",
                SnowLee.NO_HEIGHT_THRESHOLD, 0.0);
    }

    private static Map<String, Double> parseShaderConstants(GameTestHelper helper, String shaderFile) {
        String resourcePath = "/assets/frostedheart/shaders/" + shaderFile;
        String source;
        try (InputStream stream = SnowFlakes.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                helper.fail("Shader resource " + resourcePath + " was not found on the classpath");
                return null;
            }
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            helper.fail("Failed to read shader resource " + resourcePath + ": " + exception.getMessage());
            return null;
        }
        Map<String, Double> constants = new HashMap<>();
        String[] lines = source.split("\\R");
        for (String line : lines) {
            Matcher matcher = GLSL_CONST_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(1);
            String valueText = matcher.group(2).trim();
            Double parsed = parseNumericLiteral(valueText);
            if (parsed != null) {
                constants.put(name, parsed);
            }
        }
        return constants;
    }

    private static Double parseNumericLiteral(String text) {
        String cleaned = text.trim();
        if (cleaned.endsWith("u") || cleaned.endsWith("U")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
            try {
                return (double) Long.parseLong(cleaned.substring(2), 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void checkShaderConstant(GameTestHelper helper, String fileName, Map<String, Double> constants,
            String name, double expected, double tolerance) {
        Double actual = constants.get(name);
        helper.assertTrue(actual != null, fileName + " is missing const " + name);
        helper.assertTrue(Math.abs(actual - expected) <= tolerance, fileName + " defines " + name + " = " + actual
                + " instead of Java value " + expected);
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
}
