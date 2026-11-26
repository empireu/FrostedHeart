// FHShaders prepends the version line.
// Builds the wind-aligned snowfall lee map into an RG32F 1024 by 1024 target, as SnowLee.scan describes it.
// Pass 0 resamples the no-leaves far heights H and relief H - S at t = 1 upwind using two taps along the wind and
// subtracts leeSlope into RG (h_raw, e_rel). Passes 1 to 7 run the doubling max-plus scan along x on both channels
// without wrapping across the map edge. Pass 8 applies the 3-texel across-wind minimum to both channels, writes the
// combined lee surface h = min(h_raw, S + e_rel) to R, and writes the terrain-following slope clamp(w . grad S, -1, 1)
// to G.
uniform sampler2D sourceMap;
uniform isampler2D noLeavesHeights;
uniform sampler2D smoothHeights;
uniform int passIndex;
uniform int stride;
uniform int applyAcrossMin;
uniform ivec2 leeAnchor;
uniform ivec2 farHeightsMin;
uniform ivec2 smoothMin;
uniform vec2 leeWindDirection;
uniform float leeSlope;
out vec4 fragColor;

const int SNOW_LEE_MAP_SIZE = 1024;
const float SNOW_LEE_HALF_SIZE = 512.0;
const int SNOW_LEE_FAR_HEIGHTS_SIZE = 1024;
const int SNOW_LEE_FAR_HEIGHTS_MASK = 1023;
const int SNOW_LEE_UNKNOWN_HEIGHT = -32768;
const float SNOW_LEE_NO_HEIGHT = -32768.0;
const float SNOW_LEE_NO_HEIGHT_THRESHOLD = -30000.0;
const float SNOW_LEE_SAMPLE_OFFSET = 0.25;
const int SNOW_SMOOTH_SCALE = 2;
const int SNOW_SMOOTH_SIZE = 512;
const float SNOW_SMOOTH_GRADIENT_STEP = 2.0;

float sampleNoLeavesHeight(float u, float s) {
    float dx = u * leeWindDirection.x - s * leeWindDirection.y;
    float dz = u * leeWindDirection.y + s * leeWindDirection.x;
    ivec2 column = leeAnchor + ivec2(floor(dx), floor(dz));
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

// Valid-only bilinear sample of S at an offset in blocks from smoothMin, matching SnowLee.sampleSmoothBilinear:
float sampleSmoothHeightFromMin(vec2 fromSmoothMin) {
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

float sampleSmoothWind(float u, float s) {
    float dx = u * leeWindDirection.x - s * leeWindDirection.y;
    float dz = u * leeWindDirection.y + s * leeWindDirection.x;
    vec2 fromSmoothMin = vec2(leeAnchor - smoothMin) + vec2(dx, dz);
    return sampleSmoothHeightFromMin(fromSmoothMin);
}

vec2 scanTexel(ivec2 pos) {
    vec2 value = texelFetch(sourceMap, pos, 0).rg;
    if (pos.x >= stride) {
        vec2 upwind = texelFetch(sourceMap, ivec2(pos.x - stride, pos.y), 0).rg;
        float drop = leeSlope * float(stride);
        if (upwind.x > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
            value.x = max(value.x, upwind.x - drop);
        }
        if (upwind.y > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
            value.y = max(value.y, upwind.y - drop);
        }
    }
    return value;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    if (passIndex == 0) {
        float u = float(texel.x) - SNOW_LEE_HALF_SIZE + 0.5;
        float s = float(texel.y) - SNOW_LEE_HALF_SIZE + 0.5;
        float upwindU = u - 1.0;
        float height = max(sampleNoLeavesHeight(upwindU - SNOW_LEE_SAMPLE_OFFSET, s),
                sampleNoLeavesHeight(upwindU + SNOW_LEE_SAMPLE_OFFSET, s));
        if (height > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
            float rawValue = height - leeSlope;
            float smoothUpwind = sampleSmoothWind(upwindU, s);
            float relValue = smoothUpwind > SNOW_LEE_NO_HEIGHT_THRESHOLD
                    ? (height - smoothUpwind) - leeSlope : SNOW_LEE_NO_HEIGHT;
            fragColor = vec4(rawValue, relValue, 0.0, 0.0);
        } else {
            fragColor = vec4(SNOW_LEE_NO_HEIGHT, SNOW_LEE_NO_HEIGHT, 0.0, 0.0);
        }
        return;
    }
    vec2 scanned = scanTexel(texel);
    if (applyAcrossMin != 0) {
        vec2 minus = scanTexel(ivec2(texel.x, max(texel.y - 1, 0)));
        vec2 plus = scanTexel(ivec2(texel.x, min(texel.y + 1, SNOW_LEE_MAP_SIZE - 1)));
        scanned = min(scanned, min(minus, plus));
        float u = float(texel.x) - SNOW_LEE_HALF_SIZE + 0.5;
        float s = float(texel.y) - SNOW_LEE_HALF_SIZE + 0.5;
        float smoothHere = sampleSmoothWind(u, s);
        float leeHeight;
        if (!(scanned.x > SNOW_LEE_NO_HEIGHT_THRESHOLD)) {
            leeHeight = SNOW_LEE_NO_HEIGHT;
        } else if (scanned.y > SNOW_LEE_NO_HEIGHT_THRESHOLD && smoothHere > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
            leeHeight = min(scanned.x, smoothHere + scanned.y);
        } else {
            leeHeight = scanned.x;
        }
        float smoothForward = sampleSmoothWind(u + SNOW_SMOOTH_GRADIENT_STEP, s);
        float smoothBackward = sampleSmoothWind(u - SNOW_SMOOTH_GRADIENT_STEP, s);
        float terrainSlope = 0.0;
        if (smoothForward > SNOW_LEE_NO_HEIGHT_THRESHOLD && smoothBackward > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
            terrainSlope = clamp((smoothForward - smoothBackward) / (2.0 * SNOW_SMOOTH_GRADIENT_STEP), -1.0, 1.0);
        }
        fragColor = vec4(leeHeight, terrainSlope, 0.0, 0.0);
        return;
    }
    fragColor = vec4(scanned, 0.0, 0.0);
}
