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
 * The Java twin of the lamp march of weather_fog_lamp.fsh, which the GameTests and the self test of LampFogPass compare
 * with the shader. A ray from the eye walks the cells of each cascade of a LampLightField that it crosses, cut into
 * pieces at every cell boundary, and keeps to the pieces that touch the cascade's lit box, which gives what walking
 * every piece gives. Each lit piece adds the lamp color of the level in its middle times the light the fog takes out of
 * the ray over the piece, exp(-tau0) - exp(-tau1), where the optical depth is held at the largest found so far, so it
 * never falls along the ray. The march stops where less than LEAST_TRANSMITTANCE of the light gets through. Where the
 * depth at the ray's end lies below the largest found, as the interpolation of the froxel excess gives in shelters, the
 * light is scaled to the opacity at the end, which the composite draws the fog with. Allocates nothing once made.
 * Single thread.
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
    private LampLightField field;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private int count;
    private boolean clipped = true;
    private int lastSteps;
    private double runningDepth;
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
     * Sets whether the march keeps to the lit boxes, as the shader does, or walks every cascade's whole stretch, which
     * the GameTests compare it with.
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
        for (int cascade = 0; cascade < count && regionStart < endDistance; cascade++) {
            int base = 3 * cascade;
            double bandRate = Math.max(Math.max(Math.abs(directionX) * inverseReaches[base],
                    Math.abs(directionY) * inverseReaches[base + 1]), Math.abs(directionZ) * inverseReaches[base + 2]);
            double bandStart = bandRate > 0.0 ? LampLightField.BAND_START / bandRate : NO_CROSSING;
            double bandEnd = bandRate > 0.0 ? LampLightField.BAND_END / bandRate : NO_CROSSING;
            boolean hasNext = cascade + 1 < count;
            boxSpan(cascade, directionX, directionY, directionZ, ownSpan);
            if (hasNext) {
                boxSpan(cascade + 1, directionX, directionY, directionZ, nextSpan);
            } else {
                nextSpan[0] = 1.0;
                nextSpan[1] = 0.0;
            }
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
                double start = Math.max(partStart, pieceStart(cascade, directionX, directionY, directionZ,
                        spanStart));
                if (!splitCaptured && !(start < splitDistance)) {
                    captureSplit(light, splitLight);
                }
                if (depthDistance != start) {
                    runningDepth = Math.max(runningDepth, clampedDepth(depth, start));
                    depthDistance = start;
                }
                if (Math.exp(-runningDepth) < LEAST_TRANSMITTANCE) {
                    if (!splitCaptured) {
                        captureSplit(light, splitLight);
                    }
                    return;
                }
                if (!walk(cascade, part == 1, hasNext, bandRate, directionX, directionY, directionZ, start, partEnd,
                        spanEnd, splitDistance, fade, lifts, depth, light, splitLight)) {
                    return;
                }
            }
            regionStart = Math.max(regionStart, bandEnd);
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
     * The distance at which a ray last crossed a cell boundary of a cascade at or before a distance, the start of the
     * piece that holds it.
     */
    private double pieceStart(int cascade, double directionX, double directionY, double directionZ, double distance) {
        int base = 3 * cascade;
        double rateX = directionX * cellsPerBlock[base];
        double rateY = directionY * cellsPerBlock[base + 1];
        double rateZ = directionZ * cellsPerBlock[base + 2];
        return Math.max(Math.max(lastCrossing(eyesInCascade[base] + distance * rateX, rateX, distance),
                lastCrossing(eyesInCascade[base + 1] + distance * rateY, rateY, distance)),
                lastCrossing(eyesInCascade[base + 2] + distance * rateZ, rateZ, distance));
    }

    private static double lastCrossing(double position, double rate, double distance) {
        if (rate > 0.0) {
            return distance - (position - Math.floor(position)) / rate;
        }
        if (rate < 0.0) {
            return distance + (Math.ceil(position) - position) / rate;
        }
        return -NO_CROSSING;
    }

    /**
     * Walks the cells of a cascade from a start, the pieces ending at cell boundaries or at the part's end, until a
     * piece reaches the end of the lit span, and adds the light of its lit pieces.
     * @return whether the march goes on, false once the fog turned opaque or the steps ran out
     */
    private boolean walk(int cascade, boolean inBand, boolean hasNext, double bandRate, double directionX,
            double directionY, double directionZ, double start, double end, double spanEnd, double splitDistance,
            float fade, float[] lifts, OpticalDepth depth, float[] light, float[] splitLight) {
        int base = 3 * cascade;
        double rateX = directionX * cellsPerBlock[base];
        double rateY = directionY * cellsPerBlock[base + 1];
        double rateZ = directionZ * cellsPerBlock[base + 2];
        double nextX = firstCrossing(eyesInCascade[base] + start * rateX, rateX, start);
        double nextY = firstCrossing(eyesInCascade[base + 1] + start * rateY, rateY, start);
        double nextZ = firstCrossing(eyesInCascade[base + 2] + start * rateZ, rateZ, start);
        double betweenX = rateX != 0.0 ? 1.0 / Math.abs(rateX) : NO_CROSSING;
        double betweenY = rateY != 0.0 ? 1.0 / Math.abs(rateY) : NO_CROSSING;
        double betweenZ = rateZ != 0.0 ? 1.0 / Math.abs(rateZ) : NO_CROSSING;
        LampLightCascade own = field.cascade(cascade);
        double distance = start;
        while (steps < MAXIMUM_STEPS) {
            steps++;
            double exit = Math.min(Math.min(nextX, nextY), Math.min(nextZ, end));
            boolean splitsHere = !splitCaptured && distance < splitDistance && splitDistance < exit;
            if (splitsHere) {
                exit = splitDistance;
            }
            double middle = 0.5 * (distance + exit);
            double offsetX = middle * directionX;
            double offsetY = middle * directionY;
            double offsetZ = middle * directionZ;
            field.filter(own, eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ, shares);
            if (inBand) {
                double weight = Math.max(0.0, Math.min(1.0, (middle * bandRate - LampLightField.BAND_START)
                        / (LampLightField.BAND_END - LampLightField.BAND_START)));
                if (hasNext) {
                    field.filter(field.cascade(cascade + 1), eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ, nextShares);
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
                    runningDepth = Math.max(runningDepth, clampedDepth(depth, distance));
                    depthDistance = distance;
                }
                double exitDepth = Math.max(runningDepth, clampedDepth(depth, exit));
                double absorbed = Math.exp(-runningDepth) - Math.exp(-exitDepth);
                addLift(lifts, level, absorbed, light);
                runningDepth = exitDepth;
                depthDistance = exit;
            }
            if (!splitCaptured && !(exit < splitDistance)) {
                captureSplit(light, splitLight);
            }
            if (Math.exp(-runningDepth) < LEAST_TRANSMITTANCE) {
                if (!splitCaptured) {
                    captureSplit(light, splitLight);
                }
                return false;
            }
            if (!splitsHere && !(exit < spanEnd)) {
                return true;
            }
            distance = exit;
            if (nextX <= exit) {
                nextX += betweenX;
            }
            if (nextY <= exit) {
                nextY += betweenY;
            }
            if (nextZ <= exit) {
                nextZ += betweenZ;
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

    private static double firstCrossing(double position, double rate, double start) {
        if (rate > 0.0) {
            return start + (Math.floor(position) + 1.0 - position) / rate;
        }
        if (rate < 0.0) {
            return start + (Math.ceil(position) - 1.0 - position) / rate;
        }
        return NO_CROSSING;
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
