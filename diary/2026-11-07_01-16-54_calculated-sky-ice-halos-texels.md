# Calculated Sky Ice Crystal Halos On Sky Texels

- Time: `2026-11-07 01:16:54 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `partial`
- Scope: `content/climate/render/sky`, `FHConfig` client group `CalculatedSky`, `assets/frostedheart/shaders/calculated_sky.fsh`, `assets/frostedheart/datasets/ice_halos`, GameTest namespace `frostedheart_calculated_sky`

## Completed

- Replaced the three-part ice phase (diffraction peak, broad lobe, `22 degree` ring) with a halo display from hexagonal ice crystals: tumbling crystals, plates, wobbling plates, columns, and Parry columns. It draws the 22 and 46 degree halos, sun dogs, the parhelic circle, tangent arcs and the circumscribed halo, the circumzenithal arc, and Parry arcs. Behavior is in [the calculated sky docs](../docs/climate/calculated-sky.md), stage 3 of [the plan](../plans/2026-11-05_21-16-30_calculated-sky.md).
- The whole sky is drawn on square texels fixed to the sky, `0.5 degrees` by default, set by the new client config key `texelDegrees` and `/calculatedSky set texelDegrees`. `0` draws a smooth sky.
- After the second in-game look, `IceHaloFormulas` samples densely enough for `0.5 degree` texels: column axes and side face deviation every `0.25 degrees` (two steps per column of the side face table), end face entries `32`, `64` for Parry columns, plate mirror rotations `8`, and plate tilt step `0.5 degrees`. `IceHaloMap.blurSigma` blurs texels below `0.5 degrees`, and the smooth sky, as `0.5 degrees`, the size of the map's bins.
- Added `IceCrystalData`, which reads the ice crystal tables from `assets/frostedheart/datasets/ice_halos` once, and `IceHaloProfile`, `IceHaloMap`, `IceHaloFormulas`, and `IcePrism`, which build the halo display from them on the background executor.
- Ground irradiance now takes all single scattering by ice from the halo profile and map. The fog color has none, since the sky view table no longer holds any.

## Decisions

- empireu chose sky-fixed texels over screen-space ones, `1 degree` for now with `0.5` to try in game, raw data in the resources after ElectricalAge2's datasets, no preview animation, ice optical depth `0.1` with `0.3` to compare in game, and dropping the ring model without a toggle.
- The tables are ray traced offline, because tracing in game would cost seconds per sun elevation. The game evaluates formulas over them, about `0.45 s` per halo map on one thread in the preview. The generator is the `datasets` mode of the offline preview and stays outside the repository for now, described in the dataset's `src.md`. empireu may later move it into the mod so it can run on demand.
- Halo maps are built for sun elevations on a `0.5 degree` grid, and the shader blends the two around the sun by its exact elevation. When the sun leaves them, only the missing one is built. This replaced the planned fade over time since an upload, which would have lagged behind the sun, at the same cost.
- The halo map and profile are blurred by `0.45` of a texel combined with the sun disc, so arcs stay about equally bright as they move across texels. The shader reads them once at each texel center.
- Dithering is one value per texel, so texels stay flat, and the horizon fade uses the texel center.
- empireu chose `0.5 degree` texels as the default after seeing `1 degree` look coarse. The denser sampling for them was picked from a preview comparison against dense formulas averaged over texels. It costs `2.45` times the earlier sampling. Blurring as for `1 degree` texels would have been free but leaves the halos as soft as at `1 degree`. Making only the steps denser (`1.7` times) still left a dotted parhelic circle. Making everything denser (`1.8 s` per map) left the same faint traces as the chosen sampling. Splitting each `0.5 degree` column of the side face table into two steps matches a regenerated `0.25 degree` table, so the data did not change. The sampling is the same at every texel size.
- empireu asked about building the maps in a compute shader. It was set aside: Minecraft 1.20.1 asks for an OpenGL 3.2 core context and macOS stops at 4.1, so the CPU build would stay as a fallback. Core GLSL has no float atomic add, the CPU needs the map for the ground light, and the GameTests run without GL. Drawing points with additive blending into a float texture would avoid the first two problems if the CPU build becomes too slow.
- The side face table ships with effective index rows `0.005` apart below `2.0` (`241` rows, `0.8 MB`) instead of the preview's `0.002`, to keep the data near `1 MB`. Path shares ship per whole degree of sun elevation from `-6` to `90 degrees`.

## Validation

- `compileJava compileGameTestJava` with JDK 17: no errors.
- `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky`, first run on `2026-11-07`: 5 of 6 passed, including the profile, map totals, sun dog positions, and default sky against the preview. The dataset test failed on path shares that add up to `0.99972`, because the tracer drops light still inside a crystal after `40` interactions (at most `0.43%`, Parry columns under a sun at the zenith). The test now accepts `0.99..1`. The final working tree then passed 6 of 6.
- Preview `datasets` mode: side face rows integrate to `1` within `1e-4`, tumbling tables to `0.99977`. Reference radiances for the GameTests come from the same run, with the preview's halo map built from the dataset's index rows.
- `runClient` on `2026-11-07`, first look by empireu at about the spots of the before shots (times `1000`, `12500`, `73000`), default settings: in the morning the 22 degree halo, sun dogs, parhelic circle, upper tangent arc, and lower tangent arc show around the vanilla sun. At sunset the sun dogs and the V of the upper tangent arc show. In snow the halos are almost gone, with faint sun dogs. With ice optical depth `0.3` the halos are much brighter and the sky turns whitish. The `1 degree` texels look coarse, and empireu leans toward `0.5`. Facing the sun at sunset turns the fog red, by yaw but not by pitch. The before shot at the same time has the same red band, so this comes from the fog color of stage 2, not from this change.
- Second in-game look by empireu the same day: no popping as the sun moves. `/calculatedSky` at ice depth `0.3` and `0.5 degree` texels: tables `121.5 ms`, halo profile `110.5 ms`, halo map `73.6 ms`, sky view `32.1 ms`. Frame rate `600..700` with the sky on and off. At `0.5` and `0.25 degree` texels the arcs of oriented crystals break into colored speckles (strongest at `0.25`, slight at `0.75`), while the 22 degree halo stays clean, because the map's sampling and blur were set for `1 degree` texels.
- Preview mode `halftexels` with ice optical depth `0.3` on `0.5 degree` texels, fastest of three builds on one thread: the earlier sampling `185..200 ms` per map, mean dE `0.36` (sun `22 degrees`) and `0.44` (sun `50 degrees`), with a comb under the upper tangent arc, a dotted parhelic circle, and a dotted line below the sun. The chosen sampling `452..485 ms`, mean dE `0.29` and `0.34`, with only faint dots below a low sun and faint streaks under the circumscribed halo.
- With the denser sampling, the `0.5 degree` default, and reference values regenerated by the preview's `datasets` mode (datasets unchanged byte for byte): `compileJava compileGameTestJava` with no errors, and `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` passed 6 of 6.
- Documentation: rewrote `docs/climate/calculated-sky.md` for the halos and texels, in American spelling, updated its row in `docs/climate/README.md`, and updated the plan. After the second look, the docs give the denser sampling, the blur limit, the `0.5` default, and the preview's times.

- Third in-game look by empireu the same day, with the denser sampling, ice optical depth `0.1`, and `0.5 degree` texels: "It looks fine." `/calculatedSky`: tables `113.7 ms`, halo profile `122.5 ms`, halo map `287.9 ms`, sky view `29.6 ms`. The map took `3.9` times the earlier `73.6 ms`, more than the preview's `2.45`, from one reading at an unrecorded sun elevation. empireu noticed that at ice optical depth `0.1` the square vanilla sun can be seen within the calculated sun glow.

## Remaining

- The square vanilla sun showing within the calculated sun glow at ice optical depth `0.1`. Vanilla draws its sun on top of the calculated dome, so a glow that is not bright enough leaves the square visible. Not investigated yet.
- The halo map build time in game against the preview, at a known sun elevation, since one reading came out higher than the preview predicts.
- Missing halo features, because their light paths are put at the sun: the circumhorizontal arc, the sun pillar, and the anthelic arcs.
- Halos on every clear day, not tied to weather yet.
- Earlier remaining items still apply: blindness in clear weather, the ring or halos through a blizzard, and the edge cases in the plan.
