# Falling Snow Near the Eye Replaces Vanilla's Snow Under the Weather Fog

- Time: `2026-11-23 19:02:31 +0200`
- Author: `Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `2026-11-23_11-35-02_weather-fog-far-cap-and-defaults.md`, not committed. First piece of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - New: `SnowFlakes`, `SnowfallRenderer`, `snow_flakes.glsl`, `snow_flake_update.fsh`, `snow_flakes_draw.glsl`, `weather_fog_excess.glsl`, `SnowFlakesGameTests`
  - Changed: `WeatherFogRenderer`, `weather_fog_composite.fsh`, `FHShaders`, `SkySetting`, `CalculatedSkyClientCommand`, `LevelRendererMixin`, `SpatialWeatherRenderer`

## Completed

**Why.** Under the weather fog, vanilla's snow ignores the fog and its shelter, and FrostedHeart's ground particles of the snow appear at random spots that match no flake. Stage 5 replaces both, in small pieces that empireu checks in game before the next. This entry is the first piece: flakes near the eye, with only the base wind.

**What changed.**
- `SnowfallRenderer` draws at `AFTER_WEATHER` in frames where the weather fog was drawn and `snowEnabled` is on. `ownsPrecipitation()` says so for the frame, and between frames for the last one.
- The flakes live in a periodic, world-aligned box of side `2 * snowNearRadius` (default 40 blocks). A flake's position from 0 up to the side stands for its copies at every multiple of the side, and the draw takes the copy nearest the camera. The box follows the camera without respawning, positions stay small floats, and the camera reaches the shader wrapped in double precision.
- The state is two RGBA32F textures of 512 by 256 texels (131072 flakes) that take turns. `snow_flake_update.fsh` moves every flake by the wind of `WeatherWind` and its own fall speed, over the seconds the wind advanced, so a pause stops it. The first frame places every flake from hashes. xyz is the position in the box and w the angle on the flake's flutter circle, which the draw adds on top without changing the path.
- The number drawn follows the fog: the flakes cover `snowFlakeShare` of the fog's extinction at the eye, `count * meanArea / side^3 = share * extinction` (`SnowFlakes.askedCount`), capped at the capacity. They follow snowfall, storm and height as the fog does. The last 1024 flakes below the count fade, so a changing count does not pop.
- `snow_flakes_draw.glsl` draws one instanced quad per flake without vertex data:
  - a soft round sprite smeared over `snowStreakSeconds` of its motion, with its opacity spread over the streak (`SnowFlakes.streakShare`), and a flake below a pixel widened to 0.75 pixels at the same energy;
  - the fog's optical depth to the flake, analytic height fog plus the froxel excess, read the same way as the composite, and the color `J * (1 + (snowFlakeBrightness - 1) * T)` with J the snow fog color;
  - the snow share of the fog's share volume at the flake, interpolated between the eight surrounding cells, with a hashed threshold per flake over a softness of 0.25 (`SnowFlakes.shelterWeight`);
  - fades at the box faces (4 blocks) and within 0.6 blocks of the eye;
  - the depth test of the scene, no depth writes, and the alpha of the main target kept.
- The excess reads of the composite (`fogFroxelSlicePosition`, `froxelChannel`, `froxelBoundaryExcess`, `froxelExcessAtDistance` and their uniforms) moved unchanged into `weather_fog_excess.glsl`, which `FHShaders` prepends to the composite and to the flake draw. Its read is now `textureLod` at level 0, which equals the old `texture` because the excess array has no mipmaps.
- `WeatherFogRenderer` gives the flakes package-private reads of the frame: the wind and its step seconds, the snow color, the height scale, the eye offset and fraction, the share volume, and the excess array and grid.
- While the snowfall draws, vanilla's `renderSnowAndRain`, the snow of `SpatialWeatherRenderer`, its `tickGroundEffects`, and the ground particles of the replaced `tickRain` are all off. The sounds stay.
- Up close a flake is an irregular clump instead of a dot: a core lobe and five lobes around it, laid out by the flake's hash, turning once per flutter circle and squashed to 0.45 when it tips edge on. The shape takes over from the dot between true radii of 2 and 5 pixels, is smeared along the streak, and turns with the screen rather than with the streak, so it does not spin when the view turns.
- Settings, placed before the fog settings so `FogSettingsGameTests` keeps finding the fog settings last: `snowEnabled` (true), `snowFlakeShare` (0.5, empireu's value), `snowFlakeSize` (0.08 blocks), `snowFallSpeed` (0.6 blocks per second, each flake within a third of it), `snowFlutter` (0.15 blocks), `snowStreakSeconds` (0.02), `snowFlakeBrightness` (1.0), `snowNearRadius` (20 blocks).
- `/calculatedSky snow` shows the flakes drawn, the count asked for, whether it is capped, the CPU and GPU time of the update and the draw, and the bytes allocated in the last frame. `/calculatedSky on` clears an earlier failure of the snowfall too.
- `SnowFlakesGameTests` (namespace `frostedheart_weather`) checks the twin: the wrap and the nearest copy, a step across the wrap and from a far camera, the count against the fog's extinction and its edge cases, the count fade, the streak share, the shelter weight, and the spreads of each flake's values.

## Validation

- `agent-tools/build.sh` compiles the main and GameTest sources.
- `agent-tools/glsl-check.sh` passes `snow_flakes_draw.glsl` (both stages, with `weather_fog.glsl`, `weather_fog_excess.glsl` and `snow_flakes.glsl` prepended), `snow_flake_update.fsh` (with `snow_flakes.glsl`) and `weather_fog_composite.fsh` (with `weather_fog.glsl`, `snow_walk.glsl` and `weather_fog_excess.glsl`).
- In game, empireu found that it looks good, at `snowFlakeShare` 0.5. Up close the flakes looked strange, because each was a perfect round dot, which the clump shape answers. empireu judged the shape good enough for now.
- empireu measured the GPU time of the update and the draw together in a blizzard, where 0.5 asks for about 296,000 flakes and the capacity of 131,072 draws: 0.080 ms with round dots, and 0.127 ms with the clump shape. In plain snow, 0.5 asks for about 79,000.
- The GameTests are compiled but not run. They run once together with the next piece, because a GameTest run takes about 6 minutes.
- Not measured yet: the bytes allocated per frame.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- In a blizzard the count is capped, so the flakes stop following the fog's extinction above about 0.22 of it. A capacity of 262,144 would roughly double the cost.
- The clump shape costs about 0.047 ms in the fragment stage. If the snow needs time back, the shape's lobe hashes can move to the vertex stage or to a small lookup texture.
- Collision and short-lived splats where flakes hit, the next piece. Flakes still pass into walls.
- Lamps do not light the flakes, so they are dark at night.
- The flakes end 20 blocks from the eye, so snow outside a far opening is still missing until the far snow is built.
- The fade at the box faces is a cube, not a sphere.
