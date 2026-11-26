# Flat Ravine Cavity Surface, Wisp Damping, Steeper Blizzard Flakes, and Ravine Snow Trapping

- Time: `2026-11-26 19:50:00 +0200`
- Author: `Gemini 4 Neon, supervised by Anthropic Claude Opus 5.5 and empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `ba6988b1f`, not committed. Second revision to the third piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Changed: `SkySetting`, `FogCavity`, `FogCavityMap`, `fog_cavity_build.fsh`, `snow_walk.glsl`, `SnowFogWalk`, `WeatherFogRenderer`, `SnowFlakes`, `SnowLee`, `SnowLeeMap`, `snow_lee.glsl`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl`, `SnowfallRenderer`, `FogCavityGameTests`, `SnowLeeGameTests`, `FogSettingsGameTests`

## Completed

**Why.** Checking the first revision of piece 3 in game in a blizzard showed three remaining mismatches: (1) inside a deep ravine, horizontal `FogStructure` wisps kept full contrast right up to the stone walls and the inverted-V enclosure surface (`min(h_pos, h_neg)`) sat lowest against the walls, making fog seem to emerge from the rock while `fogCavityShare = 0.5` left half the fog down to the floor; (2) walking close to a steep 45-degree hill face cut off eye-level snow because nearly horizontal 1-in-20 blizzard paths routed most box re-entry into the buried upwind face while leaving a concave-foot relief lee; and (3) cross-wind flakes slammed into ravine walls instead of reaching the floor where server snow accumulation places snow layers. Lowering `fogCavityShare` to 0 also needed the vanilla fog culling bound to count cavity thinning only when the eye is near an enclosing cavity so open ground keeps its culling distance.

**What changed.**
- **Flat wall-to-wall cavity surface with opposing-rim tracking (`FogCavity`, `FogCavityMap`, `fog_cavity_build.fsh`).**
  - Intermediate work passes of `fog_cavity_build.fsh` now write two MRT outputs: sloped wall maxima to `outSloped` (`GL_RGBA32F`, attachment 0) and unsloped raw wall maxima to `outRaw` (`GL_RGBA16F`, attachment 1, +4 MB VRAM, 0 extra passes). `FogCavityMap` queries `glGetFragDataLocation` for both outputs and sets per-pass `glDrawBuffers` via preallocated buffers.
  - Passes 5 and 11 apply the 3-texel perpendicular minimum to both sloped and raw channels and combine each axis as `min(min(rawPos, rawNeg), 0.5 * (slopedPos + slopedNeg))`, publishing `(flatSurface, lowerRim)` into `GL_RG32F` targets (+3 MB VRAM). This makes the enclosure surface flat from wall to wall across ravines, clamps lopsided ravines to their lower rim, keeps wider ravines lower, and keeps single cliff feet, 80-block valleys, 1-in-2 V-valleys, and narrow posts unthinned.
- **Depth-faded fog wisps and `fogCavityShare` default 0 (`SkySetting`, `FogCavity`, `snow_walk.glsl`, `SnowFogWalk`, `WeatherFogRenderer`).**
  - Changed `SkySetting.FOG_CAVITY_SHARE` default from `0.5` to `0.0`.
  - Below `h_cav`, both `snow_walk.glsl` (`snowWalkCavityFactors`) and `SnowFogWalk` compute `openFactor = exp(-depth / cavityDecayLength)` and `wispFactor = 1.0 - cavityWindFactor * (1.0 - openFactor)` alongside `densityFactor`, attenuating `FogStructure` contrast as `1.0 + wispFactor * (structureDensity - 1.0)`. Wisps drift across the ravine opening and die out downward while remaining continuous across the calm-wind threshold. `/calculatedSky fog selftest` passes `frameCavityWindFactor` into `selfTestWalk`.
- **Near-cavity culling bound (`FogCavity`, `WeatherFogRenderer`).**
  - Added `FogCavity.NearCavityQuery` (radius `NEAR_CAVITY_RADIUS = 8` blocks, cached by eye block and `FarHeights.noLeavesChangeCount()`, zero warm allocation), which computes the highest flat `h_cav` over open-sky cavity columns (`h_cav > columnHeight`) within 8 blocks of the eye directly from `FarHeights.noLeavesHeights()`.
  - `WeatherFogRenderer.applyFogBound` passes cavity thinning into `FogCullingDistance` only while `nearCavityQuery.hasCavityAbove(...)` is true; on open ground, plateaus above ravine rims, and single cliff feet, `boundCavityDensity` stays `1.0` and `boundCavityRange` stays `0.0`.
- **Steeper blizzard flake paths (`SkySetting`, `SnowFlakes`, `SnowLee`, `SnowLeeMap`, `SnowfallRenderer`).**
  - Added `SkySetting.SNOW_BLIZZARD_FALL_SPEED` (default `1.8`, range `0.05` to `5.0`) and `SkySetting.SNOW_WIND_SHARE` (default `0.5`, range `0.0` to `1.0`).
  - Added `SnowFlakes.fallSpeed` (blending from `snowFallSpeed` at `stormIntensity = 0` to `snowBlizzardFallSpeed` at `1`) and `SnowFlakes.windSpeed` (scaling wind by `snowWindShare` for flakes and `SnowLeeMap` while fog keeps full wind). In a full blizzard this yields ~1-in-3.3 paths (`6.0` blocks/s horizontal, `1.8` blocks/s vertical, lee slope `c = 0.45`). `SnowLee.needsRebuild` now applies the 2% `SPEED_REBUILD_SHARE` threshold to `fallSpeed` as well as `windSpeed` so smooth storm onset does not restart builds on tiny deltas.
- **Ravine snow trapping (`WeatherFogRenderer`, `SnowLee`, `snow_lee.glsl`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl`, `SnowfallRenderer`).**
  - `WeatherFogRenderer` steps `FogCavityMap` whenever `frameCavityFloor < 1.0 || SkySetting.SNOW_ENABLED.isOn()`, so ravine snow trapping keeps working even if a user sets `fogCavityShare` to `1.0`.
  - `SnowfallRenderer` binds `FogCavityMap.texture()` on unit 9 (`TEXTURE_UNITS = 10`, saved and restored automatically) for both update and draw. On open-sky cavity columns (`h_cav > H`), flakes brake horizontally below `h_brake = mix(h_cav, lowerRim, clamp((h_cav - H) / 2.0, 0, 1))` using `openFactor = exp(-depth / 0.5)` and `windScale = mix(0.01, 1.0, openFactor)` for flake velocity, streak velocity, and local terrain-following / lee-sinking rates.
  - Stopped flakes move with `windVelocity * brakeWindScaleAt(flakeWorldY, centerBrakeHeight)` so flakes stopped on roofs and rims above `centerBrakeHeight` exit at full wind while flakes stopped deeper inside the cavity move at their own braked height. Six-face box re-entry integrates `|windStep * brakeWindScaleAt(y, centerBrakeHeight) - cameraStep|` over each side face (full wind above `centerBrakeHeight`, braked wind below it), samples side entry height in proportion to that flux, and keeps the top and bottom vertical fluxes; when `centerBrakeHeight` lies at or below the box bottom, re-entry reduces directly to the open-ground path. On open-sky columns (`worldY >= H - 1.0`), `snowLeeShare` blends toward `1.0` by `1.0 - openFactor` so flakes and floor splats stay visible across cavity floors while overhangs (`worldY < H - 1.0`), walls, houses, and single cliffs remain untouched.
- **Less structured fog in plain snow (`SkySetting`).**
  - Changed `SkySetting.FOG_SNOW_STRUCTURE_SHARE` default from `0.5` to `0.3`, the user's choice after the fog looked too structured in plain snow. The blizzard share stays `0.2`.
- **Tests (`FogCavityGameTests`, `SnowLeeGameTests`, `FogSettingsGameTests`).**
  - Updated `FogCavityGameTests` to check flat wall-to-wall equality across 10- and 20-block ravines, lower-rim clamping on lopsided ravines, 22.5- and 45-degree ravines, single cliff foot, 80-block valley, 1-in-2 V-valley (center and slopes), post pairs including a 1-wide post opposite a long wall, `FogStructure` wisp fading deep in a ravine with 0 bytes warm allocation, and `NearCavityQuery` culling bound parity on open ground + 0 bytes warm allocation.
  - Updated `SnowLeeGameTests` to add `collidingFlakesApproachingSteepHillFaceKeepEyeLevelSnow` (30 blocks down to 4 in front of a 45-degree hill face, plus walls, houses, and 20-block cliffs at `c = 0.45`), `flakesInCrossWindRavineReachFloorAndOutsideMovesAsBefore` (10- and 20-wide 25-deep cross-wind ravines at `camY = 42, 50, 58, 62`, a 10-wide 12-deep ravine with camera on its floor, an 8-wide street between 6-tall houses with camera at eye level, per-third floor-width assertions, dry overhang inside a cavity, and untouched plain/wall/house/single-cliff behavior including `reenterBoxOffset` parity), calm snow `snowFallSpeed` checks, and GLSL constant parity for the cavity constants in `snow_lee.glsl`.
  - Updated `FogSettingsGameTests` for `SNOW_BLIZZARD_FALL_SPEED`, `SNOW_WIND_SHARE`, `FOG_CAVITY_SHARE` default `0.0`, and `FOG_SNOW_STRUCTURE_SHARE` default `0.3`.

## Validation

- `agent-tools/build.sh` (`compileJava compileGameTestJava`): `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh`: all shader checks passed with 0 errors and 0 warnings (`snow_smooth_build.fsh`, `snow_lee_build.fsh`, `fog_cavity_build.fsh`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl` vertex + fragment, `weather_fog_share.fsh`, `weather_fog_inject.fsh`, `weather_fog_composite.fsh`).
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 260 required tests passed` (145 in `frostedheart_weather`, 115 in `frostedheart_calculated_sky`, 0 failures).
  - 45-degree hill face eye-level visible snow share (vs plain): `x=30: 0.891`, `x=20: 0.796`, `x=14: 0.745`, `x=10: 0.617`, `x=6: 0.646`, `x=4: 0.661`.
  - 10-wide 25-deep cross-wind ravine (`floor=40, rim=65`), visible floor hits `[upwind, middle, downwind]`, mean weight, air density ratio: `camY=42` -> 502 `[142, 170, 190]`, `1.000`, `0.978`; `camY=50` -> 512 `[154, 174, 184]`, `1.000`, `0.979`; `camY=58` -> 537 `[156, 184, 197]`, `1.000`, `1.045`; `camY=62` (floor sits 2 blocks below the 40-block box bottom, shaft crossings measured at `y=43`) -> 531 `[162, 185, 184]`, `1.000`, `1.066`.
  - 20-wide 25-deep cross-wind ravine (`floor=40, rim=65`): `camY=42` -> 1056 `[326, 355, 375]`, `1.000`, `0.995`; `camY=50` -> 1095 `[350, 356, 389]`, `1.000`, `0.919`; `camY=58` -> 1102 `[362, 379, 361]`, `1.000`, `1.174`; `camY=62` (shaft crossings at `y=43`) -> 1103 `[386, 377, 340]`, `1.000`, `1.187`.
  - 10-wide 12-deep cross-wind ravine (`floor=53, rim=65, camY=54.6`): 563 `[109, 263, 191]`, `1.000`, `1.159`.
  - 8-wide street between 6-tall houses (`floor=64, roof=70, camY=65.6`): 427 `[80, 209, 138]`, `1.000`, `1.172`.
  - Warm allocation checks: `Warm cavity fog walk allocated 0 bytes` (with `FogStructure` active) and `Warm NearCavityQuery allocated 0 bytes`.
- In game, the user found the ravine and the hill face working, with `fogWindScale` 8, `snowFallSpeed` 2.2 and `fogCavityShare` 0.5 still set from earlier tests. A flake's velocity changes too suddenly where it drops past a ravine's lip, since the braking starts at full strength right at the brake height.
- The `FOG_SNOW_STRUCTURE_SHARE` default change was not rerun through the GameTests.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- Smooth the braking profile at the lip, which trades against a strip with fewer flakes along the upwind wall.
- Check again in game at the defaults in a blizzard: deep ravine (fog entering from above with no wall wisps, clear floor, flakes bending vertically and splatting on the floor, no terrain clipped, `/calculatedSky fog selftest`), walk up to the steep hill face from 30 blocks to 4, `/calculatedSky fog bound` on open ground vs inside a ravine, and the wall, 20-block cliff, and village street.
- Measure GPU pass costs with `/calculatedSky fog`, `/calculatedSky fog passes`, and `/calculatedSky snow`.
