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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the face masks of block states against the shapes of vanilla blocks and the costs of crossing a face: the
 * coverage costs, the reserved mask sets of full and empty blocks, the open faces of leaves, the masks of panes,
 * fences, doors, slabs, stairs, snow layers and carpets, the cost of a face and its symmetry, the effect of the leaf
 * porosity, the sharing and caching of mask sets, and the allocation of the hot lookups.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowFacesGameTests {
    private static final int HOT_ITERATIONS = 100000;
    private static final int ALLOCATION_READS = 20;
    private static final int ALLOCATION_WARM_PASSES = 8;
    private static final int COMBINATION_COUNT = 16;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void coverageCostsFollowTheFormula(GameTestHelper helper) {
        int[] expectedCosts = {0, 2, 3, 5, 7, 9, 11, 14, 17, 20, 24, 28, 33, 40, 50, 67, SnowFaces.CLOSED_COST};
        for (int coveredSubSquares = 0; coveredSubSquares <= 16; coveredSubSquares++) {
            int cost = SnowFaces.coverageCost(coveredSubSquares);
            int expectedCost = expectedCosts[coveredSubSquares];
            helper.assertTrue(cost == expectedCost, "The coverage cost of " + coveredSubSquares + " sub-squares is "
                    + cost + " instead of " + expectedCost);
        }
        helper.assertTrue(rejectsCoverage(-1), "The coverage cost of -1 does not throw an IllegalArgumentException");
        helper.assertTrue(rejectsCoverage(17), "The coverage cost of 17 does not throw an IllegalArgumentException");
        assertStaticLeafCost(helper, 0.3, 29);
        assertStaticLeafCost(helper, 0.5, 17);
        assertStaticLeafCost(helper, 1.0, 0);
        assertStaticLeafCost(helper, 1.5, 0);
        assertStaticLeafCost(helper, 0.0, SnowFaces.CLOSED_COST);
        assertStaticLeafCost(helper, -1.0, SnowFaces.CLOSED_COST);
        assertStaticLeafCost(helper, Double.NaN, SnowFaces.CLOSED_COST);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fullAndEmptyBlocksMapToTheReservedSets(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        helper.assertTrue(SnowFaces.FACE_COUNT == 6, "SnowFaces has " + SnowFaces.FACE_COUNT + " faces instead of 6");
        BlockState waterloggedBottomSlab = oakSlab(SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, true);
        BlockState waterloggedLeaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.WATERLOGGED, true);
        BlockState waterloggedPost = fence(false, false, false, false)
                .setValue(CrossCollisionBlock.WATERLOGGED, true);
        assertMaskSet(helper, faces, Blocks.AIR.defaultBlockState(), SnowFaces.EMPTY_MASK_SET);
        assertMaskSet(helper, faces, Blocks.CAVE_AIR.defaultBlockState(), SnowFaces.EMPTY_MASK_SET);
        assertMaskSet(helper, faces, Blocks.STONE.defaultBlockState(), SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, Blocks.GLASS.defaultBlockState(), SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, Blocks.WATER.defaultBlockState(), SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, Blocks.LAVA.defaultBlockState(), SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, snowLayer(8), SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, waterloggedBottomSlab, SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, waterloggedLeaves, SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, waterloggedPost, SnowFaces.FULL_MASK_SET);
        assertMaskSet(helper, faces, oakSlab(SlabType.DOUBLE), SnowFaces.FULL_MASK_SET);
        helper.assertTrue(faces.closesAllFaces(SnowFaces.FULL_MASK_SET), "The full mask set does not close all faces");
        helper.assertFalse(faces.closesAllFaces(SnowFaces.EMPTY_MASK_SET), "The empty mask set closes all faces");
        helper.assertFalse(faces.closesAllFaces(SnowFaces.LEAVES_MASK_SET), "The leaves mask set closes all faces");
        int bottomSlabMaskSet = faces.maskSetOf(oakSlab(SlabType.BOTTOM));
        helper.assertFalse(faces.closesAllFaces(bottomSlabMaskSet), "The mask set " + bottomSlabMaskSet
                + " of a bottom oak slab closes all faces");
        for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
            int fullMask = faces.faceMask(SnowFaces.FULL_MASK_SET, face);
            helper.assertTrue(fullMask == 0xFFFF, "The " + faceName(face) + " face of the full mask set is 0x"
                    + Integer.toHexString(fullMask) + " instead of 0x" + Integer.toHexString(0xFFFF));
            int emptyMask = faces.faceMask(SnowFaces.EMPTY_MASK_SET, face);
            helper.assertTrue(emptyMask == 0, "The " + faceName(face) + " face of the empty mask set is 0x"
                    + Integer.toHexString(emptyMask) + " instead of 0x" + Integer.toHexString(0));
        }
        helper.assertTrue(faces.maskSetCount() >= 3, "A new instance has " + faces.maskSetCount()
                + " mask sets, fewer than the 3 reserved ones");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void leavesAreOpenAndFlagged(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        BlockState oakLeaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState spruceLeaves = Blocks.SPRUCE_LEAVES.defaultBlockState();
        assertMaskSet(helper, faces, oakLeaves, SnowFaces.LEAVES_MASK_SET);
        assertMaskSet(helper, faces, spruceLeaves, SnowFaces.LEAVES_MASK_SET);
        assertMasks(helper, faces, oakLeaves, 0, 0, 0, 0, 0, 0);
        assertMasks(helper, faces, spruceLeaves, 0, 0, 0, 0, 0, 0);
        helper.assertTrue(faces.isLeaves(SnowFaces.LEAVES_MASK_SET), "The leaves mask set is not flagged as leaves");
        helper.assertFalse(faces.isLeaves(SnowFaces.EMPTY_MASK_SET), "The empty mask set is flagged as leaves");
        helper.assertFalse(faces.isLeaves(SnowFaces.FULL_MASK_SET), "The full mask set is flagged as leaves");
        int bottomSlabMaskSet = faces.maskSetOf(oakSlab(SlabType.BOTTOM));
        helper.assertFalse(faces.isLeaves(bottomSlabMaskSet), "The mask set " + bottomSlabMaskSet
                + " of a bottom oak slab is flagged as leaves");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void paneMasksFollowTheirConnections(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        for (int combination = 0; combination < COMBINATION_COUNT; combination++) {
            boolean north = (combination & 1) != 0;
            boolean east = (combination & 2) != 0;
            boolean south = (combination & 4) != 0;
            boolean west = (combination & 8) != 0;
            int northAndSouthMask = (west ? 0x3333 : 0) | (east ? 0xCCCC : 0);
            int westAndEastMask = (north ? 0x3333 : 0) | (south ? 0xCCCC : 0);
            assertMasks(helper, faces, pane(north, east, south, west), 0, 0, northAndSouthMask, northAndSouthMask,
                    westAndEastMask, westAndEastMask);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fenceMasksFollowTheirConnections(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        for (int combination = 0; combination < COMBINATION_COUNT; combination++) {
            boolean north = (combination & 1) != 0;
            boolean east = (combination & 2) != 0;
            boolean south = (combination & 4) != 0;
            boolean west = (combination & 8) != 0;
            // The post covers a face only when the arms through it leave a run that does not touch the other face:
            boolean postCoversNorth = north || !south;
            boolean postCoversSouth = south || !north;
            boolean postCoversWest = west || !east;
            boolean postCoversEast = east || !west;
            int northMask = fenceSideMask(west, east, postCoversNorth);
            int southMask = fenceSideMask(west, east, postCoversSouth);
            int westMask = fenceSideMask(north, south, postCoversWest);
            int eastMask = fenceSideMask(north, south, postCoversEast);
            int horizontalMask = (north && west ? 0x0020 : 0) | (north && east ? 0x0040 : 0)
                    | (south && west ? 0x0200 : 0) | (south && east ? 0x0400 : 0);
            assertMasks(helper, faces, fence(north, east, south, west), horizontalMask, horizontalMask,
                    northMask, southMask, westMask, eastMask);
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void doorMasksFollowTheirLeaf(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        BlockState northClosedLeft = door(Direction.NORTH, false, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER);
        BlockState northClosedLeftUpper = door(Direction.NORTH, false, DoorHingeSide.LEFT, DoubleBlockHalf.UPPER);
        BlockState northOpenLeft = door(Direction.NORTH, true, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER);
        BlockState northOpenRight = door(Direction.NORTH, true, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER);
        BlockState eastClosedLeft = door(Direction.EAST, false, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER);
        assertMasks(helper, faces, northClosedLeft, 0xF000, 0xF000, 0, 0xFFFF, 0x8888, 0x8888);
        assertMasks(helper, faces, northClosedLeftUpper, 0xF000, 0xF000, 0, 0xFFFF, 0x8888, 0x8888);
        assertMasks(helper, faces, northOpenLeft, 0x1111, 0x1111, 0x1111, 0x1111, 0xFFFF, 0);
        assertMasks(helper, faces, northOpenRight, 0x8888, 0x8888, 0x8888, 0x8888, 0, 0xFFFF);
        assertMasks(helper, faces, eastClosedLeft, 0x1111, 0x1111, 0x1111, 0x1111, 0xFFFF, 0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void slabAndStairMasks(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        BlockState bottomSlab = oakSlab(SlabType.BOTTOM);
        BlockState topSlab = oakSlab(SlabType.TOP);
        BlockState northBottomStairs = oakStairs(Direction.NORTH, Half.BOTTOM);
        BlockState eastTopStairs = oakStairs(Direction.EAST, Half.TOP);
        assertMasks(helper, faces, bottomSlab, 0xFFFF, 0, 0x00FF, 0x00FF, 0x00FF, 0x00FF);
        assertMasks(helper, faces, topSlab, 0, 0xFFFF, 0xFF00, 0xFF00, 0xFF00, 0xFF00);
        assertMasks(helper, faces, northBottomStairs, 0xFFFF, 0x00FF, 0xFFFF, 0x00FF, 0x33FF, 0x33FF);
        assertMasks(helper, faces, eastTopStairs, 0xCCCC, 0xFFFF, 0xFFCC, 0xFFCC, 0xFF00, 0xFFFF);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void snowLayerAndCarpetMasks(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        assertMasks(helper, faces, snowLayer(1), 0xFFFF, 0, 0, 0, 0, 0);
        assertMasks(helper, faces, snowLayer(2), 0xFFFF, 0, 0x000F, 0x000F, 0x000F, 0x000F);
        assertMasks(helper, faces, snowLayer(7), 0xFFFF, 0, 0x0FFF, 0x0FFF, 0x0FFF, 0x0FFF);
        assertMasks(helper, faces, Blocks.WHITE_CARPET.defaultBlockState(), 0xFFFF, 0, 0, 0, 0, 0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void faceCostsCombineBothSides(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bottomSlab = oakSlab(SlabType.BOTTOM);
        BlockState topSlab = oakSlab(SlabType.TOP);
        BlockState paneNorthSouth = pane(true, false, true, false);
        BlockState paneNorth = pane(true, false, false, false);
        BlockState paneSouth = pane(false, false, true, false);
        BlockState fenceNorthSouth = fence(true, false, true, false);
        BlockState post = fence(false, false, false, false);
        BlockState door = door(Direction.NORTH, false, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER);
        BlockState stairs = oakStairs(Direction.NORTH, Half.BOTTOM);
        BlockState snowOne = snowLayer(1);
        BlockState snowTwo = snowLayer(2);
        BlockState snowSeven = snowLayer(7);
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState carpet = Blocks.WHITE_CARPET.defaultBlockState();
        assertFaceCost(helper, faces, air, Direction.UP.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, stone, Direction.NORTH.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, air, Direction.SOUTH.get3DDataValue(), stone, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, bottomSlab, Direction.UP.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, bottomSlab, Direction.DOWN.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, bottomSlab, Direction.NORTH.get3DDataValue(), air, 17);
        assertFaceCost(helper, faces, bottomSlab, Direction.EAST.get3DDataValue(), bottomSlab, 17);
        assertFaceCost(helper, faces, bottomSlab, Direction.EAST.get3DDataValue(), topSlab, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, paneNorthSouth, Direction.EAST.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, paneNorthSouth, Direction.NORTH.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, paneNorthSouth, Direction.UP.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, paneNorth, Direction.EAST.get3DDataValue(), paneSouth, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, paneNorth, Direction.EAST.get3DDataValue(), air, 17);
        assertFaceCost(helper, faces, fenceNorthSouth, Direction.EAST.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
            assertFaceCost(helper, faces, post, face, air, 0);
        }
        assertFaceCost(helper, faces, door, Direction.SOUTH.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, door, Direction.NORTH.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, door, Direction.EAST.get3DDataValue(), air, 7);
        assertFaceCost(helper, faces, door, Direction.UP.get3DDataValue(), air, 7);
        assertFaceCost(helper, faces, snowOne, Direction.NORTH.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, snowTwo, Direction.NORTH.get3DDataValue(), air, 7);
        assertFaceCost(helper, faces, snowSeven, Direction.NORTH.get3DDataValue(), air, 33);
        assertFaceCost(helper, faces, stairs, Direction.UP.get3DDataValue(), air, 17);
        assertFaceCost(helper, faces, water, Direction.UP.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, leaves, Direction.NORTH.get3DDataValue(), air, 29);
        assertFaceCost(helper, faces, leaves, Direction.EAST.get3DDataValue(), leaves, 29);
        assertFaceCost(helper, faces, leaves, Direction.NORTH.get3DDataValue(), stone, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, leaves, Direction.EAST.get3DDataValue(), bottomSlab, 29);
        assertFaceCost(helper, faces, leaves, Direction.EAST.get3DDataValue(), stairs, 33);
        assertFaceCost(helper, faces, leaves, Direction.UP.get3DDataValue(), snowTwo, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, leaves, Direction.UP.get3DDataValue(), carpet, SnowFaces.CLOSED_COST);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void faceCostIsSymmetric(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        List<BlockState> states = symmetryStates();
        int[] maskSets = new int[states.size()];
        for (int index = 0; index < states.size(); index++) {
            maskSets[index] = faces.maskSetOf(states.get(index));
        }
        for (int firstIndex = 0; firstIndex < states.size(); firstIndex++) {
            for (int secondIndex = 0; secondIndex < states.size(); secondIndex++) {
                for (int face = 0; face < SnowFaces.FACE_COUNT; face++) {
                    int forwardCost = faces.faceCost(maskSets[firstIndex], face, maskSets[secondIndex]);
                    int backwardCost = faces.faceCost(maskSets[secondIndex], face ^ 1, maskSets[firstIndex]);
                    helper.assertTrue(forwardCost == backwardCost, "The " + faceName(face) + " face cost of "
                            + states.get(firstIndex) + " against " + states.get(secondIndex) + " is " + forwardCost
                            + " where the " + faceName(face ^ 1) + " face cost of the reverse pair is "
                            + backwardCost);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void leafPorosityChangesOnlyLeafFaces(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bottomSlab = oakSlab(SlabType.BOTTOM);
        BlockState stairs = oakStairs(Direction.NORTH, Half.BOTTOM);
        List<BlockState> watchedStates = List.of(leaves, air, bottomSlab, stairs);
        int[] watchedMaskSets = new int[watchedStates.size()];
        for (int index = 0; index < watchedStates.size(); index++) {
            watchedMaskSets[index] = faces.maskSetOf(watchedStates.get(index));
        }
        faces.setLeafPorosity(1.0);
        assertLeafCost(helper, faces, 1.0, 0);
        assertFaceCost(helper, faces, leaves, Direction.NORTH.get3DDataValue(), air, 0);
        assertFaceCost(helper, faces, leaves, Direction.EAST.get3DDataValue(), bottomSlab, 17);
        assertFaceCost(helper, faces, leaves, Direction.EAST.get3DDataValue(), stairs, 33);
        assertFaceCost(helper, faces, bottomSlab, Direction.NORTH.get3DDataValue(), air, 17);
        assertMaskSetsUnchanged(helper, faces, watchedStates, watchedMaskSets, "a leaf porosity of 1.0");
        faces.setLeafPorosity(0.5);
        assertLeafCost(helper, faces, 0.5, 17);
        assertFaceCost(helper, faces, leaves, Direction.NORTH.get3DDataValue(), air, 17);
        assertFaceCost(helper, faces, bottomSlab, Direction.NORTH.get3DDataValue(), air, 17);
        assertMaskSetsUnchanged(helper, faces, watchedStates, watchedMaskSets, "a leaf porosity of 0.5");
        faces.setLeafPorosity(0.0);
        assertLeafCost(helper, faces, 0.0, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, leaves, Direction.NORTH.get3DDataValue(), air, SnowFaces.CLOSED_COST);
        assertFaceCost(helper, faces, bottomSlab, Direction.NORTH.get3DDataValue(), air, 17);
        assertMaskSetsUnchanged(helper, faces, watchedStates, watchedMaskSets, "a leaf porosity of 0.0");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void maskSetsAreSharedAndCached(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        int stoneMaskSet = faces.maskSetOf(Blocks.STONE.defaultBlockState());
        int glassMaskSet = faces.maskSetOf(Blocks.GLASS.defaultBlockState());
        helper.assertTrue(stoneMaskSet == glassMaskSet, "Stone has the mask set " + stoneMaskSet + " where glass has "
                + glassMaskSet);
        int oakStairsMaskSet = faces.maskSetOf(Blocks.OAK_STAIRS.defaultBlockState());
        int spruceStairsMaskSet = faces.maskSetOf(Blocks.SPRUCE_STAIRS.defaultBlockState());
        helper.assertTrue(oakStairsMaskSet == spruceStairsMaskSet, "Default oak stairs have the mask set "
                + oakStairsMaskSet + " where default spruce stairs have " + spruceStairsMaskSet);
        int oakSlabMaskSet = faces.maskSetOf(oakSlab(SlabType.BOTTOM));
        int stoneSlabMaskSet = faces.maskSetOf(Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        helper.assertTrue(oakSlabMaskSet == stoneSlabMaskSet, "A bottom oak slab has the mask set " + oakSlabMaskSet
                + " where a bottom stone slab has " + stoneSlabMaskSet);
        int topSlabMaskSet = faces.maskSetOf(oakSlab(SlabType.TOP));
        helper.assertTrue(topSlabMaskSet != oakSlabMaskSet, "A top oak slab has the mask set " + topSlabMaskSet
                + ", the same as the " + oakSlabMaskSet + " of a bottom oak slab");
        BlockState stone = Blocks.STONE.defaultBlockState();
        int secondStoneMaskSet = faces.maskSetOf(stone);
        helper.assertTrue(secondStoneMaskSet == stoneMaskSet, "The second lookup of stone gives the mask set "
                + secondStoneMaskSet + " instead of " + stoneMaskSet);
        List<BlockState> states = shapeTestStates();
        int[] maskSets = new int[states.size()];
        for (int index = 0; index < states.size(); index++) {
            maskSets[index] = faces.maskSetOf(states.get(index));
        }
        int firstCount = faces.maskSetCount();
        for (int index = 0; index < states.size(); index++) {
            int maskSet = faces.maskSetOf(states.get(index));
            helper.assertTrue(maskSet == maskSets[index], "The second lookup of " + states.get(index)
                    + " gives the mask set " + maskSet + " instead of " + maskSets[index]);
        }
        int secondCount = faces.maskSetCount();
        helper.assertTrue(secondCount == firstCount, "The second lookup of " + states.size() + " states leaves "
                + secondCount + " mask sets instead of the " + firstCount + " of the first lookup");
        faces.clearStateCache();
        for (int index = 0; index < states.size(); index++) {
            int maskSet = faces.maskSetOf(states.get(index));
            helper.assertTrue(maskSet == maskSets[index], "After clearing the state cache, the state "
                    + states.get(index) + " has the mask set " + maskSet + " instead of " + maskSets[index]);
        }
        int thirdCount = faces.maskSetCount();
        helper.assertTrue(thirdCount == firstCount, "After clearing the state cache, the lookup of " + states.size()
                + " states leaves " + thirdCount + " mask sets instead of the " + firstCount);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void warmLookupsAllocateNothing(GameTestHelper helper) {
        SnowFaces faces = new SnowFaces(SnowFaces.DEFAULT_LEAF_POROSITY);
        BlockState[] states = faceCostStates().toArray(new BlockState[0]);
        int[] maskSets = new int[states.length];
        for (int index = 0; index < states.length; index++) {
            maskSets[index] = faces.maskSetOf(states[index]);
        }
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean allocationBean)) {
            LogUtils.getLogger().info("The thread bean is not a com.sun.management.ThreadMXBean, so the allocation "
                    + "of the hot lookups is not measured");
            helper.succeed();
            return;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            try {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            } catch (UnsupportedOperationException unsupported) {
                LogUtils.getLogger().info("Thread allocated memory cannot be turned on", unsupported);
            }
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            LogUtils.getLogger().info("Thread allocated memory is not enabled, so the allocation of the hot lookups "
                    + "is not measured");
            helper.succeed();
            return;
        }
        long threadId = Thread.currentThread().getId();
        for (int read = 0; read < ALLOCATION_READS; read++) {
            allocationBean.getThreadAllocatedBytes(threadId);
        }
        for (int pass = 0; pass < ALLOCATION_WARM_PASSES; pass++) {
            hotLookups(faces, states, maskSets);
        }
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        long sum = hotLookups(faces, states, maskSets);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        LogUtils.getLogger().info("SnowFaces hot lookups allocated " + allocated + " bytes with a sum of " + sum);
        helper.assertTrue(allocated == 0, "The " + HOT_ITERATIONS + " hot lookups allocate " + allocated
                + " bytes instead of 0");
        helper.succeed();
    }

    /**
     * Looks up the mask set of each state in turn, and of the state after it, and adds their face costs,
     * face masks, leaves flags and closed flags into the sum this returns.
     */
    private static long hotLookups(SnowFaces faces, BlockState[] states, int[] maskSets) {
        long sum = 0;
        int stateCount = states.length;
        for (int index = 0; index < HOT_ITERATIONS; index++) {
            int maskSet = faces.maskSetOf(states[index % stateCount]);
            int face = index % 6;
            int neighborMaskSet = maskSets[(index + 1) % stateCount];
            sum += faces.faceCost(maskSet, face, neighborMaskSet);
            sum += faces.faceMask(maskSet, face);
            sum += faces.isLeaves(maskSet) ? 1 : 0;
            sum += faces.closesAllFaces(maskSet) ? 1 : 0;
        }
        return sum;
    }

    /**
     * Checks all six masks of a state against the expected masks of its faces, in the order down, up, north, south,
     * west, east.
     */
    private static void assertMasks(GameTestHelper helper, SnowFaces faces, BlockState state, int expectedDown,
            int expectedUp, int expectedNorth, int expectedSouth, int expectedWest, int expectedEast) {
        int maskSet = faces.maskSetOf(state);
        assertFaceMask(helper, faces, state, maskSet, Direction.DOWN.get3DDataValue(), expectedDown);
        assertFaceMask(helper, faces, state, maskSet, Direction.UP.get3DDataValue(), expectedUp);
        assertFaceMask(helper, faces, state, maskSet, Direction.NORTH.get3DDataValue(), expectedNorth);
        assertFaceMask(helper, faces, state, maskSet, Direction.SOUTH.get3DDataValue(), expectedSouth);
        assertFaceMask(helper, faces, state, maskSet, Direction.WEST.get3DDataValue(), expectedWest);
        assertFaceMask(helper, faces, state, maskSet, Direction.EAST.get3DDataValue(), expectedEast);
    }

    /**
     * Checks one face mask of a state whose mask set is known.
     */
    private static void assertFaceMask(GameTestHelper helper, SnowFaces faces, BlockState state, int maskSet, int face,
            int expectedMask) {
        int mask = faces.faceMask(maskSet, face);
        helper.assertTrue(mask == expectedMask, "The " + faceName(face) + " face of " + state + " in the mask set "
                + maskSet + " has the mask 0x" + Integer.toHexString(mask) + " instead of 0x"
                + Integer.toHexString(expectedMask));
    }

    /**
     * Checks the mask set of a state against the expected one.
     */
    private static void assertMaskSet(GameTestHelper helper, SnowFaces faces, BlockState state, int expectedMaskSet) {
        int maskSet = faces.maskSetOf(state);
        helper.assertTrue(maskSet == expectedMaskSet, "The state " + state + " has the mask set " + maskSet
                + " instead of " + expectedMaskSet);
    }

    /**
     * Checks the cost of the face between two states against the expected cost.
     */
    private static void assertFaceCost(GameTestHelper helper, SnowFaces faces, BlockState state, int face,
            BlockState neighbor, int expectedCost) {
        int cost = faces.faceCost(faces.maskSetOf(state), face, faces.maskSetOf(neighbor));
        helper.assertTrue(cost == expectedCost, "The " + faceName(face) + " face cost of " + state + " against "
                + neighbor + " is " + cost + " instead of " + expectedCost);
    }

    /**
     * Checks the static leaf cost of a porosity against the expected cost.
     */
    private static void assertStaticLeafCost(GameTestHelper helper, double porosity, int expectedCost) {
        int cost = SnowFaces.leafCostOf(porosity);
        helper.assertTrue(cost == expectedCost, "The leaf cost of the porosity " + porosity + " is " + cost
                + " instead of " + expectedCost);
    }

    /**
     * Checks the leaf cost of an instance at the porosity it was set to against the expected cost.
     */
    private static void assertLeafCost(GameTestHelper helper, SnowFaces faces, double porosity, int expectedCost) {
        int cost = faces.leafCost();
        helper.assertTrue(cost == expectedCost, "The leaf cost at the porosity " + porosity + " is " + cost
                + " instead of " + expectedCost);
    }

    /**
     * Checks that the watched states still have the mask sets they were recorded with before a change.
     */
    private static void assertMaskSetsUnchanged(GameTestHelper helper, SnowFaces faces, List<BlockState> states,
            int[] maskSets, String change) {
        for (int index = 0; index < states.size(); index++) {
            int maskSet = faces.maskSetOf(states.get(index));
            helper.assertTrue(maskSet == maskSets[index], "After " + change + ", the state " + states.get(index)
                    + " has the mask set " + maskSet + " instead of " + maskSets[index]);
        }
    }

    /**
     * Whether the coverage cost of a sub-square count is rejected with an IllegalArgumentException.
     */
    private static boolean rejectsCoverage(int coveredSubSquares) {
        try {
            SnowFaces.coverageCost(coveredSubSquares);
        } catch (IllegalArgumentException expectedException) {
            return true;
        }
        return false;
    }

    /**
     * The name of a face by its 3D data value.
     */
    private static String faceName(int face) {
        return Direction.from3DDataValue(face).getName();
    }

    /**
     * The expected mask of a side face of an oak fence, from the arms that cross the face's columns.
     * The arm at the low end of the columns closes column 0, and column 1 too when the post covers the face.
     * The arm at the high end closes column 3, and column 2 too when the post covers the face.
     */
    private static int fenceSideMask(boolean lowArm, boolean highArm, boolean postCoversFace) {
        int mask = 0;
        if (lowArm) {
            mask |= postCoversFace ? 0x3333 : 0x1111;
        }
        if (highArm) {
            mask |= postCoversFace ? 0xCCCC : 0x8888;
        }
        return mask;
    }

    /**
     * A glass pane with the given connections and without water.
     */
    private static BlockState pane(boolean north, boolean east, boolean south, boolean west) {
        return Blocks.GLASS_PANE.defaultBlockState()
                .setValue(CrossCollisionBlock.NORTH, north)
                .setValue(CrossCollisionBlock.EAST, east)
                .setValue(CrossCollisionBlock.SOUTH, south)
                .setValue(CrossCollisionBlock.WEST, west)
                .setValue(CrossCollisionBlock.WATERLOGGED, false);
    }

    /**
     * An oak fence with the given connections and without water.
     */
    private static BlockState fence(boolean north, boolean east, boolean south, boolean west) {
        return Blocks.OAK_FENCE.defaultBlockState()
                .setValue(CrossCollisionBlock.NORTH, north)
                .setValue(CrossCollisionBlock.EAST, east)
                .setValue(CrossCollisionBlock.SOUTH, south)
                .setValue(CrossCollisionBlock.WEST, west)
                .setValue(CrossCollisionBlock.WATERLOGGED, false);
    }

    /**
     * An unpowered oak door with the given facing, open state, hinge and half without water.
     */
    private static BlockState door(Direction facing, boolean open, DoorHingeSide hinge, DoubleBlockHalf half) {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, half)
                .setValue(DoorBlock.POWERED, false);
    }

    /**
     * An oak slab of the given type.
     */
    private static BlockState oakSlab(SlabType type) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
    }

    /**
     * An oak stair block with the given facing and half, in the straight shape.
     */
    private static BlockState oakStairs(Direction facing, Half half) {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, half)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    /**
     * A snow layer block of the given layer count.
     */
    private static BlockState snowLayer(int layers) {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    /**
     * The 16 states of a glass pane, one per combination of its connections.
     */
    private static List<BlockState> paneStates() {
        List<BlockState> states = new ArrayList<>(COMBINATION_COUNT);
        for (int combination = 0; combination < COMBINATION_COUNT; combination++) {
            states.add(pane((combination & 1) != 0, (combination & 2) != 0, (combination & 4) != 0,
                    (combination & 8) != 0));
        }
        return states;
    }

    /**
     * The 16 states of an oak fence, one per combination of its connections.
     */
    private static List<BlockState> fenceStates() {
        List<BlockState> states = new ArrayList<>(COMBINATION_COUNT);
        for (int combination = 0; combination < COMBINATION_COUNT; combination++) {
            states.add(fence((combination & 1) != 0, (combination & 2) != 0, (combination & 4) != 0,
                    (combination & 8) != 0));
        }
        return states;
    }

    /**
     * The states of the mask tests: every pane, every fence, the five doors, the two slabs, the two stairs, the three
     * snow layers and the carpet.
     */
    private static List<BlockState> shapeTestStates() {
        List<BlockState> states = new ArrayList<>(paneStates());
        states.addAll(fenceStates());
        states.add(door(Direction.NORTH, false, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
        states.add(door(Direction.NORTH, false, DoorHingeSide.LEFT, DoubleBlockHalf.UPPER));
        states.add(door(Direction.NORTH, true, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
        states.add(door(Direction.NORTH, true, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
        states.add(door(Direction.EAST, false, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
        states.add(oakSlab(SlabType.BOTTOM));
        states.add(oakSlab(SlabType.TOP));
        states.add(oakStairs(Direction.NORTH, Half.BOTTOM));
        states.add(oakStairs(Direction.EAST, Half.TOP));
        states.add(snowLayer(1));
        states.add(snowLayer(2));
        states.add(snowLayer(7));
        states.add(Blocks.WHITE_CARPET.defaultBlockState());
        return states;
    }

    /**
     * The distinct states of the face cost table: air, stone, both slabs, the three panes, the fence and its post,
     * the door, the stairs, the three snow layers, water, oak leaves and the carpet.
     */
    private static List<BlockState> faceCostStates() {
        List<BlockState> states = new ArrayList<>(17);
        states.add(Blocks.AIR.defaultBlockState());
        states.add(Blocks.STONE.defaultBlockState());
        states.add(oakSlab(SlabType.BOTTOM));
        states.add(oakSlab(SlabType.TOP));
        states.add(pane(true, false, true, false));
        states.add(pane(true, false, false, false));
        states.add(pane(false, false, true, false));
        states.add(fence(true, false, true, false));
        states.add(fence(false, false, false, false));
        states.add(door(Direction.NORTH, false, DoorHingeSide.LEFT, DoubleBlockHalf.LOWER));
        states.add(oakStairs(Direction.NORTH, Half.BOTTOM));
        states.add(snowLayer(1));
        states.add(snowLayer(2));
        states.add(snowLayer(7));
        states.add(Blocks.WATER.defaultBlockState());
        states.add(Blocks.OAK_LEAVES.defaultBlockState());
        states.add(Blocks.WHITE_CARPET.defaultBlockState());
        return states;
    }

    /**
     * The states whose face costs are checked for symmetry: those of the face cost table, and every pane and fence.
     */
    private static List<BlockState> symmetryStates() {
        List<BlockState> states = new ArrayList<>(faceCostStates());
        states.addAll(paneStates());
        states.addAll(fenceStates());
        return states;
    }
}
