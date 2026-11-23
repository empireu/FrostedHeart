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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * Checks the CPU twin of the froxel pipeline of the weather fog: the settings that snap to the grid, the atlas tiles and
 * array layers that hold the slices, the slice mapping and its inverse, the composite lookup of the excess, the open
 * air that gives no excess at any boundary, the pieces that sum to the whole walk with and without structures, the far
 * cells that take the height share of their middle column, the step caps of a piece, the guards of the piece walk and
 * the integrate pass of the excesses.
 * The layouts and the fine step reference are the ones of SnowFogWalkGameTests, so that the pieces walk the same rays.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class FogFroxelGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The tolerance of the values that the formulas give exactly.
     */
    private static final double DEPTH_TOLERANCE = 1.0e-9;
    /**
     * The tolerance in slices of a boundary index near the middle of a slice, which the logarithms round.
     */
    private static final double POSITION_TOLERANCE = 1.0e-9;
    /**
     * The tolerance in optical depths of the excess of an open field, which the sum of the pieces leaves as rounding.
     */
    private static final double OPEN_AIR_TOLERANCE = 1.0e-5;
    /**
     * The tolerance in optical depths of the sum of the pieces against the whole walk of the same ray.
     */
    private static final double IDENTITY_TOLERANCE = 1.0e-4;
    /**
     * The range in blocks of the slice grid that the pieces are walked to, which is the default of fogFroxelRange.
     */
    private static final double RANGE = 192.0;
    /**
     * The half width in blocks of the region of the standard setup, whose field radius is 32.
     */
    private static final double HALF_WIDTH = 30.0;
    /**
     * The deepest boundary in blocks whose pieces stay inside the region of every layout, and whose pieces cross at most
     * twelve cells.
     */
    private static final double PIECE_BOUNDARY_LIMIT = 43.0;
    private static final int[] PIECE_SLICE_COUNTS = {32, 40, 48, 56, 64};
    private static final int[] SINGLE_SLICE_COUNT = {64};
    private static final double[] LEVEL_DIRECTION = {1.0, 0.0, 0.0};
    private static final double[] VERTICAL_DIRECTION = {0.0, 1.0, 0.0};
    private static final double[] DIAGONAL_DIRECTION = {1.0, 1.0, 1.0};
    /**
     * The directions of the open air test: every axis both ways, a level and a rising diagonal of the x-y plane, and the
     * four space diagonals.
     */
    private static final double[][] OPEN_DIRECTIONS = {{1.0, 0.0, 0.0}, {-1.0, 0.0, 0.0}, {0.0, 0.0, 1.0},
            {0.0, 0.0, -1.0}, {0.0, 1.0, 0.0}, {0.0, -1.0, 0.0}, {0.8, 0.6, 0.0}, {0.8, -0.6, 0.0}, {1.0, 1.0, 1.0},
            {1.0, 1.0, -1.0}, {-1.0, -1.0, -1.0}, {-1.0, 1.0, 1.0}};
    /**
     * The field fade and the last shown x of the partial box that the identity test walks as well.
     */
    private static final double PARTIAL_BOX_FADE = 0.5;
    private static final int PARTIAL_BOX_LAST_X = 9;
    /**
     * The structure sizes of the fine step reference of the pieces, and the tolerances that the structured walk of
     * SnowFogWalkGameTests allows at them.
     */
    private static final double STRUCTURE_SIZE = 96.0;
    private static final double SMALL_STRUCTURE_SIZE = 48.0;
    private static final double STRUCTURE_TRANSMITTANCE_TOLERANCE = 0.03;
    private static final double SMALL_STRUCTURE_TRANSMITTANCE_TOLERANCE = 0.06;
    private static final double STRUCTURE_RELATIVE_DEPTH_TOLERANCE = 0.05;
    private static final double RELATIVE_DEPTH_LIMIT = 3.0;
    /**
     * The rays of the layouts and the lengths in blocks over which the structured reference test sums the pieces of a
     * geometric slice grid, whose 32 slices keep both parts of every piece below the step cap of 16 cells.
     */
    private static final int REFERENCE_RAYS = 5;
    private static final double[] REFERENCE_LENGTHS = {20.0, 36.0};
    private static final int REFERENCE_PIECE_SLICES = 32;
    /**
     * The roof that covers the far cells from the world x 40 to 50 and the world z -2 to 2, far above the eye.
     */
    private static final int ROOF_FIRST_X = 40;
    private static final int ROOF_LAST_X = 50;
    private static final int ROOF_FIRST_Z = -2;
    private static final int ROOF_LAST_Z = 2;
    private static final int ROOF_HEIGHT = 1000;

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void settingsSnapToTheirGridValues(GameTestHelper helper) {
        assertDivisor(helper, Double.NaN, FogFroxelGrid.DEFAULT_DIVISOR);
        assertDivisor(helper, 4.0, 4);
        assertDivisor(helper, 16.0, 16);
        assertDivisor(helper, 5.4, 5);
        assertDivisor(helper, 5.5, 6);
        assertDivisor(helper, 15.6, 16);
        assertDivisor(helper, 3.9, 4);
        assertDivisor(helper, 100.0, 16);
        assertSliceCount(helper, Double.NaN, FogFroxelGrid.DEFAULT_SLICES);
        assertSliceCount(helper, 32.0, 32);
        assertSliceCount(helper, 64.0, 64);
        assertSliceCount(helper, 33.0, 32);
        assertSliceCount(helper, 35.9, 32);
        assertSliceCount(helper, 36.0, 40);
        assertSliceCount(helper, 44.0, 48);
        assertSliceCount(helper, 45.0, 48);
        assertSliceCount(helper, 52.0, 56);
        assertSliceCount(helper, 60.0, 64);
        assertSliceCount(helper, 63.0, 64);
        assertSliceCount(helper, 70.0, 64);
        assertSliceCount(helper, 20.0, 32);
        assertRange(helper, Double.NaN, FogFroxelGrid.DEFAULT_RANGE);
        assertRange(helper, 64.0, 64.0);
        assertRange(helper, 192.0, 192.0);
        assertRange(helper, 512.0, 512.0);
        assertRange(helper, 63.9, 64.0);
        assertRange(helper, 513.0, 512.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void atlasTilesAndArrayLayersHoldTheirSlices(GameTestHelper helper) {
        assertColumnCount(helper, 1920, 8, 240);
        assertColumnCount(helper, 1080, 8, 135);
        assertColumnCount(helper, 1919, 8, 240);
        assertColumnCount(helper, 1081, 8, 136);
        assertColumnCount(helper, 1920, 16, 120);
        assertColumnCount(helper, 100, 4, 25);
        assertAtlasWidth(helper, 240, 1920);
        assertAtlasHeight(helper, 135, 1080);
        assertAtlasDrawHeight(helper, 135, 64, 1080);
        assertAtlasDrawHeight(helper, 135, 32, 540);
        assertAtlasDrawHeight(helper, 135, 40, 675);
        assertTile(helper, 1, 0, 0);
        assertTile(helper, 8, 7, 0);
        assertTile(helper, 9, 0, 1);
        assertTile(helper, 64, 7, 7);
        assertAtlasTexelX(helper, 5, 9, 240, 5);
        assertAtlasTexelY(helper, 3, 9, 135, 138);
        int[] slices = {1, 2, 3, 7, 8, 9, 16, 31, 32, 33, 40, 47, 48, 55, 63, 64};
        int[] columns = {0, 1, 5, 119, 239};
        int[] rows = {0, 3, 67, 134};
        for (int sliceIndex = 0; sliceIndex < slices.length; sliceIndex++) {
            int slice = slices[sliceIndex];
            for (int columnIndex = 0; columnIndex < columns.length; columnIndex++) {
                int column = columns[columnIndex];
                int texelX = FogFroxelGrid.atlasTexelX(column, slice, 240);
                for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                    int row = rows[rowIndex];
                    int texelY = FogFroxelGrid.atlasTexelY(row, slice, 135);
                    int sliceOfTexel = FogFroxelGrid.sliceOfAtlasTexel(texelX, texelY, 240, 135);
                    int columnOfTexel = FogFroxelGrid.columnOfAtlasTexel(texelX, 240);
                    int rowOfTexel = FogFroxelGrid.rowOfAtlasTexel(texelY, 135);
                    helper.assertTrue(sliceOfTexel == slice, "The texel (" + texelX + ", " + texelY
                            + ") of the column " + column + ", the row " + row + " and the slice " + slice
                            + " reads back as the slice " + sliceOfTexel + " instead of " + slice);
                    helper.assertTrue(columnOfTexel == column, "The texel (" + texelX + ", " + texelY
                            + ") of the column " + column + " and the slice " + slice + " reads back as the column "
                            + columnOfTexel + " instead of " + column);
                    helper.assertTrue(rowOfTexel == row, "The texel (" + texelX + ", " + texelY + ") of the row " + row
                            + " and the slice " + slice + " reads back as the row " + rowOfTexel + " instead of " + row);
                }
            }
        }
        assertLayer(helper, 1, 0, 0);
        assertLayer(helper, 4, 0, 3);
        assertLayer(helper, 5, 1, 0);
        assertLayer(helper, 64, 15, 3);
        assertLayerCount(helper, 32, 8);
        assertLayerCount(helper, 40, 10);
        assertLayerCount(helper, 64, 16);
        helper.assertTrue(FogFroxelGrid.MAXIMUM_LAYERS == 16, "MAXIMUM_LAYERS is " + FogFroxelGrid.MAXIMUM_LAYERS
                + " instead of 16");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void sliceMappingAndItsInverseAgree(GameTestHelper helper) {
        assertExactBoundaryDistance(helper, 0, 64, 192.0, 1.0);
        assertExactBoundaryDistance(helper, 64, 64, 192.0, 192.0);
        assertExactBoundaryDistance(helper, 0, 32, 192.0, 1.0);
        assertExactBoundaryDistance(helper, 64, 64, 512.0, 512.0);
        int[] sliceCounts = {64, 32};
        double[] ranges = {192.0, 512.0};
        for (int settingIndex = 0; settingIndex < sliceCounts.length; settingIndex++) {
            int sliceCount = sliceCounts[settingIndex];
            double range = ranges[settingIndex];
            for (int boundary = 1; boundary < sliceCount; boundary++) {
                double below = FogFroxelGrid.boundaryDistance(boundary - 1, sliceCount, range);
                double middle = FogFroxelGrid.boundaryDistance(boundary, sliceCount, range);
                double above = FogFroxelGrid.boundaryDistance(boundary + 1, sliceCount, range);
                double product = below * above;
                double square = middle * middle;
                double relativeDifference = Math.abs(product - square) / Math.abs(square);
                helper.assertTrue(relativeDifference <= POSITION_TOLERANCE, "At the slice count " + sliceCount
                        + " of the range " + range + ", the boundaries " + (boundary - 1) + " and " + (boundary + 1)
                        + " give the products " + product + " and " + square + " around the boundary " + boundary
                        + ", a relative difference of " + relativeDifference + " above " + POSITION_TOLERANCE);
            }
            for (int boundary = 0; boundary <= sliceCount; boundary++) {
                double distance = FogFroxelGrid.boundaryDistance(boundary, sliceCount, range);
                double position = FogFroxelGrid.slicePosition(distance, sliceCount, range);
                double difference = Math.abs(position - boundary);
                helper.assertTrue(difference <= POSITION_TOLERANCE, "At the slice count " + sliceCount + " of the range "
                        + range + ", the distance " + distance + " of the boundary " + boundary + " has the slice "
                        + "position " + position + ", a difference of " + difference + " above " + POSITION_TOLERANCE);
            }
            assertExactSlicePosition(helper, 1.0, sliceCount, range, 0.0);
            assertExactSlicePosition(helper, 0.5, sliceCount, range, 0.0);
            assertExactSlicePosition(helper, Double.NaN, sliceCount, range, 0.0);
            assertExactSlicePosition(helper, -5.0, sliceCount, range, 0.0);
        }
        assertExactSlicePosition(helper, 192.0, 64, 192.0, 64.0);
        assertExactSlicePosition(helper, 1000.0, 64, 192.0, 64.0);
        double middleDistance = Math.pow(192.0, 5.25 / 64.0);
        double middlePosition = FogFroxelGrid.slicePosition(middleDistance, 64, 192.0);
        double middleDifference = Math.abs(middlePosition - 5.25);
        helper.assertTrue(middleDifference <= POSITION_TOLERANCE, "The distance " + middleDistance + " of the slice "
                + "position 5.25 has the slice position " + middlePosition + ", a difference of " + middleDifference
                + " above " + POSITION_TOLERANCE);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void excessLookupFollowsTheCompositeRules(GameTestHelper helper) {
        double[] excesses = new double[FogFroxelGrid.MAXIMUM_SLICES];
        for (int index = 0; index < excesses.length; index++) {
            excesses[index] = index + 1.0;
        }
        int sliceCount = FogFroxelGrid.MAXIMUM_SLICES;
        assertExcess(helper, 0.5, excesses, sliceCount, 0.0);
        assertExcess(helper, 1.0, excesses, sliceCount, 0.0);
        assertExcess(helper, FogFroxelGrid.boundaryDistance(1, sliceCount, RANGE), excesses, sliceCount, 1.0);
        int[] boundaries = {1, 2, 3, 16, 32, 47, 62, 63, 64};
        for (int boundaryIndex = 0; boundaryIndex < boundaries.length; boundaryIndex++) {
            int boundary = boundaries[boundaryIndex];
            assertExcess(helper, FogFroxelGrid.boundaryDistance(boundary, sliceCount, RANGE), excesses, sliceCount,
                    boundary);
        }
        assertExcess(helper, Math.pow(192.0, 5.25 / 64.0), excesses, sliceCount, 5.25);
        assertExcess(helper, Math.pow(192.0, 63.5 / 64.0), excesses, sliceCount, 63.5);
        assertExcess(helper, 192.0, excesses, sliceCount, 64.0);
        assertExcess(helper, 300.0, excesses, sliceCount, 64.0);
        assertExcess(helper, Double.NaN, excesses, sliceCount, 0.0);
        double[] shortExcesses = new double[FogFroxelGrid.MAXIMUM_SLICES - 1];
        assertIllegalArgument(helper, "the excess lookup of a " + shortExcesses.length + " entry array for the slice "
                + "count " + sliceCount,
                () -> FogFroxelGrid.excessAtDistance(64.0, shortExcesses, sliceCount, RANGE));
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void openAirExcessIsZeroAtEveryBoundary(GameTestHelper helper) {
        SnowFogWalk walk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.unknownHeights());
        assertOpenAirExcess(helper, "the open field at the height scale 0", walk);
        walk.setFog(SnowFogWalkGameTests.EYE_EXTINCTION, SnowFogWalkGameTests.HEIGHT_SCALE);
        assertOpenAirExcess(helper, "the open field at the height scale " + SnowFogWalkGameTests.HEIGHT_SCALE, walk);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void piecesSumToTheRegionWalkAtEverySliceCount(GameTestHelper helper) {
        SnowFogWalkGameTests.ReferenceLayout[] layouts = SnowFogWalkGameTests.buildLayouts();
        double largestError = 0.0;
        int comparedCount = 0;
        for (int layoutIndex = 0; layoutIndex < layouts.length; layoutIndex++) {
            SnowFogWalkGameTests.ReferenceLayout layout = layouts[layoutIndex];
            for (int rayIndex = 0; rayIndex < layout.rays.length; rayIndex++) {
                double[] ray = layout.rays[rayIndex];
                largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                        "the layout " + layoutIndex + " and the ray " + SnowFogWalkGameTests.describeRay(ray),
                        layout.walk, layout.halfWidth, layout.eyeExtinction, layout.heightScale, ray,
                        PIECE_SLICE_COUNTS));
                comparedCount++;
            }
            largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                    "the layout " + layoutIndex + " and the level direction", layout.walk, layout.halfWidth,
                    layout.eyeExtinction, layout.heightScale, LEVEL_DIRECTION, PIECE_SLICE_COUNTS));
            largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                    "the layout " + layoutIndex + " and the vertical direction", layout.walk, layout.halfWidth,
                    layout.eyeExtinction, layout.heightScale, VERTICAL_DIRECTION, PIECE_SLICE_COUNTS));
            largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                    "the layout " + layoutIndex + " and the diagonal direction", layout.walk, layout.halfWidth,
                    layout.eyeExtinction, layout.heightScale, DIAGONAL_DIRECTION, PIECE_SLICE_COUNTS));
            comparedCount += 3;
        }
        SnowFogWalk cornerWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.filledHeights(SnowFogWalkGameTests.SHELTERED_COLUMN_HEIGHT));
        cornerWalk.setEye(SnowFogWalkGameTests.EYE_BLOCK_X, SnowFogWalkGameTests.EYE_BLOCK_Y,
                SnowFogWalkGameTests.EYE_BLOCK_Z, 0.0, 0.0, 0.0);
        largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                "the eye at the block corner and the diagonal direction", cornerWalk, HALF_WIDTH,
                SnowFogWalkGameTests.EYE_EXTINCTION, 0.0, DIAGONAL_DIRECTION, PIECE_SLICE_COUNTS));
        comparedCount++;
        SnowFogWalk fadedWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.filledHeights(SnowFogWalkGameTests.SHELTERED_COLUMN_HEIGHT));
        fadedWalk.setFieldFade(PARTIAL_BOX_FADE);
        fadedWalk.setShownBox(true, SnowFogWalkGameTests.EYE_BLOCK_X - SnowFogWalkGameTests.WHOLE_BOX_RADIUS,
                SnowFogWalkGameTests.EYE_BLOCK_Y - SnowFogWalkGameTests.WHOLE_BOX_RADIUS,
                SnowFogWalkGameTests.EYE_BLOCK_Z - SnowFogWalkGameTests.WHOLE_BOX_RADIUS,
                SnowFogWalkGameTests.EYE_BLOCK_X + PARTIAL_BOX_LAST_X,
                SnowFogWalkGameTests.EYE_BLOCK_Y + SnowFogWalkGameTests.WHOLE_BOX_SPAN,
                SnowFogWalkGameTests.EYE_BLOCK_Z + SnowFogWalkGameTests.WHOLE_BOX_SPAN);
        largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                "the fade " + PARTIAL_BOX_FADE + " and the level direction", fadedWalk, HALF_WIDTH,
                SnowFogWalkGameTests.EYE_EXTINCTION, 0.0, LEVEL_DIRECTION, PIECE_SLICE_COUNTS));
        largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                "the fade " + PARTIAL_BOX_FADE + " and the vertical direction", fadedWalk, HALF_WIDTH,
                SnowFogWalkGameTests.EYE_EXTINCTION, 0.0, VERTICAL_DIRECTION, PIECE_SLICE_COUNTS));
        largestError = Math.max(largestError, assertPieceSumsMatchTheRegionWalk(helper,
                "the fade " + PARTIAL_BOX_FADE + " and the diagonal direction", fadedWalk, HALF_WIDTH,
                SnowFogWalkGameTests.EYE_EXTINCTION, 0.0, DIAGONAL_DIRECTION, PIECE_SLICE_COUNTS));
        comparedCount += 3;
        LOGGER.info("The pieces sum to the whole walk on " + comparedCount + " rays of five slice counts, the largest "
                + "error " + largestError);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void piecesSumWithAUniformStructure(GameTestHelper helper) {
        FogStructure structure = SnowFogWalkGameTests.setUpStructure(
                SnowFogWalkGameTests.filledStructureTexels(SnowFogWalkGameTests.STRUCTURE_SHAPE_BYTE, 0),
                SnowFogWalkGameTests.STRUCTURE_SHARE, SnowFogWalkGameTests.UNIFORM_STRUCTURE_COVERAGE, 0.0,
                SnowFogWalkGameTests.UNIFORM_STRUCTURE_RANGE, SnowFogWalkGameTests.STRUCTURE_MEAN_SHAPE);
        SnowFogWalk walk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.unknownHeights());
        walk.setStructure(structure);
        double largestError = assertPieceSumsMatchTheRegionWalk(helper,
                "the uniform structure of the density " + SnowFogWalkGameTests.UNIFORM_STRUCTURE_DENSITY, walk,
                HALF_WIDTH, SnowFogWalkGameTests.EYE_EXTINCTION, 0.0, LEVEL_DIRECTION, SINGLE_SLICE_COUNT);
        LOGGER.info("The pieces of the uniform structure sum to the whole walk with the largest error " + largestError);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void piecesOfATexturedStructureMatchTheFineStepReference(GameTestHelper helper) {
        byte[] texels = new byte[SnowFogWalkGameTests.STRUCTURE_TEXEL_COUNT];
        FogStructure.build(FogStructure.SEED, texels);
        SnowFogWalkGameTests.ReferenceLayout[] layouts = SnowFogWalkGameTests.buildLayouts();
        assertStructuredPieces(helper, layouts, texels, STRUCTURE_SIZE, STRUCTURE_TRANSMITTANCE_TOLERANCE, true);
        assertStructuredPieces(helper, layouts, texels, SMALL_STRUCTURE_SIZE, SMALL_STRUCTURE_TRANSMITTANCE_TOLERANCE,
                false);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void farCellsTakeTheHeightShareOfTheirMiddleColumn(GameTestHelper helper) {
        short[] heights = SnowFogWalkGameTests.filledHeights(0);
        for (int x = ROOF_FIRST_X; x <= ROOF_LAST_X; x++) {
            for (int z = ROOF_FIRST_Z; z <= ROOF_LAST_Z; z++) {
                SnowFogWalkGameTests.setColumnHeight(heights, x, z, ROOF_HEIGHT);
            }
        }
        SnowFogWalk walk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0), heights);
        assertPiece(helper, "the piece over the open cells 35 to 39 and the covered cells 41 to 45", walk, 0.45, 35.0,
                45.0);
        assertPieceSteps(helper, "the piece from 35 to 45", walk, 0, false, 6, false);
        assertPiece(helper, "the piece that straddles the roof edge at 39.5", walk, 0.01, 39.4, 39.6);
        assertPiece(helper, "the piece that straddles the roof edge at 49.5", walk, 0.15, 49.0, 51.0);
        assertPiece(helper, "the piece over the open stretches 36 to 39.5 and 49.5 to 54", walk, 0.8, 36.0, 54.0);
        assertPiece(helper, "the piece from the region into the open far cells 31 to 35", walk, 1.0, 25.0, 35.0);
        assertPieceSteps(helper, "the piece from 25 to 35", walk, 6, false, 3, false);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void cappedPieceAddsTheRestAtShareOneAndDensityOne(GameTestHelper helper) {
        SnowFogWalk shelteredWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(SnowField.SHELTERED),
                SnowFogWalkGameTests.filledHeights(SnowFogWalkGameTests.SHELTERED_COLUMN_HEIGHT));
        assertPiece(helper, "the sheltered piece from 10 to 27.5, whose near part runs out of cells", shelteredWalk, 0.2,
                10.0, 27.5);
        assertPieceSteps(helper, "the sheltered piece from 10 to 27.5", shelteredWalk, 16, true, 0, false);
        SnowFogWalk openWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.unknownHeights());
        assertPiece(helper, "the open piece from 10 to 27.5, which the cap leaves whole", openWalk, 1.75, 10.0, 27.5);
        assertPieceSteps(helper, "the open piece from 10 to 27.5", openWalk, 16, true, 0, false);
        SnowFogWalk coveredWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.filledHeights(SnowFogWalkGameTests.SHELTERED_COLUMN_HEIGHT));
        assertPiece(helper, "the covered far piece from 30 to 97, whose far part runs out of cells", coveredWalk, 0.35,
                30.0, 97.0);
        assertPieceSteps(helper, "the covered far piece from 30 to 97", coveredWalk, 0, false, 32, true);
        assertPiece(helper, "the open far piece from 30 to 97, which the cap leaves whole", openWalk, 6.7, 30.0, 97.0);
        assertPieceSteps(helper, "the open far piece from 30 to 97", openWalk, 0, false, 32, true);
        short[] roofHeights = SnowFogWalkGameTests.filledHeights(0);
        for (int x = ROOF_FIRST_X; x <= ROOF_LAST_X; x++) {
            for (int z = ROOF_FIRST_Z; z <= ROOF_LAST_Z; z++) {
                SnowFogWalkGameTests.setColumnHeight(roofHeights, x, z, ROOF_HEIGHT);
            }
        }
        SnowFogWalk roofedWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0), roofHeights);
        assertPiece(helper, "the far piece from 30 to 97 under the roof of the columns 40 to 50", roofedWalk, 5.7, 30.0,
                97.0);
        assertPieceSteps(helper, "the far piece from 30 to 97 under the roof", roofedWalk, 0, false, 32, true);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void pieceWalkGuardsBadInput(GameTestHelper helper) {
        SnowFogWalk walk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.unknownHeights());
        assertRejectedPiece(helper, "the zero direction", walk, 0.0, 0.0, 0.0, 0.0, 1.0);
        assertRejectedPiece(helper, "the direction (NaN, 1, 1)", walk, Double.NaN, 1.0, 1.0, 0.0, 1.0);
        assertRejectedPiece(helper, "the direction (Infinity, 0, 0)", walk, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0,
                1.0);
        assertRejectedPiece(helper, "the start NaN", walk, LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2],
                Double.NaN, 1.0);
        assertRejectedPiece(helper, "the end NaN", walk, LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2], 0.0,
                Double.NaN);
        assertRejectedPiece(helper, "the start 5 and the end 5", walk, LEVEL_DIRECTION[0], LEVEL_DIRECTION[1],
                LEVEL_DIRECTION[2], 5.0, 5.0);
        assertRejectedPiece(helper, "the start 6 and the end 5", walk, LEVEL_DIRECTION[0], LEVEL_DIRECTION[1],
                LEVEL_DIRECTION[2], 6.0, 5.0);
        double negativeStart = walk.pieceOpticalDepth(LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2], -2.0,
                5.0);
        double zeroStart = walk.pieceOpticalDepth(LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2], 0.0, 5.0);
        helper.assertTrue(negativeStart == zeroStart, "The open field of the level piece from -2 to 5 has the depth "
                + negativeStart + " instead of the " + zeroStart + " of the piece from 0 to 5");
        double startDifference = Math.abs(zeroStart - 0.5);
        helper.assertTrue(startDifference <= DEPTH_TOLERANCE, "The open field of the level piece from 0 to 5 has the "
                + "depth " + zeroStart + " instead of 0.5, a difference of " + startDifference + " above "
                + DEPTH_TOLERANCE);
        walk.setFog(0.0, 0.0);
        assertRejectedPiece(helper, "the level piece without extinction", walk, LEVEL_DIRECTION[0], LEVEL_DIRECTION[1],
                LEVEL_DIRECTION[2], 0.0, 5.0);
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void columnExcessesIntegratesThePieces(GameTestHelper helper) {
        double[] boundaries = {3.0, 7.0, 50.0};
        double[] shortBoundaries = {3.0, 7.0};
        SnowFogWalk openWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.unknownHeights());
        double[] openExpected = {0.0, 0.0, 0.0};
        assertColumnExcesses(helper, "the open field with unknown heights", openWalk, LEVEL_DIRECTION, boundaries, 3,
                openExpected);
        SnowFogWalk coveredWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0),
                SnowFogWalkGameTests.filledHeights(SnowFogWalkGameTests.SHELTERED_COLUMN_HEIGHT));
        // The piece 7 to 50 has 24 near cells, so the cap stops after its 16th cell, whose crossing is 22.5.
        // The cells 7 to 22 all have the crossfade weight 1 and share 1 and add 1.55, and the rest 22.5 to 30 counts at
        // share 1 and density 1 and adds 0.75 for a near part of 2.3. The first two pieces give 0.3 and 0.4, the far
        // cells from 30 to 50 lie under the cover and add nothing, and the analytic depth to 50 is 5.0.
        double[] coveredExpected = {0.0, 0.0, -2.0};
        assertColumnExcesses(helper, "the open field under the cover of the height "
                + SnowFogWalkGameTests.SHELTERED_COLUMN_HEIGHT + ", whose third piece stops at the cap's crossing 22.5",
                coveredWalk, LEVEL_DIRECTION, boundaries, 3, coveredExpected);
        coveredWalk.setFieldFade(PARTIAL_BOX_FADE);
        double[] fadedExpected = {-0.15, -0.35};
        assertColumnExcesses(helper, "the fade " + PARTIAL_BOX_FADE + " under that cover", coveredWalk, LEVEL_DIRECTION,
                boundaries, 2, fadedExpected);
        short[] roofHeights = SnowFogWalkGameTests.filledHeights(0);
        for (int x = ROOF_FIRST_X; x <= ROOF_LAST_X; x++) {
            for (int z = ROOF_FIRST_Z; z <= ROOF_LAST_Z; z++) {
                SnowFogWalkGameTests.setColumnHeight(roofHeights, x, z, ROOF_HEIGHT);
            }
        }
        SnowFogWalk roofedWalk = SnowFogWalkGameTests.setUpWalk(SnowFogWalkGameTests.byteField(0), roofHeights);
        double[] roofedBoundaries = {3.0, 25.0, 45.0};
        double[] roofedExpected = {0.0, 0.0, -0.55};
        assertColumnExcesses(helper, "the roof of the columns 40 to 50", roofedWalk, LEVEL_DIRECTION, roofedBoundaries,
                3, roofedExpected);
        double[] excesses = new double[3];
        assertIllegalArgument(helper, "the column walk of the boundary count -1",
                () -> openWalk.columnExcesses(LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2], boundaries, -1,
                        excesses));
        double[] shortExcesses = new double[2];
        assertIllegalArgument(helper, "the column walk of 3 boundaries into an excesses array of 2 entries",
                () -> openWalk.columnExcesses(LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2], boundaries, 3,
                        shortExcesses));
        assertIllegalArgument(helper, "the column walk of 3 boundaries from a boundary array of 2 entries",
                () -> openWalk.columnExcesses(LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2],
                        shortBoundaries, 3, excesses));
        double[] zeroExcesses = {9.0, 9.0, 9.0};
        openWalk.columnExcesses(0.0, 0.0, 0.0, boundaries, 3, zeroExcesses);
        for (int boundary = 0; boundary < 3; boundary++) {
            helper.assertTrue(zeroExcesses[boundary] == 0.0, "The zero direction leaves the excess of the boundary "
                    + (boundary + 1) + " at " + zeroExcesses[boundary] + " instead of exactly 0.0");
        }
        helper.succeed();
    }

    /**
     * Checks divisorOf against the expected divisor in pixels per side.
     */
    private static void assertDivisor(GameTestHelper helper, double setting, int expected) {
        int divisor = FogFroxelGrid.divisorOf(setting);
        helper.assertTrue(divisor == expected, "divisorOf(" + setting + ") is " + divisor + " instead of " + expected);
    }

    /**
     * Checks sliceCountOf against the expected number of depth slices.
     */
    private static void assertSliceCount(GameTestHelper helper, double setting, int expected) {
        int sliceCount = FogFroxelGrid.sliceCountOf(setting);
        helper.assertTrue(sliceCount == expected, "sliceCountOf(" + setting + ") is " + sliceCount + " instead of "
                + expected);
    }

    /**
     * Checks rangeOf against the expected range in blocks.
     */
    private static void assertRange(GameTestHelper helper, double setting, double expected) {
        double range = FogFroxelGrid.rangeOf(setting);
        helper.assertTrue(range == expected, "rangeOf(" + setting + ") is " + range + " instead of " + expected);
    }

    /**
     * Checks columnCount against the expected number of columns.
     */
    private static void assertColumnCount(GameTestHelper helper, int screenPixels, int divisor, int expected) {
        int columnCount = FogFroxelGrid.columnCount(screenPixels, divisor);
        helper.assertTrue(columnCount == expected, "columnCount(" + screenPixels + ", " + divisor + ") is " + columnCount
                + " instead of " + expected);
    }

    /**
     * Checks atlasWidth against the expected width in texels.
     */
    private static void assertAtlasWidth(GameTestHelper helper, int columnCount, int expected) {
        int width = FogFroxelGrid.atlasWidth(columnCount);
        helper.assertTrue(width == expected, "atlasWidth(" + columnCount + ") is " + width + " instead of " + expected);
    }

    /**
     * Checks atlasHeight against the expected height in texels.
     */
    private static void assertAtlasHeight(GameTestHelper helper, int rowCount, int expected) {
        int height = FogFroxelGrid.atlasHeight(rowCount);
        helper.assertTrue(height == expected, "atlasHeight(" + rowCount + ") is " + height + " instead of " + expected);
    }

    /**
     * Checks atlasDrawHeight against the expected height in texels.
     */
    private static void assertAtlasDrawHeight(GameTestHelper helper, int rowCount, int sliceCount, int expected) {
        int height = FogFroxelGrid.atlasDrawHeight(rowCount, sliceCount);
        helper.assertTrue(height == expected, "atlasDrawHeight(" + rowCount + ", " + sliceCount + ") is " + height
                + " instead of " + expected);
    }

    /**
     * Checks the atlas tile of a depth slice against the expected tile across and down.
     */
    private static void assertTile(GameTestHelper helper, int slice, int expectedTileX, int expectedTileY) {
        int tileX = FogFroxelGrid.tileX(slice);
        int tileY = FogFroxelGrid.tileY(slice);
        helper.assertTrue(tileX == expectedTileX, "tileX(" + slice + ") is " + tileX + " instead of " + expectedTileX);
        helper.assertTrue(tileY == expectedTileY, "tileY(" + slice + ") is " + tileY + " instead of " + expectedTileY);
    }

    /**
     * Checks atlasTexelX against the expected texel across.
     */
    private static void assertAtlasTexelX(GameTestHelper helper, int column, int slice, int columnCount, int expected) {
        int texelX = FogFroxelGrid.atlasTexelX(column, slice, columnCount);
        helper.assertTrue(texelX == expected, "atlasTexelX(" + column + ", " + slice + ", " + columnCount + ") is "
                + texelX + " instead of " + expected);
    }

    /**
     * Checks atlasTexelY against the expected texel down.
     */
    private static void assertAtlasTexelY(GameTestHelper helper, int row, int slice, int rowCount, int expected) {
        int texelY = FogFroxelGrid.atlasTexelY(row, slice, rowCount);
        helper.assertTrue(texelY == expected, "atlasTexelY(" + row + ", " + slice + ", " + rowCount + ") is " + texelY
                + " instead of " + expected);
    }

    /**
     * Checks the layer and channel of the array texture that hold a boundary.
     */
    private static void assertLayer(GameTestHelper helper, int boundaryIndex, int expectedLayer,
            int expectedChannel) {
        int layer = FogFroxelGrid.layerOf(boundaryIndex);
        int channel = FogFroxelGrid.channelOf(boundaryIndex);
        helper.assertTrue(layer == expectedLayer, "layerOf(" + boundaryIndex + ") is " + layer + " instead of "
                + expectedLayer);
        helper.assertTrue(channel == expectedChannel, "channelOf(" + boundaryIndex + ") is " + channel + " instead of "
                + expectedChannel);
    }

    /**
     * Checks layerCount against the expected number of layers.
     */
    private static void assertLayerCount(GameTestHelper helper, int sliceCount, int expected) {
        int layerCount = FogFroxelGrid.layerCount(sliceCount);
        helper.assertTrue(layerCount == expected, "layerCount(" + sliceCount + ") is " + layerCount + " instead of "
                + expected);
    }

    /**
     * Checks a boundary distance against a value that the formula gives exactly.
     */
    private static void assertExactBoundaryDistance(GameTestHelper helper, int boundaryIndex, int sliceCount,
            double rangeDistance, double expected) {
        double distance = FogFroxelGrid.boundaryDistance(boundaryIndex, sliceCount, rangeDistance);
        helper.assertTrue(distance == expected, "boundaryDistance(" + boundaryIndex + ", " + sliceCount + ", "
                + rangeDistance + ") is " + distance + " instead of exactly " + expected);
    }

    /**
     * Checks a slice position against a value that the formula gives exactly.
     */
    private static void assertExactSlicePosition(GameTestHelper helper, double distance, int sliceCount,
            double rangeDistance, double expected) {
        double position = FogFroxelGrid.slicePosition(distance, sliceCount, rangeDistance);
        helper.assertTrue(position == expected, "slicePosition(" + distance + ", " + sliceCount + ", " + rangeDistance
                + ") is " + position + " instead of exactly " + expected);
    }

    /**
     * Checks the excess at a distance against its expected value within DEPTH_TOLERANCE.
     */
    private static void assertExcess(GameTestHelper helper, double distance, double[] excesses, int sliceCount,
            double expected) {
        double excess = FogFroxelGrid.excessAtDistance(distance, excesses, sliceCount, RANGE);
        double difference = Math.abs(excess - expected);
        helper.assertTrue(difference <= DEPTH_TOLERANCE, "The excess at the distance " + distance + " of the slice "
                + "count " + sliceCount + " is " + excess + " instead of " + expected + ", a difference of " + difference
                + " above " + DEPTH_TOLERANCE);
    }

    /**
     * Checks that the given call throws an IllegalArgumentException.
     */
    private static void assertIllegalArgument(GameTestHelper helper, String step, Runnable call) {
        boolean threw = false;
        try {
            call.run();
        } catch (IllegalArgumentException illegal) {
            threw = true;
        }
        helper.assertTrue(threw, "In " + step + ", the call throws no IllegalArgumentException");
    }

    /**
     * Checks that the open field of the walk gives every excess of both tested slice counts within OPEN_AIR_TOLERANCE
     * of 0.
     */
    private static void assertOpenAirExcess(GameTestHelper helper, String step, SnowFogWalk walk) {
        double[] boundaryDistances = new double[FogFroxelGrid.MAXIMUM_SLICES];
        double[] excesses = new double[FogFroxelGrid.MAXIMUM_SLICES];
        int[] sliceCounts = {FogFroxelGrid.MAXIMUM_SLICES, FogFroxelGrid.MINIMUM_SLICES};
        for (int sliceCountIndex = 0; sliceCountIndex < sliceCounts.length; sliceCountIndex++) {
            int sliceCount = sliceCounts[sliceCountIndex];
            fillBoundaries(boundaryDistances, sliceCount, RANGE);
            for (int directionIndex = 0; directionIndex < OPEN_DIRECTIONS.length; directionIndex++) {
                double[] direction = OPEN_DIRECTIONS[directionIndex];
                walk.columnExcesses(direction[0], direction[1], direction[2], boundaryDistances, sliceCount, excesses);
                for (int boundary = 0; boundary < sliceCount; boundary++) {
                    double excess = excesses[boundary];
                    helper.assertTrue(Math.abs(excess) <= OPEN_AIR_TOLERANCE, "In " + step + ", the direction "
                            + describeDirection(direction) + " gives the boundary " + (boundary + 1) + " of "
                            + sliceCount + " the excess " + excess + " instead of one within " + OPEN_AIR_TOLERANCE
                            + " of 0");
                }
            }
        }
    }

    /**
     * Fills the distances of the boundaries 1 to sliceCount of a slice grid.
     */
    private static void fillBoundaries(double[] boundaryDistances, int sliceCount, double rangeDistance) {
        for (int boundary = 0; boundary < sliceCount; boundary++) {
            boundaryDistances[boundary] = FogFroxelGrid.boundaryDistance(boundary + 1, sliceCount, rangeDistance);
        }
    }

    /**
     * Checks, at every given slice count, that the pieces of a direction up to every boundary that stays inside the
     * region sum to the whole walk to that boundary, and returns the largest difference in optical depths.
     */
    private static double assertPieceSumsMatchTheRegionWalk(GameTestHelper helper, String step, SnowFogWalk walk,
            double halfWidth, double eyeExtinction, double heightScale, double[] direction, int[] sliceCounts) {
        double directionLength = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1]
                + direction[2] * direction[2]);
        double unitX = direction[0] / directionLength;
        double unitY = direction[1] / directionLength;
        double unitZ = direction[2] / directionLength;
        double largestOffset = Math.max(Math.abs(unitX), Math.max(Math.abs(unitY), Math.abs(unitZ)));
        double limit = Math.min(PIECE_BOUNDARY_LIMIT, halfWidth / largestOffset);
        double[] boundaryDistances = new double[FogFroxelGrid.MAXIMUM_SLICES];
        double[] excesses = new double[FogFroxelGrid.MAXIMUM_SLICES];
        double largestError = 0.0;
        for (int sliceCountIndex = 0; sliceCountIndex < sliceCounts.length; sliceCountIndex++) {
            int sliceCount = sliceCounts[sliceCountIndex];
            int boundaryCount = 0;
            while (boundaryCount < sliceCount) {
                double distance = FogFroxelGrid.boundaryDistance(boundaryCount + 1, sliceCount, RANGE);
                if (distance > limit) {
                    break;
                }
                boundaryDistances[boundaryCount] = distance;
                boundaryCount++;
            }
            if (boundaryCount == 0) {
                continue;
            }
            walk.columnExcesses(unitX, unitY, unitZ, boundaryDistances, boundaryCount, excesses);
            boolean nearCapped = walk.lastNearCapped();
            boolean farCapped = walk.lastFarCapped();
            helper.assertTrue(!nearCapped, "In " + step + ", the direction " + describeDirection(direction)
                    + " runs out of near cells at the slice count " + sliceCount + ", although every piece of it ends "
                    + "inside the " + limit + " blocks of the region");
            helper.assertTrue(!farCapped, "In " + step + ", the direction " + describeDirection(direction)
                    + " runs out of far cells at the slice count " + sliceCount + ", although every piece of it ends "
                    + "inside the " + limit + " blocks of the region");
            for (int boundary = 0; boundary < boundaryCount; boundary++) {
                double distance = boundaryDistances[boundary];
                double analytic = HeightFog.opticalDepth(eyeExtinction, distance, distance * unitY, heightScale);
                double pieces = excesses[boundary] + analytic;
                double walked = walk.opticalDepth(unitX * distance, unitY * distance, unitZ * distance);
                double error = Math.abs(pieces - walked);
                largestError = Math.max(largestError, error);
                helper.assertTrue(error <= IDENTITY_TOLERANCE, "In " + step + ", the direction "
                        + describeDirection(direction) + " sums its pieces up to the boundary " + (boundary + 1) + " at "
                        + "the distance " + distance + " of the slice count " + sliceCount + " to " + pieces
                        + " where the whole walk gives " + walked + ", an error of " + error + " above "
                        + IDENTITY_TOLERANCE);
            }
        }
        return largestError;
    }

    /**
     * Checks the sum of the pieces of a REFERENCE_PIECE_SLICES-slice grid along the first rays of the layouts up to the
     * tested lengths against the fine step reference at the given structure size, where the transmittance error stays
     * within the given tolerance and, when asked, the relative depth below RELATIVE_DEPTH_LIMIT within
     * STRUCTURE_RELATIVE_DEPTH_TOLERANCE.
     * The pieces match the reference only within these tolerances: every part of a piece samples the structures at the
     * middle of the part, so a cell that a slice boundary cuts samples the density at two points where the whole walk
     * samples it at one.
     * The absolute depth allowance of SnowFogWalkGameTests.walkMatchesAFineStepReference belongs to the whole walk
     * alone, so it is not asserted here.
     */
    private static void assertStructuredPieces(GameTestHelper helper, SnowFogWalkGameTests.ReferenceLayout[] layouts,
            byte[] texels, double size, double transmittanceTolerance, boolean checkRelative) {
        double largestTransmittanceError = 0.0;
        String largestTransmittanceCase = "none";
        double largestRelativeError = 0.0;
        String largestRelativeCase = "none";
        int comparedCount = 0;
        for (int layoutIndex = 0; layoutIndex < layouts.length; layoutIndex++) {
            SnowFogWalkGameTests.ReferenceLayout layout = layouts[layoutIndex];
            SnowFogWalkGameTests.setLayoutStructure(layout, texels, size);
            for (int rayIndex = 0; rayIndex < REFERENCE_RAYS; rayIndex++) {
                double[] ray = layout.rays[rayIndex];
                double directionLength = Math.sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2]);
                for (int lengthIndex = 0; lengthIndex < REFERENCE_LENGTHS.length; lengthIndex++) {
                    double length = REFERENCE_LENGTHS[lengthIndex];
                    double offsetX = ray[0] / directionLength * length;
                    double offsetY = ray[1] / directionLength * length;
                    double offsetZ = ray[2] / directionLength * length;
                    SnowFogWalkGameTests.ReferenceDepth reference = SnowFogWalkGameTests.referenceDepth(layout, offsetX,
                            offsetY, offsetZ, layout.structure);
                    String step = "the layout " + layoutIndex + " and the ray " + SnowFogWalkGameTests.describeRay(ray)
                            + " up to the length " + length;
                    double depth = 0.0;
                    double previousDistance = 0.0;
                    for (int boundary = 0; boundary < REFERENCE_PIECE_SLICES; boundary++) {
                        double boundaryDistance = FogFroxelGrid.boundaryDistance(boundary + 1, REFERENCE_PIECE_SLICES,
                                length);
                        depth += layout.walk.pieceOpticalDepth(offsetX, offsetY, offsetZ, previousDistance,
                                boundaryDistance);
                        previousDistance = boundaryDistance;
                    }
                    helper.assertTrue(!layout.walk.lastNearCapped(), "At the structure size " + size + ", " + step
                            + " caps the near part of the last piece after " + layout.walk.lastNearSteps()
                            + " cells, while the 32 slices keep every piece below PIECE_MAXIMUM_STEPS = "
                            + SnowFogWalk.PIECE_MAXIMUM_STEPS);
                    helper.assertTrue(!layout.walk.lastFarCapped(), "At the structure size " + size + ", " + step
                            + " caps the far part of the last piece after " + layout.walk.lastFarSteps()
                            + " cells, while the 32 slices keep every piece below PIECE_FAR_MAXIMUM_STEPS = "
                            + SnowFogWalk.PIECE_FAR_MAXIMUM_STEPS);
                    double transmittance = Math.exp(-depth);
                    double referenceTransmittance = Math.exp(-reference.depth);
                    double transmittanceError = Math.abs(transmittance - referenceTransmittance);
                    if (transmittanceError > largestTransmittanceError) {
                        largestTransmittanceError = transmittanceError;
                        largestTransmittanceCase = step;
                    }
                    helper.assertTrue(transmittanceError <= transmittanceTolerance, "At the structure size " + size
                            + ", " + step + " gives the pieces the depth " + depth + " where the fine step reference "
                            + "gives " + reference.depth + ", whose transmittances " + transmittance + " and "
                            + referenceTransmittance + " differ by " + transmittanceError + " above "
                            + transmittanceTolerance);
                    if (checkRelative && reference.depth > 0.0 && reference.depth < RELATIVE_DEPTH_LIMIT) {
                        double relativeError = Math.abs(depth - reference.depth) / reference.depth;
                        if (relativeError > largestRelativeError) {
                            largestRelativeError = relativeError;
                            largestRelativeCase = step;
                        }
                        helper.assertTrue(relativeError <= STRUCTURE_RELATIVE_DEPTH_TOLERANCE, "At the structure size "
                                + size + ", " + step + " gives the pieces the depth " + depth + " where the fine step "
                                + "reference gives " + reference.depth + ", a relative error of " + relativeError
                                + " above " + STRUCTURE_RELATIVE_DEPTH_TOLERANCE);
                    }
                    comparedCount++;
                }
            }
        }
        LOGGER.info("The pieces at the structure size " + size + " match the fine step reference on " + comparedCount
                + " rays, the largest transmittance error " + largestTransmittanceError + " at "
                + largestTransmittanceCase + " and the largest relative depth error " + largestRelativeError + " at "
                + largestRelativeCase);
    }

    /**
     * Checks the level piece of the standard setup between two distances against its expected value within
     * DEPTH_TOLERANCE.
     */
    private static void assertPiece(GameTestHelper helper, String step, SnowFogWalk walk, double expected,
            double startDistance, double endDistance) {
        double depth = walk.pieceOpticalDepth(LEVEL_DIRECTION[0], LEVEL_DIRECTION[1], LEVEL_DIRECTION[2], startDistance,
                endDistance);
        double difference = Math.abs(depth - expected);
        helper.assertTrue(difference <= DEPTH_TOLERANCE, "In " + step + ", the level piece from " + startDistance
                + " to " + endDistance + " has the depth " + depth + " instead of " + expected + ", a difference of "
                + difference + " above " + DEPTH_TOLERANCE);
    }

    /**
     * Checks the four getters that the piece walk records against the expected cells and caps.
     */
    private static void assertPieceSteps(GameTestHelper helper, String step, SnowFogWalk walk, int expectedNearSteps,
            boolean expectedNearCapped, int expectedFarSteps, boolean expectedFarCapped) {
        int nearSteps = walk.lastNearSteps();
        boolean nearCapped = walk.lastNearCapped();
        int farSteps = walk.lastFarSteps();
        boolean farCapped = walk.lastFarCapped();
        helper.assertTrue(nearSteps == expectedNearSteps, "In " + step + ", the near part visits " + nearSteps
                + " cells instead of " + expectedNearSteps);
        helper.assertTrue(nearCapped == expectedNearCapped, "In " + step + ", the near cap is " + nearCapped
                + " instead of " + expectedNearCapped);
        helper.assertTrue(farSteps == expectedFarSteps, "In " + step + ", the far part visits " + farSteps
                + " cells instead of " + expectedFarSteps);
        helper.assertTrue(farCapped == expectedFarCapped, "In " + step + ", the far cap is " + farCapped + " instead of "
                + expectedFarCapped);
    }

    /**
     * Checks that a rejected piece walk gives exactly 0 and leaves the four getters at 0 and false.
     */
    private static void assertRejectedPiece(GameTestHelper helper, String step, SnowFogWalk walk, double directionX,
            double directionY, double directionZ, double startDistance, double endDistance) {
        double depth = walk.pieceOpticalDepth(directionX, directionY, directionZ, startDistance, endDistance);
        helper.assertTrue(depth == 0.0, "The piece of " + step + " from " + startDistance + " to " + endDistance
                + " along " + describeDirection(directionX, directionY, directionZ) + " has the depth " + depth
                + " instead of exactly 0.0");
        int nearSteps = walk.lastNearSteps();
        boolean nearCapped = walk.lastNearCapped();
        int farSteps = walk.lastFarSteps();
        boolean farCapped = walk.lastFarCapped();
        helper.assertTrue(nearSteps == 0, "The rejected piece of " + step + " leaves " + nearSteps
                + " near steps instead of 0");
        helper.assertTrue(!nearCapped, "The rejected piece of " + step + " leaves the near cap at " + nearCapped
                + " instead of false");
        helper.assertTrue(farSteps == 0, "The rejected piece of " + step + " leaves " + farSteps
                + " far steps instead of 0");
        helper.assertTrue(!farCapped, "The rejected piece of " + step + " leaves the far cap at " + farCapped
                + " instead of false");
    }

    /**
     * Checks the excesses of a column walk against the expected ones within DEPTH_TOLERANCE.
     */
    private static void assertColumnExcesses(GameTestHelper helper, String step, SnowFogWalk walk, double[] direction,
            double[] boundaryDistances, int boundaryCount, double[] expected) {
        double[] excesses = new double[boundaryCount];
        walk.columnExcesses(direction[0], direction[1], direction[2], boundaryDistances, boundaryCount, excesses);
        for (int boundary = 0; boundary < boundaryCount; boundary++) {
            double difference = Math.abs(excesses[boundary] - expected[boundary]);
            helper.assertTrue(difference <= DEPTH_TOLERANCE, "In " + step + ", the excess of the boundary "
                    + (boundary + 1) + " at the distance " + boundaryDistances[boundary] + " is " + excesses[boundary]
                    + " instead of " + expected[boundary] + ", a difference of " + difference + " above "
                    + DEPTH_TOLERANCE);
        }
    }

    /**
     * A direction as the messages name it.
     */
    private static String describeDirection(double[] direction) {
        return describeDirection(direction[0], direction[1], direction[2]);
    }

    /**
     * A direction as the messages name it.
     */
    private static String describeDirection(double directionX, double directionY, double directionZ) {
        return "(" + directionX + ", " + directionY + ", " + directionZ + ")";
    }
}
