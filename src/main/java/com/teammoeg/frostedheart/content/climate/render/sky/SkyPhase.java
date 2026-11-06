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

package com.teammoeg.frostedheart.content.climate.render.sky;

/**
 * Phase functions of the sky, each normalised over the sphere.
 * Ice scatters through three parts, a diffraction peak around the sun, a broad lobe, and the 22 degree halo ring.
 * The halo constants are repeated in calculated_sky.fsh and must stay equal.
 */
public final class SkyPhase {
    private static final double[] HALO_ANGLE_RADIANS = {
            Math.toRadians(21.7), Math.toRadians(22.0), Math.toRadians(22.4)
    };
    private static final double HALO_INNER_WIDTH_RADIANS = Math.toRadians(0.25);
    private static final double HALO_OUTER_WIDTH_RADIANS = Math.toRadians(1.2);
    private static final double HALO_UPPER_COSINE = Math.cos(Math.toRadians(19.0));
    private static final double HALO_LOWER_COSINE = Math.cos(Math.toRadians(32.0));
    private static final double HALO_ANGLE_DENSITY = 2.0
            / (Math.sqrt(2.0 * Math.PI) * (HALO_INNER_WIDTH_RADIANS + HALO_OUTER_WIDTH_RADIANS));

    private SkyPhase() {
    }

    public static double rayleigh(double cosine) {
        return 3.0 / (16.0 * Math.PI) * (1.0 + cosine * cosine);
    }

    public static double henyeyGreenstein(double cosine, double asymmetry) {
        double squared = asymmetry * asymmetry;
        double denominator = 1.0 + squared - 2.0 * asymmetry * cosine;
        return (1.0 - squared) / (4.0 * Math.PI * denominator * Math.sqrt(denominator));
    }

    /**
     * The part of the ice phase function that the sky view table holds.
     */
    public static double broadIce(double cosine) {
        return SkyAtmosphere.ICE_BROAD_FRACTION * henyeyGreenstein(cosine, SkyAtmosphere.ICE_BROAD_ASYMMETRY);
    }

    /**
     * The parts of the ice phase function that are too narrow for the sky view table, so they are added per pixel.
     */
    public static double narrowIce(double cosine, int channel) {
        return SkyAtmosphere.ICE_DIFFRACTION_FRACTION
                * henyeyGreenstein(cosine, SkyAtmosphere.ICE_DIFFRACTION_ASYMMETRY)
                + SkyAtmosphere.ICE_HALO_FRACTION * haloRing(cosine, channel);
    }

    public static double haloAngleRadians(int channel) {
        return HALO_ANGLE_RADIANS[channel];
    }

    public static double haloRing(double cosine, int channel) {
        if (cosine > HALO_UPPER_COSINE || cosine < HALO_LOWER_COSINE) {
            return 0.0;
        }
        double angle = Math.acos(cosine);
        double offset = angle - HALO_ANGLE_RADIANS[channel];
        double width = offset < 0.0 ? HALO_INNER_WIDTH_RADIANS : HALO_OUTER_WIDTH_RADIANS;
        double density = HALO_ANGLE_DENSITY * Math.exp(-0.5 * offset * offset / (width * width));
        return density / (2.0 * Math.PI * Math.sin(angle));
    }
}
