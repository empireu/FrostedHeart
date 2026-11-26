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

package com.teammoeg.frostedheart.content.climate.render.fog;

import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the snowfall and fog settings of the calculated sky: their keys, defaults, ranges, and order in the config.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogSettingsGameTests {
    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void fogSettingsHaveTheirKeysDefaultsRangesAndOrder(GameTestHelper helper) {
        checkSwitchSetting(helper, SkySetting.SNOW_ENABLED, "snowEnabled", true);
        checkNumberSetting(helper, SkySetting.SNOW_FLAKE_SHARE, "snowFlakeShare", 0.5, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.SNOW_FLAKE_SIZE, "snowFlakeSize", 0.08, 0.01, 0.5);
        checkNumberSetting(helper, SkySetting.SNOW_FALL_SPEED, "snowFallSpeed", 0.6, 0.05, 5.0);
        checkNumberSetting(helper, SkySetting.SNOW_BLIZZARD_FALL_SPEED, "snowBlizzardFallSpeed", 1.8, 0.05, 5.0);
        checkNumberSetting(helper, SkySetting.SNOW_WIND_SHARE, "snowWindShare", 0.5, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.SNOW_FLUTTER, "snowFlutter", 0.15, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.SNOW_STREAK_SECONDS, "snowStreakSeconds", 0.02, 0.0, 0.2);
        checkNumberSetting(helper, SkySetting.SNOW_FLAKE_BRIGHTNESS, "snowFlakeBrightness", 1.0, 0.0, 4.0);
        checkNumberSetting(helper, SkySetting.SNOW_NEAR_RADIUS, "snowNearRadius", 20.0, 8.0, 32.0);
        checkSwitchSetting(helper, SkySetting.SNOW_COLLISION, "snowCollision", true);
        checkNumberSetting(helper, SkySetting.SNOW_SPLAT_SECONDS, "snowSplatSeconds", 1.5, 0.0, 10.0);
        checkSwitchSetting(helper, SkySetting.SNOW_LEE, "snowLee", true);
        checkNumberSetting(helper, SkySetting.SNOW_LEE_FILL, "snowLeeFill", 0.15, 0.0, 2.0);
        checkNumberSetting(helper, SkySetting.SNOW_LEE_BAND, "snowLeeBand", 3.0, 0.0, 16.0);

        checkSwitchSetting(helper, SkySetting.FOG_ENABLED, "fogEnabled", true);

        checkNumberSetting(helper, SkySetting.FOG_SNOW_VISIBILITY, "fogSnowVisibility", 300.0, 8.0, 2048.0);
        checkNumberSetting(helper, SkySetting.FOG_BLIZZARD_VISIBILITY, "fogBlizzardVisibility", 80.0, 4.0, 2048.0);
        checkNumberSetting(helper, SkySetting.FOG_HEIGHT_SCALE, "fogHeightScale", 128.0, 16.0, 1024.0);
        checkNumberSetting(helper, SkySetting.FOG_LEAF_POROSITY, "fogLeafPorosity", 0.3, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.FOG_FIELD_BUDGET_MICROS, "fogFieldBudgetMicros", 500.0, 50.0, 5000.0);
        checkNumberSetting(helper, SkySetting.FOG_SNOW_DECAY_LENGTH, "fogSnowDecayLength", 1.5, 0.25, 4.3);
        checkNumberSetting(helper, SkySetting.FOG_BLIZZARD_DECAY_LENGTH, "fogBlizzardDecayLength", 4.0, 0.25, 4.3);
        checkNumberSetting(helper, SkySetting.FOG_FIELD_RADIUS, "fogFieldRadius", 32.0, 12.0, 40.0);
        checkNumberSetting(helper, SkySetting.FOG_FROXEL_DIVISOR, "fogFroxelDivisor", 8.0, 4.0, 16.0);
        checkNumberSetting(helper, SkySetting.FOG_FROXEL_SLICES, "fogFroxelSlices", 32.0, 32.0, 64.0);
        checkNumberSetting(helper, SkySetting.FOG_FROXEL_RANGE, "fogFroxelRange", 192.0, 64.0, 512.0);
        checkNumberSetting(helper, SkySetting.FOG_SNOW_STRUCTURE_SHARE, "fogSnowStructureShare", 0.3, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.FOG_BLIZZARD_STRUCTURE_SHARE, "fogBlizzardStructureShare", 0.2, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.FOG_STRUCTURE_COVERAGE, "fogStructureCoverage", 0.25, 0.05, 1.0);
        checkNumberSetting(helper, SkySetting.FOG_STRUCTURE_SIZE, "fogStructureSize", 16.0, 16.0, 512.0);
        checkNumberSetting(helper, SkySetting.FOG_STRUCTURE_STRETCH, "fogStructureStretch", 1.5, 1.0, 6.0);
        checkNumberSetting(helper, SkySetting.FOG_STRUCTURE_EROSION, "fogStructureErosion", 0.5, 0.0, 0.9);
        checkNumberSetting(helper, SkySetting.FOG_STRUCTURE_RANGE, "fogStructureRange", 96.0, 32.0, 160.0);
        checkNumberSetting(helper, SkySetting.FOG_WIND_SCALE, "fogWindScale", 12.0, 0.0, 40.0);
        checkNumberSetting(helper, SkySetting.FOG_CAVITY_SHARE, "fogCavityShare", 0.0, 0.0, 1.0);

        double snowVisibility = SkySetting.FOG_SNOW_VISIBILITY.defaultValue();
        double blizzardVisibility = SkySetting.FOG_BLIZZARD_VISIBILITY.defaultValue();
        helper.assertTrue(blizzardVisibility < snowVisibility, "fogBlizzardVisibility has the default "
                + blizzardVisibility + ", not below the " + snowVisibility + " of fogSnowVisibility");

        SkySetting[] weatherSettings = {SkySetting.SNOW_ENABLED, SkySetting.SNOW_FLAKE_SHARE,
                SkySetting.SNOW_FLAKE_SIZE, SkySetting.SNOW_FALL_SPEED, SkySetting.SNOW_BLIZZARD_FALL_SPEED,
                SkySetting.SNOW_WIND_SHARE, SkySetting.SNOW_FLUTTER, SkySetting.SNOW_STREAK_SECONDS,
                SkySetting.SNOW_FLAKE_BRIGHTNESS, SkySetting.SNOW_NEAR_RADIUS, SkySetting.SNOW_COLLISION,
                SkySetting.SNOW_SPLAT_SECONDS, SkySetting.SNOW_LEE, SkySetting.SNOW_LEE_FILL, SkySetting.SNOW_LEE_BAND,
                SkySetting.FOG_ENABLED, SkySetting.FOG_SNOW_VISIBILITY, SkySetting.FOG_BLIZZARD_VISIBILITY,
                SkySetting.FOG_HEIGHT_SCALE, SkySetting.FOG_LEAF_POROSITY, SkySetting.FOG_FIELD_BUDGET_MICROS,
                SkySetting.FOG_SNOW_DECAY_LENGTH, SkySetting.FOG_BLIZZARD_DECAY_LENGTH, SkySetting.FOG_FIELD_RADIUS,
                SkySetting.FOG_FROXEL_DIVISOR, SkySetting.FOG_FROXEL_SLICES, SkySetting.FOG_FROXEL_RANGE,
                SkySetting.FOG_SNOW_STRUCTURE_SHARE, SkySetting.FOG_BLIZZARD_STRUCTURE_SHARE,
                SkySetting.FOG_STRUCTURE_COVERAGE, SkySetting.FOG_STRUCTURE_SIZE, SkySetting.FOG_STRUCTURE_STRETCH,
                SkySetting.FOG_STRUCTURE_EROSION, SkySetting.FOG_STRUCTURE_RANGE, SkySetting.FOG_WIND_SCALE,
                SkySetting.FOG_CAVITY_SHARE};
        SkySetting[] settings = SkySetting.values();
        int lastIndex = settings.length - 1;
        helper.assertTrue(lastIndex + 1 >= weatherSettings.length, "SkySetting has " + settings.length
                + " settings, fewer than the " + weatherSettings.length + " snow and fog settings");
        for (int settingIndex = 0; settingIndex < weatherSettings.length; settingIndex++) {
            int distanceFromEnd = weatherSettings.length - 1 - settingIndex;
            helper.assertTrue(settings[lastIndex - distanceFromEnd] == weatherSettings[settingIndex], "SkySetting has "
                    + settings[lastIndex - distanceFromEnd] + " " + (distanceFromEnd + 1) + " from the end instead of "
                    + weatherSettings[settingIndex]);
        }

        helper.succeed();
    }

    /**
     * Checks the key, switch flag, default, manual value, and rebuilds of a boolean switch setting.
     */
    private static void checkSwitchSetting(GameTestHelper helper, SkySetting setting, String key, boolean defaultOn) {
        String settingKey = setting.key();
        helper.assertTrue(key.equals(settingKey), key + " has the key " + settingKey + " instead of " + key);
        boolean settingIsSwitch = setting.isSwitch();
        helper.assertTrue(settingIsSwitch, key + " has the switch " + settingIsSwitch + " instead of true");
        double expectedDefault = defaultOn ? 1.0 : 0.0;
        double settingDefault = setting.defaultValue();
        helper.assertTrue(settingDefault == expectedDefault, key + " has the default " + settingDefault
                + " instead of " + expectedDefault);
        SkySetting manualValue = setting.manualValue();
        helper.assertTrue(manualValue == null, key + " has the manual value " + manualValue + " instead of null");
        SkySetting.Rebuilds settingRebuilds = setting.rebuilds();
        helper.assertTrue(settingRebuilds == SkySetting.Rebuilds.NOTHING, key + " rebuilds " + settingRebuilds
                + " instead of NOTHING");
    }

    /**
     * Checks the key, default, minimum, maximum, switch, and rebuilds of a number setting, which the messages name by
     * the key it is expected to have.
     */
    private static void checkNumberSetting(GameTestHelper helper, SkySetting setting, String key, double defaultValue,
            double minimum, double maximum) {
        String settingKey = setting.key();
        helper.assertTrue(key.equals(settingKey), key + " has the key " + settingKey + " instead of " + key);
        boolean settingIsSwitch = setting.isSwitch();
        helper.assertTrue(!settingIsSwitch, key + " has the switch " + settingIsSwitch + " instead of false");
        double settingDefault = setting.defaultValue();
        helper.assertTrue(settingDefault == defaultValue, key + " has the default " + settingDefault + " instead of "
                + defaultValue);
        double settingMinimum = setting.minimum();
        helper.assertTrue(settingMinimum == minimum, key + " has the minimum " + settingMinimum + " instead of "
                + minimum);
        double settingMaximum = setting.maximum();
        helper.assertTrue(settingMaximum == maximum, key + " has the maximum " + settingMaximum + " instead of "
                + maximum);
        SkySetting.Rebuilds settingRebuilds = setting.rebuilds();
        helper.assertTrue(settingRebuilds == SkySetting.Rebuilds.NOTHING, key + " rebuilds " + settingRebuilds
                + " instead of NOTHING");
    }
}
