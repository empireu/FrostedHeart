// FHShaders prepends the version line, weather_fog.glsl and snow_walk.glsl.
// Walks the fog of snow and blizzards at a lower resolution than the frame: each output pixel stands for a block of
// divisor by divisor pixels of the main target, from which it keeps the minimum depth or the maximum one in a
// checkerboard, so that thin geometry survives in one of the two. It writes the ratio of the walked optical depth of
// that ray to the analytic optical depth of the same ray, and the distance of the ray, and weather_fog_upsample.fsh
// fills each pixel of the frame in from four of these. A texel of the sky writes SKY_DISTANCE as its distance and the
// ratio of its sky walk to the analytic depth of the height fog to infinity along its ray, since the sky has no scene
// point whose distance the fog could agree on.
// The share of the snow along the segment is the snow field's near the eye and the height of each column's highest
// block beyond it, as snow_walk.glsl walks them, so a room stays clear where the whole segment from the eye to the scene
// point stays under its roof, and a porch holds the fog back by the first open air under it.
// The depth of the main target and its size in texels:
uniform sampler2D sceneDepth;
uniform vec2 screenSize;
// The view projection translated by the eye offset and inverted, so a position it gives is the offset from the eye:
uniform mat4 inverseViewProjection;
// Which parts of the walk run, as snowWalkOpticalDepth reads them:
uniform int partMask;
// The divisor of the resolution that the fog is walked at, and the size of the full-resolution target in texels:
uniform int divisor;
uniform ivec2 fullSize;
// Whether to answer the self test instead of drawing, and the offsets of its rays, one per texel of its 64 texel wide
// target. Mode 1 walks the ray to its scene point and mode 2 walks its sky:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 fragColor;

// The distance that a sample of the sky writes, as FogUpsample.SKY_DISTANCE:
const float SKY_DISTANCE = -1.0;
// The smallest analytic depth that the ratio divides by, as FogUpsample.MINIMUM_ANALYTIC_DEPTH:
const float FOG_UPSAMPLE_MINIMUM_ANALYTIC_DEPTH = 1.0e-6;

// The ratio of the walked depth of a sample to the analytic depth of the same ray, as FogUpsample.ratio:
float fogUpsampleRatio(float walkedDepth, float analyticDepth) {
    if (!(analyticDepth >= FOG_UPSAMPLE_MINIMUM_ANALYTIC_DEPTH)) {
        return 1.0;
    }
    return walkedDepth / analyticDepth;
}

// Writes the sky texel of a pixel: the distance of the sky and the ratio of its sky walk to the analytic depth of the
// height fog to infinity along its ray, whose direction comes from the far plane of the pixel as the structure debug
// view rebuilds it. A ray whose analytic depth is at the cap is opaque, so its ratio is 1, and a far plane that gives no
// direction writes the ratio 1 without walking.
void writeSkyRatio(ivec2 pixel) {
    vec3 ndc = vec3((vec2(pixel) + vec2(0.5)) / screenSize * 2.0 - 1.0, 1.0);
    vec4 position = inverseViewProjection * vec4(ndc, 1.0);
    vec3 farOffset = position.xyz / position.w;
    float farLength = length(farOffset);
    if (!(farLength > 0.0) || isnan(farLength) || isinf(farLength)) {
        fragColor = vec4(1.0, SKY_DISTANCE, 0.0, 1.0);
        return;
    }
    vec3 direction = farOffset / farLength;
    float analyticDepth = fogSkyOpticalDepth(eyeExtinction, direction.y, heightScale);
    if (analyticDepth >= FOG_MAXIMUM_OPTICAL_DEPTH) {
        fragColor = vec4(1.0, SKY_DISTANCE, 0.0, 1.0);
        return;
    }
    int cellsWalked;
    int columnsWalked;
    int coarseSteps;
    float farStop;
    float walkedDepth = snowWalkSkyOpticalDepth(direction, structureRange, partMask, cellsWalked, columnsWalked,
            farStop, coarseSteps);
    fragColor = vec4(fogUpsampleRatio(walkedDepth, analyticDepth), SKY_DISTANCE, 0.0, 1.0);
}

void main() {
    if (selfTest != 0) {
        int cellsWalked;
        int columnsWalked;
        int coarseSteps;
        float farStop;
        float tau;
        if (selfTest == 2) {
            tau = snowWalkSkyOpticalDepth(normalize(selfTestOffsets[int(gl_FragCoord.x)]), structureRange, partMask,
                    cellsWalked, columnsWalked, farStop, coarseSteps);
        } else {
            tau = snowWalkOpticalDepth(selfTestOffsets[int(gl_FragCoord.x)], partMask, cellsWalked, columnsWalked,
                    farStop, coarseSteps);
        }
        fragColor = vec4(tau, float(cellsWalked), float(columnsWalked), farStop);
        return;
    }
    ivec2 lowPixel = ivec2(gl_FragCoord.xy);
    ivec2 firstPixel = lowPixel * divisor;
    ivec2 lastPixel = fullSize - ivec2(1);
    bool takesMaximum = ((lowPixel.x + lowPixel.y) & 1) == 1;
    float keptDepth = takesMaximum ? 0.0 : 1.0;
    ivec2 keptPixel = min(firstPixel, lastPixel);
    for (int offsetY = 0; offsetY < divisor; offsetY++) {
        for (int offsetX = 0; offsetX < divisor; offsetX++) {
            ivec2 pixel = min(firstPixel + ivec2(offsetX, offsetY), lastPixel);
            float depth = texelFetch(sceneDepth, pixel, 0).r;
            if (takesMaximum ? depth > keptDepth : depth < keptDepth) {
                keptDepth = depth;
                keptPixel = pixel;
            }
        }
    }
    if (keptDepth >= 1.0) {
        writeSkyRatio(keptPixel);
        return;
    }
    vec3 ndc = vec3((vec2(keptPixel) + vec2(0.5)) / screenSize * 2.0 - 1.0, keptDepth * 2.0 - 1.0);
    vec4 position = inverseViewProjection * vec4(ndc, 1.0);
    vec3 offset = position.xyz / position.w;
    // A depth at the near plane can give no scene point, which is stored as sky so that no pixel agrees with it:
    if (any(isnan(offset)) || any(isinf(offset))) {
        fragColor = vec4(1.0, SKY_DISTANCE, 0.0, 1.0);
        return;
    }
    float offsetLength = length(offset);
    float analyticDepth = fogOpticalDepth(eyeExtinction, offsetLength, offset.y, heightScale);
    int cellsWalked;
    int columnsWalked;
    int coarseSteps;
    float farStop;
    float walkedDepth = snowWalkOpticalDepth(offset, partMask, cellsWalked, columnsWalked, farStop, coarseSteps);
    fragColor = vec4(fogUpsampleRatio(walkedDepth, analyticDepth), offsetLength, 0.0, 1.0);
}
