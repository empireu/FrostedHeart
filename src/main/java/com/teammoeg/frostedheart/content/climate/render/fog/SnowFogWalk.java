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
 * The walk of the fog along the segment from the eye to a scene point, through the snow field near the eye and through
 * the far heights of the columns beyond it. snow_walk.glsl walks the same segment from the same inputs on the GPU, so
 * that the two can be compared.
 * The extinction of the fog at a point is the height fog of HeightFog times a snow share in the range 0 to 1, so the
 * optical depth of the segment is the integral of that product. The walk splits the segment into parts on which the
 * share is constant and adds the closed form of HeightFog.opticalDepth at the two ends of each part, which is exact
 * along one ray from the eye and takes no samples, so nothing here samples or crawls.
 * The share follows three rules. Inside the region, the box of half width radius - REGION_MARGIN around the eye, it is
 * the share of the field's cell, blended toward the height share of its column over the last CROSSFADE_WIDTH blocks
 * before the region's edge. Beyond the region it is 1, except for the last stretch of the segment when its scene point
 * lies under cover, which the backward walk over the columns under the segment finds and which gets 0.
 * A cell's share is the field's distance byte through the table of buildTable, or its column's height share when the
 * cell has no field value.
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
     * The most cells the cell walk visits. A segment inside a region of half width 38 crosses at most 3 * 39 faces, so
     * the cap only guards against float trouble, and the rest of the segment then counts as open.
     */
    public static final int MAXIMUM_CELL_STEPS = 128;
    /**
     * The most columns the backward walk visits, past which the rest of the segment counts as open.
     */
    public static final int FAR_WALK_COLUMNS = 32;
    /**
     * The horizontal length in blocks below which the segment counts as staying in the column of its scene point,
     * which keeps the backward walk off a division by a horizontal offset of nearly 0.
     */
    public static final double MINIMUM_HORIZONTAL_LENGTH = 1.0e-4;
    /**
     * The shortest radius in blocks that setRadius takes.
     */
    public static final double MINIMUM_RADIUS = 12.0;
    /**
     * The longest radius in blocks that setRadius takes.
     */
    public static final double MAXIMUM_RADIUS = 40.0;
    /**
     * The steps that the far part of the segment is marched in while the noise is on, whose depths telescope so that a
     * uniform noise factor stays exact.
     */
    public static final int FAR_MARCH_STEPS = 24;
    /**
     * The depth in optical depths of the far part that the march covers step by step, beyond which the rest of the far
     * part takes the noise of the last step.
     */
    public static final double FAR_MARCH_DEPTH = HeightFog.VISIBILITY_OPTICAL_DEPTH;

    /**
     * The radius in blocks of the region before setRadius, which is the default of fogFieldRadius.
     */
    private static final double DEFAULT_RADIUS = 32.0;
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
    private byte[] noiseTexels;
    private double noiseStrength;
    private double fineNoiseTileBlocks;
    private double fineNoiseEyeU;
    private double fineNoiseEyeV;
    private double fineNoiseEyeW;
    private double coarseNoiseEyeU;
    private double coarseNoiseEyeV;
    private double coarseNoiseEyeW;
    private int lastCellSteps;
    private int lastColumnSteps;
    private int lastMarchSteps;
    private double lastRegionEnd = 1.0;
    private double lastFarStop = 1.0;
    /**
     * The optical depth from the eye to the end of the region of the last cell walk, which the far part subtracts so
     * that the walk calls tauAt once per part.
     */
    private double regionEndDepth;

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
     * part is walked cell by cell and the far part is walked column by column, and each part adds its share times the
     * closed form of the optical depth between its ends.
     * The four last getters record what this call walked. Allocates nothing.
     * @param offsetX the offset of the scene point from the eye in x, in blocks
     * @param offsetY the offset of the scene point from the eye in y, in blocks
     * @param offsetZ the offset of the scene point from the eye in z, in blocks
     * @return the optical depth along the segment, which is 0 when the offset has a non-finite component, when its
     *         length is 0, or when the extinction at the eye is not positive
     */
    public double opticalDepth(double offsetX, double offsetY, double offsetZ) {
        lastCellSteps = 0;
        lastColumnSteps = 0;
        lastMarchSteps = 0;
        lastRegionEnd = 1.0;
        lastFarStop = 1.0;
        if (!(eyeExtinction > 0.0) || !Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                || !Double.isFinite(offsetZ)) {
            return 0.0;
        }
        double segmentLength = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        if (!(segmentLength > 0.0)) {
            return 0.0;
        }
        double largestOffset = Math.max(Math.abs(offsetX), Math.max(Math.abs(offsetY), Math.abs(offsetZ)));
        double regionEnd = Math.min(1.0, halfWidth / largestOffset);
        lastRegionEnd = regionEnd;
        double regionDepth = walkCells(offsetX, offsetY, offsetZ, segmentLength, regionEnd);
        double farStop = 1.0;
        double farDepth = 0.0;
        if (regionEnd < 1.0) {
            farStop = findFarStop(offsetX, offsetY, offsetZ, regionEnd);
            farDepth = farPartDepth(offsetX, offsetY, offsetZ, segmentLength, farStop);
        }
        lastFarStop = farStop;
        double total = regionDepth + farDepth;
        if (!(total > 0.0)) {
            return 0.0;
        }
        return Math.min(HeightFog.MAXIMUM_OPTICAL_DEPTH, total);
    }

    /**
     * The optical depth of the far part of the segment, from the region's end to where the covered stretch begins, in
     * optical depths. Without the noise it is the closed form of the height fog between the two, and with the noise it
     * is a march of FAR_MARCH_STEPS steps of equal base depth up to FAR_MARCH_DEPTH, whose rest takes the noise of the
     * last step. Allocates nothing.
     */
    private double farPartDepth(double offsetX, double offsetY, double offsetZ, double segmentLength,
            double farStop) {
        double baseDepth = Math.max(0.0, tauAt(farStop, segmentLength, offsetY) - regionEndDepth);
        if (!isNoiseOn()) {
            return baseDepth;
        }
        if (!(baseDepth > 0.0)) {
            return 0.0;
        }
        double marchedDepth = Math.min(baseDepth, FAR_MARCH_DEPTH);
        double stepDepth = marchedDepth / FAR_MARCH_STEPS;
        double depth = 0.0;
        double lastNoise = 0.0;
        for (int step = 0; step < FAR_MARCH_STEPS; step++) {
            double parameter = parameterAtDepth(eyeExtinction, segmentLength, offsetY, heightScale,
                    regionEndDepth + (step + 0.5) * stepDepth);
            lastNoise = noiseAt(parameter * offsetX, parameter * offsetY, parameter * offsetZ);
            depth += stepDepth * (1.0 + noiseStrength * lastNoise);
        }
        depth += (baseDepth - marchedDepth) * (1.0 + noiseStrength * lastNoise);
        lastMarchSteps = FAR_MARCH_STEPS;
        return depth;
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
     * The cells that the cell walk of the last opticalDepth visited, which is the number of parts it summed.
     * @return the cells walked, from 0 to MAXIMUM_CELL_STEPS
     */
    public int lastCellSteps() {
        return lastCellSteps;
    }

    /**
     * The columns that the backward walk of the last opticalDepth visited, which is 0 when its scene point stands in
     * the open.
     * @return the columns walked, from 0 to FAR_WALK_COLUMNS
     */
    public int lastColumnSteps() {
        return lastColumnSteps;
    }

    /**
     * Where the region part of the last opticalDepth ended on its segment.
     * @return the parameter of the segment's end, from 0 to 1
     */
    public double lastRegionEnd() {
        return lastRegionEnd;
    }

    /**
     * Where the covered stretch at the end of the last opticalDepth's segment begins. The stretch from it to the end of
     * the segment takes no fog, and the far part before it takes the fog of the open air.
     * @return the parameter of the segment, from the region's end to 1, which is 1 when there is no far part or the
     *         scene point stands in the open
     */
    public double lastFarStop() {
        return lastFarStop;
    }

    /**
     * Keeps the noise that the walk reads while it integrates, which it does not copy. A null texture or a strength
     * that is not positive turns the noise off, and then every depth is the depth of the fog without it.
     * @param texels the array of SnowFogNoise.SIZE cubed bytes of the texture, or null
     * @param strength how much the noise multiplies the extinction at its peak
     * @param fineTileBlocks the blocks per tile of the fine octave, which is the setting's blocks per texel times
     *        SnowFogNoise.SIZE
     * @param fineEyeU the tile coordinate of the eye of the fine octave along x
     * @param fineEyeV the tile coordinate of the eye of the fine octave along y
     * @param fineEyeW the tile coordinate of the eye of the fine octave along z
     * @param coarseEyeU the tile coordinate of the eye of the coarse octave along x
     * @param coarseEyeV the tile coordinate of the eye of the coarse octave along y
     * @param coarseEyeW the tile coordinate of the eye of the coarse octave along z
     */
    public void setNoise(byte[] texels, double strength, double fineTileBlocks, double fineEyeU, double fineEyeV,
            double fineEyeW, double coarseEyeU, double coarseEyeV, double coarseEyeW) {
        if (texels == null || !(strength > 0.0)) {
            this.noiseTexels = null;
            this.noiseStrength = 0.0;
            return;
        }
        this.noiseTexels = texels;
        this.noiseStrength = strength;
        this.fineNoiseTileBlocks = fineTileBlocks;
        this.fineNoiseEyeU = fineEyeU;
        this.fineNoiseEyeV = fineEyeV;
        this.fineNoiseEyeW = fineEyeW;
        this.coarseNoiseEyeU = coarseEyeU;
        this.coarseNoiseEyeV = coarseEyeV;
        this.coarseNoiseEyeW = coarseEyeW;
    }

    /**
     * The noise at a point given as an offset from the eye's continuous position, which weights the two octaves of the
     * texture. Allocates nothing.
     * @param offsetX the offset of the point from the eye in x, in blocks
     * @param offsetY the offset of the point from the eye in y, in blocks
     * @param offsetZ the offset of the point from the eye in z, in blocks
     * @return the noise from 0 to 1, and 0 while the noise is off
     */
    public double noiseAt(double offsetX, double offsetY, double offsetZ) {
        byte[] texels = noiseTexels;
        if (texels == null || !(noiseStrength > 0.0) || !(fineNoiseTileBlocks > 0.0)) {
            return 0.0;
        }
        double coarseTileBlocks = fineNoiseTileBlocks * SnowFogNoise.COARSE_TILE_FACTOR;
        double fine = SnowFogNoise.sample(texels, fineNoiseEyeU + offsetX / fineNoiseTileBlocks,
                fineNoiseEyeV + offsetY / fineNoiseTileBlocks, fineNoiseEyeW + offsetZ / fineNoiseTileBlocks);
        double coarse = SnowFogNoise.sample(texels, coarseNoiseEyeU + offsetX / coarseTileBlocks,
                coarseNoiseEyeV + offsetY / coarseTileBlocks, coarseNoiseEyeW + offsetZ / coarseTileBlocks);
        return SnowFogNoise.FINE_WEIGHT * fine + SnowFogNoise.COARSE_WEIGHT * coarse;
    }

    /**
     * The parameter of the segment at which the fog of setFog reaches the given optical depth, which inverts
     * HeightFog.opticalDepth along a ray from the eye. Allocates nothing.
     * @param eyeExtinction the extinction per block at the eye's height
     * @param length the length of the segment in blocks
     * @param verticalOffset the height in blocks of the segment end above the eye
     * @param heightScale the height scale of the fog in blocks, where 0 or less means fog that is uniform in height
     * @param depth the optical depth to find the parameter of
     * @return the parameter from 0 to 1, which is 0 when the depth or the extinction is not positive
     */
    public static double parameterAtDepth(double eyeExtinction, double length, double verticalOffset,
            double heightScale, double depth) {
        double rayDepth = eyeExtinction * length;
        if (!(depth > 0.0) || !(rayDepth > 0.0)) {
            return 0.0;
        }
        double parameter;
        if (!(heightScale > 0.0)) {
            parameter = depth / rayDepth;
        } else {
            double scaledOffset = verticalOffset / heightScale;
            double exponent = depth * scaledOffset / rayDepth;
            if (Math.abs(exponent) < HeightFog.SERIES_LIMIT) {
                parameter = (depth / rayDepth) * (1.0 + exponent / 2.0 + exponent * exponent / 3.0);
            } else if (1.0 - exponent > 0.0) {
                parameter = -Math.log(1.0 - exponent) / scaledOffset;
            } else {
                parameter = 1.0;
            }
        }
        if (!(parameter > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, parameter);
    }

    /**
     * The steps that the far march of the last opticalDepth walked, which is 0 while the noise is off or when the
     * segment ends before the far part begins.
     * @return the steps walked, from 0 to FAR_MARCH_STEPS
     */
    public int lastMarchSteps() {
        return lastMarchSteps;
    }

    /**
     * Whether the walk multiplies the fog by the noise, which needs a texture and a positive strength.
     */
    private boolean isNoiseOn() {
        return noiseTexels != null && noiseStrength > 0.0;
    }

    /**
     * Walks the part of the segment inside the region cell by cell, adding the share of each cell times the optical
     * depth between its two ends, and counts the cells. A walk that runs out of steps counts the rest of the region as
     * open, which errs toward fog.
     */
    private double walkCells(double offsetX, double offsetY, double offsetZ, double segmentLength, double regionEnd) {
        int cellX = 0;
        int cellY = 0;
        int cellZ = 0;
        int stepX = 0;
        int stepY = 0;
        int stepZ = 0;
        double nextBoundaryX = NO_BOUNDARY;
        double nextBoundaryY = NO_BOUNDARY;
        double nextBoundaryZ = NO_BOUNDARY;
        double boundaryStepX = NO_BOUNDARY;
        double boundaryStepY = NO_BOUNDARY;
        double boundaryStepZ = NO_BOUNDARY;
        if (offsetX > 0.0) {
            stepX = 1;
            nextBoundaryX = (1.0 - eyeFractionX) / offsetX;
            boundaryStepX = 1.0 / offsetX;
        } else if (offsetX < 0.0) {
            stepX = -1;
            nextBoundaryX = eyeFractionX / -offsetX;
            boundaryStepX = 1.0 / -offsetX;
        }
        if (offsetY > 0.0) {
            stepY = 1;
            nextBoundaryY = (1.0 - eyeFractionY) / offsetY;
            boundaryStepY = 1.0 / offsetY;
        } else if (offsetY < 0.0) {
            stepY = -1;
            nextBoundaryY = eyeFractionY / -offsetY;
            boundaryStepY = 1.0 / -offsetY;
        }
        if (offsetZ > 0.0) {
            stepZ = 1;
            nextBoundaryZ = (1.0 - eyeFractionZ) / offsetZ;
            boundaryStepZ = 1.0 / offsetZ;
        } else if (offsetZ < 0.0) {
            stepZ = -1;
            nextBoundaryZ = eyeFractionZ / -offsetZ;
            boundaryStepZ = 1.0 / -offsetZ;
        }
        double depth = 0.0;
        double enterDepth = 0.0;
        double enter = 0.0;
        boolean reachedRegionEnd = false;
        boolean noiseOn = isNoiseOn();
        for (int cellStep = 0; cellStep < MAXIMUM_CELL_STEPS; cellStep++) {
            lastCellSteps = cellStep + 1;
            double exit = Math.min(Math.min(nextBoundaryX, nextBoundaryY), Math.min(nextBoundaryZ, regionEnd));
            double exitDepth = tauAt(exit, segmentLength, offsetY);
            double share = cellShare(cellX, cellY, cellZ);
            double partDepth = Math.max(0.0, exitDepth - enterDepth);
            if (noiseOn && share > 0.0) {
                double middle = (enter + exit) * 0.5;
                partDepth *= 1.0 + noiseStrength * noiseAt(middle * offsetX, middle * offsetY, middle * offsetZ);
            }
            depth += share * partDepth;
            enterDepth = exitDepth;
            enter = exit;
            if (!(exit < regionEnd)) {
                reachedRegionEnd = true;
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
        if (!reachedRegionEnd) {
            double endDepth = tauAt(regionEnd, segmentLength, offsetY);
            depth += Math.max(0.0, endDepth - enterDepth);
            enterDepth = endDepth;
        }
        regionEndDepth = enterDepth;
        return depth;
    }

    /**
     * Finds where the covered stretch at the end of the segment begins, by walking the columns under the segment's
     * horizontal projection backward from the scene point toward the eye, and counts them. The walk stops at the first
     * column that leaves the cover, at the height the segment crosses in a column it descends in, at the region's edge,
     * or at the last column it may visit, after which the rest of the segment counts as open.
     */
    private double findFarStop(double offsetX, double offsetY, double offsetZ, double regionEnd) {
        int columnX = (int) Math.floor(eyeFractionX + offsetX);
        int columnZ = (int) Math.floor(eyeFractionZ + offsetZ);
        if (!isCovered(1.0, columnX, columnZ, offsetY)) {
            return 1.0;
        }
        double walkEnd = 1.0 - regionEnd;
        int stepX = 0;
        int stepZ = 0;
        double nextBoundaryX = NO_BOUNDARY;
        double nextBoundaryZ = NO_BOUNDARY;
        double boundaryStepX = NO_BOUNDARY;
        double boundaryStepZ = NO_BOUNDARY;
        if (Math.sqrt(offsetX * offsetX + offsetZ * offsetZ) >= MINIMUM_HORIZONTAL_LENGTH) {
            double sceneFractionX = eyeFractionX + offsetX - columnX;
            double sceneFractionZ = eyeFractionZ + offsetZ - columnZ;
            if (offsetX > 0.0) {
                stepX = -1;
                nextBoundaryX = sceneFractionX / offsetX;
                boundaryStepX = 1.0 / offsetX;
            } else if (offsetX < 0.0) {
                stepX = 1;
                nextBoundaryX = (1.0 - sceneFractionX) / -offsetX;
                boundaryStepX = 1.0 / -offsetX;
            }
            if (offsetZ > 0.0) {
                stepZ = -1;
                nextBoundaryZ = sceneFractionZ / offsetZ;
                boundaryStepZ = 1.0 / offsetZ;
            } else if (offsetZ < 0.0) {
                stepZ = 1;
                nextBoundaryZ = (1.0 - sceneFractionZ) / -offsetZ;
                boundaryStepZ = 1.0 / -offsetZ;
            }
        }
        double enter = 0.0;
        while (true) {
            lastColumnSteps++;
            double exit = Math.min(Math.min(nextBoundaryX, nextBoundaryZ), walkEnd);
            double startParameter = 1.0 - enter;
            double endParameter = 1.0 - exit;
            if (lastColumnSteps > 1 && !isCovered(startParameter, columnX, columnZ, offsetY)) {
                return clampToFarPart(startParameter, regionEnd);
            }
            if (offsetY < 0.0) {
                double cross = (heightAt(columnX, columnZ) - eyeBlockY - eyeFractionY) / offsetY;
                if (cross > endParameter) {
                    return clampToFarPart(cross, regionEnd);
                }
            }
            if (exit >= walkEnd) {
                return regionEnd;
            }
            if (lastColumnSteps == FAR_WALK_COLUMNS) {
                return endParameter;
            }
            double smallestBoundary = Math.min(nextBoundaryX, nextBoundaryZ);
            // Ties step both axes at once, as the cell walk does.
            if (nextBoundaryX <= smallestBoundary) {
                columnX += stepX;
                nextBoundaryX += boundaryStepX;
            }
            if (nextBoundaryZ <= smallestBoundary) {
                columnZ += stepZ;
                nextBoundaryZ += boundaryStepZ;
            }
            enter = exit;
        }
    }

    /**
     * Keeps a stop of the backward walk inside the far part of the segment.
     */
    private static double clampToFarPart(double stop, double regionEnd) {
        if (!(stop > regionEnd)) {
            return regionEnd;
        }
        return Math.min(1.0, stop);
    }

    /**
     * Whether a point of the segment lies under cover in a column, which is the case when it stands below the motion
     * height of that column. It compares the point's height over the eye's block against the column's height over that
     * block, in integers, so the cover is exact far from the origin.
     */
    private boolean isCovered(double parameter, int columnX, int columnZ, double verticalOffset) {
        return eyeFractionY + parameter * verticalOffset < heightAt(columnX, columnZ) - eyeBlockY;
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
     * The optical depth from the eye up to the given parameter of the segment, as HeightFog.opticalDepth gives it.
     */
    private double tauAt(double parameter, double segmentLength, double verticalOffset) {
        return HeightFog.opticalDepth(eyeExtinction, parameter * segmentLength, parameter * verticalOffset,
                heightScale);
    }
}
