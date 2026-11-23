# The Weather Fog Gets Its Own Far Step Cap and Cheaper Defaults

- Time: `2026-11-23 11:35:02 +0200`
- Author: `Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `2026-11-23_11-08-18_weather-fog-far-cell-mask.md`, not committed.
- Scope:
  - `SnowFogWalk`, `FogFroxelGrid`, `SkySetting`, `snow_walk.glsl`, `weather_fog_composite.fsh`, `FogFroxelGameTests`, `FogSettingsGameTests`

## Completed

**Why.** empireu timed the froxel fog per pass in a blizzard (fullscreen, GPU at maximum performance) and checked the open questions Q3 and Q8 of the froxel run in game:

| Slices | Range | GPU ms | CPU ms |
|---|---|---|---|
| 64 | 192 | 0.315 | 0.115 |
| 32 | 192 | 0.225 | 0.107 |
| 64 | 128 | 0.303 | 0.117 |
| 32 | 128 | 0.218 | 0.126 |

The slice count sets the cost and the range barely does. In snow, ranges 64 to 128 showed no visible difference against a tree at the edge of visibility, so Q3 stays as built. At 32 slices the froxel debug view showed capped far stretches (blue) near the horizon at ranges 192 and 512, mostly gone at 128, which is Q8: the capped rest of a long piece counts as open air, which is wrong only over cover far away.

**What changed.**
- The far stretch of a piece has its own cap, `SnowFogWalk.PIECE_FAR_MAXIMUM_STEPS` and `SNOW_WALK_PIECE_FAR_MAXIMUM_STEPS`, 32 cells. The near stretch keeps `PIECE_MAXIMUM_STEPS` 16. `walkPiece` takes both caps, and the whole walk passes `WHOLE_MAXIMUM_STEPS` for both. The froxel debug view's green scales by the far cap.
- `fogFroxelSlices` defaults to 32 (`SkySetting` and `FogFroxelGrid.DEFAULT_SLICES`).
- `fogBlizzardStructureShare` defaults to 0.2, the value empireu uses.
- `cappedPieceAddsTheRestAtShareOneAndDensityOne` walks the far pieces from 30 to 97 instead of 65, so they still run out of the 32 far cells. The capped rest is still 3.5 blocks, and the open and roofed depths grow by the 32 extra blocks at the extinction 0.1.
- The dev client config `run/config/frostedheart-client.toml` was set to the code defaults in `[CalculatedSky]`, which is not tracked.

## Validation

- `agent-tools/build.sh` compiles the main and GameTest sources.
- `agent-tools/glsl-check.sh` passes `weather_fog_inject.fsh`, `weather_fog_composite.fsh` and `weather_fog_share.fsh` with `weather_fog.glsl` and `snow_walk.glsl` prepended.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 241 required tests passed`.

- In game, empireu measured 0.25 ms GPU at 32 slices and range 192 with the new far cap (0.225 ms with the cap of 16), in the same blizzard view, and the froxel debug view shows no capped pixels.

## Documentation

No living docs changed, because the weather docs are written in stage 11.

## Remaining

- The fog costs 0.25 ms against the plan's target of 0.2 ms. The next lever is a far cell of 4 blocks, which needs both constants changed together and a GameTest run.
