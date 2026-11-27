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
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/**
 * The debug view of the lamp light field, which draws one cascade of it after the weather as a dot at the center of
 * each cell whose outdoor air is lit, colored by its level from blue for dim to amber for full block light, hidden by
 * the scene's depth. The lamp_light_debug.glsl stages read the cells straight from the cascade's texture, so the view
 * shows what the GPU holds. An error turns the view off until it is chosen again.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LampLightDebugView {
    public static final int OFF = -1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CASCADE_UNIT = 0;

    private static final Matrix4f viewProjection = new Matrix4f();
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(16);
    private static final Program program = new Program();
    private static int shownCascade = OFF;
    private static int vertexArray;

    private LampLightDebugView() {
    }

    /**
     * Shows a cascade from the next frame on, or nothing for OFF.
     * @return a line saying what the view shows from now on
     */
    public static String show(int cascade) {
        shownCascade = cascade >= 0 && cascade < LampLightField.CASCADE_COUNT ? cascade : OFF;
        if (shownCascade == OFF) {
            return "Lamp light debug view off";
        }
        return String.format(Locale.ROOT, "Lamp light debug view of cascade %d: a dot at each lit cell of %d by %d "
                + "blocks, from blue for dim to amber for full block light, while the field uses the cascade",
                shownCascade, LampLightField.cellWidth(shownCascade), LampLightField.cellHeight(shownCascade));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || shownCascade == OFF) {
            return;
        }
        LampLightField field = ClientLampLight.field(Minecraft.getInstance().level);
        if (field == null || shownCascade >= field.activeCount() || !field.cascade(shownCascade).isPlaced()) {
            return;
        }
        try {
            render(field, event);
        } catch (RuntimeException exception) {
            shownCascade = OFF;
            LOGGER.error("The lamp light debug view stopped after an error.", exception);
        }
    }

    private static void render(LampLightField field, RenderLevelStageEvent event) {
        LampLightCascade cascade = field.cascade(shownCascade);
        int activeTexture = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + CASCADE_UNIT);
        int previousTexture = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthWrites = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean programPointSize = GL11.glIsEnabled(GL32.GL_PROGRAM_POINT_SIZE);
        viewportBuffer.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
        int viewportX = viewportBuffer.get(0);
        int viewportY = viewportBuffer.get(1);
        int viewportWidth = viewportBuffer.get(2);
        int viewportHeight = viewportBuffer.get(3);
        try {
            LampLightTextures.upload(field);
            int texture = LampLightTextures.texture(shownCascade);
            if (texture == 0) {
                return;
            }
            RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainTarget.frameBufferId);
            RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            viewProjection.set(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
            float pixelsPerBlock = event.getProjectionMatrix().m11() * mainTarget.height * 0.5F;
            program.use();
            program.setUniforms(cascade, event.getCamera().getPosition(), pixelsPerBlock);
            if (vertexArray == 0) {
                vertexArray = GL30.glGenVertexArrays();
            }
            GL30.glBindVertexArray(vertexArray);
            GL11.glDrawArrays(GL11.GL_POINTS, 0, cascade.sizeX() * cascade.sizeY() * cascade.sizeZ());
        } finally {
            GL30.glBindVertexArray(previousVertexArray);
            BufferUploader.invalidate();
            GL20.glUseProgram(previousProgram);
            if (!programPointSize) {
                GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);
            }
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
            RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTexture);
            RenderSystem.activeTexture(activeTexture);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
    }

    /**
     * The program of the view, made from the lamp light debug stages on first use and made again when a resource reload
     * replaced them.
     */
    private static final class Program {
        @Nullable
        private ShaderProgram program;
        @Nullable
        private Shader vertex;
        @Nullable
        private Shader fragment;
        private int viewProjectionUniform;
        private int sizeUniform;
        private int slotOriginUniform;
        private int originFromCameraUniform;
        private int cellSizeUniform;
        private int pixelsPerBlockUniform;

        private void use() {
            Shader newVertex = FHShaders.getLampLightDebugVertex();
            Shader newFragment = FHShaders.getLampLightDebugFragment();
            if (program == null || vertex != newVertex || fragment != newFragment) {
                if (program != null) {
                    program.delete();
                    program = null;
                }
                ShaderProgram created = new ShaderProgram();
                try {
                    created.attach(newVertex).attach(newFragment);
                } catch (RuntimeException exception) {
                    created.delete();
                    throw exception;
                }
                program = created;
                vertex = newVertex;
                fragment = newFragment;
                program.use();
                int programId = program.programId;
                GL20.glUniform1i(GL20.glGetUniformLocation(programId, "lampDebugCascade"), CASCADE_UNIT);
                viewProjectionUniform = GL20.glGetUniformLocation(programId, "viewProjection");
                sizeUniform = GL20.glGetUniformLocation(programId, "debugSize");
                slotOriginUniform = GL20.glGetUniformLocation(programId, "debugSlotOrigin");
                originFromCameraUniform = GL20.glGetUniformLocation(programId, "debugOriginFromCamera");
                cellSizeUniform = GL20.glGetUniformLocation(programId, "debugCellSize");
                pixelsPerBlockUniform = GL20.glGetUniformLocation(programId, "pixelsPerBlock");
            }
            program.use();
        }

        private void setUniforms(LampLightCascade cascade, Vec3 camera, float pixelsPerBlock) {
            viewProjection.get(matrixBuffer);
            GL20.glUniformMatrix4fv(viewProjectionUniform, false, matrixBuffer);
            GL20.glUniform3i(sizeUniform, cascade.sizeX(), cascade.sizeY(), cascade.sizeZ());
            GL20.glUniform3i(slotOriginUniform, cascade.slotX(cascade.originX()), cascade.slotY(cascade.originY()),
                    cascade.slotZ(cascade.originZ()));
            int width = cascade.cellWidth();
            int height = cascade.cellHeight();
            GL20.glUniform3f(originFromCameraUniform, (float) ((double) cascade.originX() * width - camera.x),
                    (float) ((double) cascade.originY() * height - camera.y),
                    (float) ((double) cascade.originZ() * width - camera.z));
            GL20.glUniform3f(cellSizeUniform, width, height, width);
            GL20.glUniform1f(pixelsPerBlockUniform, pixelsPerBlock);
        }
    }
}
