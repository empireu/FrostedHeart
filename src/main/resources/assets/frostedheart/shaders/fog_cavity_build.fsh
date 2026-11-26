// FHShaders prepends the version line.
// Builds the wind-direction-free flat fog cavity enclosure map on a 512 by 512 world-aligned grid (1 block per texel),
// as FogCavity.scan describes it.
// Intermediate work passes write sloped wall maxima to outSloped (RGBA32F) and unsloped raw wall maxima to outRaw
// (RGBA16F) in parallel by MRT.
// Passes 0..5 scan the cardinal axes (+X, -X, +Z, -Z in RGBA) up to 32 blocks at FOG_CAVITY_ENCLOSURE_SLOPE, writing
// the winning axis's (flat surface, lower rim) with a 3-texel perpendicular minimum on both sloped and raw walls into
// the cardinal staging RG32F target on pass 5.
// Passes 6..11 scan the diagonal axes (+X+Z, -X-Z, +X-Z, -X+Z in RGBA) up to 23 diagonal steps (32.5 blocks), and pass
// 11 writes the winning (flat surface, lower rim) across all four axes into the published RG32F cavity target.
uniform sampler2D sourceMap;
uniform sampler2D sourceRawMap;
uniform isampler2D noLeavesHeights;
uniform sampler2D cardinalMap;
uniform int passIndex;
uniform int stride;
uniform ivec2 cavityMin;
uniform ivec2 farHeightsMin;
out vec4 outSloped;
out vec4 outRaw;

const int FOG_CAVITY_MAP_SIZE = 512;
const int FOG_CAVITY_FAR_HEIGHTS_SIZE = 1024;
const int FOG_CAVITY_FAR_HEIGHTS_MASK = 1023;
const int FOG_CAVITY_UNKNOWN_HEIGHT = -32768;
const float FOG_CAVITY_NO_HEIGHT = -32768.0;
const float FOG_CAVITY_NO_HEIGHT_THRESHOLD = -30000.0;
const float FOG_CAVITY_ENCLOSURE_SLOPE = 0.65;
const float FOG_CAVITY_DIAGONAL_STEP = 1.41421356;
const int FOG_CAVITY_CARDINAL_FINAL_PASS = 5;
const int FOG_CAVITY_DIAGONAL_FIRST_PASS = 6;
const int FOG_CAVITY_DIAGONAL_FINAL_PASS = 11;

float sampleWithDrop(ivec2 column, float drop) {
    if (any(lessThan(column, farHeightsMin))
            || any(greaterThanEqual(column, farHeightsMin + ivec2(FOG_CAVITY_FAR_HEIGHTS_SIZE)))) {
        return FOG_CAVITY_NO_HEIGHT;
    }
    int height = texelFetch(noLeavesHeights, column & ivec2(FOG_CAVITY_FAR_HEIGHTS_MASK), 0).r;
    if (height <= FOG_CAVITY_UNKNOWN_HEIGHT) {
        return FOG_CAVITY_NO_HEIGHT;
    }
    float value = float(height) - drop;
    return value > FOG_CAVITY_NO_HEIGHT_THRESHOLD ? value : FOG_CAVITY_NO_HEIGHT;
}

float scanChannel(sampler2D sourceTex, ivec2 pos, ivec2 offset, int channel, float drop) {
    vec4 current = texelFetch(sourceTex, pos, 0);
    float value = channel == 0 ? current.r : (channel == 1 ? current.g : (channel == 2 ? current.b : current.a));
    ivec2 samplePos = pos + offset;
    if (samplePos.x >= 0 && samplePos.x < FOG_CAVITY_MAP_SIZE
            && samplePos.y >= 0 && samplePos.y < FOG_CAVITY_MAP_SIZE) {
        vec4 neighborVec = texelFetch(sourceTex, samplePos, 0);
        float neighbor = channel == 0 ? neighborVec.r
                : (channel == 1 ? neighborVec.g : (channel == 2 ? neighborVec.b : neighborVec.a));
        if (neighbor > FOG_CAVITY_NO_HEIGHT_THRESHOLD) {
            value = max(value, neighbor - drop);
        }
    }
    return value;
}

ivec2 clampTexel(ivec2 pos) {
    return clamp(pos, ivec2(0), ivec2(FOG_CAVITY_MAP_SIZE - 1));
}

vec2 combineAxis(float slopedPos, float slopedNeg, float rawPos, float rawNeg) {
    if (!(slopedPos > FOG_CAVITY_NO_HEIGHT_THRESHOLD) || !(slopedNeg > FOG_CAVITY_NO_HEIGHT_THRESHOLD)
            || !(rawPos > FOG_CAVITY_NO_HEIGHT_THRESHOLD) || !(rawNeg > FOG_CAVITY_NO_HEIGHT_THRESHOLD)) {
        return vec2(FOG_CAVITY_NO_HEIGHT);
    }
    float lowerRim = min(rawPos, rawNeg);
    float surface = min(lowerRim, 0.5 * (slopedPos + slopedNeg));
    return vec2(surface, lowerRim);
}

vec2 pickAxis(vec2 first, vec2 second) {
    if (second.x > first.x) {
        return second;
    }
    if (first.x > second.x) {
        return first;
    }
    return vec2(first.x, max(first.y, second.y));
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 world = cavityMin + texel;
    if (passIndex == 0) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE;
        outSloped = vec4(
                sampleWithDrop(world + ivec2(1, 0), drop),
                sampleWithDrop(world + ivec2(-1, 0), drop),
                sampleWithDrop(world + ivec2(0, 1), drop),
                sampleWithDrop(world + ivec2(0, -1), drop));
        outRaw = vec4(
                sampleWithDrop(world + ivec2(1, 0), 0.0),
                sampleWithDrop(world + ivec2(-1, 0), 0.0),
                sampleWithDrop(world + ivec2(0, 1), 0.0),
                sampleWithDrop(world + ivec2(0, -1), 0.0));
        return;
    }
    if (passIndex < FOG_CAVITY_CARDINAL_FINAL_PASS) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE * float(stride);
        outSloped = vec4(
                scanChannel(sourceMap, texel, ivec2(stride, 0), 0, drop),
                scanChannel(sourceMap, texel, ivec2(-stride, 0), 1, drop),
                scanChannel(sourceMap, texel, ivec2(0, stride), 2, drop),
                scanChannel(sourceMap, texel, ivec2(0, -stride), 3, drop));
        outRaw = vec4(
                scanChannel(sourceRawMap, texel, ivec2(stride, 0), 0, 0.0),
                scanChannel(sourceRawMap, texel, ivec2(-stride, 0), 1, 0.0),
                scanChannel(sourceRawMap, texel, ivec2(0, stride), 2, 0.0),
                scanChannel(sourceRawMap, texel, ivec2(0, -stride), 3, 0.0));
        return;
    }
    if (passIndex == FOG_CAVITY_CARDINAL_FINAL_PASS) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE * float(stride);
        ivec2 zMinus = clampTexel(texel + ivec2(0, -1));
        ivec2 zPlus = clampTexel(texel + ivec2(0, 1));
        float plusX = min(scanChannel(sourceMap, texel, ivec2(stride, 0), 0, drop),
                min(scanChannel(sourceMap, zMinus, ivec2(stride, 0), 0, drop),
                        scanChannel(sourceMap, zPlus, ivec2(stride, 0), 0, drop)));
        float minusX = min(scanChannel(sourceMap, texel, ivec2(-stride, 0), 1, drop),
                min(scanChannel(sourceMap, zMinus, ivec2(-stride, 0), 1, drop),
                        scanChannel(sourceMap, zPlus, ivec2(-stride, 0), 1, drop)));
        float rawPlusX = min(scanChannel(sourceRawMap, texel, ivec2(stride, 0), 0, 0.0),
                min(scanChannel(sourceRawMap, zMinus, ivec2(stride, 0), 0, 0.0),
                        scanChannel(sourceRawMap, zPlus, ivec2(stride, 0), 0, 0.0)));
        float rawMinusX = min(scanChannel(sourceRawMap, texel, ivec2(-stride, 0), 1, 0.0),
                min(scanChannel(sourceRawMap, zMinus, ivec2(-stride, 0), 1, 0.0),
                        scanChannel(sourceRawMap, zPlus, ivec2(-stride, 0), 1, 0.0)));
        ivec2 xMinus = clampTexel(texel + ivec2(-1, 0));
        ivec2 xPlus = clampTexel(texel + ivec2(1, 0));
        float plusZ = min(scanChannel(sourceMap, texel, ivec2(0, stride), 2, drop),
                min(scanChannel(sourceMap, xMinus, ivec2(0, stride), 2, drop),
                        scanChannel(sourceMap, xPlus, ivec2(0, stride), 2, drop)));
        float minusZ = min(scanChannel(sourceMap, texel, ivec2(0, -stride), 3, drop),
                min(scanChannel(sourceMap, xMinus, ivec2(0, -stride), 3, drop),
                        scanChannel(sourceMap, xPlus, ivec2(0, -stride), 3, drop)));
        float rawPlusZ = min(scanChannel(sourceRawMap, texel, ivec2(0, stride), 2, 0.0),
                min(scanChannel(sourceRawMap, xMinus, ivec2(0, stride), 2, 0.0),
                        scanChannel(sourceRawMap, xPlus, ivec2(0, stride), 2, 0.0)));
        float rawMinusZ = min(scanChannel(sourceRawMap, texel, ivec2(0, -stride), 3, 0.0),
                min(scanChannel(sourceRawMap, xMinus, ivec2(0, -stride), 3, 0.0),
                        scanChannel(sourceRawMap, xPlus, ivec2(0, -stride), 3, 0.0)));
        vec2 axisX = combineAxis(plusX, minusX, rawPlusX, rawMinusX);
        vec2 axisZ = combineAxis(plusZ, minusZ, rawPlusZ, rawMinusZ);
        vec2 cardinal = pickAxis(axisX, axisZ);
        outSloped = vec4(cardinal, 0.0, 0.0);
        outRaw = outSloped;
        return;
    }
    float diagSlope = FOG_CAVITY_ENCLOSURE_SLOPE * FOG_CAVITY_DIAGONAL_STEP;
    if (passIndex == FOG_CAVITY_DIAGONAL_FIRST_PASS) {
        outSloped = vec4(
                sampleWithDrop(world + ivec2(1, 1), diagSlope),
                sampleWithDrop(world + ivec2(-1, -1), diagSlope),
                sampleWithDrop(world + ivec2(1, -1), diagSlope),
                sampleWithDrop(world + ivec2(-1, 1), diagSlope));
        outRaw = vec4(
                sampleWithDrop(world + ivec2(1, 1), 0.0),
                sampleWithDrop(world + ivec2(-1, -1), 0.0),
                sampleWithDrop(world + ivec2(1, -1), 0.0),
                sampleWithDrop(world + ivec2(-1, 1), 0.0));
        return;
    }
    float diagDrop = diagSlope * float(stride);
    if (passIndex < FOG_CAVITY_DIAGONAL_FINAL_PASS) {
        outSloped = vec4(
                scanChannel(sourceMap, texel, ivec2(stride, stride), 0, diagDrop),
                scanChannel(sourceMap, texel, ivec2(-stride, -stride), 1, diagDrop),
                scanChannel(sourceMap, texel, ivec2(stride, -stride), 2, diagDrop),
                scanChannel(sourceMap, texel, ivec2(-stride, stride), 3, diagDrop));
        outRaw = vec4(
                scanChannel(sourceRawMap, texel, ivec2(stride, stride), 0, 0.0),
                scanChannel(sourceRawMap, texel, ivec2(-stride, -stride), 1, 0.0),
                scanChannel(sourceRawMap, texel, ivec2(stride, -stride), 2, 0.0),
                scanChannel(sourceRawMap, texel, ivec2(-stride, stride), 3, 0.0));
        return;
    }
    ivec2 a1 = clampTexel(texel + ivec2(1, -1));
    ivec2 a2 = clampTexel(texel + ivec2(-1, 1));
    float pp = min(scanChannel(sourceMap, texel, ivec2(stride, stride), 0, diagDrop),
            min(scanChannel(sourceMap, a1, ivec2(stride, stride), 0, diagDrop),
                    scanChannel(sourceMap, a2, ivec2(stride, stride), 0, diagDrop)));
    float mm = min(scanChannel(sourceMap, texel, ivec2(-stride, -stride), 1, diagDrop),
            min(scanChannel(sourceMap, a1, ivec2(-stride, -stride), 1, diagDrop),
                    scanChannel(sourceMap, a2, ivec2(-stride, -stride), 1, diagDrop)));
    float rawPP = min(scanChannel(sourceRawMap, texel, ivec2(stride, stride), 0, 0.0),
            min(scanChannel(sourceRawMap, a1, ivec2(stride, stride), 0, 0.0),
                    scanChannel(sourceRawMap, a2, ivec2(stride, stride), 0, 0.0)));
    float rawMM = min(scanChannel(sourceRawMap, texel, ivec2(-stride, -stride), 1, 0.0),
            min(scanChannel(sourceRawMap, a1, ivec2(-stride, -stride), 1, 0.0),
                    scanChannel(sourceRawMap, a2, ivec2(-stride, -stride), 1, 0.0)));
    ivec2 b1 = clampTexel(texel + ivec2(1, 1));
    ivec2 b2 = clampTexel(texel + ivec2(-1, -1));
    float pm = min(scanChannel(sourceMap, texel, ivec2(stride, -stride), 2, diagDrop),
            min(scanChannel(sourceMap, b1, ivec2(stride, -stride), 2, diagDrop),
                    scanChannel(sourceMap, b2, ivec2(stride, -stride), 2, diagDrop)));
    float mp = min(scanChannel(sourceMap, texel, ivec2(-stride, stride), 3, diagDrop),
            min(scanChannel(sourceMap, b1, ivec2(-stride, stride), 3, diagDrop),
                    scanChannel(sourceMap, b2, ivec2(-stride, stride), 3, diagDrop)));
    float rawPM = min(scanChannel(sourceRawMap, texel, ivec2(stride, -stride), 2, 0.0),
            min(scanChannel(sourceRawMap, b1, ivec2(stride, -stride), 2, 0.0),
                    scanChannel(sourceRawMap, b2, ivec2(stride, -stride), 2, 0.0)));
    float rawMP = min(scanChannel(sourceRawMap, texel, ivec2(-stride, stride), 3, 0.0),
            min(scanChannel(sourceRawMap, b1, ivec2(-stride, stride), 3, 0.0),
                    scanChannel(sourceRawMap, b2, ivec2(-stride, stride), 3, 0.0)));
    vec2 axisD1 = combineAxis(pp, mm, rawPP, rawMM);
    vec2 axisD2 = combineAxis(pm, mp, rawPM, rawMP);
    vec2 diagBest = pickAxis(axisD1, axisD2);
    vec2 cardinal = texelFetch(cardinalMap, texel, 0).rg;
    outSloped = vec4(pickAxis(cardinal, diagBest), 0.0, 0.0);
    outRaw = outSloped;
}
