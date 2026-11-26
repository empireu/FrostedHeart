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
 * falling snow brakes horizontally and settles onto the floor. The enclosure surface h_cav(x, z) and its lower opposing
 * rim are scanned from FarHeights no-leaves heights on a world-aligned 512 by 512 grid (1 block per texel, +-256 blocks
 * around the window center) along four axes (+-X, +-Z, +-(X+Z), +-(X-Z)) up to about 32 blocks at ENCLOSURE_SLOPE =
 * 0.65. Along each axis, a 3-texel perpendicular minimum erases thin posts narrower than 3 blocks on both sloped and
 * raw wall heights, and combining min(min(rawPos, rawNeg), 0.5 * (h_pos + h_neg)) produces a wall-to-wall flat surface
 * clamped to the lower rim while giving single cliff feet, wide valleys and gentle V-valleys no thinning. Taking the
 * maximum over all four axes keeps ravines nearly isotropic at 0, 22.5 and 45 degrees.
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
     * writing mapSize by mapSize flat enclosure heights into output and the winning axis's lower opposing rim height
     * into rimOutput (when non-null), matching fog_cavity_build.fsh.
     */
    public static void scan(int mapSize, int[] cardinalStrides, int[] diagonalStrides, SnowLee.HeightLookup heights,
            int minX, int minZ, int windowMinX, int windowMinZ, int windowSize, double slope, boolean applyPerpMin,
            float[] output, float[] rimOutput) {
        int texelCount = mapSize * mapSize;
        float[] workA = new float[texelCount * 4];
        float[] workB = new float[texelCount * 4];
        float[] rawWorkA = new float[texelCount * 4];
        float[] rawWorkB = new float[texelCount * 4];
        float[] cardinal = new float[texelCount];
        float[] cardinalRim = new float[texelCount];
        float floatSlope = (float) slope;
        float diagSlope = (float) (slope * DIAGONAL_STEP);
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;

        // Pass 0: cardinal resample at t = 1 along +X (0), -X (1), +Z (2), -Z (3) for both sloped and raw heights:
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
                rawWorkA[base] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX + 1, worldZ,
                        0.0F, noHeight, threshold);
                rawWorkA[base + 1] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX - 1, worldZ,
                        0.0F, noHeight, threshold);
                rawWorkA[base + 2] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX, worldZ + 1,
                        0.0F, noHeight, threshold);
                rawWorkA[base + 3] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX, worldZ - 1,
                        0.0F, noHeight, threshold);
            }
        }

        // Passes 1..cardinalStrides.length: cardinal doubling scan; final pass applies 3-texel perp min and combines:
        float[] source = workA;
        float[] target = workB;
        float[] rawSource = rawWorkA;
        float[] rawTarget = rawWorkB;
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
                        rawTarget[base] = scanDirection(rawSource, mapSize, ix, iz, stride, 0, 0, 0.0F, threshold);
                        rawTarget[base + 1] = scanDirection(rawSource, mapSize, ix, iz, -stride, 0, 1, 0.0F, threshold);
                        rawTarget[base + 2] = scanDirection(rawSource, mapSize, ix, iz, 0, stride, 2, 0.0F, threshold);
                        rawTarget[base + 3] = scanDirection(rawSource, mapSize, ix, iz, 0, -stride, 3, 0.0F, threshold);
                        continue;
                    }
                    float plusX = scanDirection(source, mapSize, ix, iz, stride, 0, 0, drop, threshold);
                    float minusX = scanDirection(source, mapSize, ix, iz, -stride, 0, 1, drop, threshold);
                    float plusZ = scanDirection(source, mapSize, ix, iz, 0, stride, 2, drop, threshold);
                    float minusZ = scanDirection(source, mapSize, ix, iz, 0, -stride, 3, drop, threshold);
                    float rawPlusX = scanDirection(rawSource, mapSize, ix, iz, stride, 0, 0, 0.0F, threshold);
                    float rawMinusX = scanDirection(rawSource, mapSize, ix, iz, -stride, 0, 1, 0.0F, threshold);
                    float rawPlusZ = scanDirection(rawSource, mapSize, ix, iz, 0, stride, 2, 0.0F, threshold);
                    float rawMinusZ = scanDirection(rawSource, mapSize, ix, iz, 0, -stride, 3, 0.0F, threshold);
                    if (applyPerpMin) {
                        int zMinus = Math.max(iz - 1, 0);
                        int zPlus = Math.min(iz + 1, mapSize - 1);
                        plusX = Math.min(plusX, Math.min(
                                scanDirection(source, mapSize, ix, zMinus, stride, 0, 0, drop, threshold),
                                scanDirection(source, mapSize, ix, zPlus, stride, 0, 0, drop, threshold)));
                        minusX = Math.min(minusX, Math.min(
                                scanDirection(source, mapSize, ix, zMinus, -stride, 0, 1, drop, threshold),
                                scanDirection(source, mapSize, ix, zPlus, -stride, 0, 1, drop, threshold)));
                        rawPlusX = Math.min(rawPlusX, Math.min(
                                scanDirection(rawSource, mapSize, ix, zMinus, stride, 0, 0, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, ix, zPlus, stride, 0, 0, 0.0F, threshold)));
                        rawMinusX = Math.min(rawMinusX, Math.min(
                                scanDirection(rawSource, mapSize, ix, zMinus, -stride, 0, 1, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, ix, zPlus, -stride, 0, 1, 0.0F, threshold)));
                        int xMinus = Math.max(ix - 1, 0);
                        int xPlus = Math.min(ix + 1, mapSize - 1);
                        plusZ = Math.min(plusZ, Math.min(
                                scanDirection(source, mapSize, xMinus, iz, 0, stride, 2, drop, threshold),
                                scanDirection(source, mapSize, xPlus, iz, 0, stride, 2, drop, threshold)));
                        minusZ = Math.min(minusZ, Math.min(
                                scanDirection(source, mapSize, xMinus, iz, 0, -stride, 3, drop, threshold),
                                scanDirection(source, mapSize, xPlus, iz, 0, -stride, 3, drop, threshold)));
                        rawPlusZ = Math.min(rawPlusZ, Math.min(
                                scanDirection(rawSource, mapSize, xMinus, iz, 0, stride, 2, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, xPlus, iz, 0, stride, 2, 0.0F, threshold)));
                        rawMinusZ = Math.min(rawMinusZ, Math.min(
                                scanDirection(rawSource, mapSize, xMinus, iz, 0, -stride, 3, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, xPlus, iz, 0, -stride, 3, 0.0F, threshold)));
                    }
                    float surfX = combineAxisSurface(plusX, minusX, rawPlusX, rawMinusX, noHeight, threshold);
                    float rimX = combineAxisRim(plusX, minusX, rawPlusX, rawMinusX, noHeight, threshold);
                    float surfZ = combineAxisSurface(plusZ, minusZ, rawPlusZ, rawMinusZ, noHeight, threshold);
                    float rimZ = combineAxisRim(plusZ, minusZ, rawPlusZ, rawMinusZ, noHeight, threshold);
                    int index = iz * mapSize + ix;
                    if (surfZ > surfX) {
                        cardinal[index] = surfZ;
                        cardinalRim[index] = rimZ;
                    } else if (surfX > surfZ) {
                        cardinal[index] = surfX;
                        cardinalRim[index] = rimX;
                    } else {
                        cardinal[index] = surfX;
                        cardinalRim[index] = Math.max(rimX, rimZ);
                    }
                }
            }
            if (!finalPass) {
                float[] swap = source;
                source = target;
                target = swap;
                float[] rawSwap = rawSource;
                rawSource = rawTarget;
                rawTarget = rawSwap;
            }
        }

        // Diagonal resample at t = 1 along (+1,+1), (-1,-1), (+1,-1), (-1,+1):
        source = workA;
        target = workB;
        rawSource = rawWorkA;
        rawTarget = rawWorkB;
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
                rawSource[base] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX + 1, worldZ + 1,
                        0.0F, noHeight, threshold);
                rawSource[base + 1] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX - 1,
                        worldZ - 1, 0.0F, noHeight, threshold);
                rawSource[base + 2] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX + 1,
                        worldZ - 1, 0.0F, noHeight, threshold);
                rawSource[base + 3] = sampleWithDrop(heights, windowMinX, windowMinZ, windowSize, worldX - 1,
                        worldZ + 1, 0.0F, noHeight, threshold);
            }
        }

        // Diagonal doubling passes; final pass combines with cardinal into output and optional rimOutput:
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
                        rawTarget[base] = scanDirection(rawSource, mapSize, ix, iz, stride, stride, 0, 0.0F, threshold);
                        rawTarget[base + 1] = scanDirection(rawSource, mapSize, ix, iz, -stride, -stride, 1, 0.0F,
                                threshold);
                        rawTarget[base + 2] = scanDirection(rawSource, mapSize, ix, iz, stride, -stride, 2, 0.0F,
                                threshold);
                        rawTarget[base + 3] = scanDirection(rawSource, mapSize, ix, iz, -stride, stride, 3, 0.0F,
                                threshold);
                        continue;
                    }
                    float pp = scanDirection(source, mapSize, ix, iz, stride, stride, 0, drop, threshold);
                    float mm = scanDirection(source, mapSize, ix, iz, -stride, -stride, 1, drop, threshold);
                    float pm = scanDirection(source, mapSize, ix, iz, stride, -stride, 2, drop, threshold);
                    float mp = scanDirection(source, mapSize, ix, iz, -stride, stride, 3, drop, threshold);
                    float rawPP = scanDirection(rawSource, mapSize, ix, iz, stride, stride, 0, 0.0F, threshold);
                    float rawMM = scanDirection(rawSource, mapSize, ix, iz, -stride, -stride, 1, 0.0F, threshold);
                    float rawPM = scanDirection(rawSource, mapSize, ix, iz, stride, -stride, 2, 0.0F, threshold);
                    float rawMP = scanDirection(rawSource, mapSize, ix, iz, -stride, stride, 3, 0.0F, threshold);
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
                        rawPP = Math.min(rawPP, Math.min(
                                scanDirection(rawSource, mapSize, ax1, az1, stride, stride, 0, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, ax2, az2, stride, stride, 0, 0.0F, threshold)));
                        rawMM = Math.min(rawMM, Math.min(
                                scanDirection(rawSource, mapSize, ax1, az1, -stride, -stride, 1, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, ax2, az2, -stride, -stride, 1, 0.0F, threshold)));
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
                        rawPM = Math.min(rawPM, Math.min(
                                scanDirection(rawSource, mapSize, bx1, bz1, stride, -stride, 2, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, bx2, bz2, stride, -stride, 2, 0.0F, threshold)));
                        rawMP = Math.min(rawMP, Math.min(
                                scanDirection(rawSource, mapSize, bx1, bz1, -stride, stride, 3, 0.0F, threshold),
                                scanDirection(rawSource, mapSize, bx2, bz2, -stride, stride, 3, 0.0F, threshold)));
                    }
                    float surfD1 = combineAxisSurface(pp, mm, rawPP, rawMM, noHeight, threshold);
                    float rimD1 = combineAxisRim(pp, mm, rawPP, rawMM, noHeight, threshold);
                    float surfD2 = combineAxisSurface(pm, mp, rawPM, rawMP, noHeight, threshold);
                    float rimD2 = combineAxisRim(pm, mp, rawPM, rawMP, noHeight, threshold);
                    float diagSurf;
                    float diagRim;
                    if (surfD2 > surfD1) {
                        diagSurf = surfD2;
                        diagRim = rimD2;
                    } else if (surfD1 > surfD2) {
                        diagSurf = surfD1;
                        diagRim = rimD1;
                    } else {
                        diagSurf = surfD1;
                        diagRim = Math.max(rimD1, rimD2);
                    }
                    int index = iz * mapSize + ix;
                    float cardSurf = cardinal[index];
                    float cardRim = cardinalRim[index];
                    if (diagSurf > cardSurf) {
                        output[index] = diagSurf;
                        if (rimOutput != null) {
                            rimOutput[index] = diagRim;
                        }
                    } else if (cardSurf > diagSurf) {
                        output[index] = cardSurf;
                        if (rimOutput != null) {
                            rimOutput[index] = cardRim;
                        }
                    } else {
                        output[index] = cardSurf;
                        if (rimOutput != null) {
                            rimOutput[index] = Math.max(cardRim, diagRim);
                        }
                    }
                }
            }
            if (!finalPass) {
                float[] swap = source;
                source = target;
                target = swap;
                float[] rawSwap = rawSource;
                rawSource = rawTarget;
                rawTarget = rawSwap;
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
     * Independent brute-force evaluation of both the flat cavity enclosure height (out[0]) and the winning axis's lower
     * opposing rim height (out[1]) at one texel (texelX, texelZ), for verifying scan in GameTests.
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
        float bestRim = axisOut[1];

        bruteForceAxis(mapSize, cardinalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 0, 1, 1, 0,
                floatSlope, applyPerpMin, texelX, texelZ, axisOut);
        if (axisOut[0] > bestSurf) {
            bestSurf = axisOut[0];
            bestRim = axisOut[1];
        } else if (axisOut[0] == bestSurf) {
            bestRim = Math.max(bestRim, axisOut[1]);
        }

        bruteForceAxis(mapSize, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 1, 1, 1, -1,
                diagSlope, applyPerpMin, texelX, texelZ, axisOut);
        if (axisOut[0] > bestSurf) {
            bestSurf = axisOut[0];
            bestRim = axisOut[1];
        } else if (axisOut[0] == bestSurf) {
            bestRim = Math.max(bestRim, axisOut[1]);
        }

        bruteForceAxis(mapSize, diagonalReach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, 1, -1, 1, 1,
                diagSlope, applyPerpMin, texelX, texelZ, axisOut);
        if (axisOut[0] > bestSurf) {
            bestSurf = axisOut[0];
            bestRim = axisOut[1];
        } else if (axisOut[0] == bestSurf) {
            bestRim = Math.max(bestRim, axisOut[1]);
        }
        out[0] = bestSurf;
        out[1] = bestRim;
    }

    private static void bruteForceAxis(int mapSize, int reach, SnowLee.HeightLookup heights, int minX, int minZ,
            int windowMinX, int windowMinZ, int windowSize, int dx, int dz, int perpX, int perpZ, float stepSlope,
            boolean applyPerpMin, int texelX, int texelZ, float[] out) {
        float pos = bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                stepSlope, texelX, texelZ);
        float neg = bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                stepSlope, texelX, texelZ);
        float rawPos = bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                0.0F, texelX, texelZ);
        float rawNeg = bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                0.0F, texelX, texelZ);
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
            rawPos = Math.min(rawPos, Math.min(
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                            0.0F, x1, z1),
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, dx, dz,
                            0.0F, x2, z2)));
            rawNeg = Math.min(rawNeg, Math.min(
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                            0.0F, x1, z1),
                    bruteForceRay(mapSize, reach, heights, minX, minZ, windowMinX, windowMinZ, windowSize, -dx, -dz,
                            0.0F, x2, z2)));
        }
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        out[0] = combineAxisSurface(pos, neg, rawPos, rawNeg, noHeight, threshold);
        out[1] = combineAxisRim(pos, neg, rawPos, rawNeg, noHeight, threshold);
    }

    private static float combineAxisSurface(float slopedPos, float slopedNeg, float rawPos, float rawNeg,
            float noHeight, float threshold) {
        if (!(slopedPos > threshold) || !(slopedNeg > threshold) || !(rawPos > threshold) || !(rawNeg > threshold)) {
            return noHeight;
        }
        return Math.min(Math.min(rawPos, rawNeg), 0.5F * (slopedPos + slopedNeg));
    }

    private static float combineAxisRim(float slopedPos, float slopedNeg, float rawPos, float rawNeg,
            float noHeight, float threshold) {
        if (!(slopedPos > threshold) || !(slopedNeg > threshold) || !(rawPos > threshold) || !(rawNeg > threshold)) {
            return noHeight;
        }
        return Math.min(rawPos, rawNeg);
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
        private final float[] rawPos = new float[GRID_SIDE * GRID_SIDE];
        private final float[] rawNeg = new float[GRID_SIDE * GRID_SIDE];
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
                    scanRayPair(farNoLeaves, lookup, mapSize, reach, minX, minZ, windowMinX, windowMinZ, windowSize,
                            dx, dz, stepSlope, texelX, texelZ, noHeight, threshold, slot, true);
                    scanRayPair(farNoLeaves, lookup, mapSize, reach, minX, minZ, windowMinX, windowMinZ, windowSize,
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
                    float sp = Math.min(slopedPos[slot0], Math.min(slopedPos[slot1], slopedPos[slot2]));
                    float sn = Math.min(slopedNeg[slot0], Math.min(slopedNeg[slot1], slopedNeg[slot2]));
                    float rp = Math.min(rawPos[slot0], Math.min(rawPos[slot1], rawPos[slot2]));
                    float rn = Math.min(rawNeg[slot0], Math.min(rawNeg[slot1], rawNeg[slot2]));
                    float surface = combineAxisSurface(sp, sn, rp, rn, noHeight, threshold);
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

        private void scanRayPair(short[] farNoLeaves, SnowLee.HeightLookup lookup, int mapSize, int reach, int minX,
                int minZ, int windowMinX, int windowMinZ, int windowSize, int dx, int dz, float stepSlope, int texelX,
                int texelZ, float noHeight, float threshold, int slot, boolean positive) {
            float bestSloped = noHeight;
            float bestRaw = noHeight;
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
                    float rawValue = height;
                    if (rawValue > bestRaw) {
                        bestRaw = rawValue;
                    }
                    float slopedValue = rawValue - stepSlope * step;
                    if (slopedValue > threshold && slopedValue > bestSloped) {
                        bestSloped = slopedValue;
                    }
                }
            }
            if (positive) {
                slopedPos[slot] = bestSloped;
                rawPos[slot] = bestRaw;
            } else {
                slopedNeg[slot] = bestSloped;
                rawNeg[slot] = bestRaw;
            }
        }
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
