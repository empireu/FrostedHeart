/*
 * Copyright (c) 2026 TeamMoeg
 *
 * This file is part of Frosted Heart.
 *
 * Frosted Heart is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Frosted Heart is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Frosted Heart. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.teammoeg.frostedheart.content.climate.render.fog;

/**
 * Composes Embeddium's translucent chunk vertex and fragment shaders with zero-alpha fragment discard and per-fragment
 * froxel weather fog. Pure source transformation so GameTests and glsl-check can validate the composed shaders.
 */
public final class TranslucentFogShader {
    /**
     * The alpha below which translucent terrain fragments are discarded so invisible padding texels (such as around
     * icicles) do not write to the depth buffer. Matches LEAST_ALPHA in weather_fog_translucent_composite.fsh.
     */
    public static final float LEAST_ALPHA = 1.0F / 512.0F;
    /**
     * Texture unit bound to the froxel excess 2D array texture while translucent terrain draws.
     */
    public static final int EXCESS_TEXTURE_UNIT = 7;

    private static final String VERTEX_MAIN_ANCHOR = "void main() {";
    private static final String VERTEX_POSITION_ANCHOR = "vec3 position = _vert_position + translation;";
    private static final String FRAGMENT_OUT_ANCHOR = "out vec4 fragColor;";
    private static final String FRAGMENT_FOG_ANCHOR =
            "fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);";

    private static final String VERTEX_DECLARATIONS =
            "uniform vec3 fhFogEyeOffset;\n"
            + "out vec3 fhFogOffset;\n\n";

    private static final String VERTEX_OFFSET_ASSIGNMENT =
            VERTEX_POSITION_ANCHOR + "\n"
            + "    fhFogOffset = position - fhFogEyeOffset;";

    private static final String FRAGMENT_UNIFORMS =
            "\nin vec3 fhFogOffset;\n"
            + "uniform int fhWeatherFogActive;\n"
            + "uniform vec3 fhSnowColor;\n"
            + "uniform float fhEyeExtinction;\n"
            + "uniform float fhHeightScale;\n"
            + "const float FH_TRANSLUCENT_LEAST_ALPHA = 0.001953125;\n\n";

    private static final String FRAGMENT_FOG_BODY =
            "if (diffuseColor.a < FH_TRANSLUCENT_LEAST_ALPHA) {\n"
            + "        discard;\n"
            + "    }\n"
            + "    if (fhWeatherFogActive != 0) {\n"
            + "        float fhDistance = length(fhFogOffset);\n"
            + "        vec2 fhUv = gl_FragCoord.xy / (float(divisor) * vec2(columnCount, rowCount));\n"
            + "        float fhTau = clamp(fogOpticalDepth(fhEyeExtinction, fhDistance, fhFogOffset.y, fhHeightScale)\n"
            + "                + froxelExcessAtDistance(fhDistance, fhUv), 0.0, FOG_MAXIMUM_OPTICAL_DEPTH);\n"
            + "        diffuseColor.rgb = mix(fhSnowColor, diffuseColor.rgb, exp(-fhTau));\n"
            + "    }\n\n"
            + "    " + FRAGMENT_FOG_ANCHOR;

    private TranslucentFogShader() {
    }

    /**
     * Composes the translucent chunk vertex shader so it exports each vertex's offset from the fog eye.
     * @param original the Embeddium block_layer_opaque.vsh source
     * @return the transformed vertex shader source
     * @throws IllegalStateException when an expected anchor is absent from the original source
     */
    public static String vertex(String original) {
        if (!original.contains(VERTEX_MAIN_ANCHOR)) {
            throw new IllegalStateException("Missing vertex main anchor in Embeddium chunk vertex shader");
        }
        if (!original.contains(VERTEX_POSITION_ANCHOR)) {
            throw new IllegalStateException("Missing position anchor in Embeddium chunk vertex shader");
        }
        return original.replace(VERTEX_MAIN_ANCHOR, VERTEX_DECLARATIONS + VERTEX_MAIN_ANCHOR)
                .replace(VERTEX_POSITION_ANCHOR, VERTEX_OFFSET_ASSIGNMENT);
    }

    /**
     * Composes the translucent chunk fragment shader with zero-alpha discard and exact froxel weather fog.
     * @param original the Embeddium block_layer_opaque.fsh source
     * @param weatherFogInclude the contents of weather_fog.glsl
     * @param excessInclude the contents of weather_fog_excess.glsl
     * @return the transformed fragment shader source
     * @throws IllegalStateException when an expected anchor is absent from the original source
     */
    public static String fragment(String original, String weatherFogInclude, String excessInclude) {
        if (!original.contains(FRAGMENT_OUT_ANCHOR)) {
            throw new IllegalStateException("Missing fragColor output anchor in Embeddium chunk fragment shader");
        }
        if (!original.contains(FRAGMENT_FOG_ANCHOR)) {
            throw new IllegalStateException("Missing linear fog anchor in Embeddium chunk fragment shader");
        }
        String injectedDeclarations = weatherFogInclude + "\n" + excessInclude + FRAGMENT_UNIFORMS + FRAGMENT_OUT_ANCHOR;
        return original.replace(FRAGMENT_OUT_ANCHOR, injectedDeclarations)
                .replace(FRAGMENT_FOG_ANCHOR, FRAGMENT_FOG_BODY);
    }
}
