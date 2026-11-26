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

import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.content.climate.render.fog.FlakeShapeSet.ShapeCategory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * Checks the snowflake shapes that FlakeShapeTexture uploads: the mix of kinds, the empty margin, the coverage that
 * sizes each tile, and that the same seed gives the same shapes.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FlakeShapeGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    @GameTest(template = "phase0a_empty", timeoutTicks = 200)
    public static void flakeShapesAreVariedSnowInsideTheirTiles(GameTestHelper helper) {
        long start = System.nanoTime();
        FlakeShapeSet shapes = FlakeShapeGenerator.generate(FlakeShapeGenerator.DEFAULT_SEED);
        double seconds = (System.nanoTime() - start) * 1.0e-9;
        LOGGER.info("Generated {} flake shapes of {} pixels in {} seconds", shapes.count(), shapes.size(), seconds);
        helper.assertTrue(shapes.count() == SnowFlakes.SHAPE_COUNT && shapes.size() == SnowFlakes.SHAPE_SIZE,
                "the generator made " + shapes.count() + " shapes of " + shapes.size() + " pixels instead of "
                        + SnowFlakes.SHAPE_COUNT + " of " + SnowFlakes.SHAPE_SIZE);
        helper.assertTrue(seconds < 5.0, "the shapes took " + seconds + " seconds to generate");

        int[] categoryCounts = new int[ShapeCategory.values().length];
        int size = shapes.size();
        int margin = size / 8;
        double halfSide = 0.5 * size;
        for (int shape = 0; shape < shapes.count(); shape++) {
            categoryCounts[shapes.category(shape).ordinal()]++;
            long sum = 0L;
            int brightest = 0;
            double farthest = 0.0;
            for (int pixelY = 0; pixelY < size; pixelY++) {
                for (int pixelX = 0; pixelX < size; pixelX++) {
                    int value = shapes.pixel(shape, pixelX, pixelY);
                    if (value == 0) {
                        continue;
                    }
                    if (pixelX < margin || pixelX >= size - margin || pixelY < margin || pixelY >= size - margin) {
                        helper.fail("shape " + shape + " has " + value + " at " + pixelX + ", " + pixelY
                                + " in its margin");
                        return;
                    }
                    sum += value;
                    brightest = Math.max(brightest, value);
                    farthest = Math.max(farthest, Math.hypot(pixelX + 0.5 - halfSide, pixelY + 0.5 - halfSide));
                }
            }
            double coverage = sum / (255.0 * size * size);
            helper.assertTrue(Math.abs(coverage - shapes.coverage(shape)) < 1.0e-12, "shape " + shape
                    + " reports the coverage " + shapes.coverage(shape) + " but covers " + coverage);
            helper.assertTrue(coverage > 0.05 && coverage < 0.4, "shape " + shape + " (" + shapes.category(shape)
                    + ") covers " + coverage + " of its tile");
            helper.assertTrue(brightest >= 200, "the densest knot of shape " + shape + " is only " + brightest);
            helper.assertTrue(farthest <= SnowFlakes.SHAPE_REACH * halfSide, "shape " + shape + " reaches "
                    + farthest + " pixels from the middle of its tile, past the reach of its quad");
        }
        int[] expectedCounts = {14, 6, 6, 2, 2, 2};
        helper.assertTrue(Arrays.equals(categoryCounts, expectedCounts), "the shapes are "
                + Arrays.toString(categoryCounts) + " of " + Arrays.toString(ShapeCategory.values()) + " instead of "
                + Arrays.toString(expectedCounts));

        FlakeShapeSet again = FlakeShapeGenerator.generate(FlakeShapeGenerator.DEFAULT_SEED);
        helper.assertTrue(Arrays.equals(shapes.data(), again.data()), "the same seed gave other shapes");
        FlakeShapeSet other = FlakeShapeGenerator.generate(FlakeShapeGenerator.DEFAULT_SEED + 1L);
        helper.assertFalse(Arrays.equals(shapes.data(), other.data()), "another seed gave the same shapes");
        helper.succeed();
    }
}
