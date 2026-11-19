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

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import javax.annotation.Nullable;

/**
 * The mask sets of the palette of one chunk section, in a table indexed by palette index, so that reading a cell of the
 * section costs one storage read and one array read instead of the two hash lookups of SnowFaces.maskSetOf.
 * The table has TABLE_SIZE entries, which covers a single value, a linear and a hash map palette, whose indices stay
 * below their number of entries. A global palette has one entry per block state, which is more than the table holds, so
 * it is mapped through maskSetOf every time.
 * A section keeps its palette data object until a state that does not fit replaces it, so the object and the number of
 * entries seen at load tell whether the table still matches the palette while blocks change. Linear and hash map
 * palettes append entries in place, and an entry that was already handed out never changes its state, so the table
 * only has to be extended, and a new data object only cleared.
 * A chunk sent again reads its palettes in place and keeps the data object when the palette's bits are unchanged, so
 * the caller must call forget or load again after a chunk is sent again.
 * After the first lookup of each block state, nothing allocates. Not thread safe.
 */
public final class SnowSectionMasks {
    /**
     * The number of entries of the table, which is the most entries a palette of one section has before it becomes a
     * global palette.
     */
    public static final int TABLE_SIZE = 256;

    private final SnowFaces faces;
    /**
     * The mask set of each palette index, or -1 for an entry that was not looked up yet.
     */
    private final int[] maskSets = new int[TABLE_SIZE];
    @Nullable
    private LevelChunkSection section;
    @Nullable
    private PalettedContainer.Data<BlockState> sectionData;
    private int paletteSize;
    private boolean usesTable;

    /**
     * Creates the mask sets of a section, with no section loaded.
     * @param faces the table the mask set ids come from, which this instance does not modify
     */
    public SnowSectionMasks(SnowFaces faces) {
        this.faces = faces;
    }

    /**
     * Makes the palette of a section the one this instance maps, and forgets the entries of the palette of the section
     * loaded before. Both are read by the following lookups, so the section must stay the one loaded here until the
     * next load. Allocates nothing.
     * @param section the section whose cells the following lookups read
     */
    public void load(LevelChunkSection section) {
        this.section = section;
        PalettedContainer.Data<BlockState> data = section.getStates().data;
        this.sectionData = data;
        int size = data.palette().getSize();
        this.usesTable = size <= TABLE_SIZE;
        if (usesTable) {
            this.paletteSize = size;
            for (int index = 0; index < size; index++) {
                maskSets[index] = -1;
            }
        } else {
            this.paletteSize = 0;
        }
    }

    /**
     * The mask set of the block state in a cell of the loaded section, which is computed on the first lookup of its
     * entry and cached after. Allocates nothing after the first lookup of each state.
     * @param localX the local x of the cell, from 0 to 15
     * @param localY the local y of the cell, from 0 to 15
     * @param localZ the local z of the cell, from 0 to 15
     * @return the mask set id, from SnowFaces
     * @throws IllegalStateException when no section was loaded
     */
    public int maskSetAt(int localX, int localY, int localZ) {
        LevelChunkSection loadedSection = section;
        if (loadedSection == null) {
            throw new IllegalStateException("The mask sets of a section were read before a section was loaded.");
        }
        PalettedContainer.Data<BlockState> data = loadedSection.getStates().data;
        if (data != sectionData) {
            // The palette outgrew its data object, so the table no longer matches it.
            load(loadedSection);
            data = sectionData;
        }
        Palette<BlockState> palette = data.palette();
        int index = data.storage().get((localY << 8) | (localZ << 4) | localX);
        if (!usesTable) {
            return faces.maskSetOf(palette.valueFor(index));
        }
        if (index >= paletteSize) {
            if (index >= TABLE_SIZE) {
                return faces.maskSetOf(palette.valueFor(index));
            }
            // A linear or hash map palette appended entries in place, which keeps the entries they had and adds
            // unmapped ones:
            int grownSize = Math.min(palette.getSize(), TABLE_SIZE);
            for (int entry = paletteSize; entry < grownSize; entry++) {
                maskSets[entry] = -1;
            }
            paletteSize = grownSize;
        }
        int maskSet = maskSets[index];
        if (maskSet < 0) {
            maskSet = faces.maskSetOf(palette.valueFor(index));
            maskSets[index] = maskSet;
        }
        return maskSet;
    }

    /**
     * Drops the reference to the loaded section, so that a later load of any section starts with an empty table.
     * Allocates nothing.
     */
    public void forget() {
        section = null;
        sectionData = null;
        paletteSize = 0;
        usesTable = false;
    }
}
