// FHShaders prepends the version line, the stage's define and snow_flakes.glsl.
// Projects the compacted visible snowflakes and splats produced by snow_flake_update.fsh and snow_flakes_compact.glsl.
// The vertex stage computes quad parameters once per visible item, the geometry stage expands each point into a quad,
// and the fragment stage shades the dot or snowflake shape.

#ifdef VERTEX_SHADER
in uint visibleEntry;

uniform sampler2D flakeDrawPos;
uniform sampler2D flakeDrawColor;
uniform sampler2D flakeDrawStreak;
uniform sampler2D splatState;
uniform sampler2D splatDrawColor;
// The view projection without the camera's translation, so a position it takes is the offset from the camera:
uniform mat4 viewProjection;
uniform vec2 screenSize;
// The pixels that a block spans at a clip w of 1:
uniform float pixelsPerBlock;
// The camera's position relative to the fixed splat origin block:
uniform vec3 cameraFromSplatOrigin;
// The mean width of a flake in blocks:
uniform float flakeSize;
// The half side in flake radii of each shape's tile, as SnowFlakes.shapeTileScale, all 0 while the shapes are not
// ready:
uniform float shapeTileScales[SNOW_SHAPE_COUNT];

out vec4 vClipOrigin;
out vec4 vClipStepX;
out vec4 vClipStepY;
out vec2 vSpriteHalfExtent;
out vec4 vSpriteGeom;
out vec4 vSpriteColor;
out vec4 vSpriteShape;
out vec4 vSpriteTileStreak;

// The smallest clip w of a flake that is drawn, which is the near plane:
const float SNOW_NEAREST_W = 0.05;
// The smallest drawn radius in pixels:
const float SNOW_LEAST_RADIUS_PIXELS = 0.75;
// How far the quad reaches past the streak, in drawn radii, which holds the whole soft edge of the sprite:
const float SNOW_SPRITE_REACH = 1.5;
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
// How much wider a splat is than its flake and how far it sits off the face against depth fighting, in blocks:
const float SNOW_SPLAT_SPREAD = 1.3;
const float SNOW_SPLAT_LIFT = 0.01;
const uint SNOW_SPLAT_FLAG = 0x80000000u;
const uint SNOW_INDEX_MASK = 0x7FFFFFFFu;

void hideFlake() {
    vClipOrigin = vec4(2.0, 2.0, 2.0, 1.0);
    vClipStepX = vec4(0.0);
    vClipStepY = vec4(0.0);
    vSpriteHalfExtent = vec2(0.0);
    vSpriteGeom = vec4(1.0, 0.0, 0.0, 1.0);
    vSpriteColor = vec4(0.0);
    vSpriteShape = vec4(1.0, 0.0, 1.0, 0.0);
    vSpriteTileStreak = vec4(0.0, 1.0, 0.0, 1.0);
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

// Projects the precomputed splat of the flake with the given index onto the face it hit:
void projectSplat(int index) {
    ivec2 texel = ivec2(index % SNOW_STATE_WIDTH, index / SNOW_STATE_WIDTH);
    vec4 look = texelFetch(splatDrawColor, texel, 0);
    if (!(look.a > SNOW_LEAST_ALPHA)) {
        hideFlake();
        return;
    }
    uint flake = uint(index);
    vec4 splat = texelFetch(splatState, texel, 0);
    int face = int(floor(splat.w / SNOW_SPLAT_FACE_STRIDE));
    vec3 normal = snowFaceNormal(face);
    vec3 center = splat.xyz - cameraFromSplatOrigin + normal * SNOW_SPLAT_LIFT;
    vec4 clipCenter = viewProjection * vec4(center, 1.0);
    if (!(clipCenter.w > SNOW_NEAREST_W)) {
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
    float worldExtent = reach * drawnRadius;
    vClipOrigin = clipCenter;
    vClipStepX = viewProjection * vec4(tangent * worldExtent, 0.0);
    vClipStepY = viewProjection * vec4(bitangent * worldExtent, 0.0);
    vSpriteHalfExtent = vec2(reach, reach);
    vSpriteGeom = vec4(1.0, 0.0, 0.0, 1.0);
    vSpriteColor = vec4(look.rgb, look.a * energy);
    float turn = SNOW_TWO_PI * snowFlakeUnit(flake, SNOW_SALT_TURN);
    vSpriteShape = vec4(cos(turn), sin(turn), 1.0, shapeWeight);
    vSpriteTileStreak = vec4(snowSpriteTile(flake, tileScale, max(trueRadiusPixels, SNOW_LEAST_RADIUS_PIXELS), 1.0),
            1.0);
}

void main() {
    int index = int(visibleEntry & SNOW_INDEX_MASK);
    if ((visibleEntry & SNOW_SPLAT_FLAG) != 0u) {
        projectSplat(index);
        return;
    }
    ivec2 texel = ivec2(index % SNOW_STATE_WIDTH, index / SNOW_STATE_WIDTH);
    vec4 look = texelFetch(flakeDrawColor, texel, 0);
    if (!(look.a > SNOW_LEAST_ALPHA)) {
        hideFlake();
        return;
    }
    uint flake = uint(index);
    vec4 posPhase = texelFetch(flakeDrawPos, texel, 0);
    vec4 streakRadius = texelFetch(flakeDrawStreak, texel, 0);
    vec3 center = posPhase.xyz;
    float phase = posPhase.w;
    vec3 halfStreak = streakRadius.xyz;
    vec4 clipCenter = viewProjection * vec4(center, 1.0);
    if (!(clipCenter.w > SNOW_NEAREST_W)) {
        hideFlake();
        return;
    }
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
    float trueRadius = streakRadius.w * pixelsPerBlock / clipCenter.w;
    float drawnRadius = max(trueRadius, SNOW_LEAST_RADIUS_PIXELS);
    float energy = (trueRadius / drawnRadius) * (trueRadius / drawnRadius);
    float tileScale = snowTileScale(flake);
    float shapeWeight = snowShapeWeight(tileScale, trueRadius);
    streakLength = min(streakLength, mix(streakLength, SNOW_LONGEST_SHAPE_STREAK * trueRadius, shapeWeight));
    float streakShare = SNOW_PI * drawnRadius / (2.0 * streakLength + SNOW_PI * drawnRadius);
    float reach = (shapeWeight > 0.0 ? max(SNOW_SPRITE_REACH, SNOW_SHAPE_REACH * tileScale) : SNOW_SPRITE_REACH)
            * drawnRadius;
    float halfX = 0.5 * streakLength + reach;
    float halfY = reach;
    vec2 pixelToClip = (2.0 * clipCenter.w) / screenSize;
    vClipOrigin = vec4((middlePixel / screenSize * 2.0 - 1.0) * clipCenter.w, clipCenter.z, clipCenter.w);
    vClipStepX = vec4(along * halfX * pixelToClip, 0.0, 0.0);
    vClipStepY = vec4(across * halfY * pixelToClip, 0.0, 0.0);
    vSpriteHalfExtent = vec2(halfX, halfY);
    vSpriteGeom = vec4(along, 0.5 * streakLength, drawnRadius);
    vSpriteColor = vec4(look.rgb, look.a * energy);
    // The flake turns and tips once per flutter circle, so both stay smooth where the circle's angle wraps:
    float turn = phase + SNOW_TWO_PI * snowFlakeUnit(flake, SNOW_SALT_TURN);
    float tilt = 2.0 * phase + SNOW_TWO_PI * snowFlakeUnit(flake, SNOW_SALT_TILT);
    float squash = mix(SNOW_FLATTEST, 1.0, 0.5 + 0.5 * cos(tilt));
    vSpriteShape = vec4(cos(turn), sin(turn), squash, shapeWeight);
    vSpriteTileStreak = vec4(snowSpriteTile(flake, tileScale, drawnRadius, squash), streakShare);
}
#endif

#ifdef GEOMETRY_SHADER
layout(points) in;
layout(triangle_strip, max_vertices = 4) out;

in vec4 vClipOrigin[];
in vec4 vClipStepX[];
in vec4 vClipStepY[];
in vec2 vSpriteHalfExtent[];
in vec4 vSpriteGeom[];
in vec4 vSpriteColor[];
in vec4 vSpriteShape[];
in vec4 vSpriteTileStreak[];

out vec2 spritePosition;
flat out float spriteHalfLength;
flat out float spriteRadius;
flat out vec4 spriteColor;
flat out vec2 spriteAlong;
flat out vec4 spriteShape;
flat out vec3 spriteTile;
flat out float spriteStreakShare;

const vec2 SNOW_CORNERS[4] = vec2[4](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));

void main() {
    vec4 color = vSpriteColor[0];
    if (!(color.a > 0.0)) {
        return;
    }
    vec4 origin = vClipOrigin[0];
    vec4 stepX = vClipStepX[0];
    vec4 stepY = vClipStepY[0];
    vec2 halfExtent = vSpriteHalfExtent[0];
    vec4 geom = vSpriteGeom[0];
    vec4 shape = vSpriteShape[0];
    vec4 tileStreak = vSpriteTileStreak[0];
    for (int cornerIndex = 0; cornerIndex < 4; cornerIndex++) {
        vec2 corner = SNOW_CORNERS[cornerIndex];
        gl_Position = origin + corner.x * stepX + corner.y * stepY;
        spritePosition = corner * halfExtent;
        spriteHalfLength = geom.z;
        spriteRadius = geom.w;
        spriteColor = color;
        spriteAlong = geom.xy;
        spriteShape = shape;
        spriteTile = tileStreak.xyz;
        spriteStreakShare = tileStreak.w;
        EmitVertex();
    }
    EndPrimitive();
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
uniform sampler2D translucentDepth;
uniform int clipAgainstTranslucent;
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
    if (clipAgainstTranslucent != 0) {
        float tDepth = texelFetch(translucentDepth, ivec2(gl_FragCoord.xy), 0).r;
        if (!(gl_FragCoord.z > tDepth)) {
            discard;
        }
    }
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
