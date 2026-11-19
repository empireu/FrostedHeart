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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Checks the window of far heights around the eye: where the window is placed and how far it reaches, which tiles a
 * move resets and which it keeps, that the reads never use more tiles than their budget and reach every loaded column,
 * that the highest surface waits for the reads of a fill or a move, that the columns of a chunk that is not loaded stay
 * unknown until it loads or a block of it changes, that a block change updates its column and never lowers the surface
 * of its chunk, that a chunk sent again is read again, and that the warm operations allocate nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FarHeightsGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNK_WIDTH = 16;
    private static final int CHUNK_LOCAL_MASK = 15;
    private static final int CHUNK_AXIS_SHIFT = 4;
    private static final int READ_STEP = 4096;
    private static final int MAXIMUM_READ_CALLS = 100;
    private static final int MAXIMUM_SMALL_READ_CALLS = 4096;
    private static final int SMALL_TILE_BUDGET = 7;
    private static final int TILE_BUDGET_FOR_A_STEP = 128;
    private static final int TILES_OF_A_STEP = 64;
    private static final int TILES_OF_A_DIAGONAL_STEP = 127;
    private static final int TILES_OF_A_THREE_CHUNK_STEP = 4032;
    private static final int REPORTED_MISMATCHES = 5;
    private static final int HIDDEN_COLUMN_LOCAL_X = 3;
    private static final int HIDDEN_COLUMN_LOCAL_Z = 5;
    private static final int OUTSIDE_COLUMN_OFFSET = 600;
    private static final int ALLOCATION_CACHED_REACH = 40;
    private static final int ALLOCATION_EXTRA_CHUNKS = 6;
    private static final int ALLOCATION_WARM_STEPS = 2;
    private static final int ALLOCATION_MEASURED_STEPS = 3;
    private static final int ALLOCATION_HEIGHT_QUERIES = 1000;
    private static final int ALLOCATION_BEAN_WARMUPS = 20;
    /**
     * The steps of the walk of the retained column test: three steps of one chunk along x, two along z, the diagonal
     * step of three chunks back along x and two along z, and the short step that may or may not cross a rounding
     * border.
     */
    private static final int[][] WALK_STEPS = {{CHUNK_WIDTH, 0}, {CHUNK_WIDTH, 0}, {CHUNK_WIDTH, 0}, {0, CHUNK_WIDTH},
            {0, CHUNK_WIDTH}, {-3 * CHUNK_WIDTH, -2 * CHUNK_WIDTH}, {5, 0}};

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void windowIsPlacedAroundTheNearestChunkCorner(GameTestHelper helper) {
        FarHeights unplaced = new FarHeights();
        helper.assertFalse(unplaced.isPlaced(), "A fresh window is placed before any follow");
        short unplacedHeight = unplaced.motionHeightAt(0, 0);
        helper.assertTrue(unplacedHeight == FarHeights.UNKNOWN, "Before any follow, the column (0, 0) has the motion "
                + "height " + unplacedHeight + " instead of " + FarHeights.UNKNOWN);
        int[] eyeXs = {0, 7, 8, -8, -9, 1000};
        int[] expectedOriginChunkXs = {-32, -32, -31, -32, -33, 31};
        for (int index = 0; index < eyeXs.length; index++) {
            FarHeights heights = new FarHeights();
            helper.assertTrue(heights.follow(eyeXs[index], 0), "The follow of the eye x " + eyeXs[index]
                    + " did not fill the window");
            helper.assertTrue(heights.originChunkX() == expectedOriginChunkXs[index], "The eye x " + eyeXs[index]
                    + " places the window at the chunk x " + heights.originChunkX() + " instead of the "
                    + expectedOriginChunkXs[index] + " of the nearest chunk corner below the eye");
        }
        FarHeights zHeights = new FarHeights();
        zHeights.follow(0, -9);
        helper.assertTrue(zHeights.originChunkZ() == -33, "The eye z -9 places the window at the chunk z "
                + zHeights.originChunkZ() + " instead of the -33 of the nearest chunk corner below the eye");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void windowReachAndAddressing(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        helper.assertTrue(heights.follow(0, 0), "The first follow of (0, 0) did not fill the window");
        helper.assertTrue(heights.contains(-512, 0), "The first column of the window of the chunk x -32 is (-512, 0), "
                + "which the window does not contain");
        helper.assertTrue(heights.contains(511, 0), "The last column of the window of the chunk x 31 is (511, 0), "
                + "which the window does not contain");
        helper.assertTrue(heights.contains(0, -512), "The first column of the window of the chunk z -32 is (0, -512), "
                + "which the window does not contain");
        helper.assertTrue(heights.contains(0, 511), "The last column of the window of the chunk z 31 is (0, 511), "
                + "which the window does not contain");
        helper.assertFalse(heights.contains(-513, 0), "The column (-513, 0) is one block before the window and the "
                + "window contains it");
        helper.assertFalse(heights.contains(512, 0), "The column (512, 0) is one block after the window and the window "
                + "contains it");
        helper.assertFalse(heights.contains(0, 512), "The column (0, 512) is one block after the window and the window "
                + "contains it");
        short outsideHeight = heights.motionHeightAt(512, 0);
        helper.assertTrue(outsideHeight == FarHeights.UNKNOWN, "The column (512, 0) outside the window has the motion "
                + "height " + outsideHeight + " instead of " + FarHeights.UNKNOWN);
        helper.assertTrue(FarHeights.tileOf(-1, 0) == 63, "The tile of the chunk (-1, 0) is "
                + FarHeights.tileOf(-1, 0) + " instead of the 63 of the last tile of a row");
        helper.assertTrue(FarHeights.tileOf(0, -1) == 4032, "The tile of the chunk (0, -1) is "
                + FarHeights.tileOf(0, -1) + " instead of the 4032 of the first tile of the last row");
        helper.assertTrue(FarHeights.tileOf(64, 65) == 64, "The tile of the chunk (64, 65) is "
                + FarHeights.tileOf(64, 65) + " instead of the 64 of the wrapped chunk (0, 1)");
        helper.assertTrue(FarHeights.tileOf(0, 0) == 0, "The tile of the chunk (0, 0) is " + FarHeights.tileOf(0, 0)
                + " instead of 0");
        helper.assertTrue(FarHeights.texelOf(-1, -1) == 1048575, "The texel of the column (-1, -1) is "
                + FarHeights.texelOf(-1, -1) + " instead of the 1048575 of the last texel of the texture");
        helper.assertTrue(FarHeights.texelOf(1024, 3) == 3072, "The texel of the column (1024, 3) is "
                + FarHeights.texelOf(1024, 3) + " instead of the 3072 of the wrapped column (0, 3)");
        helper.assertTrue(FarHeights.texelOf(17, 0) == 17, "The texel of the column (17, 0) is "
                + FarHeights.texelOf(17, 0) + " instead of 17");
        helper.assertTrue(FarHeights.texelOf(0, 1) == 1024, "The texel of the column (0, 1) is "
                + FarHeights.texelOf(0, 1) + " instead of the 1024 of the first texel of the second row");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void followResetsOnlyTheChunksThatEnter(GameTestHelper helper) {
        FarHeights unread = new FarHeights();
        helper.assertTrue(unread.follow(0, 0), "The first follow of (0, 0) did not fill the window");
        helper.assertTrue(unread.pendingTileCount() == FarHeights.TILES, "The fill of (0, 0) leaves "
                + unread.pendingTileCount() + " tiles pending instead of the " + FarHeights.TILES + " of the window");
        helper.assertTrue(unread.fillCount() == 1, "The fill of (0, 0) counted " + unread.fillCount()
                + " fills instead of 1");
        helper.assertTrue(unread.isReadPending(), "The fill of (0, 0) does not wait for reads");
        helper.assertTrue(unread.dirtyTileCount() == FarHeights.TILES, "The fill of (0, 0) leaves "
                + unread.dirtyTileCount() + " dirty tiles instead of the " + FarHeights.TILES + " of the window");
        unread.clearDirtyTiles();
        helper.assertTrue(unread.dirtyTileCount() == 0, "The cleared fill of (0, 0) leaves " + unread.dirtyTileCount()
                + " dirty tiles instead of 0");
        helper.assertFalse(unread.follow(7, 7), "The follow of (7, 7) filled the window although its origin stayed at "
                + "the chunk (-32, -32)");
        helper.assertTrue(unread.pendingTileCount() == FarHeights.TILES, "The follow of (7, 7) without a read leaves "
                + unread.pendingTileCount() + " tiles pending instead of the " + FarHeights.TILES
                + " that the fill left");

        FarHeights heights = new FarHeights();
        NullChunkLookup nullLookup = new NullChunkLookup();
        helper.assertTrue(heights.follow(0, 0), "The first follow of (0, 0) did not fill the driven window");
        int fillRead = heights.readPending(nullLookup, READ_STEP);
        helper.assertTrue(fillRead == FarHeights.TILES, "The first read of the fill of (0, 0) read " + fillRead
                + " tiles instead of the " + FarHeights.TILES + " that filled the window");
        helper.assertTrue(heights.pendingTileCount() == 0, "The first read of the fill of (0, 0) leaves "
                + heights.pendingTileCount() + " tiles pending instead of 0");
        helper.assertFalse(heights.isReadPending(), "The first read of the fill of (0, 0) still waits for reads");
        heights.clearDirtyTiles();
        helper.assertFalse(heights.follow(8, 0), "The follow of x 8 filled the window although its origin moved by "
                + "one chunk");
        helper.assertTrue(heights.originChunkX() == -31 && heights.originChunkZ() == -32, "The follow of x 8 places "
                + "the window at the chunk (" + heights.originChunkX() + ", " + heights.originChunkZ()
                + ") instead of (-31, -32)");
        helper.assertTrue(heights.pendingTileCount() == TILES_OF_A_STEP, "The follow of x 8 leaves "
                + heights.pendingTileCount() + " tiles pending instead of the " + TILES_OF_A_STEP
                + " of the column of chunks that entered");
        helper.assertTrue(heights.dirtyTileCount() == TILES_OF_A_STEP, "The follow of x 8 leaves "
                + heights.dirtyTileCount() + " dirty tiles instead of the " + TILES_OF_A_STEP
                + " of the column of chunks that entered");
        int notPendingCount = 0;
        int firstNotPendingChunkZ = Integer.MIN_VALUE;
        for (int chunkZ = -32; chunkZ <= 31; chunkZ++) {
            if (heights.isTilePending(FarHeights.tileOf(32, chunkZ))) {
                continue;
            }
            notPendingCount++;
            if (firstNotPendingChunkZ == Integer.MIN_VALUE) {
                firstNotPendingChunkZ = chunkZ;
            }
        }
        helper.assertTrue(notPendingCount == 0, "The follow of x 8 leaves " + notPendingCount + " of the "
                + TILES_OF_A_STEP + " tiles of the new last column of chunks not pending, the first one being the "
                + "chunk (32, " + firstNotPendingChunkZ + ") with the tile " + FarHeights.tileOf(32,
                firstNotPendingChunkZ));
        helper.assertTrue(heights.isReadPending(), "The follow of x 8 does not wait for reads");
        int stepRead = heights.readPending(nullLookup, TILE_BUDGET_FOR_A_STEP);
        helper.assertTrue(stepRead == TILES_OF_A_STEP, "The read after the follow of x 8 read " + stepRead
                + " tiles instead of the " + TILES_OF_A_STEP + " that were pending");
        helper.assertFalse(heights.isReadPending(), "The read after the follow of x 8 still waits for reads");
        helper.assertFalse(heights.follow(24, 16), "The follow of (24, 16) filled the window although its origin moved "
                + "by one chunk on each axis");
        helper.assertTrue(heights.originChunkX() == -30 && heights.originChunkZ() == -31, "The follow of (24, 16) "
                + "places the window at the chunk (" + heights.originChunkX() + ", " + heights.originChunkZ()
                + ") instead of (-30, -31)");
        helper.assertTrue(heights.pendingTileCount() == TILES_OF_A_DIAGONAL_STEP, "The follow of (24, 16) leaves "
                + heights.pendingTileCount() + " tiles pending instead of the " + TILES_OF_A_DIAGONAL_STEP
                + " of the diagonal move");
        int diagonalRead = heights.readPending(nullLookup, TILE_BUDGET_FOR_A_STEP);
        helper.assertTrue(diagonalRead == TILES_OF_A_DIAGONAL_STEP, "The read after the follow of (24, 16) read "
                + diagonalRead + " tiles instead of the " + TILES_OF_A_DIAGONAL_STEP + " that were pending");
        helper.assertFalse(heights.follow(25, 16), "The follow of (25, 16) filled the window although its origin "
                + "stayed at the chunk (-30, -31)");
        helper.assertTrue(heights.pendingTileCount() == 0, "The follow of (25, 16) at the unchanged origin leaves "
                + heights.pendingTileCount() + " tiles pending instead of 0");
        long fillCountBefore = heights.fillCount();
        helper.assertFalse(heights.follow(25 + 63 * CHUNK_WIDTH, 16), "The follow of 63 chunks filled the window");
        helper.assertTrue(heights.fillCount() == fillCountBefore, "The follow of 63 chunks counted "
                + heights.fillCount() + " fills instead of the " + fillCountBefore + " from before");
        helper.assertTrue(heights.pendingTileCount() == TILES_OF_A_THREE_CHUNK_STEP, "The follow of 63 chunks leaves "
                + heights.pendingTileCount() + " tiles pending instead of the " + TILES_OF_A_THREE_CHUNK_STEP
                + " of the columns of chunks that entered");
        readAll(helper, heights, nullLookup, "the fill after the follow of 63 chunks");
        helper.assertTrue(heights.follow(25 + 63 * CHUNK_WIDTH + FarHeights.CHUNKS * CHUNK_WIDTH, 16), "The follow of "
                + FarHeights.CHUNKS + " chunks did not fill the window");
        helper.assertTrue(heights.fillCount() == fillCountBefore + 1, "The follow of " + FarHeights.CHUNKS
                + " chunks counted " + heights.fillCount() + " fills instead of the " + (fillCountBefore + 1)
                + " of a fill");
        helper.assertTrue(heights.pendingTileCount() == FarHeights.TILES, "The follow of " + FarHeights.CHUNKS
                + " chunks leaves " + heights.pendingTileCount() + " tiles pending instead of the " + FarHeights.TILES
                + " of a fill");
        int emptyRead = heights.readPending(nullLookup, 0);
        helper.assertTrue(emptyRead == 0, "The read with a budget of 0 read " + emptyRead + " tiles instead of 0");
        helper.assertTrue(heights.pendingTileCount() == FarHeights.TILES, "The read with a budget of 0 leaves "
                + heights.pendingTileCount() + " tiles pending instead of the " + FarHeights.TILES + " from before");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void readPendingNeverExceedsItsLimit(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        NullChunkLookup nullLookup = new NullChunkLookup();
        helper.assertTrue(heights.follow(0, 0), "The first follow of (0, 0) did not fill the window");
        int callCount = 0;
        int readInTotal = 0;
        while (heights.pendingTileCount() > 0 && callCount < MAXIMUM_SMALL_READ_CALLS) {
            int read = heights.readPending(nullLookup, SMALL_TILE_BUDGET);
            helper.assertTrue(read >= 1 && read <= SMALL_TILE_BUDGET, "The read " + callCount + " with a budget of "
                    + SMALL_TILE_BUDGET + " and " + heights.pendingTileCount() + " tiles pending read " + read
                    + " tiles instead of 1 to " + SMALL_TILE_BUDGET);
            readInTotal += read;
            callCount++;
        }
        helper.assertTrue(heights.pendingTileCount() == 0, "The reads with a budget of " + SMALL_TILE_BUDGET
                + " leave " + heights.pendingTileCount() + " tiles pending after " + callCount + " calls instead of 0");
        helper.assertTrue(readInTotal == FarHeights.TILES, "The reads with a budget of " + SMALL_TILE_BUDGET + " read "
                + readInTotal + " tiles in total instead of the " + FarHeights.TILES + " of the fill");
        helper.assertTrue(heights.tilesRead() == FarHeights.TILES, "The window counted " + heights.tilesRead()
                + " tiles read instead of the " + FarHeights.TILES + " of the fill");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fillReadsEveryLoadedColumn(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.assertTrue(heights.follow(eye.getX(), eye.getZ()), "The first follow of the eye at " + eye
                + " did not fill the window");
        readAll(helper, heights, lookup, "the fill at the eye");
        assertMatchesWorld(helper, heights, lookup, "the fill at the eye");
        helper.assertFalse(heights.isReadPending(), "The fill at the eye still waits for reads after its "
                + FarHeights.TILES + " tiles were read");
        helper.assertTrue(heights.tilesRead() == FarHeights.TILES, "The fill at the eye counted " + heights.tilesRead()
                + " tiles read instead of the " + FarHeights.TILES + " of the window");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void highestSurfaceWaitsForTheReads(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        int minBuildHeight = lookup.minBuildHeight();
        int maxBuildHeight = lookup.maxBuildHeight();
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.assertTrue(heights.follow(eye.getX(), eye.getZ()), "The first follow of the eye at " + eye
                + " did not fill the window");
        int highestBeforeReads = heights.highestSurface(minBuildHeight, maxBuildHeight);
        helper.assertTrue(highestBeforeReads == maxBuildHeight, "Before any read, the highest surface is "
                + highestBeforeReads + " instead of the maximum build height " + maxBuildHeight);
        int firstRead = heights.readPending(lookup, 1);
        helper.assertTrue(firstRead == 1, "The read with a budget of 1 read " + firstRead
                + " tiles instead of the 1 of its budget");
        int highestAfterOneRead = heights.highestSurface(minBuildHeight, maxBuildHeight);
        helper.assertTrue(highestAfterOneRead == maxBuildHeight, "After 1 of the " + FarHeights.TILES + " tiles were "
                + "read, the highest surface is " + highestAfterOneRead + " instead of the maximum build height "
                + maxBuildHeight);
        readAll(helper, heights, lookup, "the fill at the eye");
        assertMatchesWorld(helper, heights, lookup, "the fill at the eye");
        helper.assertFalse(heights.follow(eye.getX() + CHUNK_WIDTH, eye.getZ()), "The follow of one chunk along x "
                + "filled the window");
        helper.assertTrue(heights.isReadPending(), "The follow of one chunk along x does not wait for reads");
        int highestAfterMove = heights.highestSurface(minBuildHeight, maxBuildHeight);
        helper.assertTrue(highestAfterMove == maxBuildHeight, "After the move of one chunk, the highest surface is "
                + highestAfterMove + " instead of the maximum build height " + maxBuildHeight);
        int moveRead = heights.readPending(lookup, TILE_BUDGET_FOR_A_STEP);
        helper.assertTrue(moveRead == TILES_OF_A_STEP, "The read after the move of one chunk read " + moveRead
                + " tiles instead of the " + TILES_OF_A_STEP + " of the column of chunks that entered");
        helper.assertFalse(heights.isReadPending(), "The read after the move of one chunk still waits for reads");
        assertMatchesWorld(helper, heights, lookup, "the move of one chunk");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void movesKeepTheRetainedColumns(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        int eyeX = eye.getX();
        int eyeZ = eye.getZ();
        helper.assertTrue(heights.follow(eyeX, eyeZ), "The first follow of the eye at " + eye
                + " did not fill the window");
        readAll(helper, heights, lookup, "the fill before the walk");
        assertMatchesWorld(helper, heights, lookup, "the fill before the walk");
        for (int step = 0; step < WALK_STEPS.length; step++) {
            eyeX += WALK_STEPS[step][0];
            eyeZ += WALK_STEPS[step][1];
            String label = "the walk step " + (step + 1) + " to (" + eyeX + ", " + eyeZ + ")";
            int oldOriginChunkX = heights.originChunkX();
            int oldOriginChunkZ = heights.originChunkZ();
            short[] heightsBefore = heights.heights().clone();
            helper.assertFalse(heights.follow(eyeX, eyeZ), "The follow of " + label + " filled the window");
            assertRetainedColumnsKeepTheirHeights(helper, heights, heightsBefore, oldOriginChunkX, oldOriginChunkZ,
                    label);
            assertPendingTilesAreUnknown(helper, heights, label);
            readAll(helper, heights, lookup, label);
            assertMatchesWorld(helper, heights, lookup, label);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void unloadedChunksStayUnknownUntilTheyLoad(GameTestHelper helper) {
        SnowFieldReader.LevelChunkLookup realLookup = new SnowFieldReader.LevelChunkLookup();
        realLookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        int eyeX = eye.getX();
        int eyeZ = eye.getZ();
        int hiddenChunkX = (eyeX - CHUNK_WIDTH) >> CHUNK_AXIS_SHIFT;
        int hiddenChunkZ = eyeZ >> CHUNK_AXIS_SHIFT;
        if (realLookup.loadedChunk(hiddenChunkX, hiddenChunkZ) == null) {
            LOGGER.info("The chunk " + hiddenChunkX + " " + hiddenChunkZ + " that the hiding lookup hides is not "
                    + "loaded, so the checks after it is unhidden pass without a chunk to read");
        }
        FarHeights loaded = new FarHeights();
        HidingChunkLookup hidingLookup = new HidingChunkLookup(realLookup, hiddenChunkX, hiddenChunkZ);
        helper.assertTrue(loaded.follow(eyeX, eyeZ), "The first follow of the eye at " + eye
                + " did not fill the window of the hidden chunk");
        readAll(helper, loaded, hidingLookup, "the fill with the hidden chunk");
        assertMatchesWorld(helper, loaded, hidingLookup, "the fill with the hidden chunk");
        hidingLookup.unhide();
        loaded.onChunkLoaded(realLookup, hiddenChunkX, hiddenChunkZ);
        assertMatchesWorld(helper, loaded, realLookup, "the chunk unhidden and loaded again");
        helper.assertTrue(loaded.pendingTileCount() == 0, "The chunk unloaded and loaded again leaves "
                + loaded.pendingTileCount() + " tiles pending instead of the 0 from after the reads");

        FarHeights changed = new FarHeights();
        HidingChunkLookup secondHidingLookup = new HidingChunkLookup(realLookup, hiddenChunkX, hiddenChunkZ);
        helper.assertTrue(changed.follow(eyeX, eyeZ), "The first follow of the eye at " + eye
                + " did not fill the window of the changed chunk");
        readAll(helper, changed, secondHidingLookup, "the fill with the hidden chunk before the block change");
        assertMatchesWorld(helper, changed, secondHidingLookup, "the fill with the hidden chunk before the block "
                + "change");
        secondHidingLookup.unhide();
        changed.onBlockChanged(realLookup, hiddenChunkX * CHUNK_WIDTH + HIDDEN_COLUMN_LOCAL_X,
                hiddenChunkZ * CHUNK_WIDTH + HIDDEN_COLUMN_LOCAL_Z);
        assertMatchesWorld(helper, changed, realLookup, "the block change of the hidden chunk");
        helper.assertTrue(changed.pendingTileCount() == 0, "The block change of the hidden chunk leaves "
                + changed.pendingTileCount() + " tiles pending instead of the 0 from after the reads");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void blockChangesUpdateTheirColumn(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.assertTrue(heights.follow(eye.getX(), eye.getZ()), "The first follow of the eye at " + eye
                + " did not fill the window");
        readAll(helper, heights, lookup, "the fill before the block changes");
        heights.clearDirtyTiles();
        helper.assertTrue(heights.dirtyTileCount() == 0, "The cleared fill leaves " + heights.dirtyTileCount()
                + " dirty tiles instead of 0");
        BlockPos columnRelative = new BlockPos(1, 3, 1);
        BlockPos column = helper.absolutePos(columnRelative);
        int columnLocalX = column.getX() & CHUNK_LOCAL_MASK;
        int columnLocalZ = column.getZ() & CHUNK_LOCAL_MASK;
        int chunkX = column.getX() >> CHUNK_AXIS_SHIFT;
        int chunkZ = column.getZ() >> CHUNK_AXIS_SHIFT;
        int tile = FarHeights.tileOf(chunkX, chunkZ);
        LevelChunk chunk = lookup.loadedChunk(chunkX, chunkZ);
        helper.assertTrue(chunk != null, "The chunk (" + chunkX + ", " + chunkZ + ") of the column " + columnRelative
                + " is not loaded, so its heights cannot be checked");
        short motionBefore = heights.motionHeightAt(column.getX(), column.getZ());
        int surfaceBefore = heights.surfaceHeightOfChunk(chunkX, chunkZ);
        helper.assertTrue(surfaceBefore != FarHeights.NO_SURFACE, "The chunk (" + chunkX + ", " + chunkZ
                + ") of the column " + columnRelative + " has the surface " + surfaceBefore
                + " instead of a surface read from the world");
        helper.setBlock(columnRelative, Blocks.STONE.defaultBlockState());
        heights.onBlockChanged(lookup, column.getX(), column.getZ());
        int columnSurfaceAfterStone = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, columnLocalX, columnLocalZ) + 1;
        int expectedMotionAfterStone = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, columnLocalX, columnLocalZ) + 1;
        short motionAfterStone = heights.motionHeightAt(column.getX(), column.getZ());
        helper.assertTrue(motionAfterStone == expectedMotionAfterStone, "After the stone, the column "
                + columnRelative + " has the motion height " + motionAfterStone + " instead of the "
                + expectedMotionAfterStone + " of its chunk");
        int expectedSurfaceAfterStone = Math.max(surfaceBefore, columnSurfaceAfterStone);
        int surfaceAfterStone = heights.surfaceHeightOfChunk(chunkX, chunkZ);
        helper.assertTrue(surfaceAfterStone == expectedSurfaceAfterStone, "After the stone, the chunk (" + chunkX
                + ", " + chunkZ + ") has the surface " + surfaceAfterStone + " instead of the "
                + expectedSurfaceAfterStone + " of the larger of the surface before, " + surfaceBefore
                + ", and the surface " + columnSurfaceAfterStone + " of the column " + columnRelative);
        int dirtyAfterStone = heights.dirtyTileCount();
        if (motionAfterStone != motionBefore) {
            helper.assertTrue(heights.isTileDirty(tile), "The stone changed the motion height of the column "
                    + columnRelative + " from " + motionBefore + " to " + motionAfterStone
                    + " but does not leave its tile " + tile + " dirty");
            helper.assertTrue(dirtyAfterStone == 1, "The stone changed the motion height of the column "
                    + columnRelative + " from " + motionBefore + " to " + motionAfterStone + " and leaves "
                    + dirtyAfterStone + " dirty tiles instead of the 1 of its tile " + tile);
        } else {
            helper.assertTrue(dirtyAfterStone == 0, "The stone kept the motion height of the column " + columnRelative
                    + " at " + motionAfterStone + " and leaves " + dirtyAfterStone + " dirty tiles instead of 0");
        }
        helper.setBlock(columnRelative, Blocks.AIR.defaultBlockState());
        heights.onBlockChanged(lookup, column.getX(), column.getZ());
        int expectedMotionAfterAir = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, columnLocalX, columnLocalZ) + 1;
        short motionAfterAir = heights.motionHeightAt(column.getX(), column.getZ());
        helper.assertTrue(motionAfterAir == expectedMotionAfterAir, "After the air, the column " + columnRelative
                + " has the motion height " + motionAfterAir + " instead of the " + expectedMotionAfterAir
                + " of its chunk");
        int surfaceAfterAir = heights.surfaceHeightOfChunk(chunkX, chunkZ);
        helper.assertTrue(surfaceAfterAir == surfaceAfterStone, "After the air, the chunk (" + chunkX + ", " + chunkZ
                + ") has the surface " + surfaceAfterAir + " instead of the " + surfaceAfterStone
                + " from after the stone, since a block change never lowers the surface of its chunk");
        int dirtyBeforeOutside = heights.dirtyTileCount();
        heights.onBlockChanged(lookup, eye.getX() + OUTSIDE_COLUMN_OFFSET, eye.getZ());
        int dirtyAfterOutside = heights.dirtyTileCount();
        helper.assertTrue(dirtyAfterOutside == dirtyBeforeOutside, "The block change of the column "
                + OUTSIDE_COLUMN_OFFSET + " blocks beside the eye leaves " + dirtyAfterOutside + " dirty tiles "
                + "instead of the " + dirtyBeforeOutside + " from before, although the column is outside the window");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void chunkSentAgainIsReadAgain(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();
        lookup.setLevel(helper.getLevel());
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.assertTrue(heights.follow(eye.getX(), eye.getZ()), "The first follow of the eye at " + eye
                + " did not fill the window");
        readAll(helper, heights, lookup, "the fill before the chunks were sent again");
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos firstRelative = new BlockPos(0, 3, 0);
        BlockPos secondRelative = new BlockPos(2, 3, 2);
        helper.setBlock(firstRelative, stone);
        helper.setBlock(secondRelative, stone);
        BlockPos firstAbsolute = helper.absolutePos(firstRelative);
        BlockPos secondAbsolute = helper.absolutePos(secondRelative);
        heights.onChunkLoaded(lookup, firstAbsolute.getX() >> CHUNK_AXIS_SHIFT,
                firstAbsolute.getZ() >> CHUNK_AXIS_SHIFT);
        heights.onChunkLoaded(lookup, secondAbsolute.getX() >> CHUNK_AXIS_SHIFT,
                secondAbsolute.getZ() >> CHUNK_AXIS_SHIFT);
        assertMatchesWorld(helper, heights, lookup, "the chunks of the stone sent again");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmOperationsAllocateNothing(GameTestHelper helper) {
        FarHeights heights = new FarHeights();
        BlockPos eye = helper.absolutePos(new BlockPos(1, 2, 1));
        int eyeX = eye.getX();
        int eyeZ = eye.getZ();
        int eyeChunkX = eyeX >> CHUNK_AXIS_SHIFT;
        int eyeChunkZ = eyeZ >> CHUNK_AXIS_SHIFT;
        Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
        for (int chunkX = eyeChunkX - ALLOCATION_CACHED_REACH;
                chunkX <= eyeChunkX + ALLOCATION_CACHED_REACH + ALLOCATION_EXTRA_CHUNKS; chunkX++) {
            for (int chunkZ = eyeChunkZ - ALLOCATION_CACHED_REACH; chunkZ <= eyeChunkZ + ALLOCATION_CACHED_REACH;
                    chunkZ++) {
                chunks.put(ChunkPos.asLong(chunkX, chunkZ),
                        helper.getLevel().getChunkSource().getChunkNow(chunkX, chunkZ));
            }
        }
        CachingChunkLookup lookup = new CachingChunkLookup(chunks, helper.getLevel().getMinBuildHeight(),
                helper.getLevel().getMaxBuildHeight());
        int columnX = eye.getX();
        int columnZ = eye.getZ();
        helper.assertTrue(heights.follow(eyeX, eyeZ), "The first follow of the eye at " + eye
                + " did not fill the window");
        readAll(helper, heights, lookup, "the fill before the warm up");
        warmOperations(heights, lookup, eyeX, eyeZ, 1, ALLOCATION_WARM_STEPS, columnX, columnZ);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LOGGER.info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation of the warm "
                    + "operations is not measured");
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
            LOGGER.info("Thread allocated memory is not enabled, so the allocation of the warm operations is not "
                    + "measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int warmup = 0; warmup < ALLOCATION_BEAN_WARMUPS; warmup++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        long sum = warmOperations(heights, lookup, eyeX, eyeZ, ALLOCATION_WARM_STEPS + 1, ALLOCATION_MEASURED_STEPS,
                columnX, columnZ);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LOGGER.info("FarHeights warm operations allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + ALLOCATION_MEASURED_STEPS + " measured moves with their reads, "
                + "their block change, their chunk load, their highest surface and the "
                + ALLOCATION_HEIGHT_QUERIES + " column queries allocate " + allocated + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * The work of the allocation test: a few moves with their reads, one block change, one chunk load, the highest
     * surface and a thousand column queries, without an assertion or a message of its own, so that it can be measured.
     */
    private static long warmOperations(FarHeights heights, SnowFieldReader.ChunkLookup lookup, int eyeX, int eyeZ,
            int firstStep, int stepCount, int columnX, int columnZ) {
        long sum = 0;
        for (int step = firstStep; step < firstStep + stepCount; step++) {
            heights.follow(eyeX + step * CHUNK_WIDTH, eyeZ);
            sum += heights.readPending(lookup, TILE_BUDGET_FOR_A_STEP);
        }
        heights.onBlockChanged(lookup, columnX, columnZ);
        heights.onChunkLoaded(lookup, columnX >> CHUNK_AXIS_SHIFT, columnZ >> CHUNK_AXIS_SHIFT);
        sum += heights.highestSurface(lookup.minBuildHeight(), lookup.maxBuildHeight());
        for (int query = 0; query < ALLOCATION_HEIGHT_QUERIES; query++) {
            sum += heights.motionHeightAt(columnX + (query & 63) - 32, columnZ + ((query >> 6) & 63) - 32);
        }
        return sum;
    }

    /**
     * Reads until no tile is pending and checks that the reads end, failing after the given number of calls.
     */
    private static void readAll(GameTestHelper helper, FarHeights heights, SnowFieldReader.ChunkLookup lookup,
            String label) {
        for (int call = 0; call < MAXIMUM_READ_CALLS; call++) {
            if (heights.pendingTileCount() == 0) {
                return;
            }
            heights.readPending(lookup, READ_STEP);
        }
        helper.assertTrue(heights.pendingTileCount() == 0, "The reads of " + label + " leave "
                + heights.pendingTileCount() + " tiles pending after " + MAXIMUM_READ_CALLS + " calls of "
                + READ_STEP + " tiles instead of 0");
    }

    /**
     * Compares every column of the window with the world the lookup reads, the surface of every chunk of the window,
     * and the highest surface when the window waits for no read: the motion height of a column is UNKNOWN outside the
     * loaded chunks, the surface of a chunk is NO_SURFACE outside them, and the highest surface is the largest surface
     * of the loaded chunks, or the minimum build height when no chunk is loaded.
     */
    private static void assertMatchesWorld(GameTestHelper helper, FarHeights heights,
            SnowFieldReader.ChunkLookup lookup, String label) {
        int originChunkX = heights.originChunkX();
        int originChunkZ = heights.originChunkZ();
        int highestLoadedSurface = FarHeights.NO_SURFACE;
        int motionMismatchCount = 0;
        int surfaceMismatchCount = 0;
        int reportedCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int chunkZ = originChunkZ; chunkZ < originChunkZ + FarHeights.CHUNKS; chunkZ++) {
            for (int chunkX = originChunkX; chunkX < originChunkX + FarHeights.CHUNKS; chunkX++) {
                LevelChunk chunk = lookup.loadedChunk(chunkX, chunkZ);
                int chunkSurface = FarHeights.NO_SURFACE;
                for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                    for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                        int x = chunkX * CHUNK_WIDTH + localX;
                        int z = chunkZ * CHUNK_WIDTH + localZ;
                        short expectedHeight = FarHeights.UNKNOWN;
                        if (chunk != null) {
                            expectedHeight = (short) (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ)
                                    + 1);
                            chunkSurface = Math.max(chunkSurface,
                                    chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) + 1);
                        }
                        short actualHeight = heights.motionHeightAt(x, z);
                        if (actualHeight == expectedHeight) {
                            continue;
                        }
                        motionMismatchCount++;
                        if (reportedCount < REPORTED_MISMATCHES) {
                            reportedCount++;
                            mismatches.append(" (").append(x).append(", ").append(z).append(") ").append(actualHeight)
                                    .append("/").append(expectedHeight);
                        }
                    }
                }
                if (chunk != null) {
                    highestLoadedSurface = Math.max(highestLoadedSurface, chunkSurface);
                }
                int actualSurface = heights.surfaceHeightOfChunk(chunkX, chunkZ);
                if (actualSurface == chunkSurface) {
                    continue;
                }
                surfaceMismatchCount++;
                if (reportedCount < REPORTED_MISMATCHES) {
                    reportedCount++;
                    mismatches.append(" chunk (").append(chunkX).append(", ").append(chunkZ).append(") ")
                            .append(actualSurface).append("/").append(chunkSurface);
                }
            }
        }
        int highestMismatchCount = 0;
        if (!heights.isReadPending()) {
            int expectedHighest = highestLoadedSurface == FarHeights.NO_SURFACE ? lookup.minBuildHeight()
                    : highestLoadedSurface;
            int actualHighest = heights.highestSurface(lookup.minBuildHeight(), lookup.maxBuildHeight());
            if (actualHighest != expectedHighest) {
                highestMismatchCount++;
                if (reportedCount < REPORTED_MISMATCHES) {
                    mismatches.append(" highest ").append(actualHighest).append("/").append(expectedHighest);
                }
            }
        }
        helper.assertTrue(motionMismatchCount == 0 && surfaceMismatchCount == 0 && highestMismatchCount == 0,
                "In " + label + ", " + motionMismatchCount + " motion heights, " + surfaceMismatchCount
                        + " chunk surfaces and " + highestMismatchCount + " highest surfaces differ from the world, "
                        + "actual/expected:" + mismatches);
    }

    /**
     * Compares the motion heights of the columns that both the old and the new window hold with a copy taken before
     * the move.
     */
    private static void assertRetainedColumnsKeepTheirHeights(GameTestHelper helper, FarHeights heights,
            short[] heightsBefore, int oldOriginChunkX, int oldOriginChunkZ, String label) {
        short[] heightsArray = heights.heights();
        int retainedFirstX = Math.max(oldOriginChunkX, heights.originChunkX()) * CHUNK_WIDTH;
        int retainedLastX = Math.min(oldOriginChunkX, heights.originChunkX()) * CHUNK_WIDTH + FarHeights.SIZE - 1;
        int retainedFirstZ = Math.max(oldOriginChunkZ, heights.originChunkZ()) * CHUNK_WIDTH;
        int retainedLastZ = Math.min(oldOriginChunkZ, heights.originChunkZ()) * CHUNK_WIDTH + FarHeights.SIZE - 1;
        int compareCount = 0;
        int mismatchCount = 0;
        int reportedCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int z = retainedFirstZ; z <= retainedLastZ; z++) {
            for (int x = retainedFirstX; x <= retainedLastX; x++) {
                int texel = FarHeights.texelOf(x, z);
                compareCount++;
                if (heightsArray[texel] == heightsBefore[texel]) {
                    continue;
                }
                mismatchCount++;
                if (reportedCount < REPORTED_MISMATCHES) {
                    reportedCount++;
                    mismatches.append(" (").append(x).append(", ").append(z).append(") ").append(heightsArray[texel])
                            .append("/").append(heightsBefore[texel]);
                }
            }
        }
        helper.assertTrue(mismatchCount == 0, "In " + label + ", " + mismatchCount + " of the " + compareCount
                + " columns that both the old window at the chunk (" + oldOriginChunkX + ", " + oldOriginChunkZ
                + ") and the new window at the chunk (" + heights.originChunkX() + ", " + heights.originChunkZ()
                + ") hold changed their motion height across the move, actual/expected:" + mismatches);
    }

    /**
     * Checks that every column of every pending tile of the window holds UNKNOWN.
     */
    private static void assertPendingTilesAreUnknown(GameTestHelper helper, FarHeights heights, String label) {
        int originChunkX = heights.originChunkX();
        int originChunkZ = heights.originChunkZ();
        int pendingTileCount = 0;
        int checkedColumnCount = 0;
        int mismatchCount = 0;
        int reportedCount = 0;
        StringBuilder mismatches = new StringBuilder();
        for (int tile = 0; tile < FarHeights.TILES; tile++) {
            if (!heights.isTilePending(tile)) {
                continue;
            }
            pendingTileCount++;
            int chunkX = originChunkX + (((tile & 63) - originChunkX) & 63);
            int chunkZ = originChunkZ + (((tile >> 6) - originChunkZ) & 63);
            for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
                for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                    int x = chunkX * CHUNK_WIDTH + localX;
                    int z = chunkZ * CHUNK_WIDTH + localZ;
                    short actualHeight = heights.motionHeightAt(x, z);
                    checkedColumnCount++;
                    if (actualHeight == FarHeights.UNKNOWN) {
                        continue;
                    }
                    mismatchCount++;
                    if (reportedCount < REPORTED_MISMATCHES) {
                        reportedCount++;
                        mismatches.append(" (").append(x).append(", ").append(z).append(") ").append(actualHeight)
                                .append("/").append(FarHeights.UNKNOWN);
                    }
                }
            }
        }
        helper.assertTrue(mismatchCount == 0, "In " + label + ", " + mismatchCount + " of the " + checkedColumnCount
                + " columns of the " + pendingTileCount + " pending tiles are not " + FarHeights.UNKNOWN
                + ", actual/expected:" + mismatches);
    }

    /**
     * A chunk lookup that loads no chunk and reports the build heights of the overworld.
     */
    private static final class NullChunkLookup implements SnowFieldReader.ChunkLookup {
        private static final int MIN_BUILD_HEIGHT = -64;
        private static final int MAX_BUILD_HEIGHT = 320;

        @Nullable
        @Override
        public LevelChunk loadedChunk(int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public int minBuildHeight() {
            return MIN_BUILD_HEIGHT;
        }

        @Override
        public int maxBuildHeight() {
            return MAX_BUILD_HEIGHT;
        }
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
