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

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * Reads a snow field from the world, which is the side of SnowField that knows about the level.
 * A box is a range of world cells that waits to be read, and the boxes are a ring of BOX_CAPACITY entries that are read
 * in the order they were queued. A fill queues the whole cube as one box, a move queues the cells of the new cube that
 * were not in the old one as up to three disjoint boxes, and a chunk load queues the columns of that chunk over the
 * whole height of the cube. A box is clipped to the cube when it is queued, and the cells of a box that left the cube
 * before it was read are skipped while it is read. When the ring is full it is emptied and the whole cube is queued
 * instead, which reads cells twice but keeps the field correct, since SnowField.setCell ignores a read cell whose mask
 * set is unchanged.
 * A read uses at most the given number of steps, one per cell passed to SnowField.setCell and one per chunk column of a
 * box that is skipped because its chunk is not loaded, and setting column heights uses none. It reads the cells of a
 * box by chunk column, then by section from the top down, then by y from the top down, then by z ascending, then by x
 * ascending. No cell of the field is shown before every read is done and the solver has drained, so the order only
 * decides how much work the solver has: from the top down each column is seeded before the cells below it are read,
 * which lets the solver fill the column in one pass, while a roof read after the cells below it resets them.
 * The position inside the box, the chunk column, the row and the cell is kept between calls, so a read may stop at any
 * cell and continue in the next call. The chunk and the section are fetched again for every row, and the mask sets are
 * loaded again whenever the section object differs, so a chunk that was unloaded or sent again between two calls is
 * followed without a lock or a copy.
 * A chunk that is not loaded when its cells are due is skipped and its cells stay unread, with every face closed, until
 * a chunk load queues it again.
 * A read that empties the ring, and a read of an empty ring, call SnowField.readsComplete, which lets the solved box
 * grow once the solver drains, and a move or a fill sets the reads pending again.
 * Everything is allocated in the constructor, and the reads allocate nothing after the first lookup of each block
 * state, which SnowFaces may allocate for. Not thread safe.
 */
public final class SnowFieldReader {
    /**
     * The number of boxes the ring holds, after which a new box empties the ring and queues the whole cube.
     */
    public static final int BOX_CAPACITY = 64;

    private static final int SECTION_SIZE = 16;
    private static final int SECTION_MASK = SECTION_SIZE - 1;
    private static final int SECTION_SHIFT = 4;

    /**
     * The chunks, heights and build limits the reader reads through.
     */
    public interface ChunkLookup {
        /**
         * The chunk at a position, without loading it.
         * @param chunkX the chunk x
         * @param chunkZ the chunk z
         * @return the loaded chunk, or null when the chunk is not loaded
         */
        @Nullable
        LevelChunk loadedChunk(int chunkX, int chunkZ);

        /**
         * The lowest y of the world that holds blocks, as Level.getMinBuildHeight gives it.
         * @return the minimum build height in blocks
         */
        int minBuildHeight();

        /**
         * The lowest y at or above which the world holds no blocks, as Level.getMaxBuildHeight gives it.
         * @return the maximum build height in blocks, which is exclusive
         */
        int maxBuildHeight();
    }

    /**
     * A chunk lookup of the level it was given and of nothing else, with no level before setLevel.
     */
    public static final class LevelChunkLookup implements ChunkLookup {
        @Nullable
        private Level level;

        /**
         * Creates a lookup with no level, which reports no chunks and the build height 0.
         */
        public LevelChunkLookup() {
        }

        /**
         * Makes a level the one this lookup reads, or forgets the level when it is null.
         * @param level the client level to read, or null
         */
        public void setLevel(@Nullable Level level) {
            this.level = level;
        }

        /**
         * The level this lookup reads.
         * @return the level, or null before setLevel
         */
        @Nullable
        public Level level() {
            return level;
        }

        @Nullable
        @Override
        public LevelChunk loadedChunk(int chunkX, int chunkZ) {
            Level current = level;
            return current != null ? current.getChunkSource().getChunkNow(chunkX, chunkZ) : null;
        }

        @Override
        public int minBuildHeight() {
            Level current = level;
            return current != null ? current.getMinBuildHeight() : 0;
        }

        @Override
        public int maxBuildHeight() {
            Level current = level;
            return current != null ? current.getMaxBuildHeight() : 0;
        }
    }

    private final SnowField field;
    private final SnowFaces faces;
    private final SnowSectionMasks masks;
    private final int[] boxMinimumXs = new int[BOX_CAPACITY];
    private final int[] boxMaximumXs = new int[BOX_CAPACITY];
    private final int[] boxMinimumYs = new int[BOX_CAPACITY];
    private final int[] boxMaximumYs = new int[BOX_CAPACITY];
    private final int[] boxMinimumZs = new int[BOX_CAPACITY];
    private final int[] boxMaximumZs = new int[BOX_CAPACITY];
    private int boxHead;
    private int boxCount;
    /**
     * The chunk column of the head box that the cursor reads, and whether that column was entered.
     */
    private boolean hasCursor;
    private boolean columnStarted;
    private int cursorChunkX;
    private int cursorChunkZ;
    /**
     * The next row of the chunk column to read, and the next cell of that row, in world coordinates.
     */
    private int cursorY;
    private int cursorX;
    private int cursorZ;
    /**
     * The rows of the current chunk column of a box, clipped to the cube, and the x and z of the cells of those rows.
     */
    private int columnLowY;
    private int columnHighY;
    private int rowFirstX;
    private int rowLastX;
    private int rowFirstZ;
    private int rowLastZ;
    /**
     * The section the mask sets were loaded for, or null.
     */
    @Nullable
    private LevelChunkSection loadedSection;
    private long cellsRead;
    private long chunkColumnsSkipped;
    private long boxOverflowCount;

    /**
     * Creates a reader of a field, which reads through the lookup every method that touches the world takes.
     * @param field the field to read cells, column heights and build limits into
     * @param faces the table the mask sets come from, which must be the one the field was created with
     */
    public SnowFieldReader(SnowField field, SnowFaces faces) {
        this.field = field;
        this.faces = faces;
        this.masks = new SnowSectionMasks(faces);
    }

    /**
     * The field this reader fills.
     * @return the field this reader was created with
     */
    public SnowField field() {
        return field;
    }

    /**
     * Fills the field at the eye and queues the whole cube. Allocates nothing.
     * @param eyeX the world x of the eye block
     * @param eyeY the world y of the eye block
     * @param eyeZ the world z of the eye block
     */
    public void fill(int eyeX, int eyeY, int eyeZ) {
        field.fill(eyeX, eyeY, eyeZ);
        clearBoxes();
        masks.forget();
        queueWholeCube();
    }

    /**
     * Moves the field to the eye, and queues the cells that enter the cube. Fills the field and queues the whole cube
     * when the field has no position yet or the eye jumped more than SnowField.FILL_JUMP blocks on an axis.
     * Allocates nothing.
     * @param eyeX the world x of the eye block
     * @param eyeY the world y of the eye block
     * @param eyeZ the world z of the eye block
     * @return whether the field was filled instead of moved
     */
    public boolean follow(int eyeX, int eyeY, int eyeZ) {
        int oldOriginX = field.originX();
        int oldOriginY = field.originY();
        int oldOriginZ = field.originZ();
        if (field.moveTo(eyeX, eyeY, eyeZ)) {
            clearBoxes();
            masks.forget();
            queueWholeCube();
            return true;
        }
        int newOriginX = field.originX();
        int newOriginY = field.originY();
        int newOriginZ = field.originZ();
        if (newOriginX == oldOriginX && newOriginY == oldOriginY && newOriginZ == oldOriginZ) {
            return false;
        }
        int size = field.size();
        int oldMaximumX = oldOriginX + size - 1;
        int oldMaximumY = oldOriginY + size - 1;
        int oldMaximumZ = oldOriginZ + size - 1;
        int newMaximumX = newOriginX + size - 1;
        int newMaximumY = newOriginY + size - 1;
        int newMaximumZ = newOriginZ + size - 1;
        int retainedFirstX = Math.max(newOriginX, oldOriginX);
        int retainedLastX = Math.min(newMaximumX, oldMaximumX);
        int retainedFirstY = Math.max(newOriginY, oldOriginY);
        int retainedLastY = Math.min(newMaximumY, oldMaximumY);
        int retainedFirstZ = Math.max(newOriginZ, oldOriginZ);
        int retainedLastZ = Math.min(newMaximumZ, oldMaximumZ);
        // The cells whose x is outside the old cube, over the whole y and z of the new cube:
        queueBox(newOriginX, newOriginY, newOriginZ, retainedFirstX - 1, newMaximumY, newMaximumZ);
        queueBox(retainedLastX + 1, newOriginY, newOriginZ, newMaximumX, newMaximumY, newMaximumZ);
        // Of the rest, the cells whose y is outside the old cube, over the whole z of the new cube:
        queueBox(retainedFirstX, newOriginY, newOriginZ, retainedLastX, retainedFirstY - 1, newMaximumZ);
        queueBox(retainedFirstX, retainedLastY + 1, newOriginZ, retainedLastX, newMaximumY, newMaximumZ);
        // Of the rest, the cells whose z is outside the old cube:
        queueBox(retainedFirstX, retainedFirstY, newOriginZ, retainedLastX, retainedLastY, retainedFirstZ - 1);
        queueBox(retainedFirstX, retainedFirstY, retainedLastZ + 1, retainedLastX, retainedLastY, newMaximumZ);
        return false;
    }

    /**
     * Queues the columns of a chunk that arrived, a new one or one sent again, over the whole height of the cube, and
     * queues nothing before the field has a position. A chunk whose columns miss the cube is ignored, and the cells
     * that enter the cube later are queued by the move that brings them in. Allocates nothing.
     * @param chunkX the chunk x
     * @param chunkZ the chunk z
     */
    public void queueChunk(int chunkX, int chunkZ) {
        if (!field.hasPosition()) {
            return;
        }
        // A chunk sent again refills its palettes in place and keeps their data objects, so a table built before is
        // stale and only this call can tell:
        masks.forget();
        loadedSection = null;
        int minimumX = chunkX << SECTION_SHIFT;
        int minimumZ = chunkZ << SECTION_SHIFT;
        int maximumY = field.originY() + field.size() - 1;
        queueBox(minimumX, field.originY(), minimumZ, minimumX + SECTION_SIZE - 1, maximumY,
                minimumZ + SECTION_SIZE - 1);
    }

    /**
     * Reads cells of the queued boxes, from the top down, and returns the steps it used. A call may stop at any cell
     * and the next call continues there, and it calls SnowField.readsComplete when the ring is empty at its end.
     * Allocates nothing.
     * @param lookup the chunks, heights and build limits to read
     * @param maximumSteps the number of steps this call may use, at least 0
     * @return the number of steps used, which is at most maximumSteps, and 0 for a budget of 0 or less
     */
    public int read(ChunkLookup lookup, int maximumSteps) {
        if (maximumSteps <= 0) {
            return 0;
        }
        int minimumBuildHeight = lookup.minBuildHeight();
        int maximumBuildHeight = lookup.maxBuildHeight();
        int steps = 0;
        if (hasCursor && columnStarted && lookup.loadedChunk(cursorChunkX, cursorChunkZ) == null) {
            // The chunk of the column was unloaded since the last call, so the rest of the column is skipped:
            steps++;
            chunkColumnsSkipped++;
            columnStarted = false;
            if (!advanceChunkColumn()) {
                field.readsComplete();
                return steps;
            }
        }
        while (steps < maximumSteps) {
            if (boxCount == 0) {
                break;
            }
            if (!hasCursor) {
                startHeadBox();
            }
            if (!columnStarted) {
                if (!enterChunkColumn(lookup)) {
                    steps++;
                    chunkColumnsSkipped++;
                    if (!advanceChunkColumn()) {
                        break;
                    }
                    continue;
                }
                columnStarted = true;
                continue;
            }
            if (cursorY < columnLowY) {
                columnStarted = false;
                if (!advanceChunkColumn()) {
                    break;
                }
                continue;
            }
            LevelChunk chunk = lookup.loadedChunk(cursorChunkX, cursorChunkZ);
            if (chunk == null) {
                steps++;
                chunkColumnsSkipped++;
                columnStarted = false;
                if (!advanceChunkColumn()) {
                    break;
                }
                continue;
            }
            boolean usesSection = cursorY >= minimumBuildHeight && cursorY < maximumBuildHeight;
            int outsideWorldMaskSet = cursorY >= maximumBuildHeight ? SnowFaces.EMPTY_MASK_SET
                    : SnowFaces.FULL_MASK_SET;
            if (usesSection) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(cursorY >> SECTION_SHIFT));
                // A chunk unloaded and loaded again between two calls is a new object with new sections:
                if (section != loadedSection) {
                    masks.load(section);
                    loadedSection = section;
                }
            }
            int localY = cursorY & SECTION_MASK;
            while (cursorZ <= rowLastZ) {
                while (cursorX <= rowLastX) {
                    if (steps >= maximumSteps) {
                        return steps;
                    }
                    int maskSet = usesSection
                            ? masks.maskSetAt(cursorX & SECTION_MASK, localY, cursorZ & SECTION_MASK)
                            : outsideWorldMaskSet;
                    field.setCell(cursorX, cursorY, cursorZ, maskSet);
                    steps++;
                    cellsRead++;
                    cursorX++;
                }
                cursorX = rowFirstX;
                cursorZ++;
            }
            cursorY--;
            cursorZ = rowFirstZ;
            cursorX = rowFirstX;
        }
        if (boxCount == 0) {
            field.readsComplete();
        }
        return steps;
    }

    /**
     * Applies a block change of the tracked level: the height of its column from the chunk's heightmap, then the mask
     * set of its new state, when the cell is in the cube and its chunk is loaded. A cell that still waits in a box is
     * read here, and its later read finds the same mask set and is ignored. Allocates nothing after the first lookup of
     * its block state.
     * @param lookup the chunks, heights and build limits to read
     * @param x the world x of the changed block
     * @param y the world y of the changed block
     * @param z the world z of the changed block
     * @param newState the block state the block has now
     */
    public void onBlockChanged(ChunkLookup lookup, int x, int y, int z, BlockState newState) {
        if (!field.contains(x, y, z)) {
            return;
        }
        LevelChunk chunk = lookup.loadedChunk(x >> SECTION_SHIFT, z >> SECTION_SHIFT);
        if (chunk == null) {
            return;
        }
        field.setColumnHeight(x, z,
                chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & SECTION_MASK, z & SECTION_MASK) + 1);
        field.setCell(x, y, z, faces.maskSetOf(newState));
    }

    /**
     * Whether no box waits to be read.
     * @return whether the ring is empty
     */
    public boolean isIdle() {
        return boxCount == 0;
    }

    /**
     * The number of boxes that wait to be read.
     * @return the number of boxes in the ring, from 0 to BOX_CAPACITY
     */
    public int queuedBoxCount() {
        return boxCount;
    }

    /**
     * The number of cells the reads passed to SnowField.setCell since this reader was created.
     * @return the number of cells read, which only grows
     */
    public long cellsRead() {
        return cellsRead;
    }

    /**
     * The number of chunk columns the reads skipped because their chunk was not loaded since this reader was created.
     * @return the number of skipped chunk columns, which only grows
     */
    public long chunkColumnsSkipped() {
        return chunkColumnsSkipped;
    }

    /**
     * The number of boxes that found the ring full since this reader was created.
     * @return the number of overflows, which only grows
     */
    public long boxOverflowCount() {
        return boxOverflowCount;
    }

    /**
     * Queues a box of world cells, clipped to the current cube. An empty box is dropped, and a full ring is emptied
     * and the whole cube queued instead.
     */
    private void queueBox(int minimumX, int minimumY, int minimumZ, int maximumX, int maximumY, int maximumZ) {
        if (!field.hasPosition()) {
            return;
        }
        int cubeMinimumX = field.originX();
        int cubeMinimumY = field.originY();
        int cubeMinimumZ = field.originZ();
        int cubeMaximumX = cubeMinimumX + field.size() - 1;
        int cubeMaximumY = cubeMinimumY + field.size() - 1;
        int cubeMaximumZ = cubeMinimumZ + field.size() - 1;
        int clippedMinimumX = Math.max(minimumX, cubeMinimumX);
        int clippedMinimumY = Math.max(minimumY, cubeMinimumY);
        int clippedMinimumZ = Math.max(minimumZ, cubeMinimumZ);
        int clippedMaximumX = Math.min(maximumX, cubeMaximumX);
        int clippedMaximumY = Math.min(maximumY, cubeMaximumY);
        int clippedMaximumZ = Math.min(maximumZ, cubeMaximumZ);
        if (clippedMinimumX > clippedMaximumX || clippedMinimumY > clippedMaximumY
                || clippedMinimumZ > clippedMaximumZ) {
            return;
        }
        if (boxCount >= BOX_CAPACITY) {
            boxOverflowCount++;
            clearBoxes();
            clippedMinimumX = cubeMinimumX;
            clippedMinimumY = cubeMinimumY;
            clippedMinimumZ = cubeMinimumZ;
            clippedMaximumX = cubeMaximumX;
            clippedMaximumY = cubeMaximumY;
            clippedMaximumZ = cubeMaximumZ;
        }
        int slot = boxHead + boxCount;
        if (slot >= BOX_CAPACITY) {
            slot -= BOX_CAPACITY;
        }
        boxMinimumXs[slot] = clippedMinimumX;
        boxMinimumYs[slot] = clippedMinimumY;
        boxMinimumZs[slot] = clippedMinimumZ;
        boxMaximumXs[slot] = clippedMaximumX;
        boxMaximumYs[slot] = clippedMaximumY;
        boxMaximumZs[slot] = clippedMaximumZ;
        boxCount++;
    }

    /**
     * Queues the whole cube as one box.
     */
    private void queueWholeCube() {
        int maximumOffset = field.size() - 1;
        queueBox(field.originX(), field.originY(), field.originZ(), field.originX() + maximumOffset,
                field.originY() + maximumOffset, field.originZ() + maximumOffset);
    }

    /**
     * Empties the ring and forgets the cursor.
     */
    private void clearBoxes() {
        boxHead = 0;
        boxCount = 0;
        hasCursor = false;
        columnStarted = false;
        loadedSection = null;
    }

    /**
     * Removes the head box from the ring and forgets the cursor.
     */
    private void popHeadBox() {
        boxHead++;
        if (boxHead >= BOX_CAPACITY) {
            boxHead = 0;
        }
        boxCount--;
        hasCursor = false;
        columnStarted = false;
    }

    /**
     * Starts the cursor at the first chunk column of the head box.
     */
    private void startHeadBox() {
        hasCursor = true;
        columnStarted = false;
        cursorChunkX = boxMinimumXs[boxHead] >> SECTION_SHIFT;
        cursorChunkZ = boxMinimumZs[boxHead] >> SECTION_SHIFT;
    }

    /**
     * Starts the chunk column the cursor points at: clips the head box to the current cube, sets the column heights of
     * the columns that both hold, and moves the cursor to its highest row.
     * @return whether the chunk of the column is loaded, since the column of a chunk that is not loaded is skipped
     */
    private boolean enterChunkColumn(ChunkLookup lookup) {
        LevelChunk chunk = lookup.loadedChunk(cursorChunkX, cursorChunkZ);
        if (chunk == null) {
            return false;
        }
        int chunkMinimumX = cursorChunkX << SECTION_SHIFT;
        int chunkMinimumZ = cursorChunkZ << SECTION_SHIFT;
        int cubeMinimumX = field.originX();
        int cubeMinimumY = field.originY();
        int cubeMinimumZ = field.originZ();
        int cubeMaximumX = cubeMinimumX + field.size() - 1;
        int cubeMaximumY = cubeMinimumY + field.size() - 1;
        int cubeMaximumZ = cubeMinimumZ + field.size() - 1;
        // The rows of this column of the box are clipped to the cube once, so a box that the cube left behind costs no
        // cell test of its own:
        rowFirstX = Math.max(Math.max(boxMinimumXs[boxHead], chunkMinimumX), cubeMinimumX);
        rowLastX = Math.min(Math.min(boxMaximumXs[boxHead], chunkMinimumX + SECTION_MASK), cubeMaximumX);
        rowFirstZ = Math.max(Math.max(boxMinimumZs[boxHead], chunkMinimumZ), cubeMinimumZ);
        rowLastZ = Math.min(Math.min(boxMaximumZs[boxHead], chunkMinimumZ + SECTION_MASK), cubeMaximumZ);
        columnLowY = Math.max(boxMinimumYs[boxHead], cubeMinimumY);
        columnHighY = Math.min(boxMaximumYs[boxHead], cubeMaximumY);
        if (rowFirstX > rowLastX || rowFirstZ > rowLastZ || columnLowY > columnHighY) {
            // Nothing of this column of the box is in the cube, so it is finished before it starts.
            columnLowY = 1;
            columnHighY = 0;
        } else {
            for (int z = rowFirstZ; z <= rowLastZ; z++) {
                for (int x = rowFirstX; x <= rowLastX; x++) {
                    field.setColumnHeight(x, z, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & SECTION_MASK,
                            z & SECTION_MASK) + 1);
                }
            }
        }
        cursorY = columnHighY;
        cursorX = rowFirstX;
        cursorZ = rowFirstZ;
        return true;
    }

    /**
     * Moves the cursor to the chunk column after the current one of the head box, and removes the head box from the
     * ring when it was its last column.
     * @return whether a chunk column of a box is left to read
     */
    private boolean advanceChunkColumn() {
        if (cursorChunkX < boxMaximumXs[boxHead] >> SECTION_SHIFT) {
            cursorChunkX++;
            return true;
        }
        if (cursorChunkZ < boxMaximumZs[boxHead] >> SECTION_SHIFT) {
            cursorChunkX = boxMinimumXs[boxHead] >> SECTION_SHIFT;
            cursorChunkZ++;
            return true;
        }
        popHeadBox();
        return boxCount > 0;
    }
}
