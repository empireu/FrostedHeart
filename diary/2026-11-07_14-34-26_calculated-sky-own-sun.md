# Calculated Sky Own Sun

- Time: `2026-11-07 14:34:26 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`SkyTables`, `SkyView`, `CalculatedSkyRenderer`), `LevelRendererMixin`, `assets/frostedheart/shaders/calculated_sky.fsh`

## Completed

- `calculated_sky.fsh` draws the sun as a square with the size, place, and orientation of the core of vanilla's sun texture: half side `0.075` on the plane one unit in front of the camera (`SkyTables.SUN_HALF_TANGENT`), about `8.6 degrees` across.
- Its radiance is the sun's irradiance spread over the square's solid angle, `0.02237 sr`, times the transmittance from the camera to the top of the atmosphere at each texel's elevation. `SkyTables.buildViewRays` writes it per row of the sky view (`SkyTables.sunRadiance`) from the product of the steps it already computes, zero for rows that hit the ground. `CalculatedSkyRenderer` uploads it as a `1 x 100` texture when the tables change, and the shader reads it with the row coordinate of the sky texel.
- The outline uses each pixel's own direction with one pixel of smoothing, so the sun glides with sharp edges like vanilla's sun quad, while its color follows the sky texels.
- `LevelRendererMixin.frostedheart$hideVanillaSun` gives vanilla's sun quad no size when `CalculatedSkyRenderer.isSunDrawn` says the calculated sky drew its sun in the same `renderSky` call. The moon and stars stay vanilla.
- Sky views now exist for sun elevations on a `0.25 degree` grid from `-18` to `90 degrees`, like the halo maps on theirs. The two around the sun are kept, the missing one is built when the sun leaves them, and the shader and the fog color (`SkyView.horizonDisplayColor`, now static over two views) blend them by the exact sun elevation.

## Decisions

- Vanilla adds its sun on top of the sky: a white to golden core of `8 x 8` of `32 x 32` texels and a round glow up to `16%` out to `16 degrees`. empireu saw the core as a square inside the sky's glow with ice optical depth `0.1`, and agreed to replace it.
- empireu chose the color of each texel's own elevation over one color for the whole square, so the bottom reddens first at sunset and the horizon cuts the square off without a separate test. The table needs no work per frame.
- empireu asked for the sun to glide smoothly. The first version faded the edge rows and columns of sky texels by how much of each the square covered. In game, with the day cycle running, the sun seemed to change from one state to another. Two causes were found. Edge texels that fade in and out make the square look as if it changes shape every `1.7 s`, since the sun crosses a `0.5 degree` texel in that time. And every new sky view, every `0.25 degrees` or `0.8 s`, shifted the sky near a low sun at once: measured with the committed classes, up to `7` display steps of `255` with the sun at `4.6 degrees` and up to `12` at `1.4 degrees` near the sun, and `3` to `4` across the sky, with ice optical depth `0.1`. With a high sun it was `0` to `2`. The outline moved to screen pixels, and the views are blended like the halo maps.
- The brightness has no setting of its own. The sky's glow is already white out to about `2..3 degrees` from a high sun, so a high sun shows as a white square with soft edges, and from about `10 degrees` down it stands out clearly.
- Vanilla's sun is hidden by `@ModifyConstant` on its half size `30.0F`, the only `30.0F` in `renderSky`. Disassembly of Oculus `1.7.0` showed it hides the sun for shader packs from an `@Inject` before `BufferBuilder.end()`, and Embeddium `0.3.31` injects only at the head of `renderSky`, so neither touches the constant. The flag is cleared at the start of every `CalculatedSkyRenderer.render` and set after a successful draw, so vanilla's sun returns underwater, while tables build, outside the overworld, with a shader pack, and after an error.

## Validation

- Outside the game, the new `SkyTables` gave sun transmittances within `3%` of `SkyTransmittance` at elevations from `-0.3` to `90 degrees`, zero at `-0.4 degrees`, and display colors white above about `8 degrees`, `(255, 237, 134)` at `4`, `(245, 148, 37)` at `2`, and `(190, 63, 2)` at `0.5 degrees`.
- Outside the game, two views `0.25 degrees` apart blended halfway stayed within `1` display step of a view computed for the sun's exact elevation, at sun elevations from `1.4` to `30 degrees` and ice optical depths `0.1` and `0.3`.
- `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all 7 required tests passed, including the new `sunReddensTowardTheHorizonAndSetsBelowIt`, before and after the blended views.
- `runClient` the same day, with the first version: empireu found the low sun striking. At times `3000`, `12000`, `12500`, `12700`, and `12800` the square sat on vanilla's core, within `2` to `4` pixels in the screenshots. Snow dims it. Underwater the sun was not visible, but the water was too opaque to tell whether that differs from vanilla. With the day cycle running, the sun seemed to change from one state to another, which led to the pixel outline and the blended views. empireu then confirmed both in game the same day, and confirmed that underwater the sky and sun look the same with the calculated sky on and off.
- Documentation: added the sun and the blended views to the model, runtime, shader, fog color, validation, and known limits of `docs/climate/calculated-sky.md`, and updated the plan.

## Remaining

- empireu found ice optical depth `0.1` too little and asked for the ice and nanobot optical depths to follow a weather law. That is a separate change.
