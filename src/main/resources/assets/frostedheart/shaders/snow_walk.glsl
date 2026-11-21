// The walk of the fog along the segment from the eye to a scene point, through the snow field near the eye and through
// the far heights of the columns beyond it, which SnowFogWalk does a second time on the CPU so that the two can be
// compared. FHShaders prepends weather_fog.glsl before it, for fogOpticalDepth.
// The fog's extinction at a point is the height fog times a snow share in the range 0 to 1 times the density of the fog
// structures, so the optical depth of the segment is the integral of that product. The walk splits the segment into
// parts on which the share is constant and adds the closed form of fogOpticalDepth at the two ends of each part times the
// density of the structures at its middle, which is exact along one ray from the eye and takes no samples.
// While the structures are on, the parts inside the region are the cells, and the far part up to the range of the
// structures is walked through a coarser grid of SNOW_WALK_COARSE_CELL blocks, which stays fixed in the world. Beyond
// the range the density is 1, which the rest of the far part takes in closed form.
// Every position here is a cell offset from the eye's block, so no world position passes through a float.
// The field of the snow near the eye, a cube of size fieldMask + 1 cells whose bytes are distances in eighths of a block:
uniform sampler3D snowField;
// The motion heights of the columns of FarHeights, as integers, which the far rule compares its scene points against:
uniform isampler2D farHeights;
// The share of each distance byte, 256 texels of a single row, which SnowFogWalk.buildTable fills:
uniform sampler2D shareTable;
// The share of every cell of the region cube, which the share pass fills once per frame as an atlas of the horizontal
// slices of the cube, and whether it is bound:
uniform sampler2D shareVolume;
uniform int hasShareVolume;
// The number of cells per axis of the field minus one, an int so that the texel lookup wraps with a bitwise and:
uniform int fieldMask;
// Whether the shown box holds the cells whose field bytes the renderer may show, and its corners in world blocks,
// inclusive:
uniform int hasShownBox;
uniform ivec3 shownMin;
uniform ivec3 shownMax;
// The block the eye is in, and the place of the eye inside that block from 0 to 1, which a float can hold exactly:
uniform ivec3 eyeBlock;
uniform vec3 eyeFraction;
// The extinction per block at the eye, and the height scale of the fog in blocks:
uniform float eyeExtinction;
uniform float heightScale;
// The half width in blocks of the region around the eye inside which the share follows the field, which is the fog's
// field radius minus SnowFogWalk.REGION_MARGIN:
uniform float regionHalfWidth;
// How much of the field's share the walk takes, from 0 to 1, which rises after a fill filled the shown box:
uniform float fieldFade;
// The tiling texture of the fog structures, two channels of SIZE cubed bytes that tile on every axis, which the wind
// carries, and the density that they give the fog:
uniform sampler3D fogStructure;
uniform float structureShare;
uniform float structureCoverage;
uniform float structureErosion;
uniform float structureRange;
// The mean of the shape without erosion and the mean with it:
uniform vec2 structureMeans;
// The pattern coordinate of the eye in tiles, and the tiles that a block of offset covers along the wind, upward and
// across the wind, as three full rows of FogStructure:
uniform vec3 structureEye;
uniform vec3 structureAlong;
uniform vec3 structureUp;
uniform vec3 structureAcross;

// The size of the side in blocks of the world-aligned cells that the far part is walked in, and the most parts of that
// walk, as SnowFogWalk.COARSE_CELL and SnowFogWalk.COARSE_MAXIMUM_STEPS:
const float SNOW_WALK_COARSE_CELL = 4.0;
const int SNOW_WALK_COARSE_MAXIMUM_STEPS = 64;
// The structure constants that shape the density, as FogStructure:
const float SNOW_WALK_EDGE_WIDTH = 0.5;
const int SNOW_WALK_DETAIL_REPEAT = 4;
const float SNOW_WALK_DETAIL_FADE_START = 32.0;
const float SNOW_WALK_DETAIL_FADE_END = 64.0;
const float SNOW_WALK_RANGE_FADE_SHARE = 0.25;
const float SNOW_WALK_MINIMUM_COVERAGE = 0.05;
const float SNOW_WALK_MAXIMUM_EROSION = 0.9;
const float SNOW_WALK_MINIMUM_MEAN = 1.0e-6;

// The number of texels per axis of the window of far heights minus one, as FarHeights.SIZE - 1:
const int FAR_HEIGHTS_MASK = 1023;
// The layout of the share volume, as FogShareVolume: the cells per axis of the cube of cells around the eye's block, the
// index of the cell of the eye's block on every axis, the tiles of the atlas across and down, and the size of the atlas
// in texels:
const int SNOW_WALK_SHARE_SIDE = 80;
const int SNOW_WALK_SHARE_ORIGIN = 40;
const int SNOW_WALK_SHARE_TILES_ACROSS = 10;
const int SNOW_WALK_SHARE_TILES_DOWN = 8;
const int SNOW_WALK_SHARE_ATLAS_WIDTH = 800;
const int SNOW_WALK_SHARE_ATLAS_HEIGHT = 640;
// The distance in blocks before the region's edge over which a cell's share blends toward its height share:
const float SNOW_WALK_CROSSFADE_WIDTH = 8.0;
// The most cells the cell walk visits, as SnowFogWalk.MAXIMUM_CELL_STEPS:
const int SNOW_WALK_MAXIMUM_CELL_STEPS = 128;
// The most columns the backward walk visits, as SnowFogWalk.FAR_WALK_COLUMNS:
const int SNOW_WALK_FAR_WALK_COLUMNS = 32;
// The horizontal length in blocks below which the segment counts as staying in the column of its scene point:
const float SNOW_WALK_MINIMUM_HORIZONTAL_LENGTH = 1.0e-4;
// A boundary past the end of the segment, for an axis the segment never leaves its cell in:
const float SNOW_WALK_NO_BOUNDARY = 1.0e30;

// The optical depth from the eye up to the given parameter of the segment, as SnowFogWalk.tauAt:
float snowWalkTauAt(float parameter, float segmentLength, float verticalOffset) {
    return fogOpticalDepth(eyeExtinction, parameter * segmentLength, parameter * verticalOffset, heightScale);
}

// The density of the fog structures at a point given as an offset from the eye, as FogStructure.densityAt. The texture
// has GL_LINEAR and GL_REPEAT, so the hardware filters and wraps every read, and its red channel is the shape and its
// green one the erosion detail:
float snowWalkStructureDensity(vec3 offset) {
    if (!(structureShare > 0.0) || !(structureRange > 0.0)) {
        return 1.0;
    }
    float distance = length(offset);
    float rangeWeight = clamp((structureRange - distance) / (SNOW_WALK_RANGE_FADE_SHARE * structureRange), 0.0, 1.0);
    if (!(rangeWeight > 0.0)) {
        return 1.0;
    }
    float detailWeight = clamp((SNOW_WALK_DETAIL_FADE_END - distance)
            / (SNOW_WALK_DETAIL_FADE_END - SNOW_WALK_DETAIL_FADE_START), 0.0, 1.0);
    float localErosion = structureErosion * detailWeight;
    vec3 point = structureEye + vec3(dot(structureAlong, offset), dot(structureUp, offset),
            dot(structureAcross, offset));
    float base = texture(fogStructure, point).r;
    float detail = localErosion > 0.0 ? texture(fogStructure, point * float(SNOW_WALK_DETAIL_REPEAT)).g : 0.0;
    float coverage = clamp(structureCoverage, SNOW_WALK_MINIMUM_COVERAGE, 1.0);
    float erosion = clamp(localErosion, 0.0, SNOW_WALK_MAXIMUM_EROSION);
    float x = (base - (1.0 - coverage)) / coverage;
    float profile = smoothstep(0.0, SNOW_WALK_EDGE_WIDTH, x);
    float eaten = erosion * detail;
    float shape = clamp((profile - eaten) / (1.0 - eaten), 0.0, 1.0);
    float mean = structureMeans.x + (structureMeans.y - structureMeans.x) * detailWeight;
    return 1.0 + rangeWeight * structureShare * (shape / max(mean, SNOW_WALK_MINIMUM_MEAN) - 1.0);
}

// The optical depth of the fog structures along the part of the segment from the start to the end parameter, which is
// walked through the world-aligned grid of SNOW_WALK_COARSE_CELL blocks, so that its parts stay fixed in the world
// while the eye moves, as SnowFogWalk.coarseDepth. Each part adds the density at its middle times the closed form of the
// optical depth between its ends, and a walk that runs out of steps adds the rest at the mean density. The eye's place
// in the grid is its fraction plus its block taken modulo the side of a cell, which holds for negative blocks too:
float snowWalkCoarseDepth(vec3 offset, float segmentLength, float startParameter, float endParameter,
        out int coarseSteps) {
    coarseSteps = 0;
    if (!(endParameter > startParameter) || !(structureShare > 0.0)) {
        return 0.0;
    }
    vec3 local = vec3(eyeBlock & ivec3(3)) + eyeFraction;
    vec3 position = local + startParameter * offset;
    ivec3 cell = ivec3(floor(position / SNOW_WALK_COARSE_CELL));
    ivec3 cellStep = ivec3(0);
    vec3 nextBoundary = vec3(SNOW_WALK_NO_BOUNDARY);
    vec3 boundaryStep = vec3(SNOW_WALK_NO_BOUNDARY);
    if (offset.x > 0.0) {
        cellStep.x = 1;
        nextBoundary.x = startParameter + (float(cell.x + 1) * SNOW_WALK_COARSE_CELL - position.x) / offset.x;
        boundaryStep.x = SNOW_WALK_COARSE_CELL / offset.x;
    } else if (offset.x < 0.0) {
        cellStep.x = -1;
        nextBoundary.x = startParameter + (float(cell.x) * SNOW_WALK_COARSE_CELL - position.x) / offset.x;
        boundaryStep.x = SNOW_WALK_COARSE_CELL / -offset.x;
    }
    if (offset.y > 0.0) {
        cellStep.y = 1;
        nextBoundary.y = startParameter + (float(cell.y + 1) * SNOW_WALK_COARSE_CELL - position.y) / offset.y;
        boundaryStep.y = SNOW_WALK_COARSE_CELL / offset.y;
    } else if (offset.y < 0.0) {
        cellStep.y = -1;
        nextBoundary.y = startParameter + (float(cell.y) * SNOW_WALK_COARSE_CELL - position.y) / offset.y;
        boundaryStep.y = SNOW_WALK_COARSE_CELL / -offset.y;
    }
    if (offset.z > 0.0) {
        cellStep.z = 1;
        nextBoundary.z = startParameter + (float(cell.z + 1) * SNOW_WALK_COARSE_CELL - position.z) / offset.z;
        boundaryStep.z = SNOW_WALK_COARSE_CELL / offset.z;
    } else if (offset.z < 0.0) {
        cellStep.z = -1;
        nextBoundary.z = startParameter + (float(cell.z) * SNOW_WALK_COARSE_CELL - position.z) / offset.z;
        boundaryStep.z = SNOW_WALK_COARSE_CELL / -offset.z;
    }
    float depth = 0.0;
    float enter = startParameter;
    float enterDepth = snowWalkTauAt(startParameter, segmentLength, offset.y);
    bool reachedEnd = false;
    for (int step = 0; step < SNOW_WALK_COARSE_MAXIMUM_STEPS && !reachedEnd; step++) {
        coarseSteps = step + 1;
        float exit = min(min(nextBoundary.x, nextBoundary.y), min(nextBoundary.z, endParameter));
        float exitDepth = snowWalkTauAt(exit, segmentLength, offset.y);
        float middle = (enter + exit) * 0.5;
        depth += snowWalkStructureDensity(middle * offset) * max(0.0, exitDepth - enterDepth);
        enter = exit;
        enterDepth = exitDepth;
        if (!(exit < endParameter)) {
            reachedEnd = true;
        } else {
            float smallestBoundary = min(nextBoundary.x, min(nextBoundary.y, nextBoundary.z));
            // Ties step every tied axis, as the cell walk does.
            if (nextBoundary.x <= smallestBoundary) {
                cell.x += cellStep.x;
                nextBoundary.x += boundaryStep.x;
            }
            if (nextBoundary.y <= smallestBoundary) {
                cell.y += cellStep.y;
                nextBoundary.y += boundaryStep.y;
            }
            if (nextBoundary.z <= smallestBoundary) {
                cell.z += cellStep.z;
                nextBoundary.z += boundaryStep.z;
            }
        }
    }
    if (!reachedEnd) {
        depth += max(0.0, snowWalkTauAt(endParameter, segmentLength, offset.y) - enterDepth);
    }
    return depth;
}

// The motion height of the column of a cell, which FarHeights.UNKNOWN makes open. The texel wraps with a bitwise and,
// since the modulo of a negative value is undefined.
int snowWalkColumnHeight(int columnOffsetX, int columnOffsetZ) {
    return texelFetch(farHeights, ivec2(eyeBlock.x + columnOffsetX, eyeBlock.z + columnOffsetZ)
            & ivec2(FAR_HEIGHTS_MASK), 0).r;
}

// Whether a point of the segment lies under cover in a column, which compares its height over the eye's block against
// the column's height over that block, both in the relative form, so the cover does not depend on the distance from the
// origin. The cell of the point is the eye's block plus its offset, as everywhere in this file:
bool snowWalkCovered(int columnOffsetX, int columnOffsetZ, float parameter, float verticalOffset) {
    return eyeFraction.y + parameter * verticalOffset
            < float(snowWalkColumnHeight(columnOffsetX, columnOffsetZ) - eyeBlock.y);
}

// The height share of a cell: 1 at or above the motion height of its column and 0 below it, so a column whose height is
// FarHeights.UNKNOWN, which stands below every cell, counts as open:
float snowWalkHeightShare(ivec3 cell) {
    return float(eyeBlock.y + cell.y) >= float(snowWalkColumnHeight(cell.x, cell.z)) ? 1.0 : 0.0;
}

// The share of a cell, which is its distance byte through the share table, or its height share when it has no field
// value, blended between the two by the crossfade near the region's edge and by the field fade. Only texelFetch reads
// the field, since a filtered read would blend the codes of neighboring cells. A cell outside the shown box has no
// field value, and so has a cell whose byte flags it as unread:
// The weight comes first, so a cell of weight 0 reads no field and a cell of weight 1 reads no height, which gives the
// same share as SnowFogWalk.cellShare with fewer fetches:
float snowWalkCellShareDirect(ivec3 cell) {
    vec3 centerDistance = abs(vec3(cell) + vec3(0.5) - eyeFraction);
    float chebyshev = max(centerDistance.x, max(centerDistance.y, centerDistance.z));
    float weight = clamp((regionHalfWidth - chebyshev) / SNOW_WALK_CROSSFADE_WIDTH, 0.0, 1.0) * fieldFade;
    ivec3 world = eyeBlock + cell;
    if (hasShownBox == 0 || !(weight > 0.0) || any(lessThan(world, shownMin)) || any(greaterThan(world, shownMax))) {
        return snowWalkHeightShare(cell);
    }
    int fieldByte = int(texelFetch(snowField, world & ivec3(fieldMask), 0).r * 255.0 + 0.5);
    float fieldShare = texelFetch(shareTable, ivec2(fieldByte, 0), 0).r;
    if (fieldShare < 0.0) {
        return snowWalkHeightShare(cell);
    }
    if (weight >= 1.0) {
        return fieldShare;
    }
    float heightShare = snowWalkHeightShare(cell);
    return heightShare + (fieldShare - heightShare) * weight;
}

// The share of a cell, which is the texel of the share volume when it is bound and the cell is inside it, and the direct
// share of the field, the share table and the far heights otherwise. The volume holds the same cells at the same offsets
// as snowWalkCellShareDirect, so both give the share of the same cell:
float snowWalkCellShare(ivec3 cell) {
    if (hasShareVolume == 0 || any(lessThan(cell, ivec3(-SNOW_WALK_SHARE_ORIGIN)))
            || any(greaterThan(cell, ivec3(SNOW_WALK_SHARE_SIDE - SNOW_WALK_SHARE_ORIGIN - 1)))) {
        return snowWalkCellShareDirect(cell);
    }
    int slice = cell.y + SNOW_WALK_SHARE_ORIGIN;
    int shareVolumeX = cell.x + SNOW_WALK_SHARE_ORIGIN
            + (slice % SNOW_WALK_SHARE_TILES_ACROSS) * SNOW_WALK_SHARE_SIDE;
    int shareVolumeY = cell.z + SNOW_WALK_SHARE_ORIGIN
            + (slice / SNOW_WALK_SHARE_TILES_ACROSS) * SNOW_WALK_SHARE_SIDE;
    return texelFetch(shareVolume, ivec2(shareVolumeX, shareVolumeY), 0).r;
}

// Where the covered stretch at the end of the segment begins, found by walking the columns under the segment's
// horizontal projection backward from the scene point toward the eye, and the columns walked. The walk stops at the
// first column that leaves the cover, at the height the segment crosses in a column it descends in, at the region's
// edge, or at the last column it may visit, after which the rest of the segment counts as open.
float snowWalkFarStop(vec3 offset, float regionEnd, out int columnsWalked) {
    columnsWalked = 0;
    int sceneColumnX = int(floor(eyeFraction.x + offset.x));
    int sceneColumnZ = int(floor(eyeFraction.z + offset.z));
    if (!snowWalkCovered(sceneColumnX, sceneColumnZ, 1.0, offset.y)) {
        return 1.0;
    }
    float walkEnd = 1.0 - regionEnd;
    int columnX = sceneColumnX;
    int columnZ = sceneColumnZ;
    int columnStepX = 0;
    int columnStepZ = 0;
    float nextBoundaryX = SNOW_WALK_NO_BOUNDARY;
    float nextBoundaryZ = SNOW_WALK_NO_BOUNDARY;
    float boundaryStepX = SNOW_WALK_NO_BOUNDARY;
    float boundaryStepZ = SNOW_WALK_NO_BOUNDARY;
    if (length(offset.xz) >= SNOW_WALK_MINIMUM_HORIZONTAL_LENGTH) {
        float sceneFractionX = eyeFraction.x + offset.x - float(sceneColumnX);
        float sceneFractionZ = eyeFraction.z + offset.z - float(sceneColumnZ);
        if (offset.x > 0.0) {
            columnStepX = -1;
            nextBoundaryX = sceneFractionX / offset.x;
            boundaryStepX = 1.0 / offset.x;
        } else if (offset.x < 0.0) {
            columnStepX = 1;
            nextBoundaryX = (1.0 - sceneFractionX) / -offset.x;
            boundaryStepX = 1.0 / -offset.x;
        }
        if (offset.z > 0.0) {
            columnStepZ = -1;
            nextBoundaryZ = sceneFractionZ / offset.z;
            boundaryStepZ = 1.0 / offset.z;
        } else if (offset.z < 0.0) {
            columnStepZ = 1;
            nextBoundaryZ = (1.0 - sceneFractionZ) / -offset.z;
            boundaryStepZ = 1.0 / -offset.z;
        }
    }
    float stop = 1.0;
    float enter = 0.0;
    bool isStopped = false;
    for (int columnIndex = 0; columnIndex < SNOW_WALK_FAR_WALK_COLUMNS && !isStopped; columnIndex++) {
        columnsWalked = columnIndex + 1;
        float exit = min(min(nextBoundaryX, nextBoundaryZ), walkEnd);
        float startParameter = 1.0 - enter;
        float endParameter = 1.0 - exit;
        bool descends = offset.y < 0.0;
        // The column's height over the eye's block, fetched once for the coverage test and the crossing:
        float relativeHeight = float(snowWalkColumnHeight(columnX, columnZ) - eyeBlock.y);
        float crossParameter = 0.0;
        if (descends) {
            crossParameter = (relativeHeight - eyeFraction.y) / offset.y;
        }
        if (columnsWalked > 1 && !(eyeFraction.y + startParameter * offset.y < relativeHeight)) {
            stop = startParameter;
            isStopped = true;
        } else if (descends && crossParameter > endParameter) {
            stop = crossParameter;
            isStopped = true;
        } else if (exit >= walkEnd) {
            stop = regionEnd;
            isStopped = true;
        } else if (columnsWalked == SNOW_WALK_FAR_WALK_COLUMNS) {
            stop = endParameter;
            isStopped = true;
        } else {
            float smallestBoundary = min(nextBoundaryX, nextBoundaryZ);
            if (nextBoundaryX <= smallestBoundary) {
                columnX += columnStepX;
                nextBoundaryX += boundaryStepX;
            }
            if (nextBoundaryZ <= smallestBoundary) {
                columnZ += columnStepZ;
                nextBoundaryZ += boundaryStepZ;
            }
            enter = exit;
        }
    }
    return clamp(stop, regionEnd, 1.0);
}

// The optical depth of the fog along the segment from the eye to the scene point at the given offset, as
// SnowFogWalk.opticalDepth, with the cells and the columns that the walk visited, the parts of the coarse walk, and
// where the covered stretch at the end of the segment begins. Bit 0 of partMask runs the cell walk, without which the
// region part takes the analytic fog, and bit 1 runs the backward walk, without which the far part counts as open. The
// normal fog passes 3.
float snowWalkOpticalDepth(vec3 offset, int partMask, out int cellsWalked, out int columnsWalked, out float farStop,
        out int coarseSteps) {
    cellsWalked = 0;
    columnsWalked = 0;
    coarseSteps = 0;
    farStop = 1.0;
    if (!(eyeExtinction > 0.0)) {
        return 0.0;
    }
    if (any(isnan(offset)) || any(isinf(offset))) {
        return 0.0;
    }
    float segmentLength = length(offset);
    if (!(segmentLength > 0.0)) {
        return 0.0;
    }
    float largestOffset = max(abs(offset.x), max(abs(offset.y), abs(offset.z)));
    float regionEnd = min(1.0, regionHalfWidth / largestOffset);
    ivec3 cell = ivec3(0);
    ivec3 cellStep = ivec3(0);
    vec3 nextBoundary = vec3(SNOW_WALK_NO_BOUNDARY);
    vec3 boundaryStep = vec3(SNOW_WALK_NO_BOUNDARY);
    if (offset.x > 0.0) {
        cellStep.x = 1;
        nextBoundary.x = (1.0 - eyeFraction.x) / offset.x;
        boundaryStep.x = 1.0 / offset.x;
    } else if (offset.x < 0.0) {
        cellStep.x = -1;
        nextBoundary.x = eyeFraction.x / -offset.x;
        boundaryStep.x = 1.0 / -offset.x;
    }
    if (offset.y > 0.0) {
        cellStep.y = 1;
        nextBoundary.y = (1.0 - eyeFraction.y) / offset.y;
        boundaryStep.y = 1.0 / offset.y;
    } else if (offset.y < 0.0) {
        cellStep.y = -1;
        nextBoundary.y = eyeFraction.y / -offset.y;
        boundaryStep.y = 1.0 / -offset.y;
    }
    if (offset.z > 0.0) {
        cellStep.z = 1;
        nextBoundary.z = (1.0 - eyeFraction.z) / offset.z;
        boundaryStep.z = 1.0 / offset.z;
    } else if (offset.z < 0.0) {
        cellStep.z = -1;
        nextBoundary.z = eyeFraction.z / -offset.z;
        boundaryStep.z = 1.0 / -offset.z;
    }
    bool walksRegion = (partMask & 1) != 0;
    bool structureOn = structureShare > 0.0;
    float regionDepth = 0.0;
    float enterDepth = 0.0;
    float enter = 0.0;
    bool reachedRegionEnd = false;
    // Without the cell walk, the region takes the analytic fog in one part, so the timing modes leave out its cost:
    if (!walksRegion) {
        enterDepth = snowWalkTauAt(regionEnd, segmentLength, offset.y);
        regionDepth = enterDepth;
        reachedRegionEnd = true;
    }
    for (int cellIndex = 0; cellIndex < SNOW_WALK_MAXIMUM_CELL_STEPS && walksRegion; cellIndex++) {
        cellsWalked = cellIndex + 1;
        float exit = min(min(nextBoundary.x, nextBoundary.y), min(nextBoundary.z, regionEnd));
        float exitDepth = snowWalkTauAt(exit, segmentLength, offset.y);
        float share = snowWalkCellShare(cell);
        float partDepth = max(0.0, exitDepth - enterDepth);
        if (structureOn && share > 0.0) {
            float middle = (enter + exit) * 0.5;
            partDepth *= snowWalkStructureDensity(middle * offset);
        }
        regionDepth += share * partDepth;
        enterDepth = exitDepth;
        enter = exit;
        if (!(exit < regionEnd)) {
            reachedRegionEnd = true;
            break;
        }
        float smallestBoundary = min(nextBoundary.x, min(nextBoundary.y, nextBoundary.z));
        // Every axis whose boundary is the next one steps, so a segment through a cell edge enters both neighbors:
        if (nextBoundary.x <= smallestBoundary) {
            cell.x += cellStep.x;
            nextBoundary.x += boundaryStep.x;
        }
        if (nextBoundary.y <= smallestBoundary) {
            cell.y += cellStep.y;
            nextBoundary.y += boundaryStep.y;
        }
        if (nextBoundary.z <= smallestBoundary) {
            cell.z += cellStep.z;
            nextBoundary.z += boundaryStep.z;
        }
    }
    // A walk that ran out of steps counts the rest of the region as open, which errs toward fog:
    if (!reachedRegionEnd) {
        float endDepth = snowWalkTauAt(regionEnd, segmentLength, offset.y);
        regionDepth += max(0.0, endDepth - enterDepth);
        enterDepth = endDepth;
    }
    float farDepth = 0.0;
    if (regionEnd < 1.0) {
        if ((partMask & 2) != 0) {
            farStop = snowWalkFarStop(offset, regionEnd, columnsWalked);
        }
        float baseDepth = max(0.0, snowWalkTauAt(farStop, segmentLength, offset.y) - enterDepth);
        if (!structureOn) {
            farDepth = baseDepth;
        } else {
            float coarseEnd = min(farStop, min(1.0, structureRange / segmentLength));
            if (coarseEnd > regionEnd) {
                farDepth = snowWalkCoarseDepth(offset, segmentLength, regionEnd, coarseEnd, coarseSteps);
                farDepth += max(0.0, snowWalkTauAt(farStop, segmentLength, offset.y)
                        - snowWalkTauAt(coarseEnd, segmentLength, offset.y));
            } else {
                farDepth = baseDepth;
            }
        }
    }
    float total = regionDepth + farDepth;
    if (!(total > 0.0)) {
        return 0.0;
    }
    return min(FOG_MAXIMUM_OPTICAL_DEPTH, total);
}

// The optical depth of the snow fog from the eye to infinity along a direction, as SnowFogWalk.skyOpticalDepth, which is
// the walk to walkDistance along the unit direction and the tail past it while the walk ends in the open. The tail is
// the height fog from the point at walkDistance to infinity at the snow share 1 and the density 1, which the structures
// leave past their range. A direction of zero length or one that is not finite, and a distance that is not positive or
// not finite, give 0 and leave the outputs as a walk of length 0 does:
float snowWalkSkyOpticalDepth(vec3 direction, float walkDistance, int partMask, out int cellsWalked,
        out int columnsWalked, out float farStop, out int coarseSteps) {
    cellsWalked = 0;
    columnsWalked = 0;
    coarseSteps = 0;
    farStop = 1.0;
    float directionLength = length(direction);
    if (!(directionLength > 0.0) || isnan(directionLength) || isinf(directionLength) || !(walkDistance > 0.0)
            || isnan(walkDistance) || isinf(walkDistance)) {
        return 0.0;
    }
    vec3 unit = direction / directionLength;
    float walkedDepth = snowWalkOpticalDepth(unit * walkDistance, partMask, cellsWalked, columnsWalked, farStop,
            coarseSteps);
    if (!(farStop >= 1.0)) {
        return walkedDepth;
    }
    float tailDepth = fogSkyTailOpticalDepth(eyeExtinction, walkDistance, unit.y, heightScale);
    return min(FOG_MAXIMUM_OPTICAL_DEPTH, walkedDepth + tailDepth);
}
