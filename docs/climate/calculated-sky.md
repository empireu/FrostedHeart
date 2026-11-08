# Calculated Sky

- Status: `Partial`
- Last verified: `2026-11-08`, against source and the GameTests below. The ray steps across the ice band were checked against a converged reference outside the game that day. The first version, with one ice lobe and no snow fade, was seen in `runClient` on `2026-11-05`. The three-part ice phase and a snow fade toward gray were seen in `runClient` on `2026-11-06`, and the fog color from the sky and the snow fade toward the fog color later that day. The ice crystal halos and the sky texels were first seen in `runClient` on `2026-11-07`, with no popping as the sun moves. The sky's own sun, which replaces vanilla's, and the blended sky views were seen in `runClient` the same day. The screenshot harness took its first sets in `runClient` the same day, the last one recorded with `beginSet` and `pose`. The daylight on the terrain was seen in `runClient` the same day, with torch light unchanged.
- Scope: the optional overworld sky color calculated from air, titanium dioxide nanobots, and ice crystals, its halo display and sun, the fog color taken from it outside snow, the sky light of the lightmap taken from it outside rain, the client config and client command, and a screenshot harness for development. Clouds are unchanged.
- Primary code anchors: `CalculatedSkyRenderer`, `CalculatedSkyEffects`, `CalculatedSkyClientCommand`, `CalculatedSkyScreenshots`, `SkyTables`, `SkyView`, `SkyAtmosphere`, `SkyParticleOptics`, `SkyPhase`, `SkySettings`, `IceCrystalData`, `IceHaloProfile`, `IceHaloMap`, `IceHaloFormulas`, `IcePrism`, `LevelRendererMixin.frostedheart$drawCalculatedSky`, `LevelRendererMixin.frostedheart$hideVanillaSun`, `FogRendererMixin.frostedheart$useCalculatedSkyFogColor`, `FHClientEventsMod.registerDimensionEffects`, `assets/frostedheart/shaders/calculated_sky.fsh`, `assets/frostedheart/datasets/ice_halos`

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

`SkyTables` follows Hillaire 2020: a transmittance table (`256 x 64`, `40` steps), a multiple scattering table (`48` altitudes by `32` sun cosines, `20` steps, `64` directions), and a sky view table (`200 x 100`, `30` steps). Every ray also gets `12` steps of its own across each crossing of the ice band, as described under [Ray steps](#ray-steps). Multiple scattering is computed over a black ground. Light reflected back and forth between snow and atmosphere is added afterwards:

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

### Ray steps

The ice layer is only `1.2 km` wide, so even steps over a ray to the top of the atmosphere put one or two samples in it, about `3.3 km` apart looking straight up. `SkyRaySteps.fill` therefore splits each ray where it enters and leaves the ice band, the shell between `SkyAtmosphere.ICE_BAND_LOW_RADIUS` and `ICE_BAND_HIGH_RADIUS`, four widths below and above the layer's center (`6.2..15.8 km`). Each crossing of the band gets `12` equal steps. Outside it the steps are the cells of the even grid over the whole ray, cut at the band's edges, so the sky without ice samples as before. The transmittance table, the multiple scattering table, `G`, and the view rays all use it. The rows of the multiple scattering table are spaced evenly below the band (`8` intervals), inside it (`16`), and above it (`23`), because the light inside a thick ice layer changes over a few hundred meters.

Measured outside the game on `2026-11-08` against the same model with `1500` view steps, `1000` multiple scattering steps, `600` transmittance steps, and `128 x 128` multiple scattering texels, along four azimuths from the sun at sun elevations `2`, `15`, `30`, and `60 degrees`, leaving out `5 degrees` around the sun, in display steps of `255` at exposure `6`:

| Ice optical depth | Even steps: largest error, ripple | Ray steps: largest error, ripple |
|---|---|---|
| `0` | `2.1`, `1.3` | `2.0`, `1.2` |
| `0.1` | `3.7`, `3.3` | `1.6`, `0.6` |
| `0.5` | `13.4`, `9.5` | `1.1`, `0.7` |
| `1` | `27.4`, `15.1` | `2.1`, `1.5` |
| `3` | `64.2`, `33.7` | `1.5`, `1.0` |

The ripple is the error minus its average over `12 degrees` of elevation, which shows as bands across the sky. With even steps the ground light at depth `3` was more than twice the reference, and with ray steps it is within `2%`. Near the sun the ice's single scattering changes most: with the default depth `0.1`, the radiance at the sun moves by `-9%` and `2 degrees` from a sun at `8 degrees` by `+9%`, within about `1.7%` of the reference.

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

A reflection off a side face maps a direction angle `a` inside the hexagon to `2 * normal angle + pi - a`, so after any number of reflections light moves in one of six direction classes per entry face: odd or even reflection count, turned by `0`, `120`, or `240 degrees`. The exit direction depends only on the class and the exit face, not on where the light entered. `IcePrism` keys its outputs by class and exit face and merges those of one trace call, which cut the points written per map from about `1.1 million` to about `150,000` with the same sampling.

Axes are sampled on a jittered grid (plates, up to `196` axes, tilt step `0.5 degrees`) or every `0.25 degrees` of azimuth (columns, `720` axes), weighted by their projected side area. The side face deviation is stepped every `0.25 degrees`, two steps per column of `side_faces.csv`. End face entries are sampled `32` times per axis, `64` for Parry columns. This sampling is dense enough for `0.5 degree` texels; coarser sampling shows as colored dots and streaks along the arcs. Random numbers come from SplitMix64 seeded per axis, so a map does not depend on timing.

Both are blurred by a Gaussian of `sqrt((0.45 * texel)^2 + (sun radius / 2)^2)` with a sun radius of `0.2665 degrees`, so arcs stay about equally bright as they move across texels. Texels below `0.5 degrees`, and the smooth sky, are blurred as `0.5 degrees`, the size of the map's bins (`IceHaloMap.blurSigma`). Oriented light below `-2 degrees` is dropped, so the map keeps `0.20` (sun at `8 degrees`) to `0.25` (sun at `30 degrees`) of the `0.30` the oriented crystals scatter.

The tables were written by the offline sky preview, as described in `datasets/ice_halos/src.md`.

### Sun

The sun is a square with the size and orientation of the core of vanilla's sun texture, its central `8` of `32` texels, which vanilla draws `60` wide at distance `100`. On the plane one unit in front of the camera its half side is `h = 0.075` (`SkyTables.SUN_HALF_TANGENT`), so it is about `8.6 degrees` across, with two edges along the sun's path and two from north to south. Its radiance spreads the sun's irradiance of `1` over its solid angle:

```text
L_sun(view) = T(elevation) / Omega
Omega       = 4 * atan(h^2 / sqrt(1 + 2 * h^2)) = 0.02237 sr
```

`T` is the transmittance from the camera to the top of the atmosphere at the view's elevation, the product over the steps of that row's view ray. `SkyTables.sunRadiance` holds `L_sun` per row of the sky view, built with the tables, and zero for rows that hit the ground. So each texel of the square has the color of its own elevation: as the sun sets, the bottom of the square reddens first and the horizon cuts it off. Below about `4.6 degrees` the shader's fade to the fog color dims it further.

With the default settings and exposure, the table displays white above about `8 degrees`, `(255, 237, 134)` at `4 degrees`, `(245, 148, 37)` at `2 degrees`, `(190, 63, 2)` at `0.5 degrees`, and `(154, 37, 0)` at the horizon. Its transmittance is within `3%` of the transmittance table's at these elevations. The diffraction peak and the nanobots' forward scattering already make the sky white out to about `2..3 degrees` from a high sun, and about `230` of `255` at the square's edges, so a high sun shows as a white square with soft edges. From about `10 degrees` down, the square stands out clearly against the sky.

## 2. Runtime

| Step | Where | When |
|---|---|---|
| Transmittance, multiple scattering, `G`, `S`, view ray steps, sun radiance | `new SkyTables(settings)` on `Util.backgroundExecutor()` | When a setting changes |
| Halo profile | `new IceHaloProfile` on `Util.backgroundExecutor()` | When a setting changes |
| Halo maps | `new IceHaloMap` on `Util.backgroundExecutor()` | For sun elevations on a `0.5 degree` grid from `-6` to `90 degrees`. The two around the sun are kept, and when the sun leaves them the missing one is built |
| Sky views and ice views | `SkyTables.computeView` on `Util.backgroundExecutor()` | For sun elevations on a `0.25 degree` grid from `-18` to `90 degrees`. The two around the sun are kept, and when the sun leaves them the missing one is built |
| Upload as `RGB16F` textures | `CalculatedSkyRenderer.uploadTable`, render thread | When a build finishes, only the tables that changed |
| Draw | `calculated_sky.fsh`, one full-screen quad | Every frame while drawn |

Only one build runs at a time, on one thread. The sky view is mirror symmetric around the sun's azimuth, so only half of it is integrated. `IceCrystalData` reads the datasets once, on the first build, through `ModList.getModFileById("frostedheart")`. Below a sun elevation of `-6 degrees` the halo maps stay as they are, since the ice view is dark then.

`LevelRendererMixin.frostedheart$drawCalculatedSky` injects after the first `VertexBuffer.unbind()` in `LevelRenderer.renderSky`, which is right after vanilla draws its sky dome. Vanilla still draws the moon, stars, and the dark dome below the horizon on top. Vanilla's own exits for fog, lava, powder snow, blindness, and darkness still apply.

`LevelRendererMixin.frostedheart$hideVanillaSun` replaces the sun's half size `30.0F` in `renderSky` with `0` when the calculated sky drew its sun in the same call (`CalculatedSkyRenderer.isSunDrawn`), so vanilla's sun quad has no area. The flag is cleared at the start of every `CalculatedSkyRenderer.render` and set after a successful draw, so in every case below where the calculated sky is skipped, vanilla draws its sun. Oculus `1.7.0` hides the sun for shader packs that turn it off by emptying the vertex builder before `BufferBuilder.end()`, and Embeddium `0.3.31` injects only at the head of `renderSky`, so neither conflicts with the constant.

The shader:

- snaps each pixel's view direction to the center of its sky texel. Texels are the cells of an equi-angular cube map aligned with the world axes, with `round(90 / texelDegrees)` cells along each face edge, so they stay fixed to the sky as the camera turns, like the sun's texture. Minecraft's sun moves in the x-y plane, so its path runs down the middle of the `+x`, top, and `-x` faces. With `texelDegrees` `0` it uses the pixel's own direction;
- looks up the sky views and the ice views by azimuth from the sun and by the latitude mapping of Hillaire 2020, as `SkyView.sample` does, and blends the two around the sun by its exact elevation. Without the blend, each new view shifted the glow around a low sun by up to `16` display steps of `255` every `0.25 degrees`. With it, the sky stays within `1` step of a view computed for the exact elevation, measured halfway between two views at sun elevations from `1.4` to `30 degrees` and ice optical depths `0.1` and `0.3`;
- adds the ice view times the halo profile at the angle from the sun and the two halo maps, blended by the exact sun elevation between their two elevations;
- adds the sun's radiance from the sun table at the row of the sky texel, times the part of the pixel the sun's square covers. The outline uses the pixel's own direction, with one pixel of smoothing, so the sun glides with sharp edges like vanilla's sun quad, while its color follows the sky texels. Edges on sky texels had to step one texel about every `1.7 s` or fade whole rows of texels in and out, which looked like the square changing shape;
- grays the color by rain and thunder level with the formula of `ClientLevel.getSkyColor`, without the lightning flash, so the sky darkens like vanilla's;
- fades the color toward the fog color by the rain level. In snow `FogModification` sets the fog color, so the sky becomes the pack's snow fog. `PlayerWeatherCompatibilityModel` sets the rain level to `0.8` in snow and blizzards, so a fifth of the sky stays, with faint halos, as a fifth of vanilla's sun does;
- fades to the fog color from the horizon up to a texel direction with `y = 0.08`, about `4.6 degrees`, so terrain fog meets the sky without a seam;
- adds dithering of half a display step against banding, one value per texel so texels stay flat.

`CalculatedSkyEffects` replaces the overworld `DimensionSpecialEffects` through `RegisterDimensionSpecialEffectsEvent`. While the sky is drawn, `getSunriseColor` returns `null`, which removes vanilla's sunrise fan and its tint of the fog color (`FogRenderer.setupColor`). Otherwise it returns the vanilla value. Its `adjustLightmapColors` lights the terrain, as described under [Daylight](#daylight).

### Fog color

`FogRendererMixin.frostedheart$useCalculatedSkyFogColor` injects into `FogRenderer.setupColor` before its call to `getPriorityFogFunction`. While the calculated sky is drawn and the camera is not in a fluid, it sets the fog color to `CalculatedSkyRenderer.fogColor`, which comes from `SkyView.horizonDisplayColor`:

- the sky view table at elevation `0` in the azimuth the camera looks. It has no single scattering by ice, so the color stays smooth near the sun and across halo arcs;
- blended toward `SkyView.meanHorizonRadiance`, the horizon averaged over `64` azimuths, by `1 - h`, where `h` is the length of the look vector's horizontal part, so looking straight up does not pick a direction at random;
- blended between the two views around the sun and then tone mapped, encoded, and grayed by rain and thunder as the shader does.

This replaces vanilla's color from sky, biome fog, sunrise, render distance, and weather, which it computes before that call. Vanilla still applies what comes after: darkening for blindness, darkness, the void below `minimum height + 32`, and bosses, then night vision. `ViewportEvent.ComputeFogColor` fires after that, so `FogModification.renderFogColors` still sets its snow fog color whenever its fog density is above zero. Embeddium redirects the biome fog sampling earlier in `setupColor`, and Oculus injects at its tail, so neither conflicts. The fog color is also the clear color behind the world and the color the shader fades to at the horizon.

The calculated sky is skipped, and vanilla draws the sky as before, when:

- `CalculatedSky.enabled` is false;
- the level is not `minecraft:overworld`;
- an Oculus shader pack is in use (`IrisApi.isShaderPackInUse`, only called when Oculus is loaded);
- the camera is inside a fluid;
- no table has been uploaded yet, for the first second or so after enabling;
- building or drawing threw an exception, including a missing or broken dataset. It is logged once, and turning the sky on again with the command retries.

### Daylight

`CalculatedSkyEffects.adjustLightmapColors` replaces the sky light in the lightmap of `LightTexture.updateLightTexture` while the calculated sky is drawn. Vanilla's sky light at full sky light is `V = t * (0.95 d + 0.05)`, where `d` is `ClientLevel.getSkyDarken` (`0.2` at night, `1` by day, lower in rain and thunder) and the tint `t` is `d + 0.35 (1 - d)` in red and green and `1` in blue (`SkyView.vanillaSkyLight`). At sky light level `i` it is multiplied by `LightTexture.getBrightness(i)`. `SkyView.daylightColor` gives the color `D` that takes its place:

- `C = encode(1 - exp(-k L))` in each channel, where `L` is `SkyView.groundRadiance` blended between the two views around the sun, `k` is `exposure`, and `encode` is the sRGB encoding. `L` is the light that snow of albedo `0.8` sends back, from the sun, the sky, ice, and light reflected between snow and atmosphere, so `C` is the snow as the sky displays it below the horizon;
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

Optical depths are vertical, at `550 nm`. The spread is the geometric standard deviation of diameter. Narrow spreads give colored skies, and wide ones give white haze. An optical depth of `0.25` at the default size is about `30 Mt` of nanobots worldwide. The texel size is rounded so that a whole number of texels fits along each cube face (`SkySettings.actualTexelDegrees`), and `0` draws a smooth sky. Vanilla's sun texture is about `1 degree` per texel. The exposure also sets the daylight on the terrain: a higher exposure keeps it near its noon brightness until the sun is lower.

The renderer reads the config every frame, so changes from the command apply at once. Edits to the config file while the game runs depend on Forge reloading the file, which has not been tried. The existing key `Weather.skyRenderChanges` is unrelated. Its only reader, `DimensionSpecialEffectsMixin`, is not listed in `frostedheart.mixins.json`.

## 4. Command

`/calculatedSky` is a client command, also reachable as `/frostedheart calculatedSky`. It needs no permission and sends no packets. Every form prints the state, the settings, and the last build times of the tables, the halo profile, a halo map, and the sky view.

| Form | Effect |
|---|---|
| `/calculatedSky` | Prints the status |
| `/calculatedSky on` and `off` | Sets `enabled`, saves the config, and clears a previous failure |
| `/calculatedSky set <key> <value>` | Sets one of the six numeric keys and saves the config |
| `/calculatedSky reset` | Restores the six numeric keys to their defaults |

### Screenshot harness

`CalculatedSkyScreenshots` records and takes sets of screenshots for comparisons during development. Its subcommands are registered only while the constant `CalculatedSkyScreenshots.ENABLED` is `true`, and nothing else of it runs without them. Taking a set needs a singleplayer world.

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
| `variants` | List of `{name, sky, exposure, nanobotOpticalDepth, nanobotMedianNanometers, nanobotSpread, iceOpticalDepth, texelDegrees}`, clamped to the config ranges. Without variants each shot is taken with the sky off and on |
| `groups` | List of objects with their own `poses`, `times`, `weather`, and `variants`. Each group takes its own combinations, and a list it leaves out comes from the set. Without groups the set is one group |
| `hud` | `true` keeps the HUD and the hand. Default `false` |

Shots run group by group, in the order pose, weather, time, variant. For each shot the harness sets the config without saving the file, holds the day time, the rain and thunder levels, and the view direction on the client every frame, and teleports the player through the integrated server when the pose is somewhere else, flying when the player may fly. It waits at least `1 s`, plus `4 s` after a weather change and `2 s` after a move. It also waits until the player has arrived, the terrain is drawn after a move (`LevelRenderer.hasRenderedAllChunks`, which Embeddium answers from its own renderer), and `CalculatedSkyRenderer.isSettled` reports the tables, halo maps, and views built for the config and sun elevation. After `20 s` it takes the shot anyway and says so in the manifest. An open screen, such as chat or the pause menu, restarts the wait, and pausing on lost focus is off while a run lasts.

The frame is saved at the end of `TickEvent.RenderTickEvent` with vanilla's `Screenshot.grab`, into `screenshots/sky-shots/<set>-<date>_<time>/` as `NNN_<pose>_<time>_<weather>_<variant>.png`. `manifest.tsv` in that folder starts with the set, the world seed, and the window size. It then has one line per shot with the player's position, the sun elevation, the rain and thunder levels, the variant's config, a status (`settled`, `timed out waiting for ...`, or `sky not drawn, ...`), and the frames per second over the wait. When the run ends, the config, the HUD, the weather, the view direction, and the position are restored, and the server's next time update restores the day time within a second.

Only the client's time and weather are held, so the server's clock and climate keep running during a run. `snow` and `storm` only set the client's rain and thunder levels, so they show vanilla snowfall and the snow fades of the sky and the fog. FrostedHeart's storms from the climate (`ClientWeatherFrame`) need a world that is in a real storm, with `world` weather. The contributor's grid tool `ScreenshotGrid`, which lives with the preview outside this repository, lays a run out with `--manifest`, one row per pose, weather, and time and one column per variant.

## 5. Validation And Performance

`CalculatedSkyGameTests` (namespace `frostedheart_calculated_sky`) checks:

- a published Mie value and the Rayleigh limit;
- the normalization of the Rayleigh and Henyey-Greenstein phase functions;
- the datasets: each side face row and each tumbling table integrates to `1`, the path shares of each row and band add up to between `0.99` and `1` (the tracer drops light still inside a crystal after `40` interactions, at most `0.5%`), and the crystal shares add up to `1`;
- the halo profile's total and the halo maps' totals at sun elevations `30` and `8 degrees` against the offline preview, and that the brightest bin of the sun dogs sits between `0.25 degrees` inside and `1 degree` outside the minimum deviation from Bravais' effective index;
- the default sky at sun elevations `30` and `8 degrees` against the offline preview, with `0.5 degree` texels. The directions include the sun itself, `2 degrees` from it (the diffraction peak), and `22 degrees` from it (the 22 degree halo). The preview's `FastSky` uses the same ray steps, and its values were taken again on `2026-11-08` with its mode `skyreference`;
- that the ray steps of every row of the sky view, and of rays from `0.1` to `60 km` in every vertical direction, follow each other without gaps up to the ray's end and see the ice optical depth of a fine integration within `1%` for the view and `2%` for the others. Measured over `401` directions, the view is within `0.45%`, and rays from above that graze the band reach `1.5%`, where its `12` steps spread over a chord of about `340 km`;
- that the fog color is warmer toward a sun at `4.5 degrees` than away from it, and that looking straight up gives the same color in every direction;
- that the sun's radiance is zero below the horizon, has red at least green and green at least blue above it, and has more than ten times the red over blue at `2 degrees` that it has at `30 degrees`;
- that the daylight is within `0.95` of white at noon, never gets brighter as the sun sinks from `90` to `-18 degrees`, equals vanilla's night sky light at `-18 degrees`, and equals vanilla's sky light in full rain.

The preview is a separate Java program in the contributor's workspace, checked against a Monte Carlo reference and its own ray tracer of ice prisms. The port matches it within `1%`, the test's tolerance. The mixins, the shader, and the lightmap hook only run in the client, so the GameTests do not cover them.

Against the Monte Carlo reference, the lookup tables reach a mean color difference (CIE dE) of `2.1` to `4.0` for the default settings through the day, the largest with the sun high. The Monte Carlo reference also shows a wider glow around a low sun, from light that ice scatters forward more than once. The tables treat repeated scattering as even in all directions, so they miss it.

In the preview, the halo formulas match the ray tracer within a mean dE of `0.03` to `0.23` through the day. On `1 degree` texels, with the earlier coarser sampling, reading the map and profile once at each texel center matches traced and texel-averaged halos within a mean dE of `0.51` (`0.38` looking up). On `0.5 degree` texels with ice optical depth `0.3`, against dense formulas averaged over texels, the map reaches a mean dE of `0.29` with the sun at `22 degrees` and `0.34` at `50 degrees`, where the earlier sampling reached `0.36` and `0.44` and showed colored dots and streaks along the arcs.

Build times measured outside the game on `2026-11-05`, on one thread: tables `79 ms` warm and about `200 ms` on first use, sky view `19..60 ms`. In `runClient` on `2026-11-05`, before the ice and snow changes: tables `74..130 ms`, sky view `16..34 ms`, and the frame rate stayed at the `144` cap with the sky on and off. On `2026-11-06`, with median `300 nm` and spread `1.08`: tables `233 ms`, sky view `21..27 ms`, frame rate `140..144` both ways. Larger particles need more Mie terms, so the tables take longer. At normal day speed, the sun moves `0.25 degrees` about every `0.8 s`.

In the preview on `2026-11-07`, on one thread: a halo map `0.45..0.49 s` warm (fastest of three builds) and up to `0.9 s` on first use, the halo profile about `0.4 s`. A new map is needed every `0.5 degrees` of sun elevation, about every `1.7 s` at normal day speed, so the halos take about `28%` of one background thread in daytime by the preview's times. In `runClient` on `2026-11-07`, with the earlier sampling (`0.2 s` per map in the preview), ice optical depth `0.3`, and `0.5 degree` texels: tables `121.5 ms`, halo profile `110.5 ms`, halo map `73.6 ms` (about `4%` of one background thread), sky view `32.1 ms`, and `600..700` frames per second with the sky on and off. Later that day, with the denser sampling, ice optical depth `0.1`, and `0.5 degree` texels: tables `113.7 ms`, halo profile `122.5 ms`, halo map `287.9 ms` (about `17%` of one background thread), sky view `29.6 ms`. That map took `3.9` times the earlier one, more than the preview's ratio of `2.45`, from a single reading at a sun elevation that was not recorded.

The mod's own halo code, timed outside the game on `2026-11-07` on one thread at sun elevations from `2` to `70 degrees` (warm, high performance power mode): `202..235 ms` per map with `58..68 MB` allocated before the first speedup, `77..89 ms` after it, and `72..75 ms` once `IcePrism` merged its outputs. A sky view took `27..30 ms`, and `15..17 ms` once `SkyTables` worked out the steps of each view ray once per setting (`buildViewRays`) instead of once per texel, since they depend only on the row's elevation. Halo maps and sky views are built without allocating: `CalculatedSkyRenderer` keeps one `IceHaloMap.Workspace` for all builds and refills the halo map and `SkyView` that the previous state dropped, and `SkyTables` reuses scratch buffers for its views. New maps and views are allocated only on the first builds, after a settings change, and when the sun jumps by more than one map step. Of the `73 ms` per map, side face cones take about `24 ms`, end face to end face paths `26 ms`, Parry column side paths `17 ms`, end face to side face paths `5 ms`, and the blur `3 ms`. Of the `15.5 ms` per view, the view rays take about `13 ms` and the light that ice scatters onto the ground about `2.4 ms`. At normal day speed, with a map every `1.7 s` and a view every `0.8 s`, that is about `6%` of one background thread while the sun moves, down from about `8%`. In the laptop's automatic power mode the same builds sometimes took about twice as long, and below normal priority runs sometimes slowed to half speed partway through, so only alternating short runs were compared.

The ray steps, timed the same way on `2026-11-08` with two alternating runs of three rounds each: a sky view takes `18.9..19.9 ms` instead of `15.8..15.9 ms`, from the `12` extra steps of each view ray, and the tables `131..155 ms` instead of `74..98 ms`, from the extra rows and steps of the multiple scattering table.

On `2026-11-06` empireu also checked the render cost on a laptop RTX 4070, about a desktop RTX 4050 to 4060, and found it negligible. Exact GPU time of the draw and of the texture upload has not been measured.

## 6. Known Limits

- One sky for the whole overworld. Biome sky and fog colors are not used while the sky is drawn, except the snow fog of `FogModification`.
- Cloud color stays vanilla, so it can disagree with the sky, most visibly at sunrise and sunset.
- The daylight is one color for all terrain, as in vanilla, with no side lit by the sun. It reaches the night light at a sun elevation near `0 degrees`, where vanilla's light reaches its night level near `-6 degrees`, so evenings get dark earlier and mornings light later. The night light is vanilla's night sky light, constant and blue, not light from the moon in the model.
- The fog color is one color per frame, as in vanilla, so at the sides of a wide view the horizon can differ a little from it. It has no light from ice crystals, so toward a low sun it is a little darker than the sky above the horizon fade.
- Snow fades the sky toward the snow fog. Snow and storms do not change the model, and the same ice crystals and halos are present every clear day.
- Light on other paths is put at the sun, so what those paths draw is missing: light in through a side face and out through an end face (the circumhorizontal arc of a sun above `58 degrees`), reflections off end faces (the sun pillar), and the faint anthelic arcs of columns opposite the sun. The subsun lies below the horizon and is not drawn.
- A resource pack's `sun.png` is not used while the calculated sky is drawn, since the shader draws the sun. The pack does not replace it.
- There is no moonlight in the model, so the night sky is black apart from the vanilla moon and stars, and so is the fog at night. Vanilla's night fog is a very dark blue, `5..8%` of its day fog with the pack's colors.
- In snow, blindness does not darken the fog, because `FogModification` sets its snow fog color after vanilla's darkening. This does not depend on the calculated sky.
- `runClient` uses vanilla biomes. The `2026-11-06` run used a datapack that gives them the pack's sky and fog colors, but the pack's own world generation has not been tried.
