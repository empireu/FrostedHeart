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

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Gives the weather fog the lightmap pixels, from which it reads the color of a white top face at full sky light.
 * The image is the live one that vanilla refreshes once per tick before the level renders, so reading it needs neither
 * an upload nor a texture readback. Also gives the lightmap's texture, which the snowfall binds for lamp light.
 */
@Mixin(LightTexture.class)
public interface LightTextureAccessor_WeatherFog {
    @Accessor("lightPixels")
    NativeImage frostedheart$getLightPixels();

    @Accessor("lightTexture")
    DynamicTexture frostedheart$getLightTexture();
}
