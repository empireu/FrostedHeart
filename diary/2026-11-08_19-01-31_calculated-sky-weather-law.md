# Calculated Sky Ice From The Weather

- Time: `2026-11-08 19:01:31 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`IceWeatherLaw`, `IceWeatherDepth`, `IceDepthGrid`, `CalculatedSkyRenderer`, `SkyView`, `SkyTables`, `SkyAtmosphere`, `SkyTransmittance`, `IceCrystalData`, `CalculatedSkyClientCommand`, `CalculatedSkyScreenshots`, `calculated_sky.fsh`), `WorldClimate`, `FHClimatePacket`, `ClientClimateData`, `FHConfig`, GameTests

## Completed

- The ice optical depth follows the weather law of the plan when `iceFollowsWeather` is on, the default. `WorldClimate.getLastSnowEndHour` and `getNextSnowStartHour` search `24` climate hours each way, `FHClimatePacket` carries both hours to `ClientClimateData`, and `IceWeatherDepth` turns them into the drawn depth over the client's climate clock. The client command prints the depth, its target, and the snow hours.
- `IceDepthGrid` holds `22` depths from `0` to `3`, `0.0457 (exp(0.2 i) - 1)`. The renderer keeps the tables of the two grid depths around the drawn depth and builds the missing one when the depth leaves them, and the shader and `SkyView` blend the two linearly in depth.
- The screenshot harness has a variant key `iceFollowsWeather`. A variant follows the weather unless it names an ice depth, and the manifest records the drawn depth of every shot.
- The multiple scattering table now scatters through diffuse totals of `SkyAtmosphere`, which keep only `1 - g` of the delta scaled ice, and its sunlight comes through a second transmittance table built from them. `g` is the asymmetry of the tumbling crystals, `IceCrystalData.tumblingAsymmetry`, `0.547`, `0.542`, and `0.533` by band.
- GameTests `IceWeatherGameTests` (the law, the grid, the blend against exact tables in every cell, and the ground under ice against the path tracer) and `SnowHistoryGameTests` (the snow search and the packet). `CalculatedSkyGameTests` has new preview radiances.
- The preview in the contributor's workspace (outside the repository) got the same diffuse totals behind settings and the modes `bounce`, `groundreference`, and `crystalbounce`. Its `ScreenshotGrid` got a free layout with captions, a scale, and JPEG output.

## Decisions

- The first fixes of the failing GameTests came from a DeepSeek session run by empireu. Its cone of `10 degrees` around the sun, left out of the blend test, hid a real failure of `2.39` display steps in the top cell. The test now leaves out only the sun's square widened by `0.5 degrees` and checks every cell, and the grid step went from `0.25` to `0.2` with `3` on the grid, which brought the worst to `1.56` steps.
- empireu found too much light reaching the ground under thick ice and asked for a fix of the brightness only. Scaling `G` and `S` as well, as first proposed, made every case too dark against the path tracer, so only the multiple scattering table and its sunlight are scaled. The variants and their numbers are in `docs/climate/calculated-sky.md` under Forward scattering in the multiple scattering table.
- Under a high sun the mod stays up to `11%` above the path tracer, which only knows a generic phase. The preview's fast sky with the mod's crystals gives up to `8%` more than with the generic phase and matches the mod within `0.3%`, so the gap comes from the oriented crystals, whose share is taken to scatter like the tumbling ones. The GameTest allows `12%`.
- A gloomy day before snow would need lower and thicker clouds than ice at depth `3`. empireu kept that out of this change.
- Gradle builds wait until `runClient` is closed. A `compileJava` during a running client rewrote `build/classes` and crashed the game with a `ClassNotFoundException`.

## Validation

- `runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all `16` required tests passed. The blend test measured `1.34` display steps at worst and `0.16%` for the ground, and the ground test `10.7%` at worst, at depth `2` under a sun at `70 degrees`.
- empireu checked the law in `runClient` with humidity `0` and snow from about `71000` ticks: depth `0.089` at `53000` (next snow in `18` hours), `0.309` at `58000`, `0.999` at `65000`, `2.483` at `70000`, `2.996` at `75000`, and `0.046` at `105000`, about `5` hours after the snow ended. All match the law.
- empireu took the set `v1` at eight times in a new world of the same seed, at drawn depths `0.020`, `0.189`, `0.291`, `0.526`, `0.988`, `1.684`, `2.368`, and `2.995` (`v1-2026-11-08_18-41-21` to `18-46-36`), all frames settled. Against the runs before the multiple scattering change at the same depths, the gray sky at sunrise and sunset under depth `3` is lighter, and under a high sun the sky keeps some blue instead of washing out, with crisper halos. Depth `1` barely changed. Halos show best at `0.19..0.53`, and from `1.68` up the frames look alike.
- Grids for a Discord update were laid out from those runs in `run/screenshots/sky-shots/discord-2026-11-08`, which git ignores.
- Documentation: Ice optical depth from the weather, the grid, the new section on forward scattering, the GameTests, and the build cost in `docs/climate/calculated-sky.md`, and step 5 and the outcome in the plan.
- The full `frostedheart` GameTest namespace was not run for this entry.

## Remaining

- Not yet checked in game: the recovery after snow watched live, a relog during it, and popping as the depth crosses grid depths with the day cycle running.
- Under a high sun, thick ice brightens the ground by up to about `10%` over clear, from the oriented crystals. How much of that is real has not been traced.
- Lower clouds for gloom before snow, diamond dust, and aurorae are separate features. `CalculatedSkyScreenshots.ENABLED` goes to `false` before a pull request.
