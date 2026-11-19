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
import com.teammoeg.frostedheart.FHMain;
import com.teammoeg.frostedheart.content.climate.render.sky.CalculatedSkyRenderer;
import com.teammoeg.frostedheart.content.climate.render.sky.LightPillarSources;
import com.teammoeg.frostedheart.content.climate.render.sky.SkySetting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Keeps the snow field of the fog on the client: whether it is wanted, which level it follows, and the reading of the
 * world and the solving of the field that each frame pays for out of SkySetting.FOG_FIELD_BUDGET_MICROS.
 * The field, its faces and its reader are made on the first client tick that wants them and kept for the rest of the
 * session, about 12 MiB. Each client tick moves the field to the eye, which fills it after a jump of more than
 * SnowField.FILL_JUMP blocks or a level change, and each frame reads cells and steps the solver until the budget is
 * spent. LightPillarWorldChanges passes the block changes and the chunk loads of the tracked level, and a tag update
 * that arrives from the server clears the masks of the states and fills the field again.
 * An error turns the field off, and it comes back once the fog is turned off and on again.
 * Client thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientSnowField {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TICKS_PER_SECOND = 20;
    private static final int READ_STEPS_PER_BATCH = 256;
    private static final int SOLVER_STEPS_PER_BATCH = 1024;
    private static final double NANOSECONDS_PER_MICROSECOND = 1000.0;
    private static final double NANOSECONDS_PER_MILLISECOND = 1.0e6;

    private static final SnowFieldReader.LevelChunkLookup lookup = new SnowFieldReader.LevelChunkLookup();

    @Nullable
    private static SnowFaces faces;
    @Nullable
    private static SnowField field;
    @Nullable
    private static SnowFieldReader reader;
    @Nullable
    private static ClientLevel trackedLevel;
    /**
     * The share of a face that leaves let snow through, as last handed to faces.
     */
    private static double leafPorosity;
    /**
     * Whether a tag update asked for a fill on the next client tick.
     */
    private static boolean fillRequested;
    private static boolean failed;
    private static int ticksThisSecond;
    private static long currentCellsRead;
    private static long currentSolverSteps;
    private static int currentBlockChanges;
    private static long currentNanos;
    private static int currentFrames;
    private static long currentLargestFrameNanos;
    private static long currentBytes;
    private static long lastCellsRead;
    private static long lastSolverSteps;
    private static int lastBlockChanges;
    private static long lastNanos;
    private static int lastFrames;
    private static long lastLargestFrameNanos;
    private static long lastBytes;

    private ClientSnowField() {
    }

    /**
     * Rolls the per second statistics, stops tracking when the field is not wanted, and moves the field to the eye.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        rollStatistics();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !isWanted(level)) {
            failed = false;
            stopTracking();
            return;
        }
        if (failed) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 eye = camera.getPosition();
        int eyeX = Mth.floor(eye.x);
        int eyeY = Mth.floor(eye.y);
        int eyeZ = Mth.floor(eye.z);
        long allocatedBefore = LightPillarSources.allocatedBytes();
        long start = System.nanoTime();
        try {
            followEye(level, eyeX, eyeY, eyeZ);
        } catch (RuntimeException exception) {
            failed = true;
            stopTracking();
            LOGGER.error("The snow field of the fog stopped after an error. It comes back once the fog is turned off "
                    + "and on again.", exception);
        }
        currentNanos += System.nanoTime() - start;
        currentBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
    }

    /**
     * Reads cells and steps the solver while the tracked level is the one of the game, until the frame budget is spent
     * or nothing is left to do.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        ClientLevel level = trackedLevel;
        SnowField currentField = field;
        SnowFieldReader currentReader = reader;
        if (failed || level == null || currentField == null || currentReader == null
                || level != Minecraft.getInstance().level) {
            return;
        }
        long allocatedBefore = LightPillarSources.allocatedBytes();
        long start = System.nanoTime();
        long cellsReadBefore = currentReader.cellsRead();
        double budgetMicros = SkySetting.FOG_FIELD_BUDGET_MICROS.get();
        long deadline = start + (long) (budgetMicros * NANOSECONDS_PER_MICROSECOND);
        int solverSteps = 0;
        try {
            while (true) {
                boolean worked = false;
                if (!currentReader.isIdle()) {
                    currentReader.read(lookup, READ_STEPS_PER_BATCH);
                    worked = true;
                }
                if (!currentField.isDrained()) {
                    solverSteps += currentField.process(SOLVER_STEPS_PER_BATCH);
                    worked = true;
                }
                if (!worked || System.nanoTime() >= deadline) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            failed = true;
            stopTracking();
            LOGGER.error("The snow field of the fog stopped after an error. It comes back once the fog is turned off "
                    + "and on again.", exception);
        }
        long nanos = System.nanoTime() - start;
        currentNanos += nanos;
        if (nanos > currentLargestFrameNanos) {
            currentLargestFrameNanos = nanos;
        }
        currentFrames++;
        currentCellsRead += currentReader.cellsRead() - cellsReadBefore;
        currentSolverSteps += solverSteps;
        currentBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
    }

    /**
     * Clears the mask sets of the block states when the leaves tag may have changed, and fills the field again on the
     * next client tick, since cells that were read before hold mask sets that may be wrong.
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED || faces == null) {
            return;
        }
        faces.clearStateCache();
        fillRequested = true;
    }

    /**
     * Applies a block change of the tracked level to the field, and counts it and its time in the statistics.
     * @param level the level the change is in
     * @param pos the block position of the change
     * @param newState the block state the block has now
     */
    public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState newState) {
        SnowFieldReader currentReader = reader;
        if (level != trackedLevel || currentReader == null) {
            return;
        }
        long allocatedBefore = LightPillarSources.allocatedBytes();
        long start = System.nanoTime();
        try {
            currentReader.onBlockChanged(lookup, pos.getX(), pos.getY(), pos.getZ(), newState);
            currentBlockChanges++;
        } catch (RuntimeException exception) {
            failed = true;
            stopTracking();
            LOGGER.error("The snow field of the fog stopped after an error. It comes back once the fog is turned off "
                    + "and on again.", exception);
        }
        currentNanos += System.nanoTime() - start;
        currentBytes += LightPillarSources.allocatedBytes() - allocatedBefore;
    }

    /**
     * Queues the columns of a chunk that arrived in the tracked level, a new one or one sent again.
     * @param level the level the chunk is in
     * @param chunkX the chunk x
     * @param chunkZ the chunk z
     */
    public static void onChunkLoaded(ClientLevel level, int chunkX, int chunkZ) {
        SnowFieldReader currentReader = reader;
        if (level == trackedLevel && currentReader != null) {
            currentReader.queueChunk(chunkX, chunkZ);
        }
    }

    /**
     * A line about the work of the last second and the state of the field, for the client command.
     */
    public static String describe() {
        SnowField currentField = field;
        SnowFieldReader currentReader = reader;
        if (trackedLevel == null || currentField == null || currentReader == null) {
            return "Snow field follows no level while the fog is off or the calculated sky is not drawn";
        }
        String allocated = LightPillarSources.countsAllocations()
                ? String.format(Locale.ROOT, "%d bytes allocated", lastBytes)
                : "allocations not counted by this JVM";
        String solvedBox = coversWholeCube(currentField) ? "the solved box covers the whole cube"
                : "the solved box covers part of the cube";
        return String.format(Locale.ROOT,
                "Snow field in the last second: %d cells read, %d solver steps, %d block changes, %.3f ms of work per "
                        + "tick over %d frames with the largest frame at %.3f ms, %s; %d boxes waiting, %s, %d queue "
                        + "entries, %d spilled cells, %d fills, %d resolveAll calls, %d spills, %d box overflows, %s",
                lastCellsRead, lastSolverSteps, lastBlockChanges,
                lastNanos * 1.0e-6 / TICKS_PER_SECOND, lastFrames,
                lastLargestFrameNanos / NANOSECONDS_PER_MILLISECOND, allocated, currentReader.queuedBoxCount(),
                currentField.isDrained() ? "solver drained" : "solver working", currentField.queuedEntryCount(),
                currentField.spilledCellCount(), currentField.fillCount(), currentField.resolveAllCount(),
                currentField.spillCount(), currentReader.boxOverflowCount(), solvedBox);
    }

    /**
     * Whether the field may follow the level, which needs the calculated sky to be drawn there and the fog on.
     */
    private static boolean isWanted(ClientLevel level) {
        return CalculatedSkyRenderer.isAllowed(level) && SkySetting.FOG_ENABLED.isOn();
    }

    /**
     * Creates the field on first use, then fills it for a new level or a requested fill and moves it otherwise, and
     * applies a leaf porosity that changed since the last application.
     */
    private static void followEye(ClientLevel level, int eyeX, int eyeY, int eyeZ) {
        if (faces == null || field == null || reader == null) {
            double porosity = SkySetting.FOG_LEAF_POROSITY.get();
            SnowFaces createdFaces = new SnowFaces(porosity);
            SnowField createdField = new SnowField(createdFaces, SnowField.DEFAULT_SIZE,
                    SnowField.DEFAULT_QUEUE_CAPACITY, SnowField.DEFAULT_RESET_CAPACITY);
            faces = createdFaces;
            field = createdField;
            reader = new SnowFieldReader(createdField, createdFaces);
            leafPorosity = porosity;
        }
        if (level != trackedLevel) {
            trackedLevel = level;
            lookup.setLevel(level);
            faces.clearStateCache();
            applyLeafPorosity();
            fillRequested = false;
            reader.fill(eyeX, eyeY, eyeZ);
        } else if (fillRequested) {
            fillRequested = false;
            reader.fill(eyeX, eyeY, eyeZ);
        } else {
            reader.follow(eyeX, eyeY, eyeZ);
        }
        if (Double.compare(SkySetting.FOG_LEAF_POROSITY.get(), leafPorosity) != 0) {
            applyLeafPorosity();
            field.resolveAll();
        }
    }

    /**
     * Hands the leaf porosity of the config to the faces and remembers it.
     */
    private static void applyLeafPorosity() {
        double porosity = SkySetting.FOG_LEAF_POROSITY.get();
        SnowFaces currentFaces = faces;
        if (currentFaces != null) {
            currentFaces.setLeafPorosity(porosity);
        }
        leafPorosity = porosity;
    }

    /**
     * Stops following the level, which keeps the field for the next time it is wanted.
     */
    private static void stopTracking() {
        if (trackedLevel != null) {
            trackedLevel = null;
            lookup.setLevel(null);
        }
    }

    /**
     * Whether the solved box of the field is the whole cube.
     */
    private static boolean coversWholeCube(SnowField currentField) {
        if (!currentField.hasSolvedBox()) {
            return false;
        }
        int maximumOffset = currentField.size() - 1;
        return currentField.solvedMinX() == currentField.originX()
                && currentField.solvedMaxX() == currentField.originX() + maximumOffset
                && currentField.solvedMinY() == currentField.originY()
                && currentField.solvedMaxY() == currentField.originY() + maximumOffset
                && currentField.solvedMinZ() == currentField.originZ()
                && currentField.solvedMaxZ() == currentField.originZ() + maximumOffset;
    }

    /**
     * Rolls the statistics over once a second.
     */
    private static void rollStatistics() {
        ticksThisSecond++;
        if (ticksThisSecond < TICKS_PER_SECOND) {
            return;
        }
        lastCellsRead = currentCellsRead;
        lastSolverSteps = currentSolverSteps;
        lastBlockChanges = currentBlockChanges;
        lastNanos = currentNanos;
        lastFrames = currentFrames;
        lastLargestFrameNanos = currentLargestFrameNanos;
        lastBytes = currentBytes;
        currentCellsRead = 0L;
        currentSolverSteps = 0L;
        currentBlockChanges = 0;
        currentNanos = 0L;
        currentFrames = 0;
        currentLargestFrameNanos = 0L;
        currentBytes = 0L;
        ticksThisSecond = 0;
    }
}
