# Weather Fog Walks the Snow Field and Moves Before Particles

- Time: `2026-11-19 18:20:00 +0200`
- Author: `Claude; Anthropic Opus 5.5, supervising DeepSeek (implementer, test writer and reviewer) and GLM (reviewer) subagents through Subordinate; implementing agent for empireu (outside contributor)`
- Status: `done`, committed on `feature/weather` after `498003cb9`. Stage 1 of the weather plan is done, checked in game by empireu.
- Scope:
  - new `content/climate/render/fog/SnowFogWalk`, `shaders/snow_walk.glsl`, `mixin/client/embeddium/CloudRendererFogMixin`
  - `WeatherFogRenderer`, `weather_fog.fsh`, `WeatherConditions`, `ClientMockWeather`, `SkySetting`, `CalculatedSkyClientCommand`, `FHShaders`, `LevelRendererMixin`, `frostedheart.mixins.json`, a comment in `LightPillarRenderer`
  - reverted `DustColumns` to `77d8d0caa`; deleted `ShelterRay` and `LevelRendererAccess`
  - new GameTest class `SnowFogWalkGameTests`; `FogModelGameTests` and `FogSettingsGameTests` changed
  - piece 9 of stage 1 of the weather rendering plan, done as 9a (the walk) and 9b (the hooks)

## Completed

**The walk (9a).** Each pixel's ray from the eye to its scene point sums `E * (tauAt(s1) - tauAt(s0))` over parts on which the snow share E is constant, so it is exact and takes no samples.
- In the region, a box of half width `fogFieldRadius - 2` around the eye, it walks cell by cell. A cell's share is its field byte through a 256-entry table, blended over the last 8 blocks toward the heightmap value H (1 at or above the column's motion height, 0 below), measured from the eye's continuous position. Cells outside the shown box or unread take H.
- Beyond the region, the fog is open except for the covered stretch at the end of the ray, which a backward walk over at most 32 columns finds.
- After a fill, the field fades in from H over 0.5 s.
- `SnowFogWalk` is the plain Java twin of `snow_walk.glsl`. The eye goes to the shader as an integer block plus a fraction.
- The old column shelter and the eye's openness are gone.
- The vanilla fog bound now applies after 1 s of open air (the eye's cell has d = 0) and takes the highest loaded surface as its upper height. Before this, the upper height was the build limit, so the search found no distance and the bound never applied.
- Settings `fogSnowDecayLength` (1.5), `fogBlizzardDecayLength` (4) and `fogFieldRadius` (32, 12 to 40). The decay length is in `WeatherConditions`.
- `/calculatedSky fog debug <off|field|cells|noregion|nofar|analytic>` shows the share or the walk counts, or replaces a part by the analytic fog for timing. `/calculatedSky fog selftest` draws 64 rays through the real shader and compares them with the twin.

**The hooks (9b).**
- The pass runs at `AFTER_TRIPWIRE_BLOCKS`, before particles, clouds and weather, which write depth.
- From there to the end of `AFTER_WEATHER`, vanilla's fog is fitted to the fog at the eye (sphere, 0 to the eye's visibility), so particles and vanilla's snow fog by their own distance.
- An Embeddium `CloudRenderer` mixin fits the cloud fog and scales its alpha by the transmittance from the eye to the cloud layer.
- The snow deferral is removed.

## Decisions

- Piece 9 was split into 9a and 9b, each with its own implementer session and its own two reviews.
- The region's edge is the continuous box around the eye, not cells by their centers, so the edge moves smoothly instead of a cell at a time.
- Timing by part is done with the debug modes instead of separate GPU timers.
- In my review, the `noregion` mode still ran the cell loop, so it would not have measured the walk's cost. Fixed.

## Validation

- `agent-tools/build.sh` and `glsl-check.sh` pass.
- GameTests `frostedheart_weather` and `frostedheart_calculated_sky` together: `All 192 required tests passed`. The 10 walk tests compare hand-worked depths and a fine-step reference over random layouts, and check that the warm walk allocates 0 bytes.
- Reviews in `scratch/subordinate/weather/1i-review-*.md` and `1j-review-*.md`. Reviewer A found no error in the twin or the shader. The GLM answer for 9b degraded into garbled text partway, and its readable part found nothing new.
  - Applied: weight before fetches and one height fetch per far column in the shader, a renamed variable that hid a GLSL built-in, the bound off without a tracked field, culling saved and 300-block rays in the self test, the cloud fit flag reset each frame, a stale comment, a test message and a cell count assertion.
  - Rejected: "particles do not get the fitted fog", because `BufferUploader` draws through `VertexBuffer.drawWithShader`, which uploads the fog uniforms. Also rejected: an unreachable twin difference without a field, and tests for clamps and setter errors.
- In game, in a blizzard:
  - `fog selftest` gives 0 of 64 rays outside the tolerance, a largest error of 0.000034, and 0 rays whose walk counts differ. `fog debug field` shows the field as expected.
  - Screenshots of a porch, a forest, a pit, a cave mouth, a tunnel, a glass house from both sides, and a hillside with the fog off and on look right. Sheltered ground stays clear and the blizzard shows through windows.
  - Walking through a door, placing and breaking a roof block, an ender pearl into a house, changing the render distance and flying up through the blizzard show no discontinuity.
  - The pass takes 0.497 ms of GPU time at full resolution. Stage 2 brings it down.

## Documentation

None changed. The weather docs are written in stage 11.

## Remaining

- Not checked yet: inside by an open door, a long walkway at `fogFieldRadius` 32 and 40, clouds overhead, and smoke against a hillside, which waits for the quarter-resolution pass. The bound line once reported 1080 bytes allocated in a frame, to be looked at when the weather work ends.
- Vanilla's fitted fog fades by `smoothstep(0, V)`, while the pass fades by `1 - exp`. At half the visibility, smoke is 50% fogged against terrain at 86%, so a puff can still read as a lighter window. Look for it in game. The fix would be a shorter fitted end.
