# Weather Fog Groundwork

- Time: `2026-11-18 17:10:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done`, committed as `77d8d0caa`
- Scope:
  - `SkyGlPass`, `SkyDrawTimer` and `SkySetting` in `content/climate/render/sky`
  - new GameTest class `FogSettingsGameTests` in `content/climate/render/fog`
  - branch `feature/weather`, made from `feature/calculated-sky` at `1dc32992b`
  - stage 0 of the weather rendering plan, which is kept outside the repository

## Completed

- `SkyGlPass` and `SkyDrawTimer` are public, so passes outside the sky package can use the shared full-screen quad, the GL state saving and the GPU timer. Nothing else in them changed.
- Four settings appended to `SkySetting`, each `Rebuilds.NOTHING`:

  | Key | Kind | Default | Range |
  |---|---|---|---|
  | `fogEnabled` | switch | on | |
  | `fogSnowVisibility` | number | `60` blocks | `8` to `2048` |
  | `fogBlizzardVisibility` | number | `20` blocks | `4` to `2048` |
  | `fogHeightScale` | number | `128` blocks | `16` to `1024` |

  No code read them at this point.
- New GameTest namespace `frostedheart_weather`, with `FogSettingsGameTests` checking the keys, defaults, ranges and order of the four settings.
- Screenshot harness set `weather-before` (`run/sky-shots/weather-before.json`, not tracked):
  - 14 poses: 8 taken from earlier sets, 6 recorded by empireu (inside a house at a window and a door, village views, a lamp, a forest edge)
  - weathers `clear`, `snowfall` and `blizzard`, at noon, sun `2 degrees` setting, and midnight
  - 126 shots
  - The ice depth and diamond dust are pinned per group: ice `0.02` clear and `3.0` in snow, dust `0`. A held weather does not move them, so they would drift between runs.
  - empireu took it with this build as run `weather-before-2026-11-18_17-22-57`.

## Decisions

- The settings live in `SkySetting`, so the client command, the config and the screenshot harness pick them up without new code.
- Visibility is given in blocks at sea level, independent of the render distance. The defaults were chosen to match the old fog's end at render distance `12`. Stage 1 showed that this was the wrong match; see that entry.
- GameTests run rarely: once per stage, plus at most one run in the middle of a stage. Each run takes about 6 minutes on one thread (empireu's policy, 2026-11-18).

## Validation

- `All 116 required tests passed` for `frostedheart_weather,frostedheart_calculated_sky`.
- Two read-only reviews (DeepSeek, GLM) found no defect.
- empireu confirmed the stage complete before it was committed.

## Remaining

- The settings table in `docs/climate/calculated-sky.md` gets the four rows once the fog that reads them is accepted.
