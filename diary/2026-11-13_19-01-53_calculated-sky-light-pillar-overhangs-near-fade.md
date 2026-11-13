# Calculated Sky Light Pillar Overhangs And Near Fade

- Time: `2026-11-13 19:01:53 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `partial`
- Scope: `content/climate/render/sky` (`LightPillarHorizons`, `LightPillarOptics`, `LightPillarRenderer`, `SkySetting`), `shaders/light_pillars.glsl`, GameTests

## Completed

The two steps empireu asked for after the in-game check of the shadows in `2026-11-13_16-19-52_calculated-sky-light-pillar-shadows.md`, whose last step was committed as `35cee329a`.

- Near fade: the new setting `SkySetting.PILLAR_NEAR_FADE` (`pillarNearFade`, default `8` blocks, `0` to `64`, `0` turns it off). `LightPillarOptics.nearWeight` weights each crystal by its distance from the eye, `eyeLeg = L sin(dep + le) / sin(e + dep)`, with a smoothstep from `0` at `NEAR_FADE_START_SHARE` (`0.25`) of the setting to `1` at the setting. The highest tilt is also cut where the crystals reach the start of the fade, so the Gauss nodes stay on crystals that count.
- Overhangs: each sample of `LightPillarHorizons` holds three angles above the light's top, the horizon `G`, the ceiling `F`, and the top `H`, in `horizons`, `ceilings`, and `tops`. Light that leaves the light at angle `a` passes when `G < a < F` or `a > H`. Without overhangs all three are equal, and a sample whose ceiling ends at or below its horizon stores the top for all three.
- The walk per column, entered at `dIn` (at least `NEAREST_ENTRY`, `0.25` blocks) and left at `dOut`: `readTop` finds the top as before, `H = max atan((blockTop - lightTop) / dIn)`. `readGround` joins to the ground every stopping block whose bottom lies at or below `lightTop + dOut max(tan G, 0)`, and each joined block raises `G` to `atan((blockTop - lightTop) / dIn)`. `readCeiling` then takes the lowest stopping block above that, `F = min atan((blockBottom - lightTop) / dOut)`. Each column starts at its highest non-air block, `ChunkAccess.getHeight(WORLD_SURFACE)` through `LightPillarHorizons.Columns.beginColumn`, and each walk stops at the first block that settles its angle.
- The light's own column: `readOwnColumn` takes the lowest stopping block from `ceil(lightTop)` up, sets `F = atan((blockBottom - lightTop) / exit)` with `exit` the distance to the column's edge in the bin's direction, and sets the top to `LightPillarHorizons.CLOSED` (`pi`). A change in the light's own column marks every bin of the slot.
- `LightPillarRenderer` passes ceiling and top in the new `vec2 overhangs[48]` and the setting in `nearFade`. `light_pillars.glsl` and `LightPillarOptics.dustRadiance` sum the tilts over `[lowest, min(highest, (e - H) / 2)]` and `[max(lowest, (e - F) / 2), min(highest, (e - G) / 2)]`, and `widestLateralSpread` takes the wider of the two ranges.
- Ribbons: where a light has a gap (`F < H`), the pillar can show again just above the top in a band narrower than a half row. The rows whose span holds `H + 2 sigma`, where faces tilted by one spread clear the top, also take the half width there.

## Decisions

- empireu asked for both steps on `2026-11-13`. The near fade removes the band over the log roof from the last entry, which came from crystals within about a block of the eye, too few to show as a smooth band. The overhangs give the torch under the tree its pillar back, since the light toward the eye passes below the leaves.
- `CLOSED` is `pi` and not `pi / 2`, so a block above the light also stops light that rises past the vertical away from the eye. With `pi / 2`, the ribbon edge test found up to `2.28` times the visible value from those tilts.
- Overhangs of different columns merge into one band from the lowest ceiling to the highest top. Light that passes between two overhangs at different heights counts as stopped.
- Blocks read per column are expected to go from about `1` to about `2` on open ground and about `5` to `8` under a canopy, an estimate from the walks that is not yet measured in game.
- Subordinate, a headless subagent, wrote the six new GameTests from a spec by Claude. Claude reviewed them, narrowed one check that went beyond the spec, and ran them.

## Validation

- GameTests on `2026-11-13`, `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17 passes all `48` tests. Ribbon edges show at most `0.986` times the visible value with overhangs and a near fade, and still `0.980` without a horizon and `0.971` above one. The new tests check the near weight, the near fade and overhangs against a ray integral, light passing below a canopy, blocks above the light, low blocks joining the ground, and the ribbon edges with overhangs and a near fade. The test of the light's own column became `lightPillarHorizonsLeaveOutTheLightsOwnBlock`, and the mark test expects every bin for a change in the own column.
- Earlier runs found four problems. The test of the own column left a block on top of the buried light. Wobbling plates reach crystals near the eye with their most tilted faces, so only plates show the same value with and without a near fade far from the light. `CLOSED` moved to `pi`, as above. The check that a near fade never adds light failed by `1.13e-5` of the value, which is the Gauss rule over a shorter tilt range, so it now allows `1e-4`. The ribbon edge test found `1.0475` times the visible value in the band above a top at `0.6` radians, which the sample at `H + 2 sigma` brings to about `0.986`. A probe over eleven combinations of near fade, horizon, ceiling, and top found at most `0.988` with that sample.
- Both shader stages pass `glslangValidator`.
- Ribbon edges near the zenith leak when the horizon or the top is close to `pi / 2`, because the edge at lateral angle `hw` sits at elevation `asin(sin e cos hw)`. This was already in the last step, with `1.06` to `2.8` times the visible value for horizons from `1.45` to `1.55` radians, and a gap below a top from `1.5` to `1.55` radians reaches `4` to `7.7` times. That is at most a few sRGB steps at the edge of a ribbon, seen almost straight up beside a tall column next to the light. Evaluating the width again at the edge's elevation fixes tops up to `1.5` radians but leaves `2.5` to `3.8` times at `1.55`, and doubles the work of the vertex stage, so it is left open.
- In game on `2026-11-13`, the torch under the tree from the last entry shows its pillar again. The pillar is drawn over the canopy, since the crystals that show it float between the eye and the tree, lit by light that leaves the torch below the leaves. It lines up with the torch like every pillar, so it looks as if it stood inside the tree. empireu found that fine. The torch under the log roof and the blocks read in a forest were not checked.
- Documentation: not updated in this entry. `docs/climate/calculated-sky.md` still describes the passes and the screen-space rule.
- The full `frostedheart` GameTest namespace was not run for this entry.

## Remaining

- In game: the torch in glass under a log roof from the last entry, and the blocks read in `/calculatedSky pillars` inside a forest.
- The checks of changes from the last entry: building and breaking a wall, a piston, `/fill`, TNT, standing still, relogging, and Esc.
- Update `docs/climate/calculated-sky.md` for the event-driven scan, the shadows, the overhangs, and the near fade, and add a plan file in `plans/` for this work.
- Decide on the zenith leak of ribbon edges.
- `lightPillarLightPassesBelowTheViewRayBeforeTheLight`: keep it as a check of the optics or drop it.
- The accumulation target for overlapping ribbons is left for later.
- `CalculatedSkyClientCommand.ENABLED` goes to `false` before a pull request.
