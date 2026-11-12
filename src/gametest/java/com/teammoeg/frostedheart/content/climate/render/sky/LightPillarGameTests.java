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

import com.mojang.logging.LogUtils;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks the reflection off plate crystals and the light pillar they show against an integral along the view ray.
 */
@GameTestHolder("frostedheart_calculated_sky")
@PrefixGameTestTemplate(false)
public final class LightPillarGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double TABLE_INDEX = 1.31;
    private static final double[] TABLE_GRAZING_DEGREES = {1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 45.0, 90.0};
    private static final double[] TABLE_REFLECTANCES = {0.944, 0.889, 0.728, 0.499, 0.330, 0.217, 0.101, 0.048,
            0.035};
    private static final double TABLE_TOLERANCE = 0.0006;
    private static final double[] PILLAR_ELEVATIONS_DEGREES = {3.0, 5.0, 8.0, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0};
    private static final double[] PILLAR_LATERAL_SPREADS = {0.0, 1.0, 2.0};
    /**
     * Horizontal distances of the light, each with the heights of the light relative to the eye.
     */
    private static final double[][] PILLAR_LIGHTS = {
            {300.0, -20.0, 0.0, 15.0},
            {32.0, -3.0, 0.0, 3.0},
            {10.0, -1.0, 0.0, 1.0}
    };
    /**
     * Covers the small tilt approximation of the across tilt, which reaches 4% two spreads off the center of the
     * pillar of wobbling plates at 60 degrees. The plates stay within 0.2%.
     */
    private static final double PILLAR_TOLERANCE = 0.05;
    private static final int RAY_STEPS = 40000;
    private static final double RAY_WINDOWS = 60.0;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void iceMirrorReflectsLikeASlab(GameTestHelper helper) {
        for (int row = 0; row < TABLE_GRAZING_DEGREES.length; row++) {
            double reflectance = IceMirror.slabReflectance(Math.sin(Math.toRadians(TABLE_GRAZING_DEGREES[row])),
                    TABLE_INDEX);
            helper.assertTrue(Math.abs(reflectance - TABLE_REFLECTANCES[row]) <= TABLE_TOLERANCE,
                    "Slab reflectance at " + TABLE_GRAZING_DEGREES[row] + " degrees is " + reflectance + " instead of "
                            + TABLE_REFLECTANCES[row]);
        }
        double face = Math.pow((TABLE_INDEX - 1.0) / (TABLE_INDEX + 1.0), 2.0);
        double normal = IceMirror.slabReflectance(1.0, TABLE_INDEX);
        helper.assertTrue(Math.abs(normal - 2.0 * face / (1.0 + face)) <= 1.0e-12,
                "Slab reflectance at normal incidence is " + normal + " instead of " + 2.0 * face / (1.0 + face));
        helper.assertTrue(Math.abs(IceMirror.slabReflectance(0.0, TABLE_INDEX) - 1.0) <= 1.0e-12,
                "Slab reflectance at grazing incidence is " + IceMirror.slabReflectance(0.0, TABLE_INDEX)
                        + " instead of 1");
        double previous = 1.0;
        for (double degrees = 0.25; degrees <= 90.0; degrees += 0.25) {
            double reflectance = IceMirror.slabReflectance(Math.sin(Math.toRadians(degrees)), TABLE_INDEX);
            helper.assertTrue(reflectance <= previous,
                    "Slab reflectance rises to " + reflectance + " at " + degrees + " degrees");
            previous = reflectance;
        }
        for (IceCrystalData.Crystal crystal : plates()) {
            double tiltSigma = Math.toRadians(crystal.tiltDegrees());
            int steps = 20000;
            double step = 0.5 * Math.PI / steps;
            double total = 0.0;
            for (int index = 0; index < steps; index++) {
                double tilt = (index + 0.5) * step;
                total += IceMirror.tiltDensity(tilt, tiltSigma) * 2.0 * Math.PI * Math.sin(tilt) * step;
            }
            helper.assertTrue(Math.abs(total - 1.0) <= 0.005,
                    "Tilt density of " + crystal.name() + " integrates to " + total + " over the half sphere");
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightPillarGeometryMirrorsTheLight(GameTestHelper helper) {
        for (double[] light : PILLAR_LIGHTS) {
            double horizontalDistance = light[0];
            for (int height = 1; height < light.length; height++) {
                double lightHeight = light[height];
                for (double elevationDegrees : PILLAR_ELEVATIONS_DEGREES) {
                    double elevation = Math.toRadians(elevationDegrees);
                    double crystalHeight = LightPillarOptics.mirrorHeight(elevation, horizontalDistance, lightHeight);
                    if (crystalHeight <= Math.max(0.0, lightHeight)) {
                        continue;
                    }
                    String where = "light " + horizontalDistance + " away and " + lightHeight + " high, elevation "
                            + elevationDegrees + " degrees";
                    double back = LightPillarOptics.mirrorElevation(crystalHeight, horizontalDistance, lightHeight);
                    helper.assertTrue(Math.abs(back - elevation) <= 1.0e-12,
                            "Elevation from the mirror height is " + Math.toDegrees(back) + " degrees for " + where);
                    double crystalDistance = LightPillarOptics.crystalDistance(crystalHeight, horizontalDistance,
                            lightHeight);
                    double viewElevation = Math.atan2(crystalHeight, crystalDistance);
                    double lightDepression = Math.atan2(crystalHeight - lightHeight,
                            horizontalDistance - crystalDistance);
                    helper.assertTrue(Math.abs(viewElevation - elevation) <= 1.0e-12,
                            "Crystals are at elevation " + Math.toDegrees(viewElevation) + " degrees for " + where);
                    helper.assertTrue(Math.abs(lightDepression - elevation) <= 1.0e-12,
                            "Crystals see the light " + Math.toDegrees(lightDepression) + " degrees down for " + where);
                }
            }
        }
        helper.assertTrue(LightPillarOptics.radiance(Math.toRadians(2.0), 0.0, 100.0, 10.0, 0.01, TABLE_INDEX) == 0.0,
                "A pillar shows below the light");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 400)
    public static void lightPillarMatchesTheRayIntegral(GameTestHelper helper) {
        double refractiveIndex = IceHaloFormulas.refractiveIndex(1);
        double worst = -1.0;
        String worstWhere = "nowhere";
        for (IceCrystalData.Crystal crystal : plates()) {
            double tiltSigma = Math.toRadians(crystal.tiltDegrees());
            double crystalWorst = -1.0;
            String crystalWorstWhere = "nowhere";
            for (double[] light : PILLAR_LIGHTS) {
                double horizontalDistance = light[0];
                for (int height = 1; height < light.length; height++) {
                    double lightHeight = light[height];
                    for (double elevationDegrees : PILLAR_ELEVATIONS_DEGREES) {
                        double elevation = Math.toRadians(elevationDegrees);
                        double crystalHeight = LightPillarOptics.mirrorHeight(elevation, horizontalDistance,
                                lightHeight);
                        if (crystalHeight <= Math.max(0.0, lightHeight) + 0.05) {
                            continue;
                        }
                        double spread = LightPillarOptics.lateralSpread(elevation, horizontalDistance, lightHeight,
                                tiltSigma);
                        for (double spreads : PILLAR_LATERAL_SPREADS) {
                            double lateralAngle = spreads * spread;
                            double expected = rayIntegral(elevation, lateralAngle, horizontalDistance, lightHeight,
                                    tiltSigma, refractiveIndex);
                            double actual = LightPillarOptics.radiance(elevation, lateralAngle, horizontalDistance,
                                    lightHeight, tiltSigma, refractiveIndex);
                            double difference = Math.abs(actual / expected - 1.0);
                            if (!(difference <= crystalWorst)) {
                                crystalWorst = difference;
                                crystalWorstWhere = String.format(Locale.ROOT,
                                        "light %.0f away and %.0f high, elevation %.0f degrees, %.0f spreads off "
                                                + "the center, %.4g against %.4g",
                                        horizontalDistance, lightHeight, elevationDegrees, spreads, actual, expected);
                            }
                        }
                    }
                }
            }
            LOGGER.info("Light pillar of " + crystal.name() + " differs from the ray integral by at most "
                    + crystalWorst + " at " + crystalWorstWhere);
            if (!(crystalWorst <= worst)) {
                worst = crystalWorst;
                worstWhere = crystal.name() + ", " + crystalWorstWhere;
            }
        }
        helper.assertTrue(worst <= PILLAR_TOLERANCE,
                "Light pillar differs from the ray integral by " + worst + " at " + worstWhere + ", more than "
                        + PILLAR_TOLERANCE);
        helper.succeed();
    }

    private static List<IceCrystalData.Crystal> plates() {
        List<IceCrystalData.Crystal> plates = new ArrayList<>();
        for (IceCrystalData.Crystal crystal : IceCrystalData.get().crystals()) {
            if (crystal.orientation() == IceCrystalData.Orientation.PLATE) {
                plates.add(crystal);
            }
        }
        return plates;
    }

    /**
     * The radiance of the pillar summed along the view ray from the eye, with the reflection of every crystal worked
     * out in three dimensions. The light is on the x axis and z points up.
     */
    private static double rayIntegral(double elevation, double lateralAngle, double horizontalDistance,
            double lightHeight, double tiltSigma, double refractiveIndex) {
        double azimuth = Math.asin(Math.sin(lateralAngle) / Math.cos(elevation));
        double directionX = Math.cos(elevation) * Math.cos(azimuth);
        double directionY = Math.cos(elevation) * Math.sin(azimuth);
        double directionZ = Math.sin(elevation);
        double crystalHeight = LightPillarOptics.mirrorHeight(elevation, horizontalDistance, lightHeight);
        double mirrorDistance = crystalHeight / Math.sin(elevation);
        double lightLeg = Math.hypot(horizontalDistance - mirrorDistance * Math.cos(elevation),
                crystalHeight - lightHeight);
        double window = tiltSigma * lightLeg / (Math.sin(elevation) * Math.cos(elevation));
        double start = Math.max(1.0e-6, mirrorDistance - RAY_WINDOWS * window);
        double end = mirrorDistance + RAY_WINDOWS * window;
        double step = (end - start) / RAY_STEPS;
        double total = 0.0;
        for (int index = 0; index < RAY_STEPS; index++) {
            double distance = start + (index + 0.5) * step;
            double toLightX = horizontalDistance - distance * directionX;
            double toLightY = -distance * directionY;
            double toLightZ = lightHeight - distance * directionZ;
            double lightDistance = Math.sqrt(toLightX * toLightX + toLightY * toLightY + toLightZ * toLightZ);
            double halfX = toLightX / lightDistance - directionX;
            double halfY = toLightY / lightDistance - directionY;
            double halfZ = toLightZ / lightDistance - directionZ;
            double halfLength = Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ);
            if (halfLength < 1.0e-12) {
                continue;
            }
            double tilt = Math.acos(Math.min(1.0, Math.abs(halfZ) / halfLength));
            double cosineOfIncidence = Math.abs(toLightX * halfX + toLightY * halfY + toLightZ * halfZ)
                    / (lightDistance * halfLength);
            total += 0.25 * IceMirror.slabReflectance(cosineOfIncidence, refractiveIndex)
                    * IceMirror.tiltDensity(tilt, tiltSigma) / (lightDistance * lightDistance) * step;
        }
        return total;
    }
}
