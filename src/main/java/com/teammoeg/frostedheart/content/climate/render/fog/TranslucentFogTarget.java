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

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Offscreen framebuffer that captures Embeddium's translucent terrain pass separately from the main target so opaque
 * fog and behind-translucent snowfall can use opaque-only depth while foreground snowfall and later passes receive the
 * combined depth. Uses an RGBA16F color attachment to preserve low-alpha premultiplied color precision and a depth
 * attachment whose internal format matches the main target for exact depth blits. Allocates nothing once warm.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class TranslucentFogTarget {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FloatBuffer ZERO_CLEAR = BufferUtils.createFloatBuffer(4);

    static {
        ZERO_CLEAR.put(0.0F).put(0.0F).put(0.0F).put(0.0F).flip();
    }

    private int framebuffer;
    private int colorTexture;
    private int depthTexture;
    private int width;
    private int height;
    private int depthInternalFormat;
    private boolean failed;

    /**
     * Ensures storage matches the main target, blits opaque depth into the translucent target, clears translucent
     * color to zero, and leaves the translucent framebuffer bound as GL_DRAW_FRAMEBUFFER.
     * @return whether capture started; false leaves the previous draw framebuffer bound
     */
    boolean beginCapture(RenderTarget mainTarget) {
        if (failed || mainTarget.width <= 0 || mainTarget.height <= 0 || mainTarget.getDepthTextureId() <= 0) {
            return false;
        }
        int mainDepthFormat = queryDepthInternalFormat(mainTarget.getDepthTextureId());
        if (!ensureAllocated(mainTarget.width, mainTarget.height, mainDepthFormat)) {
            return false;
        }
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (scissor) {
                RenderSystem.disableScissor();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glBlitFramebuffer(0, 0, mainTarget.width, mainTarget.height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO_CLEAR);
            return true;
        } catch (RuntimeException exception) {
            fail("The translucent fog target could not start capture, so translucent blocks draw straight into the "
                    + "main target for the rest of the session.", exception);
            return false;
        } finally {
            if (scissor) {
                GlStateManager._enableScissorTest();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        }
    }

    /**
     * Copies the combined opaque + translucent depth back to the main target so particles, clouds, foreground snow,
     * and later effects test against visible translucent blocks without zero-alpha padding holes.
     */
    void copyDepthToMain(RenderTarget mainTarget) {
        if (failed || framebuffer == 0 || mainTarget.width != width || mainTarget.height != height) {
            return;
        }
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (scissor) {
                RenderSystem.disableScissor();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, mainTarget.width, mainTarget.height,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            if (scissor) {
                GlStateManager._enableScissorTest();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }

    int colorTexture() {
        return colorTexture;
    }

    int depthTexture() {
        return depthTexture;
    }

    void clearFailure() {
        failed = false;
    }

    private boolean ensureAllocated(int targetWidth, int targetHeight, int targetDepthFormat) {
        boolean allocate = framebuffer == 0;
        boolean resize = allocate || targetWidth != width || targetHeight != height
                || targetDepthFormat != depthInternalFormat;
        if (!resize) {
            return true;
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            if (allocate) {
                framebuffer = GL30.glGenFramebuffers();
                colorTexture = GL11.glGenTextures();
                depthTexture = GL11.glGenTextures();
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
            if (allocate) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, targetWidth, targetHeight, 0, GL11.GL_RGBA,
                    GL11.GL_FLOAT, (FloatBuffer) null);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            if (allocate) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            }
            int pixelFormat = depthPixelFormat(targetDepthFormat);
            int pixelType = depthPixelType(targetDepthFormat);
            int attachment = depthAttachment(targetDepthFormat);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, targetDepthFormat, targetWidth, targetHeight, 0, pixelFormat,
                    pixelType, (ByteBuffer) null);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    colorTexture, 0);
            // Clear any prior depth or depth-stencil attachment before attaching the new format:
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0,
                    0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, depthTexture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("The weather fog translucent framebuffer is incomplete with status "
                        + status);
            }
            width = targetWidth;
            height = targetHeight;
            depthInternalFormat = targetDepthFormat;
            return true;
        } catch (RuntimeException exception) {
            fail("The translucent fog target cannot be made, so translucent blocks draw straight into the main target "
                    + "for the rest of the session.", exception);
            return false;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        }
    }

    private void fail(String message, RuntimeException exception) {
        delete();
        failed = true;
        LOGGER.warn(message, exception);
    }

    private void delete() {
        if (colorTexture != 0) {
            GL11.glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (depthTexture != 0) {
            GL11.glDeleteTextures(depthTexture);
            depthTexture = 0;
        }
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        width = 0;
        height = 0;
        depthInternalFormat = 0;
    }

    private static int queryDepthInternalFormat(int depthTextureId) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTextureId);
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            return format != 0 ? format : GL14.GL_DEPTH_COMPONENT24;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    private static int depthPixelFormat(int internalFormat) {
        if (internalFormat == GL30.GL_DEPTH24_STENCIL8 || internalFormat == GL30.GL_DEPTH32F_STENCIL8
                || internalFormat == GL30.GL_DEPTH_STENCIL) {
            return GL30.GL_DEPTH_STENCIL;
        }
        return GL11.GL_DEPTH_COMPONENT;
    }

    private static int depthPixelType(int internalFormat) {
        if (internalFormat == GL30.GL_DEPTH32F_STENCIL8) {
            return GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
        }
        if (internalFormat == GL30.GL_DEPTH24_STENCIL8 || internalFormat == GL30.GL_DEPTH_STENCIL) {
            return GL30.GL_UNSIGNED_INT_24_8;
        }
        if (internalFormat == GL30.GL_DEPTH_COMPONENT32F) {
            return GL11.GL_FLOAT;
        }
        if (internalFormat == GL14.GL_DEPTH_COMPONENT16) {
            return GL11.GL_UNSIGNED_SHORT;
        }
        return GL11.GL_UNSIGNED_INT;
    }

    private static int depthAttachment(int internalFormat) {
        if (internalFormat == GL30.GL_DEPTH24_STENCIL8 || internalFormat == GL30.GL_DEPTH32F_STENCIL8
                || internalFormat == GL30.GL_DEPTH_STENCIL) {
            return GL30.GL_DEPTH_STENCIL_ATTACHMENT;
        }
        return GL30.GL_DEPTH_ATTACHMENT;
    }
}
