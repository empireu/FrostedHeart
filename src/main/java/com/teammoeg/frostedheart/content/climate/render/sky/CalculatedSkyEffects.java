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

package com.teammoeg.frostedheart.content.climate.render.sky;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * Overworld effects that remove the vanilla sunrise fan and its fog tint while the calculated sky is drawn.
 */
@OnlyIn(Dist.CLIENT)
public final class CalculatedSkyEffects extends DimensionSpecialEffects.OverworldEffects {
    @Nullable
    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && CalculatedSkyRenderer.isDrawing(level)) {
            return null;
        }
        return super.getSunriseColor(timeOfDay, partialTick);
    }
}
