# Calculated Sky Screenshot Harness

- Time: `2026-11-07 16:39:59 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `complete`
- Scope: `content/climate/render/sky` (`CalculatedSkyScreenshots`, `CalculatedSkyRenderer`, `CalculatedSkyClientCommand`)

## Completed

- `CalculatedSkyScreenshots` adds `/calculatedSky screenshots [<set>|stop]` to take a set, and `beginSet`, `pose`, `endSet`, `time`, and `weather` to record one. A set in `run/sky-shots/<set>.json` lists poses, times, weather, and sky variants, and every combination becomes one screenshot with a line in `manifest.tsv`. The format, the waits, and the output are in `docs/climate/calculated-sky.md`.
- The subcommands are registered only while `CalculatedSkyScreenshots.ENABLED` is `true`. A run, a hold, and a recording add their render tick and logout listeners when they start and remove them when they end, so without the subcommands nothing of the harness runs.
- `CalculatedSkyRenderer.isSettled` reports whether the sky is drawn from tables, halo maps, and views built for the current config and sun elevation with no build pending, and `isAllowed` is now public so the harness can tell a sky that cannot be drawn from one still building.
- The contributor's `sky-preview/tools/ScreenshotGrid.java`, outside the repository, reads a run's manifest with `--manifest` and puts each variant in its own column.

## Decisions

- empireu asked for one command in a fresh world that takes the whole comparison set, behind a flag hardcoded in the Java code that gates the command registration and everything else.
- Time, rain, thunder, and the view direction are held on the client every frame instead of changed on the server. `/time set` backwards adds a climate day each time and would bring the scripted storm closer.
- Poses move the player through the integrated server with `ServerPlayer.teleportTo`, so a run needs a singleplayer world but no cheats. A pose has a world position, an offset from where the run started, or neither.
- Variants set the config without saving the file and the run restores the starting config, view, weather, HUD, and position at its end, also when it is stopped or the world closes.
- A shot waits for at least `1 s`, longer after a weather change or a move, and for the move, the terrain, and the sky. After `20 s` it is taken anyway with the reason in the manifest, rather than ending the run.
- Embeddium `0.3.31` replaces `LevelRenderer.hasRenderedAllChunks` with `SodiumWorldRenderer.isTerrainRenderComplete`, by disassembly of its `WorldRendererMixin`, so the harness can wait for the terrain with the vanilla method.
- Snow and storms are only the client's rain and thunder levels. FrostedHeart's storms come from the climate on the server, so a real storm needs a world that is in one.

## Validation

- `compileJava` with JDK 17 passed with no warnings in the sky package.
- `compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all 7 required tests passed. The harness runs only in a client with a world, so the GameTests do not cover it.
- `ScreenshotGrid --manifest` laid out a made-up run of three shots in two rows, leaving the missing cell empty and printing the shot that had not settled.
- empireu ran a first set in `runClient` the same day: `36` shots from two poses (one `20` blocks up), times `6000`, `12500`, and `12800`, clear weather and snow, and the sky off, on, and on with ice optical depth `0.5`. All `36` settled after `1` to `7 s`, at `430` to `1350` frames per second, with the teleport, the weather, the time, and the variants as the set asked. The set itself was poor. Its poses looked level, so the sun overhead at `6000` was out of every frame, and the spot where the run started faced a hillside. It was replaced by `run/sky-shots/day-cycle.json`, which git ignores: `72` shots from a fixed position `20` blocks above that spot in the world with seed `-4295287779303392897`, looking toward the sun, away from it, and straight up, at times `6000`, `10900`, `12500`, `12800`, `13200`, and `18000`, in clear weather and snow, with the sky off and on. empireu's run of it showed many frames of empty sky. From `20` blocks up the snow fog hid all terrain in the `36` snow shots, and looking straight up showed only a plain gradient between noon and midnight. Sets can now hold groups, each with its own poses, times, weather, and variants, and `day-cycle` became `36` shots: looking toward the sun and away from it from the high position at all six times in clear weather, straight up only at `6000` and `18000`, and snow from the ground, where nearby terrain shows through the fog, at `6000` and `12500`. That run was better, but the snow shots looking east from the ground were still empty fog, and chosen coordinates kept missing good views. empireu suggested recording poses by hand, with their times, and then recording whole sets between a begin and an end command. `/calculatedSky beginSet <set>` opens a set, new or existing, `/calculatedSky pose <name>` saves the player's position, view, and the day time and weather the client shows into it and writes the file at once, and `/calculatedSky endSet` closes it. `/calculatedSky time <ticks>` and `/calculatedSky weather <clear|snow|storm>` hold what the client shows for framing, so `/time set` and its effect on the climate clock are not needed. A pose's own times and weather replace its group's. The hand-written sets were removed. empireu then recorded a set `v1` of six poses, among them sunrise at `23400`, a view up toward the sun at `2000` in clear weather and in snow, the sun near the zenith at `5000`, a side view, and sunset at `12500`. Taking it gave `12` frames, all settled after `1` to `7 s` at the frame cap of about `144` per second, each framed as recorded, and `ScreenshotGrid --manifest` laid them out.
- Documentation: added the harness to the scope, code anchors, and command section of `docs/climate/calculated-sky.md`, and a decision and a validation line to the plan.

## Remaining

- empireu reviewed the harness and chose to commit `CalculatedSkyScreenshots.ENABLED` as `true` while the sky is developed on `feature/calculated-sky`. It is set to `false` before a pull request.
- Plan step 4 used the harness for its comparison, in `diary/2026-11-07_17-32-58_calculated-sky-daylight.md`.
