# Calculated Sky Daylight

- Time: `2026-11-07 17:32:58 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `complete`
- Scope: `content/climate/render/sky` (`CalculatedSkyEffects`, `CalculatedSkyRenderer`, `SkyView`, `CalculatedSkyGameTests`)

## Completed

- `CalculatedSkyEffects.adjustLightmapColors` replaces the sky light in the lightmap with `SkyView.daylightColor` while the calculated sky is drawn. The formula, the night light, the rain fade, and what stays vanilla are in `docs/climate/calculated-sky.md` under Daylight.
- `SkyView.daylightColor` takes the ground radiance that every sky view already holds, so nothing new is built. `CalculatedSkyRenderer.daylightColor` blends the two views around the sun as the fog color does, and `SkyView.vanillaSkyLight` gives vanilla's sky light for a sky darkness.
- The change is computed once per lightmap update, at its first texel, and added to each texel times `LightTexture.getBrightness` of its sky light level, without allocating.
- GameTest `daylightDimsWithTheSunIntoVanillaNightAndRain`.

## Decisions

- empireu chose on `2026-11-07` to take the brightness from the model as well as the color, through the sky's own exposure, so the terrain darkens with the sky. Before that, a probe outside the game showed the model's light on snow nearly white all day and dimming much faster than vanilla's toward sunset, and nanobot optical depth from `0.25` to `2.0` changing noon light by about `2%`, so the color alone would have changed little.
- Vanilla's night sky light is the lowest each channel goes, so nights are never darker than vanilla. A sum instead of a floor would have tinted the afternoon blue by about `10%`.
- Rain blends the result back into vanilla's light, as the sky fades to the fog in snow.
- The hook carries the change through vanilla's factor `0.96` and its darkening during boss fights, which run before it, and leaves the lightmap vanilla during lightning flashes.
- It applies with the camera in a fluid too, since that is sky light reaching under the surface.

## Validation

- `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all `8` required tests passed, no warnings in the sky package.
- A probe outside the game, built from the mod's sky classes, printed `SkyView.daylightColor` against vanilla for sun elevations from `90` to `-18 degrees` at exposures `6` and `12`. The table for exposure `6` is in the docs. The daylight is `0.82` of vanilla's luminance at `30 degrees`, `0.87` to `0.90` from `12` to `5 degrees`, and `0.57` at `0 degrees`, and equals vanilla from `-6 degrees` down.
- empireu took the set `v1` again in `runClient` the same day (`run/screenshots/sky-shots/v1-2026-11-07_17-36-15`, 12 frames, all settled at `120` to `144` frames per second) and found sunrise and sunset much better. Against the run before the change (`v1-2026-11-07_17-20-44`), where the sky on still used vanilla's lightmap, the mean color of the snow in the lower quarter of the frame went from `(89, 90, 114)` to `(82, 79, 81)` at sunrise (sun `3 degrees`) and from `(75, 72, 84)` to `(73, 67, 64)` at sunset (sun `4.6 degrees`), so the lavender of vanilla's light became a neutral gray under the brown sky. A hillside at sun `42 degrees` became about `5%` darker, as expected once the brightness slider's curve is applied, and the frames in snow did not change. empireu also compared a torch at dusk with the sky off and on and found its light unchanged. They did not want a torch in the set, since it depends on the world.
- Documentation: added a Daylight section, the exposure's effect, the GameTest, and two known limits to `docs/climate/calculated-sky.md`, and a decision and step 4 to the plan.

## Remaining

- None for this step. empireu reviewed it, and it was committed together with the screenshot harness, which shares the docs and the plan.
