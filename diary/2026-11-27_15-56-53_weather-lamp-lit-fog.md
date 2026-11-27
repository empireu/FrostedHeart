# Lamps Light the Weather Fog

- Time: `2026-11-27 15:56:53 +0200`
- Author: `Anthropic Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` on `feature/weather` after `fa41611b1`, checked in game. Stage 3 of `FOG_SNOW_DUST_ILLUMINATION_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Added: `LampFogPass`, `LampFogMarch`, `weather_fog_lamp.fsh`
  - Changed: `LampLightCascade`, `LampLightField`, `LampLightBinding`, `FogLight`, `FogPassTimer`, `WeatherFogRenderer`, `SkySetting`, `CalculatedSkyClientCommand`, `FHShaders`, `lamp_light.glsl`, `weather_fog_composite.fsh`, `LampLightGameTests`, `FogSettingsGameTests`

## Completed

**Why.** A village seen at night through the snow fog should light the fog over it, from 50 blocks out to the view distance, and a torch in the snow should light the fog around it. Walls and roofs should hold the light back as vanilla's light does.

**What changed.**
- `weather_fog_lamp.fsh` marches the lamp light that the fog scatters toward the eye, in its own pass between the integrate and the composite, into an RGBA16F target at a share of the resolution (`fogLampDivisor`, default 4). Each texel takes the ray of the frame pixel in the middle of its block and holds the added light and the distance its ray ended at.
- The ray walks the cells of each cascade of the lamp light field it crosses, cut into pieces at every cell boundary, and reads the filtered field in the middle of each piece. Where two cascades blend, both are read, with the band weight taken along the ray in closed form. The piece boundaries are fixed in the world, so moving or turning the camera changes each piece's length smoothly instead of moving the samples with the eye.
- Each lit piece adds the lamp color of its level times `exp(-tau0) - exp(-tau1)`, where `tau` is the fog's optical depth along the ray from the height fog and the froxel excess, as the composite takes it, held at the largest found so far so that it never falls. Over an even light this sums to the lamp color times the fog's opacity. The march stops where less than 1/512 of the light gets through. Dark pieces read no fog.
- Where the depth at the ray's end lies below the largest the march found, the light is scaled to the opacity at the end, which the composite draws the fog with. In a house the froxel excess cancels the height fog only roughly, so the depth along a ray rises and falls a little. Without the scaling, the glow there followed the largest depth while the composite discards pixels whose opacity at the wall is below 1/512, which showed as faint rings on the walls of lit houses in the first in-game check.
- The lamp color of a level is the white face color at full sky light at that level less the color at level 0 (`FogLight.lampLifts`), read from the lightmap pixels each frame the fog reads its color, blended between whole levels at `15 s` (the texel-center mapping of the plan's decision 7) and scaled by `fogLampLight` (default 1).
- Each cascade counts its lit cells per slot layer along x, y and z (`LampLightCascade.litBox`), and `LampLightField.shaderLitBoxes` gives the shader the box of each cascade's lit cells grown by half a cell. The march keeps to the pieces that touch those boxes, which gives exactly what walking every piece gives, so dark land, the sky and the air between the player and a far village cost nothing.
- `weather_fog_composite.fsh` reads the four texels of the glow around each pixel, weighted by their bilinear share and by how close their ray's distance is to the pixel's own, and writes `vec4(snowColor * opacity + glow, opacity)` premultiplied, with `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`. The debug views keep blending by their opacity, and the pass is skipped while one shows.
- The pass is skipped while the field is not tracked, not faded in or holds no lit cell, while the fog color does not come from the lightmap, and while `fogLampLight` is 0. A target or program that cannot be made turns the glow off for the session and leaves the fog as it was.
- `/calculatedSky fog passes` times the march as `lamp glow`. `/calculatedSky lamps selftest` marches 64 rays aimed at lit cells through an analytic fog on the GPU and with the Java twin `LampFogMarch`, and reports how far they differ.
- `lamp_light.glsl` gained `lampCascadeSharesAt`, which `lampSharesAt` now uses too. `LampLightBinding.bind` takes the eye as coordinates as well, so the fog passes its eye with view bobbing without making a `Vec3`.

**Decisions.**
- The plan's skipping, which probed a coarse cascade first and skipped where it read dark, was replaced by the lit boxes. Cascades 0 and 1 count air with sky light 1 to 14, under porches and by windows, which the coarse cascades leave out, so a dark coarse cell does not prove the near cascades dark. GPU filtering also weighs texels with 8 bits, so a probe can miss a lit neighbor.
- The ray walks the cells instead of stepping evenly from the eye, so the glow does not swim, which the plan requires.
- The lamp color comes from a 16-entry table filled on the CPU instead of a read of the lightmap texture, so the march binds 7 samplers and the Java twin uses the same colors.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh` on `weather_fog_lamp.fsh`, `weather_fog_composite.fsh`, `diamond_dust.glsl`, `snow_flakes_draw.glsl` and `lamp_light_debug.glsl` with their prepended files: all passed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`, run again after the scaling: `All 272 required tests passed`, which is the 268 of stage 2 plus the 4 new tests below.
- New GameTests in `LampLightGameTests`:
  - `litBoxesHoldEveryLitCell`: after a fill, a move and a broken torch, each cascade's lit box equals a scan of every cell of its window, and a field without light gives the shader only empty boxes.
  - `lampMarchOfAnEvenFieldAddsTheLampColorTimesTheFogsOpacity`: over light 15 everywhere, 16 rays in all directions add the lamp color times `1 - exp(-tau)` within `1e-4`.
  - `lampMarchOfAFallingDepthGivesTheFogsOpacityAtTheRaysEnd`: a depth that rises to 3 and falls to 1 at 40 blocks gives the lamp color times the opacity of depth 1, and a depth that falls and then rises to 3.4 gives the opacity of 3.4.
  - `lampMarchKeepingToTheLitBoxesMatchesTheWholeMarch`: 400 rays over the test scene, half aimed at its lamps, match a march that walks every piece, and walk fewer pieces.
  - `lampShaderConstantsMatchTheField` now also checks the constants of `weather_fog_lamp.fsh` and the composite's sky distance.
  - `lampFieldAllocatesNothingOnceWarm` now also writes the lit boxes each step.
- `FogSettingsGameTests` checks `fogLampLight` and `fogLampDivisor`.
- First in-game check by empireu, before the scaling:
  - The glow works. `/calculatedSky lamps selftest` matched all 64 rays, of which 16 were lit.
  - `/calculatedSky fog passes`: the march took 0.110 ms on the GPU and the composite 0.075 ms, for 0.394 ms in total against 0.265 ms before this stage.
  - Faint rings on the walls inside a lit house, which the scaling above fixes.
  - Behind icicles the fog and the glow are missing in rectangles. The icicle models use `minecraft:translucent`, whose pass writes depth for fully transparent texels too, and the fog composite draws after the translucent terrain, so those pixels take the fog only out to the icicle. This was so before this stage. The glow makes it stand out more.
- Second in-game check by empireu, with the scaling: the rings on the walls are gone, and the screenshot set of the "before" views was taken again.

## Documentation

No living doc describes the weather fog yet, because the weather docs are written in the wrap-up stage of the weather plan. `docs/climate/calculated-sky.md` needs no change.

## Remaining

- The fog behind translucent blocks with transparent texels, such as icicles, needs the fog drawn before the translucent terrain or another fix. It comes next, as its own change.
- The upload budget and `lampFieldBudgetMicros` are set once the lights are done.
