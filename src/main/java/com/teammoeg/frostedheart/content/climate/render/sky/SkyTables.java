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

import java.util.Arrays;

/**
 * Lookup tables of Hillaire 2020 for one set of settings, built on the CPU.
 * Multiple scattering is computed against a black ground. Light reflected between snow and atmosphere is added
 * afterwards as L_ground = (A / pi) * E_0 / (1 - A * S), where S is the spherical albedo of the atmosphere.
 * Instances are immutable after construction, so a worker thread may compute views while another thread reads them.
 */
public final class SkyTables {
    public static final int VIEW_WIDTH = 200;
    public static final int VIEW_HEIGHT = 100;
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;
    private static final int VIEW_STEPS = 30;
    private static final int MULTIPLE_SCATTERING_SIZE = 32;
    private static final int MULTIPLE_SCATTERING_STEPS = 20;
    private static final int DIRECTION_ROOT = 8;
    private static final int GLOW_VIEW_SAMPLES = 64;
    private static final int GLOW_ALTITUDE_SAMPLES = 64;
    private static final int IRRADIANCE_AZIMUTH_SAMPLES = 64;
    private static final int IRRADIANCE_COSINE_SAMPLES = 32;
    private static final int HALO_IRRADIANCE_SAMPLES = 64;
    private static final int HORIZON_AZIMUTH_SAMPLES = 64;
    private static final double MAXIMUM_TRANSFER = 0.9999;

    private final SkySettings settings;
    private final SkyAtmosphere atmosphere;
    private final SkyTransmittance transmittance;
    private final double[] multipleScattering =
            new double[MULTIPLE_SCATTERING_SIZE * MULTIPLE_SCATTERING_SIZE * CHANNELS];
    private final double[] glowResponse = new double[(GLOW_VIEW_SAMPLES + 1) * CHANNELS];
    private final double[] sphericalAlbedo = new double[CHANNELS];

    public SkyTables(SkySettings settings) {
        this.settings = settings;
        this.atmosphere = new SkyAtmosphere(settings);
        this.transmittance = new SkyTransmittance(atmosphere);
        buildMultipleScattering();
        buildGlowResponse();
    }

    public SkySettings settings() {
        return settings;
    }

    public double nanobotMegatons() {
        return settings.nanobotOpticalDepth() * atmosphere.nanobotBands.megatonsForUnitOpticalDepth();
    }

    private static double[] sphereDirection(int azimuthIndex, int cosineIndex) {
        double azimuth = 2.0 * Math.PI * (azimuthIndex + 0.5) / DIRECTION_ROOT;
        double cosine = 1.0 - 2.0 * (cosineIndex + 0.5) / DIRECTION_ROOT;
        double sine = Math.sqrt(1.0 - cosine * cosine);
        return new double[] {sine * Math.cos(azimuth), cosine, sine * Math.sin(azimuth)};
    }

    private void buildMultipleScattering() {
        double[] secondOrder = new double[CHANNELS];
        double[] transfer = new double[CHANNELS];
        double[] groundReach = new double[CHANNELS];
        for (int row = 0; row < MULTIPLE_SCATTERING_SIZE; row++) {
            double altitude = Math.max(1.0, row / (MULTIPLE_SCATTERING_SIZE - 1.0) * SkyAtmosphere.ATMOSPHERE_HEIGHT);
            double radius = SkyAtmosphere.GROUND_RADIUS + altitude;
            for (int column = 0; column < MULTIPLE_SCATTERING_SIZE; column++) {
                double sunCosine = column / (MULTIPLE_SCATTERING_SIZE - 1.0) * 2.0 - 1.0;
                double sunSine = Math.sqrt(Math.max(0.0, 1.0 - sunCosine * sunCosine));
                integrateIsotropic(radius, sunSine, sunCosine, true, secondOrder, transfer, groundReach);
                int texel = (column + row * MULTIPLE_SCATTERING_SIZE) * CHANNELS;
                for (int channel = 0; channel < CHANNELS; channel++) {
                    multipleScattering[texel + channel] =
                            secondOrder[channel] / (1.0 - Math.min(MAXIMUM_TRANSFER, transfer[channel]));
                }
            }
        }
    }

    private void integrateIsotropic(double radius, double sunSine, double sunCosine, boolean withSun,
            double[] secondOrder, double[] transfer, double[] groundReach) {
        Arrays.fill(secondOrder, 0.0);
        Arrays.fill(transfer, 0.0);
        Arrays.fill(groundReach, 0.0);
        double[] sun = new double[CHANNELS];
        double[] viewTransmittance = new double[CHANNELS];
        double directionCount = DIRECTION_ROOT * DIRECTION_ROOT;
        for (int azimuthIndex = 0; azimuthIndex < DIRECTION_ROOT; azimuthIndex++) {
            for (int cosineIndex = 0; cosineIndex < DIRECTION_ROOT; cosineIndex++) {
                double[] direction = sphereDirection(azimuthIndex, cosineIndex);
                double cosine = direction[1];
                double groundDistance = SkyAtmosphere.distanceToGroundOrInfinity(radius, cosine);
                boolean hitsGround = groundDistance < Double.POSITIVE_INFINITY;
                double length = hitsGround ? groundDistance : SkyAtmosphere.distanceToTop(radius, cosine);
                double stepLength = length / MULTIPLE_SCATTERING_STEPS;
                Arrays.fill(viewTransmittance, 1.0);
                for (int step = 0; step < MULTIPLE_SCATTERING_STEPS; step++) {
                    double travelled = (step + 0.5) * stepLength;
                    double sampleRadius = SkyAtmosphere.radiusAlongRay(radius, cosine, travelled);
                    int slot = atmosphere.altitudeSlot(sampleRadius);
                    if (withSun) {
                        double sampleX = direction[0] * travelled;
                        double sampleY = radius + direction[1] * travelled;
                        double sampleSunCosine = (sampleX * sunSine + sampleY * sunCosine) / sampleRadius;
                        transmittance.sample(sampleRadius, sampleSunCosine, sun);
                    }
                    for (int channel = 0; channel < CHANNELS; channel++) {
                        double scattering = atmosphere.totalScattering[slot + channel];
                        double extinction = atmosphere.totalExtinction[slot + channel];
                        double stepTransmittance = Math.exp(-extinction * stepLength);
                        double integral = extinction > 0.0 ? (1.0 - stepTransmittance) / extinction : stepLength;
                        if (withSun) {
                            secondOrder[channel] += viewTransmittance[channel] * scattering * sun[channel]
                                    / (4.0 * Math.PI) * integral;
                        }
                        transfer[channel] += viewTransmittance[channel] * scattering * integral;
                        viewTransmittance[channel] *= stepTransmittance;
                    }
                }
                if (hitsGround) {
                    for (int channel = 0; channel < CHANNELS; channel++) {
                        groundReach[channel] += viewTransmittance[channel];
                    }
                }
            }
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            secondOrder[channel] /= directionCount;
            transfer[channel] /= directionCount;
            groundReach[channel] /= directionCount;
        }
    }

    private void buildGlowResponse() {
        double[] secondOrder = new double[CHANNELS];
        double[] transfer = new double[CHANNELS];
        double[] groundReach = new double[CHANNELS];
        double[] sourceByAltitude = new double[(GLOW_ALTITUDE_SAMPLES + 1) * CHANNELS];
        for (int sample = 0; sample <= GLOW_ALTITUDE_SAMPLES; sample++) {
            double altitude = Math.max(1.0, sample / (double) GLOW_ALTITUDE_SAMPLES * SkyAtmosphere.ATMOSPHERE_HEIGHT);
            integrateIsotropic(SkyAtmosphere.GROUND_RADIUS + altitude, 0.0, 1.0, false, secondOrder, transfer,
                    groundReach);
            for (int channel = 0; channel < CHANNELS; channel++) {
                sourceByAltitude[sample * CHANNELS + channel] =
                        groundReach[channel] / (1.0 - Math.min(MAXIMUM_TRANSFER, transfer[channel]));
            }
        }

        double radius = SkyAtmosphere.CAMERA_RADIUS;
        double[] viewTransmittance = new double[CHANNELS];
        for (int sample = 0; sample <= GLOW_VIEW_SAMPLES; sample++) {
            double cosine = sample / (double) GLOW_VIEW_SAMPLES;
            double length = SkyAtmosphere.distanceToTop(radius, cosine);
            double stepLength = length / VIEW_STEPS;
            Arrays.fill(viewTransmittance, 1.0);
            for (int step = 0; step < VIEW_STEPS; step++) {
                double travelled = (step + 0.5) * stepLength;
                double sampleRadius = SkyAtmosphere.radiusAlongRay(radius, cosine, travelled);
                int slot = atmosphere.altitudeSlot(sampleRadius);
                double position = (sampleRadius - SkyAtmosphere.GROUND_RADIUS) / SkyAtmosphere.ATMOSPHERE_HEIGHT
                        * GLOW_ALTITUDE_SAMPLES;
                int lower = Math.max(0, Math.min(GLOW_ALTITUDE_SAMPLES - 1, (int) position));
                double fraction = Math.max(0.0, Math.min(1.0, position - lower));
                for (int channel = 0; channel < CHANNELS; channel++) {
                    double extinction = atmosphere.totalExtinction[slot + channel];
                    double stepTransmittance = Math.exp(-extinction * stepLength);
                    double integral = extinction > 0.0 ? (1.0 - stepTransmittance) / extinction : stepLength;
                    double source = sourceByAltitude[lower * CHANNELS + channel] * (1.0 - fraction)
                            + sourceByAltitude[(lower + 1) * CHANNELS + channel] * fraction;
                    glowResponse[sample * CHANNELS + channel] += viewTransmittance[channel]
                            * atmosphere.totalScattering[slot + channel] * source * integral;
                    viewTransmittance[channel] *= stepTransmittance;
                }
            }
        }

        for (int channel = 0; channel < CHANNELS; channel++) {
            double response = 0.0;
            for (int sample = 0; sample < GLOW_VIEW_SAMPLES; sample++) {
                double cosineLow = sample / (double) GLOW_VIEW_SAMPLES;
                double cosineHigh = (sample + 1.0) / GLOW_VIEW_SAMPLES;
                double middle = 0.5 * (cosineLow + cosineHigh);
                double value = 0.5 * (glowResponse[sample * CHANNELS + channel]
                        + glowResponse[(sample + 1) * CHANNELS + channel]);
                response += value * middle * (cosineHigh - cosineLow);
            }
            sphericalAlbedo[channel] = 2.0 * response;
        }
    }

    private void sampleMultipleScattering(double radius, double sunCosine, double[] result) {
        double columnPosition = (0.5 + 0.5 * sunCosine) * (MULTIPLE_SCATTERING_SIZE - 1);
        double rowPosition = (radius - SkyAtmosphere.GROUND_RADIUS) / SkyAtmosphere.ATMOSPHERE_HEIGHT
                * (MULTIPLE_SCATTERING_SIZE - 1);
        columnPosition = Math.max(0.0, Math.min(MULTIPLE_SCATTERING_SIZE - 1.0, columnPosition));
        rowPosition = Math.max(0.0, Math.min(MULTIPLE_SCATTERING_SIZE - 1.0, rowPosition));
        int column = Math.min((int) columnPosition, MULTIPLE_SCATTERING_SIZE - 2);
        int row = Math.min((int) rowPosition, MULTIPLE_SCATTERING_SIZE - 2);
        double columnFraction = columnPosition - column;
        double rowFraction = rowPosition - row;
        int topLeft = (column + row * MULTIPLE_SCATTERING_SIZE) * CHANNELS;
        int bottomLeft = (column + (row + 1) * MULTIPLE_SCATTERING_SIZE) * CHANNELS;
        for (int channel = 0; channel < CHANNELS; channel++) {
            double top = multipleScattering[topLeft + channel] * (1.0 - columnFraction)
                    + multipleScattering[topLeft + CHANNELS + channel] * columnFraction;
            double bottom = multipleScattering[bottomLeft + channel] * (1.0 - columnFraction)
                    + multipleScattering[bottomLeft + CHANNELS + channel] * columnFraction;
            result[channel] = top * (1.0 - rowFraction) + bottom * rowFraction;
        }
    }

    public SkyView computeView(double sunElevationRadians) {
        double[] sun = {Math.cos(sunElevationRadians), Math.sin(sunElevationRadians), 0.0};
        float[] rawSky = new float[VIEW_WIDTH * VIEW_HEIGHT * CHANNELS];
        float[] ice = new float[VIEW_WIDTH * VIEW_HEIGHT * CHANNELS];
        double[] radiance = new double[CHANNELS];
        double[] iceRadiance = new double[CHANNELS];
        for (int row = 0; row < VIEW_HEIGHT; row++) {
            double elevation = rowElevation(row);
            for (int column = VIEW_WIDTH / 2; column < VIEW_WIDTH; column++) {
                double azimuth = -Math.PI + 2.0 * Math.PI * (column + 0.5) / VIEW_WIDTH;
                double[] direction = {
                        Math.cos(elevation) * Math.cos(azimuth),
                        Math.sin(elevation),
                        Math.cos(elevation) * Math.sin(azimuth)
                };
                integrateView(direction, sun, radiance, iceRadiance);
                int texel = (column + row * VIEW_WIDTH) * CHANNELS;
                int mirroredTexel = (VIEW_WIDTH - 1 - column + row * VIEW_WIDTH) * CHANNELS;
                for (int channel = 0; channel < CHANNELS; channel++) {
                    rawSky[texel + channel] = (float) radiance[channel];
                    rawSky[mirroredTexel + channel] = (float) radiance[channel];
                    ice[texel + channel] = (float) iceRadiance[channel];
                    ice[mirroredTexel + channel] = (float) iceRadiance[channel];
                }
            }
        }

        double[] groundRadiance = new double[CHANNELS];
        double[] sunTransmittance = new double[CHANNELS];
        transmittance.sample(SkyAtmosphere.GROUND_RADIUS, sun[1], sunTransmittance);
        for (int channel = 0; channel < CHANNELS; channel++) {
            double direct = sun[1] > 0.0 ? sun[1] * sunTransmittance[channel] : 0.0;
            double downward = direct + skyIrradiance(rawSky, channel) + narrowIceIrradiance(ice, sun, channel);
            groundRadiance[channel] = SkyAtmosphere.GROUND_ALBEDO / Math.PI * downward
                    / (1.0 - SkyAtmosphere.GROUND_ALBEDO * sphericalAlbedo[channel]);
        }

        float[] sky = new float[rawSky.length];
        for (int row = 0; row < VIEW_HEIGHT; row++) {
            double directionY = Math.sin(rowElevation(row));
            boolean hitsGround = SkyAtmosphere.distanceToGroundOrInfinity(SkyAtmosphere.CAMERA_RADIUS, directionY)
                    < Double.POSITIVE_INFINITY;
            double position = Math.max(0.0, directionY) * GLOW_VIEW_SAMPLES;
            int lower = Math.min(GLOW_VIEW_SAMPLES - 1, (int) position);
            double fraction = position - lower;
            for (int column = 0; column < VIEW_WIDTH; column++) {
                int texel = (column + row * VIEW_WIDTH) * CHANNELS;
                for (int channel = 0; channel < CHANNELS; channel++) {
                    if (hitsGround) {
                        sky[texel + channel] = (float) groundRadiance[channel];
                        ice[texel + channel] = 0.0F;
                        continue;
                    }
                    double response = glowResponse[lower * CHANNELS + channel] * (1.0 - fraction)
                            + glowResponse[(lower + 1) * CHANNELS + channel] * fraction;
                    sky[texel + channel] = (float) (rawSky[texel + channel] + groundRadiance[channel] * response);
                }
            }
        }

        double[] meanHorizonRadiance = new double[CHANNELS];
        for (int index = 0; index < HORIZON_AZIMUTH_SAMPLES; index++) {
            double azimuth = -Math.PI + 2.0 * Math.PI * (index + 0.5) / HORIZON_AZIMUTH_SAMPLES;
            for (int channel = 0; channel < CHANNELS; channel++) {
                meanHorizonRadiance[channel] += SkyView.sample(sky, channel, azimuth, 0.0) / HORIZON_AZIMUTH_SAMPLES;
            }
        }
        return new SkyView(sunElevationRadians, sky, ice, groundRadiance, meanHorizonRadiance);
    }

    private static double rowElevation(int row) {
        double position = 2.0 * (row + 0.5) / VIEW_HEIGHT - 1.0;
        double latitude = Math.signum(position) * position * position * Math.PI / 2.0;
        return SkyView.latitudeToElevation(latitude);
    }

    private void integrateView(double[] direction, double[] sun, double[] radiance, double[] iceRadiance) {
        double radius = SkyAtmosphere.CAMERA_RADIUS;
        double cosine = direction[1];
        double viewSunCosine = direction[0] * sun[0] + direction[1] * sun[1] + direction[2] * sun[2];
        double rayleighPhase = SkyPhase.rayleigh(viewSunCosine);
        double aerosolPhase = SkyPhase.henyeyGreenstein(viewSunCosine, SkyAtmosphere.AEROSOL_ASYMMETRY);
        double icePhase = SkyPhase.broadIce(viewSunCosine);
        double[] nanobotPhase = new double[CHANNELS];
        double[] viewTransmittance = new double[CHANNELS];
        double[] sunTransmittance = new double[CHANNELS];
        double[] multiple = new double[CHANNELS];
        for (int channel = 0; channel < CHANNELS; channel++) {
            nanobotPhase[channel] = SkyPhase.henyeyGreenstein(viewSunCosine,
                    atmosphere.nanobotBands.asymmetry()[channel]);
            viewTransmittance[channel] = 1.0;
            radiance[channel] = 0.0;
            iceRadiance[channel] = 0.0;
        }

        double groundDistance = SkyAtmosphere.distanceToGroundOrInfinity(radius, cosine);
        double length = groundDistance < Double.POSITIVE_INFINITY
                ? groundDistance
                : SkyAtmosphere.distanceToTop(radius, cosine);
        double stepLength = length / VIEW_STEPS;
        for (int step = 0; step < VIEW_STEPS; step++) {
            double travelled = (step + 0.5) * stepLength;
            double sampleRadius = SkyAtmosphere.radiusAlongRay(radius, cosine, travelled);
            int slot = atmosphere.altitudeSlot(sampleRadius);
            double sampleX = direction[0] * travelled;
            double sampleY = radius + direction[1] * travelled;
            double sampleZ = direction[2] * travelled;
            double sampleSunCosine = (sampleX * sun[0] + sampleY * sun[1] + sampleZ * sun[2]) / sampleRadius;
            transmittance.sample(sampleRadius, sampleSunCosine, sunTransmittance);
            sampleMultipleScattering(sampleRadius, sampleSunCosine, multiple);
            for (int channel = 0; channel < CHANNELS; channel++) {
                double scattering = atmosphere.totalScattering[slot + channel];
                double extinction = atmosphere.totalExtinction[slot + channel];
                double stepTransmittance = Math.exp(-extinction * stepLength);
                double integral = extinction > 0.0 ? (1.0 - stepTransmittance) / extinction : stepLength;
                double phaseWeighted = atmosphere.rayleighScattering[slot + channel] * rayleighPhase
                        + atmosphere.aerosolScattering[slot + channel] * aerosolPhase
                        + atmosphere.nanobotScattering[slot + channel] * nanobotPhase[channel]
                        + atmosphere.iceScattering[slot + channel] * icePhase;
                double source = phaseWeighted * sunTransmittance[channel] + scattering * multiple[channel];
                radiance[channel] += viewTransmittance[channel] * source * integral;
                iceRadiance[channel] += viewTransmittance[channel] * atmosphere.iceScattering[slot + channel]
                        * sunTransmittance[channel] * integral;
                viewTransmittance[channel] *= stepTransmittance;
            }
        }
    }

    private static double skyIrradiance(float[] table, int channel) {
        double sum = 0.0;
        for (int cosineIndex = 0; cosineIndex < IRRADIANCE_COSINE_SAMPLES; cosineIndex++) {
            double cosine = (cosineIndex + 0.5) / IRRADIANCE_COSINE_SAMPLES;
            double elevation = Math.asin(cosine);
            for (int azimuthIndex = 0; azimuthIndex < IRRADIANCE_AZIMUTH_SAMPLES; azimuthIndex++) {
                double azimuth = -Math.PI + 2.0 * Math.PI * (azimuthIndex + 0.5) / IRRADIANCE_AZIMUTH_SAMPLES;
                sum += SkyView.sample(table, channel, azimuth, elevation) * cosine;
            }
        }
        return sum * 2.0 * Math.PI / (IRRADIANCE_COSINE_SAMPLES * IRRADIANCE_AZIMUTH_SAMPLES);
    }

    /**
     * Irradiance from the diffraction peak and the halo ring, which the sky view table leaves out.
     * The peak is treated as light from the sun's direction, and the ring is sampled along its circle.
     */
    private static double narrowIceIrradiance(float[] ice, double[] sun, int channel) {
        double irradiance = 0.0;
        if (sun[1] > 0.0) {
            irradiance += SkyAtmosphere.ICE_DIFFRACTION_FRACTION * sun[1]
                    * SkyView.sample(ice, channel, 0.0, Math.asin(sun[1]));
        }
        double ringAngle = SkyPhase.haloAngleRadians(channel);
        double ringSum = 0.0;
        for (int index = 0; index < HALO_IRRADIANCE_SAMPLES; index++) {
            double around = 2.0 * Math.PI * (index + 0.5) / HALO_IRRADIANCE_SAMPLES;
            double perpendicular = Math.sin(ringAngle) * Math.cos(around);
            double x = Math.cos(ringAngle) * sun[0] - perpendicular * sun[1];
            double y = Math.cos(ringAngle) * sun[1] + perpendicular * sun[0];
            double z = Math.sin(ringAngle) * Math.sin(around);
            if (y > 0.0) {
                ringSum += y * SkyView.sample(ice, channel, Math.atan2(z, x), Math.asin(y));
            }
        }
        return irradiance + SkyAtmosphere.ICE_HALO_FRACTION * ringSum / HALO_IRRADIANCE_SAMPLES;
    }
}
