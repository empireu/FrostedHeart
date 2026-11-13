// FHShaders prepends the version line and defines VERTEX_SHADER or FRAGMENT_SHADER.
// Light pillars as in calculated-sky.md. pillarRadiance matches LightPillarOptics.dustRadiance and widestLateralSpread.
// Each pillar has one ribbon per plate kind that covers it on the screen, and the fragment shader computes its light from the crystals in front of the scene.
// Takes positions relative to the eye, which view bobbing moves away from the camera:
uniform mat4 viewProjection;
// Light positions relative to the eye, then the horizon around the light toward the eye in radians:
uniform vec4 lights[48];
// The ceiling and the top around the light toward the eye in radians, as LightPillarHorizons has them:
uniform vec2 overhangs[48];
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
// Crystals nearer to the eye than this many blocks count less, and none count nearer than a quarter of it, unless it is 0:
uniform float nearFade;
// Turns the crystal count of pillarRadiance times the face area per volume into crystals per pixel, or 0 for smooth pillars:
uniform float crystalCountScale;

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
const float NEAR_FADE_START_SHARE = 0.25;
const float SMALLEST_SINE_OF_GRAZING = 0.02;
const float MOST_SPARKLE = 64.0;
const float RIBBON_GLINT_CHANCE = 0.002;
const float RIBBON_GLINT_SIGMAS = 2.878;

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

float nearWeight(float eyeLeg) {
    return nearFade > 0.0 ? smoothstep(NEAR_FADE_START_SHARE * nearFade, nearFade, eyeLeg) : 1.0;
}

// The tilt of the crystals on the view ray at the distance from the eye. The distance falls as the tilt rises:
float tiltAtDistance(float elevation, float lightElevation, float lightDistance, float lightToRay, float distance) {
    return elevation - 0.5 * atan(lightToRay, lightDistance * cos(elevation - lightElevation) - distance);
}

// Returns the reflections for the tilts from the lowest to the highest, adds their crystals to the crystal sum, and widens the widest spread across the pillar to theirs:
float tiltSum(float lowestTilt, float highestTilt, float elevation, float lateralAngle, float lightHeight,
        float lightElevation, float lightDistance, float lightToRay, float tiltSigma, inout float widestSpread,
        inout float crystalSum) {
    if (!(highestTilt > lowestTilt)) {
        return 0.0;
    }
    float crystals = 0.0;
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
        float sineOfGrazing = sin(elevation - alongTilt);
        float present = WEIGHTS[node] * gaussian(alongTilt, tiltVariance)
                * gaussian(lateralAngle * acrossScale, acrossVariance) * dustDensity(crystalHeight) * nearWeight(eyeLeg);
        sum += present * slabReflectance(sineOfGrazing) * exp(-opticalDepth);
        float spreadSine = max(sineOfGrazing, SMALLEST_SINE_OF_GRAZING);
        crystals += present * eyeLeg * eyeLeg * lightLeg * lightLeg / (spreadSine * spreadSine);
    }
    crystalSum += crystals * halfRange;
    return sum * halfRange;
}

// The most that the crystals of a sky cell show, as a multiple of their mean value, as LightPillarOptics.sparkleCap:
float sparkleCap(float meanCount) {
    return meanCount > 0.0 ? min(MOST_SPARKLE, 1.0 + 3.0 / sqrt(meanCount) + 1.0 / meanCount) : MOST_SPARKLE;
}

// Returns the radiance per unit intensity and unit face area per volume at the dust bottom from the crystals nearer than the scene distance whose light gets past the blocks around the light, and writes the widest spread across the pillar.
// Also writes the crystals that mirror the light per unit face area per volume and unit crystalCountScale, as LightPillarOptics.dustCrystalCount.
// The horizons are the horizon, the ceiling, and the top of LightPillarHorizons:
float pillarRadiance(float elevation, float lateralAngle, float horizontalDistance, float lightHeight, float tiltSigma,
        float sceneDistance, vec3 horizons, out float widestSpread, out float crystalCount) {
    widestSpread = 0.0;
    crystalCount = 0.0;
    float lightElevation = atan(lightHeight, horizontalDistance);
    float sineOfOffset = sin(elevation - lightElevation);
    if (!(sineOfOffset > 0.0)) {
        return 0.0;
    }
    float lightDistance = length(vec2(horizontalDistance, lightHeight));
    float lightToRay = lightDistance * sineOfOffset;
    float lowestTilt = max(-TILT_RANGE_SIGMAS * tiltSigma,
            tiltAtDistance(elevation, lightElevation, lightDistance, lightToRay, sceneDistance));
    float highestTilt = min(TILT_RANGE_SIGMAS * tiltSigma, 0.5 * (elevation + lightElevation));
    if (nearFade > 0.0) {
        highestTilt = min(highestTilt, tiltAtDistance(elevation, lightElevation, lightDistance, lightToRay,
                NEAR_FADE_START_SHARE * nearFade));
    }
    // The light leaves the light rising at the elevation minus twice the tilt:
    float crystalSum = 0.0;
    float sum = tiltSum(lowestTilt, min(highestTilt, 0.5 * (elevation - horizons.z)), elevation, lateralAngle,
            lightHeight, lightElevation, lightDistance, lightToRay, tiltSigma, widestSpread, crystalSum)
            + tiltSum(max(lowestTilt, 0.5 * (elevation - horizons.y)), min(highestTilt, 0.5 * (elevation - horizons.x)),
            elevation, lateralAngle, lightHeight, lightElevation, lightDistance, lightToRay, tiltSigma, widestSpread,
            crystalSum);
    crystalCount = 2.0 * crystalSum / lightToRay;
    return sum / (2.0 * lightToRay);
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
flat out vec3 pillarHorizons;
flat out float pillarRibbon;

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

// The mean count of crystals per sky cell below which the cell shows the visible value with less than RIBBON_GLINT_CHANCE, as LightPillarOptics.leastVisibleCount:
float leastVisibleCount(float crystalOverVisible) {
    float ratio = 1.0 / crystalOverVisible;
    float needed = max(1.0, ceil(ratio));
    float chanceCount;
    if (needed <= 2.0) {
        chanceCount = pow(RIBBON_GLINT_CHANCE * needed, 1.0 / needed);
    } else {
        float root = max(0.0, 1.0 - 1.0 / (9.0 * needed) - RIBBON_GLINT_SIGMAS / (3.0 * sqrt(needed)));
        chanceCount = needed * root * root * root;
    }
    float capRoot = 0.5 * (sqrt(4.0 * ratio + 5.0) - 3.0);
    float capCount = ratio > 1.0 ? capRoot * capRoot : 0.0;
    return max(chanceCount, max(capCount, ratio / MOST_SPARKLE));
}

// Returns the angle off the center beyond which the pillar stays below the visible value, plus the margin.
// Where crystals glitter, beyond it a pixel shows the visible value with less than RIBBON_GLINT_CHANCE, as LightPillarOptics.ribbonHalfWidth:
float halfWidthAt(float elevation, float horizontalDistance, float lightHeight, float tiltSigma, vec3 horizons,
        float valueScale, float countScale) {
    float widestSpread;
    float crystalCount;
    float center = valueScale * pillarRadiance(elevation, 0.0, horizontalDistance, lightHeight, tiltSigma,
            NO_SCENE_DISTANCE, horizons, widestSpread, crystalCount);
    float meanCount = countScale * crystalCount;
    float spreads;
    if (meanCount > 0.0) {
        float leastCount = leastVisibleCount(center / (meanCount * visibleValue));
        if (!(meanCount > leastCount)) {
            return 0.0;
        }
        spreads = min(MOST_RIBBON_SPREADS, sqrt(2.0 * log(meanCount / leastCount)));
    } else {
        if (!(center > visibleValue)) {
            return 0.0;
        }
        spreads = min(MOST_RIBBON_SPREADS, sqrt(2.0 * log(center / visibleValue)));
    }
    return min(spreads * widestSpread + marginAngle, WIDEST_RIBBON_ANGLE);
}

void main() {
    int ribbon = int(Position.x + 0.5);
    int slot = ribbon / 2;
    int kind = ribbon - 2 * slot;
    vec3 light = lights[slot].xyz;
    vec3 horizons = vec3(lights[slot].w, overhangs[slot]);
    vec4 color = colors[slot];
    float tiltSigma = tiltSigmas[kind];
    float share = shares[kind];
    pillarLight = light;
    pillarColor = color.rgb;
    pillarOpacity = color.a;
    pillarTiltSigma = tiltSigma;
    pillarShare = share;
    pillarHorizons = horizons;
    pillarRibbon = float(ribbon);
    relativePosition = vec3(0.0);
    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);

    float horizontalDistance = length(light.xz);
    float valueScale = max(color.r, max(color.g, color.b)) * color.a * share * extinction;
    float countScale = share * extinction * crystalCountScale;
    if (!(valueScale > 0.0) || horizontalDistance < NEAREST_HORIZONTAL_DISTANCE) {
        return;
    }
    float lightElevation = atan(light.y, horizontalDistance);
    // Below the horizon minus the most tilted faces, no crystal gets light past the blocks around the light:
    float lowest = max(max(lightElevation, -lightElevation - 2.0 * TILT_RANGE_SIGMAS * tiltSigma),
            horizons.x - 2.0 * TILT_RANGE_SIGMAS * tiltSigma);
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
        halfWidth = max(halfWidth, halfWidthAt(rowEvaluated, horizontalDistance, light.y, tiltSigma, horizons,
                valueScale, countScale));
    }
    // The pillar can show again above the top in a band narrower than a half row:
    float reappearance = horizons.z + 2.0 * tiltSigma;
    if (horizons.y < horizons.z && reappearance > rowElevation(max(Position.y - 1.0, 0.0), firstRow)
            && reappearance < rowElevation(min(Position.y + 1.0, SEGMENTS), firstRow)) {
        halfWidth = max(halfWidth, halfWidthAt(clamp(reappearance, lowestEvaluated, highestElevation),
                horizontalDistance, light.y, tiltSigma, horizons, valueScale, countScale));
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
// Twinkle periods passed, which also run on as the eye moves:
uniform float twinkleClock;
in vec3 relativePosition;
flat in vec3 pillarLight;
flat in vec3 pillarColor;
flat in float pillarOpacity;
flat in float pillarTiltSigma;
flat in float pillarShare;
flat in vec3 pillarHorizons;
flat in float pillarRibbon;
out vec4 fragColor;

const float LARGEST_COUNTED_MEAN = 16.0;
const float MOST_DRAWN = 48.0;
const uint GOLDEN_RATIO_STEP = 0x9e3779b9u;

// The lowbias32 integer hash by Chris Wellons:
uint hashOf(uint value) {
    uint mixed = value;
    mixed ^= mixed >> 16;
    mixed *= 0x7feb352du;
    mixed ^= mixed >> 15;
    mixed *= 0x846ca68bu;
    mixed ^= mixed >> 16;
    return mixed;
}

float uniformOf(uint hashed) {
    return float(hashed >> 8) / 16777216.0;
}

// Draws a count of crystals for their mean count, as LightPillarOptics.crystalsDrawn:
float crystalsDrawn(float meanCount, uint seed) {
    float chance = uniformOf(seed);
    if (meanCount > LARGEST_COUNTED_MEAN) {
        float angle = 2.0 * PI * uniformOf(hashOf(seed));
        return max(0.0, meanCount + sqrt(meanCount) * sqrt(-2.0 * log(max(chance, 1.0e-7))) * cos(angle));
    }
    float term = exp(-meanCount);
    float cumulative = term;
    float count = 0.0;
    while (chance > cumulative && count < MOST_DRAWN) {
        count += 1.0;
        term *= meanCount / count;
        cumulative += term;
    }
    return count;
}

// Returns the share of its mean value that the pillar shows in the pixel-sized sky cell at the lateral angle and elevation, as LightPillarOptics.sparkle:
float sparkle(float meanCount, vec2 skyAngles) {
    if (!(meanCount > 0.0)) {
        return 1.0;
    }
    ivec2 cell = ivec2(floor(skyAngles / marginAngle));
    uint cellSeed = hashOf(uint(cell.x) ^ hashOf(uint(cell.y) ^ hashOf(uint(pillarRibbon))));
    float time = twinkleClock + uniformOf(cellSeed);
    float frame = floor(time);
    uint frameNumber = uint(frame);
    float cap = sparkleCap(meanCount);
    float before = min(crystalsDrawn(meanCount, hashOf(cellSeed + frameNumber * GOLDEN_RATIO_STEP)) / meanCount, cap);
    float after = min(crystalsDrawn(meanCount, hashOf(cellSeed + (frameNumber + 1u) * GOLDEN_RATIO_STEP)) / meanCount,
            cap);
    return mix(before, after, smoothstep(0.25, 0.75, time - frame));
}

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
    float crystalCount;
    float radiance = pillarShare * extinction * pillarRadiance(elevation, lateralAngle, horizontalDistance,
            pillarLight.y, pillarTiltSigma, sceneDistance, pillarHorizons, widestSpread, crystalCount);
    if (crystalCountScale > 0.0) {
        radiance *= sparkle(pillarShare * extinction * crystalCountScale * crystalCount,
                vec2(lateralAngle, elevation));
    }
    if (!(radiance > 0.0)) {
        discard;
    }
    fragColor = vec4(encodeSrgb(1.0 - exp(-pillarColor * radiance)) * pillarOpacity, 1.0);
}
#endif
