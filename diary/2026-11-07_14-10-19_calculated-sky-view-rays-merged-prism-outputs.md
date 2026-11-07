# Calculated Sky View Rays And Merged Prism Outputs

- Time: `2026-11-07 14:10:19 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`SkyTables`, `SkyView`, `IceHaloProfile`, `IceHaloMap`, `IcePrism`)

## Completed

- `SkyTables.buildViewRays` works out, once per setting, the steps of the view ray of every row: travelled distance, radius, height, altitude slot, the transmittance from the camera to the step, and the step integral. They depend only on the row's elevation, so `integrateView` no longer repeats them, with `900,000` calls of `Math.exp`, for each of the `100` computed columns of a row on every view. The sines and cosines of the row elevations and column azimuths are static tables.
- `IceHaloProfile.irradiance` and `IceHaloMap.irradiance` fill all channels in one pass. The ring and point angles of the profile are static tables, and `SkyView.sampleChannels` reads every channel of a texel with one position lookup.
- A sky view went from `27..30 ms` to `15..17 ms`, bit for bit identical in all six test views.
- `IcePrism` keys each output by the direction class of the light and its exit face, and merges the outputs of one trace call that share a key. A reflection maps a direction angle `a` to `2 * normal angle + pi - a`, so light in the hexagon moves in one of six classes per entry face, and its exit direction does not depend on where it entered. The points written per map went from about `1,100,000` to about `150,000`, and a map from about `81` to `72..75 ms`. Behavior is in [the calculated sky docs](../docs/climate/calculated-sky.md).

## Decisions

- empireu asked whether 32 bit floats with polynomial or table based sine and cosine would help, since the earlier float test might have been held back by trigonometry that stays in double. Measured in a scratch copy: a map makes `456,000` sine and cosine calls and a view `258,000` plus `900,000` exponentials. Polynomial sine, cosine, and exponential saved about `2 ms` of `82` per map and `3.5 ms` of `28` per view. A float copy of the ray tracers, which the earlier float test had left in double, gained nothing. empireu chose not to replace the library calls.
- A shared table of end face to end face light by deviation was considered and set aside. One table read by every axis would freeze its sampling noise into the arcs, unless it was built from about `25 million` rays or shipped as a new dataset.
- A version of `IcePrism` that worked out the reachable faces, inverse distances, and Fresnel factors once per direction class and trace call was tried and dropped. Its preparation per call cost as much as it saved, since the divisions of the face search overlap in the processor, and it was no faster than merging alone (`72..76 ms` against `72..74 ms`) with about twice the code.
- Merged outputs take the direction of the first light in their slot. Later light in the same slot leaves in the same direction up to rounding.

## Validation

- Outside the game, the classes were copied into the scratch harness with the dataset read from the resource folder, building maps and views at sun elevations `2`, `10`, `22`, `35`, `50`, and `70 degrees`, compared against the committed code in alternating runs of three rounds at below normal priority.
- Sky views: all values bit for bit identical to the committed code. Halo maps: every bin farther than `2 degrees` from the sun bit for bit identical. Within `2 degrees`, `54` to `108` of `198,720` values differ, by up to `0.8%` of the map's peak, at the row boundary that the sun lies on.
- `0` bytes allocated per map and per view.
- `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all 6 required tests passed, against the unchanged reference values.
- Some below normal priority runs slowed to half speed partway through, in both variants alike, and a normal priority run did as well, so priority was not the cause. Only the rounds before a slowdown were compared.
- Documentation: added the merged outputs to the halo section and the new times and per part split to the timing paragraph of `docs/climate/calculated-sky.md`, and updated the plan.
- `runClient` the same day: empireu checked these changes in game and found the optimizations sufficient. No build times were recorded.

## Remaining

- Of a map's `73 ms`, ray tracing takes about `39 ms` (Parry side paths `15 ms`, end face to end face paths `24.5 ms`) and side face cones `24 ms`. Faster tracing would need fewer or different samples, which changes the halos and needs a look in game.
- Of a view's `15.5 ms`, the view rays take about `13 ms`. The transmittance and multiple scattering lookups still compute their radius dependent parts, including a square root, at every step.
