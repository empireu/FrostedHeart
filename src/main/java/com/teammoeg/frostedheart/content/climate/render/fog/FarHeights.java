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

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The motion and surface heights of the window of chunks around the eye, which the fog's far rule reads.
 * The motion height of a column is its MOTION_BLOCKING heightmap value plus one, the first y at which the fog sees
 * open sky there, and the far rule compares the scene points of the column against it.
 * The surface height of a chunk is the largest WORLD_SURFACE value plus one over its columns, and highestSurface
 * gives the largest one of the window as the upper height of the vanilla fog bound.
 * The window holds CHUNKS by CHUNKS chunks from its origin chunk, which is the chunk corner nearest to the eye minus
 * CHUNKS / 2, so the window reaches at least 504 blocks from the eye on every side. A chunk position maps to the tile
 * of itself modulo CHUNKS, and a column to the texel of itself modulo SIZE, so the live array of heights is the layout
 * of a SIZE by SIZE texture that the fog reads.
 * UNKNOWN is the motion height of a column that no read covers, either because it is outside the window or because
 * its chunk was not loaded when its tile was read, and the far rule takes it as open, so an unknown column errs
 * toward fog.
 * A block change raises the surface height of its chunk and never lowers it, since lowering it would need a scan of
 * the whole chunk, while a too high upper height only makes the bound more careful.
 * A chunk that enters the window enters with every column UNKNOWN and a pending tile, and the reads of readPending
 * fill it. Everything is allocated in the constructor, and no operation allocates after it, unless a chunk has to
 * prime a heightmap it arrived without. Not thread safe.
 */
public final class FarHeights {
    /**
     * The number of chunks of the window on each axis.
     */
    public static final int CHUNKS = 64;
    /**
     * The number of columns of the window on each axis, which is also the size of the texture that holds them.
     */
    public static final int SIZE = 1024;
    /**
     * The number of tiles of the window, which is one per chunk of the window.
     */
    public static final int TILES = 4096;
    /**
     * The motion height of a column that no read covers, which the far rule takes as open.
     */
    public static final short UNKNOWN = Short.MIN_VALUE;
    /**
     * The surface height of a tile that holds no loaded chunk, which no column reaches.
     */
    public static final int NO_SURFACE = Integer.MIN_VALUE;

    private static final int CHUNK_WIDTH = 16;
    private static final int HALF_CHUNK_WIDTH = CHUNK_WIDTH / 2;
    private static final int CHUNK_SHIFT = 4;
    private static final int CHUNK_LOCAL_MASK = CHUNK_WIDTH - 1;
    private static final int TILE_MASK = CHUNKS - 1;
    private static final int TILE_ROW_SHIFT = 6;
    private static final int SIZE_MASK = SIZE - 1;
    private static final int SIZE_SHIFT = Integer.numberOfTrailingZeros(SIZE);
    private static final int WORD_COUNT = TILES >> 6;

    /**
     * The motion height of each column, indexed by its texel.
     */
    private final short[] heights = new short[SIZE * SIZE];
    /**
     * The surface height of each tile, from the largest of its columns, or NO_SURFACE.
     */
    private final int[] surfaceHeights = new int[TILES];
    private final long[] pendingTiles = new long[WORD_COUNT];
    private final long[] dirtyTiles = new long[WORD_COUNT];
    private int originChunkX;
    private int originChunkZ;
    private boolean placed;
    private boolean readsPending;
    /**
     * Whether a tile changed its surface height since the largest one was scanned, which needs a new scan.
     */
    private boolean surfaceScanPending;
    /**
     * The largest surface height of the last scan, or NO_SURFACE when no tile held one.
     */
    private int scannedSurface = NO_SURFACE;
    private long tilesReadCount;
    private long fillCountValue;

    /**
     * Creates an unplaced window that holds no height yet and marks every tile dirty, so that the first upload covers
     * the whole texture. Allocates the arrays of the window.
     */
    public FarHeights() {
        for (int texel = 0; texel < heights.length; texel++) {
            heights[texel] = UNKNOWN;
        }
        for (int tile = 0; tile < TILES; tile++) {
            surfaceHeights[tile] = NO_SURFACE;
        }
        for (int word = 0; word < WORD_COUNT; word++) {
            dirtyTiles[word] = -1L;
        }
        surfaceScanPending = true;
    }

    /**
     * Places the window around the eye's chunk, or moves it. A window that is not placed yet, or whose origin would
     * move by CHUNKS chunks or more along an axis, is filled: every tile is reset, pending and dirty. Otherwise only
     * the tiles of the chunks that enter are reset and made pending, and the other tiles keep their heights.
     * Allocates nothing.
     * @param eyeX the world x of the eye block
     * @param eyeZ the world z of the eye block
     * @return whether the window was filled instead of moved
     */
    public boolean follow(int eyeX, int eyeZ) {
        int newOriginChunkX = Math.floorDiv(eyeX + HALF_CHUNK_WIDTH, CHUNK_WIDTH) - CHUNKS / 2;
        int newOriginChunkZ = Math.floorDiv(eyeZ + HALF_CHUNK_WIDTH, CHUNK_WIDTH) - CHUNKS / 2;
        if (!placed || Math.abs((long) newOriginChunkX - originChunkX) >= CHUNKS
                || Math.abs((long) newOriginChunkZ - originChunkZ) >= CHUNKS) {
            originChunkX = newOriginChunkX;
            originChunkZ = newOriginChunkZ;
            placed = true;
            fillCountValue++;
            for (int tile = 0; tile < TILES; tile++) {
                resetTile(tile);
                markTilePending(tile);
            }
            readsPending = true;
            return true;
        }
        if (newOriginChunkX == originChunkX && newOriginChunkZ == originChunkZ) {
            return false;
        }
        for (int offsetZ = 0; offsetZ < CHUNKS; offsetZ++) {
            int chunkZ = newOriginChunkZ + offsetZ;
            int oldOffsetZ = chunkZ - originChunkZ;
            boolean retainedZ = oldOffsetZ >= 0 && oldOffsetZ < CHUNKS;
            for (int offsetX = 0; offsetX < CHUNKS; offsetX++) {
                int chunkX = newOriginChunkX + offsetX;
                int oldOffsetX = chunkX - originChunkX;
                if (retainedZ && oldOffsetX >= 0 && oldOffsetX < CHUNKS) {
                    continue;
                }
                int tile = tileOf(chunkX, chunkZ);
                resetTile(tile);
                markTilePending(tile);
            }
        }
        originChunkX = newOriginChunkX;
        originChunkZ = newOriginChunkZ;
        readsPending = true;
        return false;
    }

    /**
     * Forgets the window, so that the next follow fills it and every column reads UNKNOWN until the reads of that
     * fill arrive.
     */
    public void forget() {
        placed = false;
    }

    /**
     * Reads at most the given number of pending tiles, in the order of their bits, and clears the pending reads flag
     * once no tile is pending any more. Allocates nothing.
     * @param lookup the chunks to read the heights through
     * @param maximumTiles the number of tiles this call may read, at least 0
     * @return the number of tiles read, which is 0 for a budget of 0 or less
     */
    public int readPending(SnowFieldReader.ChunkLookup lookup, int maximumTiles) {
        if (maximumTiles <= 0) {
            return 0;
        }
        int readCount = 0;
        while (readCount < maximumTiles) {
            int tile = takePendingTile();
            if (tile < 0) {
                break;
            }
            readTile(lookup, tile);
            readCount++;
        }
        if (readsPending && !hasPendingTile()) {
            readsPending = false;
        }
        return readCount;
    }

    /**
     * Reads the tile of a chunk that arrived in the window, a new one or one sent again, whether the tile was pending
     * or not. Allocates nothing.
     * @param lookup the chunks to read the heights through
     * @param chunkX the chunk x
     * @param chunkZ the chunk z
     */
    public void onChunkLoaded(SnowFieldReader.ChunkLookup lookup, int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return;
        }
        readTile(lookup, tileOf(chunkX, chunkZ));
    }

    /**
     * Applies a block change to the column of the block when the column is in the window, its tile is not pending and
     * its chunk is loaded. A tile whose surface is not known yet is read whole, since the change may be the first sign
     * of a loaded chunk; otherwise this column is read and the surface height of the tile is raised to the height of
     * the column when that is larger. Allocates nothing.
     * @param lookup the chunks to read the heights through
     * @param x the world x of the changed block
     * @param z the world z of the changed block
     */
    public void onBlockChanged(SnowFieldReader.ChunkLookup lookup, int x, int z) {
        if (!contains(x, z)) {
            return;
        }
        int chunkX = x >> CHUNK_SHIFT;
        int chunkZ = z >> CHUNK_SHIFT;
        int tile = tileOf(chunkX, chunkZ);
        if (isTilePending(tile)) {
            return;
        }
        LevelChunk chunk = lookup.loadedChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        if (surfaceHeights[tile] == NO_SURFACE) {
            readTile(lookup, tile);
            return;
        }
        int localX = x & CHUNK_LOCAL_MASK;
        int localZ = z & CHUNK_LOCAL_MASK;
        int motionHeight = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ) + 1;
        int texel = texelOf(x, z);
        if (heights[texel] != motionHeight) {
            heights[texel] = (short) motionHeight;
            markTileDirty(tile);
        }
        int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) + 1;
        if (surfaceHeight > surfaceHeights[tile]) {
            setSurfaceHeight(tile, surfaceHeight);
        }
    }

    /**
     * The motion height of a column, which is the MOTION_BLOCKING height of its chunk plus one, the first y at which
     * the fog sees open sky there.
     * @param x the world x
     * @param z the world z
     * @return the height in blocks, or UNKNOWN outside the window
     */
    public short motionHeightAt(int x, int z) {
        if (!contains(x, z)) {
            return UNKNOWN;
        }
        return heights[texelOf(x, z)];
    }

    /**
     * Whether the window covers a column. A window that is not placed covers none.
     * @param x the world x
     * @param z the world z
     * @return whether the column (x, z) is in the window
     */
    public boolean contains(int x, int z) {
        return containsChunk(x >> CHUNK_SHIFT, z >> CHUNK_SHIFT);
    }

    /**
     * The upper height of the vanilla fog bound: the largest surface height of the window, or the maximum build
     * height while the reads of a fill or of a move are pending, since the tiles they reset hold no height yet and
     * the bound must not assume low terrain there. Allocates nothing and scans the tiles only after a surface changed.
     * @param minBuildHeight the lowest y of the world that holds blocks
     * @param maxBuildHeight the lowest y at or above which the world holds no blocks
     * @return the height in blocks, or minBuildHeight when no tile of the window holds a surface
     */
    public int highestSurface(int minBuildHeight, int maxBuildHeight) {
        if (readsPending) {
            return maxBuildHeight;
        }
        if (surfaceScanPending) {
            int highest = NO_SURFACE;
            for (int tile = 0; tile < TILES; tile++) {
                if (surfaceHeights[tile] > highest) {
                    highest = surfaceHeights[tile];
                }
            }
            scannedSurface = highest;
            surfaceScanPending = false;
        }
        return scannedSurface == NO_SURFACE ? minBuildHeight : scannedSurface;
    }

    /**
     * The surface height of a chunk of the window, which is the largest WORLD_SURFACE value of its columns plus one.
     * @param chunkX the chunk x
     * @param chunkZ the chunk z
     * @return the height in blocks, or NO_SURFACE outside the window and for a chunk that was not loaded when its tile
     *         was read
     */
    public int surfaceHeightOfChunk(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return NO_SURFACE;
        }
        return surfaceHeights[tileOf(chunkX, chunkZ)];
    }

    /**
     * The tile of a chunk position, which is its x modulo CHUNKS and its z modulo CHUNKS, x fastest. It needs no
     * placed window.
     * @param chunkX the chunk x
     * @param chunkZ the chunk z
     * @return the tile index, from 0 to TILES - 1
     */
    public static int tileOf(int chunkX, int chunkZ) {
        return (chunkX & TILE_MASK) | ((chunkZ & TILE_MASK) << TILE_ROW_SHIFT);
    }

    /**
     * The texel of a column in the SIZE by SIZE texture of the heights, which is its x modulo SIZE and its z modulo
     * SIZE, x fastest.
     * @param x the world x
     * @param z the world z
     * @return the index into heights(), from 0 to SIZE * SIZE - 1
     */
    public static int texelOf(int x, int z) {
        return (x & SIZE_MASK) | ((z & SIZE_MASK) << SIZE_SHIFT);
    }

    /**
     * The live array of heights in the texel layout of the texture, for the uploader. It is reused by this window and
     * changes with every read.
     * @return the array of SIZE * SIZE heights, which the caller must not write
     */
    public short[] heights() {
        return heights;
    }

    /**
     * Whether the motion heights of a tile changed since the last clearDirtyTiles, so that the uploader must upload
     * it.
     * @param tile the tile index, from 0 to TILES - 1
     * @return whether the tile is dirty
     */
    public boolean isTileDirty(int tile) {
        return (dirtyTiles[tile >> 6] & (1L << (tile & 63))) != 0L;
    }

    /**
     * The number of dirty tiles, which allocates nothing.
     * @return the number of tiles whose heights changed since the last clearDirtyTiles
     */
    public int dirtyTileCount() {
        int count = 0;
        for (int word = 0; word < WORD_COUNT; word++) {
            count += Long.bitCount(dirtyTiles[word]);
        }
        return count;
    }

    /**
     * Marks every tile clean, after the uploader has uploaded them.
     */
    public void clearDirtyTiles() {
        for (int word = 0; word < WORD_COUNT; word++) {
            dirtyTiles[word] = 0L;
        }
    }

    /**
     * Whether a tile waits to be read.
     * @param tile the tile index, from 0 to TILES - 1
     * @return whether the tile is pending
     */
    public boolean isTilePending(int tile) {
        return (pendingTiles[tile >> 6] & (1L << (tile & 63))) != 0L;
    }

    /**
     * The number of tiles that wait to be read, which allocates nothing.
     * @return the number of pending tiles
     */
    public int pendingTileCount() {
        int count = 0;
        for (int word = 0; word < WORD_COUNT; word++) {
            count += Long.bitCount(pendingTiles[word]);
        }
        return count;
    }

    /**
     * Whether the window waits for the reads of a fill or of a move, during which highestSurface returns the maximum
     * build height.
     */
    public boolean isReadPending() {
        return readsPending;
    }

    /**
     * Whether the window holds a position, which it does after the first follow and until forget.
     * @return whether the origin chunk is meaningful
     */
    public boolean isPlaced() {
        return placed;
    }

    /**
     * The first chunk of the window in x, or 0 before the window is placed.
     * @return the origin chunk x
     */
    public int originChunkX() {
        return originChunkX;
    }

    /**
     * The first chunk of the window in z, or 0 before the window is placed.
     * @return the origin chunk z
     */
    public int originChunkZ() {
        return originChunkZ;
    }

    /**
     * The number of tiles read since this window was created, one per tile read whether its chunk was loaded or not,
     * for the debug command.
     * @return the number of tile reads, which only grows
     */
    public long tilesRead() {
        return tilesReadCount;
    }

    /**
     * The number of fills since this window was created, for the debug command.
     * @return the number of fill calls, which only grows
     */
    public long fillCount() {
        return fillCountValue;
    }

    /**
     * Whether a chunk position is inside the window.
     */
    private boolean containsChunk(int chunkX, int chunkZ) {
        if (!placed) {
            return false;
        }
        int offsetX = chunkX - originChunkX;
        int offsetZ = chunkZ - originChunkZ;
        return offsetX >= 0 && offsetX < CHUNKS && offsetZ >= 0 && offsetZ < CHUNKS;
    }

    /**
     * Takes the first pending tile out of the pending set.
     * @return the tile index, or -1 when no tile is pending
     */
    private int takePendingTile() {
        for (int word = 0; word < WORD_COUNT; word++) {
            long bits = pendingTiles[word];
            if (bits == 0L) {
                continue;
            }
            pendingTiles[word] = bits & (bits - 1L);
            return (word << 6) + Long.numberOfTrailingZeros(bits);
        }
        return -1;
    }

    /**
     * Whether any tile is pending.
     */
    private boolean hasPendingTile() {
        for (int word = 0; word < WORD_COUNT; word++) {
            if (pendingTiles[word] != 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the tile of the chunk that the current window maps it to, marks the tile dirty when a motion height of it
     * changed, and makes its surface height the largest one of its columns. A tile whose chunk is not loaded is reset,
     * and either way the tile is no longer pending.
     */
    private void readTile(SnowFieldReader.ChunkLookup lookup, int tile) {
        pendingTiles[tile >> 6] &= ~(1L << (tile & 63));
        tilesReadCount++;
        int chunkX = originChunkX + (((tile & TILE_MASK) - originChunkX) & TILE_MASK);
        int chunkZ = originChunkZ + (((tile >> TILE_ROW_SHIFT) - originChunkZ) & TILE_MASK);
        LevelChunk chunk = lookup.loadedChunk(chunkX, chunkZ);
        if (chunk == null) {
            resetTile(tile);
            return;
        }
        int firstX = (tile & TILE_MASK) * CHUNK_WIDTH;
        int firstZ = (tile >> TILE_ROW_SHIFT) * CHUNK_WIDTH;
        int highestSurface = NO_SURFACE;
        boolean changed = false;
        for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
            int rowTexel = texelOf(firstX, firstZ + localZ);
            for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                short motionHeight = (short) (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ) + 1);
                int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) + 1;
                if (heights[rowTexel + localX] != motionHeight) {
                    heights[rowTexel + localX] = motionHeight;
                    changed = true;
                }
                if (surfaceHeight > highestSurface) {
                    highestSurface = surfaceHeight;
                }
            }
        }
        if (changed) {
            markTileDirty(tile);
        }
        setSurfaceHeight(tile, highestSurface);
    }

    /**
     * Makes every motion height of a tile UNKNOWN and its surface NO_SURFACE, and marks it dirty.
     */
    private void resetTile(int tile) {
        int firstX = (tile & TILE_MASK) * CHUNK_WIDTH;
        int firstZ = (tile >> TILE_ROW_SHIFT) * CHUNK_WIDTH;
        for (int localZ = 0; localZ < CHUNK_WIDTH; localZ++) {
            int rowTexel = texelOf(firstX, firstZ + localZ);
            for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                heights[rowTexel + localX] = UNKNOWN;
            }
        }
        setSurfaceHeight(tile, NO_SURFACE);
        markTileDirty(tile);
    }

    /**
     * Sets the surface height of a tile, which makes the next highestSurface scan the tiles again when it changed.
     */
    private void setSurfaceHeight(int tile, int surfaceHeight) {
        if (surfaceHeights[tile] != surfaceHeight) {
            surfaceHeights[tile] = surfaceHeight;
            surfaceScanPending = true;
        }
    }

    /**
     * Marks a tile pending.
     */
    private void markTilePending(int tile) {
        pendingTiles[tile >> 6] |= 1L << (tile & 63);
    }

    /**
     * Marks a tile dirty.
     */
    private void markTileDirty(int tile) {
        dirtyTiles[tile >> 6] |= 1L << (tile & 63);
    }
}
