# Calculated Sky

- Status: `Partial`
- Last verified: `2026-11-07`, against source and the GameTests below. The first version, with one ice lobe and no snow fade, was seen in `runClient` on `2026-11-05`. The three-part ice phase and a snow fade toward gray were seen in `runClient` on `2026-11-06`, and the fog color from the sky and the snow fade toward the fog color later that day. The ice crystal halos and the sky texels were first seen in `runClient` on `2026-11-07`, with no popping as the sun moves.
- Scope: the optional overworld sky color calculated from air, titanium dioxide nanobots, and ice crystals, its halo display, the fog color taken from it outside snow, and the client config and client command. Clouds and lightmap are unchanged.
- Primary code anchors: `CalculatedSkyRenderer`, `CalculatedSkyEffects`, `CalculatedSkyClientCommand`, `SkyTables`, `SkyView`, `SkyAtmosphere`, `SkyParticleOptics`, `SkyPhase`, `SkySettings`, `IceCrystalData`, `IceHaloProfile`, `IceHaloMap`, `IceHaloFormulas`, `IcePrism`, `LevelRendererMixin.frostedheart$drawCalculatedSky`, `FogRendererMixin.frostedheart$useCalculatedSkyFogColor`, `FHClientEventsMod.registerDimensionEffects`, `assets/frostedheart/shaders/calculated_sky.fsh`, `assets/frostedheart/datasets/ice_halos`

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

`SkyTables` follows Hillaire 2020: a transmittance table (`256 x 64`, `40` steps), a multiple scattering table (`32 x 32`, `20` steps, `64` directions), and a sky view table (`200 x 100`, `30` steps). Multiple scattering is computed over a black ground. Light reflected back and forth between snow and atmosphere is added afterwards:

```text
L(view)  = L_black_ground(view) + L_ground * G(view)
L_ground = (A / pi) * E_0 / (1 - A * S)
S        = 2 * integral over mu from 0 to 1 of G(mu) * mu
```

| Symbol | Meaning | Value |
|---|---|---|
| `A` | Snow albedo | `0.8`, source constant `SkyAtmosphere.GROUND_ALBEDO` |
| `E_0` | Downward irradiance on a black ground: direct sun, the sky view table, and single scattering by ice (`IceHaloProfile.irradiance` plus `IceHaloMap.irradiance`) | Computed per sky view |
| `G(view)` | Radiance at the camera per unit radiance of glowing ground | Table of `65` view cosines |
| `S` | Spherical albedo of the atmosphere | Computed per settings |

Radiance is relative to a sun of irradiance `1` in each band. The shader displays it as `1 - exp(-exposure * L)`, encoded to sRGB.

### Ice crystals and halos

The sky view table holds no single scattering by ice. A second table, the ice view, holds that light per unit phase function, and the ice phase function is applied per sky texel:

```text
L_ice(view) = ice_view(view) * (profile(angle from sun) + map(view, sun elevation))
```

Half of the light ice removes is diffracted around the sun, as Henyey-Greenstein with asymmetry `0.99` (`ICE_DIFFRACTION_FRACTION`, `ICE_DIFFRACTION_ASYMMETRY`). The other half passes through the crystals of `datasets/ice_halos/crystals.csv`:

| Crystal kind | Share | Shape (half height over radius) | Orientation | Gives |
|---|---:|---:|---|---|
| Tumbling crystals | `40%` | `1.0` | Random | 22 and 46 degree halos |
| Plates | `25%` | `0.15` | Axis vertical, tilt `0.7 degrees` | Sun dogs, parhelic circle, circumzenithal arc |
| Wobbling plates | `5%` | `0.15` | Axis vertical, tilt `5 degrees` | Wider, fainter sun dogs |
| Columns | `20%` | `2.0` | Axis horizontal, tilt `0.7 degrees` | Tangent arcs, circumscribed halo, parhelic circle |
| Parry columns | `10%` | `1.5` | Axis horizontal, two side faces horizontal, tilt `0.5`, turn `1 degree` | Parry arcs |

The mix was chosen for a rich display. The ice refractive index is `n = 1.3005 + 0.0030 / lambda^2`, `1.3070`, `1.3104`, and `1.3160` at the band centers.

`IceHaloProfile` holds the diffraction peak and the tumbling crystals as a function of the angle from the sun, in `3600` bins of `0.05 degrees`, from `scattering_tumbling_crystals.csv`. It is built when a setting changes.

`IceHaloMap` holds the oriented crystals for one sun elevation, in `360` columns of azimuth from the sun (`0..180 degrees`, the display is mirror symmetric) by `184` rows of elevation from `-2 degrees` to the zenith, both in `0.5 degree` bins. `IceHaloFormulas` fills it from formulas, so no rays are traced in game:

- light through side faces lies on the cone around the crystal axis through the sun, at the in-plane deviation of `side_faces.csv` for Bravais' effective index `n' = sqrt(n^2 - a^2) / sqrt(1 - a^2)`, where `a` is the cosine between sun and axis. Light with an odd number of end face reflections uses the sun's mirror image in the end faces;
- Parry columns have a fixed turn, so `IcePrism.trace` follows their light through the hexagon per axis;
- light in through an end face and out through a side face is refracted exactly in 3D, over `360` face directions, or the six faces of a Parry column;
- light from end face to end face is followed through its side face reflections by `IcePrism.traceMirrors`;
- the share of light on each path comes from `paths_<crystal>.csv`, by whole degree of sun elevation, interpolated. Light on other paths is put at the sun.

Axes are sampled on a jittered grid (plates, up to `196` axes, tilt step `0.5 degrees`) or every `0.25 degrees` of azimuth (columns, `720` axes), weighted by their projected side area. The side face deviation is stepped every `0.25 degrees`, two steps per column of `side_faces.csv`. End face entries are sampled `32` times per axis, `64` for Parry columns. This sampling is dense enough for `0.5 degree` texels; coarser sampling shows as colored dots and streaks along the arcs. Random numbers come from SplitMix64 seeded per axis, so a map does not depend on timing.

Both are blurred by a Gaussian of `sqrt((0.45 * texel)^2 + (sun radius / 2)^2)` with a sun radius of `0.2665 degrees`, so arcs stay about equally bright as they move across texels. Texels below `0.5 degrees`, and the smooth sky, are blurred as `0.5 degrees`, the size of the map's bins (`IceHaloMap.blurSigma`). Oriented light below `-2 degrees` is dropped, so the map keeps `0.20` (sun at `8 degrees`) to `0.25` (sun at `30 degrees`) of the `0.30` the oriented crystals scatter.

The tables were written by the offline sky preview, as described in `datasets/ice_halos/src.md`.

## 2. Runtime

| Step | Where | When |
|---|---|---|
| Transmittance, multiple scattering, `G`, `S` | `new SkyTables(settings)` on `Util.backgroundExecutor()` | When a setting changes |
| Halo profile | `new IceHaloProfile` on `Util.backgroundExecutor()` | When a setting changes |
| Halo maps | `new IceHaloMap` on `Util.backgroundExecutor()` | For sun elevations on a `0.5 degree` grid from `-6` to `90 degrees`. The two around the sun are kept, and when the sun leaves them the missing one is built |
| Sky view and ice view for one sun elevation | `SkyTables.computeView` on `Util.backgroundExecutor()` | When the sun has moved more than `0.25 degrees`, unless both elevations are below `-18 degrees` |
| Upload as `RGB16F` textures | `CalculatedSkyRenderer.uploadTable`, render thread | When a build finishes, only the tables that changed |
| Draw | `calculated_sky.fsh`, one full-screen quad | Every frame while drawn |

Only one build runs at a time, on one thread. The sky view is mirror symmetric around the sun's azimuth, so only half of it is integrated. `IceCrystalData` reads the datasets once, on the first build, through `ModList.getModFileById("frostedheart")`. Below a sun elevation of `-6 degrees` the halo maps stay as they are, since the ice view is dark then.

`LevelRendererMixin.frostedheart$drawCalculatedSky` injects after the first `VertexBuffer.unbind()` in `LevelRenderer.renderSky`, which is right after vanilla draws its sky dome. Vanilla still draws the sun, moon, stars, and the dark dome below the horizon on top. Vanilla's own exits for fog, lava, powder snow, blindness, and darkness still apply.

The shader:

- snaps each pixel's view direction to the center of its sky texel. Texels are the cells of an equi-angular cube map aligned with the world axes, with `round(90 / texelDegrees)` cells along each face edge, so they stay fixed to the sky as the camera turns, like the sun's texture. Minecraft's sun moves in the x-y plane, so its path runs down the middle of the `+x`, top, and `-x` faces. With `texelDegrees` `0` it uses the pixel's own direction;
- looks up the sky view and the ice view by azimuth from the sun and by the latitude mapping of Hillaire 2020, as `SkyView.sample` does;
- adds the ice view times the halo profile at the angle from the sun and the two halo maps, blended by the exact sun elevation between their two elevations;
- grays the color by rain and thunder level with the formula of `ClientLevel.getSkyColor`, without the lightning flash, so the sky darkens like vanilla's;
- fades the color toward the fog color by the rain level. In snow `FogModification` sets the fog color, so the sky becomes the pack's snow fog. `PlayerWeatherCompatibilityModel` sets the rain level to `0.8` in snow and blizzards, so a fifth of the sky stays, with faint halos, as a fifth of vanilla's sun does;
- fades to the fog color from the horizon up to a texel direction with `y = 0.08`, about `4.6 degrees`, so terrain fog meets the sky without a seam;
- adds dithering of half a display step against banding, one value per texel so texels stay flat.

`CalculatedSkyEffects` replaces the overworld `DimensionSpecialEffects` through `RegisterDimensionSpecialEffectsEvent`. While the sky is drawn, `getSunriseColor` returns `null`, which removes vanilla's sunrise fan and its tint of the fog color (`FogRenderer.setupColor`). Otherwise it returns the vanilla value.

### Fog color

`FogRendererMixin.frostedheart$useCalculatedSkyFogColor` injects into `FogRenderer.setupColor` before its call to `getPriorityFogFunction`. While the calculated sky is drawn and the camera is not in a fluid, it sets the fog color to `CalculatedSkyRenderer.fogColor`, which comes from `SkyView.horizonDisplayColor`:

- the sky view table at elevation `0` in the azimuth the camera looks. It has no single scattering by ice, so the color stays smooth near the sun and across halo arcs;
- blended toward `SkyView.meanHorizonRadiance`, the horizon averaged over `64` azimuths, by `1 - h`, where `h` is the length of the look vector's horizontal part, so looking straight up does not pick a direction at random;
- tone mapped, encoded, and grayed by rain and thunder as the shader does.

This replaces vanilla's color from sky, biome fog, sunrise, render distance, and weather, which it computes before that call. Vanilla still applies what comes after: darkening for blindness, darkness, the void below `minimum height + 32`, and bosses, then night vision. `ViewportEvent.ComputeFogColor` fires after that, so `FogModification.renderFogColors` still sets its snow fog color whenever its fog density is above zero. Embeddium redirects the biome fog sampling earlier in `setupColor`, and Oculus injects at its tail, so neither conflicts. The fog color is also the clear color behind the world and the color the shader fades to at the horizon.

The calculated sky is skipped, and vanilla draws the sky as before, when:

- `CalculatedSky.enabled` is false;
- the level is not `minecraft:overworld`;
- an Oculus shader pack is in use (`IrisApi.isShaderPackInUse`, only called when Oculus is loaded);
- the camera is inside a fluid;
- no table has been uploaded yet, for the first second or so after enabling;
- building or drawing threw an exception, including a missing or broken dataset. It is logged once, and turning the sky on again with the command retries.

## 3. Configuration

Client config, group `CalculatedSky`. Ranges come from `SkySettings`.

| Key | Java anchor | Default | Range |
|---|---|---:|---|
| `enabled` | `calculatedSky` | `false` | |
| `exposure` | `calculatedSkyExposure` | `6.0` | `0.5..50` |
| `nanobotOpticalDepth` | `calculatedSkyNanobotOpticalDepth` | `0.25` | `0..4` |
| `nanobotMedianNanometers` | `calculatedSkyNanobotMedianNanometers` | `100` | `50..1000` |
| `nanobotSpread` | `calculatedSkyNanobotSpread` | `1.75` | `1.05..3` |
| `iceOpticalDepth` | `calculatedSkyIceOpticalDepth` | `0.1` | `0..1` |
| `texelDegrees` | `calculatedSkyTexelDegrees` | `0.5` | `0..5` |

Optical depths are vertical, at `550 nm`. The spread is the geometric standard deviation of diameter. Narrow spreads give colored skies, and wide ones give white haze. An optical depth of `0.25` at the default size is about `30 Mt` of nanobots worldwide. The texel size is rounded so that a whole number of texels fits along each cube face (`SkySettings.actualTexelDegrees`), and `0` draws a smooth sky. Vanilla's sun texture is about `1 degree` per texel.

The renderer reads the config every frame, so changes from the command apply at once. Edits to the config file while the game runs depend on Forge reloading the file, which has not been tried. The existing key `Weather.skyRenderChanges` is unrelated. Its only reader, `DimensionSpecialEffectsMixin`, is not listed in `frostedheart.mixins.json`.

## 4. Command

`/calculatedSky` is a client command, also reachable as `/frostedheart calculatedSky`. It needs no permission and sends no packets. Every form prints the state, the settings, and the last build times of the tables, the halo profile, a halo map, and the sky view.

| Form | Effect |
|---|---|
| `/calculatedSky` | Prints the status |
| `/calculatedSky on` and `off` | Sets `enabled`, saves the config, and clears a previous failure |
| `/calculatedSky set <key> <value>` | Sets one of the six numeric keys and saves the config |
| `/calculatedSky reset` | Restores the six numeric keys to their defaults |

## 5. Validation And Performance

`CalculatedSkyGameTests` (namespace `frostedheart_calculated_sky`) checks:

- a published Mie value and the Rayleigh limit;
- the normalization of the Rayleigh and Henyey-Greenstein phase functions;
- the datasets: each side face row and each tumbling table integrates to `1`, the path shares of each row and band add up to between `0.99` and `1` (the tracer drops light still inside a crystal after `40` interactions, at most `0.5%`), and the crystal shares add up to `1`;
- the halo profile's total and the halo maps' totals at sun elevations `30` and `8 degrees` against the offline preview, and that the brightest bin of the sun dogs sits between `0.25 degrees` inside and `1 degree` outside the minimum deviation from Bravais' effective index;
- the default sky at sun elevations `30` and `8 degrees` against the offline preview, with `0.5 degree` texels. The directions include the sun itself, `2 degrees` from it (the diffraction peak), and `22 degrees` from it (the 22 degree halo);
- that the fog color is warmer toward a sun at `4.5 degrees` than away from it, and that looking straight up gives the same color in every direction.

The preview is a separate Java program in the contributor's workspace, checked against a Monte Carlo reference and its own ray tracer of ice prisms. The port matches it within `1%`, the test's tolerance. The mixin and the shader only run in the client, so the GameTests do not cover them.

Against the Monte Carlo reference, the lookup tables reach a mean color difference (CIE dE) of `2.1` to `4.0` for the default settings through the day, the largest with the sun high. The Monte Carlo reference also shows a wider glow around a low sun, from light that ice scatters forward more than once. The tables treat repeated scattering as even in all directions, so they miss it.

In the preview, the halo formulas match the ray tracer within a mean dE of `0.03` to `0.23` through the day. On `1 degree` texels, with the earlier coarser sampling, reading the map and profile once at each texel center matches traced and texel-averaged halos within a mean dE of `0.51` (`0.38` looking up). On `0.5 degree` texels with ice optical depth `0.3`, against dense formulas averaged over texels, the map reaches a mean dE of `0.29` with the sun at `22 degrees` and `0.34` at `50 degrees`, where the earlier sampling reached `0.36` and `0.44` and showed colored dots and streaks along the arcs.

Build times measured outside the game on `2026-11-05`, on one thread: tables `79 ms` warm and about `200 ms` on first use, sky view `19..60 ms`. In `runClient` on `2026-11-05`, before the ice and snow changes: tables `74..130 ms`, sky view `16..34 ms`, and the frame rate stayed at the `144` cap with the sky on and off. On `2026-11-06`, with median `300 nm` and spread `1.08`: tables `233 ms`, sky view `21..27 ms`, frame rate `140..144` both ways. Larger particles need more Mie terms, so the tables take longer. At normal day speed, the sun moves `0.25 degrees` about every `0.8 s`.

In the preview on `2026-11-07`, on one thread: a halo map `0.45..0.49 s` warm (fastest of three builds) and up to `0.9 s` on first use, the halo profile about `0.4 s`. A new map is needed every `0.5 degrees` of sun elevation, about every `1.7 s` at normal day speed, so the halos take about `28%` of one background thread in daytime by the preview's times. In `runClient` on `2026-11-07`, with the earlier sampling (`0.2 s` per map in the preview), ice optical depth `0.3`, and `0.5 degree` texels: tables `121.5 ms`, halo profile `110.5 ms`, halo map `73.6 ms` (about `4%` of one background thread), sky view `32.1 ms`, and `600..700` frames per second with the sky on and off. Later that day, with the denser sampling, ice optical depth `0.1`, and `0.5 degree` texels: tables `113.7 ms`, halo profile `122.5 ms`, halo map `287.9 ms` (about `17%` of one background thread), sky view `29.6 ms`. That map took `3.9` times the earlier one, more than the preview's ratio of `2.45`, from a single reading at a sun elevation that was not recorded.

The mod's own halo code, timed outside the game on `2026-11-07` on one thread at sun elevations from `2` to `70 degrees` (warm, high performance power mode): `202..235 ms` per map with `58..68 MB` allocated before the speedup, `77..89 ms` after, and a sky view `27..30 ms`. Halo maps and sky views are built without allocating: `CalculatedSkyRenderer` keeps one `IceHaloMap.Workspace` for all builds and refills the halo map and `SkyView` that the previous state dropped, and `SkyTables` reuses scratch buffers for its views. New maps and views are allocated only on the first builds, after a settings change, and when the sun jumps by more than one map step. Of the `82 ms`, side face cones take about `22 ms`, Parry column side paths `19 ms`, end face to end face paths `32 ms`, end face to side face paths `5 ms`, and the blur `3 ms`. At normal day speed that is about `5%` of one background thread while the sun moves. In the laptop's automatic power mode the same builds sometimes took about twice as long.

On `2026-11-06` empireu also checked the render cost on a laptop RTX 4070, about a desktop RTX 4050 to 4060, and found it negligible. Exact GPU time of the draw and of the texture upload has not been measured.

## 6. Known Limits

- One sky for the whole overworld. Biome sky and fog colors are not used while the sky is drawn, except the snow fog of `FogModification`.
- Cloud color and lightmap stay vanilla, so they can disagree with the sky, most visibly at sunrise and sunset.
- The fog color is one color per frame, as in vanilla, so at the sides of a wide view the horizon can differ a little from it. It has no light from ice crystals, so toward a low sun it is a little darker than the sky above the horizon fade.
- Snow fades the sky toward the snow fog. Snow and storms do not change the model, and the same ice crystals and halos are present every clear day.
- Light on other paths is put at the sun, so what those paths draw is missing: light in through a side face and out through an end face (the circumhorizontal arc of a sun above `58 degrees`), reflections off end faces (the sun pillar), and the faint anthelic arcs of columns opposite the sun. The subsun lies below the horizon and is not drawn.
- There is no moonlight in the model, so the night sky is black apart from the vanilla moon and stars, and so is the fog at night. Vanilla's night fog is a very dark blue, `5..8%` of its day fog with the pack's colors.
- In snow, blindness does not darken the fog, because `FogModification` sets its snow fog color after vanilla's darkening. This does not depend on the calculated sky.
- `runClient` uses vanilla biomes. The `2026-11-06` run used a datapack that gives them the pack's sky and fog colors, but the pack's own world generation has not been tried.
