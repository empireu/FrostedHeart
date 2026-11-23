// FHShaders prepends the version line, weather_fog.glsl and snow_walk.glsl.
// Walks the fog of snow and blizzards one slice at a time: each fragment stands for the piece of its column's ray between
// two slice boundaries, walks it with the cells and the snow shares of the fog, and writes the optical depth of that
// piece into the atlas of the froxels. A column covers divisor by divisor pixels of the screen and its ray runs from the
// eye through the center of that block, so the sum of a column's pieces is the optical depth of its whole ray.
// The atlas is columnCount * 8 texels across by rowCount * 8 texels down, and the draw covers its first
// rowCount * sliceCount / 8 rows. The tile of the slice k is at ((k - 1) % 8, (k - 1) / 8), so a fragment reads its
// column, row and slice back from its own texel. In self test mode the columns are the rays of the self test and the
// grid is 64 by 1.
// The size of the main target in texels, and the view projection translated by the eye offset and inverted, so a
// position it gives is the offset from the eye:
uniform vec2 screenSize;
uniform mat4 inverseViewProjection;
// The columns and rows of the froxel grid, the depth slices of a ray, the range in blocks of its last boundary, and the
// pixels per side of a column:
uniform int columnCount;
uniform int rowCount;
uniform int sliceCount;
uniform float rangeDistance;
uniform int divisor;
// Which stretches of a piece walk run, as snowWalkPiece reads them:
uniform int partMask;
// Whether to answer the self test instead of drawing, and the directions of its rays, one per texel of its 64 texel wide
// target:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 fragColor;

// The distance in blocks of the first slice boundary from the eye, as FogFroxelGrid.NEAR:
const float FOG_FROXEL_NEAR = 1.0;

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

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int column = texel.x % columnCount;
    int row = texel.y % rowCount;
    int slice = (texel.y / rowCount) * 8 + texel.x / columnCount + 1;
    float startDistance = slice == 1 ? 0.0 : fogFroxelBoundaryDistance(slice - 1, sliceCount, rangeDistance);
    float endDistance = fogFroxelBoundaryDistance(slice, sliceCount, rangeDistance);
    vec3 direction;
    if (selfTest != 0) {
        direction = normalize(selfTestOffsets[column]);
    } else {
        direction = fogFroxelColumnDirection(column, row);
    }
    int nearSteps;
    bool nearCapped;
    int farSteps;
    bool farCapped;
    float depth = snowWalkPiece(direction, startDistance, endDistance, partMask, nearSteps, nearCapped, farSteps,
            farCapped);
    fragColor = vec4(depth, 0.0, 0.0, 0.0);
}
