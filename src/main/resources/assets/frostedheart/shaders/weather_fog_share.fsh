// FHShaders prepends the version line, weather_fog.glsl and snow_walk.glsl.
// Fills the share volume of the weather fog, which the walk reads one texel of per cell instead of the field, the share
// table and the far heights: each texel of the atlas stands for one cell of the region cube around the eye's block, and
// writes the share that snowWalkCellShareDirect gives for that cell.
// The atlas holds the horizontal slices of the cube as tiles of the x by z cells of a slice, the slice of the offset y at
// the tile column (y + ORIGIN) % TILES_ACROSS and the tile row (y + ORIGIN) / TILES_ACROSS, as FogShareVolume lays it
// out. The texel at an x and a z of a tile is the cell at those offsets from the eye's block, so the tiles past the last
// slice of the volume hold no cell.
// The volume goes to the red channel alone, which is R16F, so it holds the share to a relative error below 2^-11.
out vec4 fragColor;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int tileColumn = texel.x / SNOW_WALK_SHARE_SIDE;
    int tileRow = texel.y / SNOW_WALK_SHARE_SIDE;
    int slice = tileRow * SNOW_WALK_SHARE_TILES_ACROSS + tileColumn;
    // A tile past the 80 slices of the volume holds no cell, and writes no share:
    if (slice >= SNOW_WALK_SHARE_SIDE) {
        fragColor = vec4(0.0);
        return;
    }
    int x = texel.x - tileColumn * SNOW_WALK_SHARE_SIDE - SNOW_WALK_SHARE_ORIGIN;
    int z = texel.y - tileRow * SNOW_WALK_SHARE_SIDE - SNOW_WALK_SHARE_ORIGIN;
    int y = slice - SNOW_WALK_SHARE_ORIGIN;
    fragColor = vec4(snowWalkCellShareDirect(ivec3(x, y, z)), 0.0, 0.0, 0.0);
}
