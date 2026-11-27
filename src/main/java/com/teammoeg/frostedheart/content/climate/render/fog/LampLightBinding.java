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

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;

/**
 * Binds the cascades of the lamp light field for a program that reads lamp_light.glsl with LAMP_LIGHT_FIELD defined,
 * on CASCADE_COUNT units from a first unit, and sets the field's uniforms on it. Each program keeps its own binding,
 * which finds the uniforms again whenever the program is made again. A program bound without a field reads dark open
 * air. The cascade textures are bound straight through GL, since GlStateManager tracks only the 2D textures of its
 * first units. Allocates nothing. Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class LampLightBinding {
    private static final int CASCADES = LampLightField.CASCADE_COUNT;
    private static final String[] SAMPLERS = {"lampCascade0", "lampCascade1", "lampCascade2", "lampCascade3",
            "lampCascade4"};

    private final int firstUnit;
    private final int[] previousTextures = new int[CASCADES];
    private final float[] sizes = new float[3 * CASCADES];
    private final float[] slotOrigins = new float[3 * CASCADES];
    private final float[] eyesInCascade = new float[3 * CASCADES];
    private final float[] cellsPerBlock = new float[3 * CASCADES];
    private final float[] inverseReaches = new float[3 * CASCADES];
    private final FloatBuffer vectorBuffer = BufferUtils.createFloatBuffer(3 * CASCADES);
    private int countUniform = -1;
    private int fadeUniform = -1;
    private int sizeUniform = -1;
    private int slotOriginUniform = -1;
    private int eyeInCascadeUniform = -1;
    private int cellsPerBlockUniform = -1;
    private int inverseReachUniform = -1;

    public LampLightBinding(int firstUnit) {
        this.firstUnit = firstUnit;
    }

    /**
     * Points the cascade samplers of the program in use at their units and finds its uniforms, once the program is
     * linked.
     */
    public void locate(int programId) {
        for (int index = 0; index < CASCADES; index++) {
            GL20.glUniform1i(GL20.glGetUniformLocation(programId, SAMPLERS[index]), firstUnit + index);
        }
        countUniform = GL20.glGetUniformLocation(programId, "lampCascadeCount");
        fadeUniform = GL20.glGetUniformLocation(programId, "lampFieldFade");
        sizeUniform = GL20.glGetUniformLocation(programId, "lampCascadeSize");
        slotOriginUniform = GL20.glGetUniformLocation(programId, "lampSlotOrigin");
        eyeInCascadeUniform = GL20.glGetUniformLocation(programId, "lampEyeInCascade");
        cellsPerBlockUniform = GL20.glGetUniformLocation(programId, "lampCellsPerBlock");
        inverseReachUniform = GL20.glGetUniformLocation(programId, "lampInverseReach");
    }

    /**
     * Remembers the 3D textures bound on the units, leaving the last unit active.
     */
    public void save() {
        for (int index = 0; index < CASCADES; index++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + firstUnit + index);
            previousTextures[index] = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        }
    }

    /**
     * Uploads the changes of a field and binds its cascades, or no texture without a field, and sets the uniforms on
     * the program in use for the camera with the field faded in by fade. Leaves the last unit active.
     */
    public void bind(@Nullable LampLightField field, Vec3 camera, float fade) {
        bind(field, camera.x, camera.y, camera.z, fade);
    }

    /**
     * Binds as bind does, for an eye given by its coordinates.
     */
    public void bind(@Nullable LampLightField field, double eyeX, double eyeY, double eyeZ, float fade) {
        int count = 0;
        if (field != null) {
            LampLightTextures.upload(field);
            count = field.shaderUniforms(eyeX, eyeY, eyeZ, sizes, slotOrigins, eyesInCascade, cellsPerBlock,
                    inverseReaches);
        }
        for (int index = 0; index < CASCADES; index++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + firstUnit + index);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, index < count ? LampLightTextures.texture(index) : 0);
        }
        GL20.glUniform1i(countUniform, count);
        GL20.glUniform1f(fadeUniform, count > 0 ? fade : 0.0F);
        if (count > 0) {
            setVectors(sizeUniform, sizes);
            setVectors(slotOriginUniform, slotOrigins);
            setVectors(eyeInCascadeUniform, eyesInCascade);
            setVectors(cellsPerBlockUniform, cellsPerBlock);
            setVectors(inverseReachUniform, inverseReaches);
        }
    }

    /**
     * Binds the remembered 3D textures on the units again, leaving the last unit active.
     */
    public void restore() {
        for (int index = 0; index < CASCADES; index++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + firstUnit + index);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousTextures[index]);
        }
    }

    private void setVectors(int location, float[] values) {
        vectorBuffer.clear();
        vectorBuffer.put(values);
        vectorBuffer.flip();
        GL20.glUniform3fv(location, vectorBuffer);
    }
}
