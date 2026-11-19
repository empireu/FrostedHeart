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
import me.jellysquid.mods.sodium.client.render.immediate.CloudRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fits Embeddium's cloud fog to the weather fog, whose pass draws before the clouds: the clouds fade out where the fog
 * at the eye already hides the scene, and their alpha falls by the fog from the eye to the cloud layer. It fits the fog
 * right after Embeddium set its own fog start and end, which is before it draws the clouds, and puts the alpha of the
 * fog color back before every return, while Embeddium restores its own start and end. It does nothing unless the
 * weather fog drew this frame.
 */
@Mixin(value = CloudRenderer.class, remap = false)
public abstract class CloudRendererFogMixin {
    /**
     * Fits the fog for the cloud draw that follows.
     */
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogStart(F)V", ordinal = 0,
            shift = At.Shift.AFTER))
    private void frostedheart$fitCloudFog(ClientLevel world, LocalPlayer player, PoseStack poseStack,
            Matrix4f projectionMatrix, float ticks, float tickDelta, double cameraX, double cameraY, double cameraZ,
            CallbackInfo callbackInfo) {
        WeatherFogRenderer.fitCloudFog(cameraY, world.effects().getCloudHeight());
    }

    /**
     * Puts the alpha of the fog color back after the cloud draw.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void frostedheart$restoreCloudFog(ClientLevel world, LocalPlayer player, PoseStack poseStack,
            Matrix4f projectionMatrix, float ticks, float tickDelta, double cameraX, double cameraY, double cameraZ,
            CallbackInfo callbackInfo) {
        WeatherFogRenderer.restoreCloudFog();
    }
}
