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

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.infrastructure.config.FHConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

import static com.teammoeg.frostedheart.infrastructure.command.CommandHelper.literal;
import static com.teammoeg.frostedheart.infrastructure.command.CommandHelper.registerCommand;

/**
 * Client command that turns the calculated sky on and off and changes its settings in the client config.
 */
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CalculatedSkyClientCommand {
    private CalculatedSkyClientCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        var command = literal("calculatedSky")
                .executes(CalculatedSkyClientCommand::status)
                .then(literal("on").executes(context -> setEnabled(context, true)))
                .then(literal("off").executes(context -> setEnabled(context, false)))
                .then(literal("reset").executes(CalculatedSkyClientCommand::reset))
                .then(literal("set")
                        .then(setting("exposure", FHConfig.CLIENT.calculatedSkyExposure,
                                SkySettings.MINIMUM_EXPOSURE, SkySettings.MAXIMUM_EXPOSURE))
                        .then(setting("nanobotOpticalDepth", FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth,
                                0.0, SkySettings.MAXIMUM_NANOBOT_OPTICAL_DEPTH))
                        .then(setting("nanobotMedianNanometers", FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers,
                                SkySettings.MINIMUM_NANOBOT_MEDIAN_NANOMETERS,
                                SkySettings.MAXIMUM_NANOBOT_MEDIAN_NANOMETERS))
                        .then(setting("nanobotSpread", FHConfig.CLIENT.calculatedSkyNanobotSpread,
                                SkySettings.MINIMUM_NANOBOT_SPREAD, SkySettings.MAXIMUM_NANOBOT_SPREAD))
                        .then(setting("iceOpticalDepth", FHConfig.CLIENT.calculatedSkyIceOpticalDepth,
                                0.0, SkySettings.MAXIMUM_ICE_OPTICAL_DEPTH)));
        registerCommand(event.getDispatcher(), command);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setting(String name, ForgeConfigSpec.DoubleValue value,
            double minimum, double maximum) {
        return literal(name).then(Commands.argument("value", DoubleArgumentType.doubleArg(minimum, maximum))
                .executes(context -> {
                    value.set(DoubleArgumentType.getDouble(context, "value"));
                    value.save();
                    return status(context);
                }));
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        FHConfig.CLIENT.calculatedSky.set(enabled);
        FHConfig.CLIENT.calculatedSky.save();
        if (enabled) {
            CalculatedSkyRenderer.clearFailure();
        }
        return status(context);
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        FHConfig.CLIENT.calculatedSkyExposure.set(SkySettings.DEFAULT_EXPOSURE);
        FHConfig.CLIENT.calculatedSkyNanobotOpticalDepth.set(SkySettings.DEFAULT.nanobotOpticalDepth());
        FHConfig.CLIENT.calculatedSkyNanobotMedianNanometers.set(SkySettings.DEFAULT.nanobotMedianNanometers());
        FHConfig.CLIENT.calculatedSkyNanobotSpread.set(SkySettings.DEFAULT.nanobotSpread());
        FHConfig.CLIENT.calculatedSkyIceOpticalDepth.set(SkySettings.DEFAULT.iceOpticalDepth());
        // Saving one value writes the whole client config file:
        FHConfig.CLIENT.calculatedSkyExposure.save();
        return status(context);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return 0;
        }
        SkySettings settings = CalculatedSkyRenderer.configSettings();
        double megatons = settings.nanobotOpticalDepth() * SkyParticleOptics.forDistribution(
                settings.nanobotMedianNanometers(), settings.nanobotSpread()).megatonsForUnitOpticalDepth();
        send(context, String.format(Locale.ROOT, "Calculated sky: %s", CalculatedSkyRenderer.describeState(level)));
        send(context, String.format(Locale.ROOT,
                "exposure %.2f, nanobotOpticalDepth %.3f (about %.0f Mt), nanobotMedianNanometers %.0f, "
                        + "nanobotSpread %.2f, iceOpticalDepth %.3f",
                FHConfig.CLIENT.calculatedSkyExposure.get(), settings.nanobotOpticalDepth(), megatons,
                settings.nanobotMedianNanometers(), settings.nanobotSpread(), settings.iceOpticalDepth()));
        send(context, String.format(Locale.ROOT, "Last build: tables %.1f ms, sky view %.1f ms",
                CalculatedSkyRenderer.lastTablesMilliseconds(), CalculatedSkyRenderer.lastViewMilliseconds()));
        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}
