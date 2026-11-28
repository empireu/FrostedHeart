// FHShaders prepends the version line, the LAMP_LIGHT_FIELD define, weather_fog.glsl, weather_fog_excess.glsl and
// lamp_light.glsl.
// Marches the lamp light that the weather fog scatters toward the eye, at a share of the frame's resolution. Each pixel
// of the target takes the ray of the frame pixel in the middle of its block out to that pixel's scene point. The ray
// walks world-aligned major-axis cell slabs of each cascade of the lamp light field that it crosses, keeping to the
// cascade's lit box and skipping dark 8x8x8 coarse slot blocks. Each lit piece adds the lamp color of the level in its
// middle times the light that the fog takes out of the ray over the piece, and the sum is scaled down to the fog's
// opacity at the ray's end where that is lower. The slabs are fixed in the world, so the glow does not swim as the eye
// moves. The pixel holds that light and the distance its ray ended at. LampFogMarch is the Java twin.
uniform sampler2D sceneDepth;
uniform sampler2D translucentDepth;
uniform int hasTranslucent;
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
// Per cascade, the 8x8x8 coarse slot occupancy mask packed as six uvec4s (two uints per coarse Y layer):
const int LAMP_COARSE_SHIFT = 3;
const int LAMP_COARSE_SIZE = 8;
const int LAMP_COARSE_VECS_PER_CASCADE = 6;
uniform uvec4 lampCoarseBits[LAMP_CASCADE_COUNT * LAMP_COARSE_VECS_PER_CASCADE];
// Whether to answer the self test instead of drawing, with the analytic fog alone, and its rays as the offsets from
// the eye to their ends, one per texel of its 64 texel wide target:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 fragColor;
out vec4 translucentFragColor;

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
// Major-axis slab thickness in cascade cells, locked to multiples of this count in world cell space:
const float LAMP_STEP_CELLS = 2.0;
const float LAMP_COARSE_SAFE_MARGIN = 0.501;

// The lamp color of a level over 15, blended between the entries of the levels around it:
vec3 lampLiftOfLevel(float level) {
    float position = clamp(level, 0.0, 1.0) * float(LAMP_LIFT_LEVELS - 1);
    int low = min(int(floor(position)), LAMP_LIFT_LEVELS - 2);
    return mix(lampLift[low], lampLift[low + 1], position - float(low));
}

// Reads one froxel boundary excess at uv, reusing the last fetched 4-slice layer texel when unchanged:
float lampBoundaryExcess(vec2 uv, int boundaryIndex, inout int cachedLayer, inout vec4 cachedTexel) {
    int layer = (boundaryIndex - 1) >> 2;
    int channel = (boundaryIndex - 1) & 3;
    if (layer != cachedLayer) {
        cachedTexel = textureLod(excesses, vec3(uv, float(layer)), 0.0);
        cachedLayer = layer;
    }
    return froxelChannel(cachedTexel, channel);
}

// The froxel excess at distance along the ray, reusing the last fetched 4-slice layer texel:
float lampFroxelExcess(float distance, vec2 uv, float invLogRange, inout int cachedLayer, inout vec4 cachedTexel) {
    if (!(distance > FOG_FROXEL_NEAR) || !(invLogRange > 0.0)) {
        return 0.0;
    }
    if (distance >= rangeDistance) {
        return lampBoundaryExcess(uv, sliceCount, cachedLayer, cachedTexel);
    }
    float position = min(float(sliceCount), log(distance / FOG_FROXEL_NEAR) * invLogRange);
    if (!(position > 0.0)) {
        return 0.0;
    }
    int lowBoundary = int(floor(position));
    float frac = position - float(lowBoundary);
    if (!(frac > 0.0)) {
        return lampBoundaryExcess(uv, lowBoundary, cachedLayer, cachedTexel);
    }
    float belowExcess = lowBoundary > 0 ? lampBoundaryExcess(uv, lowBoundary, cachedLayer, cachedTexel) : 0.0;
    float aboveExcess = lampBoundaryExcess(uv, lowBoundary + 1, cachedLayer, cachedTexel);
    return belowExcess + frac * (aboveExcess - belowExcess);
}

// The fog's optical depth from the eye to a distance along a unit direction, with the excess of the froxels at uv
// unless the self test runs:
float lampOpticalDepth(float distance, vec3 direction, vec2 uv, float invLogRange, inout int cachedLayer,
        inout vec4 cachedTexel) {
    float depth = fogOpticalDepth(eyeExtinction, distance, distance * direction.y, heightScale);
    if (selfTest == 0) {
        depth += lampFroxelExcess(distance, uv, invLogRange, cachedLayer, cachedTexel);
    }
    return clamp(depth, 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
}

// The 64-bit XZ coarse occupancy pair for one coarse Y layer of a cascade:
uvec2 lampCoarseLayer(int cascade, int coarseY) {
    uvec4 pack = lampCoarseBits[cascade * LAMP_COARSE_VECS_PER_CASCADE + (coarseY >> 1)];
    return (coarseY & 1) == 0 ? pack.xy : pack.zw;
}

// Whether any coarse slot block in the 1 to 8 combinations of lowCoarse and highCoarse holds a lit cell:
bool lampAnyCoarseLit(int cascade, ivec3 lowCoarse, ivec3 highCoarse) {
    uint xMask = (1u << uint(lowCoarse.x)) | (1u << uint(highCoarse.x));
    uint mask0 = 0u;
    uint mask1 = 0u;
    if (lowCoarse.z < 4) {
        mask0 |= xMask << uint(lowCoarse.z << 3);
    } else {
        mask1 |= xMask << uint((lowCoarse.z - 4) << 3);
    }
    if (highCoarse.z < 4) {
        mask0 |= xMask << uint(highCoarse.z << 3);
    } else {
        mask1 |= xMask << uint((highCoarse.z - 4) << 3);
    }
    uvec2 lowLayer = lampCoarseLayer(cascade, lowCoarse.y);
    if (((lowLayer.x & mask0) | (lowLayer.y & mask1)) != 0u) {
        return true;
    }
    if (highCoarse.y != lowCoarse.y) {
        uvec2 highLayer = lampCoarseLayer(cascade, highCoarse.y);
        if (((highLayer.x & mask0) | (highLayer.y & mask1)) != 0u) {
            return true;
        }
    }
    return false;
}

// Whether the trilinear filter footprint at positionInCascade touches any lit coarse slot block:
bool lampSampleMayBeLit(int cascade, vec3 positionInCascade, out vec3 inWindow, out ivec3 lowCoarse,
        out ivec3 highCoarse, out bool insideWindow) {
    vec3 size = lampCascadeSize[cascade];
    ivec3 intSize = ivec3(size + 0.5);
    insideWindow = positionInCascade.x >= 0.5 && positionInCascade.x <= size.x - 0.5
            && positionInCascade.y >= 0.5 && positionInCascade.y <= size.y - 0.5
            && positionInCascade.z >= 0.5 && positionInCascade.z <= size.z - 0.5;
    inWindow = clamp(positionInCascade, vec3(0.5), size - 0.5);
    ivec3 lowCell = clamp(ivec3(floor(inWindow - 0.5)), ivec3(0), intSize - 1);
    ivec3 highCell = min(lowCell + 1, intSize - 1);
    ivec3 intSlotOrigin = ivec3(lampSlotOrigin[cascade] + 0.5);
    ivec3 lowSlot = intSlotOrigin + lowCell;
    lowSlot -= ivec3(greaterThanEqual(lowSlot, intSize)) * intSize;
    ivec3 highSlot = intSlotOrigin + highCell;
    highSlot -= ivec3(greaterThanEqual(highSlot, intSize)) * intSize;
    lowCoarse = lowSlot >> LAMP_COARSE_SHIFT;
    highCoarse = highSlot >> LAMP_COARSE_SHIFT;
    return lampAnyCoarseLit(cascade, lowCoarse, highCoarse);
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

// The lamp light that the fog adds along a unit direction from the eye out to an end distance, before the scaling of
// scaleLampLight, with an optional split snapshot at splitDistance (< endDistance) and the largest optical depth found
// up to each endpoint:
vec3 lampFogMarched(vec3 direction, float endDistance, float splitDistance, vec2 uv, float invLogRange,
        out float runningDepth, out vec3 splitLight, out float splitRunningDepth) {
    vec3 light = vec3(0.0);
    // The largest optical depth found so far and its cached transmittance, which the pieces carry on:
    runningDepth = 0.0;
    float runningTransmittance = 1.0;
    float depthDistance = 0.0;
    int cachedLayer = -1;
    vec4 cachedTexel = vec4(0.0);
    bool splits = splitDistance < endDistance;
    bool splitCaptured = !splits;
    splitLight = vec3(0.0);
    splitRunningDepth = 0.0;
    int steps = 0;
    float regionStart = 0.0;
    vec2 ownSpan = lampCascadeCount > 0 ? lampBoxSpan(0, direction, lampLitLow[0], lampLitHigh[0]) : vec2(1.0, 0.0);
    for (int cascade = 0; cascade < LAMP_CASCADE_COUNT; cascade++) {
        if (cascade >= lampCascadeCount || !(regionStart < endDistance)) {
            break;
        }
        vec3 scaled = abs(direction) * lampInverseReach[cascade];
        float bandRate = max(max(scaled.x, scaled.y), scaled.z);
        float bandStart = bandRate > 0.0 ? LAMP_BAND_START / bandRate : LAMP_NO_CROSSING;
        float bandEnd = bandRate > 0.0 ? LAMP_BAND_END / bandRate : LAMP_NO_CROSSING;
        bool hasNext = cascade + 1 < lampCascadeCount;
        vec2 nextSpan = hasNext ? lampBoxSpan(cascade + 1, direction, lampLitLow[cascade + 1],
                lampLitHigh[cascade + 1]) : vec2(1.0, 0.0);
        if (ownSpan.x > ownSpan.y && nextSpan.x > nextSpan.y) {
            regionStart = max(regionStart, bandEnd);
            ownSpan = nextSpan;
            continue;
        }
        vec3 rate = direction * lampCellsPerBlock[cascade];
        vec3 absRate = abs(rate);
        // Align slab planes in slot space so multiples of LAMP_STEP_CELLS stay locked to world cell planes across
        // cascade window recentering:
        float majorSlotEye;
        float majorRate;
        if (absRate.x >= absRate.y && absRate.x >= absRate.z) {
            majorSlotEye = lampSlotOrigin[cascade].x + lampEyeInCascade[cascade].x;
            majorRate = rate.x;
        } else if (absRate.y >= absRate.z) {
            majorSlotEye = lampSlotOrigin[cascade].y + lampEyeInCascade[cascade].y;
            majorRate = rate.y;
        } else {
            majorSlotEye = lampSlotOrigin[cascade].z + lampEyeInCascade[cascade].z;
            majorRate = rate.z;
        }
        float slabStep = abs(majorRate) > 0.0 ? LAMP_STEP_CELLS / abs(majorRate) : LAMP_NO_CROSSING;
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
            // The walk takes the whole world-aligned major-axis slabs that the lit span touches:
            float start = partStart;
            float nextSlab;
            if (majorRate > 0.0) {
                float spanPlane = floor((majorSlotEye + spanStart * majorRate) / LAMP_STEP_CELLS) * LAMP_STEP_CELLS;
                float planeDistance = (spanPlane - majorSlotEye) / majorRate;
                if (planeDistance > partStart) {
                    start = min(planeDistance, spanStart);
                    nextSlab = (spanPlane + LAMP_STEP_CELLS - majorSlotEye) / majorRate;
                } else {
                    float startPosition = majorSlotEye + partStart * majorRate;
                    float nextPlane = (floor(startPosition / LAMP_STEP_CELLS) + 1.0) * LAMP_STEP_CELLS;
                    nextSlab = partStart + (nextPlane - startPosition) / majorRate;
                }
            } else if (majorRate < 0.0) {
                float spanPlane = ceil((majorSlotEye + spanStart * majorRate) / LAMP_STEP_CELLS) * LAMP_STEP_CELLS;
                float planeDistance = (spanPlane - majorSlotEye) / majorRate;
                if (planeDistance > partStart) {
                    start = min(planeDistance, spanStart);
                    nextSlab = (spanPlane - LAMP_STEP_CELLS - majorSlotEye) / majorRate;
                } else {
                    float startPosition = majorSlotEye + partStart * majorRate;
                    float nextPlane = (ceil(startPosition / LAMP_STEP_CELLS) - 1.0) * LAMP_STEP_CELLS;
                    nextSlab = partStart + (nextPlane - startPosition) / majorRate;
                }
            } else {
                nextSlab = LAMP_NO_CROSSING;
            }
            if (!(nextSlab > start)) {
                nextSlab = start + slabStep;
            }
            if (!splitCaptured && !(start < splitDistance)) {
                splitLight = light;
                splitRunningDepth = runningDepth;
                splitCaptured = true;
            }
            if (depthDistance != start) {
                float startDepth = lampOpticalDepth(start, direction, uv, invLogRange, cachedLayer, cachedTexel);
                if (startDepth > runningDepth) {
                    runningDepth = startDepth;
                    runningTransmittance = exp(-runningDepth);
                }
                depthDistance = start;
            }
            if (runningTransmittance < LAMP_LEAST_TRANSMITTANCE) {
                if (!splitCaptured) {
                    splitLight = light;
                    splitRunningDepth = runningDepth;
                    splitCaptured = true;
                }
                return light;
            }
            float distance = start;
            bool walking = true;
            while (walking && steps < LAMP_MAXIMUM_STEPS) {
                steps++;
                float exit = min(nextSlab, partEnd);
                bool splitsHere = !splitCaptured && distance < splitDistance && splitDistance < exit;
                if (splitsHere) {
                    exit = splitDistance;
                }
                float middle = 0.5 * (distance + exit);
                vec3 midPos = lampEyeInCascade[cascade] + middle * rate;
                vec3 ownInWindow;
                ivec3 lowCoarse;
                ivec3 highCoarse;
                bool insideWindow;
                bool ownLit = lampSampleMayBeLit(cascade, midPos, ownInWindow, lowCoarse, highCoarse, insideWindow);
                bool mayBeLit = ownLit;
                if (part == 1 && !ownLit && hasNext) {
                    vec3 nextInWindow;
                    ivec3 nextLowCoarse;
                    ivec3 nextHighCoarse;
                    bool nextInside;
                    vec3 nextMidPos = lampEyeInCascade[cascade + 1]
                            + (middle * direction) * lampCellsPerBlock[cascade + 1];
                    mayBeLit = lampSampleMayBeLit(cascade + 1, nextMidPos, nextInWindow, nextLowCoarse,
                            nextHighCoarse, nextInside);
                }
                if (!mayBeLit) {
                    if (part == 0 && !splitsHere && insideWindow && lowCoarse == highCoarse && exit == nextSlab) {
                        vec3 slotOrigin = lampSlotOrigin[cascade];
                        float coarseSize = float(LAMP_COARSE_SIZE);
                        vec3 blockBase = floor((slotOrigin + midPos) / coarseSize) * coarseSize - slotOrigin;
                        vec3 target = mix(max(blockBase + LAMP_COARSE_SAFE_MARGIN, vec3(0.5)),
                                min(blockBase + (coarseSize - LAMP_COARSE_SAFE_MARGIN),
                                        lampCascadeSize[cascade] - 0.5),
                                greaterThan(rate, vec3(0.0)));
                        vec3 dt = vec3(
                                rate.x != 0.0 ? (target.x - midPos.x) / rate.x : LAMP_NO_CROSSING,
                                rate.y != 0.0 ? (target.y - midPos.y) / rate.y : LAMP_NO_CROSSING,
                                rate.z != 0.0 ? (target.z - midPos.z) / rate.z : LAMP_NO_CROSSING);
                        float jumpLimit = min(middle + min(dt.x, min(dt.y, dt.z)), min(partEnd, spanEnd));
                        if (jumpLimit > nextSlab) {
                            float extraSlabs = floor((jumpLimit - nextSlab) / slabStep);
                            if (extraSlabs > 0.0) {
                                exit = min(nextSlab + extraSlabs * slabStep, partEnd);
                                nextSlab = exit;
                            }
                        }
                    }
                } else {
                    vec2 shares = lampCascadeShares(cascade,
                            (lampSlotOrigin[cascade] + ownInWindow) / lampCascadeSize[cascade]);
                    if (part == 1) {
                        float weight = clamp((middle * bandRate - LAMP_BAND_START) / (LAMP_BAND_END - LAMP_BAND_START),
                                0.0, 1.0);
                        vec2 nextShares = hasNext ? lampCascadeSharesAt(cascade + 1, middle * direction)
                                : vec2(0.0, 1.0);
                        shares = mix(shares, nextShares, weight);
                    }
                    float level = lampFieldFade * lampLevelOfShares(shares);
                    if (level > 0.0) {
                        if (depthDistance != distance) {
                            float startDepth = lampOpticalDepth(distance, direction, uv, invLogRange, cachedLayer,
                                    cachedTexel);
                            if (startDepth > runningDepth) {
                                runningDepth = startDepth;
                                runningTransmittance = exp(-runningDepth);
                            }
                            depthDistance = distance;
                        }
                        float exitDepth = lampOpticalDepth(exit, direction, uv, invLogRange, cachedLayer, cachedTexel);
                        if (exitDepth > runningDepth) {
                            float exitTransmittance = exp(-exitDepth);
                            light += lampLiftOfLevel(level) * (runningTransmittance - exitTransmittance);
                            runningDepth = exitDepth;
                            runningTransmittance = exitTransmittance;
                        }
                        depthDistance = exit;
                    }
                }
                if (!splitCaptured && !(exit < splitDistance)) {
                    splitLight = light;
                    splitRunningDepth = runningDepth;
                    splitCaptured = true;
                }
                if (runningTransmittance < LAMP_LEAST_TRANSMITTANCE) {
                    if (!splitCaptured) {
                        splitLight = light;
                        splitRunningDepth = runningDepth;
                        splitCaptured = true;
                    }
                    return light;
                }
                if (!splitsHere && !(exit < spanEnd)) {
                    walking = false;
                } else {
                    distance = exit;
                    if (nextSlab <= exit) {
                        nextSlab += slabStep;
                    }
                }
            }
        }
        regionStart = max(regionStart, bandEnd);
        ownSpan = nextSpan;
    }
    if (!splits || !splitCaptured) {
        splitLight = light;
        splitRunningDepth = runningDepth;
    }
    return light;
}

// Scales marched lamp light down to the opacity at endDistance when the optical depth at the end lies below the
// largest the march found, so the glow never outshines the fog it lights:
vec3 scaleLampLight(vec3 light, float runningDepth, float endDistance, vec3 direction, vec2 uv) {
    if (runningDepth > LAMP_LEAST_SCALED_DEPTH && (light.r > 0.0 || light.g > 0.0 || light.b > 0.0)) {
        float endDepth = fogOpticalDepth(eyeExtinction, endDistance, endDistance * direction.y, heightScale);
        if (selfTest == 0) {
            endDepth += froxelExcessAtDistance(endDistance, uv);
        }
        endDepth = clamp(endDepth, 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
        if (endDepth < runningDepth) {
            light *= (1.0 - exp(-endDepth)) / (1.0 - exp(-runningDepth));
        }
    }
    return light;
}

// Marches the lamp light out to endDistance and, when splitDistance < endDistance, snapshots the light at
// splitDistance in the same walk:
void lampFogAlongSplit(vec3 direction, float endDistance, float splitDistance, vec2 uv, out vec3 opaqueLight,
        out vec3 translucentLight) {
    float invLogRange = (selfTest == 0 && sliceCount > 0 && rangeDistance > FOG_FROXEL_NEAR)
            ? float(sliceCount) / log(rangeDistance / FOG_FROXEL_NEAR) : 0.0;
    float runningDepth;
    vec3 splitLight;
    float splitRunningDepth;
    vec3 light = lampFogMarched(direction, endDistance, splitDistance, uv, invLogRange, runningDepth, splitLight,
            splitRunningDepth);
    opaqueLight = scaleLampLight(light, runningDepth, endDistance, direction, uv);
    if (splitDistance < endDistance) {
        translucentLight = scaleLampLight(splitLight, splitRunningDepth, splitDistance, direction, uv);
    } else {
        translucentLight = opaqueLight;
    }
}

void main() {
    if (selfTest != 0) {
        vec3 testOffset = selfTestOffsets[int(gl_FragCoord.x)];
        float testLength = length(testOffset);
        vec3 opaqueLight = vec3(0.0);
        vec3 translucentLight = vec3(0.0);
        if (testLength > 0.0) {
            lampFogAlongSplit(testOffset / testLength, testLength, testLength, vec2(0.5), opaqueLight,
                    translucentLight);
        }
        fragColor = vec4(opaqueLight, 0.0);
        translucentFragColor = vec4(0.0);
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
        translucentFragColor = vec4(0.0);
        return;
    }
    float endDistance = isSky ? LAMP_SKY_DISTANCE : min(offsetLength, LAMP_SKY_DISTANCE);
    float splitDistance = endDistance;
    if (hasTranslucent != 0) {
        float tDepth = texelFetch(translucentDepth, framePixel, 0).r;
        if (tDepth < depth) {
            vec3 tNdc = vec3(frameCenter / screenSize * 2.0 - 1.0, tDepth * 2.0 - 1.0);
            vec4 tPosition = inverseViewProjection * vec4(tNdc, 1.0);
            vec3 tOffset = tPosition.xyz / tPosition.w;
            float tLength = length(tOffset);
            if (tLength > 0.0 && !isnan(tLength) && !isinf(tLength)) {
                splitDistance = min(tLength, endDistance);
            }
        }
    }
    vec2 uv = frameCenter / (float(divisor) * vec2(columnCount, rowCount));
    vec3 opaqueLight;
    vec3 translucentLight;
    lampFogAlongSplit(offset / offsetLength, endDistance, splitDistance, uv, opaqueLight, translucentLight);
    fragColor = vec4(opaqueLight, endDistance);
    translucentFragColor = vec4(translucentLight, splitDistance);
}
