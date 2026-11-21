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
 * The displacement of the wind, which the structures of the fog follow, accumulated from the speed and direction of
 * each frame.
 */
public final class WeatherWind {
    /**
     * The longest step in seconds that advance takes, so that a frame that stalled does not jump the structures of the
     * fog.
     */
    public static final double MAXIMUM_STEP_SECONDS = 0.25;
    /**
     * The seconds over which the speed approaches its target, which lets the wind rise and fall smoothly.
     */
    public static final double SPEED_TIME_CONSTANT_SECONDS = 8.0;

    private double speed;
    private double direction;
    private double displacementX;
    private double displacementZ;
    /**
     * Whether a step has moved the speed already, so that the first one takes its target directly.
     */
    private boolean advanced;

    public WeatherWind() {
    }

    /**
     * Advances the wind by one frame.
     * @param seconds the seconds of the frame, where 0, negative or NaN pauses the wind
     * @param targetSpeed the speed in blocks per second to move toward, where NaN or negative counts as 0
     * @param direction the direction in radians to move along, where NaN keeps the last direction
     */
    public void advance(double seconds, double targetSpeed, double direction) {
        if (!(seconds > 0.0)) {
            return;
        }
        double stepSeconds = Math.min(seconds, MAXIMUM_STEP_SECONDS);
        double countedTarget = Double.isNaN(targetSpeed) || targetSpeed < 0.0 ? 0.0 : targetSpeed;
        if (advanced) {
            speed += (countedTarget - speed) * (1.0 - Math.exp(-stepSeconds / SPEED_TIME_CONSTANT_SECONDS));
        } else {
            speed = countedTarget;
            advanced = true;
        }
        if (!Double.isNaN(direction)) {
            this.direction = direction;
        }
        displacementX += speed * stepSeconds * Math.cos(this.direction);
        displacementZ += speed * stepSeconds * Math.sin(this.direction);
    }

    /**
     * The speed of the wind of the last advance, in blocks per second.
     */
    public double speed() {
        return speed;
    }

    /**
     * The direction of the wind of the last advance that gave one, in radians.
     */
    public double direction() {
        return direction;
    }

    /**
     * The blocks the wind has moved along x.
     */
    public double displacementX() {
        return displacementX;
    }

    /**
     * The blocks the wind has moved along z.
     */
    public double displacementZ() {
        return displacementZ;
    }
}
