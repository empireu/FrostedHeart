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

import com.lowdragmc.lowdraglib.client.shader.management.Shader;
import com.lowdragmc.lowdraglib.client.shader.management.ShaderProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.sky.SkyGlPass;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Offscreen framebuffer that captures Embeddium's translucent terrain pass separately from the main target so opaque
 * fog and behind-translucent snowfall can use opaque-only depth while foreground snowfall and later passes receive the
 * combined depth. Uses an RGBA16F color attachment to preserve low-alpha premultiplied color precision and a depth-only
 * attachment whose depth precision matches the main target, initialized via a full screen depth-copy shader instead of
 * glBlitFramebuffer. Allocates nothing once warm. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
final class TranslucentFogTarget {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FloatBuffer ZERO_CLEAR = BufferUtils.createFloatBuffer(4);
    private static final ByteBuffer COLOR_MASK_BUFFER = BufferUtils.createByteBuffer(4);
    private static final IntBuffer VIEWPORT_BUFFER = BufferUtils.createIntBuffer(16);

    static {
        ZERO_CLEAR.put(0.0F).put(0.0F).put(0.0F).put(0.0F).flip();
    }

    private int framebuffer;
    private int colorTexture;
    private int depthTexture;
    private int width;
    private int height;
    private int depthInternalFormat;
    private int sourceDepthTextureId;
    @Nullable
    private ShaderProgram depthCopyProgram;
    @Nullable
    private Shader depthCopyFragment;
    private boolean failed;

    /**
     * Ensures storage matches the main target, clears translucent color to zero, copies opaque depth into the
     * translucent target via a full screen shader, and leaves the translucent framebuffer bound as GL_DRAW_FRAMEBUFFER.
     * @return whether capture started; false leaves the previous draw framebuffer bound
     */
    boolean beginCapture(RenderTarget mainTarget) {
        int mainDepthTextureId = mainTarget.getDepthTextureId();
        if (failed || mainTarget.width <= 0 || mainTarget.height <= 0 || mainDepthTextureId <= 0) {
            return false;
        }
        if (framebuffer == 0 || mainTarget.width != width || mainTarget.height != height
                || mainDepthTextureId != sourceDepthTextureId) {
            int mainDepthFormat = depthStorageFormat(queryDepthInternalFormat(mainDepthTextureId));
            if (!ensureAllocated(mainTarget.width, mainTarget.height, mainDepthTextureId, mainDepthFormat)) {
                return false;
            }
        }
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthWrites = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        COLOR_MASK_BUFFER.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, COLOR_MASK_BUFFER);
        boolean maskRed = COLOR_MASK_BUFFER.get(0) != 0;
        boolean maskGreen = COLOR_MASK_BUFFER.get(1) != 0;
        boolean maskBlue = COLOR_MASK_BUFFER.get(2) != 0;
        boolean maskAlpha = COLOR_MASK_BUFFER.get(3) != 0;
        VIEWPORT_BUFFER.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT_BUFFER);
        int viewportX = VIEWPORT_BUFFER.get(0);
        int viewportY = VIEWPORT_BUFFER.get(1);
        int viewportWidth = VIEWPORT_BUFFER.get(2);
        int viewportHeight = VIEWPORT_BUFFER.get(3);
        boolean succeeded = false;
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            RenderSystem.viewport(0, 0, width, height);
            RenderSystem.disableScissor();
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            RenderSystem.colorMask(true, true, true, true);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO_CLEAR);
            GL11.glDrawBuffer(GL11.GL_NONE);
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.depthMask(true);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(mainDepthTextureId);
            useDepthCopyProgram();
            SkyGlPass.drawFullScreenQuad();
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            succeeded = true;
            return true;
        } catch (RuntimeException exception) {
            fail("The translucent fog target could not start capture, so translucent blocks draw straight into the "
                    + "main target for the rest of the session.", exception);
            return false;
        } finally {
            GL20.glUseProgram(previousProgram);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(previousTexture0);
            RenderSystem.activeTexture(previousActiveTexture);
            RenderSystem.colorMask(maskRed, maskGreen, maskBlue, maskAlpha);
            RenderSystem.depthMask(depthWrites);
            RenderSystem.depthFunc(depthFunction);
            if (depthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            if (blending) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (culling) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            if (scissor) {
                GlStateManager._enableScissorTest();
            } else {
                RenderSystem.disableScissor();
            }
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            if (!succeeded) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            }
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

    private void useDepthCopyProgram() {
        Shader newFragment = FHShaders.getWeatherFogDepthCopy();
        if (depthCopyProgram == null || depthCopyFragment != newFragment) {
            if (depthCopyProgram != null) {
                depthCopyProgram.delete();
                depthCopyProgram = null;
            }
            ShaderProgram created = new ShaderProgram();
            try {
                created.attach(FHShaders.IMAGE_V).attach(newFragment);
            } catch (RuntimeException exception) {
                created.delete();
                throw exception;
            }
            depthCopyProgram = created;
            depthCopyFragment = newFragment;
            depthCopyProgram.use();
            GL20.glUniform1i(GL20.glGetUniformLocation(depthCopyProgram.programId, "sceneDepth"), 0);
        }
        depthCopyProgram.use();
    }

    private boolean ensureAllocated(int targetWidth, int targetHeight, int mainDepthTextureId, int targetDepthFormat) {
        boolean allocate = framebuffer == 0;
        boolean resize = allocate || targetWidth != width || targetHeight != height
                || targetDepthFormat != depthInternalFormat;
        if (!resize) {
            sourceDepthTextureId = mainDepthTextureId;
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
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, targetDepthFormat, targetWidth, targetHeight, 0,
                    GL11.GL_DEPTH_COMPONENT, depthPixelType(targetDepthFormat), (ByteBuffer) null);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    colorTexture, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
                    depthTexture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("The weather fog translucent framebuffer is incomplete with status "
                        + status);
            }
            width = targetWidth;
            height = targetHeight;
            depthInternalFormat = targetDepthFormat;
            sourceDepthTextureId = mainDepthTextureId;
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
        if (depthCopyProgram != null) {
            depthCopyProgram.delete();
            depthCopyProgram = null;
            depthCopyFragment = null;
        }
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
        sourceDepthTextureId = 0;
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

    private static int depthStorageFormat(int internalFormat) {
        if (internalFormat == GL30.GL_DEPTH32F_STENCIL8 || internalFormat == GL30.GL_DEPTH_COMPONENT32F) {
            return GL30.GL_DEPTH_COMPONENT32F;
        }
        if (internalFormat == GL14.GL_DEPTH_COMPONENT16) {
            return GL14.GL_DEPTH_COMPONENT16;
        }
        return GL14.GL_DEPTH_COMPONENT24;
    }

    private static int depthPixelType(int internalFormat) {
        if (internalFormat == GL30.GL_DEPTH_COMPONENT32F) {
            return GL11.GL_FLOAT;
        }
        if (internalFormat == GL14.GL_DEPTH_COMPONENT16) {
            return GL11.GL_UNSIGNED_SHORT;
        }
        return GL11.GL_UNSIGNED_INT;
    }
}
