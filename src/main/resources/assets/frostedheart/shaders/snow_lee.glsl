// Shared snowfall lee map lookup, fade weight and sinking fall speed, as SnowLee defines them in Java.
// FHShaders prepends snow_flakes.glsl before it, and prepends it to snow_flake_update.fsh and snow_flakes_draw.glsl.
uniform int hasLeeMap;
uniform sampler2D leeMap;
uniform isampler2D noLeavesHeights;
// The camera's horizontal offset from the published lee map's anchor block, computed in double precision on the CPU:
uniform vec2 cameraFromLeeAnchor;
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

// The published lee surface height at a point given as an offset from the camera, sampled bilinearly in the wind frame:
float snowLeeHeightAt(vec3 fromCamera) {
    vec2 offset = cameraFromLeeAnchor + fromCamera.xz;
    float u = offset.x * leeWindDirection.x + offset.y * leeWindDirection.y;
    float s = -offset.x * leeWindDirection.y + offset.y * leeWindDirection.x;
    if (abs(u) >= SNOW_LEE_HALF_SIZE - 0.5 || abs(s) >= SNOW_LEE_HALF_SIZE - 0.5) {
        return SNOW_LEE_NO_HEIGHT;
    }
    vec2 uv = (vec2(u, s) + vec2(SNOW_LEE_HALF_SIZE)) / float(SNOW_LEE_MAP_SIZE);
    return textureLod(leeMap, uv, 0.0).r;
}

// The lee visibility share from 0 to 1, as SnowLee.leeShare:
float snowLeeShare(float worldY, float leeHeight, float columnHeight) {
    if (!(leeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD) || !(columnHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
        return 1.0;
    }
    float leeDepth = leeHeight - columnHeight;
    if (!(leeDepth > 0.0)) {
        return 1.0;
    }
    if (!(leeBand > 0.0)) {
        return worldY >= leeHeight ? 1.0 : 0.0;
    }
    float effectiveBand = min(leeBand, leeDepth);
    return clamp((worldY - leeHeight) / effectiveBand, 0.0, 1.0);
}

// The per-flake opacity weight for pre-fetched lee and column heights, as SnowLee.leeWeight:
float snowLeeWeightAt(uint flake, float worldY, float leeHeight, float columnHeight) {
    float share = snowLeeShare(worldY, leeHeight, columnHeight);
    return clamp((share - snowFlakeLeeThreshold(flake)) / SNOW_SHELTER_SOFTNESS, 0.0, 1.0);
}

// The per-flake opacity weight at a point given as an offset from the camera:
float snowLeeWeight(uint flake, vec3 fromCamera, ivec3 cameraBlock, vec3 cameraFraction) {
    if (hasLeeMap == 0) {
        return 1.0;
    }
    float leeHeight = snowLeeHeightAt(fromCamera);
    float columnHeight = snowLeeColumnHeight(fromCamera, cameraBlock, cameraFraction);
    float worldY = float(cameraBlock.y) + cameraFraction.y + fromCamera.y;
    return snowLeeWeightAt(flake, worldY, leeHeight, columnHeight);
}

// The downward speed in blocks per second for pre-fetched lee and column heights, as SnowLee.sinkingFallSpeed:
float snowLeeFallSpeedAt(float worldY, float leeHeight, float columnHeight, float ownFallSpeed) {
    if (!(leeFallSpeed > ownFallSpeed) || !(leeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)
            || !(columnHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
        return ownFallSpeed;
    }
    float leeDepth = leeHeight - columnHeight;
    if (!(leeDepth > 0.0)) {
        return ownFallSpeed;
    }
    float above = clamp((worldY - leeHeight) / leeDepth, 0.0, 1.0);
    float weight = 1.0 - above * above * (3.0 - 2.0 * above);
    return ownFallSpeed + (leeFallSpeed - ownFallSpeed) * weight;
}

// The downward speed in blocks per second at a point given as an offset from the camera:
float snowLeeFallSpeed(vec3 fromCamera, float ownFallSpeed, ivec3 cameraBlock, vec3 cameraFraction) {
    if (hasLeeMap == 0 || !(leeFallSpeed > ownFallSpeed)) {
        return ownFallSpeed;
    }
    float leeHeight = snowLeeHeightAt(fromCamera);
    float columnHeight = snowLeeColumnHeight(fromCamera, cameraBlock, cameraFraction);
    float worldY = float(cameraBlock.y) + cameraFraction.y + fromCamera.y;
    return snowLeeFallSpeedAt(worldY, leeHeight, columnHeight, ownFallSpeed);
}
