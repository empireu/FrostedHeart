# Weather Fog Gathers into Moving Structures in Thin Clear Air

- Time: `2026-11-20 18:55:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (test writer, implementer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `4faaceae7`, not committed. It waits for the in-game check.
- Scope:
  - new `content/climate/render/fog/FogStructure`, and `SnowFogNoise` deleted
  - `SnowFogWalk`, `FogCullingDistance`, `WeatherFogRenderer`, `WeatherConditions`, `ClientMockWeather`, `WeatherWind`, `SkySetting`, `CalculatedSkyClientCommand`, `snow_walk.glsl`, `weather_fog.fsh`, `weather_fog_upsample.fsh`
  - new GameTest class `FogStructureGameTests`, `SnowFogNoiseGameTests` deleted, `SnowFogWalkGameTests`, `FogModelGameTests` and `FogSettingsGameTests` changed
  - replaces the density model of stage 3 of the weather rendering plan

## Completed

**Why.** In game, stage 3's noise showed nothing: "it's all a blur". It could only add to a uniform base, and it varied the density by a few percent. The user's reference clips show localized structures that move with the wind in much thinner air, both as heavy fog with little structure and as thin fog with heavy structure.

**The model.**
- The extinction is `sigma(y) * E * D`. D is the density of the structures and has a mean of 1 over space, so the visibility settings keep their meaning as the mean visibility.
- `D = 1 + share * (S / mean - 1)`. S is the structure shape from 0 in the clear air to 1 in a core, so the clear air has `1 - share` and a core `1 + share * (1 / mean - 1)`.
- The share comes from `WeatherConditions.structureShare()`. `ClientMockWeather` blends `fogSnowStructureShare` (0.3) and `fogBlizzardStructureShare` (0.75) by the storm intensity.

**The shape.**
- A tiling 64^3 `GL_RG8` texture built once from a seed.
  - The shape channel mixes Perlin billows, Worley puffs and a coarse octave that groups the structures into dense stretches and lulls. It is equalized, so every byte occurs equally often and `fogStructureCoverage` (0.35) is the share of space inside structures.
  - The detail channel is a Worley pattern at a quarter of the shape's tile. `fogStructureErosion` (0.5) lets it eat the edges into wisps within 32 to 64 blocks of the eye.
- `fogStructureSize` (96 blocks) spaces the structures across the wind, and `fogStructureStretch` (2) lengthens them along it. They are 0.6 as tall as they are wide.

**Motion.** `FogStructure.follow` keeps the pattern coordinate at the eye and adds the eye's movement minus the wind's displacement each frame. A world point keeps its coordinate while the eye moves, the pattern moves rigidly with the wind, and a turn of the wind turns the pattern about the eye.

**Sampling.**
- Inside the field region, the cell walk reads D once per cell.
- From the region's edge to `fogStructureRange` (96 blocks), a walk through a world-aligned grid of 4-block cells reads it once per part. Both grids are fixed in the world.
- D fades to 1 over the last quarter of the range. Beyond the range the fog is the mean, in closed form, so stage 3's far march is gone.

**Bound and tools.**
- `FogCullingDistance` takes the clear air out to the range and the mean beyond it as its lower bound, so a blizzard culls less.
- `/calculatedSky fog debug structure` colors each view ray, the sky included, by its structured depth over the mean depth out to the range: blue clear air, gray mean, red structures.
- `/calculatedSky fog` reports the share, the clear air and core visibility, the settings and the means.
- `fogNoiseStrength` and `fogNoiseScale` are removed.

## Decisions

- The user agreed to the model on 2026-11-20, with a share per weather and no offline preview, so tuning happens in game.
- The proposal's gust envelope of sines along the wind became the coarse octave of the shape texture. It groups the structures in the same way and keeps the mean exactly normalized.
- The density divides by the mean shape, which `meanShape` measures over the texel centers once per change of coverage or erosion. Between the fades of the erosion, the mean is blended linearly, which is off by up to a few percent.

## Validation

- `agent-tools/build.sh` and `glsl-check.sh` on both fog shaders pass.
- GameTests `frostedheart_weather` and `frostedheart_calculated_sky` together: `All 217 required tests passed`. The new tests check the texture filter, the shape, the build's histogram and seams, the mean against a dense average, the wind frame, the density's fades, the walks with uniform and real structures against hand values and a fine-step reference, the mock's share and the culling distance with clear air. The warm paths allocate 0 bytes.
- My review: the detail channel was built on half of its lattice, so it did not tile. Fixed before the GameTest run. The self test left the structure texture bound on unit 5. Fixed.
- Reviews in `scratch/subordinate/weather/3a-review-*.md`.
  - Applied: the shader's density now returns 1 for a range of 0, as the Java twin does.
  - Not applied: a float cancellation of a coarse boundary for a ray within a float step of a grid plane, which moves one part by up to a few blocks for one ray in one frame.
- Not run in game yet.

## Documentation

None changed. The weather docs are written in stage 11.

## Remaining

- The in-game check: `fog selftest`, `fog debug structure`, structures passing while standing still in a blizzard, no crawl while walking and turning, the GPU time against the budget, and `fog debug fallback` at structure edges.
- The texture and its means are built on the render thread on the first frame that needs them, a one-time hitch estimated at a few hundred milliseconds.
