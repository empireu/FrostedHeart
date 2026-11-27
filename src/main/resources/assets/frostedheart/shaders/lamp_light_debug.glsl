// The debug view of one cascade of the lamp light field, a dot at the center of every cell whose outdoor air is lit,
// colored by its level from blue for dim to amber for full block light. Follows lamp_light.glsl.

const float LAMP_DEBUG_DIMMEST_LEVEL = 0.5 / 15.0;
const float LAMP_DEBUG_DOT_SHARE = 0.35;
const float LAMP_DEBUG_SMALLEST_DOT = 2.0;
const float LAMP_DEBUG_LARGEST_DOT = 24.0;

#ifdef VERTEX_SHADER
uniform sampler3D lampDebugCascade;
uniform mat4 viewProjection;
uniform ivec3 debugSize;
uniform ivec3 debugSlotOrigin;
uniform vec3 debugOriginFromCamera;
uniform vec3 debugCellSize;
uniform float pixelsPerBlock;

out vec3 dotColor;

void main() {
    int id = gl_VertexID;
    ivec3 inWindow = ivec3(id % debugSize.x, (id / debugSize.x) % debugSize.y, id / (debugSize.x * debugSize.y));
    ivec3 slot = (debugSlotOrigin + inWindow) % debugSize;
    vec2 shares = texelFetch(lampDebugCascade, slot, 0).rg;
    float level = lampLevelOfShares(shares);
    dotColor = mix(vec3(0.2, 0.35, 1.0), vec3(1.0, 0.7, 0.2), level);
    if (level < LAMP_DEBUG_DIMMEST_LEVEL) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 1.0;
        return;
    }
    vec3 position = debugOriginFromCamera + (vec3(inWindow) + 0.5) * debugCellSize;
    gl_Position = viewProjection * vec4(position, 1.0);
    float distance = max(length(position), 0.5);
    gl_PointSize = clamp(LAMP_DEBUG_DOT_SHARE * debugCellSize.x * pixelsPerBlock / distance, LAMP_DEBUG_SMALLEST_DOT,
            LAMP_DEBUG_LARGEST_DOT);
}
#endif

#ifdef FRAGMENT_SHADER
in vec3 dotColor;

out vec4 fragColor;

void main() {
    if (length(gl_PointCoord - vec2(0.5)) > 0.5) {
        discard;
    }
    fragColor = vec4(dotColor, 1.0);
}
#endif
