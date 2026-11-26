# Generated Snowflake Shapes in the Flake Draw

- Time: `2026-11-27 00:05:00 +0200`
- Author: `Anthropic Claude Opus 5.5, supervised by empireu (outside contributor)`
- Status: `done` in the working tree on `feature/weather` after `09c3761ee`, not committed. The flake look of stage 5 of `WEATHER_PLAN.md` (workspace root, not in this repository).
- Scope:
  - Added: `FlakeShapeGenerator`, `FlakeShapeSet`, `FlakeShapeNoise`, `ReiterCrystalSimulation`, `FlakeShapeTexture`, `FlakeShapeGameTests`
  - Changed: `SnowFlakes`, `SnowfallRenderer`, `SkySetting`, `snow_flakes.glsl`, `snow_flakes_draw.glsl`, `SnowFlakesGameTests`, `SnowLeeGameTests`

## Completed

**Why.** Up close, a flake read as an evenly filled ellipse: its five lobes overlapped into one disc, every lobe was opaque inside, and the streak smeared any shape into a dash.

**What changed.**
- The shape generator, written separately in plain Java, moved into the fog package: `FlakeShapeGenerator`, `FlakeShapeSet`, `FlakeShapeNoise` and `ReiterCrystalSimulation`. It grows crystals with the hexagonal cellular model of Reiter, "A local cellular model for snow crystal growth" (2005), from a vapor field perturbed by noise. From them it builds 32 tiles of 64 by 64: 14 aggregates, 6 rimed flakes, 6 broken fragments, and 2 each of plates, dendrites and side-on columns. Each tile has an empty margin of 8 pixels, and its densest knots are nearly opaque. Moving it changed only names, line wrapping and comments. For the seed `20261126` it gives the same bytes as the original.
- `FlakeShapeTexture` starts the generation on a background thread (`Util.backgroundExecutor`) in the first frame that draws snow. It uploads the tiles once as an R8 2D array texture with mip levels, linear filtering and a zero border. Until then, or for good if the generation or upload fails, the flakes draw as round dots. `/calculatedSky snow` reports its state.
- Each flake takes shape `hash(flake, SALT_SHAPE) % 32` (`SnowFlakes.shapeIndex`). A shape that covers the share `c` of its tile draws on a tile of half side `sqrt(pi / (4 c))` flake radii (`SnowFlakes.shapeTileScale`). It then blocks as much light as the round dot of the flake's radius, so the flake count still follows the fog's extinction. The tiles' coverage runs from 0.10 to 0.27, so a shape's bounding box spans 1.2 to 2.0 times the flake's width: lacy shapes draw wider and denser ones narrower.
- The fragment stage reads the shape at a mip level chosen in the vertex stage, where a texel spans about one pixel along the squashed axis. The lobes and their hash are gone.
- Once a flake's shape shows, its streak is capped at its own radius (`SnowFlakes.LONGEST_SHAPE_STREAK`, blended by the shape weight from 2 to 5 pixels of radius). The shape is the mean of 4 reads spread along the streak. Round dots keep their full streak and their spread opacity. A flake's quad reaches 1.1 times its tile's half side (`SnowFlakes.SHAPE_REACH`).
- Splats draw the same shape as their flake.
- The flake sizes are skewed: the share is `0.5 + 2 * u^3` of the mean width (`SIZE_SKEW`), with a mean of 1, so about 9% of the flakes are more than twice the mean width. The mean square of the share rises from 1.05 to 1.32. At the same `snowFlakeShare`, 20% fewer flakes are asked for. Where the count is capped at 131072, as in a blizzard, the flakes cover 25% more.
- The descriptions of `snowFlakeSize` and `snowStreakSeconds` say what they mean now.

**Decisions.**
- The tile size keeps each flake's blocked area instead of its drawn width, so the fog's extinction match holds without raising the count, which a blizzard already caps.
- Generation runs at the first snowy frame rather than at client start, so a session without snow never pays for it. It took 0.32 s cold on the GameTest server thread.
- The streak cap applies only where the shape shows, so distant dots still show motion.

## Validation

- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL`.
- `agent-tools/glsl-check.sh` on `snow_flakes_draw.glsl` for both stages with `weather_fog.glsl`, `weather_fog_excess.glsl`, `snow_flakes.glsl` and `snow_lee.glsl` prepended, and on `snow_flake_update.fsh` with `snow_flakes.glsl` and `snow_lee.glsl` prepended: all passed.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 264 required tests passed` and `BUILD SUCCESSFUL` in the Gradle log.
- New GameTest `flakeShapesAreVariedSnowInsideTheirTiles`: 32 tiles of 64 pixels with the kinds 14, 6, 6, 2, 2, 2. Every pixel in the margin is 0, and each shape's coverage lies between 0.05 and 0.4 and matches its bytes. Each densest knot is at least 200, and every pixel lies within the quad's reach. The same seed gives the same bytes and another seed other bytes.
- New GameTest `shapeTilesKeepTheDotsAreaAndShapeStreaksStayShort`: the tile scale blocks pi square radii at coverages from 0.05 to 1, and an empty shape gets no tile. Streaks stay uncapped for dots, are capped at one radius for full shapes, and blend between.
- `flakeValuesSpreadEvenlyWithinTheirRanges` checks the mean size share 1, its mean square against `MEAN_SQUARED_SIZE_SHARE`, the 9% of large clumps, and that every shape is taken by 1/32 of the flakes within 5%.
- The shader constant check covers `SNOW_SIZE_SKEW`, `SNOW_SHAPE_COUNT`, `SNOW_SHAPE_SIZE`, `SNOW_SHAPE_REACH` and `SNOW_LONGEST_SHAPE_STREAK`.
- Not checked in game yet.

## Documentation

No living docs changed, because the weather docs are written in the wrap-up stage of the plan.

## Remaining

- Check in game: near flakes in plain snow and a blizzard, the size mix, distant flakes, and splats on blocks.
- Up close the shapes may look like a catalog of crystal photos rather than falling snow. If so, soften the near flakes in the draw and keep the generator.
- Measure the draw with `/calculatedSky snow`. Flakes that show their shape have quads up to about 4 times the area of the dot's, and moving ones read the texture 4 times per fragment.
- Splats lie on faces seen at an angle, where their mip level, chosen from the flake's radius in pixels, can be too sharp.
