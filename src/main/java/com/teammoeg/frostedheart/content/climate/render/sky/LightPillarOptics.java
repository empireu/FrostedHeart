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
 * The light pillar that plate crystals above a light show, a vertical line on the sky through the light.
 * Each point of it comes from the crystals at one height, which mirror the light to the eye.
 * Heights are relative to the eye, and distances and heights are in blocks.
 */
final class LightPillarOptics {
    /**
     * Nodes of the 8 point Gauss Legendre rule on the interval from -1 to 1.
     */
    private static final double[] NODES = {-0.9602898564975363, -0.7966664774136267, -0.5255324099163290,
            -0.1834346424956498, 0.1834346424956498, 0.5255324099163290, 0.7966664774136267, 0.9602898564975363};
    private static final double[] WEIGHTS = {0.1012285362903763, 0.2223810344533745, 0.3137066458778873,
            0.3626837833783620, 0.3626837833783620, 0.3137066458778873, 0.2223810344533745, 0.1012285362903763};
    /**
     * The tilts along the view ray that radiance integrates over, in standard deviations of the tilt.
     */
    private static final double TILT_RANGE_SIGMAS = 4.0;

    private LightPillarOptics() {
    }

    /**
     * The height of the crystals that show the point of the pillar at the elevation.
     */
    static double mirrorHeight(double elevation, double horizontalDistance, double lightHeight) {
        return 0.5 * (horizontalDistance * Math.tan(elevation) + lightHeight);
    }

    /**
     * The elevation of the point of the pillar that the crystals at the height show.
     */
    static double mirrorElevation(double crystalHeight, double horizontalDistance, double lightHeight) {
        return Math.atan2(2.0 * crystalHeight - lightHeight, horizontalDistance);
    }

    /**
     * The horizontal distance from the eye to the crystals at the height that mirror the light to the eye.
     */
    static double crystalDistance(double crystalHeight, double horizontalDistance, double lightHeight) {
        return horizontalDistance * crystalHeight / (2.0 * crystalHeight - lightHeight);
    }

    /**
     * The standard deviation across the pillar at the elevation, in radians on the sky, for plates whose tilt has
     * the standard deviation in radians.
     */
    static double lateralSpread(double elevation, double horizontalDistance, double lightHeight, double tiltSigma) {
        double crystalHeight = mirrorHeight(elevation, horizontalDistance, lightHeight);
        double eyeOverLightLeg = crystalHeight / (crystalHeight - lightHeight);
        return 2.0 * tiltSigma * Math.sin(elevation) / (1.0 + eyeOverLightLeg);
    }

    /**
     * The radiance of the pillar toward a direction, per unit intensity of the light and per unit face area of the
     * plates per volume, with that face area the same at every height.
     * The lateral angle is between the direction and the vertical plane through the eye and the light.
     * The pillar rises from the light, and the result is 0 where no crystal above both the eye and the light can
     * mirror it.
     * The tilt of the face that mirrors the light changes along the view ray, so the radiance integrates the reflected
     * light over that tilt with the 8 point Gauss Legendre rule. Crystals behind the eye are left out, which dims the
     * pillar where its crystals come close to the eye.
     */
    static double radiance(double elevation, double lateralAngle, double horizontalDistance, double lightHeight,
            double tiltSigma, double refractiveIndex) {
        double lightElevation = Math.atan2(lightHeight, horizontalDistance);
        double sineOfOffset = Math.sin(elevation - lightElevation);
        if (!(sineOfOffset > 0.0)) {
            return 0.0;
        }
        double lightToRay = Math.hypot(horizontalDistance, lightHeight) * sineOfOffset;
        if (!(lightToRay > 0.0)) {
            return 0.0;
        }
        double lowestTilt = Math.max(-TILT_RANGE_SIGMAS * tiltSigma, elevation - 0.5 * Math.PI);
        double highestTilt = Math.min(TILT_RANGE_SIGMAS * tiltSigma, 0.5 * (elevation + lightElevation));
        if (!(highestTilt > lowestTilt)) {
            return 0.0;
        }
        double middleTilt = 0.5 * (highestTilt + lowestTilt);
        double halfRange = 0.5 * (highestTilt - lowestTilt);
        double sineOfElevation = Math.sin(elevation);
        double sum = 0.0;
        for (int node = 0; node < NODES.length; node++) {
            double alongTilt = middleTilt + halfRange * NODES[node];
            double lightDepression = elevation - 2.0 * alongTilt;
            double eyeOverLightLeg = Math.sin(lightDepression + lightElevation) / sineOfOffset;
            double acrossTilt = lateralAngle * (1.0 + eyeOverLightLeg)
                    / (Math.sin(lightDepression) + sineOfElevation);
            double tilt = Math.sqrt(alongTilt * alongTilt + acrossTilt * acrossTilt);
            sum += WEIGHTS[node] * IceMirror.slabReflectance(Math.sin(elevation - alongTilt), refractiveIndex)
                    * IceMirror.tiltDensity(tilt, tiltSigma);
        }
        return sum * halfRange / (2.0 * lightToRay);
    }
}
