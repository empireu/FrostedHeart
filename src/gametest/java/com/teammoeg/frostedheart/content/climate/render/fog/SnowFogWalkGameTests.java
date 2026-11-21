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
import java.util.Random;

/**
 * Checks the walk of the fog along a ray through the snow field: the analytic depth of an open field and of a
 * sheltered one, the heightmap crossfade near the edge of the region, the fade of a fill and the shown box, the share
 * table of each decay length, the guarded zero, level, vertical and edge rays, the backward walk under a porch, the
 * whole walk against a fine step reference on random layouts, the share of a cell against the same reference, the walk
 * to the sky with the tail past it, and that the warm walks allocate nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFogWalkGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FIELD_SIZE = 128;
    private static final int FIELD_MASK = FIELD_SIZE - 1;
    private static final int FIELD_SHIFT = 7;
    private static final int WHOLE_BOX_RADIUS = FIELD_SIZE / 2;
    private static final int WHOLE_BOX_SPAN = WHOLE_BOX_RADIUS - 1;
    private static final int EYE_BLOCK_X = 0;
    private static final int EYE_BLOCK_Y = 64;
    private static final int EYE_BLOCK_Z = 0;
    private static final double EYE_FRACTION_X = 0.5;
    private static final double EYE_FRACTION_Y = 0.5;
    private static final double EYE_FRACTION_Z = 0.5;
    private static final double EYE_EXTINCTION = 0.1;
    private static final double FIELD_RADIUS = 32.0;
    /**
     * The half width of the region of a field radius of 32, which is the radius minus the region margin.
     */
    private static final double HALF_WIDTH = 30.0;
    private static final double REGION_MARGIN = 2.0;
    private static final double CROSSFADE_WIDTH = 8.0;
    private static final int FAR_WALK_COLUMNS = 32;
    private static final double DEFAULT_DECAY_LENGTH = 1.5;
    private static final double HEIGHT_SCALE = 128.0;
    private static final double DEPTH_TOLERANCE = 1.0e-9;
    private static final double SHARE_TOLERANCE = 1.0e-12;
    private static final double STOP_TOLERANCE = 1.0e-12;
    private static final double TABLE_TOLERANCE = 1.0e-6;
    private static final double STEPS_PER_BLOCK = 1024.0;
    private static final long RANDOM_SEED = 20261119L;
    private static final int LAYOUT_COUNT = 4;
    private static final int RAYS_PER_LAYOUT = 25;
    private static final int LEVEL_RAY = 0;
    private static final int HORIZONTAL_ZERO_RAY = 1;
    private static final int SHADED_RAY = 2;
    private static final int SPECIAL_RAY_COUNT = 3;
    private static final double SHORTEST_RAY = 1.0;
    private static final double LONGEST_RAY = 150.0;
    private static final double SHADED_LEAST_HORIZONTAL = 36.0;
    private static final double SHADED_LONGEST_HORIZONTAL = 100.0;
    private static final double SHADED_HIGHEST_VERTICAL = 40.0;
    private static final int LAYOUT_COLUMN_REACH = 80;
    private static final int LAYOUT_HEIGHT_LOWEST = 55;
    private static final int LAYOUT_HEIGHT_HIGHEST = 75;
    private static final int SHELTERED_COLUMN_HEIGHT = 1000;
    private static final int PORCH_FIRST_X = 60;
    private static final int PORCH_LAST_X = 69;
    private static final int PORCH_FIRST_Z = -2;
    private static final int PORCH_LAST_Z = 2;
    private static final int HIGH_PORCH_HEIGHT = 70;
    private static final int LOW_PORCH_HEIGHT = 57;
    private static final int CELL_REFERENCE_X = 40;
    private static final int CELL_REFERENCE_Y = 10;
    private static final int CELL_REFERENCE_Z = 40;
    private static final int ALLOCATION_WARMUP_CALLS = 20000;
    private static final int ALLOCATION_DEPTH_CALLS = 1000;
    private static final int ALLOCATION_TABLE_CALLS = 10;
    private static final double ALLOCATION_TABLE_STEP = 0.1;
    private static final int ALLOCATION_BEAN_WARMUPS = 20;
    private static final int REPORTED_MISMATCHES = 5;
    private static final double TWO_PI = 2.0 * Math.PI;
    /**
     * The byte values of the random layouts, and the fade, the radius, the decay length, the height scale and the eye
     * block of each of them.
     */
    private static final int[] LAYOUT_BYTE_VALUES = {0, 8, 16, 40, 100, 192, 254, 255};
    private static final double[] LAYOUT_FADES = {1.0, 0.6, 1.0, 0.3};
    private static final double[] LAYOUT_RADII = {32.0, 40.0, 12.0, 25.5};
    private static final double[] LAYOUT_DECAY_LENGTHS = {1.5, 4.0, 0.5, 4.3};
    private static final double[] LAYOUT_HEIGHT_SCALES = {0.0, 128.0, 0.0, 64.0};
    private static final int[][] LAYOUT_EYE_BLOCKS = {{0, 64, 0}, {-1000, 70, 333}, {5, 64, -7}, {1023, 61, 1024}};
    private static final int STRUCTURE_TEXEL_COUNT = FogStructure.SIZE * FogStructure.SIZE * FogStructure.SIZE
            * FogStructure.CHANNELS;
    private static final int STRUCTURE_SHAPE_BYTE = 153;
    private static final double STRUCTURE_MEAN_SHAPE = 0.176;
    private static final double STRUCTURE_SHARE = 0.75;
    private static final double UNIFORM_STRUCTURE_COVERAGE = 0.5;
    private static final double WALK_STRUCTURE_COVERAGE = 0.35;
    private static final double STRUCTURE_EROSION = 0.5;
    private static final double STRUCTURE_RANGE = 96.0;
    private static final double UNIFORM_STRUCTURE_RANGE = 1000.0;
    private static final double SHORT_STRUCTURE_RANGE = 40.0;
    private static final double UNIFORM_STRUCTURE_DENSITY = 1.75;
    private static final double STRUCTURE_SIZE = 96.0;
    private static final double SMALL_STRUCTURE_SIZE = 48.0;
    private static final double STRUCTURE_STRETCH = 2.0;
    private static final double STRUCTURE_DISPLACEMENT_X = 13.7;
    private static final double STRUCTURE_DISPLACEMENT_Z = -4.2;
    private static final double STRUCTURE_DIRECTION = 0.8;
    private static final double STRUCTURE_TRANSMITTANCE_TOLERANCE = 0.03;
    private static final double SMALL_STRUCTURE_TRANSMITTANCE_TOLERANCE = 0.06;
    private static final double STRUCTURE_RELATIVE_DEPTH_TOLERANCE = 0.05;
    private static final double RELATIVE_DEPTH_LIMIT = 3.0;
    /**
     * The fog of the sky tests at the eye, which is the extinction the blizzard of the visibility 80 has, and the
     * height scale, the radius and the directions they walk.
     */
    private static final double SKY_EYE_EXTINCTION = 0.05;
    private static final double SKY_HEIGHT_SCALE = 128.0;
    private static final double SKY_RADIUS = 12.0;
    private static final double SKY_WALK_DISTANCE = 96.0;
    private static final double SKY_SHORT_WALK_DISTANCE = 40.0;
    private static final double SKY_DIRECTION_X = 0.8;
    private static final double SKY_DIRECTION_Y = 0.6;
    private static final double UNNORMALIZED_DIRECTION_SCALE = 10.0;
    /**
     * The optical depth of the sky from the eye to infinity along the direction (0.8, 0.6, 0.0), which the extinction
     * 0.05 and the height scale 128 give, whatever the walk distance is.
     */
    private static final double SKY_TOTAL_DEPTH = 10.666666666666668;
    private static final double SKY_RELATIVE_TOLERANCE = 1.0e-6;
    private static final double SKY_STRUCTURE_RELATIVE_TOLERANCE = 1.0e-9;
    /**
     * A height of every column far above the eye, so that the columns cover every point of a ray that rises.
     */
    private static final int SKY_COVER_HEIGHT = 1000;
    private static final double SKY_COVERED_DIRECTION_X = 1.0;
    private static final double SKY_COVERED_DIRECTION_Y = 0.05;
    private static final double SKY_COVERED_WALK_DISTANCE = 40.0;
    /**
     * The directions of the sky allocation test: one that rises, one that is level, one that descends, one that rises
     * slowly and one that the guard rejects.
     */
    private static final double[][] SKY_WARM_DIRECTIONS = {{0.8, 0.6, 0.0}, {1.0, 0.0, 0.0}, {0.3, -0.2, 0.9},
            {0.0, 0.05, 1.0}, {0.0, 0.0, 0.0}};

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void openFieldGivesTheAnalyticDepth(GameTestHelper helper) {
        byte[] distances = byteField(0);
        short[] heights = unknownHeights();
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 1.0, 10.0, 0.0, 0.0);
        helper.assertTrue(walk.lastCellSteps() == 11, "The ray (10.0, 0.0, 0.0) of the open field walks "
                + walk.lastCellSteps() + " cells instead of 11, the half cell at each end and the 9 between");
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 10.0, 100.0, 0.0, 0.0);
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 1.3, 3.0, 4.0, 12.0);
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 5.0, 0.0, -50.0, 0.0);
        walk.opticalDepth(100.0, 0.0, 0.0);
        assertStops(helper, "the ray (100.0, 0.0, 0.0) of the open field", walk, 0.3, 1.0);
        assertColumnSteps(helper, "the ray (100.0, 0.0, 0.0) of the open field", walk, 0);
        walk.setFog(EYE_EXTINCTION, HEIGHT_SCALE);
        double[][] rays = {{10.0, 0.0, 0.0}, {100.0, 0.0, 0.0}, {3.0, 4.0, 12.0}, {0.0, -50.0, 0.0}};
        for (int rayIndex = 0; rayIndex < rays.length; rayIndex++) {
            double offsetX = rays[rayIndex][0];
            double offsetY = rays[rayIndex][1];
            double offsetZ = rays[rayIndex][2];
            double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
            double expected = HeightFog.opticalDepth(EYE_EXTINCTION, length, offsetY, HEIGHT_SCALE);
            assertDepth(helper, "the open field at the height scale " + HEIGHT_SCALE + ", whose parts telescope",
                    walk, expected, offsetX, offsetY, offsetZ);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void shelteredCellsAddNothing(GameTestHelper helper) {
        byte[] distances = byteField(SnowField.SHELTERED);
        short[] heights = filledHeights(SHELTERED_COLUMN_HEIGHT);
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the sheltered field at the length 10", walk, 0.0, 10.0, 0.0, 0.0);
        assertDepth(helper, "the sheltered field at the length 100", walk, 3.85, 100.0, 0.0, 0.0);
        assertStops(helper, "the ray (100.0, 0.0, 0.0) of the sheltered field", walk, 0.3, 0.685);
        assertColumnSteps(helper, "the ray (100.0, 0.0, 0.0) of the sheltered field", walk, FAR_WALK_COLUMNS);
        assertDepth(helper, "the vertical sheltered field", walk, 0.0, 0.0, -50.0, 0.0);
        assertStops(helper, "the ray (0.0, -50.0, 0.0) of the sheltered field", walk, 0.6, 0.6);
        assertColumnSteps(helper, "the ray (0.0, -50.0, 0.0) of the sheltered field", walk, 1);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void crossfadeBlendsTowardTheHeightmap(GameTestHelper helper) {
        byte[] distances = byteField(0);
        short[] heights = filledHeights(SHELTERED_COLUMN_HEIGHT);
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the crossfade of the ray along x", walk, 2.6, 40.0, 0.0, 0.0);
        assertStops(helper, "the ray (40.0, 0.0, 0.0) of the crossfade", walk, 0.75, 0.75);
        assertDepth(helper, "the crossfade of the ray along z", walk, 2.6, 0.0, 0.0, 40.0);
        for (int x = 35; x <= 45; x++) {
            for (int z = -2; z <= 2; z++) {
                setColumnHeight(heights, x, z, 0);
            }
        }
        assertDepth(helper, "the open column of the scene point below the crossfade", walk, 3.6, 40.0, 0.0, 0.0);
        for (int x = 35; x <= 45; x++) {
            for (int z = -2; z <= 2; z++) {
                setColumnHeight(heights, x, z, SHELTERED_COLUMN_HEIGHT);
            }
        }
        walk.setEye(EYE_BLOCK_X, EYE_BLOCK_Y, EYE_BLOCK_Z, 0.75, EYE_FRACTION_Y, EYE_FRACTION_Z);
        assertDepth(helper, "the crossfade of the eye at the fraction 0.75", walk, 2.59921875, 40.0, 0.0, 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fadeAndShownBoxFallBackToTheHeightmap(GameTestHelper helper) {
        byte[] distances = byteField(0);
        short[] heights = filledHeights(SHELTERED_COLUMN_HEIGHT);
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the fade 1 of the fully weighted cells", walk, 2.0, 20.0, 0.0, 0.0);
        walk.setFieldFade(0.5);
        assertDepth(helper, "the fade 0.5 of the fully weighted cells", walk, 1.0, 20.0, 0.0, 0.0);
        walk.setFieldFade(0.0);
        assertDepth(helper, "the fade 0, which leaves every cell to the heightmap", walk, 0.0, 20.0, 0.0, 0.0);
        walk.setFieldFade(1.0);
        walk.setShownBox(false, EYE_BLOCK_X, EYE_BLOCK_Y, EYE_BLOCK_Z, EYE_BLOCK_X, EYE_BLOCK_Y, EYE_BLOCK_Z);
        assertDepth(helper, "no shown box, which leaves every cell to the heightmap", walk, 0.0, 20.0, 0.0, 0.0);
        walk.setShownBox(true, EYE_BLOCK_X - WHOLE_BOX_RADIUS, EYE_BLOCK_Y - WHOLE_BOX_RADIUS,
                EYE_BLOCK_Z - WHOLE_BOX_RADIUS, EYE_BLOCK_X + 9, EYE_BLOCK_Y + WHOLE_BOX_SPAN,
                EYE_BLOCK_Z + WHOLE_BOX_SPAN);
        assertDepth(helper, "the shown box to the world x 9", walk, 0.95, 20.0, 0.0, 0.0);
        setWholeShownBox(walk);
        setByte(distances, 5, 64, 0, SnowField.UNREAD);
        assertDepth(helper, "the unread cell at the world (5, 64, 0) of the whole shown box", walk, 1.9, 20.0, 0.0,
                0.0);
        Arrays.fill(heights, (short) 0);
        assertDepth(helper, "the unread cell of the whole shown box with every column open above", walk, 2.0, 20.0,
                0.0, 0.0);
        setByte(distances, 5, 64, 0, SnowField.SHELTERED);
        assertDepth(helper, "the sheltered cell at the world (5, 64, 0) with every column open above", walk, 1.9,
                20.0, 0.0, 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void shareTableFollowsTheDecayLength(GameTestHelper helper) {
        float[] table = new float[SnowFogWalk.TABLE_SIZE];
        SnowFogWalk.buildTable(DEFAULT_DECAY_LENGTH, table);
        assertTableEntry(helper, "the decay length 1.5", table, 0, 1.0);
        assertTableEntry(helper, "the decay length 1.5", table, 12, Math.exp(-1.0));
        assertTableEntry(helper, "the decay length 1.5", table, SnowField.CAP, Math.exp(-16.0));
        for (int entry = SnowField.CAP + 1; entry < SnowField.SHELTERED; entry++) {
            double value = table[entry];
            helper.assertTrue(value == 0.0, "The decay length 1.5 has the table entry " + entry + " at " + value
                    + " instead of 0.0");
        }
        double shelteredValue = table[SnowField.SHELTERED];
        helper.assertTrue(shelteredValue == 0.0, "The decay length 1.5 has the table entry " + SnowField.SHELTERED
                + " at " + shelteredValue + " instead of 0.0");
        double unreadValue = table[SnowField.UNREAD];
        helper.assertTrue(unreadValue < 0.0, "The decay length 1.5 has the table entry " + SnowField.UNREAD + " at "
                + unreadValue + " instead of a negative flag");
        SnowFogWalk.buildTable(4.0, table);
        assertTableEntry(helper, "the decay length 4.0", table, 32, Math.exp(-1.0));
        SnowFogWalk.buildTable(10.0, table);
        assertTableEntry(helper, "the decay length 10, clamped to 4.3", table, SnowField.CAP,
                Math.exp(-SnowField.CAP / 34.4));
        double cappedValue = table[SnowField.CAP];
        helper.assertTrue(cappedValue < 1.0 / 255.0, "The decay length 10 has the table entry " + SnowField.CAP
                + " at " + cappedValue + " instead of one below " + (1.0 / 255.0) + ", the cut of the cap");
        double[] clampedLengths = {0.0, -1.0, Double.NaN};
        for (int lengthIndex = 0; lengthIndex < clampedLengths.length; lengthIndex++) {
            SnowFogWalk.buildTable(clampedLengths[lengthIndex], table);
            assertTableEntry(helper, "the decay length " + clampedLengths[lengthIndex] + ", clamped to 0.25", table, 2,
                    Math.exp(-1.0));
        }
        byte[] distances = byteField(12);
        short[] heights = unknownHeights();
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepthWithin(helper, "the every byte 12 field of the table of 1.5", walk, TABLE_TOLERANCE,
                Math.exp(-1.0), 10.0, 0.0, 0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void edgeRaysAreGuarded(GameTestHelper helper) {
        byte[] distances = byteField(0);
        short[] heights = unknownHeights();
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the ray to the face of the region at x", walk, 2.5, 25.0, 0.0, 0.0);
        assertDepth(helper, "the ray to the face of the region at y", walk, 2.5, 0.0, 25.0, 0.0);
        assertDepth(helper, "the ray to the face of the region at -y", walk, 2.5, 0.0, -25.0, 0.0);
        assertDepth(helper, "the ray to the face of the region at -z", walk, 2.5, 0.0, 0.0, -25.0);
        assertZeroDepth(helper, "the offset of length 0", walk, 0.0, 0.0, 0.0);
        assertZeroDepth(helper, "the offset (NaN, 1, 1)", walk, Double.NaN, 1.0, 1.0);
        assertZeroDepth(helper, "the offset (Infinity, 0, 0)", walk, Double.POSITIVE_INFINITY, 0.0, 0.0);
        assertZeroDepth(helper, "the offset (1, NaN, 1)", walk, 1.0, Double.NaN, 1.0);
        walk.setEye(EYE_BLOCK_X, EYE_BLOCK_Y, EYE_BLOCK_Z, 0.0, 0.0, 0.0);
        assertDepth(helper, "the eye at the block corner, the ray along x", walk, 1.0, 10.0, 0.0, 0.0);
        assertDepth(helper, "the eye at the block corner, the diagonal ray through cell corners", walk,
                0.1 * Math.sqrt(300.0), 10.0, 10.0, 10.0);
        walk.setFog(0.0, 0.0);
        assertZeroDepth(helper, "the ray along x without fog", walk, 10.0, 0.0, 0.0);
        assertZeroDepth(helper, "the diagonal ray without fog", walk, 10.0, 10.0, 10.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void backwardWalkUnderAPorch(GameTestHelper helper) {
        byte[] distances = byteField(0);
        short[] heights = filledHeights(0);
        setPorchHeights(heights, HIGH_PORCH_HEIGHT);
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the level ray under the porch of the height " + HIGH_PORCH_HEIGHT, walk, 5.95, 65.0, 0.0,
                0.0);
        assertStops(helper, "the ray (65.0, 0.0, 0.0) under the porch", walk, HALF_WIDTH / 65.0, 59.5 / 65.0);
        assertColumnSteps(helper, "the ray (65.0, 0.0, 0.0) under the porch", walk, 7);
        assertDepth(helper, "the ray above the porch of the height " + HIGH_PORCH_HEIGHT, walk,
                0.1 * Math.sqrt(4325.0), 65.0, 10.0, 0.0);
        assertStops(helper, "the ray (65.0, 10.0, 0.0) above the porch", walk, HALF_WIDTH / 65.0, 1.0);
        assertColumnSteps(helper, "the ray (65.0, 10.0, 0.0) above the porch", walk, 0);
        assertDepth(helper, "the descending ray under the porch of the height " + HIGH_PORCH_HEIGHT, walk,
                0.1 * Math.sqrt(4241.0) * 59.5 / 65.0, 65.0, -4.0, 0.0);
        assertStops(helper, "the ray (65.0, -4.0, 0.0) under the porch", walk, HALF_WIDTH / 65.0, 59.5 / 65.0);
        assertColumnSteps(helper, "the ray (65.0, -4.0, 0.0) under the porch", walk, 7);
        setPorchHeights(heights, LOW_PORCH_HEIGHT);
        assertDepth(helper, "the descending ray under the porch of the height " + LOW_PORCH_HEIGHT, walk,
                0.1 * Math.sqrt(4289.0) * 0.9375, 65.0, -8.0, 0.0);
        assertStops(helper, "the ray (65.0, -8.0, 0.0) under the low porch", walk, HALF_WIDTH / 65.0, 0.9375);
        assertColumnSteps(helper, "the ray (65.0, -8.0, 0.0) under the low porch", walk, 5);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void structureOffKeepsEveryDepth(GameTestHelper helper) {
        SnowFogWalk walk = setUpWalk(byteField(0), unknownHeights());
        walk.setStructure(null);
        assertDepthsWithoutStructure(helper, "the null structure", walk);
        FogStructure sharelessStructure = setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0), 0.0,
                UNIFORM_STRUCTURE_COVERAGE, 0.0, UNIFORM_STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE);
        walk.setStructure(sharelessStructure);
        assertDepthsWithoutStructure(helper, "the structure of the share 0", walk);
        FogStructure untexturedStructure = new FogStructure();
        untexturedStructure.setDensityWithMeans(STRUCTURE_SHARE, UNIFORM_STRUCTURE_COVERAGE, 0.0,
                UNIFORM_STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE, STRUCTURE_MEAN_SHAPE);
        walk.setStructure(untexturedStructure);
        assertDepthsWithoutStructure(helper, "the structure without a texture", walk);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void uniformStructureScalesTheDepth(GameTestHelper helper) {
        FogStructure structure = setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0), STRUCTURE_SHARE,
                UNIFORM_STRUCTURE_COVERAGE, 0.0, UNIFORM_STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE);
        SnowFogWalk walk = setUpWalk(byteField(0), unknownHeights());
        walk.setStructure(structure);
        assertDepth(helper, "the open field of the uniform structure, which multiplies the mean fog by "
                + UNIFORM_STRUCTURE_DENSITY, walk, UNIFORM_STRUCTURE_DENSITY, 10.0, 0.0, 0.0);
        assertCoarseSteps(helper, "the ray (10.0, 0.0, 0.0) of the uniform structure, which ends inside the region",
                walk, 0);
        assertDepth(helper, "the open field of the uniform structure", walk, UNIFORM_STRUCTURE_DENSITY * 10.0, 100.0,
                0.0, 0.0);
        assertCoarseSteps(helper, "the ray (100.0, 0.0, 0.0) of the uniform structure, which walks the grid from "
                + "30.5 to 100.5", walk, 19);
        assertDepth(helper, "the open field of the uniform structure", walk, UNIFORM_STRUCTURE_DENSITY * 1.3, 3.0, 4.0,
                12.0);
        assertDepth(helper, "the open field of the uniform structure", walk, UNIFORM_STRUCTURE_DENSITY * 5.0, 0.0,
                -50.0, 0.0);
        assertCoarseSteps(helper, "the ray (0.0, -50.0, 0.0) of the uniform structure, which walks the cells -8 to "
                + "-13 in y", walk, 6);
        walk.setFog(EYE_EXTINCTION, HEIGHT_SCALE);
        double[][] rays = {{10.0, 0.0, 0.0}, {100.0, 0.0, 0.0}, {3.0, 4.0, 12.0}, {0.0, -50.0, 0.0},
                {60.0, 30.0, 0.0}};
        for (int rayIndex = 0; rayIndex < rays.length; rayIndex++) {
            double offsetX = rays[rayIndex][0];
            double offsetY = rays[rayIndex][1];
            double offsetZ = rays[rayIndex][2];
            double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
            double expected = UNIFORM_STRUCTURE_DENSITY
                    * HeightFog.opticalDepth(EYE_EXTINCTION, length, offsetY, HEIGHT_SCALE);
            assertDepth(helper, "the open field of the uniform structure at the height scale " + HEIGHT_SCALE
                    + ", whose parts telescope", walk, expected, offsetX, offsetY, offsetZ);
        }
        SnowFogWalk shelteredWalk = setUpWalk(byteField(SnowField.SHELTERED), filledHeights(SHELTERED_COLUMN_HEIGHT));
        shelteredWalk.setStructure(structure);
        assertDepth(helper, "the sheltered field of the uniform structure, which multiplies its 3.85 by "
                + UNIFORM_STRUCTURE_DENSITY, shelteredWalk, UNIFORM_STRUCTURE_DENSITY * 3.85, 100.0, 0.0, 0.0);
        assertCoarseSteps(helper, "the ray (100.0, 0.0, 0.0) of the sheltered field, which walks the grid from 30.5 to "
                + "69", shelteredWalk, 11);
        assertDepth(helper, "the sheltered field of the uniform structure", shelteredWalk, 0.0, 10.0, 0.0, 0.0);
        // The region from 0 to 30 blocks adds 1.75 times its 3.0. The coarse parts from 30 to 31.5, 31.5 to 35.5,
        // 35.5 to 39.5 and 39.5 to 40 blocks take the range weights 0.925, 0.65, 0.25 and 0.025 at their midpoints and
        // add 0.2540625, 0.595, 0.475 and 0.0509375. The mean fog from 40 to 100 blocks adds 6.0.
        FogStructure shortRangeStructure = setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0),
                STRUCTURE_SHARE, UNIFORM_STRUCTURE_COVERAGE, 0.0, SHORT_STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE);
        SnowFogWalk shortRangeWalk = setUpWalk(byteField(0), unknownHeights());
        shortRangeWalk.setStructure(shortRangeStructure);
        assertDepth(helper, "the open field of the uniform structure of the range " + SHORT_STRUCTURE_RANGE,
                shortRangeWalk, 12.625, 100.0, 0.0, 0.0);
        assertCoarseSteps(helper, "the ray (100.0, 0.0, 0.0) of the range " + SHORT_STRUCTURE_RANGE
                + ", which walks the grid from 30.5 to 40.5", shortRangeWalk, 4);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void walkMatchesAFineStepReference(GameTestHelper helper) {
        ReferenceLayout[] layouts = buildLayouts();
        double largestError = 0.0;
        String largestErrorCase = "none";
        int rayCount = 0;
        for (int layoutIndex = 0; layoutIndex < layouts.length; layoutIndex++) {
            ReferenceLayout layout = layouts[layoutIndex];
            for (int rayIndex = 0; rayIndex < layout.rays.length; rayIndex++) {
                double[] ray = layout.rays[rayIndex];
                ReferenceDepth reference = referenceDepth(layout, ray[0], ray[1], ray[2]);
                double depth = layout.walk.opticalDepth(ray[0], ray[1], ray[2]);
                double length = Math.sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2]);
                double allowed = (3.0 * length + 40.0) * reference.largestStepDepth;
                double error = Math.abs(depth - reference.depth);
                if (error > largestError) {
                    largestError = error;
                    largestErrorCase = "the layout " + layoutIndex + " and the ray " + describeRay(ray);
                }
                helper.assertTrue(error <= allowed, "The layout " + layoutIndex + " gives the ray " + describeRay(ray)
                        + " of length " + length + " the depth " + depth + " where the fine step reference gives "
                        + reference.depth + ", an error of " + error + " above the " + allowed
                        + " allowed for the largest step depth " + reference.largestStepDepth);
                rayCount++;
            }
        }
        LOGGER.info("The walk matches the fine step reference on " + rayCount + " rays, the largest error "
                + largestError + " at " + largestErrorCase);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void structuredWalkMatchesAFineStepReference(GameTestHelper helper) {
        byte[] texels = new byte[STRUCTURE_TEXEL_COUNT];
        FogStructure.build(FogStructure.SEED, texels);
        ReferenceLayout[] layouts = buildLayouts();
        assertStructuredWalk(helper, layouts, texels, STRUCTURE_SIZE, STRUCTURE_TRANSMITTANCE_TOLERANCE, true);
        assertStructuredWalk(helper, layouts, texels, SMALL_STRUCTURE_SIZE, SMALL_STRUCTURE_TRANSMITTANCE_TOLERANCE,
                false);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void cellShareMatchesTheReference(GameTestHelper helper) {
        ReferenceLayout[] layouts = buildLayouts();
        int comparedCount = 0;
        int mismatchCount = 0;
        int reportedCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int layoutIndex = 0; layoutIndex < 2; layoutIndex++) {
            ReferenceLayout layout = layouts[layoutIndex];
            for (int cellX = -CELL_REFERENCE_X; cellX <= CELL_REFERENCE_X; cellX++) {
                for (int cellY = -CELL_REFERENCE_Y; cellY <= CELL_REFERENCE_Y; cellY++) {
                    for (int cellZ = -CELL_REFERENCE_Z; cellZ <= CELL_REFERENCE_Z; cellZ++) {
                        double expectedShare = referenceCellShare(layout, cellX, cellY, cellZ);
                        double share = layout.walk.cellShare(cellX, cellY, cellZ);
                        double expectedHeightShare = referenceHeightShare(layout, cellX, cellY, cellZ);
                        double heightShare = layout.walk.heightShare(cellX, cellY, cellZ);
                        comparedCount++;
                        boolean shareDiffers = Math.abs(share - expectedShare) > SHARE_TOLERANCE;
                        boolean heightDiffers = heightShare != expectedHeightShare;
                        if (!shareDiffers && !heightDiffers) {
                            continue;
                        }
                        mismatchCount++;
                        if (reportedCount < REPORTED_MISMATCHES) {
                            reportedCount++;
                            mismatches.append(" the cell (").append(cellX).append(", ").append(cellY).append(", ")
                                    .append(cellZ).append(") of the layout ").append(layoutIndex).append(" shares ")
                                    .append(share).append("/").append(expectedShare).append(" and heights ")
                                    .append(heightShare).append("/").append(expectedHeightShare);
                        }
                    }
                }
            }
        }
        helper.assertTrue(mismatchCount == 0, "In the layouts 0 and 1, " + mismatchCount + " of the " + comparedCount
                + " cells share another value than the reference or another height share, actual/expected:"
                + mismatches);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmWalkAllocatesNothing(GameTestHelper helper) {
        ReferenceLayout layout = buildLayouts()[0];
        float[] table = new float[SnowFogWalk.TABLE_SIZE];
        SnowFogWalk.buildTable(layout.decayLength, table);
        byte[] texels = new byte[STRUCTURE_TEXEL_COUNT];
        FogStructure.build(FogStructure.SEED, texels);
        FogStructure structure = new FogStructure();
        structure.setTexture(texels);
        structure.setDensity(STRUCTURE_SHARE, WALK_STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE);
        structure.follow(layout.eyeBlockX + layout.eyeFractionX, layout.eyeBlockY + layout.eyeFractionY,
                layout.eyeBlockZ + layout.eyeFractionZ, STRUCTURE_DISPLACEMENT_X, STRUCTURE_DISPLACEMENT_Z,
                STRUCTURE_DIRECTION, STRUCTURE_SIZE, STRUCTURE_STRETCH);
        warmOperations(layout, table, structure, ALLOCATION_WARMUP_CALLS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm walk "
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
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm walk is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        double sum = warmOperations(layout, table, structure, ALLOCATION_DEPTH_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm snow fog walk allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_DEPTH_CALLS + " warm optical depths of the structured "
                + "walk, the " + ALLOCATION_TABLE_CALLS + " warm tables and the setters allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkGivesTheAnalyticDepthOfAnOpenField(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        assertSkyDepth(helper, "the direction (0.8, 0.6, 0.0) of the walk " + SKY_WALK_DISTANCE, walk, SKY_DIRECTION_X,
                SKY_DIRECTION_Y, 0.0, SKY_WALK_DISTANCE, SKY_TOTAL_DEPTH);
        assertSkyDepth(helper, "the unnormalized direction (8.0, 6.0, 0.0) of the walk " + SKY_WALK_DISTANCE, walk,
                SKY_DIRECTION_X * UNNORMALIZED_DIRECTION_SCALE, SKY_DIRECTION_Y * UNNORMALIZED_DIRECTION_SCALE, 0.0,
                SKY_WALK_DISTANCE, SKY_TOTAL_DEPTH);
        assertSkyDepth(helper, "the direction (0.8, 0.6, 0.0) of the walk " + SKY_SHORT_WALK_DISTANCE, walk,
                SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, SKY_SHORT_WALK_DISTANCE, SKY_TOTAL_DEPTH);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkStaysAtTheCapBelowTheHorizon(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        assertMaximumSkyDepth(helper, "the level direction (1.0, 0.0, 0.0) of the walk " + SKY_WALK_DISTANCE, walk,
                1.0, 0.0, 0.0, SKY_WALK_DISTANCE);
        assertMaximumSkyDepth(helper, "the descending direction (1.0, -0.2, 0.0) of the walk " + SKY_WALK_DISTANCE,
                walk, 1.0, -0.2, 0.0, SKY_WALK_DISTANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkGuardsBadDirectionsAndDistances(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        assertZeroSkyDepth(helper, "the zero direction of the walk " + SKY_WALK_DISTANCE, walk, 0.0, 0.0, 0.0,
                SKY_WALK_DISTANCE);
        assertZeroLengthLastValues(helper, "the zero direction", walk);
        assertZeroSkyDepth(helper, "the direction (0.8, NaN, 0.0) of the walk " + SKY_WALK_DISTANCE, walk,
                SKY_DIRECTION_X, Double.NaN, 0.0, SKY_WALK_DISTANCE);
        assertZeroSkyDepth(helper, "the direction (Infinity, 0.6, 0.0) of the walk " + SKY_WALK_DISTANCE, walk,
                Double.POSITIVE_INFINITY, SKY_DIRECTION_Y, 0.0, SKY_WALK_DISTANCE);
        assertZeroSkyDepth(helper, "the direction (0.8, 0.6, 0.0) of the walk distance 0", walk, SKY_DIRECTION_X,
                SKY_DIRECTION_Y, 0.0, 0.0);
        assertZeroSkyDepth(helper, "the direction (0.8, 0.6, 0.0) of the walk distance -1", walk, SKY_DIRECTION_X,
                SKY_DIRECTION_Y, 0.0, -1.0);
        assertZeroSkyDepth(helper, "the direction (0.8, 0.6, 0.0) of the walk distance NaN", walk, SKY_DIRECTION_X,
                SKY_DIRECTION_Y, 0.0, Double.NaN);
        walk.setFog(0.0, SKY_HEIGHT_SCALE);
        assertZeroSkyDepth(helper, "the direction (0.8, 0.6, 0.0) of the walk " + SKY_WALK_DISTANCE + " without "
                + "extinction", walk, SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, SKY_WALK_DISTANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkStopsTheTailUnderCover(GameTestHelper helper) {
        SnowFogWalk walk = setUpWalk(byteField(0), filledHeights(SKY_COVER_HEIGHT));
        walk.setFog(SKY_EYE_EXTINCTION, SKY_HEIGHT_SCALE);
        walk.setRadius(SKY_RADIUS);
        double directionLength = Math.sqrt(SKY_COVERED_DIRECTION_X * SKY_COVERED_DIRECTION_X
                + SKY_COVERED_DIRECTION_Y * SKY_COVERED_DIRECTION_Y);
        double unitX = SKY_COVERED_DIRECTION_X / directionLength;
        double unitY = SKY_COVERED_DIRECTION_Y / directionLength;
        double skyDepth = walk.skyOpticalDepth(SKY_COVERED_DIRECTION_X, SKY_COVERED_DIRECTION_Y, 0.0,
                SKY_COVERED_WALK_DISTANCE);
        double farStop = walk.lastFarStop();
        double walked = walk.opticalDepth(unitX * SKY_COVERED_WALK_DISTANCE, unitY * SKY_COVERED_WALK_DISTANCE, 0.0);
        helper.assertTrue(farStop < 1.0, "The walk of the covered direction (1.0, 0.05, 0.0) stops its far part at "
                + farStop + " instead of below 1, although the columns of the height " + SKY_COVER_HEIGHT + " cover "
                + "every point it reaches");
        helper.assertTrue(skyDepth == walked, "The sky depth of the covered direction (1.0, 0.05, 0.0) of the walk "
                + SKY_COVERED_WALK_DISTANCE + " is " + skyDepth + " instead of the " + walked + " of the walk alone, "
                + "since no tail may follow a covered end");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkReportsTheInnerWalk(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        walk.skyOpticalDepth(SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, SKY_WALK_DISTANCE);
        int cellSteps = walk.lastCellSteps();
        int columnSteps = walk.lastColumnSteps();
        int coarseSteps = walk.lastCoarseSteps();
        double regionEnd = walk.lastRegionEnd();
        double farStop = walk.lastFarStop();
        walk.opticalDepth(SKY_DIRECTION_X * SKY_WALK_DISTANCE, SKY_DIRECTION_Y * SKY_WALK_DISTANCE, 0.0);
        String step = "the sky walk of (0.8, 0.6, 0.0) over " + SKY_WALK_DISTANCE + " blocks";
        String walkedStep = "the optical depth of (76.8, 57.6, 0.0)";
        helper.assertTrue(cellSteps == walk.lastCellSteps(), "The " + step + " leaves " + cellSteps
                + " cell steps instead of the " + walk.lastCellSteps() + " of " + walkedStep);
        helper.assertTrue(columnSteps == walk.lastColumnSteps(), "The " + step + " leaves " + columnSteps
                + " column steps instead of the " + walk.lastColumnSteps() + " of " + walkedStep);
        helper.assertTrue(coarseSteps == walk.lastCoarseSteps(), "The " + step + " leaves " + coarseSteps
                + " coarse steps instead of the " + walk.lastCoarseSteps() + " of " + walkedStep);
        helper.assertTrue(Math.abs(regionEnd - walk.lastRegionEnd()) <= STOP_TOLERANCE, "The " + step + " leaves its "
                + "region end at " + regionEnd + " instead of the " + walk.lastRegionEnd() + " of " + walkedStep);
        helper.assertTrue(Math.abs(farStop - walk.lastFarStop()) <= STOP_TOLERANCE, "The " + step + " leaves its far "
                + "stop at " + farStop + " instead of the " + walk.lastFarStop() + " of " + walkedStep);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkAddsTheTailPastTheStructureRange(GameTestHelper helper) {
        FogStructure structure = setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0), STRUCTURE_SHARE,
                UNIFORM_STRUCTURE_COVERAGE, 0.0, UNIFORM_STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE);
        SnowFogWalk walk = setUpSkyWalk();
        walk.setStructure(structure);
        double directionLength = Math.sqrt(SKY_DIRECTION_X * SKY_DIRECTION_X + SKY_DIRECTION_Y * SKY_DIRECTION_Y);
        double unitX = SKY_DIRECTION_X / directionLength;
        double unitY = SKY_DIRECTION_Y / directionLength;
        double walked = walk.opticalDepth(unitX * UNIFORM_STRUCTURE_RANGE, unitY * UNIFORM_STRUCTURE_RANGE, 0.0);
        double tail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, UNIFORM_STRUCTURE_RANGE, unitY,
                SKY_HEIGHT_SCALE);
        double skyDepth = walk.skyOpticalDepth(SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, UNIFORM_STRUCTURE_RANGE);
        double expected = walked + tail;
        double relativeDifference = Math.abs(skyDepth - expected) / Math.abs(expected);
        helper.assertTrue(relativeDifference <= SKY_STRUCTURE_RELATIVE_TOLERANCE, "The sky depth of the direction "
                + "(0.8, 0.6, 0.0) over the structure range " + UNIFORM_STRUCTURE_RANGE + " is " + skyDepth
                + " instead of the " + walked + " of the walk plus the " + tail + " of the tail at the density 1, a "
                + "relative difference of " + relativeDifference + " above " + SKY_STRUCTURE_RELATIVE_TOLERANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmSkyWalkAllocatesNothing(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        walk.setStructure(setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0), STRUCTURE_SHARE,
                UNIFORM_STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE));
        warmSkyOpticalDepth(walk, ALLOCATION_WARMUP_CALLS);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm sky "
                    + "walk is not measured");
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
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm sky walk is not "
                    + "measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        double sum = warmSkyOpticalDepth(walk, ALLOCATION_DEPTH_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm sky snow fog walk allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_DEPTH_CALLS + " warm sky optical depths allocate "
                + allocated + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * The work of the allocation test: the optical depths of the rays of the first layout with the structure on, the
     * tables into the same array and the setters, without an assertion or a message of its own, so that it can be
     * measured.
     */
    private static double warmOperations(ReferenceLayout layout, float[] table, FogStructure structure,
            int depthCalls) {
        SnowFogWalk walk = layout.walk;
        double sum = 0.0;
        walk.setField(layout.distances, FIELD_SIZE);
        walk.setShownBox(layout.hasShownBox, layout.shownMinX, layout.shownMinY, layout.shownMinZ, layout.shownMaxX,
                layout.shownMaxY, layout.shownMaxZ);
        walk.setHeights(layout.heights);
        walk.setTable(table);
        walk.setEye(layout.eyeBlockX, layout.eyeBlockY, layout.eyeBlockZ, layout.eyeFractionX, layout.eyeFractionY,
                layout.eyeFractionZ);
        walk.setFog(layout.eyeExtinction, layout.heightScale);
        walk.setRadius(layout.radius);
        walk.setFieldFade(layout.fieldFade);
        layout.structure = structure;
        walk.setStructure(structure);
        for (int call = 0; call < depthCalls; call++) {
            double[] ray = layout.rays[call % layout.rays.length];
            sum += walk.opticalDepth(ray[0], ray[1], ray[2]);
        }
        for (int call = 0; call < ALLOCATION_TABLE_CALLS; call++) {
            SnowFogWalk.buildTable(layout.decayLength + call * ALLOCATION_TABLE_STEP, table);
        }
        return sum;
    }

    /**
     * Builds the four layouts of the reference tests, one after the other from the same random sequence.
     */
    private static ReferenceLayout[] buildLayouts() {
        Random random = new Random(RANDOM_SEED);
        ReferenceLayout[] layouts = new ReferenceLayout[LAYOUT_COUNT];
        for (int layoutIndex = 0; layoutIndex < LAYOUT_COUNT; layoutIndex++) {
            layouts[layoutIndex] = buildLayout(layoutIndex, random);
        }
        return layouts;
    }

    /**
     * Builds one layout: random bytes, random heights over the columns from -80 to 80, a shown box that is the whole
     * cube or a random box around the eye, the settings of its index and random eye fractions, and then its rays.
     */
    private static ReferenceLayout buildLayout(int layoutIndex, Random random) {
        int eyeBlockX = LAYOUT_EYE_BLOCKS[layoutIndex][0];
        int eyeBlockY = LAYOUT_EYE_BLOCKS[layoutIndex][1];
        int eyeBlockZ = LAYOUT_EYE_BLOCKS[layoutIndex][2];
        double eyeFractionX = random.nextDouble();
        double eyeFractionY = random.nextDouble();
        double eyeFractionZ = random.nextDouble();
        byte[] distances = new byte[FIELD_SIZE * FIELD_SIZE * FIELD_SIZE];
        for (int slot = 0; slot < distances.length; slot++) {
            distances[slot] = (byte) LAYOUT_BYTE_VALUES[random.nextInt(LAYOUT_BYTE_VALUES.length)];
        }
        short[] heights = unknownHeights();
        for (int x = -LAYOUT_COLUMN_REACH; x <= LAYOUT_COLUMN_REACH; x++) {
            for (int z = -LAYOUT_COLUMN_REACH; z <= LAYOUT_COLUMN_REACH; z++) {
                setColumnHeight(heights, eyeBlockX + x, eyeBlockZ + z,
                        LAYOUT_HEIGHT_LOWEST + random.nextInt(LAYOUT_HEIGHT_HIGHEST - LAYOUT_HEIGHT_LOWEST + 1));
            }
        }
        boolean wholeBox = layoutIndex % 2 == 0;
        int shownMinX;
        int shownMinY;
        int shownMinZ;
        int shownMaxX;
        int shownMaxY;
        int shownMaxZ;
        if (wholeBox) {
            shownMinX = eyeBlockX - WHOLE_BOX_RADIUS;
            shownMinY = eyeBlockY - WHOLE_BOX_RADIUS;
            shownMinZ = eyeBlockZ - WHOLE_BOX_RADIUS;
            shownMaxX = eyeBlockX + WHOLE_BOX_SPAN;
            shownMaxY = eyeBlockY + WHOLE_BOX_SPAN;
            shownMaxZ = eyeBlockZ + WHOLE_BOX_SPAN;
        } else {
            shownMinX = eyeBlockX - 1 - random.nextInt(WHOLE_BOX_RADIUS);
            shownMinY = eyeBlockY - 1 - random.nextInt(WHOLE_BOX_RADIUS);
            shownMinZ = eyeBlockZ - 1 - random.nextInt(WHOLE_BOX_RADIUS);
            shownMaxX = eyeBlockX + random.nextInt(WHOLE_BOX_SPAN + 1);
            shownMaxY = eyeBlockY + random.nextInt(WHOLE_BOX_SPAN + 1);
            shownMaxZ = eyeBlockZ + random.nextInt(WHOLE_BOX_SPAN + 1);
        }
        ReferenceLayout layout = new ReferenceLayout(distances, heights, true, shownMinX, shownMinY, shownMinZ,
                shownMaxX, shownMaxY, shownMaxZ, LAYOUT_FADES[layoutIndex], LAYOUT_RADII[layoutIndex],
                LAYOUT_DECAY_LENGTHS[layoutIndex], LAYOUT_HEIGHT_SCALES[layoutIndex], EYE_EXTINCTION, eyeBlockX,
                eyeBlockY, eyeBlockZ, eyeFractionX, eyeFractionY, eyeFractionZ);
        layout.rays = buildRays(random, layout);
        return layout;
    }

    /**
     * The rays of a layout: a level one, one without a horizontal offset, one whose scene point lies under a column of
     * the height 75 more than 35 blocks away, and random ones of a length from 1 to 150 blocks.
     */
    private static double[][] buildRays(Random random, ReferenceLayout layout) {
        double[][] rays = new double[RAYS_PER_LAYOUT][];
        double levelAngle = random.nextDouble() * TWO_PI;
        double levelLength = SHORTEST_RAY + random.nextDouble() * (LONGEST_RAY - SHORTEST_RAY);
        rays[LEVEL_RAY] = new double[] {Math.cos(levelAngle) * levelLength, 0.0, Math.sin(levelAngle) * levelLength};
        double verticalLength = SHORTEST_RAY + random.nextDouble() * (LONGEST_RAY - SHORTEST_RAY);
        if (random.nextBoolean()) {
            verticalLength = -verticalLength;
        }
        rays[HORIZONTAL_ZERO_RAY] = new double[] {0.0, verticalLength, 0.0};
        double shadedAngle = random.nextDouble() * TWO_PI;
        double shadedLength = SHADED_LEAST_HORIZONTAL
                + random.nextDouble() * (SHADED_LONGEST_HORIZONTAL - SHADED_LEAST_HORIZONTAL);
        double shadedVertical = (random.nextDouble() * 2.0 - 1.0) * SHADED_HIGHEST_VERTICAL;
        rays[SHADED_RAY] = new double[] {Math.cos(shadedAngle) * shadedLength, shadedVertical,
                Math.sin(shadedAngle) * shadedLength};
        setColumnHeight(layout.heights,
                layout.eyeBlockX + referenceColumn(layout.eyeFractionX + rays[SHADED_RAY][0]),
                layout.eyeBlockZ + referenceColumn(layout.eyeFractionZ + rays[SHADED_RAY][2]), LAYOUT_HEIGHT_HIGHEST);
        int rayIndex = SPECIAL_RAY_COUNT;
        while (rayIndex < RAYS_PER_LAYOUT) {
            double componentX = random.nextDouble() * 2.0 - 1.0;
            double componentY = random.nextDouble() * 2.0 - 1.0;
            double componentZ = random.nextDouble() * 2.0 - 1.0;
            double directionLength = Math.sqrt(componentX * componentX + componentY * componentY
                    + componentZ * componentZ);
            if (directionLength < 1.0e-3) {
                continue;
            }
            double length = SHORTEST_RAY + random.nextDouble() * (LONGEST_RAY - SHORTEST_RAY);
            rays[rayIndex] = new double[] {componentX / directionLength * length,
                    componentY / directionLength * length, componentZ / directionLength * length};
            rayIndex++;
        }
        return rays;
    }

    /**
     * The optical depth of a ray by midpoint samples at a step of 1/1024 of a block, with the share of every sample
     * computed pointwise from the field and the far rule, and with the largest depth of one step of the same sampling.
     */
    private static ReferenceDepth referenceDepth(ReferenceLayout layout, double offsetX, double offsetY,
            double offsetZ) {
        return referenceDepth(layout, offsetX, offsetY, offsetZ, null);
    }

    /**
     * The optical depth of a ray by midpoint samples at a step of 1/1024 of a block, with the share of every sample
     * computed pointwise from the field and the far rule, and with the largest depth of one step of the same sampling.
     * With a structure, the extinction of each step is multiplied by the density at the step's midpoint.
     */
    private static ReferenceDepth referenceDepth(ReferenceLayout layout, double offsetX, double offsetY,
            double offsetZ, FogStructure structure) {
        double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        int stepCount = Math.max(1, (int) Math.ceil(length * STEPS_PER_BLOCK));
        double[] stepShares = new double[stepCount];
        int columnX = referenceColumn(layout.eyeFractionX + offsetX);
        int columnZ = referenceColumn(layout.eyeFractionZ + offsetZ);
        int columnCount = 1;
        boolean coveredThrough = referenceCovered(layout, 1.0, columnX, columnZ, offsetY);
        for (int stepIndex = stepCount - 1; stepIndex >= 0; stepIndex--) {
            double parameter = (stepIndex + 0.5) / stepCount;
            double pointX = layout.eyeFractionX + parameter * offsetX;
            double pointY = layout.eyeFractionY + parameter * offsetY;
            double pointZ = layout.eyeFractionZ + parameter * offsetZ;
            int pointColumnX = referenceColumn(pointX);
            int pointColumnZ = referenceColumn(pointZ);
            if (pointColumnX != columnX || pointColumnZ != columnZ) {
                columnCount++;
                columnX = pointColumnX;
                columnZ = pointColumnZ;
            }
            coveredThrough = coveredThrough
                    && referenceCovered(layout, parameter, pointColumnX, pointColumnZ, offsetY);
            boolean inRegion = Math.abs(parameter * offsetX) <= layout.halfWidth
                    && Math.abs(parameter * offsetY) <= layout.halfWidth
                    && Math.abs(parameter * offsetZ) <= layout.halfWidth;
            if (inRegion) {
                stepShares[stepIndex] = referenceCellShare(layout, (int) Math.floor(pointX),
                        (int) Math.floor(pointY), (int) Math.floor(pointZ));
            } else {
                stepShares[stepIndex] = coveredThrough && columnCount <= FAR_WALK_COLUMNS ? 0.0 : 1.0;
            }
        }
        double depth = 0.0;
        double largestStepDepth = 0.0;
        double previousDepth = 0.0;
        for (int stepIndex = 0; stepIndex < stepCount; stepIndex++) {
            double parameter = (stepIndex + 1.0) / stepCount;
            double segmentDepth = HeightFog.opticalDepth(layout.eyeExtinction, parameter * length,
                    parameter * offsetY, layout.heightScale);
            double stepDepth = Math.max(0.0, segmentDepth - previousDepth);
            double densityFactor = 1.0;
            if (structure != null) {
                double midpoint = (stepIndex + 0.5) / stepCount;
                densityFactor = structure.densityAt(midpoint * offsetX, midpoint * offsetY, midpoint * offsetZ);
            }
            depth += stepShares[stepIndex] * stepDepth * densityFactor;
            largestStepDepth = Math.max(largestStepDepth, stepDepth);
            previousDepth = segmentDepth;
        }
        return new ReferenceDepth(depth, largestStepDepth);
    }

    /**
     * Whether a point of a ray is covered in the given column, which is the far rule: it compares the height of the
     * point over the eye's block against the height of the column.
     */
    private static boolean referenceCovered(ReferenceLayout layout, double parameter, int columnX, int columnZ,
            double offsetY) {
        int height = layout.heights[FarHeights.texelOf(layout.eyeBlockX + columnX, layout.eyeBlockZ + columnZ)];
        return layout.eyeFractionY + parameter * offsetY < height - layout.eyeBlockY;
    }

    /**
     * The share of a cell from the field, the shown box, the heights, the table, the crossfade and the fade, computed
     * without SnowFogWalk.
     */
    private static double referenceCellShare(ReferenceLayout layout, int cellX, int cellY, int cellZ) {
        double heightShare = referenceHeightShare(layout, cellX, cellY, cellZ);
        if (!layout.hasShownBox) {
            return heightShare;
        }
        int worldX = layout.eyeBlockX + cellX;
        int worldY = layout.eyeBlockY + cellY;
        int worldZ = layout.eyeBlockZ + cellZ;
        if (worldX < layout.shownMinX || worldX > layout.shownMaxX || worldY < layout.shownMinY
                || worldY > layout.shownMaxY || worldZ < layout.shownMinZ || worldZ > layout.shownMaxZ) {
            return heightShare;
        }
        double fieldShare = layout.table[layout.distances[slotOf(worldX, worldY, worldZ)] & 0xFF];
        if (fieldShare < 0.0) {
            return heightShare;
        }
        double chebyshev = Math.max(Math.abs(cellX + 0.5 - layout.eyeFractionX),
                Math.max(Math.abs(cellY + 0.5 - layout.eyeFractionY), Math.abs(cellZ + 0.5 - layout.eyeFractionZ)));
        double weight = Math.max(0.0, Math.min(1.0, (layout.halfWidth - chebyshev) / CROSSFADE_WIDTH))
                * layout.fieldFade;
        return heightShare + (fieldShare - heightShare) * weight;
    }

    /**
     * The height share of a cell: 1 at or above its column's height and 0 below it, which an unknown column is never
     * above.
     */
    private static double referenceHeightShare(ReferenceLayout layout, int cellX, int cellY, int cellZ) {
        int worldY = layout.eyeBlockY + cellY;
        int height = layout.heights[FarHeights.texelOf(layout.eyeBlockX + cellX, layout.eyeBlockZ + cellZ)];
        return worldY >= height ? 1.0 : 0.0;
    }

    /**
     * The cell number of a position relative to the eye's block, which its floor.
     */
    private static int referenceColumn(double position) {
        return (int) Math.floor(position);
    }

    /**
     * A walk of the common setup: the field size 128, the eye at the block (0, 64, 0) with the fraction (0.5, 0.5,
     * 0.5), the whole cube shown, the table of the decay length 1.5, the fog of the extinction 0.1 with the height
     * scale 0, the radius 32 and the field fade 1.
     */
    private static SnowFogWalk setUpWalk(byte[] distances, short[] heights) {
        SnowFogWalk walk = new SnowFogWalk();
        float[] table = new float[SnowFogWalk.TABLE_SIZE];
        SnowFogWalk.buildTable(DEFAULT_DECAY_LENGTH, table);
        walk.setField(distances, FIELD_SIZE);
        walk.setShownBox(true, EYE_BLOCK_X - WHOLE_BOX_RADIUS, EYE_BLOCK_Y - WHOLE_BOX_RADIUS,
                EYE_BLOCK_Z - WHOLE_BOX_RADIUS, EYE_BLOCK_X + WHOLE_BOX_SPAN, EYE_BLOCK_Y + WHOLE_BOX_SPAN,
                EYE_BLOCK_Z + WHOLE_BOX_SPAN);
        walk.setHeights(heights);
        walk.setTable(table);
        walk.setEye(EYE_BLOCK_X, EYE_BLOCK_Y, EYE_BLOCK_Z, EYE_FRACTION_X, EYE_FRACTION_Y, EYE_FRACTION_Z);
        walk.setFog(EYE_EXTINCTION, 0.0);
        walk.setRadius(FIELD_RADIUS);
        walk.setFieldFade(1.0);
        return walk;
    }

    /**
     * Shows the whole cube of the eye at the block (0, 64, 0).
     */
    private static void setWholeShownBox(SnowFogWalk walk) {
        walk.setShownBox(true, EYE_BLOCK_X - WHOLE_BOX_RADIUS, EYE_BLOCK_Y - WHOLE_BOX_RADIUS,
                EYE_BLOCK_Z - WHOLE_BOX_RADIUS, EYE_BLOCK_X + WHOLE_BOX_SPAN, EYE_BLOCK_Y + WHOLE_BOX_SPAN,
                EYE_BLOCK_Z + WHOLE_BOX_SPAN);
    }

    /**
     * Gives the columns of the porch their height and every other column the height 0.
     */
    private static void setPorchHeights(short[] heights, int porchHeight) {
        Arrays.fill(heights, (short) 0);
        for (int x = PORCH_FIRST_X; x <= PORCH_LAST_X; x++) {
            for (int z = PORCH_FIRST_Z; z <= PORCH_LAST_Z; z++) {
                setColumnHeight(heights, x, z, porchHeight);
            }
        }
    }

    /**
     * A field of the given byte everywhere.
     */
    private static byte[] byteField(int value) {
        byte[] distances = new byte[FIELD_SIZE * FIELD_SIZE * FIELD_SIZE];
        Arrays.fill(distances, (byte) value);
        return distances;
    }

    /**
     * Heights of FarHeights.UNKNOWN everywhere.
     */
    private static short[] unknownHeights() {
        short[] heights = new short[FarHeights.SIZE * FarHeights.SIZE];
        Arrays.fill(heights, FarHeights.UNKNOWN);
        return heights;
    }

    /**
     * Heights of the given value everywhere.
     */
    private static short[] filledHeights(int value) {
        short[] heights = new short[FarHeights.SIZE * FarHeights.SIZE];
        Arrays.fill(heights, (short) value);
        return heights;
    }

    /**
     * Sets the height of the column at the given world position.
     */
    private static void setColumnHeight(short[] heights, int x, int z, int height) {
        heights[FarHeights.texelOf(x, z)] = (short) height;
    }

    /**
     * Sets the byte of the world cell in the slot layout of SnowField.
     */
    private static void setByte(byte[] distances, int x, int y, int z, int value) {
        distances[slotOf(x, y, z)] = (byte) value;
    }

    /**
     * The slot of a world cell in the layout of SnowField, with x fastest, then y, then z.
     */
    private static int slotOf(int x, int y, int z) {
        return (x & FIELD_MASK) | ((y & FIELD_MASK) << FIELD_SHIFT) | ((z & FIELD_MASK) << (2 * FIELD_SHIFT));
    }

    /**
     * Checks the optical depth of a ray against its expected value within DEPTH_TOLERANCE.
     */
    private static void assertDepth(GameTestHelper helper, String step, SnowFogWalk walk, double expected,
            double offsetX, double offsetY, double offsetZ) {
        assertDepthWithin(helper, step, walk, DEPTH_TOLERANCE, expected, offsetX, offsetY, offsetZ);
    }

    /**
     * Checks the optical depth of a ray against its expected value within the given tolerance.
     */
    private static void assertDepthWithin(GameTestHelper helper, String step, SnowFogWalk walk, double tolerance,
            double expected, double offsetX, double offsetY, double offsetZ) {
        double depth = walk.opticalDepth(offsetX, offsetY, offsetZ);
        double difference = Math.abs(depth - expected);
        helper.assertTrue(difference <= tolerance, "In " + step + ", the ray " + describeRay(offsetX, offsetY,
                offsetZ) + " has the depth " + depth + " instead of " + expected + ", a difference of " + difference
                + " above " + tolerance);
    }

    /**
     * Checks that the optical depth of a ray is exactly 0, which the guards and a fog without extinction give.
     */
    private static void assertZeroDepth(GameTestHelper helper, String step, SnowFogWalk walk, double offsetX,
            double offsetY, double offsetZ) {
        double depth = walk.opticalDepth(offsetX, offsetY, offsetZ);
        helper.assertTrue(depth == 0.0, "In " + step + ", the ray " + describeRay(offsetX, offsetY, offsetZ)
                + " has the depth " + depth + " instead of exactly 0.0");
    }

    /**
     * Checks the region end and the far stop that the last walk recorded.
     */
    private static void assertStops(GameTestHelper helper, String step, SnowFogWalk walk, double expectedRegionEnd,
            double expectedFarStop) {
        double regionEnd = walk.lastRegionEnd();
        helper.assertTrue(Math.abs(regionEnd - expectedRegionEnd) <= STOP_TOLERANCE, "In " + step
                + ", the region ends at " + regionEnd + " instead of " + expectedRegionEnd);
        double farStop = walk.lastFarStop();
        helper.assertTrue(Math.abs(farStop - expectedFarStop) <= STOP_TOLERANCE, "In " + step
                + ", the far part stops at " + farStop + " instead of " + expectedFarStop);
    }

    /**
     * Checks the number of columns that the backward walk of the last ray visited.
     */
    private static void assertColumnSteps(GameTestHelper helper, String step, SnowFogWalk walk,
            int expectedColumnSteps) {
        int columnSteps = walk.lastColumnSteps();
        helper.assertTrue(columnSteps == expectedColumnSteps, "In " + step + ", the backward walk visits "
                + columnSteps + " columns instead of " + expectedColumnSteps);
    }

    /**
     * Checks one entry of a share table against its expected value within TABLE_TOLERANCE.
     */
    private static void assertTableEntry(GameTestHelper helper, String step, float[] table, int entry,
            double expected) {
        double value = table[entry];
        double difference = Math.abs(value - expected);
        helper.assertTrue(difference <= TABLE_TOLERANCE, "In " + step + ", the table entry " + entry + " is " + value
                + " instead of " + expected + ", a difference of " + difference + " above " + TABLE_TOLERANCE);
    }

    /**
     * Checks the structured walk of the layouts against the fine step reference at the given structure size, where
     * the transmittance of each ray stays within its tolerance and, when asked, the depth of each ray below
     * RELATIVE_DEPTH_LIMIT stays within STRUCTURE_RELATIVE_DEPTH_TOLERANCE of the reference.
     */
    private static void assertStructuredWalk(GameTestHelper helper, ReferenceLayout[] layouts, byte[] texels,
            double size, double transmittanceTolerance, boolean checkRelative) {
        double largestTransmittanceError = 0.0;
        String largestErrorCase = "none";
        double largestRelativeError = 0.0;
        String largestRelativeCase = "none";
        int rayCount = 0;
        for (int layoutIndex = 0; layoutIndex < layouts.length; layoutIndex++) {
            ReferenceLayout layout = layouts[layoutIndex];
            setLayoutStructure(layout, texels, size);
            for (int rayIndex = 0; rayIndex < layout.rays.length; rayIndex++) {
                double[] ray = layout.rays[rayIndex];
                ReferenceDepth reference = referenceDepth(layout, ray[0], ray[1], ray[2], layout.structure);
                double depth = layout.walk.opticalDepth(ray[0], ray[1], ray[2]);
                double transmittance = Math.exp(-depth);
                double referenceTransmittance = Math.exp(-reference.depth);
                double transmittanceError = Math.abs(transmittance - referenceTransmittance);
                if (transmittanceError > largestTransmittanceError) {
                    largestTransmittanceError = transmittanceError;
                    largestErrorCase = "the layout " + layoutIndex + " and the ray " + describeRay(ray);
                }
                helper.assertTrue(transmittanceError <= transmittanceTolerance, "At the structure size " + size
                        + ", the layout " + layoutIndex + " gives the ray " + describeRay(ray) + " the depth " + depth
                        + " where the fine step reference gives " + reference.depth + ", whose transmittances "
                        + transmittance + " and " + referenceTransmittance + " differ by " + transmittanceError
                        + " above " + transmittanceTolerance);
                if (checkRelative && reference.depth > 0.0 && reference.depth < RELATIVE_DEPTH_LIMIT) {
                    double relativeError = Math.abs(depth - reference.depth) / reference.depth;
                    if (relativeError > largestRelativeError) {
                        largestRelativeError = relativeError;
                        largestRelativeCase = "the layout " + layoutIndex + " and the ray " + describeRay(ray);
                    }
                    helper.assertTrue(relativeError <= STRUCTURE_RELATIVE_DEPTH_TOLERANCE, "At the structure size "
                            + size + ", the layout " + layoutIndex + " gives the ray " + describeRay(ray) + " the "
                            + "depth " + depth + " where the fine step reference gives " + reference.depth + ", a "
                            + "relative error of " + relativeError + " above "
                            + STRUCTURE_RELATIVE_DEPTH_TOLERANCE);
                }
                rayCount++;
            }
        }
        LOGGER.info("The structured walk at the size " + size + " matches the fine step reference on " + rayCount
                + " rays, the largest transmittance error " + largestTransmittanceError + " at " + largestErrorCase
                + " and the largest relative depth error " + largestRelativeError + " at " + largestRelativeCase);
    }

    /**
     * Gives a layout's walk a structure of the built texture that follows its eye, at the given size.
     */
    private static void setLayoutStructure(ReferenceLayout layout, byte[] texels, double size) {
        double eyeX = layout.eyeBlockX + layout.eyeFractionX;
        double eyeY = layout.eyeBlockY + layout.eyeFractionY;
        double eyeZ = layout.eyeBlockZ + layout.eyeFractionZ;
        FogStructure structure = new FogStructure();
        structure.setTexture(texels);
        structure.setDensity(STRUCTURE_SHARE, WALK_STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE);
        structure.follow(eyeX, eyeY, eyeZ, STRUCTURE_DISPLACEMENT_X, STRUCTURE_DISPLACEMENT_Z, STRUCTURE_DIRECTION,
                size, STRUCTURE_STRETCH);
        layout.structure = structure;
        layout.walk.setStructure(structure);
    }

    /**
     * A structure of the given texture and density with the means given, which has not followed an eye, so that its
     * pattern coordinate is 0 everywhere and its density is the same at every offset.
     */
    private static FogStructure setUpStructure(byte[] texels, double share, double coverage, double erosion,
            double range, double mean) {
        FogStructure structure = new FogStructure();
        structure.setTexture(texels);
        structure.setDensityWithMeans(share, coverage, erosion, range, mean, mean);
        return structure;
    }

    /**
     * A structure texture of the given shape byte and detail byte everywhere.
     */
    private static byte[] filledStructureTexels(int shapeByte, int detailByte) {
        byte[] texels = new byte[STRUCTURE_TEXEL_COUNT];
        for (int texel = 0; texel < STRUCTURE_TEXEL_COUNT; texel += FogStructure.CHANNELS) {
            texels[texel + FogStructure.SHAPE_CHANNEL] = (byte) shapeByte;
            texels[texel + FogStructure.DETAIL_CHANNEL] = (byte) detailByte;
        }
        return texels;
    }

    /**
     * Checks the depths of the open field and the coarse parts of each ray, which must stay the depths of the fog
     * without structures.
     */
    private static void assertDepthsWithoutStructure(GameTestHelper helper, String step, SnowFogWalk walk) {
        assertDepth(helper, "the open field of " + step, walk, 1.0, 10.0, 0.0, 0.0);
        assertCoarseSteps(helper, "the ray (10.0, 0.0, 0.0) of " + step, walk, 0);
        assertDepth(helper, "the open field of " + step, walk, 10.0, 100.0, 0.0, 0.0);
        assertCoarseSteps(helper, "the ray (100.0, 0.0, 0.0) of " + step, walk, 0);
        assertDepth(helper, "the open field of " + step, walk, 1.3, 3.0, 4.0, 12.0);
        assertCoarseSteps(helper, "the ray (3.0, 4.0, 12.0) of " + step, walk, 0);
        assertDepth(helper, "the open field of " + step, walk, 5.0, 0.0, -50.0, 0.0);
        assertCoarseSteps(helper, "the ray (0.0, -50.0, 0.0) of " + step, walk, 0);
    }

    /**
     * Checks the parts that the coarse walk of the last ray visited against the expected count.
     */
    private static void assertCoarseSteps(GameTestHelper helper, String step, SnowFogWalk walk, int expectedSteps) {
        int coarseSteps = walk.lastCoarseSteps();
        helper.assertTrue(coarseSteps == expectedSteps, "In " + step + ", the coarse walk visits " + coarseSteps
                + " parts instead of " + expectedSteps);
    }

    /**
     * A ray as the messages name it.
     */
    private static String describeRay(double[] ray) {
        return describeRay(ray[0], ray[1], ray[2]);
    }

    /**
     * A ray as the messages name it.
     */
    private static String describeRay(double offsetX, double offsetY, double offsetZ) {
        return "(" + offsetX + ", " + offsetY + ", " + offsetZ + ")";
    }

    /**
     * A walk of the sky tests: the open field of setUpWalk with the fog of the extinction 0.05 and the height scale
     * 128, the radius 12 and no structure.
     */
    private static SnowFogWalk setUpSkyWalk() {
        SnowFogWalk walk = setUpWalk(byteField(0), unknownHeights());
        walk.setFog(SKY_EYE_EXTINCTION, SKY_HEIGHT_SCALE);
        walk.setRadius(SKY_RADIUS);
        walk.setStructure(null);
        return walk;
    }

    /**
     * Checks the sky optical depth of a direction against its expected value within SKY_RELATIVE_TOLERANCE relative.
     */
    private static void assertSkyDepth(GameTestHelper helper, String step, SnowFogWalk walk, double directionX,
            double directionY, double directionZ, double walkDistance, double expected) {
        double depth = walk.skyOpticalDepth(directionX, directionY, directionZ, walkDistance);
        double relativeDifference = Math.abs(depth - expected) / Math.abs(expected);
        helper.assertTrue(relativeDifference <= SKY_RELATIVE_TOLERANCE, "The sky depth of " + step + " is " + depth
                + " instead of " + expected + ", a relative difference of " + relativeDifference + " above "
                + SKY_RELATIVE_TOLERANCE);
    }

    /**
     * Checks that the sky optical depth of a direction is exactly the maximum optical depth.
     */
    private static void assertMaximumSkyDepth(GameTestHelper helper, String step, SnowFogWalk walk, double directionX,
            double directionY, double directionZ, double walkDistance) {
        double depth = walk.skyOpticalDepth(directionX, directionY, directionZ, walkDistance);
        helper.assertTrue(depth == HeightFog.MAXIMUM_OPTICAL_DEPTH, "The sky depth of " + step + " is " + depth
                + " instead of exactly " + HeightFog.MAXIMUM_OPTICAL_DEPTH);
    }

    /**
     * Checks that the sky optical depth of a direction is exactly 0.
     */
    private static void assertZeroSkyDepth(GameTestHelper helper, String step, SnowFogWalk walk, double directionX,
            double directionY, double directionZ, double walkDistance) {
        double depth = walk.skyOpticalDepth(directionX, directionY, directionZ, walkDistance);
        helper.assertTrue(depth == 0.0, "The sky depth of " + step + " is " + depth + " instead of exactly 0.0");
    }

    /**
     * Checks that the last values of a walk equal those that a zero-length opticalDepth call leaves, which the guards
     * of the sky walk keep.
     */
    private static void assertZeroLengthLastValues(GameTestHelper helper, String step, SnowFogWalk walk) {
        int cellSteps = walk.lastCellSteps();
        int columnSteps = walk.lastColumnSteps();
        int coarseSteps = walk.lastCoarseSteps();
        double regionEnd = walk.lastRegionEnd();
        double farStop = walk.lastFarStop();
        walk.opticalDepth(0.0, 0.0, 0.0);
        helper.assertTrue(cellSteps == walk.lastCellSteps(), "The sky walk of " + step + " leaves " + cellSteps
                + " cell steps instead of the " + walk.lastCellSteps() + " of a zero-length optical depth");
        helper.assertTrue(columnSteps == walk.lastColumnSteps(), "The sky walk of " + step + " leaves " + columnSteps
                + " column steps instead of the " + walk.lastColumnSteps() + " of a zero-length optical depth");
        helper.assertTrue(coarseSteps == walk.lastCoarseSteps(), "The sky walk of " + step + " leaves " + coarseSteps
                + " coarse steps instead of the " + walk.lastCoarseSteps() + " of a zero-length optical depth");
        helper.assertTrue(Math.abs(regionEnd - walk.lastRegionEnd()) <= STOP_TOLERANCE, "The sky walk of " + step
                + " leaves its region end at " + regionEnd + " instead of the " + walk.lastRegionEnd()
                + " of a zero-length optical depth");
        helper.assertTrue(Math.abs(farStop - walk.lastFarStop()) <= STOP_TOLERANCE, "The sky walk of " + step
                + " leaves its far stop at " + farStop + " instead of the " + walk.lastFarStop()
                + " of a zero-length optical depth");
    }

    /**
     * The work of the sky allocation test: the sky optical depths of changing directions, without an assertion or a
     * message of its own, so that it can be measured.
     */
    private static double warmSkyOpticalDepth(SnowFogWalk walk, int calls) {
        double sum = 0.0;
        for (int call = 0; call < calls; call++) {
            double[] direction = SKY_WARM_DIRECTIONS[call % SKY_WARM_DIRECTIONS.length];
            sum += walk.skyOpticalDepth(direction[0], direction[1], direction[2], SKY_WALK_DISTANCE);
        }
        return sum;
    }

    /**
     * One layout of the reference tests: the field, the heights, the shown box, the fog settings and the eye, with the
     * walk that reads them and the rays the tests walk.
     */
    private static final class ReferenceLayout {
        private final byte[] distances;
        private final short[] heights;
        private final boolean hasShownBox;
        private final int shownMinX;
        private final int shownMinY;
        private final int shownMinZ;
        private final int shownMaxX;
        private final int shownMaxY;
        private final int shownMaxZ;
        private final double fieldFade;
        private final double radius;
        private final double halfWidth;
        private final double decayLength;
        private final double heightScale;
        private final double eyeExtinction;
        private final int eyeBlockX;
        private final int eyeBlockY;
        private final int eyeBlockZ;
        private final double eyeFractionX;
        private final double eyeFractionY;
        private final double eyeFractionZ;
        private final float[] table;
        private final SnowFogWalk walk;
        private double[][] rays;
        private FogStructure structure;

        private ReferenceLayout(byte[] distances, short[] heights, boolean hasShownBox, int shownMinX, int shownMinY,
                int shownMinZ, int shownMaxX, int shownMaxY, int shownMaxZ, double fieldFade, double radius,
                double decayLength, double heightScale, double eyeExtinction, int eyeBlockX, int eyeBlockY,
                int eyeBlockZ, double eyeFractionX, double eyeFractionY, double eyeFractionZ) {
            this.distances = distances;
            this.heights = heights;
            this.hasShownBox = hasShownBox;
            this.shownMinX = shownMinX;
            this.shownMinY = shownMinY;
            this.shownMinZ = shownMinZ;
            this.shownMaxX = shownMaxX;
            this.shownMaxY = shownMaxY;
            this.shownMaxZ = shownMaxZ;
            this.fieldFade = fieldFade;
            this.radius = radius;
            this.halfWidth = radius - REGION_MARGIN;
            this.decayLength = decayLength;
            this.heightScale = heightScale;
            this.eyeExtinction = eyeExtinction;
            this.eyeBlockX = eyeBlockX;
            this.eyeBlockY = eyeBlockY;
            this.eyeBlockZ = eyeBlockZ;
            this.eyeFractionX = eyeFractionX;
            this.eyeFractionY = eyeFractionY;
            this.eyeFractionZ = eyeFractionZ;
            this.table = new float[SnowFogWalk.TABLE_SIZE];
            SnowFogWalk.buildTable(decayLength, table);
            this.walk = new SnowFogWalk();
            walk.setField(distances, FIELD_SIZE);
            walk.setShownBox(hasShownBox, shownMinX, shownMinY, shownMinZ, shownMaxX, shownMaxY, shownMaxZ);
            walk.setHeights(heights);
            walk.setTable(table);
            walk.setEye(eyeBlockX, eyeBlockY, eyeBlockZ, eyeFractionX, eyeFractionY, eyeFractionZ);
            walk.setFog(eyeExtinction, heightScale);
            walk.setRadius(radius);
            walk.setFieldFade(fieldFade);
        }
    }

    /**
     * The optical depth of a ray by the fine step reference and the largest depth of one of its steps.
     */
    private static final class ReferenceDepth {
        private final double depth;
        private final double largestStepDepth;

        private ReferenceDepth(double depth, double largestStepDepth) {
            this.depth = depth;
            this.largestStepDepth = largestStepDepth;
        }
    }
}
