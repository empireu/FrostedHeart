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
 * Reflection off the two large faces of plate crystals, which float nearly level and act as small mirrors.
 */
final class IceMirror {
    private IceMirror() {
    }

    /**
     * The share of light a plate reflects, from its near face and from its far face through the plate, for
     * unpolarized light. Each polarization reflects 2 F / (1 + F) of the Fresnel reflectance F of one face.
     * The grazing angle is between the light's direction and the face.
     */
    static double slabReflectance(double sineOfGrazingAngle, double refractiveIndex) {
        double cosineOfIncidence = Math.max(0.0, Math.min(1.0, sineOfGrazingAngle));
        double squaredSineOfIncidence = 1.0 - cosineOfIncidence * cosineOfIncidence;
        double cosineOfRefraction = Math.sqrt(1.0 - squaredSineOfIncidence / (refractiveIndex * refractiveIndex));
        double perpendicular = (cosineOfIncidence - refractiveIndex * cosineOfRefraction)
                / (cosineOfIncidence + refractiveIndex * cosineOfRefraction);
        double parallel = (refractiveIndex * cosineOfIncidence - cosineOfRefraction)
                / (refractiveIndex * cosineOfIncidence + cosineOfRefraction);
        double perpendicularFace = perpendicular * perpendicular;
        double parallelFace = parallel * parallel;
        return perpendicularFace / (1.0 + perpendicularFace) + parallelFace / (1.0 + parallelFace);
    }

    /**
     * The density per steradian of face normals at the tilt from the vertical, for tilts spread as a Gaussian of the
     * standard deviation in radians. It integrates to 1 over small tilts.
     */
    static double tiltDensity(double tilt, double tiltSigma) {
        return Math.exp(-tilt * tilt / (2.0 * tiltSigma * tiltSigma)) / (2.0 * Math.PI * tiltSigma * tiltSigma);
    }
}
