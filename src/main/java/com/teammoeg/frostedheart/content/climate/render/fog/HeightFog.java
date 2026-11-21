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
 * The height fog, whose extinction per block falls off exponentially with height, and the optical depth it adds along a
 * straight segment.
 * The extinction at a height y is referenceExtinction * exp(-(y - referenceHeight) / heightScale), with the reference
 * height at sea level, so the fog is thickest at sea level and thins out above it.
 * Every method is a pure function of its arguments, allocates nothing, and is safe to call from any thread.
 */
public final class HeightFog {
    /**
     * The optical depth at which the contrast of a black object against the fog falls to 2%, which is -ln 0.02 by
     * Koschmieder's visibility law.
     */
    public static final double VISIBILITY_OPTICAL_DEPTH = 3.912;
    /**
     * The largest optical depth any method returns, in optical depths. It stands for opaque fog, and keeps the
     * transmittance at or above exp(-64).
     */
    public static final double MAXIMUM_OPTICAL_DEPTH = 64.0;
    /**
     * The absolute argument of heightFactor below which it uses its series instead of its closed form. The two forms
     * agree to about 4e-11 there.
     */
    public static final double SERIES_LIMIT = 1.0e-3;
    /**
     * The largest absolute value of an exp argument. Arguments are clamped to it, so that the exponents of this class
     * never overflow and no result becomes infinite.
     */
    public static final double MAXIMUM_EXPONENT = 60.0;

    private HeightFog() {
    }

    /**
     * The extinction per block that brings the contrast of a black object down to 2% at the given visibility, which is
     * VISIBILITY_OPTICAL_DEPTH / visibility.
     * @param visibility the visibility in blocks, positive or infinite
     * @return the extinction per block, or 0 for an infinite visibility, so that a clear day has no fog, and 0 for a
     *         visibility that is zero, negative or NaN, so that a bad value means no fog rather than infinite fog
     */
    public static double extinctionFromVisibility(double visibility) {
        if (!(visibility > 0.0) || Double.isInfinite(visibility)) {
            return 0.0;
        }
        return VISIBILITY_OPTICAL_DEPTH / visibility;
    }

    /**
     * The extinction per block at the given height, which is referenceExtinction * exp(-(y - referenceHeight) /
     * heightScale).
     * @param referenceExtinction the extinction per block at referenceHeight, usually positive
     * @param referenceHeight the height in blocks at which the extinction is referenceExtinction, usually sea level
     * @param heightScale the height in blocks over which the extinction falls by a factor of e, positive
     * @param y the height in blocks to find the extinction at
     * @return the extinction per block at y, which has the sign of referenceExtinction. A heightScale that is not
     *         positive returns referenceExtinction, which means fog that is uniform in height. The exponent is clamped
     *         to +-MAXIMUM_EXPONENT, so the result stays finite.
     */
    public static double extinctionAt(double referenceExtinction, double referenceHeight, double heightScale,
            double y) {
        if (!(heightScale > 0.0)) {
            return referenceExtinction;
        }
        double exponent = -(y - referenceHeight) / heightScale;
        double clampedExponent = Math.max(-MAXIMUM_EXPONENT, Math.min(MAXIMUM_EXPONENT, exponent));
        return referenceExtinction * Math.exp(clampedExponent);
    }

    /**
     * The mean of the extinction along a straight segment over the extinction at its start, which is
     * f(x) = (1 - exp(-x)) / x. It is 1 at 0 and is positive and decreasing for every x, so it is about 1 / x far above
     * and grows without bound far below.
     * @param x the rise of the segment end over its start, in blocks, divided by the height scale
     * @return the factor, which is exactly 1 at 0 and stays positive and finite for every finite x. Arguments beyond
     *         +-MAXIMUM_EXPONENT are clamped to it, which changes nothing that matters at that distance.
     */
    public static double heightFactor(double x) {
        double clamped = Math.max(-MAXIMUM_EXPONENT, Math.min(MAXIMUM_EXPONENT, x));
        if (Math.abs(clamped) < SERIES_LIMIT) {
            return 1.0 - clamped / 2.0 + clamped * clamped / 6.0;
        }
        return (1.0 - Math.exp(-clamped)) / clamped;
    }

    /**
     * The optical depth from the eye along a straight segment, which is eyeExtinction * length *
     * heightFactor(verticalOffset / heightScale).
     * @param eyeExtinction the extinction per block at the eye's height
     * @param length the length of the segment in blocks
     * @param verticalOffset the height in blocks of the segment end above the eye, negative when it ends below. Its
     *        absolute value is at most length, which the caller guarantees.
     * @param heightScale the height scale of the fog in blocks, positive
     * @return the optical depth, never negative and at most MAXIMUM_OPTICAL_DEPTH. It is 0 when length or
     *         eyeExtinction is not positive, MAXIMUM_OPTICAL_DEPTH when the result would be NaN, and
     *         eyeExtinction * length when heightScale is not positive, which means fog that is uniform in height.
     */
    public static double opticalDepth(double eyeExtinction, double length, double verticalOffset, double heightScale) {
        if (!(length > 0.0) || !(eyeExtinction > 0.0)) {
            return 0.0;
        }
        double rayOpticalDepth;
        if (!(heightScale > 0.0)) {
            rayOpticalDepth = eyeExtinction * length;
        } else {
            rayOpticalDepth = eyeExtinction * length * heightFactor(verticalOffset / heightScale);
        }
        if (Double.isNaN(rayOpticalDepth)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        return Math.min(MAXIMUM_OPTICAL_DEPTH, rayOpticalDepth);
    }

    /**
     * The optical depth from the eye to infinity along a unit direction whose upward component is directionY, which is
     * eyeExtinction * heightScale / directionY, since the extinction at the point at distance s is
     * eyeExtinction * exp(-s * directionY / heightScale).
     * @param eyeExtinction the extinction per block at the eye's height, in optical depths per block
     * @param directionY the upward component of the unit direction of the ray
     * @param heightScale the height scale of the fog in blocks, positive
     * @return the optical depth, 0 when eyeExtinction is not positive, which includes NaN. It is MAXIMUM_OPTICAL_DEPTH
     *         when directionY or heightScale is not positive, which includes NaN, since such a ray never leaves the fog,
     *         and it is at most MAXIMUM_OPTICAL_DEPTH otherwise, which also covers a NaN result.
     */
    public static double skyOpticalDepth(double eyeExtinction, double directionY, double heightScale) {
        if (!(eyeExtinction > 0.0)) {
            return 0.0;
        }
        if (!(directionY > 0.0) || !(heightScale > 0.0)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        double skyOpticalDepth = eyeExtinction * heightScale / directionY;
        if (Double.isNaN(skyOpticalDepth)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        return Math.min(MAXIMUM_OPTICAL_DEPTH, skyOpticalDepth);
    }

    /**
     * The optical depth from the point at distance start along a unit direction to infinity, which is
     * eyeExtinction * heightScale * exp(-start * directionY / heightScale) / directionY.
     * @param eyeExtinction the extinction per block at the eye's height, in optical depths per block
     * @param start the distance in blocks from the eye to the point, where a negative or NaN start counts as 0
     * @param directionY the upward component of the unit direction of the ray
     * @param heightScale the height scale of the fog in blocks, positive
     * @return the optical depth, 0 when eyeExtinction is not positive or when the exponent start * directionY /
     *         heightScale passes MAXIMUM_EXPONENT, which includes NaN. It is MAXIMUM_OPTICAL_DEPTH when directionY or
     *         heightScale is not positive, which includes NaN, since such a ray never leaves the fog, and it is at most
     *         MAXIMUM_OPTICAL_DEPTH otherwise.
     */
    public static double skyTailOpticalDepth(double eyeExtinction, double start, double directionY,
            double heightScale) {
        if (!(eyeExtinction > 0.0)) {
            return 0.0;
        }
        if (!(directionY > 0.0) || !(heightScale > 0.0)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        double clampedStart = start > 0.0 ? start : 0.0;
        double exponent = clampedStart * directionY / heightScale;
        if (exponent > MAXIMUM_EXPONENT) {
            return 0.0;
        }
        double tailOpticalDepth = eyeExtinction * heightScale * Math.exp(-exponent) / directionY;
        if (Double.isNaN(tailOpticalDepth)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        return Math.min(MAXIMUM_OPTICAL_DEPTH, tailOpticalDepth);
    }

    /**
     * The share of the light that reaches the eye through the given optical depth, which is exp(-opticalDepth).
     * @param opticalDepth the optical depth along a segment, which the fog methods return in the range 0 to
     *        MAXIMUM_OPTICAL_DEPTH
     * @return the transmittance, from 1 at an optical depth of 0 down to exp(-MAXIMUM_OPTICAL_DEPTH) at an opaque one
     */
    public static double transmittance(double opticalDepth) {
        return Math.exp(-opticalDepth);
    }
}
