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
 * Checks the layout of the share volume of the weather fog: that its atlas holds every slice, that the margin of the
 * volume covers the cells the walk visits at the largest field radius, the cells that contains admits at its corners,
 * the atlas texels that atlasX and atlasY give for cells by hand, and that every cell of the volume has a texel of its
 * own inside the atlas.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogShareVolumeGameTests {

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void atlasHoldsEverySlice(GameTestHelper helper) {
        int width = FogShareVolume.SIDE * FogShareVolume.TILES_ACROSS;
        helper.assertTrue(width == FogShareVolume.ATLAS_WIDTH, "The " + FogShareVolume.SIDE + " texels of a tile at the "
                + FogShareVolume.TILES_ACROSS + " tiles across give the width " + width + " instead of "
                + FogShareVolume.ATLAS_WIDTH);
        int height = FogShareVolume.SIDE * FogShareVolume.TILES_DOWN;
        helper.assertTrue(height == FogShareVolume.ATLAS_HEIGHT, "The " + FogShareVolume.SIDE + " texels of a tile at "
                + FogShareVolume.TILES_DOWN + " tiles down give the height " + height + " instead of "
                + FogShareVolume.ATLAS_HEIGHT);
        int tileCount = FogShareVolume.TILES_ACROSS * FogShareVolume.TILES_DOWN;
        helper.assertTrue(tileCount >= FogShareVolume.SIDE, "The " + FogShareVolume.SIDE + " slices of the volume lie "
                + "at the " + tileCount + " tiles of the atlas, which holds too few of them");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void originHoldsTheLargestRegion(GameTestHelper helper) {
        double largestRadius = SkySetting.FOG_FIELD_RADIUS.maximum();
        int largestHalfWidth = (int) Math.ceil(largestRadius - SnowFogWalk.REGION_MARGIN);
        int largestOffset = largestHalfWidth + 1;
        helper.assertTrue(largestOffset <= FogShareVolume.ORIGIN, "The walk reaches the offset " + largestOffset
                + " at the largest field radius " + largestRadius + ", which the origin " + FogShareVolume.ORIGIN
                + " of the volume does not hold");
        int highestOffset = FogShareVolume.SIDE - FogShareVolume.ORIGIN - 1;
        helper.assertTrue(largestOffset <= highestOffset, "The walk reaches the offset " + largestOffset
                + " at the largest field radius " + largestRadius + ", which the volume holds up to the offset "
                + highestOffset);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void containsHoldsTheCorners(GameTestHelper helper) {
        int lowestInside = -FogShareVolume.ORIGIN;
        int highestInside = FogShareVolume.SIDE - FogShareVolume.ORIGIN - 1;
        int lowestOutside = lowestInside - 1;
        int highestOutside = highestInside + 1;
        helper.assertTrue(FogShareVolume.contains(0, 0, 0), "The cell (0, 0, 0) of the eye's block is outside the "
                + "volume");
        assertContains(helper, lowestInside, 0, 0, true);
        assertContains(helper, 0, lowestInside, 0, true);
        assertContains(helper, 0, 0, lowestInside, true);
        assertContains(helper, highestInside, 0, 0, true);
        assertContains(helper, 0, highestInside, 0, true);
        assertContains(helper, 0, 0, highestInside, true);
        assertContains(helper, lowestOutside, 0, 0, false);
        assertContains(helper, 0, lowestOutside, 0, false);
        assertContains(helper, 0, 0, lowestOutside, false);
        assertContains(helper, highestOutside, 0, 0, false);
        assertContains(helper, 0, highestOutside, 0, false);
        assertContains(helper, 0, 0, highestOutside, false);
        assertContains(helper, lowestInside, lowestInside, lowestInside, true);
        assertContains(helper, highestInside, highestInside, highestInside, true);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void atlasTexelsFollowTheTiles(GameTestHelper helper) {
        assertAtlas(helper, 0, 0, 0, 40, 360);
        assertAtlas(helper, -40, -40, -40, 0, 0);
        assertAtlas(helper, 39, 39, 39, 799, 639);
        assertAtlas(helper, 5, -3, 7, 605, 287);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void everyCellHasItsOwnTexel(GameTestHelper helper) {
        int width = FogShareVolume.ATLAS_WIDTH;
        int height = FogShareVolume.ATLAS_HEIGHT;
        boolean[] texels = new boolean[width * height];
        int first = -FogShareVolume.ORIGIN;
        int last = FogShareVolume.SIDE - FogShareVolume.ORIGIN - 1;
        int cellCount = 0;
        for (int y = first; y <= last; y++) {
            for (int z = first; z <= last; z++) {
                for (int x = first; x <= last; x++) {
                    int atlasX = FogShareVolume.atlasX(x, y);
                    int atlasY = FogShareVolume.atlasY(z, y);
                    if (!(atlasX >= 0 && atlasX < width && atlasY >= 0 && atlasY < height)) {
                        helper.fail("The cell (" + x + ", " + y + ", " + z + ") has the atlas texel (" + atlasX + ", "
                                + atlasY + ") outside the " + width + " by " + height + " atlas");
                    }
                    if (texels[atlasX + atlasY * width]) {
                        helper.fail("The cell (" + x + ", " + y + ", " + z + ") has the atlas texel (" + atlasX
                                + ", " + atlasY + "), which another cell of the volume already has");
                    }
                    texels[atlasX + atlasY * width] = true;
                    cellCount++;
                }
            }
        }
        int texelCount = width * height;
        helper.assertTrue(cellCount == texelCount, "The " + cellCount + " cells of the volume fill " + texelCount
                + " texels of the atlas, where each of them needs one of its own");
        helper.succeed();
    }

    /**
     * Checks whether a cell is inside the volume against the expected answer.
     */
    private static void assertContains(GameTestHelper helper, int x, int y, int z, boolean expected) {
        boolean contains = FogShareVolume.contains(x, y, z);
        helper.assertTrue(contains == expected, "The cell (" + x + ", " + y + ", " + z + ") is inside the volume "
                + contains + " instead of " + expected);
    }

    /**
     * Checks the atlas texel of a cell against the texel it should have.
     */
    private static void assertAtlas(GameTestHelper helper, int x, int y, int z, int expectedX, int expectedY) {
        int atlasX = FogShareVolume.atlasX(x, y);
        int atlasY = FogShareVolume.atlasY(z, y);
        helper.assertTrue(atlasX == expectedX, "The cell (" + x + ", " + y + ", " + z + ") has the atlas x " + atlasX
                + " instead of " + expectedX);
        helper.assertTrue(atlasY == expectedY, "The cell (" + x + ", " + y + ", " + z + ") has the atlas y " + atlasY
                + " instead of " + expectedY);
    }
}
