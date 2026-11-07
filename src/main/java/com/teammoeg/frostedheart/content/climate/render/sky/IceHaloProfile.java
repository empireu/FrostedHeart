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
 * The part of the ice phase function that depends only on the angle from the sun, in ANGLE_BINS equal bins from 0
 * to pi. It holds the diffraction peak and the tumbling crystals, blurred like IceHaloMap.
 * Light of the tumbling crystals within FORWARD_CUT of the sun is moved into the first bin.
 * Instances are immutable after construction. The lookup is repeated in calculated_sky.fsh and must stay equal.
 */
public final class IceHaloProfile {
    public static final int ANGLE_BINS = IceCrystalData.SCATTERING_ANGLE_BINS;
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;
    private static final double ANGLE_STEP = Math.PI / ANGLE_BINS;
    private static final double FORWARD_CUT_RADIANS = Math.toRadians(0.5);
    private static final double BLUR_SPAN_SIGMAS = 3.0;
    private static final int BLUR_RING_SAMPLES = 48;
    private static final double RING_BLURRED_RADIANS = Math.toRadians(10.0);
    private static final int IRRADIANCE_RINGS = 360;
    private static final int BINS_PER_IRRADIANCE_RING = ANGLE_BINS / IRRADIANCE_RINGS;
    private static final int IRRADIANCE_RING_POINTS = 32;

    private final double blurSigma;
    private final float[] values = new float[ANGLE_BINS * CHANNELS];
    private final double[][] blurred = new double[CHANNELS][ANGLE_BINS];
    private final double[][] ringEnergies = new double[CHANNELS][IRRADIANCE_RINGS];

    public IceHaloProfile(IceCrystalData data, double blurSigma) {
        this.blurSigma = blurSigma;
        double diffraction = SkyAtmosphere.ICE_DIFFRACTION_FRACTION;
        double[][] raw = new double[CHANNELS][ANGLE_BINS];
        for (int bin = 0; bin < ANGLE_BINS; bin++) {
            double value = diffraction * SkyPhase.henyeyGreenstein(Math.cos((bin + 0.5) * ANGLE_STEP),
                    SkyAtmosphere.ICE_DIFFRACTION_ASYMMETRY);
            for (int channel = 0; channel < CHANNELS; channel++) {
                raw[channel][bin] = value;
            }
        }
        int forwardBins = (int) Math.round(FORWARD_CUT_RADIANS / ANGLE_STEP);
        for (IceCrystalData.Crystal crystal : data.crystals()) {
            if (crystal.orientation() != IceCrystalData.Orientation.RANDOM) {
                continue;
            }
            double weight = (1.0 - diffraction) * data.shareOf(crystal);
            for (int channel = 0; channel < CHANNELS; channel++) {
                double[] angles = data.scattering(crystal, channel);
                double forwardEnergy = 0.0;
                for (int bin = 0; bin < forwardBins; bin++) {
                    forwardEnergy += angles[bin] * binSolidAngle(bin);
                }
                raw[channel][0] += weight * forwardEnergy / binSolidAngle(0);
                for (int bin = forwardBins; bin < ANGLE_BINS; bin++) {
                    raw[channel][bin] += weight * angles[bin];
                }
            }
        }

        int span = (int) Math.ceil(BLUR_SPAN_SIGMAS * blurSigma / ANGLE_STEP);
        double cosineSpan = Math.cos(BLUR_SPAN_SIGMAS * blurSigma);
        int ringBins = (int) Math.ceil(RING_BLURRED_RADIANS / ANGLE_STEP);
        double[] sums = new double[CHANNELS];
        for (int bin = 0; bin < ANGLE_BINS; bin++) {
            double angle = (bin + 0.5) * ANGLE_STEP;
            Arrays.fill(sums, 0.0);
            double weightSum = 0.0;
            for (int other = Math.max(0, bin - span); other <= Math.min(ANGLE_BINS - 1, bin + span); other++) {
                double otherAngle = (other + 0.5) * ANGLE_STEP;
                if (bin >= ringBins) {
                    double distance = otherAngle - angle;
                    double weight = Math.exp(-0.5 * distance * distance / (blurSigma * blurSigma))
                            * binSolidAngle(other);
                    weightSum += weight;
                    for (int channel = 0; channel < CHANNELS; channel++) {
                        sums[channel] += weight * raw[channel][other];
                    }
                    continue;
                }
                // Near the sun the blur is integrated around each ring of the profile:
                double sineProduct = Math.sin(angle) * Math.sin(otherAngle);
                double cosineProduct = Math.cos(angle) * Math.cos(otherAngle);
                double widest = sineProduct > 0.0 ? (cosineSpan - cosineProduct) / sineProduct : -2.0;
                double widestAzimuth = widest <= -1.0 ? Math.PI : Math.acos(Math.min(1.0, widest));
                if (widest > 1.0) {
                    continue;
                }
                double element = widestAzimuth / BLUR_RING_SAMPLES * binSolidAngle(other) / Math.PI;
                for (int sample = 0; sample < BLUR_RING_SAMPLES; sample++) {
                    double azimuth = (sample + 0.5) * widestAzimuth / BLUR_RING_SAMPLES;
                    double cosine = cosineProduct + sineProduct * Math.cos(azimuth);
                    double distance = Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
                    double weight = Math.exp(-0.5 * distance * distance / (blurSigma * blurSigma)) * element;
                    weightSum += weight;
                    for (int channel = 0; channel < CHANNELS; channel++) {
                        sums[channel] += weight * raw[channel][other];
                    }
                }
            }
            for (int channel = 0; channel < CHANNELS; channel++) {
                blurred[channel][bin] = weightSum > 0.0 ? sums[channel] / weightSum : raw[channel][bin];
                values[bin * CHANNELS + channel] = (float) blurred[channel][bin];
            }
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            for (int bin = 0; bin < ANGLE_BINS; bin++) {
                ringEnergies[channel][bin / BINS_PER_IRRADIANCE_RING] += blurred[channel][bin] * binSolidAngle(bin);
            }
        }
    }

    static double binSolidAngle(int bin) {
        double lower = bin * ANGLE_STEP;
        double upper = (bin + 1) * ANGLE_STEP;
        return 2.0 * Math.PI * (Math.cos(lower) - Math.cos(upper));
    }

    public double blurSigma() {
        return blurSigma;
    }

    /**
     * The profile as interleaved RGB by bin, as it is uploaded.
     */
    public float[] values() {
        return values;
    }

    public double phase(int channel, double sunCosine) {
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, sunCosine)));
        double position = angle / ANGLE_STEP - 0.5;
        int lower = Math.max(0, Math.min(ANGLE_BINS - 2, (int) Math.floor(position)));
        double fraction = Math.max(0.0, Math.min(1.0, position - lower));
        return blurred[channel][lower] * (1.0 - fraction) + blurred[channel][lower + 1] * fraction;
    }

    public double total(int channel) {
        double sum = 0.0;
        for (int bin = 0; bin < ANGLE_BINS; bin++) {
            sum += blurred[channel][bin] * binSolidAngle(bin);
        }
        return sum;
    }

    /**
     * Irradiance on level ground per unit of the ice table, with each ring of 180 / IRRADIANCE_RINGS degrees around
     * the sun sampled at IRRADIANCE_RING_POINTS points. The sun direction is in the frame of the sky view tables.
     */
    double irradiance(int channel, double[] sun, float[] iceTable) {
        double result = 0.0;
        double ringStep = Math.PI / IRRADIANCE_RINGS;
        for (int ring = 0; ring < IRRADIANCE_RINGS; ring++) {
            double angle = (ring + 0.5) * ringStep;
            double ringSum = 0.0;
            for (int point = 0; point < IRRADIANCE_RING_POINTS; point++) {
                double around = 2.0 * Math.PI * (point + 0.5) / IRRADIANCE_RING_POINTS;
                double perpendicular = Math.sin(angle) * Math.cos(around);
                double x = Math.cos(angle) * sun[0] - perpendicular * sun[1];
                double y = Math.cos(angle) * sun[1] + perpendicular * sun[0];
                double z = Math.sin(angle) * Math.sin(around);
                if (y > 0.0) {
                    ringSum += y * SkyView.sample(iceTable, channel, Math.atan2(z, x), Math.asin(y));
                }
            }
            result += ringEnergies[channel][ring] * ringSum / IRRADIANCE_RING_POINTS;
        }
        return result;
    }
}
