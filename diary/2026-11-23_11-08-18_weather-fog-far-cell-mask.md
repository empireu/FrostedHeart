# The Far Grid of the Weather Fog Takes Its Mask From the Cell Size

- Time: `2026-11-23 11:08:18 +0200`
- Author: `Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `2026-11-22_01-30-12_weather-fog-froxel-grid.md`, not committed.
- Scope:
  - `SnowFogWalk`, `snow_walk.glsl`, `weather_fog_composite.fsh`, `SnowFogWalkGameTests`

## Completed

**Why.** The far part of a fog piece walks a world-aligned grid of `FAR_CELL` blocks. The eye's place in that grid was taken as `eyeBlock & 1`, which is right only while the cell is 2 blocks. The plan names a 4-block far cell as the first cost lever, and at 4 the grid would have jumped by 2 blocks each time the eye crossed a 2-block line, so the far fog's cover edges would pop while walking. The CPU twin had the same mask, so no test could see it.

**What changed.**
- `SnowFogWalk.FAR_CELL_BLOCKS` (an int, a power of two) and `FAR_CELL_MASK` are the source of the cell size, and `FAR_CELL` is derived from them. `walkFarCells` masks the eye's block with `FAR_CELL_MASK`.
- `snow_walk.glsl` has the same `SNOW_WALK_FAR_CELL_BLOCKS` and `SNOW_WALK_FAR_CELL_MASK`, used by `snowWalkFarShare` and `snowWalkFarCells`, and the field debug view in `weather_fog_composite.fsh` uses the mask too.
- The structure debug view's private grid has `DEBUG_STRUCTURE_CELL_BLOCKS` and `DEBUG_STRUCTURE_CELL_MASK` in place of the literal `& 3`.
- The GameTest reference `referenceFarShare` finds the grid origin with `Math.floorDiv` by `FAR_CELL_BLOCKS` instead of `& ~1`, so it stays independent of the mask and follows the cell size.

## Validation

- `agent-tools/build.sh` compiles the main and GameTest sources.
- `agent-tools/glsl-check.sh` passes `weather_fog_inject.fsh`, `weather_fog_composite.fsh` and `weather_fog_share.fsh`, each with `weather_fog.glsl` and `snow_walk.glsl` prepended.
- At the cell size 2 the mask is 1, so the walk is unchanged and the GameTests were not rerun. No GameTest runs the walk at 4, because the GPU cell size is a shader constant and the twin has no parameter for it.

## Documentation

No living docs changed, because the weather docs are written in stage 11.

## Remaining

- Measuring the 4-block far cell as a cost lever means changing both constants together and running the GameTests and the GPU self test.
