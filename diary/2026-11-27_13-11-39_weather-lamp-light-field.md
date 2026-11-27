# Lamp Light Field for the Weather

- Time: `2026-11-27 13:11:39 +0200`
- Author: `Anthropic Claude Opus 5.5, supervised by Gemini 4 Neon and empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `6bdaaed3f`, not committed. Stage 1 of `FOG_SNOW_DUST_ILLUMINATION_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Added: `LampLightField`, `LampLightCascade`, `LampLightTextures`, `ClientLampLight`, `LampLightDebugView`, `lamp_light.glsl`, `lamp_light_debug.glsl`, `LampLightGameTests`
  - Changed: `LightPillarWorldChanges`, `CalculatedSkyClientCommand`, `SkySetting`, `FHShaders`, `FogSettingsGameTests`

## Completed

**Why.** Torches and lamps should light the falling snow, the splats, diamond dust and the weather fog, near and far, the way vanilla lights the terrain, with walls and roofs blocking the light as they do in vanilla. This stage builds the field of block light that those draws will read. Nothing draws with it yet except its debug view.

**What changed.**
- `LampLightField` keeps up to 5 cascades of cells around the eye, 1, 2, 4, 8 and 16 blocks across and 1, 2, 4, 4 and 4 blocks tall. Each `LampLightCascade` is 64 cells across, 64 tall for the first two and 96 tall for the rest, so those hold the Overworld's whole height. The cells are stored with wrapping addresses, so a move only rewrites the cells that come in.
- A cell holds two shares of its blocks as half floats. The air share counts its outdoor air: sky light above 0 in cascades 0 and 1, and sky light 15 from cascade 2 on, so lit rooms behind walls and roofs do not glow from afar. The light share sums vanilla's block brightness `B(s) = 1.5 s / (4 - 3 s)` over that air. The shader divides the filtered light share by the filtered air share, so cells without outdoor air add nothing and do not darken their neighbors.
- Sections whose sky light has no data take the lowest layer of the first section above with data, or 15, as vanilla's `SkyLightSectionStorage` does. Unloaded chunks hold dark open air.
- `LightPillarWorldChanges` passes block and sky light computed or received and chunk unloads to `ClientLampLight`, which queues the columns. Window fills and moves go into a second queue that waits behind light changes. A frame reads queued sections until `lampFieldBudgetMicros` (default 500) is spent, nearest columns first on a whole fill.
- A window follows once the eye is 0.75 cells from its center. The number of cascades follows the view distance: 1 cascade reaches 30 blocks, 5 reach 480.
- `LampLightTextures` keeps one `GL_RG16F` 3D texture per cascade, filtered linearly and repeating. It uploads the bricks (section footprints) that changed, by layer along z, or the whole texture when a quarter of its bricks changed.
- `lamp_light.glsl` holds the shared read for the next stage: each cascade filtered within its window and blended into the next over 26 to 30 cells from the eye, then the inverse of vanilla's curve. `LampLightField.levelAt` is its Java twin.
- `ClientLampLight.fade` gives the fade over 0.5 s after a whole fill, for the users of the next stage.
- `/calculatedSky lamps` reports the field and the uploads, `/calculatedSky lamps verify` reads every texture back and compares it with the cells, and `/calculatedSky lamps debug <0 to 4|off>` draws one cascade as a dot per lit cell, blue for dim to amber for full block light.

**Decisions.**
- The field is kept while the weather fog or the lamp glints of diamond dust can be drawn, so a change in the weather never refills it.
- The queues are a small ring inside `LampLightField` that never shrinks. fastutil 8.5.6's `LongArrayFIFOQueue.dequeueLong` halves its array once it is a quarter full, so a queue that fills and drains each frame would allocate.
- `DustLights` stays until the next stage moves diamond dust onto cascade 0.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh` on `lamp_light.glsl` with `LAMP_LIGHT_FIELD` defined, and on `lamp_light_debug.glsl` for both stages with `lamp_light.glsl` prepended: all passed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 270 required tests passed` and `BUILD SUCCESSFUL` in the Gradle log.
- New GameTests in `LampLightGameTests`, against a fake world whose sky light follows vanilla's rule for sections without data:
  - `lampCellsHoldTheLightOfTheirOutdoorAir`: every cell of every cascade near a torch, a lit closed house, a lit windowed room with light through its window, two far village lights, an unloaded chunk and a section that takes its sky light from above equals the sum over its blocks, as do 3000 sampled cells per cascade. The closed house holds no outdoor air. The windowed room counts in cascade 0 and not in cascade 2. Each window gives every cell its own slot, and a section's cells share one brick.
  - `aBrokenTorchAndAnUnloadedChunkLeaveNoLight`: breaking a torch and unloading a lit chunk leave no light in any slot of any cascade.
  - `lampFieldFiltersWithoutDarkeningAndBlendsWithoutJumps`: the inverse curve returns every level. Lit air next to the ground reads its own level at distances from 3 to 229 blocks, deep ground reads 0 without NaN, and the light falls steadily to 0 within a cell past the edge of lit air. The blend weights sum to 1 over at most 2 entries. Patterned light reads the same within 0.002 on both sides of a move of cascade 0.
  - `movedAndBudgetedFieldsEqualAFreshBuild`: a field moved along a path with a jump of 70 blocks, a torch placed and a chunk unloaded on the way equals a field built fresh at the end, cell for cell. So does one read 13 sections per step and drained at the end.
  - `lampShaderConstantsMatchTheField`: the constants of `lamp_light.glsl` match `LampLightField`.
  - `lampFieldAllocatesNothingOnceWarm`: following, marking, reading and sampling allocate 0 bytes once warm.
- In game, cascade 0 of the debug view shows the expected diamond around a torch in the snow at night. The other checks are listed under Remaining.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the weather plan.

## Remaining

- Check in game with the debug view: a lit closed house and a windowed one, a village from afar, flying and teleporting, and `lamps verify`.
- In snowfall the textures take about 180 uploads per second steadily, likely from the light updates of snow accumulation. Each update rewrites its section in every cascade even when nothing changed. Marking a brick only when a value changes should cut this. The budgets are set once the lights are done.
- Stage 2: the flakes, splats and diamond dust read the field, and `DustLights` goes.
- Stage 3: the fog march.
