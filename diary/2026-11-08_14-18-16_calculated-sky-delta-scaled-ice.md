# Calculated Sky Delta Scaled Ice

- Time: `2026-11-08 14:18:16 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`SkyAtmosphere`, `SkyTables`, `SkyTransmittance`, `SkyView`, `IceHaloProfile`, `CalculatedSkyRenderer`, `calculated_sky.fsh`, `CalculatedSkyGameTests`)

## Completed

- `SkyAtmosphere` counts only the half of the ice that does not diffract (`1 - ICE_DIFFRACTION_FRACTION`) in its scattering and extinction totals, so the sun's beam, the multiple scattering, `G`, and the sky seen through ice treat diffracted light as transmitted. `iceScattering` keeps all of it for the ice view.
- The sun's square keeps the full ice extinction. `SkyTables.diffractionWeights` scales the diffraction peak per row by `(1 - exp(-f tau)) / (f tau)`, so light diffracted more than once is drawn once, and the square and the glow carry the delta scaled beam between them. The renderer uploads the weights as a texture of their own on unit `8`.
- `IceHaloProfile` keeps the diffraction peak apart from the tumbling crystals, uploads them as two rows, and leaves the peak out of the light on the ground. `IceHaloProfile.phase` and `SkyView.radiance` take the weight.
- `SkyTransmittance` has `128` rows instead of `64`, spaced evenly in the distance to the horizon below (`24`), inside (`64`), and above (`39`) the ice band.
- The preview in the contributor's workspace (outside the repository) got the same delta scaling behind a setting, the same transmittance rows, a mode `delta` that compares both against its path tracer, and band steps in the path tracer's transmittance table. Its mode `skyreference` gave the GameTest's new preview radiances.
- GameTest `sunSquareAndAureoleCarryTheDeltaScaledBeam`.

## Decisions

- empireu asked on `2026-11-08` to delta scale the ice before the weather law, after the `v1` set at depth `0.5` showed a dim orange sun and a dark gray sky and snow at sunrise and sunset.
- All paths of the fast model are delta scaled, not only the sun's beam, because the path tracer showed the sky seen through ice and the light between scatterings losing the diffracted half as well. Without the weight, the glow at depth `0.5` and a sun at `3 degrees` would carry about three times the light that reaches it, by `f tau / (1 - exp(-f tau))`.
- The weight is per row of the sky view, from the ice optical depth along the row, rather than a second ice view, which would have doubled the ice view's memory and uploads. It is exact toward the sun, where the peak matters.
- The transmittance rows were concentrated in the ice band in the same change, because the check of the delta scaling found the glow around a low sun overestimated by `6..7%` at depth `0.5`, from linear interpolation of `exp(-tau)` between rows about `1 km` apart in the ice. With `64` rows, `32` of them in the band, the sky without ice got worse, so the table grew to `128` rows.

## Validation

- Preview mode `delta` against the path tracer, with the preview's generic ice phase, at depths `0.1` to `2` and sun elevations `3` to `30 degrees`: the mean dE went from `2.4..11.4` with the full extinction to `1.6..4.7` with delta scaling, the light within `10 degrees` of the sun from `0.28..0.85` of the path tracer's to `0.59..1.00`, and the ground from `0.54..1.09` to `0.79..1.07`. The table is in `docs/climate/calculated-sky.md` under Delta scaled ice. The path tracer's own noise at depth `2` is a mean dE of `2.2`.
- A probe outside the game, built from the mod's sky classes, compared sky profiles more than `5 degrees` from the sun at sun elevations `2`, `15`, `30`, and `60 degrees`. Delta scaling changed the sky by up to `19` display steps at the default depth `0.1`, in the glow around a low sun, and `1..4` elsewhere, and by up to `122` steps around a sun at `2 degrees` at depth `0.5`. Against `1024` transmittance rows, `64` even rows were off by `1.3`, `1.9`, and `5.5` steps at depths `0.5`, `1`, and `3`, and the ground by `+4.8%` at depth `3`. `128` rows with `64` in the band stay within `0.3` steps and `0.5%`. `1024` columns changed nothing.
- The new GameTest's rows, measured with the probe: the sun's square within `1.2%`, `0.9%`, and `0.4%` of a fine integration at `3.7`, `8.4`, and `45 degrees` with depth `0.5`, and the square and the glow within `3.7%`, `1.3%`, and `0.4%` of the delta scaled beam. The test asks for `2%` and `5%`. At depths `1`, `2`, and `3` the square and the glow exceed `5%` for suns below `7`, `12`, and `13 degrees`, up to `8%`, where the beam is at most `0.3%` of the sun's light. `256` band rows or `1024` even rows do not change that, so it does not come from the table's rows, and it was not traced further.
- Timed outside the game with three alternating runs against the code before: halo profile unchanged, tables and sky view about `4%` slower.
- `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all `10` required tests passed, no warnings in the sky package.
- Documentation: the transmittance table, a Delta scaled ice section with the path tracer table, the formula of the ice view, the sun, the shader, the GameTests, the Monte Carlo note, and the build times in `docs/climate/calculated-sky.md`, and a decision and step 5 in the plan.
- empireu took the set `v1` in `runClient` the same day at depth `0.1` (`v1-2026-11-08_14-24-33`) and `0.5` (`v1-2026-11-08_14-25-20`), all frames settled, `140..145` frames per second. At `0.5` sunrise and sunset show a warm glow around the sun and a brown sky over warm snow instead of the dim orange square and the dark gray, and the high sky is bluer with crisper halos. At `0.1` the frames differ from the run before by a mean of `3..5` display steps, most around the rising sun. empireu found the remaining error of up to about `8%` near a low sun at thick ice acceptable, as anything up to `10%` is.

## Remaining

- At depth `2` and a low sun the fast model stays about `20%` darker than the path tracer, where the multiple scattering treats repeated scattering by the forward scattering crystals as even in all directions.
- The weather law, with the wash-out depths measured again under delta scaling.
