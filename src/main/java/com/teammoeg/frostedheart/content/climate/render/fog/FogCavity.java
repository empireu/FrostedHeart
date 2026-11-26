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
 * weather fog thins toward fogCavityShare and its moving wisps fade out below the cavity's enclosure surface while
 * falling snow brakes horizontally and settles onto the floor. The enclosure surface h_cav(x, z) and the lower lip of
 * the two walls that define it are scanned from FarHeights no-leaves heights on a world-aligned 512 by 512 grid (1
 * block per texel, +-256 blocks around the window center) along four axes (+-X, +-Z, +-(X+Z), +-(X-Z)) up to about 32
 * blocks at ENCLOSURE_SLOPE = 0.65. Each direction's wall is the column with the highest sloped height h - 0.65 * d,
 * the nearest one on a tie, and its lip is that column's own height. Along each axis, a 3-texel perpendicular minimum
 * erases thin posts narrower than 3 blocks, and combining min(lowerLip, 0.5 * (h_pos + h_neg)) of the sloped heights
 * produces a wall-to-wall flat surface clamped to the lower lip while giving single cliff feet, wide valleys and gentle
 * V-valleys no thinning. Taking the maximum over all four axes keeps ravines nearly isotropic at 0, 22.5 and 45
 * degrees.
 * <p>
 * Below h_cav, the fog density decays exponentially with depth over the weather's decay length toward an effective
 * floor that ramps smoothly from 1.0 in calm wind to fogCavityShare in windy weather, and fog structure contrast fades
 * out by the same depth decay.
 */
public final class FogCavity {
    /**
     * Horizontal radius in blocks around the eye within which a cavity surface above the eye expands the vanilla fog
     * culling bound. Outside this radius, open ground keeps its pre-cavity culling distance.
     */
    public static final int NEAR_CAVITY_RADIUS = 8;
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
     * Depth-only openness factor exp(-depth / decayLength) in (0, 1] below cavityHeight, and 1.0 at or above
     * cavityHeight or when cavityHeight is missing.
     */
    public static double openFactor(double worldY, double cavityHeight, double decayLength) {
        if (!(cavityHeight > NO_HEIGHT_THRESHOLD)) {
            return 1.0;
        }
        double depth = cavityHeight - worldY;
        if (!(depth > 0.0)) {
            return 1.0;
        }
        double clampedDecay = clampDecayLength(decayLength);
        return Math.exp(-depth / clampedDecay);
    }

    /**
     * The fog density multiplier in [effectiveFloor, 1.0] at height worldY under an enclosure surface cavityHeight,
     * matching snowWalkCavityFactors in snow_walk.glsl.
     */
    public static double cavityFactor(double worldY, double cavityHeight, double decayLength, double effectiveFloor) {
        if (!(effectiveFloor < 1.0)) {
            return 1.0;
        }
        double open = openFactor(worldY, cavityHeight, decayLength);
        return effectiveFloor + (1.0 - effectiveFloor) * open;
    }

    /**
     * The contrast multiplier in [0, 1] applied to FogStructure wisps at depth openness openFactor and wind ramp
     * windFactor, damping moving wisps toward uniform fog deep inside cavities while staying continuous in calm wind.
     */
    public static double wispFactor(double openFactor, double windFactor) {
        double clampedOpen = Double.isFinite(openFactor) ? clamp(openFactor) : 1.0;
        double clampedWind = Double.isFinite(windFactor) ? clamp(windFactor) : 0.0;
        return 1.0 - clampedWind * (1.0 - clampedOpen);
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
     * Whether the wall with sloped maximum sloped at distance steps is lower than the wall bestSloped at bestDistance,
     * or as low and nearer, matching lowerWall in fog_cavity_build.fsh.
     */
    public static boolean isLowerWall(float sloped, float distance, float bestSloped, float bestDistance) {
        return sloped < bestSloped || (sloped == bestSloped && distance < bestDistance);
    }

    /**
     * Runs the exact 12-pass GPU cavity enclosure scan (6 cardinal plus 6 diagonal) on the CPU in single precision,
     * writing mapSize by mapSize flat enclosure heights into output (z outer, x inner), matching fog_cavity_build.fsh.
     */
    public static void scan(int mapSize, int[] cardinalStrides, int[] diagonalStrides, SnowLee.HeightLookup heights,
            int minX, int minZ, int windowMinX, int windowMinZ, int windowSize, double slope, boolean applyPerpMin,
            float[] output) {
        scan(mapSize, cardinalStrides, diagonalStrides, heights, minX, minZ, windowMinX, windowMinZ, windowSize, slope,
                applyPerpMin, output, null);
    }

    /**
     * Runs the exact 12-pass GPU cavity enclosure scan (6 cardinal plus 6 diagonal) on the CPU in single precision,
     * writing mapSize by mapSize flat enclosure heights into output and the lower lip of the winning axis's two walls
     * into lipOutput (when non-null), matching fog_cavity_build.fsh.
     */
    public static void scan(int mapSize, int[] cardinalStrides, int[] diagonalStrides, SnowLee.HeightLookup heights,
            int minX, int minZ, int windowMinX, int windowMinZ, int windowSize, double slope, boolean applyPerpMin,
            float[] output, float[] lipOutput) {
        int texelCount = mapSize * mapSize;
        float[] workA = new float[texelCount * 4];
        float[] workB = new float[texelCount * 4];
        float[] distanceWorkA = new float[texelCount * 4];
        float[] distanceWorkB = new float[texelCount * 4];
        float[] cardinal = new float[texelCount];
        float[] cardinalLip = new float[texelCount];
        float[] wall = new float[2];
        float floatSlope = (float) slope;
        float diagSlope = (float) (slope * DIAGONAL_STEP);
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;

        // Pass 0: cardinal resample one step along +X (0), -X (1), +Z (2), -Z (3):
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
                for (int channel = 0; channel < 4; channel++) {
                    distanceWorkA[base + channel] = 1.0F;
                }
            }
        }

        // Passes 1..cardinalStrides.length: cardinal doubling scan; final pass applies 3-texel perp min and combines:
        float[] source = workA;
        float[] target = workB;
        float[] distanceSource = distanceWorkA;
        float[] distanceTarget = distanceWorkB;
        for (int pass = 0; pass < cardinalStrides.length; pass++) {
            int stride = cardinalStrides[pass];
            boolean finalPass = pass == cardinalStrides.length - 1;
            float drop = floatSlope * stride;
            for (int iz = 0; iz < mapSize; iz++) {
                for (int ix = 0; ix < mapSize; ix++) {
                    if (!finalPass) {
                        int base = (iz * mapSize + ix) * 4;
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, stride, 0, 0, base,
                                drop, stride, threshold, wall);
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, -stride, 0, 1, base,
                                drop, stride, threshold, wall);
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, 0, stride, 2, base,
                                drop, stride, threshold, wall);
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, 0, -stride, 3, base,
                                drop, stride, threshold, wall);
                        continue;
                    }
                    finalWall(source, distanceSource, mapSize, ix, iz, stride, 0, 0, drop, stride, applyPerpMin, 0, 1,
                            threshold, wall);
                    float plusX = wall[0];
                    float plusXDistance = wall[1];
                    finalWall(source, distanceSource, mapSize, ix, iz, -stride, 0, 1, drop, stride, applyPerpMin, 0,
                            1, threshold, wall);
                    float minusX = wall[0];
                    float minusXDistance = wall[1];
                    finalWall(source, distanceSource, mapSize, ix, iz, 0, stride, 2, drop, stride, applyPerpMin, 1, 0,
                            threshold, wall);
                    float plusZ = wall[0];
                    float plusZDistance = wall[1];
                    finalWall(source, distanceSource, mapSize, ix, iz, 0, -stride, 3, drop, stride, applyPerpMin, 1,
                            0, threshold, wall);
                    float minusZ = wall[0];
                    float minusZDistance = wall[1];
                    float surfX = combineAxisSurface(plusX, plusXDistance, minusX, minusXDistance, floatSlope,
                            noHeight, threshold);
                    float lipX = combineAxisLip(plusX, plusXDistance, minusX, minusXDistance, floatSlope, noHeight,
                            threshold);
                    float surfZ = combineAxisSurface(plusZ, plusZDistance, minusZ, minusZDistance, floatSlope,
                            noHeight, threshold);
                    float lipZ = combineAxisLip(plusZ, plusZDistance, minusZ, minusZDistance, floatSlope, noHeight,
                            threshold);
                    int index = iz * mapSize + ix;
                    if (surfZ > surfX) {
                        cardinal[index] = surfZ;
                        cardinalLip[index] = lipZ;
                    } else if (surfX > surfZ) {
                        cardinal[index] = surfX;
                        cardinalLip[index] = lipX;
                    } else {
                        cardinal[index] = surfX;
                        cardinalLip[index] = Math.max(lipX, lipZ);
                    }
                }
            }
            if (!finalPass) {
                float[] swap = source;
                source = target;
                target = swap;
                float[] distanceSwap = distanceSource;
                distanceSource = distanceTarget;
                distanceTarget = distanceSwap;
            }
        }

        // Diagonal resample one step along (+1,+1), (-1,-1), (+1,-1), (-1,+1):
        source = workA;
        target = workB;
        distanceSource = distanceWorkA;
        distanceTarget = distanceWorkB;
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
                for (int channel = 0; channel < 4; channel++) {
                    distanceSource[base + channel] = 1.0F;
                }
            }
        }

        // Diagonal doubling passes; final pass combines with cardinal into output and optional lipOutput:
        for (int pass = 0; pass < diagonalStrides.length; pass++) {
            int stride = diagonalStrides[pass];
            boolean finalPass = pass == diagonalStrides.length - 1;
            float drop = diagSlope * stride;
            for (int iz = 0; iz < mapSize; iz++) {
                for (int ix = 0; ix < mapSize; ix++) {
                    if (!finalPass) {
                        int base = (iz * mapSize + ix) * 4;
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, stride, stride, 0,
                                base, drop, stride, threshold, wall);
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, -stride, -stride, 1,
                                base, drop, stride, threshold, wall);
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, stride, -stride, 2,
                                base, drop, stride, threshold, wall);
                        scanStep(source, distanceSource, target, distanceTarget, mapSize, ix, iz, -stride, stride, 3,
                                base, drop, stride, threshold, wall);
                        continue;
                    }
                    finalWall(source, distanceSource, mapSize, ix, iz, stride, stride, 0, drop, stride, applyPerpMin,
                            1, -1, threshold, wall);
                    float pp = wall[0];
                    float ppDistance = wall[1];
                    finalWall(source, distanceSource, mapSize, ix, iz, -stride, -stride, 1, drop, stride,
                            applyPerpMin, 1, -1, threshold, wall);
                    float mm = wall[0];
                    float mmDistance = wall[1];
                    finalWall(source, distanceSource, mapSize, ix, iz, stride, -stride, 2, drop, stride, applyPerpMin,
                            1, 1, threshold, wall);
                    float pm = wall[0];
                    float pmDistance = wall[1];
                    finalWall(source, distanceSource, mapSize, ix, iz, -stride, stride, 3, drop, stride, applyPerpMin,
                            1, 1, threshold, wall);
                    float mp = wall[0];
                    float mpDistance = wall[1];
                    float surfD1 = combineAxisSurface(pp, ppDistance, mm, mmDistance, diagSlope, noHeight, threshold);
                    float lipD1 = combineAxisLip(pp, ppDistance, mm, mmDistance, diagSlope, noHeight, threshold);
                    float surfD2 = combineAxisSurface(pm, pmDistance, mp, mpDistance, diagSlope, noHeight, threshold);
                    float lipD2 = combineAxisLip(pm, pmDistance, mp, mpDistance, diagSlope, noHeight, threshold);
                    float diagSurf;
                    float diagLip;
                    if (surfD2 > surfD1) {
                        diagSurf = surfD2;
                        diagLip = lipD2;
                    } else if (surfD1 > surfD2) {
                        diagSurf = surfD1;
                        diagLip = lipD1;
                    } else {
                        diagSurf = surfD1;
                        diagLip = Math.max(lipD1, lipD2);
                    }
                    int index = iz * mapSize + ix;
                    float cardSurf = cardinal[index];
                    float cardLip = cardinalLip[index];
                    if (diagSurf > cardSurf) {
                        output[index] = diagSurf;
                        if (lipOutput != null) {
                            lipOutput[index] = diagLip;
                        }
                    } else if (cardSurf > diagSurf) {
                        output[index] = cardSurf;
                        if (lipOutput != null) {
                            lipOutput[index] = cardLip;
                        }
                    } else {
                        output[index] = cardSurf;
                        if (lipOutput != null) {
                            lipOutput[index] = Math.max(cardLip, diagLip);
                        }
                    }
                }
            }
            if (!finalPass) {
                float[] swap = source;
                source = target;
                target = swap;
                float[] distanceSwap = distanceSource;
                distanceSource = distanceTarget;
                distanceTarget = distanceSwap;
            }
        }
    }

    /**
     * Independent brute-force evaluation of one texel (texelX, texelZ) of the flat cavity enclosure map across all four
     * opposing-wall axes, for verifying scan in GameTests.
     */
    public static float bruteForceTexel(int mapSize, int cardinalReach, int diagonalReach,
            SnowLee.HeightLookup heights, int minX, int minZ, int windowMinX, int windowMinZ, int windowSize,
            double slope, boolean applyPerpMin, int texelX, int texelZ) {
        float[] result = new float[2];
        bruteForceTexelPair(mapSize, cardinalReach, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ,
                windowSize, slope, applyPerpMin, texelX, texelZ, result);
        return result[0];
    }

    /**
     * Independent brute-force evaluation of both the flat cavity enclosure height (out[0]) and the lower lip of the
     * winning axis's two walls (out[1]) at one texel (texelX, texelZ), for verifying scan in GameTests.
     */
    public static void bruteForceTexelPair(int mapSize, int cardinalReach, int diagonalReach,
            SnowLee.HeightLookup heights, int minX, int minZ, int windowMinX, int windowMinZ, int windowSize,
            double slope, boolean applyPerpMin, int texelX, int texelZ, float[] out) {
        float floatSlope = (float) slope;
        float diagSlope = (float) (slope * DIAGONAL_STEP);
        float[] axisOut = new float[2];
        bruteForceAxis(mapSize, cardinalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 1, 0, 0, 1,
                floatSlope, applyPerpMin, texelX, texelZ, axisOut);
        float bestSurf = axisOut[0];
        float bestLip = axisOut[1];

        bruteForceAxis(mapSize, cardinalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 0, 1, 1, 0,
                floatSlope, applyPerpMin, texelX, texelZ, axisOut);
        if (axisOut[0] > bestSurf) {
            bestSurf = axisOut[0];
            bestLip = axisOut[1];
        } else if (axisOut[0] == bestSurf) {
            bestLip = Math.max(bestLip, axisOut[1]);
        }

        bruteForceAxis(mapSize, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 1, 1, 1, -1,
                diagSlope, applyPerpMin, texelX, texelZ, axisOut);
        if (axisOut[0] > bestSurf) {
            bestSurf = axisOut[0];
            bestLip = axisOut[1];
        } else if (axisOut[0] == bestSurf) {
            bestLip = Math.max(bestLip, axisOut[1]);
        }

        bruteForceAxis(mapSize, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 1, -1, 1, 1,
                diagSlope, applyPerpMin, texelX, texelZ, axisOut);
        if (axisOut[0] > bestSurf) {
            bestSurf = axisOut[0];
            bestLip = axisOut[1];
        } else if (axisOut[0] == bestSurf) {
            bestLip = Math.max(bestLip, axisOut[1]);
        }
        out[0] = bestSurf;
        out[1] = bestLip;
    }

    private static void bruteForceAxis(int mapSize, int reach, SnowLee.HeightLookup heights, int minX, int minZ,
            int windowMinX, int windowMinZ, int windowSize, int dx, int dz, int perpX, int perpZ, float stepSlope,
            boolean applyPerpMin, int texelX, int texelZ, float[] out) {
        float[] wall = new float[2];
        bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz, stepSlope,
                texelX, texelZ, wall);
        float pos = wall[0];
        float posDistance = wall[1];
        bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz, stepSlope,
                texelX, texelZ, wall);
        float neg = wall[0];
        float negDistance = wall[1];
        if (applyPerpMin) {
            for (int side = -1; side <= 1; side += 2) {
                int sideX = clampIndex(texelX + side * perpX, mapSize);
                int sideZ = clampIndex(texelZ + side * perpZ, mapSize);
                bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                        stepSlope, sideX, sideZ, wall);
                if (isLowerWall(wall[0], wall[1], pos, posDistance)) {
                    pos = wall[0];
                    posDistance = wall[1];
                }
                bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                        stepSlope, sideX, sideZ, wall);
                if (isLowerWall(wall[0], wall[1], neg, negDistance)) {
                    neg = wall[0];
                    negDistance = wall[1];
                }
            }
        }
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        out[0] = combineAxisSurface(pos, posDistance, neg, negDistance, stepSlope, noHeight, threshold);
        out[1] = combineAxisLip(pos, posDistance, neg, negDistance, stepSlope, noHeight, threshold);
    }

    private static float combineAxisSurface(float positive, float positiveDistance, float negative,
            float negativeDistance, float stepSlope, float noHeight, float threshold) {
        if (!(positive > threshold) || !(negative > threshold)) {
            return noHeight;
        }
        float lowerLip = Math.min(positive + stepSlope * positiveDistance, negative + stepSlope * negativeDistance);
        return Math.min(lowerLip, 0.5F * (positive + negative));
    }

    private static float combineAxisLip(float positive, float positiveDistance, float negative,
            float negativeDistance, float stepSlope, float noHeight, float threshold) {
        if (!(positive > threshold) || !(negative > threshold)) {
            return noHeight;
        }
        return Math.min(positive + stepSlope * positiveDistance, negative + stepSlope * negativeDistance);
    }

    /**
     * Zero-allocation cached CPU query that checks whether the flat cavity enclosure surface rises above eyeY anywhere
     * within NEAR_CAVITY_RADIUS blocks of the eye block, so the vanilla fog culling bound counts cavity thinning only
     * when the eye is near an enclosing cavity. Not thread safe.
     */
    public static final class NearCavityQuery {
        private static final int RADIUS = NEAR_CAVITY_RADIUS;
        private static final int RADIUS_SQUARED = RADIUS * RADIUS;
        private static final int MARGIN = RADIUS + 1;
        private static final int GRID_SIDE = 2 * MARGIN + 1;
        private final float[] slopedPos = new float[GRID_SIDE * GRID_SIDE];
        private final float[] slopedNeg = new float[GRID_SIDE * GRID_SIDE];
        private final float[] distancePos = new float[GRID_SIDE * GRID_SIDE];
        private final float[] distanceNeg = new float[GRID_SIDE * GRID_SIDE];
        private FarHeights cachedFarHeights;
        private int cachedCenterX;
        private int cachedCenterZ;
        private int cachedOriginChunkX;
        private int cachedOriginChunkZ;
        private long cachedHeightsVersion;
        private boolean hasCached;
        private float cachedMaxHeight = (float) NO_HEIGHT;

        /**
         * Whether any column within NEAR_CAVITY_RADIUS blocks of (centerX, centerZ) has flat cavity enclosure height
         * strictly above eyeY. Allocates nothing and caches the scan until the eye enters a new column or FarHeights
         * changes.
         */
        public boolean hasCavityAbove(FarHeights farHeights, int centerX, int centerZ, double eyeY) {
            return maxCavityHeightNear(farHeights, centerX, centerZ) > eyeY;
        }

        /**
         * Highest flat cavity enclosure height h_cav within NEAR_CAVITY_RADIUS blocks of (centerX, centerZ) for a
         * placed FarHeights window. Allocates nothing.
         */
        public float maxCavityHeightNear(FarHeights farHeights, int centerX, int centerZ) {
            if (farHeights == null || !farHeights.isPlaced()) {
                return (float) NO_HEIGHT;
            }
            int originChunkX = farHeights.originChunkX();
            int originChunkZ = farHeights.originChunkZ();
            long heightsVersion = farHeights.noLeavesChangeCount();
            if (hasCached && cachedFarHeights == farHeights && cachedCenterX == centerX && cachedCenterZ == centerZ
                    && cachedOriginChunkX == originChunkX && cachedOriginChunkZ == originChunkZ
                    && cachedHeightsVersion == heightsVersion) {
                return cachedMaxHeight;
            }
            int minX = minCoordinate(originChunkX);
            int minZ = minCoordinate(originChunkZ);
            int windowMinX = originChunkX * CHUNK_WIDTH;
            int windowMinZ = originChunkZ * CHUNK_WIDTH;
            float maxHeight = computeMaxHeightNear(farHeights.noLeavesHeights(), null, MAP_SIZE, minX, minZ,
                    windowMinX, windowMinZ, FarHeights.SIZE, centerX, centerZ);
            cachedFarHeights = farHeights;
            cachedCenterX = centerX;
            cachedCenterZ = centerZ;
            cachedOriginChunkX = originChunkX;
            cachedOriginChunkZ = originChunkZ;
            cachedHeightsVersion = heightsVersion;
            cachedMaxHeight = maxHeight;
            hasCached = true;
            return maxHeight;
        }

        /**
         * Highest flat cavity enclosure height h_cav within NEAR_CAVITY_RADIUS blocks of (centerX, centerZ) for a
         * synthetic height lookup in GameTests. Allocates nothing.
         */
        public float maxCavityHeightNear(SnowLee.HeightLookup heights, int mapSize, int minX, int minZ, int windowMinX,
                int windowMinZ, int windowSize, int centerX, int centerZ) {
            return computeMaxHeightNear(null, heights, mapSize, minX, minZ, windowMinX, windowMinZ, windowSize,
                    centerX, centerZ);
        }

        public void clear() {
            hasCached = false;
            cachedFarHeights = null;
            cachedMaxHeight = (float) NO_HEIGHT;
        }

        private float computeMaxHeightNear(short[] farNoLeaves, SnowLee.HeightLookup lookup, int mapSize, int minX,
                int minZ, int windowMinX, int windowMinZ, int windowSize, int centerX, int centerZ) {
            float floatSlope = (float) ENCLOSURE_SLOPE;
            float diagSlope = (float) (ENCLOSURE_SLOPE * DIAGONAL_STEP);
            float best = (float) NO_HEIGHT;
            best = Math.max(best, axisMaxNear(farNoLeaves, lookup, mapSize, minX, minZ, windowMinX, windowMinZ,
                    windowSize, centerX, centerZ, CARDINAL_REACH, 1, 0, 0, 1, floatSlope));
            best = Math.max(best, axisMaxNear(farNoLeaves, lookup, mapSize, minX, minZ, windowMinX, windowMinZ,
                    windowSize, centerX, centerZ, CARDINAL_REACH, 0, 1, 1, 0, floatSlope));
            best = Math.max(best, axisMaxNear(farNoLeaves, lookup, mapSize, minX, minZ, windowMinX, windowMinZ,
                    windowSize, centerX, centerZ, DIAGONAL_REACH, 1, 1, 1, -1, diagSlope));
            best = Math.max(best, axisMaxNear(farNoLeaves, lookup, mapSize, minX, minZ, windowMinX, windowMinZ,
                    windowSize, centerX, centerZ, DIAGONAL_REACH, 1, -1, 1, 1, diagSlope));
            return best;
        }

        private float axisMaxNear(short[] farNoLeaves, SnowLee.HeightLookup lookup, int mapSize, int minX, int minZ,
                int windowMinX, int windowMinZ, int windowSize, int centerX, int centerZ, int reach, int dx, int dz,
                int perpX, int perpZ, float stepSlope) {
            float noHeight = (float) NO_HEIGHT;
            float threshold = (float) NO_HEIGHT_THRESHOLD;
            for (int gz = 0; gz < GRID_SIDE; gz++) {
                int worldZ = centerZ + gz - MARGIN;
                int texelZ = clampIndex(worldZ - minZ, mapSize);
                int row = gz * GRID_SIDE;
                for (int gx = 0; gx < GRID_SIDE; gx++) {
                    int worldX = centerX + gx - MARGIN;
                    int texelX = clampIndex(worldX - minX, mapSize);
                    int slot = row + gx;
                    scanRay(farNoLeaves, lookup, mapSize, reach, minX, minZ, windowMinX, windowMinZ, windowSize,
                            dx, dz, stepSlope, texelX, texelZ, noHeight, threshold, slot, true);
                    scanRay(farNoLeaves, lookup, mapSize, reach, minX, minZ, windowMinX, windowMinZ, windowSize,
                            -dx, -dz, stepSlope, texelX, texelZ, noHeight, threshold, slot, false);
                }
            }
            float best = noHeight;
            for (int oz = -RADIUS; oz <= RADIUS; oz++) {
                int gz = oz + MARGIN;
                int worldZ = centerZ + oz;
                if (worldZ < minZ || worldZ >= minZ + mapSize) {
                    continue;
                }
                for (int ox = -RADIUS; ox <= RADIUS; ox++) {
                    if (ox * ox + oz * oz > RADIUS_SQUARED) {
                        continue;
                    }
                    int worldX = centerX + ox;
                    if (worldX < minX || worldX >= minX + mapSize) {
                        continue;
                    }
                    int gx = ox + MARGIN;
                    int slot0 = gz * GRID_SIDE + gx;
                    int slot1 = (gz + perpZ) * GRID_SIDE + (gx + perpX);
                    int slot2 = (gz - perpZ) * GRID_SIDE + (gx - perpX);
                    float sp = slopedPos[slot0];
                    float dp = distancePos[slot0];
                    float sn = slopedNeg[slot0];
                    float dn = distanceNeg[slot0];
                    if (isLowerWall(slopedPos[slot1], distancePos[slot1], sp, dp)) {
                        sp = slopedPos[slot1];
                        dp = distancePos[slot1];
                    }
                    if (isLowerWall(slopedPos[slot2], distancePos[slot2], sp, dp)) {
                        sp = slopedPos[slot2];
                        dp = distancePos[slot2];
                    }
                    if (isLowerWall(slopedNeg[slot1], distanceNeg[slot1], sn, dn)) {
                        sn = slopedNeg[slot1];
                        dn = distanceNeg[slot1];
                    }
                    if (isLowerWall(slopedNeg[slot2], distanceNeg[slot2], sn, dn)) {
                        sn = slopedNeg[slot2];
                        dn = distanceNeg[slot2];
                    }
                    float surface = combineAxisSurface(sp, dp, sn, dn, stepSlope, noHeight, threshold);
                    if (surface <= best) {
                        continue;
                    }
                    // Only open-sky cavity columns (h_cav above the column's own terrain) thin the fog:
                    if (worldX >= windowMinX && worldX < windowMinX + windowSize && worldZ >= windowMinZ
                            && worldZ < windowMinZ + windowSize) {
                        short colHeight = farNoLeaves != null ? farNoLeaves[FarHeights.texelOf(worldX, worldZ)]
                                : lookup.heightAt(worldX, worldZ);
                        if (colHeight > FarHeights.UNKNOWN && !(surface > colHeight)) {
                            continue;
                        }
                    }
                    best = surface;
                }
            }
            return best;
        }

        private void scanRay(short[] farNoLeaves, SnowLee.HeightLookup lookup, int mapSize, int reach, int minX,
                int minZ, int windowMinX, int windowMinZ, int windowSize, int dx, int dz, float stepSlope, int texelX,
                int texelZ, float noHeight, float threshold, int slot, boolean positive) {
            float bestSloped = noHeight;
            float bestDistance = 0.0F;
            int windowMaxX = windowMinX + windowSize;
            int windowMaxZ = windowMinZ + windowSize;
            for (int step = 1; step <= reach; step++) {
                int sx = texelX + dx * (step - 1);
                int sz = texelZ + dz * (step - 1);
                if (sx < 0 || sx >= mapSize || sz < 0 || sz >= mapSize) {
                    break;
                }
                int colX = minX + sx + dx;
                int colZ = minZ + sz + dz;
                if (colX < windowMinX || colX >= windowMaxX || colZ < windowMinZ || colZ >= windowMaxZ) {
                    continue;
                }
                short height = farNoLeaves != null ? farNoLeaves[FarHeights.texelOf(colX, colZ)]
                        : lookup.heightAt(colX, colZ);
                if (height > FarHeights.UNKNOWN) {
                    float slopedValue = height - stepSlope * step;
                    if (slopedValue > threshold && slopedValue > bestSloped) {
                        bestSloped = slopedValue;
                        bestDistance = step;
                    }
                }
            }
            if (positive) {
                slopedPos[slot] = bestSloped;
                distancePos[slot] = bestDistance;
            } else {
                slopedNeg[slot] = bestSloped;
                distanceNeg[slot] = bestDistance;
            }
        }
    }

    private static void bruteForceRay(int mapSize, int reach, SnowLee.HeightLookup heights, int minX, int minZ,
            int windowMinX, int windowMinZ, int windowSize, int dx, int dz, float stepSlope, int texelX, int texelZ,
            float[] wallOut) {
        float best = (float) NO_HEIGHT;
        float bestDistance = 0.0F;
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
                if (value > threshold && value > best) {
                    best = value;
                    bestDistance = step;
                }
            }
        }
        wallOut[0] = best;
        wallOut[1] = bestDistance;
    }

    private static void scanWall(float[] source, float[] distanceSource, int mapSize, int ix, int iz, int offsetX,
            int offsetZ, int channel, float drop, int stride, float threshold, float[] wallOut) {
        int index = (iz * mapSize + ix) * 4 + channel;
        float sloped = source[index];
        float distance = distanceSource[index];
        int sampleX = ix + offsetX;
        int sampleZ = iz + offsetZ;
        if (sampleX >= 0 && sampleX < mapSize && sampleZ >= 0 && sampleZ < mapSize) {
            int sampleIndex = (sampleZ * mapSize + sampleX) * 4 + channel;
            float neighbor = source[sampleIndex];
            if (neighbor > threshold && neighbor - drop > sloped) {
                sloped = neighbor - drop;
                distance = distanceSource[sampleIndex] + stride;
            }
        }
        wallOut[0] = sloped;
        wallOut[1] = distance;
    }

    private static void scanStep(float[] source, float[] distanceSource, float[] target, float[] distanceTarget,
            int mapSize, int ix, int iz, int offsetX, int offsetZ, int channel, int base, float drop, int stride,
            float threshold, float[] wall) {
        scanWall(source, distanceSource, mapSize, ix, iz, offsetX, offsetZ, channel, drop, stride, threshold, wall);
        target[base + channel] = wall[0];
        distanceTarget[base + channel] = wall[1];
    }

    private static void finalWall(float[] source, float[] distanceSource, int mapSize, int ix, int iz, int offsetX,
            int offsetZ, int channel, float drop, int stride, boolean applyPerpMin, int perpX, int perpZ,
            float threshold, float[] wallOut) {
        scanWall(source, distanceSource, mapSize, ix, iz, offsetX, offsetZ, channel, drop, stride, threshold, wallOut);
        if (!applyPerpMin) {
            return;
        }
        float sloped = wallOut[0];
        float distance = wallOut[1];
        for (int side = -1; side <= 1; side += 2) {
            int sideX = clampIndex(ix + side * perpX, mapSize);
            int sideZ = clampIndex(iz + side * perpZ, mapSize);
            scanWall(source, distanceSource, mapSize, sideX, sideZ, offsetX, offsetZ, channel, drop, stride, threshold,
                    wallOut);
            if (isLowerWall(wallOut[0], wallOut[1], sloped, distance)) {
                sloped = wallOut[0];
                distance = wallOut[1];
            }
        }
        wallOut[0] = sloped;
        wallOut[1] = distance;
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
