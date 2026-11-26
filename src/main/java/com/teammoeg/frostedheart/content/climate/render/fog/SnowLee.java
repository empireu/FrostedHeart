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
 * The math and shared constants of the snowfall lee map, which snow_lee_build.fsh and snow_lee.glsl repeat on the GPU,
 * so that GameTests can check it without an OpenGL context.
 * <p>
 * Behind steep terrain or buildings, falling snow leaves a sheltered lee whose top surface falls away downwind at the
 * slope c = fallSpeed / windSpeed + snowLeeFill:
 * h(p) = max over 1 <= t <= REACH of H(p - t * w) - c * t,
 * where H is the no-leaves motion height of FarHeights and w is the unit wind direction. The t = 0 term is excluded so
 * flat ground and wall tops keep their snow and collision owns the ground surface. Calm wind gives no lee.
 * <p>
 * The scan runs in a 1024 by 1024 frame rotated to the wind around the center of the FarHeights window, where every
 * upwind step is an integer texel offset and the doubling scan is exact. Pass 0 resamples the no-leaves heights at
 * t = 1 with two taps along the wind (so thin diagonal walls are not stepped over without dilating across the wind),
 * and passes 1 to 9 double the reach up to REACH = 480 with out-of-window samples returning NO_HEIGHT and never
 * wrapping. The final pass takes the minimum over three texels across the wind so obstacles narrower than 3 blocks
 * across the wind cast no lee while wider walls keep theirs at every wind angle.
 * <p>
 * Over a lee column (h > H), flakes sink at least as fast as c * windSpeed, fading smoothly back to their own fall
 * speed over the lee's depth h - H above the surface, and fade out across [h, h + min(snowLeeBand, h - H)] with their
 * own hash salt.
 */
public final class SnowLee {
    /**
     * Texels along each axis of the wind-aligned lee map, matching FarHeights.SIZE.
     */
    public static final int MAP_SIZE = FarHeights.SIZE;
    /**
     * Half of MAP_SIZE, the texel coordinate of the world anchor at the center of the map.
     */
    public static final int HALF_SIZE = MAP_SIZE / 2;
    /**
     * Upwind reach in blocks covered by the scan passes (1 + sum of PASS_STRIDES).
     */
    public static final int REACH = 480;
    /**
     * Number of doubling scan passes after the initial resample pass.
     */
    public static final int SCAN_PASSES = 9;
    /**
     * Total passes of one full rebuild: one resample pass plus SCAN_PASSES scan passes.
     */
    public static final int TOTAL_PASSES = SCAN_PASSES + 1;
    /**
     * Sentinel height for columns outside the window or with no obstacle, matching FarHeights.UNKNOWN.
     */
    public static final double NO_HEIGHT = FarHeights.UNKNOWN;
    /**
     * Any height at or below this threshold counts as no obstacle.
     */
    public static final double NO_HEIGHT_THRESHOLD = -30000.0;
    /**
     * Wind speed in blocks per second at or below which the air is calm and casts no lee.
     */
    public static final double MINIMUM_WIND_SPEED = 0.01;
    /**
     * Half-step along the wind in blocks for the two-tap resample in pass 0.
     */
    public static final double SAMPLE_OFFSET = 0.25;
    /**
     * Minimum wind turn in radians that triggers a rebuild of the lee map (0.25 degrees).
     */
    public static final double DIRECTION_REBUILD_RADIANS = Math.toRadians(0.25);
    /**
     * Minimum relative change in wind speed that triggers a rebuild of the lee map (2%).
     */
    public static final double SPEED_REBUILD_SHARE = 0.02;
    /**
     * Distance in blocks along either horizontal axis beyond which a published lee map is discarded immediately after
     * a jump instead of waiting for the next rebuild to finish.
     */
    public static final int MAX_ANCHOR_DRIFT_BLOCKS = 256;

    private static final int CHUNK_WIDTH = 16;
    private static final int[] PASS_STRIDES = {1, 2, 4, 8, 16, 32, 64, 128, 224};

    /**
     * Lookup of no-leaves column heights for the CPU scan twin.
     */
    public interface HeightLookup {
        short heightAt(int x, int z);
    }

    private SnowLee() {
    }

    /**
     * The upwind stride in texels of scan pass 1 to SCAN_PASSES.
     * @param pass the pass index from 1 to SCAN_PASSES
     */
    public static int passStride(int pass) {
        if (pass < 1 || pass > SCAN_PASSES) {
            throw new IllegalArgumentException("Lee scan pass " + pass + " is outside 1 to " + SCAN_PASSES);
        }
        return PASS_STRIDES[pass - 1];
    }

    /**
     * A copy of the scan strides for tests.
     */
    public static int[] passStrides() {
        return PASS_STRIDES.clone();
    }

    /**
     * Whether the wind is too calm to cast a lee.
     */
    public static boolean isCalm(double windSpeed) {
        return !(windSpeed > MINIMUM_WIND_SPEED) || !Double.isFinite(windSpeed);
    }

    /**
     * The world block coordinate of the center of a FarHeights window along one axis.
     * @param originChunk FarHeights.originChunkX or originChunkZ
     */
    public static int anchorCoordinate(int originChunk) {
        return (originChunk + FarHeights.CHUNKS / 2) * CHUNK_WIDTH;
    }

    /**
     * The lee surface downward slope c = fallSpeed / windSpeed + max(0, leeFill) in blocks dropped per block
     * downwind, or Double.POSITIVE_INFINITY when calm or invalid.
     */
    public static double slope(double fallSpeed, double windSpeed, double leeFill) {
        if (isCalm(windSpeed) || !(fallSpeed > 0.0) || !Double.isFinite(fallSpeed)) {
            return Double.POSITIVE_INFINITY;
        }
        double fill = Double.isFinite(leeFill) && leeFill > 0.0 ? leeFill : 0.0;
        return fallSpeed / windSpeed + fill;
    }

    /**
     * The downward speed c * windSpeed in blocks per second along the lee surface, or 0 when calm or invalid.
     */
    public static double leeFallSpeed(double slope, double windSpeed) {
        if (isCalm(windSpeed) || !(slope > 0.0) || !Double.isFinite(slope)) {
            return 0.0;
        }
        return slope * windSpeed;
    }

    /**
     * Shortest unsigned angle in radians between two directions, in [0, pi].
     */
    public static double angleDifference(double firstRadians, double secondRadians) {
        double delta = firstRadians - secondRadians;
        return Math.abs(Math.atan2(Math.sin(delta), Math.cos(delta)));
    }

    /**
     * Whether the lee map needs a new rebuild given the current world and wind state and the state that built the
     * published map.
     */
    public static boolean needsRebuild(boolean hasPublished, int anchorX, int anchorZ, long heightsVersion,
            double direction, double windSpeed, double fallSpeed, double leeFill, int builtAnchorX, int builtAnchorZ,
            long builtHeightsVersion, double builtDirection, double builtWindSpeed, double builtFallSpeed,
            double builtLeeFill) {
        if (!hasPublished) {
            return true;
        }
        if (anchorX != builtAnchorX || anchorZ != builtAnchorZ || heightsVersion != builtHeightsVersion) {
            return true;
        }
        if (Double.compare(fallSpeed, builtFallSpeed) != 0 || Double.compare(leeFill, builtLeeFill) != 0) {
            return true;
        }
        if (angleDifference(direction, builtDirection) >= DIRECTION_REBUILD_RADIANS) {
            return true;
        }
        double speedScale = Math.max(builtWindSpeed, MINIMUM_WIND_SPEED);
        return Math.abs(windSpeed - builtWindSpeed) >= SPEED_REBUILD_SHARE * speedScale;
    }

    /**
     * The visibility share from 0 to 1 of falling snow at height y over a column with lee surface height leeHeight and
     * no-leaves terrain height columnHeight. Flat ground and gentle slopes (leeHeight <= columnHeight) give 1
     * everywhere; below leeHeight gives 0; above leeHeight fades linearly to 1 over min(leeBand, leeHeight -
     * columnHeight) so shallow lee tips transition continuously without hiding snow on flat ground.
     */
    public static double leeShare(double y, double leeHeight, double columnHeight, double leeBand) {
        if (!(leeHeight > NO_HEIGHT_THRESHOLD) || !(columnHeight > NO_HEIGHT_THRESHOLD)) {
            return 1.0;
        }
        double leeDepth = leeHeight - columnHeight;
        if (!(leeDepth > 0.0)) {
            return 1.0;
        }
        if (!(leeBand > 0.0)) {
            return y >= leeHeight ? 1.0 : 0.0;
        }
        double effectiveBand = Math.min(leeBand, leeDepth);
        return clamp((y - leeHeight) / effectiveBand);
    }

    /**
     * The per-flake opacity weight for a lee share, using SALT_LEE independently of the shelter salt, with the same
     * softness band as SnowFlakes.shelterWeight.
     */
    public static double leeWeight(int flake, double leeShare) {
        double threshold = SnowFlakes.unit(flake, SnowFlakes.SALT_LEE) * (1.0 - SnowFlakes.SHELTER_SOFTNESS);
        return clamp((leeShare - threshold) / SnowFlakes.SHELTER_SOFTNESS);
    }

    /**
     * The downward speed in blocks per second of a flake over a lee column. When leeHeight exceeds columnHeight, the
     * descent is raised to at least leeFallSpeed at and below leeHeight, and fades smoothly back to ownFallSpeed over
     * the lee's depth (leeHeight - columnHeight) above leeHeight.
     */
    public static double sinkingFallSpeed(double y, double leeHeight, double columnHeight, double ownFallSpeed,
            double leeFallSpeed) {
        if (!(leeHeight > NO_HEIGHT_THRESHOLD) || !(columnHeight > NO_HEIGHT_THRESHOLD)) {
            return ownFallSpeed;
        }
        double leeDepth = leeHeight - columnHeight;
        if (!(leeDepth > 0.0) || !(leeFallSpeed > ownFallSpeed)) {
            return ownFallSpeed;
        }
        double above = clamp((y - leeHeight) / leeDepth);
        double weight = 1.0 - above * above * (3.0 - 2.0 * above);
        return ownFallSpeed + (leeFallSpeed - ownFallSpeed) * weight;
    }

    /**
     * Runs the exact GPU resample and doubling scan algorithm on the CPU in single precision, writing mapSize by
     * mapSize lee heights into output (x fastest along the wind, y across the wind).
     */
    public static void scan(int mapSize, int[] strides, HeightLookup heights, int anchorX, int anchorZ,
            int windowMinX, int windowMinZ, int windowSize, double dirX, double dirZ, double slope,
            boolean applyAcrossMin, float[] output, float[] scratch) {
        float halfSize = mapSize * 0.5F;
        float floatDirX = (float) dirX;
        float floatDirZ = (float) dirZ;
        float floatSlope = (float) slope;
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        float sampleOffset = (float) SAMPLE_OFFSET;

        float[] source = output;
        float[] target = scratch;
        for (int iy = 0; iy < mapSize; iy++) {
            float s = iy - halfSize + 0.5F;
            int row = iy * mapSize;
            for (int ix = 0; ix < mapSize; ix++) {
                float upwindU = ix - halfSize + 0.5F - 1.0F;
                float first = sampleWorldHeight(heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                        upwindU - sampleOffset, s, floatDirX, floatDirZ);
                float second = sampleWorldHeight(heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                        upwindU + sampleOffset, s, floatDirX, floatDirZ);
                float height = Math.max(first, second);
                source[row + ix] = height > threshold ? height - floatSlope : noHeight;
            }
        }

        for (int pass = 0; pass < strides.length; pass++) {
            int stride = strides[pass];
            boolean finalAcrossMin = applyAcrossMin && pass == strides.length - 1;
            float stepDrop = floatSlope * stride;
            for (int iy = 0; iy < mapSize; iy++) {
                int row = iy * mapSize;
                int minusRow = Math.max(iy - 1, 0) * mapSize;
                int plusRow = Math.min(iy + 1, mapSize - 1) * mapSize;
                for (int ix = 0; ix < mapSize; ix++) {
                    if (finalAcrossMin) {
                        float center = scanTexel(source, row, ix, stride, stepDrop, threshold);
                        float minus = scanTexel(source, minusRow, ix, stride, stepDrop, threshold);
                        float plus = scanTexel(source, plusRow, ix, stride, stepDrop, threshold);
                        target[row + ix] = Math.min(center, Math.min(minus, plus));
                    } else {
                        target[row + ix] = scanTexel(source, row, ix, stride, stepDrop, threshold);
                    }
                }
            }
            float[] swap = source;
            source = target;
            target = swap;
        }

        if (source != output) {
            System.arraycopy(source, 0, output, 0, mapSize * mapSize);
        }
    }

    /**
     * Independent brute-force evaluation of one texel (texelX, texelY) of the wind-aligned lee map over 1 <= t <=
     * reach, for verifying scan in GameTests.
     */
    public static float bruteForceTexel(int mapSize, int reach, HeightLookup heights, int anchorX, int anchorZ,
            int windowMinX, int windowMinZ, int windowSize, double dirX, double dirZ, double slope,
            boolean applyAcrossMin, int texelX, int texelY) {
        if (!applyAcrossMin) {
            return bruteForceRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize, dirX,
                    dirZ, slope, texelX, texelY);
        }
        float center = bruteForceRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                dirX, dirZ, slope, texelX, texelY);
        float minus = bruteForceRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                dirX, dirZ, slope, texelX, Math.max(texelY - 1, 0));
        float plus = bruteForceRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                dirX, dirZ, slope, texelX, Math.min(texelY + 1, mapSize - 1));
        return Math.min(center, Math.min(minus, plus));
    }

    /**
     * Bilinearly samples a wind-aligned lee map at a horizontal offset from the map's anchor, matching
     * snowLeeHeightAt in snow_lee.glsl.
     */
    public static float sampleBilinear(float[] map, int mapSize, double offsetX, double offsetZ, double dirX,
            double dirZ) {
        double halfSize = mapSize * 0.5;
        double u = offsetX * dirX + offsetZ * dirZ;
        double s = -offsetX * dirZ + offsetZ * dirX;
        if (Math.abs(u) >= halfSize - 0.5 || Math.abs(s) >= halfSize - 0.5) {
            return (float) NO_HEIGHT;
        }
        double fx = u + halfSize - 0.5;
        double fy = s + halfSize - 0.5;
        int x0 = Math.max(0, Math.min(mapSize - 1, (int) Math.floor(fx)));
        int y0 = Math.max(0, Math.min(mapSize - 1, (int) Math.floor(fy)));
        int x1 = Math.min(mapSize - 1, x0 + 1);
        int y1 = Math.min(mapSize - 1, y0 + 1);
        double tx = clamp(fx - x0);
        double ty = clamp(fy - y0);
        double low = map[y0 * mapSize + x0] + (map[y0 * mapSize + x1] - map[y0 * mapSize + x0]) * tx;
        double high = map[y1 * mapSize + x0] + (map[y1 * mapSize + x1] - map[y1 * mapSize + x0]) * tx;
        return (float) (low + (high - low) * ty);
    }

    private static float bruteForceRay(int mapSize, int reach, HeightLookup heights, int anchorX, int anchorZ,
            int windowMinX, int windowMinZ, int windowSize, double dirX, double dirZ, double slope, int texelX,
            int texelY) {
        float halfSize = mapSize * 0.5F;
        float floatDirX = (float) dirX;
        float floatDirZ = (float) dirZ;
        float floatSlope = (float) slope;
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        float sampleOffset = (float) SAMPLE_OFFSET;
        float s = texelY - halfSize + 0.5F;
        float best = noHeight;
        int maxSteps = Math.min(reach, texelX + 1);
        for (int step = 1; step <= maxSteps; step++) {
            float upwindU = texelX - halfSize + 0.5F - step;
            float first = sampleWorldHeight(heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                    upwindU - sampleOffset, s, floatDirX, floatDirZ);
            float second = sampleWorldHeight(heights, anchorX, anchorZ, windowMinX, windowMinZ, windowSize,
                    upwindU + sampleOffset, s, floatDirX, floatDirZ);
            float height = Math.max(first, second);
            if (height > threshold) {
                best = Math.max(best, height - floatSlope * step);
            }
        }
        return best;
    }

    private static float scanTexel(float[] source, int row, int ix, int stride, float stepDrop, float threshold) {
        float value = source[row + ix];
        if (ix >= stride) {
            float upwind = source[row + ix - stride];
            if (upwind > threshold) {
                value = Math.max(value, upwind - stepDrop);
            }
        }
        return value;
    }

    private static float sampleWorldHeight(HeightLookup heights, int anchorX, int anchorZ, int windowMinX,
            int windowMinZ, int windowSize, float u, float s, float dirX, float dirZ) {
        float dx = u * dirX - s * dirZ;
        float dz = u * dirZ + s * dirX;
        int columnX = anchorX + (int) Math.floor(dx);
        int columnZ = anchorZ + (int) Math.floor(dz);
        if (columnX < windowMinX || columnX >= windowMinX + windowSize || columnZ < windowMinZ
                || columnZ >= windowMinZ + windowSize) {
            return (float) NO_HEIGHT;
        }
        short height = heights.heightAt(columnX, columnZ);
        if (height <= FarHeights.UNKNOWN) {
            return (float) NO_HEIGHT;
        }
        return height;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
