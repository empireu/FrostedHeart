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
    /**
     * The wind level of a full wind, which windSpeed divides by.
     */
    private static final double FULL_WIND_LEVEL = 100.0;
    /**
     * The direction the wind of windDirectionAt turns around, in radians.
     */
    private static final double BASE_WIND_DIRECTION = 1.3;
    /**
     * The two periods in ticks of the turns of the wind, and the phase of each, whose sum stays smooth and comes from
     * the same synced day time for every player.
     */
    private static final double FIRST_WIND_TICKS = 36000.0;
    private static final double SECOND_WIND_TICKS = 97000.0;
    private static final double FIRST_WIND_PHASE = 0.7;
    private static final double SECOND_WIND_PHASE = 2.1;
    private static final double FIRST_WIND_WEIGHT = 0.9;
    private static final double SECOND_WIND_WEIGHT = 0.5;
    private double snowfallIntensity;
    private double stormIntensity;
    private double referenceExtinction;
    private double heightScale = DEFAULT_HEIGHT_SCALE;
    private double decayLength = DEFAULT_DECAY_LENGTH;
    private double windSpeed;
    private double windDirection;
    private double structureShare;

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
     * @param windLevel the wind level of the climate, from 0 to 100, where NaN counts as 0
     * @param windScale the speed in blocks per second of a full wind, from the fog settings, where NaN or a negative
     *        one gives no wind
     * @param dayTicks the day time in ticks with the partial tick, which sets the direction of the wind
     * @param snowStructureShare the share of the fog of plain snowfall that sits in the structures, where NaN counts
     *        as 0
     * @param blizzardStructureShare the share of the fog of a full blizzard that sits in the structures, where NaN
     *        counts as 0
     */
    public void update(double rainLevel, double thunderLevel, double snowVisibility, double blizzardVisibility,
            double heightScale, double snowDecayLength, double blizzardDecayLength, double windLevel,
            double windScale, double dayTicks, double snowStructureShare, double blizzardStructureShare) {
        double snowfall = clamp(countNaNAsZero(rainLevel) / FULL_LEVEL);
        double storm = Math.min(clamp(countNaNAsZero(thunderLevel) / FULL_LEVEL), snowfall);
        double snowExtinction = HeightFog.extinctionFromVisibility(snowVisibility);
        double blizzardExtinction = HeightFog.extinctionFromVisibility(blizzardVisibility);
        this.snowfallIntensity = snowfall;
        this.stormIntensity = storm;
        this.referenceExtinction = snowfall * (snowExtinction + (blizzardExtinction - snowExtinction) * storm);
        double updatedDecayLength = snowDecayLength + (blizzardDecayLength - snowDecayLength) * storm;
        this.decayLength = Double.isNaN(updatedDecayLength) ? DEFAULT_DECAY_LENGTH : updatedDecayLength;
        double snowStructure = countNaNAsZero(snowStructureShare);
        double blizzardStructure = countNaNAsZero(blizzardStructureShare);
        this.structureShare = clamp(snowStructure + (blizzardStructure - snowStructure) * storm);
        if (heightScale > 0.0) {
            this.heightScale = heightScale;
        }
        if (Double.isNaN(windScale) || windScale < 0.0) {
            this.windSpeed = 0.0;
        } else {
            this.windSpeed = clamp(countNaNAsZero(windLevel) / FULL_WIND_LEVEL) * windScale;
        }
        this.windDirection = windDirectionAt(dayTicks);
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

    /**
     * The wind speed of the last update in blocks per second, which is 0 before the first one.
     */
    @Override
    public double windSpeed() {
        return windSpeed;
    }

    /**
     * The direction the wind of the last update blows toward in radians, which is 0 before the first one.
     */
    @Override
    public double windDirection() {
        return windDirection;
    }

    /**
     * The share of the fog of the last update that sits in the moving structures, which is 0 before the first one.
     */
    @Override
    public double structureShare() {
        return structureShare;
    }

    /**
     * The direction the wind blows toward at a day time in ticks, which turns slowly and is the same for every player
     * since the day time is synced.
     * @param dayTicks the day time in ticks, with the partial tick of the frame
     * @return the direction in radians, and the base direction for NaN
     */
    public static double windDirectionAt(double dayTicks) {
        if (Double.isNaN(dayTicks)) {
            return BASE_WIND_DIRECTION;
        }
        double firstTurn = 2.0 * Math.PI * dayTicks / FIRST_WIND_TICKS + FIRST_WIND_PHASE;
        double secondTurn = 2.0 * Math.PI * dayTicks / SECOND_WIND_TICKS + SECOND_WIND_PHASE;
        return BASE_WIND_DIRECTION + FIRST_WIND_WEIGHT * Math.sin(firstTurn)
                + SECOND_WIND_WEIGHT * Math.sin(secondTurn);
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
