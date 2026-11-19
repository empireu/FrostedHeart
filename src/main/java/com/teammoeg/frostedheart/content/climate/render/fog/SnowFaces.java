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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * The closed parts of the six faces of block states and the cost of crossing the face between two cells.
 * A mask set is the six 16-bit face masks of a block state plus whether it is leaves, and this class keeps a table of
 * distinct mask sets, so that every distinct set has one id and the states with the same closed parts share it.
 * The masks come from the outline shape of a state at the default position, which is EmptyBlockGetter.INSTANCE at
 * BlockPos.ZERO, rasterized into a 16 by 16 by 16 grid of voxels.
 * The faces are numbered by Direction.get3DDataValue, 0 down, 1 up, 2 north, 3 south, 4 west and 5 east, and the face
 * opposite to face f is f ^ 1.
 * Each face has a 4 by 4 grid of sub-squares, and its mask has the bit j * 4 + i for the sub-square in column i and row
 * j, so 0x000F is the bottom row of a side face, 0x1111 is the column at the low end of the columns and 0xFFFF is the
 * whole face. The columns of the down and up faces run along x and their rows along z, the columns of the north and
 * south faces run along x and their rows along y, and the columns of the west and east faces run along z and their rows
 * along y. Both faces of an axis use the same columns and rows, so the masks of two cells that share a face combine
 * with a plain bitwise or.
 * The costs are in eighths of a block, and CLOSED_COST stands for a face that snow cannot cross at all.
 * Not thread safe.
 */
public final class SnowFaces {
    /**
     * The number of faces of a block state, which is the number of directions of Direction.get3DDataValue.
     */
    public static final int FACE_COUNT = 6;
    /**
     * The mask of a face whose 16 sub-squares are all closed.
     */
    public static final int FULL_MASK = 0xFFFF;
    /**
     * The mask set of a state that is open on all six faces, with all six masks 0 and without the leaves flag.
     */
    public static final int EMPTY_MASK_SET = 0;
    /**
     * The mask set of a state that is closed on all six faces, with all six masks FULL_MASK and without the leaves
     * flag.
     */
    public static final int FULL_MASK_SET = 1;
    /**
     * The mask set of a state of leaves, with all six masks 0 and the leaves flag.
     */
    public static final int LEAVES_MASK_SET = 2;
    /**
     * The most mask sets the table holds, which lets a mask set id fit in 16 bits with one value to spare.
     */
    public static final int MAXIMUM_MASK_SETS = 65535;
    /**
     * The cost of a face that snow cannot cross at all, in eighths of a block. It is far beyond the 192 eighths of a
     * block that the solver keeps, so a sum with it never overflows and always lands beyond the solver's cap.
     */
    public static final int CLOSED_COST = 1 << 16;
    /**
     * The share of a face that leaves let snow through when the caller has no porosity of its own, 0.3.
     */
    public static final double DEFAULT_LEAF_POROSITY = 0.3;

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The number of pixels along each axis of a face and of each axis of the voxel grid.
     */
    private static final int PIXEL_COUNT = 16;
    private static final int VOXEL_COUNT = PIXEL_COUNT * PIXEL_COUNT * PIXEL_COUNT;
    /**
     * The number of sub-squares of a face, which is PIXEL_COUNT / 4 along each axis.
     */
    private static final int SUB_SQUARE_COUNT = 16;
    /**
     * The number of covered pixels from which a sub-square counts as closed, which is more than half of its 16 pixels.
     */
    private static final int CLOSED_SUB_SQUARE_PIXEL_COUNT = 9;
    private static final int NEGATIVE_END_BIT = 1;
    private static final int POSITIVE_END_BIT = 1 << (PIXEL_COUNT - 1);
    /**
     * The distance in blocks that crossing a face adds for each unit of -ln of its open share.
     */
    private static final double FACE_PENALTY_BLOCKS = 3.0;
    /**
     * The number of eighths of a block in one block, in which the costs are counted.
     */
    private static final int EIGHTHS_PER_BLOCK = 8;
    private static final int INITIAL_MASK_SET_CAPACITY = 8;
    private static final int DOWN_FACE = 0;
    private static final int UP_FACE = 1;
    private static final int NORTH_FACE = 2;
    private static final int SOUTH_FACE = 3;
    private static final int WEST_FACE = 4;
    private static final int EAST_FACE = 5;
    /**
     * The cost of a face for each number of covered sub-squares, in eighths of a block, built once at class load.
     */
    private static final int[] COVERAGE_COSTS = new int[SUB_SQUARE_COUNT + 1];

    static {
        for (int coveredSubSquares = 0; coveredSubSquares <= SUB_SQUARE_COUNT; coveredSubSquares++) {
            COVERAGE_COSTS[coveredSubSquares] = coverageCostOf(coveredSubSquares);
        }
    }

    private final Object2IntOpenHashMap<MaskSetKey> maskSetIds = new Object2IntOpenHashMap<>();
    /**
     * The mask set of each shape object already rasterized. Many states share one static shape, so this rasterizes each
     * shape once.
     */
    private final Reference2IntOpenHashMap<VoxelShape> shapeMaskSets = new Reference2IntOpenHashMap<>();
    private final boolean[] filledVoxels = new boolean[VOXEL_COUNT];
    private final int[] negativeFaceSubSquarePixels = new int[SUB_SQUARE_COUNT];
    private final int[] positiveFaceSubSquarePixels = new int[SUB_SQUARE_COUNT];
    private final int[] computedFaceMasks = new int[FACE_COUNT];
    private final Shapes.DoubleLineConsumer boxFiller = this::fillBox;
    private char[] maskSetFaceMasks = new char[INITIAL_MASK_SET_CAPACITY * FACE_COUNT];
    private boolean[] maskSetLeaves = new boolean[INITIAL_MASK_SET_CAPACITY];
    private boolean[] maskSetClosesAllFaces = new boolean[INITIAL_MASK_SET_CAPACITY];
    private int maskSetTableSize;
    private int[] stateMaskSets;
    private int leafCost;
    private boolean warnedAboutMaskSetLimit;

    /**
     * Creates a table that holds the three reserved mask sets, and a per-state cache that is empty.
     * @param leafPorosity the share of a face that leaves let snow through, from 0 for closed to 1 or more for fully
     *        open, as setLeafPorosity takes it
     */
    public SnowFaces(double leafPorosity) {
        this.leafCost = leafCostOf(leafPorosity);
        this.maskSetIds.defaultReturnValue(-1);
        this.shapeMaskSets.defaultReturnValue(-1);
        this.stateMaskSets = new int[Block.BLOCK_STATE_REGISTRY.size()];
        Arrays.fill(this.stateMaskSets, -1);
        // The three reserved mask sets take the ids 0 to 2 in this order.
        addMaskSet(0, 0, 0, 0, 0, 0, false);
        addMaskSet(FULL_MASK, FULL_MASK, FULL_MASK, FULL_MASK, FULL_MASK, FULL_MASK, false);
        addMaskSet(0, 0, 0, 0, 0, 0, true);
    }

    /**
     * The id of the mask set of a block state, which is computed on the first lookup of the state and cached after.
     * The masks come from the outline shape at the default position, EmptyBlockGetter.INSTANCE at BlockPos.ZERO, so
     * offset blocks such as flowers get the masks of their shape as it is shifted at BlockPos.ZERO, which is a quarter
     * block toward negative x and z for most of them. A state with a fluid is closed on all six faces, a state of the
     * leaves tag is open with the leaves flag set, and a state whose reading throws is closed on all six faces.
     * The first lookup of a state may allocate. Later lookups allocate nothing, except for a state that has no id in
     * Block.BLOCK_STATE_REGISTRY, which is computed again on every lookup.
     * @param state the block state, which may be any state of the block state registry
     * @return the id of its mask set, in the range 0 to maskSetCount() - 1
     */
    public int maskSetOf(BlockState state) {
        int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (stateId < 0) {
            return computeMaskSet(state);
        }
        if (stateId < stateMaskSets.length) {
            int cachedMaskSet = stateMaskSets[stateId];
            if (cachedMaskSet >= 0) {
                return cachedMaskSet;
            }
        }
        int maskSet = computeMaskSet(state);
        if (stateId >= stateMaskSets.length) {
            growStateCache(stateId + 1);
        }
        stateMaskSets[stateId] = maskSet;
        return maskSet;
    }

    /**
     * The 16-bit mask of the closed sub-squares of one face of a mask set.
     * @param maskSet the mask set id, from maskSetOf
     * @param face the face, from 0 for down, 1 for up, 2 for north, 3 for south, 4 for west to 5 for east
     * @return the mask, in which the bit of the sub-square in column i and row j is j * 4 + i, and 0xFFFF is the whole
     *         face
     */
    public int faceMask(int maskSet, int face) {
        return maskSetFaceMasks[maskSet * FACE_COUNT + face];
    }

    /**
     * Whether the states of a mask set are leaves, which let snow through the smaller of their porosity and the open
     * share of the face.
     * @param maskSet the mask set id, from maskSetOf
     * @return the leaves flag of the mask set
     */
    public boolean isLeaves(int maskSet) {
        return maskSetLeaves[maskSet];
    }

    /**
     * Whether all six masks of a mask set are FULL_MASK, so that the solver may treat a cell of the set as sheltered
     * without visiting it.
     * @param maskSet the mask set id, from maskSetOf
     * @return whether the mask set closes every face
     */
    public boolean closesAllFaces(int maskSet) {
        return maskSetClosesAllFaces[maskSet];
    }

    /**
     * The cost of crossing the face between a cell of one mask set and the cell across it, in eighths of a block.
     * It is CLOSED_COST when the two masks together close the whole face, even when a cell is leaves, and otherwise
     * the coverage cost of their union, raised to the leaf cost when either cell is leaves. The cost is symmetric:
     * faceCost(firstMaskSet, face, secondMaskSet) equals faceCost(secondMaskSet, face ^ 1, firstMaskSet).
     * @param maskSet the mask set of the cell that snow crosses the face from
     * @param face the face of that cell that snow crosses, from 0 for down to 5 for east
     * @param neighborMaskSet the mask set of the cell across the face
     * @return the cost in eighths of a block, from 0 to CLOSED_COST
     */
    public int faceCost(int maskSet, int face, int neighborMaskSet) {
        int union = faceMask(maskSet, face) | faceMask(neighborMaskSet, face ^ 1);
        if (union == FULL_MASK) {
            return CLOSED_COST;
        }
        int cost = coverageCost(Integer.bitCount(union));
        if (isLeaves(maskSet) || isLeaves(neighborMaskSet)) {
            cost = Math.max(cost, leafCost);
        }
        return cost;
    }

    /**
     * Sets the porosity that faces of leaves cost, which changes only leafCost and leaves the ids and the masks of the
     * mask sets as they are.
     * @param leafPorosity the share of a face that leaves let snow through, from 0 for closed to 1 or more for fully
     *        open
     */
    public void setLeafPorosity(double leafPorosity) {
        this.leafCost = leafCostOf(leafPorosity);
    }

    /**
     * The cost of a face that leaves let snow through at this instance's leaf porosity, in eighths of a block.
     * @return 0 for a porosity of 1 or more, CLOSED_COST for a porosity of 0 or less or NaN, and the formula's cost
     *         otherwise
     */
    public int leafCost() {
        return leafCost;
    }

    /**
     * The number of mask sets in the table, which is at least the three reserved ones.
     * @return the number of mask sets
     */
    public int maskSetCount() {
        return maskSetTableSize;
    }

    /**
     * Forgets which mask set each block state has, and keeps the table, so that every id already handed out keeps its
     * meaning. This is needed when the leaves tag changes, which can happen when the client joins another server.
     * It also forgets the rasterized shapes, so shapes that mods create per call do not pile up.
     */
    public void clearStateCache() {
        Arrays.fill(stateMaskSets, -1);
        shapeMaskSets.clear();
    }

    /**
     * The cost of a face whose two masks together cover the given number of its 16 sub-squares, in eighths of a block.
     * It is round(24 * -ln(1 - coveredSubSquares / 16)), which is 3 blocks times -ln of the open share, and
     * CLOSED_COST when all 16 sub-squares are covered.
     * @param coveredSubSquares the number of covered sub-squares, from 0 to 16
     * @return the cost in eighths of a block, from 0 to CLOSED_COST
     * @throws IllegalArgumentException when coveredSubSquares is below 0 or above 16
     */
    public static int coverageCost(int coveredSubSquares) {
        if (coveredSubSquares < 0 || coveredSubSquares > SUB_SQUARE_COUNT) {
            throw new IllegalArgumentException("The covered sub-square count " + coveredSubSquares
                    + " is not in 0 to " + SUB_SQUARE_COUNT);
        }
        return COVERAGE_COSTS[coveredSubSquares];
    }

    /**
     * The cost of a face that leaves of the given porosity let snow through, in eighths of a block. It is
     * round(24 * -ln leafPorosity), which is 3 blocks times -ln of the porosity.
     * @param leafPorosity the share of a face that leaves let snow through
     * @return 0 for a porosity of 1 or more, CLOSED_COST for a porosity of 0 or less or NaN, and the formula's cost
     *         otherwise
     */
    public static int leafCostOf(double leafPorosity) {
        if (!(leafPorosity > 0.0)) {
            return CLOSED_COST;
        }
        if (leafPorosity >= 1.0) {
            return 0;
        }
        return (int) Math.round(FACE_PENALTY_BLOCKS * EIGHTHS_PER_BLOCK * -Math.log(leafPorosity));
    }

    /**
     * The cost of a face with the given number of covered sub-squares, which the table of this class is built from.
     */
    private static int coverageCostOf(int coveredSubSquares) {
        double openShare = 1.0 - coveredSubSquares / (double) SUB_SQUARE_COUNT;
        if (!(openShare > 0.0)) {
            return CLOSED_COST;
        }
        return (int) Math.round(FACE_PENALTY_BLOCKS * EIGHTHS_PER_BLOCK * -Math.log(openShare));
    }

    /**
     * The mask set of a state that is not in the per-state cache, which allocates.
     */
    private int computeMaskSet(BlockState state) {
        try {
            if (!state.getFluidState().isEmpty()) {
                return FULL_MASK_SET;
            }
            if (state.is(BlockTags.LEAVES)) {
                return LEAVES_MASK_SET;
            }
            return maskSetOfShape(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (RuntimeException readException) {
            LOGGER.warn("The block state {} cannot be read for the weather fog, so all six of its faces are closed.",
                    state, readException);
            return FULL_MASK_SET;
        }
    }

    /**
     * The mask set of an outline shape, rasterized on the first lookup of the shape object.
     */
    private int maskSetOfShape(VoxelShape shape) {
        if (shape.isEmpty()) {
            return EMPTY_MASK_SET;
        }
        if (shape == Shapes.block()) {
            return FULL_MASK_SET;
        }
        int knownMaskSet = shapeMaskSets.getInt(shape);
        if (knownMaskSet >= 0) {
            return knownMaskSet;
        }
        Arrays.fill(filledVoxels, false);
        shape.forAllBoxes(boxFiller);
        computeFaceMasks();
        int maskSet = internComputedFaceMasks();
        shapeMaskSets.put(shape, maskSet);
        return maskSet;
    }

    /**
     * Sets every voxel whose center lies strictly inside the box, and ignores the parts of the box outside the unit
     * cell.
     */
    private void fillBox(double minimumX, double minimumY, double minimumZ, double maximumX, double maximumY,
            double maximumZ) {
        int firstX = firstVoxelIndex(minimumX);
        int lastX = lastVoxelIndex(maximumX);
        int firstY = firstVoxelIndex(minimumY);
        int lastY = lastVoxelIndex(maximumY);
        int firstZ = firstVoxelIndex(minimumZ);
        int lastZ = lastVoxelIndex(maximumZ);
        for (int x = firstX; x <= lastX; x++) {
            for (int y = firstY; y <= lastY; y++) {
                for (int z = firstZ; z <= lastZ; z++) {
                    filledVoxels[(x << 8) | (y << 4) | z] = true;
                }
            }
        }
    }

    /**
     * The first voxel index along an axis whose center lies strictly above the given lower bound of a box, or
     * PIXEL_COUNT when no voxel does.
     */
    private static int firstVoxelIndex(double lowerBound) {
        double scaledBound = lowerBound * PIXEL_COUNT - 0.5;
        if (Double.isNaN(scaledBound)) {
            return PIXEL_COUNT;
        }
        if (scaledBound < 0.0) {
            return 0;
        }
        if (scaledBound >= PIXEL_COUNT) {
            return PIXEL_COUNT;
        }
        return (int) Math.floor(scaledBound) + 1;
    }

    /**
     * The last voxel index along an axis whose center lies strictly below the given upper bound of a box, or -1 when
     * no voxel does.
     */
    private static int lastVoxelIndex(double upperBound) {
        double scaledBound = upperBound * PIXEL_COUNT - 0.5;
        if (Double.isNaN(scaledBound)) {
            return -1;
        }
        if (scaledBound < 0.0) {
            return -1;
        }
        if (scaledBound >= PIXEL_COUNT) {
            return PIXEL_COUNT - 1;
        }
        return (int) Math.ceil(scaledBound) - 1;
    }

    /**
     * Fills computedFaceMasks with the masks of the six faces, from the filled voxels.
     */
    private void computeFaceMasks() {
        countDownAndUpFacePixels();
        computedFaceMasks[DOWN_FACE] = subSquareMask(negativeFaceSubSquarePixels);
        computedFaceMasks[UP_FACE] = subSquareMask(positiveFaceSubSquarePixels);
        countNorthAndSouthFacePixels();
        computedFaceMasks[NORTH_FACE] = subSquareMask(negativeFaceSubSquarePixels);
        computedFaceMasks[SOUTH_FACE] = subSquareMask(positiveFaceSubSquarePixels);
        countWestAndEastFacePixels();
        computedFaceMasks[WEST_FACE] = subSquareMask(negativeFaceSubSquarePixels);
        computedFaceMasks[EAST_FACE] = subSquareMask(positiveFaceSubSquarePixels);
    }

    /**
     * Counts the covered pixels of the down and up faces, whose columns run along x and whose rows run along z.
     */
    private void countDownAndUpFacePixels() {
        Arrays.fill(negativeFaceSubSquarePixels, 0);
        Arrays.fill(positiveFaceSubSquarePixels, 0);
        for (int row = 0; row < PIXEL_COUNT; row++) {
            for (int column = 0; column < PIXEL_COUNT; column++) {
                int lineMask = 0;
                for (int y = 0; y < PIXEL_COUNT; y++) {
                    if (filledVoxels[(column << 8) | (y << 4) | row]) {
                        lineMask |= 1 << y;
                    }
                }
                addLineCoverage(column, row, lineMask);
            }
        }
    }

    /**
     * Counts the covered pixels of the north and south faces, whose columns run along x and whose rows run along y.
     */
    private void countNorthAndSouthFacePixels() {
        Arrays.fill(negativeFaceSubSquarePixels, 0);
        Arrays.fill(positiveFaceSubSquarePixels, 0);
        for (int row = 0; row < PIXEL_COUNT; row++) {
            for (int column = 0; column < PIXEL_COUNT; column++) {
                int lineMask = 0;
                for (int z = 0; z < PIXEL_COUNT; z++) {
                    if (filledVoxels[(column << 8) | (row << 4) | z]) {
                        lineMask |= 1 << z;
                    }
                }
                addLineCoverage(column, row, lineMask);
            }
        }
    }

    /**
     * Counts the covered pixels of the west and east faces, whose columns run along z and whose rows run along y.
     */
    private void countWestAndEastFacePixels() {
        Arrays.fill(negativeFaceSubSquarePixels, 0);
        Arrays.fill(positiveFaceSubSquarePixels, 0);
        for (int row = 0; row < PIXEL_COUNT; row++) {
            for (int column = 0; column < PIXEL_COUNT; column++) {
                int lineMask = 0;
                for (int x = 0; x < PIXEL_COUNT; x++) {
                    if (filledVoxels[(x << 8) | (row << 4) | column]) {
                        lineMask |= 1 << x;
                    }
                }
                addLineCoverage(column, row, lineMask);
            }
        }
    }

    /**
     * Adds the coverage of one line of voxels to the pixel counts of the negative and the positive face.
     * A line covers its pixel of the negative face when its first voxel is filled, its pixel of the positive face when
     * its last voxel is filled, and its pixels of both faces when a run of filled voxels touches neither end.
     */
    private void addLineCoverage(int column, int row, int lineMask) {
        int subSquare = (row >> 2) * 4 + (column >> 2);
        boolean hasInteriorRun = hasInteriorRun(lineMask);
        if ((lineMask & NEGATIVE_END_BIT) != 0 || hasInteriorRun) {
            negativeFaceSubSquarePixels[subSquare]++;
        }
        if ((lineMask & POSITIVE_END_BIT) != 0 || hasInteriorRun) {
            positiveFaceSubSquarePixels[subSquare]++;
        }
    }

    /**
     * Whether the line of 16 voxels has a run of filled voxels that touches neither of its ends.
     */
    private static boolean hasInteriorRun(int lineMask) {
        int runStart = -1;
        for (int index = 0; index <= PIXEL_COUNT; index++) {
            boolean isFilled = index < PIXEL_COUNT && (lineMask & (1 << index)) != 0;
            if (isFilled) {
                if (runStart < 0) {
                    runStart = index;
                }
            } else if (runStart >= 0) {
                if (runStart > 0 && index < PIXEL_COUNT) {
                    return true;
                }
                runStart = -1;
            }
        }
        return false;
    }

    /**
     * The mask of the sub-squares whose pixel count reaches CLOSED_SUB_SQUARE_PIXEL_COUNT.
     */
    private static int subSquareMask(int[] subSquarePixels) {
        int mask = 0;
        for (int subSquare = 0; subSquare < SUB_SQUARE_COUNT; subSquare++) {
            if (subSquarePixels[subSquare] >= CLOSED_SUB_SQUARE_PIXEL_COUNT) {
                mask |= 1 << subSquare;
            }
        }
        return mask;
    }

    /**
     * The id of the mask set with the computed face masks and without the leaves flag, which adds a table entry and
     * allocates when the masks are new.
     */
    private int internComputedFaceMasks() {
        int down = computedFaceMasks[DOWN_FACE];
        int up = computedFaceMasks[UP_FACE];
        int north = computedFaceMasks[NORTH_FACE];
        int south = computedFaceMasks[SOUTH_FACE];
        int west = computedFaceMasks[WEST_FACE];
        int east = computedFaceMasks[EAST_FACE];
        if ((down | up | north | south | west | east) == 0) {
            return EMPTY_MASK_SET;
        }
        if (down == FULL_MASK && up == FULL_MASK && north == FULL_MASK && south == FULL_MASK
                && west == FULL_MASK && east == FULL_MASK) {
            return FULL_MASK_SET;
        }
        MaskSetKey key = new MaskSetKey(down, up, north, south, west, east);
        int existingMaskSet = maskSetIds.getInt(key);
        if (existingMaskSet >= 0) {
            return existingMaskSet;
        }
        if (maskSetTableSize >= MAXIMUM_MASK_SETS) {
            warnAboutMaskSetLimit();
            return FULL_MASK_SET;
        }
        int maskSet = addMaskSet(down, up, north, south, west, east, false);
        maskSetIds.put(key, maskSet);
        return maskSet;
    }

    /**
     * Adds a mask set to the end of the table and returns its id.
     */
    private int addMaskSet(int down, int up, int north, int south, int west, int east, boolean leaves) {
        ensureMaskSetCapacity(maskSetTableSize + 1);
        int maskSet = maskSetTableSize;
        int offset = maskSet * FACE_COUNT;
        maskSetFaceMasks[offset + DOWN_FACE] = (char) down;
        maskSetFaceMasks[offset + UP_FACE] = (char) up;
        maskSetFaceMasks[offset + NORTH_FACE] = (char) north;
        maskSetFaceMasks[offset + SOUTH_FACE] = (char) south;
        maskSetFaceMasks[offset + WEST_FACE] = (char) west;
        maskSetFaceMasks[offset + EAST_FACE] = (char) east;
        maskSetLeaves[maskSet] = leaves;
        maskSetClosesAllFaces[maskSet] = down == FULL_MASK && up == FULL_MASK && north == FULL_MASK
                && south == FULL_MASK && west == FULL_MASK && east == FULL_MASK;
        maskSetTableSize = maskSet + 1;
        return maskSet;
    }

    /**
     * Grows the table arrays, doubling their capacity, until they hold the given number of mask sets.
     */
    private void ensureMaskSetCapacity(int minimumCapacity) {
        if (maskSetLeaves.length >= minimumCapacity) {
            return;
        }
        int capacity = Math.min(MAXIMUM_MASK_SETS, Math.max(minimumCapacity, maskSetLeaves.length * 2));
        char[] grownFaceMasks = new char[capacity * FACE_COUNT];
        System.arraycopy(maskSetFaceMasks, 0, grownFaceMasks, 0, maskSetFaceMasks.length);
        maskSetFaceMasks = grownFaceMasks;
        boolean[] grownLeaves = new boolean[capacity];
        System.arraycopy(maskSetLeaves, 0, grownLeaves, 0, maskSetLeaves.length);
        maskSetLeaves = grownLeaves;
        boolean[] grownClosesAllFaces = new boolean[capacity];
        System.arraycopy(maskSetClosesAllFaces, 0, grownClosesAllFaces, 0, maskSetClosesAllFaces.length);
        maskSetClosesAllFaces = grownClosesAllFaces;
    }

    /**
     * Logs once that the table is full, after which further mask sets close all their faces.
     */
    private void warnAboutMaskSetLimit() {
        if (!warnedAboutMaskSetLimit) {
            warnedAboutMaskSetLimit = true;
            LOGGER.warn("The snow faces table is full at {} mask sets, so further block states close all their faces "
                    + "until the game is restarted.", MAXIMUM_MASK_SETS);
        }
    }

    /**
     * Grows the per-state cache to hold the given number of state ids, and fills the new entries with -1.
     */
    private void growStateCache(int minimumLength) {
        int length = Math.max(minimumLength, stateMaskSets.length * 2);
        int[] grownStateMaskSets = new int[length];
        System.arraycopy(stateMaskSets, 0, grownStateMaskSets, 0, stateMaskSets.length);
        Arrays.fill(grownStateMaskSets, stateMaskSets.length, length, -1);
        stateMaskSets = grownStateMaskSets;
    }

    /**
     * The six masks of a mask set that is neither empty nor full, used as the key of the table's lookup map.
     */
    private static final class MaskSetKey {
        private final int down;
        private final int up;
        private final int north;
        private final int south;
        private final int west;
        private final int east;
        private final int hash;

        private MaskSetKey(int down, int up, int north, int south, int west, int east) {
            this.down = down;
            this.up = up;
            this.north = north;
            this.south = south;
            this.west = west;
            this.east = east;
            int computedHash = down;
            computedHash = computedHash * 31 + up;
            computedHash = computedHash * 31 + north;
            computedHash = computedHash * 31 + south;
            computedHash = computedHash * 31 + west;
            computedHash = computedHash * 31 + east;
            this.hash = computedHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MaskSetKey otherKey)) {
                return false;
            }
            return down == otherKey.down && up == otherKey.up && north == otherKey.north && south == otherKey.south
                    && west == otherKey.west && east == otherKey.east;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
