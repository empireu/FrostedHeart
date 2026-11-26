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
// The share of leaf blocks that a flake passes through, the seconds a splat shows, and the update counter for box
// re-entry reseeding:
uniform float leafPorosity;
uniform float splatSeconds;
uniform int frameCounter;
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

// The radius of a flake's flutter circle around a center given from the camera, which grows below the center's braking
// ceiling:
float snowCircleRadiusAt(vec3 circleCenter, float radiusShare) {
    if (hasCavityMap == 0) {
        return flutterRadius * radiusShare;
    }
    float brakeHeight = snowCavityBrakeHeightAt(circleCenter, cameraBlock, cameraFraction);
    float worldY = float(cameraBlock.y) + cameraFraction.y + circleCenter.y;
    return snowCavityCircleRadius(flutterRadius, snowBrakeOpenFactorAt(worldY, brakeHeight), radiusShare);
}

// Exact integral of (windStep * s(y) - cameraStep) over [a, b] inside the smoothstep braking ramp, as
// SnowLee.sideRampIntegral:
float snowSideRampIntegral(float a, float b, float windStep, float cameraStep, float brakeHeight, float brakeLength) {
    float span = b - a;
    if (!(span > 0.0) || !(brakeLength > 0.0)) {
        return 0.0;
    }
    float rampStart = brakeHeight - brakeLength;
    float ta = clamp((a - rampStart) / brakeLength, 0.0, 1.0);
    float tb = clamp((b - rampStart) / brakeLength, 0.0, 1.0);
    float polyA = ta * ta * ta * (1.0 - 0.5 * ta);
    float polyB = tb * tb * tb * (1.0 - 0.5 * tb);
    return (windStep * SNOW_CAVITY_WIND_FLOOR - cameraStep) * span
            + windStep * (1.0 - SNOW_CAVITY_WIND_FLOOR) * brakeLength * (polyB - polyA);
}

// Root in (yBot, yTop) where windStep * s(y) - cameraStep crosses 0, or yTop when sign stays constant:
float snowSideSignSplit(float yBot, float yTop, float windStep, float cameraStep, float brakeHeight,
        float brakeLength) {
    if (!(yTop > yBot) || !(brakeLength > 0.0) || !(abs(windStep) > 1.0e-9)) {
        return yTop;
    }
    float targetScale = cameraStep / windStep;
    float botScale = snowBrakeWindScaleAt(yBot, brakeHeight);
    float topScale = snowBrakeWindScaleAt(yTop, brakeHeight);
    if (targetScale > botScale && targetScale < topScale && targetScale > SNOW_CAVITY_WIND_FLOOR
            && targetScale < 1.0) {
        float share = (targetScale - SNOW_CAVITY_WIND_FLOOR) / (1.0 - SNOW_CAVITY_WIND_FLOOR);
        float sineArg = clamp(1.0 - 2.0 * share, -1.0, 1.0);
        float tRoot = 0.5 - sin(asin(sineArg) / 3.0);
        float root = brakeHeight - brakeLength + brakeLength * tRoot;
        return clamp(root, yBot, yTop);
    }
    return yTop;
}

float snowSideFluxArea(float yMin, float yMax, float windStep, float cameraStep, float brakeHeight,
        float brakeLength) {
    float yBot = clamp(brakeHeight - brakeLength, yMin, yMax);
    float yTop = clamp(brakeHeight, yMin, yMax);
    float areaFloor = abs(windStep * SNOW_CAVITY_WIND_FLOOR - cameraStep) * max(0.0, yBot - yMin);
    float split = snowSideSignSplit(yBot, yTop, windStep, cameraStep, brakeHeight, brakeLength);
    float areaRamp1 = abs(snowSideRampIntegral(yBot, split, windStep, cameraStep, brakeHeight, brakeLength));
    float areaRamp2 = abs(snowSideRampIntegral(split, yTop, windStep, cameraStep, brakeHeight, brakeLength));
    float areaAbove = abs(windStep - cameraStep) * max(0.0, yMax - yTop);
    return areaFloor + areaRamp1 + areaRamp2 + areaAbove;
}

float snowSolveSideRampHeight(float a, float b, float targetArea, float windStep, float cameraStep, float brakeHeight,
        float brakeLength) {
    float lo = a;
    float hi = b;
    float y = 0.5 * (lo + hi);
    for (int iter = 0; iter < 8; iter++) {
        float fVal = abs(snowSideRampIntegral(a, y, windStep, cameraStep, brakeHeight, brakeLength)) - targetArea;
        if (fVal < 0.0) {
            lo = y;
        } else {
            hi = y;
        }
        float deriv = abs(windStep * snowBrakeWindScaleAt(y, brakeHeight) - cameraStep);
        float next = deriv > 1.0e-7 ? y - fVal / deriv : 0.5 * (lo + hi);
        if (!(next > lo && next < hi)) {
            next = 0.5 * (lo + hi);
        }
        y = next;
    }
    return y;
}

float snowSampleSideHeight(float yMin, float yMax, float windStep, float cameraStep, float brakeHeight,
        float brakeLength, float unit) {
    float yBot = clamp(brakeHeight - brakeLength, yMin, yMax);
    float yTop = clamp(brakeHeight, yMin, yMax);
    float areaFloor = abs(windStep * SNOW_CAVITY_WIND_FLOOR - cameraStep) * max(0.0, yBot - yMin);
    float split = snowSideSignSplit(yBot, yTop, windStep, cameraStep, brakeHeight, brakeLength);
    float areaRamp1 = abs(snowSideRampIntegral(yBot, split, windStep, cameraStep, brakeHeight, brakeLength));
    float areaRamp2 = abs(snowSideRampIntegral(split, yTop, windStep, cameraStep, brakeHeight, brakeLength));
    float areaAbove = abs(windStep - cameraStep) * max(0.0, yMax - yTop);
    float totalArea = areaFloor + areaRamp1 + areaRamp2 + areaAbove;
    if (!(totalArea > 0.0)) {
        return yMin + unit * (yMax - yMin);
    }
    float target = unit * totalArea;
    if (target < areaFloor) {
        return yMin + clamp(target / areaFloor, 0.0, 1.0) * (yBot - yMin);
    }
    target -= areaFloor;
    if (target < areaRamp1) {
        return snowSolveSideRampHeight(yBot, split, target, windStep, cameraStep, brakeHeight, brakeLength);
    }
    target -= areaRamp1;
    if (target < areaRamp2) {
        return snowSolveSideRampHeight(split, yTop, target, windStep, cameraStep, brakeHeight, brakeLength);
    }
    target -= areaRamp2;
    if (areaAbove > 0.0) {
        return yTop + clamp(target / areaAbove, 0.0, 1.0) * (yMax - yTop);
    }
    return yTop;
}

// Places a flake leaving the camera's box onto a flux-weighted inflow face using the height-profiled horizontal wind
// (full wind above centerBrakeHeight, easing smoothly down to SNOW_CAVITY_WIND_FLOOR over cavityBrakeLength below it)
// and top/bottom vertical motions, as SnowLee.reenterBoxOffset:
vec3 snowReenterBoxOffset(uint flake, uint counter, float windStepX, float cameraStepX, float relStepTopY,
        float relStepBottomY, float windStepZ, float cameraStepZ, float cameraWorldY, float centerBrakeHeight,
        float side) {
    uint reseed = snowHash(flake ^ (counter * 0x9e3779b9u));
    float pickUnit = (float(snowHash(reseed + 1u) >> 8) + 0.5) * (1.0 / 16777216.0);
    float depthUnit = (float(snowHash(reseed + 2u) >> 8) + 0.5) * (1.0 / 16777216.0);
    float firstUnit = (float(snowHash(reseed + 3u) >> 8) + 0.5) * (1.0 / 16777216.0);
    float secondUnit = (float(snowHash(reseed + 4u) >> 8) + 0.5) * (1.0 / 16777216.0);
    float halfSide = 0.5 * side;
    float yMin = cameraWorldY - halfSide;
    float yMax = cameraWorldY + halfSide;
    float safeBrakeLength = max(0.0, cavityBrakeLength);
    bool profiled = centerBrakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD && centerBrakeHeight > yMin;
    float fluxX = profiled
            ? snowSideFluxArea(yMin, yMax, windStepX, cameraStepX, centerBrakeHeight, safeBrakeLength) / side
            : abs(windStepX - cameraStepX);
    float fluxTop = max(0.0, -relStepTopY);
    float fluxBottom = max(0.0, relStepBottomY);
    float fluxZ = profiled
            ? snowSideFluxArea(yMin, yMax, windStepZ, cameraStepZ, centerBrakeHeight, safeBrakeLength) / side
            : abs(windStepZ - cameraStepZ);
    float totalFlux = fluxX + fluxTop + fluxBottom + fluxZ;
    if (!(totalFlux > 1.0e-6) || max(max(fluxX, fluxZ), max(fluxTop, fluxBottom)) >= side) {
        return (vec3(depthUnit, firstUnit, secondUnit) - vec3(0.5)) * side;
    }
    float pick = pickUnit * totalFlux;
    if (pick < fluxX) {
        if (!profiled) {
            float relStepX = windStepX - cameraStepX;
            float x = (relStepX > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * fluxX);
            return vec3(x, (firstUnit - 0.5) * side, (secondUnit - 0.5) * side);
        }
        float worldY = snowSampleSideHeight(yMin, yMax, windStepX, cameraStepX, centerBrakeHeight, safeBrakeLength,
                firstUnit);
        float localStep = windStepX * snowBrakeWindScaleAt(worldY, centerBrakeHeight) - cameraStepX;
        float clampedStep = min(abs(localStep), side);
        float x = (localStep > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * clampedStep);
        return vec3(x, clamp(worldY - cameraWorldY, -halfSide, halfSide), (secondUnit - 0.5) * side);
    }
    if (pick < fluxX + fluxTop) {
        return vec3((firstUnit - 0.5) * side, halfSide - depthUnit * fluxTop, (secondUnit - 0.5) * side);
    }
    if (pick < fluxX + fluxTop + fluxBottom) {
        return vec3((firstUnit - 0.5) * side, -halfSide + depthUnit * fluxBottom, (secondUnit - 0.5) * side);
    }
    if (!profiled) {
        float relStepZ = windStepZ - cameraStepZ;
        float z = (relStepZ > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * fluxZ);
        return vec3((firstUnit - 0.5) * side, (secondUnit - 0.5) * side, z);
    }
    float worldY = snowSampleSideHeight(yMin, yMax, windStepZ, cameraStepZ, centerBrakeHeight, safeBrakeLength,
            secondUnit);
    float localStep = windStepZ * snowBrakeWindScaleAt(worldY, centerBrakeHeight) - cameraStepZ;
    float clampedStep = min(abs(localStep), side);
    float z = (localStep > 0.0 ? -1.0 : 1.0) * (halfSide - depthUnit * clampedStep);
    return vec3((firstUnit - 0.5) * side, clamp(worldY - cameraWorldY, -halfSide, halfSide), z);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    uint flake = snowFlakeIndex(texel);
    float radiusShare = snowFlakeFlutterRadiusShare(flake);
    float circleRadius = flutterRadius * radiusShare;
    if (fillState != 0) {
        vec3 placed = vec3(snowFlakeUnit(flake, SNOW_SALT_POSITION_X), snowFlakeUnit(flake, SNOW_SALT_POSITION_Y),
                snowFlakeUnit(flake, SNOW_SALT_POSITION_Z)) * boxSide;
        float placedPhase = snowFlakeUnit(flake, SNOW_SALT_FLUTTER_PHASE) * SNOW_TWO_PI;
        vec3 placedCenter = snowNearestOffset(placed - cameraInBox, boxSide);
        vec3 drawn = placedCenter + snowFlutterOffset(placedPhase, snowCircleRadiusAt(placedCenter, radiusShare));
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
    float windSpeed = length(windVelocity);
    float cameraWorldY = float(cameraBlock.y) + cameraFraction.y;
    float halfSide = 0.5 * boxSide;
    float centerColumn = (hasLeeMap != 0 || hasCavityMap != 0)
            ? snowLeeColumnHeight(vec3(0.0), cameraBlock, cameraFraction) : SNOW_LEE_NO_HEIGHT;
    vec2 centerCavity = hasCavityMap != 0
            ? snowCavitySampleAt(vec3(0.0), cameraBlock, cameraFraction) : vec2(SNOW_LEE_NO_HEIGHT);
    float centerBrakeHeight = hasCavityMap != 0
            ? snowCavityBrakeHeight(centerCavity.x, centerCavity.y, centerColumn) : SNOW_LEE_NO_HEIGHT;
    // Stopped flakes move with the wind of their own height under the center column's braking profile, so flakes
    // stopped on roofs and rims above the brake height leave at full wind while flakes below it stay braked:
    vec2 flakeWind = windVelocity * snowBrakeWindScaleAt(cameraWorldY + pathStart.y, centerBrakeHeight);
    float verticalVelocity = -ownFall;
    if (!stopped) {
        // A moving flake reads its wind, braking, swirl and lee at the center of its flutter circle:
        float flakeWorldY = cameraWorldY + pathStart.y;
        float flakeColumn = (hasLeeMap != 0 || hasCavityMap != 0)
                ? snowLeeColumnHeight(pathStart, cameraBlock, cameraFraction) : SNOW_LEE_NO_HEIGHT;
        float windScale = 1.0;
        float sinkVelocity = 0.0;
        if (hasCavityMap != 0) {
            vec2 flakeCavity = snowCavitySampleAt(pathStart, cameraBlock, cameraFraction);
            float flakeBrakeHeight = snowCavityBrakeHeight(flakeCavity.x, flakeCavity.y, flakeColumn);
            float flakeOpen = snowBrakeOpenFactorAt(flakeWorldY, flakeBrakeHeight);
            windScale = snowCavityWindScale(flakeOpen);
            circleRadius = snowCavityCircleRadius(flutterRadius, flakeOpen, radiusShare);
            sinkVelocity = snowCavitySinkVelocity(pathStart, cameraBlock, cameraFraction, flakeBrakeHeight, windScale,
                    windVelocity) + snowCavityDowndraftVelocity(windSpeed, windScale);
        }
        flakeWind = windVelocity * windScale;
        if (hasLeeMap != 0) {
            vec2 leeSample = snowLeeSampleAt(pathStart);
            float flakeSmooth = snowSmoothHeightAt(pathStart);
            verticalVelocity = snowLeeVerticalVelocityAt(flakeWorldY, leeSample.x, flakeColumn, flakeSmooth,
                    leeSample.y, ownFall, windSpeed * windScale, leeFallSpeed * windScale);
        }
        verticalVelocity += sinkVelocity;
    }
    vec3 velocity = vec3(flakeWind.x, verticalVelocity, flakeWind.y);
    vec3 step = velocity * stepSeconds;
    float turn = snowFlakeFlutterRate(flake) * stepSeconds;
    vec3 position = snowWrap(state.xyz + step, boxSide);
    float nextPhase = mod(phase + turn, SNOW_TWO_PI);
    vec3 pathEnd = pathStart + step;
    if (any(greaterThanEqual(abs(pathEnd), vec3(halfSide)))) {
        // The flake comes in as a new one; when terrain-following, lee sinking or cavity braking intersects the box,
        // re-enter on a flux-weighted inflow face with height-profiled horizontal wind on the side faces:
        bool cavityInBox = centerBrakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD && centerBrakeHeight > cameraWorldY - halfSide;
        if (hasLeeMap != 0 || cavityInBox) {
            float topVelocityY = -ownFall;
            float bottomVelocityY = -ownFall;
            float topWindScale = snowBrakeWindScaleAt(cameraWorldY + halfSide, centerBrakeHeight);
            float bottomWindScale = snowBrakeWindScaleAt(cameraWorldY - halfSide, centerBrakeHeight);
            if (hasLeeMap != 0) {
                vec2 centerLee = snowLeeSampleAt(vec3(0.0));
                float centerSmooth = snowSmoothHeightAt(vec3(0.0));
                topVelocityY = snowLeeVerticalVelocityAt(cameraWorldY + halfSide, centerLee.x, centerColumn,
                        centerSmooth, centerLee.y, ownFall, windSpeed * topWindScale, leeFallSpeed * topWindScale);
                bottomVelocityY = snowLeeVerticalVelocityAt(cameraWorldY - halfSide, centerLee.x, centerColumn,
                        centerSmooth, centerLee.y, ownFall, windSpeed * bottomWindScale,
                        leeFallSpeed * bottomWindScale);
            }
            topVelocityY += snowCavityDowndraftVelocity(windSpeed, topWindScale);
            bottomVelocityY += snowCavityDowndraftVelocity(windSpeed, bottomWindScale);
            vec3 entryOffset = snowReenterBoxOffset(flake, uint(frameCounter),
                    windVelocity.x * stepSeconds, cameraStep.x, topVelocityY * stepSeconds - cameraStep.y,
                    bottomVelocityY * stepSeconds - cameraStep.y, windVelocity.y * stepSeconds, cameraStep.z,
                    cameraWorldY, centerBrakeHeight, boxSide);
            position = snowWrap(cameraInBox + entryOffset, boxSide);
        }
        vec3 nextCenter = snowNearestOffset(position - cameraInBox, boxSide);
        vec3 drawn = nextCenter + snowFlutterOffset(nextPhase, snowCircleRadiusAt(nextCenter, radiusShare));
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
