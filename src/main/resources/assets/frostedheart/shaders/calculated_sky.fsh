#version 150

// Table lookup matches SkyView.sample, and the diffraction peak and halo match SkyPhase.narrowIce.
uniform sampler2D skyView;
uniform sampler2D iceView;
uniform mat4 inverseViewProjection;
uniform vec3 sunDirection;
uniform float sunSide;
uniform float horizonDip;
uniform float exposure;
uniform float haloFraction;
uniform float diffractionFraction;
uniform float diffractionAsymmetry;
uniform vec3 fogColor;
uniform float rainLevel;
uniform float thunderLevel;

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const vec3 HALO_ANGLE = vec3(0.3787364, 0.3839724, 0.3909538);
const float HALO_INNER_WIDTH = 0.0043633231;
const float HALO_OUTER_WIDTH = 0.0209439510;
const float HALO_UPPER_COSINE = 0.9455186;
const float HALO_LOWER_COSINE = 0.8480481;
const float HALO_ANGLE_DENSITY = 31.5278744;
const float HORIZON_FADE = 0.08;

vec3 encodeSrgb(vec3 linear) {
    vec3 clamped = clamp(linear, 0.0, 1.0);
    return mix(clamped * 12.92, 1.055 * pow(clamped, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, clamped));
}

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec4 farPoint = inverseViewProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec3 direction = normalize(farPoint.xyz / farPoint.w);

    float elevation = asin(clamp(direction.y, -1.0, 1.0));
    float azimuth = atan(direction.z, direction.x * sunSide);
    float offset = elevation + horizonDip;
    float latitude = offset >= 0.0
            ? offset / (1.0 + horizonDip * 2.0 / PI)
            : offset / (1.0 - horizonDip * 2.0 / PI);
    float position = sign(latitude) * sqrt(abs(latitude) * 2.0 / PI);
    vec2 tableCoordinate = vec2((azimuth + PI) / (2.0 * PI), 0.5 + 0.5 * position);
    vec3 radiance = texture(skyView, tableCoordinate).rgb;
    vec3 ice = texture(iceView, tableCoordinate).rgb;

    float sunCosine = dot(direction, sunDirection);
    float peakDenominator = (1.0 - diffractionAsymmetry) * (1.0 - diffractionAsymmetry)
            + 2.0 * diffractionAsymmetry * (1.0 - sunCosine);
    float peak = (1.0 - diffractionAsymmetry * diffractionAsymmetry)
            / (4.0 * PI * peakDenominator * sqrt(peakDenominator));
    radiance += diffractionFraction * peak * ice;
    if (sunCosine < HALO_UPPER_COSINE && sunCosine > HALO_LOWER_COSINE) {
        vec3 angle = vec3(acos(sunCosine));
        vec3 angleOffset = angle - HALO_ANGLE;
        vec3 width = mix(vec3(HALO_OUTER_WIDTH), vec3(HALO_INNER_WIDTH), step(angleOffset, vec3(0.0)));
        vec3 ring = HALO_ANGLE_DENSITY * exp(-0.5 * angleOffset * angleOffset / (width * width))
                / (2.0 * PI * sin(angle));
        radiance += haloFraction * ring * ice;
    }

    vec3 color = encodeSrgb(1.0 - exp(-exposure * max(radiance, vec3(0.0))));

    // Same weather greying as ClientLevel.getSkyColor and SkyView.horizonDisplayColor:
    float rainGrey = dot(color, vec3(0.3, 0.59, 0.11)) * 0.6;
    color = mix(vec3(rainGrey), color, 1.0 - rainLevel * 0.75);
    float thunderGrey = dot(color, vec3(0.3, 0.59, 0.11)) * 0.2;
    color = mix(vec3(thunderGrey), color, 1.0 - thunderLevel * 0.75);

    color = mix(color, fogColor, rainLevel);
    color = mix(fogColor, color, smoothstep(0.0, HORIZON_FADE, direction.y));
    color += (interleavedGradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
