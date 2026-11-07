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

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.function.DoubleUnaryOperator;

/**
 * Checks the sky optics against published values and against the offline preview in the workspace.
 * Reference values come from the preview's datasets mode, which also wrote the ice halo datasets.
 */
@GameTestHolder("frostedheart_calculated_sky")
@PrefixGameTestTemplate(false)
public final class CalculatedSkyGameTests {
    private static final double[][] DIRECTIONS_DEGREES = {
            {0.0, 90.0}, {0.0, 10.0}, {180.0, 10.0}, {90.0, 45.0}, {0.0, 30.0}, {0.0, -5.0}
    };
    private static final double[][] PREVIEW_RADIANCE_SUN_30 = {
            {4.166095e-02, 5.124624e-02, 6.967542e-02},
            {1.467572e-01, 1.414350e-01, 1.489306e-01},
            {9.582255e-02, 1.069268e-01, 1.295040e-01},
            {5.127253e-02, 6.186302e-02, 8.348271e-02},
            {1.406775e+02, 1.072396e+02, 7.730739e+01},
            {1.224294e-01, 1.125749e-01, 1.131418e-01}
    };
    private static final double[][] PREVIEW_RADIANCE_SUN_8 = {
            {1.400972e-02, 1.480753e-02, 1.879119e-02},
            {1.110835e+00, 5.038122e-01, 2.090749e-01},
            {2.988587e-02, 2.558581e-02, 2.621068e-02},
            {1.760332e-02, 1.818259e-02, 2.239172e-02},
            {8.795442e-02, 5.732254e-02, 4.105200e-02},
            {2.247240e-02, 1.877901e-02, 1.981469e-02}
    };
    private static final double[] PREVIEW_PROFILE_TOTAL = {7.001337e-01, 7.001327e-01, 7.001303e-01};
    private static final double[] PREVIEW_MAP_TOTAL_SUN_30 = {2.486358e-01, 2.481081e-01, 2.472797e-01};
    private static final double[] PREVIEW_MAP_TOTAL_SUN_8 = {1.982199e-01, 1.981322e-01, 1.984010e-01};
    private static final double PREVIEW_TEXEL_DEGREES = 0.5;
    private static final double[] DAYLIGHT_SUN_DEGREES = {
            90.0, 45.0, 30.0, 20.0, 12.0, 5.0, 2.0, 0.0, -2.0, -6.0, -12.0, -18.0
    };

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void mieMatchesPublishedAndRayleighLimits(GameTestHelper helper) {
        double extinction = SkyParticleOptics.mie(10.0, 1.5).extinction();
        helper.assertTrue(Math.abs(extinction - 2.8820) < 5.0e-4,
                "Extinction efficiency for m=1.5 and x=10 is " + extinction + ", published 2.8820");
        double sizeParameter = 0.01;
        double index = 1.5;
        double lorentz = (index * index - 1.0) / (index * index + 2.0);
        double rayleighLimit = 8.0 / 3.0 * Math.pow(sizeParameter, 4.0) * lorentz * lorentz;
        SkyParticleOptics.Efficiencies small = SkyParticleOptics.mie(sizeParameter, index);
        helper.assertTrue(Math.abs(small.scattering() / rayleighLimit - 1.0) < 1.0e-3,
                "Small particles scatter as in the Rayleigh limit");
        helper.assertTrue(Math.abs(small.asymmetry()) < 1.0e-3, "Small particles scatter symmetrically");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void phaseFunctionsIntegrateToOne(GameTestHelper helper) {
        assertNormalized(helper, "Rayleigh", SkyPhase::rayleigh, 1.0e-3);
        assertNormalized(helper, "Henyey-Greenstein", cosine -> SkyPhase.henyeyGreenstein(cosine, 0.8), 2.0e-3);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void iceHaloDatasetsAreNormalized(GameTestHelper helper) {
        IceCrystalData data = IceCrystalData.get();
        double worstSideFaceError = 0.0;
        for (int row = 0; row < data.sideFaceIndexRows(); row++) {
            double integral = 0.0;
            for (int column = 0; column < data.sideFaceDeviationColumns(); column++) {
                integral += data.sideFaceDensity(row, column) * data.sideFaceDeviationStep();
            }
            worstSideFaceError = Math.max(worstSideFaceError, Math.abs(integral - 1.0));
        }
        helper.assertTrue(worstSideFaceError < 1.0e-3,
                "Side face deviation densities integrate to 1, worst row off by " + worstSideFaceError);
        double shareSum = 0.0;
        for (IceCrystalData.Crystal crystal : data.crystals()) {
            shareSum += data.shareOf(crystal);
            if (crystal.orientation() == IceCrystalData.Orientation.RANDOM) {
                for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
                    double[] angles = data.scattering(crystal, channel);
                    double total = 0.0;
                    for (int bin = 0; bin < angles.length; bin++) {
                        total += angles[bin] * IceHaloProfile.binSolidAngle(bin);
                    }
                    helper.assertTrue(Math.abs(total - 1.0) < 1.0e-3,
                            crystal.name() + " scattering integrates to " + total + " in channel " + channel);
                }
                continue;
            }
            for (int row = 0; row < data.pathShareRows(crystal); row++) {
                for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
                    double sum = 0.0;
                    for (int path = 0; path < IceCrystalData.PATH_COUNT; path++) {
                        sum += data.pathShare(crystal, row, channel, path);
                    }
                    helper.assertTrue(sum > 0.99 && sum < 1.0 + 1.0e-5,
                            crystal.name() + " path shares add up to " + sum + " in row " + row + ", channel "
                                    + channel + ", where light still inside after the tracer's last interaction "
                                    + "is lost");
                }
            }
        }
        helper.assertTrue(Math.abs(shareSum - 1.0) < 1.0e-9, "Crystal shares add up to " + shareSum);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void iceHalosMatchOfflinePreview(GameTestHelper helper) {
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(PREVIEW_TEXEL_DEGREES);
        IceHaloProfile profile = new IceHaloProfile(data, blurSigma);
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            double total = profile.total(channel);
            helper.assertTrue(Math.abs(total / PREVIEW_PROFILE_TOTAL[channel] - 1.0) < 1.0e-3,
                    "Halo profile total in channel " + channel + " is " + total + ", preview "
                            + PREVIEW_PROFILE_TOTAL[channel]);
        }
        assertHaloMap(helper, new IceHaloMap(data, Math.toRadians(30.0), blurSigma), 30.0, PREVIEW_MAP_TOTAL_SUN_30);
        assertHaloMap(helper, new IceHaloMap(data, Math.toRadians(8.0), blurSigma), 8.0, PREVIEW_MAP_TOTAL_SUN_8);
        helper.succeed();
    }

    /**
     * Checks the light a halo map holds against the preview, and that its sun dogs sit just outside the minimum
     * deviation that Bravais' effective index predicts, where the blur moves the brightest bin.
     */
    private static void assertHaloMap(GameTestHelper helper, IceHaloMap map, double sunElevationDegrees,
            double[] expectedTotals) {
        int sunRow = IceHaloMap.rowOfElevation(Math.toRadians(sunElevationDegrees));
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            double total = map.total(channel);
            helper.assertTrue(Math.abs(total / expectedTotals[channel] - 1.0) < 5.0e-3,
                    "Halo map total at sun " + sunElevationDegrees + " in channel " + channel + " is " + total
                            + ", preview " + expectedTotals[channel]);
            int brightest = -1;
            for (int column = 0; column < IceHaloMap.AZIMUTH_BINS; column++) {
                double degrees = Math.toDegrees((column + 0.5) * IceHaloMap.BIN_STEP);
                if (degrees > 15.0 && degrees < 40.0 && (brightest < 0
                        || map.value(channel, column, sunRow) > map.value(channel, brightest, sunRow))) {
                    brightest = column;
                }
            }
            double index = IceHaloFormulas.refractiveIndex(channel);
            double along = Math.sin(Math.toRadians(sunElevationDegrees));
            double effectiveIndex = Math.sqrt(index * index - along * along) / Math.sqrt(1.0 - along * along);
            double bravaisDegrees = Math.toDegrees(2.0 * Math.asin(effectiveIndex * 0.5)) - 60.0;
            double sunDogDegrees = Math.toDegrees((brightest + 0.5) * IceHaloMap.BIN_STEP);
            helper.assertTrue(sunDogDegrees > bravaisDegrees - 0.25 && sunDogDegrees < bravaisDegrees + 1.0,
                    "Sun dog at sun " + sunElevationDegrees + " in channel " + channel + " is at " + sunDogDegrees
                            + " degrees from the sun, Bravais minimum deviation " + bravaisDegrees);
        }
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void defaultSkyMatchesOfflinePreview(GameTestHelper helper) {
        SkySettings settings = new SkySettings(SkySettings.DEFAULT.nanobotOpticalDepth(),
                SkySettings.DEFAULT.nanobotMedianNanometers(), SkySettings.DEFAULT.nanobotSpread(),
                SkySettings.DEFAULT.iceOpticalDepth(), PREVIEW_TEXEL_DEGREES);
        SkyTables tables = new SkyTables(settings);
        helper.assertTrue(Math.abs(tables.nanobotMegatons() - 0.25 * 120.5512) < 0.01,
                "Default nanobot mass is " + tables.nanobotMegatons() + " Mt");
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(settings.actualTexelDegrees());
        IceHaloProfile profile = new IceHaloProfile(data, blurSigma);
        for (double sunElevationDegrees : new double[] {30.0, 8.0}) {
            double sunElevation = Math.toRadians(sunElevationDegrees);
            IceHaloMap map = new IceHaloMap(data, sunElevation, blurSigma);
            SkyView view = tables.computeView(sunElevation, profile, map);
            double[][] expected = sunElevationDegrees == 30.0 ? PREVIEW_RADIANCE_SUN_30 : PREVIEW_RADIANCE_SUN_8;
            assertMatchesPreview(helper, view, profile, map, expected, sunElevationDegrees);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fogColorFollowsTheLowSun(GameTestHelper helper) {
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(SkySettings.DEFAULT.actualTexelDegrees());
        double sunElevation = Math.toRadians(4.5);
        SkyView view = new SkyTables(SkySettings.DEFAULT).computeView(sunElevation,
                new IceHaloProfile(data, blurSigma), new IceHaloMap(data, sunElevation, blurSigma));
        float[] towardSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        float[] awayFromSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        SkyView.horizonDisplayColor(view, view, 0.0, 0.0, 1.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F, towardSun);
        SkyView.horizonDisplayColor(view, view, 0.0, Math.PI, 1.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F,
                awayFromSun);
        helper.assertTrue(towardSun[0] / towardSun[2] > 1.1F * awayFromSun[0] / awayFromSun[2],
                "Fog toward a sun at 4.5 degrees is warmer than away from it, red over blue "
                        + towardSun[0] / towardSun[2] + " against " + awayFromSun[0] / awayFromSun[2]);
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            helper.assertTrue(towardSun[channel] > 0.0F && towardSun[channel] <= 1.0F,
                    "Fog channel " + channel + " toward the sun is " + towardSun[channel]);
        }

        float[] upTowardSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        float[] upAwayFromSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        SkyView.horizonDisplayColor(view, view, 0.0, 0.0, 0.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F, upTowardSun);
        SkyView.horizonDisplayColor(view, view, 0.0, Math.PI, 0.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F,
                upAwayFromSun);
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            helper.assertTrue(upTowardSun[channel] == upAwayFromSun[channel],
                    "Looking straight up uses the mean horizon in every direction, channel " + channel);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void daylightDimsWithTheSunIntoVanillaNightAndRain(GameTestHelper helper) {
        IceCrystalData data = IceCrystalData.get();
        double blurSigma = IceHaloMap.blurSigma(SkySettings.DEFAULT.actualTexelDegrees());
        SkyTables tables = new SkyTables(SkySettings.DEFAULT);
        IceHaloProfile profile = new IceHaloProfile(data, blurSigma);
        float[] daylight = new float[SkyAtmosphere.CHANNEL_COUNT];
        double previousLuminance = Double.POSITIVE_INFINITY;
        for (double sunElevationDegrees : DAYLIGHT_SUN_DEGREES) {
            double sunElevation = Math.toRadians(sunElevationDegrees);
            IceHaloMap map = new IceHaloMap(data, Math.toRadians(Math.max(-6.0, sunElevationDegrees)), blurSigma);
            SkyView view = tables.computeView(sunElevation, profile, map);
            SkyView.daylightColor(view, view, 0.0, SkySettings.DEFAULT_EXPOSURE, 0.0F,
                    vanillaSkyDarken(sunElevationDegrees, 0.0F), daylight);
            double luminance = 0.2126 * daylight[0] + 0.7152 * daylight[1] + 0.0722 * daylight[2];
            helper.assertTrue(luminance <= previousLuminance + 1.0e-6,
                    "Daylight at sun " + sunElevationDegrees + " has luminance " + luminance
                            + ", brighter than " + previousLuminance + " at the higher sun before");
            previousLuminance = luminance;
            for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
                if (sunElevationDegrees == 90.0) {
                    helper.assertTrue(daylight[channel] > 0.95F && daylight[channel] <= 1.0F,
                            "Daylight at noon is close to white, channel " + channel + " is " + daylight[channel]);
                }
                if (sunElevationDegrees == -18.0) {
                    float night = SkyView.vanillaSkyLight(channel, 0.2F);
                    helper.assertTrue(Math.abs(daylight[channel] - night) < 1.0e-6F,
                            "Daylight at sun -18 is vanilla's night, channel " + channel + " is " + daylight[channel]
                                    + ", vanilla " + night);
                }
            }
            if (sunElevationDegrees == 30.0) {
                float rainSkyDarken = vanillaSkyDarken(sunElevationDegrees, 1.0F);
                SkyView.daylightColor(view, view, 0.0, SkySettings.DEFAULT_EXPOSURE, 1.0F, rainSkyDarken, daylight);
                for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
                    float vanilla = SkyView.vanillaSkyLight(channel, rainSkyDarken);
                    helper.assertTrue(Math.abs(daylight[channel] - vanilla) < 1.0e-6F,
                            "Daylight in full rain is vanilla's sky light, channel " + channel + " is "
                                    + daylight[channel] + ", vanilla " + vanilla);
                }
            }
        }
        helper.succeed();
    }

    /**
     * The sky darkness that ClientLevel.getSkyDarken gives for a sun elevation and rain level without thunder.
     */
    private static float vanillaSkyDarken(double sunElevationDegrees, float rainLevel) {
        float sunHeight = (float) Math.sin(Math.toRadians(sunElevationDegrees));
        float skyDarken = 1.0F - Math.max(0.0F, Math.min(1.0F, 1.0F - (sunHeight * 2.0F + 0.2F)));
        skyDarken *= 1.0F - rainLevel * 5.0F / 16.0F;
        return skyDarken * 0.8F + 0.2F;
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sunReddensTowardTheHorizonAndSetsBelowIt(GameTestHelper helper) {
        float[] sunRadiance = new SkyTables(SkySettings.DEFAULT).sunRadiance();
        double redOverBlueAt2 = Double.NaN;
        double redOverBlueAt30 = Double.NaN;
        for (int row = 0; row < SkyTables.VIEW_HEIGHT; row++) {
            double elevationDegrees = Math.toDegrees(SkyTables.rowElevation(row));
            float red = sunRadiance[row * SkyAtmosphere.CHANNEL_COUNT];
            float green = sunRadiance[row * SkyAtmosphere.CHANNEL_COUNT + 1];
            float blue = sunRadiance[row * SkyAtmosphere.CHANNEL_COUNT + 2];
            if (elevationDegrees < -Math.toDegrees(SkyAtmosphere.HORIZON_DIP_RADIANS)) {
                helper.assertTrue(red == 0.0F && green == 0.0F && blue == 0.0F,
                        "The sun is hidden below the horizon at elevation " + elevationDegrees);
                continue;
            }
            helper.assertTrue(red > 0.0F && red >= green && green >= blue && blue >= 0.0F,
                    "The sun at elevation " + elevationDegrees + " is " + red + ", " + green + ", " + blue
                            + ", where red should be at least green and green at least blue");
            if (Double.isNaN(redOverBlueAt2) && elevationDegrees >= 2.0) {
                redOverBlueAt2 = red / blue;
            }
            if (Double.isNaN(redOverBlueAt30) && elevationDegrees >= 30.0) {
                redOverBlueAt30 = red / blue;
            }
        }
        helper.assertTrue(redOverBlueAt2 > 10.0 * redOverBlueAt30,
                "Red over blue of the sun is " + redOverBlueAt2 + " at 2 degrees and " + redOverBlueAt30
                        + " at 30 degrees");
        helper.succeed();
    }

    private static void assertMatchesPreview(GameTestHelper helper, SkyView view, IceHaloProfile profile,
            IceHaloMap map, double[][] expected, double sunElevationDegrees) {
        for (int index = 0; index < DIRECTIONS_DEGREES.length; index++) {
            double azimuth = Math.toRadians(DIRECTIONS_DEGREES[index][0]);
            double elevation = Math.toRadians(DIRECTIONS_DEGREES[index][1]);
            for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
                double actual = view.radiance(channel, azimuth, elevation, profile, map);
                double relativeError = Math.abs(actual / expected[index][channel] - 1.0);
                helper.assertTrue(relativeError < 0.01,
                        "Sun " + sunElevationDegrees + ", azimuth " + DIRECTIONS_DEGREES[index][0] + ", elevation "
                                + DIRECTIONS_DEGREES[index][1] + ", channel " + channel + " is " + actual
                                + ", preview " + expected[index][channel]);
            }
        }
    }

    private static void assertNormalized(GameTestHelper helper, String name, DoubleUnaryOperator phase,
            double tolerance) {
        double integral = integrate(phase);
        helper.assertTrue(Math.abs(integral - 1.0) < tolerance, name + " phase integrates to " + integral);
    }

    private static double integrate(DoubleUnaryOperator phase) {
        int steps = 2000000;
        double sum = 0.0;
        for (int step = 0; step < steps; step++) {
            sum += phase.applyAsDouble(-1.0 + 2.0 * (step + 0.5) / steps);
        }
        return sum * 2.0 / steps * 2.0 * Math.PI;
    }
}
