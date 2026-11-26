# Wind-Aligned Snowfall Lee Map and Fixed-Origin Splats

- Time: `2026-11-26 16:06:00 +0200`
- Author: `Gemini 4 Neon, supervised by Anthropic Claude Opus 5.5 and empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `2026-11-24_13-17-40_weather-snowfall-collision.md`, not committed. Third piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - New: `SnowLee`, `SnowLeeMap`, `snow_lee_build.fsh`, `snow_lee.glsl`, `SnowLeeGameTests`
  - Changed: `FarHeights`, `SnowFieldTextures`, `ClientSnowField`, `SnowfallRenderer`, `SnowFlakes`, `SnowShapes`, `SnowShapeTextures`, `SkySetting`, `FHShaders`, `snow_flakes.glsl`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl`, `FarHeightsGameTests`, `FogSettingsGameTests`, `SnowCollisionGameTests`

## Completed

**Why.** In piece 2, the lee behind a wall showed only while the wall stayed inside the 40-block flake box; once the camera backed away and the wall left the box upwind, flakes entered the box's upwind face inside the lee and filled it. Piece 3 adds a wind-aligned lee map built from far terrain heights without leaves so near flakes (and later far snow) share one consistent lee surface regardless of where the eye stands, with flakes curling down along the lee surface and fading out smoothly beneath it. Along with piece 3, this change fixes splats wrapping ahead of a fast-moving or teleporting camera, adds a shader-constant parity test and the snow settings coverage to `FogSettingsGameTests`, and clarifies four `leavesBox` assertion messages in `SnowCollisionGameTests`.

**What changed.**
- `FarHeights` now maintains a second 1024 by 1024 column-height table (`noLeavesHeights`) alongside the existing `MOTION_BLOCKING` table so the fog's leaf-aware height rule is unchanged. When reading a tile or hearing a block change, `FarHeights` steps down from the motion-blocking top past `LeavesBlock` and `BlockTags.LEAVES`, skipping `hasOnlyAir()` sections with zero per-column allocations (one block check when the top is not leaves). It tracks dirty tiles and a `noLeavesChangeCount` counter, and `ClientSnowField` forgets `FarHeights` on tag updates so changes to `BlockTags.LEAVES` rebuild the heights. `SnowFieldTextures` uploads `noLeavesHeights` to a separate 1024 by 1024 `GL_R16I` texture (`noLeavesHeightTexture`), reusing its staging buffer.
- `SnowLee` holds the shared lee constants (`MAP_SIZE = 1024`, `REACH = 480`, `TOTAL_PASSES = 10`, scan strides `{1, 2, 4, 8, 16, 32, 64, 128, 224}`, rebuild thresholds) and the plain Java twin of the wind-aligned scan, brute-force reference scan, bilinear sampler, lee fade share/weight, sinking fall speed, and rebuild checks.
- `SnowLeeMap` and `snow_lee_build.fsh` build the lee map on the GPU using `#version 150`. Three 1024 by 1024 `GL_R32F` targets (one published plus two working ping-pong targets, 12 MB total) allow one pass per frame over 10 frames while flakes keep reading the published map untouched, then swap the completed map into the published slot with no copy or flicker.
  - Pass 0 resamples `noLeavesHeights` into a wind-aligned frame centered on an integer anchor, shifted 1 texel upwind (`t = 1`, excluding `t = 0` by construction) with two along-wind taps (`+-0.25`) and explicit bounds checks against the `FarHeights` window so samples outside the window read as no-height (`-32768`) and never wrap.
  - Passes 1 through 9 run exact integer doubling (`h_next(i) = max(h(i), h(i - stride) - slope * stride)`) with strides `1, 2, 4, 8, 16, 32, 64, 128, 224`, covering `1 <= t <= 480` without wrapping. Pass 9 fuses a 3-texel min across the wind (`y - 1, y, y + 1`). Taking the 3-texel min after the scan with along-wind-only resampling ensures obstacles narrower than 3 blocks across the wind (1-block towers and 2-wide pillars) cast no lee at any wind angle while 1-block-thick walls 3 or more blocks wide keep their lee even at 45-degree wind.
  - A rebuild starts when wind direction turns by at least 0.25 degrees, wind speed changes by at least 2%, `FarHeights` moves or `noLeavesChangeCount` changes, `snowLeeFill` changes the slope, or the camera drifts 256 blocks from the build anchor. In calm wind (`speed < 0.01`) the lee map stays inactive. `SnowLeeMap` times each pass with `SkyDrawTimer` and reports state, slope, rebuild/pass counts, and pass GPU/CPU time through `/calculatedSky snow`.
- `snow_lee.glsl` is prepended after `snow_flakes.glsl` to both `snow_flake_update.fsh` and `snow_flakes_draw.glsl` via `FHShaders`. It samples the lee height bilinearly (`textureLod` at level 0) and the column's no-leaves height `H`. Over flat ground or gentle slopes (`h <= H`), `snowLeeShare` returns 1.0 so flat terrain keeps full snowfall; inside a lee (`h > H`), it ramps smoothly from 0 at `h` to 1 over `min(snowLeeBand, h - H)` above `h`, weighted per flake with hash salt `SNOW_SALT_LEE = 0u` (the one unused salt in `0..15`, keeping all existing flake hashes unchanged).
- In `snow_flake_update.fsh`, active (non-stopped) flakes over a lee column (`h > H`) sink with fall speed raised to at least `slope * windSpeed`, blending back to their own fall speed over `h - H` above `h`. This runs outside the collision branch so lee sinking and fading still work with `snowCollision` off, while stopped flakes keep their base velocity so box-top entry density stays uniform. `snow_flakes_draw.glsl` uses the same lee-adjusted fall speed for streak direction/length, multiplies flake weight by `snowLeeWeightAt`, and multiplies splat weight by `snowLeeWeight` at the hit point. `SnowfallRenderer` now saves and restores 8 texture units (`LEE_MAP_UNIT = 6`, `NO_LEAVES_HEIGHT_UNIT = 7`).
- Fixed-origin splats and level-change refill: `SnowfallRenderer` tracks the current `ClientLevel` and a fixed integer `splatOrigin` updated on every fill (and refilled if the level changes or the camera drifts more than 4096 blocks from the splat origin). `snow_flake_update.fsh` stores hit points as `cameraFromSplatOrigin + hitPoint` without periodic wrapping, and `snow_flakes_draw.glsl` hides any splat whose offset from the camera exceeds half the box side on any axis (`SnowFlakes.splatVisible` is the Java twin).
- Settings added to `SkySetting` after `snowSplatSeconds` and before `fogEnabled`: `snowLee` (switch, default `true`), `snowLeeFill` (default `0.15`, range `0.0` to `2.0`), and `snowLeeBand` (default `3.0`, range `0.0` to `16.0`). `SnowShapes.SHAPES_PER_ROW` was moved to the common `SnowShapes` class so tests can verify the shader constant without loading client-only `SnowShapeTextures`.
- Tests:
  - `SnowLeeGameTests` adds 4 tests in `frostedheart_weather`: scan twin vs brute-force reference (wall, 1-block tower at 0/30/45 degrees, 2-wide pillar, 3-wide wall, ridge, gentle slope, 45-degree diagonal wind over a 1-block-thick long wall, calm wind); production 1024 by 1024 scan with reach 480 and non-wrapping outside the `FarHeights` window; lee share, lee weight over 100000 flakes, sinking fall speed, and rebuild triggers including `pi/-pi` angle wrap; and parsing all 5 snow shader files from the classpath to check every shared GLSL constant against Java (plus uniqueness of all 16 `SNOW_SALT_*` salts).
  - `FarHeightsGameTests` adds `noLeavesHeightsIgnoreLeavesAndTrackChangesUnderCanopies` and reads `noLeavesHeightAt` inside `warmOperationsAllocateNothing`.
  - `FogSettingsGameTests` now checks all 13 `SNOW_*` settings (keys, defaults, ranges, switches, rebuilds, and order before `FOG_*`).
  - `SnowCollisionGameTests` rewords the four `leavesBox` assertion messages at lines 266-270 into explicit actual-vs-expected form and checks `SnowFlakes.splatVisible`.

## Validation

- `agent-tools/build.sh` (`compileJava compileGameTestJava`): `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh`: all 4 shader checks pass with 0 errors and 0 warnings (`snow_lee_build.fsh` fragment; `snow_flake_update.fsh` fragment with `snow_flakes.glsl` and `snow_lee.glsl`; `snow_flakes_draw.glsl` vertex and fragment with `weather_fog.glsl`, `weather_fog_excess.glsl`, `snow_flakes.glsl`, and `snow_lee.glsl`).
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 255 required tests passed` (140 in `frostedheart_weather`, 115 in `frostedheart_calculated_sky`, 0 failures). `FarHeightsGameTests.warmOperationsAllocateNothing` logged `0 bytes` allocated.
- Not checked in game yet. Not measured yet: the GPU and CPU time of each lee build pass, the rebuild cadence, and the update/draw GPU delta with `snowLee` on vs off.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- Check the lee map visually in game (close and far behind a wall, 1-block tower, 20-block cliff from below, stepping across a lee edge, forest edge, 1-block-thick diagonal wall, and 15 s of changing wind) and tune `snowLeeFill` and `snowLeeBand` if needed.
- Measure the lee pass cost and flake update/draw delta via `/calculatedSky snow` with `snowLee` on and off.
- Heightmap-based lee sees no overhangs (air beneath a wind-facing arch counts as lee), and gusts do not shift the lee surface.
- Far snow will read this lee map when implemented; whether the fog's extinction field should also read it remains an open decision.
