// FHShaders prepends the version line, weather_fog.glsl, snow_walk.glsl and weather_fog_upsample.fsh.
// Fills every pixel of the frame in from the four low-resolution samples that weather_fog.fsh walked, and walks the
// pixels that those samples do not support at full resolution where the fallback allows it. A pixel takes the
// analytic optical depth of its own ray times the ratio of the walked depth of the samples to the analytic depth of
// theirs, so open air is exact at full resolution and the fog of a shelter edge stays where the walk put it.
// The scene point of a pixel is rebuilt from the depth of the main target, as weather_fog.fsh does it, and the fog
// blends over the pixel by the opacity along the segment from the eye to that point.
// The fog color is the horizon color toward the pixel, mixed toward the snow fog color by the snowfall intensity.
// The depth of the main target and its size in texels:
uniform sampler2D sceneDepth;
uniform vec2 screenSize;
// The sky's displayed horizon color per azimuth, 64 texels around from -PI:
uniform sampler2D horizonColors;
// The snow fog color, and the share of it that the horizon color blends toward, which is the snowfall intensity, all as
// display values:
uniform vec3 snowColor;
uniform float snowShare;
// The view projection translated by the eye offset and inverted, so a position it gives is the offset from the eye:
uniform mat4 inverseViewProjection;
// What the debug view shows instead of the fog, 0 for the fog itself, 1 for the share of the field, 2 for the counts of
// the walk, 3 for the transmittance and 4 for the pixels that fall back:
uniform int debugMode;
// Which parts of the walk run, as snowWalkOpticalDepth reads them:
uniform int partMask;
// The low-resolution target of the ratios and the distances, its size, and the divisor of the resolution:
uniform sampler2D lowResolution;
uniform ivec2 lowSize;
uniform int divisor;
// Which part of the fog this draw covers, 0 for the pixels that the samples support and 1 for the ones that fall back,
// and whether the fallback is allowed:
uniform int compositePart;
uniform int fallbackAllowed;
out vec4 fragColor;

const float PI = 3.14159265358979;
// The opacity below which the fog leaves a pixel as it is:
const float LEAST_OPACITY = 1.0 / 512.0;
// The opacity of the debug views, which draw over every pixel that is not sky:
const float DEBUG_FIELD_OPACITY = 0.75;
const float DEBUG_CELLS_OPACITY = 0.85;
// The rules of FogUpsample: the tolerance of the distance of a sample, the spread of the ratios above which the pixel
// walks at full resolution, and the smallest sum of the weights that the weighted mean divides by:
const float FOG_UPSAMPLE_DEPTH_TOLERANCE_SHARE = 0.05;
const float FOG_UPSAMPLE_DEPTH_TOLERANCE_BLOCKS = 0.25;
const float FOG_UPSAMPLE_RATIO_SPREAD = 0.2;
const float FOG_UPSAMPLE_MINIMUM_WEIGHT_SUM = 1.0e-6;
// The ratio that fogUpsampleResolve returns for a pixel that should walk at full resolution, which no ratio of an
// optical depth can take, since a NaN cannot be made reliably in GLSL 1.50:
const float FOG_UPSAMPLE_CANDIDATE = -1.0;

// The share of the fog at the point a quarter block in front of the surface of the pixel, for the debug view of the
// field, and the far rule beyond the region, which shows its own color where the covered stretch reaches the surface:
vec4 debugField(vec3 offset) {
    float offsetLength = length(offset);
    vec3 point = offsetLength < 0.5 ? offset : offset * (1.0 - 0.25 / offsetLength);
    if (abs(point.x) > regionHalfWidth || abs(point.y) > regionHalfWidth || abs(point.z) > regionHalfWidth) {
        int cellsWalked;
        int columnsWalked;
        int marchSteps;
        float farStop;
        snowWalkOpticalDepth(point, partMask, cellsWalked, columnsWalked, farStop, marchSteps);
        return vec4(farStop < 1.0 ? vec3(0.6, 0.0, 0.6) : vec3(0.9), DEBUG_FIELD_OPACITY);
    }
    ivec3 cell = ivec3(floor(eyeFraction + point));
    float share = snowWalkCellShare(cell);
    return vec4(mix(vec3(0.05, 0.1, 0.6), vec3(1.0, 0.85, 0.2), share), DEBUG_FIELD_OPACITY);
}

// The cells and the columns that the walk of the pixel visits, as the red and the green of the debug view:
vec4 debugCells(vec3 offset) {
    int cellsWalked;
    int columnsWalked;
    int marchSteps;
    float farStop;
    snowWalkOpticalDepth(offset, partMask, cellsWalked, columnsWalked, farStop, marchSteps);
    return vec4(float(cellsWalked) / float(SNOW_WALK_MAXIMUM_CELL_STEPS),
            float(columnsWalked) / float(SNOW_WALK_FAR_WALK_COLUMNS), 0.0, DEBUG_CELLS_OPACITY);
}

// The four low-resolution texels around a full-resolution pixel and their bilinear weights, as FogUpsample.footprint:
void fogUpsampleFootprint(ivec2 pixel, ivec2 lowTargetSize, out ivec4 texelXs, out ivec4 texelYs, out vec4 weights) {
    vec2 low = (vec2(pixel) + vec2(0.5)) / float(divisor) - vec2(0.5);
    vec2 lowFloor = floor(low);
    vec2 fraction = low - lowFloor;
    ivec2 first = ivec2(lowFloor);
    ivec2 second = first + ivec2(1);
    first = clamp(first, ivec2(0), lowTargetSize - ivec2(1));
    second = clamp(second, ivec2(0), lowTargetSize - ivec2(1));
    texelXs = ivec4(first.x, second.x, first.x, second.x);
    texelYs = ivec4(first.y, first.y, second.y, second.y);
    weights = vec4((1.0 - fraction.x) * (1.0 - fraction.y), fraction.x * (1.0 - fraction.y),
            (1.0 - fraction.x) * fraction.y, fraction.x * fraction.y);
}

// The ratio that a full-resolution pixel takes from its four low-resolution samples, or FOG_UPSAMPLE_CANDIDATE when it
// should walk at full resolution, as FogUpsample.resolve. The samples are in the order of fogUpsampleFootprint, and a
// sample of the sky has a negative distance:
float fogUpsampleResolve(float ownDistance, vec4 distances, vec4 ratios, vec4 weights, bool allowFallback) {
    float tolerance = FOG_UPSAMPLE_DEPTH_TOLERANCE_SHARE * ownDistance + FOG_UPSAMPLE_DEPTH_TOLERANCE_BLOCKS;
    int agreeingCount = 0;
    int nearestIndex = -1;
    float nearestDifference = 0.0;
    int nearestAgreeingIndex = -1;
    float nearestAgreeingDifference = 0.0;
    float smallestAgreeingRatio = 0.0;
    float largestAgreeingRatio = 0.0;
    float agreeingWeightSum = 0.0;
    float agreeingRatioSum = 0.0;
    for (int sample = 0; sample < 4; sample++) {
        float distance = distances[sample];
        if (distance < 0.0) {
            continue;
        }
        float difference = abs(distance - ownDistance);
        if (nearestIndex < 0 || difference < nearestDifference) {
            nearestIndex = sample;
            nearestDifference = difference;
        }
        if (!(difference <= tolerance)) {
            continue;
        }
        if (agreeingCount == 0 || difference < nearestAgreeingDifference) {
            nearestAgreeingIndex = sample;
            nearestAgreeingDifference = difference;
        }
        if (agreeingCount == 0) {
            smallestAgreeingRatio = ratios[sample];
            largestAgreeingRatio = ratios[sample];
        } else {
            smallestAgreeingRatio = min(smallestAgreeingRatio, ratios[sample]);
            largestAgreeingRatio = max(largestAgreeingRatio, ratios[sample]);
        }
        agreeingCount++;
        agreeingWeightSum += weights[sample];
        agreeingRatioSum += weights[sample] * ratios[sample];
    }
    if (agreeingCount == 0) {
        if (allowFallback) {
            return FOG_UPSAMPLE_CANDIDATE;
        }
        return nearestIndex >= 0 ? ratios[nearestIndex] : 1.0;
    }
    if (largestAgreeingRatio - smallestAgreeingRatio > FOG_UPSAMPLE_RATIO_SPREAD) {
        if (allowFallback) {
            return FOG_UPSAMPLE_CANDIDATE;
        }
        return ratios[nearestAgreeingIndex];
    }
    if (!(agreeingWeightSum > FOG_UPSAMPLE_MINIMUM_WEIGHT_SUM)) {
        return ratios[nearestAgreeingIndex];
    }
    return agreeingRatioSum / agreeingWeightSum;
}

void main() {
    float depth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (depth >= 1.0) {
        discard;
    }
    vec3 ndc = vec3(gl_FragCoord.xy / screenSize * 2.0 - 1.0, depth * 2.0 - 1.0);
    vec4 position = inverseViewProjection * vec4(ndc, 1.0);
    vec3 offset = position.xyz / position.w;
    // A depth at the near plane can give no scene point, which then takes no fog:
    if (any(isnan(offset)) || any(isinf(offset))) {
        discard;
    }
    // The debug views draw every pixel that is not sky, so only the first part draws them:
    if (debugMode != 0 && compositePart != 0) {
        discard;
    }
    if (debugMode == 1) {
        fragColor = debugField(offset);
        return;
    }
    if (debugMode == 2) {
        fragColor = debugCells(offset);
        return;
    }
    float ownDistance = length(offset);
    float analyticDepth = fogOpticalDepth(eyeExtinction, ownDistance, offset.y, heightScale);
    ivec4 texelXs;
    ivec4 texelYs;
    vec4 weights;
    fogUpsampleFootprint(ivec2(gl_FragCoord.xy), lowSize, texelXs, texelYs, weights);
    vec4 distances;
    vec4 ratios;
    for (int sample = 0; sample < 4; sample++) {
        vec4 low = texelFetch(lowResolution, ivec2(texelXs[sample], texelYs[sample]), 0);
        ratios[sample] = low.r;
        distances[sample] = low.g;
    }
    // A candidate is found with the fallback allowed, so the query counts the same pixels whether the cap holds or not:
    float resolvedRatio = fogUpsampleResolve(ownDistance, distances, ratios, weights, true);
    bool isCandidate = resolvedRatio < 0.0;
    // The first part draws the pixels that the samples support and the second the ones that fall back, so that only
    // the part that draws a pixel walks it:
    if (debugMode == 0 && isCandidate != (compositePart == 1)) {
        discard;
    }
    if (debugMode == 4 && isCandidate) {
        fragColor = vec4(fallbackAllowed != 0 ? vec3(1.0, 0.0, 0.0) : vec3(1.0, 0.5, 0.0), 1.0);
        return;
    }
    float tau;
    if (isCandidate) {
        if (fallbackAllowed != 0) {
            int cellsWalked;
            int columnsWalked;
            int marchSteps;
            float farStop;
            tau = snowWalkOpticalDepth(offset, partMask, cellsWalked, columnsWalked, farStop, marchSteps);
        } else {
            tau = analyticDepth * fogUpsampleResolve(ownDistance, distances, ratios, weights, false);
        }
    } else {
        tau = analyticDepth * resolvedRatio;
    }
    tau = min(FOG_MAXIMUM_OPTICAL_DEPTH, tau);
    if (debugMode == 3) {
        fragColor = vec4(vec3(exp(-tau)), 1.0);
        return;
    }
    float opacity = 1.0 - exp(-tau);
    if (opacity < LEAST_OPACITY) {
        discard;
    }
    float phi = atan(offset.z, offset.x);
    float u = phi / (2.0 * PI) + 0.5;
    vec3 fogColor = mix(texture(horizonColors, vec2(u, 0.5)).rgb, snowColor, snowShare);
    fragColor = vec4(fogColor, opacity);
}
