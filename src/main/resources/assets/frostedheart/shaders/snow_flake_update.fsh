// FHShaders prepends the version line, LAMP_LIGHT_FIELD, weather_fog.glsl, weather_fog_excess.glsl, snow_flakes.glsl,
// snow_lee.glsl and lamp_light.glsl.
// Moves every snowflake by one frame and precomputes the draw look of visible flakes and splats into four extra color
// targets so both snow draws only project them. A flake's state holds its position in the periodic box in xyz and the
// angle of its flutter circle in w, raised by SNOW_STOPPED_PHASE while the flake is stopped. Its splat holds the point
// where it last hit a block, relative to the fixed splat origin block set on each fill, and in w its face times
// SNOW_SPLAT_FACE_STRIDE plus the seconds since the hit, or -1 when it has no splat.
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
// Camera projection and screen parameters for conservative frustum culling and froxel lookup:
uniform mat4 viewProjection;
uniform vec2 screenSize;
uniform float pixelsPerBlock;
uniform vec4 frustumPlaneScales;
uniform float maxShapeReach;
// Flake look parameters shared with the draw pass:
uniform float boxFadeWidth;
uniform float drawnCount;
uniform float flakeSize;
uniform float shutterSeconds;
uniform float flakeBrightness;
// The snow fog color, as display values:
uniform vec3 snowColor;
// Vanilla's lightmap, filtered linearly as for terrain:
uniform sampler2D lampLightmap;
// The fog's extinction per block at its eye and its height scale in blocks:
uniform float eyeExtinction;
uniform float heightScale;
// The fog's eye as an offset from the camera, and its place inside its block:
uniform vec3 fogEye;
uniform vec3 fogEyeFraction;
// The share of every cell of the fog's region cube, whether it is bound, and its layout as FogShareVolume.SIDE, ORIGIN
// and TILES_ACROSS:
uniform sampler2D shareVolume;
uniform int hasShareVolume;
uniform ivec3 shareLayout;

out vec4 nextState;
out vec4 nextSplat;
out vec4 flakeDrawColor;
out vec4 flakeDrawPos;
out vec4 flakeDrawStreak;
out vec4 splatDrawColor;

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
// Visibility and lighting constants matching snow_flakes_draw.glsl and SnowFlakes:
const float SNOW_NEAREST_W = 0.05;
const float SNOW_LEAST_RADIUS_PIXELS = 0.75;
const float SNOW_SPRITE_REACH = 1.5;
const float SNOW_SHAPE_REACH = 1.1;
const float SNOW_CORNER_DIAGONAL = 1.42;
const float SNOW_NEAR_FADE_START = 0.2;
const float SNOW_NEAR_FADE_END = 0.6;
const float SNOW_COUNT_FADE = 1024.0;
const float SNOW_LEAST_ALPHA = 1.0 / 512.0;
const float SNOW_SPLAT_SPREAD = 1.3;
const float SNOW_SPLAT_LIFT = 0.01;
const float SNOW_SPLAT_SHARE_REACH = 0.5;
const vec3 SNOW_SPLAT_ALBEDO = vec3(0.977, 0.997, 0.997);
const float SNOW_LIGHTMAP_LOWEST = 0.5 / 16.0;
const float SNOW_LIGHTMAP_HIGHEST = 15.5 / 16.0;
const float SNOW_LIGHTMAP_FULL_SKY = 15.0 / 16.0;

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

// The share of a cell given as an offset from the fog's eye block, as snowWalkCellShare reads the share volume. A cell
// outside the volume counts as open:
float snowCellShare(ivec3 cell) {
    if (any(lessThan(cell, ivec3(-shareLayout.y))) || any(greaterThan(cell, ivec3(shareLayout.x - shareLayout.y - 1)))) {
        return 1.0;
    }
    int slice = cell.y + shareLayout.y;
    int atlasX = cell.x + shareLayout.y + (slice % shareLayout.z) * shareLayout.x;
    int atlasY = cell.z + shareLayout.y + (slice / shareLayout.z) * shareLayout.x;
    return texelFetch(shareVolume, ivec2(atlasX, atlasY), 0).r;
}

// The share at a point given in blocks from the fog's eye block, blended between the eight cells whose middles surround
// it, so a flake's share changes smoothly as it moves:
float snowShareAt(vec3 point) {
    vec3 shifted = point - vec3(0.5);
    vec3 base = floor(shifted);
    vec3 weight = shifted - base;
    ivec3 cell = ivec3(base);
    float low = mix(mix(snowCellShare(cell), snowCellShare(cell + ivec3(1, 0, 0)), weight.x),
            mix(snowCellShare(cell + ivec3(0, 0, 1)), snowCellShare(cell + ivec3(1, 0, 1)), weight.x), weight.z);
    float high = mix(mix(snowCellShare(cell + ivec3(0, 1, 0)), snowCellShare(cell + ivec3(1, 1, 0)), weight.x),
            mix(snowCellShare(cell + ivec3(0, 1, 1)), snowCellShare(cell + ivec3(1, 1, 1)), weight.x), weight.z);
    return mix(low, high, weight.y);
}

// How much of a flake or splat shows at an offset from the camera, which fades out toward the box's faces:
float snowBoxWeight(vec3 offset) {
    float boxDistance = max(abs(offset.x), max(abs(offset.y), abs(offset.z)));
    return 1.0 - smoothstep(0.5 * boxSide - boxFadeWidth, 0.5 * boxSide, boxDistance);
}

// How much of a flake shows under the cover at a point given from the fog's eye:
float snowShelterWeight(uint flake, vec3 fromFogEye) {
    float share = hasShareVolume != 0 ? snowShareAt(fogEyeFraction + fromFogEye) : 1.0;
    return clamp((share - snowFlakeShelterThreshold(flake)) / SNOW_SHELTER_SOFTNESS, 0.0, 1.0);
}

// The color of a white top face at full sky light at an offset from the camera, lit by the lamps as light.glsl lights
// terrain from the lightmap. Without lamp light it is the fog color:
vec3 snowLampLitColor(vec3 offset) {
    float level = lampShownLevelAt(offset);
    if (!(level > 0.0)) {
        return snowColor;
    }
    vec3 lit = texture(lampLightmap, vec2(clamp(15.0 * level / 16.0, SNOW_LIGHTMAP_LOWEST, SNOW_LIGHTMAP_HIGHEST),
            SNOW_LIGHTMAP_FULL_SKY)).rgb;
    vec3 unlit = texture(lampLightmap, vec2(SNOW_LIGHTMAP_LOWEST, SNOW_LIGHTMAP_FULL_SKY)).rgb;
    return snowColor + max(lit - unlit, vec3(0.0));
}

// The color of a flake or splat of the given color relative to the color of a white top face where it is, which the
// fog between the eye and it brings toward the fog color:
vec3 snowLitColor(vec3 litColor, vec3 relativeColor, float distance, float heightFromFogEye, vec2 pixel) {
    vec2 uv = pixel / (float(divisor) * vec2(columnCount, rowCount));
    float tau = clamp(fogOpticalDepth(eyeExtinction, distance, heightFromFogEye, heightScale)
            + froxelExcessAtDistance(distance, uv), 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
    return mix(snowColor, litColor * relativeColor, exp(-tau));
}

// The shade vanilla gives a block face numbered as Direction.get3DDataValue in a world without constant ambient light:
float snowFaceShade(int face) {
    if (face == 0) {
        return 0.5;
    }
    if (face == 1) {
        return 1.0;
    }
    return face == 2 || face == 3 ? 0.8 : 0.6;
}

// The outward normal of a block face numbered as Direction.get3DDataValue:
vec3 snowFaceNormal(int face) {
    if (face == 0) {
        return vec3(0.0, -1.0, 0.0);
    }
    if (face == 1) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (face == 2) {
        return vec3(0.0, 0.0, -1.0);
    }
    if (face == 3) {
        return vec3(0.0, 0.0, 1.0);
    }
    return face == 4 ? vec3(-1.0, 0.0, 0.0) : vec3(1.0, 0.0, 0.0);
}

// Conservative clip-space sphere test matching SnowFlakes.isOutsideFrustum:
bool isOutsideFrustum(float clipX, float clipY, float clipW, float radiusBlocks, float minimumRadiusPixels) {
    float positiveW = max(clipW + radiusBlocks, 0.0);
    float padX = (2.0 * minimumRadiusPixels / max(screenSize.x, 1.0)) * positiveW;
    float padY = (2.0 * minimumRadiusPixels / max(screenSize.y, 1.0)) * positiveW;
    return clipX < -(clipW + radiusBlocks * frustumPlaneScales.x + padX)
            || clipX > clipW + radiusBlocks * frustumPlaneScales.y + padX
            || clipY < -(clipW + radiusBlocks * frustumPlaneScales.z + padY)
            || clipY > clipW + radiusBlocks * frustumPlaneScales.w + padY;
}

void prepareFlakeDraw(uint flake, vec4 state) {
    flakeDrawColor = vec4(0.0);
    flakeDrawPos = vec4(0.0);
    flakeDrawStreak = vec4(0.0);
    float countWeight = clamp((drawnCount - float(flake)) / SNOW_COUNT_FADE, 0.0, 1.0);
    vec3 offset = snowNearestOffset(state.xyz - cameraInBox, boxSide);
    float boxWeight = snowBoxWeight(offset);
    if (!(countWeight * boxWeight > 0.0) || state.w >= SNOW_STOPPED_PHASE) {
        return;
    }
    float phase = state.w;
    float flutterRate = snowFlakeFlutterRate(flake);
    // A flake whose widest flutter circle lies behind the near plane is hidden before its lookups:
    float widestRadius = (flutterRadius + max(0.0, cavitySwirl)) * SNOW_LARGEST_FLUTTER_RADIUS_SHARE;
    vec4 clipOffset = viewProjection * vec4(offset, 1.0);
    if (!(clipOffset.w + widestRadius > SNOW_NEAREST_W)) {
        return;
    }
    float trueRadiusBlocks = 0.5 * flakeSize * snowFlakeSizeShare(flake);
    float ownFall = fallSpeed * snowFlakeFallShare(flake);
    float windSpeed = length(windVelocity);
    float maxLeeFall = hasLeeMap != 0 ? max(0.0, leeFallSpeed) : 0.0;
    float maxVerticalSpeed = ownFall + maxLeeFall + 3.0 * windSpeed;
    float maxHalfStreak = 0.5 * max(0.0, shutterSeconds)
            * (windSpeed + maxVerticalSpeed + widestRadius * abs(flutterRate));
    float maxReachFactor = max(SNOW_SPRITE_REACH, SNOW_SHAPE_REACH * maxShapeReach);
    // A factor of 2 covers the perspective ratio between clipCenter.w and either streak end when both are in front:
    float maxQuadReachBlocks = 2.0 * SNOW_CORNER_DIAGONAL * maxReachFactor * trueRadiusBlocks;
    float minRadiusPixels = SNOW_CORNER_DIAGONAL * SNOW_SPRITE_REACH * SNOW_LEAST_RADIUS_PIXELS;
    if (isOutsideFrustum(clipOffset.x, clipOffset.y, clipOffset.w,
            widestRadius + maxHalfStreak + maxQuadReachBlocks, minRadiusPixels)) {
        return;
    }
    // The flake reads its braking, swirl and lee at the center of its flutter circle, as the update moves it:
    float worldY = float(cameraBlock.y) + cameraFraction.y + offset.y;
    float columnHeight = (hasLeeMap != 0 || hasCavityMap != 0)
            ? snowLeeColumnHeight(offset, cameraBlock, cameraFraction) : SNOW_LEE_NO_HEIGHT;
    float brakeHeight = SNOW_LEE_NO_HEIGHT;
    float cavityOpen = 1.0;
    if (hasCavityMap != 0) {
        vec2 cavity = snowCavitySampleAt(offset, cameraBlock, cameraFraction);
        brakeHeight = snowCavityBrakeHeight(cavity.x, cavity.y, columnHeight);
        cavityOpen = snowBrakeOpenFactorAt(worldY, brakeHeight);
    }
    float circleRadius = snowCavityCircleRadius(flutterRadius, cavityOpen, snowFlakeFlutterRadiusShare(flake));
    vec3 center = offset + snowFlutterOffset(phase, circleRadius);
    vec4 clipCenter = viewProjection * vec4(center, 1.0);
    if (!(clipCenter.w > SNOW_NEAREST_W) || abs(clipCenter.z) > clipCenter.w) {
        return;
    }
    if (isOutsideFrustum(clipCenter.x, clipCenter.y, clipCenter.w,
            maxHalfStreak + maxQuadReachBlocks, minRadiusPixels)) {
        return;
    }
    vec3 fromFogEye = center - fogEye;
    float distance = length(fromFogEye);
    float nearWeight = smoothstep(SNOW_NEAR_FADE_START, SNOW_NEAR_FADE_END, distance);
    float baseWeight = countWeight * boxWeight * nearWeight;
    if (!(baseWeight > SNOW_LEAST_ALPHA)) {
        return;
    }
    float shelterWeight = snowShelterWeight(flake, fromFogEye);
    if (!(baseWeight * shelterWeight > SNOW_LEAST_ALPHA)) {
        return;
    }
    float leeHeight = SNOW_LEE_NO_HEIGHT;
    float terrainSlope = 0.0;
    float smoothHeight = SNOW_LEE_NO_HEIGHT;
    float windScale = snowCavityWindScale(cavityOpen);
    float leeWeight = 1.0;
    if (hasLeeMap != 0) {
        vec2 leeSample = snowLeeSampleAt(offset);
        leeHeight = leeSample.x;
        terrainSlope = leeSample.y;
        smoothHeight = snowSmoothHeightAt(offset);
        leeWeight = snowLeeWeightAt(flake, worldY, leeHeight, columnHeight, smoothHeight, cavityOpen);
    }
    float weight = baseWeight * shelterWeight * leeWeight;
    if (!(weight > SNOW_LEAST_ALPHA)) {
        return;
    }
    float trueRadiusPixels = trueRadiusBlocks * pixelsPerBlock / clipCenter.w;
    float drawnRadius = max(trueRadiusPixels, SNOW_LEAST_RADIUS_PIXELS);
    float energy = (trueRadiusPixels / drawnRadius) * (trueRadiusPixels / drawnRadius);
    if (!(weight * energy > SNOW_LEAST_ALPHA)) {
        return;
    }

    vec2 localWind = windVelocity * windScale;
    float verticalVelocity = hasLeeMap != 0
            ? snowLeeVerticalVelocityAt(worldY, leeHeight, columnHeight, smoothHeight, terrainSlope, ownFall,
                    length(localWind), leeFallSpeed * windScale)
            : -ownFall;
    if (hasCavityMap != 0) {
        verticalVelocity += snowCavitySinkVelocity(offset, cameraBlock, cameraFraction, brakeHeight, windScale,
                windVelocity) + snowCavityDowndraftVelocity(windSpeed, windScale);
    }
    vec3 velocity = vec3(localWind.x, verticalVelocity, localWind.y)
            + vec3(-sin(phase), 0.0, cos(phase)) * (circleRadius * flutterRate);
    vec3 halfStreak = 0.5 * shutterSeconds * velocity;
    vec4 clipStart = viewProjection * vec4(center - halfStreak, 1.0);
    vec4 clipEnd = viewProjection * vec4(center + halfStreak, 1.0);
    if (!(clipStart.w > SNOW_NEAREST_W) || !(clipEnd.w > SNOW_NEAREST_W)) {
        clipStart = clipCenter;
        clipEnd = clipCenter;
    }
    vec2 startPixel = (clipStart.xy / clipStart.w * 0.5 + 0.5) * screenSize;
    vec2 endPixel = (clipEnd.xy / clipEnd.w * 0.5 + 0.5) * screenSize;
    vec2 middlePixel = 0.5 * (startPixel + endPixel);
    vec2 axis = endPixel - startPixel;
    float streakLength = length(axis);
    vec2 along = streakLength > 1.0e-4 ? axis / streakLength : vec2(1.0, 0.0);
    float maxReachPixels = maxReachFactor * drawnRadius;
    float halfExtentX = abs(along.x) * (0.5 * streakLength + maxReachPixels) + abs(along.y) * maxReachPixels;
    float halfExtentY = abs(along.y) * (0.5 * streakLength + maxReachPixels) + abs(along.x) * maxReachPixels;
    if (middlePixel.x + halfExtentX < 0.0 || middlePixel.x - halfExtentX > screenSize.x
            || middlePixel.y + halfExtentY < 0.0 || middlePixel.y - halfExtentY > screenSize.y) {
        return;
    }
    vec3 color = snowLitColor(snowLampLitColor(center), vec3(flakeBrightness * snowFlakeBrightnessShare(flake)),
            distance, fromFogEye.y, middlePixel);
    flakeDrawColor = vec4(color, weight);
    flakeDrawPos = vec4(center, phase);
    flakeDrawStreak = vec4(halfStreak, trueRadiusBlocks);
}

void prepareSplatDraw(uint flake, vec4 splat) {
    splatDrawColor = vec4(0.0);
    if (!(splatSeconds > 0.0) || !(splat.w >= 0.0) || !(float(flake) < drawnCount)) {
        return;
    }
    float faceNumber = floor(splat.w / SNOW_SPLAT_FACE_STRIDE);
    float age = splat.w - faceNumber * SNOW_SPLAT_FACE_STRIDE;
    int face = int(faceNumber);
    vec3 normal = snowFaceNormal(face);
    vec3 offset = splat.xyz - cameraFromSplatOrigin;
    if (any(greaterThan(abs(offset), vec3(0.5 * boxSide)))) {
        return;
    }
    vec3 center = offset + normal * SNOW_SPLAT_LIFT;
    vec4 clipCenter = viewProjection * vec4(center, 1.0);
    // A face that turns away from the camera hides its splat:
    if (!(clipCenter.w > SNOW_NEAREST_W) || dot(normal, center) > 0.0) {
        return;
    }
    float trueRadius = 0.5 * flakeSize * snowFlakeSizeShare(flake) * SNOW_SPLAT_SPREAD;
    float drawnRadius = max(trueRadius, SNOW_LEAST_RADIUS_PIXELS * clipCenter.w / pixelsPerBlock);
    float maxReachFactor = max(SNOW_SPRITE_REACH, SNOW_SHAPE_REACH * maxShapeReach);
    if (isOutsideFrustum(clipCenter.x, clipCenter.y, clipCenter.w,
            SNOW_CORNER_DIAGONAL * maxReachFactor * drawnRadius, 0.0)) {
        return;
    }
    vec3 fromFogEye = center - fogEye;
    float distance = length(fromFogEye);
    float countWeight = clamp((drawnCount - float(flake)) / SNOW_COUNT_FADE, 0.0, 1.0);
    float lifeWeight = 1.0 - smoothstep(0.5 * splatSeconds, splatSeconds, age);
    float nearWeight = smoothstep(SNOW_NEAR_FADE_START, SNOW_NEAR_FADE_END, distance);
    float baseWeight = countWeight * snowBoxWeight(center) * lifeWeight * nearWeight;
    float energy = (trueRadius / drawnRadius) * (trueRadius / drawnRadius);
    if (!(baseWeight * energy > SNOW_LEAST_ALPHA)) {
        return;
    }
    // The shelter of the air in front of the face, where the flake was before it hit, and the lee weight at the hit:
    float weight = baseWeight * snowShelterWeight(flake, fromFogEye + normal * SNOW_SPLAT_SHARE_REACH);
    if (!(weight * energy > SNOW_LEAST_ALPHA)) {
        return;
    }
    weight *= snowLeeWeight(flake, center, cameraBlock, cameraFraction);
    if (!(weight * energy > SNOW_LEAST_ALPHA)) {
        return;
    }
    vec2 middlePixel = (clipCenter.xy / clipCenter.w * 0.5 + 0.5) * screenSize;
    // A splat is lit as snow lying on the face, by the light of the air in front of it:
    vec3 litColor = snowLampLitColor(offset + normal * SNOW_SPLAT_SHARE_REACH);
    vec3 color = snowLitColor(litColor, SNOW_SPLAT_ALBEDO * snowFaceShade(face), distance, fromFogEye.y, middlePixel);
    splatDrawColor = vec4(color, weight);
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    uint flake = snowFlakeIndex(texel);
    float radiusShare = snowFlakeFlutterRadiusShare(flake);
    float circleRadius = flutterRadius * radiusShare;
    vec4 outState;
    vec4 outSplat;
    if (fillState != 0) {
        vec3 placed = vec3(snowFlakeUnit(flake, SNOW_SALT_POSITION_X), snowFlakeUnit(flake, SNOW_SALT_POSITION_Y),
                snowFlakeUnit(flake, SNOW_SALT_POSITION_Z)) * boxSide;
        float placedPhase = snowFlakeUnit(flake, SNOW_SALT_FLUTTER_PHASE) * SNOW_TWO_PI;
        vec3 placedCenter = snowNearestOffset(placed - cameraInBox, boxSide);
        vec3 drawn = placedCenter + snowFlutterOffset(placedPhase, snowCircleRadiusAt(placedCenter, radiusShare));
        bool placedInside = collides != 0 && snowHitId(drawn, flake) != 0u;
        outState = vec4(placed, placedPhase + (placedInside ? SNOW_STOPPED_PHASE : 0.0));
        outSplat = vec4(0.0, 0.0, 0.0, -1.0);
    } else if (!(stepSeconds > 0.0)) {
        outState = texelFetch(previousState, texel, 0);
        outSplat = texelFetch(previousSplats, texel, 0);
    } else {
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
                sinkVelocity = snowCavitySinkVelocity(pathStart, cameraBlock, cameraFraction, flakeBrakeHeight,
                        windScale, windVelocity) + snowCavityDowndraftVelocity(windSpeed, windScale);
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
            // The flake comes in as a new one; when terrain-following, lee sinking or cavity braking intersects the
            // box, re-enter on a flux-weighted inflow face with height-profiled horizontal wind on the side faces:
            bool cavityInBox = centerBrakeHeight > SNOW_LEE_NO_HEIGHT_THRESHOLD
                    && centerBrakeHeight > cameraWorldY - halfSide;
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
        outState = vec4(position, nextPhase + (stopped ? SNOW_STOPPED_PHASE : 0.0));
        outSplat = splat;
    }
    nextState = outState;
    nextSplat = outSplat;
    prepareFlakeDraw(flake, outState);
    prepareSplatDraw(flake, outSplat);
}
