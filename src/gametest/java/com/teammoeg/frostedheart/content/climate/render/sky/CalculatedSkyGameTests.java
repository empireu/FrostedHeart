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
 */
@GameTestHolder("frostedheart_calculated_sky")
@PrefixGameTestTemplate(false)
public final class CalculatedSkyGameTests {
    private static final double[][] DIRECTIONS_DEGREES = {
            {0.0, 90.0}, {0.0, 10.0}, {180.0, 10.0}, {90.0, 45.0}, {0.0, 30.0}, {0.0, -5.0}
    };
    private static final double[][] PREVIEW_RADIANCE_SUN_30 = {
            {4.320987e-02, 5.210253e-02, 7.061756e-02},
            {1.660233e-01, 1.550792e-01, 1.548889e-01},
            {9.646680e-02, 1.072552e-01, 1.295390e-01},
            {5.265287e-02, 6.284106e-02, 8.426542e-02},
            {8.584539e+01, 6.546757e+01, 4.714827e+01},
            {1.219478e-01, 1.121708e-01, 1.128015e-01}
    };
    private static final double[][] PREVIEW_RADIANCE_SUN_8 = {
            {1.419630e-02, 1.494000e-02, 1.888865e-02},
            {1.038473e+00, 4.714456e-01, 1.955413e-01},
            {2.759280e-02, 2.457196e-02, 2.587705e-02},
            {1.789454e-02, 1.834341e-02, 2.250549e-02},
            {8.858680e-02, 6.062171e-02, 3.952311e-02},
            {2.258090e-02, 1.886819e-02, 1.990654e-02}
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
        assertNormalised(helper, "Rayleigh", SkyPhase::rayleigh, 1.0e-3);
        assertNormalised(helper, "Henyey-Greenstein", cosine -> SkyPhase.henyeyGreenstein(cosine, 0.8), 2.0e-3);
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            int haloChannel = channel;
            assertNormalised(helper, "Halo channel " + channel, cosine -> SkyPhase.haloRing(cosine, haloChannel), 1.0e-2);
            DoubleUnaryOperator ice = cosine -> SkyPhase.broadIce(cosine) + SkyPhase.narrowIce(cosine, haloChannel);
            assertNormalised(helper, "Ice channel " + channel, ice, 1.0e-2);
            double meanCosine = integrate(cosine -> cosine * ice.applyAsDouble(cosine));
            helper.assertTrue(Math.abs(meanCosine - 0.78) < 0.01,
                    "Ice channel " + channel + " has asymmetry " + meanCosine + ", real ice crystals about 0.78");
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void defaultSkyMatchesOfflinePreview(GameTestHelper helper) {
        SkyTables tables = new SkyTables(SkySettings.DEFAULT);
        helper.assertTrue(Math.abs(tables.nanobotMegatons() - 0.25 * 120.5512) < 0.01,
                "Default nanobot mass is " + tables.nanobotMegatons() + " Mt");
        assertMatchesPreview(helper, tables.computeView(Math.toRadians(30.0)), PREVIEW_RADIANCE_SUN_30, 30.0);
        assertMatchesPreview(helper, tables.computeView(Math.toRadians(8.0)), PREVIEW_RADIANCE_SUN_8, 8.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fogColorFollowsTheLowSun(GameTestHelper helper) {
        SkyView view = new SkyTables(SkySettings.DEFAULT).computeView(Math.toRadians(4.5));
        float[] towardSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        float[] awayFromSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        view.horizonDisplayColor(0.0, 1.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F, towardSun);
        view.horizonDisplayColor(Math.PI, 1.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F, awayFromSun);
        helper.assertTrue(towardSun[0] / towardSun[2] > 1.1F * awayFromSun[0] / awayFromSun[2],
                "Fog toward a sun at 4.5 degrees is warmer than away from it, red over blue "
                        + towardSun[0] / towardSun[2] + " against " + awayFromSun[0] / awayFromSun[2]);
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            helper.assertTrue(towardSun[channel] > 0.0F && towardSun[channel] <= 1.0F,
                    "Fog channel " + channel + " toward the sun is " + towardSun[channel]);
        }

        float[] upTowardSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        float[] upAwayFromSun = new float[SkyAtmosphere.CHANNEL_COUNT];
        view.horizonDisplayColor(0.0, 0.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F, upTowardSun);
        view.horizonDisplayColor(Math.PI, 0.0, SkySettings.DEFAULT_EXPOSURE, 0.0F, 0.0F, upAwayFromSun);
        for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
            helper.assertTrue(upTowardSun[channel] == upAwayFromSun[channel],
                    "Looking straight up uses the mean horizon in every direction, channel " + channel);
        }
        helper.succeed();
    }

    private static void assertMatchesPreview(GameTestHelper helper, SkyView view, double[][] expected,
            double sunElevationDegrees) {
        for (int index = 0; index < DIRECTIONS_DEGREES.length; index++) {
            double azimuth = Math.toRadians(DIRECTIONS_DEGREES[index][0]);
            double elevation = Math.toRadians(DIRECTIONS_DEGREES[index][1]);
            for (int channel = 0; channel < SkyAtmosphere.CHANNEL_COUNT; channel++) {
                double actual = view.radiance(channel, azimuth, elevation);
                double relativeError = Math.abs(actual / expected[index][channel] - 1.0);
                helper.assertTrue(relativeError < 0.01,
                        "Sun " + sunElevationDegrees + ", azimuth " + DIRECTIONS_DEGREES[index][0] + ", elevation "
                                + DIRECTIONS_DEGREES[index][1] + ", channel " + channel + " is " + actual
                                + ", preview " + expected[index][channel]);
            }
        }
    }

    private static void assertNormalised(GameTestHelper helper, String name, DoubleUnaryOperator phase,
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
