// FHShaders prepends the version line.
// Builds the wind-aligned snowfall lee map into an R32F 1024 by 1024 target, as SnowLee.scan describes it.
// Pass 0 resamples the no-leaves far heights at t = 1 upwind using two taps along the wind (so thin diagonal walls are
// not skipped without dilating across the wind) and subtracts leeSlope. Passes 1 to 9 run the doubling max-plus scan
// along x without wrapping across the map edge. The final pass takes the minimum over three texels across y so
// obstacles narrower than 3 blocks across the wind cast no lee while wider walls keep theirs at every wind angle.
uniform sampler2D sourceMap;
uniform isampler2D noLeavesHeights;
uniform int passIndex;
uniform int stride;
uniform int applyAcrossMin;
uniform ivec2 leeAnchor;
uniform ivec2 farHeightsMin;
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

float scanTexel(ivec2 pos) {
    float value = texelFetch(sourceMap, pos, 0).r;
    if (pos.x >= stride) {
        float upwind = texelFetch(sourceMap, ivec2(pos.x - stride, pos.y), 0).r;
        if (upwind > SNOW_LEE_NO_HEIGHT_THRESHOLD) {
            value = max(value, upwind - leeSlope * float(stride));
        }
    }
    return value;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float result;
    if (passIndex == 0) {
        float u = float(texel.x) - SNOW_LEE_HALF_SIZE + 0.5;
        float s = float(texel.y) - SNOW_LEE_HALF_SIZE + 0.5;
        float upwindU = u - 1.0;
        float height = max(sampleNoLeavesHeight(upwindU - SNOW_LEE_SAMPLE_OFFSET, s),
                sampleNoLeavesHeight(upwindU + SNOW_LEE_SAMPLE_OFFSET, s));
        result = height > SNOW_LEE_NO_HEIGHT_THRESHOLD ? height - leeSlope : SNOW_LEE_NO_HEIGHT;
    } else if (applyAcrossMin != 0) {
        float center = scanTexel(texel);
        float minus = scanTexel(ivec2(texel.x, max(texel.y - 1, 0)));
        float plus = scanTexel(ivec2(texel.x, min(texel.y + 1, SNOW_LEE_MAP_SIZE - 1)));
        result = min(center, min(minus, plus));
    } else {
        result = scanTexel(texel);
    }
    fragColor = vec4(result, 0.0, 0.0, 0.0);
}
