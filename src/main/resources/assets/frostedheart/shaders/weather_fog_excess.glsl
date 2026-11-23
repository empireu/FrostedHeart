// The excess of the froxel fog at a distance along a ray, which the fog composite and the snowflakes read the same way.
// FHShaders prepends it after weather_fog.glsl.
// The excess of every boundary of the froxel grid, four boundaries to a layer, one channel each:
uniform sampler2DArray excesses;
// The columns and rows of the froxel grid, the depth slices of a ray, the range in blocks of its last boundary, and the
// pixels per side of a column:
uniform int columnCount;
uniform int rowCount;
uniform int sliceCount;
uniform float rangeDistance;
uniform int divisor;

// The distance in blocks of the first slice boundary from the eye, as FogFroxelGrid.NEAR, below which the composite
// takes no excess:
const float FOG_FROXEL_NEAR = 1.0;

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
    vec4 texel = textureLod(excesses, vec3(uv, float(layer)), 0.0);
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
