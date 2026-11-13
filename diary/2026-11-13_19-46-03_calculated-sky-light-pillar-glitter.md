# Calculated Sky Light Pillar Glitter

- Time: `2026-11-13 19:46:03 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `partial`
- Scope: `content/climate/render/sky` (`LightPillarOptics`, `LightPillarRenderer`, `SkySetting`, `CalculatedSkyScreenshots`), `shaders/light_pillars.glsl`, GameTests, `docs/climate/calculated-sky.md`

## Completed

Light pillars glitter and twinkle instead of showing still bands, after `2026-11-13_19-01-53_calculated-sky-light-pillar-overhangs-near-fade.md` (`7f464cbee`).

- `LightPillarOptics.dustCrystalCount` counts the crystals that mirror the light into a pixel, along the same `8` points as `dustRadiance`, with the near fade and the horizons. A face mirrors the light while it lies within the spread of its reflection by diffraction, `lambda^2 / (A sin(g))`, and the face area per volume holds `1 / A` crystals per unit face area. `crystalCountScale` turns the count into crystals per pixel for the pixel's angle and the plates' diameter, the new setting `pillarCrystalSize` (default `65` micrometers, `0` for smooth pillars).
- `light_pillars.glsl` sums the count with the light. The fragment stage divides the sky into cells one pixel wide, fixed to the direction of each light, draws a count of crystals per cell from the Poisson distribution, or a normal distribution above a mean of `16`, and shows `min(k / N, sparkleCap(N))` of the mean value. A lowbias32 hash of the cell and its ribbon seeds the draws and gives each cell a phase, and the cell blends to a new count every twinkle period. `LightPillarOptics.sparkle` mirrors it for the tests.
- `LightPillarRenderer` keeps the twinkle clock. It advances by the time over the new setting `pillarTwinkleSeconds` (default `0.25`) and by the eye's movement over `0.25` blocks, at most one period a frame, holds while the game is paused, and stays at `0` while the screenshot harness takes shots (`CalculatedSkyScreenshots.isRunning`).
- Ribbons hold the glints. With glitter, a row's half width is where the mean count falls to `leastVisibleCount`, the count below which a cell shows the visible value with less than `0.2%` chance per period (`ribbonHalfWidth` with a count).
- `docs/climate/calculated-sky.md` describes the count, the drawing, the ribbon rule, and the limits, and its configuration table now lists `pillarNearFade`, `pillarCrystalSize`, and `pillarTwinkleSeconds`.

## Decisions

- empireu asked on `2026-11-13` for believable glint and grain on the bands, after reading research a web agent gathered on real pillars and diamond dust. Real pillars are the summed glints of single crystals: near ones glitter, far ones average out. One Poisson count per cell gives both the grain of far pillars and the sparse glints of near ones, from one physical size, so neither strength is a free parameter.
- A cell with fewer than `1 / 64` crystals on average draws its rare glints at `64` times the mean value, so it shows `64 (1 - exp(-N))` of the mean. That dims the parts of near pillars whose crystals are within a few blocks of the eye, as the near fade does.
- The first ribbon rule, holding the center's value times `sparkleCap`, left glints of about the visible value outside the ribbon in up to `41%` of pixels at the edge, because the count falls off center while each crystal keeps its value. The rule by chance replaced it. A chance of `1%` instead of `0.2%` saved a third of the extra area but left `8` to `11%` near the zenith.
- The twinkle period of `0.25 s` and the `0.25` blocks per period while moving are chosen by eye, to be tuned in game. By estimate, a crystal `50` blocks away drifting at `1 m/s` crosses a pixel in about `0.07 s`, and moving the eye a few centimeters changes which crystals line up, so real glitter changes at least that fast.
- Bokeh, flutter modes, and wind-driven tilts from the research were left out. Bokeh is a camera effect, and the zigzag and spiral fall modes belong to plates of millimeters, while diamond dust plates fall, by estimate, at a Reynolds number around `1` and stay stable.

## Validation

- Computed outside the game on `2026-11-13` with scratch probes against the classes of the last build, at `100` micrometers, `1 per km`, and `1080` pixels, without the near fade: plates show about `14` crystals per pixel at `10 degrees` for a torch `32` blocks away, about `1` from `30` to `60 degrees`, `0.02` for a torch `8` blocks away, and thousands below `20 degrees` for one `256` blocks away. The sampler keeps the mean value within `1.1%` for counts from `1/64` up and never exceeds its cap. Over the ribbon test's lights, ribbons cover about `64%` more sky than without glitter. Away from the ends of a ribbon a pixel just outside it shows the visible value with a chance of at most `0.54%`, and at the ends up to `3.4%`, with glints `1` to `3` times the visible value.
- GameTests on `2026-11-13`, `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17 passes all `51` tests, with the ribbon tests without glitter unchanged. The first run failed two new checks: a blended value one rounding step above its cap, and the count of wobbling plates at `3 degrees`, which now has its own tolerance of `25%`. New: `lightPillarCrystalCountMatchesTheRayIntegral` (within `1.2%` for plates and `23%` for wobbling plates, whose tilts span `40 degrees`, at `3 degrees`), `lightPillarSparkleKeepsTheMeanValue`, and `lightPillarRibbonsHoldTheGlints`.
- Both shader stages pass `glslangValidator`.
- In game on `2026-11-13`, empireu tuned `pillarCrystalSize` and chose `65` micrometers, which became the default. With `N` falling as `D^-4`, that is about `5.6` times the counts above, so about `80` crystals per pixel at `10 degrees` for the torch `32` blocks away, about `6` from `30` to `60 degrees`, and about `0.1` for the torch `8` blocks away. The GPU time of the wider ribbons in `/calculatedSky pillars` was not reported.
- The full `frostedheart` GameTest namespace was not run for this entry.

## Remaining

- In game: `pillarTwinkleSeconds`, turning the head, walking, pausing, and the GPU time.
- Whether `pillarNearFade` should come down now that the crystal count dims near crystals by itself.
- The remaining items of the last entry: the in-game checks of changes, the docs for the event-driven scan, the shadows, and the overhangs, the zenith leak of ribbon edges, `lightPillarLightPassesBelowTheViewRayBeforeTheLight`, the accumulation target, and `CalculatedSkyClientCommand.ENABLED` before a pull request.
