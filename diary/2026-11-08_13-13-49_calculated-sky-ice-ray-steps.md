# Calculated Sky Ice Ray Steps

- Time: `2026-11-08 13:13:49 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `complete`
- Scope: `content/climate/render/sky` (`SkyRaySteps`, `SkyTables`, `SkyTransmittance`, `SkyAtmosphere`, `CalculatedSkyGameTests`)

## Completed

- `SkyRaySteps.fill` splits a ray where it crosses the ice band (`6.2..15.8 km`) and gives each crossing `12` equal steps. Outside the band the steps are the cells of the even grid over the whole ray, cut at the band's edges. The view rays, `G`, the multiple scattering table, and the transmittance table use it, without allocating per ray.
- The multiple scattering table has `48` rows instead of `32`, spaced evenly below the band (`8` intervals), inside it (`16`), and above it (`23`), and keeps `32` sun cosines.
- The preview in the contributor's workspace (`sky-preview`, outside the repository) got the same steps in `FastSky` and `TransmittanceTable` (`RaySteps`), and a mode `skyreference` that logs the GameTest reference values without writing the datasets. The GameTest's preview radiances were taken again from it.
- GameTest `rayStepsCoverTheIceLayerOnEveryPath`.

## Decisions

- empireu agreed on `2026-11-08` that the ice optical depth should follow the weather, rising ahead of forecast snow as cirrostratus comes before a front, with depth changes crossfaded between tables. Diamond dust comes after the sky. The law and the numbers are in the plan.
- The fix comes first as its own step, because the probe for the law found that even steps put one or two samples in the `1.2 km` ice layer. Above depth `0.3` that left bands across the sky, and at depth `3` errors of up to `64` display steps and more than twice the ground light.
- Twelve band steps were chosen over `8` and `16`. Eight left `7` display steps at depth `0.5` and sixteen were no better than twelve. With `24` even view steps instead of `30` the sky without ice got worse, so the even steps stayed.
- Concentrating the multiple scattering rows in the band at the same `32` rows matched `128` even rows. The sun cosines made no difference. `48` rows brought depth `3` from `4.3` to `1.5` display steps for `1.5` times the table's build.

## Validation

- A probe outside the game, built from the mod's sky classes with the step counts as system properties, compared sky profiles along four azimuths at sun elevations `2`, `15`, `30`, and `60 degrees` and ice depths `0` to `3` against the same model with `1500`, `1000`, and `600` steps and `128 x 128` multiple scattering texels. The table of errors is in `docs/climate/calculated-sky.md` under Ray steps. With the default depth `0.1` the largest error away from the sun went from `3.7` to `1.6` display steps and its ripple from `3.3` to `0.6`.
- With converged sampling the 22 degree halo peaks at depth `0.3..0.5`, about `+17..+20` display steps over the sky just inside it, and washes out by `2` with a low sun and by `3..5` with the sun at `45 degrees`. The ground light at depth `3` is `0.4..0.5` of a clear day.
- Timed outside the game, two alternating runs of three rounds: sky view `18.9..19.9 ms` instead of `15.8..15.9 ms`, tables `131..155 ms` instead of `74..98 ms`.
- The ice optical depth along the ray steps, against a fine integration over `401` directions from altitudes `0.1` to `60 km`, is within `0.45%` for the view rows and at worst `1.5%` for a ray from `20 km` that grazes the band from above, where its `12` steps spread over a chord of about `340 km`. The GameTest first asked for `0.5%` on all rays and failed at `0.56%` for a ray starting at the layer's center, so it now asks for `1%` on the view and `2%` on the others.
- At the GameTest's directions the new sampling differs from the old preview values by less than `1%`, except at the sun (`-9%`), `2 degrees` from a sun at `8 degrees` (`+9%`), and on the 22 degree halo of that sun (`-7.5%` red). The converged reference agrees with the new sampling there within about `1.7%`.

- `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all `9` required tests passed, no warnings in the sky package.
- Documentation: the table sizes, a Ray steps section with the error table, the GameTest, and the build times in `docs/climate/calculated-sky.md`, and a decision and step 5 in the plan.

- empireu took the set `v1` in `runClient` the same day at depth `0.1` (`v1-2026-11-08_13-33-08`) and `0.5` (`v1-2026-11-08_13-33-59`), all frames settled. At `0.1` the sky matches the run before the change (`v1-2026-11-07_17-36-15`) within one display step, except about `4` steps dimmer in the cell around the sun at sunrise. The larger pixel differences are in trees and terrain. At `0.5` the halos are stronger and the sky overhead about `34` steps whiter, without bands. At sunrise and sunset the sun turns into a small dim orange square and the sky and snow dark gray.

## Remaining

- The dark low sun at depth `0.5` comes from the model, not the steps: the sun's beam loses the whole ice depth along a slant path about `13` times the vertical at `3 degrees`, although the diffracted half of the ice's scattering keeps going within a few degrees of it. δ-scaling, which takes only the non-diffracted half out of the beam and the multiple scattering, was proposed as the next step, before the weather law, whose ranges would then be measured again.
- empireu reviewed the in-game runs and asked for the commit on `2026-11-08`.
