// FHShaders prepends the version line and weather_fog.glsl.
// Draws the fog of snow and blizzards over the frame after the weather, after WeatherFogRenderer: the scene point of a
// pixel is rebuilt from the depth of the main target, and the fog blends over the pixel by the opacity along the segment
// from the eye to that point.
// The fog counts only where that segment runs in the open, which the first open heights of the columns along it tell, so
// a pixel takes the fog of the open air that its light crosses, and a room stays clear where the whole segment from the
// eye to the scene point stays under its roof. The walk along the columns runs only while the eye is not fully open, so
// it costs nothing outdoors.
// The fog color is the horizon color toward the pixel, mixed toward the snow fog color by the snowfall intensity.
// The depth of the main target and its size in texels:
uniform sampler2D sceneDepth;
uniform vec2 screenSize;
// The sky's displayed horizon color per azimuth, 64 texels around from -PI:
uniform sampler2D horizonColors;
// The snow fog color, and the share of it that the horizon color blends toward, which is the snowfall intensity, all as
// display values:
uniform vec3 snowColor;
uniform float snowShare;
// The view projection translated by the eye offset and inverted, so a position it gives is the offset from the eye:
uniform mat4 inverseViewProjection;
// The extinction per block at the eye, and the height scale of the fog in blocks:
uniform float eyeExtinction;
uniform float heightScale;
// The first open height of each column around the eye, as diamond_dust.glsl reads it:
uniform sampler2D columnHeights;
uniform int windowSize;
uniform ivec2 windowOrigin;
uniform ivec2 eyeInWindow;
uniform vec2 eyeFraction;
uniform float eyeY;
// Whether the window covers the eye's column, and how much of the full depth the fog takes, which is 1 in the open and 0
// in a room, where only the open part of the segment counts:
uniform int useColumns;
uniform float eyeOpenness;
out vec4 fragColor;

const float PI = 3.14159265358979;
// Half of DustColumnWindow.CLOSED, at or above which a column not read yet counts as open for the fog:
const float UNREAD_HEIGHT = 500000.0;
// The opacity below which the fog leaves a pixel as it is:
const float LEAST_OPACITY = 1.0 / 512.0;
// The horizontal length in blocks below which the segment stays in the eye's column, as
// ShelterRay.MINIMUM_HORIZONTAL_LENGTH:
const float MINIMUM_HORIZONTAL_LENGTH = 1.0e-4;
// The most columns the walk visits, as ShelterRay.MAXIMUM_COLUMN_STEPS, past which the rest of the segment counts as
// open:
const int MAXIMUM_COLUMN_STEPS = 160;
// A boundary past the end of the segment, for an axis the segment never leaves its column in:
const float NO_BOUNDARY = 1.0e30;

// The optical depth from the eye up to the given parameter of a segment, as ShelterRay.tauAt:
float tauAt(float s, float segmentLength, float verticalOffset) {
    return fogOpticalDepth(eyeExtinction, s * segmentLength, s * verticalOffset, heightScale);
}

// The optical depth of the segment between two of its parameters, as ShelterRay.openRunDepth, which is never below 0
// because the clamp of fogOpticalDepth can make its two ends equal:
float openRunDepth(float from, float to, float segmentLength, float verticalOffset) {
    return max(0.0, tauAt(to, segmentLength, verticalOffset) - tauAt(from, segmentLength, verticalOffset));
}

// The optical depth of the open part of the segment from the eye to the scene point at the given offset, as
// ShelterRay.openOpticalDepth. A place on the segment is open when its column lies outside the window, when that column
// is not read yet, or when the place stands at or above the column's first open height.
float openOpticalDepth(vec3 offset) {
    if (!(eyeExtinction > 0.0)) {
        return 0.0;
    }
    float segmentLength = length(offset);
    float horizontalLength = length(offset.xz);
    float eyeColumnPositionX = float(eyeInWindow.x) + eyeFraction.x;
    float eyeColumnPositionZ = float(eyeInWindow.y) + eyeFraction.y;
    int columnX = int(floor(eyeColumnPositionX));
    int columnZ = int(floor(eyeColumnPositionZ));
    int columnStepX = 0;
    int columnStepZ = 0;
    float nextBoundaryX = NO_BOUNDARY;
    float nextBoundaryZ = NO_BOUNDARY;
    float boundaryStepX = NO_BOUNDARY;
    float boundaryStepZ = NO_BOUNDARY;
    if (horizontalLength >= MINIMUM_HORIZONTAL_LENGTH) {
        if (offset.x != 0.0) {
            columnStepX = offset.x > 0.0 ? 1 : -1;
            nextBoundaryX = (float(columnX + (columnStepX > 0 ? 1 : 0)) - eyeColumnPositionX) / offset.x;
            boundaryStepX = 1.0 / abs(offset.x);
        }
        if (offset.z != 0.0) {
            columnStepZ = offset.z > 0.0 ? 1 : -1;
            nextBoundaryZ = (float(columnZ + (columnStepZ > 0 ? 1 : 0)) - eyeColumnPositionZ) / offset.z;
            boundaryStepZ = 1.0 / abs(offset.z);
        }
    }
    float openDepth = 0.0;
    float openRunStart = 0.0;
    bool inOpenRun = false;
    float sEnter = 0.0;
    for (int columnStep = 0; columnStep < MAXIMUM_COLUMN_STEPS; columnStep++) {
        if (!(sEnter < 1.0)) {
            break;
        }
        if (columnX < 0 || columnX >= windowSize || columnZ < 0 || columnZ >= windowSize) {
            if (!inOpenRun) {
                openRunStart = sEnter;
                inOpenRun = true;
            }
            break;
        }
        float sExit = min(min(nextBoundaryX, nextBoundaryZ), 1.0);
        float firstOpenY = texelFetch(columnHeights,
                (windowOrigin + ivec2(columnX, columnZ)) & ivec2(windowSize - 1), 0).r;
        bool openAtStart;
        bool transitionsInside;
        float sCross = 0.0;
        if (firstOpenY >= UNREAD_HEIGHT || offset.y == 0.0) {
            openAtStart = firstOpenY >= UNREAD_HEIGHT || eyeY >= firstOpenY;
            transitionsInside = false;
        } else {
            sCross = (firstOpenY - eyeY) / offset.y;
            if (offset.y > 0.0) {
                openAtStart = sCross <= sEnter;
                transitionsInside = !openAtStart && sCross < sExit;
            } else {
                openAtStart = !(sCross < sEnter);
                transitionsInside = openAtStart && sCross < sExit;
            }
        }
        if (openAtStart) {
            if (!inOpenRun) {
                openRunStart = sEnter;
                inOpenRun = true;
            }
        } else if (inOpenRun) {
            openDepth += openRunDepth(openRunStart, sEnter, segmentLength, offset.y);
            inOpenRun = false;
        }
        if (transitionsInside) {
            if (inOpenRun) {
                openDepth += openRunDepth(openRunStart, sCross, segmentLength, offset.y);
                inOpenRun = false;
            } else {
                openRunStart = sCross;
                inOpenRun = true;
            }
        }
        sEnter = sExit;
        if (!(sEnter < 1.0)) {
            break;
        }
        if (nextBoundaryX < nextBoundaryZ) {
            columnX += columnStepX;
            nextBoundaryX += boundaryStepX;
        } else if (nextBoundaryZ < nextBoundaryX) {
            columnZ += columnStepZ;
            nextBoundaryZ += boundaryStepZ;
        } else {
            columnX += columnStepX;
            columnZ += columnStepZ;
            nextBoundaryX += boundaryStepX;
            nextBoundaryZ += boundaryStepZ;
        }
    }
    if (!inOpenRun) {
        openRunStart = sEnter;
    }
    return openDepth + openRunDepth(openRunStart, 1.0, segmentLength, offset.y);
}

void main() {
    float depth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    if (depth >= 1.0) {
        discard;
    }
    vec3 ndc = vec3(gl_FragCoord.xy / screenSize * 2.0 - 1.0, depth * 2.0 - 1.0);
    vec4 position = inverseViewProjection * vec4(ndc, 1.0);
    vec3 offset = position.xyz / position.w;
    // A depth at the near plane can give no scene point, which then takes no fog:
    if (any(isnan(offset)) || any(isinf(offset))) {
        discard;
    }
    float tauFull = fogOpticalDepth(eyeExtinction, length(offset), offset.y, heightScale);
    float tau = tauFull;
    if (useColumns != 0 && eyeOpenness < 1.0) {
        tau = mix(openOpticalDepth(offset), tauFull, eyeOpenness);
    }
    float opacity = 1.0 - exp(-tau);
    float phi = atan(offset.z, offset.x);
    float u = phi / (2.0 * PI) + 0.5;
    vec3 fogColor = mix(texture(horizonColors, vec2(u, 0.5)).rgb, snowColor, snowShare);
    if (opacity < LEAST_OPACITY) {
        discard;
    }
    fragColor = vec4(fogColor, opacity);
}
