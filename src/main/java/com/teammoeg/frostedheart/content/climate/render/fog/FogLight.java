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
 * The color of the snow fog, which is the color a white top face at full sky light shows, read from the two lightmap
 * pixels beside the coordinate of full sky light.
 * The terrain, the fog and the sky then follow one light: the fog's source radiance is the radiance of a white surface
 * under the current sky, which is an upper bound of the radiance of snow, so nothing behind the fog can come out
 * brighter than the fog in front of it.
 * Every method is a pure function of its arguments, allocates nothing, and is safe to call from any thread.
 */
public final class FogLight {
    /**
     * The bits at which each channel of a lightmap pixel starts, as NativeImage packs it: red in bits 0 to 7, green in
     * 8 to 15, blue in 16 to 23, and alpha in 24 to 31, which the color ignores.
     */
    private static final int RED_SHIFT = 0;
    private static final int GREEN_SHIFT = 8;
    private static final int BLUE_SHIFT = 16;
    /**
     * The mask of one channel of a lightmap pixel, and the value that brings a channel of 0 to 255 to the color 0 to 1.
     */
    private static final int CHANNEL_MASK = 255;
    private static final float CHANNEL_DIVISOR = 255.0F;

    private FogLight() {
    }

    /**
     * Writes the red, green and blue of the mean of two lightmap pixels into the first three entries of result.
     * <p>
     * The mean is what a white top face at full sky light shows, because light.glsl samples the lightmap at uv / 256 and
     * full sky light packs to 240, so the coordinate 15/16 lies halfway between the centers of the rows for sky light 14
     * and 15, and LightTexture.turnOnLightLayer sets linear filtering. A white top face of shade 1.0 at full sky light
     * and no block light therefore shows exactly this mean.
     * @param skyFourteenAbgr the lightmap pixel at block light 0 and sky light 14, packed ABGR as NativeImage packs it
     * @param skyFifteenAbgr the lightmap pixel at block light 0 and sky light 15, packed in the same way
     * @param result the array that takes the red, green and blue as display sRGB from 0 to 1 in entries 0 to 2, whose
     *        other entries are left as they are. The next call overwrites them.
     * @throws IllegalArgumentException when result holds fewer than 3 entries
     */
    public static void whiteFaceColor(int skyFourteenAbgr, int skyFifteenAbgr, float[] result) {
        if (result.length < 3) {
            throw new IllegalArgumentException("The white face color needs a result of 3 entries instead of "
                    + result.length);
        }
        result[0] = channelMean(skyFourteenAbgr, skyFifteenAbgr, RED_SHIFT);
        result[1] = channelMean(skyFourteenAbgr, skyFifteenAbgr, GREEN_SHIFT);
        result[2] = channelMean(skyFourteenAbgr, skyFifteenAbgr, BLUE_SHIFT);
    }

    /**
     * The mean of one channel of two lightmap pixels, as (first + second) / 2 / 255. Allocates nothing.
     * @param firstAbgr the first lightmap pixel, packed ABGR
     * @param secondAbgr the second lightmap pixel, packed ABGR
     * @param shift the bits at which the channel starts, RED_SHIFT, GREEN_SHIFT or BLUE_SHIFT
     * @return the channel from 0 to 1
     */
    private static float channelMean(int firstAbgr, int secondAbgr, int shift) {
        int first = firstAbgr >> shift & CHANNEL_MASK;
        int second = secondAbgr >> shift & CHANNEL_MASK;
        return (first + second) / 2.0F / CHANNEL_DIVISOR;
    }
}
