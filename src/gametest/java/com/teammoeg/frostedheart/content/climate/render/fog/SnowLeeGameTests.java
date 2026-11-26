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
 * Checks the snowfall lee map twin (SnowLee) against an independent brute-force ray maximum on synthetic layouts,
 * verifies the fade weight, sinking downdraft and rebuild trigger rules, and verifies that the shared GLSL constants
 * across the snow shaders match their Java definitions.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowLeeGameTests {
    private static final double TOLERANCE = 1.0e-9;
    private static final float SCAN_TOLERANCE = 1.0e-3F;
    private static final int SMALL_MAP = 64;
    private static final int SMALL_HALF = SMALL_MAP / 2;
    private static final int SMALL_REACH = 48;
    private static final int[] SMALL_STRIDES = {1, 2, 4, 8, 16, 16};
    private static final short GROUND = 64;
    private static final Pattern GLSL_CONST_PATTERN = Pattern.compile(
            "^\\s*const\\s+(?:int|uint|float)\\s+([A-Z0-9_]+)\\s*=\\s*([^;]+);");

    @GameTest(template = "phase0a_empty", timeoutTicks = 60)
    public static void scanMatchesBruteForceOnWallRidgeSlopeDiagonalAndTower(GameTestHelper helper) {
        float[] output = new float[SMALL_MAP * SMALL_MAP];
        float[] scratch = new float[SMALL_MAP * SMALL_MAP];
        double slope = 0.2;

        // 1. A 1-block-thick, 7-block-wide wall across +x wind at x = -10, height 68 (drop 4, lee length 20):
        SnowLee.HeightLookup wall = (x, z) -> (x == -10 && z >= -3 && z <= 3) ? (short) 68 : GROUND;
        assertScanMatchesBruteForce(helper, "7-wide wall", SMALL_MAP, SMALL_REACH, SMALL_STRIDES, wall, 0, 0,
                -SMALL_HALF, -SMALL_HALF, SMALL_MAP, 1.0, 0.0, slope, true, output, scratch);
        // On top of the wall (t = 0 excluded): no lee above 68, lee height comes from upwind ground (64 - 0.2):
        float wallTopLee = SnowLee.sampleBilinear(output, SMALL_MAP, -9.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "lee height on top of wall", wallTopLee, 63.8F, SCAN_TOLERANCE);
        // 10 blocks behind the wall center: 68 - 0.2 * 10 = 66.0:
        float behindWall = SnowLee.sampleBilinear(output, SMALL_MAP, 0.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "lee height 10 blocks behind wall", behindWall, 66.0F, SCAN_TOLERANCE);
        // 20 blocks behind the wall center: 68 - 0.2 * 20 = 64.0 (lee ends at drop / c = 20):
        float atLeeEnd = SnowLee.sampleBilinear(output, SMALL_MAP, 10.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "lee height at drop / c behind wall", atLeeEnd, 64.0F, SCAN_TOLERANCE);
        // 25 blocks behind the wall: back to flat ground (64 - 0.2 = 63.8):
        float pastLee = SnowLee.sampleBilinear(output, SMALL_MAP, 15.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "lee height past wall lee", pastLee, 63.8F, SCAN_TOLERANCE);

        // 2. A 1-block tower 20 blocks tall casts NO lee at 0, 30 and 45 degrees:
        SnowLee.HeightLookup tower = (x, z) -> (x == -10 && z == 0) ? (short) 84 : GROUND;
        double[] towerAngles = {0.0, Math.toRadians(30.0), Math.toRadians(45.0)};
        for (double angle : towerAngles) {
            double dirX = Math.cos(angle);
            double dirZ = Math.sin(angle);
            assertScanMatchesBruteForce(helper, "1-block tower at " + Math.toDegrees(angle) + " deg", SMALL_MAP,
                    SMALL_REACH, SMALL_STRIDES, tower, 0, 0, -SMALL_HALF, -SMALL_HALF, SMALL_MAP, dirX, dirZ, slope,
                    true, output, scratch);
            for (int index = 0; index < output.length; index++) {
                if (output[index] > GROUND) {
                    helper.fail("1-block tower at " + Math.toDegrees(angle) + " deg cast a lee of " + output[index]
                            + " at texel " + index);
                    return;
                }
            }
        }

        // A 2-block-wide pillar across +x wind casts NO lee; a 3-block-wide wall DOES cast a lee along its center:
        SnowLee.HeightLookup twoWide = (x, z) -> (x == -10 && (z == 0 || z == 1)) ? (short) 84 : GROUND;
        SnowLee.scan(SMALL_MAP, SMALL_STRIDES, twoWide, 0, 0, -SMALL_HALF, -SMALL_HALF, SMALL_MAP, 1.0, 0.0, slope,
                true, output, scratch);
        for (int index = 0; index < output.length; index++) {
            if (output[index] > GROUND) {
                helper.fail("2-block-wide pillar cast a lee of " + output[index] + " at texel " + index);
                return;
            }
        }
        SnowLee.HeightLookup threeWide = (x, z) -> (x == -10 && z >= -1 && z <= 1) ? (short) 84 : GROUND;
        SnowLee.scan(SMALL_MAP, SMALL_STRIDES, threeWide, 0, 0, -SMALL_HALF, -SMALL_HALF, SMALL_MAP, 1.0, 0.0, slope,
                true, output, scratch);
        float threeWideLee = SnowLee.sampleBilinear(output, SMALL_MAP, 0.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "3-wide wall lee 10 blocks behind center", threeWideLee, 82.0F, SCAN_TOLERANCE);

        // 3. A ridge (plateau at 84 for x <= -10, dropping to 64 for x > -10):
        SnowLee.HeightLookup ridge = (x, z) -> x <= -10 ? (short) 84 : GROUND;
        assertScanMatchesBruteForce(helper, "ridge", SMALL_MAP, SMALL_REACH, SMALL_STRIDES, ridge, 0, 0, -SMALL_HALF,
                -SMALL_HALF, SMALL_MAP, 1.0, 0.0, slope, true, output, scratch);
        float ridgeTop = SnowLee.sampleBilinear(output, SMALL_MAP, -15.5, 0.5, 1.0, 0.0);
        helper.assertTrue(ridgeTop < 84.0F, "ridge plateau top got lee height " + ridgeTop + " instead of below 84");
        float ridgeLee = SnowLee.sampleBilinear(output, SMALL_MAP, 0.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "ridge lee 10 blocks downwind", ridgeLee, 82.0F, SCAN_TOLERANCE);

        // 4. A gentle slope gentler than c casts no lee above its own terrain height:
        // Steps down 1 block every 4 blocks (average 0.25) with c = 1.0 so even adjacent steps never exceed H(x):
        SnowLee.HeightLookup gentleSlope = (x, z) -> (short) (80 - Math.floorDiv(x + 32, 4));
        assertScanMatchesBruteForce(helper, "gentle slope", SMALL_MAP, SMALL_REACH, SMALL_STRIDES, gentleSlope, 0, 0,
                -SMALL_HALF, -SMALL_HALF, SMALL_MAP, 1.0, 0.0, 1.0, true, output, scratch);
        for (int x = -20; x <= 20; x++) {
            float lee = SnowLee.sampleBilinear(output, SMALL_MAP, x + 0.5, 0.5, 1.0, 0.0);
            short terrain = gentleSlope.heightAt(x, 0);
            if (lee > terrain) {
                helper.fail("gentle slope cast a lee of " + lee + " above terrain height " + terrain + " at x = " + x);
                return;
            }
        }

        // 5. Diagonal 45-degree wind across a 1-block-thick long wall: keeps its lee and matches brute force:
        double diag = Math.sqrt(0.5);
        SnowLee.HeightLookup longWall = (x, z) -> (x == -8 && z >= -24 && z <= 24) ? (short) 74 : GROUND;
        assertScanMatchesBruteForce(helper, "diagonal wind over 1-thick wall", SMALL_MAP, SMALL_REACH, SMALL_STRIDES,
                longWall, 0, 0, -SMALL_HALF, -SMALL_HALF, SMALL_MAP, diag, diag, slope, true, output, scratch);
        // Point (0.5, 0.5) is 8 blocks east of x = -8, so along 45-deg ray upwind distance is ~8 * sqrt(2) = 11.31:
        float diagLee = SnowLee.sampleBilinear(output, SMALL_MAP, 0.5, 0.5, diag, diag);
        helper.assertTrue(diagLee > 70.5F && diagLee < 72.5F,
                "diagonal wind over 1-block-thick wall gave lee height " + diagLee + " instead of about 71.5");

        // 6. Calm wind casts no lee:
        helper.assertTrue(SnowLee.isCalm(0.0), "wind speed 0.0 was not treated as calm");
        helper.assertTrue(SnowLee.isCalm(0.005), "wind speed 0.005 was not treated as calm");
        helper.assertTrue(SnowLee.isCalm(Double.NaN), "NaN wind speed was not treated as calm");
        helper.assertFalse(SnowLee.isCalm(1.0), "wind speed 1.0 was treated as calm");
        helper.assertTrue(Double.isInfinite(SnowLee.slope(0.6, 0.0, 0.15)),
                "calm wind slope was finite instead of infinite");
        checkNear(helper, "calm wind leeFallSpeed", SnowLee.leeFallSpeed(0.2, 0.0), 0.0);

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fullSizeScanReaches480AndDoesNotWrapOutsideWindow(GameTestHelper helper) {
        float[] output = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        float[] scratch = new float[SnowLee.MAP_SIZE * SnowLee.MAP_SIZE];
        int[] strides = SnowLee.passStrides();
        double slope = 0.2;

        // Verify the production stride sum covers exactly REACH = 480:
        int covered = 1;
        for (int stride : strides) {
            helper.assertTrue(stride <= covered, "stride " + stride + " exceeds covered range " + covered
                    + ", leaving a gap in the doubling scan");
            covered += stride;
        }
        helper.assertTrue(covered == SnowLee.REACH, "production strides cover " + covered + " instead of "
                + SnowLee.REACH);

        // Place:
        // - a ridge 450 blocks upwind of anchor (x = -450, height 200) -> reaches anchor at 200 - 0.2 * 450 = 110;
        // - a huge mountain 400 blocks DOWNWIND of anchor (x = +400, height 300) -> must NEVER wrap to anchor;
        // - a huge wall OUTSIDE the FarHeights window (x = -520, height 300) -> must NEVER be read.
        SnowLee.HeightLookup terrain = (x, z) -> {
            if (x == -450) {
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
        SnowLee.scan(SnowLee.MAP_SIZE, strides, terrain, 0, 0, -SnowLee.HALF_SIZE, -SnowLee.HALF_SIZE,
                SnowLee.MAP_SIZE, 1.0, 0.0, slope, true, output, scratch);

        float atAnchor = SnowLee.sampleBilinear(output, SnowLee.MAP_SIZE, 0.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "450-block upwind ridge at anchor", atAnchor, 110.0F, 0.05F);

        // Upwind of the -450 ridge (at x = -460), neither the x = -520 out-of-window wall nor the x >= +400 downwind
        // mountain may wrap in, so lee height must be ground - slope = 63.8:
        float upwindOfRidge = SnowLee.sampleBilinear(output, SnowLee.MAP_SIZE, -459.5, 0.5, 1.0, 0.0);
        checkFloatNear(helper, "upwind of ridge with no wrap", upwindOfRidge, 63.8F, 0.05F);

        // At 45 degrees, corners of the rotated 1024 map lie outside FarHeights's [-512, 511]^2 window.
        // Put a 300-block wall strictly outside [-512, 511]^2 and check nothing leaks in:
        double diag = Math.sqrt(0.5);
        SnowLee.HeightLookup outsideOnly = (x, z) -> (x < -512 || x >= 512 || z < -512 || z >= 512)
                ? (short) 300 : GROUND;
        SnowLee.scan(SnowLee.MAP_SIZE, strides, outsideOnly, 0, 0, -SnowLee.HALF_SIZE, -SnowLee.HALF_SIZE,
                SnowLee.MAP_SIZE, diag, diag, slope, true, output, scratch);
        float diagCenter = SnowLee.sampleBilinear(output, SnowLee.MAP_SIZE, 0.5, 0.5, diag, diag);
        helper.assertTrue(diagCenter <= GROUND, "diagonal scan leaked out-of-window terrain, giving " + diagCenter);
        // Out-of-bounds bilinear lookup returns NO_HEIGHT:
        float outOfBounds = SnowLee.sampleBilinear(output, SnowLee.MAP_SIZE, 600.0, 0.0, 1.0, 0.0);
        checkFloatNear(helper, "out-of-bounds lookup", outOfBounds, (float) SnowLee.NO_HEIGHT, 0.01F);

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 40)
    public static void leeFadeWeightSinkingAndRebuildTriggersBehaveAsDesigned(GameTestHelper helper) {
        // Lee share on flat ground (h <= H): 1.0 everywhere:
        checkNear(helper, "flat ground at ground", SnowLee.leeShare(64.0, 63.85, 64.0, 3.0), 1.0);
        checkNear(helper, "flat ground just above ground", SnowLee.leeShare(64.1, 63.85, 64.0, 3.0), 1.0);
        checkNear(helper, "flat ground with band 0", SnowLee.leeShare(64.0, 63.85, 64.0, 0.0), 1.0);
        // Deep lee (h - H = 6.0 >= band = 3.0):
        checkNear(helper, "deep lee on floor", SnowLee.leeShare(64.0, 70.0, 64.0, 3.0), 0.0);
        checkNear(helper, "deep lee at surface", SnowLee.leeShare(70.0, 70.0, 64.0, 3.0), 0.0);
        checkNear(helper, "deep lee halfway through band", SnowLee.leeShare(71.5, 70.0, 64.0, 3.0), 0.5);
        checkNear(helper, "deep lee above band", SnowLee.leeShare(73.0, 70.0, 64.0, 3.0), 1.0);
        // Shallow lee tip (h - H = 1.0 < band = 3.0): effectiveBand = 1.0:
        checkNear(helper, "shallow lee below surface", SnowLee.leeShare(64.5, 65.0, 64.0, 3.0), 0.0);
        checkNear(helper, "shallow lee halfway through tip band", SnowLee.leeShare(65.5, 65.0, 64.0, 3.0), 0.5);
        checkNear(helper, "shallow lee above tip band", SnowLee.leeShare(66.0, 65.0, 64.0, 3.0), 1.0);
        // Band 0 in a lee: sharp step at h:
        checkNear(helper, "sharp band below surface", SnowLee.leeShare(69.99, 70.0, 64.0, 0.0), 0.0);
        checkNear(helper, "sharp band at surface", SnowLee.leeShare(70.0, 70.0, 64.0, 0.0), 1.0);
        // Unknown heights give 1.0:
        checkNear(helper, "unknown lee height", SnowLee.leeShare(65.0, SnowLee.NO_HEIGHT, 64.0, 3.0), 1.0);
        checkNear(helper, "unknown column height", SnowLee.leeShare(65.0, 70.0, SnowLee.NO_HEIGHT, 3.0), 1.0);

        // Per-flake lee weight distribution and independence from shelter salt:
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
        helper.assertTrue(Math.abs(meanWeight - 0.5) < 0.01, "mean leeWeight at share 0.5 is " + meanWeight
                + " instead of 0.5");
        helper.assertTrue(identicalCount < flakes * 0.6, "leeWeight coincided with shelterWeight on " + identicalCount
                + " of " + flakes + " flakes");

        // Sinking fall speed:
        double ownFall = 0.6;
        double leeFall = 2.1;
        checkNear(helper, "sinking on flat ground", SnowLee.sinkingFallSpeed(65.0, 63.8, 64.0, ownFall, leeFall),
                ownFall);
        checkNear(helper, "sinking inside lee", SnowLee.sinkingFallSpeed(66.0, 70.0, 64.0, ownFall, leeFall), leeFall);
        checkNear(helper, "sinking at lee surface", SnowLee.sinkingFallSpeed(70.0, 70.0, 64.0, ownFall, leeFall),
                leeFall);
        checkNear(helper, "sinking halfway above lee", SnowLee.sinkingFallSpeed(73.0, 70.0, 64.0, ownFall, leeFall),
                0.5 * (ownFall + leeFall));
        checkNear(helper, "sinking at top of fade zone", SnowLee.sinkingFallSpeed(76.0, 70.0, 64.0, ownFall, leeFall),
                ownFall);
        checkNear(helper, "sinking never slows a faster flake",
                SnowLee.sinkingFallSpeed(66.0, 70.0, 64.0, 2.5, leeFall), 2.5);

        // Rebuild triggers:
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "identical inputs triggered a lee map rebuild");
        helper.assertTrue(SnowLee.needsRebuild(false, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "missing published map did not trigger a rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 16, 0, 5L, 1.0, 10.0, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "anchor move did not trigger a rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 6L, 1.0, 10.0, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "heights version change did not trigger a rebuild");
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0 + Math.toRadians(0.1), 10.0, 0.6, 0.15, 0, 0, 5L,
                1.0, 10.0, 0.6, 0.15), "0.1 deg wind turn triggered a rebuild below 0.25 deg");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0 + Math.toRadians(0.3), 10.0, 0.6, 0.15, 0, 0, 5L,
                1.0, 10.0, 0.6, 0.15), "0.3 deg wind turn did not trigger a rebuild");
        // Wrap across +pi / -pi must measure the small angle difference, not 2*pi:
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, -Math.PI + 0.001, 10.0, 0.6, 0.15, 0, 0, 5L,
                Math.PI - 0.001, 10.0, 0.6, 0.15), "small turn across pi/-pi wrap triggered a rebuild");
        helper.assertFalse(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.1, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "1% speed change triggered a rebuild below 2%");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.25, 0.6, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "2.5% speed change did not trigger a rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.0, 0.8, 0.15, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "fallSpeed change did not trigger a rebuild");
        helper.assertTrue(SnowLee.needsRebuild(true, 0, 0, 5L, 1.0, 10.0, 0.6, 0.2, 0, 0, 5L, 1.0, 10.0, 0.6, 0.15),
                "leeFill change did not trigger a rebuild");

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void sharedShaderConstantsMatchJavaDefinitions(GameTestHelper helper) {
        Map<String, Double> flakesGlsl = parseShaderConstants(helper, "snow_flakes.glsl");
        Map<String, Double> updateFsh = parseShaderConstants(helper, "snow_flake_update.fsh");
        Map<String, Double> drawGlsl = parseShaderConstants(helper, "snow_flakes_draw.glsl");
        Map<String, Double> leeGlsl = parseShaderConstants(helper, "snow_lee.glsl");
        Map<String, Double> leeBuildFsh = parseShaderConstants(helper, "snow_lee_build.fsh");
        if (flakesGlsl == null || updateFsh == null || drawGlsl == null || leeGlsl == null || leeBuildFsh == null) {
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

        // Verify all 16 salts in snow_flakes.glsl are distinct and in [0, 15]:
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

        // snow_lee.glsl and snow_lee_build.fsh:
        checkLeeShaderConstants(helper, "snow_lee.glsl", leeGlsl);
        checkLeeShaderConstants(helper, "snow_lee_build.fsh", leeBuildFsh);
        checkShaderConstant(helper, "snow_lee_build.fsh", leeBuildFsh, "SNOW_LEE_SAMPLE_OFFSET",
                SnowLee.SAMPLE_OFFSET, 0.0);

        helper.succeed();
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

    private static void assertScanMatchesBruteForce(GameTestHelper helper, String label, int mapSize, int reach,
            int[] strides, SnowLee.HeightLookup heights, int anchorX, int anchorZ, int windowMinX, int windowMinZ,
            int windowSize, double dirX, double dirZ, double slope, boolean applyAcrossMin, float[] output,
            float[] scratch) {
        SnowLee.scan(mapSize, strides, heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize, dirX, dirZ,
                slope, applyAcrossMin, output, scratch);
        for (int iy = 0; iy < mapSize; iy++) {
            for (int ix = 0; ix < mapSize; ix++) {
                float actual = output[iy * mapSize + ix];
                float expected = SnowLee.bruteForceTexel(mapSize, reach, heights, anchorX, anchorZ, windowMinX,
                        windowMinZ, windowSize, dirX, dirZ, slope, applyAcrossMin, ix, iy);
                if (Math.abs(actual - expected) > SCAN_TOLERANCE) {
                    helper.fail(label + " scan differs from brute force at texel (" + ix + ", " + iy + "): got "
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
}
