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

import com.teammoeg.frostedheart.content.climate.render.sky.DustColumnWindow;

/**
 * The optical depth of the open part of the segment from the eye to a scene point, which is the fog that the light of
 * the point crosses on its way to the eye.
 * A place on the segment is open when its column lies outside the column window of DustColumns, when that column is
 * not read yet, or when the place stands at or above the column's first open height. The open part of the segment is
 * then a set of intervals of its parameter, which the walk here finds by crossing the columns under the segment's
 * horizontal projection, and the optical depth of each interval is the closed form of HeightFog.opticalDepth at its two
 * ends, which is exact along one ray from the eye. The walk stops at once when the eye is not in the window or the
 * segment leaves it, so it costs nothing outdoors.
 * Every method is a pure function of its arguments, allocates nothing, and is safe to call from any thread.
 */
public final class ShelterRay {
    /**
     * The most columns the walk visits. A segment crosses at most 128 columns before it leaves a SIZE by SIZE window,
     * so the cap only guards against float trouble, and the rest of the segment then counts as open.
     */
    private static final int MAXIMUM_COLUMN_STEPS = 160;
    /**
     * The horizontal length in blocks below which a segment counts as staying in the eye's column, which keeps the walk
     * off a division by a horizontal offset of nearly 0.
     */
    private static final double MINIMUM_HORIZONTAL_LENGTH = 1.0e-4;
    /**
     * The first open height at or above which a column is not read yet, at half of DustColumnWindow.CLOSED.
     */
    private static final double UNREAD_HEIGHT = 0.5 * DustColumnWindow.CLOSED;

    private ShelterRay() {
    }

    /**
     * The optical depth of the open part of the segment from the eye to the scene point eye + offset, in optical depths.
     * The segment runs from the parameter 0 at the eye to 1 at the scene point, and its optical depth from the eye up to
     * the parameter s is HeightFog.opticalDepth(eyeExtinction, s * |offset|, s * offsetY, heightScale). A column the
     * walk leaves is counted from the window's first column, and its height sits at the texel that
     * DustColumnWindow.texelIndex gives for that column's world coordinates.
     * The result is 0 when every place on the segment is sheltered, and at most HeightFog.MAXIMUM_OPTICAL_DEPTH when all
     * of it is open. A segment whose offset or eye position is not finite gives 0 rather than an error, and one that the
     * walk gives up on counts the rest of its length as open.
     * @param heights the first open heights of the window in blocks, which holds DustColumnWindow.COLUMNS of them
     * @param windowOriginX the texel of the window's first column in x, from 0 to SIZE - 1
     * @param windowOriginZ the texel of the window's first column in z, from 0 to SIZE - 1
     * @param eyeInWindowX the eye's column in x, counted from the window's first column
     * @param eyeInWindowZ the eye's column in z, counted from the window's first column
     * @param eyeFractionX the eye's place in its block in x, from 0 to 1
     * @param eyeFractionZ the eye's place in its block in z, from 0 to 1
     * @param eyeY the eye's height in blocks
     * @param offsetX the offset of the scene point from the eye in x, in blocks
     * @param offsetY the offset of the scene point from the eye in y, in blocks
     * @param offsetZ the offset of the scene point from the eye in z, in blocks
     * @param eyeExtinction the extinction per block at the eye's height
     * @param heightScale the height scale of the fog in blocks, positive
     * @return the open optical depth along the whole segment, which is 0 when no part of it is open
     */
    public static double openOpticalDepth(float[] heights, int windowOriginX, int windowOriginZ, int eyeInWindowX,
            int eyeInWindowZ, double eyeFractionX, double eyeFractionZ, double eyeY, double offsetX, double offsetY,
            double offsetZ, double eyeExtinction, double heightScale) {
        if (!Double.isFinite(eyeY) || !Double.isFinite(eyeFractionX) || !Double.isFinite(eyeFractionZ)
                || !Double.isFinite(offsetX) || !Double.isFinite(offsetY) || !Double.isFinite(offsetZ)) {
            return 0.0;
        }
        double segmentLength = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        double horizontalLength = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
        double eyeColumnPositionX = eyeInWindowX + eyeFractionX;
        double eyeColumnPositionZ = eyeInWindowZ + eyeFractionZ;
        int columnX = (int) Math.floor(eyeColumnPositionX);
        int columnZ = (int) Math.floor(eyeColumnPositionZ);
        int columnStepX = 0;
        int columnStepZ = 0;
        double nextBoundaryX = Double.POSITIVE_INFINITY;
        double nextBoundaryZ = Double.POSITIVE_INFINITY;
        double boundaryStepX = Double.POSITIVE_INFINITY;
        double boundaryStepZ = Double.POSITIVE_INFINITY;
        if (horizontalLength >= MINIMUM_HORIZONTAL_LENGTH) {
            if (offsetX != 0.0) {
                columnStepX = offsetX > 0.0 ? 1 : -1;
                nextBoundaryX = (columnX + (columnStepX > 0 ? 1 : 0) - eyeColumnPositionX) / offsetX;
                boundaryStepX = 1.0 / Math.abs(offsetX);
            }
            if (offsetZ != 0.0) {
                columnStepZ = offsetZ > 0.0 ? 1 : -1;
                nextBoundaryZ = (columnZ + (columnStepZ > 0 ? 1 : 0) - eyeColumnPositionZ) / offsetZ;
                boundaryStepZ = 1.0 / Math.abs(offsetZ);
            }
        }
        double openDepth = 0.0;
        double openRunStart = 0.0;
        boolean inOpenRun = false;
        double sEnter = 0.0;
        for (int columnStep = 0; columnStep < MAXIMUM_COLUMN_STEPS; columnStep++) {
            if (!(sEnter < 1.0)) {
                break;
            }
            if (columnX < 0 || columnX >= DustColumnWindow.SIZE || columnZ < 0
                    || columnZ >= DustColumnWindow.SIZE) {
                if (!inOpenRun) {
                    openRunStart = sEnter;
                    inOpenRun = true;
                }
                break;
            }
            double sExit = Math.min(Math.min(nextBoundaryX, nextBoundaryZ), 1.0);
            float firstOpenY = heights[DustColumnWindow.texelIndex(windowOriginX + columnX, windowOriginZ + columnZ)];
            boolean openAtStart;
            boolean transitionsInside;
            double sCross = 0.0;
            if (firstOpenY >= UNREAD_HEIGHT || offsetY == 0.0) {
                openAtStart = firstOpenY >= UNREAD_HEIGHT || eyeY >= firstOpenY;
                transitionsInside = false;
            } else {
                sCross = (firstOpenY - eyeY) / offsetY;
                if (offsetY > 0.0) {
                    openAtStart = sCross <= sEnter;
                    transitionsInside = !openAtStart && sCross < sExit;
                } else {
                    openAtStart = !(sCross < sEnter);
                    transitionsInside = openAtStart && sCross < sExit;
                }
            }
            if (openAtStart) {
                if (!inOpenRun) {
                    openRunStart = sEnter;
                    inOpenRun = true;
                }
            } else if (inOpenRun) {
                openDepth += openRunDepth(openRunStart, sEnter, segmentLength, offsetY, eyeExtinction, heightScale);
                inOpenRun = false;
            }
            if (transitionsInside) {
                if (inOpenRun) {
                    openDepth += openRunDepth(openRunStart, sCross, segmentLength, offsetY, eyeExtinction,
                            heightScale);
                    inOpenRun = false;
                } else {
                    openRunStart = sCross;
                    inOpenRun = true;
                }
            }
            sEnter = sExit;
            if (!(sEnter < 1.0)) {
                break;
            }
            if (nextBoundaryX < nextBoundaryZ) {
                columnX += columnStepX;
                nextBoundaryX += boundaryStepX;
            } else if (nextBoundaryZ < nextBoundaryX) {
                columnZ += columnStepZ;
                nextBoundaryZ += boundaryStepZ;
            } else {
                columnX += columnStepX;
                columnZ += columnStepZ;
                nextBoundaryX += boundaryStepX;
                nextBoundaryZ += boundaryStepZ;
            }
        }
        if (!inOpenRun) {
            openRunStart = sEnter;
        }
        return openDepth + openRunDepth(openRunStart, 1.0, segmentLength, offsetY, eyeExtinction, heightScale);
    }

    /**
     * The optical depth of the segment between two of its parameters, in optical depths, which is never below 0 because
     * the clamp of HeightFog.opticalDepth can make its two ends equal.
     */
    private static double openRunDepth(double from, double to, double segmentLength, double verticalOffset,
            double eyeExtinction, double heightScale) {
        return Math.max(0.0, tauAt(to, segmentLength, verticalOffset, eyeExtinction, heightScale)
                - tauAt(from, segmentLength, verticalOffset, eyeExtinction, heightScale));
    }

    /**
     * The optical depth from the eye up to the given parameter of a segment, in optical depths, as
     * HeightFog.opticalDepth gives it.
     */
    private static double tauAt(double s, double segmentLength, double verticalOffset, double eyeExtinction,
            double heightScale) {
        return HeightFog.opticalDepth(eyeExtinction, s * segmentLength, s * verticalOffset, heightScale);
    }
}
