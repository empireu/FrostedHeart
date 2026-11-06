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

/**
 * Tunable inputs of the calculated sky.
 * Optical depths are vertical and measured at 550 nm. The spread is the geometric standard deviation of particle diameter.
 */
public record SkySettings(double nanobotOpticalDepth, double nanobotMedianNanometers, double nanobotSpread,
        double iceOpticalDepth) {
    public static final SkySettings DEFAULT = new SkySettings(0.25, 100.0, 1.75, 0.1);
    public static final double DEFAULT_EXPOSURE = 6.0;
    public static final double MINIMUM_EXPOSURE = 0.5;
    public static final double MAXIMUM_EXPOSURE = 50.0;
    public static final double MAXIMUM_NANOBOT_OPTICAL_DEPTH = 4.0;
    public static final double MINIMUM_NANOBOT_MEDIAN_NANOMETERS = 50.0;
    public static final double MAXIMUM_NANOBOT_MEDIAN_NANOMETERS = 1000.0;
    public static final double MINIMUM_NANOBOT_SPREAD = 1.05;
    public static final double MAXIMUM_NANOBOT_SPREAD = 3.0;
    public static final double MAXIMUM_ICE_OPTICAL_DEPTH = 1.0;
}
