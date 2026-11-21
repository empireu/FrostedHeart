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
 * Finds how far vanilla's terrain fog may end without hiding anything the height fog still shows, and rounds that
 * distance to the fog end vanilla takes.
 * Vanilla's terrain fog is linear in the cylinder distance max(horizontal distance, |vertical offset|) from the eye, so
 * a fog end of D also culls the terrain beyond D. The distance here is the shortest one at which every block position
 * at least that far away already has an optical depth of OPAQUE_OPTICAL_DEPTH, where the height fog alone hides it.
 * The air between the fog's structures is thinner than the height fog out to their range, and the distance then takes
 * that clear stretch of every segment into account, so that the culling does not hide what shows through the gaps.
 * Every method is a pure function of its arguments, allocates nothing, and is safe to call from any thread.
 */
public final class FogCullingDistance {
    /**
     * The optical depth at which one step of an 8-bit color channel is all the contrast that is left, which is
     * Math.log(510.0). A position at or beyond the culling distance is hidden by at least this much fog.
     */
    public static final double OPAQUE_OPTICAL_DEPTH = Math.log(510.0);
    /**
     * The step in blocks that vanilla's fog end moves in, which the rounded distance is a multiple of.
     */
    public static final double END_STEP = 8.0;
    /**
     * How far in blocks the culling distance may fall below a held fog end before the end drops, which keeps the end
     * from flickering while the distance wanders across a multiple of END_STEP.
     */
    public static final double HYSTERESIS = 12.0;
    private static final int BISECTION_ITERATIONS = 30;
    private static final int WALL_SAMPLE_COUNT = 33;
    private static final int GOLDEN_SECTION_ITERATIONS = 24;
    private static final double GOLDEN_SECTION_RATIO = 0.6180339887498949;

    private FogCullingDistance() {
    }

    /**
     * The shortest distance from the eye at which every block position at or beyond it is hidden by at least
     * OPAQUE_OPTICAL_DEPTH of the height fog.
     * The positions range over every horizontal distance and over every height from minimumHeight to maximumHeight.
     * The result is never shorter than the true distance, because the search keeps the upper end of its bracket.
     * It costs about 1900 evaluations of HeightFog.opticalDepth and allocates nothing.
     * @param eyeExtinction the extinction per block at the eye's height, positive
     * @param eyeHeight the eye's height in blocks, which may lie outside the build heights
     * @param heightScale the height scale of the fog in blocks, positive
     * @param minimumHeight the lowest build height of the level in blocks
     * @param maximumHeight the highest build height of the level in blocks
     * @param maximumDistance the longest distance in blocks the search may return
     * @return the distance in blocks, in the range (0, maximumDistance], or Double.POSITIVE_INFINITY when no distance
     *         up to maximumDistance is opaque enough, when eyeExtinction is not positive, or when maximumDistance is
     *         not positive
     */
    public static double opaqueDistance(double eyeExtinction, double eyeHeight, double heightScale,
            double minimumHeight, double maximumHeight, double maximumDistance) {
        return opaqueDistance(eyeExtinction, eyeHeight, heightScale, minimumHeight, maximumHeight, maximumDistance,
                1.0, 0.0);
    }

    /**
     * The shortest distance from the eye at which every block position at or beyond it is hidden by at least
     * OPAQUE_OPTICAL_DEPTH of the fog, whose air between the structures is thin out to a range.
     * The positions range over every horizontal distance and over every height from minimumHeight to maximumHeight.
     * Every optical depth that the search evaluates is the lower bound along its segment: the fog of the first
     * clearRange blocks is thinned by 1 - clearAirDensity, and beyond it the fog is the mean fog.
     * The result is never shorter than the true distance, because the search keeps the upper end of its bracket.
     * It costs about 1900 evaluations of HeightFog.opticalDepth and allocates nothing.
     * @param eyeExtinction the extinction per block at the eye's height, positive
     * @param eyeHeight the eye's height in blocks, which may lie outside the build heights
     * @param heightScale the height scale of the fog in blocks, positive
     * @param minimumHeight the lowest build height of the level in blocks
     * @param maximumHeight the highest build height of the level in blocks
     * @param maximumDistance the longest distance in blocks the search may return
     * @param clearAirDensity the density of the clear air between the structures, clamped to 0 to 1, where NaN counts
     *        as 1
     * @param clearRange the distance in blocks out to which the clear air reaches, where NaN or not positive means no
     *        clear stretch
     * @return the distance in blocks, in the range (0, maximumDistance], or Double.POSITIVE_INFINITY when no distance
     *         up to maximumDistance is opaque enough, when eyeExtinction is not positive, or when maximumDistance is
     *         not positive
     */
    public static double opaqueDistance(double eyeExtinction, double eyeHeight, double heightScale,
            double minimumHeight, double maximumHeight, double maximumDistance, double clearAirDensity,
            double clearRange) {
        if (!(eyeExtinction > 0.0) || !(maximumDistance > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        double countedClearAirDensity = Double.isNaN(clearAirDensity) || clearAirDensity > 1.0 ? 1.0
                : Math.max(0.0, clearAirDensity);
        double countedClearRange = clearRange > 0.0 ? clearRange : 0.0;
        double leastVerticalOffset = minimumHeight - eyeHeight;
        double greatestVerticalOffset = maximumHeight - eyeHeight;
        double distance = maximumDistance;
        if (!(leastOpticalDepthBeyond(eyeExtinction, heightScale, leastVerticalOffset, greatestVerticalOffset,
                distance, countedClearAirDensity, countedClearRange) >= OPAQUE_OPTICAL_DEPTH)) {
            return Double.POSITIVE_INFINITY;
        }
        double nearestHiddenDistance = 0.0;
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double middleDistance = 0.5 * (nearestHiddenDistance + distance);
            if (leastOpticalDepthBeyond(eyeExtinction, heightScale, leastVerticalOffset, greatestVerticalOffset,
                    middleDistance, countedClearAirDensity, countedClearRange) >= OPAQUE_OPTICAL_DEPTH) {
                distance = middleDistance;
            } else {
                nearestHiddenDistance = middleDistance;
            }
        }
        return distance;
    }

    /**
     * The least optical depth of any position at least distance blocks away from the eye, in optical depths.
     * The minimum over the wall at the horizontal distance of distance is taken from its bottom end, its 33 sampled
     * heights and a golden section search around the lowest of them. The minimum over the caps straight up and straight
     * down is taken from their nearest heights. Every depth is the lower bound along its segment for air that is thin
     * out to the clear range, as segmentOpticalDepth gives it.
     */
    private static double leastOpticalDepthBeyond(double eyeExtinction, double heightScale,
            double leastVerticalOffset, double greatestVerticalOffset, double distance, double clearAirDensity,
            double clearRange) {
        double leastDepth = Double.POSITIVE_INFINITY;
        double downwardEnd = Math.min(0.0, greatestVerticalOffset);
        if (leastVerticalOffset <= downwardEnd) {
            leastDepth = lengthOpticalDepth(eyeExtinction, heightScale, distance, downwardEnd, clearAirDensity,
                    clearRange);
        }
        double upwardStart = Math.max(0.0, leastVerticalOffset);
        if (upwardStart <= greatestVerticalOffset) {
            leastDepth = Math.min(leastDepth, wallLeastOpticalDepth(eyeExtinction, heightScale, distance, upwardStart,
                    greatestVerticalOffset, clearAirDensity, clearRange));
        }
        double upwardCap = Math.max(distance, leastVerticalOffset);
        if (upwardCap <= greatestVerticalOffset) {
            leastDepth = Math.min(leastDepth, segmentOpticalDepth(eyeExtinction, heightScale, upwardCap, upwardCap,
                    clearAirDensity, clearRange));
        }
        double downwardCap = Math.min(-distance, greatestVerticalOffset);
        if (downwardCap >= leastVerticalOffset) {
            leastDepth = Math.min(leastDepth, segmentOpticalDepth(eyeExtinction, heightScale, -downwardCap,
                    downwardCap, clearAirDensity, clearRange));
        }
        return leastDepth;
    }

    /**
     * The least optical depth along the wall of the cylinder at the given horizontal distance, over the given heights.
     */
    private static double wallLeastOpticalDepth(double eyeExtinction, double heightScale, double horizontalDistance,
            double upwardStart, double upwardEnd, double clearAirDensity, double clearRange) {
        double sampleStep = (upwardEnd - upwardStart) / (WALL_SAMPLE_COUNT - 1);
        double leastDepth = Double.POSITIVE_INFINITY;
        int leastIndex = 0;
        for (int sampleIndex = 0; sampleIndex < WALL_SAMPLE_COUNT; sampleIndex++) {
            boolean isLastSample = sampleIndex == WALL_SAMPLE_COUNT - 1;
            double verticalOffset = isLastSample ? upwardEnd : upwardStart + sampleStep * sampleIndex;
            double depth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, verticalOffset,
                    clearAirDensity, clearRange);
            if (depth < leastDepth) {
                leastDepth = depth;
                leastIndex = sampleIndex;
            }
        }
        double neighborStart = upwardStart;
        if (leastIndex > 0) {
            neighborStart = Math.max(upwardStart, upwardStart + sampleStep * (leastIndex - 1));
        }
        double neighborEnd = upwardEnd;
        if (leastIndex < WALL_SAMPLE_COUNT - 1) {
            neighborEnd = Math.min(upwardEnd, upwardStart + sampleStep * (leastIndex + 1));
        }
        return Math.min(leastDepth, goldenSectionLeastDepth(eyeExtinction, heightScale, horizontalDistance,
                neighborStart, neighborEnd, clearAirDensity, clearRange));
    }

    /**
     * The least optical depth along the wall of the cylinder between the given heights, found in
     * GOLDEN_SECTION_ITERATIONS steps of a golden section search.
     */
    private static double goldenSectionLeastDepth(double eyeExtinction, double heightScale,
            double horizontalDistance, double lowerOffset, double upperOffset, double clearAirDensity,
            double clearRange) {
        double lower = lowerOffset;
        double upper = upperOffset;
        double leftProbe = upper - GOLDEN_SECTION_RATIO * (upper - lower);
        double rightProbe = lower + GOLDEN_SECTION_RATIO * (upper - lower);
        double leftDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, leftProbe,
                clearAirDensity, clearRange);
        double rightDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, rightProbe,
                clearAirDensity, clearRange);
        for (int iteration = 0; iteration < GOLDEN_SECTION_ITERATIONS; iteration++) {
            if (leftDepth < rightDepth) {
                upper = rightProbe;
                rightProbe = leftProbe;
                rightDepth = leftDepth;
                leftProbe = upper - GOLDEN_SECTION_RATIO * (upper - lower);
                leftDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, leftProbe,
                        clearAirDensity, clearRange);
            } else {
                lower = leftProbe;
                leftProbe = rightProbe;
                leftDepth = rightDepth;
                rightProbe = lower + GOLDEN_SECTION_RATIO * (upper - lower);
                rightDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, rightProbe,
                        clearAirDensity, clearRange);
            }
        }
        return Math.min(leftDepth, rightDepth);
    }

    /**
     * The optical depth of a segment that reaches the given height at the given horizontal distance from the eye.
     */
    private static double lengthOpticalDepth(double eyeExtinction, double heightScale, double horizontalDistance,
            double verticalOffset, double clearAirDensity, double clearRange) {
        double length = Math.sqrt(horizontalDistance * horizontalDistance + verticalOffset * verticalOffset);
        return segmentOpticalDepth(eyeExtinction, heightScale, length, verticalOffset, clearAirDensity, clearRange);
    }

    /**
     * The optical depth of a segment from the eye, which is the height fog's own depth minus the part of it that the
     * clear air between the structures takes away, out to the clear range. With a clear air density of 1 or no clear
     * stretch it is exactly the height fog's depth, so that the bound without structures is the one it always was.
     */
    private static double segmentOpticalDepth(double eyeExtinction, double heightScale, double length,
            double verticalOffset, double clearAirDensity, double clearRange) {
        double fullDepth = HeightFog.opticalDepth(eyeExtinction, length, verticalOffset, heightScale);
        if (!(clearAirDensity < 1.0) || !(clearRange > 0.0) || !(length > 0.0)) {
            return fullDepth;
        }
        double clearShare = Math.min(1.0, clearRange / length);
        double innerDepth = HeightFog.opticalDepth(eyeExtinction, clearShare * length, clearShare * verticalOffset,
                heightScale);
        return Math.max(0.0, fullDepth - (1.0 - clearAirDensity) * innerDepth);
    }

    /**
     * The fog end in blocks that vanilla takes for the given culling distance, rounded up to a multiple of END_STEP and
     * held while the distance stays close.
     * The end rises at once when the distance passes it and falls only once the distance is HYSTERESIS blocks below it,
     * so it does not flicker between two values. A held end is kept as it is, so it is not always a multiple of
     * END_STEP.
     * @param distance the culling distance in blocks that the height fog needs, from FogCullingDistance.opaqueDistance
     * @param previousEnd the fog end in blocks of the previous frame, or positive infinity when there is none
     * @return the fog end in blocks, which is never below distance, or Double.POSITIVE_INFINITY when distance is
     *         infinite or NaN
     */
    public static double fogEnd(double distance, double previousEnd) {
        if (Double.isInfinite(distance) || Double.isNaN(distance)) {
            return Double.POSITIVE_INFINITY;
        }
        if (!Double.isInfinite(previousEnd) && !Double.isNaN(previousEnd) && previousEnd >= distance
                && previousEnd - distance < HYSTERESIS) {
            return previousEnd;
        }
        return Math.max(END_STEP, Math.ceil(distance / END_STEP) * END_STEP);
    }
}
