# Ravine Braking Ceiling at the Lip, Sinking Braked Flakes, Swirl and Downdraft

- Time: `2026-11-26 22:00:00 +0200`
- Author: `Anthropic Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `19d6e1802`, on top of the uncommitted lip braking (diary `2026-11-26_21-10-00_weather-snowfall-lip-braking.md`), not committed. Third revision of the third piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Changed: `fog_cavity_build.fsh`, `FogCavity`, `FogCavityMap`, `SnowLee`, `snow_lee.glsl`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl`, `SnowfallRenderer`, `SkySetting`, `SnowLeeGameTests`, `FogCavityGameTests`, `FogSettingsGameTests`

## Completed

**Why.** In game, a column of braked flakes stood in open air above a ravine and showed flakes that the lee hid in the columns next to it. Flakes crowded into dense bands near stepped walls, and inside the ravine they looked slow. Three causes:
- The cavity map's G channel held the lower of the two highest raw heights within 32 blocks, not the ravine's lip. Next to higher ground the braking ceiling rose above the lip, and the lee release followed it.
- Braking changed only the horizontal speed. Wherever the ceiling changed from column to column along the wind, fast flakes ran into braked ones and piled up, by up to the speed ratio of 100.
- Braked flakes fell straight at their own fall speed next to flakes outside that streak sideways.

**What changed.**
- The cavity scan's second work target (RGBA16F) holds the distance in steps of each direction's sloped maximum instead of the raw maximum. On a tie, the nearer wall wins. The 3-texel perpendicular minimum picks the lower wall, and on a tie the nearer one. A wall's lip is its sloped maximum plus the slope times its distance, which is the height of the column that defines the wall. An axis's surface is `min(lowerLip, 0.5 * (h_pos + h_neg))`, and the published G channel is `lowerLip` of the winning axis. `FogCavity.scan`, `bruteForceTexelPair` and `NearCavityQuery` mirror this. `FogCavityMap` renames the target and its uniforms (`outDistance`, `sourceDistanceMap`).
- `SnowLee.cavityBrakeHeight` is the lip over an open-sky cavity column (`h_cav > H`), with no blend toward `h_cav`. `CAVITY_RIM_BLEND_DEPTH` is gone.
- A braked flake sinks where the flow ceiling 2 blocks upwind (`CAVITY_SLOPE_DISTANCE`) is lower than its braking ceiling: `w += -U * clamp((C - C_upwind) / 2, 0, 1) * (1 - s)`, where `U` is the flake wind before braking and `s` the braking wind multiplier (`SnowLee.cavitySinkVelocity`, `CAVITY_SLOPE_LIMIT`). The flow ceiling is the braking ceiling over a cavity column and the terrain elsewhere (`SnowLee.cavityFlowCeiling`). This follows from the flake flux: with `u = U * s(C(x) - y)`, continuity gives `w = w_top - U * C'(x) * (1 - s)`.
- Added `SkySetting.SNOW_CAVITY_SWIRL` (`snowCavitySwirl`, default `0.25`, range `0.0` to `4.0`). The flutter circle's radius is `(snowFlutter + snowCavitySwirl * (1 - open)) * radiusShare` (`SnowLee.cavityCircleRadius`), so fully braked flakes trace circles of 0.4 blocks mean radius. The planned default of 1.5 swirled too much in the first in-game check.
- Added `SkySetting.SNOW_CAVITY_DOWNDRAFT` (`snowCavityDowndraft`, default `0.5`, range `0.0` to `1.0`). A share of the wind that the braking removes pulls braked flakes down: `w += -U * snowCavityDowndraft * (1 - s)` (`SnowLee.cavityDowndraftVelocity`). It applies to moving flakes, streaks, and the top and bottom faces of the box re-entry. In a full blizzard at the default, braked flakes fall at about 4.8 blocks per second instead of 1.8. In the first in-game check the flakes in the ravine still fell too slowly after the swirl was turned down to 0.25.
- The update and the draw read the wind, braking, swirl, sinking and lee at the center of the flutter circle instead of the drawn point. The fill and box re-entry place flakes with the swirl radius at their new center, and the collision substeps use the radius at the start of the frame.
- The draw hides a flake whose widest possible circle lies behind the near plane before it reads any map.

**Decisions.**
- Flakes only sink and never rise. Where the upwind column is solid terrain above the ceiling, no flakes come from there, so a rise term would push braked flakes up to the ceiling. Where the ceiling really drops along the wind, a rise only moves the empty space from the band above the new ceiling down to the floor.
- The slope is clamped to 1 block per block, as the terrain-following slope is. A jump of the ceiling still sinks the flakes below the ramp to the floor within the 2 blocks, because they move at 1% of the wind there.
- `h_cav` changes only where one wall is much higher and nearer than the other, so the mean of the sloped heights lies above the lower wall. It then clamps to that wall's lip instead of to the highest ground within 32 blocks on that side, so it can only get lower.
- The swirl uses the flakes' own flutter rate, so its edge speed is about 1.2 blocks per second at the default.
- Faster flakes with the same flux are fewer in the air at once, by the ratio of the fall speeds, so the downdraft thins the air below the ceiling to about 0.4 of the box mean in a blizzard. Keeping more of the sideways wind instead would carry the snow onto the downwind wall. The downdraft only grows with depth below the ceiling, so it can spread flakes but never crowd them.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh` on `fog_cavity_build.fsh`, on `snow_flake_update.fsh` with `snow_flakes.glsl` and `snow_lee.glsl` prepended, and on `snow_flakes_draw.glsl` for both stages with the fog, flake and lee includes prepended: all passed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 261 required tests passed`.
- New GameTest `ravineCeilingStaysAtTheLipAndSteppedRimsKeepFlakesSpread`:
  - A 10-wide ravine with ground 5 blocks higher 12 blocks behind each lip brakes below 65 (its lip) in every air column, and its terraces do not brake. `FogCavityGameTests` checks the same ravine against the brute force, with its lip at 65 and its surface unchanged.
  - A cross-wind ravine with rims of 61 to 66 blocks that vary along z, a 58 step upwind and 55 and 60 steps downwind. Live flake density per column from x = -9 to 9, from one block above the ground up to y = 64, as a share of the box mean, measured without the downdraft:

| Sinking | Columns x = -9 to 9 | Max / mean | Min / mean |
|---|---|---|---|
| On | `0.19 0.26 0.24 0.35 0.22 0.16 0.37 0.54 0.65 0.94 0.94 0.96 0.93 0.81 0.68 0.90 0.12 0.31 0.27` | 1.85 | 0.23 |
| Off | `0.19 0.25 0.26 0.34 0.63 0.54 0.57 0.69 0.68 0.96 0.93 0.95 0.90 0.81 0.67 3.18 0.12 0.29 0.27` | 4.57 | 0.18 |

  - Without the sinking, flakes pile up over the low downwind step at x = 6 (3.18). With it, the first 3 columns of the ravine floor hold 0.16 to 0.37, because the flakes that come in over the upwind step drop faster. The columns over the steps sit in the lee of the rims, so the lee hides them.
  - With the default downdraft as well, the ravine columns hold 0.18 to 0.48. The column over the low downwind step keeps 0.79, because part of it lies outside the cavity.
- Without the downdraft, the four cases of `flakesInCrossWindRavineReachFloorAndOutsideMovesAsBefore` (10-wide and 20-wide 25-deep ravines, a 12-deep ravine, a village street) give the same floor hits, strips and density ratios as before, because their ceilings are flat. Its lower bound on the air density ratio is scaled by the downdraft's thinning, `0.35 * fall / (fall + downdraft speed)`. Downdraft sweep, visible floor hits `[upwind, middle, downwind]` thirds and air density ratio:

| Case | Share 0 | Share 0.3 | Share 0.5 (default) |
|---|---|---|---|
| 10-wide 25-deep ravine, `camY=58` | `[154, 183, 190]`, 0.896 | `[115, 256, 236]`, 0.615 | `[123, 266, 242]`, 0.460 |
| 8-wide street between 6-tall houses, `camY=65.6` | `[0, 208, 154]`, 0.918 | `[31, 222, 155]`, 0.547 | `[54, 226, 147]`, 0.449 |

  - The floor catches more snow with the downdraft, because fewer flakes reach the downwind wall, and the upwind third of the street gets some again.
- Not checked in game yet.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- Check in game: the ravine from the screenshots, from its rim and from its floor, and a village street.
- Confirm the defaults of `snowCavityBrakeLength` and `snowCavityDowndraft` in game.
- A swirling flake whose circle center crosses from a cavity column into a column without braking, at the end of a street or where a sloped floor rises to `h_cav`, loses its swirl radius within one frame, so its drawn position jumps by up to about 0.4 blocks at the default.
- At the entrance of a cavity whose ceiling jumps, the sinking flakes stream down to the floor at up to the wind speed.
