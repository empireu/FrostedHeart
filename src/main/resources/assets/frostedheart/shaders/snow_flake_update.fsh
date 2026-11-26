// FHShaders prepends the version line, snow_flakes.glsl and snow_lee.glsl.
// Moves every snowflake by one frame, one fragment for each texel of the state textures. A flake's state holds its
// position in the periodic box in xyz and the angle of its flutter circle in w, raised by SNOW_STOPPED_PHASE while the
// flake is stopped. Its splat holds the point where it last hit a block, relative to the fixed splat origin block set
// on each fill, and in w its face times SNOW_SPLAT_FACE_STRIDE plus the seconds since the hit, or -1 when it has no
// splat.
// A flake stands for its copies at every multiple of the box's side, so the box follows the eye without respawning
// flakes, and the position stays a small float. A flake that hits a block stops. It moves on unseen and without
// collision until it leaves the box seen from the camera, and comes in at the opposite face as a new flake. So the flakes
// stay evenly spread through the box, and each shows only on its way from the box's face to the block it hits.
// The previous state and splats, which the pass does not draw to:
uniform sampler2D previousState;
uniform sampler2D previousSplats;
// Whether to place every flake from its hashes instead of moving it:
uniform int fillState;
uniform float stepSeconds;
// The wind along x and z, and the median fall speed, in blocks per second:
uniform vec2 windVelocity;
uniform float fallSpeed;
// The side of the periodic box and the mean radius of the flutter circles in blocks:
uniform float boxSide;
uniform float flutterRadius;
// The camera's position modulo the box's side at the last update and now, its motion since the last update, and its
// position relative to the fixed splat origin block:
uniform vec3 previousCameraInBox;
uniform vec3 cameraInBox;
uniform vec3 cameraStep;
uniform vec3 cameraFromSplatOrigin;
// Whether flakes collide, the ids of SnowShapeVolume, and the rows of SnowShapes in tiles as SnowShapeTextures:
uniform int collides;
uniform usampler3D shapeVolume;
uniform usampler2D shapeRows;
// The camera's block, the camera's place inside it, and the lowest corner of the shape volume from the camera's block:
uniform ivec3 cameraBlock;
uniform vec3 cameraFraction;
uniform ivec3 volumeCorner;
// The share of leaf blocks that a flake passes through, and the seconds a splat shows:
uniform float leafPorosity;
uniform float splatSeconds;
out vec4 nextState;
out vec4 nextSplat;

// The cells per axis of the shape volume and the layout of its ids, as SnowShapeVolume.SIZE and SnowShapes:
const int SNOW_VOLUME_SIZE = 64;
const uint SNOW_SHAPE_MASK = 0x3FFFu;
const uint SNOW_LEAVES_FLAG = 0x4000u;
const uint SNOW_FLUID_FLAG = 0x8000u;
const uint SNOW_EMPTY_SHAPE = 0u;
const uint SNOW_FULL_SHAPE = 1u;
// The tiles across the rows texture, as SnowShapeTextures.SHAPES_PER_ROW:
const uint SNOW_SHAPES_PER_ROW = 64u;
// The longest move between two collision tests, the most tests of one frame, and the halvings that narrow a hit, as
// SnowFlakes:
const float SNOW_SUBSTEP_BLOCKS = 0.25;
const int SNOW_MOST_SUBSTEPS = 16;
const int SNOW_HIT_REFINEMENTS = 4;

// A number from 0 to just below 1 for a flake and a block, below leafPorosity when the flake passes the block's leaves,
// as SnowFlakes.leafPassUnit:
float snowLeafPassUnit(uint flake, ivec3 block) {
    uint blockKey = snowHash(uint(block.x) * 73856093u ^ uint(block.y) * 19349663u ^ uint(block.z) * 83492791u);
    return float(snowHash((flake * 16u + SNOW_SALT_LEAVES) ^ blockKey) >> 8) * (1.0 / 16777216.0);
}

// The id of the shape that a point given from the camera lies in, or 0 when the flake passes the point, as
// SnowFlakes.hitIdAt:
uint snowHitId(vec3 point, uint flake) {
    vec3 local = cameraFraction + point;
    vec3 blockFloor = floor(local);
    ivec3 relativeBlock = ivec3(blockFloor);
    if (any(lessThan(relativeBlock, volumeCorner))
            || any(greaterThanEqual(relativeBlock, volumeCorner + ivec3(SNOW_VOLUME_SIZE)))) {
        return 0u;
    }
    ivec3 block = cameraBlock + relativeBlock;
    uint id = texelFetch(shapeVolume, block & ivec3(SNOW_VOLUME_SIZE - 1), 0).r;
    uint shape = id & SNOW_SHAPE_MASK;
    if (shape == SNOW_EMPTY_SHAPE) {
        return 0u;
    }
    if ((id & SNOW_LEAVES_FLAG) != 0u && snowLeafPassUnit(flake, block) < leafPorosity) {
        return 0u;
    }
    if (shape != SNOW_FULL_SHAPE) {
        ivec3 voxel = min(ivec3((local - blockFloor) * 16.0), ivec3(15));
        ivec2 texel = ivec2(int(shape % SNOW_SHAPES_PER_ROW) * 16 + voxel.z, int(shape / SNOW_SHAPES_PER_ROW) * 16
                + voxel.y);
        uint row = texelFetch(shapeRows, texel, 0).r;
        if (((row >> uint(voxel.x)) & 1u) == 0u) {
            return 0u;
        }
    }
    return id;
}

// Narrows a hit between a point the flake passes and a point inside a shape, and returns the face of the shape that the
// flake enters, numbered as Direction.get3DDataValue, with the point on that face in hitPoint, as SnowFlakes.hitFace:
int snowHitFace(vec3 passed, vec3 inside, uint flake, out vec3 hitPoint) {
    for (int refinement = 0; refinement < SNOW_HIT_REFINEMENTS; refinement++) {
        vec3 middle = 0.5 * (passed + inside);
        if (snowHitId(middle, flake) != 0u) {
            inside = middle;
        } else {
            passed = middle;
        }
    }
    vec3 direction = inside - passed;
    ivec3 passedVoxel = ivec3(floor((cameraFraction + passed) * 16.0));
    ivec3 insideVoxel = ivec3(floor((cameraFraction + inside) * 16.0));
    // The axis along which the flake crossed into another voxel, the one it moved along most when several:
    vec3 reach = abs(direction) * vec3(notEqual(passedVoxel, insideVoxel));
    if (!(max(reach.x, max(reach.y, reach.z)) > 0.0)) {
        reach = abs(direction);
    }
    int axis = reach.x >= reach.y && reach.x >= reach.z ? 0 : (reach.y >= reach.z ? 1 : 2);
    hitPoint = inside;
    hitPoint[axis] = float(max(passedVoxel[axis], insideVoxel[axis])) / 16.0 - cameraFraction[axis];
    if (axis == 0) {
        return direction.x > 0.0 ? 4 : 5;
    }
    if (axis == 1) {
        return direction.y > 0.0 ? 0 : 1;
    }
    return direction.z > 0.0 ? 2 : 3;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    uint flake = snowFlakeIndex(texel);
    float circleRadius = flutterRadius * snowFlakeFlutterRadiusShare(flake);
    if (fillState != 0) {
        vec3 placed = vec3(snowFlakeUnit(flake, SNOW_SALT_POSITION_X), snowFlakeUnit(flake, SNOW_SALT_POSITION_Y),
                snowFlakeUnit(flake, SNOW_SALT_POSITION_Z)) * boxSide;
        float placedPhase = snowFlakeUnit(flake, SNOW_SALT_FLUTTER_PHASE) * SNOW_TWO_PI;
        vec3 drawn = snowNearestOffset(placed - cameraInBox, boxSide) + snowFlutterOffset(placedPhase, circleRadius);
        bool placedInside = collides != 0 && snowHitId(drawn, flake) != 0u;
        nextState = vec4(placed, placedPhase + (placedInside ? SNOW_STOPPED_PHASE : 0.0));
        nextSplat = vec4(0.0, 0.0, 0.0, -1.0);
        return;
    }
    vec4 state = texelFetch(previousState, texel, 0);
    vec4 splat = texelFetch(previousSplats, texel, 0);
    bool stopped = state.w >= SNOW_STOPPED_PHASE && collides != 0;
    float phase = state.w >= SNOW_STOPPED_PHASE ? state.w - SNOW_STOPPED_PHASE : state.w;
    if (splat.w >= 0.0) {
        float face = floor(splat.w / SNOW_SPLAT_FACE_STRIDE);
        float age = splat.w - face * SNOW_SPLAT_FACE_STRIDE + stepSeconds;
        splat.w = age < splatSeconds ? face * SNOW_SPLAT_FACE_STRIDE + age : -1.0;
    }
    // The flake's path from the camera as it is now, which the draw continues from:
    vec3 pathStart = snowNearestOffset(state.xyz - previousCameraInBox, boxSide) - cameraStep;
    float ownFall = fallSpeed * snowFlakeFallShare(flake);
    float effectiveFall = stopped ? ownFall
            : snowLeeFallSpeed(pathStart + snowFlutterOffset(phase, circleRadius), ownFall, cameraBlock,
                    cameraFraction);
    vec3 velocity = vec3(windVelocity.x, -effectiveFall, windVelocity.y);
    vec3 step = velocity * stepSeconds;
    float turn = snowFlakeFlutterRate(flake) * stepSeconds;
    vec3 position = snowWrap(state.xyz + step, boxSide);
    float nextPhase = mod(phase + turn, SNOW_TWO_PI);
    vec3 pathEnd = pathStart + step;
    if (any(greaterThanEqual(abs(pathEnd), vec3(0.5 * boxSide)))) {
        // The flake comes in at the opposite face as a new one, stopped only when it comes in inside a block:
        vec3 drawn = snowNearestOffset(position - cameraInBox, boxSide) + snowFlutterOffset(nextPhase, circleRadius);
        stopped = collides != 0 && snowHitId(drawn, flake) != 0u;
    } else if (!stopped && collides != 0) {
        vec3 previous = pathStart + snowFlutterOffset(phase, circleRadius);
        vec3 end = pathEnd + snowFlutterOffset(phase + turn, circleRadius);
        int substeps = clamp(int(ceil(length(end - previous) / SNOW_SUBSTEP_BLOCKS)), 1, SNOW_MOST_SUBSTEPS);
        for (int substep = 1; substep <= substeps; substep++) {
            float share = float(substep) / float(substeps);
            vec3 point = pathStart + step * share + snowFlutterOffset(phase + turn * share, circleRadius);
            uint id = snowHitId(point, flake);
            if (id != 0u) {
                stopped = true;
                // A flake that a new block or a newly read cell put inside a shape stops without a splat:
                bool startedInside = substep == 1 && snowHitId(previous, flake) != 0u;
                if (!startedInside && splatSeconds > 0.0 && (id & SNOW_FLUID_FLAG) == 0u) {
                    vec3 hitPoint;
                    int face = snowHitFace(previous, point, flake, hitPoint);
                    splat = vec4(cameraFromSplatOrigin + hitPoint, float(face) * SNOW_SPLAT_FACE_STRIDE);
                }
                break;
            }
            previous = point;
        }
    }
    nextState = vec4(position, nextPhase + (stopped ? SNOW_STOPPED_PHASE : 0.0));
    nextSplat = splat;
}
