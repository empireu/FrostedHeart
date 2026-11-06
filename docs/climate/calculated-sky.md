# Calculated Sky

- Status: `Partial`
- Last verified: `2026-11-06`, against source and the GameTests below. The first version, with one ice lobe and no snow fade, was seen in `runClient` on `2026-11-05`. The three-part ice phase and a snow fade toward grey were seen in `runClient` on `2026-11-06`. The fog colour from the sky and the snow fade toward the fog colour were seen in `runClient` later that day.
- Scope: the optional overworld sky colour calculated from air, titanium dioxide nanobots, and ice crystals, the fog colour taken from it outside snow, and the client config and client command. Clouds and lightmap are unchanged.
- Primary code anchors: `CalculatedSkyRenderer`, `CalculatedSkyEffects`, `CalculatedSkyClientCommand`, `SkyTables`, `SkyView`, `SkyAtmosphere`, `SkyParticleOptics`, `SkyPhase`, `SkySettings`, `LevelRendererMixin.frostedheart$drawCalculatedSky`, `FogRendererMixin.frostedheart$useCalculatedSkyFogColor`, `FHClientEventsMod.registerDimensionEffects`, `assets/frostedheart/shaders/calculated_sky.fsh`

The feature is off by default. The plan and later stages are in [the calculated sky plan](../../plans/2026-11-05_21-16-30_calculated-sky.md).

## 1. Model

The sky is a sphere of radius `6360 km` with an atmosphere up to `6460 km`, seen from `100 m` above snow. Each colour channel is one band, centred at `680`, `550`, and `440 nm`.

| Layer | Profile | Source of its numbers |
|---|---|---|
| Air (Rayleigh) | Exponential, scale height `8 km` | Hillaire 2020, Table 1 |
| Ozone | Tent, peak at `25 km`, half width `15 km` | Hillaire 2020, Table 1 |
| Ground aerosol | Exponential, scale height `1.2 km`, asymmetry `0.8` | Hillaire 2020, Table 1 |
| Nanobots | Gaussian, centre `25 km`, width `6 km` | Mie theory for rutile spheres, sizes from `design/world-design.md` |
| Ice crystals | Gaussian, centre `11 km`, width `1.2 km`, phase function in three parts (below) | Neutral colour, not calculated from crystal shapes |

The ice phase function has three parts, set in `SkyAtmosphere` and evaluated in `SkyPhase`:

| Part | Share of ice scattering | Shape | Source constant |
|---|---:|---|---|
| Diffraction peak around the sun | `50%` | Henyey-Greenstein, asymmetry `0.99`, half width about `0.4 degrees` | `ICE_DIFFRACTION_FRACTION`, `ICE_DIFFRACTION_ASYMMETRY` |
| Broad lobe | `40%` | Henyey-Greenstein, asymmetry `0.48` | `ICE_BROAD_FRACTION`, `ICE_BROAD_ASYMMETRY` |
| Halo ring | `10%` | Peak at `21.7`, `22.0`, and `22.4 degrees` for red, green, and blue, inner width `0.25 degrees`, outer width `1.2 degrees` | `ICE_HALO_FRACTION`, `SkyPhase.haloRing` |

The whole function has a mean cosine of `0.78`, the usual value for ice crystals. Large particles diffract about half of the light they remove, which sets the peak's share. The ring's radius comes from the `60 degree` prism angle of hexagonal ice and does not depend on settings. The sky view table holds only the broad lobe (`SkyPhase.broadIce`). The peak and the ring (`SkyPhase.narrowIce`) are too narrow for its texels and are added per pixel.

`SkyParticleOptics.forDistribution` averages Mie efficiencies over a log-normal size distribution truncated to `50..1000 nm` and over nine wavelengths per band. The refractive index is the rutile formula `n^2 = 5.913 + 0.2441 / (lambda^2 - 0.0803)` with `lambda` in micrometres. Nanobots do not absorb.

`SkyTables` follows Hillaire 2020: a transmittance table (`256 x 64`, `40` steps), a multiple scattering table (`32 x 32`, `20` steps, `64` directions), and a sky view table (`200 x 100`, `30` steps). Multiple scattering is computed over a black ground. Light reflected back and forth between snow and atmosphere is added afterwards:

```text
L(view)  = L_black_ground(view) + L_ground * G(view)
L_ground = (A / pi) * E_0 / (1 - A * S)
S        = 2 * integral over mu from 0 to 1 of G(mu) * mu
```

| Symbol | Meaning | Value |
|---|---|---|
| `A` | Snow albedo | `0.8`, source constant `SkyAtmosphere.GROUND_ALBEDO` |
| `E_0` | Downward irradiance on a black ground: direct sun, the sky view table, and the peak and ring of the ice (the peak as light from the sun's direction, the ring sampled at `64` points of its circle) | Computed per sky view |
| `G(view)` | Radiance at the camera per unit radiance of glowing ground | Table of `65` view cosines |
| `S` | Spherical albedo of the atmosphere | Computed per settings |

Radiance is relative to a sun of irradiance `1` in each band. The shader displays it as `1 - exp(-exposure * L)`, encoded to sRGB.

## 2. Runtime

| Step | Where | When |
|---|---|---|
| Transmittance, multiple scattering, `G`, `S` | `new SkyTables(settings)` on `Util.backgroundExecutor()` | When a setting changes |
| Sky view and ice view for one sun elevation | `SkyTables.computeView` on `Util.backgroundExecutor()` | When the sun has moved more than `0.25 degrees`, unless both elevations are below `-18 degrees` |
| Upload as two `RGB16F` textures | `CalculatedSkyRenderer.uploadTable`, render thread | When a build finishes |
| Draw | `calculated_sky.fsh`, one full-screen quad | Every frame while drawn |

Only one build runs at a time. The sky view is mirror symmetric around the sun's azimuth, so only half of it is integrated.

`LevelRendererMixin.frostedheart$drawCalculatedSky` injects after the first `VertexBuffer.unbind()` in `LevelRenderer.renderSky`, which is right after vanilla draws its sky dome. Vanilla still draws the sun, moon, stars, and the dark dome below the horizon on top. Vanilla's own exits for fog, lava, powder snow, blindness, and darkness still apply.

The shader:

- looks up the sky view by azimuth from the sun and by the latitude mapping of Hillaire 2020, as `SkyView.sample` does;
- adds the ice diffraction peak and the `22 degree` halo ring per pixel from the ice view, as `SkyPhase.narrowIce` does, because a texel of the sky view spans about `1.8 degrees`;
- greys the colour by rain and thunder level with the formula of `ClientLevel.getSkyColor`, without the lightning flash, so the sky darkens like vanilla's;
- fades the colour toward the fog colour by the rain level. In snow `FogModification` sets the fog colour, so the sky becomes the pack's snow fog. `PlayerWeatherCompatibilityModel` sets the rain level to `0.8` in snow and blizzards, so a fifth of the sky stays, with a faint halo, as a fifth of vanilla's sun does;
- fades to the fog colour from the horizon up to a view direction with `y = 0.08`, about `4.6 degrees`, so terrain fog meets the sky without a seam;
- adds dithering of half a display step against banding.

`CalculatedSkyEffects` replaces the overworld `DimensionSpecialEffects` through `RegisterDimensionSpecialEffectsEvent`. While the sky is drawn, `getSunriseColor` returns `null`, which removes vanilla's sunrise fan and its tint of the fog colour (`FogRenderer.setupColor`). Otherwise it returns the vanilla value.

### Fog colour

`FogRendererMixin.frostedheart$useCalculatedSkyFogColor` injects into `FogRenderer.setupColor` before its call to `getPriorityFogFunction`. While the calculated sky is drawn and the camera is not in a fluid, it sets the fog colour to `CalculatedSkyRenderer.fogColor`, which comes from `SkyView.horizonDisplayColor`:

- the sky view table at elevation `0` in the azimuth the camera looks, without the diffraction peak and the ring, so the colour stays smooth near the sun;
- blended toward `SkyView.meanHorizonRadiance`, the horizon averaged over `64` azimuths, by `1 - h`, where `h` is the length of the look vector's horizontal part, so looking straight up does not pick a direction at random;
- tone mapped, encoded, and greyed by rain and thunder as the shader does.

This replaces vanilla's colour from sky, biome fog, sunrise, render distance, and weather, which it computes before that call. Vanilla still applies what comes after: darkening for blindness, darkness, the void below `minimum height + 32`, and bosses, then night vision. `ViewportEvent.ComputeFogColor` fires after that, so `FogModification.renderFogColors` still sets its snow fog colour whenever its fog density is above zero. Embeddium redirects the biome fog sampling earlier in `setupColor`, and Oculus injects at its tail, so neither conflicts. The fog colour is also the clear colour behind the world and the colour the shader fades to at the horizon.

The calculated sky is skipped, and vanilla draws the sky as before, when:

- `CalculatedSky.enabled` is false;
- the level is not `minecraft:overworld`;
- an Oculus shader pack is in use (`IrisApi.isShaderPackInUse`, only called when Oculus is loaded);
- the camera is inside a fluid;
- no table has been uploaded yet, for the first fraction of a second after enabling;
- building or drawing threw an exception. It is logged once, and turning the sky on again with the command retries.

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

Optical depths are vertical, at `550 nm`. The spread is the geometric standard deviation of diameter. Narrow spreads give coloured skies, and wide ones give white haze. An optical depth of `0.25` at the default size is about `30 Mt` of nanobots worldwide.

The renderer reads the config every frame, so changes from the command apply at once. Edits to the config file while the game runs depend on Forge reloading the file, which has not been tried. The existing key `Weather.skyRenderChanges` is unrelated. Its only reader, `DimensionSpecialEffectsMixin`, is not listed in `frostedheart.mixins.json`.

## 4. Command

`/calculatedSky` is a client command, also reachable as `/frostedheart calculatedSky`. It needs no permission and sends no packets. Every form prints the state, the settings, and the last build times.

| Form | Effect |
|---|---|
| `/calculatedSky` | Prints the status |
| `/calculatedSky on` and `off` | Sets `enabled`, saves the config, and clears a previous failure |
| `/calculatedSky set <key> <value>` | Sets one of the five numeric keys and saves the config |
| `/calculatedSky reset` | Restores the five numeric keys to their defaults |

## 5. Validation And Performance

`CalculatedSkyGameTests` (namespace `frostedheart_calculated_sky`) checks a published Mie value, the Rayleigh limit, the normalisation of all phase functions, the mean cosine of the ice phase function, and the default sky at sun elevations `30` and `8 degrees` against the offline preview. The directions include the sun itself, `2 degrees` from it (the diffraction peak), and `22 degrees` from it (the ring). The preview is a separate Java program in the contributor's workspace, checked against a Monte Carlo reference. The port matches it within `1%`, the test's tolerance. A fourth test checks that the fog colour is warmer toward a sun at `4.5 degrees` than away from it, and that looking straight up gives the same colour in every direction. The mixin itself only runs in the client, so the GameTests do not cover it.

Against the Monte Carlo reference, the lookup tables reach a mean colour difference (CIE dE) of `2.1` to `4.0` for the default settings through the day, the largest with the sun high. The Monte Carlo reference also shows a wider glow around a low sun, from light that ice scatters forward more than once. The tables treat repeated scattering as even in all directions, so they miss it.

Build times measured outside the game on `2026-11-05`, on one thread: tables `79 ms` warm and about `200 ms` on first use, sky view `19..60 ms`. In `runClient` on `2026-11-05`, before the ice and snow changes: tables `74..130 ms`, sky view `16..34 ms`, and the frame rate stayed at the `144` cap with the sky on and off. On `2026-11-06`, with median `300 nm` and spread `1.08`: tables `233 ms`, sky view `21..27 ms`, frame rate `140..144` both ways. Larger particles need more Mie terms, so the tables take longer. At normal day speed, the sun moves `0.25 degrees` about every `0.8 s`.

On `2026-11-06` empireu also checked the render cost on a laptop RTX 4070, about a desktop RTX 4050 to 4060, and found it negligible. Exact GPU time of the draw and of the texture upload has not been measured.

## 6. Known Limits

- One sky for the whole overworld. Biome sky and fog colours are not used while the sky is drawn, except the snow fog of `FogModification`.
- Cloud colour and lightmap stay vanilla, so they can disagree with the sky, most visibly at sunrise and sunset.
- The fog colour is one colour per frame, as in vanilla, so at the sides of a wide view the horizon can differ a little from it.
- Snow fades the sky toward the snow fog. Snow and storms do not change the model, and ice crystals are present every clear day.
- There is no moonlight in the model, so the night sky is black apart from the vanilla moon and stars, and so is the fog at night. Vanilla's night fog is a very dark blue, `5..8%` of its day fog with the pack's colours.
- In snow, blindness does not darken the fog, because `FogModification` sets its snow fog colour after vanilla's darkening. This does not depend on the calculated sky.
- `runClient` uses vanilla biomes. The `2026-11-06` run used a datapack that gives them the pack's sky and fog colours, but the pack's own world generation has not been tried.
