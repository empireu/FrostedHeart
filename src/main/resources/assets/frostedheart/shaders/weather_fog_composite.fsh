// FHShaders prepends the version line, weather_fog.glsl and snow_walk.glsl.
// Draws the fog of snow and blizzards over the frame at full resolution: a pixel reads the depth of the main target,
// takes the analytic height fog along its own ray, adds the excess that weather_fog_integrate.fsh stored at the
// boundaries around its distance, which is the walk of the froxels of its column, and writes the fog color with the
// opacity of that optical depth. Open air stays exact at full resolution, and a shelter's edge blends bilinearly across
// the columns of the froxel grid.
// A pixel of the sky has no scene point: it takes the excess of the last boundary and the tail of the height fog beyond
// the range.
// The debug views show the share of the field, the piece that holds the pixel's distance, the transmittance, or the
// ratio of the fog structures, instead of the fog.
// The depth of the main target and its size in texels:
uniform sampler2D sceneDepth;
uniform vec2 screenSize;
// The snow fog color, as display values:
uniform vec3 snowColor;
// The view projection translated by the eye offset and inverted, so a position it gives is the offset from the eye:
uniform mat4 inverseViewProjection;
// What the debug view shows instead of the fog, 0 for the fog itself, 1 for the share of the field, 2 for the piece that
// holds the pixel's distance, 3 for the transmittance and 4 for the fog structures:
uniform int debugMode;
// Which stretches of a piece walk run, as snowWalkPiece reads them:
uniform int partMask;
// The excess of every boundary of the froxel grid, four boundaries to a layer, one channel each:
uniform sampler2DArray excesses;
// The columns and rows of the froxel grid, the depth slices of a ray, the range in blocks of its last boundary, and the
// pixels per side of a column:
uniform int columnCount;
uniform int rowCount;
uniform int sliceCount;
uniform float rangeDistance;
uniform int divisor;
// Whether to answer the self test instead of drawing, and the directions of its rays, one per texel of its 64 texel wide
// target:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 fragColor;

// The opacity below which the fog leaves a pixel as it is:
const float LEAST_OPACITY = 1.0 / 512.0;
// The opacity of the debug views, which draw over every pixel:
const float DEBUG_FIELD_OPACITY = 0.75;
const float DEBUG_FROXEL_OPACITY = 0.85;
// The debug views: the share of the field, the piece that holds the pixel's distance, the transmittance, and the fog
// structures:
const int DEBUG_MODE_FIELD = 1;
const int DEBUG_MODE_FROXEL = 2;
const int DEBUG_MODE_TRANSMITTANCE = 3;
const int DEBUG_MODE_STRUCTURE = 4;
// The distance in blocks of the first slice boundary from the eye, as FogFroxelGrid.NEAR, below which the composite
// takes no excess:
const float FOG_FROXEL_NEAR = 1.0;
// The side in blocks of the cells of the density walk of the structure view and its most steps, which belong to the
// view alone and share nothing with the walk of the fog:
const float DEBUG_STRUCTURE_CELL = 4.0;
const int DEBUG_STRUCTURE_MAXIMUM_STEPS = 64;
// The smallest analytic depth that the ratio of the structure view divides by:
const float DEBUG_STRUCTURE_MINIMUM_ANALYTIC_DEPTH = 1.0e-6;

// The distance in blocks of a slice boundary from the eye, as FogFroxelGrid.boundaryDistance: the boundaries are
// geometric, so that the slices are thin near the eye and thick far from it.
float fogFroxelBoundaryDistance(int boundaryIndex, int sliceCount, float rangeDistance) {
    return FOG_FROXEL_NEAR * pow(rangeDistance / FOG_FROXEL_NEAR, float(boundaryIndex) / float(sliceCount));
}

// The unit direction of the center ray of a column, from the eye through the screen point at the center of the column's
// block of pixels. A ray that gives no direction is the zero vector:
vec3 fogFroxelColumnDirection(int column, int row) {
    vec2 screenPoint = (vec2(column, row) + vec2(0.5)) * float(divisor);
    vec3 ndc = vec3(screenPoint / screenSize * 2.0 - 1.0, 1.0);
    vec4 position = inverseViewProjection * vec4(ndc, 1.0);
    vec3 offset = position.xyz / position.w;
    float offsetLength = length(offset);
    if (!(offsetLength > 0.0) || isnan(offsetLength) || isinf(offsetLength)) {
        return vec3(0.0);
    }
    return offset / offsetLength;
}

// The continuous boundary index of a distance along a ray, as FogFroxelGrid.slicePosition: 0 at or below
// FOG_FROXEL_NEAR and sliceCount at or past the range.
float fogFroxelSlicePosition(float distance, int sliceCount, float rangeDistance) {
    if (!(sliceCount > 0) || isnan(distance) || isinf(distance) || !(rangeDistance > FOG_FROXEL_NEAR)) {
        return 0.0;
    }
    if (!(distance > FOG_FROXEL_NEAR)) {
        return 0.0;
    }
    if (distance >= rangeDistance) {
        return float(sliceCount);
    }
    return float(sliceCount) * log(distance / FOG_FROXEL_NEAR) / log(rangeDistance / FOG_FROXEL_NEAR);
}

// The channel of an excess texel that holds a boundary, as FogFroxelGrid.channelOf: the red, green, blue or alpha of the
// texel for the channel 0, 1, 2 or 3:
float froxelChannel(vec4 texel, int channel) {
    if (channel == 0) {
        return texel.r;
    }
    if (channel == 1) {
        return texel.g;
    }
    if (channel == 2) {
        return texel.b;
    }
    return texel.a;
}

// The excess of a boundary of the froxel grid, which is the channel (boundaryIndex - 1) % 4 of the layer
// (boundaryIndex - 1) / 4, as FogFroxelGrid.layerOf and channelOf place it. The read is filtered across the columns of
// the grid and clamped to the array. The boundary must be 1 or a later one:
float froxelBoundaryExcess(vec2 uv, int boundaryIndex) {
    int layer = (boundaryIndex - 1) / 4;
    int channel = (boundaryIndex - 1) % 4;
    vec4 texel = texture(excesses, vec3(uv, float(layer)));
    return froxelChannel(texel, channel);
}

// The excess of the walked optical depth over the analytic height fog at a distance along a ray, as
// FogFroxelGrid.excessAtDistance: 0 at or below FOG_FROXEL_NEAR, the last boundary at or past the range, and else the
// linear blend between the boundaries that surround the distance. The read takes the columns of the grid bilinearly at
// uv:
float froxelExcessAtDistance(float distance, vec2 uv) {
    if (!(distance > FOG_FROXEL_NEAR)) {
        return 0.0;
    }
    if (distance >= rangeDistance) {
        return froxelBoundaryExcess(uv, sliceCount);
    }
    float position = min(float(sliceCount), fogFroxelSlicePosition(distance, sliceCount, rangeDistance));
    if (!(position > 0.0)) {
        return 0.0;
    }
    int lowBoundary = int(floor(position));
    if (!(position > float(lowBoundary))) {
        return froxelBoundaryExcess(uv, lowBoundary);
    }
    float belowExcess = lowBoundary > 0 ? froxelBoundaryExcess(uv, lowBoundary) : 0.0;
    float aboveExcess = froxelBoundaryExcess(uv, lowBoundary + 1);
    return belowExcess + (position - float(lowBoundary)) * (aboveExcess - belowExcess);
}

// The optical depth of the sky along a unit direction whose last boundary holds the given excess: the analytic height
// fog at the range, the excess there and the tail past the range, clamped to the maximum optical depth:
float fogSkyTau(vec3 direction, float lastExcess) {
    return clamp(fogOpticalDepth(eyeExtinction, rangeDistance, rangeDistance * direction.y, heightScale) + lastExcess
            + fogSkyTailOpticalDepth(eyeExtinction, rangeDistance, direction.y, heightScale), 0.0,
            FOG_MAXIMUM_OPTICAL_DEPTH);
}

// The share of the fog at the point a quarter block in front of the surface of the pixel, for the debug view of the
// field. Inside the region it is the share of the cell that holds the point. Beyond it, the point's far cell of
// SNOW_WALK_FAR_CELL blocks takes the height share of the column under its middle, which is the rule the far part of a
// piece walks by, and the share is 1 while the far part is off or past the range of the slices:
vec4 debugField(vec3 offset) {
    float offsetLength = length(offset);
    vec3 point = offsetLength < 0.5 ? offset : offset * (1.0 - 0.25 / offsetLength);
    if (abs(point.x) > regionHalfWidth || abs(point.y) > regionHalfWidth || abs(point.z) > regionHalfWidth) {
        vec3 localPosition = vec3(eyeBlock & ivec3(1)) + eyeFraction + point;
        ivec3 farCell = ivec3(floor(localPosition / SNOW_WALK_FAR_CELL));
        float share = (partMask & 2) != 0 && offsetLength < rangeDistance ? snowWalkFarShare(farCell) : 1.0;
        return vec4(mix(vec3(0.6, 0.0, 0.6), vec3(0.9), share), DEBUG_FIELD_OPACITY);
    }
    ivec3 cell = ivec3(floor(eyeFraction + point));
    float share = snowWalkCellShare(cell);
    return vec4(mix(vec3(0.05, 0.1, 0.6), vec3(1.0, 0.85, 0.2), share), DEBUG_FIELD_OPACITY);
}

// The piece of the center ray of a column that holds the pixel's own distance, for the debug view of the froxel grid,
// with the near and the far steps of its walk as shares of their cap in the red and the green, and the blue set when
// either stretch ran out of steps. A sky pixel takes the last piece. A broken column direction shows no steps:
vec4 debugFroxel(int column, int row, bool isSky, float distance) {
    vec3 direction = fogFroxelColumnDirection(column, row);
    if (!(length(direction) > 0.0)) {
        return vec4(0.0, 0.0, 0.0, DEBUG_FROXEL_OPACITY);
    }
    float position = isSky ? float(sliceCount) : fogFroxelSlicePosition(distance, sliceCount, rangeDistance);
    int piece = int(ceil(position));
    piece = min(sliceCount, max(1, piece));
    float startDistance = piece == 1 ? 0.0 : fogFroxelBoundaryDistance(piece - 1, sliceCount, rangeDistance);
    float endDistance = fogFroxelBoundaryDistance(piece, sliceCount, rangeDistance);
    int nearSteps;
    bool nearCapped;
    int farSteps;
    bool farCapped;
    snowWalkPiece(direction, startDistance, endDistance, partMask, nearSteps, nearCapped, farSteps, farCapped);
    return vec4(float(nearSteps) / float(SNOW_WALK_PIECE_MAXIMUM_STEPS),
            float(farSteps) / float(SNOW_WALK_PIECE_MAXIMUM_STEPS), (nearCapped || farCapped) ? 1.0 : 0.0,
            DEBUG_FROXEL_OPACITY);
}

// The optical depth of the fog structures along the part of the segment from the start to the end parameter, which the
// debug view of the structures walks through the world-aligned grid of DEBUG_STRUCTURE_CELL blocks, so that its parts
// stay fixed in the world while the eye moves. Each part adds the density at its middle times the closed form of the
// optical depth between its ends, and a walk that runs out of steps adds the rest at the mean density. The eye's place in
// the grid is its fraction plus its block taken modulo the side of a cell, which holds for negative blocks too:
float debugStructureDepth(vec3 offset, float segmentLength, float startParameter, float endParameter) {
    if (!(endParameter > startParameter) || !(structureShare > 0.0)) {
        return 0.0;
    }
    vec3 local = vec3(eyeBlock & ivec3(3)) + eyeFraction;
    vec3 position = local + startParameter * offset;
    ivec3 cell = ivec3(floor(position / DEBUG_STRUCTURE_CELL));
    ivec3 cellStep = ivec3(0);
    vec3 nextBoundary = vec3(SNOW_WALK_NO_BOUNDARY);
    vec3 boundaryStep = vec3(SNOW_WALK_NO_BOUNDARY);
    if (offset.x > 0.0) {
        cellStep.x = 1;
        nextBoundary.x = startParameter + (float(cell.x + 1) * DEBUG_STRUCTURE_CELL - position.x) / offset.x;
        boundaryStep.x = DEBUG_STRUCTURE_CELL / offset.x;
    } else if (offset.x < 0.0) {
        cellStep.x = -1;
        nextBoundary.x = startParameter + (float(cell.x) * DEBUG_STRUCTURE_CELL - position.x) / offset.x;
        boundaryStep.x = DEBUG_STRUCTURE_CELL / -offset.x;
    }
    if (offset.y > 0.0) {
        cellStep.y = 1;
        nextBoundary.y = startParameter + (float(cell.y + 1) * DEBUG_STRUCTURE_CELL - position.y) / offset.y;
        boundaryStep.y = DEBUG_STRUCTURE_CELL / offset.y;
    } else if (offset.y < 0.0) {
        cellStep.y = -1;
        nextBoundary.y = startParameter + (float(cell.y) * DEBUG_STRUCTURE_CELL - position.y) / offset.y;
        boundaryStep.y = DEBUG_STRUCTURE_CELL / -offset.y;
    }
    if (offset.z > 0.0) {
        cellStep.z = 1;
        nextBoundary.z = startParameter + (float(cell.z + 1) * DEBUG_STRUCTURE_CELL - position.z) / offset.z;
        boundaryStep.z = DEBUG_STRUCTURE_CELL / offset.z;
    } else if (offset.z < 0.0) {
        cellStep.z = -1;
        nextBoundary.z = startParameter + (float(cell.z) * DEBUG_STRUCTURE_CELL - position.z) / offset.z;
        boundaryStep.z = DEBUG_STRUCTURE_CELL / -offset.z;
    }
    float depth = 0.0;
    float enter = startParameter;
    float enterDepth = snowWalkTauAt(startParameter, segmentLength, offset.y);
    bool reachedEnd = false;
    for (int step = 0; step < DEBUG_STRUCTURE_MAXIMUM_STEPS && !reachedEnd; step++) {
        float exit = min(min(nextBoundary.x, nextBoundary.y), min(nextBoundary.z, endParameter));
        float exitDepth = snowWalkTauAt(exit, segmentLength, offset.y);
        float middle = (enter + exit) * 0.5;
        depth += snowWalkStructureDensity(middle * offset) * max(0.0, exitDepth - enterDepth);
        enter = exit;
        enterDepth = exitDepth;
        if (!(exit < endParameter)) {
            reachedEnd = true;
        } else {
            float smallestBoundary = min(nextBoundary.x, min(nextBoundary.y, nextBoundary.z));
            // Ties step every tied axis, as the cell walk does.
            if (nextBoundary.x <= smallestBoundary) {
                cell.x += cellStep.x;
                nextBoundary.x += boundaryStep.x;
            }
            if (nextBoundary.y <= smallestBoundary) {
                cell.y += cellStep.y;
                nextBoundary.y += boundaryStep.y;
            }
            if (nextBoundary.z <= smallestBoundary) {
                cell.z += cellStep.z;
                nextBoundary.z += boundaryStep.z;
            }
        }
    }
    if (!reachedEnd) {
        depth += max(0.0, snowWalkTauAt(endParameter, segmentLength, offset.y) - enterDepth);
    }
    return depth;
}

// The ratio of the structured fog to the mean fog along the ray of the pixel at the given offset, which is 1 while the
// structures are off and where the mean fog is too thin to divide by, as the structure debug view shows it:
vec4 debugStructure(vec3 offset) {
    float offsetLength = length(offset);
    if (!(offsetLength > 0.0)) {
        return vec4(vec3(0.85), 1.0);
    }
    float end = min(1.0, structureRange / offsetLength);
    float analyticDepth = fogOpticalDepth(eyeExtinction, end * offsetLength, end * offset.y, heightScale);
    float ratio = 1.0;
    if (structureShare > 0.0 && analyticDepth >= DEBUG_STRUCTURE_MINIMUM_ANALYTIC_DEPTH) {
        ratio = debugStructureDepth(offset, offsetLength, 0.0, end) / analyticDepth;
    }
    if (ratio <= 1.0) {
        return vec4(mix(vec3(0.1, 0.25, 0.9), vec3(0.85), ratio), 1.0);
    }
    return vec4(mix(vec3(0.85), vec3(0.95, 0.1, 0.05), clamp((ratio - 1.0) / 2.0, 0.0, 1.0)), 1.0);
}

void main() {
    if (selfTest != 0) {
        int ray = int(gl_FragCoord.x);
        vec3 direction = normalize(selfTestOffsets[ray]);
        vec4 lastTexel = texelFetch(excesses, ivec3(ray, 0, (sliceCount - 1) / 4), 0);
        float lastExcess = froxelChannel(lastTexel, (sliceCount - 1) % 4);
        fragColor = vec4(fogSkyTau(direction, lastExcess), 0.0, 0.0, 0.0);
        return;
    }
    float depth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    int column = int(gl_FragCoord.x / float(divisor));
    int row = int(gl_FragCoord.y / float(divisor));
    // The structure view draws every pixel, the sky included: a sky pixel shows the far plane of its pixel, which is
    // the fog at the range of the structures along its ray:
    if (debugMode == DEBUG_MODE_STRUCTURE) {
        vec3 structureNdc = vec3(gl_FragCoord.xy / screenSize * 2.0 - 1.0, depth * 2.0 - 1.0);
        vec4 structurePosition = inverseViewProjection * vec4(structureNdc, 1.0);
        vec3 structureOffset = structurePosition.xyz / structurePosition.w;
        if (depth >= 1.0) {
            structureOffset = structureOffset * (structureRange / length(structureOffset));
        }
        if (any(isnan(structureOffset)) || any(isinf(structureOffset))) {
            discard;
        }
        fragColor = debugStructure(structureOffset);
        return;
    }
    vec2 uv = gl_FragCoord.xy / (float(divisor) * vec2(columnCount, rowCount));
    float tau;
    if (depth >= 1.0) {
        if (debugMode == DEBUG_MODE_FIELD) {
            discard;
        }
        vec3 skyNdc = vec3(gl_FragCoord.xy / screenSize * 2.0 - 1.0, 1.0);
        vec4 skyPosition = inverseViewProjection * vec4(skyNdc, 1.0);
        vec3 skyOffset = skyPosition.xyz / skyPosition.w;
        float skyLength = length(skyOffset);
        if (!(skyLength > 0.0) || isnan(skyLength) || isinf(skyLength)) {
            discard;
        }
        vec3 skyDirection = skyOffset / skyLength;
        if (debugMode == DEBUG_MODE_FROXEL) {
            fragColor = debugFroxel(column, row, true, 0.0);
            return;
        }
        tau = fogSkyTau(skyDirection, froxelBoundaryExcess(uv, sliceCount));
    } else {
        vec3 ndc = vec3(gl_FragCoord.xy / screenSize * 2.0 - 1.0, depth * 2.0 - 1.0);
        vec4 position = inverseViewProjection * vec4(ndc, 1.0);
        vec3 offset = position.xyz / position.w;
        // A depth at the near plane can give no scene point, which then takes no fog:
        if (any(isnan(offset)) || any(isinf(offset))) {
            discard;
        }
        if (debugMode == DEBUG_MODE_FIELD) {
            fragColor = debugField(offset);
            return;
        }
        float distance = length(offset);
        if (debugMode == DEBUG_MODE_FROXEL) {
            fragColor = debugFroxel(column, row, false, distance);
            return;
        }
        tau = clamp(fogOpticalDepth(eyeExtinction, distance, offset.y, heightScale)
                + froxelExcessAtDistance(distance, uv), 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
    }
    if (debugMode == DEBUG_MODE_TRANSMITTANCE) {
        fragColor = vec4(vec3(exp(-tau)), 1.0);
        return;
    }
    float opacity = 1.0 - exp(-tau);
    if (opacity < LEAST_OPACITY) {
        discard;
    }
    fragColor = vec4(snowColor, opacity);
}
