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
import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Chunk shader interface for Embeddium's translucent pass that binds the weather fog froxel excesses and uploads the
 * frame's fog uniforms when WeatherFogRenderer is active for translucent terrain. Allocates nothing per frame.
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class TranslucentFogChunkShaderInterface extends ChunkShaderInterface {
    private final GlUniformFloat3v fogEyeOffset;
    private final GlUniformInt weatherFogActive;
    private final GlUniformFloat3v snowColor;
    private final GlUniformFloat eyeExtinction;
    private final GlUniformFloat heightScale;
    private final GlUniformInt excessesSampler;
    private final GlUniformInt columnCount;
    private final GlUniformInt rowCount;
    private final GlUniformInt sliceCount;
    private final GlUniformFloat rangeDistance;
    private final GlUniformInt divisor;
    private boolean boundExcess;
    private int previousExcessTexture;

    public TranslucentFogChunkShaderInterface(ShaderBindingContext context, ChunkShaderOptions options) {
        super(context, options);
        fogEyeOffset = context.bindUniform("fhFogEyeOffset", GlUniformFloat3v::new);
        weatherFogActive = context.bindUniform("fhWeatherFogActive", GlUniformInt::new);
        snowColor = context.bindUniform("fhSnowColor", GlUniformFloat3v::new);
        eyeExtinction = context.bindUniform("fhEyeExtinction", GlUniformFloat::new);
        heightScale = context.bindUniform("fhHeightScale", GlUniformFloat::new);
        excessesSampler = context.bindUniform("excesses", GlUniformInt::new);
        columnCount = context.bindUniform("columnCount", GlUniformInt::new);
        rowCount = context.bindUniform("rowCount", GlUniformInt::new);
        sliceCount = context.bindUniform("sliceCount", GlUniformInt::new);
        rangeDistance = context.bindUniform("rangeDistance", GlUniformFloat::new);
        divisor = context.bindUniform("divisor", GlUniformInt::new);
    }

    @Override
    public void setupState() {
        super.setupState();
        boundExcess = false;
        boolean active = WeatherFogRenderer.isTranslucentFogActive();
        weatherFogActive.setInt(active ? 1 : 0);
        if (!active) {
            return;
        }
        int previousActiveTexture = GlStateManager._getActiveTexture();
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TranslucentFogShader.EXCESS_TEXTURE_UNIT);
            previousExcessTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, WeatherFogRenderer.excessTexture());
            boundExcess = true;
        } finally {
            RenderSystem.activeTexture(previousActiveTexture);
        }
        fogEyeOffset.set(WeatherFogRenderer.eyeOffset(0), WeatherFogRenderer.eyeOffset(1),
                WeatherFogRenderer.eyeOffset(2));
        snowColor.set(WeatherFogRenderer.snowColor(0), WeatherFogRenderer.snowColor(1),
                WeatherFogRenderer.snowColor(2));
        eyeExtinction.setFloat((float) WeatherFogRenderer.eyeExtinction());
        heightScale.setFloat((float) WeatherFogRenderer.heightScale());
        excessesSampler.setInt(TranslucentFogShader.EXCESS_TEXTURE_UNIT);
        columnCount.setInt(WeatherFogRenderer.froxelColumnCount());
        rowCount.setInt(WeatherFogRenderer.froxelRowCount());
        sliceCount.setInt(WeatherFogRenderer.froxelSliceCount());
        rangeDistance.setFloat((float) WeatherFogRenderer.froxelRangeDistance());
        divisor.setInt(WeatherFogRenderer.froxelDivisor());
    }

    /**
     * Restores the 2D array texture binding replaced by setupState once the translucent pass ends.
     */
    public void restoreState() {
        if (!boundExcess) {
            return;
        }
        boundExcess = false;
        int previousActiveTexture = GlStateManager._getActiveTexture();
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TranslucentFogShader.EXCESS_TEXTURE_UNIT);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, previousExcessTexture);
        } finally {
            RenderSystem.activeTexture(previousActiveTexture);
        }
    }
}
