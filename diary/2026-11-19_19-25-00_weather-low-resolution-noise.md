# Weather Fog Walks at a Lower Resolution and Moves with the Wind

- Time: `2026-11-19 19:25:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (test writer, implementer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `0e9990e41`, not committed. It waits for the in-game check.
- Scope:
  - new `content/climate/render/fog/SnowFogNoise`, `WeatherWind`, `FogUpsample`, `shaders/weather_fog_upsample.fsh`
  - `SnowFogWalk`, `WeatherFogRenderer`, `WeatherConditions`, `ClientMockWeather`, `SkySetting`, `CalculatedSkyClientCommand`, `FHShaders`, `snow_walk.glsl`, `weather_fog.fsh`
  - new GameTest classes `SnowFogNoiseGameTests`, `WeatherWindGameTests`, `FogUpsampleGameTests`; `SnowFogWalkGameTests`, `FogModelGameTests` and `FogSettingsGameTests` changed
  - stages 2 and 3 of the weather rendering plan, done as one task

## Completed

**Lower resolution (stage 2).**
- The walk runs once per low-resolution pixel, by default at half width and half height (`fogResolutionDivisor`, 1, 2 or 4).
  - Each low pixel takes the minimum or the maximum depth of its block in a checkerboard, so thin geometry survives in one of the two.
  - It stores the walked optical depth over the open-air depth along the same ray, and the distance.
- A full-resolution composite computes its own open-air depth and multiplies it by the ratio, blended bilinearly over the low samples whose distance agrees with its own.
- Where no sample agrees, or the agreeing ratios differ by more than 0.2, the pixel walks at full resolution.
  - A `GL_SAMPLES_PASSED` query counts those pixels.
  - Above `fogFallbackCap` (0.25 of the screen) they take the nearest sample instead, until the share falls below 80% of the cap.
- The rules are in `FogUpsample`, a plain twin of the composite's.

**Noise and wind (stage 3).**
- A tiling 64^3 gradient noise texture, read twice at two scales (`fogNoiseScale` blocks per texel for the fine one, 4.3 times that for the coarse one). It multiplies the density by `1 + fogNoiseStrength * n`.
  - In the field region the noise is read once per cell.
  - Beyond it, a march of 24 steps of equal base optical depth covers up to 3.912 of it, and the rest takes the last step's noise.
- The wind moves the noise. `WeatherWind` accumulates the displacement per frame, pauses with the game, and limits a step to 0.25 s.
  - The speed is the climate's wind level times `fogWindScale` (12 blocks per second at level 100), smoothed over 8 s.
  - The direction turns slowly with `dayTime`.
- `/calculatedSky fog debug transmittance` and `fog debug fallback` are new, and `/calculatedSky fog` reports the divisor, the fallback share, the noise and the wind.

## Decisions

- Stages 2 and 3 ran as one task with one implementer session, because the user wants bigger tasks for the subagents.
- The noise only adds density, so the culling bound and the fitted vanilla fog keep using the base fog. At the default strength of 0.8, the mean fog is about 40% denser than in stage 1.
- `fogNoiseScale` starts at 1, so the finest noise features stay at least 8 blocks wide, as the per-cell reads need.

## Validation

- `agent-tools/build.sh` and `glsl-check.sh` on both fog shaders pass.
- GameTests `frostedheart_weather` and `frostedheart_calculated_sky` together: `All 214 required tests passed`. The new tests check the noise filter and wrap against hand values, the wind, every upsample rule, and the noisy walk against a fine-step reference. The warm paths allocate 0 bytes.
- My review: both shaders took the open-air depth at extinction 1, whose 64 clamp cut it at 64 blocks. Fixed to the eye's extinction.
- Reviews in `scratch/subordinate/weather/2a-review-*.md`.
  - Applied: the fallback candidates were found with the current cap flag, so the cap released itself every other frame; a sample without a scene point wrote a depth value as a distance; a label in `describe`.
  - Rejected: "`GL_RG16F` cannot be rendered to on macOS", because RG16F is a required color-renderable format in OpenGL 3.0 core and later.
- Not run in game yet.

## Documentation

None changed. The weather docs are written in stage 11.

## Remaining

- The in-game check:
  - `fog selftest`
  - `fogResolutionDivisor` 1, 2 and 4 against each other
  - `fog debug fallback` in a spruce forest
  - the noise moving downwind while standing still
  - the GPU time
  - smoke against a hillside
- The noise is built on the render thread on the first frame the fog draws, a one-time hitch.
