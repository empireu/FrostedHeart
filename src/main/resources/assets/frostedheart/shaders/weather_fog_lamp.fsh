// FHShaders prepends the version line, the LAMP_LIGHT_FIELD define, weather_fog.glsl, weather_fog_excess.glsl and
// lamp_light.glsl.
// Marches the lamp light that the weather fog scatters toward the eye, at a share of the frame's resolution. Each pixel
// of the target takes the ray of the frame pixel in the middle of its block out to that pixel's scene point. The ray
// walks the cells of each cascade of the lamp light field that it crosses, cut into pieces at the cell boundaries, and
// keeps to the pieces that touch the cascade's lit box. Each lit piece adds the lamp color of the level in its middle
// times the light that the fog takes out of the ray over the piece, and the sum is scaled down to the fog's opacity at
// the ray's end where that is lower. The pieces are fixed in the world, so the glow does not swim as the eye moves.
// The pixel holds that light and the distance its ray ended at. LampFogMarch is the Java twin.
uniform sampler2D sceneDepth;
uniform vec2 screenSize;
uniform mat4 inverseViewProjection;
uniform float eyeExtinction;
uniform float heightScale;
// The frame pixels per side of a pixel of the target:
uniform int lampDivisor;
// The lamp color that each level from 0 to 15 adds to the fog, as display values:
uniform vec3 lampLift[16];
// Per cascade, the box of its lit cells in cells from its lowest cell, grown by half a cell:
uniform vec3 lampLitLow[LAMP_CASCADE_COUNT];
uniform vec3 lampLitHigh[LAMP_CASCADE_COUNT];
// Whether to answer the self test instead of drawing, with the analytic fog alone, and its rays as the offsets from
// the eye to their ends, one per texel of its 64 texel wide target:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 fragColor;

// The transmittance below which the march stops, 1 / 512, its most pieces per ray, the distance a ray of the sky ends
// at, and a distance no crossing reaches. These must match LampFogMarch:
const float LAMP_LEAST_TRANSMITTANCE = 0.001953125;
const int LAMP_MAXIMUM_STEPS = 512;
const float LAMP_SKY_DISTANCE = 60000.0;
const float LAMP_NO_CROSSING = 1.0e30;
const int LAMP_LIFT_LEVELS = 16;
// The optical depth below which the light is not scaled to the opacity at the ray's end, since that light is too faint
// to see:
const float LAMP_LEAST_SCALED_DEPTH = 1.0e-6;

// The lamp color of a level over 15, blended between the entries of the levels around it:
vec3 lampLiftOfLevel(float level) {
    float position = clamp(level, 0.0, 1.0) * float(LAMP_LIFT_LEVELS - 1);
    int low = min(int(floor(position)), LAMP_LIFT_LEVELS - 2);
    return mix(lampLift[low], lampLift[low + 1], position - float(low));
}

// The fog's optical depth from the eye to a distance along a unit direction, with the excess of the froxels at uv
// unless the self test runs:
float lampOpticalDepth(float distance, vec3 direction, vec2 uv) {
    float depth = fogOpticalDepth(eyeExtinction, distance, distance * direction.y, heightScale);
    if (selfTest == 0) {
        depth += froxelExcessAtDistance(distance, uv);
    }
    return clamp(depth, 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
}

// Narrows the span of a ray to the slab of one axis of a box, the ray starting at origin and moving rate cells per
// block:
void lampSlab(float origin, float rate, float low, float high, inout vec2 span) {
    if (rate != 0.0) {
        float first = (low - origin) / rate;
        float second = (high - origin) / rate;
        span.x = max(span.x, min(first, second));
        span.y = min(span.y, max(first, second));
    } else if (origin < low || origin > high) {
        span = vec2(1.0, 0.0);
    }
}

// The distances along a ray from the eye between which it lies in a box of a cascade's cells, the start above the
// end where it misses the box:
vec2 lampBoxSpan(int cascade, vec3 direction, vec3 low, vec3 high) {
    if (low.x > high.x || low.y > high.y || low.z > high.z) {
        return vec2(1.0, 0.0);
    }
    vec3 origin = lampEyeInCascade[cascade];
    vec3 rate = direction * lampCellsPerBlock[cascade];
    vec2 span = vec2(0.0, LAMP_NO_CROSSING);
    lampSlab(origin.x, rate.x, low.x, high.x, span);
    lampSlab(origin.y, rate.y, low.y, high.y, span);
    lampSlab(origin.z, rate.z, low.z, high.z, span);
    return span;
}

// The distance at which a ray last crossed a cell boundary of one axis at or before a distance, for a ray at position
// cells there that moves rate cells per block:
float lampLastCrossing(float position, float rate, float distance) {
    if (rate > 0.0) {
        return distance - (position - floor(position)) / rate;
    }
    if (rate < 0.0) {
        return distance + (ceil(position) - position) / rate;
    }
    return -LAMP_NO_CROSSING;
}

// The distance at which a ray first crosses a cell boundary of one axis after a start, and the distance between two
// crossings, for a ray at position cells at the start that moves rate cells per block:
void lampCrossing(float position, float rate, float start, out float next, out float between) {
    if (rate > 0.0) {
        next = start + (floor(position) + 1.0 - position) / rate;
        between = 1.0 / rate;
    } else if (rate < 0.0) {
        next = start + (ceil(position) - 1.0 - position) / rate;
        between = -1.0 / rate;
    } else {
        next = LAMP_NO_CROSSING;
        between = LAMP_NO_CROSSING;
    }
}

// The lamp light that the fog adds along a unit direction from the eye out to an end distance, before the scaling of
// lampFogAlong, and the largest optical depth the march found:
vec3 lampFogMarched(vec3 direction, float endDistance, vec2 uv, out float runningDepth) {
    vec3 light = vec3(0.0);
    // The largest optical depth found so far and the distance it was found at, which the pieces carry on:
    runningDepth = 0.0;
    float depthDistance = 0.0;
    int steps = 0;
    float regionStart = 0.0;
    for (int cascade = 0; cascade < LAMP_CASCADE_COUNT; cascade++) {
        if (cascade >= lampCascadeCount || !(regionStart < endDistance)) {
            break;
        }
        vec3 scaled = abs(direction) * lampInverseReach[cascade];
        float bandRate = max(max(scaled.x, scaled.y), scaled.z);
        float bandStart = bandRate > 0.0 ? LAMP_BAND_START / bandRate : LAMP_NO_CROSSING;
        float bandEnd = bandRate > 0.0 ? LAMP_BAND_END / bandRate : LAMP_NO_CROSSING;
        bool hasNext = cascade + 1 < lampCascadeCount;
        vec2 ownSpan = lampBoxSpan(cascade, direction, lampLitLow[cascade], lampLitHigh[cascade]);
        vec2 nextSpan = hasNext ? lampBoxSpan(cascade + 1, direction, lampLitLow[cascade + 1],
                lampLitHigh[cascade + 1]) : vec2(1.0, 0.0);
        // Part 0 is where this cascade alone holds the ray and part 1 its band, where the next one blends in:
        for (int part = 0; part < 2; part++) {
            float partStart = part == 0 ? regionStart : max(regionStart, bandStart);
            float partEnd = min(endDistance, part == 0 ? bandStart : bandEnd);
            vec2 span = ownSpan;
            if (part == 1 && nextSpan.x <= nextSpan.y) {
                span = ownSpan.x <= ownSpan.y ? vec2(min(ownSpan.x, nextSpan.x), max(ownSpan.y, nextSpan.y))
                        : nextSpan;
            }
            float spanStart = max(partStart, span.x);
            float spanEnd = min(partEnd, span.y);
            if (!(spanStart < spanEnd)) {
                continue;
            }
            // The walk takes the whole pieces that the lit span touches, so it gives what a walk of the whole part
            // gives:
            vec3 rate = direction * lampCellsPerBlock[cascade];
            vec3 spanPosition = lampEyeInCascade[cascade] + spanStart * rate;
            float start = max(partStart, max(max(lampLastCrossing(spanPosition.x, rate.x, spanStart),
                    lampLastCrossing(spanPosition.y, rate.y, spanStart)),
                    lampLastCrossing(spanPosition.z, rate.z, spanStart)));
            if (depthDistance != start) {
                runningDepth = max(runningDepth, lampOpticalDepth(start, direction, uv));
                depthDistance = start;
            }
            if (exp(-runningDepth) < LAMP_LEAST_TRANSMITTANCE) {
                return light;
            }
            vec3 position = lampEyeInCascade[cascade] + start * rate;
            vec3 nextCrossing;
            vec3 between;
            lampCrossing(position.x, rate.x, start, nextCrossing.x, between.x);
            lampCrossing(position.y, rate.y, start, nextCrossing.y, between.y);
            lampCrossing(position.z, rate.z, start, nextCrossing.z, between.z);
            float distance = start;
            bool walking = true;
            while (walking && steps < LAMP_MAXIMUM_STEPS) {
                steps++;
                float exit = min(min(nextCrossing.x, nextCrossing.y), min(nextCrossing.z, partEnd));
                float middle = 0.5 * (distance + exit);
                vec3 offset = middle * direction;
                vec2 shares = lampCascadeSharesAt(cascade, offset);
                if (part == 1) {
                    float weight = clamp((middle * bandRate - LAMP_BAND_START) / (LAMP_BAND_END - LAMP_BAND_START),
                            0.0, 1.0);
                    vec2 nextShares = hasNext ? lampCascadeSharesAt(cascade + 1, offset) : vec2(0.0, 1.0);
                    shares = mix(shares, nextShares, weight);
                }
                float level = lampFieldFade * lampLevelOfShares(shares);
                if (level > 0.0) {
                    if (depthDistance != distance) {
                        runningDepth = max(runningDepth, lampOpticalDepth(distance, direction, uv));
                        depthDistance = distance;
                    }
                    float exitDepth = max(runningDepth, lampOpticalDepth(exit, direction, uv));
                    light += lampLiftOfLevel(level) * (exp(-runningDepth) - exp(-exitDepth));
                    runningDepth = exitDepth;
                    depthDistance = exit;
                    if (exp(-runningDepth) < LAMP_LEAST_TRANSMITTANCE) {
                        return light;
                    }
                }
                if (!(exit < spanEnd)) {
                    walking = false;
                } else {
                    distance = exit;
                    // Ties step every tied axis:
                    if (nextCrossing.x <= exit) {
                        nextCrossing.x += between.x;
                    }
                    if (nextCrossing.y <= exit) {
                        nextCrossing.y += between.y;
                    }
                    if (nextCrossing.z <= exit) {
                        nextCrossing.z += between.z;
                    }
                }
            }
        }
        regionStart = max(regionStart, bandEnd);
    }
    return light;
}

// The lamp light that the fog adds along a unit direction from the eye out to an end distance. Where the optical
// depth at the end lies below the largest the march found, which the interpolation of the froxel excess gives in
// shelters, the light is scaled to the opacity at the end, which the composite draws the fog with, so the glow never
// outshines the fog it lights:
vec3 lampFogAlong(vec3 direction, float endDistance, vec2 uv) {
    float runningDepth;
    vec3 light = lampFogMarched(direction, endDistance, uv, runningDepth);
    if (runningDepth > LAMP_LEAST_SCALED_DEPTH && (light.r > 0.0 || light.g > 0.0 || light.b > 0.0)) {
        float endDepth = lampOpticalDepth(endDistance, direction, uv);
        if (endDepth < runningDepth) {
            light *= (1.0 - exp(-endDepth)) / (1.0 - exp(-runningDepth));
        }
    }
    return light;
}

void main() {
    if (selfTest != 0) {
        vec3 testOffset = selfTestOffsets[int(gl_FragCoord.x)];
        float testLength = length(testOffset);
        fragColor = vec4(testLength > 0.0 ? lampFogAlong(testOffset / testLength, testLength, vec2(0.5)) : vec3(0.0),
                0.0);
        return;
    }
    ivec2 framePixel = min(ivec2(gl_FragCoord.xy) * lampDivisor + lampDivisor / 2, ivec2(screenSize) - 1);
    vec2 frameCenter = vec2(framePixel) + 0.5;
    float depth = texelFetch(sceneDepth, framePixel, 0).r;
    bool isSky = depth >= 1.0;
    vec3 ndc = vec3(frameCenter / screenSize * 2.0 - 1.0, isSky ? 1.0 : depth * 2.0 - 1.0);
    vec4 position = inverseViewProjection * vec4(ndc, 1.0);
    vec3 offset = position.xyz / position.w;
    float offsetLength = length(offset);
    // A depth at the near plane can give no scene point, which then takes no lamp light:
    if (!(offsetLength > 0.0) || isnan(offsetLength) || isinf(offsetLength)) {
        fragColor = vec4(0.0);
        return;
    }
    float endDistance = isSky ? LAMP_SKY_DISTANCE : min(offsetLength, LAMP_SKY_DISTANCE);
    vec2 uv = frameCenter / (float(divisor) * vec2(columnCount, rowCount));
    fragColor = vec4(lampFogAlong(offset / offsetLength, endDistance, uv), endDistance);
}
