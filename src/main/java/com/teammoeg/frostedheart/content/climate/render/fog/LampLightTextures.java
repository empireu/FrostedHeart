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

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The 3D textures of the cascades of a LampLightField, one GL_RG16F texture per cascade with its slots as texels,
 * filtered linearly and repeating on every axis. Each upload sends the marked bricks of every used cascade, the whole
 * texture when at least WHOLE_UPLOAD_SHARE of its bricks are marked and otherwise the box around the marked bricks of
 * each layer of bricks along z, straight from the cascade's values with the unpack state skipping to the box. A
 * texture is made with its whole contents on first use. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class LampLightTextures {
    private static final float WHOLE_UPLOAD_SHARE = 0.25F;
    private static final int BYTES_PER_CELL = LampLightCascade.CHANNELS * Short.BYTES;
    private static final double NANOSECONDS_PER_MILLISECOND = 1.0e6;
    private static final double BYTES_PER_KIBIBYTE = 1024.0;

    private static final int[] textures = new int[LampLightField.CASCADE_COUNT];
    private static final int[] planeLowX = new int[LampLightField.ACROSS];
    private static final int[] planeHighX = new int[LampLightField.ACROSS];
    private static final int[] planeLowY = new int[LampLightField.ACROSS];
    private static final int[] planeHighY = new int[LampLightField.ACROSS];

    private static int currentUploads;
    private static long currentBytes;
    private static long currentNanos;
    private static int lastUploads;
    private static long lastBytes;
    private static long lastNanos;

    private LampLightTextures() {
    }

    /**
     * The texture of a cascade, 0 before its first upload.
     */
    public static int texture(int cascade) {
        return textures[cascade];
    }

    /**
     * Uploads the marked bricks of every used cascade of the field. The 3D texture bound to the active unit before
     * stays bound, and the unpack state is set back to its defaults.
     */
    public static void upload(LampLightField field) {
        long start = System.nanoTime();
        int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        try {
            for (int index = 0; index < field.activeCount(); index++) {
                LampLightCascade cascade = field.cascade(index);
                if (textures[index] == 0) {
                    create(index, cascade);
                    cascade.clearDirty();
                    continue;
                }
                int dirty = cascade.dirtyCount();
                if (dirty == 0) {
                    continue;
                }
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, textures[index]);
                if (dirty >= cascade.brickCount() * WHOLE_UPLOAD_SHARE) {
                    uploadBox(cascade, 0, 0, 0, cascade.sizeX(), cascade.sizeY(), cascade.sizeZ());
                } else {
                    uploadPlanes(cascade);
                }
                cascade.clearDirty();
            }
        } finally {
            resetUnpack();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
        }
        currentNanos += System.nanoTime() - start;
    }

    private static void create(int index, LampLightCascade cascade) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        resetUnpack();
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG16F, cascade.sizeX(), cascade.sizeY(), cascade.sizeZ(), 0,
                GL30.GL_RG, GL30.GL_HALF_FLOAT, cascade.values());
        textures[index] = texture;
        currentUploads++;
        currentBytes += (long) cascade.sizeX() * cascade.sizeY() * cascade.sizeZ() * BYTES_PER_CELL;
    }

    /**
     * Uploads, for each layer of bricks along z with a marked brick, the box of bricks around its marked ones.
     */
    private static void uploadPlanes(LampLightCascade cascade) {
        int bricksX = cascade.bricksX();
        int bricksY = cascade.bricksY();
        int bricksZ = cascade.bricksZ();
        for (int plane = 0; plane < bricksZ; plane++) {
            planeLowX[plane] = Integer.MAX_VALUE;
            planeHighX[plane] = -1;
            planeLowY[plane] = Integer.MAX_VALUE;
            planeHighY[plane] = -1;
        }
        for (int word = 0; word < cascade.dirtyWordCount(); word++) {
            long bits = cascade.dirtyWord(word);
            while (bits != 0L) {
                int brick = word * Long.SIZE + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                int brickX = brick % bricksX;
                int brickY = brick / bricksX % bricksY;
                int plane = brick / (bricksX * bricksY);
                planeLowX[plane] = Math.min(planeLowX[plane], brickX);
                planeHighX[plane] = Math.max(planeHighX[plane], brickX);
                planeLowY[plane] = Math.min(planeLowY[plane], brickY);
                planeHighY[plane] = Math.max(planeHighY[plane], brickY);
            }
        }
        int brickWidth = cascade.brickWidth();
        int brickHeight = cascade.brickHeight();
        for (int plane = 0; plane < bricksZ; plane++) {
            if (planeHighX[plane] < 0) {
                continue;
            }
            uploadBox(cascade, planeLowX[plane] * brickWidth, planeLowY[plane] * brickHeight, plane * brickWidth,
                    (planeHighX[plane] + 1) * brickWidth, (planeHighY[plane] + 1) * brickHeight,
                    (plane + 1) * brickWidth);
        }
    }

    /**
     * Uploads a box of slots given by its lowest and past-highest coordinates into the bound texture.
     */
    private static void uploadBox(LampLightCascade cascade, int lowX, int lowY, int lowZ, int highX, int highY,
            int highZ) {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, cascade.sizeX());
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, cascade.sizeY());
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, lowX);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, lowY);
        GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, lowZ);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, lowX, lowY, lowZ, highX - lowX, highY - lowY, highZ - lowZ,
                GL30.GL_RG, GL30.GL_HALF_FLOAT, cascade.values());
        currentUploads++;
        currentBytes += (long) (highX - lowX) * (highY - lowY) * (highZ - lowZ) * BYTES_PER_CELL;
    }

    private static void resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    /**
     * Uploads the field's marked bricks and reads every used cascade's texture back to compare it with the cascade,
     * for the verify subcommand of the client command. Only a command calls it, so it allocates its readback buffers
     * and lines.
     * @return the lines to send, in order
     */
    public static List<String> verify(LampLightField field) {
        List<String> lines = new ArrayList<>();
        try {
            upload(field);
            int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            try {
                for (int index = 0; index < field.activeCount(); index++) {
                    lines.add(verifyCascade(index, field.cascade(index)));
                }
            } finally {
                GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
            }
        } catch (RuntimeException exception) {
            lines.add("The lamp light texture check stopped after an error: " + exception.getMessage());
        }
        return lines;
    }

    private static String verifyCascade(int index, LampLightCascade cascade) {
        int texture = textures[index];
        if (texture == 0) {
            return String.format(Locale.ROOT, "Lamp light cascade %d: no texture", index);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        int width = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_HEIGHT);
        int depth = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH);
        int format = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if (width != cascade.sizeX() || height != cascade.sizeY() || depth != cascade.sizeZ()
                || format != GL30.GL_RG16F) {
            return String.format(Locale.ROOT, "Lamp light cascade %d: the texture is %dx%dx%d in format 0x%x instead "
                    + "of %dx%dx%d in RG16F", index, width, height, depth, format, cascade.sizeX(), cascade.sizeY(),
                    cascade.sizeZ());
        }
        ShortBuffer values = cascade.values();
        ShortBuffer readback = BufferUtils.createShortBuffer(values.capacity());
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG, GL30.GL_HALF_FLOAT, readback);
        int cells = values.capacity() / LampLightCascade.CHANNELS;
        int differing = 0;
        int firstDiffering = -1;
        int litCells = 0;
        for (int cell = 0; cell < cells; cell++) {
            int slot = cell * LampLightCascade.CHANNELS;
            if (values.get(slot) != 0) {
                litCells++;
            }
            if (values.get(slot) != readback.get(slot) || values.get(slot + 1) != readback.get(slot + 1)) {
                if (firstDiffering < 0) {
                    firstDiffering = cell;
                }
                differing++;
            }
        }
        String first = "";
        if (firstDiffering >= 0) {
            int slot = firstDiffering * LampLightCascade.CHANNELS;
            first = String.format(Locale.ROOT, ", first at slot %d %d %d holds %.6f %.6f instead of %.6f %.6f",
                    firstDiffering % cascade.sizeX(), firstDiffering / cascade.sizeX() % cascade.sizeY(),
                    firstDiffering / (cascade.sizeX() * cascade.sizeY()),
                    LampLightField.fromHalf(readback.get(slot)), LampLightField.fromHalf(readback.get(slot + 1)),
                    LampLightField.fromHalf(values.get(slot)), LampLightField.fromHalf(values.get(slot + 1)));
        }
        return String.format(Locale.ROOT, "Lamp light cascade %d: %dx%dx%d cells of %d by %d blocks, %d lit, %d differ "
                        + "from the texture%s", index, cascade.sizeX(), cascade.sizeY(), cascade.sizeZ(),
                cascade.cellWidth(), cascade.cellHeight(), litCells, differing, first);
    }

    /**
     * Rolls the per second statistics, called from the roll of ClientLampLight.
     */
    static void rollStatistics() {
        lastUploads = currentUploads;
        lastBytes = currentBytes;
        lastNanos = currentNanos;
        currentUploads = 0;
        currentBytes = 0L;
        currentNanos = 0L;
    }

    /**
     * A line about the uploads of the last second, for the client command.
     */
    public static String describe() {
        int made = 0;
        for (int texture : textures) {
            if (texture != 0) {
                made++;
            }
        }
        return String.format(Locale.ROOT, "Lamp light textures in the last second: %d uploads of %.1f KiB, %.3f ms; "
                + "%d of %d made", lastUploads, lastBytes / BYTES_PER_KIBIBYTE, lastNanos / NANOSECONDS_PER_MILLISECOND,
                made, LampLightField.CASCADE_COUNT);
    }
}
