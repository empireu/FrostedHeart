# Calculated Sky Refactor

- Time: `2026-11-11 19:59:08 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`SkyFrame`, `SkyViewSet`, `SkyState`, `SkyBuilder`, `SkyTextures`, `SkyGlPass`, `CalculatedSkyRenderer`, `SkySetting`, `SkySettings`, `BandRows`, `SkyDisplay`, `SkyView`, `SkyTables`, `SkyTransmittance`, `SkyRaySteps`, `IceWeatherDepth`, `IceDepthGrid`, `CalculatedSkyClientCommand`, `CalculatedSkyScreenshots`), `FHConfig`, GameTests, `docs/climate/calculated-sky.md`, the plan

## Completed

A refactor of the sky package before the next visuals add passes and settings, in five commits on `feature/calculated-sky`, with nothing drawn or computed differently:

- `f9dc8a2b1`: `SkyFrame` works out the sun's elevation and direction, the drawn ice depth, the grid indices, the exposure, and the rain and thunder levels once per frame, at their first use. The draw, the fog color, the daylight, and `CalculatedSkyRenderer.isSettled` read it, so `IceWeatherDepth.update` runs once per frame instead of up to four times.
- `f2d3758ed`: `SkyViewSet` holds the four views by slot instead of four named fields, and owns the fog color and daylight blends that were static on `SkyView`.
- `6d6eaa88c`: the static renderer is split into `SkyBuilder` (states, spares, and builds, without GL), `SkyState`, `SkyTextures` (uploads), `SkyGlPass` (GL state around a raw draw and one static `VertexBuffer` quad), and a renderer of about `380` lines. Uniforms are set with raw `glUniform` calls at locations looked up once per shader program.
- `5582826b7`: the enum `SkySetting` holds every setting's key, config comment, default, range, and what a change rebuilds, and switches name their manual value. `FHConfig` defines the group with `SkySetting.define`, and the command's `set`, `reset`, and status and the harness's variants and manifest loop over it. `SkySettings` holds a value per setting. `SkyBuilder` reuses tables and halo maps separately, so `texelDegrees` keeps the tables and a nanobot change keeps the halo maps. New GameTest `settingsRebuildOnlyWhatDependsOnThem`.
- The commit of this entry: `BandRows` replaces the two copies of the row mapping around the ice band, `SkyView` samples rows through one `rowPositionAt`, `SkyRaySteps.ICE_BAND_STEPS` is declared once, and `SkyDisplay` holds the tone curve and the sRGB encoding for `SkyViewSet` and the GameTests. The whole `/calculatedSky` command, with the screenshot harness under it, is gated by `CalculatedSkyClientCommand.ENABLED`.

## Decisions

- Blend weights between views, halo maps, and depths are taken at each use and are not part of `SkyFrame`, because a build that finishes in `render` replaces the state after the fog color and the daylight of the same frame used the old one.
- LDLib's `UniformCache` allocates a lambda and a varargs array per uniform call, so the draw sets uniforms with raw `glUniform` calls, with locations keyed by the identity of the `ShaderProgram` so that a reload is noticed.
- `texelDegrees` blurs the halo profile and maps, and the views take their ground light from both, so a change of it rebuilds those and the views but keeps the tables, about `150 ms` each. A nanobot change rebuilds the tables and views and keeps the halo maps, which do not depend on it.
- empireu dropped array textures (step 3 of the refactor plan), the only part that would have changed GL behavior.
- empireu chose to keep the screenshot harness and gate the whole command instead of the harness alone. With `CalculatedSkyClientCommand.ENABLED` false the sky follows the client config only, and an error that stopped the sky clears only with a restart, since only the command's `on` cleared it.
- The shared GLSL include was skipped. The sky shader is loaded through LDLib's `Shaders.load`, which passes the source to GL without handling `#moj_import`.
- In `SkyViewSet.daylightColor` the normalization by snow under a zenith sun keeps its own expression through `SkyDisplay.encodeSrgb`, because `SkyDisplay.toneMap` would group `-exposure * albedo / pi` differently and could change the last bit.
- No build speedups. The builds take about `10%` of one background thread while the sun moves, which empireu found sufficient on `2026-11-07`.
- After each step a second model (DeepSeek V4.1 Flash through pi, read only) reviewed the change. It found no bugs. Its notes on an unbind outside `finally`, visibility, docs wording, and a shadowed name were applied.

## Validation

- `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17 after every step: all `16` required tests passed, and all `17` once `settingsRebuildOnlyWhatDependsOnThem` was added.
- empireu checked the frame context, the view set, and the renderer split by eye in `runClient`, and approved the settings table and the removal of duplicates.
- A temporary probe in `runClient` on `2026-11-11` measured `0` bytes allocated per frame by the draw, the fog color, and the daylight once warm. Some early reports showed `24` bytes per draw, likely a mixin callback in a `GlStateManager` call that the JIT later removes. This was not traced.
- The last commit was checked outside the game on the compiled classes before and after, with the dataset loader swapped to read files. Every output was byte identical, `8.9 MB` of views at ice depths `0.1`, `1`, and `3` and sun elevations from `-4` to `60 degrees`, the sun radiance, the diffraction weights, the fog and daylight colors, and the `SkyView` samplers. Warm timings over three alternating runs: two table builds `298 ms` before and `302 ms` after (median, within the spread between runs), four views `77.8 ms` and `77.7 ms`.
- Documentation: the code anchors, the runtime, configuration, command, harness, and GameTest sections of `docs/climate/calculated-sky.md`, and step 6, the harness decision, and the outcome in the plan.
- The full `frostedheart` GameTest namespace was not run for this entry.

## Remaining

- `CalculatedSkyClientCommand.ENABLED` goes to `false` before a pull request.
- The GPU time of the draw has never been measured.
- Diamond dust, light pillars, and moonlight come next, built on `SkyFrame`, `SkyGlPass`, and `SkySetting`.
