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
 * The rules that fill the fog of a full-resolution pixel in from four pixels walked at a lower resolution, which the
 * composite pass and the fallback of the fog follow.
 */
public final class FogUpsample {
    /**
     * The share of the pixel's own distance that the distance of a low-resolution sample may differ by to agree.
     */
    public static final double DEPTH_TOLERANCE_SHARE = 0.05;
    /**
     * The blocks that the distance of a low-resolution sample may differ by besides the share, so that a sample near
     * the eye agrees.
     */
    public static final double DEPTH_TOLERANCE_BLOCKS = 0.25;
    /**
     * The largest difference of the ratios of the agreeing samples that still averages them, above which the fog walks
     * the pixel at full resolution instead.
     */
    public static final double RATIO_SPREAD = 0.2;
    /**
     * The distance a low-resolution sample writes for a sky pixel.
     */
    public static final double SKY_DISTANCE = -1.0;
    /**
     * The smallest analytic depth, in optical depths, that the ratio divides by, below which the ratio is 1.
     */
    public static final double MINIMUM_ANALYTIC_DEPTH = 1.0e-6;
    /**
     * The share of the cap below which the fallback is allowed again, so that a frame at the cap does not toggle it.
     */
    public static final double REENABLE_SHARE = 0.8;
    /**
     * The smallest sum of the weights of the agreeing samples that the weighted mean divides by.
     */
    public static final double MINIMUM_WEIGHT_SUM = 1.0e-6;
    /**
     * The samples that a full-resolution pixel takes from the low-resolution target.
     */
    private static final int SAMPLE_COUNT = 4;

    private FogUpsample() {
    }

    /**
     * The divisor of the resolution that the fog is walked at.
     * @param setting the setting, where below 1.5 gives 1, below 3 gives 2, and higher gives 4
     * @return the divisor, 1, 2 or 4, and 2 for NaN
     */
    public static int divisorOf(double setting) {
        if (Double.isNaN(setting)) {
            return 2;
        }
        if (setting < 1.5) {
            return 1;
        }
        if (setting < 3.0) {
            return 2;
        }
        return 4;
    }

    /**
     * The size of the low-resolution target along an axis of the full-resolution screen.
     * @param fullSize the size of the screen along the axis, in pixels
     * @param divisor the divisor of the resolution, positive
     * @return the texels of the target along the axis, which rounds up so that the last block of pixels fits
     */
    public static int lowResolutionSize(int fullSize, int divisor) {
        return (fullSize + divisor - 1) / divisor;
    }

    /**
     * Whether a low-resolution pixel keeps the maximum depth of its block of full-resolution pixels rather than the
     * minimum. The two alternate in a checkerboard, so thin geometry survives in one of the two.
     * @param lowX the x of the low-resolution pixel
     * @param lowY the y of the low-resolution pixel
     * @return whether it keeps the maximum
     */
    public static boolean takesMaximumDepth(int lowX, int lowY) {
        return ((lowX + lowY) & 1) == 1;
    }

    /**
     * The ratio of the walked depth of a low-resolution pixel to the analytic depth of the same segment.
     * @param walkedDepth the optical depth the walk gives
     * @param analyticDepth the optical depth the height fog gives without the field, at an extinction of 1
     * @return the ratio, and 1 when the analytic depth is too small to divide by
     */
    public static double ratio(double walkedDepth, double analyticDepth) {
        if (!(analyticDepth >= MINIMUM_ANALYTIC_DEPTH)) {
            return 1.0;
        }
        return walkedDepth / analyticDepth;
    }

    /**
     * Finds the four low-resolution texels around a full-resolution pixel and their bilinear weights.
     * @param pixelX the x of the full-resolution pixel
     * @param pixelY the y of the full-resolution pixel
     * @param divisor the divisor of the resolution, positive
     * @param lowWidth the texels of the low-resolution target along x
     * @param lowHeight the texels of the low-resolution target along y
     * @param texelX the array of four ints that takes the x of each texel, in the order (x0, y0), (x1, y0), (x0, y1),
     *        (x1, y1)
     * @param texelY the array of four ints that takes the y of each texel, in the same order
     * @param weights the array of four doubles that takes the weight of each texel, in the same order
     */
    public static void footprint(int pixelX, int pixelY, int divisor, int lowWidth, int lowHeight, int[] texelX,
            int[] texelY, double[] weights) {
        double lowX = (pixelX + 0.5) / divisor - 0.5;
        double lowY = (pixelY + 0.5) / divisor - 0.5;
        int firstX = (int) Math.floor(lowX);
        int firstY = (int) Math.floor(lowY);
        double fractionX = lowX - firstX;
        double fractionY = lowY - firstY;
        int secondX = clampIndex(firstX + 1, lowWidth);
        int secondY = clampIndex(firstY + 1, lowHeight);
        firstX = clampIndex(firstX, lowWidth);
        firstY = clampIndex(firstY, lowHeight);
        texelX[0] = firstX;
        texelX[1] = secondX;
        texelX[2] = firstX;
        texelX[3] = secondX;
        texelY[0] = firstY;
        texelY[1] = firstY;
        texelY[2] = secondY;
        texelY[3] = secondY;
        weights[0] = (1.0 - fractionX) * (1.0 - fractionY);
        weights[1] = fractionX * (1.0 - fractionY);
        weights[2] = (1.0 - fractionX) * fractionY;
        weights[3] = fractionX * fractionY;
    }

    /**
     * The ratio that a full-resolution pixel takes from its four low-resolution samples, or NaN when it should walk at
     * full resolution.
     * @param ownDistance the distance of the pixel's own scene point, in blocks
     * @param distances the distances of the four samples, SKY_DISTANCE for a sample of the sky
     * @param ratios the ratios of the four samples
     * @param weights the bilinear weights of the four samples
     * @param fallbackAllowed whether a pixel that the samples do not support may walk at full resolution
     * @return the ratio, or NaN when the pixel should walk at full resolution
     */
    public static double resolve(double ownDistance, double[] distances, double[] ratios, double[] weights,
            boolean fallbackAllowed) {
        double tolerance = DEPTH_TOLERANCE_SHARE * ownDistance + DEPTH_TOLERANCE_BLOCKS;
        int agreeingCount = 0;
        int nearestIndex = -1;
        double nearestDifference = Double.POSITIVE_INFINITY;
        int nearestAgreeingIndex = -1;
        double nearestAgreeingDifference = Double.POSITIVE_INFINITY;
        double smallestAgreeingRatio = Double.POSITIVE_INFINITY;
        double largestAgreeingRatio = Double.NEGATIVE_INFINITY;
        double agreeingWeightSum = 0.0;
        double agreeingRatioSum = 0.0;
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            double distance = distances[sample];
            if (distance < 0.0) {
                continue;
            }
            double difference = Math.abs(distance - ownDistance);
            if (difference < nearestDifference) {
                nearestIndex = sample;
                nearestDifference = difference;
            }
            if (!(difference <= tolerance)) {
                continue;
            }
            agreeingCount++;
            if (difference < nearestAgreeingDifference) {
                nearestAgreeingIndex = sample;
                nearestAgreeingDifference = difference;
            }
            smallestAgreeingRatio = Math.min(smallestAgreeingRatio, ratios[sample]);
            largestAgreeingRatio = Math.max(largestAgreeingRatio, ratios[sample]);
            agreeingWeightSum += weights[sample];
            agreeingRatioSum += weights[sample] * ratios[sample];
        }
        if (agreeingCount == 0) {
            if (fallbackAllowed) {
                return Double.NaN;
            }
            return nearestIndex >= 0 ? ratios[nearestIndex] : 1.0;
        }
        if (largestAgreeingRatio - smallestAgreeingRatio > RATIO_SPREAD) {
            if (fallbackAllowed) {
                return Double.NaN;
            }
            return ratios[nearestAgreeingIndex];
        }
        if (!(agreeingWeightSum > MINIMUM_WEIGHT_SUM)) {
            return ratios[nearestAgreeingIndex];
        }
        return agreeingRatioSum / agreeingWeightSum;
    }

    /**
     * Whether the fallback may run, which stays on while the share stays at or below the cap and comes back on only
     * below a lower share, so that the count of the fallback does not toggle.
     * @param allowedBefore whether it was allowed in the frame before
     * @param share the share of the screen that fell back in the last frame, where NaN keeps the last answer
     * @param cap the largest share that may fall back
     * @return whether it is allowed now
     */
    public static boolean fallbackAllowed(boolean allowedBefore, double share, double cap) {
        if (Double.isNaN(share)) {
            return allowedBefore;
        }
        if (allowedBefore) {
            return share <= cap;
        }
        return share < REENABLE_SHARE * cap;
    }

    /**
     * The index clamped into the texels of the low-resolution target, which keeps a footprint at the edge inside it.
     */
    private static int clampIndex(int index, int size) {
        if (index < 0) {
            return 0;
        }
        if (index >= size) {
            return size - 1;
        }
        return index;
    }
}
