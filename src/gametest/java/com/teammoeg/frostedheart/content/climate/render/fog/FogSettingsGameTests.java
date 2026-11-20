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
 * Checks the fog settings of the calculated sky: their keys, defaults, ranges, and order in the config.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogSettingsGameTests {
    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void fogSettingsHaveTheirKeysDefaultsRangesAndOrder(GameTestHelper helper) {
        String enabledKey = SkySetting.FOG_ENABLED.key();
        helper.assertTrue("fogEnabled".equals(enabledKey), "fogEnabled has the key " + enabledKey + " instead of "
                + "fogEnabled");
        boolean enabledIsSwitch = SkySetting.FOG_ENABLED.isSwitch();
        helper.assertTrue(enabledIsSwitch, "fogEnabled has the switch " + enabledIsSwitch + " instead of true");
        double enabledDefault = SkySetting.FOG_ENABLED.defaultValue();
        helper.assertTrue(enabledDefault == 1.0, "fogEnabled has the default " + enabledDefault + " instead of 1.0");
        SkySetting enabledManualValue = SkySetting.FOG_ENABLED.manualValue();
        helper.assertTrue(enabledManualValue == null, "fogEnabled has the manual value " + enabledManualValue
                + " instead of null");
        SkySetting.Rebuilds enabledRebuilds = SkySetting.FOG_ENABLED.rebuilds();
        helper.assertTrue(enabledRebuilds == SkySetting.Rebuilds.NOTHING, "fogEnabled rebuilds " + enabledRebuilds
                + " instead of NOTHING");

        checkNumberSetting(helper, SkySetting.FOG_SNOW_VISIBILITY, "fogSnowVisibility", 300.0, 8.0, 2048.0);
        checkNumberSetting(helper, SkySetting.FOG_BLIZZARD_VISIBILITY, "fogBlizzardVisibility", 80.0, 4.0, 2048.0);
        checkNumberSetting(helper, SkySetting.FOG_HEIGHT_SCALE, "fogHeightScale", 128.0, 16.0, 1024.0);
        checkNumberSetting(helper, SkySetting.FOG_LEAF_POROSITY, "fogLeafPorosity", 0.3, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.FOG_FIELD_BUDGET_MICROS, "fogFieldBudgetMicros", 500.0, 50.0, 5000.0);
        checkNumberSetting(helper, SkySetting.FOG_SNOW_DECAY_LENGTH, "fogSnowDecayLength", 1.5, 0.25, 4.3);
        checkNumberSetting(helper, SkySetting.FOG_BLIZZARD_DECAY_LENGTH, "fogBlizzardDecayLength", 4.0, 0.25, 4.3);
        checkNumberSetting(helper, SkySetting.FOG_FIELD_RADIUS, "fogFieldRadius", 32.0, 12.0, 40.0);
        checkNumberSetting(helper, SkySetting.FOG_RESOLUTION_DIVISOR, "fogResolutionDivisor", 2.0, 1.0, 4.0);
        checkNumberSetting(helper, SkySetting.FOG_FALLBACK_CAP, "fogFallbackCap", 0.25, 0.0, 1.0);
        checkNumberSetting(helper, SkySetting.FOG_NOISE_STRENGTH, "fogNoiseStrength", 0.8, 0.0, 4.0);
        checkNumberSetting(helper, SkySetting.FOG_NOISE_SCALE, "fogNoiseScale", 2.0, 1.0, 8.0);
        checkNumberSetting(helper, SkySetting.FOG_WIND_SCALE, "fogWindScale", 12.0, 0.0, 40.0);

        double snowVisibility = SkySetting.FOG_SNOW_VISIBILITY.defaultValue();
        double blizzardVisibility = SkySetting.FOG_BLIZZARD_VISIBILITY.defaultValue();
        helper.assertTrue(blizzardVisibility < snowVisibility, "fogBlizzardVisibility has the default "
                + blizzardVisibility + ", not below the " + snowVisibility + " of fogSnowVisibility");

        SkySetting[] settings = SkySetting.values();
        int lastIndex = settings.length - 1;
        helper.assertTrue(lastIndex >= 13, "SkySetting has " + settings.length + " settings, fewer than the 14 fog "
                + "settings");
        helper.assertTrue(settings[lastIndex - 13] == SkySetting.FOG_ENABLED, "SkySetting has "
                + settings[lastIndex - 13] + " fourteenth from the end instead of FOG_ENABLED");
        helper.assertTrue(settings[lastIndex - 12] == SkySetting.FOG_SNOW_VISIBILITY, "SkySetting has "
                + settings[lastIndex - 12] + " thirteenth from the end instead of FOG_SNOW_VISIBILITY");
        helper.assertTrue(settings[lastIndex - 11] == SkySetting.FOG_BLIZZARD_VISIBILITY, "SkySetting has "
                + settings[lastIndex - 11] + " twelfth from the end instead of FOG_BLIZZARD_VISIBILITY");
        helper.assertTrue(settings[lastIndex - 10] == SkySetting.FOG_HEIGHT_SCALE, "SkySetting has "
                + settings[lastIndex - 10] + " eleventh from the end instead of FOG_HEIGHT_SCALE");
        helper.assertTrue(settings[lastIndex - 9] == SkySetting.FOG_LEAF_POROSITY, "SkySetting has "
                + settings[lastIndex - 9] + " tenth from the end instead of FOG_LEAF_POROSITY");
        helper.assertTrue(settings[lastIndex - 8] == SkySetting.FOG_FIELD_BUDGET_MICROS, "SkySetting has "
                + settings[lastIndex - 8] + " ninth from the end instead of FOG_FIELD_BUDGET_MICROS");
        helper.assertTrue(settings[lastIndex - 7] == SkySetting.FOG_SNOW_DECAY_LENGTH, "SkySetting has "
                + settings[lastIndex - 7] + " eighth from the end instead of FOG_SNOW_DECAY_LENGTH");
        helper.assertTrue(settings[lastIndex - 6] == SkySetting.FOG_BLIZZARD_DECAY_LENGTH, "SkySetting has "
                + settings[lastIndex - 6] + " seventh from the end instead of FOG_BLIZZARD_DECAY_LENGTH");
        helper.assertTrue(settings[lastIndex - 5] == SkySetting.FOG_FIELD_RADIUS, "SkySetting has "
                + settings[lastIndex - 5] + " sixth from the end instead of FOG_FIELD_RADIUS");
        helper.assertTrue(settings[lastIndex - 4] == SkySetting.FOG_RESOLUTION_DIVISOR, "SkySetting has "
                + settings[lastIndex - 4] + " fifth from the end instead of FOG_RESOLUTION_DIVISOR");
        helper.assertTrue(settings[lastIndex - 3] == SkySetting.FOG_FALLBACK_CAP, "SkySetting has "
                + settings[lastIndex - 3] + " fourth from the end instead of FOG_FALLBACK_CAP");
        helper.assertTrue(settings[lastIndex - 2] == SkySetting.FOG_NOISE_STRENGTH, "SkySetting has "
                + settings[lastIndex - 2] + " third from the end instead of FOG_NOISE_STRENGTH");
        helper.assertTrue(settings[lastIndex - 1] == SkySetting.FOG_NOISE_SCALE, "SkySetting has "
                + settings[lastIndex - 1] + " second from the end instead of FOG_NOISE_SCALE");
        helper.assertTrue(settings[lastIndex] == SkySetting.FOG_WIND_SCALE, "SkySetting ends with "
                + settings[lastIndex] + " instead of FOG_WIND_SCALE");

        helper.succeed();
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
