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

package com.teammoeg.frostedheart.mixin.client.embeddium;

import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.bootstrap.client.FHShaders;
import com.teammoeg.frostedheart.content.climate.render.fog.TranslucentFogChunkShaderInterface;
import com.teammoeg.frostedheart.content.climate.render.fog.TranslucentFogShader;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderParser;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Embeddium's translucent chunk shader with a variant that discards invisible alpha == 0 texels (preventing
 * depth cutouts around translucent models such as icicles) and applies exact per-fragment froxel weather fog while
 * WeatherFogRenderer is active. Skipped automatically when an Oculus shader pack overrides the chunk program.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class TranslucentFogChunkShaderMixin {
    @Unique
    private static final Logger frostedheart$LOGGER = LogUtils.getLogger();
    @Unique
    private static boolean frostedheart$failedLogged;

    @Shadow
    protected GlProgram<ChunkShaderInterface> activeProgram;

    @Inject(method = "createShader", at = @At("HEAD"), cancellable = true)
    private void frostedheart$createTranslucentFogShader(String path, ChunkShaderOptions options,
            CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> callbackInfo) {
        if (options.pass() != DefaultTerrainRenderPasses.TRANSLUCENT) {
            return;
        }
        GlShader vertShader = null;
        GlShader fragShader = null;
        try {
            ShaderConstants constants = options.constants();
            ResourceLocation vertLocation = new ResourceLocation("sodium", path + ".vsh");
            ResourceLocation fragLocation = new ResourceLocation("sodium", path + ".fsh");
            String vertSource = TranslucentFogShader.vertex(ShaderLoader.getShaderSource(vertLocation));
            String fragSource = TranslucentFogShader.fragment(ShaderLoader.getShaderSource(fragLocation),
                    FHShaders.readWeatherFogInclude(), FHShaders.readWeatherFogExcessInclude());
            vertShader = new GlShader(ShaderType.VERTEX, vertLocation, ShaderParser.parseShader(vertSource, constants));
            fragShader = new GlShader(ShaderType.FRAGMENT, fragLocation,
                    ShaderParser.parseShader(fragSource, constants));
            GlProgram<ChunkShaderInterface> program = GlProgram.builder(
                            new ResourceLocation("frostedheart", "translucent_weather_fog"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_PosId", ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE)
                    .bindAttribute("a_LightCoord", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link(shader -> new TranslucentFogChunkShaderInterface(shader, options));
            callbackInfo.setReturnValue(program);
        } catch (RuntimeException exception) {
            if (!frostedheart$failedLogged) {
                frostedheart$failedLogged = true;
                frostedheart$LOGGER.warn("The translucent weather fog chunk shader could not be built, falling back to "
                        + "Embeddium's default translucent shader.", exception);
            }
        } finally {
            if (vertShader != null) {
                vertShader.delete();
            }
            if (fragShader != null) {
                fragShader.delete();
            }
        }
    }

    @Inject(method = "end", at = @At("HEAD"))
    private void frostedheart$restoreTranslucentFogState(TerrainRenderPass pass, CallbackInfo callbackInfo) {
        if (activeProgram != null && activeProgram.getInterface() instanceof TranslucentFogChunkShaderInterface fogInterface) {
            fogInterface.restoreState();
        }
    }
}
