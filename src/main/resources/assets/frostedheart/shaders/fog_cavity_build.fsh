// FHShaders prepends the version line.
// Builds the wind-direction-free fog cavity enclosure map on a 512 by 512 world-aligned grid (1 block per texel), as
// FogCavity.scan describes it.
// Passes 0..5 scan the cardinal axes (+X, -X, +Z, -Z in RGBA) up to 32 blocks at FOG_CAVITY_ENCLOSURE_SLOPE, writing
// max(e_X, e_Z) with a 3-texel perpendicular minimum into the cardinal staging R32F target on pass 5.
// Passes 6..11 scan the diagonal axes (+X+Z, -X-Z, +X-Z, -X+Z in RGBA) up to 23 diagonal steps (32.5 blocks), and pass
// 11 writes max(cardinal, max(e_D1, e_D2)) into the published R32F cavity target.
uniform sampler2D sourceMap;
uniform isampler2D noLeavesHeights;
uniform sampler2D cardinalMap;
uniform int passIndex;
uniform int stride;
uniform ivec2 cavityMin;
uniform ivec2 farHeightsMin;
out vec4 fragColor;

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

float scanChannel(ivec2 pos, ivec2 offset, int channel, float drop) {
    vec4 current = texelFetch(sourceMap, pos, 0);
    float value = channel == 0 ? current.r : (channel == 1 ? current.g : (channel == 2 ? current.b : current.a));
    ivec2 samplePos = pos + offset;
    if (samplePos.x >= 0 && samplePos.x < FOG_CAVITY_MAP_SIZE
            && samplePos.y >= 0 && samplePos.y < FOG_CAVITY_MAP_SIZE) {
        vec4 neighborVec = texelFetch(sourceMap, samplePos, 0);
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

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 world = cavityMin + texel;
    if (passIndex == 0) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE;
        fragColor = vec4(
                sampleWithDrop(world + ivec2(1, 0), drop),
                sampleWithDrop(world + ivec2(-1, 0), drop),
                sampleWithDrop(world + ivec2(0, 1), drop),
                sampleWithDrop(world + ivec2(0, -1), drop));
        return;
    }
    if (passIndex < FOG_CAVITY_CARDINAL_FINAL_PASS) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE * float(stride);
        fragColor = vec4(
                scanChannel(texel, ivec2(stride, 0), 0, drop),
                scanChannel(texel, ivec2(-stride, 0), 1, drop),
                scanChannel(texel, ivec2(0, stride), 2, drop),
                scanChannel(texel, ivec2(0, -stride), 3, drop));
        return;
    }
    if (passIndex == FOG_CAVITY_CARDINAL_FINAL_PASS) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE * float(stride);
        ivec2 zMinus = clampTexel(texel + ivec2(0, -1));
        ivec2 zPlus = clampTexel(texel + ivec2(0, 1));
        float plusX = min(scanChannel(texel, ivec2(stride, 0), 0, drop),
                min(scanChannel(zMinus, ivec2(stride, 0), 0, drop),
                        scanChannel(zPlus, ivec2(stride, 0), 0, drop)));
        float minusX = min(scanChannel(texel, ivec2(-stride, 0), 1, drop),
                min(scanChannel(zMinus, ivec2(-stride, 0), 1, drop),
                        scanChannel(zPlus, ivec2(-stride, 0), 1, drop)));
        ivec2 xMinus = clampTexel(texel + ivec2(-1, 0));
        ivec2 xPlus = clampTexel(texel + ivec2(1, 0));
        float plusZ = min(scanChannel(texel, ivec2(0, stride), 2, drop),
                min(scanChannel(xMinus, ivec2(0, stride), 2, drop),
                        scanChannel(xPlus, ivec2(0, stride), 2, drop)));
        float minusZ = min(scanChannel(texel, ivec2(0, -stride), 3, drop),
                min(scanChannel(xMinus, ivec2(0, -stride), 3, drop),
                        scanChannel(xPlus, ivec2(0, -stride), 3, drop)));
        float cardinal = max(min(plusX, minusX), min(plusZ, minusZ));
        fragColor = vec4(cardinal, 0.0, 0.0, 0.0);
        return;
    }
    float diagSlope = FOG_CAVITY_ENCLOSURE_SLOPE * FOG_CAVITY_DIAGONAL_STEP;
    if (passIndex == FOG_CAVITY_DIAGONAL_FIRST_PASS) {
        fragColor = vec4(
                sampleWithDrop(world + ivec2(1, 1), diagSlope),
                sampleWithDrop(world + ivec2(-1, -1), diagSlope),
                sampleWithDrop(world + ivec2(1, -1), diagSlope),
                sampleWithDrop(world + ivec2(-1, 1), diagSlope));
        return;
    }
    float diagDrop = diagSlope * float(stride);
    if (passIndex < FOG_CAVITY_DIAGONAL_FINAL_PASS) {
        fragColor = vec4(
                scanChannel(texel, ivec2(stride, stride), 0, diagDrop),
                scanChannel(texel, ivec2(-stride, -stride), 1, diagDrop),
                scanChannel(texel, ivec2(stride, -stride), 2, diagDrop),
                scanChannel(texel, ivec2(-stride, stride), 3, diagDrop));
        return;
    }
    ivec2 a1 = clampTexel(texel + ivec2(1, -1));
    ivec2 a2 = clampTexel(texel + ivec2(-1, 1));
    float pp = min(scanChannel(texel, ivec2(stride, stride), 0, diagDrop),
            min(scanChannel(a1, ivec2(stride, stride), 0, diagDrop),
                    scanChannel(a2, ivec2(stride, stride), 0, diagDrop)));
    float mm = min(scanChannel(texel, ivec2(-stride, -stride), 1, diagDrop),
            min(scanChannel(a1, ivec2(-stride, -stride), 1, diagDrop),
                    scanChannel(a2, ivec2(-stride, -stride), 1, diagDrop)));
    ivec2 b1 = clampTexel(texel + ivec2(1, 1));
    ivec2 b2 = clampTexel(texel + ivec2(-1, -1));
    float pm = min(scanChannel(texel, ivec2(stride, -stride), 2, diagDrop),
            min(scanChannel(b1, ivec2(stride, -stride), 2, diagDrop),
                    scanChannel(b2, ivec2(stride, -stride), 2, diagDrop)));
    float mp = min(scanChannel(texel, ivec2(-stride, stride), 3, diagDrop),
            min(scanChannel(b1, ivec2(-stride, stride), 3, diagDrop),
                    scanChannel(b2, ivec2(-stride, stride), 3, diagDrop)));
    float diagMax = max(min(pp, mm), min(pm, mp));
    float cardinal = texelFetch(cardinalMap, texel, 0).r;
    fragColor = vec4(max(cardinal, diagMax), 0.0, 0.0, 0.0);
}
