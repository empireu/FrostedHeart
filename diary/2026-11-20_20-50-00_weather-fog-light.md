# Weather Fog Takes Its Color from the Lightmap and Covers the Sky

- Time: `2026-11-20 20:50:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (test writer, implementer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `4faaceae7`, not committed. It waits for the in-game check.
- Scope:
  - new `content/climate/render/fog/FogLight` and `mixin/client/LightTextureAccessor_WeatherFog`
  - `HeightFog`, `SnowFogWalk`, `FogUpsample`, `WeatherFogRenderer`, `weather_fog.glsl`, `snow_walk.glsl`, `weather_fog.fsh`, `weather_fog_upsample.fsh`, `frostedheart.mixins.json`
  - `CalculatedSkyRenderer.horizonColors` removed, since nothing calls it any more
  - GameTests in `FogModelGameTests`, `FogUpsampleGameTests` and `SnowFogWalkGameTests`
  - in-game tuning of the structured fog (`2026-11-20_18-55-00_weather-structured-fog.md`): new defaults in `SkySetting` and a fix of the storm intensity

## Completed

**Tuning and a blizzard fix.**
- `fogStructureSize` now goes down to 16. The defaults are the user's tuned values: `fogSnowStructureShare` 0.5, `fogStructureCoverage` 0.25, `fogStructureSize` 16, `fogStructureStretch` 1.5.
- `Level.getThunderLevel` multiplies the thunder level by the rain level, so a full blizzard (both 0.8 from `PlayerWeatherCompatibilityModel`) read as a storm of 0.8. `WeatherFogRenderer.updateWeather` now divides the rain level back out.

**Why the light changed.** In a blizzard a distant snow mountain glowed brighter than the fog in front of it and the sky behind it. The snow fog took the fixed config color `fogColorDay` (about 0.75), which nothing tied to the light. The sky only went 80% of the way to it and got no snow fog, so distant terrain converged to a brighter color than the sky.

**The model.**
- Under an overcast the fog and every surface are lit by the same diffuse light. A ray of transmittance T shows `T * behind + (1 - T) * J`.
- With one J for every ray, nothing behind the fog comes out brighter than the brighter of itself and J, and every ray converges to J with depth.

**J.**
- `FogLight.whiteFaceColor` takes the mean of the lightmap pixels at block light 0 and sky light 14 and 15. `light.glsl` samples at 240/256, halfway between those rows, with linear filtering, so this is exactly what a white top face at full sky light shows.
- It follows the weather darkening, the time of day, the brightness option, lightning and night vision.
- The composite and `applyFogColor` both use J outright, so vanilla's fog color, the sky's weather mix, the clouds and the culled terrain take it too. The horizon color mix is gone.
- Without sky light, or when the lightmap cannot be read, the fog falls back to the config colors. After a throw it stops reading the lightmap for the session, and `/calculatedSky fog` shows J's source and the count of fallback frames.

**The sky.**
- Sky pixels get the snow fog along their ray. The walk runs out to `fogStructureRange`, and past it `HeightFog.skyTailOpticalDepth` adds `eyeExtinction * H * exp(-start * dy / H) / dy` in closed form. A ray at or below the horizon is opaque.
- The low resolution pass stores a sky ratio, which the composite reads through `FogUpsample.resolveSky`. Sky pixels draw in composite part 0 only, so they never count toward the fallback query or its cap.
- `fog selftest` also compares the sky depth of its 64 rays and reports how many fall outside the tolerance.

## Decisions

- The design was proposed after two fast agents diagnosed the cause independently, and both agreed to it with changes (`scratch/weather-fog-light-proposal.md`, "Agreed revision").
- J has no albedo factor and no tint. It is an upper bound of the fog's true source radiance, so distant snow can come out slightly darker than the fog in front of it, but never brighter.
- The sky shader is unchanged. Its weather mix already targets vanilla's fog color, which is now J.

## Validation

- `agent-tools/build.sh` and `glsl-check.sh` on both fog shaders pass.
- GameTests `frostedheart_weather` and `frostedheart_calculated_sky`: `All 232 required tests passed`. The new tests cover the sky depth and its tail against hand values, their guards, the sum with the segment depth, the sky walk with and without cover and structures, `resolveSky`, and the white face color.
- At first, two of the new allocation tests reported 104 and 376 bytes over 1000 calls, constant across two runs, in code with no allocation. Raising the warm-up from 100 to 20000 calls made them 0 in both test classes.
- Reviews in `scratch/subordinate/weather/3b-review-*.md`. Applied: the fallback count counted twice per frame, a broken lightmap threw every frame, and `CalculatedSkyRenderer.horizonColors` was dead code.
- Not run in game yet.

## Documentation

None changed. The weather docs are written in stage 11.

## Remaining

- The in-game check and a new GPU timing, since the low resolution pass now walks the sky texels.
- Night fog is now as bright as the terrain's night light (about 0.15 to 0.3) instead of the old config color (about 0.05).
- Torch-lit terrain is brighter than J and still shows through the fog without a halo.
- The falling snow (`SpatialWeatherRenderer`) is drawn after the fog at a brightness from vanilla's sky darkness, not J, so at night its flakes are brighter than the fog. This is a follow-up.
- Vanilla darkens its fog color in the void and under a boss bar. The new fog's J replaces that darkening, as the old snow fog color did.
