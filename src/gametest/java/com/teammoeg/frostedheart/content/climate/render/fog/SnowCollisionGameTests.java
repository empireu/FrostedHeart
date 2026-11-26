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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;

/**
 * Checks the collision of the falling snow: the shapes of block states at a sixteenth of a block, the volume of shape ids
 * that follows the eye and reads a chunk, the Java twin of the update's hit search on faces, slabs, leaves and water,
 * the test for a flake leaving the box, and the fade of a splat.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class SnowCollisionGameTests {
    private static final double TOLERANCE = 1.0e-9;
    /**
     * How far a narrowed hit may lie from the true face along the move, a sixty-fourth of a block.
     */
    private static final double HIT_TOLERANCE = 1.0 / 64.0 + 1.0e-9;
    private static final int READ_BUDGET = 1 << 20;
    private static final int SECTION_WIDTH = 16;
    private static final int EYE_SECTION = 8;
    private static final int LEAF_FLAKES = 20000;

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void shapesFollowWhatCollidingBlocksLookLike(GameTestHelper helper) {
        SnowShapes shapes = new SnowShapes();
        checkId(helper, "air", shapes.idOf(Blocks.AIR.defaultBlockState()), SnowShapes.EMPTY_SHAPE);
        checkId(helper, "stone", shapes.idOf(Blocks.STONE.defaultBlockState()), SnowShapes.FULL_SHAPE);
        checkId(helper, "grass without collision", shapes.idOf(Blocks.GRASS.defaultBlockState()),
                SnowShapes.EMPTY_SHAPE);
        checkId(helper, "torch without collision", shapes.idOf(Blocks.TORCH.defaultBlockState()),
                SnowShapes.EMPTY_SHAPE);
        checkId(helper, "oak leaves", shapes.idOf(Blocks.OAK_LEAVES.defaultBlockState()),
                SnowShapes.FULL_SHAPE | SnowShapes.LEAVES_FLAG);
        checkId(helper, "dirt shares the full shape of stone", shapes.idOf(Blocks.DIRT.defaultBlockState()),
                SnowShapes.FULL_SHAPE);
        int bottomSlab = shapes.idOf(slab(SlabType.BOTTOM, false));
        checkRows(helper, shapes, "bottom slab", bottomSlab, 0, 15, 0, 7, 0, 15);
        int topSlab = shapes.idOf(slab(SlabType.TOP, false));
        checkRows(helper, shapes, "top slab", topSlab, 0, 15, 8, 15, 0, 15);
        int fence = shapes.idOf(Blocks.OAK_FENCE.defaultBlockState());
        checkRows(helper, shapes, "fence post", fence, 6, 9, 0, 15, 6, 9);
        BlockState snowLayers = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3);
        checkRows(helper, shapes, "three layers of snow, as tall as they look", shapes.idOf(snowLayers), 0, 15, 0, 5,
                0, 15);
        int water = shapes.idOf(Blocks.WATER.defaultBlockState());
        helper.assertTrue(SnowShapes.isFluid(water) && !SnowShapes.isLeaves(water), "water has the id " + water
                + " without the fluid flag alone");
        checkRows(helper, shapes, "water up to eight ninths", water, 0, 15, 0, 13, 0, 15);
        int wetSlab = shapes.idOf(slab(SlabType.BOTTOM, true));
        helper.assertTrue(SnowShapes.isFluid(wetSlab), "a waterlogged slab has the id " + wetSlab
                + " without the fluid flag");
        checkRows(helper, shapes, "a waterlogged bottom slab", wetSlab, 0, 15, 0, 13, 0, 15);
        int northStairs = shapes.idOf(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,
                Direction.NORTH));
        int southStairs = shapes.idOf(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,
                Direction.SOUTH));
        helper.assertTrue(northStairs != southStairs, "stairs facing north and south share the id " + northStairs);
        int countBefore = shapes.shapeCount();
        checkId(helper, "a bottom slab looked up again", shapes.idOf(slab(SlabType.BOTTOM, false)), bottomSlab);
        shapes.clearStateCache();
        checkId(helper, "a bottom slab after the cache is cleared", shapes.idOf(slab(SlabType.BOTTOM, false)),
                bottomSlab);
        helper.assertTrue(shapes.shapeCount() == countBefore, "the table grew from " + countBefore + " to "
                + shapes.shapeCount() + " shapes for states it had seen");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void volumeReadsTheWorldAndFollowsTheEye(GameTestHelper helper) {
        SnowShapes shapes = new SnowShapes();
        SnowShapeVolume volume = new SnowShapeVolume(shapes);
        BlockPos testPos = helper.absolutePos(BlockPos.ZERO);
        LevelChunk chunk = new LevelChunk(helper.getLevel(), new ChunkPos(testPos.getX() >> 4, testPos.getZ() >> 4));
        OneChunkLookup lookup = new OneChunkLookup(chunk, helper.getLevel().getMinBuildHeight(),
                helper.getLevel().getMaxBuildHeight());
        int chunkMinimumX = chunk.getPos().getMinBlockX();
        int chunkMinimumZ = chunk.getPos().getMinBlockZ();
        int eyeX = chunkMinimumX + SECTION_WIDTH / 2;
        int eyeY = lookup.minBuildHeight() + EYE_SECTION * SECTION_WIDTH + SECTION_WIDTH / 2;
        int eyeZ = chunkMinimumZ + SECTION_WIDTH / 2;
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(eyeY));
        section.setBlockState(2, 0, 2, Blocks.STONE.defaultBlockState());
        section.setBlockState(3, 1, 3, slab(SlabType.BOTTOM, false));
        section.setBlockState(4, 2, 4, Blocks.WATER.defaultBlockState());
        section.setBlockState(5, 3, 5, Blocks.OAK_LEAVES.defaultBlockState());
        helper.assertTrue(volume.follow(eyeX, eyeY, eyeZ), "the first follow did not fill the volume");
        helper.assertTrue(volume.idAt(eyeX, eyeY, eyeZ) == SnowShapes.EMPTY_SHAPE && !volume.isIdle(),
                "the filled volume does not start empty with the cube queued");
        helper.assertTrue(volume.read(lookup, 1) >= 1 && !volume.isIdle(), "a read of one cell emptied the queue");
        readAll(helper, volume, lookup, "the fill");
        int sectionMinimumY = eyeY & ~(SECTION_WIDTH - 1);
        checkId(helper, "the stone cell", volume.idAt(chunkMinimumX + 2, sectionMinimumY, chunkMinimumZ + 2),
                shapes.idOf(Blocks.STONE.defaultBlockState()));
        assertMatchesChunk(helper, volume, shapes, chunk, "the fill");
        int outsideX = chunkMinimumX - 3;
        checkId(helper, "a cell of a chunk that is not loaded", volume.idAt(outsideX, eyeY, eyeZ),
                SnowShapes.EMPTY_SHAPE);
        // A move of one block brings in cells in the slots of the cells that leave, empty until they are read:
        int leavingX = eyeX - SnowShapeVolume.SIZE / 2;
        volume.onBlockChanged(leavingX, eyeY, eyeZ, Blocks.STONE.defaultBlockState());
        checkId(helper, "the cell that is about to leave", volume.idAt(leavingX, eyeY, eyeZ),
                shapes.idOf(Blocks.STONE.defaultBlockState()));
        helper.assertTrue(!volume.follow(eyeX + 1, eyeY, eyeZ), "a move of one block filled the volume");
        helper.assertTrue(volume.originX() == leavingX + 1 && !volume.isIdle(), "the moved volume has the origin "
                + volume.originX() + " or nothing queued");
        readAll(helper, volume, lookup, "the move");
        checkId(helper, "the cell that entered in the slot of the stone", volume.idAt(leavingX + SnowShapeVolume.SIZE,
                eyeY, eyeZ), SnowShapes.EMPTY_SHAPE);
        assertMatchesChunk(helper, volume, shapes, chunk, "the move");
        // A chunk that arrives again is read again:
        section.setBlockState(15, 0, 8, Blocks.GLASS.defaultBlockState());
        volume.queueChunk(chunk.getPos().x, chunk.getPos().z);
        readAll(helper, volume, lookup, "the chunk sent again");
        assertMatchesChunk(helper, volume, shapes, chunk, "the chunk sent again");
        // A block change applies at once and marks only its brick dirty:
        volume.clearDirtyBricks();
        volume.onBlockChanged(eyeX, eyeY, eyeZ, Blocks.GLASS.defaultBlockState());
        checkId(helper, "the changed cell", volume.idAt(eyeX, eyeY, eyeZ),
                shapes.idOf(Blocks.GLASS.defaultBlockState()));
        int dirtyBricks = 0;
        for (int brick = 0; brick < SnowShapeVolume.BRICK_COUNT; brick++) {
            if (volume.isBrickDirty(brick)) {
                dirtyBricks++;
            }
        }
        helper.assertTrue(dirtyBricks == 1, "one block change marked " + dirtyBricks + " bricks dirty");
        volume.onBlockChanged(eyeX + SnowShapeVolume.SIZE, eyeY, eyeZ, Blocks.STONE.defaultBlockState());
        checkId(helper, "the cell of the same slot outside the cube", volume.idAt(eyeX, eyeY, eyeZ),
                shapes.idOf(Blocks.GLASS.defaultBlockState()));
        long fillsBefore = volume.fillCount();
        helper.assertTrue(volume.follow(eyeX + 1 + SnowShapeVolume.FILL_JUMP + 1, eyeY, eyeZ)
                && volume.fillCount() == fillsBefore + 1, "a jump past FILL_JUMP did not fill the volume");
        volume.forget();
        helper.assertTrue(!volume.hasPosition() && volume.isIdle(), "a forgotten volume keeps a position or a queue");
        helper.assertTrue(volume.follow(eyeX, eyeY, eyeZ), "the follow after forget did not fill the volume");
        readAll(helper, volume, lookup, "the fill after forget");
        assertMatchesChunk(helper, volume, shapes, chunk, "the fill after forget");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void flakesStopOnTheFaceTheyHit(GameTestHelper helper) {
        SnowShapeVolume volume = new SnowShapeVolume(new SnowShapes());
        int baseX = 1000;
        int baseY = 70;
        int baseZ = -2000;
        volume.follow(baseX, baseY, baseZ);
        volume.onBlockChanged(baseX, baseY, baseZ, Blocks.STONE.defaultBlockState());
        volume.onBlockChanged(baseX + 3, baseY, baseZ, slab(SlabType.BOTTOM, false));
        volume.onBlockChanged(baseX + 6, baseY, baseZ, Blocks.WATER.defaultBlockState());
        double[] hit = new double[3];
        int flake = 7;
        // Straight down onto the stone's top:
        checkHit(helper, "a fall onto stone", volume, flake, 0.3, point(baseX + 0.5, baseY + 1.3, baseZ + 0.5),
                point(baseX + 0.5, baseY + 0.9, baseZ + 0.5), 1, point(baseX + 0.5, baseY + 1.0, baseZ + 0.5));
        // Along x into its west face, and along -z into its south face:
        checkHit(helper, "wind into the west face", volume, flake, 0.3, point(baseX - 0.2, baseY + 0.5, baseZ + 0.5),
                point(baseX + 0.1, baseY + 0.5, baseZ + 0.5), 4, point(baseX, baseY + 0.5, baseZ + 0.5));
        checkHit(helper, "wind into the south face", volume, flake, 0.3, point(baseX + 0.5, baseY + 0.5, baseZ + 1.2),
                point(baseX + 0.5, baseY + 0.5, baseZ + 0.9), 3, point(baseX + 0.5, baseY + 0.5, baseZ + 1.0));
        // A slanted fall that enters through the side:
        checkHit(helper, "a slanted fall into the side", volume, flake, 0.3,
                point(baseX - 0.1, baseY + 0.62, baseZ + 0.5), point(baseX + 0.1, baseY + 0.58, baseZ + 0.5), 4,
                point(baseX, baseY + 0.6, baseZ + 0.5));
        // A long step tested in sub-steps:
        checkHit(helper, "a fall of 3.4 blocks in one step", volume, flake, 0.3,
                point(baseX + 0.5, baseY + 4.0, baseZ + 0.5), point(baseX + 0.5, baseY + 0.6, baseZ + 0.5), 1,
                point(baseX + 0.5, baseY + 1.0, baseZ + 0.5));
        // The slab stops a flake on its top half's floor and lets it pass above:
        checkHit(helper, "a fall onto a bottom slab", volume, flake, 0.3, point(baseX + 3.5, baseY + 0.8, baseZ + 0.5),
                point(baseX + 3.5, baseY + 0.3, baseZ + 0.5), 1, point(baseX + 3.5, baseY + 0.5, baseZ + 0.5));
        int passing = SnowFlakes.findHit(volume, flake, 0.3, point(baseX + 3.5, baseY + 0.95, baseZ + 0.5),
                point(baseX + 3.5, baseY + 0.55, baseZ + 0.5), hit);
        helper.assertTrue(passing == SnowFlakes.NO_HIT, "a fall above the bottom slab gives " + passing);
        int inside = SnowFlakes.findHit(volume, flake, 0.3, point(baseX + 0.5, baseY + 0.5, baseZ + 0.5),
                point(baseX + 0.5, baseY + 0.4, baseZ + 0.5), hit);
        helper.assertTrue(inside == SnowFlakes.STARTS_INSIDE, "a flake inside the stone gives " + inside);
        int waterId = SnowFlakes.hitIdAt(volume, flake, 0.3, point(baseX + 6.5, baseY + 0.5, baseZ + 0.5));
        helper.assertTrue(SnowShapes.isFluid(waterId), "a flake in water finds the id " + waterId
                + " without the fluid flag, so it would leave a splat");
        helper.assertTrue(SnowFlakes.hitIdAt(volume, flake, 0.3, point(baseX + 0.5, baseY + 0.5,
                baseZ + 0.5 + SnowShapeVolume.SIZE)) == 0, "a flake hits the stone's slot outside the volume");
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void flakesPassLeavesAtTheLeafPorosity(GameTestHelper helper) {
        SnowShapeVolume volume = new SnowShapeVolume(new SnowShapes());
        int baseX = -37;
        int baseY = 100;
        int baseZ = 12;
        volume.follow(baseX, baseY, baseZ);
        volume.onBlockChanged(baseX, baseY, baseZ, Blocks.OAK_LEAVES.defaultBlockState());
        double[] center = point(baseX + 0.5, baseY + 0.5, baseZ + 0.5);
        int passed = 0;
        for (int flake = 0; flake < LEAF_FLAKES; flake++) {
            if (SnowFlakes.hitIdAt(volume, flake, 1.0, center) != 0) {
                helper.fail("flake " + flake + " hits leaves of porosity 1");
                return;
            }
            if (SnowFlakes.hitIdAt(volume, flake, 0.0, center) == 0) {
                helper.fail("flake " + flake + " passes leaves of porosity 0");
                return;
            }
            if (SnowFlakes.hitIdAt(volume, flake, 0.3, center) == 0) {
                passed++;
            }
        }
        double passedShare = (double) passed / LEAF_FLAKES;
        helper.assertTrue(Math.abs(passedShare - 0.3) < 0.02, passedShare + " of the flakes pass leaves of "
                + "porosity 0.3");
        // A flake decides once per block, so it passes the same block at every point inside it:
        for (int flake = 0; flake < 100; flake++) {
            boolean passesCenter = SnowFlakes.hitIdAt(volume, flake, 0.3, center) == 0;
            boolean passesCorner = SnowFlakes.hitIdAt(volume, flake, 0.3,
                    point(baseX + 0.05, baseY + 0.95, baseZ + 0.05)) == 0;
            if (passesCenter != passesCorner) {
                helper.fail("flake " + flake + " passes the leaves at their center but not at a corner, or the other "
                        + "way");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 20)
    public static void flakesLeaveTheBoxAndSplatsFade(GameTestHelper helper) {
        double side = 40.0;
        helper.assertTrue(SnowFlakes.leavesBox(19.9, 0.2, 0.0, side),
                "a flake moving past the box face returned false from leavesBox instead of true");
        helper.assertTrue(!SnowFlakes.leavesBox(19.7, 0.2, 0.0, side),
                "a flake staying inside the box face returned true from leavesBox instead of false");
        helper.assertTrue(SnowFlakes.leavesBox(-19.5, 0.0, 1.0, side),
                "a camera stepping away from a flake behind it returned false from leavesBox instead of true");
        helper.assertTrue(SnowFlakes.leavesBox(3.0, 0.0, 1.0e6, side),
                "a teleported camera returned false from leavesBox instead of true");
        helper.assertTrue(SnowFlakes.splatVisible(19.5, -10.0, 20.0, side),
                "a splat within half a box returned false from splatVisible instead of true");
        helper.assertTrue(!SnowFlakes.splatVisible(20.1, 0.0, 0.0, side),
                "a splat beyond half a box along x returned true from splatVisible instead of false");
        helper.assertTrue(!SnowFlakes.splatVisible(0.0, -25.0, 0.0, side),
                "a splat beyond half a box along y returned true from splatVisible instead of false");
        helper.assertTrue(!SnowFlakes.splatVisible(0.0, 0.0, 25.0, side),
                "a splat beyond half a box along z returned true from splatVisible instead of false");
        checkNear(helper, "sub-steps of no move", SnowFlakes.substepCount(0.0), 1.0);
        checkNear(helper, "sub-steps of a quarter block", SnowFlakes.substepCount(0.25), 1.0);
        checkNear(helper, "sub-steps of a bit more", SnowFlakes.substepCount(0.26), 2.0);
        checkNear(helper, "sub-steps of 100 blocks", SnowFlakes.substepCount(100.0), SnowFlakes.MOST_SUBSTEPS);
        checkNear(helper, "a new splat", SnowFlakes.splatWeight(0.0, 1.5), 1.0);
        checkNear(helper, "a splat at half its life", SnowFlakes.splatWeight(0.75, 1.5), 1.0);
        checkNear(helper, "a splat at three quarters of its life", SnowFlakes.splatWeight(1.125, 1.5), 0.5);
        checkNear(helper, "a splat at the end of its life", SnowFlakes.splatWeight(1.5, 1.5), 0.0);
        checkNear(helper, "a splat without a life", SnowFlakes.splatWeight(0.0, 0.0), 0.0);
        helper.assertTrue(SnowFlakes.STOPPED_PHASE > 2.0 * Math.PI && SnowFlakes.SPLAT_FACE_STRIDE
                > SkySetting.SNOW_SPLAT_SECONDS.maximum(), "the stopped phase or the splat stride overlaps the values "
                + "below it");
        helper.succeed();
    }

    private static void checkHit(GameTestHelper helper, String what, SnowShapeVolume volume, int flake,
            double leafPorosity, double[] start, double[] end, int expectedFace, double[] expectedPoint) {
        double[] hit = new double[3];
        int face = SnowFlakes.findHit(volume, flake, leafPorosity, start, end, hit);
        helper.assertTrue(face == expectedFace, what + " hits the face " + face + " instead of " + expectedFace);
        for (int axis = 0; axis < 3; axis++) {
            helper.assertTrue(Math.abs(hit[axis] - expectedPoint[axis]) <= HIT_TOLERANCE, what + " hits at " + hit[0]
                    + " " + hit[1] + " " + hit[2] + " instead of " + expectedPoint[0] + " " + expectedPoint[1] + " "
                    + expectedPoint[2]);
        }
    }

    /**
     * Checks that every voxel inside a box of voxels is filled and every other voxel is empty.
     */
    private static void checkRows(GameTestHelper helper, SnowShapes shapes, String what, int id, int firstX, int lastX,
            int firstY, int lastY, int firstZ, int lastZ) {
        int shape = SnowShapes.shapeIndex(id);
        for (int y = 0; y < SnowShapes.SIDE; y++) {
            for (int z = 0; z < SnowShapes.SIDE; z++) {
                for (int x = 0; x < SnowShapes.SIDE; x++) {
                    boolean expected = x >= firstX && x <= lastX && y >= firstY && y <= lastY && z >= firstZ
                            && z <= lastZ;
                    if (shapes.isFilled(shape, x, y, z) != expected) {
                        helper.fail(what + " has the voxel " + x + " " + y + " " + z
                                + (expected ? " empty" : " filled"));
                        return;
                    }
                }
            }
        }
    }

    /**
     * Checks that every cell of the cube in the chunk holds the id of the chunk's block there.
     */
    private static void assertMatchesChunk(GameTestHelper helper, SnowShapeVolume volume, SnowShapes shapes,
            LevelChunk chunk, String what) {
        int chunkMinimumX = chunk.getPos().getMinBlockX();
        int chunkMinimumZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int y = volume.originY(); y <= volume.originY() + SnowShapeVolume.MASK; y++) {
            for (int z = chunkMinimumZ; z < chunkMinimumZ + SECTION_WIDTH; z++) {
                for (int x = chunkMinimumX; x < chunkMinimumX + SECTION_WIDTH; x++) {
                    if (!volume.contains(x, y, z)) {
                        continue;
                    }
                    int expected = shapes.idOf(chunk.getBlockState(position.set(x, y, z)));
                    if (volume.idAt(x, y, z) != expected) {
                        helper.fail("After " + what + ", the cell " + x + " " + y + " " + z + " holds "
                                + volume.idAt(x, y, z) + " instead of " + expected);
                        return;
                    }
                }
            }
        }
    }

    private static void readAll(GameTestHelper helper, SnowShapeVolume volume, OneChunkLookup lookup, String what) {
        volume.read(lookup, READ_BUDGET);
        helper.assertTrue(volume.isIdle(), "After " + what + ", reads of " + READ_BUDGET + " cells left "
                + volume.queuedBoxCount() + " boxes queued");
    }

    private static BlockState slab(SlabType type, boolean waterlogged) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type)
                .setValue(BlockStateProperties.WATERLOGGED, waterlogged);
    }

    private static double[] point(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    private static void checkId(GameTestHelper helper, String what, int actual, int expected) {
        helper.assertTrue(actual == expected, what + " has the id " + actual + " instead of " + expected);
    }

    private static void checkNear(GameTestHelper helper, String what, double actual, double expected) {
        helper.assertTrue(Math.abs(actual - expected) <= TOLERANCE * Math.max(1.0, Math.abs(expected)), what + " is "
                + actual + " instead of " + expected);
    }

    /**
     * A chunk lookup that returns one standalone chunk at its own position and nothing at any other.
     */
    private static final class OneChunkLookup implements SnowFieldReader.ChunkLookup {
        private final LevelChunk chunk;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        private OneChunkLookup(LevelChunk chunk, int minBuildHeight, int maxBuildHeight) {
            this.chunk = chunk;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        @Nullable
        @Override
        public LevelChunk loadedChunk(int chunkX, int chunkZ) {
            return chunkX == chunk.getPos().x && chunkZ == chunk.getPos().z ? chunk : null;
        }

        @Override
        public int minBuildHeight() {
            return minBuildHeight;
        }

        @Override
        public int maxBuildHeight() {
            return maxBuildHeight;
        }
    }
}
