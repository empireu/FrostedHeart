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

import org.joml.Matrix4f;

/**
 * The Java twin of the lamp march of weather_fog_lamp.fsh, which the GameTests and the self test of LampFogPass compare
 * with the shader. A ray from the eye walks the world-aligned major-axis cell slabs of each cascade of a
 * LampLightField that it crosses, keeping to the cascade's lit box and skipping dark 8x8x8 coarse slot blocks. Each
 * lit piece adds the lamp color of the level in its middle times the light the fog takes out of the ray over the
 * piece, exp(-tau0) - exp(-tau1), where the optical depth is held at the largest found so far, so it never falls along
 * the ray. The march stops where less than LEAST_TRANSMITTANCE of the light gets through. Where the depth at the ray's
 * end lies below the largest found, as the interpolation of the froxel excess gives in shelters, the light is scaled
 * to the opacity at the end, which the composite draws the fog with. Allocates nothing once made. Single thread.
 */
public final class LampFogMarch {
    /**
     * The transmittance below which the march stops, its most pieces per ray, the distance a ray of the sky ends at, a
     * distance no crossing reaches, and the levels of the lamp color table. These must match weather_fog_lamp.fsh.
     */
    public static final double LEAST_TRANSMITTANCE = 1.0 / 512.0;
    public static final int MAXIMUM_STEPS = 512;
    public static final float SKY_DISTANCE = 60000.0F;
    public static final double NO_CROSSING = 1.0e30;
    public static final int LIFT_LEVELS = 16;
    /**
     * The optical depth below which the light is not scaled to the opacity at the ray's end, since that light is too
     * faint to see. It must match weather_fog_lamp.fsh.
     */
    public static final double LEAST_SCALED_DEPTH = 1.0e-6;
    /**
     * Major-axis slab thickness in cascade cells, locked to multiples of this count in world cell space. It must match
     * weather_fog_lamp.fsh.
     */
    public static final double STEP_CELLS = 2.0;
    private static final double COARSE_SAFE_MARGIN = 0.501;
    private static final double FRUSTUM_BOX_PAD = 0.05;
    private static final double INNER_REACH_MARGIN = 0.5;
    private static final int CASCADES = LampLightField.CASCADE_COUNT;

    /**
     * The fog's optical depth from the eye to a distance along the ray being marched.
     */
    public interface OpticalDepth {
        double at(double distance);
    }

    private final float[] sizes = new float[3 * CASCADES];
    private final float[] slotOrigins = new float[3 * CASCADES];
    private final float[] eyesInCascade = new float[3 * CASCADES];
    private final float[] cellsPerBlock = new float[3 * CASCADES];
    private final float[] inverseReaches = new float[3 * CASCADES];
    private final float[] litLows = new float[3 * CASCADES];
    private final float[] litHighs = new float[3 * CASCADES];
    private final float[] shares = new float[2];
    private final float[] nextShares = new float[2];
    private final double[] span = new double[2];
    private final double[] ownSpan = new double[2];
    private final double[] nextSpan = new double[2];
    private final int[] coarseScratch = new int[6];
    private LampLightField field;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private int count;
    private boolean clipped = true;
    private int lastSteps;
    private double runningDepth;
    private double runningTransmittance;
    private double depthDistance;
    private double splitRunningDepth;
    private boolean splitCaptured;
    private int steps;
    private final float[] splitScratch = new float[3];

    /**
     * Takes a field's uniforms for an eye, as LampFogPass sets them for the shader.
     * @return whether any cascade the shader reads holds a lit cell
     */
    public boolean prepare(LampLightField newField, double newEyeX, double newEyeY, double newEyeZ) {
        field = newField;
        eyeX = newEyeX;
        eyeY = newEyeY;
        eyeZ = newEyeZ;
        count = newField.shaderUniforms(newEyeX, newEyeY, newEyeZ, sizes, slotOrigins, eyesInCascade, cellsPerBlock,
                inverseReaches);
        return newField.shaderLitBoxes(litLows, litHighs);
    }

    /**
     * Tightens the prepared lit boxes against a fog-eye-centered view-projection frustum.
     * @return whether any cascade holds a lit coarse block intersecting the frustum
     */
    public boolean clipToFrustum(Matrix4f viewProjection) {
        return clipLitBoxesToFrustum(viewProjection, field, eyeX, eyeY, eyeZ, litLows, litHighs);
    }

    /**
     * Culls cascades whose lit coarse blocks lie outside the view frustum and tightens each remaining cascade's lit
     * box to the union of its visible lit coarse blocks. Allocates nothing.
     * @param viewProjection the matrix mapping fog-eye-relative offsets to clip space
     * @return whether any cascade remains visible
     */
    public static boolean clipLitBoxesToFrustum(Matrix4f viewProjection, LampLightField field, double eyeX,
            double eyeY, double eyeZ, float[] litLows, float[] litHighs) {
        float m00 = viewProjection.m00();
        float m01 = viewProjection.m01();
        float m03 = viewProjection.m03();
        float m10 = viewProjection.m10();
        float m11 = viewProjection.m11();
        float m13 = viewProjection.m13();
        float m20 = viewProjection.m20();
        float m21 = viewProjection.m21();
        float m23 = viewProjection.m23();
        float m30 = viewProjection.m30();
        float m31 = viewProjection.m31();
        float m33 = viewProjection.m33();
        if (!Float.isFinite(m00) || !Float.isFinite(m01) || !Float.isFinite(m03)
                || !Float.isFinite(m10) || !Float.isFinite(m11) || !Float.isFinite(m13)
                || !Float.isFinite(m20) || !Float.isFinite(m21) || !Float.isFinite(m23)
                || !Float.isFinite(m30) || !Float.isFinite(m31) || !Float.isFinite(m33)) {
            return true;
        }
        boolean anyVisible = false;
        int activeCount = field.activeCount();
        for (int index = 0; index < activeCount; index++) {
            LampLightCascade cascade = field.cascade(index);
            if (!cascade.isPlaced()) {
                break;
            }
            int base = 3 * index;
            if (litLows[base] > litHighs[base] || litLows[base + 1] > litHighs[base + 1]
                    || litLows[base + 2] > litHighs[base + 2]) {
                continue;
            }
            int width = cascade.cellWidth();
            int height = cascade.cellHeight();
            int sizeX = cascade.sizeX();
            int sizeY = cascade.sizeY();
            int sizeZ = cascade.sizeZ();
            double eyeInX = eyeX / width - cascade.originX();
            double eyeInY = eyeY / height - cascade.originY();
            double eyeInZ = eyeZ / width - cascade.originZ();
            double reachXZ = LampLightField.BAND_END * 0.5 * sizeX * width;
            double reachY = cascade.isFixedVertically() ? SKY_DISTANCE : LampLightField.BAND_END * 0.5 * sizeY * height;
            double innerReachXZ = 0.0;
            double innerReachY = 0.0;
            if (index > 0) {
                LampLightCascade previous = field.cascade(index - 1);
                innerReachXZ = Math.max(0.0,
                        LampLightField.BAND_START * 0.5 * previous.sizeX() * previous.cellWidth() - INNER_REACH_MARGIN);
                innerReachY = previous.isFixedVertically() ? Double.POSITIVE_INFINITY
                        : Math.max(0.0, LampLightField.BAND_START * 0.5 * previous.sizeY() * previous.cellHeight()
                                - INNER_REACH_MARGIN);
            }
            if (!cascadeBoxVisible(viewProjection, eyeInX, eyeInY, eyeInZ, width, height, reachXZ, reachY,
                    innerReachXZ, innerReachY, litLows[base], litLows[base + 1], litLows[base + 2], litHighs[base],
                    litHighs[base + 1], litHighs[base + 2])) {
                clearLitBox(litLows, litHighs, base);
                continue;
            }
            int originSlotX = cascade.slotX(cascade.originX());
            int originSlotY = cascade.slotY(cascade.originY());
            int originSlotZ = cascade.slotZ(cascade.originZ());
            float visibleLowX = LampLightField.EMPTY_BOX_LOW;
            float visibleLowY = LampLightField.EMPTY_BOX_LOW;
            float visibleLowZ = LampLightField.EMPTY_BOX_LOW;
            float visibleHighX = -LampLightField.EMPTY_BOX_LOW;
            float visibleHighY = -LampLightField.EMPTY_BOX_LOW;
            float visibleHighZ = -LampLightField.EMPTY_BOX_LOW;
            boolean anyCoarseVisible = false;
            int coarseWords = cascade.coarseY() * 2;
            for (int wordIndex = 0; wordIndex < coarseWords; wordIndex++) {
                int bits = cascade.coarseMaskWord(wordIndex);
                if (bits == 0) {
                    continue;
                }
                int coarseY = wordIndex >> 1;
                int zBase = (wordIndex & 1) << 2;
                int yOffset = (coarseY << LampLightCascade.COARSE_SHIFT) - originSlotY;
                if (yOffset < 0) {
                    yOffset += sizeY;
                }
                int ySpans = yOffset + LampLightCascade.COARSE_SIZE > sizeY ? 2 : 1;
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int coarseX = bit & 7;
                    int coarseZ = zBase | (bit >> 3);
                    int xOffset = (coarseX << LampLightCascade.COARSE_SHIFT) - originSlotX;
                    if (xOffset < 0) {
                        xOffset += sizeX;
                    }
                    int zOffset = (coarseZ << LampLightCascade.COARSE_SHIFT) - originSlotZ;
                    if (zOffset < 0) {
                        zOffset += sizeZ;
                    }
                    int xSpans = xOffset + LampLightCascade.COARSE_SIZE > sizeX ? 2 : 1;
                    int zSpans = zOffset + LampLightCascade.COARSE_SIZE > sizeZ ? 2 : 1;
                    for (int spanY = 0; spanY < ySpans; spanY++) {
                        int cellLowY = spanY == 0 ? yOffset : 0;
                        int cellHighY = spanY == 0 ? Math.min(yOffset + LampLightCascade.COARSE_SIZE, sizeY)
                                : yOffset + LampLightCascade.COARSE_SIZE - sizeY;
                        float blockLowY = cellLowY == 0 && litLows[base + 1] <= -LampLightField.UNBOUNDED_BOX
                                ? -LampLightField.UNBOUNDED_BOX : Math.max(litLows[base + 1], cellLowY - 0.5F);
                        float blockHighY = cellHighY == sizeY && litHighs[base + 1] >= LampLightField.UNBOUNDED_BOX
                                ? LampLightField.UNBOUNDED_BOX : Math.min(litHighs[base + 1], cellHighY + 0.5F);
                        if (!(blockLowY < blockHighY)) {
                            continue;
                        }
                        for (int spanZ = 0; spanZ < zSpans; spanZ++) {
                            int cellLowZ = spanZ == 0 ? zOffset : 0;
                            int cellHighZ = spanZ == 0 ? Math.min(zOffset + LampLightCascade.COARSE_SIZE, sizeZ)
                                    : zOffset + LampLightCascade.COARSE_SIZE - sizeZ;
                            float blockLowZ = cellLowZ == 0 && litLows[base + 2] <= -LampLightField.UNBOUNDED_BOX
                                    ? -LampLightField.UNBOUNDED_BOX : Math.max(litLows[base + 2], cellLowZ - 0.5F);
                            float blockHighZ = cellHighZ == sizeZ && litHighs[base + 2] >= LampLightField.UNBOUNDED_BOX
                                    ? LampLightField.UNBOUNDED_BOX : Math.min(litHighs[base + 2], cellHighZ + 0.5F);
                            if (!(blockLowZ < blockHighZ)) {
                                continue;
                            }
                            for (int spanX = 0; spanX < xSpans; spanX++) {
                                int cellLowX = spanX == 0 ? xOffset : 0;
                                int cellHighX = spanX == 0 ? Math.min(xOffset + LampLightCascade.COARSE_SIZE, sizeX)
                                        : xOffset + LampLightCascade.COARSE_SIZE - sizeX;
                                float blockLowX = cellLowX == 0 && litLows[base] <= -LampLightField.UNBOUNDED_BOX
                                        ? -LampLightField.UNBOUNDED_BOX : Math.max(litLows[base], cellLowX - 0.5F);
                                float blockHighX = cellHighX == sizeX && litHighs[base] >= LampLightField.UNBOUNDED_BOX
                                        ? LampLightField.UNBOUNDED_BOX : Math.min(litHighs[base], cellHighX + 0.5F);
                                if (!(blockLowX < blockHighX)) {
                                    continue;
                                }
                                if (cascadeBoxVisible(viewProjection, eyeInX, eyeInY, eyeInZ, width, height, reachXZ,
                                        reachY, innerReachXZ, innerReachY, blockLowX, blockLowY, blockLowZ, blockHighX,
                                        blockHighY, blockHighZ)) {
                                    anyCoarseVisible = true;
                                    if (blockLowX < visibleLowX) {
                                        visibleLowX = blockLowX;
                                    }
                                    if (blockLowY < visibleLowY) {
                                        visibleLowY = blockLowY;
                                    }
                                    if (blockLowZ < visibleLowZ) {
                                        visibleLowZ = blockLowZ;
                                    }
                                    if (blockHighX > visibleHighX) {
                                        visibleHighX = blockHighX;
                                    }
                                    if (blockHighY > visibleHighY) {
                                        visibleHighY = blockHighY;
                                    }
                                    if (blockHighZ > visibleHighZ) {
                                        visibleHighZ = blockHighZ;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!anyCoarseVisible) {
                clearLitBox(litLows, litHighs, base);
                continue;
            }
            litLows[base] = visibleLowX;
            litLows[base + 1] = visibleLowY;
            litLows[base + 2] = visibleLowZ;
            litHighs[base] = visibleHighX;
            litHighs[base + 1] = visibleHighY;
            litHighs[base + 2] = visibleHighZ;
            anyVisible = true;
        }
        return anyVisible;
    }

    private static void clearLitBox(float[] litLows, float[] litHighs, int base) {
        litLows[base] = LampLightField.EMPTY_BOX_LOW;
        litLows[base + 1] = LampLightField.EMPTY_BOX_LOW;
        litLows[base + 2] = LampLightField.EMPTY_BOX_LOW;
        litHighs[base] = -LampLightField.EMPTY_BOX_LOW;
        litHighs[base + 1] = -LampLightField.EMPTY_BOX_LOW;
        litHighs[base + 2] = -LampLightField.EMPTY_BOX_LOW;
    }

    private static boolean cascadeBoxVisible(Matrix4f viewProjection, double eyeInX, double eyeInY, double eyeInZ,
            int width, int height, double reachXZ, double reachY, double innerReachXZ, double innerReachY,
            float boxLowX, float boxLowY, float boxLowZ, float boxHighX, float boxHighY, float boxHighZ) {
        double minX = Math.max(-reachXZ, (boxLowX - eyeInX) * width);
        double maxX = Math.min(reachXZ, (boxHighX - eyeInX) * width);
        if (!(minX <= maxX)) {
            return false;
        }
        double minY = Math.max(-reachY, (boxLowY - eyeInY) * height);
        double maxY = Math.min(reachY, (boxHighY - eyeInY) * height);
        if (!(minY <= maxY)) {
            return false;
        }
        double minZ = Math.max(-reachXZ, (boxLowZ - eyeInZ) * width);
        double maxZ = Math.min(reachXZ, (boxHighZ - eyeInZ) * width);
        if (!(minZ <= maxZ)) {
            return false;
        }
        minX -= FRUSTUM_BOX_PAD;
        maxX += FRUSTUM_BOX_PAD;
        minY -= FRUSTUM_BOX_PAD;
        maxY += FRUSTUM_BOX_PAD;
        minZ -= FRUSTUM_BOX_PAD;
        maxZ += FRUSTUM_BOX_PAD;
        // Skip boxes completely inside the finer cascade's unblended interior where this cascade is never sampled:
        if (innerReachXZ > 0.0 && minX > -innerReachXZ && maxX < innerReachXZ && minZ > -innerReachXZ
                && maxZ < innerReachXZ && minY > -innerReachY && maxY < innerReachY) {
            return false;
        }
        double m03 = viewProjection.m03();
        double m13 = viewProjection.m13();
        double m23 = viewProjection.m23();
        double m33 = viewProjection.m33();
        // Front of the eye (clip.w >= 0), left, right, bottom and top frustum planes; no far plane because sky rays
        // march out to SKY_DISTANCE:
        if (boxOutsidePlane(m03, m13, m23, m33, minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        double m00 = viewProjection.m00();
        double m10 = viewProjection.m10();
        double m20 = viewProjection.m20();
        double m30 = viewProjection.m30();
        if (boxOutsidePlane(m03 + m00, m13 + m10, m23 + m20, m33 + m30, minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        if (boxOutsidePlane(m03 - m00, m13 - m10, m23 - m20, m33 - m30, minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        double m01 = viewProjection.m01();
        double m11 = viewProjection.m11();
        double m21 = viewProjection.m21();
        double m31 = viewProjection.m31();
        if (boxOutsidePlane(m03 + m01, m13 + m11, m23 + m21, m33 + m31, minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        if (boxOutsidePlane(m03 - m01, m13 - m11, m23 - m21, m33 - m31, minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        return true;
    }

    private static boolean boxOutsidePlane(double normalX, double normalY, double normalZ, double distance,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double positiveX = normalX >= 0.0 ? maxX : minX;
        double positiveY = normalY >= 0.0 ? maxY : minY;
        double positiveZ = normalZ >= 0.0 ? maxZ : minZ;
        return normalX * positiveX + normalY * positiveY + normalZ * positiveZ + distance < 0.0;
    }

    /**
     * Sets whether the march keeps to the lit boxes and coarse occupancy mask, as the shader does, or walks every
     * cascade's whole stretch, which the GameTests compare it with.
     */
    public void setClipped(boolean newClipped) {
        clipped = newClipped;
    }

    /**
     * The pieces the last march walked.
     */
    public int lastSteps() {
        return lastSteps;
    }

    /**
     * Marches a ray from the eye of the last prepare.
     * @param directionX the unit direction of the ray, with directionY and directionZ
     * @param fade the share the field has faded in by
     * @param lifts the lamp color of each level from 0 to 15, 3 entries per level
     * @param light receives the red, green and blue the fog adds
     */
    public void march(double directionX, double directionY, double directionZ, double endDistance, float fade,
            float[] lifts, OpticalDepth depth, float[] light) {
        marchSplit(directionX, directionY, directionZ, endDistance, endDistance, fade, lifts, depth, light,
                splitScratch);
    }

    /**
     * Marches a ray from the eye of the last prepare out to endDistance and, when splitDistance is below endDistance,
     * snapshots the accumulated lamp light at splitDistance in the same walk, scaling both endpoints independently.
     * Matches lampFogAlongSplit in weather_fog_lamp.fsh.
     * @param directionX the unit direction of the ray, with directionY and directionZ
     * @param fade the share the field has faded in by
     * @param lifts the lamp color of each level from 0 to 15, 3 entries per level
     * @param opaqueLight receives the red, green and blue the fog adds up to endDistance
     * @param translucentLight receives the red, green and blue the fog adds up to splitDistance
     */
    public void marchSplit(double directionX, double directionY, double directionZ, double endDistance,
            double splitDistance, float fade, float[] lifts, OpticalDepth depth, float[] opaqueLight,
            float[] translucentLight) {
        opaqueLight[0] = 0.0F;
        opaqueLight[1] = 0.0F;
        opaqueLight[2] = 0.0F;
        translucentLight[0] = 0.0F;
        translucentLight[1] = 0.0F;
        translucentLight[2] = 0.0F;
        runningDepth = 0.0;
        runningTransmittance = 1.0;
        depthDistance = 0.0;
        splitRunningDepth = 0.0;
        boolean splits = splitDistance < endDistance;
        splitCaptured = !splits;
        steps = 0;
        marchPieces(directionX, directionY, directionZ, endDistance, splitDistance, fade, lifts, depth, opaqueLight,
                translucentLight);
        lastSteps = steps;
        if (!splits || !splitCaptured) {
            translucentLight[0] = opaqueLight[0];
            translucentLight[1] = opaqueLight[1];
            translucentLight[2] = opaqueLight[2];
            splitRunningDepth = runningDepth;
        }
        scaleLight(opaqueLight, runningDepth, endDistance, depth);
        if (splits) {
            scaleLight(translucentLight, splitRunningDepth, splitDistance, depth);
        } else {
            translucentLight[0] = opaqueLight[0];
            translucentLight[1] = opaqueLight[1];
            translucentLight[2] = opaqueLight[2];
        }
    }

    private static void scaleLight(float[] light, double peakDepth, double endDistance, OpticalDepth depth) {
        // The light is scaled to the opacity at the ray's end where the depth there lies below the largest found:
        if (peakDepth > LEAST_SCALED_DEPTH && (light[0] > 0.0F || light[1] > 0.0F || light[2] > 0.0F)) {
            double endDepth = clampedDepth(depth, endDistance);
            if (endDepth < peakDepth) {
                double scale = (1.0 - Math.exp(-endDepth)) / (1.0 - Math.exp(-peakDepth));
                light[0] = (float) (light[0] * scale);
                light[1] = (float) (light[1] * scale);
                light[2] = (float) (light[2] * scale);
            }
        }
    }

    /**
     * Adds the light of the lit pieces along a ray, before the scaling of marchSplit.
     */
    private void marchPieces(double directionX, double directionY, double directionZ, double endDistance,
            double splitDistance, float fade, float[] lifts, OpticalDepth depth, float[] light, float[] splitLight) {
        double regionStart = 0.0;
        if (count > 0) {
            boxSpan(0, directionX, directionY, directionZ, ownSpan);
        } else {
            ownSpan[0] = 1.0;
            ownSpan[1] = 0.0;
        }
        for (int cascade = 0; cascade < count && regionStart < endDistance; cascade++) {
            int base = 3 * cascade;
            double bandRate = Math.max(Math.max(Math.abs(directionX) * inverseReaches[base],
                    Math.abs(directionY) * inverseReaches[base + 1]), Math.abs(directionZ) * inverseReaches[base + 2]);
            double bandStart = bandRate > 0.0 ? LampLightField.BAND_START / bandRate : NO_CROSSING;
            double bandEnd = bandRate > 0.0 ? LampLightField.BAND_END / bandRate : NO_CROSSING;
            boolean hasNext = cascade + 1 < count;
            if (hasNext) {
                boxSpan(cascade + 1, directionX, directionY, directionZ, nextSpan);
            } else {
                nextSpan[0] = 1.0;
                nextSpan[1] = 0.0;
            }
            if (ownSpan[0] > ownSpan[1] && nextSpan[0] > nextSpan[1]) {
                regionStart = Math.max(regionStart, bandEnd);
                ownSpan[0] = nextSpan[0];
                ownSpan[1] = nextSpan[1];
                continue;
            }
            double rateX = directionX * cellsPerBlock[base];
            double rateY = directionY * cellsPerBlock[base + 1];
            double rateZ = directionZ * cellsPerBlock[base + 2];
            double absRateX = Math.abs(rateX);
            double absRateY = Math.abs(rateY);
            double absRateZ = Math.abs(rateZ);
            double majorSlotEye;
            double majorRate;
            if (absRateX >= absRateY && absRateX >= absRateZ) {
                majorSlotEye = slotOrigins[base] + eyesInCascade[base];
                majorRate = rateX;
            } else if (absRateY >= absRateZ) {
                majorSlotEye = slotOrigins[base + 1] + eyesInCascade[base + 1];
                majorRate = rateY;
            } else {
                majorSlotEye = slotOrigins[base + 2] + eyesInCascade[base + 2];
                majorRate = rateZ;
            }
            double slabStep = Math.abs(majorRate) > 0.0 ? STEP_CELLS / Math.abs(majorRate) : NO_CROSSING;
            for (int part = 0; part < 2; part++) {
                double partStart = part == 0 ? regionStart : Math.max(regionStart, bandStart);
                double partEnd = Math.min(endDistance, part == 0 ? bandStart : bandEnd);
                span[0] = ownSpan[0];
                span[1] = ownSpan[1];
                if (part == 1 && nextSpan[0] <= nextSpan[1]) {
                    if (ownSpan[0] <= ownSpan[1]) {
                        span[0] = Math.min(ownSpan[0], nextSpan[0]);
                        span[1] = Math.max(ownSpan[1], nextSpan[1]);
                    } else {
                        span[0] = nextSpan[0];
                        span[1] = nextSpan[1];
                    }
                }
                double spanStart = Math.max(partStart, span[0]);
                double spanEnd = Math.min(partEnd, span[1]);
                if (!(spanStart < spanEnd)) {
                    continue;
                }
                double start = partStart;
                double nextSlab;
                if (majorRate > 0.0) {
                    double spanPlane = Math.floor((majorSlotEye + spanStart * majorRate) / STEP_CELLS) * STEP_CELLS;
                    double planeDistance = (spanPlane - majorSlotEye) / majorRate;
                    if (planeDistance > partStart) {
                        start = Math.min(planeDistance, spanStart);
                        nextSlab = (spanPlane + STEP_CELLS - majorSlotEye) / majorRate;
                    } else {
                        double startPosition = majorSlotEye + partStart * majorRate;
                        double nextPlane = (Math.floor(startPosition / STEP_CELLS) + 1.0) * STEP_CELLS;
                        nextSlab = partStart + (nextPlane - startPosition) / majorRate;
                    }
                } else if (majorRate < 0.0) {
                    double spanPlane = Math.ceil((majorSlotEye + spanStart * majorRate) / STEP_CELLS) * STEP_CELLS;
                    double planeDistance = (spanPlane - majorSlotEye) / majorRate;
                    if (planeDistance > partStart) {
                        start = Math.min(planeDistance, spanStart);
                        nextSlab = (spanPlane - STEP_CELLS - majorSlotEye) / majorRate;
                    } else {
                        double startPosition = majorSlotEye + partStart * majorRate;
                        double nextPlane = (Math.ceil(startPosition / STEP_CELLS) - 1.0) * STEP_CELLS;
                        nextSlab = partStart + (nextPlane - startPosition) / majorRate;
                    }
                } else {
                    nextSlab = NO_CROSSING;
                }
                if (!(nextSlab > start)) {
                    nextSlab = start + slabStep;
                }
                if (!splitCaptured && !(start < splitDistance)) {
                    captureSplit(light, splitLight);
                }
                if (depthDistance != start) {
                    double startDepth = clampedDepth(depth, start);
                    if (startDepth > runningDepth) {
                        runningDepth = startDepth;
                        runningTransmittance = Math.exp(-runningDepth);
                    }
                    depthDistance = start;
                }
                if (runningTransmittance < LEAST_TRANSMITTANCE) {
                    if (!splitCaptured) {
                        captureSplit(light, splitLight);
                    }
                    return;
                }
                if (!walk(cascade, part == 1, hasNext, bandRate, directionX, directionY, directionZ, rateX, rateY,
                        rateZ, start, nextSlab, slabStep, partEnd, spanEnd, splitDistance, fade, lifts, depth, light,
                        splitLight)) {
                    return;
                }
            }
            regionStart = Math.max(regionStart, bandEnd);
            ownSpan[0] = nextSpan[0];
            ownSpan[1] = nextSpan[1];
        }
    }

    private void captureSplit(float[] light, float[] splitLight) {
        splitLight[0] = light[0];
        splitLight[1] = light[1];
        splitLight[2] = light[2];
        splitRunningDepth = runningDepth;
        splitCaptured = true;
    }

    /**
     * Checks whether any coarse 8x8x8 slot block touched by the trilinear filter at (positionX, positionY, positionZ)
     * in cascade holds a lit cell, writing the low and high coarse slot indices into coarseScratch.
     */
    private boolean sampleMayBeLit(int cascade, double positionX, double positionY, double positionZ,
            int[] coarseBounds) {
        LampLightCascade cells = field.cascade(cascade);
        int sizeX = cells.sizeX();
        int sizeY = cells.sizeY();
        int sizeZ = cells.sizeZ();
        double inX = Math.max(0.5, Math.min(sizeX - 0.5, positionX));
        double inY = Math.max(0.5, Math.min(sizeY - 0.5, positionY));
        double inZ = Math.max(0.5, Math.min(sizeZ - 0.5, positionZ));
        int lowCellX = Math.max(0, Math.min(sizeX - 1, (int) Math.floor(inX - 0.5)));
        int lowCellY = Math.max(0, Math.min(sizeY - 1, (int) Math.floor(inY - 0.5)));
        int lowCellZ = Math.max(0, Math.min(sizeZ - 1, (int) Math.floor(inZ - 0.5)));
        int highCellX = Math.min(lowCellX + 1, sizeX - 1);
        int highCellY = Math.min(lowCellY + 1, sizeY - 1);
        int highCellZ = Math.min(lowCellZ + 1, sizeZ - 1);
        int base = 3 * cascade;
        int originSlotX = (int) slotOrigins[base];
        int originSlotY = (int) slotOrigins[base + 1];
        int originSlotZ = (int) slotOrigins[base + 2];
        int lowSlotX = originSlotX + lowCellX;
        if (lowSlotX >= sizeX) {
            lowSlotX -= sizeX;
        }
        int lowSlotY = originSlotY + lowCellY;
        if (lowSlotY >= sizeY) {
            lowSlotY -= sizeY;
        }
        int lowSlotZ = originSlotZ + lowCellZ;
        if (lowSlotZ >= sizeZ) {
            lowSlotZ -= sizeZ;
        }
        int highSlotX = originSlotX + highCellX;
        if (highSlotX >= sizeX) {
            highSlotX -= sizeX;
        }
        int highSlotY = originSlotY + highCellY;
        if (highSlotY >= sizeY) {
            highSlotY -= sizeY;
        }
        int highSlotZ = originSlotZ + highCellZ;
        if (highSlotZ >= sizeZ) {
            highSlotZ -= sizeZ;
        }
        int lowCoarseX = lowSlotX >> LampLightCascade.COARSE_SHIFT;
        int lowCoarseY = lowSlotY >> LampLightCascade.COARSE_SHIFT;
        int lowCoarseZ = lowSlotZ >> LampLightCascade.COARSE_SHIFT;
        int highCoarseX = highSlotX >> LampLightCascade.COARSE_SHIFT;
        int highCoarseY = highSlotY >> LampLightCascade.COARSE_SHIFT;
        int highCoarseZ = highSlotZ >> LampLightCascade.COARSE_SHIFT;
        coarseBounds[0] = lowCoarseX;
        coarseBounds[1] = lowCoarseY;
        coarseBounds[2] = lowCoarseZ;
        coarseBounds[3] = highCoarseX;
        coarseBounds[4] = highCoarseY;
        coarseBounds[5] = highCoarseZ;
        return cells.anyCoarseSlotLit(lowCoarseX, lowCoarseY, lowCoarseZ, highCoarseX, highCoarseY, highCoarseZ);
    }

    /**
     * Walks the world-aligned major-axis slabs of a cascade from a start, skipping dark coarse blocks when clipped,
     * until a piece reaches the end of the lit span, and adds the light of its lit pieces.
     * @return whether the march goes on, false once the fog turned opaque or the steps ran out
     */
    private boolean walk(int cascade, boolean inBand, boolean hasNext, double bandRate, double directionX,
            double directionY, double directionZ, double rateX, double rateY, double rateZ, double start,
            double initialNextSlab, double slabStep, double end, double spanEnd, double splitDistance, float fade,
            float[] lifts, OpticalDepth depth, float[] light, float[] splitLight) {
        int base = 3 * cascade;
        double eyeInX = eyesInCascade[base];
        double eyeInY = eyesInCascade[base + 1];
        double eyeInZ = eyesInCascade[base + 2];
        double sizeX = sizes[base];
        double sizeY = sizes[base + 1];
        double sizeZ = sizes[base + 2];
        double slotOriginX = slotOrigins[base];
        double slotOriginY = slotOrigins[base + 1];
        double slotOriginZ = slotOrigins[base + 2];
        int nextBase = base + 3;
        double nextRateX = hasNext ? directionX * cellsPerBlock[nextBase] : 0.0;
        double nextRateY = hasNext ? directionY * cellsPerBlock[nextBase + 1] : 0.0;
        double nextRateZ = hasNext ? directionZ * cellsPerBlock[nextBase + 2] : 0.0;
        LampLightCascade own = field.cascade(cascade);
        double nextSlab = initialNextSlab;
        double distance = start;
        while (steps < MAXIMUM_STEPS) {
            steps++;
            double exit = Math.min(nextSlab, end);
            boolean splitsHere = !splitCaptured && distance < splitDistance && splitDistance < exit;
            if (splitsHere) {
                exit = splitDistance;
            }
            double middle = 0.5 * (distance + exit);
            double midX = eyeInX + middle * rateX;
            double midY = eyeInY + middle * rateY;
            double midZ = eyeInZ + middle * rateZ;
            boolean mayBeLit = true;
            if (clipped) {
                boolean ownLit = sampleMayBeLit(cascade, midX, midY, midZ, coarseScratch);
                mayBeLit = ownLit;
                if (inBand && !ownLit && hasNext) {
                    double nextMidX = eyesInCascade[nextBase] + middle * nextRateX;
                    double nextMidY = eyesInCascade[nextBase + 1] + middle * nextRateY;
                    double nextMidZ = eyesInCascade[nextBase + 2] + middle * nextRateZ;
                    mayBeLit = sampleMayBeLit(cascade + 1, nextMidX, nextMidY, nextMidZ, coarseScratch);
                }
            }
            if (!mayBeLit) {
                boolean insideWindow = midX >= 0.5 && midX <= sizeX - 0.5 && midY >= 0.5 && midY <= sizeY - 0.5
                        && midZ >= 0.5 && midZ <= sizeZ - 0.5;
                boolean singleCoarse = coarseScratch[0] == coarseScratch[3] && coarseScratch[1] == coarseScratch[4]
                        && coarseScratch[2] == coarseScratch[5];
                if (!inBand && !splitsHere && insideWindow && singleCoarse && exit == nextSlab) {
                    double coarseSize = LampLightCascade.COARSE_SIZE;
                    double blockBaseX = Math.floor((slotOriginX + midX) / coarseSize) * coarseSize;
                    double blockBaseY = Math.floor((slotOriginY + midY) / coarseSize) * coarseSize;
                    double blockBaseZ = Math.floor((slotOriginZ + midZ) / coarseSize) * coarseSize;
                    double safeLowX = Math.max(blockBaseX + COARSE_SAFE_MARGIN - slotOriginX, 0.5);
                    double safeLowY = Math.max(blockBaseY + COARSE_SAFE_MARGIN - slotOriginY, 0.5);
                    double safeLowZ = Math.max(blockBaseZ + COARSE_SAFE_MARGIN - slotOriginZ, 0.5);
                    double safeHighX = Math.min(blockBaseX + (coarseSize - COARSE_SAFE_MARGIN) - slotOriginX,
                            sizeX - 0.5);
                    double safeHighY = Math.min(blockBaseY + (coarseSize - COARSE_SAFE_MARGIN) - slotOriginY,
                            sizeY - 0.5);
                    double safeHighZ = Math.min(blockBaseZ + (coarseSize - COARSE_SAFE_MARGIN) - slotOriginZ,
                            sizeZ - 0.5);
                    double safeExit = NO_CROSSING;
                    if (rateX > 0.0) {
                        safeExit = Math.min(safeExit, middle + (safeHighX - midX) / rateX);
                    } else if (rateX < 0.0) {
                        safeExit = Math.min(safeExit, middle + (safeLowX - midX) / rateX);
                    }
                    if (rateY > 0.0) {
                        safeExit = Math.min(safeExit, middle + (safeHighY - midY) / rateY);
                    } else if (rateY < 0.0) {
                        safeExit = Math.min(safeExit, middle + (safeLowY - midY) / rateY);
                    }
                    if (rateZ > 0.0) {
                        safeExit = Math.min(safeExit, middle + (safeHighZ - midZ) / rateZ);
                    } else if (rateZ < 0.0) {
                        safeExit = Math.min(safeExit, middle + (safeLowZ - midZ) / rateZ);
                    }
                    double jumpLimit = Math.min(safeExit, Math.min(end, spanEnd));
                    if (jumpLimit > nextSlab) {
                        double extraSlabs = Math.floor((jumpLimit - nextSlab) / slabStep);
                        if (extraSlabs > 0.0) {
                            exit = Math.min(nextSlab + extraSlabs * slabStep, end);
                            nextSlab = exit;
                        }
                    }
                }
            } else {
                double offsetX = middle * directionX;
                double offsetY = middle * directionY;
                double offsetZ = middle * directionZ;
                field.filter(own, eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ, shares);
                if (inBand) {
                    double weight = Math.max(0.0, Math.min(1.0, (middle * bandRate - LampLightField.BAND_START)
                            / (LampLightField.BAND_END - LampLightField.BAND_START)));
                    if (hasNext) {
                        field.filter(field.cascade(cascade + 1), eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ,
                                nextShares);
                    } else {
                        nextShares[0] = 0.0F;
                        nextShares[1] = 1.0F;
                    }
                    shares[0] = (float) (shares[0] + weight * (nextShares[0] - shares[0]));
                    shares[1] = (float) (shares[1] + weight * (nextShares[1] - shares[1]));
                }
                float brightness = shares[1] > LampLightField.NO_AIR ? shares[0] / shares[1] : 0.0F;
                float level = fade * LampLightField.levelOfBrightness(brightness);
                if (level > 0.0F) {
                    if (depthDistance != distance) {
                        double startDepth = clampedDepth(depth, distance);
                        if (startDepth > runningDepth) {
                            runningDepth = startDepth;
                            runningTransmittance = Math.exp(-runningDepth);
                        }
                        depthDistance = distance;
                    }
                    double exitDepth = clampedDepth(depth, exit);
                    if (exitDepth > runningDepth) {
                        double exitTransmittance = Math.exp(-exitDepth);
                        addLift(lifts, level, runningTransmittance - exitTransmittance, light);
                        runningDepth = exitDepth;
                        runningTransmittance = exitTransmittance;
                    }
                    depthDistance = exit;
                }
            }
            if (!splitCaptured && !(exit < splitDistance)) {
                captureSplit(light, splitLight);
            }
            if (runningTransmittance < LEAST_TRANSMITTANCE) {
                if (!splitCaptured) {
                    captureSplit(light, splitLight);
                }
                return false;
            }
            if (!splitsHere && !(exit < spanEnd)) {
                return true;
            }
            distance = exit;
            if (nextSlab <= exit) {
                nextSlab += slabStep;
            }
        }
        return false;
    }

    /**
     * Adds the lamp color of a level over 15, blended between the entries of the levels around it, times a share.
     */
    public static void addLift(float[] lifts, float level, double share, float[] light) {
        float position = Math.max(0.0F, Math.min(1.0F, level)) * (LIFT_LEVELS - 1);
        int low = Math.min((int) Math.floor(position), LIFT_LEVELS - 2);
        float fraction = position - low;
        for (int channel = 0; channel < 3; channel++) {
            float lowLift = lifts[3 * low + channel];
            float highLift = lifts[3 * (low + 1) + channel];
            light[channel] += (float) (share * (lowLift + fraction * (highLift - lowLift)));
        }
    }

    private static double clampedDepth(OpticalDepth depth, double distance) {
        return Math.max(0.0, Math.min(HeightFog.MAXIMUM_OPTICAL_DEPTH, depth.at(distance)));
    }

    /**
     * The distances along a ray between which it lies in a cascade's lit box, every distance when the march is not
     * clipped, and the start above the end where it misses the box.
     */
    private void boxSpan(int cascade, double directionX, double directionY, double directionZ, double[] result) {
        result[0] = 0.0;
        result[1] = NO_CROSSING;
        if (!clipped) {
            return;
        }
        int base = 3 * cascade;
        if (litLows[base] > litHighs[base] || litLows[base + 1] > litHighs[base + 1]
                || litLows[base + 2] > litHighs[base + 2]) {
            result[0] = 1.0;
            result[1] = 0.0;
            return;
        }
        slab(eyesInCascade[base], directionX * cellsPerBlock[base], litLows[base], litHighs[base], result);
        slab(eyesInCascade[base + 1], directionY * cellsPerBlock[base + 1], litLows[base + 1], litHighs[base + 1],
                result);
        slab(eyesInCascade[base + 2], directionZ * cellsPerBlock[base + 2], litLows[base + 2], litHighs[base + 2],
                result);
    }

    private static void slab(double origin, double rate, double low, double high, double[] result) {
        if (rate != 0.0) {
            double first = (low - origin) / rate;
            double second = (high - origin) / rate;
            result[0] = Math.max(result[0], Math.min(first, second));
            result[1] = Math.min(result[1], Math.max(first, second));
        } else if (origin < low || origin > high) {
            result[0] = 1.0;
            result[1] = 0.0;
        }
    }
}
