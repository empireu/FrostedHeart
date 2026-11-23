// FHShaders prepends the version line and snow_flakes.glsl.
// Moves every snowflake by one frame, one fragment for each texel of the state texture, which holds a flake's position
// in the periodic box in xyz and the angle of its flutter circle in w. A flake stands for its copies at every multiple
// of the box's side, so the box follows the eye without respawning flakes, and the position stays a small float.
// The previous state, which the pass does not draw to:
uniform sampler2D previousState;
// Whether to place every flake from its hashes instead of moving it:
uniform int fillState;
uniform float stepSeconds;
// The wind along x and z, and the median fall speed, in blocks per second:
uniform vec2 windVelocity;
uniform float fallSpeed;
// The side of the periodic box in blocks:
uniform float boxSide;
out vec4 nextState;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    uint flake = snowFlakeIndex(texel);
    vec3 position;
    float phase;
    if (fillState != 0) {
        position = vec3(snowFlakeUnit(flake, SNOW_SALT_POSITION_X), snowFlakeUnit(flake, SNOW_SALT_POSITION_Y),
                snowFlakeUnit(flake, SNOW_SALT_POSITION_Z)) * boxSide;
        phase = snowFlakeUnit(flake, SNOW_SALT_FLUTTER_PHASE) * SNOW_TWO_PI;
    } else {
        vec4 state = texelFetch(previousState, texel, 0);
        vec3 velocity = vec3(windVelocity.x, -fallSpeed * snowFlakeFallShare(flake), windVelocity.y);
        position = state.xyz + velocity * stepSeconds;
        phase = state.w + snowFlakeFlutterRate(flake) * stepSeconds;
    }
    nextState = vec4(snowWrap(position, boxSide), mod(phase, SNOW_TWO_PI));
}
