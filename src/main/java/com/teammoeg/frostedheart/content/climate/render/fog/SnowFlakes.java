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
 * The math of the snowflakes near the eye, which snow_flakes.glsl, snow_flake_update.fsh and snow_flakes_draw.glsl
 * repeat on the GPU, so that GameTests can check it.
 * A flake lives in a periodic box of a given side: its position from 0 up to the side stands for its copies at every
 * multiple of the side, and the draw takes the copy nearest the camera. So the box follows the camera without
 * respawning flakes, and every position stays a small number.
 * A flake's own values come from hashes of its index, which is its texel in the state texture, row by row.
 * How many flakes draw follows the fog: the flakes cover a set share of the fog's extinction at the eye, so they thin
 * and thicken with the snowfall, the storm and the height, as the fog does.
 */
public final class SnowFlakes {
    public static final int STATE_WIDTH = 512;
    public static final int STATE_HEIGHT = 256;
    /**
     * The most flakes the state texture holds.
     */
    public static final int CAPACITY = STATE_WIDTH * STATE_HEIGHT;
    /**
     * The flakes over which the last drawn ones fade as the drawn count changes.
     */
    public static final double COUNT_FADE_FLAKES = 1024.0;
    public static final int SALT_POSITION_X = 1;
    public static final int SALT_POSITION_Y = 2;
    public static final int SALT_POSITION_Z = 3;
    public static final int SALT_FLUTTER_PHASE = 4;
    public static final int SALT_FALL = 5;
    public static final int SALT_SIZE = 6;
    public static final int SALT_FLUTTER_PERIOD = 7;
    public static final int SALT_FLUTTER_TURN = 8;
    public static final int SALT_FLUTTER_RADIUS = 9;
    public static final int SALT_BRIGHTNESS = 10;
    public static final int SALT_SHELTER = 11;
    public static final double SLOWEST_FALL_SHARE = 2.0 / 3.0;
    public static final double FASTEST_FALL_SHARE = 4.0 / 3.0;
    public static final double SMALLEST_SIZE_SHARE = 0.6;
    public static final double LARGEST_SIZE_SHARE = 1.4;
    public static final double SHORTEST_FLUTTER_PERIOD = 1.2;
    public static final double LONGEST_FLUTTER_PERIOD = 3.0;
    public static final double SMALLEST_FLUTTER_RADIUS_SHARE = 0.5;
    public static final double LARGEST_FLUTTER_RADIUS_SHARE = 1.5;
    public static final double DIMMEST_BRIGHTNESS_SHARE = 0.85;
    public static final double BRIGHTEST_BRIGHTNESS_SHARE = 1.15;
    /**
     * The width of the snow share over which a flake fades out under cover.
     */
    public static final double SHELTER_SOFTNESS = 0.25;
    /**
     * The mean of the square of a flake's size share, for a size share spread evenly between its smallest and largest.
     */
    public static final double MEAN_SQUARED_SIZE_SHARE = (LARGEST_SIZE_SHARE * LARGEST_SIZE_SHARE * LARGEST_SIZE_SHARE
            - SMALLEST_SIZE_SHARE * SMALLEST_SIZE_SHARE * SMALLEST_SIZE_SHARE)
            / (3.0 * (LARGEST_SIZE_SHARE - SMALLEST_SIZE_SHARE));
    private static final double UNIT_SCALE = 1.0 / 16777216.0;

    private SnowFlakes() {
    }

    /**
     * The 32-bit mix of a value, the same bits as the GLSL snowHash on an unsigned int.
     */
    public static int hash(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return mixed;
    }

    /**
     * A number from 0 to just below 1 that only the flake and the salt decide.
     */
    public static double unit(int flake, int salt) {
        return (hash(flake * 16 + salt) >>> 8) * UNIT_SCALE;
    }

    /**
     * The flake's fall speed as a share of the median fall speed.
     */
    public static double fallShare(int flake) {
        return mix(SLOWEST_FALL_SHARE, FASTEST_FALL_SHARE, unit(flake, SALT_FALL));
    }

    /**
     * The flake's width as a share of the mean width.
     */
    public static double sizeShare(int flake) {
        return mix(SMALLEST_SIZE_SHARE, LARGEST_SIZE_SHARE, unit(flake, SALT_SIZE));
    }

    /**
     * The radians per second at which the flake circles while it falls, negative for a flake that turns the other way.
     */
    public static double flutterRate(int flake) {
        double period = mix(SHORTEST_FLUTTER_PERIOD, LONGEST_FLUTTER_PERIOD, unit(flake, SALT_FLUTTER_PERIOD));
        double turn = unit(flake, SALT_FLUTTER_TURN) < 0.5 ? -1.0 : 1.0;
        return turn * 2.0 * Math.PI / period;
    }

    /**
     * The radius of the flake's circle as a share of the flutter setting.
     */
    public static double flutterRadiusShare(int flake) {
        return mix(SMALLEST_FLUTTER_RADIUS_SHARE, LARGEST_FLUTTER_RADIUS_SHARE, unit(flake, SALT_FLUTTER_RADIUS));
    }

    public static double brightnessShare(int flake) {
        return mix(DIMMEST_BRIGHTNESS_SHARE, BRIGHTEST_BRIGHTNESS_SHARE, unit(flake, SALT_BRIGHTNESS));
    }

    /**
     * The snow share below which the flake is hidden under cover, from 0 to 1 - SHELTER_SOFTNESS.
     */
    public static double shelterThreshold(int flake) {
        return unit(flake, SALT_SHELTER) * (1.0 - SHELTER_SOFTNESS);
    }

    /**
     * How much of the flake shows at a snow share, which falls from 1 to 0 as the share drops through the flake's
     * threshold.
     */
    public static double shelterWeight(int flake, double share) {
        return clamp((share - shelterThreshold(flake)) / SHELTER_SOFTNESS);
    }

    /**
     * A position wrapped into the box of the given side, from 0 up to the side.
     */
    public static double wrap(double position, double side) {
        return position - side * Math.floor(position / side);
    }

    /**
     * The offset from the camera of the copy of a flake that is nearest the camera along one axis, from minus half the
     * side to half the side.
     * @param position the flake's position in the box
     * @param cameraInBox the camera's position wrapped into the box
     */
    public static double nearestCopyOffset(double position, double cameraInBox, double side) {
        double offset = position - cameraInBox;
        return offset - side * Math.floor(offset / side + 0.5);
    }

    /**
     * The flake's position after one step along one axis, wrapped into the box.
     */
    public static double step(double position, double velocity, double seconds, double side) {
        return wrap(position + velocity * seconds, side);
    }

    /**
     * The mean area in square blocks that a flake covers, for flakes whose mean width is the given size.
     */
    public static double meanArea(double flakeSize) {
        return 0.25 * Math.PI * flakeSize * flakeSize * MEAN_SQUARED_SIZE_SHARE;
    }

    /**
     * The number of flakes in the box at which their number per block times their mean area is the share of the fog's
     * extinction, which may be more than CAPACITY. A value that is not finite or not positive gives 0.
     * @param extinction the fog's extinction per block at the eye
     * @param flakeShare the share of that extinction that the flakes cover
     */
    public static double askedCount(double extinction, double flakeShare, double flakeSize, double boxSide) {
        double area = meanArea(flakeSize);
        double count = flakeShare * extinction * boxSide * boxSide * boxSide / area;
        if (!(count > 0.0) || !(area > 0.0) || Double.isInfinite(count)) {
            return 0.0;
        }
        return count;
    }

    /**
     * The number of flakes to draw, which is askedCount capped at CAPACITY. It may end in a fraction, which fades the
     * last flake.
     */
    public static double drawnCount(double extinction, double flakeShare, double flakeSize, double boxSide) {
        return Math.min(askedCount(extinction, flakeShare, flakeSize, boxSide), CAPACITY);
    }

    /**
     * How much of the flake draws at a drawn count, which fades the last COUNT_FADE_FLAKES flakes below the count.
     */
    public static double countWeight(int flake, double drawnCount) {
        return clamp((drawnCount - flake) / COUNT_FADE_FLAKES);
    }

    /**
     * The opacity of a flake's sprite stretched over a streak, as a share of its opacity at rest, which spreads the
     * flake's area over the area of the streak.
     * @param radiusPixels the drawn radius of the sprite
     * @param lengthPixels the length of the streak, not counting the sprite's radius at either end
     */
    public static double streakShare(double radiusPixels, double lengthPixels) {
        return Math.PI * radiusPixels / (2.0 * lengthPixels + Math.PI * radiusPixels);
    }

    private static double mix(double from, double to, double share) {
        return from + (to - from) * share;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
