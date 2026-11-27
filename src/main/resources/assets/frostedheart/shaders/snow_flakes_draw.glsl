// FHShaders prepends the version line, the stage's define, weather_fog.glsl, weather_fog_excess.glsl,
// snow_flakes.glsl, snow_lee.glsl and lamp_light.glsl, with LAMP_LIGHT_FIELD defined in the vertex stage.
// Draws the snowflakes near the eye as one instanced draw of quads without vertex data. Each instance is the copy of one
// flake of the periodic box that is nearest the camera, which fades out toward the box's faces. A flake is a soft round
// sprite stretched along the path it travels while the shutter is open, and its opacity is spread over the streak so a
// fast flake does not turn into a solid stick. A flake smaller than a pixel keeps its energy at a wider, fainter
// footprint. A flake a few pixels wide turns into its shape from flakeShapes, on a tile sized so the shape blocks as
// much light as the dot, averaged over a streak capped at SNOW_LONGEST_SHAPE_STREAK of its radius. The snow share of
// the fog fades flakes out under cover, each at its own threshold, and the fog between the eye and the flake blends its
// color toward the fog color, as the fog composite fogs the scene. Torches and lamps light flakes and splats by the
// lamp light field, in the lightmap's color as vanilla lights snow blocks.
// A stopped flake is not drawn. With splatPass set, each instance is instead the splat a flake left where it hit a
// block, a quad on the block's face with the flake's own shape, that fades over the second half of splatSeconds.

#ifdef VERTEX_SHADER
uniform sampler2D flakeState;
uniform sampler2D splatState;
uniform int splatPass;
uniform float splatSeconds;
// The view projection without the camera's translation, so a position it takes is the offset from the camera:
uniform mat4 viewProjection;
uniform vec2 screenSize;
// The pixels that a block spans at a clip w of 1:
uniform float pixelsPerBlock;
// The camera's position modulo the box's side, its position relative to the fixed splat origin block, its block and
// place inside it, and the side and the width of the fade at the box's faces, in blocks:
uniform vec3 cameraInBox;
uniform vec3 cameraFromSplatOrigin;
uniform ivec3 cameraBlock;
uniform vec3 cameraFraction;
uniform float boxSide;
uniform float boxFadeWidth;
// The flakes drawn, which may end in a fraction that fades the last ones:
uniform float drawnCount;
// The mean width of a flake, and the mean radius of its flutter circle, in blocks:
uniform float flakeSize;
uniform float flutterRadius;
// The wind along x and z, and the median fall speed, in blocks per second:
uniform vec2 windVelocity;
uniform float fallSpeed;
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
// The half side in flake radii of each shape's tile, as SnowFlakes.shapeTileScale, all 0 while the shapes are not
// ready:
uniform float shapeTileScales[SNOW_SHAPE_COUNT];

// Pixels from the middle of the streak along it and across it:
out vec2 spritePosition;
flat out float spriteHalfLength;
flat out float spriteRadius;
flat out vec4 spriteColor;
// The streak's direction on screen:
flat out vec2 spriteAlong;
// The cosine and sine of the flake's turn on screen, its squash by its tilt, and how much of its shape shows instead of
// a round dot:
flat out vec4 spriteShape;
// The layer of the flake's shape, the half side of its tile in drawn radii, and the mip level to read it at:
flat out vec3 spriteTile;
// The share of the round dot's opacity left after spreading it over the streak:
flat out float spriteStreakShare;

// The smallest clip w of a flake that is drawn, which is the near plane:
const float SNOW_NEAREST_W = 0.05;
// The smallest drawn radius in pixels:
const float SNOW_LEAST_RADIUS_PIXELS = 0.75;
// How far the quad reaches past the streak, in drawn radii, which holds the whole soft edge of the sprite:
const float SNOW_SPRITE_REACH = 1.5;
// The distances in blocks from the fog's eye over which flakes fade in, so none fills the view:
const float SNOW_NEAR_FADE_START = 0.2;
const float SNOW_NEAR_FADE_END = 0.6;
// The flakes over which the last ones fade as the drawn count changes, as SnowFlakes.COUNT_FADE_FLAKES:
const float SNOW_COUNT_FADE = 1024.0;
const float SNOW_LEAST_ALPHA = 1.0 / 512.0;
const float SNOW_PI = 3.14159265359;
// The true radii in pixels over which a flake's shape replaces the round dot:
const float SNOW_SHAPE_START_PIXELS = 2.0;
const float SNOW_SHAPE_FULL_PIXELS = 5.0;
// The squash of a flake seen edge on:
const float SNOW_FLATTEST = 0.45;
// The side of a shape's tile in texels, as SnowFlakes.SHAPE_SIZE, how far the quad of a flake that shows its shape
// reaches as a share of the tile's half side, as SnowFlakes.SHAPE_REACH, and the longest streak of a flake that shows
// its full shape in flake radii, as SnowFlakes.LONGEST_SHAPE_STREAK:
const float SNOW_SHAPE_SIZE = 64.0;
const float SNOW_SHAPE_REACH = 1.1;
const float SNOW_LONGEST_SHAPE_STREAK = 1.0;
// How much wider a splat is than its flake, how far it sits off the face against depth fighting, and how far from the
// face its shelter and its lamp light are read, in blocks:
const float SNOW_SPLAT_SPREAD = 1.3;
const float SNOW_SPLAT_LIFT = 0.01;
const float SNOW_SPLAT_SHARE_REACH = 0.5;
// The mean color of vanilla's snow texture:
const vec3 SNOW_SPLAT_ALBEDO = vec3(0.977, 0.997, 0.997);
// The lightmap coordinates of light.glsl: the clamp of the block axis and full sky light:
const float SNOW_LIGHTMAP_LOWEST = 0.5 / 16.0;
const float SNOW_LIGHTMAP_HIGHEST = 15.5 / 16.0;
const float SNOW_LIGHTMAP_FULL_SKY = 15.0 / 16.0;
// The corners of the quad as a triangle strip:
const vec2 SNOW_CORNERS[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));

void hideFlake() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    spritePosition = vec2(0.0);
    spriteHalfLength = 0.0;
    spriteRadius = 1.0;
    spriteColor = vec4(0.0);
    spriteAlong = vec2(1.0, 0.0);
    spriteShape = vec4(1.0, 0.0, 1.0, 0.0);
    spriteTile = vec3(0.0, 1.0, 0.0);
    spriteStreakShare = 1.0;
}

// The half side in flake radii of the tile of the flake's shape, or 0 while it has none:
float snowTileScale(uint flake) {
    return shapeTileScales[snowFlakeShapeIndex(flake)];
}

// How much of the flake's shape shows instead of the round dot, by its true radius in pixels:
float snowShapeWeight(float tileScale, float trueRadiusPixels) {
    return tileScale > 0.0 ? smoothstep(SNOW_SHAPE_START_PIXELS, SNOW_SHAPE_FULL_PIXELS, trueRadiusPixels) : 0.0;
}

// The tile of the flake's shape for spriteTile, with the mip level at which a texel spans about a pixel along the
// squashed axis:
vec3 snowSpriteTile(uint flake, float tileScale, float drawnRadiusPixels, float squash) {
    float texelsPerPixel = SNOW_SHAPE_SIZE / max(2.0 * tileScale * drawnRadiusPixels * squash, 1.0e-3);
    return vec3(float(snowFlakeShapeIndex(flake)), max(tileScale, 1.0e-3), log2(max(texelsPerPixel, 1.0)));
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

// Draws the splat of the flake with the given index as a quad on the face it hit, turned by the flake's hash:
void drawSplat(int index) {
    uint flake = uint(index);
    vec4 splat = texelFetch(splatState, ivec2(index % SNOW_STATE_WIDTH, index / SNOW_STATE_WIDTH), 0);
    if (!(splat.w >= 0.0)) {
        hideFlake();
        return;
    }
    float faceNumber = floor(splat.w / SNOW_SPLAT_FACE_STRIDE);
    float age = splat.w - faceNumber * SNOW_SPLAT_FACE_STRIDE;
    int face = int(faceNumber);
    vec3 normal = snowFaceNormal(face);
    vec3 offset = splat.xyz - cameraFromSplatOrigin;
    if (any(greaterThan(abs(offset), vec3(0.5 * boxSide)))) {
        hideFlake();
        return;
    }
    vec3 center = offset + normal * SNOW_SPLAT_LIFT;
    vec3 fromFogEye = center - fogEye;
    float distance = length(fromFogEye);
    float countWeight = clamp((drawnCount - float(index)) / SNOW_COUNT_FADE, 0.0, 1.0);
    float lifeWeight = 1.0 - smoothstep(0.5 * splatSeconds, splatSeconds, age);
    // The shelter of the air in front of the face, where the flake was before it hit, and the lee weight at the hit:
    float weight = countWeight * snowBoxWeight(center) * lifeWeight
            * snowShelterWeight(flake, fromFogEye + normal * SNOW_SPLAT_SHARE_REACH)
            * snowLeeWeight(flake, center, cameraBlock, cameraFraction)
            * smoothstep(SNOW_NEAR_FADE_START, SNOW_NEAR_FADE_END, distance);
    vec4 clipCenter = viewProjection * vec4(center, 1.0);
    // A face that turns away from the camera hides its splat:
    if (!(weight > SNOW_LEAST_ALPHA) || !(clipCenter.w > SNOW_NEAREST_W) || dot(normal, center) > 0.0) {
        hideFlake();
        return;
    }
    float trueRadius = 0.5 * flakeSize * snowFlakeSizeShare(flake) * SNOW_SPLAT_SPREAD;
    float drawnRadius = max(trueRadius, SNOW_LEAST_RADIUS_PIXELS * clipCenter.w / pixelsPerBlock);
    float energy = (trueRadius / drawnRadius) * (trueRadius / drawnRadius);
    float trueRadiusPixels = trueRadius * pixelsPerBlock / clipCenter.w;
    float tileScale = snowTileScale(flake);
    float shapeWeight = snowShapeWeight(tileScale, trueRadiusPixels);
    float reach = shapeWeight > 0.0 ? max(SNOW_SPRITE_REACH, SNOW_SHAPE_REACH * tileScale) : SNOW_SPRITE_REACH;
    vec3 tangent = abs(normal.x) > 0.5 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 bitangent = cross(normal, tangent);
    vec2 corner = SNOW_CORNERS[gl_VertexID];
    gl_Position = viewProjection * vec4(center + (tangent * corner.x + bitangent * corner.y)
            * (reach * drawnRadius), 1.0);
    vec2 middlePixel = (clipCenter.xy / clipCenter.w * 0.5 + 0.5) * screenSize;
    // The sprite's coordinates are in drawn radii, so the fragment stage draws the same dot or shape as for a flake:
    spritePosition = corner * reach;
    spriteHalfLength = 0.0;
    spriteRadius = 1.0;
    // A splat is lit as snow lying on the face, by the light of the air in front of it:
    vec3 litColor = snowLampLitColor(offset + normal * SNOW_SPLAT_SHARE_REACH);
    spriteColor = vec4(snowLitColor(litColor, SNOW_SPLAT_ALBEDO * snowFaceShade(face), distance, fromFogEye.y,
            middlePixel), weight * energy);
    spriteAlong = vec2(1.0, 0.0);
    float turn = SNOW_TWO_PI * snowFlakeUnit(flake, SNOW_SALT_TURN);
    spriteShape = vec4(cos(turn), sin(turn), 1.0, shapeWeight);
    spriteTile = snowSpriteTile(flake, tileScale, max(trueRadiusPixels, SNOW_LEAST_RADIUS_PIXELS), 1.0);
    spriteStreakShare = 1.0;
}

void main() {
    int index = gl_InstanceID;
    if (splatPass != 0) {
        drawSplat(index);
        return;
    }
    uint flake = uint(index);
    float countWeight = clamp((drawnCount - float(index)) / SNOW_COUNT_FADE, 0.0, 1.0);
    vec4 state = texelFetch(flakeState, ivec2(index % SNOW_STATE_WIDTH, index / SNOW_STATE_WIDTH), 0);
    vec3 offset = snowNearestOffset(state.xyz - cameraInBox, boxSide);
    float boxWeight = snowBoxWeight(offset);
    if (!(countWeight * boxWeight > 0.0) || state.w >= SNOW_STOPPED_PHASE) {
        hideFlake();
        return;
    }
    float phase = state.w;
    float flutterRate = snowFlakeFlutterRate(flake);
    // A flake whose widest flutter circle lies behind the near plane is hidden before its lookups:
    float widestRadius = (flutterRadius + max(0.0, cavitySwirl)) * SNOW_LARGEST_FLUTTER_RADIUS_SHARE;
    if (!((viewProjection * vec4(offset, 1.0)).w + widestRadius > SNOW_NEAREST_W)) {
        hideFlake();
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
    if (!(clipCenter.w > SNOW_NEAREST_W)) {
        hideFlake();
        return;
    }
    vec3 fromFogEye = center - fogEye;
    float distance = length(fromFogEye);
    float shelterWeight = snowShelterWeight(flake, fromFogEye);
    float nearWeight = smoothstep(SNOW_NEAR_FADE_START, SNOW_NEAR_FADE_END, distance);
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
    float weight = countWeight * boxWeight * shelterWeight * leeWeight * nearWeight;
    if (!(weight > SNOW_LEAST_ALPHA)) {
        hideFlake();
        return;
    }

    float ownFall = fallSpeed * snowFlakeFallShare(flake);
    vec2 localWind = windVelocity * windScale;
    float verticalVelocity = hasLeeMap != 0
            ? snowLeeVerticalVelocityAt(worldY, leeHeight, columnHeight, smoothHeight, terrainSlope, ownFall,
                    length(localWind), leeFallSpeed * windScale)
            : -ownFall;
    if (hasCavityMap != 0) {
        verticalVelocity += snowCavitySinkVelocity(offset, cameraBlock, cameraFraction, brakeHeight, windScale,
                windVelocity) + snowCavityDowndraftVelocity(length(windVelocity), windScale);
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
    vec2 across = vec2(-along.y, along.x);
    float trueRadius = 0.5 * flakeSize * snowFlakeSizeShare(flake) * pixelsPerBlock / clipCenter.w;
    float drawnRadius = max(trueRadius, SNOW_LEAST_RADIUS_PIXELS);
    float energy = (trueRadius / drawnRadius) * (trueRadius / drawnRadius);
    float tileScale = snowTileScale(flake);
    float shapeWeight = snowShapeWeight(tileScale, trueRadius);
    streakLength = min(streakLength, mix(streakLength, SNOW_LONGEST_SHAPE_STREAK * trueRadius, shapeWeight));
    float streakShare = SNOW_PI * drawnRadius / (2.0 * streakLength + SNOW_PI * drawnRadius);

    vec3 color = snowLitColor(snowLampLitColor(center), vec3(flakeBrightness * snowFlakeBrightnessShare(flake)),
            distance, fromFogEye.y, middlePixel);

    vec2 corner = SNOW_CORNERS[gl_VertexID];
    float reach = (shapeWeight > 0.0 ? max(SNOW_SPRITE_REACH, SNOW_SHAPE_REACH * tileScale) : SNOW_SPRITE_REACH)
            * drawnRadius;
    vec2 local = vec2(corner.x * (0.5 * streakLength + reach), corner.y * reach);
    vec2 pixel = middlePixel + along * local.x + across * local.y;
    gl_Position = vec4((pixel / screenSize * 2.0 - 1.0) * clipCenter.w, clipCenter.z, clipCenter.w);
    spritePosition = local;
    spriteHalfLength = 0.5 * streakLength;
    spriteRadius = drawnRadius;
    spriteColor = vec4(color, weight * energy);
    spriteAlong = along;
    // The flake turns and tips once per flutter circle, so both stay smooth where the circle's angle wraps:
    float turn = phase + SNOW_TWO_PI * snowFlakeUnit(flake, SNOW_SALT_TURN);
    float tilt = 2.0 * phase + SNOW_TWO_PI * snowFlakeUnit(flake, SNOW_SALT_TILT);
    float squash = mix(SNOW_FLATTEST, 1.0, 0.5 + 0.5 * cos(tilt));
    spriteShape = vec4(cos(turn), sin(turn), squash, shapeWeight);
    spriteTile = snowSpriteTile(flake, tileScale, drawnRadius, squash);
    spriteStreakShare = streakShare;
}
#endif

#ifdef FRAGMENT_SHADER
in vec2 spritePosition;
flat in float spriteHalfLength;
flat in float spriteRadius;
flat in vec4 spriteColor;
flat in vec2 spriteAlong;
flat in vec4 spriteShape;
flat in vec3 spriteTile;
flat in float spriteStreakShare;
uniform sampler2DArray flakeShapes;
out vec4 fragColor;

// The soft edge of the sprite in drawn radii, which spreads about the area of a disc of the drawn radius:
const float SNOW_PROFILE_INNER = 0.5;
const float SNOW_PROFILE_OUTER = 1.5;
const float SNOW_LEAST_ALPHA = 1.0 / 512.0;
// The reads of the shape spread along a streak, and the half streak in pixels below which one read does:
const int SNOW_STREAK_TAPS = 4;
const float SNOW_SINGLE_TAP_HALF_LENGTH = 0.25;

// The opacity of the flake's shape at a point given in pixels from its middle, along and across the streak. The shape
// turns with the screen, not with the streak, so it does not spin when the view turns:
float snowShapeAt(vec2 point) {
    vec2 screenPoint = spriteAlong * point.x + vec2(-spriteAlong.y, spriteAlong.x) * point.y;
    vec2 turned = vec2(spriteShape.x * screenPoint.x + spriteShape.y * screenPoint.y,
            spriteShape.x * screenPoint.y - spriteShape.y * screenPoint.x) / spriteRadius;
    turned.y /= spriteShape.z;
    vec2 uv = turned / (2.0 * spriteTile.y) + 0.5;
    return textureLod(flakeShapes, vec3(uv, spriteTile.x), spriteTile.z).r;
}

void main() {
    // The offset from the nearest point of the streak, so a moving dot is smeared along its path:
    vec2 offset = vec2(spritePosition.x - clamp(spritePosition.x, -spriteHalfLength, spriteHalfLength),
            spritePosition.y);
    float profile = spriteStreakShare * (1.0 - smoothstep(SNOW_PROFILE_INNER * spriteRadius,
            SNOW_PROFILE_OUTER * spriteRadius, length(offset)));
    if (spriteShape.w > 0.0) {
        // A moving shape is the mean of its copies spread evenly along the streak:
        float shape = 0.0;
        if (spriteHalfLength > SNOW_SINGLE_TAP_HALF_LENGTH) {
            for (int tap = 0; tap < SNOW_STREAK_TAPS; tap++) {
                float along = spriteHalfLength * ((2.0 * float(tap) + 1.0) / float(SNOW_STREAK_TAPS) - 1.0);
                shape += snowShapeAt(spritePosition - vec2(along, 0.0));
            }
            shape /= float(SNOW_STREAK_TAPS);
        } else {
            shape = snowShapeAt(spritePosition);
        }
        profile = mix(profile, shape, spriteShape.w);
    }
    float alpha = spriteColor.a * profile;
    if (!(alpha > SNOW_LEAST_ALPHA)) {
        discard;
    }
    fragColor = vec4(spriteColor.rgb, alpha);
}
#endif
