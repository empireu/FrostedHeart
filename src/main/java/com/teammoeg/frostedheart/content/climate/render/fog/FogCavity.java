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
 * The math and shared constants of the wind-direction-free fog cavity enclosure map, which fog_cavity_build.fsh and
 * snow_walk.glsl repeat on the GPU, so that GameTests and fog selftest can check them without an OpenGL context.
 * <p>
 * Deep narrow open-sky cavities such as ravines and walled alleys hold less blowing snow than exposed plains, so the
 * weather fog thins toward fogCavityShare below the cavity's enclosure surface while falling snow continues to enter
 * unchanged. The enclosure surface h_cav(x, z) is scanned from FarHeights no-leaves heights on a world-aligned 512 by
 * 512 grid (1 block per texel, +-256 blocks around the window center) along four axes (+-X, +-Z, +-(X+Z), +-(X-Z)) up
 * to about 32 blocks at ENCLOSURE_SLOPE = 0.65. Taking min(r_k+, r_k-) along each axis requires opposing walls (so a
 * single cliff foot or wide valley gets no thinning), a 3-texel perpendicular minimum erases thin posts narrower than 3
 * blocks, and taking the maximum over all four axes keeps ravines nearly isotropic at 0, 22.5 and 45 degrees.
 * <p>
 * Below h_cav, the fog density decays exponentially with depth over the weather's decay length toward an effective
 * floor that ramps smoothly from 1.0 in calm wind to fogCavityShare in windy weather.
 */
public final class FogCavity {
    /**
     * Texels along each axis of the world-aligned cavity enclosure map (1 block per texel).
     */
    public static final int MAP_SIZE = 512;
    /**
     * Half of MAP_SIZE, the reach in blocks around the window center along each horizontal axis.
     */
    public static final int HALF_SIZE = MAP_SIZE / 2;
    /**
     * Offset in blocks from FarHeights's minimum corner to the cavity map's minimum corner, centering the 512 by 512
     * map inside the 1024 by 1024 FarHeights window.
     */
    public static final int OFFSET_IN_FAR_HEIGHTS = (FarHeights.SIZE - MAP_SIZE) / 2;
    /**
     * Reach in blocks along each cardinal axis (+-X and +-Z), equal to 1 + sum of CARDINAL_STRIDES.
     */
    public static final int CARDINAL_REACH = 32;
    /**
     * Reach in diagonal steps along each diagonal axis (+-(X+Z) and +-(X-Z)), equal to 1 + sum of DIAGONAL_STRIDES
     * (23 * sqrt(2) = 32.53 blocks).
     */
    public static final int DIAGONAL_REACH = 23;
    /**
     * Number of doubling scan passes for the cardinal batch after its initial resample pass.
     */
    public static final int CARDINAL_SCAN_PASSES = 5;
    /**
     * Number of doubling scan passes for the diagonal batch after its initial resample pass.
     */
    public static final int DIAGONAL_SCAN_PASSES = 5;
    /**
     * Index of the first pass of the diagonal batch (passes 0..5 are cardinal, passes 6..11 are diagonal).
     */
    public static final int DIAGONAL_FIRST_PASS = CARDINAL_SCAN_PASSES + 1;
    /**
     * Total GPU passes of one full cavity map rebuild (6 cardinal plus 6 diagonal).
     */
    public static final int TOTAL_PASSES = (CARDINAL_SCAN_PASSES + 1) + (DIAGONAL_SCAN_PASSES + 1);
    /**
     * Minimum opposing-wall slope (blocks dropped per block distance) required to enclose a cavity. An open canyon
     * requires depth / width > ENCLOSURE_SLOPE / 2 = 0.325 to begin enclosing at its floor, leaving gentle 1:2
     * V-valleys and shallow ditches unthinned while strongly enclosing deep ravines.
     */
    public static final double ENCLOSURE_SLOPE = 0.65;
    /**
     * Euclidean step length in blocks of one diagonal grid step (1, 1) or (1, -1).
     */
    public static final double DIAGONAL_STEP = Math.sqrt(2.0);
    /**
     * Wind speed in blocks per second at or below which cavity thinning is completely off.
     */
    public static final double CALM_WIND_SPEED = 0.5;
    /**
     * Wind speed in blocks per second at or above which cavity thinning reaches its full fogCavityShare strength.
     */
    public static final double FULL_WIND_SPEED = 6.0;
    /**
     * Sentinel height for unknown or open columns, matching FarHeights.UNKNOWN.
     */
    public static final double NO_HEIGHT = FarHeights.UNKNOWN;
    /**
     * Any height at or below this threshold counts as no enclosing surface.
     */
    public static final double NO_HEIGHT_THRESHOLD = -30000.0;
    /**
     * Distance in blocks along either horizontal axis beyond which a published cavity map is discarded immediately
     * after a teleport instead of waiting for the next rebuild.
     */
    public static final int MAX_DRIFT_BLOCKS = 256;

    private static final int CHUNK_WIDTH = 16;
    private static final int[] CARDINAL_STRIDES = {1, 2, 4, 8, 16};
    private static final int[] DIAGONAL_STRIDES = {1, 2, 4, 8, 7};

    private FogCavity() {
    }

    /**
     * The lowest world block coordinate of the cavity map along one axis for a FarHeights origin chunk.
     * @param originChunk FarHeights.originChunkX or originChunkZ
     */
    public static int minCoordinate(int originChunk) {
        return originChunk * CHUNK_WIDTH + OFFSET_IN_FAR_HEIGHTS;
    }

    /**
     * The stride in texels of cardinal scan pass 1 to CARDINAL_SCAN_PASSES.
     */
    public static int cardinalStride(int pass) {
        if (pass < 1 || pass > CARDINAL_SCAN_PASSES) {
            throw new IllegalArgumentException("Cavity cardinal scan pass " + pass + " is outside 1 to "
                    + CARDINAL_SCAN_PASSES);
        }
        return CARDINAL_STRIDES[pass - 1];
    }

    /**
     * The stride in texels of diagonal scan pass 1 to DIAGONAL_SCAN_PASSES.
     */
    public static int diagonalStride(int pass) {
        if (pass < 1 || pass > DIAGONAL_SCAN_PASSES) {
            throw new IllegalArgumentException("Cavity diagonal scan pass " + pass + " is outside 1 to "
                    + DIAGONAL_SCAN_PASSES);
        }
        return DIAGONAL_STRIDES[pass - 1];
    }

    public static int[] cardinalStrides() {
        return CARDINAL_STRIDES.clone();
    }

    public static int[] diagonalStrides() {
        return DIAGONAL_STRIDES.clone();
    }

    /**
     * Smooth wind factor in [0, 1]: 0 at or below CALM_WIND_SPEED, ramping smoothly to 1 at FULL_WIND_SPEED.
     */
    public static double windFactor(double windSpeed) {
        if (!(windSpeed > CALM_WIND_SPEED) || !Double.isFinite(windSpeed)) {
            return 0.0;
        }
        double t = clamp((windSpeed - CALM_WIND_SPEED) / (FULL_WIND_SPEED - CALM_WIND_SPEED));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * The effective minimum fog density share deep inside a cavity for the current fogCavityShare setting and wind
     * speed. Equals 1.0 when calm or when cavityShare >= 1.0, and approaches cavityShare as wind rises to
     * FULL_WIND_SPEED.
     */
    public static double effectiveFloor(double cavityShare, double windSpeed) {
        double share = Double.isFinite(cavityShare) ? clamp(cavityShare) : 1.0;
        if (!(share < 1.0)) {
            return 1.0;
        }
        return 1.0 - windFactor(windSpeed) * (1.0 - share);
    }

    /**
     * The fog density multiplier in [effectiveFloor, 1.0] at height worldY under an enclosure surface cavityHeight,
     * matching snowWalkCavityFactor in snow_walk.glsl.
     */
    public static double cavityFactor(double worldY, double cavityHeight, double decayLength, double effectiveFloor) {
        if (!(effectiveFloor < 1.0) || !(cavityHeight > NO_HEIGHT_THRESHOLD)) {
            return 1.0;
        }
        double depth = cavityHeight - worldY;
        if (!(depth > 0.0)) {
            return 1.0;
        }
        double clampedDecay = clampDecayLength(decayLength);
        return effectiveFloor + (1.0 - effectiveFloor) * Math.exp(-depth / clampedDecay);
    }

    /**
     * Whether the cavity map needs a rebuild. Because the enclosure surface is purely geometric, it rebuilds only when
     * the FarHeights window moves or its no-leaves heights change, never when the wind turns or changes speed.
     */
    public static boolean needsRebuild(boolean hasPublished, int minX, int minZ, long heightsVersion, int builtMinX,
            int builtMinZ, long builtHeightsVersion) {
        if (!hasPublished) {
            return true;
        }
        return minX != builtMinX || minZ != builtMinZ || heightsVersion != builtHeightsVersion;
    }

    /**
     * Runs the exact 12-pass GPU cavity enclosure scan (6 cardinal plus 6 diagonal) on the CPU in single precision,
     * writing mapSize by mapSize enclosure heights into output (z outer, x inner), matching fog_cavity_build.fsh.
     */
    public static void scan(int mapSize, int[] cardinalStrides, int[] diagonalStrides, SnowLee.HeightLookup heights,
            int minX, int minZ, int windowMinX, int windowMinZ, int windowSize, double slope, boolean applyPerpMin,
            float[] output) {
        int texelCount = mapSize * mapSize;
        float[] workA = new float[texelCount * 4];
        float[] workB = new float[texelCount * 4];
        float[] cardinal = new float[texelCount];
        float floatSlope = (float) slope;
        float diagSlope = (float) (slope * DIAGONAL_STEP);
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;

        // Pass 0: cardinal resample at t = 1 along +X (0), -X (1), +Z (2), -Z (3):
        for (int iz = 0; iz < mapSize; iz++) {
            int worldZ = minZ + iz;
            for (int ix = 0; ix < mapSize; ix++) {
                int worldX = minX + ix;
                int base = (iz * mapSize + ix) * 4;
                workA[base] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX + 1, worldZ,
                        floatSlope, noHeight, threshold);
                workA[base + 1] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX - 1, worldZ,
                        floatSlope, noHeight, threshold);
                workA[base + 2] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX, worldZ + 1,
                        floatSlope, noHeight, threshold);
                workA[base + 3] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX, worldZ - 1,
                        floatSlope, noHeight, threshold);
            }
        }

        // Passes 1..cardinalStrides.length: cardinal doubling scan; final pass applies 3-texel perp min and combines:
        float[] source = workA;
        float[] target = workB;
        for (int pass = 0; pass < cardinalStrides.length; pass++) {
            int stride = cardinalStrides[pass];
            boolean finalPass = pass == cardinalStrides.length - 1;
            float drop = floatSlope * stride;
            for (int iz = 0; iz < mapSize; iz++) {
                for (int ix = 0; ix < mapSize; ix++) {
                    if (!finalPass) {
                        int base = (iz * mapSize + ix) * 4;
                        target[base] = scanDirection(source, mapSize, ix, iz, stride, 0, 0, drop, threshold);
                        target[base + 1] = scanDirection(source, mapSize, ix, iz, -stride, 0, 1, drop, threshold);
                        target[base + 2] = scanDirection(source, mapSize, ix, iz, 0, stride, 2, drop, threshold);
                        target[base + 3] = scanDirection(source, mapSize, ix, iz, 0, -stride, 3, drop, threshold);
                        continue;
                    }
                    float plusX = scanDirection(source, mapSize, ix, iz, stride, 0, 0, drop, threshold);
                    float minusX = scanDirection(source, mapSize, ix, iz, -stride, 0, 1, drop, threshold);
                    float plusZ = scanDirection(source, mapSize, ix, iz, 0, stride, 2, drop, threshold);
                    float minusZ = scanDirection(source, mapSize, ix, iz, 0, -stride, 3, drop, threshold);
                    if (applyPerpMin) {
                        int zMinus = Math.max(iz - 1, 0);
                        int zPlus = Math.min(iz + 1, mapSize - 1);
                        plusX = Math.min(plusX, Math.min(
                                scanDirection(source, mapSize, ix, zMinus, stride, 0, 0, drop, threshold),
                                scanDirection(source, mapSize, ix, zPlus, stride, 0, 0, drop, threshold)));
                        minusX = Math.min(minusX, Math.min(
                                scanDirection(source, mapSize, ix, zMinus, -stride, 0, 1, drop, threshold),
                                scanDirection(source, mapSize, ix, zPlus, -stride, 0, 1, drop, threshold)));
                        int xMinus = Math.max(ix - 1, 0);
                        int xPlus = Math.min(ix + 1, mapSize - 1);
                        plusZ = Math.min(plusZ, Math.min(
                                scanDirection(source, mapSize, xMinus, iz, 0, stride, 2, drop, threshold),
                                scanDirection(source, mapSize, xPlus, iz, 0, stride, 2, drop, threshold)));
                        minusZ = Math.min(minusZ, Math.min(
                                scanDirection(source, mapSize, xMinus, iz, 0, -stride, 3, drop, threshold),
                                scanDirection(source, mapSize, xPlus, iz, 0, -stride, 3, drop, threshold)));
                    }
                    cardinal[iz * mapSize + ix] = Math.max(Math.min(plusX, minusX), Math.min(plusZ, minusZ));
                }
            }
            if (!finalPass) {
                float[] swap = source;
                source = target;
                target = swap;
            }
        }

        // Diagonal resample at t = 1 along (+1,+1), (-1,-1), (+1,-1), (-1,+1):
        source = workA;
        target = workB;
        for (int iz = 0; iz < mapSize; iz++) {
            int worldZ = minZ + iz;
            for (int ix = 0; ix < mapSize; ix++) {
                int worldX = minX + ix;
                int base = (iz * mapSize + ix) * 4;
                source[base] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX + 1, worldZ + 1,
                        diagSlope, noHeight, threshold);
                source[base + 1] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX - 1, worldZ - 1,
                        diagSlope, noHeight, threshold);
                source[base + 2] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX + 1, worldZ - 1,
                        diagSlope, noHeight, threshold);
                source[base + 3] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX - 1, worldZ + 1,
                        diagSlope, noHeight, threshold);
            }
        }

        // Diagonal doubling passes; final pass combines with cardinal into output:
        for (int pass = 0; pass < diagonalStrides.length; pass++) {
            int stride = diagonalStrides[pass];
            boolean finalPass = pass == diagonalStrides.length - 1;
            float drop = diagSlope * stride;
            for (int iz = 0; iz < mapSize; iz++) {
                for (int ix = 0; ix < mapSize; ix++) {
                    if (!finalPass) {
                        int base = (iz * mapSize + ix) * 4;
                        target[base] = scanDirection(source, mapSize, ix, iz, stride, stride, 0, drop, threshold);
                        target[base + 1] = scanDirection(source, mapSize, ix, iz, -stride, -stride, 1, drop, threshold);
                        target[base + 2] = scanDirection(source, mapSize, ix, iz, stride, -stride, 2, drop, threshold);
                        target[base + 3] = scanDirection(source, mapSize, ix, iz, -stride, stride, 3, drop, threshold);
                        continue;
                    }
                    float pp = scanDirection(source, mapSize, ix, iz, stride, stride, 0, drop, threshold);
                    float mm = scanDirection(source, mapSize, ix, iz, -stride, -stride, 1, drop, threshold);
                    float pm = scanDirection(source, mapSize, ix, iz, stride, -stride, 2, drop, threshold);
                    float mp = scanDirection(source, mapSize, ix, iz, -stride, stride, 3, drop, threshold);
                    if (applyPerpMin) {
                        int ax1 = clampIndex(ix + 1, mapSize);
                        int az1 = clampIndex(iz - 1, mapSize);
                        int ax2 = clampIndex(ix - 1, mapSize);
                        int az2 = clampIndex(iz + 1, mapSize);
                        pp = Math.min(pp, Math.min(
                                scanDirection(source, mapSize, ax1, az1, stride, stride, 0, drop, threshold),
                                scanDirection(source, mapSize, ax2, az2, stride, stride, 0, drop, threshold)));
                        mm = Math.min(mm, Math.min(
                                scanDirection(source, mapSize, ax1, az1, -stride, -stride, 1, drop, threshold),
                                scanDirection(source, mapSize, ax2, az2, -stride, -stride, 1, drop, threshold)));
                        int bx1 = clampIndex(ix + 1, mapSize);
                        int bz1 = clampIndex(iz + 1, mapSize);
                        int bx2 = clampIndex(ix - 1, mapSize);
                        int bz2 = clampIndex(iz - 1, mapSize);
                        pm = Math.min(pm, Math.min(
                                scanDirection(source, mapSize, bx1, bz1, stride, -stride, 2, drop, threshold),
                                scanDirection(source, mapSize, bx2, bz2, stride, -stride, 2, drop, threshold)));
                        mp = Math.min(mp, Math.min(
                                scanDirection(source, mapSize, bx1, bz1, -stride, stride, 3, drop, threshold),
                                scanDirection(source, mapSize, bx2, bz2, -stride, stride, 3, drop, threshold)));
                    }
                    float diagMax = Math.max(Math.min(pp, mm), Math.min(pm, mp));
                    output[iz * mapSize + ix] = Math.max(cardinal[iz * mapSize + ix], diagMax);
                }
            }
            if (!finalPass) {
                float[] swap = source;
                source = target;
                target = swap;
            }
        }
    }

    /**
     * Independent brute-force evaluation of one texel (texelX, texelZ) of the cavity enclosure map across all four
     * opposing-wall axes, for verifying scan in GameTests.
     */
    public static float bruteForceTexel(int mapSize, int cardinalReach, int diagonalReach,
            SnowLee.HeightLookup heights, int minX, int minZ, int windowMinX, int windowMinZ, int windowSize,
            double slope, boolean applyPerpMin, int texelX, int texelZ) {
        float floatSlope = (float) slope;
        float diagSlope = (float) (slope * DIAGONAL_STEP);
        float ex = bruteForceAxis(mapSize, cardinalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize,
                1, 0, 0, 1, floatSlope, applyPerpMin, texelX, texelZ);
        float ez = bruteForceAxis(mapSize, cardinalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize,
                0, 1, 1, 0, floatSlope, applyPerpMin, texelX, texelZ);
        float ed1 = bruteForceAxis(mapSize, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize,
                1, 1, 1, -1, diagSlope, applyPerpMin, texelX, texelZ);
        float ed2 = bruteForceAxis(mapSize, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize,
                1, -1, 1, 1, diagSlope, applyPerpMin, texelX, texelZ);
        return Math.max(Math.max(ex, ez), Math.max(ed1, ed2));
    }

    private static float bruteForceAxis(int mapSize, int reach, SnowLee.HeightLookup heights, int minX, int minZ,
            int windowMinX, int windowMinZ, int windowSize, int dx, int dz, int perpX, int perpZ, float stepSlope,
            boolean applyPerpMin, int texelX, int texelZ) {
        float pos = bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                stepSlope, texelX, texelZ);
        float neg = bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                stepSlope, texelX, texelZ);
        if (applyPerpMin) {
            int x1 = clampIndex(texelX + perpX, mapSize);
            int z1 = clampIndex(texelZ + perpZ, mapSize);
            int x2 = clampIndex(texelX - perpX, mapSize);
            int z2 = clampIndex(texelZ - perpZ, mapSize);
            pos = Math.min(pos, Math.min(
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                            stepSlope, x1, z1),
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                            stepSlope, x2, z2)));
            neg = Math.min(neg, Math.min(
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                            stepSlope, x1, z1),
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                            stepSlope, x2, z2)));
        }
        return Math.min(pos, neg);
    }

    private static float bruteForceRay(int mapSize, int reach, SnowLee.HeightLookup heights, int minX, int minZ,
            int windowMinX, int windowMinZ, int windowSize, int dx, int dz, float stepSlope, int texelX, int texelZ) {
        float best = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        for (int step = 1; step <= reach; step++) {
            // Stop once the stepped texel leaves the cavity map, matching the doubling scan bounds:
            int sx = texelX + dx * (step - 1);
            int sz = texelZ + dz * (step - 1);
            if (sx < 0 || sx >= mapSize || sz < 0 || sz >= mapSize) {
                break;
            }
            int colX = minX + sx + dx;
            int colZ = minZ + sz + dz;
            if (colX < windowMinX || colX >= windowMinX + windowSize || colZ < windowMinZ
                    || colZ >= windowMinZ + windowSize) {
                continue;
            }
            short height = heights.heightAt(colX, colZ);
            if (height > FarHeights.UNKNOWN) {
                float value = height - stepSlope * step;
                if (value > threshold) {
                    best = Math.max(best, value);
                }
            }
        }
        return best;
    }

    private static float scanDirection(float[] source, int mapSize, int ix, int iz, int offsetX, int offsetZ,
            int channel, float drop, float threshold) {
        float value = source[(iz * mapSize + ix) * 4 + channel];
        int sampleX = ix + offsetX;
        int sampleZ = iz + offsetZ;
        if (sampleX >= 0 && sampleX < mapSize && sampleZ >= 0 && sampleZ < mapSize) {
            float neighbor = source[(sampleZ * mapSize + sampleX) * 4 + channel];
            if (neighbor > threshold) {
                value = Math.max(value, neighbor - drop);
            }
        }
        return value;
    }

    private static float sampleWithDrop(SnowLee.HeightLookup heights, int windowMinX, int windowMinZ, int windowSize,
            int colX, int colZ, float drop, float noHeight, float threshold) {
        if (colX < windowMinX || colX >= windowMinX + windowSize || colZ < windowMinZ
                || colZ >= windowMinZ + windowSize) {
            return noHeight;
        }
        short height = heights.heightAt(colX, colZ);
        if (height <= FarHeights.UNKNOWN) {
            return noHeight;
        }
        float value = height - drop;
        return value > threshold ? value : noHeight;
    }

    private static int clampIndex(int index, int size) {
        return Math.max(0, Math.min(size - 1, index));
    }

    private static double clampDecayLength(double decayLength) {
        if (!(decayLength >= SnowFogWalk.MINIMUM_DECAY_LENGTH)) {
            return SnowFogWalk.MINIMUM_DECAY_LENGTH;
        }
        return Math.min(SnowFogWalk.MAXIMUM_DECAY_LENGTH, decayLength);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
