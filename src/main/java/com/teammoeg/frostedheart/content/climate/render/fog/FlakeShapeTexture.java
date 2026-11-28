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
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * The shapes of the snowflakes as an R8 2D array texture with mip levels, one layer per shape, and the size of each
 * shape's tile, as SnowFlakes.shapeTileScale gives it.
 * The shapes are generated once per session on a background thread, starting with the first frame that draws snow,
 * and uploaded on the render thread once they are ready. Until then, and for good if they cannot be made, every tile
 * scale is 0, so the flakes draw as round dots.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class FlakeShapeTexture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FloatBuffer tileScales = BufferUtils.createFloatBuffer(SnowFlakes.SHAPE_COUNT);
    @Nullable
    private static CompletableFuture<FlakeShapeSet> generation;
    private static int texture;
    private static float maxTileScale;
    private static boolean failed;

    private FlakeShapeTexture() {
    }

    /**
     * Starts the generation on first use, and uploads the shapes in the first call after it finished. Binds the 2D
     * array texture of the active unit back after an upload.
     */
    static void prepare() {
        if (texture != 0 || failed) {
            return;
        }
        if (generation == null) {
            generation = CompletableFuture.supplyAsync(
                    () -> FlakeShapeGenerator.generate(FlakeShapeGenerator.DEFAULT_SEED), Util.backgroundExecutor());
            return;
        }
        if (!generation.isDone()) {
            return;
        }
        FlakeShapeSet shapes;
        try {
            shapes = generation.join();
        } catch (RuntimeException exception) {
            failed = true;
            LOGGER.error("The snowflake shapes cannot be generated, so the flakes draw as round dots.", exception);
            return;
        } finally {
            generation = null;
        }
        if (shapes.count() != SnowFlakes.SHAPE_COUNT || shapes.size() != SnowFlakes.SHAPE_SIZE) {
            failed = true;
            LOGGER.error("The snowflake shapes came out as {} tiles of {} pixels instead of {} of {}, so the flakes "
                    + "draw as round dots.", shapes.count(), shapes.size(), SnowFlakes.SHAPE_COUNT,
                    SnowFlakes.SHAPE_SIZE);
            return;
        }
        upload(shapes);
    }

    /**
     * The texture, or 0 before the shapes are uploaded.
     */
    static int texture() {
        return texture;
    }

    /**
     * The half side of each shape's tile in flake radii, all 0 before the shapes are uploaded, positioned at 0.
     */
    static FloatBuffer tileScales() {
        tileScales.rewind();
        return tileScales;
    }

    /**
     * The largest half side in flake radii across all uploaded shapes, or 0 before the shapes are uploaded.
     */
    static float maxTileScale() {
        return maxTileScale;
    }

    /**
     * A few words about the state of the shapes, for the client command.
     */
    static String describe() {
        if (failed) {
            return "flake shapes failed, round dots";
        }
        if (texture == 0) {
            return generation != null ? "flake shapes generating" : "flake shapes not started";
        }
        return SnowFlakes.SHAPE_COUNT + " flake shapes";
    }

    private static void upload(FlakeShapeSet shapes) {
        int size = shapes.size();
        int count = shapes.count();
        ByteBuffer pixels = BufferUtils.createByteBuffer(shapes.data().length);
        pixels.put(shapes.data()).flip();
        int previousTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int created = GL11.glGenTextures();
        try {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, created);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_R8, size, size, count, 0, GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE, pixels);
            GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(created);
            failed = true;
            LOGGER.error("The snowflake shapes cannot be uploaded, so the flakes draw as round dots.", exception);
            return;
        } finally {
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousTexture);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
        texture = created;
        float largest = 0.0F;
        for (int shape = 0; shape < count; shape++) {
            float scale = (float) SnowFlakes.shapeTileScale(shapes.coverage(shape));
            tileScales.put(shape, scale);
            if (scale > largest) {
                largest = scale;
            }
        }
        maxTileScale = largest;
    }
}
