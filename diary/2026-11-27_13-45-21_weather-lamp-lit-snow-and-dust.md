# Lamps Light the Falling Snow, Splats and Diamond Dust

- Time: `2026-11-27 13:45:21 +0200`
- Author: `Anthropic Claude Opus 5.5, supervised by Gemini 4 Neon and empireu (outside contributor)`
- Status: `done` on `feature/weather` after `42ee82f50`, checked in game. Stage 2 of `FOG_SNOW_DUST_ILLUMINATION_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Added: `LampLightBinding`
  - Changed: `LampLightField`, `ClientLampLight`, `SnowfallRenderer`, `WeatherFogRenderer`, `DustGlitterRenderer`, `DustGlitterField`, `LightPillarWorldChanges`, `CalculatedSkyScreenshots`, `CalculatedSkyClientCommand`, `LightTextureAccessor_WeatherFog`, `FHShaders`, `lamp_light.glsl`, `snow_flakes_draw.glsl`, `diamond_dust.glsl`, `LampLightGameTests`, `DustGlitterGameTests`, `docs/climate/calculated-sky.md`
  - Removed: `DustLights`, `DustLightVolume`, `DustLightGameTests`

## Completed

**Why.** A torch in the snow at night should light the flakes falling past it and the splats around it the way vanilla lights the snow blocks there, and diamond dust should take its lamp light from the same field instead of a volume of its own.

**What changed.**
- `LampLightBinding` binds the five cascades of the lamp light field for a program that reads `lamp_light.glsl`, saves and restores their 3D textures, uploads the field's changes, and sets the uniforms from `LampLightField.shaderUniforms`, the Java side of the shader's read. Without a field it sets no cascade, and the read gives dark open air.
- `lamp_light.glsl` gained `lampFieldFade` and `lampShownLevelAt`, the level faded in by `ClientLampLight.fade` after a whole fill.
- Flakes and splats read the field in the vertex stage, a flake at its center and a splat half a block out from its face. The color of a white top face there is the fog color plus the lightmap's lift at that level over level 0, read at full sky light with vanilla's mapping of terrain. A flake or splat takes that color times its own relative color, and the fog in front of it blends it toward the fog color: `mix(snowColor, litColor * relativeColor, exp(-tau))`. Without lamp light this is today's color exactly.
- `SnowfallRenderer` binds the cascades on units 11 to 15 and vanilla's lightmap on unit 5 with linear filtering, as `LightTexture.turnOnLightLayer` sets it for terrain. That makes 14 samplers in the vertex stage. `LightTextureAccessor_WeatherFog` gives the lightmap's texture.
- Diamond dust reads `lampShownLevelAt` in place of its own volume, with the cascades on units 4 to 8, and keeps `blockLightColor` for its glints, now in `DustGlitterField`. `DustLights` and `DustLightVolume` are gone, and so are their hooks in `LightPillarWorldChanges`, the screenshot harness and `/calculatedSky dust`, which now prints the field's line. The screenshot harness waits for the field to read its queue and fade in (`ClientLampLight.isReading`).
- `docs/climate/calculated-sky.md` describes the glints' lamp light from the field.

**Decisions.**
- The lamps' light is added to the fog color as a lift over the lightmap at level 0, instead of the lightmap's color itself, so an unlit flake keeps exactly the fog color the CPU reads from the lightmap's pixels, whatever precision the GPU filters with. This makes the planned check that the lightmap read at level 0 equals `FogLight.whiteFaceColor` unnecessary.
- Lamps do not light flakes while the fog color falls back to the config colors, that is in a dimension without sky light or with an unreadable lightmap.
- Every user binds all five cascades. Flakes within 26 blocks read only cascade 0, and flakes near the 32-block largest `snowNearRadius` blend into cascade 1.
- Glints now take only the light of outdoor air, blocks with sky light above 0. A glint beside a wall is no longer darkened by it. Glints only show in open air, so lamps inside closed rooms lit none before either.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh` on `diamond_dust.glsl` and `snow_flakes_draw.glsl` with their prepended files and `LAMP_LIGHT_FIELD` defined, and on `lamp_light_debug.glsl`: all passed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 268 required tests passed`, which is the 270 of stage 1 less the 4 of `DustLightGameTests` plus the 2 below.
- New and moved GameTests:
  - `LampLightGameTests.theShadersReadOfTheUniformsMatchesTheField`: a moved field over patterned light gives the shader all five cascades, and the read of `lamp_light.glsl` from those uniforms, in float with each cascade filtered linearly and repeating as the GPU samples it, matches `LampLightField.levelAt` at 20000 points out to 500 blocks, of which over a tenth are lit. A field that never followed gives 0 cascades.
  - `LampLightGameTests.lampFieldAllocatesNothingOnceWarm` now also writes the shader's uniforms each step.
  - `DustGlitterGameTests.lampGlintsTakeVanillasBlockLightColor`, moved from `DustLightGameTests`: the glints' color matches vanilla's block light at every level.
- In game, checked by empireu:
  - At night in a blizzard a torch lights the falling snow nicely. The snowfall then takes about 0.27 to 0.3 ms on the GPU and 0.1 ms on the CPU, which now also holds the uploads of the field's textures.
  - At `snowNearRadius` 32 the snow is less dense, as before this stage, because the flake count is capped.
  - In snow by day the lamps add little, as vanilla's lightmap gives by day.
  - At night lamps light the glints of diamond dust.

## Documentation

`docs/climate/calculated-sky.md` now takes the glints' lamp light from the lamp light field, with the new code anchors, the screenshot wait and the GameTests. The flakes and splats have no living doc yet, because the weather docs are written in the wrap-up stage of the weather plan.

## Remaining

- The fog does not glow around lamps until stage 3.
- The upload budget and `lampFieldBudgetMicros` are set once the lights are done.
- Stage 3: the fog march.
