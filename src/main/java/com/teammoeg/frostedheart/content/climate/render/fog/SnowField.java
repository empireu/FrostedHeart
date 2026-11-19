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

/**
 * The snow-share field: a cube of cells around the eye, each holding a mask set id and a capped distance to open sky.
 * A cell is one block position. It holds a mask set id, which SnowFaces gives for its block state, and a distance byte.
 * The byte is the distance to open sky in eighths of a block and at most CAP, or SHELTERED when no path within CAP
 * reaches the cell, or UNREAD before the caller has read the cell. An unread cell always has FULL_MASK_SET, so every
 * face of it is closed.
 * The cube has size cells per axis, a power of two, and its minimum corner, the origin, is the eye minus size / 2 on
 * each axis, so it spans the eye minus size / 2 to the eye plus size / 2 minus 1. A cell at (x, y, z) is stored at the
 * slot (x & mask) + ((y & mask) << s) + ((z & mask) << 2s), with mask size - 1 and s log2 size, so x is the fastest
 * axis and the byte array is the layout of a GL 3D texture. World positions repeat every size blocks and only the one
 * inside the cube is stored.
 * Snow moves from a cell to a neighbor across one of its faces at MOVE_DOWN_COST for a down move, MOVE_UP_COST for an
 * up move and MOVE_SIDEWAYS_COST for a sideways move, plus the cost of the face between the two mask sets. A cell is a
 * seed of open sky when the caller has read it and either it is the top of the cube or the cell above it is unread, and
 * its column height, the world's MOTION_BLOCKING heightmap value, is at most one above it.
 * The solution is a capped shortest path from the seeds with integer costs in eighths of a block: every read cell holds
 * the smallest cost of a path from a seed to it, when that cost is at most CAP, and SHELTERED otherwise. This class
 * knows nothing about the level: the caller reads blocks, changes them, sets column heights and moves the eye, and the
 * distances stay equal to the solution without a full solve. Everything is allocated in the constructor, and the warm
 * operations allocate nothing.
 * Not thread safe.
 */
public final class SnowField {
    /**
     * The largest distance a cell holds, in eighths of a block, which is 24 blocks. A path that costs more is not
     * stored.
     */
    public static final int CAP = 192;
    /**
     * The byte of a read cell that no path from a seed reaches within CAP.
     */
    public static final int SHELTERED = 254;
    /**
     * The byte of a cell that the caller has not read yet. Its mask set is FULL_MASK_SET, so every face of it is
     * closed.
     */
    public static final int UNREAD = 255;
    /**
     * The cost of moving down into a cell across a face, in eighths of a block.
     */
    public static final int MOVE_DOWN_COST = 0;
    /**
     * The cost of moving sideways into a cell across a face, in eighths of a block.
     */
    public static final int MOVE_SIDEWAYS_COST = 8;
    /**
     * The cost of moving up into a cell across a face, in eighths of a block.
     */
    public static final int MOVE_UP_COST = 16;
    /**
     * The distance in blocks along any axis by which the eye may move before the cube is filled again rather than
     * moved.
     */
    public static final int FILL_JUMP = 16;
    /**
     * The column height of a column whose height the caller has not set, which is above every cell, so no cell of such
     * a column is seeded.
     */
    public static final int UNKNOWN_HEIGHT = Integer.MAX_VALUE;
    /**
     * The number of cells per axis of the field of the game, which spans 128 blocks.
     */
    public static final int DEFAULT_SIZE = 128;
    /**
     * The number of cells per axis of a brick, the unit in which the distances are marked dirty for the uploader.
     */
    public static final int BRICK_SIZE = 8;
    /**
     * The number of queue entries the field of the game allocates once.
     */
    public static final int DEFAULT_QUEUE_CAPACITY = 1 << 19;
    /**
     * The number of cells the field of the game can reset in one decrease of its distances.
     */
    public static final int DEFAULT_RESET_CAPACITY = 1 << 18;
    /**
     * The smallest number of queue entries a field may allocate, which is the number of bits in one word of the
     * spilled set.
     */
    public static final int MINIMUM_QUEUE_CAPACITY = 64;

    private static final int DOWN_FACE = 0;
    private static final int UP_FACE = 1;
    private static final int NORTH_FACE = 2;
    private static final int SOUTH_FACE = 3;
    private static final int WEST_FACE = 4;
    private static final int EAST_FACE = 5;
    /**
     * The cost of a move into a cell across each face, in eighths of a block, indexed by the face.
     */
    private static final int[] MOVE_COSTS = {MOVE_DOWN_COST, MOVE_UP_COST, MOVE_SIDEWAYS_COST, MOVE_SIDEWAYS_COST,
            MOVE_SIDEWAYS_COST, MOVE_SIDEWAYS_COST};

    private final SnowFaces faces;
    private final int size;
    private final int sizeMask;
    private final int sizeShift;
    private final int halfSize;
    private final int slotStepZ;
    private final int cellCount;
    /**
     * The distance byte of each slot, from 0 to CAP, SHELTERED or UNREAD.
     */
    private final byte[] distances;
    /**
     * The mask set id of each slot, from SnowFaces.
     */
    private final char[] maskSets;
    /**
     * The height of each column, indexed by its slot x and then its slot z, or UNKNOWN_HEIGHT.
     */
    private final int[] columnHeights;
    private final int bricksPerAxis;
    private final int brickCount;
    private final long[] dirtyBricks;
    /**
     * The cell of each queue entry, and the next entry of its bucket chain, which is also the free chain of the pool.
     */
    private final int[] entryCells;
    private final int[] entryNexts;
    private final int[] bucketHeads;
    private final int[] bucketTails;
    /**
     * The cells whose queue push found the pool full, one bit per slot.
     */
    private final long[] spilledBits;
    private final int spilledWordCount;
    /**
     * The cells the current decrease reset, with the byte each held before, in the order they were reset.
     */
    private final int[] resetCells;
    private final byte[] resetOldBytes;
    /**
     * The cells the current change may have made more expensive, collected before any byte is reset.
     */
    private final int[] startCells;
    private int startCount;
    private int resetEntryCount;
    private int resetWalkedCount;
    private int originX;
    private int originY;
    private int originZ;
    private int lastEyeX;
    private int lastEyeY;
    private int lastEyeZ;
    private boolean hasPosition;
    private int freeEntryHead;
    private int entryCount;
    private int currentBucket;
    private int spilledCellCount;
    private int spillWordCursor;
    private long spillCount;
    private long resolveAllCount;
    private long fillCount;
    private boolean readsPending;
    private boolean hasSolvedBox;
    private int solvedMinX;
    private int solvedMinY;
    private int solvedMinZ;
    private int solvedMaxX;
    private int solvedMaxY;
    private int solvedMaxZ;

    /**
     * Creates a field of the given size and capacities, with no position yet and every cell unread.
     * @param faces the table the face costs come from, which this field does not modify
     * @param size the number of cells per axis, a power of two from 16 to 256
     * @param queueCapacity the number of queue entries allocated once, at least MINIMUM_QUEUE_CAPACITY
     * @param resetCapacity the number of cells one decrease of the distances may reset, at least 1
     * @throws IllegalArgumentException when size is not a power of two from 16 to 256, queueCapacity is below
     *         MINIMUM_QUEUE_CAPACITY, or resetCapacity is below 1
     */
    public SnowField(SnowFaces faces, int size, int queueCapacity, int resetCapacity) {
        if (size < 16 || size > 256 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("The snow field size " + size
                    + " is not a power of two from 16 to 256");
        }
        if (queueCapacity < MINIMUM_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("The snow field queue capacity " + queueCapacity + " is below "
                    + MINIMUM_QUEUE_CAPACITY);
        }
        if (resetCapacity < 1) {
            throw new IllegalArgumentException("The snow field reset capacity " + resetCapacity + " is below 1");
        }
        this.faces = faces;
        this.size = size;
        this.sizeMask = size - 1;
        this.sizeShift = Integer.numberOfTrailingZeros(size);
        this.halfSize = size / 2;
        this.slotStepZ = 1 << (2 * sizeShift);
        this.cellCount = size * size * size;
        this.distances = new byte[cellCount];
        this.maskSets = new char[cellCount];
        this.columnHeights = new int[size * size];
        for (int cell = 0; cell < cellCount; cell++) {
            distances[cell] = (byte) UNREAD;
            maskSets[cell] = (char) SnowFaces.FULL_MASK_SET;
        }
        for (int column = 0; column < columnHeights.length; column++) {
            columnHeights[column] = UNKNOWN_HEIGHT;
        }
        this.bricksPerAxis = size / BRICK_SIZE;
        this.brickCount = bricksPerAxis * bricksPerAxis * bricksPerAxis;
        this.dirtyBricks = new long[(brickCount + 63) >> 6];
        this.entryCells = new int[queueCapacity];
        this.entryNexts = new int[queueCapacity];
        for (int entry = 0; entry < queueCapacity - 1; entry++) {
            entryNexts[entry] = entry + 1;
        }
        entryNexts[queueCapacity - 1] = -1;
        this.freeEntryHead = 0;
        this.bucketHeads = new int[CAP + 1];
        this.bucketTails = new int[CAP + 1];
        for (int bucket = 0; bucket <= CAP; bucket++) {
            bucketHeads[bucket] = -1;
            bucketTails[bucket] = -1;
        }
        this.spilledWordCount = (cellCount + 63) >> 6;
        this.spilledBits = new long[spilledWordCount];
        this.resetCells = new int[resetCapacity];
        this.resetOldBytes = new byte[resetCapacity];
        // A cell change starts at most thirteen cells and a column change at most one per cell of the column.
        this.startCells = new int[size + 16];
    }

    /**
     * The number of cells per axis, from 16 to 256.
     * @return the number of cells per axis
     */
    public int size() {
        return size;
    }

    /**
     * Whether the field has been given a position by fill or moveTo.
     * @return whether the origin is meaningful
     */
    public boolean hasPosition() {
        return hasPosition;
    }

    /**
     * The world x of the minimum corner of the cube, which is the eye minus size / 2.
     * @return the world x of the minimum corner, or 0 before the first fill or moveTo
     */
    public int originX() {
        return originX;
    }

    /**
     * The world y of the minimum corner of the cube, which is the eye minus size / 2.
     * @return the world y of the minimum corner, or 0 before the first fill or moveTo
     */
    public int originY() {
        return originY;
    }

    /**
     * The world z of the minimum corner of the cube, which is the eye minus size / 2.
     * @return the world z of the minimum corner, or 0 before the first fill or moveTo
     */
    public int originZ() {
        return originZ;
    }

    /**
     * Whether a world position is one of the cells of the cube.
     * @param x the world x
     * @param y the world y
     * @param z the world z
     * @return whether the position is inside the cube, which needs a position
     */
    public boolean contains(int x, int y, int z) {
        if (!hasPosition) {
            return false;
        }
        int localX = x - originX;
        int localY = y - originY;
        int localZ = z - originZ;
        return localX >= 0 && localX < size && localY >= 0 && localY < size && localZ >= 0 && localZ < size;
    }

    /**
     * Fills the cube at the eye: the origin becomes the eye minus size / 2, every cell becomes unread, every column
     * height becomes UNKNOWN_HEIGHT, and the queue, the spilled set, the decrease and the solved box become empty.
     * Allocates nothing.
     * @param eyeX the world x of the eye block
     * @param eyeY the world y of the eye block
     * @param eyeZ the world z of the eye block
     */
    public void fill(int eyeX, int eyeY, int eyeZ) {
        originX = eyeX - halfSize;
        originY = eyeY - halfSize;
        originZ = eyeZ - halfSize;
        lastEyeX = eyeX;
        lastEyeY = eyeY;
        lastEyeZ = eyeZ;
        hasPosition = true;
        fillCount++;
        for (int cell = 0; cell < cellCount; cell++) {
            distances[cell] = (byte) UNREAD;
            maskSets[cell] = (char) SnowFaces.FULL_MASK_SET;
        }
        for (int column = 0; column < columnHeights.length; column++) {
            columnHeights[column] = UNKNOWN_HEIGHT;
        }
        clearQueue();
        clearSpilledCells();
        resetEntryCount = 0;
        resetWalkedCount = 0;
        markAllBricksDirty();
        hasSolvedBox = false;
        readsPending = true;
    }

    /**
     * Moves the cube to the eye, which fills it instead when it has no position yet or the eye moved more than
     * FILL_JUMP blocks on any axis. A move marks the cells that enter unread and keeps the others, so the retained
     * bytes stay the lengths of real paths. Allocates nothing.
     * @param eyeX the world x of the eye block
     * @param eyeY the world y of the eye block
     * @param eyeZ the world z of the eye block
     * @return whether the field was filled instead of moved
     */
    public boolean moveTo(int eyeX, int eyeY, int eyeZ) {
        if (!hasPosition || Math.abs((long) eyeX - lastEyeX) > FILL_JUMP
                || Math.abs((long) eyeY - lastEyeY) > FILL_JUMP || Math.abs((long) eyeZ - lastEyeZ) > FILL_JUMP) {
            fill(eyeX, eyeY, eyeZ);
            return true;
        }
        if (eyeX == lastEyeX && eyeY == lastEyeY && eyeZ == lastEyeZ) {
            return false;
        }
        int newOriginX = eyeX - halfSize;
        int newOriginY = eyeY - halfSize;
        int newOriginZ = eyeZ - halfSize;
        int deltaX = newOriginX - originX;
        int deltaY = newOriginY - originY;
        int deltaZ = newOriginZ - originZ;
        int retainedFirstX = Math.max(0, -deltaX);
        int retainedLastX = Math.min(sizeMask, sizeMask - deltaX);
        int retainedFirstY = Math.max(0, -deltaY);
        int retainedLastY = Math.min(sizeMask, sizeMask - deltaY);
        int retainedFirstZ = Math.max(0, -deltaZ);
        int retainedLastZ = Math.min(sizeMask, sizeMask - deltaZ);
        originX = newOriginX;
        originY = newOriginY;
        originZ = newOriginZ;
        lastEyeX = eyeX;
        lastEyeY = eyeY;
        lastEyeZ = eyeZ;
        for (int localZ = 0; localZ < size; localZ++) {
            boolean columnLeftInZ = localZ < retainedFirstZ || localZ > retainedLastZ;
            int slotZ = (originZ + localZ) & sizeMask;
            for (int localX = 0; localX < size; localX++) {
                boolean columnEntered = columnLeftInZ || localX < retainedFirstX || localX > retainedLastX;
                int slotX = (originX + localX) & sizeMask;
                if (columnEntered) {
                    columnHeights[slotX | (slotZ << sizeShift)] = UNKNOWN_HEIGHT;
                }
                int columnCell = slotX | (slotZ << (2 * sizeShift));
                if (columnEntered) {
                    markUnreadRows(columnCell, 0, sizeMask);
                } else {
                    markUnreadRows(columnCell, 0, retainedFirstY - 1);
                    markUnreadRows(columnCell, retainedLastY + 1, sizeMask);
                }
            }
        }
        if (deltaY < 0) {
            int topLayer = ((originY + sizeMask) & sizeMask) << sizeShift;
            for (int slotZ = 0; slotZ < size; slotZ++) {
                for (int slotX = 0; slotX < size; slotX++) {
                    pushSeedIfBetter(slotX | topLayer | (slotZ << (2 * sizeShift)));
                }
            }
        }
        if (hasSolvedBox) {
            int minimumX = Math.max(solvedMinX, originX);
            int minimumY = Math.max(solvedMinY, originY);
            int minimumZ = Math.max(solvedMinZ, originZ);
            int maximumX = Math.min(solvedMaxX, originX + sizeMask);
            int maximumY = Math.min(solvedMaxY, originY + sizeMask);
            int maximumZ = Math.min(solvedMaxZ, originZ + sizeMask);
            if (minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ) {
                hasSolvedBox = false;
            } else {
                solvedMinX = minimumX;
                solvedMinY = minimumY;
                solvedMinZ = minimumZ;
                solvedMaxX = maximumX;
                solvedMaxY = maximumY;
                solvedMaxZ = maximumZ;
            }
        }
        readsPending = true;
        return false;
    }

    /**
     * Sets the mask set of a cell, which reads an unread cell and changes a read one. The distances stay equal to the
     * solution. A position outside the cube and a read cell whose mask set is unchanged are ignored. Allocates nothing.
     * @param x the world x
     * @param y the world y
     * @param z the world z
     * @param maskSet the mask set id of the block state now in the cell, from maskSetOf of the SnowFaces this field
     *        was created with, since an id it never handed out makes a later face cost throw
     */
    public void setCell(int x, int y, int z, int maskSet) {
        if (!contains(x, y, z)) {
            return;
        }
        int cell = slotOf(x, y, z);
        int oldMaskSet = maskSets[cell];
        if ((distances[cell] & 0xFF) == UNREAD) {
            readUnreadCell(cell, maskSet);
            return;
        }
        if (oldMaskSet == maskSet) {
            return;
        }
        changeMaskSetOfReadCell(cell, oldMaskSet, maskSet);
    }

    /**
     * Sets the height of a column, which is the world's MOTION_BLOCKING heightmap value, one above the highest block
     * that blocks motion. The distances stay equal to the solution. A column outside the cube and an unchanged height
     * are ignored. Allocates nothing.
     * @param x the world x of the column
     * @param z the world z of the column
     * @param height the height in blocks, or UNKNOWN_HEIGHT for a column the caller has not read
     */
    public void setColumnHeight(int x, int z, int height) {
        if (!hasPosition) {
            return;
        }
        int localX = x - originX;
        int localZ = z - originZ;
        if (localX < 0 || localX >= size || localZ < 0 || localZ >= size) {
            return;
        }
        int slotX = x & sizeMask;
        int slotZ = z & sizeMask;
        int columnIndex = slotX | (slotZ << sizeShift);
        int oldHeight = columnHeights[columnIndex];
        if (oldHeight == height) {
            return;
        }
        beginRepair();
        int columnCell = slotX | (slotZ << (2 * sizeShift));
        for (int slotY = 0; slotY < size; slotY++) {
            int cell = columnCell + (slotY << sizeShift);
            int cellDistance = distances[cell] & 0xFF;
            if (!isFiniteDistance(cellDistance)) {
                continue;
            }
            int oldSeed = seedCostOf(cell, maskSets[cell], oldHeight);
            if (oldSeed > CAP) {
                continue;
            }
            int newSeed = seedCostOf(cell, maskSets[cell], height);
            if (newSeed > oldSeed && cellDistance >= oldSeed) {
                addStart(cell);
            }
        }
        boolean decreased = decreaseWalk();
        columnHeights[columnIndex] = height;
        if (!decreased) {
            resolveAll();
            return;
        }
        increaseFromResets();
        for (int slotY = 0; slotY < size; slotY++) {
            pushSeedIfBetter(columnCell + (slotY << sizeShift));
        }
    }

    /**
     * Processes at most the given number of steps of the solver, so that the caller can split the work over frames:
     * one step per queue entry popped, one per empty 64-bit word of the spilled set scanned and one per spilled cell
     * taken back into the queue. Allocates nothing.
     * @param maximumSteps the number of steps this call may use, at least 0
     * @return the number of steps used, which is at most maximumSteps
     */
    public int process(int maximumSteps) {
        int steps = 0;
        while (steps < maximumSteps) {
            if (entryCount > 0) {
                if (!expandNextEntry()) {
                    break;
                }
                steps++;
            } else if (spilledCellCount > 0) {
                steps += takeBackSpilledWord(maximumSteps - steps);
            } else {
                break;
            }
        }
        updateSolvedBox();
        return steps;
    }

    /**
     * Whether the queue and the spilled set are empty, so that every byte is the solution.
     * @return whether the solver has nothing left to do
     */
    public boolean isDrained() {
        return entryCount == 0 && spilledCellCount == 0;
    }

    /**
     * Tells the field that the caller has read every cell it can for the current position, so that the solved box may
     * cover the whole cube once the solver is drained. A move or a fill makes the reads pending again.
     */
    public void readsComplete() {
        readsPending = false;
        updateSolvedBox();
    }

    /**
     * Recomputes every byte from the stored mask sets and column heights without reading anything: every read cell
     * becomes SHELTERED, the queue and the spilled set become empty, and every eligible cell is seeded again. It costs
     * about a scan of the cube, allocates nothing, and is what a change of every leaf face cost needs.
     */
    public void resolveAll() {
        resolveAllCount++;
        clearQueue();
        clearSpilledCells();
        for (int cell = 0; cell < cellCount; cell++) {
            if ((distances[cell] & 0xFF) == UNREAD) {
                continue;
            }
            distances[cell] = (byte) SHELTERED;
            markBrickDirty(cell);
        }
        for (int cell = 0; cell < cellCount; cell++) {
            pushSeedIfBetter(cell);
        }
    }

    /**
     * The distance byte of a cell, in eighths of a block.
     * @param x the world x
     * @param y the world y
     * @param z the world z
     * @return the distance from 0 to CAP, or SHELTERED, or UNREAD outside the cube and with no position
     */
    public int distanceAt(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return UNREAD;
        }
        return distances[slotOf(x, y, z)] & 0xFF;
    }

    /**
     * The mask set id of a cell.
     * @param x the world x
     * @param y the world y
     * @param z the world z
     * @return the mask set id, or FULL_MASK_SET outside the cube and with no position
     */
    public int maskSetAt(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return SnowFaces.FULL_MASK_SET;
        }
        return maskSets[slotOf(x, y, z)];
    }

    /**
     * The height of a column, in blocks.
     * @param x the world x of the column
     * @param z the world z of the column
     * @return the height, or UNKNOWN_HEIGHT outside the cube and for a column the caller has not set
     */
    public int columnHeightAt(int x, int z) {
        if (!hasPosition) {
            return UNKNOWN_HEIGHT;
        }
        int localX = x - originX;
        int localZ = z - originZ;
        if (localX < 0 || localX >= size || localZ < 0 || localZ >= size) {
            return UNKNOWN_HEIGHT;
        }
        return columnHeights[(x & sizeMask) | ((z & sizeMask) << sizeShift)];
    }

    /**
     * Whether the solved box is not empty, after which its six corner getters are meaningful.
     * @return whether the solved box holds at least one cell
     */
    public boolean hasSolvedBox() {
        return hasSolvedBox;
    }

    /**
     * The world x of the low corner of the solved box.
     * @return the world x, meaningful only when hasSolvedBox
     */
    public int solvedMinX() {
        return solvedMinX;
    }

    /**
     * The world y of the low corner of the solved box.
     * @return the world y, meaningful only when hasSolvedBox
     */
    public int solvedMinY() {
        return solvedMinY;
    }

    /**
     * The world z of the low corner of the solved box.
     * @return the world z, meaningful only when hasSolvedBox
     */
    public int solvedMinZ() {
        return solvedMinZ;
    }

    /**
     * The world x of the high corner of the solved box, inclusive.
     * @return the world x, meaningful only when hasSolvedBox
     */
    public int solvedMaxX() {
        return solvedMaxX;
    }

    /**
     * The world y of the high corner of the solved box, inclusive.
     * @return the world y, meaningful only when hasSolvedBox
     */
    public int solvedMaxY() {
        return solvedMaxY;
    }

    /**
     * The world z of the high corner of the solved box, inclusive.
     * @return the world z, meaningful only when hasSolvedBox
     */
    public int solvedMaxZ() {
        return solvedMaxZ;
    }

    /**
     * Whether a world position is in the solved box, the box of cells whose bytes a renderer may show. It becomes the
     * whole cube once the reads are complete and the solver is drained, is cut down by every move, and is empty after
     * a fill.
     * @param x the world x
     * @param y the world y
     * @param z the world z
     * @return whether the position is in the solved box, which is false when the box is empty
     */
    public boolean isInSolvedBox(int x, int y, int z) {
        return hasSolvedBox && x >= solvedMinX && x <= solvedMaxX && y >= solvedMinY && y <= solvedMaxY
                && z >= solvedMinZ && z <= solvedMaxZ;
    }

    /**
     * The live array of distance bytes in the slot layout of this class, for the uploader. It is reused by this field
     * and changes with every solve.
     * @return the array of distance bytes, one byte per slot, which the caller must not write
     */
    public byte[] distances() {
        return distances;
    }

    /**
     * The number of bricks, which is (size / BRICK_SIZE) cubed.
     * @return the number of bricks, at least 8
     */
    public int brickCount() {
        return brickCount;
    }

    /**
     * The index of the brick that holds a world position inside the cube, in a grid of bricks that runs along x, then
     * y, then z.
     * @param x the world x, inside the cube
     * @param y the world y, inside the cube
     * @param z the world z, inside the cube
     * @return the brick index, from 0 to brickCount() - 1
     */
    public int brickIndexOf(int x, int y, int z) {
        return brickIndexOfSlot(slotOf(x, y, z));
    }

    /**
     * Whether the distances of a brick changed since the last clearDirtyBricks, so that the uploader must upload it.
     * @param brickIndex the brick index, from 0 to brickCount() - 1
     * @return whether the brick is dirty
     */
    public boolean isBrickDirty(int brickIndex) {
        return (dirtyBricks[brickIndex >> 6] & (1L << (brickIndex & 63))) != 0L;
    }

    /**
     * The number of dirty bricks.
     * @return the number of bricks whose distances changed since the last clearDirtyBricks
     */
    public int dirtyBrickCount() {
        int count = 0;
        for (int word = 0; word < dirtyBricks.length; word++) {
            count += Long.bitCount(dirtyBricks[word]);
        }
        return count;
    }

    /**
     * Marks every brick clean, after the uploader has uploaded them.
     */
    public void clearDirtyBricks() {
        for (int word = 0; word < dirtyBricks.length; word++) {
            dirtyBricks[word] = 0L;
        }
    }

    /**
     * The number of queue entries in the pool, stale ones included.
     * @return the number of entries in use
     */
    public int queuedEntryCount() {
        return entryCount;
    }

    /**
     * The number of cells in the spilled set, which are cells whose queue push found the pool full.
     * @return the number of set bits of the spilled set
     */
    public int spilledCellCount() {
        return spilledCellCount;
    }

    /**
     * The number of pushes that found the pool full since this field was created, for tests and the debug command.
     * @return the number of spilled pushes, which only grows
     */
    public long spillCount() {
        return spillCount;
    }

    /**
     * The number of full solves since this field was created, for tests and the debug command.
     * @return the number of resolveAll calls, which only grows
     */
    public long resolveAllCount() {
        return resolveAllCount;
    }

    /**
     * The number of fills since this field was created, for tests and the debug command.
     * @return the number of fill calls, which only grows
     */
    public long fillCount() {
        return fillCount;
    }

    /**
     * The slot of a world position, which must be inside the cube.
     */
    private int slotOf(int x, int y, int z) {
        return (x & sizeMask) | ((y & sizeMask) << sizeShift) | ((z & sizeMask) << (2 * sizeShift));
    }

    /**
     * The slot of the cell across one face of a cell, or -1 when that face lies on the side of the cube.
     */
    private int neighborSlot(int cell, int face) {
        int slotX = cell & sizeMask;
        int slotY = (cell >>> sizeShift) & sizeMask;
        int slotZ = cell >>> (2 * sizeShift);
        switch (face) {
            case DOWN_FACE:
                return ((slotY - originY) & sizeMask) > 0 ? withSlotY(cell, slotY - 1) : -1;
            case UP_FACE:
                return ((slotY - originY) & sizeMask) < sizeMask ? withSlotY(cell, slotY + 1) : -1;
            case NORTH_FACE:
                return ((slotZ - originZ) & sizeMask) > 0 ? withSlotZ(cell, slotZ - 1) : -1;
            case SOUTH_FACE:
                return ((slotZ - originZ) & sizeMask) < sizeMask ? withSlotZ(cell, slotZ + 1) : -1;
            case WEST_FACE:
                return ((slotX - originX) & sizeMask) > 0 ? withSlotX(cell, slotX - 1) : -1;
            case EAST_FACE:
                return ((slotX - originX) & sizeMask) < sizeMask ? withSlotX(cell, slotX + 1) : -1;
            default:
                return -1;
        }
    }

    /**
     * A slot with its x replaced, wrapped into the slot range.
     */
    private int withSlotX(int cell, int slotX) {
        return (cell & ~sizeMask) | (slotX & sizeMask);
    }

    /**
     * A slot with its y replaced, wrapped into the slot range.
     */
    private int withSlotY(int cell, int slotY) {
        return (cell & ~(sizeMask << sizeShift)) | ((slotY & sizeMask) << sizeShift);
    }

    /**
     * A slot with its z replaced, wrapped into the slot range.
     */
    private int withSlotZ(int cell, int slotZ) {
        return (cell & (slotStepZ - 1)) | ((slotZ & sizeMask) << (2 * sizeShift));
    }

    /**
     * The index of the column of a cell, in the array of column heights.
     */
    private int columnIndexOfCell(int cell) {
        return (cell & sizeMask) | ((cell >>> (2 * sizeShift)) << sizeShift);
    }

    /**
     * The index of the brick that holds a slot.
     */
    private int brickIndexOfSlot(int cell) {
        int brickX = (cell & sizeMask) >> 3;
        int brickY = ((cell >>> sizeShift) & sizeMask) >> 3;
        int brickZ = (cell >>> (2 * sizeShift)) >> 3;
        return brickX + brickY * bricksPerAxis + brickZ * bricksPerAxis * bricksPerAxis;
    }

    /**
     * Marks the brick of a cell dirty.
     */
    private void markBrickDirty(int cell) {
        int brickIndex = brickIndexOfSlot(cell);
        dirtyBricks[brickIndex >> 6] |= 1L << (brickIndex & 63);
    }

    /**
     * Marks every brick dirty, without leaving bits set beyond the last brick.
     */
    private void markAllBricksDirty() {
        for (int word = 0; word < dirtyBricks.length; word++) {
            dirtyBricks[word] = -1L;
        }
        int usedBits = brickCount & 63;
        if (usedBits != 0) {
            dirtyBricks[dirtyBricks.length - 1] = (1L << usedBits) - 1L;
        }
    }

    /**
     * Marks the cells of one column in the given closed range of y relative to the origin unread, as cells that just
     * entered the cube, and marks their bricks dirty.
     */
    private void markUnreadRows(int columnCell, int firstLocalY, int lastLocalY) {
        for (int localY = firstLocalY; localY <= lastLocalY; localY++) {
            int cell = columnCell | (((originY + localY) & sizeMask) << sizeShift);
            distances[cell] = (byte) UNREAD;
            maskSets[cell] = (char) SnowFaces.FULL_MASK_SET;
            markBrickDirty(cell);
        }
    }

    /**
     * Makes the solved box the whole cube, which is only allowed once the reads are complete and the solver is
     * drained.
     */
    private void updateSolvedBox() {
        if (readsPending || !hasPosition || !isDrained()) {
            return;
        }
        hasSolvedBox = true;
        solvedMinX = originX;
        solvedMinY = originY;
        solvedMinZ = originZ;
        solvedMaxX = originX + sizeMask;
        solvedMaxY = originY + sizeMask;
        solvedMaxZ = originZ + sizeMask;
    }

    /**
     * Whether a distance byte is the length of a path, which is at most CAP, rather than SHELTERED or UNREAD.
     */
    private static boolean isFiniteDistance(int distance) {
        return distance <= CAP;
    }

    /**
     * The seed cost of a read cell with the current mask set and column height, in eighths of a block.
     */
    private int seedCostOf(int cell) {
        if ((distances[cell] & 0xFF) == UNREAD) {
            return SnowFaces.CLOSED_COST;
        }
        return seedCostOf(cell, maskSets[cell], columnHeights[columnIndexOfCell(cell)]);
    }

    /**
     * The seed cost of a cell counted as read, with the given mask set and the given height of its column, in eighths
     * of a block. It is CLOSED_COST unless the cell is at the top of the cube or the cell above it is unread, and the
     * height is at most one above it.
     */
    private int seedCostOf(int cell, int maskSet, int columnHeight) {
        int slotY = (cell >>> sizeShift) & sizeMask;
        int relativeY = (slotY - originY) & sizeMask;
        if (relativeY != sizeMask && (distances[withSlotY(cell, slotY + 1)] & 0xFF) != UNREAD) {
            return SnowFaces.CLOSED_COST;
        }
        if (columnHeight > originY + relativeY + 1) {
            return SnowFaces.CLOSED_COST;
        }
        return faces.faceCost(maskSet, UP_FACE, SnowFaces.EMPTY_MASK_SET);
    }

    /**
     * Pushes a cell with its seed cost when that is at most CAP and below its byte, which makes the byte the seed cost
     * and marks its brick dirty.
     */
    private void pushSeedIfBetter(int cell) {
        int seed = seedCostOf(cell);
        if (seed > CAP) {
            return;
        }
        if (seed < (distances[cell] & 0xFF)) {
            distances[cell] = (byte) seed;
            markBrickDirty(cell);
            pushCell(cell, seed);
        }
    }

    /**
     * Pushes a cell with its own byte when that is finite, so that it may refill the cells a decrease reset.
     */
    private void pushIfFinite(int cell) {
        int cellDistance = distances[cell] & 0xFF;
        if (isFiniteDistance(cellDistance)) {
            pushCell(cell, cellDistance);
        }
    }

    /**
     * Clears the start list, which every repair fills again.
     */
    private void beginRepair() {
        startCount = 0;
        resetEntryCount = 0;
        resetWalkedCount = 0;
    }

    /**
     * Adds a cell to the start list of the current change.
     */
    private void addStart(int cell) {
        if (startCount < startCells.length) {
            startCells[startCount] = cell;
            startCount++;
        }
    }

    /**
     * Adds the cells that may depend on a face whose cost rises from the given old cost, to the start list. The two
     * tests include equality, which is what resets the cells below a cell that a free down move reaches.
     */
    private void addFaceStarts(int cell, int neighbor, int face, int oldCost) {
        int cellDistance = distances[cell] & 0xFF;
        int neighborDistance = distances[neighbor] & 0xFF;
        if (!isFiniteDistance(cellDistance) || !isFiniteDistance(neighborDistance)) {
            return;
        }
        if (cellDistance >= neighborDistance + MOVE_COSTS[face ^ 1] + oldCost) {
            addStart(cell);
        }
        if (neighborDistance >= cellDistance + MOVE_COSTS[face] + oldCost) {
            addStart(neighbor);
        }
    }

    /**
     * Changes the mask set of a read cell, whose read state stays the same, so no other cell changes its eligibility.
     */
    private void changeMaskSetOfReadCell(int cell, int oldMaskSet, int newMaskSet) {
        beginRepair();
        int cellDistance = distances[cell] & 0xFF;
        int fallenFaces = 0;
        for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
            int neighbor = neighborSlot(cell, face);
            if (neighbor < 0) {
                continue;
            }
            int oldCost = faces.faceCost(oldMaskSet, face, maskSets[neighbor]);
            int newCost = faces.faceCost(newMaskSet, face, maskSets[neighbor]);
            if (newCost > oldCost) {
                addFaceStarts(cell, neighbor, face, oldCost);
            } else if (newCost < oldCost) {
                fallenFaces |= 1 << face;
            }
        }
        int columnHeight = columnHeights[columnIndexOfCell(cell)];
        int oldSeed = seedCostOf(cell, oldMaskSet, columnHeight);
        int newSeed = seedCostOf(cell, newMaskSet, columnHeight);
        if (oldSeed <= CAP && newSeed > oldSeed && cellDistance >= oldSeed) {
            addStart(cell);
        }
        boolean decreased = decreaseWalk();
        maskSets[cell] = (char) newMaskSet;
        if (!decreased) {
            resolveAll();
            return;
        }
        increaseFromResets();
        pushSeedIfBetter(cell);
        for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
            if ((fallenFaces & (1 << face)) == 0) {
                continue;
            }
            pushIfFinite(cell);
            int neighbor = neighborSlot(cell, face);
            if (neighbor >= 0) {
                pushIfFinite(neighbor);
            }
        }
    }

    /**
     * Reads a cell that was unread, whose faces were all closed and are now those of its mask set. Only the cell below
     * it can lose a seed, and it keeps its byte when the sky still reaches it through the new cell.
     */
    private void readUnreadCell(int cell, int newMaskSet) {
        beginRepair();
        int belowCell = neighborSlot(cell, DOWN_FACE);
        if (belowCell >= 0) {
            int belowDistance = distances[belowCell] & 0xFF;
            int oldSeed = seedCostOf(belowCell);
            if (oldSeed <= CAP && isFiniteDistance(belowDistance)) {
                int newSeed = seedCostOf(cell, newMaskSet, columnHeights[columnIndexOfCell(cell)]);
                int downFaceCost = faces.faceCost(newMaskSet, DOWN_FACE, maskSets[belowCell]);
                int alternative = SnowFaces.CLOSED_COST;
                if (newSeed < SnowFaces.CLOSED_COST && downFaceCost < SnowFaces.CLOSED_COST) {
                    alternative = newSeed + MOVE_COSTS[DOWN_FACE] + downFaceCost;
                }
                if (alternative > oldSeed && belowDistance >= oldSeed) {
                    addStart(belowCell);
                }
            }
        }
        boolean decreased = decreaseWalk();
        installReadCell(cell, newMaskSet);
        if (!decreased) {
            resolveAll();
            return;
        }
        increaseFromResets();
        pushSeedIfBetter(cell);
        for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
            int neighbor = neighborSlot(cell, face);
            if (neighbor < 0) {
                continue;
            }
            if (faces.faceCost(newMaskSet, face, maskSets[neighbor]) >= SnowFaces.CLOSED_COST) {
                continue;
            }
            pushIfFinite(neighbor);
        }
    }

    /**
     * Makes a cell read with the given mask set, at SHELTERED until a path reaches it, and marks its brick dirty.
     */
    private void installReadCell(int cell, int newMaskSet) {
        distances[cell] = (byte) SHELTERED;
        maskSets[cell] = (char) newMaskSet;
        markBrickDirty(cell);
    }

    /**
     * Resets the cells that the current change may have made too cheap, with the mask sets and heights of before the
     * change still installed, and keeps their neighbors as sources for the refill.
     * @return whether the decrease ran to completion, which is false when it filled its array
     */
    private boolean decreaseWalk() {
        for (int index = 0; index < startCount; index++) {
            int cell = startCells[index];
            if (!isFiniteDistance(distances[cell] & 0xFF)) {
                continue;
            }
            if (!enqueueReset(cell)) {
                return false;
            }
        }
        while (resetWalkedCount < resetEntryCount) {
            int cell = resetCells[resetWalkedCount];
            int oldByte = resetOldBytes[resetWalkedCount] & 0xFF;
            resetWalkedCount++;
            int cellMaskSet = maskSets[cell];
            for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
                int neighbor = neighborSlot(cell, face);
                if (neighbor < 0) {
                    continue;
                }
                int neighborDistance = distances[neighbor] & 0xFF;
                if (!isFiniteDistance(neighborDistance)) {
                    continue;
                }
                int faceCost = faces.faceCost(cellMaskSet, face, maskSets[neighbor]);
                if (faceCost < SnowFaces.CLOSED_COST
                        && neighborDistance >= oldByte + MOVE_COSTS[face] + faceCost) {
                    if (!enqueueReset(neighbor)) {
                        return false;
                    }
                } else {
                    pushCell(neighbor, neighborDistance);
                }
            }
        }
        return true;
    }

    /**
     * Resets a cell to SHELTERED and appends it to the reset list with its old byte.
     * @return whether the cell fitted in the reset list
     */
    private boolean enqueueReset(int cell) {
        if (resetEntryCount >= resetCells.length) {
            return false;
        }
        resetCells[resetEntryCount] = cell;
        resetOldBytes[resetEntryCount] = distances[cell];
        resetEntryCount++;
        distances[cell] = (byte) SHELTERED;
        markBrickDirty(cell);
        return true;
    }

    /**
     * Seeds every cell the decrease reset, with the mask sets and heights of after the change.
     */
    private void increaseFromResets() {
        for (int index = 0; index < resetEntryCount; index++) {
            pushSeedIfBetter(resetCells[index]);
        }
    }

    /**
     * Takes the next word of the spilled set back into the queue, from the scan cursor on, and returns the steps it
     * used: one for an empty word and one per cell taken back.
     */
    private int takeBackSpilledWord(int remainingSteps) {
        int wordIndex = spillWordCursor;
        long word = spilledBits[wordIndex];
        if (word == 0L) {
            spillWordCursor = wordIndex + 1 == spilledWordCount ? 0 : wordIndex + 1;
            return 1;
        }
        // The cursor stays on a word until it is empty, so a budget of one step still takes back one cell per call:
        int steps = 0;
        while (word != 0L && steps < remainingSteps) {
            int bit = Long.numberOfTrailingZeros(word);
            word &= word - 1L;
            steps++;
            int cell = (wordIndex << 6) + bit;
            spilledCellCount--;
            int cellDistance = distances[cell] & 0xFF;
            if (isFiniteDistance(cellDistance)) {
                pushCell(cell, cellDistance);
            }
        }
        spilledBits[wordIndex] = word;
        if (word == 0L) {
            spillWordCursor = wordIndex + 1 == spilledWordCount ? 0 : wordIndex + 1;
        }
        return steps;
    }

    /**
     * Pops the lowest queued entry and expands its cell when the entry is not stale.
     * @return whether an entry was found
     */
    private boolean expandNextEntry() {
        while (currentBucket <= CAP && bucketHeads[currentBucket] < 0) {
            currentBucket++;
        }
        if (currentBucket > CAP) {
            return false;
        }
        int entry = bucketHeads[currentBucket];
        int nextEntry = entryNexts[entry];
        bucketHeads[currentBucket] = nextEntry;
        if (nextEntry < 0) {
            bucketTails[currentBucket] = -1;
        }
        int cell = entryCells[entry];
        entryNexts[entry] = freeEntryHead;
        freeEntryHead = entry;
        entryCount--;
        if ((distances[cell] & 0xFF) != currentBucket) {
            return true;
        }
        expand(cell);
        return true;
    }

    /**
     * Lowers the bytes of the cells around one cell that its own byte reaches, and queues each one it lowers.
     */
    private void expand(int cell) {
        int cellDistance = distances[cell] & 0xFF;
        int cellMaskSet = maskSets[cell];
        int slotX = cell & sizeMask;
        int slotY = (cell >>> sizeShift) & sizeMask;
        int slotZ = cell >>> (2 * sizeShift);
        int relativeX = (slotX - originX) & sizeMask;
        int relativeY = (slotY - originY) & sizeMask;
        int relativeZ = (slotZ - originZ) & sizeMask;
        for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
            int neighbor;
            if (face == DOWN_FACE) {
                if (relativeY == 0) {
                    continue;
                }
                neighbor = withSlotY(cell, slotY - 1);
            } else if (face == UP_FACE) {
                if (relativeY == sizeMask) {
                    continue;
                }
                neighbor = withSlotY(cell, slotY + 1);
            } else if (face == NORTH_FACE) {
                if (relativeZ == 0) {
                    continue;
                }
                neighbor = withSlotZ(cell, slotZ - 1);
            } else if (face == SOUTH_FACE) {
                if (relativeZ == sizeMask) {
                    continue;
                }
                neighbor = withSlotZ(cell, slotZ + 1);
            } else if (face == WEST_FACE) {
                if (relativeX == 0) {
                    continue;
                }
                neighbor = withSlotX(cell, slotX - 1);
            } else {
                if (relativeX == sizeMask) {
                    continue;
                }
                neighbor = withSlotX(cell, slotX + 1);
            }
            int neighborDistance = distances[neighbor] & 0xFF;
            int faceCost = faces.faceCost(cellMaskSet, face, maskSets[neighbor]);
            if (faceCost >= SnowFaces.CLOSED_COST) {
                continue;
            }
            int candidate = cellDistance + MOVE_COSTS[face] + faceCost;
            if (candidate > CAP || candidate >= neighborDistance) {
                continue;
            }
            distances[neighbor] = (byte) candidate;
            markBrickDirty(neighbor);
            pushCell(neighbor, candidate);
        }
    }

    /**
     * Appends a cell to the bucket of a key, or spills it when the pool has no free entry.
     */
    private void pushCell(int cell, int key) {
        if (freeEntryHead < 0) {
            spillCell(cell);
            return;
        }
        int entry = freeEntryHead;
        freeEntryHead = entryNexts[entry];
        entryCells[entry] = cell;
        entryNexts[entry] = -1;
        int tail = bucketTails[key];
        if (tail < 0) {
            bucketHeads[key] = entry;
        } else {
            entryNexts[tail] = entry;
        }
        bucketTails[key] = entry;
        if (key < currentBucket) {
            currentBucket = key;
        }
        entryCount++;
    }

    /**
     * Sets the bit of a cell in the spilled set, which is taken back into the queue once the queue is empty. A spilled
     * cell is taken back with its own byte, so the order the bits come back in does not matter.
     */
    private void spillCell(int cell) {
        int wordIndex = cell >> 6;
        long bit = 1L << (cell & 63);
        if ((spilledBits[wordIndex] & bit) == 0L) {
            spilledBits[wordIndex] |= bit;
            spilledCellCount++;
        }
        spillCount++;
    }

    /**
     * Empties the queue and returns every entry to the pool.
     */
    private void clearQueue() {
        for (int bucket = 0; bucket <= CAP; bucket++) {
            int entry = bucketHeads[bucket];
            while (entry >= 0) {
                int nextEntry = entryNexts[entry];
                entryNexts[entry] = freeEntryHead;
                freeEntryHead = entry;
                entry = nextEntry;
            }
            bucketHeads[bucket] = -1;
            bucketTails[bucket] = -1;
        }
        entryCount = 0;
        currentBucket = 0;
    }

    /**
     * Empties the spilled set.
     */
    private void clearSpilledCells() {
        for (int word = 0; word < spilledWordCount; word++) {
            spilledBits[word] = 0L;
        }
        spilledCellCount = 0;
        spillWordCursor = 0;
    }
}
