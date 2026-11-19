// FHShaders prepends the version line, weather_fog.glsl and snow_walk.glsl.
// Draws the fog of snow and blizzards over the frame after the weather, after WeatherFogRenderer: the scene point of a
// pixel is rebuilt from the depth of the main target, and the fog blends over the pixel by the opacity along the segment
// from the eye to that point.
// The share of the snow along that segment is the snow field's near the eye and the height of each column's highest
// block beyond it, as snow_walk.glsl walks them, so a room stays clear where the whole segment from the eye to the scene
// point stays under its roof, and a porch holds the fog back by the first open air under it.
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
// What the debug view shows instead of the fog, 0 for the fog itself, 1 for the share of the field and 2 for the counts
// of the walk:
uniform int debugMode;
// Which parts of the walk run, as snowWalkOpticalDepth reads them:
uniform int partMask;
// Whether to answer the self test instead of drawing, and the offsets of its rays, one per texel of its 64 texel wide
// target:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 fragColor;

const float PI = 3.14159265358979;
// The opacity below which the fog leaves a pixel as it is:
const float LEAST_OPACITY = 1.0 / 512.0;
// The opacity of the debug views, which draw over every pixel that is not sky:
const float DEBUG_FIELD_OPACITY = 0.75;
const float DEBUG_CELLS_OPACITY = 0.85;

// The share of the fog at the point a quarter block in front of the surface of the pixel, for the debug view of the
// field, and the far rule beyond the region, which shows its own color where the covered stretch reaches the surface:
vec4 debugField(vec3 offset) {
    float offsetLength = length(offset);
    vec3 point = offsetLength < 0.5 ? offset : offset * (1.0 - 0.25 / offsetLength);
    if (abs(point.x) > regionHalfWidth || abs(point.y) > regionHalfWidth || abs(point.z) > regionHalfWidth) {
        int cellsWalked;
        int columnsWalked;
        float farStop;
        snowWalkOpticalDepth(point, partMask, cellsWalked, columnsWalked, farStop);
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
    float farStop;
    snowWalkOpticalDepth(offset, partMask, cellsWalked, columnsWalked, farStop);
    return vec4(float(cellsWalked) / float(SNOW_WALK_MAXIMUM_CELL_STEPS),
            float(columnsWalked) / float(SNOW_WALK_FAR_WALK_COLUMNS), 0.0, DEBUG_CELLS_OPACITY);
}

void main() {
    if (selfTest != 0) {
        int cellsWalked;
        int columnsWalked;
        float farStop;
        float tau = snowWalkOpticalDepth(selfTestOffsets[int(gl_FragCoord.x)], partMask, cellsWalked, columnsWalked,
                farStop);
        fragColor = vec4(tau, float(cellsWalked), float(columnsWalked), farStop);
        return;
    }
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
    if (debugMode == 1) {
        fragColor = debugField(offset);
        return;
    }
    if (debugMode == 2) {
        fragColor = debugCells(offset);
        return;
    }
    int cellsWalked;
    int columnsWalked;
    float farStop;
    float tau = snowWalkOpticalDepth(offset, partMask, cellsWalked, columnsWalked, farStop);
    float opacity = 1.0 - exp(-tau);
    float phi = atan(offset.z, offset.x);
    float u = phi / (2.0 * PI) + 0.5;
    vec3 fogColor = mix(texture(horizonColors, vec2(u, 0.5)).rgb, snowColor, snowShare);
    if (opacity < LEAST_OPACITY) {
        discard;
    }
    fragColor = vec4(fogColor, opacity);
}
