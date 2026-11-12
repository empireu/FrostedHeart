# Calculated Sky

- Status: `Partial`
- Last verified: `2026-11-11`, against source and the GameTests below. The refactor of that day was checked by the GameTests, by eye in `runClient`, and in its last step by a byte comparison of the tables, views, and display colors outside the game. The ray steps across the ice band were checked against a converged reference outside the game that day, and delta scaled ice and the transmittance rows in the ice band against the preview's path tracer and a converged table. The diffuse totals of the multiple scattering table were checked against the path tracer that day and have not been seen in game. Delta scaled ice was seen in `runClient` the same day at ice optical depths `0.1` and `0.5`. The first version, with one ice lobe and no snow fade, was seen in `runClient` on `2026-11-05`. The three-part ice phase and a snow fade toward gray were seen in `runClient` on `2026-11-06`, and the fog color from the sky and the snow fade toward the fog color later that day. The ice crystal halos and the sky texels were first seen in `runClient` on `2026-11-07`, with no popping as the sun moves. The sky's own sun, which replaces vanilla's, and the blended sky views were seen in `runClient` the same day. The screenshot harness took its first sets in `runClient` the same day, the last one recorded with `beginSet` and `pose`. The daylight on the terrain was seen in `runClient` the same day, with torch light unchanged.
- Scope: the optional overworld sky color calculated from air, titanium dioxide nanobots, and ice crystals, its halo display and sun, the fog color taken from it outside snow, the sky light of the lightmap taken from it outside rain, the client config and client command, and a screenshot harness for development. Clouds are unchanged.
- Primary code anchors: `CalculatedSkyRenderer`, `SkyFrame`, `SkyBuilder`, `SkyState`, `SkyTextures`, `SkyGlPass`, `CalculatedSkyEffects`, `CalculatedSkyClientCommand`, `CalculatedSkyScreenshots`, `SkyTables`, `SkyView`, `SkyViewSet`, `SkyDisplay`, `BandRows`, `SkyAtmosphere`, `SkyParticleOptics`, `SkyPhase`, `SkySetting`, `SkySettings`, `IceWeatherLaw`, `IceWeatherDepth`, `IceDepthGrid`, `DiamondDustLaw`, `DiamondDustExtinction`, `IceMirror`, `LightPillarOptics`, `WorldClimate.getLastSnowEndHour`, `WorldClimate.getNextSnowStartHour`, `FHClimatePacket`, `IceCrystalData`, `IceHaloProfile`, `IceHaloMap`, `IceHaloFormulas`, `IcePrism`, `LevelRendererMixin.frostedheart$drawCalculatedSky`, `LevelRendererMixin.frostedheart$hideVanillaSun`, `FogRendererMixin.frostedheart$useCalculatedSkyFogColor`, `FHClientEventsMod.registerDimensionEffects`, `assets/frostedheart/shaders/calculated_sky.fsh`, `assets/frostedheart/datasets/ice_halos`

The feature is off by default. The plan and later stages are in [the calculated sky plan](../../plans/2026-11-05_21-16-30_calculated-sky.md).

## 1. Model

The sky is a sphere of radius `6360 km` with an atmosphere up to `6460 km`, seen from `100 m` above snow. Each color channel is one band, centered at `680`, `550`, and `440 nm`.

| Layer | Profile | Source of its numbers |
|---|---|---|
| Air (Rayleigh) | Exponential, scale height `8 km` | Hillaire 2020, Table 1 |
| Ozone | Tent, peak at `25 km`, half width `15 km` | Hillaire 2020, Table 1 |
| Ground aerosol | Exponential, scale height `1.2 km`, asymmetry `0.8` | Hillaire 2020, Table 1 |
| Nanobots | Gaussian, center `25 km`, width `6 km` | Mie theory for rutile spheres, sizes from `design/world-design.md` |
| Ice crystals | Gaussian, center `11 km`, width `1.2 km`, phase function from hexagonal crystals (below) | Ray traced prisms, tables in `datasets/ice_halos` |

`SkyParticleOptics.forDistribution` averages Mie efficiencies over a log-normal size distribution truncated to `50..1000 nm` and over nine wavelengths per band. The refractive index is the rutile formula `n^2 = 5.913 + 0.2441 / (lambda^2 - 0.0803)` with `lambda` in micrometers. Nanobots do not absorb.

`SkyTables` follows Hillaire 2020: a transmittance table (`256 x 128`, `40` steps), a multiple scattering table (`48` altitudes by `32` sun cosines, `20` steps, `64` directions), and a sky view table (`200 x 100`, `30` steps). Every ray also gets `12` steps of its own across each crossing of the ice band, as described under [Ray steps](#ray-steps). Multiple scattering is computed over a black ground. Light reflected back and forth between snow and atmosphere is added afterwards:

```text
L(view)  = L_black_ground(view) + L_ground * G(view)
L_ground = (A / pi) * E_0 / (1 - A * S)
S        = 2 * integral over mu from 0 to 1 of G(mu) * mu
```

| Symbol | Meaning | Value |
|---|---|---|
| `A` | Snow albedo | `0.8`, source constant `SkyAtmosphere.GROUND_ALBEDO` |
| `E_0` | Downward irradiance on a black ground: direct sun through delta scaled ice, the sky view table, and single scattering by ice crystals without the diffraction peak (`IceHaloProfile.irradiance` plus `IceHaloMap.irradiance`) | Computed per sky view |
| `G(view)` | Radiance at the camera per unit radiance of glowing ground | Table of `65` view cosines |
| `S` | Spherical albedo of the atmosphere | Computed per settings |

Radiance is relative to a sun of irradiance `1` in each band. The shader displays it as `1 - exp(-exposure * L)`, encoded to sRGB.

### Ray steps

The ice layer is only `1.2 km` wide, so even steps over a ray to the top of the atmosphere put one or two samples in it, about `3.3 km` apart looking straight up. `SkyRaySteps.fill` therefore splits each ray where it enters and leaves the ice band, the shell between `SkyAtmosphere.ICE_BAND_LOW_RADIUS` and `ICE_BAND_HIGH_RADIUS`, four widths below and above the layer's center (`6.2..15.8 km`). Each crossing of the band gets `12` equal steps. Outside it the steps are the cells of the even grid over the whole ray, cut at the band's edges, so the sky without ice samples as before. The transmittance table, the multiple scattering table, `G`, and the view rays all use it. The rows of the multiple scattering table are spaced evenly below the band (`8` intervals), inside it (`16`), and above it (`23`), because the light inside a thick ice layer changes over a few hundred meters. The rows of the transmittance table are spaced evenly in the distance to the horizon, `sqrt(r^2 - R^2)`, below the band (`24` intervals), inside it (`64`), and above it (`39`), for the same reason. Both tables map rows with `BandRows`.

Measured outside the game on `2026-11-08` against the same model with `1500` view steps, `1000` multiple scattering steps, `600` transmittance steps, and `128 x 128` multiple scattering texels, along four azimuths from the sun at sun elevations `2`, `15`, `30`, and `60 degrees`, leaving out `5 degrees` around the sun, in display steps of `255` at exposure `6`:

| Ice optical depth | Even steps: largest error, ripple | Ray steps: largest error, ripple |
|---|---|---|
| `0` | `2.1`, `1.3` | `2.0`, `1.2` |
| `0.1` | `3.7`, `3.3` | `1.6`, `0.6` |
| `0.5` | `13.4`, `9.5` | `1.1`, `0.7` |
| `1` | `27.4`, `15.1` | `2.1`, `1.5` |
| `3` | `64.2`, `33.7` | `1.5`, `1.0` |

The ripple is the error minus its average over `12 degrees` of elevation, which shows as bands across the sky. With even steps the ground light at depth `3` was more than twice the reference, and with ray steps it is within `2%`. Near the sun the ice's single scattering changes most: with the default depth `0.1`, the radiance at the sun moves by `-9%` and `2 degrees` from a sun at `8 degrees` by `+9%`, within about `1.7%` of the reference.

The transmittance table had `64` even rows until the change to [delta scaled ice](#delta-scaled-ice), about `1 km` of altitude apart in the ice layer. Interpolating `exp(-tau)` linearly between them overestimates the sunlight inside a thick layer. Measured the same way on `2026-11-08` with delta scaled ice, against `1024` even rows, with `256` columns, which agree with `1024` columns within `0.1` display steps:

| Ice optical depth | `64` even rows | `64` rows, `32` in the band | `128` rows, `64` in the band |
|---|---|---|---|
| `0.5` | `1.3` | `1.2` | `0.2` |
| `1` | `1.9` | `1.1` | `0.2` |
| `3` | `5.5`, ground `+4.8%` | `0.8`, ground `+2.8%` | `0.3`, ground `+0.5%` |

With only `64` rows the band took too many from the air, and the sky without ice was off by `1.3` steps. Within a few degrees of a low sun the error is larger: at depth `0.5` with the sun at `3.7 degrees`, `64` even rows overestimated the light that the sun's square and its glow carry by `6..7%`, and `128` rows by `3..4%`.

### Ice crystals and halos

The sky view table holds no single scattering by ice. A second table, the ice view, holds that light per unit phase function, and the ice phase function is applied per sky texel:

```text
L_ice(view) = ice_view(view) * (crystals(angle from sun) + w(row) * diffraction(angle from sun) + map(view, sun elevation))
```

Half of the light ice removes is diffracted around the sun, as Henyey-Greenstein with asymmetry `0.99` (`ICE_DIFFRACTION_FRACTION`, `ICE_DIFFRACTION_ASYMMETRY`), and `w` is the diffraction weight described under [Delta scaled ice](#delta-scaled-ice). The other half passes through the crystals of `datasets/ice_halos/crystals.csv`:

| Crystal kind | Share | Shape (half height over radius) | Orientation | Gives |
|---|---:|---:|---|---|
| Tumbling crystals | `40%` | `1.0` | Random | 22 and 46 degree halos |
| Plates | `25%` | `0.15` | Axis vertical, tilt `0.7 degrees` | Sun dogs, parhelic circle, circumzenithal arc |
| Wobbling plates | `5%` | `0.15` | Axis vertical, tilt `5 degrees` | Wider, fainter sun dogs |
| Columns | `20%` | `2.0` | Axis horizontal, tilt `0.7 degrees` | Tangent arcs, circumscribed halo, parhelic circle |
| Parry columns | `10%` | `1.5` | Axis horizontal, two side faces horizontal, tilt `0.5`, turn `1 degree` | Parry arcs |

The mix was chosen for a rich display. The ice refractive index is `n = 1.3005 + 0.0030 / lambda^2`, `1.3070`, `1.3104`, and `1.3160` at the band centers.

`IceHaloProfile` holds the tumbling crystals and, in a row of its own, the diffraction peak as a function of the angle from the sun, in `3600` bins of `0.05 degrees`, from `scattering_tumbling_crystals.csv`. It is built when a setting changes.

`IceHaloMap` holds the oriented crystals for one sun elevation, in `360` columns of azimuth from the sun (`0..180 degrees`, the display is mirror symmetric) by `184` rows of elevation from `-2 degrees` to the zenith, both in `0.5 degree` bins. `IceHaloFormulas` fills it from formulas, so no rays are traced in game:

- light through side faces lies on the cone around the crystal axis through the sun, at the in-plane deviation of `side_faces.csv` for Bravais' effective index `n' = sqrt(n^2 - a^2) / sqrt(1 - a^2)`, where `a` is the cosine between sun and axis. Light with an odd number of end face reflections uses the sun's mirror image in the end faces;
- Parry columns have a fixed turn, so `IcePrism.trace` follows their light through the hexagon per axis;
- light in through an end face and out through a side face is refracted exactly in 3D, over `360` face directions, or the six faces of a Parry column;
- light from end face to end face is followed through its side face reflections by `IcePrism.traceMirrors`;
- the share of light on each path comes from `paths_<crystal>.csv`, by whole degree of sun elevation, interpolated. Light on other paths is put at the sun.

A reflection off a side face maps a direction angle `a` inside the hexagon to `2 * normal angle + pi - a`, so after any number of reflections light moves in one of six direction classes per entry face: odd or even reflection count, turned by `0`, `120`, or `240 degrees`. The exit direction depends only on the class and the exit face, not on where the light entered. `IcePrism` keys its outputs by class and exit face and merges those of one trace call, which cut the points written per map from about `1.1 million` to about `150,000` with the same sampling.

Axes are sampled on a jittered grid (plates, up to `196` axes, tilt step `0.5 degrees`) or every `0.25 degrees` of azimuth (columns, `720` axes), weighted by their projected side area. The side face deviation is stepped every `0.25 degrees`, two steps per column of `side_faces.csv`. End face entries are sampled `32` times per axis, `64` for Parry columns. This sampling is dense enough for `0.5 degree` texels; coarser sampling shows as colored dots and streaks along the arcs. Random numbers come from SplitMix64 seeded per axis, so a map does not depend on timing.

Both are blurred by a Gaussian of `sqrt((0.45 * texel)^2 + (sun radius / 2)^2)` with a sun radius of `0.2665 degrees`, so arcs stay about equally bright as they move across texels. Texels below `0.5 degrees`, and the smooth sky, are blurred as `0.5 degrees`, the size of the map's bins (`IceHaloMap.blurSigma`). Oriented light below `-2 degrees` is dropped, so the map keeps `0.20` (sun at `8 degrees`) to `0.25` (sun at `30 degrees`) of the `0.30` the oriented crystals scatter.

The tables were written by the offline sky preview, as described in `datasets/ice_halos/src.md`.

### Delta scaled ice

Diffraction sends light on within a few degrees of its direction, so for everything but the view toward the sun it is as if the light had passed. `SkyAtmosphere` therefore counts only `(1 - f) * beta_ice` in its scattering and extinction totals, where `f` is `ICE_DIFFRACTION_FRACTION` and `beta_ice` the ice's scattering coefficient, and keeps all of `beta_ice` in `iceScattering`. The transmittance table, `G`, and the view rays use the totals, and the multiple scattering table the diffuse totals described under [Forward scattering in the multiple scattering table](#forward-scattering-in-the-multiple-scattering-table), so the sun's beam, the light between scatterings, and the sky seen through ice lose only the ice's other half. The ice view still holds all of the ice's single scattering, so the diffraction peak still draws the glow around the sun. Three things keep diffracted light from counting twice:

- The sun's square uses the full ice extinction, `T(row) * exp(-f * tau(row))`, where `T` is the transmittance of the row's view ray and `tau` the ice optical depth along it. The light diffracted out of the square is in the glow.
- The glow's single scattering uses delta scaled transmittance, which keeps light that is diffracted again in the beam, so light diffracted `n` times would count `n` times. `SkyTables.diffractionWeights` scales the peak per row by `w = (1 - exp(-f * tau)) / (f * tau)`, which leaves the light diffracted at least once. Toward the sun, the square and the glow then carry `T(row)` between them. Rows that hit the ground take the weight of the lowest row of sky, so it stays smooth where the shader interpolates across the horizon.
- `IceHaloProfile.irradiance` leaves the peak out of the light on the ground, since the direct sun through delta scaled ice already carries it.

Measured in the preview on `2026-11-08`, whose path tracer follows diffracted light as scattering, against the same model with the full ice extinction. The ice has the preview's generic phase function, half of it diffraction, with the default nanobots and snow. Mean color difference (CIE dE) over the sky above `2 degrees` at exposure `6`, and the radiance over the path tracer's:

| Ice, sun | dE, full extinction and delta scaled | Within `10 degrees` of the sun | Whole sky | Ground |
|---|---|---|---|---|
| `0.1`, `10` | `2.4`, `1.6` | `0.84..0.85`, `0.95..0.98` | `0.92..0.94`, `0.98` | `0.97..0.98`, `0.99` |
| `0.5`, `3` | `3.6`, `1.8` | `0.28..0.63`, `0.81..0.99` | `0.76..0.82`, `0.92` | `0.85..0.87`, `0.94..0.95` |
| `0.5`, `10` | `5.6`, `2.9` | `0.44..0.52`, `0.92..0.95` | `0.72..0.81`, `0.94` | `0.87..0.88`, `0.96..1.00` |
| `0.5`, `30` | `6.0`, `3.3` | `0.77..0.80`, `0.98..1.00` | `1.02..1.03`, `1.04..1.05` | `1.05..1.09`, `1.05..1.07` |
| `2`, `3` | `8.0`, `3.3` | `0.38..0.43`, `0.59..0.66` | `0.51..0.53`, `0.77..0.79` | `0.54..0.57`, `0.79..0.82` |
| `2`, `10` | `11.4`, `4.7` | `0.31..0.42`, `0.74..0.77` | `0.52..0.54`, `0.79..0.82` | `0.56..0.60`, `0.83..0.88` |
| `2`, `30` | `7.9`, `3.8` | `0.37..0.39`, `0.95..0.97` | `0.69..0.71`, `0.96..0.99` | `0.75..0.78`, `1.00..1.02` |

Two seeds of the path tracer at ice `2` and sun `10 degrees` differ by a mean dE of `2.2` and up to `6%` within `10 degrees` of the sun, so part of what remains is the reference's own noise. The rest grows with the ice and a low sun, where the multiple scattering table treated repeated scattering by ice as even in all directions, although the crystals scatter mostly forward. This table was measured before the change described in the next section.

In the mod, against the same code with the full ice extinction, the sky more than `5 degrees` from the sun changed by up to `19` display steps at the default depth `0.1`, in the glow around a low sun, and by `1..4` steps elsewhere. At depth `0.5` the glow around a sun at `2 degrees` grew by up to `122` steps, and a high sun's sky got `4..15` steps darker, because the full extinction had also sent the diffracted half into the even multiple scattering.

### Forward scattering in the multiple scattering table

Apart from diffraction, the crystals still send most of their light on within some tens of degrees of its direction. The tumbling crystals' phase has an asymmetry `g`, the mean cosine of the scattering angle, of `0.547`, `0.542`, and `0.533` in the three bands (`IceCrystalData.tumblingAsymmetry`). The multiple scattering table spreads light evenly in all directions at every scattering. With delta scaled ice alone it sent half of the ice's light back up near the top of a thick layer, where a low sun's beam ends, and too much of what snow reflected under a high sun came back down. `SkyAtmosphere` therefore keeps diffuse totals, `diffuseScattering` and `diffuseExtinction`, with only `(1 - g) * (1 - f) * beta_ice` of the ice, the similarity scaling of van de Hulst. The oriented crystals are taken to scatter like the tumbling ones. The multiple scattering table follows its rays through the diffuse totals, and its sunlight comes through a second transmittance table built from them, so light the ice scatters forward lights deeper layers like the sun's beam. The view rays, the sun's square, the ice view, the light ice scatters once onto the ground, `G`, and `S` keep the delta scaled totals.

Measured in the preview on `2026-11-08` (mode `bounce`) against its path tracer, with the generic phase (asymmetry `0.57` without diffraction), the default nanobots, and snow. Ground radiance under ice depths `1`, `2`, and `3` over the ground under depth `0.02`, green channel:

| Sun | Path tracer, mean of three seeds | Even scattering, before | Diffuse totals for `G` and `S` too | Diffuse totals for the table only, as built |
|---|---|---|---|---|
| `10` | `0.86`, `0.81`, `0.78` | `0.82`, `0.69`, `0.58` | `0.75`, `0.64`, `0.56` | `0.84`, `0.78`, `0.73` |
| `30` | `0.93`, `0.89`, `0.85` | `0.96`, `0.87`, `0.76` | `0.82`, `0.68`, `0.60` | `0.91`, `0.84`, `0.79` |
| `45` | `0.99`, `0.95`, `0.92` | `1.04`, `1.00`, `0.92` | `0.89`, `0.76`, `0.67` | `0.99`, `0.93`, `0.87` |
| `70` | `1.01`, `1.00`, `0.97` | `1.10`, `1.12`, `1.08` | `0.94`, `0.85`, `0.76` | `1.05`, `1.04`, `0.99` |

Three seeds of the path tracer differ by up to `0.10` (sun `30`, depth `2`, red), so the means carry about `3%` of noise (modes `bounce` and `groundreference`). Scaling `G` and `S` as well made every case too dark. Without ice, the fast sky's ground is `1..4%` above the path tracer's and its sky up to `10%` under a high sun, which these ratios divide out. Over the whole sky above the horizon, against the path tracer, the diffuse totals moved depth `3` under a sun at `10 degrees` from `0.70` to `0.91`, and depth `2` under a sun at `70 degrees` from `1.17` to `1.08`, where the sky without ice is at `1.09`.

The path tracer only knows the generic phase. With the mod's crystals, the preview's fast sky (mode `crystalbounce`) gives up to `8%` more light on the ground than with the generic phase under a sun at `70 degrees`, and up to `3%` less under a sun at `10 degrees`, and the mod matches it within `0.3%`. The mod, measured by `IceWeatherGameTests.groundUnderIceFollowsThePathTracer` on `2026-11-08`, red, green, and blue at depths `1`, `2`, and `3`:

| Sun | Depth `1` | Depth `2` | Depth `3` |
|---|---|---|---|
| `10` | `0.75`, `0.81`, `0.88` | `0.70`, `0.76`, `0.82` | `0.67`, `0.71`, `0.75` |
| `30` | `0.93`, `0.94`, `0.95` | `0.83`, `0.85`, `0.89` | `0.76`, `0.79`, `0.84` |
| `45` | `1.03`, `1.02`, `1.02` | `0.97`, `0.98`, `0.99` | `0.90`, `0.91`, `0.94` |
| `70` | `1.11`, `1.09`, `1.08` | `1.12`, `1.11`, `1.10` | `1.08`, `1.07`, `1.07` |

Before the change the mod gave `1.21`, `1.18`, and `1.15` at depth `2` under a sun at `70 degrees`. Under a high sun the daylight on the terrain stays at about `1.0` at every depth, because the display curve is saturated, and under a sun at `30 degrees` it falls from about `0.80` at depth `1` to `0.75` at depth `3`. Thick ice therefore barely dims a high sun's day, as in the path tracer. A gloomy day before snow would need lower, thicker clouds, which the model does not have.

### Sun

The sun is a square with the size and orientation of the core of vanilla's sun texture, its central `8` of `32` texels, which vanilla draws `60` wide at distance `100`. On the plane one unit in front of the camera its half side is `h = 0.075` (`SkyTables.SUN_HALF_TANGENT`), so it is about `8.6 degrees` across, with two edges along the sun's path and two from north to south. Its radiance spreads the sun's irradiance of `1` over its solid angle:

```text
L_sun(view) = T(elevation) / Omega
Omega       = 4 * atan(h^2 / sqrt(1 + 2 * h^2)) = 0.02237 sr
```

`T` is the transmittance from the camera to the top of the atmosphere at the view's elevation with the full ice extinction, the product over the steps of that row's view ray times `exp(-f * tau)`, as described under [Delta scaled ice](#delta-scaled-ice). `SkyTables.sunRadiance` holds `L_sun` per row of the sky view, built with the tables, and zero for rows that hit the ground. So each texel of the square has the color of its own elevation: as the sun sets, the bottom of the square reddens first and the horizon cuts it off. Below about `4.6 degrees` the shader's fade to the fog color dims it further.

With the default settings and exposure, the table displays white above about `8 degrees`, `(255, 237, 134)` at `4 degrees`, `(245, 148, 37)` at `2 degrees`, `(190, 63, 2)` at `0.5 degrees`, and `(154, 37, 0)` at the horizon. Its transmittance is within `1.6%` of a fine integration with the full ice extinction from `1 degree` up. The diffraction peak and the nanobots' forward scattering already make the sky white out to about `2..3 degrees` from a high sun, and about `230` of `255` at the square's edges, so a high sun shows as a white square with soft edges. From about `10 degrees` down, the square stands out clearly against the sky.

### Light pillars

Nothing draws light pillars yet. Their optics are in `IceMirror` and `LightPillarOptics`, for the diamond dust's plate crystals, which float nearly level so that their two large faces mirror a light below them to the eye. Each point of the pillar comes from the crystals at one height. With heights relative to the eye, a light at horizontal distance `d` and height `z_S`, and crystals at height `z`:

```text
tan(e) = (2 z - z_S) / d                  (LightPillarOptics.mirrorElevation, mirrorHeight)
x      = d z / (2 z - z_S)                (crystalDistance, horizontal distance from the eye to the crystals)
s      = 2 sigma sin(e) / (1 + r_2 / r_1)  (lateralSpread, r_2 / r_1 = z / (z - z_S))
```

`e` is the elevation of the point of the pillar and `s` the standard deviation across it on the sky. `sigma` is `tilt_degrees` of the plate crystals in `crystals.csv`, `0.7` for plates and `5` for wobbling plates. A plate reflects `R = F_s / (1 + F_s) + F_p / (1 + F_p)` of the light, from both faces with the light between them, where `F_s` and `F_p` are the Fresnel reflectances of one face for the two polarizations (`IceMirror.slabReflectance`). With the refractive index `1.31`, `R` is `0.944` at a grazing angle of `1 degree`, `0.499` at `10`, `0.217` at `20`, `0.101` at `30`, and `0.035` at `90`, so pillars are brightest at the bottom. Face normals spread as `p(tilt) = exp(-tilt^2 / (2 sigma^2)) / (2 pi sigma^2)` per steradian (`IceMirror.tiltDensity`).

The radiance toward a direction at elevation `e` and lateral angle `psi` from the pillar's plane, per unit intensity `I` of the light and unit face area per volume `a_f`, is the reflection integrated along the view ray. Taking the along tilt `tau` of the face that mirrors the light as the variable, with `beta_S = atan2(z_S, d)` and `q = sqrt(d^2 + z_S^2) sin(e - beta_S)`, the distance from the light to the view ray:

```text
L / (I a_f) = 1 / (2 q) * integral of R(e - tau) p(sqrt(tau^2 + t^2)) d tau
t           = psi (1 + r_2 / r_1) / (sin(e - 2 tau) + sin(e)),   r_2 / r_1 = sin(e - 2 tau + beta_S) / sin(e - beta_S)
```

`tau` runs from `max(-4 sigma, e - 90 degrees)` to `min(4 sigma, (e + beta_S) / 2)`. At `e - 90 degrees` the grazing angle reaches `90 degrees`, and `(e + beta_S) / 2` is the crystals at the eye, which cuts the window at the base of a pillar. `LightPillarOptics.radiance` takes the integral with the `8` point Gauss Legendre rule. Where the window of tilts is short against the ray, it is about the closed form `I a_f R(e) / (2 d) * exp(-psi^2 / (2 s^2)) / (sqrt(2 pi) s)`. That closed form, which an earlier plan proposed, is within `0.2%` for plates from `10 degrees` up, but `27%` too bright where the crystals come close to the eye (a light `300` blocks away and `20` below the eye, at `5 degrees`) and up to `2.3` times too bright at the base of a pillar of wobbling plates, measured outside the game on `2026-11-11` against the integral along the ray. The integral over `tau` removes both, so the base needs no clamp. Brightness falls with `1 / d`, so distant lights still make pillars. The result is `0` where no crystal above both the eye and the light can mirror it, and the face area per volume is taken as the same at every height along the ray.

## 2. Runtime

| Step | Where | When |
|---|---|---|
| Transmittance, multiple scattering, `G`, `S`, view ray steps, sun radiance | `new SkyTables(settings)` on `Util.backgroundExecutor()` | For ice optical depths on `IceDepthGrid`. The two around the drawn depth are kept, and when the depth leaves them the missing one is built. Both are built again when a setting that rebuilds tables changes |
| Halo profile | `new IceHaloProfile` on `Util.backgroundExecutor()` | On the first build, and when a setting that rebuilds halos changes |
| Halo maps | `new IceHaloMap` on `Util.backgroundExecutor()` | For sun elevations on a `0.5 degree` grid from `-6` to `90 degrees`. The two around the sun are kept, and when the sun leaves them the missing one is built. Both are built again when a setting that rebuilds halos changes |
| Sky views and ice views | `SkyTables.computeView` on `Util.backgroundExecutor()` | For sun elevations on a `0.25 degree` grid from `-18` to `90 degrees`, with the tables of each of the two depths. The four around the sun and the depth are kept, and when either leaves them the missing ones are built. All four are built again when the tables or the halo maps are |
| Upload as `RGB16F` textures | `SkyTextures.uploadTable`, render thread | When a build finishes, only content that no texture holds yet. A view or halo map that moves from the high end to the low end keeps its texture (`reassignSlots`) |
| Draw | `calculated_sky.fsh` on the full-screen quad of `SkyGlPass`, render thread | Every frame while drawn |

What the sky is drawn from in a frame is worked out once, at its first use, in `SkyFrame`: the sun's elevation and direction, the ice optical depth from `IceWeatherDepth.update`, the grid indices they call for, the exposure, and the rain and thunder levels. The lightmap's daylight, the fog color, the draw, and `CalculatedSkyRenderer.isSettled` read it. A Forge `RenderTickEvent` at the start of each frame marks it stale, and a call with another partial tick fills it again. The blend weights between views, halo maps, and depths are taken at each use, because a build that finishes in `CalculatedSkyRenderer.render` changes the state after the daylight and the fog color of the same frame used the previous one.

`SkyBuilder` runs the builds, one at a time on one thread, and needs no GL. `CalculatedSkyRenderer.updateTables`, called from `render`, hands each finished `SkyState` to `SkyTextures.upload` and then to `SkyBuilder.install`, which keeps the halo maps and views the new state no longer uses for the next builds to fill. The draw saves and restores the GL state it changes through `SkyGlPass`, draws one static `VertexBuffer` quad, and sets the samplers and constant uniforms once per shader program and the others with raw `glUniform` calls, so its own code allocates nothing per frame. Measured in `runClient` on `2026-11-11` with a temporary probe over many reports of `1000` frames: the draw, the fog color, and the daylight allocated `0` bytes once warm. Some earlier reports showed `24` bytes per draw, likely a mixin callback inside a `GlStateManager` call that the JIT later removes. This was not traced further. The draw uses `12` texture units, which is as many as `GlStateManager` caches: four sky views, four ice views, two halo maps, the halo profile, and one texture with the sun's radiance and the diffraction weights of both depths in four columns. The sky view is mirror symmetric around the sun's azimuth, so only half of it is integrated. `IceCrystalData` reads the datasets once, on the first build, through `ModList.getModFileById("frostedheart")`. Below a sun elevation of `-6 degrees` the halo maps stay as they are, since the ice view is dark then.

`LevelRendererMixin.frostedheart$drawCalculatedSky` injects after the first `VertexBuffer.unbind()` in `LevelRenderer.renderSky`, which is right after vanilla draws its sky dome. Vanilla still draws the moon, stars, and the dark dome below the horizon on top. Vanilla's own exits for fog, lava, powder snow, blindness, and darkness still apply.

`LevelRendererMixin.frostedheart$hideVanillaSun` replaces the sun's half size `30.0F` in `renderSky` with `0` when the calculated sky drew its sun in the same call (`CalculatedSkyRenderer.isSunDrawn`), so vanilla's sun quad has no area. The flag is cleared at the start of every `CalculatedSkyRenderer.render` and set after a successful draw, so in every case below where the calculated sky is skipped, vanilla draws its sun. Oculus `1.7.0` hides the sun for shader packs that turn it off by emptying the vertex builder before `BufferBuilder.end()`, and Embeddium `0.3.31` injects only at the head of `renderSky`, so neither conflicts with the constant.

The shader:

- snaps each pixel's view direction to the center of its sky texel. Texels are the cells of an equi-angular cube map aligned with the world axes, with `round(90 / texelDegrees)` cells along each face edge, so they stay fixed to the sky as the camera turns, like the sun's texture. Minecraft's sun moves in the x-y plane, so its path runs down the middle of the `+x`, top, and `-x` faces. With `texelDegrees` `0` it uses the pixel's own direction;
- looks up the sky views and the ice views by azimuth from the sun and by the latitude mapping of Hillaire 2020, as `SkyView.sample` does, and blends the two around the sun by its exact elevation, then the two depths by the exact ice optical depth (`IceDepthGrid.blendWeight`). Without the sun blend, each new view shifted the glow around a low sun by up to `16` display steps of `255` every `0.25 degrees`. With it, the sky stays within `1` step of a view computed for the exact elevation, measured halfway between two views at sun elevations from `1.4` to `30 degrees` and ice optical depths `0.1` and `0.3`;
- adds the ice view times the halo profile at the angle from the sun and the two halo maps, blended by the exact sun elevation between their two elevations. The profile's diffraction peak is scaled by the diffraction weight at the row of the sky texel, each depth's weight times its own ice view before the depth blend, so the result is the blend of the two depths' whole radiance;
- adds the sun's radiance at the row of the sky texel, blended between the two depths, times the part of the pixel the sun's square covers. The outline uses the pixel's own direction, with one pixel of smoothing, so the sun glides with sharp edges like vanilla's sun quad, while its color follows the sky texels. Edges on sky texels had to step one texel about every `1.7 s` or fade whole rows of texels in and out, which looked like the square changing shape;
- grays the color by rain and thunder level with the formula of `ClientLevel.getSkyColor`, without the lightning flash, so the sky darkens like vanilla's;
- fades the color toward the fog color by the rain level. In snow `FogModification` sets the fog color, so the sky becomes the pack's snow fog. `PlayerWeatherCompatibilityModel` sets the rain level to `0.8` in snow and blizzards, so a fifth of the sky stays, with faint halos, as a fifth of vanilla's sun does;
- fades to the fog color from the horizon up to a texel direction with `y = 0.08`, about `4.6 degrees`, so terrain fog meets the sky without a seam;
- adds dithering of half a display step against banding, one value per texel so texels stay flat.

`CalculatedSkyEffects` replaces the overworld `DimensionSpecialEffects` through `RegisterDimensionSpecialEffectsEvent`. While the sky is drawn, `getSunriseColor` returns `null`, which removes vanilla's sunrise fan and its tint of the fog color (`FogRenderer.setupColor`). Otherwise it returns the vanilla value. Its `adjustLightmapColors` lights the terrain, as described under [Daylight](#daylight).

### Fog color

`FogRendererMixin.frostedheart$useCalculatedSkyFogColor` injects into `FogRenderer.setupColor` before its call to `getPriorityFogFunction`. While the calculated sky is drawn and the camera is not in a fluid, it sets the fog color to `CalculatedSkyRenderer.fogColor`, which comes from `SkyViewSet.horizonDisplayColor`:

- the sky view table at elevation `0` in the azimuth the camera looks. It has no single scattering by ice, so the color stays smooth near the sun and across halo arcs;
- blended toward `SkyView.meanHorizonRadiance`, the horizon averaged over `64` azimuths, by `1 - h`, where `h` is the length of the look vector's horizontal part, so looking straight up does not pick a direction at random;
- blended between the four views around the sun and the ice depth and then tone mapped and encoded (`SkyDisplay.displayValue`), and grayed by rain and thunder as the shader does.

This replaces vanilla's color from sky, biome fog, sunrise, render distance, and weather, which it computes before that call. Vanilla still applies what comes after: darkening for blindness, darkness, the void below `minimum height + 32`, and bosses, then night vision. `ViewportEvent.ComputeFogColor` fires after that, so `FogModification.renderFogColors` still sets its snow fog color whenever its fog density is above zero. Embeddium redirects the biome fog sampling earlier in `setupColor`, and Oculus injects at its tail, so neither conflicts. The fog color is also the clear color behind the world and the color the shader fades to at the horizon.

The calculated sky is skipped, and vanilla draws the sky as before, when:

- `CalculatedSky.enabled` is false;
- the level is not `minecraft:overworld`;
- an Oculus shader pack is in use (`IrisApi.isShaderPackInUse`, only called when Oculus is loaded);
- the camera is inside a fluid;
- no table has been uploaded yet, for the first second or so after enabling;
- building or drawing threw an exception, including a missing or broken dataset. It is logged once, and turning the sky on again with the command retries. Without the command, only a restart does.

### Ice optical depth from the weather

With `iceFollowsWeather` on, the default, `IceWeatherDepth.update` sets the ice optical depth every frame from `IceWeatherLaw`, so cirrostratus thickens ahead of snow and halos foretell it. Hours are climate hours, `50 s` each at normal speed, and snow includes storms.

| Weather | Target depth |
|---|---|
| No snow within `24` hours before or `6` hours after | `0.02 + 0.13 h / 50` (`IceWeatherLaw.clearDepth`), from the day's humidity `h` (`0..50`, `WorldClimate.getHumidity`) |
| `12` hours before snow | `0.4` |
| `6` hours before snow | `1` |
| When snow starts and while it snows (`ClientClimateData.climate`) | `3` |
| `6` hours after snow ends | the clear depth again |

Between these points the target changes evenly in its logarithm, and when the ramp before one snow and the recovery after another overlap, the larger applies. The drawn depth follows the target over the client's climate clock (`ClientWeatherState.frameClimateSeconds`), closing all but `1/e` of the gap in its logarithm per climate hour (`IceWeatherLaw.smoothedDepth`). The day's humidity jumps at midnight and `/time` jumps the clock, and both become a glide or, for a jump of hours, a change at once. A clock that goes back also sets the depth at once.

The client learns about snow from `FHClimatePacket`, which carries `lastSnowEndHour` and `nextSnowStartHour` as absolute climate hours, or `-1` for none (`ClientClimateData`). The server fills them with `WorldClimate.getLastSnowEndHour` and `getNextSnowStartHour`, which search the `24` hours before and after the current hour, not the current hour itself, in the hourly climate of `dailyTempData` merged with a white curtain that covers the player's chunk, sampled in the middle of each hour. The packet goes out every climate hour, after a jump of the clock, and on login, respawn, and dimension change, so after a relog the depth continues where the weather has it. The forecast reaches every player through the sky, whether or not they have the forecast research.

The sky for a depth between two grid depths of `IceDepthGrid`, `S (exp(0.2 i) - 1)` for `i` from `0` to `21` with `S = 3 / (exp(4.2) - 1) = 0.0457`, is the linear blend in depth of the radiance of the two depths' views. The grid steps by about `0.009` near `0` and by a ratio of `exp(0.2) = 1.22` above `S`, and its last depth is the maximum of `SkySetting.ICE_OPTICAL_DEPTH`, `3`, so the depth while it snows is drawn from the tables of a grid depth. A new end needs tables (about `150 ms`) and two views. In the last `12` hours before snow the ramp crosses a grid depth about every `55..75 s` at normal speed, and before that every `45..160 s` depending on the humidity. The recovery after snow, from `3` to a clear depth of `0.05` in `6` hours, crosses one about every `17 s`. While a build is pending the blend weight stops at the end of the cell, so the depth waits there instead of jumping. With `iceFollowsWeather` off, the depth is `iceOpticalDepth`, drawn through the same grid.

### Diamond dust from the weather

Diamond dust is ice crystals in clear, calm, cold air near the ground, and here it follows each snow. With `dustFollowsWeather` on, the default, `DiamondDustExtinction.update` gives its extinction at the ground from `DiamondDustLaw`, in `1/km`, with the diffracted light counted as transmitted as for the ice layer. Nothing draws it yet. Only the command's status reads it.

| Weather | Target extinction |
|---|---|
| While it snows or storms (`ClientClimateData.climate`) | `0` |
| From the end of the snow to `1` hour after | rising evenly from `0` to `1 per km` (`DiamondDustLaw.FULL_EXTINCTION`) |
| `1` to `12` hours after | `1 per km` |
| `12` to `24` hours after | falling evenly to `0` |
| No snow in the last `24` hours | `0` |

The hours since snow come from `ClientClimateData.lastSnowEndHour`, as for the ice, and the dust ends at `WorldClimate.SNOW_SEARCH_HOURS`, where the client's knowledge of past snow ends. The extinction follows the target over the client's climate clock, closing all but `1/e` of the gap per climate hour (`DiamondDustLaw.smoothedExtinction`, evenly in the extinction, since the target reaches `0`). A clock that goes back sets it at once. Temperature and wind are not used. Snow stops once the climate temperature rises above `-13` (`WorldTemperature.SNOW_REACHES_GROUND`), and a cold event returns to `0` soon after, about `4` climate hours after the opening storm, so a limit on temperature would cut the dust short. The wind of `WorldClimate` follows only the weather type, settling toward `30` after a blizzard and toward `0` after other snow. With `dustFollowsWeather` off, the extinction is `dustExtinction`.

### Daylight

`CalculatedSkyEffects.adjustLightmapColors` replaces the sky light in the lightmap of `LightTexture.updateLightTexture` while the calculated sky is drawn. Vanilla's sky light at full sky light is `V = t * (0.95 d + 0.05)`, where `d` is `ClientLevel.getSkyDarken` (`0.2` at night, `1` by day, lower in rain and thunder) and the tint `t` is `d + 0.35 (1 - d)` in red and green and `1` in blue (`SkyView.vanillaSkyLight`). At sky light level `i` it is multiplied by `LightTexture.getBrightness(i)`. `SkyView.daylightColor` gives the color `D` that takes its place:

- `C = encode(1 - exp(-k L))` in each channel, where `L` is `SkyView.groundRadiance` blended between the four views around the sun and the ice depth, `k` is `exposure`, and `encode` is the sRGB encoding (`SkyDisplay.encodeSrgb`). `L` is the light that snow of albedo `0.8` sends back, from the sun, the sky, ice, and light reflected between snow and atmosphere, so `C` is the snow as the sky displays it below the horizon;
- `C` is divided by the larger of its brightest channel and `encode(1 - exp(-k 0.8 / pi))`, snow under a sun of irradiance `1` at the zenith, so noon is close to white and no channel passes `1`;
- each channel is raised to at least vanilla's night sky light, `V` at `d = 0.2`, which is `(0.1152, 0.1152, 0.24)`;
- the result is blended toward `V` by the rain level, so terrain in snow and storms is lit as in vanilla, as the sky fades to the fog there.

At the first texel of each lightmap update, `CalculatedSkyRenderer.daylightColor` computes `D`, and the hook adds `0.96 (D - V)` times vanilla's darkening during boss fights to every texel, times `getBrightness(i)`. The factor `0.96` and the darkening are what vanilla applies to the sky light before the hook runs. Block light, night vision, the darkness effect, the brightness slider, and lightning flashes, during which the lightmap stays vanilla's, are unchanged. The hook applies under the same conditions as the sky, and also with the camera in a fluid. It changes only what the client draws, so mob spawning and light levels on the server stay as they are. One lightmap update costs one evaluation of `D` and `256` multiply-adds, without allocating.

With the default settings, outside the game on `2026-11-07`, against vanilla's `V` in clear weather (luminance with the weights `0.2126`, `0.7152`, `0.0722`):

| Sun | `D` | `V` | Luminance of `D` over `V` |
|---:|---|---|---:|
| `90` | `0.998 0.994 1.000` | `1 1 1` | `1.00` |
| `45` | `0.935 0.924 0.928` | `1 1 1` | `0.93` |
| `30` | `0.834 0.813 0.814` | `1 1 1` | `0.82` |
| `20` | `0.709 0.678 0.677` | `0.857 0.857 0.912` | `0.80` |
| `12` | `0.545 0.509 0.513` | `0.567 0.567 0.708` | `0.90` |
| `5` | `0.338 0.310 0.323` | `0.354 0.354 0.524` | `0.87` |
| `2` | `0.227 0.203 0.240` | `0.276 0.276 0.445` | `0.73` |
| `0` | `0.143 0.127 0.240` | `0.229 0.229 0.392` | `0.57` |
| `-2` | `0.115 0.115 0.240` | `0.186 0.186 0.339` | `0.63` |
| `-6` and below | `0.115 0.115 0.240` | `0.115 0.115 0.240` | `1.00` |

Vanilla turns the light blue from a sun at about `24 degrees` down, and `D` stays neutral to faintly warm until the night light's blue channel takes over at about `2 degrees`. With `exposure` `12`, `D` is `0.90` of `V` at `30 degrees`, `1.08` to `1.12` from `12` to `5 degrees`, and `0.76` at `0 degrees`.

## 3. Configuration

Client config, group `CalculatedSky`. `enabled` is `FHConfig.Client.calculatedSky`. Every other key is a constant of the enum `SkySetting`, which holds its key, comment, default, range, and what a change rebuilds, and `FHConfig` defines them all with `SkySetting.define`. The command, its status, `SkySettings`, and the screenshot harness read that table, so a new setting is one constant. `SkySettings` holds a value of every setting, with a switch as `1` or `0`, and is what a build is asked for.

| Key | Java anchor | Default | Range | A change rebuilds |
|---|---|---:|---|---|
| `enabled` | `FHConfig.Client.calculatedSky` | `false` | | |
| `exposure` | `SkySetting.EXPOSURE` | `6.0` | `0.5..50` | nothing |
| `nanobotOpticalDepth` | `SkySetting.NANOBOT_OPTICAL_DEPTH` | `0.25` | `0..4` | tables and views |
| `nanobotMedianNanometers` | `SkySetting.NANOBOT_MEDIAN_NANOMETERS` | `100` | `50..1000` | tables and views |
| `nanobotSpread` | `SkySetting.NANOBOT_SPREAD` | `1.75` | `1.05..3` | tables and views |
| `iceOpticalDepth` | `SkySetting.ICE_OPTICAL_DEPTH` | `0.1` | `0..3` | nothing |
| `iceFollowsWeather` | `SkySetting.ICE_FOLLOWS_WEATHER` | `true` | | nothing |
| `dustExtinction` | `SkySetting.DUST_EXTINCTION` | `0` | `0..3` | nothing |
| `dustFollowsWeather` | `SkySetting.DUST_FOLLOWS_WEATHER` | `true` | | nothing |
| `texelDegrees` | `SkySetting.TEXEL_DEGREES` | `0.5` | `0..5` | halo profile, halo maps, and views |

`SkySetting.Rebuilds` names what a change rebuilds. `TABLES` rebuilds the tables of both depths, `HALOS` the halo profile and both halo maps, which are blurred by the texel size, and either one the four views, whose ground light uses the tables and the halo maps. The rest is kept, so a change of `texelDegrees` keeps the tables, about `150 ms` each, and a change of the nanobots keeps the halo maps. Settings that rebuild nothing are read every frame. Ice tables are built at the grid depths of `IceDepthGrid` whatever `iceOpticalDepth` holds.

Optical depths are vertical, at `550 nm`. `iceOpticalDepth` is drawn only while `iceFollowsWeather` is off, as described under [Ice optical depth from the weather](#ice-optical-depth-from-the-weather). Likewise `dustExtinction`, in `1/km` at the ground, applies only while `dustFollowsWeather` is off, as described under [Diamond dust from the weather](#diamond-dust-from-the-weather), so turning the switch off means no dust unless a value is set. The spread is the geometric standard deviation of diameter. Narrow spreads give colored skies, and wide ones give white haze. An optical depth of `0.25` at the default size is about `30 Mt` of nanobots worldwide. The texel size is rounded so that a whole number of texels fits along each cube face (`SkySettings.actualTexelDegrees`), and `0` draws a smooth sky. Vanilla's sun texture is about `1 degree` per texel. The exposure also sets the daylight on the terrain: a higher exposure keeps it near its noon brightness until the sun is lower.

The renderer compares the config with the settings of the drawn state every frame (`SkySettings.matchesConfig`, without allocating), so changes from the command apply at once. Edits to the config file while the game runs depend on Forge reloading the file, which has not been tried. The existing key `Weather.skyRenderChanges` is unrelated. Its only reader, `DimensionSpecialEffectsMixin`, is not listed in `frostedheart.mixins.json`.

## 4. Command

`/calculatedSky` is a client command, also reachable as `/frostedheart calculatedSky`. It needs no permission and sends no packets. It is registered only while the constant `CalculatedSkyClientCommand.ENABLED` is `true`, as it is during development. Without it the sky follows the client config alone, and the screenshot harness below is gone with it. Every form prints the state, every `SkySetting`, the nanobot mass and the drawn texel size, and the last build times of the tables, the halo profile, a halo map, and the sky view. It also prints the drawn ice optical depth, and while it follows the weather its target, whether it snows, the hours until and since snow, and the humidity. A line for the diamond dust gives its extinction, and while it follows the weather its target, whether it snows, and the hours since snow.

| Form | Effect |
|---|---|
| `/calculatedSky` | Prints the status |
| `/calculatedSky on` and `off` | Sets `enabled`, saves the config, and clears a previous failure |
| `/calculatedSky set <key> <value>` | Sets a key of `SkySetting`, a number in its range or `true` or `false` for a switch, and saves the config |
| `/calculatedSky reset` | Restores every key of `SkySetting` to its default and saves the config |

### Screenshot harness

`CalculatedSkyScreenshots` records and takes sets of screenshots for comparisons during development. Its subcommands are part of `/calculatedSky`, so they exist only while `CalculatedSkyClientCommand.ENABLED` is `true`, and nothing else of it runs without them. Taking a set needs a singleplayer world.

| Form | Effect |
|---|---|
| `/calculatedSky beginSet <set>` | Starts recording `<game directory>/sky-shots/<set>.json`, which is `run/sky-shots` in `runClient`. An existing set is loaded and extended, and a new one starts with the sky off and on as its variants |
| `/calculatedSky pose <name>` | Saves where the player stands and looks, with the day time and weather the client shows, as a pose of the set being recorded. A pose of the same name is replaced. The file is written at once |
| `/calculatedSky endSet` | Ends the recording and releases a held time and weather |
| `/calculatedSky time <ticks>` and `time off` | Holds the client's day time for framing, as a run does, and releases it. The server clock keeps running |
| `/calculatedSky weather <clear\|snow\|storm>` and `weather off` | Holds the client's rain and thunder levels for framing, and releases them back to the levels from before |
| `/calculatedSky screenshots` | Takes the current view at the current time with the sky off and on |
| `/calculatedSky screenshots <set>` | Takes every combination in the set |
| `/calculatedSky screenshots stop` | Ends a run early, keeping the shots taken so far |

Taking a set releases a held time and weather, and leaving the world releases them and ends a recording.

A set is one JSON object. Whatever it leaves out comes from the current view, time, weather, and config. Unknown keys and wrong values are reported in chat and skipped or replaced by the default.

| Key | Value |
|---|---|
| `poses` | List of `{name, yaw, pitch, position, offset, times, weather}`. `position` is `[x, y, z]` in the world and `offset` is `[x, y, z]` from where the run started. Without either the pose is where the run started. Yaw follows Minecraft, so `90` faces west toward the setting sun and `-90` faces east. `times` and `weather` replace those of the pose's group. Recorded poses go here, with the time and weather they were recorded with |
| `times` | List of day times in ticks. `18000 + 24000 * n` shows the moon phase of day `n` |
| `weather` | List of `world` (as the server has it), `clear`, `snow` (rain level `1`), and `storm` (rain and thunder level `1`) |
| `variants` | List of objects with `name`, `sky`, and any key of `SkySetting`, clamped to its range. A key left out keeps the config from the start of the run, except that a variant that names the manual value of a switch (`SkySetting.manualValue`) turns the switch off unless it sets it to `true`. So a variant with `iceOpticalDepth` draws that depth unless it also sets `iceFollowsWeather` to `true`. Without variants each shot is taken with the sky off and on |
| `groups` | List of objects with their own `poses`, `times`, `weather`, and `variants`. Each group takes its own combinations, and a list it leaves out comes from the set. Without groups the set is one group |
| `hud` | `true` keeps the HUD and the hand. Default `false` |

Shots run group by group, in the order pose, weather, time, variant. For each shot the harness sets the config without saving the file, holds the day time, the rain and thunder levels, and the view direction on the client every frame, and teleports the player through the integrated server when the pose is somewhere else, flying when the player may fly. It waits at least `1 s`, plus `4 s` after a weather change and `2 s` after a move. It also waits until the player has arrived, the terrain is drawn after a move (`LevelRenderer.hasRenderedAllChunks`, which Embeddium answers from its own renderer), and `CalculatedSkyRenderer.isSettled` reports the tables, halo maps, and views built for the config and sun elevation. After `20 s` it takes the shot anyway and says so in the manifest. An open screen, such as chat or the pause menu, restarts the wait, and pausing on lost focus is off while a run lasts.

The frame is saved at the end of `TickEvent.RenderTickEvent` with vanilla's `Screenshot.grab`, into `screenshots/sky-shots/<set>-<date>_<time>/` as `NNN_<pose>_<time>_<weather>_<variant>.png`. `manifest.tsv` in that folder starts with the set, the world seed, and the window size. It then has one line per shot with the player's position, the sun elevation, the rain and thunder levels, the variant's `sky` and every `SkySetting` in the enum's order, the ice optical depth drawn (`drawnIceOpticalDepth`), a status (`settled`, `timed out waiting for ...`, or `sky not drawn, ...`), and the frames per second over the wait. When the run ends, the config, the HUD, the weather, the view direction, and the position are restored, and the server's next time update restores the day time within a second.

Only the client's time and weather are held, so the server's clock and climate keep running during a run. The client's climate clock follows the server's, not the held day time, so a variant that follows the weather draws the ice of the world's weather under the pose's sun, and the depth can move between shots. `snow` and `storm` only set the client's rain and thunder levels, so they show vanilla snowfall and the snow fades of the sky and the fog. FrostedHeart's storms from the climate (`ClientWeatherFrame`) need a world that is in a real storm, with `world` weather. The contributor's grid tool `ScreenshotGrid`, which lives with the preview outside this repository, lays a run out with `--manifest`, one row per pose, weather, and time and one column per variant.

## 5. Validation And Performance

`CalculatedSkyGameTests` (namespace `frostedheart_calculated_sky`) checks:

- a published Mie value and the Rayleigh limit;
- the normalization of the Rayleigh and Henyey-Greenstein phase functions;
- the datasets: each side face row and each tumbling table integrates to `1`, the path shares of each row and band add up to between `0.99` and `1` (the tracer drops light still inside a crystal after `40` interactions, at most `0.5%`), and the crystal shares add up to `1`;
- the halo profile's total and the halo maps' totals at sun elevations `30` and `8 degrees` against the offline preview, and that the brightest bin of the sun dogs sits between `0.25 degrees` inside and `1 degree` outside the minimum deviation from Bravais' effective index;
- the default sky at sun elevations `30` and `8 degrees` against the offline preview, with `0.5 degree` texels. The directions include the sun itself, `2 degrees` from it (the diffraction peak), and `22 degrees` from it (the 22 degree halo). The preview's `FastSky` uses the same ray steps, transmittance rows, delta scaling, and diffuse totals with the asymmetry of its own traced tumbling crystals, which equals the mod's, and its values were taken again on `2026-11-08` with its mode `skyreference`;
- that with the sun in the middle of a row of the sky view, at `3.7`, `8.4`, and `45 degrees` and ice optical depth `0.5`, the sun's square carries the transmittance of a fine integration with the full ice extinction within `2%`, and together with the diffraction peak around it, taken as if all of it lay in the sun's direction, the transmittance with delta scaled ice within `5%`. Measured: `1.2%`, `0.9%`, and `0.4%` for the square, `3.7%`, `1.3%`, and `0.4%` with the peak. With `64` even transmittance rows the peak's excess at `3.7 degrees` was twice as large. At depths `1`, `2`, and `3` the excess passes `5%` for suns below `7`, `12`, and `13 degrees`, where the beam is at most `0.3%` of the sun's light. `1024` transmittance rows do not change that, and it was not traced further;
- that the ray steps of every row of the sky view, and of rays from `0.1` to `60 km` in every vertical direction, follow each other without gaps up to the ray's end and see the ice optical depth of a fine integration within `1%` for the view and `2%` for the others. Measured over `401` directions, the view is within `0.45%`, and rays from above that graze the band reach `1.5%`, where its `12` steps spread over a chord of about `340 km`;
- that the fog color is warmer toward a sun at `4.5 degrees` than away from it, and that looking straight up gives the same color in every direction;
- that the sun's radiance is zero below the horizon, has red at least green and green at least blue above it, and has more than ten times the red over blue at `2 degrees` that it has at `30 degrees`;
- that the daylight is within `0.95` of white at noon, never gets brighter as the sun sinks from `90` to `-18 degrees`, equals vanilla's night sky light at `-18 degrees`, and equals vanilla's sky light in full rain;
- that `SkyBuilder` rebuilds only what a changed setting needs (`settingsRebuildOnlyWhatDependsOnThem`): a change of `texelDegrees` keeps both tables and builds the halo profile, the halo maps, and the views again, a change of `nanobotOpticalDepth` then keeps the halo profile and maps, builds the tables again, and fills the four views the first state dropped instead of allocating, and a change of any setting that rebuilds nothing, such as `exposure` or `dustExtinction`, starts no build.

`IceWeatherGameTests`, `LightPillarGameTests`, and `SnowHistoryGameTests`, in the same namespace, check:

- the weather law: the clear depth across the humidity, the points of the ramp and the recovery, that the ramp never falls as snow nears and the recovery never rises, that the larger of the two applies where they overlap, and that the smoothing leaves `1/e` of the gap in the logarithm after one climate hour and never leaves the range between the depth and the target;
- the diamond dust law (`diamondDustLawFollowsSnow`): none while it snows, with no snow known, or `24` hours and more after it, the points of the rise and the fall, that it rises until `12` hours and falls after, and that the smoothing leaves `1/e` of the gap after one climate hour and never leaves the range between the extinction and the target;
- the grid: that grid depths rise, map to their own index, end at `3`, and blend from `0` to `1` across each cell;
- that in every cell, the blend of the two grid depths' radiance at the cell's middle is within `2` display steps of the tables built for that depth, and the ground radiance within `2%`. Display steps are of `255`, through `exposure` and the sRGB encoding as the shader draws them, over directions around suns at `5`, `20`, and `45 degrees`. Directions under the sun's square, widened by `0.5 degrees`, are logged but not asserted, because `SkyView.radiance` leaves the square out. Measured on `2026-11-08`: worst `1.56` steps in the top cell, `5 degrees` below a sun at `20 degrees` and just outside its square, and `0.12%` for the ground. Under the square the diffraction peak reaches `19.4` steps in the cell from `2.0` to `2.45`, with the sun at `5 degrees`. With the diffuse totals the same day: worst `1.34` steps at the same place, `0.16%` for the ground, and `18.7` under the square. With a grid step of `0.25` instead of `0.2`, the worst outside the square was `2.39`;
- that the ground radiance under ice depths `1`, `2`, and `3` over that under depth `0.02`, with suns at `10`, `30`, `45`, and `70 degrees`, is within `12%` of the preview's path tracer in every channel. The tolerance covers the reference's noise and its generic phase, as measured under [Forward scattering in the multiple scattering table](#forward-scattering-in-the-multiple-scattering-table). Measured on `2026-11-08`: worst `10.7%`, at depth `2` under a sun at `70 degrees` in blue. The code before the change was off by up to `19%` at that depth and sun;
- the light pillar optics: the slab reflectance against its table at the refractive index `1.31` within `0.0006`, at normal and grazing incidence, and that it never rises with the grazing angle, that the tilt density of both plate kinds integrates to `1` over the half sphere within `0.5%`, that the mirror height, elevation, and crystal distance agree with each other, and that `LightPillarOptics.radiance` matches a sum along the view ray of the reflection off every crystal in three dimensions within `5%`. The cases are lights `10`, `32`, and `300` blocks away, below, level with, and above the eye, elevations from `3` to `60 degrees`, and `0`, `1`, and `2` spreads off the pillar's center. Measured on `2026-11-11`: worst `0.2%` for plates and `4.0%` for wobbling plates, two spreads off the center at `60 degrees`, from the small tilt approximation across the pillar;
- that `WorldClimate` finds the last and the next snow up to `24` hours from the current hour, not counting the current hour and counting blizzards as snow, and that `FHClimatePacket` carries both hours through encoding and decoding.

The preview is a separate Java program in the contributor's workspace, checked against a Monte Carlo reference and its own ray tracer of ice prisms. The port matches it within `1%`, the test's tolerance. The mixins, the shader, and the lightmap hook only run in the client, so the GameTests do not cover them.

Against the Monte Carlo reference, the lookup tables reached a mean color difference (CIE dE) of `2.1` to `4.0` for the default settings through the day before delta scaling, the largest with the sun high. The Monte Carlo reference also showed a wider and brighter glow around a low sun, from light that ice diffracts forward more than once, which the tables treated as scattered evenly in all directions. Delta scaling keeps that light in the beam, as measured under [Delta scaled ice](#delta-scaled-ice). Light diffracted more than once is still drawn with the shape of a single diffraction, so the glow stays a little narrower than the reference's.

In the preview, the halo formulas match the ray tracer within a mean dE of `0.03` to `0.23` through the day. On `1 degree` texels, with the earlier coarser sampling, reading the map and profile once at each texel center matches traced and texel-averaged halos within a mean dE of `0.51` (`0.38` looking up). On `0.5 degree` texels with ice optical depth `0.3`, against dense formulas averaged over texels, the map reaches a mean dE of `0.29` with the sun at `22 degrees` and `0.34` at `50 degrees`, where the earlier sampling reached `0.36` and `0.44` and showed colored dots and streaks along the arcs.

Build times measured outside the game on `2026-11-05`, on one thread: tables `79 ms` warm and about `200 ms` on first use, sky view `19..60 ms`. In `runClient` on `2026-11-05`, before the ice and snow changes: tables `74..130 ms`, sky view `16..34 ms`, and the frame rate stayed at the `144` cap with the sky on and off. On `2026-11-06`, with median `300 nm` and spread `1.08`: tables `233 ms`, sky view `21..27 ms`, frame rate `140..144` both ways. Larger particles need more Mie terms, so the tables take longer. At normal day speed, the sun moves `0.25 degrees` about every `0.8 s`.

In the preview on `2026-11-07`, on one thread: a halo map `0.45..0.49 s` warm (fastest of three builds) and up to `0.9 s` on first use, the halo profile about `0.4 s`. A new map is needed every `0.5 degrees` of sun elevation, about every `1.7 s` at normal day speed, so the halos take about `28%` of one background thread in daytime by the preview's times. In `runClient` on `2026-11-07`, with the earlier sampling (`0.2 s` per map in the preview), ice optical depth `0.3`, and `0.5 degree` texels: tables `121.5 ms`, halo profile `110.5 ms`, halo map `73.6 ms` (about `4%` of one background thread), sky view `32.1 ms`, and `600..700` frames per second with the sky on and off. Later that day, with the denser sampling, ice optical depth `0.1`, and `0.5 degree` texels: tables `113.7 ms`, halo profile `122.5 ms`, halo map `287.9 ms` (about `17%` of one background thread), sky view `29.6 ms`. That map took `3.9` times the earlier one, more than the preview's ratio of `2.45`, from a single reading at a sun elevation that was not recorded.

The mod's own halo code, timed outside the game on `2026-11-07` on one thread at sun elevations from `2` to `70 degrees` (warm, high performance power mode): `202..235 ms` per map with `58..68 MB` allocated before the first speedup, `77..89 ms` after it, and `72..75 ms` once `IcePrism` merged its outputs. A sky view took `27..30 ms`, and `15..17 ms` once `SkyTables` worked out the steps of each view ray once per setting (`buildViewRays`) instead of once per texel, since they depend only on the row's elevation. Halo maps and sky views are built without allocating: `SkyBuilder` keeps one `IceHaloMap.Workspace` for all builds and refills the halo map and `SkyView` that the previous state dropped, and `SkyTables` reuses scratch buffers for its views. New maps and views are allocated only on the first builds, after a settings change, and when the sun jumps by more than one map step. Of the `73 ms` per map, side face cones take about `24 ms`, end face to end face paths `26 ms`, Parry column side paths `17 ms`, end face to side face paths `5 ms`, and the blur `3 ms`. Of the `15.5 ms` per view, the view rays take about `13 ms` and the light that ice scatters onto the ground about `2.4 ms`. At normal day speed, with a map every `1.7 s` and a view every `0.8 s`, that is about `6%` of one background thread while the sun moves, down from about `8%`. In the laptop's automatic power mode the same builds sometimes took about twice as long, and below normal priority runs sometimes slowed to half speed partway through, so only alternating short runs were compared.

The ray steps, timed the same way on `2026-11-08` with two alternating runs of three rounds each: a sky view takes `18.9..19.9 ms` instead of `15.8..15.9 ms`, from the `12` extra steps of each view ray, and the tables `131..155 ms` instead of `74..98 ms`, from the extra rows and steps of the multiple scattering table.

Delta scaled ice with `128` transmittance rows, timed the same way on `2026-11-08` with three alternating runs against the code before it, in a slower power state of the laptop: the halo profile unchanged at about `192 ms`, the tables about `4%` slower (`239..252 ms` instead of `229..240 ms`), where the transmittance table alone, timed in the faster state, went from `4.7` to `10 ms` of `130..137 ms`, and a sky view about `4%` slower (`34..36 ms` instead of `33..34 ms`), from the row mapping in each lookup of the transmittance table.

The diffuse totals add a second transmittance table to each build of the tables, which by the timing above is about `5..10 ms` of `130..250 ms`. It has not been timed on its own.

On `2026-11-06` empireu also checked the render cost on a laptop RTX 4070, about a desktop RTX 4050 to 4060, and found it negligible. Exact GPU time of the draw and of the texture upload has not been measured.

## 6. Known Limits

- One sky for the whole overworld. Biome sky and fog colors are not used while the sky is drawn, except the snow fog of `FogModification`.
- Cloud color stays vanilla, so it can disagree with the sky, most visibly at sunrise and sunset.
- The daylight is one color for all terrain, as in vanilla, with no side lit by the sun. It reaches the night light at a sun elevation near `0 degrees`, where vanilla's light reaches its night level near `-6 degrees`, so evenings get dark earlier and mornings light later. The night light is vanilla's night sky light, constant and blue, not light from the moon in the model.
- The fog color is one color per frame, as in vanilla, so at the sides of a wide view the horizon can differ a little from it. It has no light from ice crystals, so toward a low sun it is a little darker than the sky above the horizon fade.
- Snow fades the sky toward the snow fog. Weather changes only the amount of ice. Its crystals and their halos are the same on every day, and a faint halo shows on most clear days. Thicker cloud and the nanobots do not follow the weather.
- Light on other paths is put at the sun, so what those paths draw is missing: light in through a side face and out through an end face (the circumhorizontal arc of a sun above `58 degrees`), reflections off end faces (the sun pillar), and the faint anthelic arcs of columns opposite the sun. The subsun lies below the horizon and is not drawn.
- The diamond dust extinction and the optics of light pillars are worked out, but nothing draws them yet.
- A resource pack's `sun.png` is not used while the calculated sky is drawn, since the shader draws the sun. The pack does not replace it.
- There is no moonlight in the model, so the night sky is black apart from the vanilla moon and stars, and so is the fog at night. Vanilla's night fog is a very dark blue, `5..8%` of its day fog with the pack's colors.
- In snow, blindness does not darken the fog, because `FogModification` sets its snow fog color after vanilla's darkening. This does not depend on the calculated sky.
- `runClient` uses vanilla biomes. The `2026-11-06` run used a datapack that gives them the pack's sky and fog colors, but the pack's own world generation has not been tried.
