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
 * The math and shared constants of the snowfall smoothed terrain and lee map, which snow_smooth_build.fsh,
 * snow_lee_build.fsh, snow_lee.glsl and snow_flake_update.fsh repeat on the GPU, so that GameTests can check them
 * without an OpenGL context.
 * <p>
 * The no-leaves heights H of FarHeights are first smoothed on a coarser 512 by 512 grid (2 blocks per texel) with a
 * normalized separable Gaussian of sigma = 16 blocks over known columns, yielding a macro terrain surface S that the
 * wind follows. The wind-aligned 1024 by 1024 scan then runs in two channels up to REACH = 256 blocks upwind at slope
 * c = fallSpeed / windSpeed + snowLeeFill: raw height h_raw = max(H(q) - c * t) and relief
 * e_rel = max((H(q) - S(q)) - c * t). The final pass applies the 3-texel across-wind minimum to both channels, writes
 * the combined lee surface h = min(h_raw, S + e_rel) to R, and writes the terrain-following slope
 * clamp(w . grad S, -1, 1) to G. Rounded hills cast almost no lee onto the plain behind them, walls, houses and cliff
 * rims keep their lees, and the min with h_raw prevents a false lee at the upwind foot of a cliff.
 * <p>
 * Active flakes gain the terrain-following vertical velocity (w . grad S) * windSpeed fading linearly to 0 over 32
 * blocks above S, and over a lee column sink at least as fast as (c + max(0, -w . grad S)) * windSpeed, fading smoothly
 * back over the lee's depth above h. The flat-ground bypass (h <= H gives full snow) applies only at or above H - 1 so
 * flakes beneath an overhang under h are hidden.
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
     * Blocks per coarse texel of the smoothed macro terrain surface S.
     */
    public static final int SMOOTH_SCALE = 2;
    /**
     * Texels along each axis of the smoothed macro terrain surface S (512 by 512).
     */
    public static final int SMOOTH_SIZE = FarHeights.SIZE / SMOOTH_SCALE;
    /**
     * Gaussian standard deviation of the smoothed terrain surface in blocks.
     */
    public static final double SMOOTH_SIGMA_BLOCKS = 16.0;
    /**
     * Gaussian standard deviation of the smoothed terrain surface in coarse texels.
     */
    public static final double SMOOTH_SIGMA_TEXELS = SMOOTH_SIGMA_BLOCKS / SMOOTH_SCALE;
    /**
     * Half-width of each 1D Gaussian pass in coarse texels (3 * sigma = 24 coarse texels = 48 blocks).
     */
    public static final int SMOOTH_KERNEL_RADIUS = 24;
    /**
     * Number of GPU passes to build S: 2x2 downsample, horizontal Gaussian, vertical Gaussian.
     */
    public static final int SMOOTH_PASSES = 3;
    /**
     * Half-span in blocks along the wind for the central difference that evaluates w . grad S.
     */
    public static final double SMOOTH_GRADIENT_STEP = 2.0;
    /**
     * Height in blocks above the smoothed terrain S over which the terrain-following vertical velocity fades to 0.
     */
    public static final double TERRAIN_FOLLOW_HEIGHT = 32.0;
    /**
     * Depth in blocks below a column's top within which the flat-ground shortcut still applies, preserving snow layers,
     * carpets, slabs and stairs while catching flakes strictly under an overhang.
     */
    public static final double OVERHANG_MARGIN = 1.0;
    /**
     * Upwind reach in blocks covered by the scan passes (1 + sum of PASS_STRIDES).
     */
    public static final int REACH = 256;
    /**
     * Number of doubling scan passes after the initial resample pass.
     */
    public static final int SCAN_PASSES = 8;
    /**
     * Total passes of one full lee rebuild: one resample pass plus SCAN_PASSES scan passes.
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
     * Minimum normalized Gaussian weight sum treated as valid when building S.
     */
    public static final double SMOOTH_MINIMUM_WEIGHT = 1.0e-6;
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
     * Distance in blocks along either horizontal axis beyond which a published map is discarded immediately after a
     * jump instead of waiting for the next rebuild to finish.
     */
    public static final int MAX_ANCHOR_DRIFT_BLOCKS = 256;
    /**
     * Size in texels of the cavity enclosure map read for ravine wind braking, matching FogCavity.MAP_SIZE.
     */
    public static final int CAVITY_MAP_SIZE = FogCavity.MAP_SIZE;
    /**
     * Minimum horizontal wind speed multiplier deep inside a narrow ravine so flakes settle nearly straight down.
     */
    public static final double CAVITY_WIND_FLOOR = 0.01;
    /**
     * Distance in blocks upwind of a braked flake at which the flow ceiling is read for the slope of the braking
     * ceiling along the wind.
     */
    public static final double CAVITY_SLOPE_DISTANCE = 2.0;
    /**
     * Steepest rise of the braking ceiling along the wind, in blocks per block, that sinks braked flakes.
     */
    public static final double CAVITY_SLOPE_LIMIT = 1.0;

    private static final int CHUNK_WIDTH = 16;
    private static final int[] PASS_STRIDES = {1, 2, 4, 8, 16, 32, 64, 128};
    private static final double UNIT_SCALE = 1.0 / 16777216.0;

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
     * The lowest world block coordinate of a FarHeights window along one axis, which is also the origin of the
     * smoothed terrain surface S.
     * @param originChunk FarHeights.originChunkX or originChunkZ
     */
    public static int smoothMinCoordinate(int originChunk) {
        return originChunk * CHUNK_WIDTH;
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
     * Whether the smoothed terrain surface S needs a rebuild. S depends only on the FarHeights window and its
     * no-leaves heights, never on wind direction or speed.
     */
    public static boolean needsSmoothRebuild(boolean hasPublished, int minX, int minZ, long heightsVersion,
            int builtMinX, int builtMinZ, long builtHeightsVersion) {
        if (!hasPublished) {
            return true;
        }
        return minX != builtMinX || minZ != builtMinZ || heightsVersion != builtHeightsVersion;
    }

    /**
     * Whether the lee map needs a new rebuild given the current world, smooth terrain version and wind state and the
     * state that built the published map.
     */
    public static boolean needsRebuild(boolean hasPublished, int anchorX, int anchorZ, long smoothVersion,
            double direction, double windSpeed, double fallSpeed, double leeFill, int builtAnchorX, int builtAnchorZ,
            long builtSmoothVersion, double builtDirection, double builtWindSpeed, double builtFallSpeed,
            double builtLeeFill) {
        if (!hasPublished) {
            return true;
        }
        if (anchorX != builtAnchorX || anchorZ != builtAnchorZ || smoothVersion != builtSmoothVersion) {
            return true;
        }
        if (Double.compare(leeFill, builtLeeFill) != 0) {
            return true;
        }
        double fallScale = Math.max(builtFallSpeed, MINIMUM_WIND_SPEED);
        if (Math.abs(fallSpeed - builtFallSpeed) >= SPEED_REBUILD_SHARE * fallScale) {
            return true;
        }
        if (angleDifference(direction, builtDirection) >= DIRECTION_REBUILD_RADIANS) {
            return true;
        }
        double speedScale = Math.max(builtWindSpeed, MINIMUM_WIND_SPEED);
        return Math.abs(windSpeed - builtWindSpeed) >= SPEED_REBUILD_SHARE * speedScale;
    }

    /**
     * The height below which flakes brake horizontally over an open-sky cavity column, which is the lower lip of the
     * two walls that define the cavity, or NO_HEIGHT outside a cavity.
     */
    public static double cavityBrakeHeight(double cavityHeight, double cavityLip, double columnHeight) {
        if (!(cavityHeight > NO_HEIGHT_THRESHOLD) || !(columnHeight > NO_HEIGHT_THRESHOLD)) {
            return NO_HEIGHT;
        }
        if (!(cavityHeight > columnHeight)) {
            return NO_HEIGHT;
        }
        return cavityLip > NO_HEIGHT_THRESHOLD ? Math.max(cavityHeight, cavityLip) : cavityHeight;
    }

    /**
     * The height below which the wind carries no full flake flux over a column, which is the braking ceiling over an
     * open-sky cavity column and the terrain elsewhere, matching snowCavityFlowCeilingAt in snow_lee.glsl.
     */
    public static double cavityFlowCeiling(double cavityHeight, double cavityLip, double columnHeight) {
        double brakeHeight = cavityBrakeHeight(cavityHeight, cavityLip, columnHeight);
        if (brakeHeight > NO_HEIGHT_THRESHOLD) {
            return brakeHeight;
        }
        return columnHeight > NO_HEIGHT_THRESHOLD ? columnHeight : NO_HEIGHT;
    }

    /**
     * The extra vertical velocity in blocks per second (negative downward) of a braked flake under brakeHeight where
     * the flow ceiling upwindCeiling CAVITY_SLOPE_DISTANCE blocks upwind is lower, so the flakes that the braking
     * slows sink toward the floor instead of crowding, matching snowCavitySinkVelocityFor in snow_lee.glsl.
     * @param windSpeed the flake wind speed before braking
     * @param windScale the braking wind multiplier at the flake
     */
    public static double cavitySinkVelocity(double brakeHeight, double upwindCeiling, double windSpeed,
            double windScale) {
        if (!(brakeHeight > NO_HEIGHT_THRESHOLD) || !(upwindCeiling > NO_HEIGHT_THRESHOLD)
                || !(windSpeed > MINIMUM_WIND_SPEED) || !Double.isFinite(windSpeed)) {
            return 0.0;
        }
        double rise = Math.max(0.0, Math.min(CAVITY_SLOPE_LIMIT,
                (brakeHeight - upwindCeiling) / CAVITY_SLOPE_DISTANCE));
        return -windSpeed * rise * (1.0 - clamp(windScale));
    }

    /**
     * The extra vertical velocity in blocks per second (negative downward) of a flake braked below a cavity's ceiling,
     * where a share of the wind that the braking removes pulls it down, matching snowCavityDowndraftVelocity in
     * snow_lee.glsl.
     * @param windSpeed the flake wind speed before braking
     * @param windScale the braking wind multiplier at the flake
     * @param downdraft the share of the removed wind that pulls down, snowCavityDowndraft
     */
    public static double cavityDowndraftVelocity(double windSpeed, double windScale, double downdraft) {
        if (!(windSpeed > MINIMUM_WIND_SPEED) || !Double.isFinite(windSpeed) || !(downdraft > 0.0)) {
            return 0.0;
        }
        return -windSpeed * Math.min(1.0, downdraft) * (1.0 - clamp(windScale));
    }

    /**
     * The radius in blocks of a flake's flutter circle, which grows by swirl blocks below a braking ceiling so braked
     * flakes swirl, matching snowCavityCircleRadius in snow_lee.glsl.
     * @param radiusShare the flake's own share of the mean radius, SnowFlakes.flutterRadiusShare
     */
    public static double cavityCircleRadius(double flutterRadius, double swirl, double cavityOpenFactor,
            double radiusShare) {
        double open = Double.isFinite(cavityOpenFactor) ? clamp(cavityOpenFactor) : 1.0;
        return (flutterRadius + Math.max(0.0, swirl) * (1.0 - open)) * radiusShare;
    }

    /**
     * Cavity openness factor in [0, 1] at world height y under a precomputed braking ceiling brakeHeight and transition
     * depth brakeLength in blocks, matching snowBrakeOpenFactorAt in snow_lee.glsl. Equals 1.0 outside cavities or
     * at/above brakeHeight, and eases smoothly down to 0.0 over brakeLength blocks below it.
     */
    public static double brakeOpenFactorAt(double y, double brakeHeight, double brakeLength) {
        if (!(brakeHeight > NO_HEIGHT_THRESHOLD)) {
            return 1.0;
        }
        double depth = brakeHeight - y;
        if (!(depth > 0.0)) {
            return 1.0;
        }
        if (!(brakeLength > 0.0) || !Double.isFinite(brakeLength)) {
            return 0.0;
        }
        double share = clamp(depth / brakeLength);
        return 1.0 - share * share * (3.0 - 2.0 * share);
    }

    /**
     * Depth-only cavity openness factor in [0, 1] for falling flakes at height y over transition depth brakeLength in
     * blocks, matching snowCavityOpenFactor in snow_lee.glsl.
     */
    public static double cavityOpenFactor(double y, double cavityHeight, double cavityLip, double columnHeight,
            double brakeLength) {
        double brakeHeight = cavityBrakeHeight(cavityHeight, cavityLip, columnHeight);
        return brakeOpenFactorAt(y, brakeHeight, brakeLength);
    }

    /**
     * Horizontal flake wind multiplier in [CAVITY_WIND_FLOOR, 1.0] for a cavity openness factor, matching
     * snowCavityWindScale in snow_lee.glsl.
     */
    public static double cavityWindScale(double cavityOpenFactor) {
        double open = Double.isFinite(cavityOpenFactor) ? clamp(cavityOpenFactor) : 1.0;
        return CAVITY_WIND_FLOOR + (1.0 - CAVITY_WIND_FLOOR) * open;
    }

    /**
     * Horizontal wind multiplier in [CAVITY_WIND_FLOOR, 1.0] at world height y under a precomputed braking ceiling
     * brakeHeight and transition depth brakeLength in blocks, matching snowBrakeWindScaleAt in snow_lee.glsl.
     */
    public static double brakeWindScaleAt(double y, double brakeHeight, double brakeLength) {
        return cavityWindScale(brakeOpenFactorAt(y, brakeHeight, brakeLength));
    }

    /**
     * The visibility share from 0 to 1 of falling snow at height y outside any cavity.
     */
    public static double leeShare(double y, double leeHeight, double columnHeight, double smoothHeight,
            double leeBand) {
        return leeShare(y, leeHeight, columnHeight, smoothHeight, 1.0, leeBand);
    }

    /**
     * The visibility share from 0 to 1 of falling snow at height y over a column with lee surface height leeHeight,
     * no-leaves terrain height columnHeight, smoothed macro surface height smoothHeight, and cavity openness
     * cavityOpenFactor in (0, 1].
     * <p>
     * Strictly below an overhang (y < columnHeight - OVERHANG_MARGIN), the flat-ground shortcut and cavity release do
     * not apply, keeping overhangs dry. On open-sky columns (y >= columnHeight - OVERHANG_MARGIN), a deep cavity
     * (cavityOpenFactor < 1) releases the lee smoothly toward 1 so snow settles visibly onto ravine floors.
     */
    public static double leeShare(double y, double leeHeight, double columnHeight, double smoothHeight,
            double cavityOpenFactor, double leeBand) {
        if (!(leeHeight > NO_HEIGHT_THRESHOLD) || !(columnHeight > NO_HEIGHT_THRESHOLD)) {
            return 1.0;
        }
        if (y < columnHeight - OVERHANG_MARGIN) {
            if (y < leeHeight) {
                return 0.0;
            }
            double groundFloor = smoothHeight > NO_HEIGHT_THRESHOLD
                    ? Math.min(columnHeight, smoothHeight) : leeHeight;
            double leeDepth = leeHeight - groundFloor;
            if (!(leeDepth > 0.0) || !(leeBand > 0.0)) {
                return 1.0;
            }
            double effectiveBand = Math.min(leeBand, leeDepth);
            return clamp((y - leeHeight) / effectiveBand);
        }
        double open = Double.isFinite(cavityOpenFactor) ? clamp(cavityOpenFactor) : 1.0;
        double leeDepth = leeHeight - columnHeight;
        double share;
        if (!(leeDepth > 0.0)) {
            share = 1.0;
        } else if (!(leeBand > 0.0)) {
            share = y >= leeHeight ? 1.0 : 0.0;
        } else {
            double effectiveBand = Math.min(leeBand, leeDepth);
            share = clamp((y - leeHeight) / effectiveBand);
        }
        return share + (1.0 - share) * (1.0 - open);
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
     * Linear fade in [0, 1] of the terrain-following vertical velocity over TERRAIN_FOLLOW_HEIGHT blocks above S.
     * Linear decay minimizes the peak vertical velocity gradient over the fade band.
     */
    public static double terrainFollowHeightFade(double y, double smoothHeight) {
        if (!(smoothHeight > NO_HEIGHT_THRESHOLD)) {
            return 0.0;
        }
        return 1.0 - clamp((y - smoothHeight) / TERRAIN_FOLLOW_HEIGHT);
    }

    /**
     * The vertical velocity in blocks per second (positive upward, negative downward) of an active flake at height y,
     * combining its own fall speed, the terrain-following wind component (w . grad S) * windSpeed fading over
     * TERRAIN_FOLLOW_HEIGHT blocks above S, and the downward sinking over a lee column at least as fast as
     * (c + max(0, -terrainSlope)) * windSpeed fading smoothly over the lee's depth above leeHeight.
     */
    public static double verticalVelocity(double y, double leeHeight, double columnHeight, double smoothHeight,
            double terrainSlope, double ownFallSpeed, double windSpeed, double leeFallSpeed) {
        double velocityY = -ownFallSpeed;
        boolean hasSmooth = smoothHeight > NO_HEIGHT_THRESHOLD;
        if (hasSmooth && windSpeed > MINIMUM_WIND_SPEED && Double.isFinite(terrainSlope)) {
            double clampedSlope = Math.max(-1.0, Math.min(1.0, terrainSlope));
            velocityY += clampedSlope * windSpeed * terrainFollowHeightFade(y, smoothHeight);
        }
        if (!(leeHeight > NO_HEIGHT_THRESHOLD) || !(columnHeight > NO_HEIGHT_THRESHOLD)) {
            return velocityY;
        }
        double groundFloor = y < columnHeight - OVERHANG_MARGIN && hasSmooth
                ? Math.min(columnHeight, smoothHeight) : columnHeight;
        double leeDepth = leeHeight - groundFloor;
        if (!(leeDepth > 0.0)) {
            return velocityY;
        }
        double extraSlopeDown = hasSmooth && windSpeed > MINIMUM_WIND_SPEED && Double.isFinite(terrainSlope)
                && terrainSlope < 0.0 ? -Math.max(-1.0, terrainSlope) * windSpeed : 0.0;
        double leeTargetVelocityY = -(leeFallSpeed + extraSlopeDown);
        if (!(leeTargetVelocityY < velocityY)) {
            return velocityY;
        }
        double above = clamp((y - leeHeight) / leeDepth);
        double weight = 1.0 - above * above * (3.0 - 2.0 * above);
        return velocityY + (leeTargetVelocityY - velocityY) * weight;
    }

    /**
     * Places a flake that just left the camera's periodic box onto one of the box's inflow faces with uniform
     * horizontal wind (outside any cavity), matching snowReenterBoxOffset when centerBrakeHeight is NO_HEIGHT.
     */
    public static void reenterBoxOffset(int flake, int frameCounter, double relStepX, double relStepTopY,
            double relStepBottomY, double relStepZ, double boxSide, double[] offsetOut) {
        reenterBoxOffset(flake, frameCounter, relStepX, 0.0, relStepTopY, relStepBottomY, relStepZ, 0.0, 0.0,
                NO_HEIGHT, 0.0, boxSide, offsetOut);
    }

    /**
     * Places a flake that just left the camera's periodic box onto one of the box's inflow faces weighted by the
     * height-profiled horizontal side-face flux (full wind above centerBrakeHeight, easing smoothly down to
     * CAVITY_WIND_FLOOR over brakeLength blocks below it) and the top/bottom vertical fluxes, placing side-face entries
     * by height in proportion to that local flux. Matches snowReenterBoxOffset in snow_flake_update.fsh.
     */
    public static void reenterBoxOffset(int flake, int frameCounter, double windStepX, double cameraStepX,
            double relStepTopY, double relStepBottomY, double windStepZ, double cameraStepZ, double cameraWorldY,
            double centerBrakeHeight, double brakeLength, double boxSide, double[] offsetOut) {
        int reseed = SnowFlakes.hash(flake ^ (frameCounter * 0x9e3779b9));
        double pickUnit = ((SnowFlakes.hash(reseed + 1) >>> 8) + 0.5) * UNIT_SCALE;
        double depthUnit = ((SnowFlakes.hash(reseed + 2) >>> 8) + 0.5) * UNIT_SCALE;
        double firstUnit = ((SnowFlakes.hash(reseed + 3) >>> 8) + 0.5) * UNIT_SCALE;
        double secondUnit = ((SnowFlakes.hash(reseed + 4) >>> 8) + 0.5) * UNIT_SCALE;
        double halfSide = 0.5 * boxSide;
        double yMin = cameraWorldY - halfSide;
        double yMax = cameraWorldY + halfSide;
        double safeBrakeLength = Double.isFinite(brakeLength) && brakeLength > 0.0 ? brakeLength : 0.0;
        boolean profiled = centerBrakeHeight > NO_HEIGHT_THRESHOLD && centerBrakeHeight > yMin;
        double fluxX = profiled
                ? sideFluxArea(yMin, yMax, windStepX, cameraStepX, centerBrakeHeight, safeBrakeLength) / boxSide
                : Math.abs(windStepX - cameraStepX);
        double fluxTop = Math.max(0.0, -relStepTopY);
        double fluxBottom = Math.max(0.0, relStepBottomY);
        double fluxZ = profiled
                ? sideFluxArea(yMin, yMax, windStepZ, cameraStepZ, centerBrakeHeight, safeBrakeLength) / boxSide
                : Math.abs(windStepZ - cameraStepZ);
        double totalFlux = fluxX + fluxTop + fluxBottom + fluxZ;
        if (!(totalFlux > 1.0e-6) || Math.max(Math.max(fluxX, fluxZ), Math.max(fluxTop, fluxBottom)) >= boxSide) {
            offsetOut[0] = (depthUnit - 0.5) * boxSide;
            offsetOut[1] = (firstUnit - 0.5) * boxSide;
            offsetOut[2] = (secondUnit - 0.5) * boxSide;
            return;
        }
        double pick = pickUnit * totalFlux;
        if (pick < fluxX) {
            if (!profiled) {
                double relStepX = windStepX - cameraStepX;
                offsetOut[0] = (relStepX > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * fluxX);
                offsetOut[1] = (firstUnit - 0.5) * boxSide;
            } else {
                double worldY = sampleSideHeight(yMin, yMax, windStepX, cameraStepX, centerBrakeHeight,
                        safeBrakeLength, firstUnit);
                double localStep = windStepX * brakeWindScaleAt(worldY, centerBrakeHeight, safeBrakeLength)
                        - cameraStepX;
                double clampedStep = Math.min(Math.abs(localStep), boxSide);
                offsetOut[0] = (localStep > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * clampedStep);
                offsetOut[1] = Math.max(-halfSide, Math.min(halfSide, worldY - cameraWorldY));
            }
            offsetOut[2] = (secondUnit - 0.5) * boxSide;
        } else if (pick < fluxX + fluxTop) {
            offsetOut[0] = (firstUnit - 0.5) * boxSide;
            offsetOut[1] = halfSide - depthUnit * fluxTop;
            offsetOut[2] = (secondUnit - 0.5) * boxSide;
        } else if (pick < fluxX + fluxTop + fluxBottom) {
            offsetOut[0] = (firstUnit - 0.5) * boxSide;
            offsetOut[1] = -halfSide + depthUnit * fluxBottom;
            offsetOut[2] = (secondUnit - 0.5) * boxSide;
        } else {
            offsetOut[0] = (firstUnit - 0.5) * boxSide;
            if (!profiled) {
                double relStepZ = windStepZ - cameraStepZ;
                offsetOut[1] = (secondUnit - 0.5) * boxSide;
                offsetOut[2] = (relStepZ > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * fluxZ);
            } else {
                double worldY = sampleSideHeight(yMin, yMax, windStepZ, cameraStepZ, centerBrakeHeight,
                        safeBrakeLength, secondUnit);
                double localStep = windStepZ * brakeWindScaleAt(worldY, centerBrakeHeight, safeBrakeLength)
                        - cameraStepZ;
                double clampedStep = Math.min(Math.abs(localStep), boxSide);
                offsetOut[1] = Math.max(-halfSide, Math.min(halfSide, worldY - cameraWorldY));
                offsetOut[2] = (localStep > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * clampedStep);
            }
        }
    }

    private static double sideRampIntegral(double a, double b, double windStep, double cameraStep, double brakeHeight,
            double brakeLength) {
        double span = b - a;
        if (!(span > 0.0) || !(brakeLength > 0.0)) {
            return 0.0;
        }
        double rampStart = brakeHeight - brakeLength;
        double ta = clamp((a - rampStart) / brakeLength);
        double tb = clamp((b - rampStart) / brakeLength);
        double polyA = ta * ta * ta * (1.0 - 0.5 * ta);
        double polyB = tb * tb * tb * (1.0 - 0.5 * tb);
        return (windStep * CAVITY_WIND_FLOOR - cameraStep) * span
                + windStep * (1.0 - CAVITY_WIND_FLOOR) * brakeLength * (polyB - polyA);
    }

    private static double sideSignSplit(double yBot, double yTop, double windStep, double cameraStep,
            double brakeHeight, double brakeLength) {
        if (!(yTop > yBot) || !(brakeLength > 0.0) || !(Math.abs(windStep) > 1.0e-9)) {
            return yTop;
        }
        double targetScale = cameraStep / windStep;
        double botScale = brakeWindScaleAt(yBot, brakeHeight, brakeLength);
        double topScale = brakeWindScaleAt(yTop, brakeHeight, brakeLength);
        if (targetScale > botScale && targetScale < topScale && targetScale > CAVITY_WIND_FLOOR
                && targetScale < 1.0) {
            double share = (targetScale - CAVITY_WIND_FLOOR) / (1.0 - CAVITY_WIND_FLOOR);
            double sineArg = Math.max(-1.0, Math.min(1.0, 1.0 - 2.0 * share));
            double tRoot = 0.5 - Math.sin(Math.asin(sineArg) / 3.0);
            double root = brakeHeight - brakeLength + brakeLength * tRoot;
            return Math.max(yBot, Math.min(yTop, root));
        }
        return yTop;
    }

    private static double sideFluxArea(double yMin, double yMax, double windStep, double cameraStep,
            double brakeHeight, double brakeLength) {
        double yBot = Math.max(yMin, Math.min(yMax, brakeHeight - brakeLength));
        double yTop = Math.max(yMin, Math.min(yMax, brakeHeight));
        double areaFloor = Math.abs(windStep * CAVITY_WIND_FLOOR - cameraStep) * Math.max(0.0, yBot - yMin);
        double split = sideSignSplit(yBot, yTop, windStep, cameraStep, brakeHeight, brakeLength);
        double areaRamp1 = Math.abs(sideRampIntegral(yBot, split, windStep, cameraStep, brakeHeight, brakeLength));
        double areaRamp2 = Math.abs(sideRampIntegral(split, yTop, windStep, cameraStep, brakeHeight, brakeLength));
        double areaAbove = Math.abs(windStep - cameraStep) * Math.max(0.0, yMax - yTop);
        return areaFloor + areaRamp1 + areaRamp2 + areaAbove;
    }

    private static double sampleSideHeight(double yMin, double yMax, double windStep, double cameraStep,
            double brakeHeight, double brakeLength, double unit) {
        double yBot = Math.max(yMin, Math.min(yMax, brakeHeight - brakeLength));
        double yTop = Math.max(yMin, Math.min(yMax, brakeHeight));
        double areaFloor = Math.abs(windStep * CAVITY_WIND_FLOOR - cameraStep) * Math.max(0.0, yBot - yMin);
        double split = sideSignSplit(yBot, yTop, windStep, cameraStep, brakeHeight, brakeLength);
        double areaRamp1 = Math.abs(sideRampIntegral(yBot, split, windStep, cameraStep, brakeHeight, brakeLength));
        double areaRamp2 = Math.abs(sideRampIntegral(split, yTop, windStep, cameraStep, brakeHeight, brakeLength));
        double areaAbove = Math.abs(windStep - cameraStep) * Math.max(0.0, yMax - yTop);
        double totalArea = areaFloor + areaRamp1 + areaRamp2 + areaAbove;
        if (!(totalArea > 0.0)) {
            return yMin + unit * (yMax - yMin);
        }
        double target = unit * totalArea;
        if (target < areaFloor) {
            return yMin + clamp(target / areaFloor) * (yBot - yMin);
        }
        target -= areaFloor;
        if (target < areaRamp1) {
            return solveSideRampHeight(yBot, split, target, windStep, cameraStep, brakeHeight, brakeLength);
        }
        target -= areaRamp1;
        if (target < areaRamp2) {
            return solveSideRampHeight(split, yTop, target, windStep, cameraStep, brakeHeight, brakeLength);
        }
        target -= areaRamp2;
        if (areaAbove > 0.0) {
            return yTop + clamp(target / areaAbove) * (yMax - yTop);
        }
        return yTop;
    }

    private static double solveSideRampHeight(double a, double b, double targetArea, double windStep,
            double cameraStep, double brakeHeight, double brakeLength) {
        double lo = a;
        double hi = b;
        double y = 0.5 * (lo + hi);
        for (int iter = 0; iter < 8; iter++) {
            double fVal = Math.abs(sideRampIntegral(a, y, windStep, cameraStep, brakeHeight, brakeLength))
                    - targetArea;
            if (fVal < 0.0) {
                lo = y;
            } else {
                hi = y;
            }
            double deriv = Math.abs(windStep * brakeWindScaleAt(y, brakeHeight, brakeLength) - cameraStep);
            double next = deriv > 1.0e-7 ? y - fVal / deriv : 0.5 * (lo + hi);
            if (!(next > lo && next < hi)) {
                next = 0.5 * (lo + hi);
            }
            y = next;
        }
        return y;
    }

    /**
     * Runs the exact 3-pass GPU smoothed terrain algorithm (valid-only 2x2 downsample plus separable normalized
     * Gaussian) on the CPU in single precision, matching snow_smooth_build.fsh.
     * @param windowSize the fine window size in blocks (a multiple of SMOOTH_SCALE)
     * @param output receives smoothSize by smoothSize heights (z outer, x inner)
     * @param scratchA working array of at least 2 * smoothSize * smoothSize floats
     * @param scratchB working array of at least 2 * smoothSize * smoothSize floats
     */
    public static void buildSmoothHeights(int windowSize, HeightLookup heights, int windowMinX, int windowMinZ,
            float[] output, float[] scratchA, float[] scratchB) {
        int smoothSize = windowSize / SMOOTH_SCALE;
        float sigmaTexels = (float) SMOOTH_SIGMA_TEXELS;
        float minWeight = (float) SMOOTH_MINIMUM_WEIGHT;
        float noHeight = (float) NO_HEIGHT;

        // Pass 0: valid-only 2x2 downsample into scratchA as (weightedSum, weight):
        for (int tz = 0; tz < smoothSize; tz++) {
            int rowBase = tz * smoothSize;
            int worldBaseZ = windowMinZ + tz * SMOOTH_SCALE;
            for (int tx = 0; tx < smoothSize; tx++) {
                int worldBaseX = windowMinX + tx * SMOOTH_SCALE;
                float sum = 0.0F;
                float count = 0.0F;
                for (int oz = 0; oz < SMOOTH_SCALE; oz++) {
                    for (int ox = 0; ox < SMOOTH_SCALE; ox++) {
                        short height = heights.heightAt(worldBaseX + ox, worldBaseZ + oz);
                        if (height > FarHeights.UNKNOWN) {
                            sum += height;
                            count += 1.0F;
                        }
                    }
                }
                int pair = (rowBase + tx) * 2;
                scratchA[pair] = sum * 0.25F;
                scratchA[pair + 1] = count * 0.25F;
            }
        }

        // Precompute 1D Gaussian weights matching exp(-0.5 * (offset / sigma)^2):
        float[] weights = new float[2 * SMOOTH_KERNEL_RADIUS + 1];
        for (int offset = -SMOOTH_KERNEL_RADIUS; offset <= SMOOTH_KERNEL_RADIUS; offset++) {
            float scaled = offset / sigmaTexels;
            weights[offset + SMOOTH_KERNEL_RADIUS] = (float) Math.exp(-0.5 * scaled * scaled);
        }

        // Pass 1: horizontal Gaussian along x from scratchA into scratchB:
        for (int tz = 0; tz < smoothSize; tz++) {
            int rowBase = tz * smoothSize;
            for (int tx = 0; tx < smoothSize; tx++) {
                float accValue = 0.0F;
                float accWeight = 0.0F;
                for (int offset = -SMOOTH_KERNEL_RADIUS; offset <= SMOOTH_KERNEL_RADIUS; offset++) {
                    int sampleX = tx + offset;
                    if (sampleX >= 0 && sampleX < smoothSize) {
                        float weight = weights[offset + SMOOTH_KERNEL_RADIUS];
                        int pair = (rowBase + sampleX) * 2;
                        accValue += weight * scratchA[pair];
                        accWeight += weight * scratchA[pair + 1];
                    }
                }
                int dest = (rowBase + tx) * 2;
                scratchB[dest] = accValue;
                scratchB[dest + 1] = accWeight;
            }
        }

        // Pass 2: vertical Gaussian along z from scratchB into output, normalized by weight sum:
        for (int tz = 0; tz < smoothSize; tz++) {
            int rowBase = tz * smoothSize;
            for (int tx = 0; tx < smoothSize; tx++) {
                float accValue = 0.0F;
                float accWeight = 0.0F;
                for (int offset = -SMOOTH_KERNEL_RADIUS; offset <= SMOOTH_KERNEL_RADIUS; offset++) {
                    int sampleZ = tz + offset;
                    if (sampleZ >= 0 && sampleZ < smoothSize) {
                        float weight = weights[offset + SMOOTH_KERNEL_RADIUS];
                        int pair = (sampleZ * smoothSize + tx) * 2;
                        accValue += weight * scratchB[pair];
                        accWeight += weight * scratchB[pair + 1];
                    }
                }
                output[rowBase + tx] = accWeight > minWeight ? accValue / accWeight : noHeight;
            }
        }
    }

    /**
     * Independent brute-force 2D normalized Gaussian over the coarse 2x2 blocks around (texelX, texelZ), for verifying
     * buildSmoothHeights in GameTests.
     */
    public static float bruteForceSmoothTexel(int windowSize, HeightLookup heights, int windowMinX, int windowMinZ,
            int texelX, int texelZ) {
        int smoothSize = windowSize / SMOOTH_SCALE;
        double accValue = 0.0;
        double accWeight = 0.0;
        for (int dz = -SMOOTH_KERNEL_RADIUS; dz <= SMOOTH_KERNEL_RADIUS; dz++) {
            int sampleZ = texelZ + dz;
            if (sampleZ < 0 || sampleZ >= smoothSize) {
                continue;
            }
            double wz = Math.exp(-0.5 * (dz / SMOOTH_SIGMA_TEXELS) * (dz / SMOOTH_SIGMA_TEXELS));
            int worldBaseZ = windowMinZ + sampleZ * SMOOTH_SCALE;
            for (int dx = -SMOOTH_KERNEL_RADIUS; dx <= SMOOTH_KERNEL_RADIUS; dx++) {
                int sampleX = texelX + dx;
                if (sampleX < 0 || sampleX >= smoothSize) {
                    continue;
                }
                double wx = Math.exp(-0.5 * (dx / SMOOTH_SIGMA_TEXELS) * (dx / SMOOTH_SIGMA_TEXELS));
                double weight = wx * wz;
                int worldBaseX = windowMinX + sampleX * SMOOTH_SCALE;
                double sum = 0.0;
                double count = 0.0;
                for (int oz = 0; oz < SMOOTH_SCALE; oz++) {
                    for (int ox = 0; ox < SMOOTH_SCALE; ox++) {
                        short height = heights.heightAt(worldBaseX + ox, worldBaseZ + oz);
                        if (height > FarHeights.UNKNOWN) {
                            sum += height;
                            count += 1.0;
                        }
                    }
                }
                accValue += weight * (sum * 0.25);
                accWeight += weight * (count * 0.25);
            }
        }
        return accWeight > SMOOTH_MINIMUM_WEIGHT ? (float) (accValue / accWeight) : (float) NO_HEIGHT;
    }

    /**
     * Valid-only bilinear sample of the coarse smoothed terrain surface S at a horizontal offset in blocks from
     * (smoothMinX, smoothMinZ), ignoring any NO_HEIGHT neighbor texels so sentinel values never bleed in. Matches
     * snowSmoothHeightAt in snow_lee.glsl and snow_lee_build.fsh.
     */
    public static float sampleSmoothBilinear(float[] smoothMap, int smoothSize, double fromSmoothMinX,
            double fromSmoothMinZ) {
        double windowSpan = smoothSize * (double) SMOOTH_SCALE;
        if (fromSmoothMinX < 0.0 || fromSmoothMinX >= windowSpan
                || fromSmoothMinZ < 0.0 || fromSmoothMinZ >= windowSpan) {
            return (float) NO_HEIGHT;
        }
        double fx = fromSmoothMinX / SMOOTH_SCALE - 0.5;
        double fz = fromSmoothMinZ / SMOOTH_SCALE - 0.5;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = clamp(fx - x0);
        double tz = clamp(fz - z0);
        int cx0 = Math.max(0, Math.min(smoothSize - 1, x0));
        int cz0 = Math.max(0, Math.min(smoothSize - 1, z0));
        int cx1 = Math.max(0, Math.min(smoothSize - 1, x0 + 1));
        int cz1 = Math.max(0, Math.min(smoothSize - 1, z0 + 1));
        float s00 = smoothMap[cz0 * smoothSize + cx0];
        float s10 = smoothMap[cz0 * smoothSize + cx1];
        float s01 = smoothMap[cz1 * smoothSize + cx0];
        float s11 = smoothMap[cz1 * smoothSize + cx1];
        double w00 = (1.0 - tx) * (1.0 - tz);
        double w10 = tx * (1.0 - tz);
        double w01 = (1.0 - tx) * tz;
        double w11 = tx * tz;
        double sum = 0.0;
        double weightSum = 0.0;
        if (s00 > NO_HEIGHT_THRESHOLD) {
            sum += s00 * w00;
            weightSum += w00;
        }
        if (s10 > NO_HEIGHT_THRESHOLD) {
            sum += s10 * w10;
            weightSum += w10;
        }
        if (s01 > NO_HEIGHT_THRESHOLD) {
            sum += s01 * w01;
            weightSum += w01;
        }
        if (s11 > NO_HEIGHT_THRESHOLD) {
            sum += s11 * w11;
            weightSum += w11;
        }
        if (!(weightSum > 0.0)) {
            return (float) NO_HEIGHT;
        }
        return (float) (sum / weightSum);
    }

    /**
     * Runs the exact GPU dual-channel resample, doubling scan, across-wind minimum, relief combination
     * h = min(h_raw, S + e_rel), and terrain-following slope clamp(w . grad S, -1, 1) on the CPU in single precision,
     * matching snow_lee_build.fsh.
     */
    public static void scan(int mapSize, int[] strides, HeightLookup heights, float[] smoothMap, int smoothSize,
            int smoothMinX, int smoothMinZ, int anchorX, int anchorZ, int windowMinX, int windowMinZ, int windowSize,
            double dirX, double dirZ, double slope, boolean applyAcrossMin, float[] leeOutput, float[] slopeOutput,
            float[] rawScratchA, float[] rawScratchB, float[] relScratchA, float[] relScratchB) {
        float halfSize = mapSize * 0.5F;
        float floatDirX = (float) dirX;
        float floatDirZ = (float) dirZ;
        float floatSlope = (float) slope;
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        float sampleOffset = (float) SAMPLE_OFFSET;
        float gradientStep = (float) SMOOTH_GRADIENT_STEP;
        float anchorFromSmoothX = anchorX - smoothMinX;
        float anchorFromSmoothZ = anchorZ - smoothMinZ;

        float[] rawSource = rawScratchA;
        float[] rawTarget = rawScratchB;
        float[] relSource = relScratchA;
        float[] relTarget = relScratchB;

        // Pass 0: two-tap along-wind resample at t = 1 for both raw height H and relief H - S:
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
                if (height > threshold) {
                    rawSource[row + ix] = height - floatSlope;
                    float smoothUpwind = sampleSmoothWind(smoothMap, smoothSize, anchorFromSmoothX, anchorFromSmoothZ,
                            upwindU, s, floatDirX, floatDirZ);
                    relSource[row + ix] = smoothUpwind > threshold ? (height - smoothUpwind) - floatSlope : noHeight;
                } else {
                    rawSource[row + ix] = noHeight;
                    relSource[row + ix] = noHeight;
                }
            }
        }

        // Passes 1 to strides.length: doubling max-plus scan on both channels; final pass combines with S and grad S:
        for (int pass = 0; pass < strides.length; pass++) {
            int stride = strides[pass];
            boolean finalPass = pass == strides.length - 1;
            float stepDrop = floatSlope * stride;
            for (int iy = 0; iy < mapSize; iy++) {
                int row = iy * mapSize;
                int minusRow = Math.max(iy - 1, 0) * mapSize;
                int plusRow = Math.min(iy + 1, mapSize - 1) * mapSize;
                float s = iy - halfSize + 0.5F;
                for (int ix = 0; ix < mapSize; ix++) {
                    if (!finalPass) {
                        rawTarget[row + ix] = scanTexel(rawSource, row, ix, stride, stepDrop, threshold);
                        relTarget[row + ix] = scanTexel(relSource, row, ix, stride, stepDrop, threshold);
                        continue;
                    }
                    float rawValue = scanTexel(rawSource, row, ix, stride, stepDrop, threshold);
                    float relValue = scanTexel(relSource, row, ix, stride, stepDrop, threshold);
                    if (applyAcrossMin) {
                        float rawMinus = scanTexel(rawSource, minusRow, ix, stride, stepDrop, threshold);
                        float rawPlus = scanTexel(rawSource, plusRow, ix, stride, stepDrop, threshold);
                        rawValue = Math.min(rawValue, Math.min(rawMinus, rawPlus));
                        float relMinus = scanTexel(relSource, minusRow, ix, stride, stepDrop, threshold);
                        float relPlus = scanTexel(relSource, plusRow, ix, stride, stepDrop, threshold);
                        relValue = Math.min(relValue, Math.min(relMinus, relPlus));
                    }
                    float u = ix - halfSize + 0.5F;
                    float smoothHere = sampleSmoothWind(smoothMap, smoothSize, anchorFromSmoothX, anchorFromSmoothZ,
                            u, s, floatDirX, floatDirZ);
                    float leeHeight;
                    if (!(rawValue > threshold)) {
                        leeHeight = noHeight;
                    } else if (relValue > threshold && smoothHere > threshold) {
                        leeHeight = Math.min(rawValue, smoothHere + relValue);
                    } else {
                        leeHeight = rawValue;
                    }
                    leeOutput[row + ix] = leeHeight;

                    float smoothForward = sampleSmoothWind(smoothMap, smoothSize, anchorFromSmoothX, anchorFromSmoothZ,
                            u + gradientStep, s, floatDirX, floatDirZ);
                    float smoothBackward = sampleSmoothWind(smoothMap, smoothSize, anchorFromSmoothX, anchorFromSmoothZ,
                            u - gradientStep, s, floatDirX, floatDirZ);
                    float terrainSlope = 0.0F;
                    if (smoothForward > threshold && smoothBackward > threshold) {
                        terrainSlope = Math.max(-1.0F, Math.min(1.0F,
                                (smoothForward - smoothBackward) / (2.0F * gradientStep)));
                    }
                    slopeOutput[row + ix] = terrainSlope;
                }
            }
            if (!finalPass) {
                float[] swapRaw = rawSource;
                rawSource = rawTarget;
                rawTarget = swapRaw;
                float[] swapRel = relSource;
                relSource = relTarget;
                relTarget = swapRel;
            }
        }
    }

    /**
     * Independent brute-force evaluation of one texel (texelX, texelY) of the combined relief lee map over
     * 1 <= t <= reach, for verifying scan in GameTests.
     */
    public static float bruteForceLeeTexel(int mapSize, int reach, HeightLookup heights, float[] smoothMap,
            int smoothSize, int smoothMinX, int smoothMinZ, int anchorX, int anchorZ, int windowMinX, int windowMinZ,
            int windowSize, double dirX, double dirZ, double slope, boolean applyAcrossMin, int texelX, int texelY) {
        float rawValue = bruteForceRawRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ,
                windowSize, dirX, dirZ, slope, texelX, texelY);
        float relValue = bruteForceRelRay(mapSize, reach, heights, smoothMap, smoothSize, smoothMinX, smoothMinZ,
                anchorX, anchorZ, windowMinX, windowMinZ, windowSize, dirX, dirZ, slope, texelX, texelY);
        if (applyAcrossMin) {
            int minusY = Math.max(texelY - 1, 0);
            int plusY = Math.min(texelY + 1, mapSize - 1);
            float rawMinus = bruteForceRawRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ,
                    windowSize, dirX, dirZ, slope, texelX, minusY);
            float rawPlus = bruteForceRawRay(mapSize, reach, heights, anchorX, anchorZ, windowMinX, windowMinZ,
                    windowSize, dirX, dirZ, slope, texelX, plusY);
            rawValue = Math.min(rawValue, Math.min(rawMinus, rawPlus));
            float relMinus = bruteForceRelRay(mapSize, reach, heights, smoothMap, smoothSize, smoothMinX, smoothMinZ,
                    anchorX, anchorZ, windowMinX, windowMinZ, windowSize, dirX, dirZ, slope, texelX, minusY);
            float relPlus = bruteForceRelRay(mapSize, reach, heights, smoothMap, smoothSize, smoothMinX, smoothMinZ,
                    anchorX, anchorZ, windowMinX, windowMinZ, windowSize, dirX, dirZ, slope, texelX, plusY);
            relValue = Math.min(relValue, Math.min(relMinus, relPlus));
        }
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        if (!(rawValue > threshold)) {
            return (float) NO_HEIGHT;
        }
        float halfSize = mapSize * 0.5F;
        float u = texelX - halfSize + 0.5F;
        float s = texelY - halfSize + 0.5F;
        float smoothHere = sampleSmoothWind(smoothMap, smoothSize, anchorX - smoothMinX, anchorZ - smoothMinZ,
                u, s, (float) dirX, (float) dirZ);
        if (relValue > threshold && smoothHere > threshold) {
            return Math.min(rawValue, smoothHere + relValue);
        }
        return rawValue;
    }

    /**
     * Bilinearly samples a wind-aligned lee map channel at a horizontal offset from the map's anchor, matching
     * snowLeeSampleAt in snow_lee.glsl.
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

    private static float bruteForceRawRay(int mapSize, int reach, HeightLookup heights, int anchorX, int anchorZ,
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

    private static float bruteForceRelRay(int mapSize, int reach, HeightLookup heights, float[] smoothMap,
            int smoothSize, int smoothMinX, int smoothMinZ, int anchorX, int anchorZ, int windowMinX, int windowMinZ,
            int windowSize, double dirX, double dirZ, double slope, int texelX, int texelY) {
        float halfSize = mapSize * 0.5F;
        float floatDirX = (float) dirX;
        float floatDirZ = (float) dirZ;
        float floatSlope = (float) slope;
        float noHeight = (float) NO_HEIGHT;
        float threshold = (float) NO_HEIGHT_THRESHOLD;
        float sampleOffset = (float) SAMPLE_OFFSET;
        float anchorFromSmoothX = anchorX - smoothMinX;
        float anchorFromSmoothZ = anchorZ - smoothMinZ;
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
                float smoothAt = sampleSmoothWind(smoothMap, smoothSize, anchorFromSmoothX, anchorFromSmoothZ,
                        upwindU, s, floatDirX, floatDirZ);
                if (smoothAt > threshold) {
                    best = Math.max(best, (height - smoothAt) - floatSlope * step);
                }
            }
        }
        return best;
    }

    private static float sampleSmoothWind(float[] smoothMap, int smoothSize, float anchorFromSmoothX,
            float anchorFromSmoothZ, float u, float s, float dirX, float dirZ) {
        float dx = u * dirX - s * dirZ;
        float dz = u * dirZ + s * dirX;
        return sampleSmoothBilinear(smoothMap, smoothSize, anchorFromSmoothX + dx, anchorFromSmoothZ + dz);
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
