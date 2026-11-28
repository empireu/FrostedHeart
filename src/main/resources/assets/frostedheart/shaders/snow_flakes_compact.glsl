// FHShaders prepends the version line, the stage's define and snow_flakes.glsl.
// Compacts visible splat and flake indices into a transform feedback buffer once per frame so both snow draw passes
// process only visible quads. Each point tests SNOW_COMPACT_GROUP_SIZE consecutive texels in a row and emits only
// visible entries in order.

const int SNOW_COMPACT_GROUP_SIZE = 32;

#ifdef VERTEX_SHADER
uniform sampler2D drawColor;
uniform int splatPass;
uniform int instanceCount;

flat out uint vMask;
flat out uint vBaseEntry;

const float SNOW_LEAST_ALPHA = 1.0 / 512.0;
const uint SNOW_SPLAT_FLAG = 0x80000000u;

void main() {
    int baseIndex = gl_VertexID * SNOW_COMPACT_GROUP_SIZE;
    int row = baseIndex / SNOW_STATE_WIDTH;
    int baseX = baseIndex - row * SNOW_STATE_WIDTH;
    int limit = min(SNOW_COMPACT_GROUP_SIZE, instanceCount - baseIndex);
    uint mask = 0u;
    for (int bit = 0; bit < limit; bit++) {
        if (texelFetch(drawColor, ivec2(baseX + bit, row), 0).a > SNOW_LEAST_ALPHA) {
            mask |= (1u << uint(bit));
        }
    }
    vMask = mask;
    vBaseEntry = uint(baseIndex) | (splatPass != 0 ? SNOW_SPLAT_FLAG : 0u);
}
#endif

#ifdef GEOMETRY_SHADER
layout(points) in;
layout(points, max_vertices = 32) out;

flat in uint vMask[];
flat in uint vBaseEntry[];
flat out uint visibleEntry;

void main() {
    uint mask = vMask[0];
    if (mask == 0u) {
        return;
    }
    uint base = vBaseEntry[0];
    for (uint chunk = 0u; chunk < uint(SNOW_COMPACT_GROUP_SIZE); chunk += 8u) {
        uint byteMask = (mask >> chunk) & 0xFFu;
        if (byteMask != 0u) {
            for (uint bit = 0u; bit < 8u; bit++) {
                if (((byteMask >> bit) & 1u) != 0u) {
                    visibleEntry = base + chunk + bit;
                    EmitVertex();
                    EndPrimitive();
                }
            }
        }
    }
}
#endif
