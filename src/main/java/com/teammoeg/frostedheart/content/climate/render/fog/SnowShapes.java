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
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * The shapes of block states that falling snowflakes collide with, at a sixteenth of a block, in a table of distinct
 * shapes, so that every distinct shape has one index and the states with the same shape share it.
 * A shape is a grid of 16 by 16 by 16 voxels, kept as 256 rows of 16 bits. The row of y and z is y * 16 + z, and its bit
 * x is the voxel at x. A voxel is filled when its center lies strictly inside a box of the shape, the rule of SnowFaces.
 * The shape of a state is its outline shape at the default position, EmptyBlockGetter.INSTANCE at BlockPos.ZERO, when
 * the state has a collision shape there, and nothing otherwise, so plants, torches and signs are empty while a fence or
 * a slab is what it looks like. A state with a fluid adds a box from the bottom up to the height of its fluid.
 * The id of a state is the index of its shape with two flags in its top bits: LEAVES_FLAG for a state of the leaves tag,
 * which flakes pass at the leaf porosity, and FLUID_FLAG for a state with a fluid, where a flake vanishes without a
 * splat. snow_flake_update.fsh reads the ids from SnowShapeVolume and the rows from SnowShapeTextures.
 * Not thread safe.
 */
public final class SnowShapes {
    /**
     * The voxels along each axis of a shape.
     */
    public static final int SIDE = 16;
    public static final int ROWS_PER_SHAPE = SIDE * SIDE;
    public static final int EMPTY_SHAPE = 0;
    public static final int FULL_SHAPE = 1;
    /**
     * The bits of an id that hold the index of its shape.
     */
    public static final int SHAPE_MASK = 0x3FFF;
    public static final int LEAVES_FLAG = 0x4000;
    public static final int FLUID_FLAG = 0x8000;
    /**
     * The most shapes the table holds, which is every index SHAPE_MASK can hold.
     */
    public static final int MAXIMUM_SHAPES = SHAPE_MASK + 1;
    /**
     * A row whose 16 voxels are all filled.
     */
    public static final int FULL_ROW = 0xFFFF;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int INITIAL_SHAPE_CAPACITY = 64;

    private final Object2IntOpenHashMap<ShapeKey> shapeIndices = new Object2IntOpenHashMap<>();
    /**
     * The shape index of each outline shape object already rasterized. Many states share one static shape, so this
     * rasterizes each shape once.
     */
    private final Reference2IntOpenHashMap<VoxelShape> outlineShapeIndices = new Reference2IntOpenHashMap<>();
    private final char[] computedRows = new char[ROWS_PER_SHAPE];
    private final Shapes.DoubleLineConsumer boxFiller = this::fillBox;
    private char[] rows = new char[INITIAL_SHAPE_CAPACITY * ROWS_PER_SHAPE];
    private int shapeCount;
    private int[] stateIds;
    private boolean warnedAboutShapeLimit;

    /**
     * Creates a table that holds the empty and the full shape, and a per-state cache that is empty.
     */
    public SnowShapes() {
        this.shapeIndices.defaultReturnValue(-1);
        this.outlineShapeIndices.defaultReturnValue(-1);
        this.stateIds = new int[Block.BLOCK_STATE_REGISTRY.size()];
        Arrays.fill(this.stateIds, -1);
        // The two reserved shapes take the indices 0 and 1 in this order:
        Arrays.fill(computedRows, (char) 0);
        addShape();
        Arrays.fill(computedRows, (char) FULL_ROW);
        addShape();
    }

    /**
     * The id of a block state, which is computed on the first lookup of the state and cached after. A state whose
     * reading throws is full, without flags. The first lookup of a state may allocate. Later lookups allocate nothing,
     * except for a state that has no id in Block.BLOCK_STATE_REGISTRY, which is computed again on every lookup.
     * @param state the block state, which may be any state of the block state registry
     * @return the index of its shape, from 0 to shapeCount() - 1, with LEAVES_FLAG and FLUID_FLAG
     */
    public int idOf(BlockState state) {
        int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (stateId < 0) {
            return computeId(state);
        }
        if (stateId < stateIds.length) {
            int cachedId = stateIds[stateId];
            if (cachedId >= 0) {
                return cachedId;
            }
        }
        int id = computeId(state);
        if (stateId >= stateIds.length) {
            int length = Math.max(stateId + 1, stateIds.length * 2);
            int[] grownStateIds = Arrays.copyOf(stateIds, length);
            Arrays.fill(grownStateIds, stateIds.length, length, -1);
            stateIds = grownStateIds;
        }
        stateIds[stateId] = id;
        return id;
    }

    /**
     * The number of shapes in the table, which is at least the two reserved ones.
     */
    public int shapeCount() {
        return shapeCount;
    }

    /**
     * The 16 voxels along x of one row of a shape.
     * @param shape the shape index, which is an id masked with SHAPE_MASK
     * @return the row, whose bit x is the voxel at x
     */
    public int row(int shape, int y, int z) {
        return rows[shape * ROWS_PER_SHAPE + y * SIDE + z];
    }

    public boolean isFilled(int shape, int x, int y, int z) {
        return ((row(shape, y, z) >>> x) & 1) != 0;
    }

    /**
     * The rows of every shape, shape after shape, for the upload. The array is replaced when the table grows, so it
     * must not be kept between frames.
     */
    char[] rows() {
        return rows;
    }

    /**
     * Forgets which id each block state has, and keeps the table, so that every shape index already handed out keeps
     * its meaning. This is needed when the leaves tag changes, which can happen when the client joins another server.
     * It also forgets the rasterized outline shapes, so shapes that mods create per call do not pile up.
     */
    public void clearStateCache() {
        Arrays.fill(stateIds, -1);
        outlineShapeIndices.clear();
    }

    public static int shapeIndex(int id) {
        return id & SHAPE_MASK;
    }

    public static boolean isLeaves(int id) {
        return (id & LEAVES_FLAG) != 0;
    }

    public static boolean isFluid(int id) {
        return (id & FLUID_FLAG) != 0;
    }

    /**
     * The id of a state that is not in the per-state cache, which allocates.
     */
    private int computeId(BlockState state) {
        try {
            int flags = state.is(BlockTags.LEAVES) ? LEAVES_FLAG : 0;
            int shape = EMPTY_SHAPE;
            if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                shape = outline.isEmpty()
                        ? shapeOfOutline(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                        : shapeOfOutline(outline);
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                flags |= FLUID_FLAG;
                shape = shapeWithFluid(shape, fluid.getOwnHeight());
            }
            return shape | flags;
        } catch (RuntimeException readException) {
            LOGGER.warn("The block state {} cannot be read for the falling snow, so snowflakes collide with all of it.",
                    state, readException);
            return FULL_SHAPE;
        }
    }

    /**
     * The shape index of an outline shape, rasterized on the first lookup of the shape object.
     */
    private int shapeOfOutline(VoxelShape outline) {
        if (outline.isEmpty()) {
            return EMPTY_SHAPE;
        }
        if (outline == Shapes.block()) {
            return FULL_SHAPE;
        }
        int knownShape = outlineShapeIndices.getInt(outline);
        if (knownShape >= 0) {
            return knownShape;
        }
        Arrays.fill(computedRows, (char) 0);
        outline.forAllBoxes(boxFiller);
        int shape = internComputedRows();
        outlineShapeIndices.put(outline, shape);
        return shape;
    }

    /**
     * The shape index of a shape joined with a box from the bottom of the block up to a fluid's height.
     */
    private int shapeWithFluid(int shape, double fluidHeight) {
        System.arraycopy(rows, shape * ROWS_PER_SHAPE, computedRows, 0, ROWS_PER_SHAPE);
        int lastY = SnowFaces.lastVoxelIndex(fluidHeight);
        for (int y = 0; y <= lastY; y++) {
            Arrays.fill(computedRows, y * SIDE, (y + 1) * SIDE, (char) FULL_ROW);
        }
        return internComputedRows();
    }

    /**
     * Sets every voxel whose center lies strictly inside the box, and ignores the parts of the box outside the block.
     */
    private void fillBox(double minimumX, double minimumY, double minimumZ, double maximumX, double maximumY,
            double maximumZ) {
        int firstX = SnowFaces.firstVoxelIndex(minimumX);
        int lastX = SnowFaces.lastVoxelIndex(maximumX);
        if (firstX > lastX) {
            return;
        }
        int xBits = ((1 << (lastX + 1)) - 1) & ~((1 << firstX) - 1);
        int firstY = SnowFaces.firstVoxelIndex(minimumY);
        int lastY = SnowFaces.lastVoxelIndex(maximumY);
        int firstZ = SnowFaces.firstVoxelIndex(minimumZ);
        int lastZ = SnowFaces.lastVoxelIndex(maximumZ);
        for (int y = firstY; y <= lastY; y++) {
            for (int z = firstZ; z <= lastZ; z++) {
                computedRows[y * SIDE + z] |= (char) xBits;
            }
        }
    }

    /**
     * The index of the shape with the computed rows, which adds a table entry and allocates when the rows are new. A
     * new shape past MAXIMUM_SHAPES counts as full.
     */
    private int internComputedRows() {
        boolean empty = true;
        boolean full = true;
        for (int row = 0; row < ROWS_PER_SHAPE; row++) {
            empty &= computedRows[row] == 0;
            full &= computedRows[row] == FULL_ROW;
        }
        if (empty) {
            return EMPTY_SHAPE;
        }
        if (full) {
            return FULL_SHAPE;
        }
        ShapeKey key = new ShapeKey(computedRows.clone());
        int existingShape = shapeIndices.getInt(key);
        if (existingShape >= 0) {
            return existingShape;
        }
        if (shapeCount >= MAXIMUM_SHAPES) {
            if (!warnedAboutShapeLimit) {
                warnedAboutShapeLimit = true;
                LOGGER.warn("The table of shapes for the falling snow is full at {} shapes, so snowflakes collide "
                        + "with all of every further shape until the game is restarted.", MAXIMUM_SHAPES);
            }
            return FULL_SHAPE;
        }
        int shape = addShape();
        shapeIndices.put(key, shape);
        return shape;
    }

    /**
     * Adds the computed rows to the end of the table and returns their index.
     */
    private int addShape() {
        int shape = shapeCount;
        int needed = (shape + 1) * ROWS_PER_SHAPE;
        if (rows.length < needed) {
            rows = Arrays.copyOf(rows, Math.max(needed, rows.length * 2));
        }
        System.arraycopy(computedRows, 0, rows, shape * ROWS_PER_SHAPE, ROWS_PER_SHAPE);
        shapeCount = shape + 1;
        return shape;
    }

    /**
     * The rows of a shape that is neither empty nor full, used as the key of the table's lookup map.
     */
    private static final class ShapeKey {
        private final char[] rows;
        private final int hash;

        private ShapeKey(char[] rows) {
            this.rows = rows;
            this.hash = Arrays.hashCode(rows);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof ShapeKey otherKey && Arrays.equals(rows, otherKey.rows);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
