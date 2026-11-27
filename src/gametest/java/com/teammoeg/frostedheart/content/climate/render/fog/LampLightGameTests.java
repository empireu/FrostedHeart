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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the LampLightField against sums over a fake world's light: the cells of every cascade, the sky light that
 * sections without data take from above, closed and windowed rooms, unloaded chunks, clearing, moved and budgeted
 * fields against fresh ones, the filter and the blend of the cascades, the lit boxes, the fog march's twin
 * LampFogMarch, the shaders' constants, and that a warm field allocates nothing.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class LampLightGameTests {
    private static final int MIN_SECTION = -4;
    private static final int SECTION_COUNT = 24;
    private static final int MIN_Y = MIN_SECTION * 16;
    private static final int TOP_Y = (MIN_SECTION + SECTION_COUNT) * 16;
    private static final int GROUND_Y = 64;
    private static final int BASE_X = 1000;
    private static final int BASE_Z = -2000;
    private static final double EYE_X = BASE_X + 0.3;
    private static final double EYE_Y = 70.6;
    private static final double EYE_Z = BASE_Z - 0.2;
    private static final int TORCH_LEVEL = 14;
    private static final int SAMPLED_CELLS = 3000;
    private static final float RELATIVE_TOLERANCE = 1.0e-3F;
    private static final float ABSOLUTE_TOLERANCE = 1.0e-7F;
    private static final float LEVEL_TOLERANCE = 2.0e-3F;
    private static final int SHADER_PATTERN_REACH = 250;
    private static final int SHADER_SAMPLES = 20000;
    /**
     * The half side in blocks of the even field's lit square, its eye, and the lamp color of level 15 in the march's
     * checks.
     */
    private static final int EVEN_REACH = 280;
    private static final double EVEN_EYE_X = BASE_X + 0.3;
    private static final double EVEN_EYE_Y = GROUND_Y + 8.4;
    private static final double EVEN_EYE_Z = BASE_Z - 0.2;
    private static final float[] EVEN_LIFT = {0.6F, 0.45F, 0.3F};
    private static final Pattern GLSL_CONST_PATTERN = Pattern.compile(
            "^\\s*const\\s+(?:int|uint|float)\\s+([A-Z0-9_]+)\\s*=\\s*([^;]+);");

    @GameTest(template = "phase0a_empty", timeoutTicks = 400)
    public static void lampCellsHoldTheLightOfTheirOutdoorAir(GameTestHelper helper) {
        FakeSource source = scene();
        LampLightField field = filledField(source, EYE_X, EYE_Y, EYE_Z);
        for (int cascade = 0; cascade < LampLightField.CASCADE_COUNT; cascade++) {
            checkBox(helper, field, source, cascade, BASE_X - 16, GROUND_Y - 4, BASE_Z - 16, BASE_X + 20, GROUND_Y + 20,
                    BASE_Z + 20, "around the torch");
            checkBox(helper, field, source, cascade, BASE_X + 12, GROUND_Y - 4, BASE_Z - 14, BASE_X + 34,
                    GROUND_Y + 12, BASE_Z + 8, "around the closed house");
            checkBox(helper, field, source, cascade, BASE_X - 38, GROUND_Y - 4, BASE_Z + 2, BASE_X - 16,
                    GROUND_Y + 12, BASE_Z + 24, "around the windowed room");
            checkBox(helper, field, source, cascade, BASE_X + 184, GROUND_Y - 4, BASE_Z + 134, BASE_X + 216,
                    GROUND_Y + 20, BASE_Z + 166, "around the near village light");
            checkBox(helper, field, source, cascade, BASE_X - 416, GROUND_Y - 4, BASE_Z - 316, BASE_X - 384,
                    GROUND_Y + 20, BASE_Z - 284, "around the far village light");
            checkBox(helper, field, source, cascade, BASE_X + 96, GROUND_Y - 4, BASE_Z + 96, BASE_X + 112,
                    GROUND_Y + 20, BASE_Z + 112, "in the unloaded chunk");
            checkBox(helper, field, source, cascade, BASE_X + 48, GROUND_Y, BASE_Z + 48, BASE_X + 64,
                    GROUND_Y + 40, BASE_Z + 64, "under the section whose sky light the one below takes");
            checkSampledCells(helper, field, source, cascade, new Random(17L + cascade));
            checkSlots(helper, field.cascade(cascade));
        }
        LampLightCascade nearest = field.cascade(0);
        int houseX = BASE_X + 23;
        int houseY = GROUND_Y + 2;
        int houseZ = BASE_Z - 3;
        helper.assertTrue(nearest.airAt(houseX, houseY, houseZ) == 0 && nearest.lightAt(houseX, houseY, houseZ) == 0,
                "the lit closed house holds outdoor air in cascade 0");
        int roomX = BASE_X - 27;
        int roomZ = BASE_Z + 13;
        helper.assertTrue(LampLightField.fromHalf(nearest.airAt(roomX, houseY, roomZ)) == 1.0F
                        && LampLightField.fromHalf(nearest.lightAt(roomX, houseY, roomZ)) > 0.0F,
                "the windowed room does not count as lit outdoor air in cascade 0");
        LampLightCascade middle = field.cascade(LampLightField.FIRST_FAR_CASCADE);
        int roomCellX = Math.floorDiv(roomX, middle.cellWidth());
        int roomCellY = Math.floorDiv(houseY, middle.cellHeight());
        int roomCellZ = Math.floorDiv(roomZ, middle.cellWidth());
        helper.assertTrue(middle.lightAt(roomCellX, roomCellY, roomCellZ) == 0,
                "the windowed room's light counts in a far cascade, where only open sky counts");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 400)
    public static void aBrokenTorchAndAnUnloadedChunkLeaveNoLight(GameTestHelper helper) {
        FakeSource source = new FakeSource();
        LampLightField field = filledField(source, EYE_X, EYE_Y, EYE_Z);
        int torchX = BASE_X + 5;
        int torchZ = BASE_Z + 6;
        placeTorch(source, torchX, GROUND_Y + 1, torchZ, TORCH_LEVEL, false);
        markTorch(field, torchX, GROUND_Y + 1, torchZ, TORCH_LEVEL);
        drain(field, source);
        for (int cascade = 0; cascade < LampLightField.CASCADE_COUNT; cascade++) {
            LampLightCascade cells = field.cascade(cascade);
            helper.assertTrue(cells.lightAt(Math.floorDiv(torchX, cells.cellWidth()),
                    Math.floorDiv(GROUND_Y + 1, cells.cellHeight()), Math.floorDiv(torchZ, cells.cellWidth())) != 0,
                    "cascade " + cascade + " holds no light at the torch");
        }
        source.clearBlockLight();
        markTorch(field, torchX, GROUND_Y + 1, torchZ, TORCH_LEVEL);
        drain(field, source);
        checkNoLight(helper, field, "after the torch was broken");

        int chunkX = Math.floorDiv(BASE_X + 16, 16);
        int chunkZ = Math.floorDiv(BASE_Z - 16, 16);
        placeTorch(source, chunkX * 16 + 8, GROUND_Y + 1, chunkZ * 16 + 8, 7, true);
        field.markColumn(chunkX, chunkZ);
        drain(field, source);
        helper.assertTrue(field.cascade(0).lightAt(chunkX * 16 + 8, GROUND_Y + 1, chunkZ * 16 + 8) != 0,
                "the torch in the chunk that unloads gives no light");
        source.unloaded.add(ChunkPos.asLong(chunkX, chunkZ));
        field.markColumn(chunkX, chunkZ);
        drain(field, source);
        checkNoLight(helper, field, "after the chunk unloaded");
        for (int cascade = 0; cascade < LampLightField.CASCADE_COUNT; cascade++) {
            checkBox(helper, field, source, cascade, chunkX * 16, GROUND_Y - 8, chunkZ * 16, chunkX * 16 + 16,
                    GROUND_Y + 8, chunkZ * 16 + 16, "in the unloaded chunk");
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 400)
    public static void lampFieldFiltersWithoutDarkeningAndBlendsWithoutJumps(GameTestHelper helper) {
        for (int level = 0; level <= LampLightField.BRIGHTEST_LEVEL; level++) {
            float share = level / (float) LampLightField.BRIGHTEST_LEVEL;
            float inverted = LampLightField.levelOfBrightness(LampLightField.brightnessOfLevel(share));
            helper.assertTrue(Math.abs(inverted - share) < 1.0e-6F, "the inverse curve gives " + inverted
                    + " for level " + level);
        }

        int litLevel = 9;
        int edgeX = BASE_X + 8;
        FakeSource source = new FakeSource();
        for (int x = BASE_X - 300; x < edgeX; x++) {
            for (int z = BASE_Z - 300; z < BASE_Z + 300; z++) {
                for (int y = GROUND_Y; y < GROUND_Y + 8; y++) {
                    source.setBlockLight(x, y, z, litLevel);
                }
            }
        }
        double eyeX = BASE_X + 0.5;
        double eyeY = GROUND_Y + 2.5;
        double eyeZ = BASE_Z + 0.5;
        LampLightField field = filledField(source, eyeX, eyeY, eyeZ);
        float litShare = litLevel / (float) LampLightField.BRIGHTEST_LEVEL;
        double[] distances = {3.0, 12.0, 40.0, 57.0, 100.0, 113.0, 200.0, 229.0};
        double[] heights = {GROUND_Y + 0.05, GROUND_Y + 0.5, GROUND_Y + 1.0, GROUND_Y + 2.0};
        for (double distance : distances) {
            for (double height : heights) {
                float level = field.levelAt(eyeX, eyeY, eyeZ, -distance, height - eyeY, distance * 0.3);
                helper.assertTrue(Math.abs(level - litShare) < LEVEL_TOLERANCE, "lit air next to the ground reads "
                        + level + " instead of " + litShare + " at " + distance + " blocks and height " + height);
            }
            float inGround = field.levelAt(eyeX, eyeY, eyeZ, -distance, GROUND_Y - 6.0 - eyeY, distance * 0.3);
            helper.assertTrue(!Float.isNaN(inGround) && inGround == 0.0F, "deep ground reads " + inGround + " at "
                    + distance + " blocks");
        }
        float previous = Float.MAX_VALUE;
        for (double x = edgeX - 4.0; x <= edgeX + 4.0; x += 0.125) {
            float level = field.levelAt(eyeX, eyeY, eyeZ, x - eyeX, 1.5, 0.0);
            helper.assertTrue(level <= previous + 1.0e-6F, "the light rises again to " + level + " at x " + x
                    + " past the edge of the lit air");
            previous = level;
        }
        helper.assertTrue(Math.abs(field.levelAt(eyeX, eyeY, eyeZ, edgeX - 4.0 - eyeX, 1.5, 0.0) - litShare)
                < LEVEL_TOLERANCE && field.levelAt(eyeX, eyeY, eyeZ, edgeX + 1.0 - eyeX, 1.5, 0.0) == 0.0F,
                "the light does not fade from lit air to dark open air within a cell of the edge");

        float[] weights = new float[LampLightField.CASCADE_COUNT + 1];
        for (double offset = 0.0; offset < 600.0; offset += 0.37) {
            checkWeights(helper, field, weights, offset, 0.0, 0.0);
            checkWeights(helper, field, weights, -offset * 0.7, offset * 0.2, offset * 0.7);
            checkWeights(helper, field, weights, offset * 0.1, -offset * 0.3, 0.0);
        }

        FakeSource patterned = new FakeSource();
        for (int x = BASE_X - 80; x < BASE_X + 80; x++) {
            for (int z = BASE_Z - 80; z < BASE_Z + 80; z++) {
                for (int y = GROUND_Y; y < GROUND_Y + 8; y++) {
                    patterned.setBlockLight(x, y, z, 2 + Math.floorMod(3 * x + 5 * z, 13));
                }
            }
        }
        LampLightField moving = filledField(patterned, eyeX, eyeY, eyeZ);
        double snapEyeX = moving.cascade(0).originX() + moving.cascade(0).sizeX() / 2.0
                + LampLightField.RECENTER_DISTANCE;
        moving.follow(snapEyeX - 1.0e-4, eyeY, eyeZ);
        drain(moving, patterned);
        int originBefore = moving.cascade(0).originX();
        float[] before = new float[200];
        int pointIndex = 0;
        for (double offset = -45.0; offset < 45.0 && pointIndex < before.length; offset += 0.5) {
            before[pointIndex++] = moving.levelAt(snapEyeX - 1.0e-4, eyeY, eyeZ, offset + 1.0e-4, 1.25, 4.0);
        }
        moving.follow(snapEyeX + 1.0e-4, eyeY, eyeZ);
        drain(moving, patterned);
        helper.assertTrue(moving.cascade(0).originX() != originBefore, "cascade 0 did not move past its snap");
        pointIndex = 0;
        boolean varies = false;
        for (double offset = -45.0; offset < 45.0 && pointIndex < before.length; offset += 0.5) {
            float after = moving.levelAt(snapEyeX + 1.0e-4, eyeY, eyeZ, offset - 1.0e-4, 1.25, 4.0);
            helper.assertTrue(Math.abs(after - before[pointIndex]) < LEVEL_TOLERANCE, "the light at offset " + offset
                    + " jumps from " + before[pointIndex] + " to " + after + " when cascade 0 moves");
            varies |= pointIndex > 0 && Math.abs(before[pointIndex] - before[0]) > 0.05F;
            pointIndex++;
        }
        helper.assertTrue(varies, "the patterned light reads evenly, so the snap check shows nothing");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void movedAndBudgetedFieldsEqualAFreshBuild(GameTestHelper helper) {
        FakeSource source = scene();
        LampLightField moved = new LampLightField();
        moved.reset(MIN_SECTION, SECTION_COUNT);
        LampLightField budgeted = new LampLightField();
        budgeted.reset(MIN_SECTION, SECTION_COUNT);
        double eyeX = EYE_X;
        double eyeY = EYE_Y;
        double eyeZ = EYE_Z;
        int lateTorchX = BASE_X - 9;
        int lateTorchZ = BASE_Z - 11;
        int unloadedX = Math.floorDiv(BASE_X - 70, 16);
        int unloadedZ = Math.floorDiv(BASE_Z + 30, 16);
        for (int step = 0; step < 48; step++) {
            if (step == 30) {
                eyeX += 70.0;
            } else {
                eyeX += 0.9;
                eyeY += step < 24 ? 0.35 : -0.5;
                eyeZ -= 0.7;
            }
            if (step == 10) {
                placeTorch(source, lateTorchX, GROUND_Y + 2, lateTorchZ, 12, false);
                markTorch(moved, lateTorchX, GROUND_Y + 2, lateTorchZ, 12);
                markTorch(budgeted, lateTorchX, GROUND_Y + 2, lateTorchZ, 12);
            }
            if (step == 20) {
                source.unloaded.add(ChunkPos.asLong(unloadedX, unloadedZ));
                moved.markColumn(unloadedX, unloadedZ);
                budgeted.markColumn(unloadedX, unloadedZ);
            }
            moved.follow(eyeX, eyeY, eyeZ);
            drain(moved, source);
            budgeted.follow(eyeX, eyeY, eyeZ);
            budgeted.process(source, 13, Long.MAX_VALUE);
        }
        drain(budgeted, source);
        LampLightField fresh = filledField(source, eyeX, eyeY, eyeZ);
        for (int cascade = 0; cascade < LampLightField.CASCADE_COUNT; cascade++) {
            checkSameCells(helper, moved.cascade(cascade), fresh.cascade(cascade), "the moved field");
            checkSameCells(helper, budgeted.cascade(cascade), fresh.cascade(cascade), "the budgeted field");
            checkSampledCells(helper, moved, source, cascade, new Random(31L + cascade));
        }
        for (double offset = -300.0; offset < 300.0; offset += 3.7) {
            float movedLevel = moved.levelAt(eyeX, eyeY, eyeZ, offset, -4.0, offset * 0.4);
            float freshLevel = fresh.levelAt(eyeX, eyeY, eyeZ, offset, -4.0, offset * 0.4);
            helper.assertTrue(Math.abs(movedLevel - freshLevel) < LEVEL_TOLERANCE, "the moved field reads "
                    + movedLevel + " instead of " + freshLevel + " at offset " + offset);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void theShadersReadOfTheUniformsMatchesTheField(GameTestHelper helper) {
        float[] sizes = new float[3 * LampLightField.CASCADE_COUNT];
        float[] slotOrigins = new float[3 * LampLightField.CASCADE_COUNT];
        float[] eyes = new float[3 * LampLightField.CASCADE_COUNT];
        float[] cellsPerBlock = new float[3 * LampLightField.CASCADE_COUNT];
        float[] inverseReaches = new float[3 * LampLightField.CASCADE_COUNT];
        LampLightField unplaced = new LampLightField();
        unplaced.reset(MIN_SECTION, SECTION_COUNT);
        int unplacedCount = unplaced.shaderUniforms(EYE_X, EYE_Y, EYE_Z, sizes, slotOrigins, eyes, cellsPerBlock,
                inverseReaches);
        helper.assertTrue(unplacedCount == 0, "a field that never followed gives the shader " + unplacedCount
                + " cascades instead of 0");

        FakeSource patterned = new FakeSource();
        for (int x = BASE_X - SHADER_PATTERN_REACH; x < BASE_X + SHADER_PATTERN_REACH; x++) {
            for (int z = BASE_Z - SHADER_PATTERN_REACH; z < BASE_Z + SHADER_PATTERN_REACH; z++) {
                for (int y = GROUND_Y; y < GROUND_Y + 8; y++) {
                    patterned.setBlockLight(x, y, z, 2 + Math.floorMod(3 * x + 5 * z + y, 13));
                }
            }
        }
        LampLightField field = filledField(patterned, EYE_X, EYE_Y, EYE_Z);
        double eyeX = EYE_X + 37.4;
        double eyeY = EYE_Y + 5.3;
        double eyeZ = EYE_Z - 21.8;
        field.follow(eyeX, eyeY, eyeZ);
        drain(field, patterned);
        int count = field.shaderUniforms(eyeX, eyeY, eyeZ, sizes, slotOrigins, eyes, cellsPerBlock, inverseReaches);
        helper.assertTrue(count == LampLightField.CASCADE_COUNT, "a placed field gives the shader " + count
                + " cascades instead of " + LampLightField.CASCADE_COUNT);
        float[] shares = new float[2];
        Random random = new Random(17L);
        int lit = 0;
        for (int sample = 0; sample < SHADER_SAMPLES; sample++) {
            double reach = Math.pow(random.nextDouble(), 2.0) * 500.0;
            double angle = random.nextDouble() * 2.0 * Math.PI;
            float offsetX = (float) (reach * Math.cos(angle));
            float offsetY = (float) (GROUND_Y - 6.0 + random.nextDouble() * 20.0 - eyeY);
            float offsetZ = (float) (reach * Math.sin(angle));
            float expected = field.levelAt(eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ);
            float read = shaderLevel(field, count, sizes, slotOrigins, eyes, cellsPerBlock, inverseReaches, offsetX,
                    offsetY, offsetZ, shares);
            if (!(Math.abs(read - expected) < LEVEL_TOLERANCE)) {
                helper.fail("the shader reads " + read + " instead of the field's " + expected + " at offset "
                        + offsetX + " " + offsetY + " " + offsetZ);
            }
            if (expected > 0.05F) {
                lit++;
            }
        }
        helper.assertTrue(lit > SHADER_SAMPLES / 10, "only " + lit + " samples are lit, so the check shows little");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void lampShaderConstantsMatchTheField(GameTestHelper helper) {
        Map<String, Double> constants = parseShaderConstants(helper, "lamp_light.glsl");
        checkConstant(helper, "lamp_light.glsl", constants, "LAMP_CASCADE_COUNT", LampLightField.CASCADE_COUNT);
        checkConstant(helper, "lamp_light.glsl", constants, "LAMP_BRIGHTEST", LampLightField.BRIGHTEST);
        checkConstant(helper, "lamp_light.glsl", constants, "LAMP_NO_AIR", LampLightField.NO_AIR);
        checkConstant(helper, "lamp_light.glsl", constants, "LAMP_BAND_START", LampLightField.BAND_START);
        checkConstant(helper, "lamp_light.glsl", constants, "LAMP_BAND_END", LampLightField.BAND_END);
        Map<String, Double> march = parseShaderConstants(helper, "weather_fog_lamp.fsh");
        checkConstant(helper, "weather_fog_lamp.fsh", march, "LAMP_LEAST_TRANSMITTANCE",
                LampFogMarch.LEAST_TRANSMITTANCE);
        checkConstant(helper, "weather_fog_lamp.fsh", march, "LAMP_MAXIMUM_STEPS", LampFogMarch.MAXIMUM_STEPS);
        checkConstant(helper, "weather_fog_lamp.fsh", march, "LAMP_SKY_DISTANCE", LampFogMarch.SKY_DISTANCE);
        checkConstant(helper, "weather_fog_lamp.fsh", march, "LAMP_NO_CROSSING", LampFogMarch.NO_CROSSING);
        checkConstant(helper, "weather_fog_lamp.fsh", march, "LAMP_LIFT_LEVELS", LampFogMarch.LIFT_LEVELS);
        checkConstant(helper, "weather_fog_lamp.fsh", march, "LAMP_LEAST_SCALED_DEPTH",
                LampFogMarch.LEAST_SCALED_DEPTH);
        Map<String, Double> composite = parseShaderConstants(helper, "weather_fog_composite.fsh");
        checkConstant(helper, "weather_fog_composite.fsh", composite, "LAMP_SKY_DISTANCE",
                LampFogMarch.SKY_DISTANCE);
        Map<String, Double> translucentComposite = parseShaderConstants(helper,
                "weather_fog_translucent_composite.fsh");
        checkConstant(helper, "weather_fog_translucent_composite.fsh", translucentComposite, "LAMP_SKY_DISTANCE",
                LampFogMarch.SKY_DISTANCE);
        checkConstant(helper, "weather_fog_translucent_composite.fsh", translucentComposite, "LEAST_ALPHA",
                TranslucentFogShader.LEAST_ALPHA);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void litBoxesHoldEveryLitCell(GameTestHelper helper) {
        FakeSource source = scene();
        LampLightField field = filledField(source, EYE_X, EYE_Y, EYE_Z);
        helper.assertTrue(field.cascade(0).litBox(new int[6]), "the scene lights no cell of cascade 0");
        checkLitBoxes(helper, field, "after a fill");
        field.follow(EYE_X + 37.4, EYE_Y + 5.3, EYE_Z - 21.8);
        drain(field, source);
        checkLitBoxes(helper, field, "after a move");
        source.clearBlockLight();
        markTorch(field, BASE_X + 3, GROUND_Y + 1, BASE_Z + 2, TORCH_LEVEL);
        drain(field, source);
        checkLitBoxes(helper, field, "after the near torch was broken");
        LampLightField dark = filledField(new FakeSource(), EYE_X, EYE_Y, EYE_Z);
        float[] lows = new float[3 * LampLightField.CASCADE_COUNT];
        float[] highs = new float[3 * LampLightField.CASCADE_COUNT];
        helper.assertTrue(!dark.shaderLitBoxes(lows, highs), "a field without light gives the shader a lit box");
        helper.assertTrue(lows[0] > highs[0], "a cascade without light gives the shader the box from " + lows[0]
                + " to " + highs[0]);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void lampMarchOfAnEvenFieldAddsTheLampColorTimesTheFogsOpacity(GameTestHelper helper) {
        LampLightField field = evenField();
        LampFogMarch march = new LampFogMarch();
        helper.assertTrue(march.prepare(field, EVEN_EYE_X, EVEN_EYE_Y, EVEN_EYE_Z), "the even field holds no light");
        float[] lifts = testLifts();
        float[] light = new float[3];
        for (int ray = 0; ray < 16; ray++) {
            double angle = ray * 2.0 * Math.PI / 16.0 + 0.1;
            double directionY = (ray % 3 - 1) * 0.01;
            double horizontal = Math.sqrt(1.0 - directionY * directionY);
            double endDistance = 20.0 + ray * 13.5;
            march.march(horizontal * Math.cos(angle), directionY, horizontal * Math.sin(angle), endDistance, 1.0F,
                    lifts, distance -> 0.01 * distance, light);
            double opacity = 1.0 - Math.exp(-0.01 * endDistance);
            for (int channel = 0; channel < 3; channel++) {
                double expected = EVEN_LIFT[channel] * opacity;
                helper.assertTrue(Math.abs(light[channel] - expected) <= 1.0e-4 * expected + 1.0e-6, "ray " + ray
                        + " to " + endDistance + " blocks adds " + light[channel] + " in channel " + channel
                        + " instead of the lamp color times the fog's opacity, " + expected);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void lampMarchOfAFallingDepthGivesTheFogsOpacityAtTheRaysEnd(GameTestHelper helper) {
        LampLightField field = evenField();
        LampFogMarch march = new LampFogMarch();
        march.prepare(field, EVEN_EYE_X, EVEN_EYE_Y, EVEN_EYE_Z);
        float[] lifts = testLifts();
        float[] light = new float[3];
        march.march(Math.cos(0.7), 0.0, Math.sin(0.7), 200.0, 1.0F, lifts,
                distance -> distance <= 40.0 ? 0.075 * distance : 1.0, light);
        double endOpacity = EVEN_LIFT[0] * (1.0 - Math.exp(-1.0));
        helper.assertTrue(Math.abs(light[0] - endOpacity) <= 1.0e-4 * endOpacity, "a depth that falls from 3 to 1 at "
                + "40 blocks gives " + light[0] + " instead of the lamp color times the opacity at the ray's end, "
                + endOpacity);
        march.march(Math.cos(0.7), 0.0, Math.sin(0.7), 200.0, 1.0F, lifts,
                distance -> distance <= 40.0 ? 0.05 * distance
                        : distance <= 80.0 ? 1.0 : 1.0 + 0.02 * (distance - 80.0), light);
        double risenOpacity = EVEN_LIFT[0] * (1.0 - Math.exp(-3.4));
        helper.assertTrue(Math.abs(light[0] - risenOpacity) <= 1.0e-4 * risenOpacity, "a depth that falls from 2 to "
                + "1 at 40 blocks and rises to 3.4 gives " + light[0] + " instead of the lamp color times the opacity "
                + "at the ray's end, " + risenOpacity);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void lampMarchKeepingToTheLitBoxesMatchesTheWholeMarch(GameTestHelper helper) {
        FakeSource source = scene();
        LampLightField field = filledField(source, EYE_X, EYE_Y, EYE_Z);
        LampFogMarch clipped = new LampFogMarch();
        LampFogMarch whole = new LampFogMarch();
        helper.assertTrue(clipped.prepare(field, EYE_X, EYE_Y, EYE_Z), "the scene holds no light");
        whole.prepare(field, EYE_X, EYE_Y, EYE_Z);
        whole.setClipped(false);
        float[] lifts = testLifts();
        float[] clippedLight = new float[3];
        float[] wholeLight = new float[3];
        double[][] targets = {{BASE_X + 3, GROUND_Y + 1, BASE_Z + 2}, {BASE_X - 22, GROUND_Y + 2, BASE_Z + 13},
                {BASE_X + 200, GROUND_Y + 1, BASE_Z + 150}, {BASE_X - 400, GROUND_Y + 2, BASE_Z - 300},
                {BASE_X + 56, GROUND_Y + 4, BASE_Z + 56}};
        Random random = new Random(29L);
        int lit = 0;
        int clippedSteps = 0;
        int wholeSteps = 0;
        int rays = 400;
        for (int ray = 0; ray < rays; ray++) {
            double directionX;
            double directionY;
            double directionZ;
            double endDistance;
            if (ray % 2 == 0) {
                double[] target = targets[(ray / 2) % targets.length];
                directionX = target[0] + random.nextGaussian() * 4.0 - EYE_X;
                directionY = target[1] + random.nextGaussian() * 2.0 - EYE_Y;
                directionZ = target[2] + random.nextGaussian() * 4.0 - EYE_Z;
                double length = Math.sqrt(directionX * directionX + directionY * directionY
                        + directionZ * directionZ);
                endDistance = length * (0.5 + random.nextDouble());
            } else {
                directionX = random.nextGaussian();
                directionY = -0.35 + 0.45 * random.nextDouble();
                directionZ = random.nextGaussian();
                endDistance = 10.0 + 590.0 * random.nextDouble();
            }
            double length = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
            double unitX = directionX / length;
            double unitY = directionY / length;
            double unitZ = directionZ / length;
            clipped.march(unitX, unitY, unitZ, endDistance, 1.0F, lifts, distance -> 0.004 * distance, clippedLight);
            whole.march(unitX, unitY, unitZ, endDistance, 1.0F, lifts, distance -> 0.004 * distance, wholeLight);
            helper.assertTrue(whole.lastSteps() < LampFogMarch.MAXIMUM_STEPS, "ray " + ray + " ran out of steps "
                    + "walking every piece");
            clippedSteps += clipped.lastSteps();
            wholeSteps += whole.lastSteps();
            float brightest = Math.max(wholeLight[0], Math.max(wholeLight[1], wholeLight[2]));
            if (brightest > 1.0e-3F) {
                lit++;
            }
            for (int channel = 0; channel < 3; channel++) {
                helper.assertTrue(Math.abs(clippedLight[channel] - wholeLight[channel]) <= 1.0e-4 * brightest + 1.0e-6,
                        "ray " + ray + " toward " + unitX + " " + unitY + " " + unitZ + " to " + endDistance
                                + " blocks adds " + clippedLight[channel] + " in channel " + channel + " within the "
                                + "lit boxes and " + wholeLight[channel] + " walking every piece");
            }
        }
        helper.assertTrue(lit > rays / 5, "only " + lit + " rays are lit, so the check shows little");
        helper.assertTrue(clippedSteps < wholeSteps, "keeping to the lit boxes took " + clippedSteps + " pieces, not "
                + "fewer than the " + wholeSteps + " of walking every piece");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 600)
    public static void lampSplitMarchMatchesIndependentMarches(GameTestHelper helper) {
        FakeSource source = scene();
        LampLightField field = filledField(source, EYE_X, EYE_Y, EYE_Z);
        LampFogMarch march = new LampFogMarch();
        helper.assertTrue(march.prepare(field, EYE_X, EYE_Y, EYE_Z), "the scene holds no light");
        float[] lifts = testLifts();
        float[] splitOpaque = new float[3];
        float[] splitTranslucent = new float[3];
        float[] singleOpaque = new float[3];
        float[] singleTranslucent = new float[3];
        double[][] targets = {{BASE_X + 3, GROUND_Y + 1, BASE_Z + 2}, {BASE_X - 22, GROUND_Y + 2, BASE_Z + 13},
                {BASE_X + 200, GROUND_Y + 1, BASE_Z + 150}, {BASE_X - 400, GROUND_Y + 2, BASE_Z - 300},
                {BASE_X + 56, GROUND_Y + 4, BASE_Z + 56}};
        double[] splitFractions = {0.0, 0.15, 0.45, 0.75, 1.0};
        Random random = new Random(43L);
        int litTranslucent = 0;
        int litOpaque = 0;
        for (int ray = 0; ray < 200; ray++) {
            double[] target = targets[ray % targets.length];
            double dx = target[0] + random.nextGaussian() * 4.0 - EYE_X;
            double dy = target[1] + random.nextGaussian() * 2.0 - EYE_Y;
            double dz = target[2] + random.nextGaussian() * 4.0 - EYE_Z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double unitX = dx / length;
            double unitY = dy / length;
            double unitZ = dz / length;
            double endDistance = length * (0.6 + random.nextDouble());
            double splitDistance = endDistance * splitFractions[ray % splitFractions.length];
            LampFogMarch.OpticalDepth depth = distance -> 0.004 * distance;
            march.marchSplit(unitX, unitY, unitZ, endDistance, splitDistance, 1.0F, lifts, depth, splitOpaque,
                    splitTranslucent);
            march.march(unitX, unitY, unitZ, endDistance, 1.0F, lifts, depth, singleOpaque);
            march.march(unitX, unitY, unitZ, splitDistance, 1.0F, lifts, depth, singleTranslucent);
            float opaqueBrightest = Math.max(singleOpaque[0], Math.max(singleOpaque[1], singleOpaque[2]));
            float translucentBrightest = Math.max(singleTranslucent[0],
                    Math.max(singleTranslucent[1], singleTranslucent[2]));
            if (opaqueBrightest > 1.0e-3F) {
                litOpaque++;
            }
            if (translucentBrightest > 1.0e-3F) {
                litTranslucent++;
            }
            for (int channel = 0; channel < 3; channel++) {
                helper.assertTrue(
                        Math.abs(splitTranslucent[channel] - singleTranslucent[channel])
                                <= 1.0e-4 * translucentBrightest + 1.0e-6,
                        "ray " + ray + " translucent split at " + splitDistance + " of " + endDistance
                                + " gives " + splitTranslucent[channel] + " instead of "
                                + singleTranslucent[channel]);
                helper.assertTrue(
                        Math.abs(splitOpaque[channel] - singleOpaque[channel])
                                <= 2.0e-2 * opaqueBrightest + 5.0e-4,
                        "ray " + ray + " opaque split at " + splitDistance + " of " + endDistance
                                + " gives " + splitOpaque[channel] + " instead of " + singleOpaque[channel]);
            }
        }
        helper.assertTrue(litOpaque > 40 && litTranslucent > 20, "too few lit split rays (" + litOpaque
                + " opaque, " + litTranslucent + " translucent)");

        // Verify independent end-opacity scaling under falling optical depth (as inside a shelter):
        LampLightField even = evenField();
        march.prepare(even, EVEN_EYE_X, EVEN_EYE_Y, EVEN_EYE_Z);
        LampFogMarch.OpticalDepth fallingDepth = distance -> distance <= 40.0 ? 0.075 * distance : 1.0;
        double dirX = Math.cos(0.7);
        double dirZ = Math.sin(0.7);
        march.marchSplit(dirX, 0.0, dirZ, 200.0, 20.0, 1.0F, lifts, fallingDepth, splitOpaque, splitTranslucent);
        double expectedOpaque = EVEN_LIFT[0] * (1.0 - Math.exp(-1.0));
        double expectedTranslucent = EVEN_LIFT[0] * (1.0 - Math.exp(-1.5));
        helper.assertTrue(Math.abs(splitOpaque[0] - expectedOpaque) <= 1.0e-4 * expectedOpaque,
                "falling depth split opaque gives " + splitOpaque[0] + " instead of " + expectedOpaque);
        helper.assertTrue(Math.abs(splitTranslucent[0] - expectedTranslucent) <= 1.0e-4 * expectedTranslucent,
                "falling depth split translucent gives " + splitTranslucent[0] + " instead of "
                        + expectedTranslucent);

        // Verify warm split march allocates nothing:
        com.sun.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean counter && counter.isThreadAllocatedMemorySupported()
                && counter.isThreadAllocatedMemoryEnabled() ? counter : null;
        if (threads != null) {
            LampFogMarch.OpticalDepth linear = distance -> 0.005 * distance;
            march.marchSplit(dirX, 0.0, dirZ, 120.0, 35.0, 1.0F, lifts, linear, splitOpaque, splitTranslucent);
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int iteration = 0; iteration < 32; iteration++) {
                march.marchSplit(dirX, 0.0, dirZ, 120.0, 10.0 + iteration * 3.0, 1.0F, lifts, linear, splitOpaque,
                        splitTranslucent);
            }
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            helper.assertTrue(allocated == 0L, "warm marchSplit allocated " + allocated + " bytes");
        }
        helper.succeed();
    }

    /**
     * Compares each cascade's lit box with a scan of every cell of its window.
     */
    private static void checkLitBoxes(GameTestHelper helper, LampLightField field, String when) {
        int[] box = new int[6];
        for (int index = 0; index < field.activeCount(); index++) {
            LampLightCascade cascade = field.cascade(index);
            int[] expected = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1, -1};
            for (int z = 0; z < cascade.sizeZ(); z++) {
                for (int y = 0; y < cascade.sizeY(); y++) {
                    for (int x = 0; x < cascade.sizeX(); x++) {
                        if (LampLightCascade.isLit(cascade.lightAt(cascade.originX() + x, cascade.originY() + y,
                                cascade.originZ() + z))) {
                            expected[0] = Math.min(expected[0], x);
                            expected[1] = Math.min(expected[1], y);
                            expected[2] = Math.min(expected[2], z);
                            expected[3] = Math.max(expected[3], x + 1);
                            expected[4] = Math.max(expected[4], y + 1);
                            expected[5] = Math.max(expected[5], z + 1);
                        }
                    }
                }
            }
            boolean anyLit = expected[3] > 0;
            boolean reported = cascade.litBox(box);
            helper.assertTrue(reported == anyLit, "cascade " + index + " " + when + " reports a lit box " + reported
                    + " while its cells hold light " + anyLit);
            if (anyLit) {
                for (int entry = 0; entry < 6; entry++) {
                    helper.assertTrue(box[entry] == expected[entry], "cascade " + index + " " + when + " has the lit "
                            + "box entry " + entry + " at " + box[entry] + " instead of " + expected[entry]);
                }
            }
        }
    }

    /**
     * A field whose outdoor air holds light 15 in the section layer from GROUND_Y over a square around the eye, so
     * every cascade reads level 1 at the height of the eye.
     */
    private static LampLightField evenField() {
        FakeSource source = new FakeSource();
        int sectionY = Math.floorDiv(GROUND_Y, 16);
        for (int sectionX = Math.floorDiv(BASE_X - EVEN_REACH, 16); sectionX <= Math.floorDiv(BASE_X + EVEN_REACH, 16);
                sectionX++) {
            for (int sectionZ = Math.floorDiv(BASE_Z - EVEN_REACH, 16);
                    sectionZ <= Math.floorDiv(BASE_Z + EVEN_REACH, 16); sectionZ++) {
                source.fillBlockLight(SectionPos.asLong(sectionX, sectionY, sectionZ), LampLightField.BRIGHTEST_LEVEL);
            }
        }
        return filledField(source, EVEN_EYE_X, EVEN_EYE_Y, EVEN_EYE_Z);
    }

    /**
     * Lamp colors that grow evenly from 0 at level 0 to EVEN_LIFT at level 15.
     */
    private static float[] testLifts() {
        float[] lifts = new float[3 * LampFogMarch.LIFT_LEVELS];
        for (int level = 0; level < LampFogMarch.LIFT_LEVELS; level++) {
            for (int channel = 0; channel < 3; channel++) {
                lifts[3 * level + channel] = EVEN_LIFT[channel] * level / (LampFogMarch.LIFT_LEVELS - 1.0F);
            }
        }
        return lifts;
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 400)
    public static void lampFieldAllocatesNothingOnceWarm(GameTestHelper helper) {
        com.sun.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean counter && counter.isThreadAllocatedMemorySupported()
                && counter.isThreadAllocatedMemoryEnabled() ? counter : null;
        if (threads == null) {
            helper.succeed();
            return;
        }
        FakeSource source = scene();
        LampLightField field = filledField(source, EYE_X, EYE_Y, EYE_Z);
        float[] shares = new float[2];
        float[] uniforms = new float[3 * LampLightField.CASCADE_COUNT];
        walk(field, source, shares, uniforms, 0.0);
        long before = threads.getCurrentThreadAllocatedBytes();
        walk(field, source, shares, uniforms, 0.5);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        helper.assertTrue(allocated == 0L, "a warm field allocated " + allocated + " bytes in following, marking, "
                + "reading, sampling and writing the shader's uniforms and lit boxes");
        helper.succeed();
    }

    /**
     * Moves the eye around and back while marking sections, reading them within a budget, sampling and writing the
     * shader's uniforms.
     */
    private static void walk(LampLightField field, FakeSource source, float[] shares, float[] uniforms,
            double shift) {
        for (int step = 0; step < 40; step++) {
            double eyeX = EYE_X + shift + (step < 20 ? step : 40 - step) * 1.3;
            double eyeZ = EYE_Z + (step < 20 ? step : 40 - step) * 0.8;
            field.follow(eyeX, EYE_Y, eyeZ);
            field.markSections(Math.floorDiv(BASE_X, 16), Math.floorDiv(BASE_Z, 16), 3, 5);
            field.process(source, 40, Long.MAX_VALUE);
            field.sharesAt(eyeX, EYE_Y, eyeZ, step * 7.0 - 120.0, -3.0, step * 2.0, shares);
            field.shaderUniforms(eyeX, EYE_Y, eyeZ, uniforms, uniforms, uniforms, uniforms, uniforms);
            field.shaderLitBoxes(uniforms, uniforms);
        }
        drain(field, source);
    }

    /**
     * The level that lampLevelAt in lamp_light.glsl reads from the uniforms, in float as the shader computes it, with
     * each cascade sampled as a linearly filtered texture repeating on every axis.
     */
    private static float shaderLevel(LampLightField field, int count, float[] sizes, float[] slotOrigins,
            float[] eyes, float[] cellsPerBlock, float[] inverseReaches, float offsetX, float offsetY, float offsetZ,
            float[] shares) {
        float bandStart = (float) LampLightField.BAND_START;
        float bandEnd = (float) LampLightField.BAND_END;
        float light = 0.0F;
        float air = 0.0F;
        float remaining = 1.0F;
        for (int cascade = 0; cascade < LampLightField.CASCADE_COUNT; cascade++) {
            if (cascade >= count || remaining <= 0.0F) {
                break;
            }
            int base = 3 * cascade;
            float distance = Math.max(Math.max(Math.abs(offsetX) * inverseReaches[base],
                    Math.abs(offsetY) * inverseReaches[base + 1]), Math.abs(offsetZ) * inverseReaches[base + 2]);
            if (distance >= bandEnd) {
                continue;
            }
            float weight = Math.max(0.0F, Math.min(1.0F, (distance - bandStart) / (bandEnd - bandStart)));
            float inX = Math.max(0.5F, Math.min(sizes[base] - 0.5F, eyes[base] + offsetX * cellsPerBlock[base]));
            float inY = Math.max(0.5F, Math.min(sizes[base + 1] - 0.5F,
                    eyes[base + 1] + offsetY * cellsPerBlock[base + 1]));
            float inZ = Math.max(0.5F, Math.min(sizes[base + 2] - 0.5F,
                    eyes[base + 2] + offsetZ * cellsPerBlock[base + 2]));
            sampleTexture(field.cascade(cascade), (slotOrigins[base] + inX) / sizes[base],
                    (slotOrigins[base + 1] + inY) / sizes[base + 1], (slotOrigins[base + 2] + inZ) / sizes[base + 2],
                    shares);
            light += remaining * (1.0F - weight) * shares[0];
            air += remaining * (1.0F - weight) * shares[1];
            remaining *= weight;
        }
        air += remaining;
        return LampLightField.levelOfBrightness(air > LampLightField.NO_AIR ? light / air : 0.0F);
    }

    /**
     * Samples a cascade's slots at a texture coordinate as GL_LINEAR with GL_REPEAT does.
     */
    private static void sampleTexture(LampLightCascade cascade, float u, float v, float w, float[] shares) {
        float texelX = u * cascade.sizeX() - 0.5F;
        float texelY = v * cascade.sizeY() - 0.5F;
        float texelZ = w * cascade.sizeZ() - 0.5F;
        int lowX = (int) Math.floor(texelX);
        int lowY = (int) Math.floor(texelY);
        int lowZ = (int) Math.floor(texelZ);
        float fractionX = texelX - lowX;
        float fractionY = texelY - lowY;
        float fractionZ = texelZ - lowZ;
        float light = 0.0F;
        float air = 0.0F;
        for (int corner = 0; corner < 8; corner++) {
            int stepX = corner & 1;
            int stepY = corner >> 1 & 1;
            int stepZ = corner >> 2 & 1;
            float weight = (stepX == 1 ? fractionX : 1.0F - fractionX) * (stepY == 1 ? fractionY : 1.0F - fractionY)
                    * (stepZ == 1 ? fractionZ : 1.0F - fractionZ);
            int index = cascade.valueIndex(Math.floorMod(lowX + stepX, cascade.sizeX()),
                    Math.floorMod(lowY + stepY, cascade.sizeY()), Math.floorMod(lowZ + stepZ, cascade.sizeZ()));
            light += weight * LampLightField.fromHalf(cascade.values().get(index));
            air += weight * LampLightField.fromHalf(cascade.values().get(index + 1));
        }
        shares[0] = light;
        shares[1] = air;
    }

    private static LampLightField filledField(FakeSource source, double eyeX, double eyeY, double eyeZ) {
        LampLightField field = new LampLightField();
        field.reset(MIN_SECTION, SECTION_COUNT);
        field.setActiveCount(LampLightField.CASCADE_COUNT);
        field.follow(eyeX, eyeY, eyeZ);
        drain(field, source);
        return field;
    }

    private static void drain(LampLightField field, FakeSource source) {
        field.process(source, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * The fake world: a torch near the eye, a lit closed house, a lit windowed room whose window lights the air
     * outside, two village lights far away, a torch in an unloaded chunk, and a section without sky light data under a
     * section whose lowest layer is half dark.
     */
    private static FakeSource scene() {
        FakeSource source = new FakeSource();
        placeTorch(source, BASE_X + 3, GROUND_Y + 1, BASE_Z + 2, TORCH_LEVEL, false);
        for (int x = BASE_X + 20; x <= BASE_X + 26; x++) {
            for (int y = GROUND_Y; y <= GROUND_Y + 5; y++) {
                for (int z = BASE_Z - 6; z <= BASE_Z; z++) {
                    boolean wall = x == BASE_X + 20 || x == BASE_X + 26 || y == GROUND_Y || y == GROUND_Y + 5
                            || z == BASE_Z - 6 || z == BASE_Z;
                    source.setSkyLight(x, y, z, 0);
                    source.setBlockLight(x, y, z, wall ? 0 : 13);
                }
            }
        }
        for (int x = BASE_X - 30; x <= BASE_X - 24; x++) {
            for (int y = GROUND_Y; y <= GROUND_Y + 5; y++) {
                for (int z = BASE_Z + 10; z <= BASE_Z + 16; z++) {
                    boolean wall = x == BASE_X - 30 || x == BASE_X - 24 || y == GROUND_Y || y == GROUND_Y + 5
                            || z == BASE_Z + 10 || z == BASE_Z + 16;
                    source.setSkyLight(x, y, z, wall ? 0 : 13);
                    source.setBlockLight(x, y, z, wall ? 0 : 12);
                }
            }
        }
        for (int y = GROUND_Y + 1; y <= GROUND_Y + 3; y++) {
            for (int z = BASE_Z + 12; z <= BASE_Z + 14; z++) {
                for (int step = 1; step <= 4; step++) {
                    source.setBlockLight(BASE_X - 24 + step, y, z, 12 - step);
                }
            }
        }
        placeTorch(source, BASE_X + 200, GROUND_Y + 1, BASE_Z + 150, TORCH_LEVEL, false);
        placeTorch(source, BASE_X - 400, GROUND_Y + 2, BASE_Z - 300, TORCH_LEVEL, false);
        int unloadedX = Math.floorDiv(BASE_X + 104, 16);
        int unloadedZ = Math.floorDiv(BASE_Z + 104, 16);
        placeTorch(source, unloadedX * 16 + 8, GROUND_Y + 1, unloadedZ * 16 + 8, 7, true);
        source.unloaded.add(ChunkPos.asLong(unloadedX, unloadedZ));
        int roofX = BASE_X + 48;
        int roofZ = BASE_Z + 48;
        for (int x = roofX; x < roofX + 16; x++) {
            for (int z = roofZ; z < roofZ + 16; z++) {
                source.setSkyLight(x, GROUND_Y + 16, z, x < roofX + 8 ? 0 : 15);
                source.setBlockLight(x, GROUND_Y + 4, z, 10);
            }
        }
        return source;
    }

    /**
     * Gives the air around a point the diamond of block light a torch makes, down to level 1 and not below the ground,
     * staying inside the point's chunk when asked.
     */
    private static void placeTorch(FakeSource source, int torchX, int torchY, int torchZ, int level,
            boolean insideChunk) {
        int chunkX = Math.floorDiv(torchX, 16);
        int chunkZ = Math.floorDiv(torchZ, 16);
        for (int dx = -level; dx <= level; dx++) {
            for (int dy = -level; dy <= level; dy++) {
                for (int dz = -level; dz <= level; dz++) {
                    int light = level - Math.abs(dx) - Math.abs(dy) - Math.abs(dz);
                    int x = torchX + dx;
                    int y = torchY + dy;
                    int z = torchZ + dz;
                    if (light <= 0 || y < GROUND_Y) {
                        continue;
                    }
                    if (insideChunk && (Math.floorDiv(x, 16) != chunkX || Math.floorDiv(z, 16) != chunkZ)) {
                        continue;
                    }
                    source.setBlockLight(x, y, z, Math.max(light, source.blockLightAt(x, y, z)));
                }
            }
        }
    }

    private static void markTorch(LampLightField field, int torchX, int torchY, int torchZ, int level) {
        for (int chunkX = Math.floorDiv(torchX - level, 16); chunkX <= Math.floorDiv(torchX + level, 16); chunkX++) {
            for (int chunkZ = Math.floorDiv(torchZ - level, 16); chunkZ <= Math.floorDiv(torchZ + level, 16);
                    chunkZ++) {
                field.markSections(chunkX, chunkZ, Math.floorDiv(torchY - level, 16),
                        Math.floorDiv(torchY + level, 16));
            }
        }
    }

    /**
     * Checks the cells of a cascade that hold any block of a box, within its window, against sums over their blocks.
     */
    private static void checkBox(GameTestHelper helper, LampLightField field, FakeSource source, int cascade,
            int lowX, int lowY, int lowZ, int highX, int highY, int highZ, String where) {
        LampLightCascade cells = field.cascade(cascade);
        int width = cells.cellWidth();
        int height = cells.cellHeight();
        float[] expected = new float[2];
        for (int cellZ = Math.floorDiv(lowZ, width); cellZ <= Math.floorDiv(highZ - 1, width); cellZ++) {
            for (int cellY = Math.floorDiv(lowY, height); cellY <= Math.floorDiv(highY - 1, height); cellY++) {
                for (int cellX = Math.floorDiv(lowX, width); cellX <= Math.floorDiv(highX - 1, width); cellX++) {
                    if (cells.containsCell(cellX, cellY, cellZ)) {
                        checkCell(helper, cells, source, cellX, cellY, cellZ, expected, where);
                    }
                }
            }
        }
    }

    private static void checkSampledCells(GameTestHelper helper, LampLightField field, FakeSource source,
            int cascade, Random random) {
        LampLightCascade cells = field.cascade(cascade);
        float[] expected = new float[2];
        for (int sample = 0; sample < SAMPLED_CELLS; sample++) {
            int cellX = cells.originX() + random.nextInt(cells.sizeX());
            int cellY = cells.originY() + random.nextInt(cells.sizeY());
            int cellZ = cells.originZ() + random.nextInt(cells.sizeZ());
            checkCell(helper, cells, source, cellX, cellY, cellZ, expected, "at a sampled cell");
        }
    }

    private static void checkCell(GameTestHelper helper, LampLightCascade cells, FakeSource source, int cellX,
            int cellY, int cellZ, float[] expected, String where) {
        expectedShares(source, cells, cellX, cellY, cellZ, expected);
        float light = LampLightField.fromHalf(cells.lightAt(cellX, cellY, cellZ));
        float air = LampLightField.fromHalf(cells.airAt(cellX, cellY, cellZ));
        if (!near(light, expected[0]) || !near(air, expected[1])) {
            helper.fail("cascade " + cells.index() + " holds " + light + " " + air + " instead of " + expected[0] + " "
                    + expected[1] + " at cell " + cellX + " " + cellY + " " + cellZ + " " + where);
        }
    }

    /**
     * The light share and the air share of a cell summed over its blocks, dark open air in unloaded chunks and outside
     * the level.
     */
    private static void expectedShares(FakeSource source, LampLightCascade cells, int cellX, int cellY, int cellZ,
            float[] result) {
        int width = cells.cellWidth();
        int height = cells.cellHeight();
        int lowX = cellX * width;
        int lowY = cellY * height;
        int lowZ = cellZ * width;
        if (lowY < MIN_Y || lowY >= TOP_Y || source.unloaded.contains(ChunkPos.asLong(Math.floorDiv(lowX, 16),
                Math.floorDiv(lowZ, 16)))) {
            result[0] = 0.0F;
            result[1] = 1.0F;
            return;
        }
        double light = 0.0;
        double air = 0.0;
        for (int y = lowY; y < lowY + height; y++) {
            for (int z = lowZ; z < lowZ + width; z++) {
                for (int x = lowX; x < lowX + width; x++) {
                    int sky = source.skyLightAt(x, y, z);
                    boolean outdoor = cells.index() < LampLightField.FIRST_FAR_CASCADE ? sky > 0
                            : sky == LampLightField.OPEN_SKY_LIGHT;
                    if (outdoor) {
                        light += LampLightField.brightnessOfLevel(source.blockLightAt(x, y, z)
                                / (float) LampLightField.BRIGHTEST_LEVEL);
                        air += 1.0;
                    }
                }
            }
        }
        double blocks = width * height * width;
        result[0] = (float) (light / blocks);
        result[1] = (float) (air / blocks);
    }

    private static boolean near(float actual, float expected) {
        return Math.abs(actual - expected) <= Math.abs(expected) * RELATIVE_TOLERANCE + ABSOLUTE_TOLERANCE;
    }

    /**
     * Checks that the window's cells take every slot once and that each section's cells share one brick.
     */
    private static void checkSlots(GameTestHelper helper, LampLightCascade cells) {
        int count = cells.sizeX() * cells.sizeY() * cells.sizeZ();
        boolean[] taken = new boolean[count];
        for (int cellZ = cells.originZ(); cellZ < cells.originZ() + cells.sizeZ(); cellZ++) {
            for (int cellY = cells.originY(); cellY < cells.originY() + cells.sizeY(); cellY++) {
                for (int cellX = cells.originX(); cellX < cells.originX() + cells.sizeX(); cellX++) {
                    int slot = cells.valueIndex(cells.slotX(cellX), cells.slotY(cellY), cells.slotZ(cellZ))
                            / LampLightCascade.CHANNELS;
                    if (taken[slot]) {
                        helper.fail("cascade " + cells.index() + " gives two cells the slot " + slot);
                    }
                    taken[slot] = true;
                    int sectionX = Math.floorDiv(cellX * cells.cellWidth(), 16);
                    int sectionY = Math.floorDiv(cellY * cells.cellHeight(), 16);
                    int sectionZ = Math.floorDiv(cellZ * cells.cellWidth(), 16);
                    int brickX = cells.slotX(sectionX * cells.brickWidth()) / cells.brickWidth();
                    int brickY = cells.slotY(sectionY * cells.brickHeight()) / cells.brickHeight();
                    int brickZ = cells.slotZ(sectionZ * cells.brickWidth()) / cells.brickWidth();
                    if (cells.slotX(cellX) / cells.brickWidth() != brickX
                            || cells.slotY(cellY) / cells.brickHeight() != brickY
                            || cells.slotZ(cellZ) / cells.brickWidth() != brickZ) {
                        helper.fail("cascade " + cells.index() + " puts cell " + cellX + " " + cellY + " " + cellZ
                                + " outside its section's brick");
                    }
                }
            }
        }
    }

    private static void checkNoLight(GameTestHelper helper, LampLightField field, String when) {
        for (int cascade = 0; cascade < LampLightField.CASCADE_COUNT; cascade++) {
            LampLightCascade cells = field.cascade(cascade);
            int count = cells.sizeX() * cells.sizeY() * cells.sizeZ();
            for (int cell = 0; cell < count; cell++) {
                if (cells.values().get(cell * LampLightCascade.CHANNELS) != 0) {
                    helper.fail("cascade " + cascade + " keeps light in slot " + cell + " " + when);
                }
            }
        }
    }

    /**
     * Checks that two cascades hold the same values for every cell in both windows.
     */
    private static void checkSameCells(GameTestHelper helper, LampLightCascade actual, LampLightCascade expected,
            String what) {
        int lowX = Math.max(actual.originX(), expected.originX());
        int lowY = Math.max(actual.originY(), expected.originY());
        int lowZ = Math.max(actual.originZ(), expected.originZ());
        int highX = Math.min(actual.originX() + actual.sizeX(), expected.originX() + expected.sizeX());
        int highY = Math.min(actual.originY() + actual.sizeY(), expected.originY() + expected.sizeY());
        int highZ = Math.min(actual.originZ() + actual.sizeZ(), expected.originZ() + expected.sizeZ());
        helper.assertTrue(highX - lowX > actual.sizeX() / 2 && highZ - lowZ > actual.sizeZ() / 2,
                what + " cascade " + actual.index() + " lies far from the fresh one");
        for (int cellZ = lowZ; cellZ < highZ; cellZ++) {
            for (int cellY = lowY; cellY < highY; cellY++) {
                for (int cellX = lowX; cellX < highX; cellX++) {
                    if (actual.lightAt(cellX, cellY, cellZ) != expected.lightAt(cellX, cellY, cellZ)
                            || actual.airAt(cellX, cellY, cellZ) != expected.airAt(cellX, cellY, cellZ)) {
                        helper.fail(what + " cascade " + actual.index() + " holds "
                                + LampLightField.fromHalf(actual.lightAt(cellX, cellY, cellZ)) + " "
                                + LampLightField.fromHalf(actual.airAt(cellX, cellY, cellZ)) + " instead of "
                                + LampLightField.fromHalf(expected.lightAt(cellX, cellY, cellZ)) + " "
                                + LampLightField.fromHalf(expected.airAt(cellX, cellY, cellZ)) + " at cell " + cellX
                                + " " + cellY + " " + cellZ);
                    }
                }
            }
        }
    }

    private static void checkWeights(GameTestHelper helper, LampLightField field, float[] weights, double offsetX,
            double offsetY, double offsetZ) {
        field.weightsAt(offsetX, offsetY, offsetZ, weights);
        float sum = 0.0F;
        int blended = 0;
        for (int index = 0; index < weights.length; index++) {
            helper.assertTrue(weights[index] >= 0.0F, "a weight is negative at offset " + offsetX + " " + offsetY + " "
                    + offsetZ);
            sum += weights[index];
            if (weights[index] > 0.0F) {
                blended++;
            }
        }
        helper.assertTrue(Math.abs(sum - 1.0F) < 1.0e-6F && blended <= 2, "the weights sum to " + sum + " over "
                + blended + " cascades at offset " + offsetX + " " + offsetY + " " + offsetZ);
    }

    private static Map<String, Double> parseShaderConstants(GameTestHelper helper, String shaderFile) {
        String resourcePath = "/assets/frostedheart/shaders/" + shaderFile;
        String text;
        try (InputStream stream = LampLightField.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                helper.fail("Shader resource " + resourcePath + " was not found on the classpath");
                return Map.of();
            }
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            helper.fail("Failed to read shader resource " + resourcePath + ": " + exception.getMessage());
            return Map.of();
        }
        Map<String, Double> constants = new HashMap<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = GLSL_CONST_PATTERN.matcher(line);
            if (matcher.find()) {
                try {
                    constants.put(matcher.group(1), Double.parseDouble(matcher.group(2).trim()));
                } catch (NumberFormatException ignored) {
                    // Constants that are expressions are not compared.
                }
            }
        }
        return constants;
    }

    private static void checkConstant(GameTestHelper helper, String shaderFile, Map<String, Double> constants,
            String name, double expected) {
        Double actual = constants.get(name);
        helper.assertTrue(actual != null, shaderFile + " is missing const " + name);
        helper.assertTrue(Math.abs(actual - expected) <= Math.abs(expected) * 1.0e-6, shaderFile + " defines "
                + name + " = " + actual + " instead of Java value " + expected);
    }

    /**
     * Light data by section, with ground below GROUND_Y whose sky light is 0 in every section without data of its own,
     * and no data above it unless set.
     */
    private static final class FakeSource implements LampLightField.LightSource {
        private static final DataLayer DARK_GROUND = new DataLayer(0);

        private final Long2ObjectOpenHashMap<DataLayer> blockLayers = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<DataLayer> skyLayers = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet unloaded = new LongOpenHashSet();

        private static long key(int x, int y, int z) {
            return SectionPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        }

        private void setBlockLight(int x, int y, int z, int level) {
            blockLayers.computeIfAbsent(key(x, y, z), ignored -> new DataLayer()).set(x & 15, y & 15, z & 15, level);
        }

        private void setSkyLight(int x, int y, int z, int level) {
            skyLayers.computeIfAbsent(key(x, y, z), ignored -> new DataLayer(LampLightField.OPEN_SKY_LIGHT))
                    .set(x & 15, y & 15, z & 15, level);
        }

        private void clearBlockLight() {
            blockLayers.clear();
        }

        private void fillBlockLight(long sectionKey, int level) {
            DataLayer layer = new DataLayer();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        layer.set(x, y, z, level);
                    }
                }
            }
            blockLayers.put(sectionKey, layer);
        }

        private int blockLightAt(int x, int y, int z) {
            DataLayer layer = blockLayers.get(key(x, y, z));
            return layer != null ? layer.get(x & 15, y & 15, z & 15) : 0;
        }

        /**
         * The sky light of a block by vanilla's rule for sections without data.
         */
        private int skyLightAt(int x, int y, int z) {
            int sectionX = Math.floorDiv(x, 16);
            int sectionZ = Math.floorDiv(z, 16);
            DataLayer own = skyLight(key(x, y, z));
            if (own != null) {
                return own.get(x & 15, y & 15, z & 15);
            }
            for (int sectionY = Math.floorDiv(y, 16) + 1; sectionY <= MIN_SECTION + SECTION_COUNT; sectionY++) {
                DataLayer above = skyLight(SectionPos.asLong(sectionX, sectionY, sectionZ));
                if (above != null) {
                    return above.get(x & 15, 0, z & 15);
                }
            }
            return LampLightField.OPEN_SKY_LIGHT;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return !unloaded.contains(ChunkPos.asLong(chunkX, chunkZ));
        }

        @Override
        public boolean hasSkyLight() {
            return true;
        }

        @Nullable
        @Override
        public DataLayer blockLight(long sectionKey) {
            return blockLayers.get(sectionKey);
        }

        @Nullable
        @Override
        public DataLayer skyLight(long sectionKey) {
            DataLayer layer = skyLayers.get(sectionKey);
            if (layer != null) {
                return layer;
            }
            return SectionPos.y(sectionKey) < Math.floorDiv(GROUND_Y, 16) ? DARK_GROUND : null;
        }
    }
}
