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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks the reflection off plate crystals and the light pillar they show against an integral along the view ray, the
 * pillar in the diamond dust that the shader draws with its ribbons and brightness, and how the lights that show
 * pillars are merged, colored, chosen, and faded.
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
    /**
     * Horizontal distance and height of the light, then the height of the dust bottom, all relative to the eye.
     */
    private static final double[][] RIBBON_LIGHTS = {
            {4.0, 20.0, -1.62}, {8.0, -1.12, -1.62}, {32.0, -1.12, -1.62}, {32.0, 0.0, 30.0}, {128.0, -40.0, -1.62},
            {128.0, 20.0, -40.0}, {256.0, -1.12, -1.62}
    };
    /**
     * A torch, and a cluster of 16 lights of full strength such as a lava lake's.
     */
    private static final double[] RIBBON_STRENGTHS = {196.0 / 225.0, 16.0};
    private static final double RIBBON_EXTINCTION = 0.001;
    /**
     * The angle of a pixel at the center of a screen 1080 pixels tall with a vertical field of view of 70 degrees, as
     * LightPillarOptics.BRIGHTNESS is calibrated with.
     */
    private static final double REFERENCE_PIXEL_ANGLE = pixelAngle(1080);
    /**
     * Screen heights in pixels, with a vertical field of view of 70 degrees, that set the margin and blur of ribbons.
     */
    private static final int[] RIBBON_SCREEN_HEIGHTS = {720, 1080, 2160};
    /**
     * As SEGMENTS and WIDEST_RIBBON_ANGLE in light_pillars.glsl.
     */
    private static final int RIBBON_SEGMENTS = 16;
    private static final double WIDEST_RIBBON_ANGLE = 1.4;
    private static final int EDGE_STEPS = 40;
    /**
     * The scene distance of directions where no scene stands.
     */
    private static final double NO_SCENE = Double.POSITIVE_INFINITY;
    /**
     * Scene distances as shares of the distance to the crystals with level faces.
     */
    private static final double[] SCENE_DISTANCE_SHARES = {0.25, 0.75, 0.95, 1.0, 1.05, 1.5, 4.0};
    private static final double[] SCENE_LATERAL_SPREADS = {0.0, 1.0};

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
                                    tiltSigma, refractiveIndex, NO_SCENE);
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

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void lightPillarCountsOnlyCrystalsInFrontOfTheScene(GameTestHelper helper) {
        double refractiveIndex = IceHaloFormulas.refractiveIndex(1);
        double worst = -1.0;
        String worstWhere = "nowhere";
        for (IceCrystalData.Crystal crystal : plates()) {
            double tiltSigma = Math.toRadians(crystal.tiltDegrees());
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
                        double mirrorDistance = crystalHeight / Math.sin(elevation);
                        double spread = LightPillarOptics.lateralSpread(elevation, horizontalDistance, lightHeight,
                                tiltSigma);
                        for (double spreads : SCENE_LATERAL_SPREADS) {
                            double lateralAngle = spreads * spread;
                            double whole = rayIntegral(elevation, lateralAngle, horizontalDistance, lightHeight,
                                    tiltSigma, refractiveIndex, NO_SCENE);
                            for (double share : SCENE_DISTANCE_SHARES) {
                                double sceneDistance = share * mirrorDistance;
                                double expected = rayIntegral(elevation, lateralAngle, horizontalDistance,
                                        lightHeight, tiltSigma, refractiveIndex, sceneDistance);
                                double actual = LightPillarOptics.dustRadiance(elevation, lateralAngle,
                                        horizontalDistance, lightHeight, tiltSigma, refractiveIndex, 0.0, 1.0e9, 0.0,
                                        sceneDistance);
                                double difference = Math.abs(actual - expected) / whole;
                                if (!(difference <= worst)) {
                                    worst = difference;
                                    worstWhere = String.format(Locale.ROOT,
                                            "%s, light %.0f away and %.0f high, elevation %.0f degrees, %.0f spreads "
                                                    + "off the center, scene at %.2f of the crystals, %.4g against "
                                                    + "%.4g of %.4g",
                                            crystal.name(), horizontalDistance, lightHeight, elevationDegrees, spreads,
                                            share, actual, expected, whole);
                                }
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info("Light pillar in front of a scene differs from the ray integral by at most " + worst
                + " of the whole pillar, at " + worstWhere);
        helper.assertTrue(worst <= PILLAR_TOLERANCE, "Light pillar in front of a scene differs from the ray integral by "
                + worst + " of the whole pillar, at " + worstWhere);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightPillarLightPassesBelowTheViewRayBeforeTheLight(GameTestHelper helper) {
        int checked = 0;
        for (IceCrystalData.Crystal crystal : plates()) {
            double tiltSigma = Math.toRadians(crystal.tiltDegrees());
            for (double[] light : PILLAR_LIGHTS) {
                double horizontalDistance = light[0];
                for (int height = 1; height < light.length; height++) {
                    double lightHeight = light[height];
                    double lightElevation = Math.atan2(lightHeight, horizontalDistance);
                    double lightDistance = Math.hypot(horizontalDistance, lightHeight);
                    for (double elevationDegrees = -60.0; elevationDegrees <= 85.0; elevationDegrees += 0.5) {
                        double elevation = Math.toRadians(elevationDegrees);
                        double lowestTilt = Math.max(-4.0 * tiltSigma, elevation - 0.5 * Math.PI);
                        double highestTilt = Math.min(4.0 * tiltSigma, 0.5 * (elevation + lightElevation));
                        if (!(Math.sin(elevation - lightElevation) > 0.0) || !(highestTilt > lowestTilt)) {
                            continue;
                        }
                        for (int step = 1; step < 20; step++) {
                            double alongTilt = lowestTilt + (highestTilt - lowestTilt) * step / 20.0;
                            double lightDepression = elevation - 2.0 * alongTilt;
                            double eyeLeg = lightDistance * Math.sin(lightDepression + lightElevation)
                                    / Math.sin(elevation + lightDepression);
                            double crystalX = eyeLeg * Math.cos(elevation);
                            double crystalY = eyeLeg * Math.sin(elevation);
                            if (!(crystalX < horizontalDistance)) {
                                continue;
                            }
                            double towardLight = Math.atan2(lightHeight - crystalY, horizontalDistance - crystalX);
                            boolean mirrored = Math.abs(towardLight + lightDepression) <= 1.0e-9;
                            boolean below = true;
                            for (double fraction : new double[]{0.25, 0.5, 0.75}) {
                                double between = crystalX + fraction * (horizontalDistance - crystalX);
                                double rayHeight = between * Math.tan(elevation);
                                double legHeight = crystalY + fraction * (lightHeight - crystalY);
                                below &= legHeight < rayHeight;
                            }
                            if (!mirrored || !below) {
                                helper.fail(String.format(Locale.ROOT, "The light %s for %s, light %.0f away and %.0f "
                                                + "high, elevation %.1f degrees, tilt %.2f degrees",
                                        mirrored ? "passes above the view ray on its way to the crystals"
                                                : "is not where the crystals see it", crystal.name(), horizontalDistance,
                                        lightHeight, elevationDegrees, Math.toDegrees(alongTilt)));
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        helper.assertTrue(checked > 1000, "Only " + checked + " crystals were checked");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightPillarInDustReducesToTheUniformPillar(GameTestHelper helper) {
        double refractiveIndex = IceHaloFormulas.refractiveIndex(1);
        for (IceCrystalData.Crystal crystal : plates()) {
            double tiltSigma = Math.toRadians(crystal.tiltDegrees());
            for (double[] light : PILLAR_LIGHTS) {
                double horizontalDistance = light[0];
                for (int height = 1; height < light.length; height++) {
                    double lightHeight = light[height];
                    for (double elevationDegrees : PILLAR_ELEVATIONS_DEGREES) {
                        double elevation = Math.toRadians(elevationDegrees);
                        for (double spreads : PILLAR_LATERAL_SPREADS) {
                            double lateralAngle = spreads * tiltSigma * Math.sin(elevation);
                            double uniform = LightPillarOptics.radiance(elevation, lateralAngle, horizontalDistance,
                                    lightHeight, tiltSigma, refractiveIndex);
                            double inDust = LightPillarOptics.dustRadiance(elevation, lateralAngle,
                                    horizontalDistance, lightHeight, tiltSigma, refractiveIndex, 0.0, 1.0e9, 0.0, NO_SCENE);
                            helper.assertTrue(Math.abs(inDust - uniform) <= 1.0e-9 * uniform,
                                    "The pillar in dust of the same density everywhere is " + inDust + " instead of "
                                            + uniform + " for " + crystal.name() + ", light " + horizontalDistance
                                            + " away and " + lightHeight + " high, elevation " + elevationDegrees);
                            double dimmed = LightPillarOptics.dustRadiance(elevation, lateralAngle,
                                    horizontalDistance, lightHeight, tiltSigma, refractiveIndex, 0.0, -1.62, 0.001, NO_SCENE);
                            helper.assertTrue(dimmed <= uniform, "Thinning dust and extinction brighten the pillar");
                        }
                    }
                }
            }
        }
        double scaleHeight = LightPillarSelection.DUST_SCALE_HEIGHT;
        double upward = LightPillarOptics.dustOpticalDepth(-2.0, -2.0 + scaleHeight, scaleHeight, 0.001, -2.0);
        helper.assertTrue(Math.abs(upward - 0.001 * scaleHeight * (1.0 - Math.exp(-1.0))) <= 1.0e-12,
                "The optical depth up through one scale height is " + upward);
        double level = LightPillarOptics.dustOpticalDepth(-5.0, -5.0, 100.0, 0.001, -2.0);
        helper.assertTrue(Math.abs(level - 0.1) <= 1.0e-12,
                "The optical depth of a level path below the dust bottom is " + level);
        double downward = LightPillarOptics.dustOpticalDepth(-2.0 + scaleHeight, -2.0, scaleHeight, 0.001, -2.0);
        helper.assertTrue(Math.abs(downward - upward) <= 1.0e-12, "The optical depth depends on the direction");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 400)
    public static void lightPillarRibbonsHoldTheVisibleLight(GameTestHelper helper) {
        double refractiveIndex = IceHaloFormulas.refractiveIndex(1);
        double lateralBlur = LightPillarOptics.lateralBlur(REFERENCE_PIXEL_ANGLE);
        double visible = LightPillarOptics.VISIBLE_VALUE;
        List<IceCrystalData.Crystal> plates = plates();
        double worstBlurChange = 0.0;
        double worstOutside = 0.0;
        String worstOutsideWhere = "nowhere";
        for (IceCrystalData.Crystal crystal : plates) {
            double tiltSigma = Math.toRadians(crystal.tiltDegrees());
            double share = IceCrystalData.get().shareOf(crystal);
            for (double[] light : RIBBON_LIGHTS) {
                double horizontalDistance = light[0];
                double lightHeight = light[1];
                double dustBottomHeight = light[2];
                for (double elevationDegrees : new double[]{5.0, 10.0, 20.0, 40.0, 70.0}) {
                    double elevation = Math.toRadians(elevationDegrees);
                    double width = LightPillarOptics.widestLateralSpread(elevation, horizontalDistance, lightHeight,
                            tiltSigma, lateralBlur);
                    if (width > 0.0) {
                        double sharp = lateralIntegral(elevation, horizontalDistance, lightHeight, tiltSigma,
                                refractiveIndex, 0.0, dustBottomHeight, 10.0 * width);
                        double blurred = lateralIntegral(elevation, horizontalDistance, lightHeight, tiltSigma,
                                refractiveIndex, lateralBlur, dustBottomHeight, 10.0 * width);
                        helper.assertTrue(sharp > 0.0, "A pillar with a width has no light across it");
                        worstBlurChange = Math.max(worstBlurChange, Math.abs(blurred / sharp - 1.0));
                    }
                }
                for (double strength : RIBBON_STRENGTHS) {
                    double scale = LightPillarOptics.BRIGHTNESS * strength * share * RIBBON_EXTINCTION;
                    double lowest = LightPillarOptics.lowestRibbonElevation(horizontalDistance, lightHeight,
                            tiltSigma);
                    for (double elevationDegrees = -45.0; elevationDegrees < 89.5; elevationDegrees += 0.5) {
                        double elevation = Math.toRadians(elevationDegrees);
                        double center = scale * LightPillarOptics.dustRadiance(elevation, 0.0, horizontalDistance,
                                lightHeight, tiltSigma, refractiveIndex, lateralBlur, dustBottomHeight,
                                RIBBON_EXTINCTION, NO_SCENE);
                        double halfWidth = elevation < lowest || elevation > LightPillarOptics.HIGHEST_RIBBON_ELEVATION
                                ? 0.0 : LightPillarOptics.ribbonHalfWidth(center, visible,
                                LightPillarOptics.widestLateralSpread(elevation, horizontalDistance, lightHeight,
                                        tiltSigma, lateralBlur));
                        for (double lateralDegrees = Math.toDegrees(halfWidth) + 0.01; lateralDegrees < 60.0;
                                lateralDegrees += 0.05) {
                            double value = scale * LightPillarOptics.dustRadiance(elevation,
                                    Math.toRadians(lateralDegrees), horizontalDistance, lightHeight, tiltSigma,
                                    refractiveIndex, lateralBlur, dustBottomHeight, RIBBON_EXTINCTION, NO_SCENE);
                            if (value > worstOutside) {
                                worstOutside = value;
                                worstOutsideWhere = String.format(Locale.ROOT,
                                        "%s of strength %.2f, light %.0f away and %.2f high, dust bottom %.2f, "
                                                + "elevation %.1f, %.2f degrees off the center",
                                        crystal.name(), strength, horizontalDistance, lightHeight, dustBottomHeight,
                                        elevationDegrees, lateralDegrees);
                            }
                            if (value < 1.0e-3 * visible) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info("Light pillar blur changes the light across a pillar by at most " + worstBlurChange
                + ", and outside the ribbons a pillar shows at most " + worstOutside / visible
                + " times the visible value, at " + worstOutsideWhere);
        helper.assertTrue(worstBlurChange <= 0.005,
                "The lateral blur changes the light across a pillar by " + worstBlurChange);
        helper.assertTrue(worstOutside <= 1.01 * visible, "A pillar shows " + worstOutside / visible
                + " times the visible value outside its ribbon, at " + worstOutsideWhere);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 200)
    public static void lightPillarRibbonEdgesHoldTheVisibleLight(GameTestHelper helper) {
        double refractiveIndex = IceHaloFormulas.refractiveIndex(1);
        double visible = LightPillarOptics.VISIBLE_VALUE;
        double worst = 0.0;
        String worstWhere = "nowhere";
        for (int screenHeight : RIBBON_SCREEN_HEIGHTS) {
            double pixelAngle = pixelAngle(screenHeight);
            double lateralBlur = LightPillarOptics.lateralBlur(pixelAngle);
            for (IceCrystalData.Crystal crystal : plates()) {
                double tiltSigma = Math.toRadians(crystal.tiltDegrees());
                double share = IceCrystalData.get().shareOf(crystal);
                for (double[] light : RIBBON_LIGHTS) {
                    for (double strength : RIBBON_STRENGTHS) {
                        double scale = LightPillarOptics.BRIGHTNESS * strength * share * RIBBON_EXTINCTION;
                        double[][] corners = ribbonEdge(light[0], light[1], light[2], tiltSigma, refractiveIndex,
                                lateralBlur, pixelAngle, scale);
                        if (corners == null) {
                            continue;
                        }
                        for (int segment = 0; segment < RIBBON_SEGMENTS; segment++) {
                            double[] below = corners[segment];
                            double[] above = corners[segment + 1];
                            for (int step = 0; step <= EDGE_STEPS; step++) {
                                double fraction = (double) step / EDGE_STEPS;
                                double x = below[0] + (above[0] - below[0]) * fraction;
                                double y = below[1] + (above[1] - below[1]) * fraction;
                                double z = below[2] + (above[2] - below[2]) * fraction;
                                double length = Math.sqrt(x * x + y * y + z * z);
                                double elevation = Math.asin(y / length);
                                double lateralAngle = Math.asin(z / length);
                                double value = scale * LightPillarOptics.dustRadiance(elevation, lateralAngle,
                                        light[0], light[1], tiltSigma, refractiveIndex, lateralBlur, light[2],
                                        RIBBON_EXTINCTION, NO_SCENE);
                                if (value > worst) {
                                    worst = value;
                                    worstWhere = String.format(Locale.ROOT,
                                            "%s of strength %.2f, light %.0f away and %.2f high, dust bottom %.2f, "
                                                    + "screen %d pixels tall, elevation %.2f, %.3f degrees off the "
                                                    + "center", crystal.name(), strength, light[0], light[1],
                                            light[2], screenHeight, Math.toDegrees(elevation),
                                            Math.toDegrees(lateralAngle));
                                }
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info("On the edges of light pillar ribbons a pillar shows at most " + worst / visible
                + " times the visible value, at " + worstWhere);
        helper.assertTrue(worst <= 1.01 * visible, "A pillar shows " + worst / visible
                + " times the visible value on the edge of its ribbon, at " + worstWhere);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightPillarBrightnessIsCalibratedOnATorch(GameTestHelper helper) {
        double refractiveIndex = IceHaloFormulas.refractiveIndex(1);
        double lateralBlur = LightPillarOptics.lateralBlur(REFERENCE_PIXEL_ANGLE);
        double radiance = 0.0;
        for (IceCrystalData.Crystal crystal : plates()) {
            radiance += IceCrystalData.get().shareOf(crystal) * 0.001 * LightPillarOptics.dustRadiance(
                    Math.toRadians(10.0), 0.0, 32.0, -1.12, Math.toRadians(crystal.tiltDegrees()), refractiveIndex,
                    lateralBlur, -1.62, 0.001, NO_SCENE);
        }
        double value = LightPillarOptics.BRIGHTNESS * LightPillarEmitters.strength(14) * radiance;
        double shown = 1.0 - Math.exp(-value);
        helper.assertTrue(Math.abs(shown - 0.1) <= 0.001,
                "The calibrated torch's pillar shows " + shown + " of the display at 10 degrees instead of 0.1");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightClustersMergeLightsPerCell(GameTestHelper helper) {
        LightPillarClusters clusters = new LightPillarClusters();
        clusters.beginSection(2, 4, -1);
        clusters.addLight(33, 65, -15, 1.0F, 0.5F, 0.25F);
        clusters.addLight(35, 66, -13, 0.5F, 0.25F, 0.125F);
        clusters.addLight(40, 65, -15, 0.0F, 0.0F, 1.0F);
        clusters.endSection();
        helper.assertTrue(clusters.count() == 0 && clusters.fillingCount() == 2,
                "A pass that is not finished shows " + clusters.count() + " clusters and fills "
                        + clusters.fillingCount() + " instead of 0 and 2");
        clusters.finishPass();
        helper.assertTrue(clusters.count() == 2, "The finished pass has " + clusters.count() + " clusters, not 2");
        int merged = clusterWithKey(clusters, BlockPos.asLong(8, 16, -4));
        int single = clusterWithKey(clusters, BlockPos.asLong(10, 16, -4));
        helper.assertTrue(merged >= 0 && single >= 0, "The clusters do not have the keys of their cells");
        double firstWeight = 1.75;
        double secondWeight = 0.875;
        double expectedX = (firstWeight * 33.5 + secondWeight * 35.5) / (firstWeight + secondWeight);
        double expectedY = (firstWeight * 65.5 + secondWeight * 66.5) / (firstWeight + secondWeight);
        double expectedZ = (firstWeight * -14.5 + secondWeight * -12.5) / (firstWeight + secondWeight);
        helper.assertTrue(Math.abs(clusters.x(merged) - expectedX) < 1.0e-9
                        && Math.abs(clusters.y(merged) - expectedY) < 1.0e-9
                        && Math.abs(clusters.z(merged) - expectedZ) < 1.0e-9,
                "The merged cluster is centered at " + clusters.x(merged) + " " + clusters.y(merged) + " "
                        + clusters.z(merged) + " instead of " + expectedX + " " + expectedY + " " + expectedZ);
        helper.assertTrue(clusters.red(merged) == 1.5F && clusters.green(merged) == 0.75F
                        && clusters.blue(merged) == 0.375F,
                "The merged cluster has the strengths " + clusters.red(merged) + " " + clusters.green(merged) + " "
                        + clusters.blue(merged) + " instead of 1.5 0.75 0.375");
        helper.assertTrue(clusters.x(single) == 40.5 && clusters.y(single) == 65.5 && clusters.z(single) == -14.5,
                "A single light's cluster is not centered on its block");
        clusters.beginSection(2, 4, -1);
        clusters.endSection();
        clusters.finishPass();
        helper.assertTrue(clusters.count() == 0, "A pass without lights still has " + clusters.count() + " clusters");
        helper.assertTrue(LightPillarClusters.sectionCanShowPillars(63, 71)
                        && !LightPillarClusters.sectionCanShowPillars(63, 72),
                "Sections are not skipped 8 blocks below the lowest surface");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightPillarSelectionKeepsTheBrightestAndFades(GameTestHelper helper) {
        double near = LightPillarSelection.score(1.0, 1.0, 1.0, 10.0, 0.0, 0.0, 1000.0);
        double far = LightPillarSelection.score(1.0, 1.0, 1.0, 20.0, 0.0, 0.0, 1000.0);
        helper.assertTrue(Math.abs(near / far - 2.0) < 1.0e-12, "Scores do not fall with the distance: " + near
                + " at 10 blocks and " + far + " at 20");
        helper.assertTrue(LightPillarSelection.score(1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1000.0)
                        == LightPillarSelection.score(1.0, 1.0, 1.0, LightPillarSelection.NEAREST_SCORED_DISTANCE, 0.0,
                        0.0, 1000.0),
                "A light next to the eye scores differently from one at the nearest scored distance");
        double inDust = LightPillarSelection.score(1.0, 1.0, 1.0, 100.0, 0.0, 0.0, 0.0);
        double crystalHeight = 0.5 * 100.0 * Math.tan(Math.toRadians(10.0));
        double expectedDensity = Math.exp(-crystalHeight / LightPillarSelection.DUST_SCALE_HEIGHT);
        helper.assertTrue(Math.abs(inDust / (expectedDensity / 100.0) - 1.0) < 1.0e-12,
                "A light 100 blocks away scores " + inDust + " instead of " + expectedDensity / 100.0);
        helper.assertTrue(LightPillarSelection.score(1.0, 1.0, 1.0, 100.0, 40.0, 0.0, 0.0) < inDust,
                "A light 40 blocks above the eye scores as high as one at the eye");

        int lightCount = LightPillarSelection.SLOT_COUNT + 10;
        LightPillarClusters clusters = new LightPillarClusters();
        addLightsInARow(clusters, lightCount, -1);
        LightPillarSelection selection = new LightPillarSelection();
        selection.choose(clusters, 0.0, 0.5, 0.5, 1000.0);
        helper.assertTrue(selection.chosenCount() == LightPillarSelection.SLOT_COUNT
                        && selection.usedCount() == LightPillarSelection.SLOT_COUNT,
                "The selection chose " + selection.chosenCount() + " and uses " + selection.usedCount() + " slots");
        for (int light = 0; light < lightCount; light++) {
            boolean shouldBeChosen = light < LightPillarSelection.SLOT_COUNT;
            helper.assertTrue((slotWithKey(selection, rowKey(light)) >= 0) == shouldBeChosen,
                    "Light " + light + " of the row is " + (shouldBeChosen ? "not chosen" : "chosen"));
        }
        int nearestSlot = slotWithKey(selection, rowKey(0));
        float fadeStep = 1.0F / LightPillarSelection.FADE_TICKS;
        helper.assertTrue(selection.fade(nearestSlot, 1.0F) == fadeStep && selection.fade(nearestSlot, 0.0F) == 0.0F
                        && Math.abs(selection.fade(nearestSlot, 0.5F) - 0.5F * fadeStep) < 1.0e-6F,
                "A new slot does not start its fade from 0");
        stepSelection(selection, clusters, LightPillarSelection.FADE_TICKS);
        helper.assertTrue(selection.fade(nearestSlot, 1.0F) == 1.0F, "A chosen slot does not fade in fully");

        LightPillarClusters withoutNearest = new LightPillarClusters();
        addLightsInARow(withoutNearest, lightCount, 0);
        int stepsToFreeASlot = LightPillarSelection.FADE_TICKS;
        stepSelection(selection, withoutNearest, 1);
        long nextKey = rowKey(LightPillarSelection.SLOT_COUNT);
        helper.assertTrue(selection.isUsed(nearestSlot) && selection.key(nearestSlot) == rowKey(0)
                        && slotWithKey(selection, nextKey) < 0,
                "A newly chosen light did not wait while every slot is used");
        stepSelection(selection, withoutNearest, stepsToFreeASlot - 1);
        helper.assertTrue(!selection.isUsed(nearestSlot) && slotWithKey(selection, nextKey) < 0,
                "The slot of a light that went away is not free once it faded out");
        stepSelection(selection, withoutNearest, 1);
        helper.assertTrue(slotWithKey(selection, nextKey) == nearestSlot && selection.fade(nearestSlot, 1.0F) == fadeStep,
                "The waiting light did not take the freed slot");

        for (int step = 0; step < stepsToFreeASlot; step++) {
            selection.chooseNone();
        }
        helper.assertTrue(selection.usedCount() == 0, "Slots stay used after choosing nothing");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lightPillarEmittersTakeTheirColors(GameTestHelper helper) {
        float[] color = new float[3];
        LightPillarEmitters.averageTextureColor(new int[]{0xFF0000FF}, 1, color);
        helper.assertTrue(color[0] == 1.0F && color[1] == 0.0F && color[2] == 0.0F,
                "A red pixel reads as " + color[0] + " " + color[1] + " " + color[2]);
        LightPillarEmitters.averageTextureColor(new int[]{0x00FFFFFF, 0xFFFF0000}, 2, color);
        helper.assertTrue(color[0] == 0.0F && color[1] == 0.0F && color[2] == 1.0F,
                "A transparent white pixel and a blue pixel read as " + color[0] + " " + color[1] + " " + color[2]);
        LightPillarEmitters.averageTextureColor(new int[]{0x00FFFFFF, 0xFF000000}, 2, color);
        helper.assertTrue(color[0] == 1.0F && color[1] == 1.0F && color[2] == 1.0F,
                "A texture without a bright opaque pixel is not white");
        int flame = 0xFF64C8FF;
        int stick = 0xFF102040;
        LightPillarEmitters.averageTextureColor(new int[]{stick, flame, stick, stick, stick}, 5, color);
        double expectedGreen = decodeSrgb(200.0 / 255.0);
        double expectedBlue = decodeSrgb(100.0 / 255.0);
        helper.assertTrue(color[0] == 1.0F && Math.abs(color[1] - expectedGreen) < 0.01
                        && Math.abs(color[2] - expectedBlue) < 0.01,
                "A torch-like texture reads as " + color[0] + " " + color[1] + " " + color[2] + " instead of its flame, 1 "
                        + expectedGreen + " " + expectedBlue);

        LightPillarEmitters overrides = LightPillarEmitters.overrides();
        float[] campfire = overrides.overrideColor(Blocks.CAMPFIRE.defaultBlockState());
        helper.assertTrue(campfire != null && campfire[0] == 1.0F && Math.abs(campfire[1] - 0.680F) < 1.0e-6F
                        && Math.abs(campfire[2] - 0.224F) < 1.0e-6F,
                "The campfire has no override or the wrong one");
        float[] soulCampfire = overrides.overrideColor(Blocks.SOUL_CAMPFIRE.defaultBlockState());
        helper.assertTrue(soulCampfire != null && soulCampfire[2] == 1.0F && soulCampfire[0] < soulCampfire[1],
                "The soul campfire's override is not normalized to its blue");
        float[] candle = overrides.overrideColor(Blocks.RED_CANDLE.defaultBlockState());
        helper.assertTrue(candle != null && Math.abs(candle[2] - 0.077F) < 1.0e-6F,
                "A red candle does not take the candle tag's override");
        helper.assertTrue(overrides.overrideColor(Blocks.TORCH.defaultBlockState()) == null,
                "The torch has an override although its texture gives its color");

        helper.assertTrue(LightPillarEmitters.showsPillar(Blocks.TORCH.defaultBlockState())
                        && LightPillarEmitters.showsPillar(Blocks.REDSTONE_TORCH.defaultBlockState())
                        && !LightPillarEmitters.showsPillar(Blocks.MAGMA_BLOCK.defaultBlockState())
                        && !LightPillarEmitters.showsPillar(Blocks.STONE.defaultBlockState()),
                "Lights below " + LightPillarEmitters.MINIMUM_EMISSION + " or above it are sorted wrongly");
        helper.assertTrue(LightPillarEmitters.strength(15) == 1.0F
                        && Math.abs(LightPillarEmitters.strength(14) - 196.0F / 225.0F) < 1.0e-6F,
                "Light strength is not the square of the emission over 15");
        helper.succeed();
    }

    private static int clusterWithKey(LightPillarClusters clusters, long key) {
        for (int cluster = 0; cluster < clusters.count(); cluster++) {
            if (clusters.key(cluster) == key) {
                return cluster;
            }
        }
        return -1;
    }

    private static int slotWithKey(LightPillarSelection selection, long key) {
        for (int slot = 0; slot < LightPillarSelection.SLOT_COUNT; slot++) {
            if (selection.isUsed(slot) && selection.key(slot) == key) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Adds equal lights along the x axis, one per section from x = 8 on, leaving out the light at the skipped index,
     * and finishes the pass.
     */
    private static void addLightsInARow(LightPillarClusters clusters, int count, int skipped) {
        for (int light = 0; light < count; light++) {
            if (light == skipped) {
                continue;
            }
            clusters.beginSection(light, 0, 0);
            clusters.addLight(16 * light + 8, 0, 0, 1.0F, 1.0F, 1.0F);
            clusters.endSection();
        }
        clusters.finishPass();
    }

    private static long rowKey(int light) {
        return BlockPos.asLong(4 * light + 2, 0, 0);
    }

    private static void stepSelection(LightPillarSelection selection, LightPillarClusters clusters, int steps) {
        for (int step = 0; step < steps; step++) {
            selection.choose(clusters, 0.0, 0.5, 0.5, 1000.0);
        }
    }

    /**
     * The radiance of the pillar in dust summed across it, from the given lateral angle on one side to the other.
     */
    private static double lateralIntegral(double elevation, double horizontalDistance, double lightHeight,
            double tiltSigma, double refractiveIndex, double lateralBlur, double dustBottomHeight, double end) {
        int steps = 4000;
        double step = 2.0 * end / steps;
        double total = 0.0;
        for (int index = 0; index < steps; index++) {
            double lateralAngle = -end + (index + 0.5) * step;
            total += LightPillarOptics.dustRadiance(elevation, lateralAngle, horizontalDistance, lightHeight,
                    tiltSigma, refractiveIndex, lateralBlur, dustBottomHeight, RIBBON_EXTINCTION, NO_SCENE) * step;
        }
        return total;
    }

    /**
     * The corners on one side of a pillar's ribbon from the bottom row up, as the vertex stage of light_pillars.glsl
     * places them at a distance of 1, with x level toward the light, y up, and z across. It is null where the ribbon is
     * collapsed.
     */
    private static double[][] ribbonEdge(double horizontalDistance, double lightHeight, double dustBottomHeight,
            double tiltSigma, double refractiveIndex, double lateralBlur, double marginAngle, double valueScale) {
        double lowest = LightPillarOptics.lowestRibbonElevation(horizontalDistance, lightHeight, tiltSigma);
        double highest = LightPillarOptics.HIGHEST_RIBBON_ELEVATION;
        if (!(highest > lowest)) {
            return null;
        }
        double firstRow = lowest - marginAngle;
        double lowestEvaluated = Math.min(lowest + 1.0e-3, highest);
        double[] halfWidths = new double[RIBBON_SEGMENTS + 1];
        for (int row = 0; row <= RIBBON_SEGMENTS; row++) {
            double evaluated = Math.max(lowestEvaluated, Math.min(ribbonRowElevation(row, firstRow), highest));
            double center = valueScale * LightPillarOptics.dustRadiance(evaluated, 0.0, horizontalDistance,
                    lightHeight, tiltSigma, refractiveIndex, lateralBlur, dustBottomHeight, RIBBON_EXTINCTION, NO_SCENE);
            double halfWidth = LightPillarOptics.ribbonHalfWidth(center, LightPillarOptics.VISIBLE_VALUE,
                    LightPillarOptics.widestLateralSpread(evaluated, horizontalDistance, lightHeight, tiltSigma,
                            lateralBlur));
            halfWidths[row] = center > LightPillarOptics.VISIBLE_VALUE
                    ? Math.min(halfWidth + marginAngle, WIDEST_RIBBON_ANGLE) : 0.0;
        }
        double[][] corners = new double[RIBBON_SEGMENTS + 1][];
        for (int row = 0; row <= RIBBON_SEGMENTS; row++) {
            double halfWidth = halfWidths[row];
            if (row > 0) {
                halfWidth = Math.max(halfWidth, halfWidths[row - 1]);
            }
            if (row < RIBBON_SEGMENTS) {
                halfWidth = Math.max(halfWidth, halfWidths[row + 1]);
            }
            double elevation = ribbonRowElevation(row, firstRow);
            corners[row] = new double[]{Math.cos(elevation), Math.sin(elevation), Math.tan(halfWidth)};
        }
        return corners;
    }

    private static double ribbonRowElevation(int row, double firstRow) {
        double rowFraction = (double) row / RIBBON_SEGMENTS;
        return firstRow + (LightPillarOptics.HIGHEST_RIBBON_ELEVATION - firstRow) * rowFraction * rowFraction;
    }

    private static double pixelAngle(int screenHeight) {
        return 2.0 * Math.tan(Math.toRadians(35.0)) / screenHeight;
    }

    private static double decodeSrgb(double encoded) {
        return encoded <= 0.04045 ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4);
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
     * The radiance of the pillar summed along the view ray from the eye up to the scene distance, with the reflection
     * of every crystal worked out in three dimensions. The light is on the x axis and z points up.
     */
    private static double rayIntegral(double elevation, double lateralAngle, double horizontalDistance,
            double lightHeight, double tiltSigma, double refractiveIndex, double sceneDistance) {
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
        double end = Math.min(mirrorDistance + RAY_WINDOWS * window, sceneDistance);
        if (!(end > start)) {
            return 0.0;
        }
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
