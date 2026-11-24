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

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The shape ids of SnowShapes in a cube of cells around the eye, which the falling snow collides with, read from the
 * world through the palette tables of SnowSectionMasks.
 * The cube has SIZE cells per axis, and its minimum corner, the origin, is the eye block minus SIZE / 2 on each axis. A
 * cell at (x, y, z) is stored at the slot (x & MASK) + ((y & MASK) << SHIFT) + ((z & MASK) << 2 * SHIFT), so x is the
 * fastest axis and the array is the layout of a GL 3D texture with repeat wrapping. World positions repeat every SIZE
 * blocks and only the one inside the cube is stored.
 * A cell that is not read yet holds SnowShapes.EMPTY_SHAPE, and so do the cells of a chunk that is not loaded and the
 * cells outside the world's height, so flakes pass them as they pass air.
 * A box is a range of world cells that waits to be read, kept in a ring of BOX_CAPACITY entries. A fill clears the cube
 * and queues it as one box, a move clears the cells that enter the cube and queues them as up to six boxes, and a chunk
 * that arrives queues its columns over the height of the cube. A box is read one section at a time, clipped to the cube
 * as it is at that moment, so cells of a box that left the cube are skipped. When the ring is full it is emptied and the
 * whole cube is queued instead.
 * Each brick of BRICK_SIZE cells per axis whose cells changed stays dirty until the uploader clears it.
 * Everything is allocated in the constructor, and the warm operations allocate nothing after the first lookup of each
 * block state. Not thread safe.
 */
public final class SnowShapeVolume {
    public static final int SIZE = 64;
    public static final int MASK = SIZE - 1;
    public static final int SHIFT = 6;
    public static final int CELL_COUNT = SIZE * SIZE * SIZE;
    public static final int BRICK_SIZE = 8;
    public static final int BRICKS_PER_AXIS = SIZE / BRICK_SIZE;
    public static final int BRICK_COUNT = BRICKS_PER_AXIS * BRICKS_PER_AXIS * BRICKS_PER_AXIS;
    /**
     * The blocks along any axis by which the cube may move before it is filled again rather than moved.
     */
    public static final int FILL_JUMP = 16;
    public static final int BOX_CAPACITY = 64;

    private static final int SECTION_SIZE = 16;
    private static final int SECTION_MASK = SECTION_SIZE - 1;
    private static final int SECTION_SHIFT = 4;
    private static final int BRICK_SHIFT = 3;
    private static final int BRICKS_PER_AXIS_SHIFT = 3;

    private final SnowShapes shapes;
    private final SnowSectionMasks sectionIds;
    private final char[] cells = new char[CELL_COUNT];
    private final long[] dirtyBricks = new long[BRICK_COUNT / Long.SIZE];
    private final int[] boxMinimumXs = new int[BOX_CAPACITY];
    private final int[] boxMaximumXs = new int[BOX_CAPACITY];
    private final int[] boxMinimumYs = new int[BOX_CAPACITY];
    private final int[] boxMaximumYs = new int[BOX_CAPACITY];
    private final int[] boxMinimumZs = new int[BOX_CAPACITY];
    private final int[] boxMaximumZs = new int[BOX_CAPACITY];
    private int boxHead;
    private int boxCount;
    /**
     * The section of the head box that the next read reads, and whether the cursor points into the head box.
     */
    private boolean hasCursor;
    private int cursorSectionX;
    private int cursorSectionY;
    private int cursorSectionZ;
    @Nullable
    private LevelChunkSection loadedSection;
    private boolean hasPosition;
    private int originX;
    private int originY;
    private int originZ;
    private long cellsRead;
    private long fillCount;
    private long boxOverflowCount;

    /**
     * Creates a volume with no position, whose cells are all empty.
     * @param shapes the table the ids come from, which only this volume's reads add shapes to
     */
    public SnowShapeVolume(SnowShapes shapes) {
        this.shapes = shapes;
        this.sectionIds = new SnowSectionMasks(shapes::idOf);
    }

    public SnowShapes shapes() {
        return shapes;
    }

    /**
     * Moves the cube to the eye, clearing and queueing the cells that enter it. Fills the cube instead when it has no
     * position yet or its origin would move more than FILL_JUMP blocks on an axis. Allocates nothing.
     * @param eyeX the world x of the eye block
     * @param eyeY the world y of the eye block
     * @param eyeZ the world z of the eye block
     * @return whether the cube was filled instead of moved
     */
    public boolean follow(int eyeX, int eyeY, int eyeZ) {
        int newOriginX = eyeX - SIZE / 2;
        int newOriginY = eyeY - SIZE / 2;
        int newOriginZ = eyeZ - SIZE / 2;
        if (!hasPosition || Math.abs((long) newOriginX - originX) > FILL_JUMP
                || Math.abs((long) newOriginY - originY) > FILL_JUMP
                || Math.abs((long) newOriginZ - originZ) > FILL_JUMP) {
            fillAt(newOriginX, newOriginY, newOriginZ);
            return true;
        }
        if (newOriginX == originX && newOriginY == originY && newOriginZ == originZ) {
            return false;
        }
        int retainedFirstX = Math.max(newOriginX, originX);
        int retainedLastX = Math.min(newOriginX, originX) + MASK;
        int retainedFirstY = Math.max(newOriginY, originY);
        int retainedLastY = Math.min(newOriginY, originY) + MASK;
        int retainedFirstZ = Math.max(newOriginZ, originZ);
        int retainedLastZ = Math.min(newOriginZ, originZ) + MASK;
        originX = newOriginX;
        originY = newOriginY;
        originZ = newOriginZ;
        int maximumX = newOriginX + MASK;
        int maximumY = newOriginY + MASK;
        int maximumZ = newOriginZ + MASK;
        // The cells whose x is outside the old cube, over the whole y and z of the new cube:
        enter(newOriginX, newOriginY, newOriginZ, retainedFirstX - 1, maximumY, maximumZ);
        enter(retainedLastX + 1, newOriginY, newOriginZ, maximumX, maximumY, maximumZ);
        // Of the rest, the cells whose y is outside the old cube, over the whole z of the new cube:
        enter(retainedFirstX, newOriginY, newOriginZ, retainedLastX, retainedFirstY - 1, maximumZ);
        enter(retainedFirstX, retainedLastY + 1, newOriginZ, retainedLastX, maximumY, maximumZ);
        // Of the rest, the cells whose z is outside the old cube:
        enter(retainedFirstX, retainedFirstY, newOriginZ, retainedLastX, retainedLastY, retainedFirstZ - 1);
        enter(retainedFirstX, retainedFirstY, retainedLastZ + 1, retainedLastX, retainedLastY, maximumZ);
        return false;
    }

    /**
     * Places the cube at the eye with every cell empty and queues the whole cube. Allocates nothing.
     */
    public void fill(int eyeX, int eyeY, int eyeZ) {
        fillAt(eyeX - SIZE / 2, eyeY - SIZE / 2, eyeZ - SIZE / 2);
    }

    /**
     * Drops the position and the queued boxes, so that the next follow fills the cube. Allocates nothing.
     */
    public void forget() {
        hasPosition = false;
        clearBoxes();
        sectionIds.forget();
    }

    /**
     * Queues the columns of a chunk that arrived, a new one or one sent again, over the height of the cube, and queues
     * nothing before the cube has a position. Allocates nothing.
     */
    public void queueChunk(int chunkX, int chunkZ) {
        if (!hasPosition) {
            return;
        }
        // A chunk sent again refills its palettes in place and keeps their data objects, so a table built before is
        // stale and only this call can tell:
        sectionIds.forget();
        loadedSection = null;
        int minimumX = chunkX << SECTION_SHIFT;
        int minimumZ = chunkZ << SECTION_SHIFT;
        queueBox(minimumX, originY, minimumZ, minimumX + SECTION_MASK, originY + MASK, minimumZ + SECTION_MASK);
    }

    /**
     * Sets the cell of a changed block when it is in the cube. Allocates nothing after the first lookup of its state.
     */
    public void onBlockChanged(int x, int y, int z, BlockState newState) {
        if (contains(x, y, z)) {
            setCell(x, y, z, shapes.idOf(newState));
        }
    }

    /**
     * Reads the sections of the queued boxes in the order they were queued, until at least the given number of cells
     * is read or nothing waits. A section of a box is read whole, so a call may read up to a section's 4096 cells more
     * than it was given, and a section without cells to read counts as one cell. Allocates nothing after the first
     * lookup of each block state.
     * @return the cells read and counted, at least maximumCells unless the ring emptied
     */
    public int read(SnowFieldReader.ChunkLookup lookup, int maximumCells) {
        int minimumBuildHeight = lookup.minBuildHeight();
        int maximumBuildHeight = lookup.maxBuildHeight();
        int counted = 0;
        while (boxCount > 0 && counted < maximumCells) {
            if (!hasCursor) {
                hasCursor = true;
                cursorSectionX = boxMinimumXs[boxHead] >> SECTION_SHIFT;
                cursorSectionY = boxMinimumYs[boxHead] >> SECTION_SHIFT;
                cursorSectionZ = boxMinimumZs[boxHead] >> SECTION_SHIFT;
            }
            counted += Math.max(1, readCursorSection(lookup, minimumBuildHeight, maximumBuildHeight));
            advanceCursor();
        }
        return counted;
    }

    public boolean isIdle() {
        return boxCount == 0;
    }

    public int queuedBoxCount() {
        return boxCount;
    }

    public boolean hasPosition() {
        return hasPosition;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public boolean contains(int x, int y, int z) {
        return hasPosition && x >= originX && x <= originX + MASK && y >= originY && y <= originY + MASK
                && z >= originZ && z <= originZ + MASK;
    }

    /**
     * The id of the cell at a world position, which is empty outside the cube.
     */
    public int idAt(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return SnowShapes.EMPTY_SHAPE;
        }
        return cells[slotOf(x, y, z)];
    }

    /**
     * The cells read from sections since this volume was created.
     */
    public long cellsRead() {
        return cellsRead;
    }

    public long fillCount() {
        return fillCount;
    }

    public long boxOverflowCount() {
        return boxOverflowCount;
    }

    /**
     * Whether a brick changed since the last clearDirtyBricks.
     * @param brick the brick index, brick x + brick y * BRICKS_PER_AXIS + brick z * BRICKS_PER_AXIS squared, with the
     *        bricks numbered by slot
     */
    public boolean isBrickDirty(int brick) {
        return (dirtyBricks[brick >>> 6] & (1L << (brick & 63))) != 0L;
    }

    public void clearDirtyBricks() {
        Arrays.fill(dirtyBricks, 0L);
    }

    public void markAllBricksDirty() {
        Arrays.fill(dirtyBricks, -1L);
    }

    /**
     * The id of every slot, for the upload, which must not change it.
     */
    char[] cells() {
        return cells;
    }

    public static int slotOf(int x, int y, int z) {
        return (x & MASK) | ((y & MASK) << SHIFT) | ((z & MASK) << (2 * SHIFT));
    }

    private void fillAt(int newOriginX, int newOriginY, int newOriginZ) {
        hasPosition = true;
        originX = newOriginX;
        originY = newOriginY;
        originZ = newOriginZ;
        Arrays.fill(cells, (char) SnowShapes.EMPTY_SHAPE);
        markAllBricksDirty();
        clearBoxes();
        sectionIds.forget();
        queueBox(originX, originY, originZ, originX + MASK, originY + MASK, originZ + MASK);
        fillCount++;
    }

    /**
     * Empties the cells of a box that enters the cube, which hold the cells of the far side of the cube it left, and
     * queues the box. An empty box is dropped.
     */
    private void enter(int minimumX, int minimumY, int minimumZ, int maximumX, int maximumY, int maximumZ) {
        if (minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ) {
            return;
        }
        for (int z = minimumZ; z <= maximumZ; z++) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    setCell(x, y, z, SnowShapes.EMPTY_SHAPE);
                }
            }
        }
        queueBox(minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    /**
     * Reads the part of the cursor's section that lies in the head box and in the cube, and returns the cells it read.
     * A section above or below the world or of a chunk that is not loaded reads nothing, and its cells keep what they
     * hold.
     */
    private int readCursorSection(SnowFieldReader.ChunkLookup lookup, int minimumBuildHeight, int maximumBuildHeight) {
        int sectionMinimumX = cursorSectionX << SECTION_SHIFT;
        int sectionMinimumY = cursorSectionY << SECTION_SHIFT;
        int sectionMinimumZ = cursorSectionZ << SECTION_SHIFT;
        int firstX = Math.max(Math.max(boxMinimumXs[boxHead], sectionMinimumX), originX);
        int lastX = Math.min(Math.min(boxMaximumXs[boxHead], sectionMinimumX + SECTION_MASK), originX + MASK);
        int firstY = Math.max(Math.max(boxMinimumYs[boxHead], sectionMinimumY), originY);
        int lastY = Math.min(Math.min(boxMaximumYs[boxHead], sectionMinimumY + SECTION_MASK), originY + MASK);
        int firstZ = Math.max(Math.max(boxMinimumZs[boxHead], sectionMinimumZ), originZ);
        int lastZ = Math.min(Math.min(boxMaximumZs[boxHead], sectionMinimumZ + SECTION_MASK), originZ + MASK);
        if (firstX > lastX || firstY > lastY || firstZ > lastZ || sectionMinimumY < minimumBuildHeight
                || sectionMinimumY >= maximumBuildHeight) {
            return 0;
        }
        LevelChunk chunk = lookup.loadedChunk(cursorSectionX, cursorSectionZ);
        if (chunk == null) {
            return 0;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(cursorSectionY));
        boolean onlyAir = section.hasOnlyAir();
        if (!onlyAir && section != loadedSection) {
            sectionIds.load(section);
            loadedSection = section;
        }
        for (int y = firstY; y <= lastY; y++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int x = firstX; x <= lastX; x++) {
                    int id = onlyAir ? SnowShapes.EMPTY_SHAPE
                            : sectionIds.maskSetAt(x & SECTION_MASK, y & SECTION_MASK, z & SECTION_MASK);
                    setCell(x, y, z, id);
                }
            }
        }
        int count = (lastX - firstX + 1) * (lastY - firstY + 1) * (lastZ - firstZ + 1);
        cellsRead += count;
        return count;
    }

    /**
     * Moves the cursor to the next section of the head box, x first, then z, then y, and removes the head box from the
     * ring after its last section.
     */
    private void advanceCursor() {
        if (cursorSectionX < boxMaximumXs[boxHead] >> SECTION_SHIFT) {
            cursorSectionX++;
            return;
        }
        cursorSectionX = boxMinimumXs[boxHead] >> SECTION_SHIFT;
        if (cursorSectionZ < boxMaximumZs[boxHead] >> SECTION_SHIFT) {
            cursorSectionZ++;
            return;
        }
        cursorSectionZ = boxMinimumZs[boxHead] >> SECTION_SHIFT;
        if (cursorSectionY < boxMaximumYs[boxHead] >> SECTION_SHIFT) {
            cursorSectionY++;
            return;
        }
        boxHead = (boxHead + 1) % BOX_CAPACITY;
        boxCount--;
        hasCursor = false;
    }

    /**
     * Queues a box of world cells, clipped to the cube. An empty box is dropped, and a full ring is emptied and the
     * whole cube queued instead.
     */
    private void queueBox(int minimumX, int minimumY, int minimumZ, int maximumX, int maximumY, int maximumZ) {
        int clippedMinimumX = Math.max(minimumX, originX);
        int clippedMinimumY = Math.max(minimumY, originY);
        int clippedMinimumZ = Math.max(minimumZ, originZ);
        int clippedMaximumX = Math.min(maximumX, originX + MASK);
        int clippedMaximumY = Math.min(maximumY, originY + MASK);
        int clippedMaximumZ = Math.min(maximumZ, originZ + MASK);
        if (clippedMinimumX > clippedMaximumX || clippedMinimumY > clippedMaximumY
                || clippedMinimumZ > clippedMaximumZ) {
            return;
        }
        if (boxCount >= BOX_CAPACITY) {
            boxOverflowCount++;
            clearBoxes();
            clippedMinimumX = originX;
            clippedMinimumY = originY;
            clippedMinimumZ = originZ;
            clippedMaximumX = originX + MASK;
            clippedMaximumY = originY + MASK;
            clippedMaximumZ = originZ + MASK;
        }
        int slot = (boxHead + boxCount) % BOX_CAPACITY;
        boxMinimumXs[slot] = clippedMinimumX;
        boxMinimumYs[slot] = clippedMinimumY;
        boxMinimumZs[slot] = clippedMinimumZ;
        boxMaximumXs[slot] = clippedMaximumX;
        boxMaximumYs[slot] = clippedMaximumY;
        boxMaximumZs[slot] = clippedMaximumZ;
        boxCount++;
    }

    private void clearBoxes() {
        boxHead = 0;
        boxCount = 0;
        hasCursor = false;
        loadedSection = null;
    }

    private void setCell(int x, int y, int z, int id) {
        int slot = slotOf(x, y, z);
        if (cells[slot] == id) {
            return;
        }
        cells[slot] = (char) id;
        int brick = ((x & MASK) >> BRICK_SHIFT) | (((y & MASK) >> BRICK_SHIFT) << BRICKS_PER_AXIS_SHIFT)
                | (((z & MASK) >> BRICK_SHIFT) << (2 * BRICKS_PER_AXIS_SHIFT));
        dirtyBricks[brick >>> 6] |= 1L << (brick & 63);
    }
}
