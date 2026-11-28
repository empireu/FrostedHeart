# Weather Lamp Glow March Optimization

- Time: `2026-11-28 17:25:00 +0200`
- Author: `Gemini 4 Neon, supervised by empireu (outside contributor)`
- Status: `done` (verified in game)
- Scope:
  - Changed: `weather_fog_lamp.fsh`, `LampLightCascade`, `LampLightField`, `LampFogMarch`, `LampFogPass`, `WeatherFogRenderer`, `LampLightGameTests`

## Change

- **Baseline measurement before the fix** (RTX 4070 Laptop GPU, 1080p fullscreen, blizzard over river ice):
  - `lamp glow`: `0.047 ms` with no lamps in view and `0.190` to `0.208 ms` with 3 torches.
  - Budget: lamp lighting gets at most `0.25 ms` of the `1.0 ms` total weather GPU budget, including a lit village street at night.
- **Scaling bottlenecks identified**:
  - `LampFogPass.draw` ran the full low-resolution pass whenever any cascade up to 512 blocks away held a lit cell, even when all lit cells lay behind or outside the camera frustum.
  - Each cascade exposed only a single AABB (`lampLitLow`/`lampLitHigh`), so lamps spread along a street or at different heights inflated the box over vast stretches of dark air and sky, forcing rays to march empty 3D cells.
  - 3D DDA split every cell boundary on all three axes (1.5 to 1.73x more steps than the major axis, plus narrow corner slivers).
  - Every lit step called `froxelExcessAtDistance` (two `log` calls and two `textureLod(excesses)` fetches) and up to three `exp(-depth)` calls.
- **Per-cascade 8x8x8 coarse slot occupancy mask (`LampLightCascade`, `LampLightField`, `LampFogPass`, `weather_fog_lamp.fsh`, `LampFogMarch`)**:
  - Tracked per-coarse-block lit counts and incrementally maintained a 24-int (6 `uvec4`) bitmask per cascade in `LampLightCascade`, uploaded as `uniform uvec4 lampCoarseBits[30]` via `GL30.glUniform4uiv` with zero per-frame allocations.
  - Before sampling any 3D cascade texture, the march tests the 1 to 8 coarse slot blocks touched by the trilinear footprint via branchless 64-bit XZ layer bitmasks. In unblended cascade interiors (`part == 0`), dark interior slabs strictly inside a single dark 8x8x8 coarse block jump across all remaining dark slabs in that block on the same world-aligned slab grid.
- **CPU frustum culling and inner-reach pruning (`LampFogMarch.clipLitBoxesToFrustum`, `LampFogPass`, `WeatherFogRenderer`)**:
  - Passed `WeatherFogRenderer.viewProjection` to `LampFogPass.draw` and tested each cascade's lit coarse blocks against the five eye-centered frustum planes (front of eye, left, right, bottom, top; no far plane because sky rays march to `SKY_DISTANCE`) while excluding blocks strictly inside the finer cascade's unblended interior (`BAND_START`).
  - Cascades with no visible lit coarse block are cleared to empty boxes, remaining cascades are tightened to the union of their visible lit coarse blocks, and the entire GPU pass is skipped when no cascade remains visible.
- **World-aligned major-axis slabs and cached optical-depth evaluation (`weather_fog_lamp.fsh`, `LampFogMarch`, `LampFogPass`)**:
  - Replaced 3-axis DDA with single major-axis world-aligned cell slabs (`LAMP_STEP_CELLS = 2.0` cells along the major axis), snapped in slot space (`lampSlotOrigin + lampEyeInCascade`) so slab planes stay fixed to even world cell boundaries across both eye movement and cascade window recentering (no swimming).
  - Carried `ownSpan` across cascade iterations and skipped empty cascades before computing major-axis rates and reciprocals.
  - Hoisted `float(sliceCount) / log(rangeDistance / FOG_FROXEL_NEAR)` once per ray, cached the last fetched 4-slice `excesses` layer texel locally inside `lampFogMarched`, reused `inWindow` from `lampSampleMayBeLit` for `lampCascadeShares`, and tracked `runningTransmittance` so each lit slab evaluates at most one `exp(-exitDepth)` instead of three.
  - Replaced the two per-frame single-value `GL11.glGetInteger(int)` calls in `LampFogPass.draw` with a pre-allocated 1-int `IntBuffer` via `glGetIntegerv` and used `GL20.glUseProgram` on warm frames.
- **Intermediate in-game measurement at 1-cell major-axis slabs** (RTX 4070 Laptop GPU, 1080p fullscreen, fog `integrate` `0.027` to `0.028 ms`):
  - `/calculatedSky lamps selftest`: `64 of 64 rays match the twin`, `37` lit, at most `71` pieces per ray.
  - 3 torches in blizzard: `lamp glow` `0.182 ms` mean (`0.193 ms` max).
  - Looking away from torches (standing inside their 14-block light radius): `lamp glow` `0.068 ms` mean.
  - Lit village street at night in blizzard: `lamp glow` `0.246 ms` mean (`0.261 ms` max), total fog `0.596 ms`.
  - Widening slabs to `LAMP_STEP_CELLS = 2.0` and trimming live loop registers (`cachedLowBoundary`/`cachedBelow`/`cachedAbove`, duplicate `vec3` uniform copies, and branched jump math) cut steps in half across every cascade and reduced register pressure.

## Validation

- `agent-tools/glsl-check.sh weather_fog_lamp.fsh --define LAMP_LIGHT_FIELD --prepend weather_fog.glsl --prepend weather_fog_excess.glsl --prepend lamp_light.glsl`: `All 1 checks passed.`
- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL` (`scratch/logs/build-2026-11-28_17-04-49.log`).
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 280 required tests passed :)` (`scratch/logs/gametest-frostedheart_weather+frostedheart_calculated_sky-2026-11-28_17-06-22-server.log`), including new tests `coarseOccupancyMatchesLitCells`, `litVillageStreetCoarseMarchMatchesUnclipped`, and `frustumCullingClipsOnlyOffScreenLitBoxes`.
- Final in-game measurements (RTX 4070 Laptop GPU, 1080p fullscreen, blizzard over river ice, fog `integrate` `0.027 ms`):
  - `/calculatedSky lamps selftest`: `64 of 64 rays match the twin within 3% of their brightest channel plus 0.0020`, `27` rays lit, at most `43` pieces per ray of the twin.
  - 3 torches (`/calculatedSky fog passes`, 120 frames): `lamp glow` GPU mean `0.112 ms`, median `0.112 ms`, max `0.144 ms`, CPU `0.035 ms` (down from `0.190` to `0.208 ms`); total fog GPU mean `0.459 ms`, max `0.493 ms`.
  - Snowfall at the same spot (`/calculatedSky snow`, 131072 flakes at cap): `update` `0.091 ms`, `behind` `0.056 ms`, `draw` `0.083 ms` (`0.230 ms` total, `0 bytes allocated in the last frame`); combined weather GPU total `0.689 ms` (down from `~1.27 ms`, budget `1.0 ms`).
  - Lit village street at night with many lights (`/calculatedSky fog passes`, 120 frames): `lamp glow` GPU mean `0.132 ms`, median `0.132 ms`, max `0.138 ms`, CPU `0.040 ms` (well under the `0.25 ms` lamp budget, only `+0.020 ms` above 3 torches); total fog GPU mean `0.485 ms`, max `0.498 ms` (`0.715 ms` combined with snowfall).

## Documentation

No living doc describes the lamp glow march internals yet. `docs/climate/calculated-sky.md` needs no change.
