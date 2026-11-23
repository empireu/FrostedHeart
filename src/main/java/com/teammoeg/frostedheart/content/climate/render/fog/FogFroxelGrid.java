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
 * The shape of the grid that the weather fog is walked in: the columns of the screen, the depth slices of each column's
 * ray, the atlas of the array texture that holds the optical depth of every froxel, and the mapping between distances
 * along a ray and the boundaries of the slices.
 * A column covers divisor by divisor pixels of the screen and walks one ray through the screen point at its center. A
 * ray is cut into sliceCount slices whose boundaries are geometric, so that near slices are thin and far slices thick,
 * and the boundary at distance d_k from the eye lies at NEAR * (rangeDistance / NEAR) ^ (k / sliceCount).
 * Every method is a pure function of its arguments, allocates nothing, and is safe to call from any thread.
 */
public final class FogFroxelGrid {
    /**
     * The distance in blocks from the eye at which the first slice boundary lies, and below which the composite lookup
     * takes no excess.
     */
    public static final double NEAR = 1.0;
    /**
     * The smallest divisor in pixels per side of a column, which is the finest grid.
     */
    public static final int MINIMUM_DIVISOR = 4;
    /**
     * The largest divisor in pixels per side of a column, which is the coarsest grid.
     */
    public static final int MAXIMUM_DIVISOR = 16;
    /**
     * The divisor in pixels per side of a column before the settings say otherwise.
     */
    public static final int DEFAULT_DIVISOR = 8;
    /**
     * The smallest number of depth slices of a ray.
     */
    public static final int MINIMUM_SLICES = 32;
    /**
     * The largest number of depth slices of a ray, which is also the number of boundaries the array texture holds.
     */
    public static final int MAXIMUM_SLICES = 64;
    /**
     * The step in slices to which the settings snap the slice count.
     */
    public static final int SLICE_STEP = 8;
    /**
     * The number of depth slices of a ray before the settings say otherwise.
     */
    public static final int DEFAULT_SLICES = 64;
    /**
     * The shortest range in blocks out to which the slices are walked.
     */
    public static final double MINIMUM_RANGE = 64.0;
    /**
     * The longest range in blocks out to which the slices are walked.
     */
    public static final double MAXIMUM_RANGE = 512.0;
    /**
     * The range in blocks out to which the slices are walked before the settings say otherwise.
     */
    public static final double DEFAULT_RANGE = 192.0;
    /**
     * The slice boundaries that one layer of the array texture holds, which is also the number of channels of a texel.
     */
    public static final int SLICES_PER_LAYER = 4;
    /**
     * The tiles per side of the atlas and the array texture, so that one layer holds TILES_ACROSS * TILES_ACROSS
     * tiles.
     */
    public static final int TILES_ACROSS = 8;
    /**
     * The layers of the array texture that the maximum slice count needs.
     */
    public static final int MAXIMUM_LAYERS = MAXIMUM_SLICES / SLICES_PER_LAYER;

    private FogFroxelGrid() {
    }

    /**
     * The divisor that the columns take.
     * @param setting the value of fogFroxelDivisor, in pixels per side, where NaN counts as DEFAULT_DIVISOR
     * @return the nearest whole number of pixels per side to the setting, clamped to MINIMUM_DIVISOR to
     *         MAXIMUM_DIVISOR
     */
    public static int divisorOf(double setting) {
        if (Double.isNaN(setting)) {
            return DEFAULT_DIVISOR;
        }
        long roundedDivisor = Math.round(setting);
        if (roundedDivisor < MINIMUM_DIVISOR) {
            return MINIMUM_DIVISOR;
        }
        if (roundedDivisor > MAXIMUM_DIVISOR) {
            return MAXIMUM_DIVISOR;
        }
        return (int) roundedDivisor;
    }

    /**
     * The number of depth slices of a ray.
     * @param setting the value of fogFroxelSlices, in slices, where NaN counts as DEFAULT_SLICES
     * @return the nearest multiple of SLICE_STEP to the setting, clamped to MINIMUM_SLICES to MAXIMUM_SLICES
     */
    public static int sliceCountOf(double setting) {
        if (Double.isNaN(setting)) {
            return DEFAULT_SLICES;
        }
        double snappedSlices = Math.round(setting / SLICE_STEP) * (double) SLICE_STEP;
        if (snappedSlices < MINIMUM_SLICES) {
            return MINIMUM_SLICES;
        }
        if (snappedSlices > MAXIMUM_SLICES) {
            return MAXIMUM_SLICES;
        }
        return (int) snappedSlices;
    }

    /**
     * The distance out to which the slices are walked, which is the distance of the last boundary.
     * @param setting the value of fogFroxelRange, in blocks, where NaN counts as DEFAULT_RANGE
     * @return the setting in blocks, clamped to MINIMUM_RANGE to MAXIMUM_RANGE
     */
    public static double rangeOf(double setting) {
        if (Double.isNaN(setting)) {
            return DEFAULT_RANGE;
        }
        if (setting < MINIMUM_RANGE) {
            return MINIMUM_RANGE;
        }
        if (setting > MAXIMUM_RANGE) {
            return MAXIMUM_RANGE;
        }
        return setting;
    }

    /**
     * The columns that a screen axis is walked in.
     * @param screenPixels the pixels of the screen axis, positive
     * @param divisor the pixels per side of a column, from MINIMUM_DIVISOR to MAXIMUM_DIVISOR
     * @return the columns of the axis, at least 1, which is ceil(screenPixels / divisor)
     */
    public static int columnCount(int screenPixels, int divisor) {
        if (screenPixels < 1 || divisor < 1) {
            return 1;
        }
        return (screenPixels + divisor - 1) / divisor;
    }

    /**
     * The width of the atlas in texels.
     * @param columnCount the columns of the screen axis, from 1 up
     * @return the texels across, which is columnCount * TILES_ACROSS
     */
    public static int atlasWidth(int columnCount) {
        return columnCount * TILES_ACROSS;
    }

    /**
     * The height of the atlas in texels, which is allocated for the maximum slice count.
     * @param rowCount the rows of the screen axis, from 1 up
     * @return the texels down, which is rowCount * TILES_ACROSS
     */
    public static int atlasHeight(int rowCount) {
        return rowCount * TILES_ACROSS;
    }

    /**
     * The texel rows of the atlas that the inject draw covers.
     * @param rowCount the rows of the screen axis, from 1 up
     * @param sliceCount the depth slices of a ray, from MINIMUM_SLICES to MAXIMUM_SLICES
     * @return the texels down, which is rowCount * sliceCount / TILES_ACROSS
     */
    public static int atlasDrawHeight(int rowCount, int sliceCount) {
        return rowCount * sliceCount / TILES_ACROSS;
    }

    /**
     * The atlas tile across of a depth slice.
     * @param slice the depth slice, from 1 to MAXIMUM_SLICES
     * @return the tile across, from 0 to TILES_ACROSS - 1
     */
    public static int tileX(int slice) {
        return (slice - 1) % TILES_ACROSS;
    }

    /**
     * The atlas tile down of a depth slice.
     * @param slice the depth slice, from 1 to MAXIMUM_SLICES
     * @return the tile down, from 0 to TILES_ACROSS - 1
     */
    public static int tileY(int slice) {
        return (slice - 1) / TILES_ACROSS;
    }

    /**
     * The texel across of the cell of a column in the tile of a depth slice.
     * @param column the column of the screen axis, from 0 to columnCount - 1
     * @param slice the depth slice, from 1 to MAXIMUM_SLICES
     * @param columnCount the columns of the screen axis, from 1 up
     * @return the texel across, from 0 to atlasWidth(columnCount) - 1
     */
    public static int atlasTexelX(int column, int slice, int columnCount) {
        return tileX(slice) * columnCount + column;
    }

    /**
     * The texel down of the cell of a row in the tile of a depth slice.
     * @param row the row of the screen axis, from 0 to rowCount - 1
     * @param slice the depth slice, from 1 to MAXIMUM_SLICES
     * @param rowCount the rows of the screen axis, from 1 up
     * @return the texel down, from 0 to atlasHeight(rowCount) - 1
     */
    public static int atlasTexelY(int row, int slice, int rowCount) {
        return tileY(slice) * rowCount + row;
    }

    /**
     * The depth slice that a texel of the atlas holds, which is the inverse of atlasTexelX and atlasTexelY.
     * @param texelX the texel across, from 0 to 8 * columnCount - 1
     * @param texelY the texel down, from 0 to 8 * rowCount - 1
     * @param columnCount the columns of the screen axis, from 1 up
     * @param rowCount the rows of the screen axis, from 1 up
     * @return the depth slice, from 1 to MAXIMUM_SLICES
     */
    public static int sliceOfAtlasTexel(int texelX, int texelY, int columnCount, int rowCount) {
        return (texelY / rowCount) * TILES_ACROSS + texelX / columnCount + 1;
    }

    /**
     * The column of the screen axis that a texel of the atlas holds, which is the inverse of atlasTexelX.
     * @param texelX the texel across, from 0 to 8 * columnCount - 1
     * @param columnCount the columns of the screen axis, from 1 up
     * @return the column, from 0 to columnCount - 1
     */
    public static int columnOfAtlasTexel(int texelX, int columnCount) {
        return texelX % columnCount;
    }

    /**
     * The row of the screen axis that a texel of the atlas holds, which is the inverse of atlasTexelY.
     * @param texelY the texel down, from 0 to 8 * rowCount - 1
     * @param rowCount the rows of the screen axis, from 1 up
     * @return the row, from 0 to rowCount - 1
     */
    public static int rowOfAtlasTexel(int texelY, int rowCount) {
        return texelY % rowCount;
    }

    /**
     * The distance in blocks of a slice boundary from the eye, which is NEAR * (rangeDistance / NEAR) ^
     * (boundaryIndex / sliceCount). The boundaries are geometric, so that the slices are thin near the eye and thick
     * far from it.
     * @param boundaryIndex the boundary, from 0 for the one at NEAR to sliceCount for the one at the range
     * @param sliceCount the depth slices of a ray, positive
     * @param rangeDistance the distance in blocks of the last boundary, above NEAR
     * @return the distance in blocks, from NEAR to rangeDistance
     * @throws IllegalArgumentException when the slice count is not positive, the range is not above NEAR, or the index
     *         is outside 0 to sliceCount
     */
    public static double boundaryDistance(int boundaryIndex, int sliceCount, double rangeDistance) {
        if (sliceCount <= 0) {
            throw new IllegalArgumentException("The slice count is " + sliceCount + " instead of positive");
        }
        if (!(rangeDistance > NEAR)) {
            throw new IllegalArgumentException("The range is " + rangeDistance + " instead of above NEAR");
        }
        if (boundaryIndex < 0 || boundaryIndex > sliceCount) {
            throw new IllegalArgumentException("The boundary " + boundaryIndex + " is outside 0 to " + sliceCount);
        }
        return NEAR * Math.pow(rangeDistance / NEAR, (double) boundaryIndex / sliceCount);
    }

    /**
     * The continuous boundary index of a distance along a ray, which is the inverse of boundaryDistance: 0 at or below
     * NEAR and sliceCount at or past the range.
     * @param distance the distance in blocks from the eye, along the ray
     * @param sliceCount the depth slices of a ray, positive
     * @param rangeDistance the distance in blocks of the last boundary
     * @return the boundary index, from 0 to sliceCount, which is 0 for a distance that is not finite and for a range
     *         that is not above NEAR
     */
    public static double slicePosition(double distance, int sliceCount, double rangeDistance) {
        if (!(sliceCount > 0) || !Double.isFinite(distance) || !(rangeDistance > NEAR)) {
            return 0.0;
        }
        if (!(distance > NEAR)) {
            return 0.0;
        }
        if (distance >= rangeDistance) {
            return sliceCount;
        }
        return sliceCount * Math.log(distance / NEAR) / Math.log(rangeDistance / NEAR);
    }

    /**
     * The layer of the array texture that holds the boundary of a depth slice.
     * @param boundaryIndex the boundary, from 1 to MAXIMUM_SLICES
     * @return the layer, from 0 to MAXIMUM_LAYERS - 1, which is (boundaryIndex - 1) / SLICES_PER_LAYER
     */
    public static int layerOf(int boundaryIndex) {
        return (boundaryIndex - 1) / SLICES_PER_LAYER;
    }

    /**
     * The channel of the array texture's texel that holds the boundary of a depth slice.
     * @param boundaryIndex the boundary, from 1 to MAXIMUM_SLICES
     * @return the channel, from 0 to SLICES_PER_LAYER - 1, which is (boundaryIndex - 1) % SLICES_PER_LAYER
     */
    public static int channelOf(int boundaryIndex) {
        return (boundaryIndex - 1) % SLICES_PER_LAYER;
    }

    /**
     * The layers of the array texture that a slice count needs.
     * @param sliceCount the depth slices of a ray, from MINIMUM_SLICES to MAXIMUM_SLICES
     * @return the layers, from 1 to MAXIMUM_LAYERS, which is sliceCount / SLICES_PER_LAYER
     */
    public static int layerCount(int sliceCount) {
        return sliceCount / SLICES_PER_LAYER;
    }

    /**
     * The excess of the walked optical depth over the analytic height fog at a distance along a ray, which is the
     * composite lookup: 0 at or below NEAR, the last entry at or past the range, and else the linear blend between the
     * boundaries that surround the distance. The composite's froxelExcessAtDistance in weather_fog_composite.fsh is
     * the GLSL twin this method mirrors, the rule every terrain pixel reads.
     * @param distance the distance in blocks from the eye, along the ray
     * @param excesses the excesses of the boundaries 1 to sliceCount, which hold at least sliceCount entries
     * @param sliceCount the depth slices of a ray, positive
     * @param rangeDistance the distance in blocks of the last boundary
     * @return the excess in optical depths, at most the largest excess
     * @throws IllegalArgumentException when the array holds fewer than sliceCount entries
     */
    public static double excessAtDistance(double distance, double[] excesses, int sliceCount, double rangeDistance) {
        if (excesses.length < sliceCount) {
            throw new IllegalArgumentException("The excesses of the slice count " + sliceCount + " have "
                    + excesses.length + " entries instead of " + sliceCount);
        }
        if (!(sliceCount > 0) || !Double.isFinite(distance) || !(rangeDistance > NEAR)) {
            return 0.0;
        }
        if (!(distance > NEAR)) {
            return 0.0;
        }
        if (distance >= rangeDistance) {
            return excesses[sliceCount - 1];
        }
        double position = Math.min(sliceCount, slicePosition(distance, sliceCount, rangeDistance));
        if (!(position > 0.0)) {
            return 0.0;
        }
        int lowBoundary = (int) Math.floor(position);
        if (!(position > lowBoundary)) {
            return excesses[lowBoundary - 1];
        }
        double belowExcess = lowBoundary > 0 ? excesses[lowBoundary - 1] : 0.0;
        double aboveExcess = excesses[lowBoundary];
        return belowExcess + (position - lowBoundary) * (aboveExcess - belowExcess);
    }
}
