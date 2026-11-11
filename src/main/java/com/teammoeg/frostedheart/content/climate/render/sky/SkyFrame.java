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

import com.teammoeg.frostedheart.FHMain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * What the calculated sky draws from in one frame, which is the sun, the ice optical depth, the grid indices they call
 * for, the exposure, and the weather. It is filled at its first use in a frame and reused by the draw, the fog color,
 * the daylight, and CalculatedSkyRenderer.isSettled, so the renderer calls IceWeatherDepth.update once per frame.
 * A new frame begins with each render tick, and a call with another partial tick fills it again. Render thread only.
 * Blend weights are not part of the frame, because they depend on the state the renderer draws from, and a build that
 * finishes in CalculatedSkyRenderer.render replaces that state after the fog color and the daylight of the same frame.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SkyFrame {
    private static final SkyFrame CURRENT = new SkyFrame();

    private boolean stale = true;
    private float partialTick;
    private double sunElevationRadians;
    private float sunX;
    private float sunY;
    private float sunSide;
    private double iceDepth;
    private int haloIndex;
    private int depthIndex;
    private int viewIndex;
    private double exposure;
    private float rainLevel;
    private float thunderLevel;

    private SkyFrame() {
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            CURRENT.stale = true;
        }
    }

    /**
     * The frame for the level and partial tick, filled if this is its first use in the frame.
     */
    public static SkyFrame current(ClientLevel level, float partialTick) {
        if (CURRENT.stale || CURRENT.partialTick != partialTick) {
            CURRENT.fill(level, partialTick);
        }
        return CURRENT;
    }

    private void fill(ClientLevel level, float partialTick) {
        this.partialTick = partialTick;
        double timeOfDayAngle = level.getTimeOfDay(partialTick) * Math.PI * 2.0;
        sunElevationRadians = Math.asin(Math.cos(timeOfDayAngle));
        sunX = (float) -Math.sin(timeOfDayAngle);
        sunY = (float) Math.cos(timeOfDayAngle);
        sunSide = Math.sin(timeOfDayAngle) > 0.0 ? -1.0F : 1.0F;
        iceDepth = IceWeatherDepth.update(partialTick);
        haloIndex = SkyBuilder.haloGridIndex(sunElevationRadians);
        depthIndex = IceDepthGrid.index(iceDepth);
        viewIndex = SkyBuilder.viewGridIndex(sunElevationRadians);
        exposure = SkySetting.EXPOSURE.get();
        rainLevel = level.getRainLevel(partialTick);
        thunderLevel = level.getThunderLevel(partialTick);
        stale = false;
    }

    public double sunElevationRadians() {
        return sunElevationRadians;
    }

    /**
     * The x component of the direction to the sun, which moves in the plane of x and y.
     */
    public float sunX() {
        return sunX;
    }

    public float sunY() {
        return sunY;
    }

    /**
     * The sign of the sun's x direction, which turns world azimuths into azimuths from the sun as the tables use them.
     */
    public float sunSide() {
        return sunSide;
    }

    /**
     * The ice optical depth drawn in this frame, as IceWeatherDepth gives it.
     */
    public double iceDepth() {
        return iceDepth;
    }

    /**
     * The index of the halo map grid at or below the sun.
     */
    public int haloIndex() {
        return haloIndex;
    }

    /**
     * The index of IceDepthGrid at or below the ice depth.
     */
    public int depthIndex() {
        return depthIndex;
    }

    /**
     * The index of the sky view grid at or below the sun.
     */
    public int viewIndex() {
        return viewIndex;
    }

    public double exposure() {
        return exposure;
    }

    public float rainLevel() {
        return rainLevel;
    }

    public float thunderLevel() {
        return thunderLevel;
    }
}
