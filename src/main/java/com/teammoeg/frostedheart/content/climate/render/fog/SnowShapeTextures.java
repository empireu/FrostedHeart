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

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * The two textures that the update of the snowflakes collides with: the ids of SnowShapeVolume as an R16UI 3D texture of
 * the same slots, with repeat wrapping, and the rows of SnowShapes as an R16UI 2D texture.
 * The rows texture holds each shape as a tile of 16 by 16 texels, SHAPES_PER_ROW tiles across, with the tile of shape s
 * at column s % SHAPES_PER_ROW and row s / SHAPES_PER_ROW. Inside a tile, texel x is the voxel z, texel y is the voxel y,
 * and the 16 bits are the voxels along x.
 * The volume uploads its dirty bricks in runs along x, and the table uploads the shapes added since the last upload. The
 * rows texture grows by doubling its height and uploads every shape again when the table outgrows it.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class SnowShapeTextures {
    static final int SHAPES_PER_ROW = SnowShapes.SHAPES_PER_ROW;
    private static final int ROWS_TEXTURE_WIDTH = SHAPES_PER_ROW * SnowShapes.SIDE;
    private static final int BRICK = SnowShapeVolume.BRICK_SIZE;
    private static final int RUN_CAPACITY = SnowShapeVolume.SIZE * BRICK * BRICK;

    private static final ShortBuffer runStaging = BufferUtils.createShortBuffer(RUN_CAPACITY);
    private static final ShortBuffer tileStaging = BufferUtils.createShortBuffer(SnowShapes.ROWS_PER_SHAPE);
    private static int volumeTexture;
    private static int rowsTexture;
    /**
     * The rows of tiles the rows texture has room for.
     */
    private static int rowsTextureTileRows;
    private static int uploadedShapes;
    @Nullable
    private static SnowShapes uploadedTable;
    private static long currentBrickUploads;

    private SnowShapeTextures() {
    }

    /**
     * Makes both textures on first use and uploads what changed since the last upload. Binds the textures it changes on
     * the active unit and binds the earlier textures back.
     */
    static void upload(SnowShapeVolume volume) {
        int previous3D = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int previous2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 2);
            if (volumeTexture == 0) {
                volumeTexture = createVolumeTexture();
                volume.markAllBricksDirty();
            }
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volumeTexture);
            uploadDirtyBricks(volume);
            uploadNewShapes(volume.shapes());
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous3D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous2D);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
    }

    /**
     * The texture of the ids, or 0 before the first upload.
     */
    static int volumeTexture() {
        return volumeTexture;
    }

    /**
     * The texture of the rows, or 0 before the first upload.
     */
    static int rowsTexture() {
        return rowsTexture;
    }

    /**
     * The bricks uploaded since the last call, for the client command.
     */
    static long takeBrickUploads() {
        long uploads = currentBrickUploads;
        currentBrickUploads = 0L;
        return uploads;
    }

    private static int createVolumeTexture() {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R16UI, SnowShapeVolume.SIZE, SnowShapeVolume.SIZE,
                SnowShapeVolume.SIZE, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, (ByteBuffer) null);
        return texture;
    }

    /**
     * Uploads the runs of dirty bricks of the volume, one call of glTexSubImage3D per run, and clears the dirty bricks.
     */
    private static void uploadDirtyBricks(SnowShapeVolume volume) {
        int bricks = SnowShapeVolume.BRICKS_PER_AXIS;
        for (int brickZ = 0; brickZ < bricks; brickZ++) {
            for (int brickY = 0; brickY < bricks; brickY++) {
                int firstBrickX = -1;
                for (int brickX = 0; brickX <= bricks; brickX++) {
                    boolean dirty = brickX < bricks
                            && volume.isBrickDirty(brickX + brickY * bricks + brickZ * bricks * bricks);
                    if (dirty) {
                        if (firstBrickX < 0) {
                            firstBrickX = brickX;
                        }
                    } else if (firstBrickX >= 0) {
                        uploadBrickRun(volume.cells(), firstBrickX, brickX - 1, brickY, brickZ);
                        firstBrickX = -1;
                    }
                }
            }
        }
        volume.clearDirtyBricks();
    }

    private static void uploadBrickRun(char[] cells, int firstBrickX, int lastBrickX, int brickY, int brickZ) {
        int x0 = firstBrickX * BRICK;
        int y0 = brickY * BRICK;
        int z0 = brickZ * BRICK;
        int width = (lastBrickX - firstBrickX + 1) * BRICK;
        int index = 0;
        for (int localZ = 0; localZ < BRICK; localZ++) {
            for (int localY = 0; localY < BRICK; localY++) {
                int rowSlot = SnowShapeVolume.slotOf(x0, y0 + localY, z0 + localZ);
                for (int localX = 0; localX < width; localX++) {
                    runStaging.put(index++, (short) cells[rowSlot + localX]);
                }
            }
        }
        runStaging.position(0);
        runStaging.limit(index);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x0, y0, z0, width, BRICK, BRICK, GL30.GL_RED_INTEGER,
                GL11.GL_UNSIGNED_SHORT, runStaging);
        runStaging.limit(RUN_CAPACITY);
        currentBrickUploads += lastBrickX - firstBrickX + 1;
    }

    /**
     * Uploads the shapes the table added since the last upload, after making the rows texture or growing it when the
     * table outgrew it, which uploads every shape again.
     */
    private static void uploadNewShapes(SnowShapes shapes) {
        if (uploadedTable != shapes) {
            uploadedTable = shapes;
            uploadedShapes = 0;
        }
        int shapeCount = shapes.shapeCount();
        int neededTileRows = (shapeCount + SHAPES_PER_ROW - 1) / SHAPES_PER_ROW;
        if (rowsTexture == 0 || neededTileRows > rowsTextureTileRows) {
            if (rowsTexture == 0) {
                rowsTexture = GL11.glGenTextures();
            }
            rowsTextureTileRows = Math.max(neededTileRows, Math.max(1, rowsTextureTileRows * 2));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, rowsTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16UI, ROWS_TEXTURE_WIDTH,
                    rowsTextureTileRows * SnowShapes.SIDE, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT,
                    (ByteBuffer) null);
            uploadedShapes = 0;
        }
        if (uploadedShapes >= shapeCount) {
            return;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rowsTexture);
        char[] rows = shapes.rows();
        for (int shape = uploadedShapes; shape < shapeCount; shape++) {
            int first = shape * SnowShapes.ROWS_PER_SHAPE;
            for (int row = 0; row < SnowShapes.ROWS_PER_SHAPE; row++) {
                tileStaging.put(row, (short) rows[first + row]);
            }
            tileStaging.position(0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (shape % SHAPES_PER_ROW) * SnowShapes.SIDE,
                    (shape / SHAPES_PER_ROW) * SnowShapes.SIDE, SnowShapes.SIDE, SnowShapes.SIDE, GL30.GL_RED_INTEGER,
                    GL11.GL_UNSIGNED_SHORT, tileStaging);
        }
        uploadedShapes = shapeCount;
    }
}
