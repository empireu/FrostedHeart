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
    /**
     * Below this height difference in blocks, the optical depth of a path takes the density at its middle.
     */
    private static final double SMALLEST_RISE = 0.01;
    /**
     * Keeps crystals finitely far at the zenith, where the angle at the crystals between the eye and the light closes.
     */
    private static final double SMALLEST_SINE_AT_CRYSTAL = 1.0e-6;
    /**
     * The most standard deviations of the pillar's width that a ribbon covers on each side.
     */
    private static final double MOST_RIBBON_SPREADS = 8.0;
    /**
     * The smallest value before sRGB encoding that a pillar adds to the display and is still drawn, which encodes to
     * half a step of 255.
     */
    static final double VISIBLE_VALUE = 0.5 / 255.0 / 12.92;
    /**
     * Ribbons end here, short of the zenith, where every pillar meets.
     */
    static final double HIGHEST_RIBBON_ELEVATION = Math.toRadians(89.5);
    /**
     * The lateral blur as a share of the angle of a pixel at the center of the screen.
     */
    private static final double PIXEL_BLUR_FRACTION = 0.5;
    /**
     * Turns the radiance of dustRadiance, times the face area per volume in 1 per block and the strength of the light,
     * into the value a pillar adds to the display before its tone curve. It makes the pillar of a torch 32 blocks away
     * and 1.12 blocks below the eye, whose dust bottom is 1.62 blocks below the eye, at an extinction of 1 per
     * kilometer, show 0.1 of the display in linear light at 10 degrees, with plates and wobbling plates of
     * crystals.csv and the lateral blur of a screen 1080 pixels tall with a vertical field of view of 70 degrees.
     */
    static final double BRIGHTNESS = 433.0;

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
        double lowestTilt = lowestTilt(elevation, tiltSigma);
        double highestTilt = highestTilt(elevation, lightElevation, tiltSigma);
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

    /**
     * The face area per volume at the height, as a share of the face area at the bottom of the diamond dust layer. It
     * is 1 below the bottom and falls with LightPillarSelection.DUST_SCALE_HEIGHT above it.
     */
    static double dustDensity(double height, double dustBottomHeight) {
        return Math.exp(-Math.max(0.0, height - dustBottomHeight) / LightPillarSelection.DUST_SCALE_HEIGHT);
    }

    /**
     * The optical depth of the dust along a straight path of the length between two heights, for the extinction per
     * block at the bottom of the layer.
     */
    static double dustOpticalDepth(double fromHeight, double toHeight, double length, double extinction,
            double dustBottomHeight) {
        double rise = toHeight - fromHeight;
        double meanDensity = Math.abs(rise) > SMALLEST_RISE
                ? (dustColumn(toHeight, dustBottomHeight) - dustColumn(fromHeight, dustBottomHeight)) / rise
                : dustDensity(0.5 * (fromHeight + toHeight), dustBottomHeight);
        return extinction * length * meanDensity;
    }

    /**
     * The integral of dustDensity over height from the bottom of the layer up to the height.
     */
    private static double dustColumn(double height, double dustBottomHeight) {
        double above = height - dustBottomHeight;
        if (above <= 0.0) {
            return above;
        }
        return LightPillarSelection.DUST_SCALE_HEIGHT
                * (1.0 - Math.exp(-above / LightPillarSelection.DUST_SCALE_HEIGHT));
    }

    /**
     * The radiance of the pillar in the diamond dust, as radiance gives it but per unit face area of the plates per
     * volume at the bottom of the dust. Each reflection takes the face area at its crystals from dustDensity and is
     * dimmed along its way from the light and to the eye by the extinction per block at the bottom of the dust.
     * The lateral blur in radians widens the pillar on the sky without changing its light. Only crystals nearer to the
     * eye than the scene distance count, which is infinite where no scene stands. light_pillars.glsl computes the same.
     */
    static double dustRadiance(double elevation, double lateralAngle, double horizontalDistance, double lightHeight,
            double tiltSigma, double refractiveIndex, double lateralBlur, double dustBottomHeight, double extinction,
            double sceneDistance) {
        double lightElevation = Math.atan2(lightHeight, horizontalDistance);
        double sineOfOffset = Math.sin(elevation - lightElevation);
        if (!(sineOfOffset > 0.0)) {
            return 0.0;
        }
        double lightDistance = Math.hypot(horizontalDistance, lightHeight);
        double lightToRay = lightDistance * sineOfOffset;
        if (!(lightToRay > 0.0)) {
            return 0.0;
        }
        // The distance to the crystals falls as their tilt rises, and it is the scene distance at this tilt:
        double nearTilt = elevation - 0.5 * Math.atan2(lightToRay,
                lightDistance * Math.cos(elevation - lightElevation) - sceneDistance);
        double lowestTilt = Math.max(lowestTilt(elevation, tiltSigma), nearTilt);
        double highestTilt = highestTilt(elevation, lightElevation, tiltSigma);
        if (!(highestTilt > lowestTilt)) {
            return 0.0;
        }
        double middleTilt = 0.5 * (highestTilt + lowestTilt);
        double halfRange = 0.5 * (highestTilt - lowestTilt);
        double sineOfElevation = Math.sin(elevation);
        double tiltVariance = tiltSigma * tiltSigma;
        double sum = 0.0;
        for (int node = 0; node < NODES.length; node++) {
            double alongTilt = middleTilt + halfRange * NODES[node];
            double lightDepression = elevation - 2.0 * alongTilt;
            double sineAtCrystal = Math.max(Math.sin(elevation + lightDepression), SMALLEST_SINE_AT_CRYSTAL);
            double eyeLeg = lightDistance * Math.sin(lightDepression + lightElevation) / sineAtCrystal;
            double lightLeg = lightToRay / sineAtCrystal;
            double crystalHeight = eyeLeg * sineOfElevation;
            double acrossScale = (1.0 + eyeLeg / lightLeg) / (Math.sin(lightDepression) + sineOfElevation);
            double acrossVariance = tiltVariance + lateralBlur * lateralBlur * acrossScale * acrossScale;
            double opticalDepth = dustOpticalDepth(lightHeight, crystalHeight, lightLeg, extinction, dustBottomHeight)
                    + dustOpticalDepth(crystalHeight, 0.0, eyeLeg, extinction, dustBottomHeight);
            sum += WEIGHTS[node] * IceMirror.slabReflectance(Math.sin(elevation - alongTilt), refractiveIndex)
                    * gaussian(alongTilt, tiltVariance) * gaussian(lateralAngle * acrossScale, acrossVariance)
                    * dustDensity(crystalHeight, dustBottomHeight) * Math.exp(-opticalDepth);
        }
        return sum * halfRange / (2.0 * lightToRay);
    }

    /**
     * The largest standard deviation across the pillar at the elevation, in radians on the sky, among the reflections
     * that dustRadiance sums. The pillar falls off sideways at least as fast as a Gaussian of this width.
     */
    static double widestLateralSpread(double elevation, double horizontalDistance, double lightHeight,
            double tiltSigma, double lateralBlur) {
        double lightElevation = Math.atan2(lightHeight, horizontalDistance);
        double sineOfOffset = Math.sin(elevation - lightElevation);
        double lowestTilt = lowestTilt(elevation, tiltSigma);
        double highestTilt = highestTilt(elevation, lightElevation, tiltSigma);
        if (!(sineOfOffset > 0.0) || !(highestTilt > lowestTilt)) {
            return 0.0;
        }
        double middleTilt = 0.5 * (highestTilt + lowestTilt);
        double halfRange = 0.5 * (highestTilt - lowestTilt);
        double sineOfElevation = Math.sin(elevation);
        double widest = 0.0;
        for (double node : NODES) {
            double lightDepression = elevation - 2.0 * (middleTilt + halfRange * node);
            double eyeOverLightLeg = Math.sin(lightDepression + lightElevation) / sineOfOffset;
            double acrossScale = (1.0 + eyeOverLightLeg) / (Math.sin(lightDepression) + sineOfElevation);
            double spread = Math.sqrt(tiltSigma * tiltSigma / (acrossScale * acrossScale) + lateralBlur * lateralBlur);
            widest = Math.max(widest, spread);
        }
        return widest;
    }

    /**
     * The lowest elevation where the pillar can show. It is the light itself, or for a light below the eye, where
     * crystals above the eye mirror it with the most tilted faces that dustRadiance counts.
     */
    static double lowestRibbonElevation(double horizontalDistance, double lightHeight, double tiltSigma) {
        double lightElevation = Math.atan2(lightHeight, horizontalDistance);
        return Math.max(lightElevation, -lightElevation - 2.0 * TILT_RANGE_SIGMAS * tiltSigma);
    }

    /**
     * Half the width of the ribbon a pillar is drawn on at the elevation, the lateral angle beyond which the pillar
     * stays below the visible value, given its value at the center. It is 0 where the center is not visible.
     */
    static double ribbonHalfWidth(double centerValue, double visibleValue, double widestLateralSpread) {
        if (!(centerValue > visibleValue)) {
            return 0.0;
        }
        double spreads = Math.min(MOST_RIBBON_SPREADS, Math.sqrt(2.0 * Math.log(centerValue / visibleValue)));
        return spreads * widestLateralSpread;
    }

    /**
     * The lateral blur in radians that keeps a pillar narrower than a pixel from flickering, for the angle of a pixel
     * at the center of the screen.
     */
    static double lateralBlur(double pixelAngle) {
        return PIXEL_BLUR_FRACTION * pixelAngle;
    }

    private static double lowestTilt(double elevation, double tiltSigma) {
        return Math.max(-TILT_RANGE_SIGMAS * tiltSigma, elevation - 0.5 * Math.PI);
    }

    private static double highestTilt(double elevation, double lightElevation, double tiltSigma) {
        return Math.min(TILT_RANGE_SIGMAS * tiltSigma, 0.5 * (elevation + lightElevation));
    }

    private static double gaussian(double value, double variance) {
        return Math.exp(-value * value / (2.0 * variance)) / Math.sqrt(2.0 * Math.PI * variance);
    }
}
