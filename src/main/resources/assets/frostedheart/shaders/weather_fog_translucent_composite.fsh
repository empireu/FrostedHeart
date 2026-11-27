// FHShaders prepends the version line, weather_fog.glsl and weather_fog_excess.glsl.
// Composites the translucent terrain target over the main target after the opaque fog and the snow behind translucent
// surfaces have been drawn. Each translucent pixel already holds its own premultiplied color fogged at its own depth,
// and this pass adds the premultiplied lamp glow marched up to the translucent surface, with depth-aware upsampling
// and a thin-geometry opacity clamp so narrow silhouettes do not pick up background lamp glow.
uniform sampler2D translucentColor;
uniform sampler2D translucentDepth;
// The lamp glow that weather_fog_lamp.fsh marched up to translucent surfaces at a share of the resolution, each texel
// holding its light and the distance its ray ended at, and the frame pixels per side of a texel, 0 while there is no
// glow:
uniform sampler2D lampFog;
uniform int lampFogDivisor;
uniform vec2 screenSize;
uniform mat4 inverseViewProjection;
uniform float eyeExtinction;
uniform float heightScale;
out vec4 fragColor;

// The alpha below which a translucent pixel leaves the main target as it is (1 / 512):
const float LEAST_ALPHA = 0.001953125;
// The distance a ray of the sky ends at in the lamp glow, as LampFogMarch.SKY_DISTANCE, the share of a pixel's
// distance by which a texel's distance may differ before its weight halves, and the least bilinear weight of a texel:
const float LAMP_SKY_DISTANCE = 60000.0;
const float LAMP_UPSAMPLE_DEPTH_SHARE = 0.1;
const float LAMP_UPSAMPLE_LEAST_WEIGHT = 1.0e-3;
// When all four coarse lamp samples sit farther than this multiple of a thin translucent pixel's own distance, scale
// the upsampled glow by the ratio of fog opacities so a thin silhouette cannot pick up distant background glow:
const float LAMP_THIN_DEPTH_RATIO = 1.25;
const float LAMP_LEAST_SCALED_OPACITY = 1.0e-6;

// The lamp glow at a frame pixel whose ray ends at a distance, from the four texels of the glow around it, each
// weighted by its bilinear share and by how close its distance is to the pixel's, plus the weighted sample distance:
vec3 lampGlowAt(vec2 pixel, float distance, out float weightedDistance) {
    if (lampFogDivisor <= 0) {
        weightedDistance = distance;
        return vec3(0.0);
    }
    ivec2 size = textureSize(lampFog, 0);
    vec2 position = (pixel - (float(lampFogDivisor / 2) + 0.5)) / float(lampFogDivisor);
    vec2 low = floor(position);
    vec2 fraction = position - low;
    vec3 light = vec3(0.0);
    float distanceSum = 0.0;
    float total = 0.0;
    for (int corner = 0; corner < 4; corner++) {
        ivec2 cornerStep = ivec2(corner & 1, corner >> 1);
        vec4 texelValue = texelFetch(lampFog, clamp(ivec2(low) + cornerStep, ivec2(0), size - 1), 0);
        float bilinear = (cornerStep.x == 1 ? fraction.x : 1.0 - fraction.x)
                * (cornerStep.y == 1 ? fraction.y : 1.0 - fraction.y);
        float difference = abs(texelValue.a - distance) / (LAMP_UPSAMPLE_DEPTH_SHARE * max(distance, 1.0));
        float weight = max(bilinear, LAMP_UPSAMPLE_LEAST_WEIGHT) / (1.0 + difference * difference);
        light += weight * texelValue.rgb;
        distanceSum += weight * texelValue.a;
        total += weight;
    }
    weightedDistance = distanceSum / total;
    return light / total;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 color = texelFetch(translucentColor, pixel, 0);
    if (!(color.a >= LEAST_ALPHA)) {
        discard;
    }
    if (lampFogDivisor > 0) {
        float depth = texelFetch(translucentDepth, pixel, 0).r;
        if (depth < 1.0) {
            vec3 ndc = vec3(gl_FragCoord.xy / screenSize * 2.0 - 1.0, depth * 2.0 - 1.0);
            vec4 position = inverseViewProjection * vec4(ndc, 1.0);
            vec3 offset = position.xyz / position.w;
            if (!any(isnan(offset)) && !any(isinf(offset))) {
                float distance = min(length(offset), LAMP_SKY_DISTANCE);
                float weightedDistance;
                vec3 glow = lampGlowAt(gl_FragCoord.xy, distance, weightedDistance);
                if (weightedDistance > distance * LAMP_THIN_DEPTH_RATIO
                        && (glow.r > 0.0 || glow.g > 0.0 || glow.b > 0.0)) {
                    float directionY = distance > 0.0 ? offset.y / distance : 0.0;
                    vec2 uv = gl_FragCoord.xy / (float(divisor) * vec2(columnCount, rowCount));
                    float tau = clamp(fogOpticalDepth(eyeExtinction, distance, offset.y, heightScale)
                            + froxelExcessAtDistance(distance, uv), 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
                    float sampleTau = clamp(fogOpticalDepth(eyeExtinction, weightedDistance,
                            weightedDistance * directionY, heightScale)
                            + froxelExcessAtDistance(weightedDistance, uv), 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);
                    float sampleOpacity = 1.0 - exp(-sampleTau);
                    if (sampleOpacity > LAMP_LEAST_SCALED_OPACITY) {
                        glow *= min(1.0, (1.0 - exp(-tau)) / sampleOpacity);
                    } else {
                        glow = vec3(0.0);
                    }
                }
                color.rgb += color.a * glow;
            }
        }
    }
    fragColor = color;
}
