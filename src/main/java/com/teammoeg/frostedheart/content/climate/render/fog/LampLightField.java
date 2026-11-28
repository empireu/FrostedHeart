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

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The block light of the lamps around the eye as it lights outdoor air, kept in up to CASCADE_COUNT cascades of
 * cells, 1, 2, 4, 8 and 16 blocks across and 1, 2, 4, 4 and 4 blocks tall, each a LampLightCascade of ACROSS cells
 * across, centered on the eye and aligned to the world grid.
 * A cell holds two shares of its blocks. The air share counts its outdoor air, blocks with sky light above 0 in the
 * near cascades and with sky light OPEN_SKY_LIGHT from FIRST_FAR_CASCADE on. The light share sums vanilla's block
 * brightness brightness(level) over that outdoor air. Their ratio is the mean brightness of the cell's outdoor air,
 * and filtering both before dividing lets cells without outdoor air add nothing.
 * Cells change by whole chunk sections. Light changes, chunk unloads, windows that move and whole fills queue chunk
 * columns with a range of sections and a mask of cascades, and process reads them from a LightSource within a budget,
 * the urgent queue of changes before the bulk queue of fills. A window moves once the eye is RECENTER_DISTANCE cells
 * from its center, and the cells it brings in hold dark open air until read. Single thread.
 */
public final class LampLightField {
    public static final int CASCADE_COUNT = 5;
    public static final int ACROSS = 64;
    public static final int COARSE_WORDS_PER_CASCADE = LampLightCascade.COARSE_WORDS_PER_CASCADE;
    public static final int COARSE_VECS_PER_CASCADE = COARSE_WORDS_PER_CASCADE / 4;
    public static final int COARSE_VECS_TOTAL = CASCADE_COUNT * COARSE_VECS_PER_CASCADE;
    public static final int FIRST_FAR_CASCADE = 2;
    public static final int OPEN_SKY_LIGHT = 15;
    public static final int BRIGHTEST_LEVEL = 15;
    /**
     * Vanilla's block brightness at full block light.
     */
    public static final float BRIGHTEST = 1.5F;
    /**
     * An air share at or below this counts as no outdoor air, which adds no light.
     */
    public static final float NO_AIR = 1.0e-5F;
    /**
     * Where the blend from one cascade to the next starts and ends, as shares of the window's half side measured from
     * the eye.
     */
    public static final double BAND_START = 0.8125;
    public static final double BAND_END = 0.9375;
    /**
     * How far in cells the eye may move from a window's center before the window follows.
     */
    public static final double RECENTER_DISTANCE = 0.75;
    static final int SECTION_SIZE = 16;
    static final int SECTION_BLOCKS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    static final short DARK_OPEN_AIR_LIGHT = 0;
    static final short DARK_OPEN_AIR_AIR = 0x3c00;
    private static final short NO_AIR_HALF = 0;
    private static final int[] CELL_WIDTHS = {1, 2, 4, 8, 16};
    private static final int[] CELL_HEIGHTS = {1, 2, 4, 4, 4};
    private static final int[] HEIGHTS = {64, 64, 96, 96, 96};
    private static final int NEAR_MASK = (1 << FIRST_FAR_CASCADE) - 1;
    private static final int ALL_MASK = (1 << CASCADE_COUNT) - 1;
    private static final int LAYER_ROW_MASK = SECTION_SIZE * SECTION_SIZE - 1;
    private static final long NONE = -1L;
    private static final int RANGE_OFFSET = 32768;
    private static final int RANGE_BITS = 0xffff;
    private static final int MASK_SHIFT = 32;
    private static final long URGENT_BIT = 1L << 40;
    private static final int INITIAL_COLUMNS = 8192;
    private static final float HALF_SUBNORMAL_UNIT = 0x1.0p-24F;
    /**
     * The side of a lit box that reaches without end, in cells, and the low of an empty box.
     */
    public static final float UNBOUNDED_BOX = 1.0e9F;
    public static final float EMPTY_BOX_LOW = 1.0e9F;
    private static final float[] BRIGHTNESS = new float[BRIGHTEST_LEVEL + 1];

    static {
        for (int level = 0; level <= BRIGHTEST_LEVEL; level++) {
            BRIGHTNESS[level] = brightnessOfLevel(level / (float) BRIGHTEST_LEVEL);
        }
    }

    private final LampLightCascade[] cascades = new LampLightCascade[CASCADE_COUNT];
    private final Long2LongOpenHashMap pending = new Long2LongOpenHashMap(INITIAL_COLUMNS);
    private final ColumnQueue urgent = new ColumnQueue(INITIAL_COLUMNS);
    private final ColumnQueue bulk = new ColumnQueue(INITIAL_COLUMNS);
    private final float[] nearLight = new float[SECTION_BLOCKS];
    private final float[] nearAir = new float[SECTION_BLOCKS];
    private final float[] farLight = new float[SECTION_BLOCKS];
    private final float[] farAir = new float[SECTION_BLOCKS];
    /**
     * The cell box of the current section in each cascade, as lowest x, y, z and past-highest x, y, z.
     */
    private final int[] boxes = new int[CASCADE_COUNT * 6];
    private final float[] sharesScratch = new float[2];
    private final int[] litBoxScratch = new int[6];
    private int activeCount = CASCADE_COUNT;
    private boolean hasLevel;
    private int minSection;
    private int sectionCount;
    private long sectionsProcessed;
    private long columnsProcessed;
    private long nearWholeFills;

    public LampLightField() {
        pending.defaultReturnValue(NONE);
        for (int index = 0; index < CASCADE_COUNT; index++) {
            cascades[index] = new LampLightCascade(index, CELL_WIDTHS[index], CELL_HEIGHTS[index], ACROSS,
                    HEIGHTS[index], ACROSS);
        }
    }

    /**
     * Vanilla's red block brightness without flicker in a level without ambient light, for a block light level over
     * BRIGHTEST_LEVEL, from 0 to BRIGHTEST.
     */
    public static float brightnessOfLevel(float levelShare) {
        float share = Math.max(0.0F, Math.min(1.0F, levelShare));
        return 1.5F * share / (4.0F - 3.0F * share);
    }

    /**
     * The block light level over BRIGHTEST_LEVEL that has a brightness, the inverse of brightnessOfLevel.
     */
    public static float levelOfBrightness(float brightness) {
        float clamped = Math.max(0.0F, Math.min(BRIGHTEST, brightness));
        return 4.0F * clamped / (1.5F + 3.0F * clamped);
    }

    /**
     * The number of cascades whose reach covers a view distance in blocks, from 1 to CASCADE_COUNT. A cascade reaches
     * the end of its blend band.
     */
    public static int cascadesFor(int viewBlocks) {
        for (int index = 0; index < CASCADE_COUNT; index++) {
            if (BAND_END * ACROSS * 0.5 * CELL_WIDTHS[index] >= viewBlocks) {
                return index + 1;
            }
        }
        return CASCADE_COUNT;
    }

    public LampLightCascade cascade(int index) {
        return cascades[index];
    }

    /**
     * The width of a cascade's cells in blocks.
     */
    public static int cellWidth(int cascade) {
        return CELL_WIDTHS[cascade];
    }

    public static int cellHeight(int cascade) {
        return CELL_HEIGHTS[cascade];
    }

    public int activeCount() {
        return activeCount;
    }

    public boolean hasLevel() {
        return hasLevel;
    }

    /**
     * Forgets every cell and all queued work and takes the section range of a new level, so the next follow fills
     * every window again.
     */
    public void reset(int newMinSection, int newSectionCount) {
        for (LampLightCascade cascade : cascades) {
            cascade.clear();
        }
        pending.clear();
        urgent.clear();
        bulk.clear();
        hasLevel = true;
        minSection = newMinSection;
        sectionCount = newSectionCount;
    }

    /**
     * Forgets every cell and the level, after which nothing is filled until the next reset.
     */
    public void forget() {
        reset(0, 0);
        hasLevel = false;
    }

    /**
     * Uses the first count cascades. Cascades that stop being used forget their cells, and cascades that start are
     * filled from the next follow.
     */
    public void setActiveCount(int count) {
        int clamped = Math.max(1, Math.min(CASCADE_COUNT, count));
        for (int index = clamped; index < activeCount; index++) {
            cascades[index].clear();
        }
        activeCount = clamped;
    }

    /**
     * Places every used window that is not placed yet and queues its fill, and moves windows that the eye left the
     * center of, clearing the cells they bring in and queueing their sections.
     */
    public void follow(double eyeX, double eyeY, double eyeZ) {
        if (!hasLevel) {
            return;
        }
        for (int index = 0; index < activeCount; index++) {
            LampLightCascade cascade = cascades[index];
            int width = cascade.cellWidth();
            int height = cascade.cellHeight();
            double cellX = eyeX / width;
            double cellY = eyeY / height;
            double cellZ = eyeZ / width;
            boolean fixedVertically = (long) cascade.sizeY() * height >= (long) sectionCount * SECTION_SIZE;
            int wantedX = (int) Math.floor(cellX + 0.5) - cascade.sizeX() / 2;
            int wantedZ = (int) Math.floor(cellZ + 0.5) - cascade.sizeZ() / 2;
            int wantedY = fixedVertically ? Math.floorDiv(minSection * SECTION_SIZE, height)
                    : (int) Math.floor(cellY + 0.5) - cascade.sizeY() / 2;
            if (!cascade.isPlaced()) {
                cascade.place(wantedX, wantedY, wantedZ, fixedVertically);
                queueWindow(cascade, eyeX, eyeZ);
                continue;
            }
            int newX = Math.abs(cellX - (cascade.originX() + cascade.sizeX() / 2)) > RECENTER_DISTANCE ? wantedX
                    : cascade.originX();
            int newZ = Math.abs(cellZ - (cascade.originZ() + cascade.sizeZ() / 2)) > RECENTER_DISTANCE ? wantedZ
                    : cascade.originZ();
            int newY = fixedVertically || Math.abs(cellY - (cascade.originY() + cascade.sizeY() / 2))
                    > RECENTER_DISTANCE ? wantedY : cascade.originY();
            if (newX != cascade.originX() || newY != cascade.originY() || newZ != cascade.originZ()) {
                shift(cascade, newX, newY, newZ, eyeX, eyeZ);
            }
        }
    }

    private void shift(LampLightCascade cascade, int newX, int newY, int newZ, double eyeX, double eyeZ) {
        int oldX = cascade.originX();
        int oldY = cascade.originY();
        int oldZ = cascade.originZ();
        int sizeX = cascade.sizeX();
        int sizeY = cascade.sizeY();
        int sizeZ = cascade.sizeZ();
        cascade.place(newX, newY, newZ, cascade.isFixedVertically());
        if (Math.abs(newX - oldX) >= sizeX || Math.abs(newY - oldY) >= sizeY || Math.abs(newZ - oldZ) >= sizeZ) {
            cascade.fill(DARK_OPEN_AIR_LIGHT, DARK_OPEN_AIR_AIR);
            queueWindow(cascade, eyeX, eyeZ);
            return;
        }
        if (newX != oldX) {
            int low = newX > oldX ? oldX + sizeX : newX;
            int high = newX > oldX ? newX + sizeX : oldX;
            enter(cascade, low, newY, newZ, high, newY + sizeY, newZ + sizeZ);
        }
        if (newY != oldY) {
            int low = newY > oldY ? oldY + sizeY : newY;
            int high = newY > oldY ? newY + sizeY : oldY;
            enter(cascade, newX, low, newZ, newX + sizeX, high, newZ + sizeZ);
        }
        if (newZ != oldZ) {
            int low = newZ > oldZ ? oldZ + sizeZ : newZ;
            int high = newZ > oldZ ? newZ + sizeZ : oldZ;
            enter(cascade, newX, newY, low, newX + sizeX, newY + sizeY, high);
        }
    }

    /**
     * Clears a box of cells that a window brought in and queues the sections that hold them.
     */
    private void enter(LampLightCascade cascade, int lowX, int lowY, int lowZ, int highX, int highY, int highZ) {
        cascade.fillBox(lowX, lowY, lowZ, highX, highY, highZ, DARK_OPEN_AIR_LIGHT, DARK_OPEN_AIR_AIR);
        int width = cascade.cellWidth();
        int height = cascade.cellHeight();
        int firstChunkX = Math.floorDiv(lowX * width, SECTION_SIZE);
        int lastChunkX = Math.floorDiv(highX * width - 1, SECTION_SIZE);
        int firstChunkZ = Math.floorDiv(lowZ * width, SECTION_SIZE);
        int lastChunkZ = Math.floorDiv(highZ * width - 1, SECTION_SIZE);
        int lowSection = Math.floorDiv(lowY * height, SECTION_SIZE);
        int highSection = Math.floorDiv(highY * height - 1, SECTION_SIZE);
        int mask = 1 << cascade.index();
        for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
            for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                enqueue(chunkX, chunkZ, lowSection, highSection, mask, false);
            }
        }
    }

    /**
     * Queues every column of a window in rings around the eye's column, nearest first.
     */
    private void queueWindow(LampLightCascade cascade, double eyeX, double eyeZ) {
        if (cascade.index() == 0) {
            nearWholeFills++;
        }
        int width = cascade.cellWidth();
        int height = cascade.cellHeight();
        int firstChunkX = Math.floorDiv(cascade.originX() * width, SECTION_SIZE);
        int lastChunkX = Math.floorDiv((cascade.originX() + cascade.sizeX()) * width - 1, SECTION_SIZE);
        int firstChunkZ = Math.floorDiv(cascade.originZ() * width, SECTION_SIZE);
        int lastChunkZ = Math.floorDiv((cascade.originZ() + cascade.sizeZ()) * width - 1, SECTION_SIZE);
        int lowSection = Math.floorDiv(cascade.originY() * height, SECTION_SIZE);
        int highSection = Math.floorDiv((cascade.originY() + cascade.sizeY()) * height - 1, SECTION_SIZE);
        int mask = 1 << cascade.index();
        int eyeChunkX = Math.max(firstChunkX, Math.min(lastChunkX, Math.floorDiv((int) Math.floor(eyeX),
                SECTION_SIZE)));
        int eyeChunkZ = Math.max(firstChunkZ, Math.min(lastChunkZ, Math.floorDiv((int) Math.floor(eyeZ),
                SECTION_SIZE)));
        int rings = Math.max(Math.max(eyeChunkX - firstChunkX, lastChunkX - eyeChunkX),
                Math.max(eyeChunkZ - firstChunkZ, lastChunkZ - eyeChunkZ));
        for (int ring = 0; ring <= rings; ring++) {
            for (int chunkZ = eyeChunkZ - ring; chunkZ <= eyeChunkZ + ring; chunkZ++) {
                if (chunkZ < firstChunkZ || chunkZ > lastChunkZ) {
                    continue;
                }
                boolean edgeRow = chunkZ == eyeChunkZ - ring || chunkZ == eyeChunkZ + ring;
                int step = edgeRow || ring == 0 ? 1 : 2 * ring;
                for (int chunkX = eyeChunkX - ring; chunkX <= eyeChunkX + ring; chunkX += step) {
                    if (chunkX >= firstChunkX && chunkX <= lastChunkX) {
                        enqueue(chunkX, chunkZ, lowSection, highSection, mask, false);
                    }
                }
            }
        }
    }

    /**
     * Queues the sections of a column from lowSection to highSection whose light changed, for every used cascade, in
     * the urgent queue. Light sections past the level's ends count as its nearest section, since the sections below
     * one may take its sky light. Columns that no placed window reaches are skipped.
     */
    public void markSections(int chunkX, int chunkZ, int lowSection, int highSection) {
        if (!hasLevel || sectionCount <= 0) {
            return;
        }
        int topSection = minSection + sectionCount - 1;
        int low = Math.max(minSection, Math.min(topSection, lowSection));
        int high = Math.max(minSection, Math.min(topSection, highSection));
        if (low > high || !anyWindowReaches(chunkX, chunkZ)) {
            return;
        }
        enqueue(chunkX, chunkZ, low, high, ALL_MASK, true);
    }

    /**
     * Queues every section of a column, for a chunk that was unloaded.
     */
    public void markColumn(int chunkX, int chunkZ) {
        markSections(chunkX, chunkZ, minSection, minSection + sectionCount - 1);
    }

    private boolean anyWindowReaches(int chunkX, int chunkZ) {
        int lowBlockX = chunkX * SECTION_SIZE;
        int lowBlockZ = chunkZ * SECTION_SIZE;
        for (int index = 0; index < activeCount; index++) {
            LampLightCascade cascade = cascades[index];
            if (!cascade.isPlaced()) {
                continue;
            }
            int width = cascade.cellWidth();
            long windowLowX = (long) cascade.originX() * width;
            long windowLowZ = (long) cascade.originZ() * width;
            long windowHighX = windowLowX + (long) cascade.sizeX() * width;
            long windowHighZ = windowLowZ + (long) cascade.sizeZ() * width;
            if (lowBlockX + SECTION_SIZE > windowLowX && lowBlockX < windowHighX
                    && lowBlockZ + SECTION_SIZE > windowLowZ && lowBlockZ < windowHighZ) {
                return true;
            }
        }
        return false;
    }

    private void enqueue(int chunkX, int chunkZ, int lowSection, int highSection, int mask, boolean isUrgent) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        long existing = pending.get(key);
        if (existing == NONE) {
            pending.put(key, pack(lowSection, highSection, mask, isUrgent));
            (isUrgent ? urgent : bulk).enqueue(key);
            return;
        }
        boolean wasUrgent = (existing & URGENT_BIT) != 0L;
        pending.put(key, pack(Math.min(lowSection, unpackLow(existing)), Math.max(highSection, unpackHigh(existing)),
                mask | unpackMask(existing), isUrgent || wasUrgent));
        if (isUrgent && !wasUrgent) {
            urgent.enqueue(key);
        }
    }

    private static long pack(int lowSection, int highSection, int mask, boolean isUrgent) {
        return (long) (lowSection + RANGE_OFFSET) | (long) (highSection + RANGE_OFFSET) << 16
                | (long) mask << MASK_SHIFT | (isUrgent ? URGENT_BIT : 0L);
    }

    private static int unpackLow(long packed) {
        return (int) (packed & RANGE_BITS) - RANGE_OFFSET;
    }

    private static int unpackHigh(long packed) {
        return (int) (packed >>> 16 & RANGE_BITS) - RANGE_OFFSET;
    }

    private static int unpackMask(long packed) {
        return (int) (packed >>> MASK_SHIFT) & ALL_MASK;
    }

    /**
     * Whether no queued work is left.
     */
    public boolean isDrained() {
        return pending.isEmpty();
    }

    /**
     * Whether the bulk queue of window fills is empty, though light changes may still wait.
     */
    public boolean isBulkDrained() {
        return bulk.isEmpty();
    }

    /**
     * How many times the finest cascade was filled whole, on its first placement and after jumps past its window.
     */
    public long nearWholeFills() {
        return nearWholeFills;
    }

    public int pendingColumns() {
        return pending.size();
    }

    public long sectionsProcessed() {
        return sectionsProcessed;
    }

    public long columnsProcessed() {
        return columnsProcessed;
    }

    /**
     * Reads queued columns, urgent ones first, until the queues are empty, sectionBudget sections were read, or the
     * clock passed deadlineNanos after a column.
     * @return the sections read
     */
    public int process(LightSource source, int sectionBudget, long deadlineNanos) {
        int processed = 0;
        while (processed < sectionBudget) {
            long key;
            if (!urgent.isEmpty()) {
                key = urgent.dequeueLong();
            } else if (!bulk.isEmpty()) {
                key = bulk.dequeueLong();
            } else {
                break;
            }
            long packed = pending.remove(key);
            if (packed == NONE) {
                continue;
            }
            processed += processColumn(source, ChunkPos.getX(key), ChunkPos.getZ(key), unpackLow(packed),
                    unpackHigh(packed), unpackMask(packed));
            columnsProcessed++;
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
        }
        sectionsProcessed += processed;
        return processed;
    }

    /**
     * Reads the sections of a column from the top of its range down, and on below it through sections without sky
     * light data, which take the sky light of the section above them.
     */
    private int processColumn(LightSource source, int chunkX, int chunkZ, int lowSection, int highSection, int mask) {
        int usedMask = mask & placedMask();
        int low = Math.max(lowSection, minSection);
        int high = Math.min(highSection, minSection + sectionCount - 1);
        if (usedMask == 0 || low > high) {
            return 0;
        }
        boolean loaded = source.hasChunk(chunkX, chunkZ);
        int count = 0;
        if (!loaded || !source.hasSkyLight()) {
            for (int sectionY = high; sectionY >= low; sectionY--) {
                DataLayer blockLight = loaded ? source.blockLight(SectionPos.asLong(chunkX, sectionY, chunkZ)) : null;
                processSection(usedMask, chunkX, sectionY, chunkZ, loaded, blockLight, null, false);
                count++;
            }
            return count;
        }
        DataLayer above = null;
        int lightTop = minSection + sectionCount + 1;
        for (int sectionY = high + 1; sectionY < lightTop && above == null; sectionY++) {
            above = source.skyLight(SectionPos.asLong(chunkX, sectionY, chunkZ));
        }
        for (int sectionY = high; sectionY >= minSection; sectionY--) {
            long sectionKey = SectionPos.asLong(chunkX, sectionY, chunkZ);
            DataLayer skyLight = source.skyLight(sectionKey);
            if (sectionY < low && skyLight != null) {
                break;
            }
            DataLayer blockLight = source.blockLight(sectionKey);
            if (skyLight != null) {
                processSection(usedMask, chunkX, sectionY, chunkZ, true, blockLight, skyLight, false);
                above = skyLight;
            } else {
                processSection(usedMask, chunkX, sectionY, chunkZ, true, blockLight, above, above != null);
            }
            count++;
        }
        return count;
    }

    private int placedMask() {
        int mask = 0;
        for (int index = 0; index < activeCount; index++) {
            if (cascades[index].isPlaced()) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    /**
     * Writes the cells of one section into the cascades of the mask whose windows hold them.
     * @param skyLight the sky light of the section, null where it is OPEN_SKY_LIGHT everywhere
     * @param skyFromRow whether skyLight belongs to a section above, whose lowest layer then holds the sky light of
     * every layer of this section
     */
    private void processSection(int mask, int sectionX, int sectionY, int sectionZ, boolean loaded,
            @Nullable DataLayer blockLight, @Nullable DataLayer skyLight, boolean skyFromRow) {
        int touched = 0;
        for (int index = 0; index < activeCount; index++) {
            if ((mask & (1 << index)) != 0 && clipSection(cascades[index], sectionX, sectionY, sectionZ)) {
                touched |= 1 << index;
            }
        }
        if (touched == 0) {
            return;
        }
        if (!loaded) {
            fillTouched(touched, DARK_OPEN_AIR_LIGHT, DARK_OPEN_AIR_AIR, DARK_OPEN_AIR_LIGHT, DARK_OPEN_AIR_AIR);
            return;
        }
        int blockConstant = -1;
        byte[] blockData = null;
        if (blockLight == null) {
            blockConstant = 0;
        } else if (blockLight.isDefinitelyHomogenous()) {
            blockConstant = blockLight.get(0, 0, 0);
        } else {
            blockData = blockLight.getData();
        }
        int skyConstant = -1;
        byte[] skyData = null;
        if (skyLight == null) {
            skyConstant = OPEN_SKY_LIGHT;
        } else if (skyLight.isDefinitelyHomogenous()) {
            skyConstant = skyLight.get(0, 0, 0);
        } else {
            skyData = skyLight.getData();
        }
        if (blockData == null && skyData == null) {
            float brightness = BRIGHTNESS[blockConstant];
            boolean nearOutdoor = skyConstant > 0;
            boolean farOutdoor = skyConstant == OPEN_SKY_LIGHT;
            fillTouched(touched, toHalf(nearOutdoor ? brightness : 0.0F), nearOutdoor ? DARK_OPEN_AIR_AIR : NO_AIR_HALF,
                    toHalf(farOutdoor ? brightness : 0.0F), farOutdoor ? DARK_OPEN_AIR_AIR : NO_AIR_HALF);
            return;
        }
        boolean needNear = (touched & NEAR_MASK) != 0;
        boolean needFar = (touched & ~NEAR_MASK) != 0;
        for (int block = 0; block < SECTION_BLOCKS; block++) {
            int level = blockData == null ? blockConstant : nibble(blockData, block);
            int sky = skyData == null ? skyConstant : nibble(skyData, skyFromRow ? block & LAYER_ROW_MASK : block);
            float brightness = BRIGHTNESS[level];
            if (needNear) {
                boolean outdoor = sky > 0;
                nearLight[block] = outdoor ? brightness : 0.0F;
                nearAir[block] = outdoor ? 1.0F : 0.0F;
            }
            if (needFar) {
                boolean outdoor = sky == OPEN_SKY_LIGHT;
                farLight[block] = outdoor ? brightness : 0.0F;
                farAir[block] = outdoor ? 1.0F : 0.0F;
            }
        }
        for (int index = 0; index < activeCount; index++) {
            if ((touched & (1 << index)) != 0) {
                boolean far = index >= FIRST_FAR_CASCADE;
                writeCells(cascades[index], sectionX, sectionY, sectionZ, far ? farLight : nearLight,
                        far ? farAir : nearAir);
            }
        }
    }

    private static int nibble(byte[] data, int index) {
        return data[index >> 1] >> ((index & 1) << 2) & 15;
    }

    /**
     * Finds the cells of a section within a cascade's window and keeps them in boxes.
     * @return whether the window holds any of them
     */
    private boolean clipSection(LampLightCascade cascade, int sectionX, int sectionY, int sectionZ) {
        if (!cascade.isPlaced()) {
            return false;
        }
        int brickWidth = cascade.brickWidth();
        int brickHeight = cascade.brickHeight();
        int lowX = Math.max(sectionX * brickWidth, cascade.originX());
        int lowY = Math.max(sectionY * brickHeight, cascade.originY());
        int lowZ = Math.max(sectionZ * brickWidth, cascade.originZ());
        int highX = Math.min(sectionX * brickWidth + brickWidth, cascade.originX() + cascade.sizeX());
        int highY = Math.min(sectionY * brickHeight + brickHeight, cascade.originY() + cascade.sizeY());
        int highZ = Math.min(sectionZ * brickWidth + brickWidth, cascade.originZ() + cascade.sizeZ());
        if (lowX >= highX || lowY >= highY || lowZ >= highZ) {
            return false;
        }
        int base = cascade.index() * 6;
        boxes[base] = lowX;
        boxes[base + 1] = lowY;
        boxes[base + 2] = lowZ;
        boxes[base + 3] = highX;
        boxes[base + 4] = highY;
        boxes[base + 5] = highZ;
        return true;
    }

    private void fillTouched(int touched, short nearLightValue, short nearAirValue, short farLightValue,
            short farAirValue) {
        for (int index = 0; index < activeCount; index++) {
            if ((touched & (1 << index)) == 0) {
                continue;
            }
            int base = index * 6;
            boolean far = index >= FIRST_FAR_CASCADE;
            cascades[index].fillBox(boxes[base], boxes[base + 1], boxes[base + 2], boxes[base + 3], boxes[base + 4],
                    boxes[base + 5], far ? farLightValue : nearLightValue, far ? farAirValue : nearAirValue);
        }
    }

    /**
     * Writes the shares of the cells in a cascade's box from the blocks of the section, indexed as in DataLayer.
     */
    private void writeCells(LampLightCascade cascade, int sectionX, int sectionY, int sectionZ, float[] light,
            float[] air) {
        int base = cascade.index() * 6;
        int width = cascade.cellWidth();
        int height = cascade.cellHeight();
        int firstCellX = sectionX * cascade.brickWidth();
        int firstCellY = sectionY * cascade.brickHeight();
        int firstCellZ = sectionZ * cascade.brickWidth();
        float blocksPerCell = width * height * width;
        for (int cellZ = boxes[base + 2]; cellZ < boxes[base + 5]; cellZ++) {
            int blockZ = (cellZ - firstCellZ) * width;
            for (int cellY = boxes[base + 1]; cellY < boxes[base + 4]; cellY++) {
                int blockY = (cellY - firstCellY) * height;
                for (int cellX = boxes[base]; cellX < boxes[base + 3]; cellX++) {
                    int blockX = (cellX - firstCellX) * width;
                    float lightSum = 0.0F;
                    float airSum = 0.0F;
                    for (int dy = 0; dy < height; dy++) {
                        for (int dz = 0; dz < width; dz++) {
                            int row = (blockY + dy) << 8 | (blockZ + dz) << 4;
                            for (int dx = 0; dx < width; dx++) {
                                int block = row | (blockX + dx);
                                lightSum += light[block];
                                airSum += air[block];
                            }
                        }
                    }
                    cascade.put(cellX, cellY, cellZ, toHalf(lightSum / blocksPerCell), toHalf(airSum / blocksPerCell));
                }
            }
        }
        cascade.markBoxDirty(boxes[base], boxes[base + 1], boxes[base + 2], boxes[base + 3], boxes[base + 4],
                boxes[base + 5]);
    }

    /**
     * The block light level over BRIGHTEST_LEVEL that the field gives a point at an offset from the eye, filtering
     * each cascade as the GPU does and blending the cascades as lamp_light.glsl does.
     */
    public float levelAt(double eyeX, double eyeY, double eyeZ, double offsetX, double offsetY, double offsetZ) {
        sharesAt(eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ, sharesScratch);
        float air = sharesScratch[1];
        float brightness = air > NO_AIR ? sharesScratch[0] / air : 0.0F;
        return levelOfBrightness(brightness);
    }

    /**
     * The blended light share and air share at an offset from the eye, with dark open air past the last cascade.
     * @param result receives the light share and then the air share
     */
    public void sharesAt(double eyeX, double eyeY, double eyeZ, double offsetX, double offsetY, double offsetZ,
            float[] result) {
        float lightSum = 0.0F;
        float airSum = 0.0F;
        float remaining = 1.0F;
        for (int index = 0; index < activeCount && remaining > 0.0F; index++) {
            LampLightCascade cascade = cascades[index];
            if (!cascade.isPlaced()) {
                break;
            }
            float distance = bandDistance(cascade, offsetX, offsetY, offsetZ);
            if (distance >= BAND_END) {
                continue;
            }
            float weight = bandWeight(distance);
            filter(cascade, eyeX, eyeY, eyeZ, offsetX, offsetY, offsetZ, result);
            lightSum += remaining * (1.0F - weight) * result[0];
            airSum += remaining * (1.0F - weight) * result[1];
            remaining *= weight;
        }
        result[0] = lightSum;
        result[1] = airSum + remaining;
    }

    /**
     * The weight that each used cascade gets at an offset from the eye, and in the last entry the weight of the dark
     * open air past them.
     * @param weights receives CASCADE_COUNT plus one weights
     */
    public void weightsAt(double offsetX, double offsetY, double offsetZ, float[] weights) {
        float remaining = 1.0F;
        for (int index = 0; index < CASCADE_COUNT; index++) {
            weights[index] = 0.0F;
            LampLightCascade cascade = cascades[index];
            if (index >= activeCount || !cascade.isPlaced() || remaining <= 0.0F) {
                continue;
            }
            float distance = bandDistance(cascade, offsetX, offsetY, offsetZ);
            if (distance >= BAND_END) {
                continue;
            }
            float weight = bandWeight(distance);
            weights[index] = remaining * (1.0F - weight);
            remaining *= weight;
        }
        weights[CASCADE_COUNT] = remaining;
    }

    /**
     * Writes the per-cascade uniforms of lamp_light.glsl for an eye, 3 floats per cascade in x, y, z order: the size
     * in cells, the slot of the lowest cell, the eye in cells from the lowest cell, the cells per block, and the
     * inverse of the half side in blocks, 0 vertically where the window is fixed vertically.
     * @return the number of cascades the shader reads, the used ones up to the first not placed
     */
    public int shaderUniforms(double eyeX, double eyeY, double eyeZ, float[] sizes, float[] slotOrigins,
            float[] eyesInCascade, float[] cellsPerBlock, float[] inverseReaches) {
        int count = 0;
        for (int index = 0; index < activeCount; index++) {
            LampLightCascade cascade = cascades[index];
            if (!cascade.isPlaced()) {
                break;
            }
            int base = 3 * index;
            int width = cascade.cellWidth();
            int height = cascade.cellHeight();
            sizes[base] = cascade.sizeX();
            sizes[base + 1] = cascade.sizeY();
            sizes[base + 2] = cascade.sizeZ();
            slotOrigins[base] = cascade.slotX(cascade.originX());
            slotOrigins[base + 1] = cascade.slotY(cascade.originY());
            slotOrigins[base + 2] = cascade.slotZ(cascade.originZ());
            eyesInCascade[base] = (float) (eyeX / width - cascade.originX());
            eyesInCascade[base + 1] = (float) (eyeY / height - cascade.originY());
            eyesInCascade[base + 2] = (float) (eyeZ / width - cascade.originZ());
            cellsPerBlock[base] = 1.0F / width;
            cellsPerBlock[base + 1] = 1.0F / height;
            cellsPerBlock[base + 2] = 1.0F / width;
            float inverseAcross = (float) (1.0 / (0.5 * cascade.sizeX() * width));
            inverseReaches[base] = inverseAcross;
            inverseReaches[base + 1] = cascade.isFixedVertically() ? 0.0F
                    : (float) (1.0 / (0.5 * cascade.sizeY() * height));
            inverseReaches[base + 2] = inverseAcross;
            count++;
        }
        return count;
    }

    /**
     * Writes for the fog march the box of each cascade's lit cells, for the cascades shaderUniforms counts, 3 floats
     * per cascade in x, y, z order, in cells from the lowest cell of the window. Each box is grown by half a cell,
     * which bounds where the filtered light share is above 0, and reaches without end past a side of the window that
     * holds a lit cell, since the shader clamps reads to the window. A cascade without a lit cell gets an empty box,
     * its low above its high.
     * @return whether any of those cascades holds a lit cell
     */
    public boolean shaderLitBoxes(float[] lows, float[] highs) {
        boolean anyLit = false;
        for (int index = 0; index < activeCount; index++) {
            LampLightCascade cascade = cascades[index];
            if (!cascade.isPlaced()) {
                break;
            }
            int base = 3 * index;
            if (!cascade.litBox(litBoxScratch)) {
                for (int axis = 0; axis < 3; axis++) {
                    lows[base + axis] = EMPTY_BOX_LOW;
                    highs[base + axis] = -EMPTY_BOX_LOW;
                }
                continue;
            }
            anyLit = true;
            for (int axis = 0; axis < 3; axis++) {
                int size = axis == 0 ? cascade.sizeX() : axis == 1 ? cascade.sizeY() : cascade.sizeZ();
                int low = litBoxScratch[axis];
                int high = litBoxScratch[axis + 3];
                lows[base + axis] = low == 0 ? -UNBOUNDED_BOX : low - 0.5F;
                highs[base + axis] = high == size ? UNBOUNDED_BOX : high + 0.5F;
            }
        }
        return anyLit;
    }

    /**
     * Writes each cascade's 8x8x8 coarse slot occupancy mask for the shader, COARSE_WORDS_PER_CASCADE ints per
     * cascade, zeroed for cascades that are inactive or not yet placed. Allocates nothing.
     */
    public void shaderCoarseMasks(int[] masks) {
        for (int index = 0; index < CASCADE_COUNT; index++) {
            int offset = index * COARSE_WORDS_PER_CASCADE;
            if (index < activeCount && cascades[index].isPlaced()) {
                cascades[index].writeCoarseMask(masks, offset);
            } else {
                Arrays.fill(masks, offset, offset + COARSE_WORDS_PER_CASCADE, 0);
            }
        }
    }

    /**
     * The largest of an offset's distances from the eye along the axes, each as a share of the window's half side.
     * A window fixed vertically does not count the vertical distance.
     */
    public static float bandDistance(LampLightCascade cascade, double offsetX, double offsetY, double offsetZ) {
        double across = 0.5 * cascade.sizeX() * cascade.cellWidth();
        double distance = Math.max(Math.abs(offsetX), Math.abs(offsetZ)) / across;
        if (!cascade.isFixedVertically()) {
            distance = Math.max(distance, Math.abs(offsetY) / (0.5 * cascade.sizeY() * cascade.cellHeight()));
        }
        return (float) distance;
    }

    public static float bandWeight(float distance) {
        return (float) Math.max(0.0, Math.min(1.0, (distance - BAND_START) / (BAND_END - BAND_START)));
    }

    /**
     * Filters a cascade's two shares linearly between the centers of its cells at an offset from the eye, clamped to
     * half a cell inside the window.
     * @param result receives the light share and then the air share
     */
    void filter(LampLightCascade cascade, double eyeX, double eyeY, double eyeZ, double offsetX, double offsetY,
            double offsetZ, float[] result) {
        int width = cascade.cellWidth();
        int height = cascade.cellHeight();
        double inX = clamp(eyeX / width - cascade.originX() + offsetX / width, cascade.sizeX());
        double inY = clamp(eyeY / height - cascade.originY() + offsetY / height, cascade.sizeY());
        double inZ = clamp(eyeZ / width - cascade.originZ() + offsetZ / width, cascade.sizeZ());
        double texelX = inX - 0.5;
        double texelY = inY - 0.5;
        double texelZ = inZ - 0.5;
        int lowX = (int) Math.floor(texelX);
        int lowY = (int) Math.floor(texelY);
        int lowZ = (int) Math.floor(texelZ);
        float fractionX = (float) (texelX - lowX);
        float fractionY = (float) (texelY - lowY);
        float fractionZ = (float) (texelZ - lowZ);
        float lightSum = 0.0F;
        float airSum = 0.0F;
        for (int corner = 0; corner < 8; corner++) {
            int stepX = corner & 1;
            int stepY = corner >> 1 & 1;
            int stepZ = corner >> 2 & 1;
            float weight = (stepX == 1 ? fractionX : 1.0F - fractionX) * (stepY == 1 ? fractionY : 1.0F - fractionY)
                    * (stepZ == 1 ? fractionZ : 1.0F - fractionZ);
            int cellX = cascade.originX() + lowX + stepX;
            int cellY = cascade.originY() + lowY + stepY;
            int cellZ = cascade.originZ() + lowZ + stepZ;
            lightSum += weight * fromHalf(cascade.lightAt(cellX, cellY, cellZ));
            airSum += weight * fromHalf(cascade.airAt(cellX, cellY, cellZ));
        }
        result[0] = lightSum;
        result[1] = airSum;
    }

    private static double clamp(double inWindow, int size) {
        return Math.max(0.5, Math.min(size - 0.5, inWindow));
    }

    /**
     * Rounds a float to the nearest half float, ties to even, keeping subnormals. Values past the largest half float
     * become infinite.
     */
    public static short toHalf(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = bits >>> 16 & 0x8000;
        int exponent = (bits >>> 23 & 0xff) - 127 + 15;
        int mantissa = bits & 0x7fffff;
        if ((bits & 0x7fffffff) == 0) {
            return (short) sign;
        }
        if (exponent >= 31) {
            return (short) (sign | 0x7c00);
        }
        if (exponent <= 0) {
            if (exponent < -10) {
                return (short) sign;
            }
            int full = mantissa | 0x800000;
            int shift = 14 - exponent;
            int half = full >> shift;
            int remainder = full & ((1 << shift) - 1);
            int halfway = 1 << (shift - 1);
            if (remainder > halfway || (remainder == halfway && (half & 1) != 0)) {
                half++;
            }
            return (short) (sign | half);
        }
        int half = exponent << 10 | mantissa >> 13;
        int remainder = mantissa & 0x1fff;
        if (remainder > 0x1000 || (remainder == 0x1000 && (half & 1) != 0)) {
            half++;
        }
        return (short) (sign | half);
    }

    public static float fromHalf(short half) {
        int bits = half & 0xffff;
        int exponent = bits >>> 10 & 0x1f;
        int mantissa = bits & 0x3ff;
        boolean negative = (bits & 0x8000) != 0;
        float value;
        if (exponent == 0) {
            value = mantissa * HALF_SUBNORMAL_UNIT;
        } else if (exponent == 31) {
            value = mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else {
            value = Float.intBitsToFloat((exponent - 15 + 127) << 23 | mantissa << 13);
        }
        return negative ? -value : value;
    }

    /**
     * A first-in first-out ring of column keys that doubles when full and never shrinks, so a warm queue allocates
     * nothing.
     */
    private static final class ColumnQueue {
        private long[] keys;
        private int first;
        private int size;

        private ColumnQueue(int capacity) {
            keys = new long[capacity];
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void clear() {
            first = 0;
            size = 0;
        }

        private void enqueue(long key) {
            if (size == keys.length) {
                long[] grown = new long[keys.length * 2];
                for (int index = 0; index < size; index++) {
                    grown[index] = keys[(first + index) % keys.length];
                }
                keys = grown;
                first = 0;
            }
            keys[(first + size) % keys.length] = key;
            size++;
        }

        private long dequeueLong() {
            long key = keys[first];
            first = (first + 1) % keys.length;
            size--;
            return key;
        }
    }

    /**
     * The light a field reads, as the client's light engine stores it.
     */
    public interface LightSource {
        boolean hasChunk(int chunkX, int chunkZ);

        /**
         * Whether the level has sky light. Without it every block counts as open sky.
         */
        boolean hasSkyLight();

        /**
         * The block light data of a section by its packed position, or null where it holds no block light.
         */
        @Nullable
        DataLayer blockLight(long sectionKey);

        /**
         * The sky light data of a section by its packed position, or null where the light engine stores none, in
         * which case the section takes the lowest layer of the first section above it that has data, or
         * OPEN_SKY_LIGHT where none has.
         */
        @Nullable
        DataLayer skyLight(long sectionKey);
    }
}
