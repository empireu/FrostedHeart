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

/**
 * The weather that the fog renderers read, which they take from this interface alone.
 * The client's own rain and thunder levels are enough at first, which ClientMockWeather turns into these values, and a
 * provider synced from the server can replace it later without touching the renderers.
 * Implementations are read on the render thread and allocate nothing.
 */
public interface WeatherConditions {
    /**
     * How strongly it snows, from 0 when it does not snow to 1 when it snows fully.
     * @return the snowfall intensity, in the range 0 to 1
     */
    double snowfallIntensity();

    /**
     * How much of a blizzard the snow is, from 0 for plain snowfall to 1 for a full blizzard.
     * @return the storm intensity, in the range 0 to 1, never above snowfallIntensity
     */
    double stormIntensity();

    /**
     * The fog's extinction per block at sea level, which sets how thick the fog is.
     * @return the extinction per block at sea level, 0 when there is no fog
     */
    double referenceExtinction();

    /**
     * The height in blocks over which the fog's extinction falls by a factor of e, which sets how low the fog hangs.
     * @return the height scale in blocks, positive
     */
    double heightScale();

    /**
     * The distance in blocks over which the snow share falls by a factor of e going under cover.
     * @return the decay length in blocks, positive
     */
    double decayLength();

    /**
     * The speed of the wind, which carries the structures of the fog.
     * @return the wind speed in blocks per second, not negative
     */
    double windSpeed();

    /**
     * The direction the wind blows toward in the x-z plane, where the wind blows toward (cos, sin) of it.
     * @return the wind direction in radians
     */
    double windDirection();

    /**
     * The share of the fog that sits in the moving structures, with the rest spread thin as clear air between them.
     * @return the structure share from 0 for fog of an even density to 1 for fog that all sits in the structures
     */
    double structureShare();
}
