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
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two textures of the fog, the snow field and the far heights, and their uploads.
 * The field texture is a GL_R8 3D texture of the cells of the SnowField cube, whose byte array is already its layout,
 * and it is uploaded brick by brick, the unit in which SnowField marks its distances dirty. A frame uploads the dirty
 * bricks only while the solver is drained and has a solved box, because a decrease of the distances leaves cells at
 * SHELTERED or UNREAD until the increase reaches them, and a frame that uploaded them would show wrong values for a
 * frame or two. The shader shows a cell only inside the shown box, which never contains a cell whose texel is older
 * than its meaning there: it is cut down to its intersection with the solved box on every frame that uploads nothing,
 * and it only grows again on the upload of a drained field, which brought the values up to date.
 * The height texture is a GL_R16I 2D texture of FarHeights.SIZE by FarHeights.SIZE texels that holds the motion
 * heights of the window of FarHeights in the layout of FarHeights.heights, and it is uploaded tile row by tile row, the
 * unit in which FarHeights marks its tiles dirty.
 * Both textures are made on first use and kept for the session. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class SnowFieldTextures {
    /**
     * The number of texels per side of one tile of the heights, which is the height of one upload of a tile row.
     */
    private static final int TILE_WIDTH = FarHeights.SIZE / FarHeights.CHUNKS;
    private static final double NANOSECONDS_PER_MILLISECOND = 1.0e6;

    /**
     * One tile row of the heights, the 16 rows of texels of the tiles with the same tile z.
     */
    private static final ShortBuffer heightStaging = BufferUtils.createShortBuffer(FarHeights.SIZE * TILE_WIDTH);

    /**
     * One brick row of the field, the 8 by 8 by size cells of the bricks with the same brick y and brick z, or null
     * before the field texture is made.
     */
    private static ByteBuffer fieldStaging;
    /**
     * The number of cells per axis of the field texture, or 0 before it is made.
     */
    private static int fieldSize;
    private static int fieldTexture;
    private static boolean hasShownBox;
    private static int shownMinX;
    private static int shownMinY;
    private static int shownMinZ;
    private static int shownMaxX;
    private static int shownMaxY;
    private static int shownMaxZ;
    private static int heightTexture;
    private static int currentFieldUploads;
    private static int currentBricksUploaded;
    private static int currentWaitedFrames;
    private static int currentHeightUploads;
    private static int currentTilesUploaded;
    private static long currentFieldNanos;
    private static long currentHeightNanos;
    private static int lastFieldUploads;
    private static int lastBricksUploaded;
    private static int lastWaitedFrames;
    private static int lastHeightUploads;
    private static int lastTilesUploaded;
    private static long lastFieldNanos;
    private static long lastHeightNanos;

    private SnowFieldTextures() {
    }

    /**
     * The field texture, made on first use, with the dirty bricks of the field uploaded when the solver is drained and
     * the field has a solved box. The texture bound before stays bound. Allocates nothing after the frame that makes
     * the texture.
     * @param field the field whose distances to upload
     * @return the texture id
     * @throws IllegalStateException when the field has a different size than the texture, since the client keeps one
     *         field per session
     */
    public static int uploadField(SnowField field) {
        long start = System.nanoTime();
        if (fieldTexture == 0) {
            createFieldTexture(field);
        } else if (field.size() != fieldSize) {
            throw new IllegalStateException("The snow field of the fog has " + field.size() + " cells per axis while "
                    + "its texture has " + fieldSize + ", and the client keeps one field per session");
        }
        if (field.isDrained() && field.hasSolvedBox()) {
            if (field.dirtyBrickCount() > 0) {
                uploadDirtyBricks(field);
                field.clearDirtyBricks();
            }
            hasShownBox = true;
            shownMinX = field.solvedMinX();
            shownMinY = field.solvedMinY();
            shownMinZ = field.solvedMinZ();
            shownMaxX = field.solvedMaxX();
            shownMaxY = field.solvedMaxY();
            shownMaxZ = field.solvedMaxZ();
        } else {
            if (field.dirtyBrickCount() > 0) {
                currentWaitedFrames++;
            }
            cutShownBox(field);
        }
        currentFieldNanos += System.nanoTime() - start;
        return fieldTexture;
    }

    /**
     * The height texture, made on first use, with the tiles that changed since the last call uploaded. The texture
     * bound before stays bound. Allocates nothing after the frame that makes the texture.
     * @param heights the heights to upload
     * @return the texture id
     */
    public static int uploadHeights(FarHeights heights) {
        long start = System.nanoTime();
        if (heightTexture == 0) {
            createHeightTexture();
        } else if (heights.dirtyTileCount() == 0) {
            return heightTexture;
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, FarHeights.SIZE);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, heightTexture);
            for (int tileRow = 0; tileRow < FarHeights.CHUNKS; tileRow++) {
                int firstTileX = -1;
                for (int tileX = 0; tileX <= FarHeights.CHUNKS; tileX++) {
                    boolean dirty = tileX < FarHeights.CHUNKS
                            && heights.isTileDirty(FarHeights.tileOf(tileX, tileRow));
                    if (dirty) {
                        if (firstTileX < 0) {
                            firstTileX = tileX;
                        }
                    } else if (firstTileX >= 0) {
                        uploadTileRun(heights, tileRow, firstTileX, tileX - 1);
                        firstTileX = -1;
                    }
                }
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            restoreUnpackState();
        }
        heights.clearDirtyTiles();
        currentHeightNanos += System.nanoTime() - start;
        return heightTexture;
    }

    /**
     * The field texture, made on first use.
     * @return the texture id, or 0 while no field was uploaded
     */
    public static int fieldTexture() {
        return fieldTexture;
    }

    /**
     * The height texture, made on first use.
     * @return the texture id, or 0 while no heights were uploaded
     */
    public static int heightTexture() {
        return heightTexture;
    }

    /**
     * Whether the shown box holds at least one cell, after which its six corner getters are meaningful.
     * @return whether the shader may show a cell
     */
    public static boolean hasShownBox() {
        return hasShownBox;
    }

    /**
     * The world x of the low corner of the shown box, meaningful only while hasShownBox.
     * @return the world x, inclusive
     */
    public static int shownMinX() {
        return shownMinX;
    }

    /**
     * The world y of the low corner of the shown box, meaningful only while hasShownBox.
     * @return the world y, inclusive
     */
    public static int shownMinY() {
        return shownMinY;
    }

    /**
     * The world z of the low corner of the shown box, meaningful only while hasShownBox.
     * @return the world z, inclusive
     */
    public static int shownMinZ() {
        return shownMinZ;
    }

    /**
     * The world x of the high corner of the shown box, meaningful only while hasShownBox.
     * @return the world x, inclusive
     */
    public static int shownMaxX() {
        return shownMaxX;
    }

    /**
     * The world y of the high corner of the shown box, meaningful only while hasShownBox.
     * @return the world y, inclusive
     */
    public static int shownMaxY() {
        return shownMaxY;
    }

    /**
     * The world z of the high corner of the shown box, meaningful only while hasShownBox.
     * @return the world z, inclusive
     */
    public static int shownMaxZ() {
        return shownMaxZ;
    }

    /**
     * Uploads both textures and reads them back to compare them with the field and the heights, for the verify
     * subcommand of the client command. Only a command calls it, so it allocates the readback buffers and its lines.
     * @param field the field to upload and compare
     * @param heights the heights to upload and compare
     * @return the lines to send, in order
     */
    public static List<String> verify(SnowField field, FarHeights heights) {
        List<String> lines = new ArrayList<>();
        try {
            uploadField(field);
            uploadHeights(heights);
            verifyField(field, lines);
            verifyHeights(heights, lines);
        } catch (RuntimeException exception) {
            lines.add("The texture check stopped after an error: " + exception.getMessage());
        }
        return lines;
    }

    /**
     * A line about the uploads of the last second and the state of the two textures, for the client command.
     * @return the line
     */
    public static String describe() {
        return String.format(Locale.ROOT, "Snow field textures in the last second: %d field uploads of %d bricks, "
                        + "%d frames with dirty bricks waiting, %d height uploads of %d tiles, %.3f ms in the uploads; "
                        + "the field texture %s, the height texture %s, the shown box %s",
                lastFieldUploads, lastBricksUploaded, lastWaitedFrames, lastHeightUploads, lastTilesUploaded,
                (lastFieldNanos + lastHeightNanos) / NANOSECONDS_PER_MILLISECOND,
                fieldTexture != 0 ? "exists" : "is not made", heightTexture != 0 ? "exists" : "is not made",
                hasShownBox ? "is not empty" : "is empty");
    }

    /**
     * Rolls the per second statistics of the uploads, called from the roll of ClientSnowField.
     */
    public static void rollStatistics() {
        lastFieldUploads = currentFieldUploads;
        lastBricksUploaded = currentBricksUploaded;
        lastWaitedFrames = currentWaitedFrames;
        lastHeightUploads = currentHeightUploads;
        lastTilesUploaded = currentTilesUploaded;
        lastFieldNanos = currentFieldNanos;
        lastHeightNanos = currentHeightNanos;
        currentFieldUploads = 0;
        currentBricksUploaded = 0;
        currentWaitedFrames = 0;
        currentHeightUploads = 0;
        currentTilesUploaded = 0;
        currentFieldNanos = 0L;
        currentHeightNanos = 0L;
    }

    /**
     * Makes the field texture, allocates the staging buffer of one brick row, and fills every cell with
     * SnowField.UNREAD, which the first drained upload replaces. A field texture that fails to be made is deleted
     * again, so that the next frame does not retry with it.
     */
    private static void createFieldTexture(SnowField field) {
        int size = field.size();
        int stagingCapacity = size * SnowField.BRICK_SIZE * SnowField.BRICK_SIZE;
        ByteBuffer staging = BufferUtils.createByteBuffer(stagingCapacity);
        int bricksPerAxis = size / SnowField.BRICK_SIZE;
        int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int target = GL11.glGenTextures();
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, size);
            GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, SnowField.BRICK_SIZE);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, target);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, size, size, size, 0, GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            for (int index = 0; index < stagingCapacity; index++) {
                staging.put(index, (byte) SnowField.UNREAD);
            }
            for (int brickZ = 0; brickZ < bricksPerAxis; brickZ++) {
                for (int brickY = 0; brickY < bricksPerAxis; brickY++) {
                    GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, brickY * SnowField.BRICK_SIZE,
                            brickZ * SnowField.BRICK_SIZE, size, SnowField.BRICK_SIZE, SnowField.BRICK_SIZE,
                            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, staging);
                    currentFieldUploads++;
                }
            }
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(target);
            throw exception;
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
            restoreUnpackState();
        }
        fieldSize = size;
        fieldStaging = staging;
        fieldTexture = target;
    }

    /**
     * Uploads the runs of dirty bricks of the field, one call of glTexSubImage3D per run, and counts them.
     */
    private static void uploadDirtyBricks(SnowField field) {
        ByteBuffer staging = fieldStaging;
        byte[] distances = field.distances();
        int size = fieldSize;
        int shift = Integer.numberOfTrailingZeros(size);
        int bricksPerAxis = size / SnowField.BRICK_SIZE;
        int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, size);
            GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, SnowField.BRICK_SIZE);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, fieldTexture);
            for (int brickZ = 0; brickZ < bricksPerAxis; brickZ++) {
                for (int brickY = 0; brickY < bricksPerAxis; brickY++) {
                    int firstBrickX = -1;
                    for (int brickX = 0; brickX <= bricksPerAxis; brickX++) {
                        boolean dirty = brickX < bricksPerAxis && field.isBrickDirty(
                                brickX + brickY * bricksPerAxis + brickZ * bricksPerAxis * bricksPerAxis);
                        if (dirty) {
                            if (firstBrickX < 0) {
                                firstBrickX = brickX;
                            }
                        } else if (firstBrickX >= 0) {
                            uploadBrickRun(staging, distances, size, shift, firstBrickX, brickX - 1, brickY, brickZ);
                            firstBrickX = -1;
                        }
                    }
                }
            }
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
            restoreUnpackState();
        }
    }

    /**
     * Copies one run of bricks of a brick row into the staging buffer and uploads it as one region of the texture.
     */
    private static void uploadBrickRun(ByteBuffer staging, byte[] distances, int size, int shift, int firstBrickX,
            int lastBrickX, int brickY, int brickZ) {
        int runLength = lastBrickX - firstBrickX + 1;
        int x0 = firstBrickX * SnowField.BRICK_SIZE;
        int y0 = brickY * SnowField.BRICK_SIZE;
        int z0 = brickZ * SnowField.BRICK_SIZE;
        int bytesPerRow = SnowField.BRICK_SIZE * runLength;
        for (int localY = 0; localY < SnowField.BRICK_SIZE; localY++) {
            for (int localZ = 0; localZ < SnowField.BRICK_SIZE; localZ++) {
                int slot = x0 + ((y0 + localY) << shift) + ((z0 + localZ) << (2 * shift));
                int destination = x0 + localY * size + localZ * size * SnowField.BRICK_SIZE;
                staging.put(destination, distances, slot, bytesPerRow);
            }
        }
        // The run sits at x0 inside each staged row, so the unpack skips x0 bytes of every row:
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, x0);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x0, y0, z0, bytesPerRow, SnowField.BRICK_SIZE,
                SnowField.BRICK_SIZE, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, staging);
        currentFieldUploads++;
        currentBricksUploaded += runLength;
    }

    /**
     * Makes the height texture, which asks for a full upload with its first frame since FarHeights starts with every
     * tile dirty. A texture that fails to be made is deleted again, so that the next frame does not retry with it.
     */
    private static void createHeightTexture() {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int target = GL11.glGenTextures();
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16I, FarHeights.SIZE, FarHeights.SIZE, 0,
                    GL30.GL_RED_INTEGER, GL11.GL_SHORT, (ShortBuffer) null);
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(target);
            throw exception;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            restoreUnpackState();
        }
        heightTexture = target;
    }

    /**
     * Copies one run of tiles of a tile row into the staging buffer and uploads it as one region of the texture. The
     * texel (x, z) of the row goes to the index x + (z & 15) * SIZE of the buffer, which is the layout of one tile
     * row of the texture with row length SIZE.
     */
    private static void uploadTileRun(FarHeights heights, int tileRow, int firstTileX, int lastTileX) {
        int runLength = lastTileX - firstTileX + 1;
        int firstX = firstTileX * TILE_WIDTH;
        int width = TILE_WIDTH * runLength;
        int firstZ = tileRow * TILE_WIDTH;
        short[] heightsArray = heights.heights();
        for (int localZ = 0; localZ < TILE_WIDTH; localZ++) {
            int source = FarHeights.texelOf(firstX, firstZ + localZ);
            heightStaging.put(firstX + localZ * FarHeights.SIZE, heightsArray, source, width);
        }
        // The run sits at firstX inside each staged row, so the unpack skips firstX texels of every row:
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, firstX);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, firstX, firstZ, width, TILE_WIDTH, GL30.GL_RED_INTEGER,
                GL11.GL_SHORT, heightStaging);
        currentHeightUploads++;
        currentTilesUploaded += runLength;
    }

    /**
     * Cuts the shown box down to its intersection with the solved box of the field, which is empty when the field has
     * none, so that a move never shows a cell whose texel left the solved box.
     */
    private static void cutShownBox(SnowField field) {
        if (!hasShownBox) {
            return;
        }
        if (!field.hasSolvedBox()) {
            hasShownBox = false;
            return;
        }
        shownMinX = Math.max(shownMinX, field.solvedMinX());
        shownMinY = Math.max(shownMinY, field.solvedMinY());
        shownMinZ = Math.max(shownMinZ, field.solvedMinZ());
        shownMaxX = Math.min(shownMaxX, field.solvedMaxX());
        shownMaxY = Math.min(shownMaxY, field.solvedMaxY());
        shownMaxZ = Math.min(shownMaxZ, field.solvedMaxZ());
        if (shownMinX > shownMaxX || shownMinY > shownMaxY || shownMinZ > shownMaxZ) {
            hasShownBox = false;
        }
    }

    /**
     * Sets the pixel store back to the values the rest of the game assumes, which every upload does in its finally
     * block.
     */
    private static void restoreUnpackState() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    /**
     * Sets the pack row length, image height and skips to 0 and the pack alignment to the given value, which the
     * readbacks need and which 4 sets back to the values the rest of the game assumes.
     */
    private static void setPackState(int alignment) {
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL12.GL_PACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL12.GL_PACK_SKIP_IMAGES, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, alignment);
    }

    /**
     * Reads the field texture back and compares it with the distances of the field inside the shown box.
     */
    private static void verifyField(SnowField field, List<String> lines) {
        if (!field.isDrained()) {
            lines.add("The field texture is not compared while the solver works");
            return;
        }
        if (!hasShownBox) {
            lines.add("The shown box is empty, so no cell of the field texture is compared");
            return;
        }
        int size = fieldSize;
        int shift = Integer.numberOfTrailingZeros(size);
        int sizeMask = size - 1;
        ByteBuffer readback = MemoryUtil.memAlloc(size * size * size);
        try {
            int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            try {
                setPackState(1);
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, fieldTexture);
                GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, readback);
            } finally {
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
                setPackState(4);
            }
            byte[] distances = field.distances();
            int comparedCount = 0;
            int mismatchCount = 0;
            int firstX = 0;
            int firstY = 0;
            int firstZ = 0;
            int firstTexel = 0;
            int firstExpected = 0;
            int firstActual = 0;
            for (int z = shownMinZ; z <= shownMaxZ; z++) {
                for (int y = shownMinY; y <= shownMaxY; y++) {
                    for (int x = shownMinX; x <= shownMaxX; x++) {
                        int slot = (x & sizeMask) + ((y & sizeMask) << shift) + ((z & sizeMask) << (2 * shift));
                        int expected = distances[slot] & 0xFF;
                        int actual = readback.get(slot) & 0xFF;
                        comparedCount++;
                        if (actual == expected) {
                            continue;
                        }
                        mismatchCount++;
                        if (mismatchCount == 1) {
                            firstX = x;
                            firstY = y;
                            firstZ = z;
                            firstTexel = slot;
                            firstExpected = expected;
                            firstActual = actual;
                        }
                    }
                }
            }
            if (mismatchCount == 0) {
                lines.add("The field texture matches the field in all " + comparedCount + " cells of the shown box");
            } else {
                lines.add("The field texture differs in " + mismatchCount + " of the " + comparedCount + " cells of "
                        + "the shown box, the first at (" + firstX + ", " + firstY + ", " + firstZ + ") in the texel "
                        + firstTexel + " with " + firstActual + " instead of " + firstExpected);
            }
        } finally {
            MemoryUtil.memFree(readback);
        }
    }

    /**
     * Reads the height texture back and compares all of its texels with the heights of the window.
     */
    private static void verifyHeights(FarHeights heights, List<String> lines) {
        int texelCount = FarHeights.SIZE * FarHeights.SIZE;
        ShortBuffer readback = MemoryUtil.memAllocShort(texelCount);
        try {
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                setPackState(2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, heightTexture);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_SHORT, readback);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                setPackState(4);
            }
            short[] expectedHeights = heights.heights();
            int mismatchCount = 0;
            int firstTexel = 0;
            short firstExpected = 0;
            short firstActual = 0;
            for (int texel = 0; texel < texelCount; texel++) {
                short expected = expectedHeights[texel];
                short actual = readback.get(texel);
                if (actual == expected) {
                    continue;
                }
                mismatchCount++;
                if (mismatchCount == 1) {
                    firstTexel = texel;
                    firstExpected = expected;
                    firstActual = actual;
                }
            }
            if (mismatchCount == 0) {
                lines.add("The height texture matches the window in all " + texelCount + " texels");
            } else {
                lines.add("The height texture differs in " + mismatchCount + " of the " + texelCount + " texels, the "
                        + "first at the texel " + firstTexel + " with " + firstActual + " instead of " + firstExpected);
            }
        } finally {
            MemoryUtil.memFree(readback);
        }
    }
}
