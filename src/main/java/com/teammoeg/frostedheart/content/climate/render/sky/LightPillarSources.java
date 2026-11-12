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

import com.mojang.logging.LogUtils;
import com.teammoeg.frostedheart.FHMain;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Finds the lights around the player that can show light pillars, on the client tick.
 * Each tick tests a fixed number of chunk sections within pillarChunkRadius by their block palette, and walks the blocks
 * of at most a fixed number of sections whose palette holds a light. Sections too far below the surface for sky light
 * are skipped. A light counts by its sky light, which leaves out lights indoors and in caves, and lights are merged into
 * LightPillarClusters. Each full pass replaces the clusters of the last one, and LightPillarSelection chooses from them
 * every tick. The scan runs only while the calculated sky is allowed and the diamond dust is above zero.
 * Client thread only.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FHMain.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LightPillarSources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SECTION_TESTS_PER_TICK = 256;
    private static final int SECTION_BLOCKS = 16 * 16 * 16;
    private static final int BLOCKS_WALKED_PER_TICK = 4 * SECTION_BLOCKS;
    private static final int LISTED_PILLARS = 10;
    /**
     * Lowest surfaces are kept for the chunks of a pass and one ring of chunks around them, at the largest radius.
     */
    private static final int SURFACE_GRID_SIDE = 2 * (int) SkySetting.PILLAR_CHUNK_RADIUS.maximum() + 3;
    private static final int SURFACE_NOT_READ = Integer.MIN_VALUE;
    private static final int SURFACE_UNLOADED = Integer.MAX_VALUE;
    /**
     * The share of the way to the ground under the camera that the dust's bottom moves per tick, which follows the
     * ground over about 30 seconds.
     */
    private static final double DUST_BOTTOM_STEP = 1.0 - Math.exp(-0.05 / 30.0);
    /**
     * A horizontal move of the camera in one tick beyond this is taken as a teleport, faster than any travel.
     */
    private static final double TELEPORT_BLOCKS = 16.0;
    private static final Predicate<BlockState> SHOWS_PILLAR = LightPillarEmitters::showsPillar;
    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};

    private static final LightPillarClusters clusters = new LightPillarClusters();
    private static final LightPillarSelection selection = new LightPillarSelection();
    private static final Reference2ObjectOpenHashMap<BlockState, float[]> emitterColors =
            new Reference2ObjectOpenHashMap<>();
    private static final BlockPos.MutableBlockPos lightPosition = new BlockPos.MutableBlockPos();
    private static final int[] lowestSurfaces = new int[SURFACE_GRID_SIDE * SURFACE_GRID_SIDE];
    private static int[] texturePixels = new int[SECTION_BLOCKS];

    @Nullable
    private static ClientLevel scannedLevel;
    private static boolean failed;
    private static boolean scanning;
    private static boolean passRunning;
    private static boolean passFinishedThisTick;
    private static int centerChunkX;
    private static int centerChunkZ;
    private static int passRadius;
    private static int columnIndex;
    /**
     * The next section to test in the column, or -1 before the column's first section is found.
     */
    private static int sectionIndex;
    private static double dustBottomY = Double.NaN;
    private static double lastEyeX = Double.NaN;
    private static double lastEyeZ = Double.NaN;

    private static final PassStatistics currentPass = new PassStatistics();
    private static final PassStatistics lastPass = new PassStatistics();

    private LightPillarSources() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != scannedLevel) {
            reset();
            scannedLevel = level;
        }
        if (level == null || failed) {
            return;
        }
        long start = System.nanoTime();
        try {
            tick(minecraft, level);
        } catch (RuntimeException exception) {
            failed = true;
            reset();
            LOGGER.error("Light pillars stopped after an error. They stay off until the calculated sky is turned on "
                    + "again.", exception);
            return;
        }
        if (scanning) {
            currentPass.addTick(System.nanoTime() - start);
        }
        if (passFinishedThisTick) {
            lastPass.copy(currentPass);
            currentPass.clear();
            passFinishedThisTick = false;
        }
    }

    private static void tick(Minecraft minecraft, ClientLevel level) {
        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        updateDustBottom(level, eye);
        scanning = CalculatedSkyRenderer.isAllowed(level) && DiamondDustExtinction.lastValue() > 0.0;
        if (!scanning) {
            if (passRunning) {
                clusters.abandonPass();
                currentPass.clear();
                passRunning = false;
            }
            selection.chooseNone();
            return;
        }
        scan(minecraft, level, eye);
        selection.choose(clusters, eye.x, eye.y, eye.z, dustBottomY);
    }

    /**
     * Moves the dust's bottom toward the top of the ground under the camera, or sets it there at first and after the
     * camera jumped further than TELEPORT_BLOCKS in one tick. Until the ground under the camera is loaded after such a
     * jump, the bottom is unknown and no cluster is chosen.
     */
    private static void updateDustBottom(ClientLevel level, Vec3 eye) {
        double movedX = eye.x - lastEyeX;
        double movedZ = eye.z - lastEyeZ;
        if (!(movedX * movedX + movedZ * movedZ <= TELEPORT_BLOCKS * TELEPORT_BLOCKS)) {
            dustBottomY = Double.NaN;
        }
        lastEyeX = eye.x;
        lastEyeZ = eye.z;
        int blockX = Mth.floor(eye.x);
        int blockZ = Mth.floor(eye.z);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
            return;
        }
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        dustBottomY = Double.isNaN(dustBottomY) ? ground : dustBottomY + (ground - dustBottomY) * DUST_BOTTOM_STEP;
    }

    private static void scan(Minecraft minecraft, ClientLevel level, Vec3 eye) {
        if (!passRunning) {
            startPass(minecraft, eye);
        }
        int side = 2 * passRadius + 1;
        int columns = side * side;
        int tests = 0;
        int blocks = 0;
        while (tests < SECTION_TESTS_PER_TICK && blocks < BLOCKS_WALKED_PER_TICK) {
            if (columnIndex >= columns) {
                finishPass();
                return;
            }
            int offsetX = columnIndex % side - passRadius;
            int offsetZ = columnIndex / side - passRadius;
            LevelChunk chunk = offsetX * offsetX + offsetZ * offsetZ <= passRadius * passRadius + passRadius
                    ? level.getChunkSource().getChunk(centerChunkX + offsetX, centerChunkZ + offsetZ, false) : null;
            if (chunk == null) {
                nextColumn();
                continue;
            }
            if (sectionIndex < 0) {
                sectionIndex = firstSectionIndex(level, offsetX, offsetZ);
            }
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= sections.length) {
                nextColumn();
                continue;
            }
            LevelChunkSection section = sections[sectionIndex];
            tests++;
            currentPass.sectionsTested++;
            if (!section.hasOnlyAir() && section.maybeHas(SHOWS_PILLAR)) {
                walk(minecraft, level, section, centerChunkX + offsetX,
                        level.getSectionYFromSectionIndex(sectionIndex), centerChunkZ + offsetZ);
                blocks += SECTION_BLOCKS;
                currentPass.sectionsWalked++;
            }
            sectionIndex++;
        }
    }

    private static void startPass(Minecraft minecraft, Vec3 eye) {
        int configured = (int) Math.round(SkySetting.PILLAR_CHUNK_RADIUS.get());
        int largest = (int) SkySetting.PILLAR_CHUNK_RADIUS.maximum();
        passRadius = Math.max(1, Math.min(Math.min(configured, largest),
                minecraft.options.getEffectiveRenderDistance()));
        centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(eye.x));
        centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(eye.z));
        columnIndex = 0;
        sectionIndex = -1;
        Arrays.fill(lowestSurfaces, SURFACE_NOT_READ);
        passRunning = true;
    }

    private static void nextColumn() {
        columnIndex++;
        sectionIndex = -1;
    }

    private static void finishPass() {
        clusters.finishPass();
        currentPass.clusters = clusters.count();
        passRunning = false;
        passFinishedThisTick = true;
    }

    /**
     * The index of the column's lowest section that can hold a light with enough sky light for a pillar.
     */
    private static int firstSectionIndex(ClientLevel level, int offsetX, int offsetZ) {
        int lowest = SURFACE_UNLOADED;
        for (int aroundX = -1; aroundX <= 1; aroundX++) {
            for (int aroundZ = -1; aroundZ <= 1; aroundZ++) {
                lowest = Math.min(lowest, lowestSurface(level, offsetX + aroundX, offsetZ + aroundZ));
            }
        }
        int sectionCount = level.getSectionsCount();
        for (int index = 0; index < sectionCount; index++) {
            int topY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(index)) + 15;
            if (LightPillarClusters.sectionCanShowPillars(topY, lowest)) {
                return index;
            }
        }
        return sectionCount;
    }

    /**
     * The height of the lowest top block of the chunk at the offset from the pass's center, read once per pass, or
     * SURFACE_UNLOADED for a chunk that is not loaded.
     */
    private static int lowestSurface(ClientLevel level, int offsetX, int offsetZ) {
        int gridIndex = (offsetX + passRadius + 1) * SURFACE_GRID_SIDE + offsetZ + passRadius + 1;
        int lowest = lowestSurfaces[gridIndex];
        if (lowest != SURFACE_NOT_READ) {
            return lowest;
        }
        lowest = SURFACE_UNLOADED;
        LevelChunk chunk = level.getChunkSource().getChunk(centerChunkX + offsetX, centerChunkZ + offsetZ, false);
        if (chunk != null) {
            for (int blockX = 0; blockX < 16; blockX++) {
                for (int blockZ = 0; blockZ < 16; blockZ++) {
                    lowest = Math.min(lowest, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ));
                }
            }
        }
        lowestSurfaces[gridIndex] = lowest;
        return lowest;
    }

    /**
     * Adds every light of the section with sky light above 8 at itself or the block above to the clusters.
     */
    @SuppressWarnings("deprecation")
    private static void walk(Minecraft minecraft, ClientLevel level, LevelChunkSection section, int chunkX,
            int sectionY, int chunkZ) {
        int originX = SectionPos.sectionToBlockCoord(chunkX);
        int originY = SectionPos.sectionToBlockCoord(sectionY);
        int originZ = SectionPos.sectionToBlockCoord(chunkZ);
        clusters.beginSection(chunkX, sectionY, chunkZ);
        for (int blockY = 0; blockY < 16; blockY++) {
            for (int blockZ = 0; blockZ < 16; blockZ++) {
                for (int blockX = 0; blockX < 16; blockX++) {
                    BlockState state = section.getBlockState(blockX, blockY, blockZ);
                    int emission = state.getLightEmission();
                    if (emission < LightPillarEmitters.MINIMUM_EMISSION) {
                        continue;
                    }
                    lightPosition.set(originX + blockX, originY + blockY, originZ + blockZ);
                    int skyLight = level.getBrightness(LightLayer.SKY, lightPosition);
                    lightPosition.setY(lightPosition.getY() + 1);
                    skyLight = Math.max(skyLight, level.getBrightness(LightLayer.SKY, lightPosition));
                    if (skyLight <= 8) {
                        continue;
                    }
                    float strength = LightPillarEmitters.strength(emission) * Math.min(1.0F, (skyLight - 8) / 7.0F);
                    float[] color = emitterColor(minecraft, state);
                    clusters.addLight(originX + blockX, originY + blockY, originZ + blockZ, color[0] * strength,
                            color[1] * strength, color[2] * strength);
                    currentPass.lights++;
                }
            }
        }
        clusters.endSection();
    }

    private static float[] emitterColor(Minecraft minecraft, BlockState state) {
        float[] color = emitterColors.get(state);
        if (color == null) {
            color = findEmitterColor(minecraft, state);
            emitterColors.put(state, color);
        }
        return color;
    }

    /**
     * The state's color from the override table, or else from its particle texture, or white when the texture cannot
     * be read.
     */
    private static float[] findEmitterColor(Minecraft minecraft, BlockState state) {
        float[] override = LightPillarEmitters.overrides().overrideColor(state);
        if (override != null) {
            return override;
        }
        float[] color = new float[3];
        try {
            TextureAtlasSprite sprite = minecraft.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            int count = width * height;
            if (texturePixels.length < count) {
                texturePixels = new int[count];
            }
            for (int pixelY = 0; pixelY < height; pixelY++) {
                for (int pixelX = 0; pixelX < width; pixelX++) {
                    texturePixels[pixelY * width + pixelX] = sprite.getPixelRGBA(0, pixelX, pixelY);
                }
            }
            LightPillarEmitters.averageTextureColor(texturePixels, count, color);
        } catch (RuntimeException exception) {
            return WHITE;
        }
        return color;
    }

    private static void reset() {
        clusters.clear();
        selection.clear();
        passRunning = false;
        passFinishedThisTick = false;
        scanning = false;
        dustBottomY = Double.NaN;
        lastEyeX = Double.NaN;
        lastEyeZ = Double.NaN;
        currentPass.clear();
        lastPass.clear();
    }

    /**
     * Forgets the colors found for block states, after a resource reload.
     */
    public static void clearColors() {
        emitterColors.clear();
    }

    public static void clearFailure() {
        failed = false;
    }

    /**
     * A line about the scan and the last finished pass, for the client command.
     */
    public static String describe(ClientLevel level) {
        String state;
        if (failed) {
            state = "stopped after an error, see the log";
        } else if (!CalculatedSkyRenderer.isAllowed(level)) {
            state = "idle while the calculated sky is not drawn";
        } else if (!(DiamondDustExtinction.lastValue() > 0.0)) {
            state = "idle without diamond dust";
        } else {
            state = "scanning " + passRadius + " chunks around";
        }
        String pass = lastPass.ticks == 0 ? "no pass finished yet" : String.format(Locale.ROOT,
                "last pass %d ticks with %d sections tested, %d walked, %d lights in %d clusters, "
                        + "tick %.3f ms mean and %.3f ms most",
                lastPass.ticks, lastPass.sectionsTested, lastPass.sectionsWalked, lastPass.lights, lastPass.clusters,
                lastPass.nanos / 1.0e6 / lastPass.ticks, lastPass.mostNanos / 1.0e6);
        return String.format(Locale.ROOT, "Light pillars %s, %s; %d chosen, %d of %d slots used, dust bottom y %.1f",
                state, pass, selection.chosenCount(), selection.usedCount(), LightPillarSelection.SLOT_COUNT,
                dustBottomY);
    }

    /**
     * Lines about the pillars in use with the highest scores, for the client command.
     */
    public static List<String> describePillars(Vec3 eye) {
        List<Integer> used = new ArrayList<>();
        for (int slot = 0; slot < LightPillarSelection.SLOT_COUNT; slot++) {
            if (selection.isUsed(slot)) {
                used.add(slot);
            }
        }
        used.sort(Comparator.comparingDouble(slot -> -selection.slotScore(slot)));
        List<String> lines = new ArrayList<>();
        for (int rank = 0; rank < Math.min(LISTED_PILLARS, used.size()); rank++) {
            int slot = used.get(rank);
            float brightest = Math.max(selection.red(slot), Math.max(selection.green(slot), selection.blue(slot)));
            double distance = Math.hypot(selection.x(slot) - eye.x, selection.z(slot) - eye.z);
            lines.add(String.format(Locale.ROOT,
                    "%d. at %.1f %.1f %.1f, %.1f blocks away, color %.2f %.2f %.2f, strength %.2f, score %.3g, "
                            + "fade %.1f",
                    rank + 1, selection.x(slot), selection.y(slot), selection.z(slot), distance,
                    selection.red(slot) / brightest, selection.green(slot) / brightest,
                    selection.blue(slot) / brightest, brightest, selection.slotScore(slot), selection.fade(slot, 1.0F)));
        }
        return lines;
    }

    /**
     * Counts of one pass of the scan, and the time its ticks took.
     */
    private static final class PassStatistics {
        private int ticks;
        private int sectionsTested;
        private int sectionsWalked;
        private int lights;
        private int clusters;
        private long nanos;
        private long mostNanos;

        private void addTick(long tickNanos) {
            ticks++;
            nanos += tickNanos;
            mostNanos = Math.max(mostNanos, tickNanos);
        }

        private void copy(PassStatistics other) {
            ticks = other.ticks;
            sectionsTested = other.sectionsTested;
            sectionsWalked = other.sectionsWalked;
            lights = other.lights;
            clusters = other.clusters;
            nanos = other.nanos;
            mostNanos = other.mostNanos;
        }

        private void clear() {
            ticks = 0;
            sectionsTested = 0;
            sectionsWalked = 0;
            lights = 0;
            clusters = 0;
            nanos = 0;
            mostNanos = 0;
        }
    }
}
