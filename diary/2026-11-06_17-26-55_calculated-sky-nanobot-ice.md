# Calculated Sky From Nanobot And Ice Scattering

- Time: `2026-11-06 17:26:55 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `partial`
- Scope: `content/climate/render/sky`, `LevelRendererMixin`, `FogRendererMixin`, `FHClientEventsMod`, `FHShaders`, `FHConfig` client group `CalculatedSky`, `assets/frostedheart/shaders/calculated_sky.fsh`, GameTest namespace `frostedheart_calculated_sky`

## Completed

- Added an optional overworld sky calculated with the lookup tables of Hillaire 2020. It is built from air, ozone, ground aerosol, titanium dioxide nanobots, and ice crystals, plus a term for light reflected between snow and atmosphere. Nanobot optics come from Mie theory for rutile, with sizes from `design/world-design.md`. It is off by default. Behaviour is in [the calculated sky docs](../docs/climate/calculated-sky.md), and later stages are in [the plan](../plans/2026-11-05_21-16-30_calculated-sky.md).
- The sky toggles at runtime through the client config group `CalculatedSky` and the client command `/calculatedSky`, so before and after can be compared in one game instance.
- After the first in-game run, split the ice phase function into a diffraction peak, a broad lobe, and the `22 degree` ring, and added a fade in snow.
- After comparison screenshots, the fog colour outside snow comes from the sky's horizon in the direction the camera looks (stage 2 of the plan), and the sky in snow fades toward the snow fog instead of a computed grey.

## Decisions

- Tables are built on the CPU on `Util.backgroundExecutor()`, because the Java model was checked against an offline Monte Carlo reference and a GLSL port would need checking again. This also avoids float render targets.
- The dome is drawn by an inject after the first `VertexBuffer.unbind()` in `LevelRenderer.renderSky`, so vanilla still draws the sun, moon, and stars and keeps its exits for fog, blindness, and similar effects. Oculus `1.20.1-1.7.0` and Embeddium `0.3.31` only inject into `renderSky` and `getSunriseColor`, so they do not conflict with it. `CalculatedSkyEffects` removes the sunrise fan while the sky is drawn.
- The calculated sky is skipped while an Oculus shader pack is in use, in other dimensions, and with the camera in a fluid.
- Ice: `50%` diffraction peak (Henyey-Greenstein, asymmetry `0.99`), `40%` broad lobe (asymmetry `0.48`), `10%` ring, giving the usual ice asymmetry of `0.78`. The single lobe with asymmetry `0.8` it replaces made the halo look too big. The ring's radius comes from the prism angle of ice, so it was kept at `22 degrees`.
- Snow first faded the radiance toward the grey of a uniform sky that gives the snow the same light. Screenshots showed that grey was darker and more neutral than the pack's lavender snow fog, which left a band at the horizon. The sky now fades toward the fog colour by the rain level, after vanilla's rain greying, so in snow it meets the fog that `FogModification` sets.
- The fog colour is replaced by a mixin in `FogRenderer.setupColor` before its call to `getPriorityFogFunction`, not in `ViewportEvent.ComputeFogColor`. The event fires after vanilla darkens the fog for blindness, darkness, the void, and bosses, so replacing the colour there would undo those. At the mixin's point, vanilla's later steps and the event, with the snow fog of `FogModification`, still apply. The colour leaves out the diffraction peak and the ring, so looking at a low sun does not turn all fog white.
- Removing the sunrise fan also removed vanilla's orange fog tint toward the sun, which left a cold band under the sunset sky. The fog colour from the sky replaces that tint.
- Single scattering in the style of Unity's The Blacksmith was compared offline and rejected as too dark and too olive over snow.

## Validation

- `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` on `2026-11-06`: 4 of 4 passed. The tests check a published Mie value, the Rayleigh limit, normalisation of every phase function, the ice asymmetry, the default sky against the offline preview at two sun elevations, and the fog colour toward and away from a low sun. The mixin only runs in the client. Its refmap resolved both `setupColor` and the `getPriorityFogFunction` call.
- Offline preview against its Monte Carlo reference with default settings: mean CIE dE `2.1..4.0` through the day.
- `runClient` on `2026-11-05`, run by empireu on the first version: the toggle worked both ways, the halo was centred on the vanilla sun, there was no seam at the horizon, the night looked like vanilla, and the frame rate stayed at the `144` cap with the sky on and off. Tables took `74..130 ms` and sky views `16..34 ms`. The halo looked too big and stayed visible through snowfall, which led to the two changes above.
- `runClient` on `2026-11-06`, run by empireu after the ice and snow changes, with median `300 nm` and spread `1.08`: the halo is a thin ring with a red inner edge and a darker inside, and the glow stays close to the sun. In snow the sky is flat grey with a faint ring, because FrostedHeart sets the rain level to `0.8` in snow. A change to finish the fade at `0.8` was tried and undone, because empireu likes the faint ring and it matches vanilla's sun, which also stays at a fifth. Frame rate `140..144` with the sky on and off, capped. Tables `233 ms`, sky views `21..27 ms`. The world had a datapack that gives vanilla biomes the pack's sky and fog colours. empireu also checked the render cost on a laptop RTX 4070 and found it negligible.
- Clean comparison screenshots by empireu on `2026-11-06` at times `1000`, `12500`, and `73000` (snow), with default settings: the morning matched the pack's sky colour with the halo added. At sunset a cold grey-blue fog band sat under a warm grey-brown sky, and in snow the grey sky met the lavender snow fog in a band. Both led to the fog changes above.
- Retaken shots after the fog changes, same times and views: the sunset horizon is now a warm rose that meets the sky, the snow sky meets the snow fog with only the faint ring left, and the morning is unchanged. Grid made with a small screenshot grid tool in the contributor's workspace. At night the fog is black, close to vanilla's very dark blue. Blindness, tried after the snow shots, gave fog at about `1 m` that was not black. From the code: vanilla makes blindness fog black and `5` blocks deep, and while snow fog is active `FogModification` sets its snow colour after that and shortens the fog further. The calculated sky's colour goes through vanilla's darkening, so in clear weather it should turn black.
- The full `frostedheart` GameTest namespace was not rerun.
- Documentation: added `docs/climate/calculated-sky.md` and its row in `docs/climate/README.md`, and the plan.

## Remaining

- Try blindness in clear weather, where the fog should turn black as in vanilla.
- Check whether the ring shows through a blizzard, where thick cloud should hide it. If it does, finish the fade by the thunder level, which only blizzards set.
- Compare pixels with the preview, and go through the edge cases listed in the plan, now including blindness, darkness, and night vision with the fog colour.
- Later stages in the plan: daylight colour, aerial perspective, and ice that follows weather.
