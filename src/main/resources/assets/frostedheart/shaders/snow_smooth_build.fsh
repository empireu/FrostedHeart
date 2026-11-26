// FHShaders prepends the version line.
// Builds the smoothed macro terrain surface S on a 512 by 512 grid (2 blocks per texel), as SnowLee.buildSmoothHeights
// describes it. Pass 0 performs a valid-only 2x2 downsample of noLeavesHeights into RG32F (weighted sum, weight).
// Pass 1 runs the horizontal Gaussian (sigma = 8 coarse texels = 16 blocks, radius 24). Pass 2 runs the vertical
// Gaussian and normalizes by the accumulated valid weight so window edges and unloaded chunks never droop.
uniform isampler2D noLeavesHeights;
uniform sampler2D smoothSource;
uniform int passIndex;
uniform ivec2 farHeightsMin;
out vec4 fragColor;

const int SNOW_SMOOTH_SCALE = 2;
const int SNOW_SMOOTH_SIZE = 512;
const float SNOW_SMOOTH_SIGMA_TEXELS = 8.0;
const int SNOW_SMOOTH_KERNEL_RADIUS = 24;
const float SNOW_SMOOTH_MINIMUM_WEIGHT = 1.0e-6;
const int SNOW_LEE_FAR_HEIGHTS_MASK = 1023;
const int SNOW_LEE_UNKNOWN_HEIGHT = -32768;
const float SNOW_LEE_NO_HEIGHT = -32768.0;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    if (passIndex == 0) {
        ivec2 worldBase = farHeightsMin + texel * SNOW_SMOOTH_SCALE;
        float sum = 0.0;
        float count = 0.0;
        for (int oz = 0; oz < SNOW_SMOOTH_SCALE; oz++) {
            for (int ox = 0; ox < SNOW_SMOOTH_SCALE; ox++) {
                ivec2 column = worldBase + ivec2(ox, oz);
                int height = texelFetch(noLeavesHeights, column & ivec2(SNOW_LEE_FAR_HEIGHTS_MASK), 0).r;
                if (height > SNOW_LEE_UNKNOWN_HEIGHT) {
                    sum += float(height);
                    count += 1.0;
                }
            }
        }
        fragColor = vec4(sum * 0.25, count * 0.25, 0.0, 0.0);
        return;
    }
    vec2 acc = vec2(0.0);
    if (passIndex == 1) {
        for (int offset = -SNOW_SMOOTH_KERNEL_RADIUS; offset <= SNOW_SMOOTH_KERNEL_RADIUS; offset++) {
            int sampleX = texel.x + offset;
            if (sampleX >= 0 && sampleX < SNOW_SMOOTH_SIZE) {
                float scaled = float(offset) / SNOW_SMOOTH_SIGMA_TEXELS;
                float weight = exp(-0.5 * scaled * scaled);
                acc += weight * texelFetch(smoothSource, ivec2(sampleX, texel.y), 0).rg;
            }
        }
        fragColor = vec4(acc, 0.0, 0.0);
        return;
    }
    for (int offset = -SNOW_SMOOTH_KERNEL_RADIUS; offset <= SNOW_SMOOTH_KERNEL_RADIUS; offset++) {
        int sampleY = texel.y + offset;
        if (sampleY >= 0 && sampleY < SNOW_SMOOTH_SIZE) {
            float scaled = float(offset) / SNOW_SMOOTH_SIGMA_TEXELS;
            float weight = exp(-0.5 * scaled * scaled);
            acc += weight * texelFetch(smoothSource, ivec2(texel.x, sampleY), 0).rg;
        }
    }
    float result = acc.y > SNOW_SMOOTH_MINIMUM_WEIGHT ? acc.x / acc.y : SNOW_LEE_NO_HEIGHT;
    fragColor = vec4(result, 0.0, 0.0, 0.0);
}
