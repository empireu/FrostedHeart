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
 * The WeatherConditions that the client already has, built from its own rain and thunder levels.
 * It is named for its source rather than for being a test double: the caller passes the values in, so it stays a plain
 * class that a dedicated server can load. One instance lives for the session and is updated every frame, which keeps
 * its allocation at none.
 */
public final class ClientMockWeather implements WeatherConditions {
    /**
     * The rain and thunder levels that the server sends in snow and in blizzards, where the weather counts as full.
     */
    public static final double FULL_LEVEL = 0.8;
    private static final double DEFAULT_HEIGHT_SCALE = 128.0;
    private static final double DEFAULT_DECAY_LENGTH = 1.5;
    private double snowfallIntensity;
    private double stormIntensity;
    private double referenceExtinction;
    private double heightScale = DEFAULT_HEIGHT_SCALE;
    private double decayLength = DEFAULT_DECAY_LENGTH;

    /**
     * Takes the weather of this frame, which replaces the weather of the frame before.
     * A rain or thunder level that is NaN counts as 0, and a visibility that is not positive or is infinite gives no
     * fog at that visibility.
     * @param rainLevel the client's rain level, where 0 is clear and FULL_LEVEL or more is full snow
     * @param thunderLevel the client's thunder level, where 0 is plain snow and FULL_LEVEL or more is a blizzard
     * @param snowVisibility the fog visibility in blocks of plain snowfall, from the fog settings
     * @param blizzardVisibility the fog visibility in blocks of a full blizzard, from the fog settings
     * @param heightScale the height scale in blocks of the fog, which replaces the current one only when it is positive
     *        and not NaN
     * @param snowDecayLength the decay length in blocks of the snow share in snowfall, from the fog settings
     * @param blizzardDecayLength the decay length in blocks of the snow share in a full blizzard, from the fog settings
     */
    public void update(double rainLevel, double thunderLevel, double snowVisibility, double blizzardVisibility,
            double heightScale, double snowDecayLength, double blizzardDecayLength) {
        double snowfall = clamp(countNaNAsZero(rainLevel) / FULL_LEVEL);
        double storm = Math.min(clamp(countNaNAsZero(thunderLevel) / FULL_LEVEL), snowfall);
        double snowExtinction = HeightFog.extinctionFromVisibility(snowVisibility);
        double blizzardExtinction = HeightFog.extinctionFromVisibility(blizzardVisibility);
        this.snowfallIntensity = snowfall;
        this.stormIntensity = storm;
        this.referenceExtinction = snowfall * (snowExtinction + (blizzardExtinction - snowExtinction) * storm);
        double updatedDecayLength = snowDecayLength + (blizzardDecayLength - snowDecayLength) * storm;
        this.decayLength = Double.isNaN(updatedDecayLength) ? DEFAULT_DECAY_LENGTH : updatedDecayLength;
        if (heightScale > 0.0) {
            this.heightScale = heightScale;
        }
    }

    /**
     * The snowfall intensity of the last update, which is 0 before the first one.
     */
    @Override
    public double snowfallIntensity() {
        return snowfallIntensity;
    }

    /**
     * The storm intensity of the last update, which is 0 before the first one.
     */
    @Override
    public double stormIntensity() {
        return stormIntensity;
    }

    /**
     * The extinction per block at sea level of the last update, which is 0 before the first one.
     */
    @Override
    public double referenceExtinction() {
        return referenceExtinction;
    }

    /**
     * The height scale in blocks of the last update that gave a positive one, which is 128 before the first one.
     */
    @Override
    public double heightScale() {
        return heightScale;
    }

    /**
     * The distance in blocks over which the snow share falls by a factor of e going under cover, which is the snow or
     * blizzard decay length of the last update by its storm, and 1.5 before the first one or after NaN lengths.
     */
    @Override
    public double decayLength() {
        return decayLength;
    }

    private static double countNaNAsZero(double level) {
        if (Double.isNaN(level)) {
            return 0.0;
        }
        return level;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
