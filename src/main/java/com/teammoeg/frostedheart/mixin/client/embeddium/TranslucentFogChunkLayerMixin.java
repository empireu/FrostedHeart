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

import com.mojang.blaze3d.vertex.PoseStack;
import com.teammoeg.frostedheart.content.climate.render.fog.WeatherFogRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps Embeddium's translucent terrain chunk draw (outside RenderDevice.enterManagedCode/exitManagedCode) so
 * WeatherFogRenderer can prepare froxels, capture translucent terrain into its offscreen target, draw opaque fog and
 * behind-translucent snowfall, composite translucent terrain with lamp glow, sync depth, and fit eye fog before
 * AFTER_TRANSLUCENT_BLOCKS, Flywheel, translucent entities, particles, clouds, and weather run.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class TranslucentFogChunkLayerMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "renderChunkLayer", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gl/device/RenderDevice;enterManagedCode()V",
            shift = At.Shift.BEFORE, remap = false))
    private void frostedheart$beforeTranslucentChunks(RenderType renderLayer, PoseStack matrices, double x, double y,
            double z, Matrix4f projectionMatrix, CallbackInfo callbackInfo) {
        if (renderLayer == RenderType.translucent()) {
            WeatherFogRenderer.beforeTranslucentChunks(level, matrices, projectionMatrix);
        }
    }

    @Inject(method = "renderChunkLayer", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gl/device/RenderDevice;exitManagedCode()V",
            shift = At.Shift.AFTER, remap = false))
    private void frostedheart$afterTranslucentChunks(RenderType renderLayer, PoseStack matrices, double x, double y,
            double z, Matrix4f projectionMatrix, CallbackInfo callbackInfo) {
        if (renderLayer == RenderType.translucent()) {
            WeatherFogRenderer.afterTranslucentChunks(level, matrices, projectionMatrix);
        }
    }
}
