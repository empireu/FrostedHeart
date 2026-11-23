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
 * table of each decay length, the guarded zero, level, vertical and edge rays, the cells of the far part, the whole
 * walk against a fine step reference on random layouts, the share of a cell against the same reference, the walk to the
 * sky with the tail past it, and that the warm walks allocate nothing.
 * The helpers of the reference layouts are shared with FogFroxelGameTests, which walks the pieces of the same rays.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFogWalkGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FIELD_SIZE = 128;
    private static final int FIELD_MASK = FIELD_SIZE - 1;
    private static final int FIELD_SHIFT = 7;
    static final int WHOLE_BOX_RADIUS = FIELD_SIZE / 2;
    static final int WHOLE_BOX_SPAN = WHOLE_BOX_RADIUS - 1;
    static final int EYE_BLOCK_X = 0;
    static final int EYE_BLOCK_Y = 64;
    static final int EYE_BLOCK_Z = 0;
    private static final double EYE_FRACTION_X = 0.5;
    private static final double EYE_FRACTION_Y = 0.5;
    private static final double EYE_FRACTION_Z = 0.5;
    static final double EYE_EXTINCTION = 0.1;
    private static final double FIELD_RADIUS = 32.0;
    private static final double REGION_MARGIN = 2.0;
    private static final double CROSSFADE_WIDTH = 8.0;
    private static final double DEFAULT_DECAY_LENGTH = 1.5;
    static final double HEIGHT_SCALE = 128.0;
    private static final double DEPTH_TOLERANCE = 1.0e-9;
    private static final double SHARE_TOLERANCE = 1.0e-12;
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
    static final int SHELTERED_COLUMN_HEIGHT = 1000;
    private static final int CELL_REFERENCE_X = 40;
    private static final int CELL_REFERENCE_Y = 10;
    private static final int CELL_REFERENCE_Z = 40;
    private static final int ALLOCATION_WARMUP_CALLS = 20000;
    private static final int ALLOCATION_DEPTH_CALLS = 1000;
    private static final int ALLOCATION_TABLE_CALLS = 10;
    private static final double ALLOCATION_TABLE_STEP = 0.1;
    private static final int ALLOCATION_PIECE_CALLS = 300;
    private static final double ALLOCATION_PIECE_LENGTH = 47.0;
    private static final int ALLOCATION_BOUNDARY_COUNT = 64;
    private static final double ALLOCATION_BOUNDARY_RANGE = 192.0;
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
    static final int STRUCTURE_TEXEL_COUNT = FogStructure.SIZE * FogStructure.SIZE * FogStructure.SIZE
            * FogStructure.CHANNELS;
    static final int STRUCTURE_SHAPE_BYTE = 153;
    static final double STRUCTURE_MEAN_SHAPE = 0.176;
    static final double STRUCTURE_SHARE = 0.75;
    static final double UNIFORM_STRUCTURE_COVERAGE = 0.5;
    private static final double WALK_STRUCTURE_COVERAGE = 0.35;
    private static final double STRUCTURE_EROSION = 0.5;
    private static final double STRUCTURE_RANGE = 96.0;
    static final double UNIFORM_STRUCTURE_RANGE = 1000.0;
    private static final double SHORT_STRUCTURE_RANGE = 40.0;
    static final double UNIFORM_STRUCTURE_DENSITY = 1.75;
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
    /**
     * The distance in blocks at which the sky walk of the uniform structure begins, which stays below the cap with the
     * tail.
     */
    private static final double SKY_STRUCTURE_WALK_DISTANCE = 200.0;
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
     * A height of every column far above the eye, so that the columns cover every point of a ray that rises, and the
     * distance of the sky walk under that cover, at which the walk and the tail together stay below the cap.
     */
    private static final int SKY_COVER_HEIGHT = 1000;
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
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 10.0, 100.0, 0.0, 0.0);
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 1.3, 3.0, 4.0, 12.0);
        assertDepth(helper, "the open field of the extinction 0.1 times the length", walk, 5.0, 0.0, -50.0, 0.0);
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
        assertZeroDepth(helper, "the sheltered field at the length 10", walk, 10.0, 0.0, 0.0);
        assertZeroDepth(helper, "the sheltered field at the length 100, whose far cells all lie under the roof", walk,
                100.0, 0.0, 0.0);
        assertZeroDepth(helper, "the vertical sheltered field, whose far cells all lie under the roof", walk, 0.0, -50.0,
                0.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void crossfadeBlendsTowardTheHeightmap(GameTestHelper helper) {
        byte[] distances = byteField(0);
        short[] heights = filledHeights(SHELTERED_COLUMN_HEIGHT);
        SnowFogWalk walk = setUpWalk(distances, heights);
        assertDepth(helper, "the crossfade of the ray along x", walk, 2.6, 40.0, 0.0, 0.0);
        assertDepth(helper, "the crossfade of the ray along z", walk, 2.6, 0.0, 0.0, 40.0);
        for (int x = 35; x <= 45; x++) {
            for (int z = -2; z <= 2; z++) {
                setColumnHeight(heights, x, z, 0);
            }
        }
        // The far cells of the parts 30 to 31.5 and 31.5 to 33.5 stand under the covered columns 31 and 33, while the
        // cells of the parts 33.5 to 35.5, 35.5 to 37.5, 37.5 to 39.5 and 39.5 to 40 stand under the open columns 35, 37,
        // 39 and 41, so the far part adds 6.5 blocks on top of the 2.6 of the crossfade.
        assertDepth(helper, "the open columns 35 to 45 of the far part beyond the crossfade", walk, 3.25, 40.0, 0.0, 0.0);
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
        assertFarSteps(helper, "the ray (10.0, 0.0, 0.0) of the uniform structure, which ends inside the region",
                walk, 0);
        assertDepth(helper, "the open field of the uniform structure", walk, UNIFORM_STRUCTURE_DENSITY * 10.0, 100.0,
                0.0, 0.0);
        assertFarSteps(helper, "the ray (100.0, 0.0, 0.0) of the uniform structure, which walks the far cells from 30 "
                + "to 100 in steps of 2", walk, 36);
        assertDepth(helper, "the open field of the uniform structure", walk, UNIFORM_STRUCTURE_DENSITY * 1.3, 3.0, 4.0,
                12.0);
        assertDepth(helper, "the open field of the uniform structure", walk, UNIFORM_STRUCTURE_DENSITY * 5.0, 0.0,
                -50.0, 0.0);
        assertFarSteps(helper, "the ray (0.0, -50.0, 0.0) of the uniform structure, which walks the far cells from 30 "
                + "to 50 in steps of 2", walk, 11);
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
        assertDepth(helper, "the sheltered field of the uniform structure, whose cells and far cells all lie under the "
                + "roof of the height " + SHELTERED_COLUMN_HEIGHT, shelteredWalk, 0.0, 100.0, 0.0, 0.0);
        assertFarSteps(helper, "the ray (100.0, 0.0, 0.0) of the sheltered field, which walks the far cells from 30 to "
                + "100 in steps of 2", shelteredWalk, 36);
        assertDepth(helper, "the sheltered field of the uniform structure", shelteredWalk, 0.0, 10.0, 0.0, 0.0);
        // The region from 0 to 30 blocks adds 1.75 times its 3.0. The far cells of the parts 30 to 31.5, 31.5 to 33.5,
        // 33.5 to 35.5, 35.5 to 37.5 and 37.5 to 39.5 take the range weights 0.925, 0.75, 0.55, 0.35 and 0.15 at their
        // middles and add 0.2540625, 0.3125, 0.2825, 0.2525 and 0.2225. The mean fog from 39.5 to 100 blocks adds 6.05.
        FogStructure shortRangeStructure = setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0),
                STRUCTURE_SHARE, UNIFORM_STRUCTURE_COVERAGE, 0.0, SHORT_STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE);
        SnowFogWalk shortRangeWalk = setUpWalk(byteField(0), unknownHeights());
        shortRangeWalk.setStructure(shortRangeStructure);
        assertDepth(helper, "the open field of the uniform structure of the range " + SHORT_STRUCTURE_RANGE,
                shortRangeWalk, 12.6240625, 100.0, 0.0, 0.0);
        assertFarSteps(helper, "the ray (100.0, 0.0, 0.0) of the range " + SHORT_STRUCTURE_RANGE
                + ", which walks the far cells from 30 to 100 in steps of 2", shortRangeWalk, 36);
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
        double[] boundaryDistances = new double[ALLOCATION_BOUNDARY_COUNT];
        double[] excesses = new double[ALLOCATION_BOUNDARY_COUNT];
        fillAllocationBoundaries(boundaryDistances);
        warmOperations(layout, table, structure, boundaryDistances, excesses, ALLOCATION_WARMUP_CALLS);
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
        double sum = warmOperations(layout, table, structure, boundaryDistances, excesses, ALLOCATION_DEPTH_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm snow fog walk allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_DEPTH_CALLS + " warm optical depths of the structured "
                + "walk, the " + ALLOCATION_PIECE_CALLS + " warm pieces, the warm column excesses, the "
                + ALLOCATION_TABLE_CALLS + " warm tables and the setters allocate " + allocated + " bytes instead of 0");
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
    public static void skyWalkAddsTheTailEvenUnderCover(GameTestHelper helper) {
        SnowFogWalk walk = setUpWalk(byteField(0), filledHeights(SKY_COVER_HEIGHT));
        walk.setFog(SKY_EYE_EXTINCTION, SKY_HEIGHT_SCALE);
        walk.setRadius(SKY_RADIUS);
        double directionLength = Math.sqrt(SKY_DIRECTION_X * SKY_DIRECTION_X + SKY_DIRECTION_Y * SKY_DIRECTION_Y);
        double unitX = SKY_DIRECTION_X / directionLength;
        double unitY = SKY_DIRECTION_Y / directionLength;
        double skyDepth = walk.skyOpticalDepth(SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, SKY_COVERED_WALK_DISTANCE);
        double walked = walk.opticalDepth(unitX * SKY_COVERED_WALK_DISTANCE, unitY * SKY_COVERED_WALK_DISTANCE, 0.0);
        double tail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, SKY_COVERED_WALK_DISTANCE, unitY,
                SKY_HEIGHT_SCALE);
        double expected = walked + tail;
        double relativeDifference = Math.abs(skyDepth - expected) / Math.abs(expected);
        helper.assertTrue(relativeDifference <= SKY_STRUCTURE_RELATIVE_TOLERANCE, "The sky depth of the direction "
                + "(0.8, 0.6, 0.0) of the walk " + SKY_COVERED_WALK_DISTANCE + " under the cover of the height "
                + SKY_COVER_HEIGHT + " is " + skyDepth + " instead of the " + walked + " of the walk plus the " + tail
                + " of the tail, a relative difference of " + relativeDifference + " above "
                + SKY_STRUCTURE_RELATIVE_TOLERANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void skyWalkReportsTheInnerWalk(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        walk.skyOpticalDepth(SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, SKY_WALK_DISTANCE);
        int nearSteps = walk.lastNearSteps();
        boolean nearCapped = walk.lastNearCapped();
        int farSteps = walk.lastFarSteps();
        boolean farCapped = walk.lastFarCapped();
        double directionLength = Math.sqrt(SKY_DIRECTION_X * SKY_DIRECTION_X + SKY_DIRECTION_Y * SKY_DIRECTION_Y);
        double unitX = SKY_DIRECTION_X / directionLength;
        double unitY = SKY_DIRECTION_Y / directionLength;
        walk.opticalDepth(unitX * SKY_WALK_DISTANCE, unitY * SKY_WALK_DISTANCE, 0.0);
        String step = "the sky walk of (0.8, 0.6, 0.0) over " + SKY_WALK_DISTANCE + " blocks";
        String walkedStep = "the optical depth of (76.8, 57.6, 0.0)";
        helper.assertTrue(nearSteps == walk.lastNearSteps(), "The " + step + " leaves " + nearSteps
                + " near steps instead of the " + walk.lastNearSteps() + " of " + walkedStep);
        helper.assertTrue(nearCapped == walk.lastNearCapped(), "The " + step + " leaves the near cap at " + nearCapped
                + " instead of the " + walk.lastNearCapped() + " of " + walkedStep);
        helper.assertTrue(farSteps == walk.lastFarSteps(), "The " + step + " leaves " + farSteps
                + " far steps instead of the " + walk.lastFarSteps() + " of " + walkedStep);
        helper.assertTrue(farCapped == walk.lastFarCapped(), "The " + step + " leaves the far cap at " + farCapped
                + " instead of the " + walk.lastFarCapped() + " of " + walkedStep);
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
        double walked = walk.opticalDepth(unitX * SKY_STRUCTURE_WALK_DISTANCE, unitY * SKY_STRUCTURE_WALK_DISTANCE,
                0.0);
        double tail = HeightFog.skyTailOpticalDepth(SKY_EYE_EXTINCTION, SKY_STRUCTURE_WALK_DISTANCE, unitY,
                SKY_HEIGHT_SCALE);
        double skyDepth = walk.skyOpticalDepth(SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, SKY_STRUCTURE_WALK_DISTANCE);
        double expected = walked + tail;
        double relativeDifference = Math.abs(skyDepth - expected) / Math.abs(expected);
        helper.assertTrue(relativeDifference <= SKY_STRUCTURE_RELATIVE_TOLERANCE, "The sky depth of the direction "
                + "(0.8, 0.6, 0.0) over the structure range " + UNIFORM_STRUCTURE_RANGE + " at the walk distance "
                + SKY_STRUCTURE_WALK_DISTANCE + " is " + skyDepth + " instead of the " + walked + " of the walk plus "
                + "the " + tail + " of the tail at the density 1, a relative difference of " + relativeDifference
                + " above " + SKY_STRUCTURE_RELATIVE_TOLERANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmSkyWalkAllocatesNothing(GameTestHelper helper) {
        SnowFogWalk walk = setUpSkyWalk();
        walk.setStructure(setUpStructure(filledStructureTexels(STRUCTURE_SHAPE_BYTE, 0), STRUCTURE_SHARE,
                UNIFORM_STRUCTURE_COVERAGE, STRUCTURE_EROSION, STRUCTURE_RANGE, STRUCTURE_MEAN_SHAPE));
        double[] boundaryDistances = new double[ALLOCATION_BOUNDARY_COUNT];
        double[] excesses = new double[ALLOCATION_BOUNDARY_COUNT];
        fillAllocationBoundaries(boundaryDistances);
        warmSkyOpticalDepth(walk, boundaryDistances, excesses, ALLOCATION_WARMUP_CALLS);
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
        double sum = warmSkyOpticalDepth(walk, boundaryDistances, excesses, ALLOCATION_DEPTH_CALLS);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("The warm sky snow fog walk allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_DEPTH_CALLS + " warm sky optical depths, the "
                + ALLOCATION_PIECE_CALLS + " warm pieces and the warm column excesses allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * The work of the allocation test: the optical depths of the rays of the first layout with the structure on, a few
     * pieces of those rays, one column of excesses over the caller-owned arrays, the tables into the same array and the
     * setters, without an assertion or a message of its own, so that it can be measured.
     */
    private static double warmOperations(ReferenceLayout layout, float[] table, FogStructure structure,
            double[] boundaryDistances, double[] excesses, int depthCalls) {
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
        for (int call = 0; call < ALLOCATION_PIECE_CALLS; call++) {
            double[] ray = layout.rays[call % layout.rays.length];
            double start = call % ALLOCATION_BOUNDARY_COUNT;
            sum += walk.pieceOpticalDepth(ray[0], ray[1], ray[2], start, start + ALLOCATION_PIECE_LENGTH);
        }
        double[] levelRay = layout.rays[LEVEL_RAY];
        walk.columnExcesses(levelRay[0], levelRay[1], levelRay[2], boundaryDistances, ALLOCATION_BOUNDARY_COUNT,
                excesses);
        sum += excesses[ALLOCATION_BOUNDARY_COUNT - 1];
        for (int call = 0; call < ALLOCATION_TABLE_CALLS; call++) {
            SnowFogWalk.buildTable(layout.decayLength + call * ALLOCATION_TABLE_STEP, table);
        }
        return sum;
    }

    /**
     * The boundary distances of the allocation test: geometric boundaries from NEAR out to the allocation range, so
     * that the warm pieces and the warm column walk every part of the grid.
     */
    private static void fillAllocationBoundaries(double[] boundaryDistances) {
        for (int boundary = 0; boundary < boundaryDistances.length; boundary++) {
            boundaryDistances[boundary] = Math.pow(ALLOCATION_BOUNDARY_RANGE,
                    (boundary + 1.0) / ALLOCATION_BOUNDARY_COUNT);
        }
    }

    /**
     * Builds the four layouts of the reference tests, one after the other from the same random sequence.
     */
    static ReferenceLayout[] buildLayouts() {
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
    static ReferenceDepth referenceDepth(ReferenceLayout layout, double offsetX, double offsetY, double offsetZ,
            FogStructure structure) {
        double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        int stepCount = Math.max(1, (int) Math.ceil(length * STEPS_PER_BLOCK));
        double[] stepShares = new double[stepCount];
        for (int stepIndex = stepCount - 1; stepIndex >= 0; stepIndex--) {
            double parameter = (stepIndex + 0.5) / stepCount;
            double pointX = layout.eyeFractionX + parameter * offsetX;
            double pointY = layout.eyeFractionY + parameter * offsetY;
            double pointZ = layout.eyeFractionZ + parameter * offsetZ;
            boolean inRegion = Math.abs(parameter * offsetX) <= layout.halfWidth
                    && Math.abs(parameter * offsetY) <= layout.halfWidth
                    && Math.abs(parameter * offsetZ) <= layout.halfWidth;
            if (inRegion) {
                stepShares[stepIndex] = referenceCellShare(layout, (int) Math.floor(pointX),
                        (int) Math.floor(pointY), (int) Math.floor(pointZ));
            } else {
                stepShares[stepIndex] = referenceFarShare(layout, pointX, pointY, pointZ);
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
     * The share of a point beyond the region, which is the far rule: the height share of the far cell of FAR_CELL
     * blocks that contains the point, whose middle point decides, and which is 1 for a column of unknown height.
     * The cell is found in the frame of the grid, which starts at the grid line at or below the eye's block, and its
     * middle is turned into a world point before the column under it and the height it stands at are read.
     */
    private static double referenceFarShare(ReferenceLayout layout, double pointX, double pointY, double pointZ) {
        int gridOriginX = Math.floorDiv(layout.eyeBlockX, SnowFogWalk.FAR_CELL_BLOCKS) * SnowFogWalk.FAR_CELL_BLOCKS;
        int gridOriginY = Math.floorDiv(layout.eyeBlockY, SnowFogWalk.FAR_CELL_BLOCKS) * SnowFogWalk.FAR_CELL_BLOCKS;
        int gridOriginZ = Math.floorDiv(layout.eyeBlockZ, SnowFogWalk.FAR_CELL_BLOCKS) * SnowFogWalk.FAR_CELL_BLOCKS;
        double localX = layout.eyeBlockX - gridOriginX + pointX;
        double localY = layout.eyeBlockY - gridOriginY + pointY;
        double localZ = layout.eyeBlockZ - gridOriginZ + pointZ;
        double middleX = (Math.floor(localX / SnowFogWalk.FAR_CELL) + 0.5) * SnowFogWalk.FAR_CELL;
        double middleY = (Math.floor(localY / SnowFogWalk.FAR_CELL) + 0.5) * SnowFogWalk.FAR_CELL;
        double middleZ = (Math.floor(localZ / SnowFogWalk.FAR_CELL) + 0.5) * SnowFogWalk.FAR_CELL;
        int worldMiddleX = gridOriginX + (int) Math.floor(middleX);
        int worldMiddleZ = gridOriginZ + (int) Math.floor(middleZ);
        double worldMiddleY = gridOriginY + middleY;
        int height = layout.heights[FarHeights.texelOf(worldMiddleX, worldMiddleZ)];
        return worldMiddleY >= height ? 1.0 : 0.0;
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
    static SnowFogWalk setUpWalk(byte[] distances, short[] heights) {
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
    static void setWholeShownBox(SnowFogWalk walk) {
        walk.setShownBox(true, EYE_BLOCK_X - WHOLE_BOX_RADIUS, EYE_BLOCK_Y - WHOLE_BOX_RADIUS,
                EYE_BLOCK_Z - WHOLE_BOX_RADIUS, EYE_BLOCK_X + WHOLE_BOX_SPAN, EYE_BLOCK_Y + WHOLE_BOX_SPAN,
                EYE_BLOCK_Z + WHOLE_BOX_SPAN);
    }

    /**
     * A field of the given byte everywhere.
     */
    static byte[] byteField(int value) {
        byte[] distances = new byte[FIELD_SIZE * FIELD_SIZE * FIELD_SIZE];
        Arrays.fill(distances, (byte) value);
        return distances;
    }

    /**
     * Heights of FarHeights.UNKNOWN everywhere.
     */
    static short[] unknownHeights() {
        short[] heights = new short[FarHeights.SIZE * FarHeights.SIZE];
        Arrays.fill(heights, FarHeights.UNKNOWN);
        return heights;
    }

    /**
     * Heights of the given value everywhere.
     */
    static short[] filledHeights(int value) {
        short[] heights = new short[FarHeights.SIZE * FarHeights.SIZE];
        Arrays.fill(heights, (short) value);
        return heights;
    }

    /**
     * Sets the height of the column at the given world position.
     */
    static void setColumnHeight(short[] heights, int x, int z, int height) {
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
    static void setLayoutStructure(ReferenceLayout layout, byte[] texels, double size) {
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
    static FogStructure setUpStructure(byte[] texels, double share, double coverage, double erosion, double range,
            double mean) {
        FogStructure structure = new FogStructure();
        structure.setTexture(texels);
        structure.setDensityWithMeans(share, coverage, erosion, range, mean, mean);
        return structure;
    }

    /**
     * A structure texture of the given shape byte and detail byte everywhere.
     */
    static byte[] filledStructureTexels(int shapeByte, int detailByte) {
        byte[] texels = new byte[STRUCTURE_TEXEL_COUNT];
        for (int texel = 0; texel < STRUCTURE_TEXEL_COUNT; texel += FogStructure.CHANNELS) {
            texels[texel + FogStructure.SHAPE_CHANNEL] = (byte) shapeByte;
            texels[texel + FogStructure.DETAIL_CHANNEL] = (byte) detailByte;
        }
        return texels;
    }

    /**
     * Checks the depths of the open field and the far cells of each ray, which must stay the depths of the fog without
     * structures.
     */
    private static void assertDepthsWithoutStructure(GameTestHelper helper, String step, SnowFogWalk walk) {
        assertDepth(helper, "the open field of " + step, walk, 1.0, 10.0, 0.0, 0.0);
        assertFarSteps(helper, "the ray (10.0, 0.0, 0.0) of " + step, walk, 0);
        assertDepth(helper, "the open field of " + step, walk, 10.0, 100.0, 0.0, 0.0);
        assertFarSteps(helper, "the ray (100.0, 0.0, 0.0) of " + step + ", whose far cells run from 30 to 100 in steps "
                + "of 2", walk, 36);
        assertDepth(helper, "the open field of " + step, walk, 1.3, 3.0, 4.0, 12.0);
        assertFarSteps(helper, "the ray (3.0, 4.0, 12.0) of " + step, walk, 0);
        assertDepth(helper, "the open field of " + step, walk, 5.0, 0.0, -50.0, 0.0);
        assertFarSteps(helper, "the ray (0.0, -50.0, 0.0) of " + step + ", whose far cells run from 30 to 50 in steps "
                + "of 2", walk, 11);
    }

    /**
     * Checks the cells that the far part of the last ray visited against the expected count.
     */
    private static void assertFarSteps(GameTestHelper helper, String step, SnowFogWalk walk, int expectedSteps) {
        int farSteps = walk.lastFarSteps();
        helper.assertTrue(farSteps == expectedSteps, "In " + step + ", the far part visits " + farSteps
                + " cells instead of " + expectedSteps);
    }

    /**
     * A ray as the messages name it.
     */
    static String describeRay(double[] ray) {
        return describeRay(ray[0], ray[1], ray[2]);
    }

    /**
     * A ray as the messages name it.
     */
    static String describeRay(double offsetX, double offsetY, double offsetZ) {
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
     * Checks that the four last values of a walk equal those that a zero-length opticalDepth call leaves, which the
     * guards of the sky walk keep.
     */
    private static void assertZeroLengthLastValues(GameTestHelper helper, String step, SnowFogWalk walk) {
        int nearSteps = walk.lastNearSteps();
        boolean nearCapped = walk.lastNearCapped();
        int farSteps = walk.lastFarSteps();
        boolean farCapped = walk.lastFarCapped();
        walk.opticalDepth(0.0, 0.0, 0.0);
        helper.assertTrue(nearSteps == walk.lastNearSteps(), "The sky walk of " + step + " leaves " + nearSteps
                + " near steps instead of the " + walk.lastNearSteps() + " of a zero-length optical depth");
        helper.assertTrue(nearCapped == walk.lastNearCapped(), "The sky walk of " + step + " leaves the near cap at "
                + nearCapped + " instead of the " + walk.lastNearCapped() + " of a zero-length optical depth");
        helper.assertTrue(farSteps == walk.lastFarSteps(), "The sky walk of " + step + " leaves " + farSteps
                + " far steps instead of the " + walk.lastFarSteps() + " of a zero-length optical depth");
        helper.assertTrue(farCapped == walk.lastFarCapped(), "The sky walk of " + step + " leaves the far cap at "
                + farCapped + " instead of the " + walk.lastFarCapped() + " of a zero-length optical depth");
    }

    /**
     * The work of the sky allocation test: the sky optical depths of changing directions, a few pieces of those
     * directions and one column of excesses over the caller-owned arrays, without an assertion or a message of its own,
     * so that it can be measured.
     */
    private static double warmSkyOpticalDepth(SnowFogWalk walk, double[] boundaryDistances, double[] excesses,
            int calls) {
        double sum = 0.0;
        for (int call = 0; call < calls; call++) {
            double[] direction = SKY_WARM_DIRECTIONS[call % SKY_WARM_DIRECTIONS.length];
            sum += walk.skyOpticalDepth(direction[0], direction[1], direction[2], SKY_WALK_DISTANCE);
        }
        for (int call = 0; call < ALLOCATION_PIECE_CALLS; call++) {
            double[] direction = SKY_WARM_DIRECTIONS[call % SKY_WARM_DIRECTIONS.length];
            sum += walk.pieceOpticalDepth(direction[0], direction[1], direction[2], 0.0, ALLOCATION_PIECE_LENGTH);
        }
        walk.columnExcesses(SKY_DIRECTION_X, SKY_DIRECTION_Y, 0.0, boundaryDistances, ALLOCATION_BOUNDARY_COUNT,
                excesses);
        sum += excesses[ALLOCATION_BOUNDARY_COUNT - 1];
        return sum;
    }

    /**
     * One layout of the reference tests: the field, the heights, the shown box, the fog settings and the eye, with the
     * walk that reads them and the rays the tests walk.
     */
    static final class ReferenceLayout {
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
        final double halfWidth;
        private final double decayLength;
        final double heightScale;
        final double eyeExtinction;
        private final int eyeBlockX;
        private final int eyeBlockY;
        private final int eyeBlockZ;
        private final double eyeFractionX;
        private final double eyeFractionY;
        private final double eyeFractionZ;
        private final float[] table;
        final SnowFogWalk walk;
        double[][] rays;
        FogStructure structure;

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
    static final class ReferenceDepth {
        final double depth;
        final double largestStepDepth;

        private ReferenceDepth(double depth, double largestStepDepth) {
            this.depth = depth;
            this.largestStepDepth = largestStepDepth;
        }
    }
}
