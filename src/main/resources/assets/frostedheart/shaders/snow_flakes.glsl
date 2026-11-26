// What every snowflake keeps for its whole life, derived from its index by hashes, as SnowFlakes does in Java.
// FHShaders prepends it to the update and the draw of the snowflakes.
// A flake's index is its texel in the state texture, row by row.

// The texels per row of the state texture, as SnowFlakes.STATE_WIDTH:
const int SNOW_STATE_WIDTH = 512;
const float SNOW_TWO_PI = 6.28318530718;
// The salts that make the hashes of one flake independent, as the SALT constants of SnowFlakes:
const uint SNOW_SALT_LEE = 0u;
const uint SNOW_SALT_POSITION_X = 1u;
const uint SNOW_SALT_POSITION_Y = 2u;
const uint SNOW_SALT_POSITION_Z = 3u;
const uint SNOW_SALT_FLUTTER_PHASE = 4u;
const uint SNOW_SALT_FALL = 5u;
const uint SNOW_SALT_SIZE = 6u;
const uint SNOW_SALT_FLUTTER_PERIOD = 7u;
const uint SNOW_SALT_FLUTTER_TURN = 8u;
const uint SNOW_SALT_FLUTTER_RADIUS = 9u;
const uint SNOW_SALT_BRIGHTNESS = 10u;
const uint SNOW_SALT_SHELTER = 11u;
const uint SNOW_SALT_SHAPE = 12u;
const uint SNOW_SALT_TURN = 13u;
const uint SNOW_SALT_TILT = 14u;
const uint SNOW_SALT_LEAVES = 15u;
// What the angle of a flake's flutter circle in its state is raised by while the flake is stopped, more than a full
// circle, as SnowFlakes.STOPPED_PHASE:
const float SNOW_STOPPED_PHASE = 8.0;
// The step between faces in the w of a splat, which holds the seconds since the hit below it, as
// SnowFlakes.SPLAT_FACE_STRIDE:
const float SNOW_SPLAT_FACE_STRIDE = 16.0;
// The spreads of a flake's own values around the settings, as SnowFlakes:
const float SNOW_SLOWEST_FALL_SHARE = 0.6666667;
const float SNOW_FASTEST_FALL_SHARE = 1.3333333;
const float SNOW_SMALLEST_SIZE_SHARE = 0.5;
const float SNOW_LARGEST_SIZE_SHARE = 2.5;
const float SNOW_SIZE_SKEW = 3.0;
const float SNOW_SHORTEST_FLUTTER_PERIOD = 1.2;
const float SNOW_LONGEST_FLUTTER_PERIOD = 3.0;
const float SNOW_SMALLEST_FLUTTER_RADIUS_SHARE = 0.5;
const float SNOW_LARGEST_FLUTTER_RADIUS_SHARE = 1.5;
const float SNOW_DIMMEST_BRIGHTNESS_SHARE = 0.85;
const float SNOW_BRIGHTEST_BRIGHTNESS_SHARE = 1.15;
// The width of the share over which a flake fades out under cover, as SnowFlakes.SHELTER_SOFTNESS:
const float SNOW_SHELTER_SOFTNESS = 0.25;
// The shapes that flakes take, as SnowFlakes.SHAPE_COUNT:
const int SNOW_SHAPE_COUNT = 32;

// The 32-bit mix of a value, as SnowFlakes.hash:
uint snowHash(uint value) {
    uint mixed = value;
    mixed ^= mixed >> 16;
    mixed *= 0x7feb352du;
    mixed ^= mixed >> 15;
    mixed *= 0x846ca68bu;
    mixed ^= mixed >> 16;
    return mixed;
}

// A number from 0 to just below 1 that only the flake and the salt decide, as SnowFlakes.unit:
float snowFlakeUnit(uint flake, uint salt) {
    return float(snowHash(flake * 16u + salt) >> 8) * (1.0 / 16777216.0);
}

uint snowFlakeIndex(ivec2 texel) {
    return uint(texel.y * SNOW_STATE_WIDTH + texel.x);
}

// The flake's fall speed as a share of the median fall speed:
float snowFlakeFallShare(uint flake) {
    return mix(SNOW_SLOWEST_FALL_SHARE, SNOW_FASTEST_FALL_SHARE, snowFlakeUnit(flake, SNOW_SALT_FALL));
}

// The flake's width as a share of the mean width, mostly small with a few large clumps:
float snowFlakeSizeShare(uint flake) {
    return mix(SNOW_SMALLEST_SIZE_SHARE, SNOW_LARGEST_SIZE_SHARE,
            pow(snowFlakeUnit(flake, SNOW_SALT_SIZE), SNOW_SIZE_SKEW));
}

// The shape the flake takes, as SnowFlakes.shapeIndex:
int snowFlakeShapeIndex(uint flake) {
    return int(snowHash(flake * 16u + SNOW_SALT_SHAPE) % uint(SNOW_SHAPE_COUNT));
}

// The radians per second at which the flake circles while it falls, with a sign for the way it turns:
float snowFlakeFlutterRate(uint flake) {
    float period = mix(SNOW_SHORTEST_FLUTTER_PERIOD, SNOW_LONGEST_FLUTTER_PERIOD,
            snowFlakeUnit(flake, SNOW_SALT_FLUTTER_PERIOD));
    float turn = snowFlakeUnit(flake, SNOW_SALT_FLUTTER_TURN) < 0.5 ? -1.0 : 1.0;
    return turn * SNOW_TWO_PI / period;
}

// The radius of the flake's circle as a share of the flutter setting:
float snowFlakeFlutterRadiusShare(uint flake) {
    return mix(SNOW_SMALLEST_FLUTTER_RADIUS_SHARE, SNOW_LARGEST_FLUTTER_RADIUS_SHARE,
            snowFlakeUnit(flake, SNOW_SALT_FLUTTER_RADIUS));
}

float snowFlakeBrightnessShare(uint flake) {
    return mix(SNOW_DIMMEST_BRIGHTNESS_SHARE, SNOW_BRIGHTEST_BRIGHTNESS_SHARE,
            snowFlakeUnit(flake, SNOW_SALT_BRIGHTNESS));
}

// The snow share below which the flake is hidden under cover, from 0 to 1 - SNOW_SHELTER_SOFTNESS:
float snowFlakeShelterThreshold(uint flake) {
    return snowFlakeUnit(flake, SNOW_SALT_SHELTER) * (1.0 - SNOW_SHELTER_SOFTNESS);
}

// The lee share below which the flake is hidden in a lee, from 0 to 1 - SNOW_SHELTER_SOFTNESS:
float snowFlakeLeeThreshold(uint flake) {
    return snowFlakeUnit(flake, SNOW_SALT_LEE) * (1.0 - SNOW_SHELTER_SOFTNESS);
}

// A position wrapped into the box of the given side, from 0 up to the side:
vec3 snowWrap(vec3 position, float side) {
    return position - side * floor(position / side);
}

// The offset of the copy nearest the camera, given a position minus the camera's position in the box, as
// SnowFlakes.nearestCopyOffset:
vec3 snowNearestOffset(vec3 offset, float side) {
    return offset - side * floor(offset / side + 0.5);
}

// Where a flake sits on its flutter circle, from the point its path passes:
vec3 snowFlutterOffset(float phase, float circleRadius) {
    return vec3(cos(phase), 0.0, sin(phase)) * circleRadius;
}
