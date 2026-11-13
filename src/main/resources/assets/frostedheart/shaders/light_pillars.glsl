// FHShaders prepends the version line and defines VERTEX_SHADER or FRAGMENT_SHADER.
// Light pillars as in calculated-sky.md. pillarRadiance matches LightPillarOptics.dustRadiance and widestLateralSpread.
// Each pillar has one ribbon per plate kind that covers it on the screen, and the fragment shader computes its light from the crystals in front of the scene.
// Takes positions relative to the eye, which view bobbing moves away from the camera:
uniform mat4 viewProjection;
// Light positions relative to the eye, then the horizon around the light toward the eye in radians:
uniform vec4 lights[48];
// Colors times strength and brightness, then the opacity:
uniform vec4 colors[48];
uniform vec2 tiltSigmas;
uniform vec2 shares;
uniform float refractiveIndex;
uniform float extinction;
uniform float dustBottomHeight;
uniform float scaleHeight;
uniform float lateralBlur;
uniform float marginAngle;
uniform float visibleValue;
uniform float highestElevation;

const float PI = 3.14159265358979;
// A scene distance beyond every crystal, for directions where no scene stands:
const float NO_SCENE_DISTANCE = 1.0e30;
const int NODE_COUNT = 8;
const float NODES[8] = float[8](-0.9602898564975363, -0.7966664774136267, -0.5255324099163290,
        -0.1834346424956498, 0.1834346424956498, 0.5255324099163290, 0.7966664774136267, 0.9602898564975363);
const float WEIGHTS[8] = float[8](0.1012285362903763, 0.2223810344533745, 0.3137066458778873,
        0.3626837833783620, 0.3626837833783620, 0.3137066458778873, 0.2223810344533745, 0.1012285362903763);
const float TILT_RANGE_SIGMAS = 4.0;
const float SMALLEST_RISE = 0.01;
const float SMALLEST_SINE_AT_CRYSTAL = 1.0e-6;
const float MOST_RIBBON_SPREADS = 8.0;

float slabReflectance(float sineOfGrazingAngle) {
    float cosineOfIncidence = clamp(sineOfGrazingAngle, 0.0, 1.0);
    float squaredSineOfIncidence = 1.0 - cosineOfIncidence * cosineOfIncidence;
    float cosineOfRefraction = sqrt(1.0 - squaredSineOfIncidence / (refractiveIndex * refractiveIndex));
    float perpendicular = (cosineOfIncidence - refractiveIndex * cosineOfRefraction)
            / (cosineOfIncidence + refractiveIndex * cosineOfRefraction);
    float parallel = (refractiveIndex * cosineOfIncidence - cosineOfRefraction)
            / (refractiveIndex * cosineOfIncidence + cosineOfRefraction);
    float perpendicularFace = perpendicular * perpendicular;
    float parallelFace = parallel * parallel;
    return perpendicularFace / (1.0 + perpendicularFace) + parallelFace / (1.0 + parallelFace);
}

float dustDensity(float height) {
    return exp(-max(0.0, height - dustBottomHeight) / scaleHeight);
}

float dustColumn(float height) {
    float above = height - dustBottomHeight;
    return above <= 0.0 ? above : scaleHeight * (1.0 - exp(-above / scaleHeight));
}

float dustOpticalDepth(float fromHeight, float toHeight, float pathLength) {
    float rise = toHeight - fromHeight;
    float meanDensity = abs(rise) > SMALLEST_RISE ? (dustColumn(toHeight) - dustColumn(fromHeight)) / rise
            : dustDensity(0.5 * (fromHeight + toHeight));
    return extinction * pathLength * meanDensity;
}

float gaussian(float value, float variance) {
    return exp(-value * value / (2.0 * variance)) / sqrt(2.0 * PI * variance);
}

// Returns the radiance per unit intensity and unit face area per volume at the dust bottom from the crystals nearer than the scene distance whose light leaves the light above the horizon, and writes the widest spread across the pillar:
float pillarRadiance(float elevation, float lateralAngle, float horizontalDistance, float lightHeight, float tiltSigma,
        float sceneDistance, float horizon, out float widestSpread) {
    widestSpread = 0.0;
    float lightElevation = atan(lightHeight, horizontalDistance);
    float sineOfOffset = sin(elevation - lightElevation);
    float lightDistance = length(vec2(horizontalDistance, lightHeight));
    float lightToRay = lightDistance * sineOfOffset;
    // The distance to the crystals falls as their tilt rises, and it is the scene distance at this tilt:
    float nearTilt = elevation - 0.5 * atan(lightToRay, lightDistance * cos(elevation - lightElevation) - sceneDistance);
    float lowestTilt = max(-TILT_RANGE_SIGMAS * tiltSigma, nearTilt);
    // The light leaves the light rising at the elevation minus twice the tilt, and it is the horizon at this tilt:
    float highestTilt = min(min(TILT_RANGE_SIGMAS * tiltSigma, 0.5 * (elevation + lightElevation)),
            0.5 * (elevation - horizon));
    if (!(sineOfOffset > 0.0) || !(highestTilt > lowestTilt)) {
        return 0.0;
    }
    float middleTilt = 0.5 * (highestTilt + lowestTilt);
    float halfRange = 0.5 * (highestTilt - lowestTilt);
    float sineOfElevation = sin(elevation);
    float tiltVariance = tiltSigma * tiltSigma;
    float sum = 0.0;
    for (int node = 0; node < NODE_COUNT; node++) {
        float alongTilt = middleTilt + halfRange * NODES[node];
        float lightDepression = elevation - 2.0 * alongTilt;
        float sineAtCrystal = max(sin(elevation + lightDepression), SMALLEST_SINE_AT_CRYSTAL);
        float eyeLeg = lightDistance * sin(lightDepression + lightElevation) / sineAtCrystal;
        float lightLeg = lightToRay / sineAtCrystal;
        float crystalHeight = eyeLeg * sineOfElevation;
        float acrossScale = (1.0 + eyeLeg / lightLeg) / (sin(lightDepression) + sineOfElevation);
        float acrossVariance = tiltVariance + lateralBlur * lateralBlur * acrossScale * acrossScale;
        widestSpread = max(widestSpread, sqrt(acrossVariance) / acrossScale);
        float opticalDepth = dustOpticalDepth(lightHeight, crystalHeight, lightLeg)
                + dustOpticalDepth(crystalHeight, 0.0, eyeLeg);
        sum += WEIGHTS[node] * slabReflectance(sin(elevation - alongTilt)) * gaussian(alongTilt, tiltVariance)
                * gaussian(lateralAngle * acrossScale, acrossVariance) * dustDensity(crystalHeight)
                * exp(-opticalDepth);
    }
    return sum * halfRange / (2.0 * lightToRay);
}

#ifdef VERTEX_SHADER
// The ribbon index is slot * 2 + plate kind, then the row from the bottom and the side, -1 or 1:
in vec3 Position;
out vec3 relativePosition;
flat out vec3 pillarLight;
flat out vec3 pillarColor;
flat out float pillarOpacity;
flat out float pillarTiltSigma;
flat out float pillarShare;
flat out float pillarHorizon;

const float SEGMENTS = 16.0;
// Ribbons only cover the pillar on the screen, so they all stand at this distance from the eye:
const float RIBBON_DISTANCE = 1.0;
const float NEAREST_HORIZONTAL_DISTANCE = 0.5;
const float WIDEST_RIBBON_ANGLE = 1.4;

// Rows are closer together near the bottom of the ribbon:
float rowElevation(float row, float firstRow) {
    float rowFraction = row / SEGMENTS;
    return firstRow + (highestElevation - firstRow) * rowFraction * rowFraction;
}

// Returns the angle off the center beyond which the pillar stays below the visible value, plus the margin:
float halfWidthAt(float elevation, float horizontalDistance, float lightHeight, float tiltSigma, float horizon,
        float valueScale) {
    float widestSpread;
    float center = valueScale * pillarRadiance(elevation, 0.0, horizontalDistance, lightHeight, tiltSigma,
            NO_SCENE_DISTANCE, horizon, widestSpread);
    if (!(center > visibleValue)) {
        return 0.0;
    }
    float spreads = min(MOST_RIBBON_SPREADS, sqrt(2.0 * log(center / visibleValue)));
    return min(spreads * widestSpread + marginAngle, WIDEST_RIBBON_ANGLE);
}

void main() {
    int ribbon = int(Position.x + 0.5);
    int slot = ribbon / 2;
    int kind = ribbon - 2 * slot;
    vec3 light = lights[slot].xyz;
    float horizon = lights[slot].w;
    vec4 color = colors[slot];
    float tiltSigma = tiltSigmas[kind];
    float share = shares[kind];
    pillarLight = light;
    pillarColor = color.rgb;
    pillarOpacity = color.a;
    pillarTiltSigma = tiltSigma;
    pillarShare = share;
    pillarHorizon = horizon;
    relativePosition = vec3(0.0);
    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);

    float horizontalDistance = length(light.xz);
    float valueScale = max(color.r, max(color.g, color.b)) * color.a * share * extinction;
    if (!(valueScale > 0.0) || horizontalDistance < NEAREST_HORIZONTAL_DISTANCE) {
        return;
    }
    float lightElevation = atan(light.y, horizontalDistance);
    // Below the horizon minus the most tilted faces, no crystal gets light past the blocks around the light:
    float lowest = max(max(lightElevation, -lightElevation - 2.0 * TILT_RANGE_SIGMAS * tiltSigma),
            horizon - 2.0 * TILT_RANGE_SIGMAS * tiltSigma);
    if (!(highestElevation > lowest)) {
        return;
    }
    float firstRow = lowest - marginAngle;
    float lowestEvaluated = min(lowest + 1.0e-3, highestElevation);
    float elevation = rowElevation(Position.y, firstRow);
    // Each row is also as wide as the rows next to it and the half rows between, so the straight edges between rows
    // hold the pillar:
    float halfWidth = 0.0;
    for (float row = max(Position.y - 1.0, 0.0); row <= min(Position.y + 1.0, SEGMENTS); row += 0.5) {
        float rowEvaluated = clamp(rowElevation(row, firstRow), lowestEvaluated, highestElevation);
        halfWidth = max(halfWidth, halfWidthAt(rowEvaluated, horizontalDistance, light.y, tiltSigma, horizon,
                valueScale));
    }

    vec2 toward = light.xz / horizontalDistance;
    vec3 along = vec3(toward.x * cos(elevation), sin(elevation), toward.y * cos(elevation));
    vec3 across = vec3(-toward.y, 0.0, toward.x);
    vec3 position = RIBBON_DISTANCE * (along + Position.z * tan(halfWidth) * across);
    relativePosition = position;
    gl_Position = viewProjection * vec4(position, 1.0);
}
#endif

#ifdef FRAGMENT_SHADER
// The depth of the scene drawn so far, read at each pixel:
uniform sampler2D sceneDepth;
// Takes clip positions back to positions relative to the eye:
uniform mat4 inverseViewProjection;
uniform vec2 screenSize;
in vec3 relativePosition;
flat in vec3 pillarLight;
flat in vec3 pillarColor;
flat in float pillarOpacity;
flat in float pillarTiltSigma;
flat in float pillarShare;
flat in float pillarHorizon;
out vec4 fragColor;

vec3 encodeSrgb(vec3 linear) {
    vec3 clamped = clamp(linear, 0.0, 1.0);
    return mix(clamped * 12.92, 1.055 * pow(clamped, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, clamped));
}

void main() {
    vec3 direction = normalize(relativePosition);
    float horizontalDistance = length(pillarLight.xz);
    vec2 toward = pillarLight.xz / horizontalDistance;
    if (dot(direction.xz, toward) <= 0.0) {
        discard;
    }
    float sceneDistance = NO_SCENE_DISTANCE;
    float depth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (depth < 1.0) {
        vec4 scene = inverseViewProjection * vec4(gl_FragCoord.xy / screenSize * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        sceneDistance = length(scene.xyz / scene.w);
    }
    float elevation = asin(clamp(direction.y, -1.0, 1.0));
    float lateralAngle = asin(clamp(dot(direction.xz, vec2(-toward.y, toward.x)), -1.0, 1.0));
    float widestSpread;
    float radiance = pillarShare * extinction * pillarRadiance(elevation, lateralAngle, horizontalDistance,
            pillarLight.y, pillarTiltSigma, sceneDistance, pillarHorizon, widestSpread);
    if (!(radiance > 0.0)) {
        discard;
    }
    fragColor = vec4(encodeSrgb(1.0 - exp(-pillarColor * radiance)) * pillarOpacity, 1.0);
}
#endif
