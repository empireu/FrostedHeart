# Calculated Sky Diamond Dust Law And Light Pillars

- Time: `2026-11-13 16:19:01 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`DiamondDustLaw`, `DiamondDustExtinction`, `IceMirror`, `LightPillarOptics`, `LightPillarSources`, `LightPillarClusters`, `LightPillarSelection`, `LightPillarEmitters`, `LightPillarRenderer`, `SkyDrawTimer`, `SkySetting`, `CalculatedSkyClientCommand`), `shaders/light_pillars.glsl`, `datasets/light_pillars`, `FHShaders`, GameTests, `docs/climate/calculated-sky.md`

## Completed

Steps A and B of the visuals plan (diamond dust, then light pillars from the dust's plate crystals), in four commits on `feature/calculated-sky`. How it works now is in `docs/climate/calculated-sky.md`, sections "Light pillars", "Diamond dust from the weather", "Lights for light pillars", and "Drawing light pillars".

- `9ce1bf828`: `DiamondDustLaw` and `DiamondDustExtinction` give the dust's extinction at the ground from the time since the last snow: `0` while it snows, rising to `1 per km` over one climate hour after it, held to `12` hours, and back to `0` at `24` hours, followed smoothly over the climate clock. Settings `dustFollowsWeather` and `dustExtinction`. Nothing draws the dust itself yet.
- `4cb9578b9`: `IceMirror` (the reflectance of a plate from both faces, the tilt density of its faces) and `LightPillarOptics.radiance`, the pillar as an `8` point Gauss Legendre integral over the tilt of the mirroring faces along the view ray.
- `671fe0ca7`: finding the lights. `LightPillarSources` scans the loaded sections in passes, `LightPillarClusters` merges lights into cells of `4 x 4 x 4` blocks, `LightPillarSelection` keeps `48` slots that fade over `10` ticks, and `LightPillarEmitters` takes colors from `datasets/light_pillars/emitters.csv` or the particle texture. Setting `pillarChunkRadius`, status in `/calculatedSky pillars`.
- `cb15106e6`: drawing. `LightPillarRenderer` draws two ribbons per slot at `AFTER_WEATHER` with `light_pillars.glsl`, which computes `LightPillarOptics.dustRadiance` per pixel. The fragment shader reads the main depth through a framebuffer that holds only the main color, and counts only crystals in front of the scene. `findEyeOffset` places the lights at the bobbed eye. `SkyDrawTimer` measures the draw on the CPU and the GPU. Setting `pillarBrightness`, with `LightPillarOptics.BRIGHTNESS` at `433`.

## Decisions

- empireu's answers to the plan on `2026-11-11`: the dust follows every snow and stays under the sky's own switch, and no aurora until this agenda is done.
- The dust law uses the time since snow only. Cold events return to `0` within hours after the snow, so a temperature limit would cut the dust short, and no packet field was added.
- The pillar is integrated along the view ray instead of the closed form from the plan, which is up to `2.3` times too bright at the base of a pillar of wobbling plates.
- Step B went in three pieces, each reviewed and committed on its own. For the scan empireu chose double-buffered passes, a configurable radius, and the override table, and left the timing to the instrumentation.
- Pillars are drawn per pixel, not on the sky texels the plan proposed. Their crystals are near, so on texels they slid and morphed as the eye moved, seen in `runClient` on `2026-11-12`.
- Ribbons stand `1` block from the eye without a depth test. The crystals that light one pixel spread along its view ray, so a depth test at one crystal distance drew pillars on a wall between the eye and the torches.
- The light side of the shadow was a screen-space rule in this step: scene points horizontally nearer than the light got no pillar light. It was known to miss walls around a light, and the next entry replaces it.

## Validation

- `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: `18` of `18` after step A, `24` of `24` after the scan, and `30` of `30` after the drawing. Both shader stages passed `glslangValidator`.
- empireu confirmed the dust law in `runClient`. The scan was committed after a code review without an in-game run, so its tick cost was first measured in the next entry, `0.120 ms` per tick for the passes.
- empireu looked at the drawing in `runClient` three times on `2026-11-12`. The first look found sliding texels, pillars bending with view bobbing, too low a resolution, and too much brightness (`BRIGHTNESS` was `5009`). The second found pillars on a wall in front of distant torches. The third, a torch in a ring of walls, showed the pillar above the walls, which is what the next entry fixes.
- A read-only review of the drawing by a second model through pi led to fixes of the saved blend equation, half-loaded shaders, cleanup after a failure, and docs.
- Documentation: the four sections named above, the configuration and command tables, and the known limits. The sections on the scan and the drawing describe the passes and the screen-space rule, which the next entry replaces, and are updated with it.
- The full `frostedheart` GameTest namespace was not run for this entry.

## Remaining

- Overlapping pillars show up to `1.29` times too bright, because each ribbon is sRGB-encoded before the screen blend. An accumulation target would fix it.
- A fade of crystals within `8` to `24` blocks of the eye was proposed and not decided.
- Pausing can step the climate clock back and snap the ice and dust to their targets. empireu wants to see first whether the snap is visible.
- Glints, the sun pillar, and moonlight from the plan are not started, and the plan has no file in `plans/` yet.
- `CalculatedSkyClientCommand.ENABLED` goes to `false` before a pull request.
