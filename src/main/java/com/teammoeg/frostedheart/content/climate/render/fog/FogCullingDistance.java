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
 * The air between the fog's structures, inside deep cavities and inside the near shelter region can be thinner than the
 * mean height fog out to their respective ranges, and the distance takes that lower-bound stretch of every segment into
 * account so that culling does not cut off what thinner fog or covered openings show.
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
     */
    public static double opaqueDistance(double eyeExtinction, double eyeHeight, double heightScale,
            double minimumHeight, double maximumHeight, double maximumDistance) {
        return opaqueDistance(eyeExtinction, eyeHeight, heightScale, minimumHeight, maximumHeight, maximumDistance,
                1.0, 0.0, 1.0, 0.0, 0.0);
    }

    /**
     * The shortest distance from the eye at which every block position at or beyond it is hidden by at least
     * OPAQUE_OPTICAL_DEPTH of the fog, whose air between the structures is thin out to a range.
     */
    public static double opaqueDistance(double eyeExtinction, double eyeHeight, double heightScale,
            double minimumHeight, double maximumHeight, double maximumDistance, double clearAirDensity,
            double clearRange) {
        return opaqueDistance(eyeExtinction, eyeHeight, heightScale, minimumHeight, maximumHeight, maximumDistance,
                clearAirDensity, clearRange, 1.0, 0.0, 0.0);
    }

    /**
     * The shortest distance from the eye at which every block position at or beyond it is hidden by at least
     * OPAQUE_OPTICAL_DEPTH of the fog, accounting for clear air between structures out to clearRange, cavity thinning
     * down to cavityDensity out to cavityRange, and sheltered air inside the near field out to shelterRange.
     * @param clearAirDensity minimum density between fog structures in [0, 1], where NaN counts as 1
     * @param clearRange distance in blocks out to which fog structures reach
     * @param cavityDensity minimum density share inside deep cavities in [0, 1], where NaN counts as 1
     * @param cavityRange distance in blocks out to which the froxel fog applies cavity thinning
     * @param shelterRange distance in blocks out to which the near shelter region can hold sheltered air
     */
    public static double opaqueDistance(double eyeExtinction, double eyeHeight, double heightScale,
            double minimumHeight, double maximumHeight, double maximumDistance, double clearAirDensity,
            double clearRange, double cavityDensity, double cavityRange, double shelterRange) {
        if (!(eyeExtinction > 0.0) || !(maximumDistance > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        double countedClearAirDensity = Double.isNaN(clearAirDensity) || clearAirDensity > 1.0 ? 1.0
                : Math.max(0.0, clearAirDensity);
        double countedClearRange = clearRange > 0.0 ? clearRange : 0.0;
        double countedCavityDensity = Double.isNaN(cavityDensity) || cavityDensity > 1.0 ? 1.0
                : Math.max(0.0, cavityDensity);
        double countedCavityRange = cavityRange > 0.0 ? cavityRange : 0.0;
        double countedShelterRange = shelterRange > 0.0 ? shelterRange : 0.0;
        double leastVerticalOffset = minimumHeight - eyeHeight;
        double greatestVerticalOffset = maximumHeight - eyeHeight;
        double distance = maximumDistance;
        if (!(leastOpticalDepthBeyond(eyeExtinction, heightScale, leastVerticalOffset, greatestVerticalOffset,
                distance, countedClearAirDensity, countedClearRange, countedCavityDensity, countedCavityRange,
                countedShelterRange) >= OPAQUE_OPTICAL_DEPTH)) {
            return Double.POSITIVE_INFINITY;
        }
        double nearestHiddenDistance = 0.0;
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double middleDistance = 0.5 * (nearestHiddenDistance + distance);
            if (leastOpticalDepthBeyond(eyeExtinction, heightScale, leastVerticalOffset, greatestVerticalOffset,
                    middleDistance, countedClearAirDensity, countedClearRange, countedCavityDensity,
                    countedCavityRange, countedShelterRange) >= OPAQUE_OPTICAL_DEPTH) {
                distance = middleDistance;
            } else {
                nearestHiddenDistance = middleDistance;
            }
        }
        return distance;
    }

    private static double leastOpticalDepthBeyond(double eyeExtinction, double heightScale,
            double leastVerticalOffset, double greatestVerticalOffset, double distance, double clearAirDensity,
            double clearRange, double cavityDensity, double cavityRange, double shelterRange) {
        double leastDepth = Double.POSITIVE_INFINITY;
        double downwardEnd = Math.min(0.0, greatestVerticalOffset);
        if (leastVerticalOffset <= downwardEnd) {
            leastDepth = lengthOpticalDepth(eyeExtinction, heightScale, distance, downwardEnd, clearAirDensity,
                    clearRange, cavityDensity, cavityRange, shelterRange);
        }
        double upwardStart = Math.max(0.0, leastVerticalOffset);
        if (upwardStart <= greatestVerticalOffset) {
            leastDepth = Math.min(leastDepth, wallLeastOpticalDepth(eyeExtinction, heightScale, distance, upwardStart,
                    greatestVerticalOffset, clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange));
        }
        double upwardCap = Math.max(distance, leastVerticalOffset);
        if (upwardCap <= greatestVerticalOffset) {
            leastDepth = Math.min(leastDepth, segmentOpticalDepth(eyeExtinction, heightScale, upwardCap, upwardCap,
                    clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange));
        }
        double downwardCap = Math.min(-distance, greatestVerticalOffset);
        if (downwardCap >= leastVerticalOffset) {
            leastDepth = Math.min(leastDepth, segmentOpticalDepth(eyeExtinction, heightScale, -downwardCap,
                    downwardCap, clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange));
        }
        return leastDepth;
    }

    private static double wallLeastOpticalDepth(double eyeExtinction, double heightScale, double horizontalDistance,
            double upwardStart, double upwardEnd, double clearAirDensity, double clearRange, double cavityDensity,
            double cavityRange, double shelterRange) {
        double sampleStep = (upwardEnd - upwardStart) / (WALL_SAMPLE_COUNT - 1);
        double leastDepth = Double.POSITIVE_INFINITY;
        int leastIndex = 0;
        for (int sampleIndex = 0; sampleIndex < WALL_SAMPLE_COUNT; sampleIndex++) {
            boolean isLastSample = sampleIndex == WALL_SAMPLE_COUNT - 1;
            double verticalOffset = isLastSample ? upwardEnd : upwardStart + sampleStep * sampleIndex;
            double depth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, verticalOffset,
                    clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange);
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
                neighborStart, neighborEnd, clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange));
    }

    private static double goldenSectionLeastDepth(double eyeExtinction, double heightScale,
            double horizontalDistance, double lowerOffset, double upperOffset, double clearAirDensity,
            double clearRange, double cavityDensity, double cavityRange, double shelterRange) {
        double lower = lowerOffset;
        double upper = upperOffset;
        double leftProbe = upper - GOLDEN_SECTION_RATIO * (upper - lower);
        double rightProbe = lower + GOLDEN_SECTION_RATIO * (upper - lower);
        double leftDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, leftProbe,
                clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange);
        double rightDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, rightProbe,
                clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange);
        for (int iteration = 0; iteration < GOLDEN_SECTION_ITERATIONS; iteration++) {
            if (leftDepth < rightDepth) {
                upper = rightProbe;
                rightProbe = leftProbe;
                rightDepth = leftDepth;
                leftProbe = upper - GOLDEN_SECTION_RATIO * (upper - lower);
                leftDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, leftProbe,
                        clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange);
            } else {
                lower = leftProbe;
                leftProbe = rightProbe;
                leftDepth = rightDepth;
                rightProbe = lower + GOLDEN_SECTION_RATIO * (upper - lower);
                rightDepth = lengthOpticalDepth(eyeExtinction, heightScale, horizontalDistance, rightProbe,
                        clearAirDensity, clearRange, cavityDensity, cavityRange, shelterRange);
            }
        }
        return Math.min(leftDepth, rightDepth);
    }

    private static double lengthOpticalDepth(double eyeExtinction, double heightScale, double horizontalDistance,
            double verticalOffset, double clearAirDensity, double clearRange, double cavityDensity,
            double cavityRange, double shelterRange) {
        double length = Math.sqrt(horizontalDistance * horizontalDistance + verticalOffset * verticalOffset);
        return segmentOpticalDepth(eyeExtinction, heightScale, length, verticalOffset, clearAirDensity, clearRange,
                cavityDensity, cavityRange, shelterRange);
    }

    /**
     * Lower-bound optical depth of a segment from the eye through the piecewise-constant minimum density profile:
     * 0 on [0, shelterRange], clearAirDensity * cavityDensity while inside both clearRange and cavityRange, each
     * factor alone past the shorter range, and 1 past both.
     */
    private static double segmentOpticalDepth(double eyeExtinction, double heightScale, double length,
            double verticalOffset, double clearAirDensity, double clearRange, double cavityDensity,
            double cavityRange, double shelterRange) {
        double fullDepth = HeightFog.opticalDepth(eyeExtinction, length, verticalOffset, heightScale);
        if (!(length > 0.0)) {
            return fullDepth;
        }
        boolean hasClear = clearAirDensity < 1.0 && clearRange > 0.0;
        boolean hasCavity = cavityDensity < 1.0 && cavityRange > 0.0;
        boolean hasShelter = shelterRange > 0.0;
        if (!hasClear && !hasCavity && !hasShelter) {
            return fullDepth;
        }
        if (!hasCavity && !hasShelter) {
            double clearShare = Math.min(1.0, clearRange / length);
            double innerDepth = HeightFog.opticalDepth(eyeExtinction, clearShare * length,
                    clearShare * verticalOffset, heightScale);
            return Math.max(0.0, fullDepth - (1.0 - clearAirDensity) * innerDepth);
        }
        double effectiveClearRange = hasClear ? clearRange : 0.0;
        double effectiveCavityRange = hasCavity ? cavityRange : 0.0;
        double effectiveClearDensity = hasClear ? clearAirDensity : 1.0;
        double effectiveCavityDensity = hasCavity ? cavityDensity : 1.0;
        double b1 = Math.min(length, hasShelter ? shelterRange : 0.0);
        double c1 = Math.min(length, Math.max(b1, effectiveClearRange));
        double c2 = Math.min(length, Math.max(b1, effectiveCavityRange));
        double mid1 = Math.min(c1, c2);
        double mid2 = Math.max(c1, c2);

        double tauB1 = b1 > 0.0
                ? HeightFog.opticalDepth(eyeExtinction, b1, (b1 / length) * verticalOffset, heightScale) : 0.0;
        double tauMid1 = mid1 > b1
                ? HeightFog.opticalDepth(eyeExtinction, mid1, (mid1 / length) * verticalOffset, heightScale) : tauB1;
        double tauMid2 = mid2 > mid1
                ? HeightFog.opticalDepth(eyeExtinction, mid2, (mid2 / length) * verticalOffset, heightScale) : tauMid1;

        double depth = 0.0;
        if (mid1 > b1) {
            depth += effectiveClearDensity * effectiveCavityDensity * Math.max(0.0, tauMid1 - tauB1);
        }
        if (mid2 > mid1) {
            double midFactor = c1 > c2 ? effectiveClearDensity : effectiveCavityDensity;
            depth += midFactor * Math.max(0.0, tauMid2 - tauMid1);
        }
        if (length > mid2) {
            depth += Math.max(0.0, fullDepth - tauMid2);
        }
        return Math.max(0.0, depth);
    }

    /**
     * The fog end in blocks that vanilla takes for the given culling distance, rounded up to a multiple of END_STEP and
     * held while the distance stays close.
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
