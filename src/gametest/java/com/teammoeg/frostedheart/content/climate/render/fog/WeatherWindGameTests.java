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

/**
 * Checks the displacement of the wind: that it stands still and that a paused or bad step leaves it, that a steady wind
 * moves at its speed along its direction, that a long step is clamped, that the speed approaches its target over the
 * time constant, that bad targets and directions are guarded, and that the warm advance allocates nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class WeatherWindGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double TOLERANCE = 1.0e-12;
    private static final double RISE_TOLERANCE = 1.0e-9;
    private static final double STEP_SECONDS = 0.1;
    private static final double STEADY_SPEED = 5.0;
    private static final int STEADY_STEPS = 10;
    private static final double STEADY_DISPLACEMENT = STEADY_SPEED * STEP_SECONDS * STEADY_STEPS;
    private static final double BAD_STEP_SPEED = 9.0;
    private static final double BAD_STEP_DIRECTION = 1.0;
    private static final double CLAMPED_STEP_SECONDS = 10.0;
    private static final double CLAMPED_TARGET_SPEED = 4.0;
    private static final double CLAMPED_DISPLACEMENT = CLAMPED_TARGET_SPEED * WeatherWind.MAXIMUM_STEP_SECONDS;
    private static final double RISE_TARGET_SPEED = 10.0;
    private static final int RISE_STEPS = 80;
    private static final double ZERO_DIRECTION = 0.0;
    private static final double GUARDED_DIRECTION = 1.0;
    private static final double TWO_STEP_DISPLACEMENT = 2.0 * STEADY_SPEED * STEP_SECONDS;
    private static final int ALLOCATION_WARMUP_CALLS = 100;
    private static final int ALLOCATION_ADVANCE_CALLS = 1000;
    private static final int ALLOCATION_BEAN_WARMUPS = 20;
    private static final double WARM_TARGET_SPEED = 3.0;
    private static final double WARM_DIRECTION_STEP = 0.017453292519943295;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void freshWindStandsStill(GameTestHelper helper) {
        WeatherWind wind = new WeatherWind();
        assertClose(helper, "the speed of a fresh wind", wind.speed(), 0.0);
        assertClose(helper, "the direction of a fresh wind", wind.direction(), 0.0);
        assertClose(helper, "the x displacement of a fresh wind", wind.displacementX(), 0.0);
        assertClose(helper, "the z displacement of a fresh wind", wind.displacementZ(), 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void steadyWindMovesAtItsSpeed(GameTestHelper helper) {
        WeatherWind eastward = new WeatherWind();
        advanceTimes(eastward, STEADY_STEPS, STEP_SECONDS, STEADY_SPEED, 0.0);
        assertClose(helper, "the speed after " + STEADY_STEPS + " steps toward " + STEADY_SPEED, eastward.speed(),
                STEADY_SPEED);
        assertClose(helper, "the x displacement after " + STEADY_STEPS + " steps of " + STEP_SECONDS + " s toward "
                + STEADY_SPEED + " along 0", eastward.displacementX(), STEADY_DISPLACEMENT);
        assertClose(helper, "the z displacement after " + STEADY_STEPS + " steps toward " + STEADY_SPEED
                + " along 0", eastward.displacementZ(), 0.0);
        WeatherWind northward = new WeatherWind();
        advanceTimes(northward, STEADY_STEPS, STEP_SECONDS, STEADY_SPEED, Math.PI / 2.0);
        assertClose(helper, "the x displacement after " + STEADY_STEPS + " steps of " + STEP_SECONDS + " s toward "
                + STEADY_SPEED + " along pi/2", northward.displacementX(), 0.0);
        assertClose(helper, "the z displacement after " + STEADY_STEPS + " steps toward " + STEADY_SPEED
                + " along pi/2", northward.displacementZ(), STEADY_DISPLACEMENT);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void pausesAndBadStepsDoNotMoveIt(GameTestHelper helper) {
        WeatherWind wind = new WeatherWind();
        advanceTimes(wind, STEADY_STEPS, STEP_SECONDS, STEADY_SPEED, 0.0);
        double speed = wind.speed();
        double direction = wind.direction();
        double displacementX = wind.displacementX();
        double displacementZ = wind.displacementZ();
        wind.advance(0.0, BAD_STEP_SPEED, BAD_STEP_DIRECTION);
        assertUnmoved(helper, "a step of 0 s", wind, speed, direction, displacementX, displacementZ);
        wind.advance(-1.0, BAD_STEP_SPEED, BAD_STEP_DIRECTION);
        assertUnmoved(helper, "a step of -1 s", wind, speed, direction, displacementX, displacementZ);
        wind.advance(Double.NaN, BAD_STEP_SPEED, BAD_STEP_DIRECTION);
        assertUnmoved(helper, "a step of NaN s", wind, speed, direction, displacementX, displacementZ);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void longStepsAreClamped(GameTestHelper helper) {
        WeatherWind wind = new WeatherWind();
        wind.advance(CLAMPED_STEP_SECONDS, CLAMPED_TARGET_SPEED, 0.0);
        assertClose(helper, "the x displacement of a step of " + CLAMPED_STEP_SECONDS + " s toward "
                + CLAMPED_TARGET_SPEED + " along 0, clamped to " + WeatherWind.MAXIMUM_STEP_SECONDS + " s",
                wind.displacementX(), CLAMPED_DISPLACEMENT);
        assertClose(helper, "the z displacement of a step of " + CLAMPED_STEP_SECONDS + " s toward "
                + CLAMPED_TARGET_SPEED + " along 0", wind.displacementZ(), 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void speedFollowsItsTargetSmoothly(GameTestHelper helper) {
        WeatherWind wind = new WeatherWind();
        wind.advance(STEP_SECONDS, STEADY_SPEED, ZERO_DIRECTION);
        advanceTimes(wind, RISE_STEPS, STEP_SECONDS, RISE_TARGET_SPEED, ZERO_DIRECTION);
        double expected = RISE_TARGET_SPEED - STEADY_SPEED * Math.exp(-RISE_STEPS * STEP_SECONDS
                / WeatherWind.SPEED_TIME_CONSTANT_SECONDS);
        assertClose(helper, "the speed after " + RISE_STEPS + " steps of " + STEP_SECONDS + " s from " + STEADY_SPEED
                + " toward " + RISE_TARGET_SPEED + ", which is " + RISE_TARGET_SPEED + " - " + STEADY_SPEED
                + " * exp(-1)", wind.speed(), expected, RISE_TOLERANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void badTargetsAndDirections(GameTestHelper helper) {
        WeatherWind wind = new WeatherWind();
        wind.advance(STEP_SECONDS, STEADY_SPEED, GUARDED_DIRECTION);
        wind.advance(STEP_SECONDS, STEADY_SPEED, Double.NaN);
        assertClose(helper, "the direction after a NaN direction, which keeps " + GUARDED_DIRECTION, wind.direction(),
                GUARDED_DIRECTION);
        assertClose(helper, "the x displacement of two steps of " + STEP_SECONDS + " s toward " + STEADY_SPEED
                + " along " + GUARDED_DIRECTION, wind.displacementX(),
                TWO_STEP_DISPLACEMENT * Math.cos(GUARDED_DIRECTION));
        assertClose(helper, "the z displacement of two steps of " + STEP_SECONDS + " s toward " + STEADY_SPEED
                + " along " + GUARDED_DIRECTION, wind.displacementZ(),
                TWO_STEP_DISPLACEMENT * Math.sin(GUARDED_DIRECTION));
        WeatherWind negativeTarget = new WeatherWind();
        negativeTarget.advance(STEP_SECONDS, -3.0, 0.0);
        assertClose(helper, "the speed at the target -3", negativeTarget.speed(), 0.0);
        assertClose(helper, "the x displacement at the target -3", negativeTarget.displacementX(), 0.0);
        assertClose(helper, "the z displacement at the target -3", negativeTarget.displacementZ(), 0.0);
        WeatherWind nanTarget = new WeatherWind();
        nanTarget.advance(STEP_SECONDS, Double.NaN, 0.0);
        assertClose(helper, "the speed at a NaN target", nanTarget.speed(), 0.0);
        assertClose(helper, "the x displacement at a NaN target", nanTarget.displacementX(), 0.0);
        assertClose(helper, "the z displacement at a NaN target", nanTarget.displacementZ(), 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmAdvanceAllocatesNothing(GameTestHelper helper) {
        WeatherWind wind = new WeatherWind();
        warmAdvances(wind, ALLOCATION_WARMUP_CALLS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm wind "
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
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm wind is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        double sum = warmAdvances(wind, ALLOCATION_ADVANCE_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm wind allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_ADVANCE_CALLS + " warm wind advances allocate "
                + allocated + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * Calls advance the given number of times with the same arguments.
     */
    private static void advanceTimes(WeatherWind wind, int stepCount, double seconds, double targetSpeed,
            double direction) {
        for (int step = 0; step < stepCount; step++) {
            wind.advance(seconds, targetSpeed, direction);
        }
    }

    /**
     * The work of the allocation test: the wind advances with changing arguments and reads its getters, without an
     * assertion or a message of its own, so that it can be measured.
     */
    private static double warmAdvances(WeatherWind wind, int advanceCalls) {
        double sum = 0.0;
        for (int call = 0; call < advanceCalls; call++) {
            double targetSpeed = WARM_TARGET_SPEED + call % 7;
            double direction = (call % 360) * WARM_DIRECTION_STEP;
            wind.advance(STEP_SECONDS, targetSpeed, direction);
            sum += wind.speed() + wind.direction() + wind.displacementX() + wind.displacementZ();
        }
        return sum;
    }

    /**
     * Checks that the speed, the direction and both displacements of a wind are still the given ones.
     */
    private static void assertUnmoved(GameTestHelper helper, String step, WeatherWind wind, double speed,
            double direction, double displacementX, double displacementZ) {
        assertClose(helper, "the speed after " + step, wind.speed(), speed);
        assertClose(helper, "the direction after " + step, wind.direction(), direction);
        assertClose(helper, "the x displacement after " + step, wind.displacementX(), displacementX);
        assertClose(helper, "the z displacement after " + step, wind.displacementZ(), displacementZ);
    }

    /**
     * Checks a value against its expected value within TOLERANCE, naming the case.
     */
    private static void assertClose(GameTestHelper helper, String step, double actual, double expected) {
        assertClose(helper, step, actual, expected, TOLERANCE);
    }

    /**
     * Checks a value against its expected value within the given tolerance, naming the case.
     */
    private static void assertClose(GameTestHelper helper, String step, double actual, double expected,
            double tolerance) {
        double difference = Math.abs(actual - expected);
        helper.assertTrue(difference <= tolerance, "The value of " + step + " is " + actual + " instead of " + expected
                + ", a difference of " + difference + " above " + tolerance);
    }
}
