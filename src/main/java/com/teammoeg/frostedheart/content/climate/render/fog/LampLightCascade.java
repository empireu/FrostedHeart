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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * One cascade of a LampLightField, a window of cells aligned to the world grid and stored with wrapping addresses, so
 * a cell's slot is its world cell coordinate modulo the window's size on each axis. Each cell holds two half floats,
 * the light share and the air share described in LampLightField. A brick is the footprint of one chunk section in
 * the slots, so one section's cells always lie in one brick. Bricks whose slots changed are marked for upload.
 */
public final class LampLightCascade {
    /**
     * The shorts of one cell, its light share and its air share.
     */
    public static final int CHANNELS = 2;
    private static final int FILL_CELLS = 4096;

    private final short[] fillPattern = new short[FILL_CELLS * CHANNELS];
    private final int index;
    private final int cellWidth;
    private final int cellHeight;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int brickWidth;
    private final int brickHeight;
    private final int bricksX;
    private final int bricksY;
    private final int bricksZ;
    private final ShortBuffer values;
    /**
     * The number of slots with a light share above 0 in each slot layer across x, y and z.
     */
    private final int[] litCellsX;
    private final int[] litCellsY;
    private final int[] litCellsZ;
    private final long[] dirtyBricks;
    private int dirtyCount;
    private boolean placed;
    private int originX;
    private int originY;
    private int originZ;
    /**
     * Whether the window stays at the bottom of the level vertically, because it holds the level's whole height.
     */
    private boolean fixedVertically;

    LampLightCascade(int index, int cellWidth, int cellHeight, int sizeX, int sizeY, int sizeZ) {
        this.index = index;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        brickWidth = LampLightField.SECTION_SIZE / cellWidth;
        brickHeight = LampLightField.SECTION_SIZE / cellHeight;
        bricksX = sizeX / brickWidth;
        bricksY = sizeY / brickHeight;
        bricksZ = sizeZ / brickWidth;
        values = ByteBuffer.allocateDirect(sizeX * sizeY * sizeZ * CHANNELS * Short.BYTES)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        litCellsX = new int[sizeX];
        litCellsY = new int[sizeY];
        litCellsZ = new int[sizeZ];
        dirtyBricks = new long[(brickCount() + Long.SIZE - 1) / Long.SIZE];
        clear();
    }

    public int index() {
        return index;
    }

    public int cellWidth() {
        return cellWidth;
    }

    public int cellHeight() {
        return cellHeight;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int brickWidth() {
        return brickWidth;
    }

    public int brickHeight() {
        return brickHeight;
    }

    public int bricksX() {
        return bricksX;
    }

    public int bricksY() {
        return bricksY;
    }

    public int bricksZ() {
        return bricksZ;
    }

    public int brickCount() {
        return bricksX * bricksY * bricksZ;
    }

    public boolean isPlaced() {
        return placed;
    }

    /**
     * The lowest world cell coordinates of the window.
     */
    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public boolean isFixedVertically() {
        return fixedVertically;
    }

    /**
     * The cells of every slot, interleaved light and air shares as half floats, with x fastest, then y, then z.
     */
    public ShortBuffer values() {
        return values;
    }

    void place(int newOriginX, int newOriginY, int newOriginZ, boolean newFixedVertically) {
        placed = true;
        originX = newOriginX;
        originY = newOriginY;
        originZ = newOriginZ;
        fixedVertically = newFixedVertically;
    }

    /**
     * Forgets the window and sets every slot to dark open air, marking every brick.
     */
    void clear() {
        placed = false;
        fill(LampLightField.DARK_OPEN_AIR_LIGHT, LampLightField.DARK_OPEN_AIR_AIR);
    }

    /**
     * Sets every slot to one cell value and marks every brick.
     */
    void fill(short light, short air) {
        for (int cell = 0; cell < FILL_CELLS; cell++) {
            fillPattern[cell * CHANNELS] = light;
            fillPattern[cell * CHANNELS + 1] = air;
        }
        int total = sizeX * sizeY * sizeZ * CHANNELS;
        for (int start = 0; start < total; start += fillPattern.length) {
            values.put(start, fillPattern, 0, Math.min(fillPattern.length, total - start));
        }
        boolean lit = isLit(light);
        Arrays.fill(litCellsX, lit ? sizeY * sizeZ : 0);
        Arrays.fill(litCellsY, lit ? sizeX * sizeZ : 0);
        Arrays.fill(litCellsZ, lit ? sizeX * sizeY : 0);
        markAllDirty();
    }

    public boolean containsCell(int cellX, int cellY, int cellZ) {
        return placed && cellX >= originX && cellX < originX + sizeX && cellY >= originY && cellY < originY + sizeY
                && cellZ >= originZ && cellZ < originZ + sizeZ;
    }

    public int slotX(int cellX) {
        return Math.floorMod(cellX, sizeX);
    }

    public int slotY(int cellY) {
        return Math.floorMod(cellY, sizeY);
    }

    public int slotZ(int cellZ) {
        return Math.floorMod(cellZ, sizeZ);
    }

    /**
     * The index of a slot's light share in values, its air share following it.
     */
    public int valueIndex(int slotX, int slotY, int slotZ) {
        return ((slotZ * sizeY + slotY) * sizeX + slotX) * CHANNELS;
    }

    /**
     * The light share of a world cell's slot as a half float, whether or not the window holds the cell.
     */
    public short lightAt(int cellX, int cellY, int cellZ) {
        return values.get(valueIndex(slotX(cellX), slotY(cellY), slotZ(cellZ)));
    }

    public short airAt(int cellX, int cellY, int cellZ) {
        return values.get(valueIndex(slotX(cellX), slotY(cellY), slotZ(cellZ)) + 1);
    }

    void put(int cellX, int cellY, int cellZ, short light, short air) {
        int slotX = slotX(cellX);
        int slotY = slotY(cellY);
        int slotZ = slotZ(cellZ);
        int slot = valueIndex(slotX, slotY, slotZ);
        boolean wasLit = isLit(values.get(slot));
        boolean lit = isLit(light);
        if (lit != wasLit) {
            int change = lit ? 1 : -1;
            litCellsX[slotX] += change;
            litCellsY[slotY] += change;
            litCellsZ[slotZ] += change;
        }
        values.put(slot, light);
        values.put(slot + 1, air);
    }

    /**
     * Whether a light share as a half float is above 0, subnormals included.
     */
    public static boolean isLit(short light) {
        return (light & 0x7fff) != 0;
    }

    /**
     * Finds the box of the window's cells whose light share is above 0, in cells from the window's lowest cell.
     * @param box receives the lowest cell on x, y and z and then the cell past the highest on each
     * @return whether the window is placed and any of its cells holds light
     */
    public boolean litBox(int[] box) {
        return placed && litRange(litCellsX, originX, sizeX, box, 0) && litRange(litCellsY, originY, sizeY, box, 1)
                && litRange(litCellsZ, originZ, sizeZ, box, 2);
    }

    private static boolean litRange(int[] litCells, int origin, int size, int[] box, int axis) {
        int originSlot = Math.floorMod(origin, size);
        int low = -1;
        int high = -1;
        for (int offset = 0; offset < size; offset++) {
            int slot = originSlot + offset;
            if (slot >= size) {
                slot -= size;
            }
            if (litCells[slot] > 0) {
                if (low < 0) {
                    low = offset;
                }
                high = offset;
            }
        }
        if (low < 0) {
            return false;
        }
        box[axis] = low;
        box[axis + 3] = high + 1;
        return true;
    }

    /**
     * Sets the cells of a box of world cells, given by its lowest and past-highest coordinates, which must lie in the
     * window, to one value and marks their bricks.
     */
    void fillBox(int lowX, int lowY, int lowZ, int highX, int highY, int highZ, short light, short air) {
        for (int cellZ = lowZ; cellZ < highZ; cellZ++) {
            for (int cellY = lowY; cellY < highY; cellY++) {
                for (int cellX = lowX; cellX < highX; cellX++) {
                    put(cellX, cellY, cellZ, light, air);
                }
            }
        }
        markBoxDirty(lowX, lowY, lowZ, highX, highY, highZ);
    }

    /**
     * Marks the bricks that hold any cell of a box of world cells within the window.
     */
    void markBoxDirty(int lowX, int lowY, int lowZ, int highX, int highY, int highZ) {
        if (lowX >= highX || lowY >= highY || lowZ >= highZ) {
            return;
        }
        int firstBrickX = Math.floorDiv(lowX, brickWidth);
        int lastBrickX = Math.floorDiv(highX - 1, brickWidth);
        int firstBrickY = Math.floorDiv(lowY, brickHeight);
        int lastBrickY = Math.floorDiv(highY - 1, brickHeight);
        int firstBrickZ = Math.floorDiv(lowZ, brickWidth);
        int lastBrickZ = Math.floorDiv(highZ - 1, brickWidth);
        for (int brickZ = firstBrickZ; brickZ <= lastBrickZ; brickZ++) {
            for (int brickY = firstBrickY; brickY <= lastBrickY; brickY++) {
                for (int brickX = firstBrickX; brickX <= lastBrickX; brickX++) {
                    markBrickDirty(Math.floorMod(brickX, bricksX), Math.floorMod(brickY, bricksY),
                            Math.floorMod(brickZ, bricksZ));
                }
            }
        }
    }

    private void markBrickDirty(int slotBrickX, int slotBrickY, int slotBrickZ) {
        int brick = (slotBrickZ * bricksY + slotBrickY) * bricksX + slotBrickX;
        long bit = 1L << (brick & (Long.SIZE - 1));
        int word = brick >>> 6;
        if ((dirtyBricks[word] & bit) == 0L) {
            dirtyBricks[word] |= bit;
            dirtyCount++;
        }
    }

    void markAllDirty() {
        Arrays.fill(dirtyBricks, 0L);
        int count = brickCount();
        for (int brick = 0; brick < count; brick++) {
            dirtyBricks[brick >>> 6] |= 1L << (brick & (Long.SIZE - 1));
        }
        dirtyCount = count;
    }

    public int dirtyCount() {
        return dirtyCount;
    }

    /**
     * The marks of 64 bricks, the brick (z * bricksY + y) * bricksX + x at bit 64 * word + bit.
     */
    public long dirtyWord(int word) {
        return dirtyBricks[word];
    }

    public int dirtyWordCount() {
        return dirtyBricks.length;
    }

    /**
     * Whether the brick of slot brick coordinates is marked for upload.
     */
    public boolean isBrickDirty(int slotBrickX, int slotBrickY, int slotBrickZ) {
        int brick = (slotBrickZ * bricksY + slotBrickY) * bricksX + slotBrickX;
        return (dirtyBricks[brick >>> 6] & (1L << (brick & (Long.SIZE - 1)))) != 0L;
    }

    /**
     * Unmarks every brick, called once the uploads took them.
     */
    public void clearDirty() {
        Arrays.fill(dirtyBricks, 0L);
        dirtyCount = 0;
    }
}
