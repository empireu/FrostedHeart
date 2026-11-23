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

/**
 * Checks the Java twin of the snowflakes: the periodic box, the count drawn from the fog's extinction, the fades, and
 * the spreads of each flake's own values.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFlakesGameTests {
    private static final double TOLERANCE = 1.0e-9;
    private static final double BOX_SIDE = 40.0;

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void flakesWrapIntoTheBoxAndDrawTheirNearestCopy(GameTestHelper helper) {
        checkNear(helper, "wrap of -0.5", SnowFlakes.wrap(-0.5, BOX_SIDE), 39.5);
        checkNear(helper, "wrap of the side", SnowFlakes.wrap(BOX_SIDE, BOX_SIDE), 0.0);
        checkNear(helper, "wrap of 85.25", SnowFlakes.wrap(85.25, BOX_SIDE), 5.25);
        checkNear(helper, "nearest copy of 39 from 1", SnowFlakes.nearestCopyOffset(39.0, 1.0, BOX_SIDE), -2.0);
        checkNear(helper, "nearest copy of 5 from 30", SnowFlakes.nearestCopyOffset(5.0, 30.0, BOX_SIDE), 15.0);
        // A flake that wraps from the top of the box to its bottom moves on by its step, seen from a camera inside:
        double before = SnowFlakes.nearestCopyOffset(39.9, 0.5, BOX_SIDE);
        double after = SnowFlakes.nearestCopyOffset(SnowFlakes.step(39.9, 1.0, 0.2, BOX_SIDE), 0.5, BOX_SIDE);
        checkNear(helper, "step across the wrap", after - before, 0.2);
        // A camera far from the origin sees the same offsets as one inside the box:
        double farCamera = 1.0e7 + 0.5;
        checkNear(helper, "nearest copy from a far camera",
                SnowFlakes.nearestCopyOffset(39.9, SnowFlakes.wrap(farCamera, BOX_SIDE), BOX_SIDE), before);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void flakeCountFollowsTheFogsExtinction(GameTestHelper helper) {
        double extinction = 3.912 / 80.0;
        double share = 0.12;
        double size = 0.08;
        double asked = SnowFlakes.askedCount(extinction, share, size, BOX_SIDE);
        double covered = asked / (BOX_SIDE * BOX_SIDE * BOX_SIDE) * SnowFlakes.meanArea(size);
        helper.assertTrue(Math.abs(covered - share * extinction) < 1.0e-12, "the flakes cover " + covered
                + " per block instead of " + share * extinction);
        double expectedArea = 0.25 * Math.PI * size * size * (1.4 * 1.4 * 1.4 - 0.6 * 0.6 * 0.6) / (3.0 * 0.8);
        checkNear(helper, "mean area", SnowFlakes.meanArea(size), expectedArea);
        checkNear(helper, "drawn count below the capacity", SnowFlakes.drawnCount(extinction, share, size, BOX_SIDE),
                asked);
        checkNear(helper, "drawn count of a dense fog", SnowFlakes.drawnCount(1.0, 1.0, size, BOX_SIDE),
                SnowFlakes.CAPACITY);
        checkNear(helper, "count without fog", SnowFlakes.drawnCount(0.0, share, size, BOX_SIDE), 0.0);
        checkNear(helper, "count of a NaN extinction", SnowFlakes.drawnCount(Double.NaN, share, size, BOX_SIDE), 0.0);
        checkNear(helper, "count of size 0", SnowFlakes.drawnCount(extinction, share, 0.0, BOX_SIDE), 0.0);
        checkNear(helper, "first flake at a count of 1024", SnowFlakes.countWeight(0, 1024.0), 1.0);
        checkNear(helper, "last flake at a count of 1024", SnowFlakes.countWeight(1023, 1024.0), 1.0 / 1024.0);
        checkNear(helper, "flake past the count", SnowFlakes.countWeight(2000, 1024.0), 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void streaksAndShelterFadeAsTheyShould(GameTestHelper helper) {
        checkNear(helper, "a flake at rest", SnowFlakes.streakShare(1.0, 0.0), 1.0);
        checkNear(helper, "a streak of pi / 2 pixels", SnowFlakes.streakShare(1.0, 0.5 * Math.PI), 0.5);
        double weightSum = 0.0;
        int flakes = 100000;
        for (int flake = 0; flake < flakes; flake++) {
            double open = SnowFlakes.shelterWeight(flake, 1.0);
            double covered = SnowFlakes.shelterWeight(flake, 0.0);
            if (open != 1.0 || covered != 0.0) {
                helper.fail("flake " + flake + " shows " + open + " in the open and " + covered + " under cover");
                return;
            }
            weightSum += SnowFlakes.shelterWeight(flake, 0.5);
        }
        double meanWeight = weightSum / flakes;
        helper.assertTrue(Math.abs(meanWeight - 0.5) < 0.01, "flakes show " + meanWeight
                + " at the share 0.5 on average instead of 0.5");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void flakeValuesSpreadEvenlyWithinTheirRanges(GameTestHelper helper) {
        int flakes = SnowFlakes.CAPACITY;
        double unitSum = 0.0;
        double fallSum = 0.0;
        int turningLeft = 0;
        for (int flake = 0; flake < flakes; flake++) {
            double unit = SnowFlakes.unit(flake, SnowFlakes.SALT_POSITION_X);
            double fall = SnowFlakes.fallShare(flake);
            double size = SnowFlakes.sizeShare(flake);
            double rate = Math.abs(SnowFlakes.flutterRate(flake));
            if (!(unit >= 0.0 && unit < 1.0) || !(fall >= SnowFlakes.SLOWEST_FALL_SHARE
                    && fall < SnowFlakes.FASTEST_FALL_SHARE) || !(size >= SnowFlakes.SMALLEST_SIZE_SHARE
                    && size < SnowFlakes.LARGEST_SIZE_SHARE) || !(rate > 2.0 * Math.PI / SnowFlakes.LONGEST_FLUTTER_PERIOD
                    - TOLERANCE && rate <= 2.0 * Math.PI / SnowFlakes.SHORTEST_FLUTTER_PERIOD + TOLERANCE)) {
                helper.fail("flake " + flake + " has the unit " + unit + ", fall share " + fall + ", size share "
                        + size + " and flutter rate " + rate + ", outside their ranges");
                return;
            }
            unitSum += unit;
            fallSum += fall;
            if (SnowFlakes.flutterRate(flake) < 0.0) {
                turningLeft++;
            }
        }
        double meanUnit = unitSum / flakes;
        double meanFall = fallSum / flakes;
        double leftShare = (double) turningLeft / flakes;
        helper.assertTrue(Math.abs(meanUnit - 0.5) < 0.005, "the mean unit is " + meanUnit + " instead of 0.5");
        helper.assertTrue(Math.abs(meanFall - 1.0) < 0.005, "the mean fall share is " + meanFall + " instead of 1");
        helper.assertTrue(Math.abs(leftShare - 0.5) < 0.01, leftShare + " of the flakes turn one way instead of 0.5");
        helper.succeed();
    }

    private static void checkNear(GameTestHelper helper, String what, double actual, double expected) {
        helper.assertTrue(Math.abs(actual - expected) <= TOLERANCE * Math.max(1.0, Math.abs(expected)), what + " is "
                + actual + " instead of " + expected);
    }
}
