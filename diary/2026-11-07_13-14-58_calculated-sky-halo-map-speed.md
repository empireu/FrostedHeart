# Calculated Sky Halo Map Build Speed

- Time: `2026-11-07 13:14:58 +0200`
- Author: `Claude; Anthropic claude-opus-5-5; implementing agent for empireu (outside contributor)`
- Status: `completed`
- Scope: `content/climate/render/sky` (`IceHaloFormulas`, `IceHaloMap`, `IcePrism`, `SkyTables`, `SkyView`, `CalculatedSkyRenderer`)

## Completed

- `IcePrism.trace` and `IcePrism.traceMirrors` hand their outputs over as the cosine and sine of the deviation, which are the components of the exit direction, instead of an angle from `atan2` that the callers turned back into a cosine and sine.
- `IcePrism.traceMirrors` computes the hexagon corners once per call instead of four trigonometric calls per entry ray.
- `IceHaloFormulas.addEndToSide` reads its `360` face angles from a table built once.
- `IceHaloFormulas` keeps its scratch buffers for end face refraction and end face to end face outputs in the instance, which is created once per map.
- Side face cones gather consecutive points that land in the same bin and add them once. `IceHaloMap.inverseBinSolidAngle` replaces a division per point.
- `IceHaloMap.blurAzimuth` pads each row only as far as the blur reaches, without an integer modulo per element.
- The halo map build of the mod's own code went from `202..235 ms` to `77..89 ms` per map on one thread, and from `58..68 MB` to `11 MB` allocated per map. Behavior is in [the calculated sky docs](../docs/climate/calculated-sky.md).
- After empireu asked why anything was still allocated, halo maps and sky views are built without allocating. `IcePrism` became an instance with its own normal, corner, and output buffers, and returns its outputs in arrays instead of calling lambdas. `IceHaloFormulas` reuses its axis objects and the cone base and perpendicular arrays. `IceHaloMap.Workspace` holds the accumulation and blur buffers, and the azimuth blur writes the float table directly. `SkyView` changed from a record to a class that `SkyTables.computeView` fills in place, with the raw sky completed in the sky table itself and per texel arrays replaced by scratch buffers. `CalculatedSkyRenderer` keeps one workspace and hands the next build the halo map and view that the previous state dropped. The table build no longer creates an array per sample direction.

## Decisions

- empireu asked whether switching to 32 bit floats would help. A float copy of the map storage, and one with the side face loop and bin lookup in float as well, timed the same as the double version (`225..244 ms`) and moved the map totals by `0.016%`, so the code stays in double. Java's trigonometry is double only, and scalar float and double arithmetic cost the same on x86.
- Scratch buffers live in instances, not in static fields, because the builds run on Minecraft's shared background executor and static buffers would corrupt maps if two builds ever ran at once. The renderer's single workspace is safe because it runs one build at a time, and recycled maps and views are only handed over on the render thread after the build that dropped them has finished. The renderer keeps at most one spare map and one spare view, so a settings change or a sun jump of more than one map step still allocates.
- The remaining allocations per build are a few small bookkeeping objects (the future, its lambda, and the state and result records). Removing them would mean replacing the background executor and futures with a dedicated thread.
- A Java Flight Recorder profile showed the side face cones and the bin lookup as the hot spots and the ray tracers at a few percent. Timing each part with `System.nanoTime` showed the opposite: the two ray traced paths took `131` of `158 ms`. The profile had dropped most samples inside the ray tracers, so per part timing decided what to change.
- Smaller bin lookup tables (`4096` and `1024` cells instead of `65536`), a bin lookup that steps from the previous point's bin without division, and keeping the three colors next to each other were tried or weighed and left out, because the first two measured no gain and the side face writes were no longer a large cost after merging.

## Validation

- Outside the game, the halo classes were copied into a scratch harness with the dataset read from the resource folder, building maps at sun elevations `2`, `10`, `22`, `35`, `50`, and `70 degrees`, five rounds, compared against the committed code in alternating runs at below normal priority.
- All steps before the ray tracer change gave bit for bit identical maps. After it, every bin farther than `2 degrees` from the sun is identical. Within `2 degrees`, bins differ by up to `0.3%` (sun at `2 degrees`) to `2.1%` (sun at `70 degrees`) of the map's peak, with the same totals to nine digits. The sun of every map lies exactly on a row boundary, because map elevations are multiples of `0.5 degrees` and rows start at `-2 degrees` in `0.5 degree` steps, so light leaving exactly along the sun direction falls into the row above or below by rounding. The exit directions have unit length within `3e-12`.
- `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all 6 required tests passed, against the unchanged reference values.
- With the allocation-free builds, the scratch harness refilled one map and one view with one workspace at the six sun elevations, six rounds: `0` bytes allocated per map and per view from the first build on, a map in `79..85 ms` and a view in `27..30 ms` once warm. Maps and views from the last round are bit for bit identical to those of the version before, so refilled buffers start clean. `compileJava compileGameTestJava runGameTestServer -PgameTestNamespaces=frostedheart_calculated_sky` with JDK 17: all 6 required tests passed.
- Documentation: added the new build times and the reuse of maps, views, and the workspace to the timing paragraph of `docs/climate/calculated-sky.md`, and updated the plan.
- `runClient` the same day: empireu checked the allocation-free build in game and reported "Ingame checks out". No build times were recorded.

## Remaining

- A halo map time from `/calculatedSky` in game with the faster build, at a known sun elevation.
- The sun on a row boundary makes the row of light along the sun direction depend on rounding. Moving the rows by half a bin would make it stable, but changes the map grid and the GameTest references. It cannot be seen in game, since that light lies under the sun's glow and is blurred by about `0.26 degrees`.
