// The height fog of HeightFog in GLSL, which the shaders that draw the fog prepend to their own source.
// Its extinction per block falls off exponentially with height, and a straight segment through it adds the closed form
// of its optical depth.

// The largest optical depth any function here returns, as HeightFog.MAXIMUM_OPTICAL_DEPTH, which stands for opaque fog:
const float FOG_MAXIMUM_OPTICAL_DEPTH = 64.0;
// The absolute argument below which fogHeightFactor uses its series, as HeightFog.SERIES_LIMIT:
const float FOG_SERIES_LIMIT = 1.0e-3;
// The largest absolute value of an exp argument, as HeightFog.MAXIMUM_EXPONENT, so no exponent overflows:
const float FOG_MAXIMUM_EXPONENT = 60.0;

// The mean of the extinction along a straight segment over the extinction at its start, as HeightFog.heightFactor:
float fogHeightFactor(float x) {
    float clamped = clamp(x, -FOG_MAXIMUM_EXPONENT, FOG_MAXIMUM_EXPONENT);
    if (abs(clamped) < FOG_SERIES_LIMIT) {
        return 1.0 - clamped / 2.0 + clamped * clamped / 6.0;
    }
    return (1.0 - exp(-clamped)) / clamped;
}

// The optical depth from the eye along a straight segment, as HeightFog.opticalDepth:
float fogOpticalDepth(float eyeExtinction, float segmentLength, float verticalOffset, float heightScale) {
    if (!(segmentLength > 0.0) || !(eyeExtinction > 0.0)) {
        return 0.0;
    }
    float rayOpticalDepth;
    if (!(heightScale > 0.0)) {
        rayOpticalDepth = eyeExtinction * segmentLength;
    } else {
        rayOpticalDepth = eyeExtinction * segmentLength * fogHeightFactor(verticalOffset / heightScale);
    }
    if (isnan(rayOpticalDepth)) {
        return FOG_MAXIMUM_OPTICAL_DEPTH;
    }
    return min(FOG_MAXIMUM_OPTICAL_DEPTH, rayOpticalDepth);
}

// The optical depth from the point at distance start along the same ray to infinity, as HeightFog.skyTailOpticalDepth:
// eyeExtinction * heightScale * exp(-start * directionY / heightScale) / directionY. A start that is negative or NaN
// counts as 0, and an exponent past FOG_MAXIMUM_EXPONENT leaves no fog to add:
float fogSkyTailOpticalDepth(float eyeExtinction, float start, float directionY, float heightScale) {
    if (!(eyeExtinction > 0.0)) {
        return 0.0;
    }
    if (!(directionY > 0.0) || !(heightScale > 0.0)) {
        return FOG_MAXIMUM_OPTICAL_DEPTH;
    }
    float clampedStart = start > 0.0 ? start : 0.0;
    float exponent = clampedStart * directionY / heightScale;
    if (exponent > FOG_MAXIMUM_EXPONENT) {
        return 0.0;
    }
    float tailDepth = eyeExtinction * heightScale * exp(-exponent) / directionY;
    if (isnan(tailDepth)) {
        return FOG_MAXIMUM_OPTICAL_DEPTH;
    }
    return min(FOG_MAXIMUM_OPTICAL_DEPTH, tailDepth);
}
