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
 * The CPU twin of one piece of a column ray, from a start distance to an end distance along a unit direction, through
 * the snow field near the eye and through the far heights of the columns beyond it, which snow_walk.glsl walks from the
 * same inputs on the GPU so that the two can be compared. The whole ray sum of opticalDepth, the column excesses of
 * columnExcesses and the sky depth of skyOpticalDepth are built on that piece walk.
 * The extinction of the fog at a point is the height fog of HeightFog times a snow share in the range 0 to 1, so the
 * optical depth of the segment is the integral of that product. The walk splits the segment into parts on which the
 * share is constant and adds the closed form of HeightFog.opticalDepth at the two ends of each part, which is exact
 * along one ray from the eye and takes no samples, so nothing here samples or crawls.
 * The share follows two rules. Inside the region, the box of half width radius - REGION_MARGIN around the eye, it is
 * the share of the field's cell, blended toward the height share of its column over the last CROSSFADE_WIDTH blocks
 * before the region's edge. Beyond the region the segment is walked in the world-aligned cells of FAR_CELL blocks,
 * whose share is the height share of the column under the cell's middle, which is 1 for a column of unknown height.
 * A cell's share is the field's distance byte through the table of buildTable, or its column's height share when the
 * cell has no field value.
 * While the structures of FogStructure are on, each part is multiplied by their density at its middle.
 * The parts are computed on cell offsets from the eye's block, in doubles, so that no world position passes through a
 * float. Not thread safe, and it allocates nothing after the constructor.
 */
public final class SnowFogWalk {
    /**
     * The number of entries of the share table, which indexes it by the distance byte of a cell.
     */
    public static final int TABLE_SIZE = 256;
    /**
     * The shortest decay length in blocks that buildTable takes, below which the snow share falls faster than the
     * settings allow.
     */
    public static final double MINIMUM_DECAY_LENGTH = 0.25;
    /**
     * The longest decay length in blocks that buildTable takes, at which the share of SnowField.CAP is below 1/255, so
     * the cut of the cap is invisible.
     */
    public static final double MAXIMUM_DECAY_LENGTH = 4.3;
    /**
     * The distance in blocks by which the region stays inside the field radius, which the crossfade spends.
     */
    public static final double REGION_MARGIN = 2.0;
    /**
     * The distance in blocks before the region's edge over which a cell's share blends from the field toward its
     * height share.
     */
    public static final double CROSSFADE_WIDTH = 8.0;
    /**
     * The shortest radius in blocks that setRadius takes.
     */
    public static final double MINIMUM_RADIUS = 12.0;
    /**
     * The longest radius in blocks that setRadius takes.
     */
    public static final double MAXIMUM_RADIUS = 40.0;
    /**
     * The side in blocks of the world-aligned cells that the far part of a piece of the segment is walked in, whose
     * parts stay fixed in the world while the eye moves. It must be a power of two, because the eye's place in the grid
     * is its block masked with FAR_CELL_MASK, and it must match SNOW_WALK_FAR_CELL_BLOCKS in snow_walk.glsl.
     */
    public static final int FAR_CELL_BLOCKS = 2;
    /**
     * The mask that gives a block's place in the far grid, which is the floor modulo by FAR_CELL_BLOCKS.
     */
    public static final int FAR_CELL_MASK = FAR_CELL_BLOCKS - 1;
    /**
     * FAR_CELL_BLOCKS as a double, for the walk's distances.
     */
    public static final double FAR_CELL = FAR_CELL_BLOCKS;
    /**
     * The most cells that the near part of a piece of the segment visits, after which the rest of the piece counts as
     * the open fog at the density 1.
     */
    public static final int PIECE_MAXIMUM_STEPS = 16;
    /**
     * The most cells of FAR_CELL blocks that the far part of a piece of the segment visits, after which the rest of the
     * piece counts as the open fog at the density 1. It matches SNOW_WALK_PIECE_FAR_MAXIMUM_STEPS in snow_walk.glsl.
     */
    public static final int PIECE_FAR_MAXIMUM_STEPS = 32;

    /**
     * The radius in blocks of the region before setRadius, which is the default of fogFieldRadius.
     */
    private static final double DEFAULT_RADIUS = 32.0;
    /**
     * The most cells that one part of a whole ray visits, which is far beyond any scene point of a frame, so that a
     * distance beyond that many cells stops at the open fog instead of running on.
     */
    private static final int WHOLE_MAXIMUM_STEPS = 1 << 16;
    /**
     * A boundary past the end of the segment, for an axis the segment never leaves its cell in.
     */
    private static final double NO_BOUNDARY = Double.POSITIVE_INFINITY;

    private byte[] distances;
    private int fieldMask;
    private int fieldShift;
    private boolean hasShownBox;
    private int shownMinX;
    private int shownMinY;
    private int shownMinZ;
    private int shownMaxX;
    private int shownMaxY;
    private int shownMaxZ;
    private short[] heights;
    private float[] table;
    private int eyeBlockX;
    private int eyeBlockY;
    private int eyeBlockZ;
    private double eyeFractionX;
    private double eyeFractionY;
    private double eyeFractionZ;
    private double eyeExtinction;
    private double heightScale;
    private double halfWidth = DEFAULT_RADIUS - REGION_MARGIN;
    private double fieldFade = 1.0;
    private FogStructure structure;
    private float[] cavityHeights;
    private int cavityMinX;
    private int cavityMinZ;
    private double cavityFloor = 1.0;
    private double cavityDecayLength = MINIMUM_DECAY_LENGTH;
    private int lastNearSteps;
    private boolean lastNearCapped;
    private int lastFarSteps;
    private boolean lastFarCapped;

    /**
     * Creates a walk that has no field, no shown box and no heights, whose table is the one of
     * buildTable(MINIMUM_DECAY_LENGTH), with the eye at the block (0, 0, 0) and no fog. Allocates the share table.
     */
    public SnowFogWalk() {
        table = new float[TABLE_SIZE];
        buildTable(MINIMUM_DECAY_LENGTH, table);
    }

    /**
     * Fills the share table of a decay length: the share of a cell whose distance byte is b is exp(-b / (8 * L)),
     * because a byte is a distance in eighths of a block. Entries above SnowField.CAP are 0, the solver never writes
     * them, and SnowField.SHELTERED is 0 as well, while SnowField.UNREAD is negative, the flag for a cell without a
     * field value. Allocates nothing.
     * @param decayLength the distance in blocks over which the share falls by a factor of e, clamped to
     *        MINIMUM_DECAY_LENGTH to MAXIMUM_DECAY_LENGTH, where NaN counts as MINIMUM_DECAY_LENGTH
     * @param table the array of TABLE_SIZE floats to fill, which the caller keeps
     */
    public static void buildTable(double decayLength, float[] table) {
        double clampedLength = decayLength;
        if (!(clampedLength >= MINIMUM_DECAY_LENGTH)) {
            clampedLength = MINIMUM_DECAY_LENGTH;
        } else if (clampedLength > MAXIMUM_DECAY_LENGTH) {
            clampedLength = MAXIMUM_DECAY_LENGTH;
        }
        double bytesPerBlock = 8.0 * clampedLength;
        for (int entry = 0; entry <= SnowField.CAP; entry++) {
            table[entry] = (float) Math.exp(-entry / bytesPerBlock);
        }
        for (int entry = SnowField.CAP + 1; entry < SnowField.SHELTERED; entry++) {
            table[entry] = 0.0F;
        }
        table[SnowField.SHELTERED] = 0.0F;
        table[SnowField.UNREAD] = -1.0F;
    }

    /**
     * Keeps the field that this walk reads, which it does not copy.
     * @param distances the distance bytes in the slot layout of SnowField
     * @param fieldSize the number of cells per axis of the field, a power of two
     * @throws IllegalArgumentException when the size is not a power of two or the array is not a cube of that size
     */
    public void setField(byte[] distances, int fieldSize) {
        if (!(fieldSize > 0) || (fieldSize & (fieldSize - 1)) != 0) {
            throw new IllegalArgumentException("The field has " + fieldSize + " cells per axis, which is not a power "
                    + "of two");
        }
        long cellCount = (long) fieldSize * fieldSize * fieldSize;
        if (distances.length != cellCount) {
            throw new IllegalArgumentException("The field of " + fieldSize + " cells per axis has " + distances.length
                    + " bytes instead of " + cellCount);
        }
        this.distances = distances;
        this.fieldMask = fieldSize - 1;
        this.fieldShift = Integer.numberOfTrailingZeros(fieldSize);
    }

    /**
     * Keeps the shown box of the field, the box of world positions whose field values the renderer may show. Every
     * cell outside it counts as a cell without a field value.
     * @param hasShownBox whether the six corners hold a box, which they do not before the field solved a cell
     * @param minX the world x of the low corner, inclusive
     * @param minY the world y of the low corner, inclusive
     * @param minZ the world z of the low corner, inclusive
     * @param maxX the world x of the high corner, inclusive
     * @param maxY the world y of the high corner, inclusive
     * @param maxZ the world z of the high corner, inclusive
     */
    public void setShownBox(boolean hasShownBox, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.hasShownBox = hasShownBox;
        this.shownMinX = minX;
        this.shownMinY = minY;
        this.shownMinZ = minZ;
        this.shownMaxX = maxX;
        this.shownMaxY = maxY;
        this.shownMaxZ = maxZ;
    }

    /**
     * Keeps the motion heights of the columns of the window of FarHeights, which this walk does not copy.
     * @param heights the heights in the layout of FarHeights.texelOf, FarHeights.SIZE squared of them
     * @throws IllegalArgumentException when the array holds another number of heights
     */
    public void setHeights(short[] heights) {
        if (heights.length != FarHeights.SIZE * FarHeights.SIZE) {
            throw new IllegalArgumentException("The heights have " + heights.length + " entries instead of "
                    + (FarHeights.SIZE * FarHeights.SIZE));
        }
        this.heights = heights;
    }

    /**
     * Keeps the share table that this walk reads, which it does not copy.
     * @param table the array of TABLE_SIZE floats that buildTable filled
     * @throws IllegalArgumentException when the array holds another number of entries
     */
    public void setTable(float[] table) {
        if (table.length != TABLE_SIZE) {
            throw new IllegalArgumentException("The share table has " + table.length + " entries instead of "
                    + TABLE_SIZE);
        }
        this.table = table;
    }

    /**
     * Sets the eye, which is the block of the eye plus its fraction inside that block. The walk works on cell offsets
     * from that block, so no world position passes through a float.
     * @param blockX the world x of the block the eye is in
     * @param blockY the world y of the block the eye is in
     * @param blockZ the world z of the block the eye is in
     * @param fractionX the place of the eye in its block in x, from 0 to 1
     * @param fractionY the place of the eye in its block in y, from 0 to 1
     * @param fractionZ the place of the eye in its block in z, from 0 to 1
     */
    public void setEye(int blockX, int blockY, int blockZ, double fractionX, double fractionY, double fractionZ) {
        this.eyeBlockX = blockX;
        this.eyeBlockY = blockY;
        this.eyeBlockZ = blockZ;
        this.eyeFractionX = fractionX;
        this.eyeFractionY = fractionY;
        this.eyeFractionZ = fractionZ;
    }

    /**
     * Sets the fog that the walk integrates: the extinction per block at the eye's height and the height scale.
     * @param eyeExtinction the extinction per block at the eye's height, in optical depths per block, where 0 or less
     *        turns the whole walk off
     * @param heightScale the height scale of the fog in blocks, positive, where 0 or less means fog that is uniform in
     *        height
     */
    public void setFog(double eyeExtinction, double heightScale) {
        this.eyeExtinction = eyeExtinction;
        this.heightScale = heightScale;
    }

    /**
     * Sets the radius of the region around the eye in which the walk follows the field. The region itself is a box of
     * half width radius - REGION_MARGIN around the eye's block.
     * @param radius the radius in blocks, clamped to MINIMUM_RADIUS to MAXIMUM_RADIUS, where NaN counts as
     *        MINIMUM_RADIUS
     */
    public void setRadius(double radius) {
        double clampedRadius = radius;
        if (!(clampedRadius >= MINIMUM_RADIUS)) {
            clampedRadius = MINIMUM_RADIUS;
        } else if (clampedRadius > MAXIMUM_RADIUS) {
            clampedRadius = MAXIMUM_RADIUS;
        }
        this.halfWidth = clampedRadius - REGION_MARGIN;
    }

    /**
     * Sets how much of the field's share the walk takes, which rises from 0 to 1 over the half second after a fill
     * filled the shown box.
     * @param fade the field fade, clamped to 0 to 1, where NaN counts as 0
     */
    public void setFieldFade(double fade) {
        double clampedFade = fade;
        if (!(clampedFade > 0.0)) {
            clampedFade = 0.0;
        } else if (clampedFade > 1.0) {
            clampedFade = 1.0;
        }
        this.fieldFade = clampedFade;
    }

    /**
     * The optical depth of the fog along the segment from the eye to the scene point at the given offset, in optical
     * depths for the fog of setFog, at most HeightFog.MAXIMUM_OPTICAL_DEPTH.
     * The segment runs from the parameter 0 at the eye to 1 at the scene point, and its optical depth from the eye up
     * to the parameter s is HeightFog.opticalDepth(eyeExtinction, s * |offset|, s * offsetY, heightScale). The region
     * part is walked cell by cell and the far part through the far cells of FAR_CELL blocks, and each part adds its
     * share times the density of the structures at its middle times the closed form of the optical depth between its
     * ends.
     * The four last getters record what this call walked. Allocates nothing.
     * @param offsetX the offset of the scene point from the eye in x, in blocks
     * @param offsetY the offset of the scene point from the eye in y, in blocks
     * @param offsetZ the offset of the scene point from the eye in z, in blocks
     * @return the optical depth along the segment, which is 0 when the offset has a non-finite component, when its
     *         length is 0, or when the extinction at the eye is not positive
     */
    public double opticalDepth(double offsetX, double offsetY, double offsetZ) {
        resetLastWalk();
        if (!(eyeExtinction > 0.0) || !Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                || !Double.isFinite(offsetZ)) {
            return 0.0;
        }
        double segmentLength = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        if (!(segmentLength > 0.0)) {
            return 0.0;
        }
        double unitX = offsetX / segmentLength;
        double unitY = offsetY / segmentLength;
        double unitZ = offsetZ / segmentLength;
        double total = walkPiece(unitX, unitY, unitZ, 0.0, segmentLength, WHOLE_MAXIMUM_STEPS, WHOLE_MAXIMUM_STEPS);
        if (!(total > 0.0)) {
            return 0.0;
        }
        return Math.min(HeightFog.MAXIMUM_OPTICAL_DEPTH, total);
    }

    /**
     * The optical depth of the snow fog from the eye to infinity along a direction, which is the walk to walkDistance
     * and the tail past it. The walk follows the ray of the unit direction out to the point at walkDistance, with the
     * cells and the far cells of a scene point there, and the tail is the height fog from that point to infinity at the
     * snow share 1 and the density 1, which the structures leave past their range. The sum is clamped to
     * HeightFog.MAXIMUM_OPTICAL_DEPTH. The four last getters record what that walk walked, and a direction or a distance
     * that the guards reject leaves them as a zero-length opticalDepth call does. Allocates nothing.
     * @param directionX the x of the direction of the ray, which need not be a unit vector
     * @param directionY the y of the direction of the ray, whose sign decides whether the ray leaves the fog
     * @param directionZ the z of the direction of the ray
     * @param walkDistance the distance in blocks from the eye at which the tail begins, the range of the structures
     * @return the optical depth to infinity, at most HeightFog.MAXIMUM_OPTICAL_DEPTH, which is 0 for a direction of
     *         zero length and for a direction, a distance or an eye extinction that is not positive or not finite
     */
    public double skyOpticalDepth(double directionX, double directionY, double directionZ, double walkDistance) {
        double directionLength = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        if (!(directionLength > 0.0) || !Double.isFinite(directionLength) || !(walkDistance > 0.0)
                || !Double.isFinite(walkDistance)) {
            return opticalDepth(0.0, 0.0, 0.0);
        }
        double unitX = directionX / directionLength;
        double unitY = directionY / directionLength;
        double unitZ = directionZ / directionLength;
        double walkedDepth = opticalDepth(unitX * walkDistance, unitY * walkDistance, unitZ * walkDistance);
        double tailDepth = HeightFog.skyTailOpticalDepth(eyeExtinction, walkDistance, unitY, heightScale);
        double totalDepth = walkedDepth + tailDepth;
        if (!(totalDepth > 0.0)) {
            return 0.0;
        }
        return Math.min(HeightFog.MAXIMUM_OPTICAL_DEPTH, totalDepth);
    }

    /**
     * The optical depth of the piece of the ray (given by any non-zero direction) between two distances along it, in
     * optical depths, from the near part's cell walk inside the region and the far part's cells of FAR_CELL blocks
     * beyond it, capped at PIECE_MAXIMUM_STEPS and PIECE_FAR_MAXIMUM_STEPS cells with the rest of the piece at share 1
     * and density 1. The result is clamped to HeightFog.MAXIMUM_OPTICAL_DEPTH.
     * Guards: a direction or distance that is not finite, a direction of length 0, a start that is not below the end, or
     * an extinction that is not positive give 0; a start below 0 counts as 0. The four last getters record the parts
     * this call walked, and a rejected call leaves them at 0. Allocates nothing.
     * @param directionX the x of the direction of the ray, which need not be a unit vector
     * @param directionY the y of the direction of the ray, whose sign decides whether the piece rises or falls
     * @param directionZ the z of the direction of the ray
     * @param startDistance the distance in blocks from the eye at which the piece begins, where a negative one counts as
     *        0
     * @param endDistance the distance in blocks from the eye at which the piece ends, above the start
     * @return the optical depth of the piece, from 0 to HeightFog.MAXIMUM_OPTICAL_DEPTH
     */
    public double pieceOpticalDepth(double directionX, double directionY, double directionZ, double startDistance,
            double endDistance) {
        resetLastWalk();
        if (!(eyeExtinction > 0.0) || !Double.isFinite(startDistance) || !Double.isFinite(endDistance)
                || !(startDistance < endDistance)) {
            return 0.0;
        }
        double directionLength = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        if (!(directionLength > 0.0) || !Double.isFinite(directionLength)) {
            return 0.0;
        }
        double unitX = directionX / directionLength;
        double unitY = directionY / directionLength;
        double unitZ = directionZ / directionLength;
        double pieceDepth = walkPiece(unitX, unitY, unitZ, Math.max(startDistance, 0.0), endDistance,
                PIECE_MAXIMUM_STEPS, PIECE_FAR_MAXIMUM_STEPS);
        if (!(pieceDepth > 0.0)) {
            return 0.0;
        }
        return Math.min(HeightFog.MAXIMUM_OPTICAL_DEPTH, pieceDepth);
    }

    /**
     * The excesses that the integrate pass mirrors: walks the pieces between consecutive distances (the first from 0 to
     * boundaryDistances[0]) along the direction and fills excesses[k] with the running sum of the pieces up to
     * boundaryDistances[k] minus HeightFog.opticalDepth(eyeExtinction, boundaryDistances[k],
     * boundaryDistances[k] * unitDirectionY, heightScale), for k from 0 to boundaryCount - 1. The distance entries must
     * be positive, strictly increasing and finite. A direction or an extinction the guards of pieceOpticalDepth reject
     * fills every excess with 0. The four last getters record the last piece walked. Allocates nothing.
     * @param directionX the x of the direction of the ray, which need not be a unit vector
     * @param directionY the y of the direction of the ray, whose sign decides whether the pieces rise or fall
     * @param directionZ the z of the direction of the ray
     * @param boundaryDistances the distances in blocks of the boundaries 1 to boundaryCount, which hold at least
     *        boundaryCount entries
     * @param boundaryCount the boundaries to fill, from 0 up
     * @param excesses the excesses of those boundaries in optical depths, which hold at least boundaryCount entries
     * @throws IllegalArgumentException when the boundary count is negative or an array holds fewer entries
     */
    public void columnExcesses(double directionX, double directionY, double directionZ, double[] boundaryDistances,
            int boundaryCount, double[] excesses) {
        if (boundaryCount < 0 || boundaryDistances.length < boundaryCount || excesses.length < boundaryCount) {
            throw new IllegalArgumentException("The column walk of " + boundaryCount + " boundaries needs "
                    + boundaryCount + " distances and excesses but has " + boundaryDistances.length + " and "
                    + excesses.length);
        }
        resetLastWalk();
        double directionLength = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        if (!(eyeExtinction > 0.0) || !(directionLength > 0.0) || !Double.isFinite(directionLength)) {
            for (int boundary = 0; boundary < boundaryCount; boundary++) {
                excesses[boundary] = 0.0;
            }
            return;
        }
        double unitX = directionX / directionLength;
        double unitY = directionY / directionLength;
        double unitZ = directionZ / directionLength;
        double runningDepth = 0.0;
        double previousDistance = 0.0;
        for (int boundary = 0; boundary < boundaryCount; boundary++) {
            double boundaryDistance = boundaryDistances[boundary];
            runningDepth += walkPiece(unitX, unitY, unitZ, previousDistance, boundaryDistance, PIECE_MAXIMUM_STEPS,
                    PIECE_FAR_MAXIMUM_STEPS);
            previousDistance = boundaryDistance;
            excesses[boundary] = runningDepth - tauAt(boundaryDistance, unitY);
        }
    }

    /**
     * The share of a cell, which is its distance byte through the table of buildTable, or its height share when the
     * cell has no field value, blended between the two by the crossfade near the region's edge and by the field fade.
     * The blend weight depends on the eye's place inside its block, so the share does not pulse when the eye crosses a
     * block boundary. Allocates nothing.
     * @param cellX the x of the cell counted from the eye's block
     * @param cellY the y of the cell counted from the eye's block
     * @param cellZ the z of the cell counted from the eye's block
     * @return the share, from 0 for a cell that no snow reaches to 1 for a cell in the open
     */
    public double cellShare(int cellX, int cellY, int cellZ) {
        double heightShare = heightShare(cellX, cellY, cellZ);
        byte[] currentDistances = distances;
        if (!hasShownBox || currentDistances == null) {
            return heightShare;
        }
        int worldX = eyeBlockX + cellX;
        int worldY = eyeBlockY + cellY;
        int worldZ = eyeBlockZ + cellZ;
        if (worldX < shownMinX || worldX > shownMaxX || worldY < shownMinY || worldY > shownMaxY
                || worldZ < shownMinZ || worldZ > shownMaxZ) {
            return heightShare;
        }
        int slot = (worldX & fieldMask) | ((worldY & fieldMask) << fieldShift)
                | ((worldZ & fieldMask) << (2 * fieldShift));
        float fieldShare = table[currentDistances[slot] & 0xFF];
        // A negative table entry is the flag for a cell the solver has not read, which has no field value yet.
        if (fieldShare < 0.0F) {
            return heightShare;
        }
        double distanceX = Math.abs(cellX + 0.5 - eyeFractionX);
        double distanceY = Math.abs(cellY + 0.5 - eyeFractionY);
        double distanceZ = Math.abs(cellZ + 0.5 - eyeFractionZ);
        double chebyshev = Math.max(distanceX, Math.max(distanceY, distanceZ));
        double weight = (halfWidth - chebyshev) / CROSSFADE_WIDTH;
        if (!(weight > 0.0)) {
            weight = 0.0;
        } else if (weight > 1.0) {
            weight = 1.0;
        }
        weight *= fieldFade;
        return heightShare + (fieldShare - heightShare) * weight;
    }

    /**
     * The height share of a cell: 1 at or above the motion height of its column, and 0 below it. A column whose height
     * is FarHeights.UNKNOWN stands below every cell, so it counts as open. Allocates nothing.
     * @param cellX the x of the cell counted from the eye's block
     * @param cellY the y of the cell counted from the eye's block
     * @param cellZ the z of the cell counted from the eye's block
     * @return the height share, exactly 0 or 1
     */
    public double heightShare(int cellX, int cellY, int cellZ) {
        return eyeBlockY + cellY >= heightAt(cellX, cellZ) ? 1.0 : 0.0;
    }

    /**
     * Keeps the fog structure that the walk reads, which it does not copy. A null structure turns the structures off,
     * and then every depth stays the depth of the fog without them.
     * @param structure the structure that shapes the fog, or null
     */
    public void setStructure(FogStructure structure) {
        this.structure = structure;
    }

    /**
     * Keeps the cavity enclosure map that the fog walk reads, which it does not copy, together with its world origin,
     * effective floor share and vertical decay length. Leaves cellShare and heightShare unchanged.
     */
    public void setCavity(float[] cavityHeights, int minX, int minZ, double effectiveFloor, double decayLength) {
        if (cavityHeights.length != FogCavity.MAP_SIZE * FogCavity.MAP_SIZE) {
            throw new IllegalArgumentException("The cavity map has " + cavityHeights.length + " entries instead of "
                    + (FogCavity.MAP_SIZE * FogCavity.MAP_SIZE));
        }
        this.cavityHeights = cavityHeights;
        this.cavityMinX = minX;
        this.cavityMinZ = minZ;
        if (!(effectiveFloor >= 0.0)) {
            this.cavityFloor = 0.0;
        } else if (effectiveFloor > 1.0) {
            this.cavityFloor = 1.0;
        } else {
            this.cavityFloor = effectiveFloor;
        }
        if (!(decayLength >= MINIMUM_DECAY_LENGTH)) {
            this.cavityDecayLength = MINIMUM_DECAY_LENGTH;
        } else if (decayLength > MAXIMUM_DECAY_LENGTH) {
            this.cavityDecayLength = MAXIMUM_DECAY_LENGTH;
        } else {
            this.cavityDecayLength = decayLength;
        }
    }

    /**
     * Turns cavity thinning off for subsequent walks without allocating.
     */
    public void clearCavity() {
        this.cavityHeights = null;
        this.cavityFloor = 1.0;
    }

    /**
     * The fog density multiplier in [cavityFloor, 1.0] at world position (worldX, worldY, worldZ), matching
     * snowWalkCavityFactor in snow_walk.glsl. Allocates nothing.
     */
    public double cavityFactor(int worldX, double worldY, int worldZ) {
        float[] currentCavity = cavityHeights;
        if (currentCavity == null || !(cavityFloor < 1.0)) {
            return 1.0;
        }
        int localX = worldX - cavityMinX;
        int localZ = worldZ - cavityMinZ;
        if (localX < 0 || localX >= FogCavity.MAP_SIZE || localZ < 0 || localZ >= FogCavity.MAP_SIZE) {
            return 1.0;
        }
        return FogCavity.cavityFactor(worldY, currentCavity[localZ * FogCavity.MAP_SIZE + localX],
                cavityDecayLength, cavityFloor);
    }

    /**
     * The cells that the near part of the last walk visited, which is 0 when the walk began past the region.
     * @return the cells walked, from 0 up, at most PIECE_MAXIMUM_STEPS for a piece and WHOLE_MAXIMUM_STEPS for the
     *         whole ray, whose region crosses at most three cells per block of the radius
     */
    public int lastNearSteps() {
        return lastNearSteps;
    }

    /**
     * Whether the near part of the last piece walk ran out of its PIECE_MAXIMUM_STEPS cells, after which the rest of
     * the piece counts as the open fog at the density 1.
     * @return true when the near part was capped
     */
    public boolean lastNearCapped() {
        return lastNearCapped;
    }

    /**
     * The cells that the far part of the last walk visited, which is 0 when the walk ended inside the region.
     * @return the cells walked, from 0 up, at most PIECE_FAR_MAXIMUM_STEPS for a piece and however many far cells of
     *         FAR_CELL blocks the whole ray crosses
     */
    public int lastFarSteps() {
        return lastFarSteps;
    }

    /**
     * Whether the far part of the last piece walk ran out of its PIECE_FAR_MAXIMUM_STEPS cells, after which the rest of
     * the piece counts as the open fog at the density 1.
     * @return true when the far part was capped
     */
    public boolean lastFarCapped() {
        return lastFarCapped;
    }

    /**
     * Clears the four last values of the walks, which every walk and every rejected call starts with.
     */
    private void resetLastWalk() {
        lastNearSteps = 0;
        lastNearCapped = false;
        lastFarSteps = 0;
        lastFarCapped = false;
    }

    /**
     * The optical depth of one piece of the ray of a unit direction from the start to the end distance, which is the
     * near cells inside the region plus the far cells of FAR_CELL blocks beyond it. The near part visits at most
     * nearMaximumSteps cells and the far part farMaximumSteps, and the rest of a part that runs out of them counts as
     * the open fog at the share 1 and the density 1. Records the four last values of the piece. Allocates nothing.
     */
    private double walkPiece(double unitX, double unitY, double unitZ, double startDistance, double endDistance,
            int nearMaximumSteps, int farMaximumSteps) {
        resetLastWalk();
        double largestComponent = Math.max(Math.abs(unitX), Math.max(Math.abs(unitY), Math.abs(unitZ)));
        if (!(largestComponent > 0.0)) {
            return 0.0;
        }
        double exitDistance = halfWidth / largestComponent;
        double depth = 0.0;
        double nearStart = Math.max(startDistance, 0.0);
        double nearEnd = Math.min(endDistance, exitDistance);
        if (nearEnd > nearStart) {
            depth += walkNearCells(unitX, unitY, unitZ, nearStart, nearEnd, nearMaximumSteps);
        }
        double farStart = Math.max(startDistance, exitDistance);
        if (endDistance > farStart) {
            depth += walkFarCells(unitX, unitY, unitZ, farStart, endDistance, farMaximumSteps);
        }
        return depth;
    }

    /**
     * The optical depth of the stretch of the ray inside the region, cell by cell from the cell that holds the start
     * distance. Each cell adds its share times the density of the structures at the middle of the part times the closed
     * form between the two ends of the part, and the rest after maximumSteps cells counts as the open fog at the share
     * 1 and the density 1. Records the near cells and their cap. Allocates nothing.
     */
    private double walkNearCells(double unitX, double unitY, double unitZ, double startDistance, double endDistance,
            int maximumSteps) {
        double startX = eyeFractionX + unitX * startDistance;
        double startY = eyeFractionY + unitY * startDistance;
        double startZ = eyeFractionZ + unitZ * startDistance;
        int cellX = (int) Math.floor(startX);
        int cellY = (int) Math.floor(startY);
        int cellZ = (int) Math.floor(startZ);
        int stepX = 0;
        int stepY = 0;
        int stepZ = 0;
        double nextBoundaryX = NO_BOUNDARY;
        double nextBoundaryY = NO_BOUNDARY;
        double nextBoundaryZ = NO_BOUNDARY;
        double boundaryStepX = NO_BOUNDARY;
        double boundaryStepY = NO_BOUNDARY;
        double boundaryStepZ = NO_BOUNDARY;
        if (unitX > 0.0) {
            stepX = 1;
            nextBoundaryX = startDistance + (cellX + 1 - startX) / unitX;
            boundaryStepX = 1.0 / unitX;
        } else if (unitX < 0.0) {
            stepX = -1;
            nextBoundaryX = startDistance + (cellX - startX) / unitX;
            boundaryStepX = 1.0 / -unitX;
        }
        if (unitY > 0.0) {
            stepY = 1;
            nextBoundaryY = startDistance + (cellY + 1 - startY) / unitY;
            boundaryStepY = 1.0 / unitY;
        } else if (unitY < 0.0) {
            stepY = -1;
            nextBoundaryY = startDistance + (cellY - startY) / unitY;
            boundaryStepY = 1.0 / -unitY;
        }
        if (unitZ > 0.0) {
            stepZ = 1;
            nextBoundaryZ = startDistance + (cellZ + 1 - startZ) / unitZ;
            boundaryStepZ = 1.0 / unitZ;
        } else if (unitZ < 0.0) {
            stepZ = -1;
            nextBoundaryZ = startDistance + (cellZ - startZ) / unitZ;
            boundaryStepZ = 1.0 / -unitZ;
        }
        FogStructure currentStructure = structure;
        boolean structureOn = currentStructure != null && currentStructure.isOn();
        boolean cavityOn = cavityHeights != null && cavityFloor < 1.0;
        double depth = 0.0;
        double enter = startDistance;
        double enterDepth = tauAt(enter, unitY);
        boolean reachedEnd = false;
        int cellsWalked = 0;
        for (int cellStep = 0; cellStep < maximumSteps; cellStep++) {
            cellsWalked = cellStep + 1;
            double exit = Math.min(Math.min(nextBoundaryX, nextBoundaryY), Math.min(nextBoundaryZ, endDistance));
            double exitDepth = tauAt(exit, unitY);
            double share = cellShare(cellX, cellY, cellZ);
            double partDepth = Math.max(0.0, exitDepth - enterDepth);
            if (share > 0.0) {
                if (cavityOn) {
                    partDepth *= cavityFactor(eyeBlockX + cellX, eyeBlockY + cellY + 0.5, eyeBlockZ + cellZ);
                }
                if (structureOn) {
                    double middle = (enter + exit) * 0.5;
                    partDepth *= currentStructure.densityAt(middle * unitX, middle * unitY, middle * unitZ);
                }
            }
            depth += share * partDepth;
            enter = exit;
            enterDepth = exitDepth;
            if (!(exit < endDistance)) {
                reachedEnd = true;
                break;
            }
            double smallestBoundary = Math.min(nextBoundaryX, Math.min(nextBoundaryY, nextBoundaryZ));
            // Every axis whose boundary is the next one steps, so a segment through a cell edge enters both neighbors.
            if (nextBoundaryX <= smallestBoundary) {
                cellX += stepX;
                nextBoundaryX += boundaryStepX;
            }
            if (nextBoundaryY <= smallestBoundary) {
                cellY += stepY;
                nextBoundaryY += boundaryStepY;
            }
            if (nextBoundaryZ <= smallestBoundary) {
                cellZ += stepZ;
                nextBoundaryZ += boundaryStepZ;
            }
        }
        if (!reachedEnd) {
            depth += Math.max(0.0, tauAt(endDistance, unitY) - enterDepth);
        }
        lastNearSteps = cellsWalked;
        lastNearCapped = !reachedEnd;
        return depth;
    }

    /**
     * The optical depth of the stretch of the ray beyond the region, cell by cell through the world-aligned grid of
     * FAR_CELL blocks from the cell that holds the start distance. A cell adds the height share of the column under its
     * middle times the density of the structures at the middle of the part times the closed form between the two ends
     * of the part, which is nothing under cover, and the rest after maximumSteps cells counts as the open fog at the
     * share 1 and the density 1. Records the far cells and their cap. Allocates nothing.
     */
    private double walkFarCells(double unitX, double unitY, double unitZ, double startDistance, double endDistance,
            int maximumSteps) {
        double startX = (eyeBlockX & FAR_CELL_MASK) + eyeFractionX + unitX * startDistance;
        double startY = (eyeBlockY & FAR_CELL_MASK) + eyeFractionY + unitY * startDistance;
        double startZ = (eyeBlockZ & FAR_CELL_MASK) + eyeFractionZ + unitZ * startDistance;
        int cellX = (int) Math.floor(startX / FAR_CELL);
        int cellY = (int) Math.floor(startY / FAR_CELL);
        int cellZ = (int) Math.floor(startZ / FAR_CELL);
        int stepX = 0;
        int stepY = 0;
        int stepZ = 0;
        double nextBoundaryX = NO_BOUNDARY;
        double nextBoundaryY = NO_BOUNDARY;
        double nextBoundaryZ = NO_BOUNDARY;
        double boundaryStepX = NO_BOUNDARY;
        double boundaryStepY = NO_BOUNDARY;
        double boundaryStepZ = NO_BOUNDARY;
        if (unitX > 0.0) {
            stepX = 1;
            nextBoundaryX = startDistance + ((cellX + 1) * FAR_CELL - startX) / unitX;
            boundaryStepX = FAR_CELL / unitX;
        } else if (unitX < 0.0) {
            stepX = -1;
            nextBoundaryX = startDistance + (cellX * FAR_CELL - startX) / unitX;
            boundaryStepX = FAR_CELL / -unitX;
        }
        if (unitY > 0.0) {
            stepY = 1;
            nextBoundaryY = startDistance + ((cellY + 1) * FAR_CELL - startY) / unitY;
            boundaryStepY = FAR_CELL / unitY;
        } else if (unitY < 0.0) {
            stepY = -1;
            nextBoundaryY = startDistance + (cellY * FAR_CELL - startY) / unitY;
            boundaryStepY = FAR_CELL / -unitY;
        }
        if (unitZ > 0.0) {
            stepZ = 1;
            nextBoundaryZ = startDistance + ((cellZ + 1) * FAR_CELL - startZ) / unitZ;
            boundaryStepZ = FAR_CELL / unitZ;
        } else if (unitZ < 0.0) {
            stepZ = -1;
            nextBoundaryZ = startDistance + (cellZ * FAR_CELL - startZ) / unitZ;
            boundaryStepZ = FAR_CELL / -unitZ;
        }
        FogStructure currentStructure = structure;
        boolean structureOn = currentStructure != null && currentStructure.isOn();
        boolean cavityOn = cavityHeights != null && cavityFloor < 1.0;
        double depth = 0.0;
        double enter = startDistance;
        double enterDepth = tauAt(enter, unitY);
        boolean reachedEnd = false;
        int cellsWalked = 0;
        for (int cellStep = 0; cellStep < maximumSteps; cellStep++) {
            cellsWalked = cellStep + 1;
            double exit = Math.min(Math.min(nextBoundaryX, nextBoundaryY), Math.min(nextBoundaryZ, endDistance));
            double exitDepth = tauAt(exit, unitY);
            int columnOffsetX = (int) Math.floor((cellX + 0.5) * FAR_CELL) - (eyeBlockX & FAR_CELL_MASK);
            int columnOffsetY = (int) Math.floor((cellY + 0.5) * FAR_CELL) - (eyeBlockY & FAR_CELL_MASK);
            int columnOffsetZ = (int) Math.floor((cellZ + 0.5) * FAR_CELL) - (eyeBlockZ & FAR_CELL_MASK);
            double share = heightShare(columnOffsetX, columnOffsetY, columnOffsetZ);
            if (share > 0.0) {
                double partDepth = Math.max(0.0, exitDepth - enterDepth);
                if (cavityOn) {
                    partDepth *= cavityFactor(eyeBlockX + columnOffsetX, eyeBlockY + columnOffsetY,
                            eyeBlockZ + columnOffsetZ);
                }
                if (structureOn) {
                    double middle = (enter + exit) * 0.5;
                    partDepth *= currentStructure.densityAt(middle * unitX, middle * unitY, middle * unitZ);
                }
                depth += share * partDepth;
            }
            enter = exit;
            enterDepth = exitDepth;
            if (!(exit < endDistance)) {
                reachedEnd = true;
                break;
            }
            double smallestBoundary = Math.min(nextBoundaryX, Math.min(nextBoundaryY, nextBoundaryZ));
            // Every axis whose boundary is the next one steps, so a segment through a cell edge enters both neighbors.
            if (nextBoundaryX <= smallestBoundary) {
                cellX += stepX;
                nextBoundaryX += boundaryStepX;
            }
            if (nextBoundaryY <= smallestBoundary) {
                cellY += stepY;
                nextBoundaryY += boundaryStepY;
            }
            if (nextBoundaryZ <= smallestBoundary) {
                cellZ += stepZ;
                nextBoundaryZ += boundaryStepZ;
            }
        }
        if (!reachedEnd) {
            depth += Math.max(0.0, tauAt(endDistance, unitY) - enterDepth);
        }
        lastFarSteps = cellsWalked;
        lastFarCapped = !reachedEnd;
        return depth;
    }

    /**
     * The motion height of the column of a cell, or FarHeights.UNKNOWN while no heights are set, which stands below
     * every cell.
     */
    private int heightAt(int columnX, int columnZ) {
        short[] currentHeights = heights;
        if (currentHeights == null) {
            return FarHeights.UNKNOWN;
        }
        return currentHeights[FarHeights.texelOf(eyeBlockX + columnX, eyeBlockZ + columnZ)];
    }

    /**
     * The optical depth from the eye up to the given distance along the ray of the unit direction whose upward
     * component is unitY, as HeightFog.opticalDepth gives it.
     */
    private double tauAt(double distance, double unitY) {
        return HeightFog.opticalDepth(eyeExtinction, distance, distance * unitY, heightScale);
    }
}
