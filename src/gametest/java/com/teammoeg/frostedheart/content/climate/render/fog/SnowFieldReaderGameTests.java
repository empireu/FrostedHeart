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
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
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
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks reading the world into the snow field: that the masks of a section follow its palette through the four palette
 * kinds, growth in place and a replaced palette, that a fill reads every cell of the cube and the cells below and above
 * the world, that a move reads only the cells that enter, that lagging reads catch up, that a read never uses more
 * steps than its budget, that a block change applies at once whether the cell waits or not, that a chunk sent again is
 * read again, that a chunk that is not loaded stays unread until it loads, that a jump becomes a fill, that a full box
 * ring requeues the cube, and that the warm reads allocate nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFieldReaderGameTests {
    private static final int STANDARD_FIELD_SIZE = 32;
    private static final int LARGE_FIELD_SIZE = 64;
    private static final int READ_STEP = 4096;
    private static final int MAXIMUM_READ_CALLS = 10000;
    private static final int SMALL_STEP_READ_CALLS = 20000;
    private static final int ONE_STEP_BUDGET = 1;
    private static final int SEVEN_STEP_BUDGET = 7;
    private static final int LAGGING_READ_BUDGET = 500;
    private static final int LAGGING_SOLVER_BUDGET = 300;
    private static final int LAGGING_WALK_LENGTH = 30;
    private static final int SECTION_CELL_COUNT = 4096;
    private static final int SECTION_AXIS_MASK = 15;
    private static final int SECTION_AXIS_SHIFT = 4;
    private static final int SECTION_WIDTH = 16;
    private static final int LINEAR_STATE_COUNT = 10;
    private static final int HASHMAP_STATE_COUNT = 100;
    private static final int GLOBAL_STATE_COUNT = 300;
    private static final int MAXIMUM_GROWTH_STATES = 64;
    private static final int OVERFLOW_FOLLOW_STEPS = 70;
    private static final int JUMP_FOLLOW_STEPS = 16;
    private static final int LONG_JUMP_FOLLOW_STEPS = 17;
    private static final int REPORTED_MISMATCHES = 5;
    /**
     * The margin that the comparison with a fresh solve keeps from the cube's sides, and the layer it keeps from the
     * cube's bottom, as SnowFieldGameTests keeps them.
     */
    private static final int EXACT_MARGIN = 25;
    private static final int EXACT_LOWEST_LAYER = 13;
    private static final int ALLOCATION_READS = 20;
    private static final int ALLOCATION_CACHED_REACH = 48;
    private static final int ALLOCATION_WARM_STEPS = 5;
    private static final int ALLOCATION_MEASURED_STEPS = 5;
    private static final long FAILED_ALLOCATION_SUM = Long.MIN_VALUE;
    /**
     * The steps of the move test: the x, y and z of a step and how many times it repeats.
     */
    private static final int[][] WALK_STEPS = {{1, 0, 0, 12}, {0, 0, 2, 6}, {0, 3, 0, 4}, {0, -3, 0, 4},
            {-1, 0, 1, 10}};

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sectionMasksMatchEveryPaletteKind(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        LevelChunkSection freshSection = newSection(helper);
        SnowSectionMasks freshMasks = new SnowSectionMasks(faces);
        freshMasks.load(freshSection);
        helper.assertTrue(freshSection.getStates().data.palette() instanceof SingleValuePalette, "A fresh section has "
                + "the palette " + paletteName(freshSection) + " instead of a SingleValuePalette");
        int nonEmptyCount = 0;
        int firstNonEmptyCell = -1;
        for (int index = 0; index < SECTION_CELL_COUNT; index++) {
            int maskSet = freshMasks.maskSetAt(localXOf(index), localYOf(index), localZOf(index));
            if (maskSet == SnowFaces.EMPTY_MASK_SET) {
                continue;
            }
            nonEmptyCount++;
            if (firstNonEmptyCell < 0) {
                firstNonEmptyCell = index;
            }
        }
        helper.assertTrue(nonEmptyCount == 0, "The fresh section has " + nonEmptyCount + " cells that are not "
                + SnowFaces.EMPTY_MASK_SET + ", the first one being the cell " + firstNonEmptyCell);
        assertSectionMatches(helper, freshSection, freshMasks, faces, "the fresh section");
        assertSectionOfStates(helper, faces, LINEAR_STATE_COUNT, "LinearPalette", LinearPalette.class);
        assertSectionOfStates(helper, faces, HASHMAP_STATE_COUNT, "HashMapPalette", HashMapPalette.class);
        assertSectionOfStates(helper, faces, GLOBAL_STATE_COUNT, "GlobalPalette", GlobalPalette.class);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sectionMasksFollowAPaletteThatGrowsOrIsReplaced(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        LevelChunkSection section = newSection(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        setSectionCell(section, 0, stone);
        setSectionCell(section, 1, glass);
        helper.assertTrue(section.getStates().data.palette() instanceof LinearPalette, "The section of 3 states has "
                + "the palette " + paletteName(section) + " instead of a LinearPalette");
        PalettedContainer.Data<BlockState> loadedData = section.getStates().data;
        SnowSectionMasks masks = new SnowSectionMasks(faces);
        masks.load(section);
        helper.assertTrue(masks.maskSetAt(localXOf(0), localYOf(0), localZOf(0)) == faces.maskSetOf(stone),
                "The loaded section does not give the cell of stone the mask set " + faces.maskSetOf(stone)
                        + " but the " + masks.maskSetAt(localXOf(0), localYOf(0), localZOf(0)));
        assertSectionMatches(helper, section, masks, faces, "the section of 3 states");
        setSectionCell(section, 2, leaves);
        helper.assertTrue(section.getStates().data == loadedData, "The state that the linear palette appends in place "
                + "replaced the palette data object of the section");
        int leavesMaskSet = masks.maskSetAt(localXOf(2), localYOf(2), localZOf(2));
        helper.assertTrue(leavesMaskSet == faces.maskSetOf(leaves), "Without a new load, the cell of the fourth state "
                + "has the mask set " + leavesMaskSet + " instead of the " + faces.maskSetOf(leaves) + " of " + leaves);
        assertSectionMatches(helper, section, masks, faces, "the section of 4 states");
        List<BlockState> wireStates = Blocks.REDSTONE_WIRE.getStateDefinition().getPossibleStates();
        int growthStateCount = 0;
        while (!(section.getStates().data.palette() instanceof HashMapPalette)
                && growthStateCount < MAXIMUM_GROWTH_STATES) {
            setSectionCell(section, 4 + growthStateCount, wireStates.get(growthStateCount));
            growthStateCount++;
        }
        helper.assertTrue(section.getStates().data.palette() instanceof HashMapPalette, "After " + growthStateCount
                + " more states, the section has the palette " + paletteName(section) + " instead of a "
                + "HashMapPalette");
        helper.assertTrue(section.getStates().data != loadedData, "The section kept its palette data object when its "
                + "palette became a HashMapPalette");
        int grownMaskSet = masks.maskSetAt(localXOf(4), localYOf(4), localZOf(4));
        helper.assertTrue(grownMaskSet == faces.maskSetOf(wireStates.get(0)), "Without a new load, the first cell of "
                + "the grown palette has the mask set " + grownMaskSet + " instead of the "
                + faces.maskSetOf(wireStates.get(0)) + " of " + wireStates.get(0));
        assertSectionMatches(helper, section, masks, faces, "the section with a HashMapPalette");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fillReadsEveryLoadedCellOfTheCube(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        int size = SnowField.DEFAULT_SIZE;
        SnowField field = new SnowField(faces, size, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        reader.fill(eye.getX(), eye.getY(), eye.getZ());
        readAll(helper, reader, lookup, "the fill at size " + size);
        assertMatchesWorld(helper, field, faces, lookup, "the fill at size " + size);
        drain(helper, field);
        helper.assertTrue(field.hasSolvedBox(), "The solved box is empty after the reads of the fill at size " + size
                + " are complete");
        helper.assertTrue(field.solvedMinX() == field.originX() && field.solvedMaxX() == field.originX() + size - 1
                        && field.solvedMinY() == field.originY() && field.solvedMaxY() == field.originY() + size - 1
                        && field.solvedMinZ() == field.originZ() && field.solvedMaxZ() == field.originZ() + size - 1,
                "The solved box is " + field.solvedMinX() + ".." + field.solvedMaxX() + ", " + field.solvedMinY()
                        + ".." + field.solvedMaxY() + ", " + field.solvedMinZ() + ".." + field.solvedMaxZ()
                        + " instead of the whole cube " + field.originX() + ".." + (field.originX() + size - 1)
                        + ", " + field.originY() + ".." + (field.originY() + size - 1) + ", " + field.originZ()
                        + ".." + (field.originZ() + size - 1));
        long cellCount = (long) size * size * size;
        helper.assertTrue(reader.cellsRead() <= cellCount, "The fill read " + reader.cellsRead() + " cells instead of "
                + "at most the " + cellCount + " of the cube");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void cellsBelowAndAboveTheWorld(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        int minBuildHeight = lookup.minBuildHeight();
        int maxBuildHeight = lookup.maxBuildHeight();
        BlockPos testPos = helper.absolutePos(BlockPos.ZERO);
        int eyeX = testPos.getX();
        int eyeZ = testPos.getZ();
        reader.fill(eyeX, minBuildHeight + 4, eyeZ);
        readAll(helper, reader, lookup, "the fill below the world");
        assertMatchesWorld(helper, field, faces, lookup, "the fill below the world");
        int belowCount = 0;
        int firstBelowY = Integer.MIN_VALUE;
        for (int y = field.originY(); y < minBuildHeight; y++) {
            boolean isFull = field.maskSetAt(eyeX, y, eyeZ) == SnowFaces.FULL_MASK_SET;
            boolean isRead = field.distanceAt(eyeX, y, eyeZ) != SnowField.UNREAD;
            if (isFull && isRead) {
                belowCount++;
            } else if (firstBelowY == Integer.MIN_VALUE) {
                firstBelowY = y;
            }
        }
        helper.assertTrue(belowCount > 0, "The fill below the world checked " + belowCount + " cells of its own "
                + "column as " + SnowFaces.FULL_MASK_SET + " and read, instead of at least 1, the first cell that is "
                + "not being the one at y " + firstBelowY);
        reader.fill(eyeX, maxBuildHeight - 4, eyeZ);
        readAll(helper, reader, lookup, "the fill above the world");
        assertMatchesWorld(helper, field, faces, lookup, "the fill above the world");
        int aboveCount = 0;
        int firstAboveY = Integer.MIN_VALUE;
        for (int y = maxBuildHeight; y < field.originY() + field.size(); y++) {
            if (field.maskSetAt(eyeX, y, eyeZ) == SnowFaces.EMPTY_MASK_SET) {
                aboveCount++;
            } else if (firstAboveY == Integer.MIN_VALUE) {
                firstAboveY = y;
            }
        }
        helper.assertTrue(aboveCount > 0, "The fill above the world checked " + aboveCount + " cells of its own "
                + "column as " + SnowFaces.EMPTY_MASK_SET + ", instead of at least 1, the first cell that is not being "
                + "the one at y " + firstAboveY);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void followReadsOnlyTheCellsThatEnter(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, LARGE_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos start = helper.absolutePos(BlockPos.ZERO);
        int eyeX = start.getX();
        int eyeY = start.getY();
        int eyeZ = start.getZ();
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, lookup, "the fill before the walk");
        for (int[] step : WALK_STEPS) {
            for (int repeat = 0; repeat < step[3]; repeat++) {
                int oldOriginX = field.originX();
                int oldOriginY = field.originY();
                int oldOriginZ = field.originZ();
                long cellsReadBefore = reader.cellsRead();
                eyeX += step[0];
                eyeY += step[1];
                eyeZ += step[2];
                String label = "the walk to (" + eyeX + ", " + eyeY + ", " + eyeZ + ")";
                helper.assertFalse(reader.follow(eyeX, eyeY, eyeZ), "The follow of " + label + " refilled the field");
                readAll(helper, reader, lookup, label);
                long entered = enteredCellCount(oldOriginX, oldOriginY, oldOriginZ, field.originX(), field.originY(),
                        field.originZ(), LARGE_FIELD_SIZE);
                long cellsRead = reader.cellsRead() - cellsReadBefore;
                helper.assertTrue(cellsRead <= entered, "The reads of " + label + " read " + cellsRead + " cells "
                        + "instead of at most the " + entered + " that entered the cube");
            }
        }
        assertMatchesWorld(helper, field, faces, lookup, "the walk");
        assertDistancesMatchFresh(helper, field, lookup, "the walk");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void laggingReadsCatchUp(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, LARGE_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos start = helper.absolutePos(BlockPos.ZERO);
        int eyeX = start.getX();
        int eyeY = start.getY();
        int eyeZ = start.getZ();
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, lookup, "the fill before the lagging walk");
        for (int step = 1; step <= LAGGING_WALK_LENGTH; step++) {
            eyeX += 1;
            eyeZ += 1;
            String label = "the lagging walk to (" + eyeX + ", " + eyeY + ", " + eyeZ + ")";
            helper.assertFalse(reader.follow(eyeX, eyeY, eyeZ), "The follow of " + label + " refilled the field");
            int steps = reader.read(lookup, LAGGING_READ_BUDGET);
            helper.assertTrue(steps <= LAGGING_READ_BUDGET, "The read of " + label + " used " + steps
                    + " steps instead of at most " + LAGGING_READ_BUDGET);
            field.process(LAGGING_SOLVER_BUDGET);
        }
        readAll(helper, reader, lookup, "the catch up after the lagging walk");
        assertMatchesWorld(helper, field, faces, lookup, "the catch up after the lagging walk");
        assertDistancesMatchFresh(helper, field, lookup, "the catch up after the lagging walk");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void stepsNeverExceedTheLimit(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        int size = 16;
        SnowField field = new SnowField(faces, size, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos start = helper.absolutePos(BlockPos.ZERO);
        int eyeX = start.getX();
        int eyeY = start.getY();
        int eyeZ = start.getZ();
        reader.fill(eyeX, eyeY, eyeZ);
        int callCount = 0;
        while (!reader.isIdle() && callCount < SMALL_STEP_READ_CALLS) {
            int steps = reader.read(lookup, ONE_STEP_BUDGET);
            helper.assertTrue(steps >= 0 && steps <= ONE_STEP_BUDGET, "The read with a budget of " + ONE_STEP_BUDGET
                    + " used " + steps + " steps instead of at most " + ONE_STEP_BUDGET);
            callCount++;
        }
        helper.assertTrue(reader.isIdle(), "The reads of the fill with a budget of " + ONE_STEP_BUDGET
                + " are still not idle after " + callCount + " calls, with " + reader.queuedBoxCount()
                + " boxes waiting");
        assertMatchesWorld(helper, field, faces, lookup, "the fill with a budget of " + ONE_STEP_BUDGET);
        eyeX += 3;
        helper.assertFalse(reader.follow(eyeX, eyeY, eyeZ), "The follow of 3 blocks refilled the field");
        callCount = 0;
        while (!reader.isIdle() && callCount < SMALL_STEP_READ_CALLS) {
            int steps = reader.read(lookup, SEVEN_STEP_BUDGET);
            helper.assertTrue(steps >= 0 && steps <= SEVEN_STEP_BUDGET, "The read with a budget of "
                    + SEVEN_STEP_BUDGET + " used " + steps + " steps instead of at most " + SEVEN_STEP_BUDGET);
            callCount++;
        }
        helper.assertTrue(reader.isIdle(), "The reads of the follow with a budget of " + SEVEN_STEP_BUDGET
                + " are still not idle after " + callCount + " calls, with " + reader.queuedBoxCount()
                + " boxes waiting");
        assertMatchesWorld(helper, field, faces, lookup, "the follow with a budget of " + SEVEN_STEP_BUDGET);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void blockChangesApplyAtOnce(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 1, 1));
        reader.fill(eye.getX(), eye.getY(), eye.getZ());
        readAll(helper, reader, lookup, "the fill before the block changes");
        drain(helper, field);
        assertBlockChange(helper, field, faces, lookup, reader, new BlockPos(1, 3, 1),
                Blocks.STONE.defaultBlockState(), "the stone");
        assertBlockChange(helper, field, faces, lookup, reader, new BlockPos(0, 1, 0),
                Blocks.GLASS.defaultBlockState(), "the glass");
        assertBlockChange(helper, field, faces, lookup, reader, new BlockPos(2, 1, 2), oakSlab(SlabType.BOTTOM),
                "the oak slab");
        assertBlockChange(helper, field, faces, lookup, reader, new BlockPos(1, 3, 1), Blocks.AIR.defaultBlockState(),
                "the air");
        assertMatchesWorld(helper, field, faces, lookup, "the block changes");
        reader.fill(eye.getX(), eye.getY(), eye.getZ());
        int steps = reader.read(lookup, ONE_STEP_BUDGET);
        helper.assertTrue(steps >= 0 && steps <= ONE_STEP_BUDGET, "The read with a budget of " + ONE_STEP_BUDGET
                + " after the second fill used " + steps + " steps instead of at most " + ONE_STEP_BUDGET);
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockPos glassRelative = new BlockPos(1, 1, 1);
        helper.setBlock(glassRelative, glass);
        BlockPos glassAbsolute = helper.absolutePos(glassRelative);
        reader.onBlockChanged(lookup, glassAbsolute.getX(), glassAbsolute.getY(), glassAbsolute.getZ(), glass);
        int glassMaskSet = field.maskSetAt(glassAbsolute.getX(), glassAbsolute.getY(), glassAbsolute.getZ());
        helper.assertTrue(glassMaskSet == faces.maskSetOf(glass), "The glass of the waiting fill has the mask set "
                + glassMaskSet + " instead of the " + faces.maskSetOf(glass) + " of " + glass);
        helper.assertTrue(field.distanceAt(glassAbsolute.getX(), glassAbsolute.getY(), glassAbsolute.getZ())
                != SnowField.UNREAD, "The glass of the waiting fill is still unread after its block change");
        readAll(helper, reader, lookup, "the fill after the glass was placed");
        assertMatchesWorld(helper, field, faces, lookup, "the fill after the glass was placed");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void chunkSentAgainIsReadAgain(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 1, 1));
        reader.fill(eye.getX(), eye.getY(), eye.getZ());
        readAll(helper, reader, lookup, "the fill before the chunk was sent again");
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos firstRelative = new BlockPos(0, 2, 0);
        BlockPos secondRelative = new BlockPos(2, 2, 2);
        BlockPos firstAbsolute = helper.absolutePos(firstRelative);
        BlockPos secondAbsolute = helper.absolutePos(secondRelative);
        int oldMaskSet = field.maskSetAt(firstAbsolute.getX(), firstAbsolute.getY(), firstAbsolute.getZ());
        helper.setBlock(firstRelative, stone);
        helper.setBlock(secondRelative, stone);
        int unchangedMaskSet = field.maskSetAt(firstAbsolute.getX(), firstAbsolute.getY(), firstAbsolute.getZ());
        helper.assertTrue(unchangedMaskSet == oldMaskSet, "The field changed the mask set at " + firstRelative
                + " from " + oldMaskSet + " to " + unchangedMaskSet + " without its chunk being sent again");
        reader.queueChunk(firstAbsolute.getX() >> 4, firstAbsolute.getZ() >> 4);
        reader.queueChunk(secondAbsolute.getX() >> 4, secondAbsolute.getZ() >> 4);
        readAll(helper, reader, lookup, "the chunk that was sent again");
        int stoneMaskSet = faces.maskSetOf(stone);
        int readMaskSet = field.maskSetAt(firstAbsolute.getX(), firstAbsolute.getY(), firstAbsolute.getZ());
        helper.assertTrue(readMaskSet == stoneMaskSet, "After its chunk was sent again, the cell " + firstRelative
                + " has the mask set " + readMaskSet + " instead of the " + stoneMaskSet + " of " + stone);
        assertMatchesWorld(helper, field, faces, lookup, "the chunk that was sent again");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sectionSentAgainInPlaceIsReadAgain(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        int size = 16;
        SnowField field = new SnowField(faces, size, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        BlockPos testPos = helper.absolutePos(BlockPos.ZERO);
        int chunkX = testPos.getX() >> 4;
        int chunkZ = testPos.getZ() >> 4;
        LevelChunk chunk = new LevelChunk(helper.getLevel(), new ChunkPos(chunkX, chunkZ));
        StandaloneChunkLookup lookup = new StandaloneChunkLookup(chunk, helper.getLevel().getMinBuildHeight(),
                helper.getLevel().getMaxBuildHeight());
        int sectionY = 8;
        int eyeX = chunkX * SECTION_WIDTH + SECTION_WIDTH / 2;
        int eyeY = lookup.minBuildHeight() + sectionY * SECTION_WIDTH + SECTION_WIDTH / 2;
        int eyeZ = chunkZ * SECTION_WIDTH + SECTION_WIDTH / 2;
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(eyeY));
        fillSectionLayers(section, 0, 7, Blocks.STONE.defaultBlockState());
        fillSectionLayers(section, 8, 9, Blocks.GLASS.defaultBlockState());
        fillSectionLayers(section, 10, 10, oakSlab(SlabType.BOTTOM));
        helper.assertTrue(section.getStates().data.palette() instanceof LinearPalette, "The section of the four states "
                + "air, stone, glass and oak slab has the palette " + paletteName(section) + " instead of a "
                + "LinearPalette");
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, lookup, "the fill of the standalone chunk");
        assertMatchesWorld(helper, field, faces, lookup, "the fill of the standalone chunk");
        LevelChunkSection otherSection = newSection(helper);
        fillSectionLayers(otherSection, 0, 3, Blocks.OAK_PLANKS.defaultBlockState());
        fillSectionLayers(otherSection, 4, 4, Blocks.GLASS_PANE.defaultBlockState());
        fillSectionLayers(otherSection, 5, 5, Blocks.WHITE_CARPET.defaultBlockState());
        helper.assertTrue(otherSection.getStates().data.palette() instanceof LinearPalette, "The section of the four "
                + "states air, oak planks, glass pane and white carpet has the palette " + paletteName(otherSection)
                + " instead of a LinearPalette");
        PalettedContainer.Data<BlockState> dataBeforeRead = section.getStates().data;
        int writtenBits = otherSection.getStates().data.storage().getBits();
        int readBits = dataBeforeRead.storage().getBits();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        otherSection.write(buffer);
        section.read(buffer);
        helper.assertTrue(section.getStates().data == dataBeforeRead, "The section that was sent again replaced its "
                + "palette data object, so this test no longer covers the in-place case: the section that was written "
                + "has the palette " + paletteName(otherSection) + " with " + writtenBits + " bits, the section that "
                + "was read has the palette " + paletteName(section) + " with " + readBits + " bits");
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int staleCount = 0;
        for (int localY = 0; localY < size; localY++) {
            for (int localZ = 0; localZ < size; localZ++) {
                for (int localX = 0; localX < size; localX++) {
                    int x = originX + localX;
                    int y = originY + localY;
                    int z = originZ + localZ;
                    if (field.maskSetAt(x, y, z) == faces.maskSetOf(chunk.getBlockState(cursor.set(x, y, z)))) {
                        continue;
                    }
                    staleCount++;
                }
            }
        }
        helper.assertTrue(staleCount > 0, "The field holds no stale mask set after the section was sent again in "
                + "place: " + staleCount + " of the " + (size * size * size) + " cells of the section differ from the "
                + "states of the chunk, instead of at least 1 before the resend is reported");
        reader.queueChunk(chunkX, chunkZ);
        readAll(helper, reader, lookup, "the section that was sent again in place");
        assertMatchesWorld(helper, field, faces, lookup, "the section that was sent again in place");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void unloadedChunksStayUnreadUntilTheyLoad(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup realLookup = new SnowFieldReader.LevelChunkLookup();
        realLookup.setLevel(helper.getLevel());
        BlockPos start = helper.absolutePos(BlockPos.ZERO);
        int eyeX = start.getX();
        int eyeY = start.getY();
        int eyeZ = start.getZ();
        int hiddenChunkX = (eyeX - 16) >> 4;
        int hiddenChunkZ = eyeZ >> 4;
        HidingChunkLookup hidingLookup = new HidingChunkLookup(realLookup, hiddenChunkX, hiddenChunkZ);
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, hidingLookup, "the fill with the hidden chunk");
        assertMatchesWorld(helper, field, faces, hidingLookup, "the fill with the hidden chunk");
        if (realLookup.loadedChunk(hiddenChunkX, hiddenChunkZ) == null) {
            LogUtils.getLogger().info("The chunk " + hiddenChunkX + " " + hiddenChunkZ + " that the hiding lookup "
                    + "hides is not loaded, so the skipped chunk column count is not checked");
        } else {
            helper.assertTrue(reader.chunkColumnsSkipped() > 0, "The reader skipped " + reader.chunkColumnsSkipped()
                    + " chunk columns with a hidden chunk instead of at least 1");
        }
        hidingLookup.unhide();
        reader.queueChunk(hiddenChunkX, hiddenChunkZ);
        readAll(helper, reader, realLookup, "the fill after the chunk was unhidden");
        assertMatchesWorld(helper, field, faces, realLookup, "the fill after the chunk was unhidden");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void jumpsBecomeFills(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 1, 1));
        int eyeX = eye.getX();
        int eyeY = eye.getY();
        int eyeZ = eye.getZ();
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, lookup, "the fill before the jumps");
        long fillCountBefore = field.fillCount();
        int jumpX = eyeX + JUMP_FOLLOW_STEPS;
        helper.assertFalse(reader.follow(jumpX, eyeY, eyeZ), "The follow of " + JUMP_FOLLOW_STEPS + " blocks refilled "
                + "the field");
        helper.assertTrue(field.fillCount() == fillCountBefore, "The follow of " + JUMP_FOLLOW_STEPS + " blocks "
                + "counted " + field.fillCount() + " fills instead of the " + fillCountBefore + " from before");
        int longJumpX = jumpX + LONG_JUMP_FOLLOW_STEPS;
        helper.assertTrue(reader.follow(longJumpX, eyeY, eyeZ), "The follow of " + LONG_JUMP_FOLLOW_STEPS
                + " blocks did not refill the field");
        helper.assertTrue(field.fillCount() == fillCountBefore + 1, "The follow of " + LONG_JUMP_FOLLOW_STEPS
                + " blocks counted " + field.fillCount() + " fills instead of the " + (fillCountBefore + 1)
                + " of a refill");
        helper.assertTrue(reader.queuedBoxCount() == 1, "The fill of the jump leaves " + reader.queuedBoxCount()
                + " boxes waiting instead of the 1 of the whole cube");
        readAll(helper, reader, lookup, "the fill after the jump");
        assertMatchesWorld(helper, field, faces, lookup, "the fill after the jump");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void boxOverflowRequeuesTheCube(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 1, 1));
        int eyeX = eye.getX();
        int eyeY = eye.getY();
        int eyeZ = eye.getZ();
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, lookup, "the fill before the overflowing follows");
        for (int step = 1; step <= OVERFLOW_FOLLOW_STEPS; step++) {
            helper.assertFalse(reader.follow(eyeX + step, eyeY, eyeZ), "The follow to x " + (eyeX + step)
                    + " refilled the field");
        }
        helper.assertTrue(reader.boxOverflowCount() >= 1, "The " + OVERFLOW_FOLLOW_STEPS + " follows without a read "
                + "overflowed the boxes " + reader.boxOverflowCount() + " times instead of at least 1");
        helper.assertTrue(reader.queuedBoxCount() <= SnowFieldReader.BOX_CAPACITY, "The overflowing follows leave "
                + reader.queuedBoxCount() + " boxes waiting, more than the " + SnowFieldReader.BOX_CAPACITY
                + " of the ring");
        readAll(helper, reader, lookup, "the reads after the overflow");
        assertMatchesWorld(helper, field, faces, lookup, "the reads after the overflow");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmReadsAllocateNothing(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField field = new SnowField(faces, STANDARD_FIELD_SIZE, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader reader = new SnowFieldReader(field, faces);
        BlockPos start = helper.absolutePos(BlockPos.ZERO);
        int eyeX = start.getX();
        int eyeY = start.getY();
        int eyeZ = start.getZ();
        Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
        for (int chunkX = (eyeX - ALLOCATION_CACHED_REACH) >> 4; chunkX <= (eyeX + ALLOCATION_CACHED_REACH) >> 4;
                chunkX++) {
            for (int chunkZ = (eyeZ - ALLOCATION_CACHED_REACH) >> 4; chunkZ <= (eyeZ + ALLOCATION_CACHED_REACH) >> 4;
                    chunkZ++) {
                chunks.put(ChunkPos.asLong(chunkX, chunkZ),
                        helper.getLevel().getChunkSource().getChunkNow(chunkX, chunkZ));
            }
        }
        CachingChunkLookup lookup = new CachingChunkLookup(chunks, helper.getLevel().getMinBuildHeight(),
                helper.getLevel().getMaxBuildHeight());
        BlockPos changedRelative = new BlockPos(1, 1, 1);
        BlockPos changedAbsolute = helper.absolutePos(changedRelative);
        BlockState changedState = helper.getBlockState(changedRelative);
        reader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, reader, lookup, "the fill before the warm up");
        drain(helper, field);
        // The warm up walks over the measured steps and back, so the measured reads meet no state for the first time:
        for (int step = 1; step <= 2 * ALLOCATION_WARM_STEPS + ALLOCATION_MEASURED_STEPS; step++) {
            int offset = step <= ALLOCATION_WARM_STEPS + ALLOCATION_MEASURED_STEPS ? step
                    : 2 * (ALLOCATION_WARM_STEPS + ALLOCATION_MEASURED_STEPS) - step;
            helper.assertFalse(reader.follow(eyeX + offset, eyeY, eyeZ), "The warm up follow to x "
                    + (eyeX + offset) + " refilled the field");
            readAll(helper, reader, lookup, "the warm up step " + step);
            drain(helper, field);
        }
        reader.onBlockChanged(lookup, changedAbsolute.getX(), changedAbsolute.getY(), changedAbsolute.getZ(),
                changedState);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LogUtils.getLogger().info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation "
                    + "of the warm reads is not measured");
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
            LogUtils.getLogger().info("Thread allocated memory is not enabled, so the allocation of the warm reads "
                    + "is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int read = 0; read < ALLOCATION_READS; read++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        long sum = measuredReads(reader, field, lookup, changedState, changedAbsolute, eyeX + ALLOCATION_WARM_STEPS,
                eyeY, eyeZ);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LogUtils.getLogger().info("SnowFieldReader warm reads allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(sum != FAILED_ALLOCATION_SUM, "The measured reads did not finish within "
                + MAXIMUM_READ_CALLS + " calls each");
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_MEASURED_STEPS + " warm reads allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * The measured work of the allocation test: a few moves with their reads and the solver, and one block change,
     * without any assertion or message of its own, so that it can be measured.
     */
    private static long measuredReads(SnowFieldReader reader, SnowField field, SnowFieldReader.ChunkLookup lookup,
            BlockState changedState, BlockPos changedPos, int eyeX, int eyeY, int eyeZ) {
        for (int step = 1; step <= ALLOCATION_MEASURED_STEPS; step++) {
            reader.follow(eyeX + step, eyeY, eyeZ);
            if (readUntilIdle(reader, lookup, MAXIMUM_READ_CALLS) < 0) {
                return FAILED_ALLOCATION_SUM;
            }
            field.process(Integer.MAX_VALUE);
        }
        reader.onBlockChanged(lookup, changedPos.getX(), changedPos.getY(), changedPos.getZ(), changedState);
        return field.distanceAt(changedPos.getX(), changedPos.getY(), changedPos.getZ());
    }

    /**
     * Reads until the reader is idle and checks that it is, failing after the given number of calls.
     */
    private static void readAll(GameTestHelper helper, SnowFieldReader reader, SnowFieldReader.ChunkLookup lookup,
            String label) {
        int callCount = readUntilIdle(reader, lookup, MAXIMUM_READ_CALLS);
        helper.assertTrue(callCount >= 0, "The reads of " + label + " are still not idle after " + MAXIMUM_READ_CALLS
                + " calls, with " + reader.queuedBoxCount() + " boxes waiting");
    }

    /**
     * Reads until the reader is idle and returns the number of calls it used, or -1 when the calls run out first.
     */
    private static int readUntilIdle(SnowFieldReader reader, SnowFieldReader.ChunkLookup lookup, int maximumCalls) {
        for (int call = 0; call < maximumCalls; call++) {
            if (reader.isIdle()) {
                return call;
            }
            reader.read(lookup, READ_STEP);
        }
        return reader.isIdle() ? maximumCalls : -1;
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
     * Compares every cell of the cube with the world the lookup reads: the mask set of its state, the world's rules
     * below and above the build height, UNREAD outside the loaded chunks, and the MOTION_BLOCKING height of each
     * column.
     */
    private static void assertMatchesWorld(GameTestHelper helper, SnowField field, SnowFaces faces,
            SnowFieldReader.ChunkLookup lookup, String label) {
        int size = field.size();
        int originX = field.originX();
        int originY = field.originY();
        int originZ = field.originZ();
        int minBuildHeight = lookup.minBuildHeight();
        int maxBuildHeight = lookup.maxBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int maskMismatchCount = 0;
        int readStateMismatchCount = 0;
        int heightMismatchCount = 0;
        int reportedCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int localZ = 0; localZ < size; localZ++) {
            int z = originZ + localZ;
            for (int localX = 0; localX < size; localX++) {
                int x = originX + localX;
                LevelChunk chunk = lookup.loadedChunk(x >> 4, z >> 4);
                int expectedHeight = chunk == null ? SnowField.UNKNOWN_HEIGHT
                        : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
                int actualHeight = field.columnHeightAt(x, z);
                if (actualHeight != expectedHeight) {
                    heightMismatchCount++;
                    reportedCount++;
                    if (reportedCount <= REPORTED_MISMATCHES) {
                        mismatches.append(" (").append(x).append(", ").append(z).append(") height ")
                                .append(actualHeight).append("/").append(expectedHeight);
                    }
                }
                for (int localY = 0; localY < size; localY++) {
                    int y = originY + localY;
                    int expectedMaskSet;
                    boolean mustBeRead;
                    if (chunk == null) {
                        expectedMaskSet = SnowFaces.FULL_MASK_SET;
                        mustBeRead = false;
                    } else if (y < minBuildHeight) {
                        expectedMaskSet = SnowFaces.FULL_MASK_SET;
                        mustBeRead = true;
                    } else if (y >= maxBuildHeight) {
                        expectedMaskSet = SnowFaces.EMPTY_MASK_SET;
                        mustBeRead = true;
                    } else {
                        expectedMaskSet = faces.maskSetOf(chunk.getBlockState(cursor.set(x, y, z)));
                        mustBeRead = true;
                    }
                    int actualMaskSet = field.maskSetAt(x, y, z);
                    if (actualMaskSet != expectedMaskSet) {
                        maskMismatchCount++;
                        reportedCount++;
                        if (reportedCount <= REPORTED_MISMATCHES) {
                            mismatches.append(" (").append(x).append(", ").append(y).append(", ").append(z)
                                    .append(") ").append(actualMaskSet).append("/").append(expectedMaskSet);
                        }
                    }
                    int actualDistance = field.distanceAt(x, y, z);
                    boolean isRead = actualDistance != SnowField.UNREAD;
                    if (isRead == mustBeRead) {
                        continue;
                    }
                    readStateMismatchCount++;
                    reportedCount++;
                    if (reportedCount <= REPORTED_MISMATCHES) {
                        mismatches.append(" (").append(x).append(", ").append(y).append(", ").append(z)
                                .append(") distance ").append(actualDistance)
                                .append(mustBeRead ? "/read" : "/UNREAD");
                    }
                }
            }
        }
        helper.assertTrue(maskMismatchCount == 0 && readStateMismatchCount == 0 && heightMismatchCount == 0,
                "In " + label + ", " + maskMismatchCount + " mask sets, " + readStateMismatchCount + " read states and "
                        + heightMismatchCount + " column heights differ from the world, actual/expected:"
                        + mismatches);
    }

    /**
     * Fills a second field and a second reader at the eye of the first field, reads them, drains both and compares the
     * distances of the exact region, in which retained cells cannot keep paths a fresh solve does not see.
     */
    private static void assertDistancesMatchFresh(GameTestHelper helper, SnowField field,
            SnowFieldReader.ChunkLookup lookup, String label) {
        int size = field.size();
        int eyeX = field.originX() + size / 2;
        int eyeY = field.originY() + size / 2;
        int eyeZ = field.originZ() + size / 2;
        SnowFaces freshFaces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        SnowField freshField = new SnowField(freshFaces, size, SnowField.DEFAULT_QUEUE_CAPACITY,
                SnowField.DEFAULT_RESET_CAPACITY);
        SnowFieldReader freshReader = new SnowFieldReader(freshField, freshFaces);
        freshReader.fill(eyeX, eyeY, eyeZ);
        readAll(helper, freshReader, lookup, "the fresh fill of " + label);
        drain(helper, freshField);
        drain(helper, field);
        int horizontalReach = size / 2 - EXACT_MARGIN;
        helper.assertTrue(horizontalReach > 0, "A field of size " + size + " has no exact region to compare with a "
                + "fresh solve, since the margin of " + EXACT_MARGIN + " leaves " + horizontalReach + " blocks");
        int lowestY = field.originY() + EXACT_LOWEST_LAYER;
        int mismatchCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int x = eyeX - horizontalReach; x <= eyeX + horizontalReach; x++) {
            for (int z = eyeZ - horizontalReach; z <= eyeZ + horizontalReach; z++) {
                for (int y = lowestY; y < field.originY() + size; y++) {
                    int actual = field.distanceAt(x, y, z);
                    int expected = freshField.distanceAt(x, y, z);
                    if (actual == expected) {
                        continue;
                    }
                    mismatchCount++;
                    if (mismatchCount <= REPORTED_MISMATCHES) {
                        mismatches.append(" (").append(x).append(", ").append(y).append(", ").append(z)
                                .append(") ").append(actual).append("/").append(expected);
                    }
                }
            }
        }
        helper.assertTrue(mismatchCount == 0, "In the exact region after " + label + ", " + mismatchCount + " cells "
                + "differ from a fresh solve at the same eye, actual/expected:" + mismatches);
    }

    /**
     * The number of cells of the new cube that were not in the old one.
     */
    private static long enteredCellCount(int oldOriginX, int oldOriginY, int oldOriginZ, int newOriginX,
            int newOriginY, int newOriginZ, int size) {
        long retainedX = size - Math.abs((long) newOriginX - oldOriginX);
        long retainedY = size - Math.abs((long) newOriginY - oldOriginY);
        long retainedZ = size - Math.abs((long) newOriginZ - oldOriginZ);
        return (long) size * size * size - retainedX * retainedY * retainedZ;
    }

    /**
     * Checks all 4096 cells of a section against the mask set of the state the section holds there.
     */
    private static void assertSectionMatches(GameTestHelper helper, LevelChunkSection section, SnowSectionMasks masks,
            SnowFaces faces, String label) {
        int mismatchCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int index = 0; index < SECTION_CELL_COUNT; index++) {
            int localX = localXOf(index);
            int localY = localYOf(index);
            int localZ = localZOf(index);
            int actualMaskSet = masks.maskSetAt(localX, localY, localZ);
            int expectedMaskSet = faces.maskSetOf(section.getBlockState(localX, localY, localZ));
            if (actualMaskSet == expectedMaskSet) {
                continue;
            }
            mismatchCount++;
            if (mismatchCount <= REPORTED_MISMATCHES) {
                mismatches.append(" (").append(localX).append(", ").append(localY).append(", ").append(localZ)
                        .append(") ").append(actualMaskSet).append("/").append(expectedMaskSet);
            }
        }
        helper.assertTrue(mismatchCount == 0, "In " + label + ", " + mismatchCount + " of the " + SECTION_CELL_COUNT
                + " cells have a mask set other than their state's, actual/expected:" + mismatches);
    }

    /**
     * Checks the palette kind of a filled section against the expected one, then all 4096 of its cells.
     */
    private static void assertSectionOfStates(GameTestHelper helper, SnowFaces faces, int distinctStateCount,
            String expectedKind, Class<?> expectedPalette) {
        LevelChunkSection section = filledSection(helper, distinctStateCount);
        SnowSectionMasks masks = new SnowSectionMasks(faces);
        masks.load(section);
        Object palette = section.getStates().data.palette();
        helper.assertTrue(expectedPalette.isInstance(palette), "A section of " + distinctStateCount + " distinct "
                + "states has the palette " + palette.getClass().getSimpleName() + " instead of a " + expectedKind);
        assertSectionMatches(helper, section, masks, faces, "the section of " + distinctStateCount + " distinct "
                + "states");
    }

    /**
     * A fresh section of the level's biome registry.
     */
    private static LevelChunkSection newSection(GameTestHelper helper) {
        Registry<Biome> biomeRegistry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        return new LevelChunkSection(biomeRegistry);
    }

    /**
     * A fresh section with the given number of distinct states in its first cells, in the section's storage order, and
     * air in every other cell.
     */
    private static LevelChunkSection filledSection(GameTestHelper helper, int distinctStateCount) {
        LevelChunkSection section = newSection(helper);
        List<BlockState> states = distinctStates(distinctStateCount);
        for (int index = 0; index < states.size(); index++) {
            setSectionCell(section, index, states.get(index));
        }
        return section;
    }

    /**
     * The given number of distinct states, starting with the varied states and continued by redstone wire states,
     * which have 1296 of them.
     */
    private static List<BlockState> distinctStates(int distinctStateCount) {
        List<BlockState> states = new ArrayList<>(distinctStateCount);
        for (BlockState state : variedStates()) {
            if (states.size() == distinctStateCount) {
                return states;
            }
            states.add(state);
        }
        List<BlockState> wireStates = Blocks.REDSTONE_WIRE.getStateDefinition().getPossibleStates();
        for (int index = 0; states.size() < distinctStateCount; index++) {
            states.add(wireStates.get(index));
        }
        return states;
    }

    /**
     * The varied states that the section tests place first, from air, so that the untouched cells of a section hold a
     * state that the palette already has.
     */
    private static List<BlockState> variedStates() {
        List<BlockState> states = new ArrayList<>(15);
        states.add(Blocks.AIR.defaultBlockState());
        states.add(Blocks.STONE.defaultBlockState());
        states.add(Blocks.GLASS.defaultBlockState());
        states.add(Blocks.GLASS_PANE.defaultBlockState());
        states.add(Blocks.OAK_FENCE.defaultBlockState());
        states.add(oakSlab(SlabType.BOTTOM));
        states.add(oakSlab(SlabType.TOP));
        states.add(oakStairs(Direction.NORTH, Half.BOTTOM));
        states.add(oakDoor(Direction.NORTH, true, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
        states.add(Blocks.WATER.defaultBlockState());
        states.add(Blocks.OAK_LEAVES.defaultBlockState());
        states.add(snowLayer(1));
        states.add(snowLayer(2));
        states.add(snowLayer(8));
        states.add(Blocks.WHITE_CARPET.defaultBlockState());
        return states;
    }

    /**
     * Sets the state of a cell of a section, by its index in the section's storage order.
     */
    private static void setSectionCell(LevelChunkSection section, int index, BlockState state) {
        section.setBlockState(localXOf(index), localYOf(index), localZOf(index), state);
    }

    /**
     * Sets every cell of a closed range of local y of a section to a state.
     */
    private static void fillSectionLayers(LevelChunkSection section, int firstLocalY, int lastLocalY,
            BlockState state) {
        for (int localY = firstLocalY; localY <= lastLocalY; localY++) {
            for (int localZ = 0; localZ < SECTION_WIDTH; localZ++) {
                for (int localX = 0; localX < SECTION_WIDTH; localX++) {
                    section.setBlockState(localX, localY, localZ, state);
                }
            }
        }
    }

    /**
     * The local x of a cell index of a section, whose storage index is (y << 8) | (z << 4) | x.
     */
    private static int localXOf(int index) {
        return index & SECTION_AXIS_MASK;
    }

    /**
     * The local y of a cell index of a section.
     */
    private static int localYOf(int index) {
        return (index >> (2 * SECTION_AXIS_SHIFT)) & SECTION_AXIS_MASK;
    }

    /**
     * The local z of a cell index of a section.
     */
    private static int localZOf(int index) {
        return (index >> SECTION_AXIS_SHIFT) & SECTION_AXIS_MASK;
    }

    /**
     * The simple name of the palette of a section, for the failure messages.
     */
    private static String paletteName(LevelChunkSection section) {
        return section.getStates().data.palette().getClass().getSimpleName();
    }

    /**
     * Places a block of the test and passes the change to the reader, then checks that the field holds its mask set
     * and the height of the chunk's column at once.
     */
    private static void assertBlockChange(GameTestHelper helper, SnowField field, SnowFaces faces,
            SnowFieldReader.ChunkLookup lookup, SnowFieldReader reader, BlockPos relativePos, BlockState state,
            String label) {
        helper.setBlock(relativePos, state);
        BlockPos absolutePos = helper.absolutePos(relativePos);
        int x = absolutePos.getX();
        int y = absolutePos.getY();
        int z = absolutePos.getZ();
        reader.onBlockChanged(lookup, x, y, z, state);
        int expectedMaskSet = faces.maskSetOf(state);
        int actualMaskSet = field.maskSetAt(x, y, z);
        helper.assertTrue(actualMaskSet == expectedMaskSet, "After " + label + " at " + relativePos + ", the field "
                + "holds the mask set " + actualMaskSet + " instead of the " + expectedMaskSet + " of " + state);
        LevelChunk chunk = lookup.loadedChunk(x >> 4, z >> 4);
        helper.assertTrue(chunk != null, "The chunk of " + relativePos + " is not loaded, so its height cannot be "
                + "checked after " + label);
        int expectedHeight = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
        int actualHeight = field.columnHeightAt(x, z);
        helper.assertTrue(actualHeight == expectedHeight, "After " + label + " at " + relativePos + ", the field "
                + "holds the height " + actualHeight + " instead of the " + expectedHeight + " of the chunk");
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
     * A snow layer block of the given layer count.
     */
    private static BlockState snowLayer(int layers) {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    /**
     * A chunk lookup that hides one chunk of another lookup until it is unhidden.
     */
    private static final class HidingChunkLookup implements SnowFieldReader.ChunkLookup {
        private final SnowFieldReader.ChunkLookup delegate;
        private final int hiddenChunkX;
        private final int hiddenChunkZ;
        private boolean hidden = true;

        private HidingChunkLookup(SnowFieldReader.ChunkLookup delegate, int hiddenChunkX, int hiddenChunkZ) {
            this.delegate = delegate;
            this.hiddenChunkX = hiddenChunkX;
            this.hiddenChunkZ = hiddenChunkZ;
        }

        private void unhide() {
            hidden = false;
        }

        @Nullable
        @Override
        public LevelChunk loadedChunk(int chunkX, int chunkZ) {
            if (hidden && chunkX == hiddenChunkX && chunkZ == hiddenChunkZ) {
                return null;
            }
            return delegate.loadedChunk(chunkX, chunkZ);
        }

        @Override
        public int minBuildHeight() {
            return delegate.minBuildHeight();
        }

        @Override
        public int maxBuildHeight() {
            return delegate.maxBuildHeight();
        }
    }

    /**
     * A chunk lookup that returns one standalone chunk at its own position and nothing at any other.
     */
    private static final class StandaloneChunkLookup implements SnowFieldReader.ChunkLookup {
        private final LevelChunk chunk;
        private final int chunkX;
        private final int chunkZ;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        private StandaloneChunkLookup(LevelChunk chunk, int minBuildHeight, int maxBuildHeight) {
            this.chunk = chunk;
            this.chunkX = chunk.getPos().x;
            this.chunkZ = chunk.getPos().z;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        @Nullable
        @Override
        public LevelChunk loadedChunk(int chunkX, int chunkZ) {
            if (chunkX == this.chunkX && chunkZ == this.chunkZ) {
                return chunk;
            }
            return null;
        }

        @Override
        public int minBuildHeight() {
            return minBuildHeight;
        }

        @Override
        public int maxBuildHeight() {
            return maxBuildHeight;
        }
    }

    /**
     * A chunk lookup of the chunks it was given and of nothing else, which allocates nothing per lookup.
     */
    private static final class CachingChunkLookup implements SnowFieldReader.ChunkLookup {
        private final Long2ObjectOpenHashMap<LevelChunk> chunks;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        private CachingChunkLookup(Long2ObjectOpenHashMap<LevelChunk> chunks, int minBuildHeight, int maxBuildHeight) {
            this.chunks = chunks;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        @Nullable
        @Override
        public LevelChunk loadedChunk(int chunkX, int chunkZ) {
            return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        @Override
        public int minBuildHeight() {
            return minBuildHeight;
        }

        @Override
        public int maxBuildHeight() {
            return maxBuildHeight;
        }
    }
}
