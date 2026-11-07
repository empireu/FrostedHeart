#version 150

// Table lookups match SkyView.sample, IceHaloProfile.phase, and IceHaloMap.phase.
// Sky texels are the cells of an equi-angular cube map aligned with the world axes, as in calculated-sky.md.
uniform sampler2D skyView;
uniform sampler2D iceView;
uniform sampler2D lowHaloMap;
uniform sampler2D highHaloMap;
uniform sampler2D haloProfile;
uniform mat4 inverseViewProjection;
uniform vec3 sunDirection;
uniform float sunSide;
uniform float horizonDip;
uniform float exposure;
uniform float texelFaceSize;
uniform float haloBlend;
uniform float haloLowestElevation;
uniform float haloElevationSpan;
uniform vec3 fogColor;
uniform float rainLevel;
uniform float thunderLevel;

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265358979;
const float HORIZON_FADE = 0.08;

vec3 encodeSrgb(vec3 linear) {
    vec3 clamped = clamp(linear, 0.0, 1.0);
    return mix(clamped * 12.92, 1.055 * pow(clamped, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, clamped));
}

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Snaps the tangents of a direction on one cube face to its texel center and writes the texel's cell:
vec2 snapOnFace(vec2 tangents, out vec2 cell) {
    vec2 coordinate = atan(tangents) * (4.0 / PI);
    cell = clamp(floor((coordinate + 1.0) * 0.5 * texelFaceSize), 0.0, texelFaceSize - 1.0);
    return tan(((cell + 0.5) / texelFaceSize * 2.0 - 1.0) * (PI / 4.0));
}

// Returns the direction through the center of the sky texel and writes a position that is different for each texel:
vec3 texelCenter(vec3 direction, out vec2 texelPosition) {
    vec3 absolute = abs(direction);
    vec2 cell;
    if (absolute.x >= absolute.y && absolute.x >= absolute.z) {
        float face = direction.x > 0.0 ? 0.0 : 1.0;
        vec2 center = snapOnFace(direction.yz / absolute.x, cell);
        texelPosition = cell + vec2(face * texelFaceSize, 0.0);
        return normalize(vec3(direction.x > 0.0 ? 1.0 : -1.0, center));
    }
    if (absolute.y >= absolute.z) {
        float face = direction.y > 0.0 ? 2.0 : 3.0;
        vec2 center = snapOnFace(direction.xz / absolute.y, cell);
        texelPosition = cell + vec2(face * texelFaceSize, 0.0);
        return normalize(vec3(center.x, direction.y > 0.0 ? 1.0 : -1.0, center.y));
    }
    float face = direction.z > 0.0 ? 4.0 : 5.0;
    vec2 center = snapOnFace(direction.xy / absolute.z, cell);
    texelPosition = cell + vec2(face * texelFaceSize, 0.0);
    return normalize(vec3(center, direction.z > 0.0 ? 1.0 : -1.0));
}

void main() {
    vec4 farPoint = inverseViewProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec3 direction = normalize(farPoint.xyz / farPoint.w);
    vec2 noisePosition = gl_FragCoord.xy;
    if (texelFaceSize > 0.0) {
        direction = texelCenter(direction, noisePosition);
    }

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

    float sunAngle = acos(clamp(dot(direction, sunDirection), -1.0, 1.0));
    vec3 icePhase = texture(haloProfile, vec2(sunAngle / PI, 0.5)).rgb;
    vec2 haloCoordinate = vec2(abs(azimuth) / PI, (elevation - haloLowestElevation) / haloElevationSpan);
    icePhase += mix(texture(lowHaloMap, haloCoordinate).rgb, texture(highHaloMap, haloCoordinate).rgb, haloBlend);
    radiance += icePhase * ice;

    vec3 color = encodeSrgb(1.0 - exp(-exposure * max(radiance, vec3(0.0))));

    // Same weather graying as ClientLevel.getSkyColor and SkyView.horizonDisplayColor:
    float rainGray = dot(color, vec3(0.3, 0.59, 0.11)) * 0.6;
    color = mix(vec3(rainGray), color, 1.0 - rainLevel * 0.75);
    float thunderGray = dot(color, vec3(0.3, 0.59, 0.11)) * 0.2;
    color = mix(vec3(thunderGray), color, 1.0 - thunderLevel * 0.75);

    color = mix(color, fogColor, rainLevel);
    color = mix(fogColor, color, smoothstep(0.0, HORIZON_FADE, direction.y));
    color += (interleavedGradientNoise(noisePosition) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
