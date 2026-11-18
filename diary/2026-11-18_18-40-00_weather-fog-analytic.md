# Weather Fog, Analytic Per Pixel

- Time: `2026-11-18 18:40:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `in progress`, uncommitted on `feature/weather` after `77d8d0caa`.
  - The first look in game found two problems, under Remaining.
  - The shelter part is being replaced by a snow-share field, under "Shelter redesigned".
- Scope:
  - new package `content/climate/render/fog`
  - `weather_fog.fsh` and `weather_fog.glsl`
  - `FHShaders`
  - `CalculatedSkyRenderer.horizonColors`
  - `DustColumns`
  - `LightPillarRenderer.fillPillars`
  - `LightPillarSources` (visibility only)
  - `CalculatedSkyClientCommand`
  - `FogModification`
  - `LevelRendererMixin`
  - new `LevelRendererAccess` and its entry in `frostedheart.mixins.json`
  - new GameTest class `FogModelGameTests`
  - stage 1 of the weather rendering plan

## Completed

**Model.** Extinction falls off exponentially with height above sea level:

`sigma(y) = sigmaRef exp(-(y - yRef) / H)`

- `sigmaRef = 3.912 / V` per block, where `V` is the visibility in blocks at 2% contrast.
- `H` is `fogHeightScale`.

The optical depth from the eye along a segment of length `t` and rise `dy` is `tau = sigma(eye) t f(dy / H)`, with `f(x) = (1 - exp(-x)) / x`:
- The series `1 - x/2 + x^2/6` is used below `|x| = 1e-3`.
- Exponents are clamped at `60`, and `tau` at `64`.
- `HeightFog` holds this in Java, and `weather_fog.glsl` in GLSL.

**Weather source.** The renderers read the weather only through `WeatherConditions`. Today `ClientMockWeather` implements it from the client's levels:
- snowfall intensity `clamp(rain / 0.8)`
- storm intensity `min(clamp(thunder / 0.8), snowfall)`
- `sigmaRef = snowfall * lerp(storm, 3.912 / fogSnowVisibility, 3.912 / fogBlizzardVisibility)`

A provider synced from the server would replace it in `WeatherFogRenderer.weather()`. Wind is left for stage 3.

**The pass.** `WeatherFogRenderer` draws one full-screen pass at `AFTER_WEATHER` with `HIGH` priority. It runs while `isActive` holds: `fogEnabled` on, the calculated sky allowed and drawn, and graphics not Fabulous. It reads the main depth through a color-only framebuffer and rebuilds each scene point from the eye under view bobbing, as the light pillars do.

- **Color:** the fog color is `mix(Hz, W, s)`, blended by `1 - exp(-tau)`.
  - `Hz` is the sky's displayed horizon color for the pixel's azimuth, in a 64 by 1 texture filled each frame from `CalculatedSkyRenderer.horizonColors`.
  - `W` is the old fog's day and night snow color.
  - `s` is the snowfall intensity.
- **Shelter:** a pixel takes the full fog unless both the eye and the scene point are below the first open height of their column (`MOTION_BLOCKING`), in which case it takes the eye's openness.
  - The scene point is read from the dust's 64 by 64 column window, moved `1/16` block toward the eye first.
  - Unread columns and columns outside the window count as open.
  - The eye's openness glides between 0 and 1 over about 1 s.
  - `DustColumns` now also tracks while `fogEnabled` is on.

**Vanilla's fog.**
- Outside Fabulous, `FogModification` steps aside while the new fog is active.
- In `ViewportEvent.RenderFog`, for terrain, `FogCullingDistance` finds the smallest cylinder distance beyond which every block position within the build heights has `tau >= ln 510`.
  - It searches the wall at that distance (33 samples, then a golden-section refinement), the two caps straight up and down, and a bisection on the distance.
  - `fogEnd` rounds the result up to 8 blocks, with 12 blocks of hysteresis on the way down.
  - When that end is below the event's far plane, it becomes vanilla's fog end, with the start 2 blocks before it, so Embeddium culls beyond it and Flywheel never divides by zero.
  - This applies only while the eye's openness is exactly 1, so caves and houses keep vanilla's fog.
- In `ViewportEvent.ComputeFogColor`, vanilla's fog color becomes the new fog's color in the look direction, in a snowy biome.

**Snow, pillars, command.**
- Vanilla's snow is cancelled at its call while the new fog is active and drawn again after the fog pass through `LevelRendererAccess`. Otherwise the pass fogged the flakes as if they stood at the scene behind them. Around the redraw, vanilla's fog is set to a sphere from `0` to the visibility at the eye.
- The mixin leaves precipitation alone when a white curtain owns it.
- Light pillars fade by `HeightFog` while the fog is drawn.
- `/calculatedSky` has a `Fog draw` timer line. `/calculatedSky fog` prints the eye's extinction, visibility and openness, the opaque distance, the fog end and far plane, whether the bound applied, and the bytes the fog's handlers allocated in the last frame.

## Decisions

- Three implementer pieces, each reviewed before the next started:
  1. the math and its tests
  2. the pass
  3. the hooks
- Fabulous keeps the old fog. It composites translucent layers and weather after `AFTER_WEATHER`.
- From the reviews:
  - **Fixed:**
    - Vanilla snow was deferred even when a white curtain owned precipitation.
    - The color mix had no biome check.
    - The byte count missed the fog events.
  - **Rejected:**
    - "The bound overrides blindness and darkness": it only shortens the far plane, and theirs is shorter.
    - The one-frame lag of the openness between the bound and the pass.
    - A redundant `depthMask(false)` around the redraw, since vanilla sets the mask itself.

## Validation

- **Tests:** `All 123 required tests passed` for `frostedheart_weather,frostedheart_calculated_sky`. `FogModelGameTests` compares:
  - `opticalDepth` with a 10,000-step integral, within `1e-4` relative
  - the series switch, within `1e-9`
  - the culling distance with a brute-force search over `dy` in `0.25` block steps, over 72 cases, with the largest overshoot `0.23` blocks
  - every position within 64 blocks beyond the distance, against the opaque optical depth
  - the hysteresis
  - the mock at the five harness weathers
- **Static checks:** the build and `glslangValidator` pass after the review fixes.
- **First look in game** (empireu): `weather-before` was taken again with this build, as runs `weather-before-2026-11-18_18-15-03` and `_18-18-50`, against `_17-22-57` from stage 0.

## Follow-up the same evening

Piece 4 addressed both problems below. It is not yet seen in game.

- **Shelter by ray.** The fog counts only on the open parts of the segment from the eye to the scene point.
  - A 2D grid walk through the column window splits each column at the height where the segment crosses the column's first open height. Columns outside the window and unread columns count as open.
  - The closed form gives each open part's optical depth as the difference of two `tau` values along the same ray.
  - `ShelterRay` holds the walk in plain Java, and `weather_fog.fsh` mirrors it step by step.
  - The walk runs only while the eye's openness is below 1, as `mix(open, full, openness)`. Outdoors the pass is as before.
  - The point test and its `1/16` block nudge are gone.
- **Defaults.** `fogSnowVisibility` is now `300`, and `fogBlizzardVisibility` `80`.
- **Validation:**
  - 5 new GameTests compare the walk with a 20,000-step brute force on a synthetic house and canopy, and check the reported case: two faces of the canopy seen from inside take fog within 25% of each other.
  - `All 13 required tests passed` in `frostedheart_weather`.
  - The build and `glslangValidator` pass.

## Shelter redesigned, later the same evening

empireu rejected the column model, by point or by ray walk, as unsound. It fails in several cases:
- open-sided cover counts as enclosed
- caves beyond the 32-block window count as open
- with the eye in the open, interiors and cave mouths take the full fog
- unread columns count as open after a teleport

Vanilla's sky light is no substitute. It passes glass, panes, fences, doors and trapdoors at full strength (`AbstractGlassBlock` and `CrossCollisionBlock` override `propagatesSkylightDown`).

**The replacement** is designed and reviewed, not implemented. It is section 4.5 of the weather plan in the contribution workspace.

- **The field.** The fog's extinction is `sigma(y)` times a snow share `E`, a field over space that depends only on the blocks.
  - It is the share of snow that reaches a point from open sky. This is empireu's idea of tracing snow particles backward, solved on the block grid.
- **The solve.** A shortest path, as Minecraft's sky light is computed:
  - in a 128-block volume around the eye
  - over per-face coverage masks of each block's outline shape
  - with integer costs
  - capped at 24 blocks
  - stored as a distance d, with `E = exp(-d / L)` and a decay length L from the weather
- **The shader** integrates `sigma * E` exactly by walking cells. Beyond a drawn radius, it uses the column rule.
- **Prior art.** A web research report found no shipped game with a 3D shelter field. Penetration lengths from snow fence data are inferences only, so L is a tuning value.
- **Plan review.** Four reviewers checked the plan. Besides fixes to the design, they found a bug in this entry's code:
  - `FogCullingDistance` searches up to the maximum build height, and straight up this fog never becomes opaque: about 5.1 optical depths by y = 320 at V = 80.
  - So with the defaults the bound never applied, and the Embeddium check under Remaining, not yet done, would have failed.
  - The fix is to search up to the highest loaded block. It is planned for the same stage.
- **Kept code.** Nothing here is discarded:
  - `ShelterRay` becomes the far-field walk.
  - The eye's openness and the column window uniforms go away.
  - The `DustColumns` changes go back.

## Remaining

- **Shelter** (superseded by the redesign above). From inside a house (pose `house-window-1`, blizzard), the outer faces of a tree's leaves turn white while the leaves behind the holes and the trunk under them stay dark. The outer faces sit next to open columns and take the full fog. The rest is under the canopy, counts as sheltered like the eye, and takes none. The rule "both sheltered, no fog" is wrong for any point outside the eye's own shelter. Light from the inner leaves crosses the same open air as light from the outer ones. The fix is to count the fog only on the open part of the ray, testing the column window along it, which the plan had left for stage 3.
- **Thickness** (defaults raised in the follow-up, still to be tuned). The defaults fog much more than the old fog near the eye. The old fog is linear, from `0.03` to `0.1` of the far plane in a blizzard: `7.7` to `25.6` blocks at empireu's render distance of 16 chunks. The new one is exponential, so at the same visibility it hides 78% at 10 blocks, where the old fog hid 13% (pose `forest_edge`, blizzard). The match at render distance 12 compared the old fog's end with the new fog's 2% point, which is the wrong comparison. The visibilities need to be tuned in game.
- Not yet checked:
  - motion (stepping out of a door, flying up, changing the render distance, pausing)
  - Embeddium's section count
  - a Create contraption
  - GPU time and bytes per frame
- **Documentation:** `docs/climate/calculated-sky.md` (the four settings) and a weather fog document get written once this stage is accepted.
