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
 * A flake collides with the shapes of SnowShapeVolume. It is tested every SUBSTEP_BLOCKS of its move, and where it hits
 * a shape it stops and leaves a splat on the face it entered. A stopped flake moves on unseen until it leaves the box
 * seen from the camera and comes in at the opposite face as a new flake, which keeps the flakes evenly spread.
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
    public static final int SALT_LEE = 0;
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
    public static final int SALT_SHAPE = 12;
    public static final int SALT_TURN = 13;
    public static final int SALT_TILT = 14;
    public static final int SALT_LEAVES = 15;
    /**
     * What the angle of a flake's flutter circle in its state is raised by while the flake is stopped, which is more
     * than a full circle.
     */
    public static final double STOPPED_PHASE = 8.0;
    /**
     * The step between faces in the w of a splat, which holds the seconds since the hit below it, so it must exceed the
     * longest snowSplatSeconds.
     */
    public static final double SPLAT_FACE_STRIDE = 16.0;
    /**
     * The longest move between two collision tests, in blocks.
     */
    public static final double SUBSTEP_BLOCKS = 0.25;
    public static final int MOST_SUBSTEPS = 16;
    /**
     * The halvings that narrow a hit down from a sub-step to a sixty-fourth of a block.
     */
    public static final int HIT_REFINEMENTS = 4;
    /**
     * What findHit gives when the flake passes, and when it starts inside a shape, which stops it without a splat.
     */
    public static final int NO_HIT = -1;
    public static final int STARTS_INSIDE = -2;
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
     * The median flake fall speed in blocks per second for the current storm intensity, keeping plain snowfall at
     * snowFallSpeed when stormIntensity is 0 and blending to blizzardFallSpeed at stormIntensity 1.
     */
    public static double fallSpeed(double snowFallSpeed, double blizzardFallSpeed, double stormIntensity) {
        double snow = Double.isFinite(snowFallSpeed) && snowFallSpeed > 0.0 ? snowFallSpeed : 0.0;
        double blizzard = Double.isFinite(blizzardFallSpeed) && blizzardFallSpeed > 0.0 ? blizzardFallSpeed : snow;
        double storm = Double.isFinite(stormIntensity) ? clamp(stormIntensity) : 0.0;
        return snow + (blizzard - snow) * storm;
    }

    /**
     * The horizontal wind speed in blocks per second that snowflakes and the lee map follow, while the fog keeps the
     * full wind speed.
     */
    public static double windSpeed(double fogWindSpeed, double windShare) {
        if (!(fogWindSpeed > 0.0) || !Double.isFinite(fogWindSpeed) || !(windShare > 0.0)
                || !Double.isFinite(windShare)) {
            return 0.0;
        }
        return fogWindSpeed * clamp(windShare);
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

    /**
     * Whether a flake leaves the box seen from the camera in an update along one axis, after which it comes in at the
     * opposite face as a new flake.
     * @param offsetBefore the offset from the camera at the last update of the flake's nearest copy
     * @param step the flake's move in this update
     * @param cameraStep the camera's move since the last update
     */
    public static boolean leavesBox(double offsetBefore, double step, double cameraStep, double side) {
        return Math.abs(offsetBefore - cameraStep + step) >= 0.5 * side;
    }

    /**
     * The collision tests of a move of the given length in blocks, one every SUBSTEP_BLOCKS and from 1 to
     * MOST_SUBSTEPS.
     */
    public static int substepCount(double blocks) {
        double count = Math.ceil(blocks / SUBSTEP_BLOCKS);
        if (!(count >= 1.0)) {
            return 1;
        }
        return (int) Math.min(count, MOST_SUBSTEPS);
    }

    /**
     * A number from 0 to just below 1 that only the flake and the block decide, below the leaf porosity when the flake
     * passes through the leaves of that block.
     */
    public static double leafPassUnit(int flake, int x, int y, int z) {
        int blockKey = hash(x * 73856093 ^ y * 19349663 ^ z * 83492791);
        return (hash((flake * 16 + SALT_LEAVES) ^ blockKey) >>> 8) * UNIT_SCALE;
    }

    /**
     * The id of the shape that a world point lies in, or 0 when the flake passes the point, which it does outside the
     * volume, in an empty cell, in leaves that let it through, and in a voxel that the shape leaves open.
     */
    public static int hitIdAt(SnowShapeVolume volume, int flake, double leafPorosity, double[] point) {
        int blockX = (int) Math.floor(point[0]);
        int blockY = (int) Math.floor(point[1]);
        int blockZ = (int) Math.floor(point[2]);
        int id = volume.idAt(blockX, blockY, blockZ);
        int shape = SnowShapes.shapeIndex(id);
        if (shape == SnowShapes.EMPTY_SHAPE) {
            return 0;
        }
        if (SnowShapes.isLeaves(id) && leafPassUnit(flake, blockX, blockY, blockZ) < leafPorosity) {
            return 0;
        }
        if (shape != SnowShapes.FULL_SHAPE) {
            int voxelX = Math.min((int) ((point[0] - blockX) * SnowShapes.SIDE), SnowShapes.SIDE - 1);
            int voxelY = Math.min((int) ((point[1] - blockY) * SnowShapes.SIDE), SnowShapes.SIDE - 1);
            int voxelZ = Math.min((int) ((point[2] - blockZ) * SnowShapes.SIDE), SnowShapes.SIDE - 1);
            if (!volume.shapes().isFilled(shape, voxelX, voxelY, voxelZ)) {
                return 0;
            }
        }
        return id;
    }

    /**
     * Follows a straight move of a flake in world coordinates in sub-steps, as the update does for a flake that does
     * not flutter. Allocates.
     * @param hitPoint receives the point on the face where the flake hits, when it hits
     * @return the face it hits, numbered as Direction.get3DDataValue, or NO_HIT, or STARTS_INSIDE
     */
    public static int findHit(SnowShapeVolume volume, int flake, double leafPorosity, double[] start, double[] end,
            double[] hitPoint) {
        double length = Math.sqrt((end[0] - start[0]) * (end[0] - start[0]) + (end[1] - start[1]) * (end[1] - start[1])
                + (end[2] - start[2]) * (end[2] - start[2]));
        int substeps = substepCount(length);
        double[] previous = start.clone();
        double[] point = new double[3];
        for (int substep = 1; substep <= substeps; substep++) {
            double share = (double) substep / substeps;
            for (int axis = 0; axis < 3; axis++) {
                point[axis] = start[axis] + (end[axis] - start[axis]) * share;
            }
            if (hitIdAt(volume, flake, leafPorosity, point) != 0) {
                if (substep == 1 && hitIdAt(volume, flake, leafPorosity, previous) != 0) {
                    return STARTS_INSIDE;
                }
                return hitFace(volume, flake, leafPorosity, previous, point, hitPoint);
            }
            System.arraycopy(point, 0, previous, 0, 3);
        }
        return NO_HIT;
    }

    /**
     * Narrows a hit between a point the flake passes and a point inside a shape, and returns the face of the shape that
     * the flake enters, which is the face across the axis on which the two narrowed points lie in different voxels, the
     * one the flake moved along most when several. Allocates.
     * @param hitPoint receives the narrowed point inside, moved onto the face
     * @return the face, numbered as Direction.get3DDataValue
     */
    public static int hitFace(SnowShapeVolume volume, int flake, double leafPorosity, double[] passedPoint,
            double[] insidePoint, double[] hitPoint) {
        double[] passed = passedPoint.clone();
        double[] inside = insidePoint.clone();
        double[] middle = new double[3];
        for (int refinement = 0; refinement < HIT_REFINEMENTS; refinement++) {
            for (int axis = 0; axis < 3; axis++) {
                middle[axis] = 0.5 * (passed[axis] + inside[axis]);
            }
            if (hitIdAt(volume, flake, leafPorosity, middle) != 0) {
                System.arraycopy(middle, 0, inside, 0, 3);
            } else {
                System.arraycopy(middle, 0, passed, 0, 3);
            }
        }
        double[] reach = new double[3];
        int[] passedVoxel = new int[3];
        int[] insideVoxel = new int[3];
        double largestReach = 0.0;
        for (int axis = 0; axis < 3; axis++) {
            passedVoxel[axis] = (int) Math.floor(passed[axis] * SnowShapes.SIDE);
            insideVoxel[axis] = (int) Math.floor(inside[axis] * SnowShapes.SIDE);
            reach[axis] = passedVoxel[axis] != insideVoxel[axis] ? Math.abs(inside[axis] - passed[axis]) : 0.0;
            largestReach = Math.max(largestReach, reach[axis]);
        }
        if (!(largestReach > 0.0)) {
            for (int axis = 0; axis < 3; axis++) {
                reach[axis] = Math.abs(inside[axis] - passed[axis]);
            }
        }
        int axis = reach[0] >= reach[1] && reach[0] >= reach[2] ? 0 : (reach[1] >= reach[2] ? 1 : 2);
        System.arraycopy(inside, 0, hitPoint, 0, 3);
        hitPoint[axis] = Math.max(passedVoxel[axis], insideVoxel[axis]) / (double) SnowShapes.SIDE;
        boolean positive = inside[axis] - passed[axis] > 0.0;
        if (axis == 0) {
            return positive ? 4 : 5;
        }
        if (axis == 1) {
            return positive ? 0 : 1;
        }
        return positive ? 2 : 3;
    }

    /**
     * How much of a splat shows at an age in seconds, which fades over the second half of its life.
     */
    public static double splatWeight(double age, double splatSeconds) {
        if (!(age < splatSeconds)) {
            return 0.0;
        }
        double fade = clamp((age - 0.5 * splatSeconds) / (0.5 * splatSeconds));
        return 1.0 - fade * fade * (3.0 - 2.0 * fade);
    }

    /**
     * Whether a splat stored relative to the fixed splat origin lies within half a box of the camera on every axis,
     * hiding splats left behind by fast flight or teleports instead of wrapping them ahead of the camera.
     */
    public static boolean splatVisible(double offsetX, double offsetY, double offsetZ, double boxSide) {
        double halfSide = 0.5 * boxSide;
        return Math.abs(offsetX) <= halfSide && Math.abs(offsetY) <= halfSide && Math.abs(offsetZ) <= halfSide;
    }

    private static double mix(double from, double to, double share) {
        return from + (to - from) * share;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
