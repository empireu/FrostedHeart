// Shared snowfall lee map lookup, smoothed terrain lookup, ravine cavity braking, overhang-aware fade weight and
// terrain-following vertical velocity, as SnowLee defines them in Java.
// FHShaders prepends snow_flakes.glsl before it, and prepends it to snow_flake_update.fsh and snow_flakes_draw.glsl.
uniform int hasLeeMap;
uniform sampler2D leeMap;
uniform isampler2D noLeavesHeights;
uniform sampler2D smoothHeights;
uniform int hasCavityMap;
uniform sampler2D cavityMap;
uniform ivec2 cavityMin;
uniform float cavityBrakeLength;
// The blocks by which a flake's flutter circle grows below a braking ceiling:
uniform float cavitySwirl;
// The share of the wind that braking removes which pulls flakes down below a braking ceiling:
uniform float cavityDowndraft;
// The camera's horizontal offset from the published lee map's anchor block and from the smoothed terrain's minimum
// corner, both computed in double precision on the CPU:
uniform vec2 cameraFromLeeAnchor;
uniform vec2 cameraFromSmoothMin;
uniform vec2 leeWindDirection;
// The downward speed along the lee surface (slope * windSpeed) and the vertical fade band above it, in blocks:
uniform float leeFallSpeed;
uniform float leeBand;
// The lowest world column of the current FarHeights window, so lookups never wrap outside the window:
uniform ivec2 farHeightsMin;

const int SNOW_LEE_MAP_SIZE = 1024;
const float SNOW_LEE_HALF_SIZE = 512.0;
const int SNOW_LEE_FAR_HEIGHTS_SIZE = 1024;
const int SNOW_LEE_FAR_HEIGHTS_MASK = 1023;
const int SNOW_LEE_UNKNOWN_HEIGHT = -32768;
const float SNOW_LEE_NO_HEIGHT = -32768.0;
const float SNOW_LEE_NO_HEIGHT_THRESHOLD = -30000.0;
const int SNOW_SMOOTH_SCALE = 2;
const int SNOW_SMOOTH_SIZE = 512;
const float SNOW_TERRAIN_FOLLOW_HEIGHT = 32.0;
const float SNOW_OVERHANG_MARGIN = 1.0;
const float SNOW_LEE_MINIMUM_WIND_SPEED = 0.01;
const int SNOW_CAVITY_MAP_SIZE = 512;
const float SNOW_CAVITY_WIND_FLOOR = 0.01;
const float SNOW_CAVITY_SLOPE_DISTANCE = 2.0;
const float SNOW_CAVITY_SLOPE_LIMIT = 1.0;

// The no-leaves terrain height of the column containing a point given as an offset from the camera:
float snowLeeColumnHeight(vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction) {
    vec2 localXZ = cameraFraction.xz + fromCamera.xz;
    ivec2 column = cameraBlock.xz + ivec2(floor(localXZ));
    if (any(lessThan(column, farHeightsMin))
            || any(greaterThanEqual(column, farHeightsMin + ivec2(SNOW_LEE_FAR_HEIGHTS_SIZE)))) {
        return SNOW_LEE_NO_HEIGHT;
    }
    int height = texelFetch(noLeavesHeights, column & ivec2(SNOW_LEE_FAR_HEIGHTS_MASK), 0).r;
    if (height <= SNOW_LEE_UNKNOWN_HEIGHT) {
        return SNOW_LEE_NO_HEIGHT;
    }
    return float(height);
}

// The published flat cavity enclosure surface (x) and the lower lip of its walls (y) at a point given from the camera:
vec2 snowCavitySampleAt(vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction) {
    if (hasCavityMap == 0) {
        return vec2(SNOW_LEE_NO_HEIGHT);
    }
    vec2 localXZ = cameraFraction.xz + fromCamera.xz;
    ivec2 column = cameraBlock.xz + ivec2(floor(localXZ));
    ivec2 local = column - cavityMin;
    if (local.x < 0 || local.x >= SNOW_CAVITY_MAP_SIZE || local.y < 0 || local.y >= SNOW_CAVITY_MAP_SIZE) {
        return vec2(SNOW_LEE_NO_HEIGHT);
    }
    return texelFetch(cavityMap, local, 0).rg;
}

// The braking ceiling over an open-sky cavity column, as SnowLee.cavityBrakeHeight:
float snowCavityBrakeHeight(float cavityHeight, float cavityLip, float columnHeight) {
    if (!(cavityHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD) || !(columnHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
        return SNOW_LEE_NO_HEIGHT;
    }
    if (!(cavityHeight > columnHeight)) {
        return SNOW_LEE_NO_HEIGHT;
    }
    return cavityLip > SNOW_LEE_NO_HEIGHT_THRESHOLD ? max(cavityHeight, cavityLip) : cavityHeight;
}

// The braking ceiling of the column containing a point given from the camera:
float snowCavityBrakeHeightAt(vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction) {
    vec2 cavity = snowCavitySampleAt(fromCamera, cameraBlock, cameraFraction);
    return snowCavityBrakeHeight(cavity.x, cavity.y, snowLeeColumnHeight(fromCamera, cameraBlock, cameraFraction));
}

// The flow ceiling of the column containing a point given from the camera, as SnowLee.cavityFlowCeiling:
float snowCavityFlowCeilingAt(vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction) {
    float columnHeight = snowLeeColumnHeight(fromCamera, cameraBlock, cameraFraction);
    vec2 cavity = snowCavitySampleAt(fromCamera, cameraBlock, cameraFraction);
    float brakeHeight = snowCavityBrakeHeight(cavity.x, cavity.y, columnHeight);
    return brakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD ? brakeHeight : columnHeight;
}

// The extra vertical velocity of a braked flake where the flow ceiling upwind is lower, as SnowLee.cavitySinkVelocity:
float snowCavitySinkVelocityFor(float brakeHeight, float upwindCeiling, float windSpeed, float windScale) {
    if (!(brakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD) || !(upwindCeiling > SNOW_LEE_NO_HEIGHT_THRESHOLD)
            || !(windSpeed > SNOW_LEE_MINIMUM_WIND_SPEED)) {
        return 0.0;
    }
    float rise = clamp((brakeHeight - upwindCeiling) / SNOW_CAVITY_SLOPE_DISTANCE, 0.0, SNOW_CAVITY_SLOPE_LIMIT);
    return -windSpeed * rise * (1.0 - clamp(windScale, 0.0, 1.0));
}

// The extra vertical velocity of a braked flake at a point given from the camera under the wind before braking:
float snowCavitySinkVelocity(vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction, float brakeHeight,
        float windScale, vec2 wind) {
    float windSpeed = length(wind);
    if (!(brakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD) || !(windScale < 1.0)
            || !(windSpeed > SNOW_LEE_MINIMUM_WIND_SPEED)) {
        return 0.0;
    }
    vec2 upwind = wind * (-SNOW_CAVITY_SLOPE_DISTANCE / windSpeed);
    float upwindCeiling = snowCavityFlowCeilingAt(fromCamera + vec3(upwind.x, 0.0, upwind.y), cameraBlock,
            cameraFraction);
    return snowCavitySinkVelocityFor(brakeHeight, upwindCeiling, windSpeed, windScale);
}

// The extra vertical velocity of a braked flake that the downdraft pulls down, as SnowLee.cavityDowndraftVelocity:
float snowCavityDowndraftVelocity(float windSpeed, float windScale) {
    if (!(windSpeed > SNOW_LEE_MINIMUM_WIND_SPEED) || !(cavityDowndraft > 0.0)) {
        return 0.0;
    }
    return -windSpeed * min(1.0, cavityDowndraft) * (1.0 - clamp(windScale, 0.0, 1.0));
}

// The radius of a flake's flutter circle, which grows by cavitySwirl below a braking ceiling, as
// SnowLee.cavityCircleRadius:
float snowCavityCircleRadius(float flutterRadius, float cavityOpen, float radiusShare) {
    return (flutterRadius + max(0.0, cavitySwirl) * (1.0 - clamp(cavityOpen, 0.0, 1.0))) * radiusShare;
}

// Cavity openness factor in [0, 1] at worldY under a precomputed braking ceiling brakeHeight and transition depth
// cavityBrakeLength, as SnowLee.brakeOpenFactorAt:
float snowBrakeOpenFactorAt(float worldY, float brakeHeight) {
    if (!(brakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
        return 1.0;
    }
    float depth = brakeHeight - worldY;
    if (!(depth > 0.0)) {
        return 1.0;
    }
    if (!(cavityBrakeLength > 0.0)) {
        return 0.0;
    }
    float share = clamp(depth / cavityBrakeLength, 0.0, 1.0);
    return 1.0 - share * share * (3.0 - 2.0 * share);
}

// Depth-only cavity openness factor in [0, 1] for falling flakes, as SnowLee.cavityOpenFactor:
float snowCavityOpenFactor(float worldY, float cavityHeight, float cavityLip, float columnHeight) {
    float brakeHeight = snowCavityBrakeHeight(cavityHeight, cavityLip, columnHeight);
    return snowBrakeOpenFactorAt(worldY, brakeHeight);
}

// Horizontal flake wind speed multiplier in [SNOW_CAVITY_WIND_FLOOR, 1.0], as SnowLee.cavityWindScale:
float snowCavityWindScale(float cavityOpen) {
    return SNOW_CAVITY_WIND_FLOOR + (1.0 - SNOW_CAVITY_WIND_FLOOR) * clamp(cavityOpen, 0.0, 1.0);
}

// Horizontal wind multiplier in [SNOW_CAVITY_WIND_FLOOR, 1.0] at worldY under a precomputed braking ceiling
// brakeHeight and transition depth cavityBrakeLength, as SnowLee.brakeWindScaleAt:
float snowBrakeWindScaleAt(float worldY, float brakeHeight) {
    return snowCavityWindScale(snowBrakeOpenFactorAt(worldY, brakeHeight));
}

// Valid-only bilinear sample of the smoothed macro terrain surface S at a point given from the camera, as
// SnowLee.sampleSmoothBilinear:
float snowSmoothHeightAt(vec3 fromCamera) {
    vec2 fromSmoothMin = cameraFromSmoothMin + fromCamera.xz;
    float windowSpan = float(SNOW_SMOOTH_SIZE * SNOW_SMOOTH_SCALE);
    if (fromSmoothMin.x < 0.0 || fromSmoothMin.x >= windowSpan
            || fromSmoothMin.y < 0.0 || fromSmoothMin.y >= windowSpan) {
        return SNOW_LEE_NO_HEIGHT;
    }
    vec2 f = fromSmoothMin / float(SNOW_SMOOTH_SCALE) - vec2(0.5);
    ivec2 base = ivec2(floor(f));
    vec2 frac = clamp(f - vec2(base), 0.0, 1.0);
    int cx0 = clamp(base.x, 0, SNOW_SMOOTH_SIZE - 1);
    int cz0 = clamp(base.y, 0, SNOW_SMOOTH_SIZE - 1);
    int cx1 = clamp(base.x + 1, 0, SNOW_SMOOTH_SIZE - 1);
    int cz1 = clamp(base.y + 1, 0, SNOW_SMOOTH_SIZE - 1);
    float s00 = texelFetch(smoothHeights, ivec2(cx0, cz0), 0).r;
    float s10 = texelFetch(smoothHeights, ivec2(cx1, cz0), 0).r;
    float s01 = texelFetch(smoothHeights, ivec2(cx0, cz1), 0).r;
    float s11 = texelFetch(smoothHeights, ivec2(cx1, cz1), 0).r;
    float w00 = (1.0 - frac.x) * (1.0 - frac.y);
    float w10 = frac.x * (1.0 - frac.y);
    float w01 = (1.0 - frac.x) * frac.y;
    float w11 = frac.x * frac.y;
    float sum = 0.0;
    float weightSum = 0.0;
    if (s00 > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
        sum += s00 * w00;
        weightSum += w00;
    }
    if (s10 > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
        sum += s10 * w10;
        weightSum += w10;
    }
    if (s01 > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
        sum += s01 * w01;
        weightSum += w01;
    }
    if (s11 > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
        sum += s11 * w11;
        weightSum += w11;
    }
    return weightSum > 0.0 ? sum / weightSum : SNOW_LEE_NO_HEIGHT;
}

// The published lee height (x) and terrain-following slope w . grad S (y) at a point given from the camera, sampled
// bilinearly in the wind frame:
vec2 snowLeeSampleAt(vec3 fromCamera) {
    vec2 offset = cameraFromLeeAnchor + fromCamera.xz;
    float u = offset.x * leeWindDirection.x + offset.y * leeWindDirection.y;
    float s = -offset.x * leeWindDirection.y + offset.y * leeWindDirection.x;
    if (abs(u) >= SNOW_LEE_HALF_SIZE - 0.5 || abs(s) >= SNOW_LEE_HALF_SIZE - 0.5) {
        return vec2(SNOW_LEE_NO_HEIGHT, 0.0);
    }
    vec2 uv = (vec2(u, s) + vec2(SNOW_LEE_HALF_SIZE)) / float(SNOW_LEE_MAP_SIZE);
    return textureLod(leeMap, uv, 0.0).rg;
}

// The overhang-aware lee visibility share from 0 to 1, releasing the lee below a ravine's braking ceiling on open-sky
// columns while keeping overhangs dry, as SnowLee.leeShare:
float snowLeeShare(float worldY, float leeHeight, float columnHeight, float smoothHeight, float cavityOpen) {
    if (!(leeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD) || !(columnHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
        return 1.0;
    }
    if (worldY < columnHeight - SNOW_OVERHANG_MARGIN) {
        if (worldY < leeHeight) {
            return 0.0;
        }
        float groundFloor = smoothHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD
                ? min(columnHeight, smoothHeight) : leeHeight;
        float leeDepth = leeHeight - groundFloor;
        if (!(leeDepth > 0.0) || !(leeBand > 0.0)) {
            return 1.0;
        }
        float effectiveBand = min(leeBand, leeDepth);
        return clamp((worldY - leeHeight) / effectiveBand, 0.0, 1.0);
    }
    float leeDepth = leeHeight - columnHeight;
    float share;
    if (!(leeDepth > 0.0)) {
        share = 1.0;
    } else if (!(leeBand > 0.0)) {
        share = worldY >= leeHeight ? 1.0 : 0.0;
    } else {
        float effectiveBand = min(leeBand, leeDepth);
        share = clamp((worldY - leeHeight) / effectiveBand, 0.0, 1.0);
    }
    return share + (1.0 - share) * (1.0 - clamp(cavityOpen, 0.0, 1.0));
}

// The per-flake opacity weight for pre-fetched heights and cavity openness, as SnowLee.leeWeight:
float snowLeeWeightAt(uint flake, float worldY, float leeHeight, float columnHeight, float smoothHeight,
        float cavityOpen) {
    float share = snowLeeShare(worldY, leeHeight, columnHeight, smoothHeight, cavityOpen);
    return clamp((share - snowFlakeLeeThreshold(flake)) / SNOW_SHELTER_SOFTNESS, 0.0, 1.0);
}

// The per-flake opacity weight at a point given as an offset from the camera:
float snowLeeWeight(uint flake, vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction) {
    if (hasLeeMap == 0) {
        return 1.0;
    }
    float leeHeight = snowLeeSampleAt(fromCamera).x;
    float columnHeight = snowLeeColumnHeight(fromCamera, cameraBlock, cameraFraction);
    float smoothHeight = snowSmoothHeightAt(fromCamera);
    float worldY = float(cameraBlock.y) + cameraFraction.y + fromCamera.y;
    vec2 cavity = snowCavitySampleAt(fromCamera, cameraBlock, cameraFraction);
    float cavityOpen = snowCavityOpenFactor(worldY, cavity.x, cavity.y, columnHeight);
    return snowLeeWeightAt(flake, worldY, leeHeight, columnHeight, smoothHeight, cavityOpen);
}

// The vertical velocity in blocks per second (positive upward) for pre-fetched heights, terrain slope and braked wind
// speeds, as SnowLee.verticalVelocity:
float snowLeeVerticalVelocityAt(float worldY, float leeHeight, float columnHeight, float smoothHeight,
        float terrainSlope, float ownFallSpeed, float localWindSpeed, float localLeeFallSpeed) {
    float velocityY = -ownFallSpeed;
    bool hasSmooth = smoothHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD;
    if (hasSmooth && localWindSpeed > SNOW_LEE_MINIMUM_WIND_SPEED) {
        float heightFade = 1.0 - clamp((worldY - smoothHeight) / SNOW_TERRAIN_FOLLOW_HEIGHT, 0.0, 1.0);
        velocityY += clamp(terrainSlope, -1.0, 1.0) * localWindSpeed * heightFade;
    }
    if (!(leeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD) || !(columnHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
        return velocityY;
    }
    float groundFloor = worldY < columnHeight - SNOW_OVERHANG_MARGIN && hasSmooth
            ? min(columnHeight, smoothHeight) : columnHeight;
    float leeDepth = leeHeight - groundFloor;
    if (!(leeDepth > 0.0)) {
        return velocityY;
    }
    float extraSlopeDown = hasSmooth && localWindSpeed > SNOW_LEE_MINIMUM_WIND_SPEED && terrainSlope < 0.0
            ? -max(-1.0, terrainSlope) * localWindSpeed : 0.0;
    float leeTargetVelocityY = -(localLeeFallSpeed + extraSlopeDown);
    if (!(leeTargetVelocityY < velocityY)) {
        return velocityY;
    }
    float above = clamp((worldY - leeHeight) / leeDepth, 0.0, 1.0);
    float weight = 1.0 - above * above * (3.0 - 2.0 * above);
    return velocityY + (leeTargetVelocityY - velocityY) * weight;
}
