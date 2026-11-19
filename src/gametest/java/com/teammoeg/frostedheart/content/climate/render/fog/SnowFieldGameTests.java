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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Checks the snow-share solver against hand-worked distances of small layouts and against an independent Dijkstra over
 * the same definition: open sky, porches, a leaf canopy, sealed rooms, doorways and open doors, a cave, shafts, the
 * distance cap, seeds at the cube's top, edits that raise a face cost, random edits, unloaded chunks, the cube
 * following the eye, the solved box, the dirty bricks and the allocation of the warm operations.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFieldGameTests {
    private static final int GROUND_TOP = 60;
    private static final int WORLD_BOTTOM = -64;
    private static final int WORLD_TOP = 319;
    private static final int WHOLE = 0;
    private static final int EXACT = 1;
    private static final int TOP_DOWN = 0;
    private static final int BOTTOM_UP = 1;
    private static final int SHUFFLED = 2;
    private static final long SHUFFLE_SEED = 11L;
    /**
     * The margin that the exact comparison keeps from the cube's sides, and the layer it keeps from the cube's bottom,
     * because retained cells there can keep paths through cells that have left the cube.
     */
    private static final int EXACT_MARGIN = 25;
    private static final int EXACT_LOWEST_LAYER = 13;
    private static final int STANDARD_FIELD_SIZE = 32;
    private static final int LARGE_FIELD_SIZE = 64;
    private static final int RANDOM_EDIT_COUNT = 300;
    private static final int RANDOM_EDIT_MAXIMUM_BUDGET = 200;
    private static final int COMPARE_INTERVAL = 25;
    private static final long RANDOM_EDIT_SEED = 20260101L;
    private static final int INTERLEAVED_ITERATIONS = 600;
    private static final long INTERLEAVED_SEED = 20260202L;
    private static final int INTERLEAVED_MAXIMUM_LAG_READS = 60;
    private static final int INTERLEAVED_EDIT_LIMIT = 45;
    private static final int INTERLEAVED_MOVE_LIMIT = 65;
    private static final int INTERLEAVED_CHUNK_LIMIT = 70;
    private static final int CHUNK_CHANGE_COUNT = 3;
    private static final int EYE_LOWEST_Y = 58;
    private static final int EYE_HIGHEST_Y = 72;
    /**
     * The chunks that the interleaved test unloads at the start, as x and z pairs, all within 20 blocks of the start.
     */
    private static final int[] START_UNLOADED_CHUNKS = {0, 0, 1, 0, 0, 1, -1, 0};
    /**
     * The most chunks a test world can have unloaded, which is more than the four of the interleaved test.
     */
    private static final int MAXIMUM_UNLOADED_CHUNKS = 16;
    private static final int WALK_FEATURE_STEP = 10;
    private static final int WALK_FEATURE_LOWEST = -150;
    private static final int WALK_FEATURE_HIGHEST = 150;
    private static final int WALK_FEATURE_KIND_COUNT = 7;
    private static final int WALK_LENGTH = 50;
    private static final int WALK_UP_LENGTH = 20;
    private static final int WALK_DOWN_LENGTH = 40;
    private static final int PALETTE_AIR_WEIGHT = 4;
    private static final int PALETTE_STONE_WEIGHT = 2;
    private static final int PALETTE_OTHER_COUNT = 12;
    private static final int PALETTE_WEIGHT = PALETTE_AIR_WEIGHT + PALETTE_STONE_WEIGHT + PALETTE_OTHER_COUNT;
    private static final int WARM_ITERATIONS = 50;
    private static final int WARM_PASSES = 8;
    private static final int ALLOCATION_READS = 20;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST};

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void openGroundIsOpenSky(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        SnowField topDownField = defaultField(faces);
        topDownField.fill(0, 64, 0);
        readAll(topDownField, world, TOP_DOWN);
        drain(helper, topDownField);
        assertOpenGround(helper, topDownField, faces, world, "the top down read");
        SnowField bottomUpField = defaultField(faces);
        bottomUpField.fill(0, 64, 0);
        readAll(bottomUpField, world, BOTTOM_UP);
        drain(helper, bottomUpField);
        assertOpenGround(helper, bottomUpField, faces, world, "the bottom up read");
        SnowField shuffledField = defaultField(faces);
        shuffledField.fill(0, 64, 0);
        readAll(shuffledField, world, SHUFFLED);
        drain(helper, shuffledField);
        assertOpenGround(helper, shuffledField, faces, world, "the shuffled read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void porchIsReachedSideways(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        placePorch(world, faces.maskSetOf(Blocks.STONE.defaultBlockState()), 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, SHUFFLED);
        drain(helper, field);
        assertDistance(helper, field, 2, 62, 2, 24);
        assertDistance(helper, field, 0, 61, 0, 8);
        assertDistance(helper, field, 1, 63, 2, 16);
        assertDistance(helper, field, 4, 62, 2, 8);
        assertDistance(helper, field, 2, 65, 2, 0);
        assertDistance(helper, field, 2, 64, 2, SnowField.SHELTERED);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the shuffled read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void canopyPassesThroughLeavesAndSideways(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        placeCanopy(world, faces.maskSetOf(Blocks.OAK_LEAVES.defaultBlockState()), 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertDistance(helper, field, 0, 65, 0, 29);
        assertDistance(helper, field, 5, 65, 5, 29);
        assertDistance(helper, field, 0, 64, 0, 48);
        assertDistance(helper, field, 0, 61, 0, 48);
        assertDistance(helper, field, -3, 62, 0, 24);
        assertDistance(helper, field, 5, 64, 5, 8);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the top down read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void glassHouseIsSheltered(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        placeGlassHouse(world, faces.maskSetOf(Blocks.GLASS.defaultBlockState()), SnowFaces.EMPTY_MASK_SET, 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, BOTTOM_UP);
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 2, 64, -2, SnowField.SHELTERED);
        assertDistance(helper, field, 0, 66, 0, 0);
        assertDistance(helper, field, 4, 61, 0, 0);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the bottom up read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void doorwayAndOpenDoorFillTheRoom(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        TestWorld openWorld = new TestWorld(GROUND_TOP);
        placeHouseWithDoorway(openWorld, stoneMaskSet, SnowFaces.EMPTY_MASK_SET, 0, 0, 0);
        SnowField openField = defaultField(faces);
        openField.fill(0, 64, 0);
        readAll(openField, openWorld, SHUFFLED);
        drain(helper, openField);
        assertDistance(helper, openField, 0, 61, -4, 8);
        assertDistance(helper, openField, 0, 61, -3, 16);
        assertDistance(helper, openField, 0, 63, -3, 32);
        assertDistance(helper, openField, 0, 64, -3, 48);
        assertDistance(helper, openField, 0, 61, 0, 40);
        assertDistance(helper, openField, 3, 64, 3, 120);
        assertMatches(helper, openField, expectedBytes(openField, faces, openWorld, loadedCells(openWorld)), WHOLE,
                "the empty doorway");
        TestWorld doorWorld = new TestWorld(GROUND_TOP);
        placeHouseWithDoorway(doorWorld, stoneMaskSet, SnowFaces.EMPTY_MASK_SET, 0, 0, 0);
        int doorLowerMaskSet = faces.maskSetOf(oakDoor(Direction.NORTH, true, DoorHingeSide.LEFT,
                DoubleBlockHalf.LOWER));
        int doorUpperMaskSet = faces.maskSetOf(oakDoor(Direction.NORTH, true, DoorHingeSide.LEFT,
                DoubleBlockHalf.UPPER));
        doorWorld.set(0, 61, -4, doorLowerMaskSet);
        doorWorld.set(0, 62, -4, doorUpperMaskSet);
        SnowField doorField = defaultField(faces);
        doorField.fill(0, 64, 0);
        readAll(doorField, doorWorld, SHUFFLED);
        drain(helper, doorField);
        assertDistance(helper, doorField, 0, 61, -4, 15);
        assertDistance(helper, doorField, 0, 61, -3, 30);
        assertDistance(helper, doorField, 0, 62, -3, 30);
        assertDistance(helper, doorField, 0, 63, -3, 46);
        assertDistance(helper, doorField, 0, 61, 0, 54);
        assertMatches(helper, doorField, expectedBytes(doorField, faces, doorWorld, loadedCells(doorWorld)), WHOLE,
                "the open door");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void caveMouthAndChamber(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        world.fillBox(0, 61, -15, 15, 70, 15, stoneMaskSet);
        world.fillBox(0, 61, 0, 10, 62, 0, airMaskSet);
        world.fillBox(11, 61, -2, 14, 64, 2, airMaskSet);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, 8);
        assertDistance(helper, field, 10, 62, 0, 88);
        assertDistance(helper, field, 11, 61, 0, 96);
        assertDistance(helper, field, 14, 61, 0, 120);
        assertDistance(helper, field, 11, 64, 0, 128);
        assertDistance(helper, field, 14, 64, 2, 168);
        assertDistance(helper, field, 15, 61, 0, SnowField.SHELTERED);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the top down read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void shaftsOpenAndUnderAnOverhang(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        placeOpenShaft(world, stoneMaskSet, airMaskSet, 0, 0, 0);
        placeRoofedShaft(world, stoneMaskSet, airMaskSet, 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, BOTTOM_UP);
        drain(helper, field);
        assertDistance(helper, field, -9, 61, 0, 0);
        assertDistance(helper, field, -9, 70, 0, 0);
        assertDistance(helper, field, 9, 69, 0, 8);
        assertDistance(helper, field, 6, 69, 0, 32);
        assertDistance(helper, field, 6, 61, 0, 32);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the bottom up read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void cellAtTheCapIsKept(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        world.fillBox(1, 61, -1, 31, 63, 1, stoneMaskSet);
        world.fillBox(1, 62, 0, 31, 62, 0, SnowFaces.EMPTY_MASK_SET);
        SnowField field = new SnowField(faces, LARGE_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertDistance(helper, field, 1, 62, 0, 8);
        assertDistance(helper, field, 12, 62, 0, 96);
        assertDistance(helper, field, 24, 62, 0, 192);
        assertDistance(helper, field, 25, 62, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 31, 62, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 0, 62, 0, 0);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the top down read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void seedsFollowHeightAndTopFaces(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        world.fillBox(-3, 90, -3, 3, 90, 3, stoneMaskSet);
        world.set(8, 79, 8, faces.maskSetOf(oakStairs(Direction.NORTH, Half.BOTTOM)));
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, SHUFFLED);
        drain(helper, field);
        assertDistance(helper, field, 0, 70, 0, 32);
        assertDistance(helper, field, 3, 70, 0, 8);
        assertDistance(helper, field, 2, 65, 1, 16);
        assertDistance(helper, field, 0, 79, 0, 32);
        assertDistance(helper, field, 8, 79, 8, 17);
        assertDistance(helper, field, 8, 78, 8, 8);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the shuffled read");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void shaftRoofedAndOpenedAgain(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        placeOpenShaft(world, stoneMaskSet, airMaskSet, 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertDistance(helper, field, -9, 61, 0, 0);
        assertDistance(helper, field, -9, 70, 0, 0);
        edit(field, world, -9, 70, 0, stoneMaskSet);
        drain(helper, field);
        assertDistance(helper, field, -9, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, -9, 69, 0, SnowField.SHELTERED);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the roofed shaft");
        edit(field, world, -9, 70, 0, airMaskSet);
        drain(helper, field);
        assertDistance(helper, field, -9, 61, 0, 0);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the shaft opened again");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void thickCeilingPlaceAndBreak(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        placeThickCeilingRoom(world, stoneMaskSet, airMaskSet, 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, BOTTOM_UP);
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, 0);
        assertDistance(helper, field, 1, 61, 0, 8);
        assertDistance(helper, field, 3, 61, 3, 48);
        edit(field, world, 0, 65, 0, stoneMaskSet);
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 3, 61, 3, SnowField.SHELTERED);
        assertDistance(helper, field, 0, 64, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 0, 66, 0, 0);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the plugged hole");
        edit(field, world, 0, 65, 0, airMaskSet);
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, 0);
        assertDistance(helper, field, 1, 61, 0, 8);
        assertDistance(helper, field, 3, 61, 3, 48);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the hole opened again");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void paneInAGapRaisesThePorchBehindIt(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        world.fillBox(-1, 61, -1, 4, 62, 1, stoneMaskSet);
        world.fillBox(0, 61, 0, 3, 61, 0, airMaskSet);
        world.set(0, 62, 0, airMaskSet);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, 0);
        assertDistance(helper, field, 1, 61, 0, 8);
        assertDistance(helper, field, 3, 61, 0, 24);
        edit(field, world, 0, 61, 0, faces.maskSetOf(pane(true, false, true, false)));
        drain(helper, field);
        assertDistance(helper, field, 0, 61, 0, 0);
        assertDistance(helper, field, 1, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 3, 61, 0, SnowField.SHELTERED);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the pane in the gap");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void randomEditsMatchAFullSolve(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int leavesMaskSet = faces.maskSetOf(Blocks.OAK_LEAVES.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        TestWorld world = new TestWorld(GROUND_TOP);
        placeThickCeilingRoom(world, stoneMaskSet, airMaskSet, 0, 0, 0);
        placeHouseWithDoorway(world, stoneMaskSet, airMaskSet, 10, 0, 0);
        placePorch(world, stoneMaskSet, -14, 0, 6);
        placeCanopy(world, leavesMaskSet, 0, 0, -11);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, SHUFFLED);
        drain(helper, field);
        Random random = new Random(RANDOM_EDIT_SEED);
        for (int editIndex = 0; editIndex < RANDOM_EDIT_COUNT; editIndex++) {
            int x = random.nextInt(29) - 14;
            int z = random.nextInt(29) - 14;
            int y = 61 + random.nextInt(12);
            edit(field, world, x, y, z, randomPaletteMaskSet(random, faces));
            field.process(random.nextInt(RANDOM_EDIT_MAXIMUM_BUDGET + 1));
            if ((editIndex + 1) % COMPARE_INTERVAL == 0) {
                drain(helper, field);
                assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                        "edit " + (editIndex + 1));
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void unreadChunkIsImpassableUntilLoaded(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        world.fillBox(12, 61, -3, 19, 65, 3, stoneMaskSet);
        world.fillBox(13, 61, -2, 18, 64, 2, SnowFaces.EMPTY_MASK_SET);
        world.set(19, 61, 0, SnowFaces.EMPTY_MASK_SET);
        world.unloadChunk(1, -1);
        world.unloadChunk(1, 0);
        SnowField field = defaultField(faces);
        field.fill(16, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertDistance(helper, field, 13, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 15, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 16, 61, 0, SnowField.UNREAD);
        assertDistance(helper, field, 11, 61, 0, 0);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the unloaded chunk");
        world.loadChunk(1, -1);
        world.loadChunk(1, 0);
        readChunk(field, world, 1, -1);
        readChunk(field, world, 1, 0);
        drain(helper, field);
        assertDistance(helper, field, 19, 61, 0, 8);
        assertDistance(helper, field, 18, 61, 0, 16);
        assertDistance(helper, field, 15, 61, 0, 40);
        assertDistance(helper, field, 13, 61, 0, 56);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the loaded chunk");
        world.set(19, 61, 0, stoneMaskSet);
        readChunk(field, world, 1, -1);
        readChunk(field, world, 1, 0);
        drain(helper, field);
        assertDistance(helper, field, 13, 61, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 18, 61, 0, SnowField.SHELTERED);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE,
                "the doorway turned to stone");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void walksMatchAFullSolve(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = buildWalkWorld(faces);
        SnowField field = new SnowField(faces, LARGE_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        field.fill(0, 64, 0);
        readAll(field, world, SHUFFLED);
        drain(helper, field);
        int[] eye = {0, 64, 0};
        walk(helper, field, world, faces, eye, 1, 0, 0, WALK_LENGTH, true);
        walk(helper, field, world, faces, eye, 0, 0, 1, WALK_LENGTH, true);
        walk(helper, field, world, faces, eye, -1, 0, -1, WALK_LENGTH, true);
        walk(helper, field, world, faces, eye, 0, 1, 0, WALK_UP_LENGTH, false);
        walk(helper, field, world, faces, eye, 0, -1, 0, WALK_DOWN_LENGTH, false);
        walk(helper, field, world, faces, eye, 0, 0, -1, WALK_LENGTH, false);
        SnowField largeField = new SnowField(faces, SnowField.DEFAULT_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        largeField.fill(0, 64, 0);
        readAll(largeField, world, BOTTOM_UP);
        drain(helper, largeField);
        int[] largeEye = {0, 64, 0};
        walk(helper, largeField, world, faces, largeEye, 1, 0, 0, WALK_LENGTH, false);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void interleavedOperationsMatchAFullSolve(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        runInterleaved(helper, faces, INTERLEAVED_SEED, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY, false, false);
        runInterleaved(helper, faces, INTERLEAVED_SEED, SnowField.MINIMUM_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY, true, false);
        runInterleaved(helper, faces, INTERLEAVED_SEED, SnowField.DEFAULT_QUEUE_CAPACITY, 4, false, true);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void jumpsBecomeFills(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        helper.assertTrue(field.fillCount() == 1, "The first fill counted " + field.fillCount()
                + " fills instead of 1");
        helper.assertFalse(field.moveTo(16, 64, 0), "The move of 16 blocks refilled the field");
        helper.assertTrue(field.fillCount() == 1, "The move of 16 blocks leaves " + field.fillCount()
                + " fills instead of 1");
        assertAllUnread(helper, field, 16, field.originY(), -16, 31, field.originY() + STANDARD_FIELD_SIZE - 1, 15,
                "the half of the cube that entered");
        assertDistance(helper, field, 0, 61, 0, 0);
        helper.assertTrue(field.moveTo(33, 64, 0), "The move of 17 blocks did not refill the field");
        helper.assertTrue(field.fillCount() == 2, "The move of 17 blocks counted " + field.fillCount()
                + " fills instead of 2");
        assertAllUnread(helper, field, 17, field.originY(), -16, 48, field.originY() + STANDARD_FIELD_SIZE - 1, 15,
                "the cube after the fill");
        helper.assertFalse(field.hasSolvedBox(), "The solved box is not empty after a fill");
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        helper.assertTrue(field.moveTo(33, 64, -17), "The jump in z did not refill the field");
        helper.assertTrue(field.fillCount() == 3, "The jump in z counted " + field.fillCount()
                + " fills instead of 3");
        SnowField freshField = defaultField(faces);
        helper.assertTrue(freshField.moveTo(5, 64, 5), "The first move of a field without a position did not fill "
                + "it");
        helper.assertTrue(freshField.fillCount() == 1, "The first move of a field without a position counted "
                + freshField.fillCount() + " fills instead of 1");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void solvedBoxFollowsReads(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        helper.assertFalse(field.hasSolvedBox(), "The solved box is not empty after the first fill");
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        assertSolvedBox(helper, field, -16, 48, -16, 15, 79, 15, "the first read");
        helper.assertFalse(field.moveTo(1, 64, 0), "The move to x 1 refilled the field");
        assertSolvedBox(helper, field, -15, 48, -16, 15, 79, 15, "the move to x 1");
        helper.assertFalse(field.isInSolvedBox(16, 70, 0), "The cell at x 16 is in the solved box after the move "
                + "to x 1");
        helper.assertFalse(field.moveTo(2, 64, 0), "The move to x 2 refilled the field");
        assertSolvedBox(helper, field, -14, 48, -16, 15, 79, 15, "the move to x 2 before reading");
        readUnreadLoadedCells(field, world);
        field.readsComplete();
        drain(helper, field);
        assertSolvedBox(helper, field, -14, 48, -16, 17, 79, 15, "the read after the move to x 2");
        helper.assertFalse(field.moveTo(2, 65, 0), "The move to y 65 refilled the field");
        readUnreadLoadedCells(field, world);
        drain(helper, field);
        assertSolvedBox(helper, field, -14, 49, -16, 17, 79, 15, "the move to y 65 without completing the reads");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void editsMarkTheirBricksDirty(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        placeOpenShaft(world, stoneMaskSet, airMaskSet, 0, 0, 0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, TOP_DOWN);
        drain(helper, field);
        field.clearDirtyBricks();
        helper.assertTrue(field.dirtyBrickCount() == 0, "Clearing the dirty bricks leaves "
                + field.dirtyBrickCount() + " dirty bricks instead of 0");
        helper.assertTrue(field.brickCount() == 64, "A field of size 32 has " + field.brickCount()
                + " bricks instead of 64");
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int[] bytesBefore = new int[size * size * size];
        for (int localX = 0; localX < size; localX++) {
            for (int localY = 0; localY < size; localY++) {
                for (int localZ = 0; localZ < size; localZ++) {
                    bytesBefore[cellIndex(localX, localY, localZ, size)] = field.distanceAt(originX + localX,
                            originY + localY, originZ + localZ);
                }
            }
        }
        edit(field, world, -9, 70, 0, stoneMaskSet);
        drain(helper, field);
        int changedCount = 0;
        int uncleanedCount = 0;
        String firstUncleanCell = "";
        for (int localX = 0; localX < size; localX++) {
            for (int localY = 0; localY < size; localY++) {
                for (int localZ = 0; localZ < size; localZ++) {
                    int x = originX + localX;
                    int y = originY + localY;
                    int z = originZ + localZ;
                    int index = cellIndex(localX, localY, localZ, size);
                    if (field.distanceAt(x, y, z) == bytesBefore[index]) {
                        continue;
                    }
                    changedCount++;
                    if (!field.isBrickDirty(field.brickIndexOf(x, y, z))) {
                        uncleanedCount++;
                        if (uncleanedCount == 1) {
                            firstUncleanCell = "(" + x + ", " + y + ", " + z + ")";
                        }
                    }
                }
            }
        }
        helper.assertTrue(changedCount > 0, "The edit changed no byte");
        helper.assertTrue(uncleanedCount == 0, changedCount + " cells changed, and " + uncleanedCount
                + " of them are in bricks that are not dirty, the first being " + firstUncleanCell);
        helper.assertTrue(field.dirtyBrickCount() >= 1, "The edit leaves " + field.dirtyBrickCount()
                + " dirty bricks instead of at least 1");
        helper.assertTrue(field.dirtyBrickCount() < field.brickCount(), "The edit leaves "
                + field.dirtyBrickCount() + " dirty bricks, which is not below the " + field.brickCount()
                + " bricks of the field");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmOperationsAllocateNothing(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        TestWorld world = new TestWorld(GROUND_TOP);
        placeThickCeilingRoom(world, faces.maskSetOf(Blocks.STONE.defaultBlockState()), SnowFaces.EMPTY_MASK_SET, 0, 0,
                0);
        SnowField field = defaultField(faces);
        field.fill(0, 64, 0);
        readAll(field, world, BOTTOM_UP);
        drain(helper, field);
        int[] enteringMaskSets = openGroundSlabMaskSets(field.size(), field.originY());
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LogUtils.getLogger().info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation "
                    + "of the warm operations is not measured");
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
            LogUtils.getLogger().info("Thread allocated memory is not enabled, so the allocation of the warm "
                    + "operations is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int read = 0; read < ALLOCATION_READS; read++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        for (int pass = 0; pass < WARM_PASSES; pass++) {
            warmOperations(field, enteringMaskSets);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        long sum = warmOperations(field, enteringMaskSets);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LogUtils.getLogger().info("SnowField warm operations allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + WARM_ITERATIONS + " warm operations allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * Checks the distances of open ground against the hand values and the whole cube against the reference.
     */
    private static void assertOpenGround(GameTestHelper helper, SnowField field, SnowFaces faces, TestWorld world,
            String label) {
        assertDistance(helper, field, 0, 61, 0, 0);
        assertDistance(helper, field, 5, 79, -7, 0);
        assertDistance(helper, field, 0, 60, 0, SnowField.SHELTERED);
        assertDistance(helper, field, 3, 48, 3, SnowField.SHELTERED);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), WHOLE, label);
    }

    /**
     * A field of size 32 with the default capacities, at the position the tests fill it to.
     */
    private static SnowField defaultField(SnowFaces faces) {
        return new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
    }

    /**
     * The predicate that reads the cells of the loaded chunks.
     */
    private static ReadPredicate loadedCells(TestWorld world) {
        return (x, y, z) -> world.isLoaded(x, z);
    }

    /**
     * Sets the column height of every loaded column of the cube, then reads every cell of the cube whose chunk is
     * loaded, in the given order, and completes the reads.
     */
    private static void readAll(SnowField field, TestWorld world, int order) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        for (int x = originX; x < originX + size; x++) {
            for (int z = originZ; z < originZ + size; z++) {
                if (world.isLoaded(x, z)) {
                    field.setColumnHeight(x, z, world.heightAt(x, z));
                }
            }
        }
        if (order == SHUFFLED) {
            int cellCount = size * size * size;
            int[] shuffledCells = new int[cellCount];
            for (int index = 0; index < cellCount; index++) {
                shuffledCells[index] = index;
            }
            Random random = new Random(SHUFFLE_SEED);
            for (int index = cellCount - 1; index > 0; index--) {
                int swapIndex = random.nextInt(index + 1);
                int swap = shuffledCells[index];
                shuffledCells[index] = shuffledCells[swapIndex];
                shuffledCells[swapIndex] = swap;
            }
            for (int cell : shuffledCells) {
                int localX = cell % size;
                int localY = (cell / size) % size;
                int localZ = cell / (size * size);
                readCell(field, world, originX + localX, originY + localY, originZ + localZ);
            }
        } else {
            for (int localX = 0; localX < size; localX++) {
                for (int localZ = 0; localZ < size; localZ++) {
                    for (int step = 0; step < size; step++) {
                        int localY = order == TOP_DOWN ? size - 1 - step : step;
                        readCell(field, world, originX + localX, originY + localY, originZ + localZ);
                    }
                }
            }
        }
        field.readsComplete();
    }

    /**
     * Reads one cell from the world, unless its chunk is unloaded.
     */
    private static void readCell(SnowField field, TestWorld world, int x, int y, int z) {
        if (world.isLoaded(x, z)) {
            field.setCell(x, y, z, world.maskSetAt(x, y, z));
        }
    }

    /**
     * Reads every cell of the cube that is still unread and whose chunk is loaded, with its column height, and
     * completes the reads.
     */
    private static void readUnreadLoadedCells(SnowField field, TestWorld world) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        for (int x = originX; x < originX + size; x++) {
            for (int z = originZ; z < originZ + size; z++) {
                if (!world.isLoaded(x, z)) {
                    continue;
                }
                boolean heightSet = false;
                for (int y = originY; y < originY + size; y++) {
                    if (field.distanceAt(x, y, z) != SnowField.UNREAD) {
                        continue;
                    }
                    if (!heightSet) {
                        field.setColumnHeight(x, z, world.heightAt(x, z));
                        heightSet = true;
                    }
                    field.setCell(x, y, z, world.maskSetAt(x, y, z));
                }
            }
        }
    }

    /**
     * Reads the cells of one chunk that lie in the cube, with their column heights, as a reload of the chunk does.
     */
    private static void readChunk(SnowField field, TestWorld world, int chunkX, int chunkZ) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int firstX = Math.max(originX, chunkX << 4);
        int lastX = Math.min(originX + size - 1, (chunkX << 4) + 15);
        int firstZ = Math.max(originZ, chunkZ << 4);
        int lastZ = Math.min(originZ + size - 1, (chunkZ << 4) + 15);
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                field.setColumnHeight(x, z, world.heightAt(x, z));
            }
        }
        for (int x = firstX; x <= lastX; x++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int y = originY; y < originY + size; y++) {
                    field.setCell(x, y, z, world.maskSetAt(x, y, z));
                }
            }
        }
    }

    /**
     * Processes the field without a budget and checks that the solver is drained.
     */
    private static void drain(GameTestHelper helper, SnowField field) {
        field.process(Integer.MAX_VALUE);
        helper.assertTrue(field.isDrained(), "The solver is not drained: " + field.queuedEntryCount()
                + " queued entries and " + field.spilledCellCount() + " spilled cells remain");
    }

    /**
     * Changes one block in the world and in the field, with its column height, as the game does on a block change.
     */
    private static void edit(SnowField field, TestWorld world, int x, int y, int z, int maskSet) {
        world.set(x, y, z, maskSet);
        if (!world.isLoaded(x, z)) {
            return;
        }
        if (field.contains(x, y, z)) {
            field.setCell(x, y, z, maskSet);
        }
        field.setColumnHeight(x, z, world.heightAt(x, z));
    }

    /**
     * The distances of the cube from an independent Dijkstra over the definition of the solver, indexed by
     * {@link #cellIndex(int, int, int, int)}.
     */
    private static int[] expectedBytes(SnowField field, SnowFaces faces, TestWorld world, ReadPredicate readPredicate) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int cellCount = size * size * size;
        int[] expected = new int[cellCount];
        int[] maskSets = new int[cellCount];
        boolean[] read = new boolean[cellCount];
        for (int localZ = 0; localZ < size; localZ++) {
            for (int localY = 0; localY < size; localY++) {
                for (int localX = 0; localX < size; localX++) {
                    int index = cellIndex(localX, localY, localZ, size);
                    boolean isRead = readPredicate.isRead(originX + localX, originY + localY, originZ + localZ);
                    read[index] = isRead;
                    if (isRead) {
                        expected[index] = SnowField.SHELTERED;
                        maskSets[index] = world.maskSetAt(originX + localX, originY + localY, originZ + localZ);
                    } else {
                        expected[index] = SnowField.UNREAD;
                        maskSets[index] = SnowFaces.FULL_MASK_SET;
                    }
                }
            }
        }
        PriorityQueue<Long> queue = new PriorityQueue<>();
        for (int localZ = 0; localZ < size; localZ++) {
            for (int localX = 0; localX < size; localX++) {
                for (int localY = 0; localY < size; localY++) {
                    int index = cellIndex(localX, localY, localZ, size);
                    if (!read[index]) {
                        continue;
                    }
                    if (localY != size - 1 && read[cellIndex(localX, localY + 1, localZ, size)]) {
                        continue;
                    }
                    int y = originY + localY;
                    if (world.heightAt(originX + localX, originZ + localZ) > y + 1) {
                        continue;
                    }
                    int seedCost = faces.faceCost(maskSets[index], Direction.UP.get3DDataValue(),
                            SnowFaces.EMPTY_MASK_SET);
                    if (seedCost <= SnowField.CAP && seedCost < expected[index]) {
                        expected[index] = seedCost;
                        queue.add(packedEntry(seedCost, index));
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            long entry = queue.poll();
            int key = (int) (entry >>> 32);
            int index = (int) (entry & 0xFFFFFFFFL);
            if (key != expected[index]) {
                continue;
            }
            int localX = index % size;
            int localY = (index / size) % size;
            int localZ = index / (size * size);
            for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
                Direction direction = Direction.from3DDataValue(face);
                int neighborLocalX = localX + direction.getStepX();
                int neighborLocalY = localY + direction.getStepY();
                int neighborLocalZ = localZ + direction.getStepZ();
                if (neighborLocalX < 0 || neighborLocalX >= size || neighborLocalY < 0 || neighborLocalY >= size
                        || neighborLocalZ < 0 || neighborLocalZ >= size) {
                    continue;
                }
                int neighborIndex = cellIndex(neighborLocalX, neighborLocalY, neighborLocalZ, size);
                if (!read[neighborIndex]) {
                    continue;
                }
                int faceCost = faces.faceCost(maskSets[index], face, maskSets[neighborIndex]);
                if (faceCost >= SnowFaces.CLOSED_COST) {
                    continue;
                }
                int candidate = key + moveCost(face) + faceCost;
                if (candidate > SnowField.CAP) {
                    continue;
                }
                if (candidate < expected[neighborIndex]) {
                    expected[neighborIndex] = candidate;
                    queue.add(packedEntry(candidate, neighborIndex));
                }
            }
        }
        return expected;
    }

    /**
     * The index of a cell of the cube in the arrays of the reference, with x fastest and then y and then z.
     */
    private static int cellIndex(int localX, int localY, int localZ, int size) {
        return localX + localY * size + localZ * size * size;
    }

    /**
     * The entry of the reference queue: the key in the high half and the cell index in the low half.
     */
    private static long packedEntry(int key, int index) {
        return ((long) key << 32) | (index & 0xFFFFFFFFL);
    }

    /**
     * The cost of the move across a face, without the cost of the face itself.
     */
    private static int moveCost(int face) {
        if (face == Direction.DOWN.get3DDataValue()) {
            return SnowField.MOVE_DOWN_COST;
        }
        if (face == Direction.UP.get3DDataValue()) {
            return SnowField.MOVE_UP_COST;
        }
        return SnowField.MOVE_SIDEWAYS_COST;
    }

    /**
     * Walks the eye one block at a time, reads the cells that entered, drains as the walk asks, and compares the
     * exact region of the cube against the reference.
     */
    private static void walk(GameTestHelper helper, SnowField field, TestWorld world, SnowFaces faces, int[] eye,
            int stepX, int stepY, int stepZ, int steps, boolean drainEveryStep) {
        String label = "the walk by (" + stepX + ", " + stepY + ", " + stepZ + ")";
        for (int step = 0; step < steps; step++) {
            eye[0] += stepX;
            eye[1] += stepY;
            eye[2] += stepZ;
            boolean refilled = field.moveTo(eye[0], eye[1], eye[2]);
            helper.assertFalse(refilled, "The one block move to (" + eye[0] + ", " + eye[1] + ", " + eye[2]
                    + ") refilled the field");
            readUnreadLoadedCells(field, world);
            field.readsComplete();
            if (drainEveryStep) {
                drain(helper, field);
            }
        }
        readUnreadLoadedCells(field, world);
        field.readsComplete();
        drain(helper, field);
        assertAllRead(helper, field, label);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), EXACT, label);
        helper.assertTrue(field.fillCount() == 1, "After " + label + ", the field counted " + field.fillCount()
                + " fills instead of 1");
    }

    /**
     * One run of the interleaved test: edits, moves, chunk loads and a lagging reader with small processing budgets,
     * then the whole exact region against the reference.
     */
    private static void runInterleaved(GameTestHelper helper, SnowFaces faces, long seed, int queueCapacity,
            int resetCapacity, boolean expectSpills, boolean expectResolves) {
        TestWorld world = buildWalkWorld(faces);
        for (int index = 0; index < START_UNLOADED_CHUNKS.length; index += 2) {
            world.unloadChunk(START_UNLOADED_CHUNKS[index], START_UNLOADED_CHUNKS[index + 1]);
        }
        SnowField field = new SnowField(faces, LARGE_FIELD_SIZE, queueCapacity, resetCapacity);
        field.fill(0, 64, 0);
        readAll(field, world, SHUFFLED);
        Random random = new Random(seed);
        int[] cursor = {0};
        int[] eye = {0, 64, 0};
        for (int iteration = 0; iteration < INTERLEAVED_ITERATIONS; iteration++) {
            int choice = random.nextInt(100);
            if (choice < INTERLEAVED_EDIT_LIMIT) {
                int x = eye[0] + random.nextInt(25) - 12;
                int z = eye[2] + random.nextInt(25) - 12;
                int y = 61 + random.nextInt(10);
                edit(field, world, x, y, z, randomPaletteMaskSet(random, faces));
            } else if (choice < INTERLEAVED_MOVE_LIMIT) {
                moveEye(helper, field, eye, random);
            } else if (choice < INTERLEAVED_CHUNK_LIMIT) {
                changeAChunk(field, world, eye, random, faces);
            }
            readCursorCells(field, world, cursor);
            field.process(1 + random.nextInt(7));
        }
        readUnreadLoadedCells(field, world);
        field.readsComplete();
        drain(helper, field);
        assertMatches(helper, field, expectedBytes(field, faces, world, loadedCells(world)), EXACT,
                "the interleaved run with a queue capacity of " + queueCapacity + " and a reset capacity of "
                        + resetCapacity);
        if (expectSpills) {
            helper.assertTrue(field.spillCount() > 0, "The run with a queue capacity of " + queueCapacity
                    + " spilled " + field.spillCount() + " times instead of more than 0");
        }
        if (expectResolves) {
            helper.assertTrue(field.resolveAllCount() > 0, "The run with a reset capacity of " + resetCapacity
                    + " resolved everything " + field.resolveAllCount() + " times instead of more than 0");
        }
    }

    /**
     * Moves the eye by one block, along a random horizontal direction mostly and along y while it stays in the range.
     */
    private static void moveEye(GameTestHelper helper, SnowField field, int[] eye, Random random) {
        int axis = random.nextInt(3);
        int sign = random.nextBoolean() ? 1 : -1;
        int nextX = eye[0];
        int nextY = eye[1];
        int nextZ = eye[2];
        if (axis == 0) {
            nextX += sign;
        } else if (axis == 1) {
            nextZ += sign;
        } else {
            int candidateY = eye[1] + sign;
            if (candidateY < EYE_LOWEST_Y || candidateY > EYE_HIGHEST_Y) {
                candidateY = eye[1] - sign;
            }
            nextY = candidateY;
        }
        boolean refilled = field.moveTo(nextX, nextY, nextZ);
        helper.assertFalse(refilled, "The one block move to (" + nextX + ", " + nextY + ", " + nextZ
                + ") refilled the field");
        eye[0] = nextX;
        eye[1] = nextY;
        eye[2] = nextZ;
    }

    /**
     * Loads one of the unloaded chunks, or reloads a loaded chunk near the eye after changing three of its blocks in
     * the world only.
     */
    private static void changeAChunk(SnowField field, TestWorld world, int[] eye, Random random, SnowFaces faces) {
        int unloadedIndex = -1;
        for (int index = 0; index < START_UNLOADED_CHUNKS.length; index += 2) {
            if (!world.isChunkLoaded(START_UNLOADED_CHUNKS[index], START_UNLOADED_CHUNKS[index + 1])) {
                unloadedIndex = index;
                break;
            }
        }
        if (unloadedIndex >= 0 && random.nextBoolean()) {
            int chunkX = START_UNLOADED_CHUNKS[unloadedIndex];
            int chunkZ = START_UNLOADED_CHUNKS[unloadedIndex + 1];
            world.loadChunk(chunkX, chunkZ);
            readChunk(field, world, chunkX, chunkZ);
            return;
        }
        int chunkX = (eye[0] >> 4) + random.nextInt(3) - 1;
        int chunkZ = (eye[2] >> 4) + random.nextInt(3) - 1;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        for (int change = 0; change < CHUNK_CHANGE_COUNT; change++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            int y = 61 + random.nextInt(10);
            world.set(x, y, z, randomPaletteMaskSet(random, faces));
        }
        readChunk(field, world, chunkX, chunkZ);
    }

    /**
     * Reads up to the lagging read count of unread cells of loaded chunks, scanning the cube from the cursor on.
     */
    private static void readCursorCells(SnowField field, TestWorld world, int[] cursor) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int cellCount = size * size * size;
        int readCount = 0;
        int scannedCount = 0;
        while (readCount < INTERLEAVED_MAXIMUM_LAG_READS && scannedCount < cellCount) {
            int index = cursor[0];
            cursor[0] = (cursor[0] + 1) % cellCount;
            scannedCount++;
            int localX = index % size;
            int localY = (index / size) % size;
            int localZ = index / (size * size);
            int x = originX + localX;
            int y = originY + localY;
            int z = originZ + localZ;
            if (!world.isLoaded(x, z) || field.distanceAt(x, y, z) != SnowField.UNREAD) {
                continue;
            }
            field.setColumnHeight(x, z, world.heightAt(x, z));
            field.setCell(x, y, z, world.maskSetAt(x, y, z));
            readCount++;
        }
    }

    /**
     * The warm operations whose allocation is measured: a block change and its undo, a move into the open ground and
     * back, and a few distances.
     */
    private static long warmOperations(SnowField field, int[] enteringMaskSets) {
        long sum = 0;
        for (int iteration = 0; iteration < WARM_ITERATIONS; iteration++) {
            field.setCell(0, 65, 0, SnowFaces.FULL_MASK_SET);
            field.setColumnHeight(0, 0, GROUND_TOP + 1);
            field.process(Integer.MAX_VALUE);
            field.setCell(0, 65, 0, SnowFaces.EMPTY_MASK_SET);
            field.setColumnHeight(0, 0, GROUND_TOP + 1);
            field.process(Integer.MAX_VALUE);
            field.moveTo(1, 64, 0);
            readSlabCells(field, field.originX() + field.size() - 1, enteringMaskSets);
            field.readsComplete();
            field.process(Integer.MAX_VALUE);
            field.moveTo(0, 64, 0);
            readSlabCells(field, field.originX(), enteringMaskSets);
            field.readsComplete();
            field.process(Integer.MAX_VALUE);
            sum += field.distanceAt(0, 61, 0);
            sum += field.distanceAt(0, 65, 0);
            sum += field.distanceAt(3, 61, 3);
            sum += field.distanceAt(0, 79, 0);
        }
        return sum;
    }

    /**
     * Reads one slab of cells along x from the stored mask ids, with the column height of plain open ground.
     */
    private static void readSlabCells(SnowField field, int x, int[] enteringMaskSets) {
        int size = field.size();
        int originY = field.originY();
        int originZ = field.originZ();
        for (int localZ = 0; localZ < size; localZ++) {
            field.setColumnHeight(x, originZ + localZ, GROUND_TOP + 1);
        }
        for (int localY = 0; localY < size; localY++) {
            for (int localZ = 0; localZ < size; localZ++) {
                field.setCell(x, originY + localY, originZ + localZ, enteringMaskSets[localY * size + localZ]);
            }
        }
    }

    /**
     * The mask ids of a slab of plain open ground: stone up to the ground top and air above, with a height of one above
     * the ground top.
     */
    private static int[] openGroundSlabMaskSets(int size, int originY) {
        int[] maskSets = new int[size * size];
        for (int localY = 0; localY < size; localY++) {
            for (int localZ = 0; localZ < size; localZ++) {
                int y = originY + localY;
                maskSets[localY * size + localZ] = y <= GROUND_TOP ? SnowFaces.FULL_MASK_SET
                        : SnowFaces.EMPTY_MASK_SET;
            }
        }
        return maskSets;
    }

    /**
     * Compares the cells of a region of the cube with the distances of the reference.
     */
    private static void assertMatches(GameTestHelper helper, SnowField field, int[] expected, int region, String label) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int eyeX = originX + size / 2;
        int eyeZ = originZ + size / 2;
        int mismatchCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int localZ = 0; localZ < size; localZ++) {
            for (int localY = 0; localY < size; localY++) {
                for (int localX = 0; localX < size; localX++) {
                    int x = originX + localX;
                    int y = originY + localY;
                    int z = originZ + localZ;
                    if (!isInRegion(region, x, y, z, eyeX, eyeZ, originY, size)) {
                        continue;
                    }
                    int actual = field.distanceAt(x, y, z);
                    int wanted = expected[cellIndex(localX, localY, localZ, size)];
                    if (actual == wanted) {
                        continue;
                    }
                    mismatchCount++;
                    if (mismatchCount <= 5) {
                        mismatches.append(" (").append(x).append(", ").append(y).append(", ").append(z)
                                .append(") ").append(actual).append("/").append(wanted);
                    }
                }
            }
        }
        helper.assertTrue(mismatchCount == 0, "In the " + regionName(region) + " region after " + label + ", "
                + mismatchCount + " cells differ from the reference Dijkstra, actual/expected:" + mismatches);
    }

    /**
     * Whether a cell belongs to the compared region.
     */
    private static boolean isInRegion(int region, int x, int y, int z, int eyeX, int eyeZ, int originY, int size) {
        if (region == WHOLE) {
            return true;
        }
        int horizontalReach = size / 2 - EXACT_MARGIN;
        return Math.abs(x - eyeX) <= horizontalReach && Math.abs(z - eyeZ) <= horizontalReach
                && y >= originY + EXACT_LOWEST_LAYER;
    }

    /**
     * The name of a compared region, for the failure messages.
     */
    private static String regionName(int region) {
        return region == WHOLE ? "whole cube" : "exact";
    }

    /**
     * Checks the distance of one cell against the expected one.
     */
    private static void assertDistance(GameTestHelper helper, SnowField field, int x, int y, int z, int expected) {
        int actual = field.distanceAt(x, y, z);
        helper.assertTrue(actual == expected, "The distance at (" + x + ", " + y + ", " + z + ") is " + actual
                + " instead of " + expected);
    }

    /**
     * Checks every cell of a box against UNREAD.
     */
    private static void assertAllUnread(GameTestHelper helper, SnowField field, int minimumX, int minimumY,
            int minimumZ, int maximumX, int maximumY, int maximumZ, String label) {
        int readCount = 0;
        String firstReadCell = "";
        for (int x = minimumX; x <= maximumX; x++) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    int distance = field.distanceAt(x, y, z);
                    if (distance != SnowField.UNREAD) {
                        readCount++;
                        if (readCount == 1) {
                            firstReadCell = "(" + x + ", " + y + ", " + z + ") is " + distance;
                        }
                    }
                }
            }
        }
        helper.assertTrue(readCount == 0, "In " + label + ", " + readCount + " cells are not UNREAD, the first one "
                + "being " + firstReadCell);
    }

    /**
     * Checks that every cell of the cube has been read.
     */
    private static void assertAllRead(GameTestHelper helper, SnowField field, String label) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int unreadCount = 0;
        String firstUnreadCell = "";
        for (int x = originX; x < originX + size; x++) {
            for (int y = originY; y < originY + size; y++) {
                for (int z = originZ; z < originZ + size; z++) {
                    if (field.distanceAt(x, y, z) != SnowField.UNREAD) {
                        continue;
                    }
                    unreadCount++;
                    if (unreadCount == 1) {
                        firstUnreadCell = "(" + x + ", " + y + ", " + z + ")";
                    }
                }
            }
        }
        helper.assertTrue(unreadCount == 0, "After " + label + ", " + unreadCount + " cells are still UNREAD, the "
                + "first one being " + firstUnreadCell);
    }

    /**
     * Checks the solved box against its expected corners.
     */
    private static void assertSolvedBox(GameTestHelper helper, SnowField field, int minimumX, int minimumY,
            int minimumZ, int maximumX, int maximumY, int maximumZ, String label) {
        helper.assertTrue(field.hasSolvedBox(), "The solved box is empty after " + label);
        String actualBox = field.solvedMinX() + ".." + field.solvedMaxX() + ", " + field.solvedMinY() + ".."
                + field.solvedMaxY() + ", " + field.solvedMinZ() + ".." + field.solvedMaxZ();
        String expectedBox = minimumX + ".." + maximumX + ", " + minimumY + ".." + maximumY + ", " + minimumZ + ".."
                + maximumZ;
        boolean matches = field.solvedMinX() == minimumX && field.solvedMaxX() == maximumX
                && field.solvedMinY() == minimumY && field.solvedMaxY() == maximumY
                && field.solvedMinZ() == minimumZ && field.solvedMaxZ() == maximumZ;
        helper.assertTrue(matches, "The solved box after " + label + " is " + actualBox + " instead of "
                + expectedBox);
    }

    /**
     * A stone roof at y 64 over x and z 0 to 4, the porch of the sideways test.
     */
    private static void placePorch(TestWorld world, int stoneMaskSet, int offsetX, int offsetY, int offsetZ) {
        world.fillBox(offsetX, offsetY + 64, offsetZ, offsetX + 4, offsetY + 64, offsetZ + 4, stoneMaskSet);
    }

    /**
     * A roof of leaves at y 65 over x and z -5 to 5.
     */
    private static void placeCanopy(TestWorld world, int leavesMaskSet, int offsetX, int offsetY, int offsetZ) {
        world.fillBox(offsetX - 5, offsetY + 65, offsetZ - 5, offsetX + 5, offsetY + 65, offsetZ + 5, leavesMaskSet);
    }

    /**
     * A glass shell at y 61 to 65 over x and z -3 to 3, hollow inside at y 61 to 64.
     */
    private static void placeGlassHouse(TestWorld world, int glassMaskSet, int airMaskSet, int offsetX, int offsetY,
            int offsetZ) {
        world.fillBox(offsetX - 3, offsetY + 61, offsetZ - 3, offsetX + 3, offsetY + 65, offsetZ + 3, glassMaskSet);
        world.fillBox(offsetX - 2, offsetY + 61, offsetZ - 2, offsetX + 2, offsetY + 64, offsetZ + 2, airMaskSet);
    }

    /**
     * A stone shell at y 61 to 65 over x and z -4 to 4, hollow inside at y 61 to 64, with a doorway two blocks high
     * at the middle of its north wall.
     */
    private static void placeHouseWithDoorway(TestWorld world, int stoneMaskSet, int airMaskSet, int offsetX,
            int offsetY, int offsetZ) {
        world.fillBox(offsetX - 4, offsetY + 61, offsetZ - 4, offsetX + 4, offsetY + 65, offsetZ + 4, stoneMaskSet);
        world.fillBox(offsetX - 3, offsetY + 61, offsetZ - 3, offsetX + 3, offsetY + 64, offsetZ + 3, airMaskSet);
        world.set(offsetX, offsetY + 61, offsetZ - 4, airMaskSet);
        world.set(offsetX, offsetY + 62, offsetZ - 4, airMaskSet);
    }

    /**
     * A stone box at y 61 to 66 over x and z -4 to 4, hollow inside at y 61 to 63, with a hole of air one block wide
     * through the ceiling at y 64 to 66.
     */
    private static void placeThickCeilingRoom(TestWorld world, int stoneMaskSet, int airMaskSet, int offsetX,
            int offsetY, int offsetZ) {
        world.fillBox(offsetX - 4, offsetY + 61, offsetZ - 4, offsetX + 4, offsetY + 66, offsetZ + 4, stoneMaskSet);
        world.fillBox(offsetX - 3, offsetY + 61, offsetZ - 3, offsetX + 3, offsetY + 63, offsetZ + 3, airMaskSet);
        world.fillBox(offsetX, offsetY + 64, offsetZ, offsetX, offsetY + 66, offsetZ, airMaskSet);
    }

    /**
     * A stone box at y 61 to 70 over x -12 to -6 and z -3 to 3 with a shaft of air one block wide at x -9 and z 0,
     * open to the sky.
     */
    private static void placeOpenShaft(TestWorld world, int stoneMaskSet, int airMaskSet, int offsetX, int offsetY,
            int offsetZ) {
        world.fillBox(offsetX - 12, offsetY + 61, offsetZ - 3, offsetX - 6, offsetY + 70, offsetZ + 3, stoneMaskSet);
        world.fillBox(offsetX - 9, offsetY + 61, offsetZ, offsetX - 9, offsetY + 70, offsetZ, airMaskSet);
    }

    /**
     * A stone box at y 61 to 70 over x 3 to 9 and z -3 to 3 with a shaft of air one block wide at x 6 and z 0 that
     * stops below the stone at y 70, and a tunnel of air at y 69 that opens into the open air at x 10.
     */
    private static void placeRoofedShaft(TestWorld world, int stoneMaskSet, int airMaskSet, int offsetX, int offsetY,
            int offsetZ) {
        world.fillBox(offsetX + 3, offsetY + 61, offsetZ - 3, offsetX + 9, offsetY + 70, offsetZ + 3, stoneMaskSet);
        world.fillBox(offsetX + 6, offsetY + 61, offsetZ, offsetX + 6, offsetY + 69, offsetZ, airMaskSet);
        world.fillBox(offsetX + 7, offsetY + 69, offsetZ, offsetX + 9, offsetY + 69, offsetZ, airMaskSet);
    }

    /**
     * A small porch of the walk world: a stone roof at y 64 over a box of 5 by 5 blocks.
     */
    private static void placeSmallPorch(TestWorld world, int stoneMaskSet, int centerX, int centerZ) {
        world.fillBox(centerX - 2, 64, centerZ - 2, centerX + 2, 64, centerZ + 2, stoneMaskSet);
    }

    /**
     * A small canopy of the walk world: leaves at y 65 over a box of 5 by 5 blocks.
     */
    private static void placeSmallCanopy(TestWorld world, int leavesMaskSet, int centerX, int centerZ) {
        world.fillBox(centerX - 2, 65, centerZ - 2, centerX + 2, 65, centerZ + 2, leavesMaskSet);
    }

    /**
     * A small glass hut of the walk world: a shell at y 61 to 64 over a box of 5 by 5 blocks, hollow inside.
     */
    private static void placeSmallGlassHut(TestWorld world, int glassMaskSet, int airMaskSet, int centerX,
            int centerZ) {
        world.fillBox(centerX - 2, 61, centerZ - 2, centerX + 2, 64, centerZ + 2, glassMaskSet);
        world.fillBox(centerX - 1, 61, centerZ - 1, centerX + 1, 63, centerZ + 1, airMaskSet);
    }

    /**
     * A small stone hut of the walk world: a shell at y 61 to 64 over a box of 5 by 5 blocks, hollow inside, with a
     * doorway two blocks high in its north wall.
     */
    private static void placeSmallStoneHut(TestWorld world, int stoneMaskSet, int airMaskSet, int centerX,
            int centerZ) {
        world.fillBox(centerX - 2, 61, centerZ - 2, centerX + 2, 64, centerZ + 2, stoneMaskSet);
        world.fillBox(centerX - 1, 61, centerZ - 1, centerX + 1, 63, centerZ + 1, airMaskSet);
        world.set(centerX, 61, centerZ - 2, airMaskSet);
        world.set(centerX, 62, centerZ - 2, airMaskSet);
    }

    /**
     * A small wall of the walk world: a row of five panes three blocks high along x.
     */
    private static void placePaneWall(TestWorld world, int paneMaskSet, int centerX, int centerZ) {
        world.fillBox(centerX - 2, 61, centerZ, centerX + 2, 63, centerZ, paneMaskSet);
    }

    /**
     * A small ring of the walk world: fences at y 61 around a box of 5 by 5 blocks, each connected to the ring
     * neighbors beside it.
     */
    private static void placeFenceRing(TestWorld world, SnowFaces faces, int centerX, int centerZ) {
        int minimumX = centerX - 2;
        int minimumZ = centerZ - 2;
        int maximumX = centerX + 2;
        int maximumZ = centerZ + 2;
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                if (!isRingCell(minimumX, minimumZ, maximumX, maximumZ, x, z)) {
                    continue;
                }
                BlockState state = fence(isRingCell(minimumX, minimumZ, maximumX, maximumZ, x, z - 1),
                        isRingCell(minimumX, minimumZ, maximumX, maximumZ, x + 1, z),
                        isRingCell(minimumX, minimumZ, maximumX, maximumZ, x, z + 1),
                        isRingCell(minimumX, minimumZ, maximumX, maximumZ, x - 1, z));
                world.set(x, 61, z, faces.maskSetOf(state));
            }
        }
    }

    /**
     * Whether a cell is on the border of the given box.
     */
    private static boolean isRingCell(int minimumX, int minimumZ, int maximumX, int maximumZ, int x, int z) {
        if (x < minimumX || x > maximumX || z < minimumZ || z > maximumZ) {
            return false;
        }
        return x == minimumX || x == maximumX || z == minimumZ || z == maximumZ;
    }

    /**
     * The world of the walk tests: a feature on every cell of a grid of 10 blocks, chosen by the hash of the cell.
     */
    private static TestWorld buildWalkWorld(SnowFaces faces) {
        TestWorld world = new TestWorld(GROUND_TOP);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int leavesMaskSet = faces.maskSetOf(Blocks.OAK_LEAVES.defaultBlockState());
        int glassMaskSet = faces.maskSetOf(Blocks.GLASS.defaultBlockState());
        int paneMaskSet = faces.maskSetOf(pane(false, true, false, true));
        int airMaskSet = SnowFaces.EMPTY_MASK_SET;
        for (int gridX = WALK_FEATURE_LOWEST; gridX <= WALK_FEATURE_HIGHEST; gridX += WALK_FEATURE_STEP) {
            for (int gridZ = WALK_FEATURE_LOWEST; gridZ <= WALK_FEATURE_HIGHEST; gridZ += WALK_FEATURE_STEP) {
                int kind = Math.floorMod(featureHash(gridX, gridZ), WALK_FEATURE_KIND_COUNT);
                switch (kind) {
                    case 1 -> placeSmallPorch(world, stoneMaskSet, gridX, gridZ);
                    case 2 -> placeSmallCanopy(world, leavesMaskSet, gridX, gridZ);
                    case 3 -> placeSmallGlassHut(world, glassMaskSet, airMaskSet, gridX, gridZ);
                    case 4 -> placeSmallStoneHut(world, stoneMaskSet, airMaskSet, gridX, gridZ);
                    case 5 -> placePaneWall(world, paneMaskSet, gridX, gridZ);
                    case 6 -> placeFenceRing(world, faces, gridX, gridZ);
                    default -> {
                    }
                }
            }
        }
        return world;
    }

    /**
     * The hash of a cell of the grid of the walk world's features.
     */
    private static int featureHash(int gridX, int gridZ) {
        int hash = gridX * 7349 + gridZ * 104729 + 17;
        hash ^= hash >>> 15;
        hash *= 0x2545F491;
        hash ^= hash >>> 13;
        return hash;
    }

    /**
     * The mask set of one state of the random edit palette, with air and stone drawn more often than the rest.
     */
    private static int randomPaletteMaskSet(Random random, SnowFaces faces) {
        int kind = random.nextInt(PALETTE_WEIGHT);
        if (kind < PALETTE_AIR_WEIGHT) {
            return SnowFaces.EMPTY_MASK_SET;
        }
        if (kind < PALETTE_AIR_WEIGHT + PALETTE_STONE_WEIGHT) {
            return SnowFaces.FULL_MASK_SET;
        }
        return switch (kind - PALETTE_AIR_WEIGHT - PALETTE_STONE_WEIGHT) {
            case 0 -> faces.maskSetOf(Blocks.GLASS.defaultBlockState());
            case 1 -> faces.maskSetOf(pane(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean()));
            case 2 -> faces.maskSetOf(fence(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean()));
            case 3 -> faces.maskSetOf(oakSlab(SlabType.BOTTOM));
            case 4 -> faces.maskSetOf(oakSlab(SlabType.TOP));
            case 5 -> faces.maskSetOf(oakDoor(HORIZONTAL_DIRECTIONS[random.nextInt(4)], random.nextBoolean(),
                    random.nextBoolean() ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
            case 6 -> faces.maskSetOf(Blocks.OAK_LEAVES.defaultBlockState());
            case 7 -> faces.maskSetOf(oakStairs(HORIZONTAL_DIRECTIONS[random.nextInt(4)],
                    random.nextBoolean() ? Half.BOTTOM : Half.TOP));
            case 8 -> faces.maskSetOf(snowLayer(1));
            case 9 -> faces.maskSetOf(snowLayer(2));
            case 10 -> faces.maskSetOf(snowLayer(7));
            default -> faces.maskSetOf(Blocks.WHITE_CARPET.defaultBlockState());
        };
    }

    /**
     * A glass pane with the given connections and without water.
     */
    private static BlockState pane(boolean north, boolean east, boolean south, boolean west) {
        return Blocks.GLASS_PANE.defaultBlockState()
                .setValue(CrossCollisionBlock.NORTH, north)
                .setValue(CrossCollisionBlock.EAST, east)
                .setValue(CrossCollisionBlock.SOUTH, south)
                .setValue(CrossCollisionBlock.WEST, west)
                .setValue(CrossCollisionBlock.WATERLOGGED, false);
    }

    /**
     * An oak fence with the given connections and without water.
     */
    private static BlockState fence(boolean north, boolean east, boolean south, boolean west) {
        return Blocks.OAK_FENCE.defaultBlockState()
                .setValue(CrossCollisionBlock.NORTH, north)
                .setValue(CrossCollisionBlock.EAST, east)
                .setValue(CrossCollisionBlock.SOUTH, south)
                .setValue(CrossCollisionBlock.WEST, west)
                .setValue(CrossCollisionBlock.WATERLOGGED, false);
    }

    /**
     * An unpowered oak door with the given facing, open state, hinge and half without water.
     */
    private static BlockState oakDoor(Direction facing, boolean open, DoorHingeSide hinge, DoubleBlockHalf half) {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, half)
                .setValue(DoorBlock.POWERED, false);
    }

    /**
     * An oak slab of the given type.
     */
    private static BlockState oakSlab(SlabType type) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
    }

    /**
     * An oak stair block with the given facing and half, in the straight shape.
     */
    private static BlockState oakStairs(Direction facing, Half half) {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, half)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    /**
     * A snow layer block of the given layer count.
     */
    private static BlockState snowLayer(int layers) {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    /**
     * The predicate that tells the reference which cells are read.
     */
    @FunctionalInterface
    private interface ReadPredicate {
        boolean isRead(int x, int y, int z);
    }

    /**
     * A world of mask set ids: stone up to a given height with air above, boxes of one mask set on top of that, column
     * heights cached per column, and chunks that may be unloaded.
     */
    private static final class TestWorld {
        private final int groundTop;
        private final HashMap<Long, Integer> maskSetOverrides = new HashMap<>();
        private final HashMap<Long, Integer> columnHeights = new HashMap<>();
        /**
         * The unloaded chunks as x and z pairs, kept as plain arrays so that the loaded test of every scanned cell of
         * the interleaved test allocates nothing.
         */
        private final int[] unloadedChunkXs = new int[MAXIMUM_UNLOADED_CHUNKS];
        private final int[] unloadedChunkZs = new int[MAXIMUM_UNLOADED_CHUNKS];
        private int unloadedChunkCount;
        /**
         * The highest y of a cell that holds a mask set other than air, which bounds the column height scan from above.
         */
        private int highestBlockY = WORLD_BOTTOM;

        private TestWorld(int groundTop) {
            this.groundTop = groundTop;
        }

        private void set(int x, int y, int z, int maskSet) {
            maskSetOverrides.put(BlockPos.asLong(x, y, z), maskSet);
            if (maskSet != SnowFaces.EMPTY_MASK_SET && y > highestBlockY) {
                highestBlockY = y;
            }
            columnHeights.remove(columnKey(x, z));
        }

        private void fillBox(int minimumX, int minimumY, int minimumZ, int maximumX, int maximumY, int maximumZ,
                int maskSet) {
            for (int x = minimumX; x <= maximumX; x++) {
                for (int y = minimumY; y <= maximumY; y++) {
                    for (int z = minimumZ; z <= maximumZ; z++) {
                        set(x, y, z, maskSet);
                    }
                }
            }
        }

        private int maskSetAt(int x, int y, int z) {
            Integer override = maskSetOverrides.get(BlockPos.asLong(x, y, z));
            if (override != null) {
                return override;
            }
            return y <= groundTop ? SnowFaces.FULL_MASK_SET : SnowFaces.EMPTY_MASK_SET;
        }

        private int heightAt(int x, int z) {
            long key = columnKey(x, z);
            Integer cachedHeight = columnHeights.get(key);
            if (cachedHeight != null) {
                return cachedHeight;
            }
            int height = WORLD_BOTTOM;
            int scanTop = Math.min(WORLD_TOP, Math.max(groundTop, highestBlockY));
            for (int y = scanTop; y >= WORLD_BOTTOM; y--) {
                if (maskSetAt(x, y, z) != SnowFaces.EMPTY_MASK_SET) {
                    height = y + 1;
                    break;
                }
            }
            columnHeights.put(key, height);
            return height;
        }

        private boolean isLoaded(int x, int z) {
            return isChunkLoaded(x >> 4, z >> 4);
        }

        private boolean isChunkLoaded(int chunkX, int chunkZ) {
            for (int index = 0; index < unloadedChunkCount; index++) {
                if (unloadedChunkXs[index] == chunkX && unloadedChunkZs[index] == chunkZ) {
                    return false;
                }
            }
            return true;
        }

        private void unloadChunk(int chunkX, int chunkZ) {
            if (!isChunkLoaded(chunkX, chunkZ) || unloadedChunkCount >= MAXIMUM_UNLOADED_CHUNKS) {
                return;
            }
            unloadedChunkXs[unloadedChunkCount] = chunkX;
            unloadedChunkZs[unloadedChunkCount] = chunkZ;
            unloadedChunkCount++;
        }

        private void loadChunk(int chunkX, int chunkZ) {
            for (int index = 0; index < unloadedChunkCount; index++) {
                if (unloadedChunkXs[index] == chunkX && unloadedChunkZs[index] == chunkZ) {
                    unloadedChunkCount--;
                    unloadedChunkXs[index] = unloadedChunkXs[unloadedChunkCount];
                    unloadedChunkZs[index] = unloadedChunkZs[unloadedChunkCount];
                    return;
                }
            }
        }

        private static long columnKey(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
