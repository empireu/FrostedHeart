// FHShaders prepends the version line.
// Builds the wind-direction-free flat fog cavity enclosure map on a 512 by 512 world-aligned grid (1 block per texel),
// as FogCavity.scan describes it.
// Intermediate work passes write sloped wall maxima to outSloped (RGBA32F) and the distance in steps of each sloped
// maximum to outDistance (RGBA16F) in parallel by MRT, so a wall's own height is its sloped maximum plus the slope
// times its distance.
// Passes 0..5 scan the cardinal axes (+X, -X, +Z, -Z in RGBA) up to 32 blocks at FOG_CAVITY_ENCLOSURE_SLOPE, writing
// the winning axis's (flat surface, lower wall lip) with a 3-texel perpendicular minimum of the walls into the cardinal
// staging RG32F target on pass 5.
// Passes 6..11 scan the diagonal axes (+X+Z, -X-Z, +X-Z, -X+Z in RGBA) up to 23 diagonal steps (32.5 blocks), and pass
// 11 writes the winning (flat surface, lower wall lip) across all four axes into the published RG32F cavity target.
uniform sampler2D sourceMap;
uniform sampler2D sourceDistanceMap;
uniform isampler2D noLeavesHeights;
uniform sampler2D cardinalMap;
uniform int passIndex;
uniform int stride;
uniform ivec2 cavityMin;
uniform ivec2 farHeightsMin;
out vec4 outSloped;
out vec4 outDistance;

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

float channelOf(vec4 value, int channel) {
    return channel == 0 ? value.r : (channel == 1 ? value.g : (channel == 2 ? value.b : value.a));
}

// The sloped wall maximum (x) of one direction and its distance in steps (y), extended by the wall stride steps
// further along offset. The nearer wall wins a tie:
vec2 scanWall(ivec2 pos, ivec2 offset, int channel, float drop) {
    vec2 wall = vec2(channelOf(texelFetch(sourceMap, pos, 0), channel),
            channelOf(texelFetch(sourceDistanceMap, pos, 0), channel));
    ivec2 samplePos = pos + offset;
    if (samplePos.x >= 0 && samplePos.x < FOG_CAVITY_MAP_SIZE
            && samplePos.y >= 0 && samplePos.y < FOG_CAVITY_MAP_SIZE) {
        float neighbor = channelOf(texelFetch(sourceMap, samplePos, 0), channel);
        if (neighbor > FOG_CAVITY_NO_HEIGHT_THRESHOLD && neighbor - drop > wall.x) {
            wall = vec2(neighbor - drop,
                    channelOf(texelFetch(sourceDistanceMap, samplePos, 0), channel) + float(stride));
        }
    }
    return wall;
}

// The wall with the lower sloped maximum, or the nearer one when both are equal, as FogCavity.lowerWall:
vec2 lowerWall(vec2 first, vec2 second) {
    if (second.x < first.x || (second.x == first.x && second.y < first.y)) {
        return second;
    }
    return first;
}

ivec2 clampTexel(ivec2 pos) {
    return clamp(pos, ivec2(0), ivec2(FOG_CAVITY_MAP_SIZE - 1));
}

// The flat surface (x) and the lower lip of the two walls (y) of one axis, where stepSlope is the drop per step:
vec2 combineAxis(vec2 positive, vec2 negative, float stepSlope) {
    if (!(positive.x > FOG_CAVITY_NO_HEIGHT_THRESHOLD) || !(negative.x > FOG_CAVITY_NO_HEIGHT_THRESHOLD)) {
        return vec2(FOG_CAVITY_NO_HEIGHT);
    }
    float lowerLip = min(positive.x + stepSlope * positive.y, negative.x + stepSlope * negative.y);
    float surface = min(lowerLip, 0.5 * (positive.x + negative.x));
    return vec2(surface, lowerLip);
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
        outDistance = vec4(1.0);
        return;
    }
    if (passIndex < FOG_CAVITY_CARDINAL_FINAL_PASS) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE * float(stride);
        vec2 plusX = scanWall(texel, ivec2(stride, 0), 0, drop);
        vec2 minusX = scanWall(texel, ivec2(-stride, 0), 1, drop);
        vec2 plusZ = scanWall(texel, ivec2(0, stride), 2, drop);
        vec2 minusZ = scanWall(texel, ivec2(0, -stride), 3, drop);
        outSloped = vec4(plusX.x, minusX.x, plusZ.x, minusZ.x);
        outDistance = vec4(plusX.y, minusX.y, plusZ.y, minusZ.y);
        return;
    }
    if (passIndex == FOG_CAVITY_CARDINAL_FINAL_PASS) {
        float drop = FOG_CAVITY_ENCLOSURE_SLOPE * float(stride);
        ivec2 zMinus = clampTexel(texel + ivec2(0, -1));
        ivec2 zPlus = clampTexel(texel + ivec2(0, 1));
        vec2 plusX = lowerWall(scanWall(texel, ivec2(stride, 0), 0, drop),
                lowerWall(scanWall(zMinus, ivec2(stride, 0), 0, drop), scanWall(zPlus, ivec2(stride, 0), 0, drop)));
        vec2 minusX = lowerWall(scanWall(texel, ivec2(-stride, 0), 1, drop),
                lowerWall(scanWall(zMinus, ivec2(-stride, 0), 1, drop), scanWall(zPlus, ivec2(-stride, 0), 1, drop)));
        ivec2 xMinus = clampTexel(texel + ivec2(-1, 0));
        ivec2 xPlus = clampTexel(texel + ivec2(1, 0));
        vec2 plusZ = lowerWall(scanWall(texel, ivec2(0, stride), 2, drop),
                lowerWall(scanWall(xMinus, ivec2(0, stride), 2, drop), scanWall(xPlus, ivec2(0, stride), 2, drop)));
        vec2 minusZ = lowerWall(scanWall(texel, ivec2(0, -stride), 3, drop),
                lowerWall(scanWall(xMinus, ivec2(0, -stride), 3, drop), scanWall(xPlus, ivec2(0, -stride), 3, drop)));
        vec2 axisX = combineAxis(plusX, minusX, FOG_CAVITY_ENCLOSURE_SLOPE);
        vec2 axisZ = combineAxis(plusZ, minusZ, FOG_CAVITY_ENCLOSURE_SLOPE);
        vec2 cardinal = pickAxis(axisX, axisZ);
        outSloped = vec4(cardinal, 0.0, 0.0);
        outDistance = outSloped;
        return;
    }
    float diagSlope = FOG_CAVITY_ENCLOSURE_SLOPE * FOG_CAVITY_DIAGONAL_STEP;
    if (passIndex == FOG_CAVITY_DIAGONAL_FIRST_PASS) {
        outSloped = vec4(
                sampleWithDrop(world + ivec2(1, 1), diagSlope),
                sampleWithDrop(world + ivec2(-1, -1), diagSlope),
                sampleWithDrop(world + ivec2(1, -1), diagSlope),
                sampleWithDrop(world + ivec2(-1, 1), diagSlope));
        outDistance = vec4(1.0);
        return;
    }
    float diagDrop = diagSlope * float(stride);
    if (passIndex < FOG_CAVITY_DIAGONAL_FINAL_PASS) {
        vec2 pp = scanWall(texel, ivec2(stride, stride), 0, diagDrop);
        vec2 mm = scanWall(texel, ivec2(-stride, -stride), 1, diagDrop);
        vec2 pm = scanWall(texel, ivec2(stride, -stride), 2, diagDrop);
        vec2 mp = scanWall(texel, ivec2(-stride, stride), 3, diagDrop);
        outSloped = vec4(pp.x, mm.x, pm.x, mp.x);
        outDistance = vec4(pp.y, mm.y, pm.y, mp.y);
        return;
    }
    ivec2 a1 = clampTexel(texel + ivec2(1, -1));
    ivec2 a2 = clampTexel(texel + ivec2(-1, 1));
    vec2 pp = lowerWall(scanWall(texel, ivec2(stride, stride), 0, diagDrop),
            lowerWall(scanWall(a1, ivec2(stride, stride), 0, diagDrop),
                    scanWall(a2, ivec2(stride, stride), 0, diagDrop)));
    vec2 mm = lowerWall(scanWall(texel, ivec2(-stride, -stride), 1, diagDrop),
            lowerWall(scanWall(a1, ivec2(-stride, -stride), 1, diagDrop),
                    scanWall(a2, ivec2(-stride, -stride), 1, diagDrop)));
    ivec2 b1 = clampTexel(texel + ivec2(1, 1));
    ivec2 b2 = clampTexel(texel + ivec2(-1, -1));
    vec2 pm = lowerWall(scanWall(texel, ivec2(stride, -stride), 2, diagDrop),
            lowerWall(scanWall(b1, ivec2(stride, -stride), 2, diagDrop),
                    scanWall(b2, ivec2(stride, -stride), 2, diagDrop)));
    vec2 mp = lowerWall(scanWall(texel, ivec2(-stride, stride), 3, diagDrop),
            lowerWall(scanWall(b1, ivec2(-stride, stride), 3, diagDrop),
                    scanWall(b2, ivec2(-stride, stride), 3, diagDrop)));
    vec2 axisD1 = combineAxis(pp, mm, diagSlope);
    vec2 axisD2 = combineAxis(pm, mp, diagSlope);
    vec2 diagBest = pickAxis(axisD1, axisD2);
    vec2 cardinal = texelFetch(cardinalMap, texel, 0).rg;
    outSloped = vec4(pickAxis(cardinal, diagBest), 0.0, 0.0);
    outDistance = outSloped;
}
