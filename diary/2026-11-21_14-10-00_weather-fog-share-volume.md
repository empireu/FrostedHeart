# Weather Fog Walk Reads the Snow Share from a Per-Frame Volume

- Time: `2026-11-21 14:10:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `4faaceae7`, not committed. It waits for the in-game check and a new GPU timing.
- Scope:
  - new `content/climate/render/fog/FogShareVolume`, `shaders/weather_fog_share.fsh` and `FogShareVolumeGameTests`
  - `WeatherFogRenderer`, `FHShaders`, `snow_walk.glsl`

## Completed

**Why.** In a blizzard the fog took about 2.6 ms of GPU time per frame at 1080p and divisor 2, five times the 0.5 ms budget for fog and snowfall. The timing modes split it (log of 2026-11-21, same settings in every mode, no fallback pixels): off 2.583 ms, `noregion` 0.590 ms, `nofar` 2.624 ms, `analytic` 0.572 ms. So about 2.0 ms is the walk through the region cube, where every ray recomputes the share of each cell it crosses. Sky pixels cost the most, since they cross the whole cube (sky view 2.726 ms against ground view 1.129 ms). `fogStructureRange` and `fogStructureSize` made no measurable difference.

**What changed.**
- A share pass at the start of each fog draw writes `snowWalkCellShareDirect` (the old `snowWalkCellShare`, unchanged) for every cell of an 80-cell cube around the eye's block into an R16F atlas of 800 by 640 texels, 80 horizontal slices of 80 by 80 in 10 by 8 tiles (`FogShareVolume`).
- `snowWalkCellShare` reads one texel of it with `texelFetch` when a volume is bound and the cell is inside, and falls back to the direct share otherwise. So the cell walk, the full resolution fallback, the self test and the debug views all take it.
- The height fog per cell and the structure density at each ray's segment midpoint are unchanged, so the result is the same up to the half precision of the share (below 2^-11 relative), inside the self test's tolerance. `SnowFogWalk` keeps its math.
- The atlas sits on texture unit 1, which the removed horizon texture freed. If its framebuffer is incomplete, the fog logs once and uses the direct share for the session, and `/calculatedSky fog` says so.
- The share pass is inside the fog's GPU timer. The self test refills the volume before it draws.

## Decisions

- Design in `scratch/weather-fog-cost-proposal.md`, reviewed by two fast agents (both "agree with changes"). They rejected baking the height fog and the structure density into the volume. The wisps are about 2 blocks wide, so per-cell structure density would show as blocks. Storing the height fog per cell would add an error for nothing.
- The volume is eye relative with one texel per world block, so it cannot crawl as the camera turns. A view-aligned froxel volume was rejected for that reason.
- A 2D atlas written in one draw, instead of a 3D target written slice by slice.

## Validation

- `agent-tools/build.sh` passes, `glsl-check.sh` passes on all fog shaders.
- GameTests `frostedheart_weather` and `frostedheart_calculated_sky`: `All 237 required tests passed`. The new tests check the atlas constants, the margin against the largest `fogFieldRadius`, `contains` at the corners, four hand-computed atlas texels, and that all 512000 cells map to distinct texels.
- Reviews: reviewer B (shader index math by hand in both directions) found nothing. Reviewer A found the share program's unused `fogStructure` sampler left on unit 0, where a 2D depth texture is bound, which is fixed. Not changed: a zero object name from `glGen*` is not checked (as elsewhere in the file), and the share pass queries the framebuffer binding and the viewport that its caller already saved.
- In game on 2026-11-21 (blizzard, structure share 0.20, nominal view, divisor 2 unless noted):
  - Self test: 0 of 64 rays and 0 of 64 sky rays outside the tolerance, largest error 0.000896.
  - Fog GPU: off 0.741 ms (2.583 ms before), `noregion` 0.283 ms, `analytic` 0.259 ms, `analytic` with structures off 0.218 ms, and the same at divisor 4 about 0.19 ms. Off at divisor 4 with structures on: 0.52 to 0.57 ms.
  - The cell walk is now about 0.46 ms, down from about 2.0 ms.
  - `analytic` was 0.572 ms in the run before the change and 0.259 ms in this one, although the change does not touch that mode. So only readings within one run compare.

## Documentation

None changed. The weather docs are written in stage 11.

## Remaining

- The in-game check: `/calculatedSky fog selftest` and a look at the fog, the sky and the `field` debug view against the last screenshots, then the timings off, `noregion` and `analytic` again, plus `analytic` with structures off and `analytic` at divisor 4 to split the 0.57 ms floor.
- The share pass fills the whole atlas, 512000 cells, against at most 61 cubed reachable at the default radius and 23 cubed at radius 12. Its cost shows in the new timing.
- Next steps, by the proposal's revision: the structure density into the volume only after an A/B in the `structure` debug view, and sky rays on a 2 by 2 stride if the sky stays expensive.
