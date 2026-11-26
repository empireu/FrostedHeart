# Snowflake Drift Cap

- Time: `2026-11-26 23:20:00 +0200`
- Author: `Anthropic Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `f241f1c0d`, not committed. Follows the third revision of the third piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Changed: `SnowFlakes`, `SnowfallRenderer`, `SkySetting`, `SnowFlakesGameTests`, `FogSettingsGameTests`

## Completed

**Why.** In the first in-game check of the ravine braking, plain snow still fell almost horizontally. Its flakes fall at 0.6 blocks per second, while `snowWindShare` 0.5 of a wind of 4 to 6 blocks per second carries them 2 to 3 blocks per second sideways, so they drift 3 to 5 blocks for each block they fall.

**What changed.**
- Added `SnowFlakes.driftSpeed(windSpeed, fallSpeed, maxDrift)`, which gives `M * tanh(w / M)` with `M = maxDrift * fallSpeed`. It follows the flake wind `w` in light wind and approaches `M` in strong wind.
- Added `SkySetting.SNOW_MAX_DRIFT` (`snowMaxDrift`, default `1.5`, range `0.0` to `10.0`), the most blocks a flake drifts sideways for each block it falls. 0 makes the flakes fall straight.
- `SnowfallRenderer` caps the flake wind once, where it computes it from the fog's wind and `snowWindShare`, with the median fall speed of the current storm. So the lee map, the update, the draw, the box re-entry, the braking, the sinking and the downdraft all use the capped wind. The fog keeps the full wind.

**Effect at the defaults.**

| Weather | Fog wind | Flake wind before | Drift now | Blocks sideways per block of fall | Path below horizontal |
|---|---|---|---|---|---|
| Plain snow, fall 0.6 | 5 | 2.5 | 0.89 | 4.2 before, 1.49 now | 13.5 degrees before, 33.9 now |
| Full blizzard, fall 1.8 | 12 | 6.0 | 2.64 | 3.3 before, 1.47 now | 16.7 degrees before, 34.3 now |

- A plain-snow breeze with a flake wind of 0.3 keeps 96% of it.
- The lee slope `fall / wind + snowLeeFill` rises from 0.39 to 0.82 in plain snow and from 0.45 to 0.83 in a blizzard. A 10-block wall shelters about 12 blocks behind it instead of 22 to 26.
- The downdraft scales with the capped wind. Fully braked flakes now fall at about 1.05 blocks per second in plain snow instead of 1.85, and about 3.1 in a blizzard instead of 4.8. The flakes outside the ravine move slower too, so the contrast that made the ravine flakes look slow is smaller.

**Decisions.**
- One cap for snow and blizzards, as a share of the fall speed, so a blizzard still drifts three times as fast as plain snow because its flakes fall three times as fast. A separate blizzard cap that blends with the storm, like the fall speed, can come if blizzards should stay flatter.
- The cap uses the median fall speed. A flake's own fall speed varies by a third either way, so the slowest flakes drift up to 2.25 blocks per block of fall and the fastest 1.1.
- `tanh` keeps light wind almost unchanged and has no corner where the wind grows past the knee.
- The existing lee and ravine GameTests keep their flake wind of 6 in a blizzard. They test the braking and the lee at that wind, which now needs a `snowMaxDrift` well above 3.3, so they are a harder case than the defaults.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- No shader changed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 262 required tests passed`.
- New GameTest `driftCapKeepsFlakesSteepAndLeavesLightWind`: at fall speeds 0.6, 1.2 and 1.8, the drift grows with the wind, stays below the cap and below the wind, keeps at least 99% of a wind of a tenth of the cap, and reaches at least 99% of the cap at three times the cap. Plain snow at a wind of 5 drifts at 0.89 and a blizzard at a wind of 12 at 2.64. An infinite or huge cap leaves the wind, and a cap of 0, calm air and NaN give 0.
- `FogSettingsGameTests` checks the key, default and range of `snowMaxDrift` and its place after `snowWindShare`.
- Not checked in game yet.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- Check in game: plain snow and a blizzard in the open, the ravine and a village street, and confirm the defaults of `snowMaxDrift`, `snowCavityBrakeLength` and `snowCavityDowndraft`.
- The ravine and street cases were not simulated at the capped wind, because their lee maps are built for a flake wind of 6.
