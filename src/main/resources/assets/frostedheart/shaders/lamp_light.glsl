// The block light of the lamps around the eye, from the cascades of LampLightField, which hold per cell the light
// share and the air share of its outdoor air. The constants must match LampLightField.

const int LAMP_CASCADE_COUNT = 5;
const float LAMP_BRIGHTEST = 1.5;
const float LAMP_NO_AIR = 1.0e-5;
const float LAMP_BAND_START = 0.8125;
const float LAMP_BAND_END = 0.9375;

// The block light level over 15 that has a brightness of vanilla's block light curve, its inverse.
float lampLevelOfBrightness(float brightness) {
    float clamped = clamp(brightness, 0.0, LAMP_BRIGHTEST);
    return 4.0 * clamped / (1.5 + 3.0 * clamped);
}

// The level of a light share and an air share, 0 where there is no outdoor air.
float lampLevelOfShares(vec2 shares) {
    return lampLevelOfBrightness(shares.y > LAMP_NO_AIR ? shares.x / shares.y : 0.0);
}

#ifdef LAMP_LIGHT_FIELD
uniform sampler3D lampCascade0;
uniform sampler3D lampCascade1;
uniform sampler3D lampCascade2;
uniform sampler3D lampCascade3;
uniform sampler3D lampCascade4;
uniform int lampCascadeCount;
// How far the field has faded in after its last whole fill, from 0 to 1:
uniform float lampFieldFade;
// Per cascade: its size in cells, the slot of its lowest cell, the eye in cells from its lowest cell, its cells per
// block, and the inverse of its half side in blocks, 0 vertically where it holds the level's whole height.
uniform vec3 lampCascadeSize[LAMP_CASCADE_COUNT];
uniform vec3 lampSlotOrigin[LAMP_CASCADE_COUNT];
uniform vec3 lampEyeInCascade[LAMP_CASCADE_COUNT];
uniform vec3 lampCellsPerBlock[LAMP_CASCADE_COUNT];
uniform vec3 lampInverseReach[LAMP_CASCADE_COUNT];

vec2 lampCascadeShares(int cascade, vec3 coordinate) {
    if (cascade == 0) {
        return textureLod(lampCascade0, coordinate, 0.0).rg;
    }
    if (cascade == 1) {
        return textureLod(lampCascade1, coordinate, 0.0).rg;
    }
    if (cascade == 2) {
        return textureLod(lampCascade2, coordinate, 0.0).rg;
    }
    if (cascade == 3) {
        return textureLod(lampCascade3, coordinate, 0.0).rg;
    }
    return textureLod(lampCascade4, coordinate, 0.0).rg;
}

// The blended shares at an offset from the eye, each cascade filtered within its window and blended into the next
// over its band, with dark open air past the last.
vec2 lampSharesAt(vec3 offsetFromEye) {
    vec2 shares = vec2(0.0);
    float remaining = 1.0;
    for (int cascade = 0; cascade < LAMP_CASCADE_COUNT; cascade++) {
        if (cascade >= lampCascadeCount || remaining <= 0.0) {
            break;
        }
        vec3 scaled = abs(offsetFromEye) * lampInverseReach[cascade];
        float distance = max(max(scaled.x, scaled.y), scaled.z);
        if (distance >= LAMP_BAND_END) {
            continue;
        }
        float weight = clamp((distance - LAMP_BAND_START) / (LAMP_BAND_END - LAMP_BAND_START), 0.0, 1.0);
        vec3 size = lampCascadeSize[cascade];
        vec3 inWindow = clamp(lampEyeInCascade[cascade] + offsetFromEye * lampCellsPerBlock[cascade], vec3(0.5),
                size - 0.5);
        shares += remaining * (1.0 - weight) * lampCascadeShares(cascade, (lampSlotOrigin[cascade] + inWindow) / size);
        remaining *= weight;
    }
    shares.y += remaining;
    return shares;
}

float lampLevelAt(vec3 offsetFromEye) {
    return lampLevelOfShares(lampSharesAt(offsetFromEye));
}

// The level at an offset from the eye as it shows while the field fades in:
float lampShownLevelAt(vec3 offsetFromEye) {
    return lampCascadeCount > 0 ? lampFieldFade * lampLevelAt(offsetFromEye) : 0.0;
}
#endif
