// The walk of the fog along one piece of a column ray, from a start distance to an end distance along a unit direction,
// through the snow field near the eye and the far heights of the columns beyond it, which SnowFogWalk repeats on the CPU
// so that the two can be compared. FHShaders prepends weather_fog.glsl before it, for fogOpticalDepth.
// The fog's extinction at a point is the height fog times a snow share in the range 0 to 1 times the density of the fog
// structures, so the optical depth of the segment is the integral of that product. The walk splits the segment into
// parts on which the share is constant and adds the closed form of fogOpticalDepth
// at the two ends of each part times the
// density of the structures at its middle, which is exact along one ray from the eye and takes no samples.
// While the structures are on, the parts inside the region are the cells, and the parts beyond it are the
// SNOW_WALK_FAR_CELL cells with the height share of the column under the cell's middle. Past the structure range the
// density is 1, which the rest of the far part takes in closed form.
// A piece of a ray, which the froxel stages walk, is the stretch between two distances along a unit direction, which the
// inject cuts at the slice boundaries. Its near stretch inside the region is the cell walk from the cell that holds the
// start distance, and its far stretch beyond it is the world-aligned grid of SNOW_WALK_FAR_CELL blocks, whose share is
// the height share of the column under the cell's middle. Each stretch visits at most SNOW_WALK_PIECE_MAXIMUM_STEPS
// cells, and the rest of a stretch that runs out of them counts as the open fog at the share 1 and the density 1.
// Every position here is a cell offset from the eye's block, so no world position passes through a float.
// The field of the snow near the eye, a cube of size fieldMask + 1 cells
// whose bytes are distances in eighths of a block:
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

// The side in blocks of the world-aligned cells that the far stretch of a piece is walked in, and the most cells that
// each stretch of a piece visits, as SnowFogWalk.FAR_CELL and SnowFogWalk.PIECE_MAXIMUM_STEPS:
const float SNOW_WALK_FAR_CELL = 2.0;
const int SNOW_WALK_PIECE_MAXIMUM_STEPS = 16;
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

// The motion height of the column of a cell, which FarHeights.UNKNOWN makes open. The texel wraps with a bitwise and,
// since the modulo of a negative value is undefined.
int snowWalkColumnHeight(int columnOffsetX, int columnOffsetZ) {
    return texelFetch(farHeights, ivec2(eyeBlock.x + columnOffsetX, eyeBlock.z + columnOffsetZ)
            & ivec2(FAR_HEIGHTS_MASK), 0).r;
}

// The height share of a cell: 1 at or above the motion height of its column and 0 below it, so a column whose height is
// FarHeights.UNKNOWN, which stands below every cell, counts as open:
float snowWalkHeightShare(ivec3 cell) {
    return float(eyeBlock.y + cell.y) >= float(snowWalkColumnHeight(cell.x, cell.z)) ? 1.0 : 0.0;
}

// The share of a far cell of SNOW_WALK_FAR_CELL blocks, which is the height share of the 1-block cell that holds its
// middle, counted from the eye's block as SnowFogWalk.walkFarCells counts it. The eye's place in the far grid is its low
// block bit on every axis plus its fraction, so eyeBlock & ivec3(1) subtracts that bit from the cell's middle:
float snowWalkFarShare(ivec3 farCell) {
    ivec3 columnCell = ivec3(floor((vec3(farCell) + vec3(0.5)) * SNOW_WALK_FAR_CELL)) - (eyeBlock & ivec3(1));
    return snowWalkHeightShare(columnCell);
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

// The optical depth of the stretch of a ray inside the region, from the cell that holds the start distance, as
// SnowFogWalk.walkNearCells: each cell adds its share times the density of the structures
// at the middle of the part times
// the closed form between the two ends of the part, and the rest after maximumSteps cells counts as the open fog at the
// share 1 and the density 1. Records the near cells and their cap.
float snowWalkNearCells(vec3 unit, float startDistance, float endDistance, int maximumSteps, out int cellsWalked,
        out bool capped) {
    vec3 start = eyeFraction + unit * startDistance;
    ivec3 cell = ivec3(floor(start));
    ivec3 cellStep = ivec3(0);
    vec3 nextBoundary = vec3(SNOW_WALK_NO_BOUNDARY);
    vec3 boundaryStep = vec3(SNOW_WALK_NO_BOUNDARY);
    if (unit.x > 0.0) {
        cellStep.x = 1;
        nextBoundary.x = startDistance + (float(cell.x + 1) - start.x) / unit.x;
        boundaryStep.x = 1.0 / unit.x;
    } else if (unit.x < 0.0) {
        cellStep.x = -1;
        nextBoundary.x = startDistance + (float(cell.x) - start.x) / unit.x;
        boundaryStep.x = 1.0 / -unit.x;
    }
    if (unit.y > 0.0) {
        cellStep.y = 1;
        nextBoundary.y = startDistance + (float(cell.y + 1) - start.y) / unit.y;
        boundaryStep.y = 1.0 / unit.y;
    } else if (unit.y < 0.0) {
        cellStep.y = -1;
        nextBoundary.y = startDistance + (float(cell.y) - start.y) / unit.y;
        boundaryStep.y = 1.0 / -unit.y;
    }
    if (unit.z > 0.0) {
        cellStep.z = 1;
        nextBoundary.z = startDistance + (float(cell.z + 1) - start.z) / unit.z;
        boundaryStep.z = 1.0 / unit.z;
    } else if (unit.z < 0.0) {
        cellStep.z = -1;
        nextBoundary.z = startDistance + (float(cell.z) - start.z) / unit.z;
        boundaryStep.z = 1.0 / -unit.z;
    }
    bool structureOn = structureShare > 0.0;
    float depth = 0.0;
    float enter = startDistance;
    float enterDepth = snowWalkTauAt(enter, 1.0, unit.y);
    bool reachedEnd = false;
    cellsWalked = 0;
    for (int cellIndex = 0; cellIndex < maximumSteps; cellIndex++) {
        cellsWalked = cellIndex + 1;
        float exit = min(min(nextBoundary.x, nextBoundary.y), min(nextBoundary.z, endDistance));
        float exitDepth = snowWalkTauAt(exit, 1.0, unit.y);
        float share = snowWalkCellShare(cell);
        float partDepth = max(0.0, exitDepth - enterDepth);
        if (structureOn && share > 0.0) {
            float middle = (enter + exit) * 0.5;
            partDepth *= snowWalkStructureDensity(middle * unit);
        }
        depth += share * partDepth;
        enter = exit;
        enterDepth = exitDepth;
        if (!(exit < endDistance)) {
            reachedEnd = true;
            break;
        }
        float smallestBoundary = min(nextBoundary.x, min(nextBoundary.y, nextBoundary.z));
        // Every axis whose boundary is the next one steps, so a segment through a cell edge enters both neighbors.
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
    if (!reachedEnd) {
        depth += max(0.0, snowWalkTauAt(endDistance, 1.0, unit.y) - enterDepth);
    }
    capped = !reachedEnd;
    return depth;
}

// The optical depth of the stretch of a ray beyond the region, from the far cell that holds the start distance, as
// SnowFogWalk.walkFarCells: a cell adds the height share of the column under its middle times the density of the
// structures at the middle of the part times the closed form between the two ends of the part, which is nothing under
// cover, and the rest after maximumSteps cells counts as the open fog at the share 1 and the density 1. The grid is
// world aligned, so the eye's place in it is its low block bit on every axis plus its fraction.
float snowWalkFarCells(vec3 unit, float startDistance, float endDistance, int maximumSteps, out int cellsWalked,
        out bool capped) {
    vec3 start = vec3(eyeBlock & ivec3(1)) + eyeFraction + unit * startDistance;
    ivec3 cell = ivec3(floor(start / SNOW_WALK_FAR_CELL));
    ivec3 cellStep = ivec3(0);
    vec3 nextBoundary = vec3(SNOW_WALK_NO_BOUNDARY);
    vec3 boundaryStep = vec3(SNOW_WALK_NO_BOUNDARY);
    if (unit.x > 0.0) {
        cellStep.x = 1;
        nextBoundary.x = startDistance + (float(cell.x + 1) * SNOW_WALK_FAR_CELL - start.x) / unit.x;
        boundaryStep.x = SNOW_WALK_FAR_CELL / unit.x;
    } else if (unit.x < 0.0) {
        cellStep.x = -1;
        nextBoundary.x = startDistance + (float(cell.x) * SNOW_WALK_FAR_CELL - start.x) / unit.x;
        boundaryStep.x = SNOW_WALK_FAR_CELL / -unit.x;
    }
    if (unit.y > 0.0) {
        cellStep.y = 1;
        nextBoundary.y = startDistance + (float(cell.y + 1) * SNOW_WALK_FAR_CELL - start.y) / unit.y;
        boundaryStep.y = SNOW_WALK_FAR_CELL / unit.y;
    } else if (unit.y < 0.0) {
        cellStep.y = -1;
        nextBoundary.y = startDistance + (float(cell.y) * SNOW_WALK_FAR_CELL - start.y) / unit.y;
        boundaryStep.y = SNOW_WALK_FAR_CELL / -unit.y;
    }
    if (unit.z > 0.0) {
        cellStep.z = 1;
        nextBoundary.z = startDistance + (float(cell.z + 1) * SNOW_WALK_FAR_CELL - start.z) / unit.z;
        boundaryStep.z = SNOW_WALK_FAR_CELL / unit.z;
    } else if (unit.z < 0.0) {
        cellStep.z = -1;
        nextBoundary.z = startDistance + (float(cell.z) * SNOW_WALK_FAR_CELL - start.z) / unit.z;
        boundaryStep.z = SNOW_WALK_FAR_CELL / -unit.z;
    }
    bool structureOn = structureShare > 0.0;
    float depth = 0.0;
    float enter = startDistance;
    float enterDepth = snowWalkTauAt(enter, 1.0, unit.y);
    bool reachedEnd = false;
    cellsWalked = 0;
    for (int cellIndex = 0; cellIndex < maximumSteps; cellIndex++) {
        cellsWalked = cellIndex + 1;
        float exit = min(min(nextBoundary.x, nextBoundary.y), min(nextBoundary.z, endDistance));
        float exitDepth = snowWalkTauAt(exit, 1.0, unit.y);
        float share = snowWalkFarShare(cell);
        if (share > 0.0) {
            float partDepth = max(0.0, exitDepth - enterDepth);
            if (structureOn) {
                float middle = (enter + exit) * 0.5;
                partDepth *= snowWalkStructureDensity(middle * unit);
            }
            depth += share * partDepth;
        }
        enter = exit;
        enterDepth = exitDepth;
        if (!(exit < endDistance)) {
            reachedEnd = true;
            break;
        }
        float smallestBoundary = min(nextBoundary.x, min(nextBoundary.y, nextBoundary.z));
        // Every axis whose boundary is the next one steps, so a segment through a cell edge enters both neighbors.
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
    if (!reachedEnd) {
        depth += max(0.0, snowWalkTauAt(endDistance, 1.0, unit.y) - enterDepth);
    }
    capped = !reachedEnd;
    return depth;
}

// The optical depth of a piece of the ray of a unit direction from the start to the end distance, as
// SnowFogWalk.walkPiece, whose near stretch inside the region and far stretch beyond it are cut at
// regionHalfWidth / largestComponent. Bit 0 of partMask runs the near walk and bit 1 the far one, and a stretch that is
// off takes the closed form of the open fog instead, with no cells, no share and no density, and reports 0 steps and no
// cap; with both off the piece is the height fog of the whole stretch, which is the analytic timing mode. Rejected
// inputs give 0 and leave the four out values at 0 and false, and the depth is not clamped, since the composite clamps
// the total of the column. Every closed form of the piece starts at the eye, as the analytic depth of a boundary does.
float snowWalkPiece(vec3 unit, float startDistance, float endDistance, int partMask, out int nearSteps,
        out bool nearCapped, out int farSteps, out bool farCapped) {
    nearSteps = 0;
    nearCapped = false;
    farSteps = 0;
    farCapped = false;
    if (!(eyeExtinction > 0.0) || any(isnan(unit)) || any(isinf(unit))) {
        return 0.0;
    }
    float directionLength = length(unit);
    if (!(directionLength > 0.0) || isnan(directionLength) || isinf(directionLength)) {
        return 0.0;
    }
    if (isnan(startDistance) || isinf(startDistance) || isnan(endDistance) || isinf(endDistance)
            || !(startDistance < endDistance)) {
        return 0.0;
    }
    float largestComponent = max(abs(unit.x), max(abs(unit.y), abs(unit.z)));
    if (!(largestComponent > 0.0)) {
        return 0.0;
    }
    float exitDistance = regionHalfWidth / largestComponent;
    float nearStart = max(startDistance, 0.0);
    float nearEnd = min(endDistance, exitDistance);
    float farStart = max(startDistance, exitDistance);
    bool runsNear = (partMask & 1) != 0;
    bool runsFar = (partMask & 2) != 0;
    float depth;
    if (!runsNear && !runsFar) {
        depth = max(0.0, snowWalkTauAt(endDistance, 1.0, unit.y) - snowWalkTauAt(nearStart, 1.0, unit.y));
    } else {
        depth = 0.0;
        if (nearEnd > nearStart) {
            if (runsNear) {
                int steps;
                bool isCapped;
                depth += snowWalkNearCells(unit, nearStart, nearEnd, SNOW_WALK_PIECE_MAXIMUM_STEPS, steps, isCapped);
                nearSteps = steps;
                nearCapped = isCapped;
            } else {
                depth += max(0.0, snowWalkTauAt(nearEnd, 1.0, unit.y) - snowWalkTauAt(nearStart, 1.0, unit.y));
            }
        }
        if (endDistance > farStart) {
            if (runsFar) {
                int steps;
                bool isCapped;
                depth += snowWalkFarCells(unit, farStart, endDistance, SNOW_WALK_PIECE_MAXIMUM_STEPS, steps, isCapped);
                farSteps = steps;
                farCapped = isCapped;
            } else {
                depth += max(0.0, snowWalkTauAt(endDistance, 1.0, unit.y) - snowWalkTauAt(farStart, 1.0, unit.y));
            }
        }
    }
    if (!(depth > 0.0)) {
        return 0.0;
    }
    return depth;
}
