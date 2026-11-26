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
 * The shapes of snowflakes that FlakeShapeGenerator makes, as square tiles of how much light each pixel blocks, from 0
 * for air to 255 for opaque ice.
 * The tiles lie one after another in data, row by row, so pixel x, y of shape s is at (s * size + y) * size + x.
 */
public final class FlakeShapeSet {
    public enum ShapeCategory {
        AGGREGATE,
        RIMED,
        FRAGMENT,
        PLATE,
        DENDRITE,
        COLUMN
    }

    private final int count;
    private final int size;
    private final byte[] data;
    private final double[] means;
    private final ShapeCategory[] categories;

    /**
     * @param means the mean pixel value of each shape, from 0 to 255
     */
    public FlakeShapeSet(int count, int size, byte[] data, double[] means, ShapeCategory[] categories) {
        if (count <= 0) {
            throw new IllegalArgumentException("Shape count must be positive");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Shape size must be positive");
        }
        if (data.length != count * size * size) {
            throw new IllegalArgumentException("Data array length does not match count * size * size");
        }
        if (means.length != count) {
            throw new IllegalArgumentException("Means array length does not match count");
        }
        if (categories.length != count) {
            throw new IllegalArgumentException("Categories array length does not match count");
        }
        this.count = count;
        this.size = size;
        this.data = data;
        this.means = means;
        this.categories = categories;
    }

    public int count() {
        return count;
    }

    /**
     * The side of each tile in pixels.
     */
    public int size() {
        return size;
    }

    public byte[] data() {
        return data;
    }

    public ShapeCategory category(int shape) {
        return categories[shape];
    }

    /**
     * The share of its tile that a shape covers, its mean pixel value over 255.
     */
    public double coverage(int shape) {
        return means[shape] / 255.0;
    }

    public int pixel(int shape, int pixelX, int pixelY) {
        return data[(shape * size + pixelY) * size + pixelX] & 0xFF;
    }
}
