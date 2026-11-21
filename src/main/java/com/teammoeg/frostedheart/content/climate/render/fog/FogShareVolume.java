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
 * The layout of the share volume of the weather fog, a cube of cells centered on the eye's block whose share of the snow
 * the renderer computes once per frame and the walk reads with one texelFetch per cell.
 * The volume is eye relative and holds one cell per block, in the cells that SnowFogWalk.cellShare counts from the eye's
 * block: the cell at the offset (x, y, z) is the world block of the eye's block plus that offset. Its horizontal slices
 * of SIDE by SIDE cells lie in a 2D atlas of TILES_ACROSS by TILES_DOWN tiles, the slice of the offset y at the tile
 * column (y + ORIGIN) % TILES_ACROSS and the tile row (y + ORIGIN) / TILES_ACROSS, so that one draw fills the whole
 * volume and a texelFetch reads it without a 3D render target.
 * The volume covers every cell the walk may visit at the largest field radius: the region half width is the field radius
 * of SkySetting.FOG_FIELD_RADIUS minus SnowFogWalk.REGION_MARGIN, which is at most 38 blocks, and the walk visits only
 * the cells whose center lies within the half width plus one cell of the eye's block, so the offsets from -39 to 39 hold
 * every cell it reads.
 * The class holds no state, and no method allocates.
 */
public final class FogShareVolume {
    /**
     * The number of cells per axis of the volume.
     */
    public static final int SIDE = 80;
    /**
     * The index of the offset 0 on every axis, so the offsets from -ORIGIN to SIDE - ORIGIN - 1 are inside the volume.
     */
    public static final int ORIGIN = 40;
    /**
     * The number of tiles of the atlas across, and of the tiles down.
     */
    public static final int TILES_ACROSS = 10;
    public static final int TILES_DOWN = 8;
    /**
     * The size of the atlas in texels, which holds a tile of SIDE by SIDE texels for every slice of the volume.
     */
    public static final int ATLAS_WIDTH = SIDE * TILES_ACROSS;
    public static final int ATLAS_HEIGHT = SIDE * TILES_DOWN;

    private FogShareVolume() {
    }

    /**
     * Whether a cell lies inside the volume, which every axis of it must.
     * @param x the x of the cell counted from the eye's block
     * @param y the y of the cell counted from the eye's block
     * @param z the z of the cell counted from the eye's block
     * @return whether the cell is inside the volume
     */
    public static boolean contains(int x, int y, int z) {
        return isInside(x) && isInside(y) && isInside(z);
    }

    /**
     * The x of the atlas texel of a cell inside the volume, in texels from the left edge of the atlas. Undefined for a
     * cell outside the volume, which contains rejects.
     * @param x the x of the cell counted from the eye's block
     * @param y the y of the cell counted from the eye's block
     * @return the x of its atlas texel, from 0 to ATLAS_WIDTH - 1
     */
    public static int atlasX(int x, int y) {
        return x + ORIGIN + ((y + ORIGIN) % TILES_ACROSS) * SIDE;
    }

    /**
     * The y of the atlas texel of a cell inside the volume, in texels from the bottom edge of the atlas. Undefined for a
     * cell outside the volume, which contains rejects.
     * @param z the z of the cell counted from the eye's block
     * @param y the y of the cell counted from the eye's block
     * @return the y of its atlas texel, from 0 to ATLAS_HEIGHT - 1
     */
    public static int atlasY(int z, int y) {
        return z + ORIGIN + ((y + ORIGIN) / TILES_ACROSS) * SIDE;
    }

    private static boolean isInside(int offset) {
        return offset >= -ORIGIN && offset <= SIDE - ORIGIN - 1;
    }
}
