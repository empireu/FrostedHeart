# Smooth Snowflake Braking at a Ravine's Lip

- Time: `2026-11-26 21:10:00 +0200`
- Author: `Gemini 4 Neon and Anthropic Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `19d6e1802`, not committed. Follow-up to the second revision of the third piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository), diary `2026-11-26_19-50-00_weather-snowfall-lee-revision-2.md`.
- Scope:
  - Changed: `SkySetting`, `SnowLee`, `snow_lee.glsl`, `snow_flake_update.fsh`, `SnowfallRenderer`, `SnowLeeGameTests`, `FogSettingsGameTests`

## Completed

**Why.** In game, a flake's velocity changed too suddenly where it dropped past the lip of a ravine. The horizontal wind braked along `exp(-depth / 0.5)` from the brake height down, so the braking started at its steepest right at the lip.

**What changed.**
- Added `SkySetting.SNOW_CAVITY_BRAKE_LENGTH` (`snowCavityBrakeLength`, default `2.0`, range `0.0` to `16.0`). Below the brake height, the horizontal flake wind eases from full wind down to `CAVITY_WIND_FLOOR` (`0.01`) along `1 - smoothstep(0, snowCavityBrakeLength, depth)`, which starts with zero slope at the brake height. 0 brakes right at the brake height.
- The profile replaces `CAVITY_WIND_DECAY` everywhere it was used: flake motion, streaks, stopped flakes, box re-entry and the lee release (`SnowLee.brakeOpenFactorAt`, `brakeWindScaleAt`, `cavityOpenFactor`, `reenterBoxOffset`, and `snowBrakeOpenFactorAt`, `snowBrakeWindScaleAt`, `snowSideRampIntegral`, `snowSideSignSplit` in the shaders). The side-face re-entry flux stays exact: a constant part below the ramp, a quartic over the ramp and full wind above it, with the sign split from the closed-form inverse smoothstep `0.5 - sin(asin(1 - 2s) / 3)`.
- `SnowfallRenderer` sets the `cavityBrakeLength` uniform in the update and draw programs.
- `SnowLeeGameTests` measures the width of the upwind strip with fewer flakes (below 10% and below 50% of the steady floor density) and sweeps the brake length over 1, 2 and 3 blocks for each cavity case. The upwind-third assertions are loosened for the default. `FogSettingsGameTests` checks the new setting.

**Decisions.**
- A longer brake length makes flakes drift further at full wind before they fall straight, which empties a strip along the upwind wall. Starting the braking above the rim instead would make the wind jump where a flake crosses from the wall top into the opening.
- Removed Neon's assertion that the strip widens with the brake length. In the 25-deep ravines the strip stays at the measurement's resolution (about 0.5 blocks) at every length, so the assertion failed without a fault in the model.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh` on `snow_flake_update.fsh` and `snow_flakes_draw.glsl` with `snow_flakes.glsl` and `snow_lee.glsl` prepended: all 3 checks passed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 260 required tests passed`.
- Brake length sweep, visible floor hits `[upwind, middle, downwind]` thirds, width of the strip below half density, air density ratio:

| Case | L = 1 | L = 2 | L = 3 |
|---|---|---|---|
| 10-wide 25-deep ravine, `camY=58` | `[158, 183, 197]`, 0.49, 1.064 | `[154, 183, 190]`, 0.48, 0.896 | `[144, 184, 188]`, 0.48, 0.760 |
| 20-wide 25-deep ravine, `camY=58` | `[363, 379, 362]`, 0.54, 1.172 | `[364, 374, 366]`, 0.54, 1.134 | `[358, 376, 363]`, 0.54, 1.083 |
| 10-wide 12-deep ravine, `camY=54.6` | `[128, 259, 183]`, 1.52, 1.167 | `[35, 260, 225]`, 2.84, 1.005 | `[8, 190, 247]`, 4.33, 0.850 |
| 8-wide street between 6-tall houses, `camY=65.6` | `[96, 203, 133]`, 1.56, 1.147 | `[0, 208, 154]`, 2.72, 0.918 | `[0, 75, 201]`, 4.38, 0.742 |

- Not checked in game yet.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- Choose the default `snowCavityBrakeLength` in game by watching flakes at a ravine's lip and in a village street. At 2 the upwind third of an 8-wide street gets no falling snow, and at 1 it keeps about half of the middle third's.
