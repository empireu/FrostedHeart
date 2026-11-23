# The Weather Fog Walks Column Rays Once Into a Froxel Grid

- Time: `2026-11-22 01:30:12 +0200`
- Author: `GLM 5.3 Max orchestrator, supervising DeepSeek V4.1 Flash 1017 (test writer, implementer, advisor, reviewer) and GLM (reviewer) subagents through Subordinate pattern-blue, supervised by empireu (outside contributor) and Claude Opus 5.5`
- Status: `done` in the working tree on `feature/weather` after `2026-11-21_14-10-00_weather-fog-share-volume.md`, not committed. It waits for the in-game check and a new GPU timing.
- Scope:
  - new `content/climate/render/fog/FogFroxelGrid`, `FogFroxelGameTests`, `FogPassTimer`, `shaders/weather_fog_inject.fsh`, `shaders/weather_fog_integrate.fsh`, `shaders/weather_fog_composite.fsh`
  - `SnowFogWalk`, `WeatherFogRenderer`, `FHShaders`, `SkySetting`, `CalculatedSkyClientCommand`, `snow_walk.glsl`, `weather_fog.glsl`, `SnowFogWalkGameTests`, `FogSettingsGameTests`
  - removed `FogUpsample`, `FogUpsampleGameTests`, `shaders/weather_fog.fsh`, `shaders/weather_fog_upsample.fsh`

## Completed

**Why.** After the share volume, the fog still walks one ray per sky pixel across the whole range: the sky is the expensive half of the draw, since its rays never end at a scene point. The plan (`scratch/weather-fog-froxel-proposal.md`) moves the walk to a froxel grid: one column ray per `fogFroxelDivisor` by `fogFroxelDivisor` pixels, walked once into `fogFroxelSlices` slices out to `fogFroxelRange`, and every pixel blends the fog of the two columns beside it. The plan estimates about 0.12 to 0.17 ms for the whole fog against the 0.36 and 0.53 ms measured for the two current paths, and the walk of the whole ray stays only on the CPU twin.

**What changed.**
- A CPU twin of the piece walk (`SnowFogWalk`): one shared `walkPiece` from a start to an end distance (a near part through the field's cells, a far part through world-aligned 2-block cells under the height share of the column under the cell's middle, at most 16 cells a piece per part with the rest open), and on it the whole ray sum, the per-boundary `columnExcesses` and the sky depth; the backward and coarse walks and their counters are gone.
- `FogFroxelGrid` maps the grid: columns and rows, geometric boundaries from `NEAR` 1 block, the atlas tiles (`(k - 1) % 8`, `(k - 1) / 8`), the layers and channels of the excess array (`(k - 1) / 4`, `(k - 1) % 4`), and the Java mirror of the composite's excess lookup.
- Three fragment stages replace the two per-pixel ones: the inject walks the piece of each column's center ray per fragment of a 512-row atlas and writes the piece's optical depth (`snow_walk.glsl` grew the piece walk and the part rules); the integrate sums the pieces into the running excess over the analytic height fog at every boundary, into a 2D array through two draws of eight layers; the composite reads each pixel's depth, takes the interpolated excess at its distance (the sky takes the excess at the range plus the analytic tail), and blends the snow color.
- `WeatherFogRenderer` runs the share pass, the inject, the integrate and the composite with per-pass timing (`FogPassTimer`, `/calculatedSky fog passes`), the two old stages, `FogUpsample`, its query, its tests and the `fogResolutionDivisor`/`fogFallbackCap` settings are deleted, and the debug views are the field, the froxel steps, the transmittance and the structures.
- The GPU self test drives the three production stages over the fixed 64 rays as a 64 by 1 grid at the frame's slice count and again at 64, into its own targets, reads every boundary and the sky value back, and compares them against the twin (`/calculatedSky fog selftest`). The fog self test of the two stages it replaces is gone.

## Decisions

All settled with the run's persistent advisor and recorded in `scratch/froxel-run/` (STATE.md, log.md, ADVISOR.md, questions.md):
- The pieces-sum identity is tested with the structures off and once with a constant-density structure, where it is exact; a textured structure is tested against a fine-step reference, because a slice boundary that cuts a cell samples the density twice (Q1, and the plan's section 11 bullet is overstated to say the least).
- The step caps stay at 16 cells per part per piece as the plan fixes them (Q2); their cost at low slice counts and long ranges is Q8 below.
- The GLSL piece returns the raw depth, keeping the NaN guard, mirroring the `walkPiece` the column excesses sum; only the composite clamps the total (Q-h). The composite's sky early-out - carried from the old branch - was removed so the sky is exactly the plan's formula, `analytic + excess + tail` clamped (Q-k).
- The self test runs the whole cycle at the frame's slice count and again at 64 when below (Q-i), compares the pieces plus the tail for the sky rather than `skyOpticalDepth` (Q-j), and reports the twin's step and cap counts.
- The targets are allocated at the maximum sizes (the atlas at its full tile height, the array at 16 layers), so a slice-count change reallocates nothing (Q4, Q7); the plan's setting-sized numbers are the one-line alternative.
- The old path's last children went after it: the five unread GLSL members and the stale intro (Q-l), the unused share of the walk with its old counters, and the unreachable debug mode number.
- `d_N` stays the free setting `fogFroxelRange` exactly as the plan writes it (Q3, below).

## Validation

- `agent-tools/build.sh` passes on the final tree; `agent-tools/glsl-check.sh` passes on the composite, inject and share against the full prepends, on the integrate against its include, and on `snow_walk.glsl` as an include.
- GameTests `frostedheart_weather` and `frostedheart_calculated_sky`, three runs, all `All 241 required tests passed :)` (248 tests in run 1, before one over-specified assertion of ours was removed; the run's rounds after that hold 241).
- Reviews by two independent agents on the full diff and the new files: no behavioural defect; the GameTest pins were recomputed by hand and match. Their style findings (stale openings, a false ray javadoc, six long lines, a missing debug mode number, no production caller for the Java lookup mirror) were verified and fixed; nothing else changed.
- The GPU self test has not been run: no game was started in this change, so its printed lines wait for the in-game check.

## Documentation

None changed. The weather docs are written in stage 11.

## Remaining

- The in-game check: `/calculatedSky fog selftest` (both slice counts, with the structures on), a look at the fog, the sky and the `field`, `froxel`, `transmittance` and `structure` debug views, then `/calculatedSky fog passes` for the per-pass timing against the plan's 0.12 to 0.17 ms estimate.
- Q3, the user's call after the check: `d_N` is the free setting `fogFroxelRange` today; the recommendation is `d_N = max(fogFroxelRange, regionHalfWidth * sqrt(3), fogStructureRange)` so shelter and structures past a short range are not dropped. One line in `FogFroxelGrid.rangeOf` changes `d_N` either way (`scratch/froxel-run/questions.md`).
- Q8, same check: at 32 slices the last pieces can reach the 16-cell far cap (29.2 blocks and 25 far cells at the default range 192; above at any longer range), and the capped remainder counts as open fog, per the plan's cap rule. Keep, or raise the far cap (a plan change that moves the inject cost).
- The piece atlas is 132.7 MB at 3840 by 2160 with `fogFroxelDivisor` 4 (against 66.4 MB if the allocation followed a 32-slice setting); 8.3 MB at the defaults. If 4K users with divisor 4 matter, the setting-sized allocation is the one-line change of Q4/Q7.
