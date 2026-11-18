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

package com.teammoeg.frostedheart.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls vanilla's snow and rain from the weather fog, which draws it again after the fog, with the fog state that the
 * flakes stand in.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccess {
    /**
     * Draws vanilla's snow and rain into the target bound now, with the light texture and camera position of the frame.
     */
    @Invoker("renderSnowAndRain")
    void frostedheart$renderSnowAndRain(LightTexture lightTexture, float partialTick, double cameraX, double cameraY,
            double cameraZ);
}
