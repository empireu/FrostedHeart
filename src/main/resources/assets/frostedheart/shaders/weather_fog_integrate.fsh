// FHShaders prepends the version line and weather_fog.glsl.
// Sums the pieces of each column of the froxel fog, of the columns of a frame or of the rays of the self test: one
// fragment per column reads the optical depths that weather_fog_inject.fsh walked for its ray, sums them front to back,
// and writes at every slice boundary of its draw the excess of the walked depth over the analytic height fog along the
// same ray, which weather_fog_composite.fsh reads at each pixel's own distance.
// The boundaries of a column share the layers of the array texture that this pass draws to, four boundaries to a layer,
// one channel each. A fragment keeps the excesses in a local array and writes its eight draw buffers at the end, since
// the index of an output cannot be dynamic, and a boundary past the slice count keeps 0.
// The optical depth of every piece, as weather_fog_inject.fsh wrote it, and the columns and rows of the froxel grid, the
// depth slices of a ray, the range in blocks of its last boundary, and the pixels per side of a column:
uniform sampler2D pieceAtlas;
uniform int columnCount;
uniform int rowCount;
uniform int sliceCount;
uniform float rangeDistance;
uniform int divisor;
// The extinction per block at the eye, and the height scale of the fog in blocks:
uniform float eyeExtinction;
uniform float heightScale;
// The size of the main target in texels, and the view projection translated by the eye offset and inverted, so a
// position it gives is the offset from the eye:
uniform vec2 screenSize;
uniform mat4 inverseViewProjection;
// The boundary that the first of the eight outputs holds, 1 or 33: a draw writes 32 boundaries, so a slice count above
// 32 takes a second draw that sums the first 32 pieces again:
uniform int firstBoundary;
// Whether to answer the self test instead of drawing, and the directions of its rays, one per texel of its 64 texel wide
// target:
uniform int selfTest;
uniform vec3 selfTestOffsets[64];
out vec4 froxelSlices0;
out vec4 froxelSlices1;
out vec4 froxelSlices2;
out vec4 froxelSlices3;
out vec4 froxelSlices4;
out vec4 froxelSlices5;
out vec4 froxelSlices6;
out vec4 froxelSlices7;

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
    int column = int(gl_FragCoord.x);
    int row = int(gl_FragCoord.y);
    vec3 direction;
    if (selfTest != 0) {
        direction = normalize(selfTestOffsets[column]);
    } else {
        direction = fogFroxelColumnDirection(column, row);
    }
    if (!(length(direction) > 0.0)) {
        froxelSlices0 = vec4(0.0);
        froxelSlices1 = vec4(0.0);
        froxelSlices2 = vec4(0.0);
        froxelSlices3 = vec4(0.0);
        froxelSlices4 = vec4(0.0);
        froxelSlices5 = vec4(0.0);
        froxelSlices6 = vec4(0.0);
        froxelSlices7 = vec4(0.0);
        return;
    }
    float excesses[32];
    float runningDepth = 0.0;
    for (int piece = 1; piece < firstBoundary; piece++) {
        runningDepth += texelFetch(pieceAtlas, ivec2(((piece - 1) % 8) * columnCount + column,
                ((piece - 1) / 8) * rowCount + row), 0).r;
    }
    for (int index = 0; index < 32; index++) {
        int boundary = firstBoundary + index;
        if (boundary > sliceCount) {
            excesses[index] = 0.0;
        } else {
            runningDepth += texelFetch(pieceAtlas, ivec2(((boundary - 1) % 8) * columnCount + column,
                    ((boundary - 1) / 8) * rowCount + row), 0).r;
            float distance = fogFroxelBoundaryDistance(boundary, sliceCount, rangeDistance);
            excesses[index] = runningDepth - fogOpticalDepth(eyeExtinction, distance, distance * direction.y,
                    heightScale);
        }
    }
    froxelSlices0 = vec4(excesses[0], excesses[1], excesses[2], excesses[3]);
    froxelSlices1 = vec4(excesses[4], excesses[5], excesses[6], excesses[7]);
    froxelSlices2 = vec4(excesses[8], excesses[9], excesses[10], excesses[11]);
    froxelSlices3 = vec4(excesses[12], excesses[13], excesses[14], excesses[15]);
    froxelSlices4 = vec4(excesses[16], excesses[17], excesses[18], excesses[19]);
    froxelSlices5 = vec4(excesses[20], excesses[21], excesses[22], excesses[23]);
    froxelSlices6 = vec4(excesses[24], excesses[25], excesses[26], excesses[27]);
    froxelSlices7 = vec4(excesses[28], excesses[29], excesses[30], excesses[31]);
}
