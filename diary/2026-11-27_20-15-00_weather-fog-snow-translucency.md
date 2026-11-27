# Weather Fog and Falling Snow Behind Translucent Terrain

- Time: `2026-11-27 20:15:00 +0200`
- Author: `Gemini 4 Neon, supervised by empireu (outside contributor)`
- Status: `done` on `feature/weather` (pending in-game review)
- Scope:
  - Added: `TranslucentFogShader`, `TranslucentFogChunkShaderInterface`, `TranslucentFogTarget`, `FogPassTiming`, `TranslucentFogChunkShaderMixin`, `TranslucentFogChunkLayerMixin`, `SodiumWorldRendererAccessor`, `ChunkRenderListAccessor`, `SortedRenderListsAccessor`, `weather_fog_translucent_composite.fsh`, `TranslucentFogGameTests`, `plans/2026-11-27_19-36-00_weather-fog-snow-translucency.md`
  - Changed: `WeatherFogRenderer`, `SnowfallRenderer`, `LampFogPass`, `LampFogMarch`, `FogPassTimer`, `FHShaders`, `LevelRendererMixin`, `frostedheart.mixins.json`, `weather_fog_lamp.fsh`, `snow_flakes_draw.glsl`, `LampLightGameTests`

## Completed

**Why.** Looking through stained glass, glass panes, ice, water, or past icicles cut holes in both the weather fog (including its lamp glow) and falling snow behind them. Two mechanisms caused this:
1. `WeatherFogRenderer` ran at `AFTER_TRIPWIRE_BLOCKS` and `SnowfallRenderer` at `AFTER_WEATHER`, both after `RenderType.translucent()` had written translucent depth into `mainTarget`, so fog stopped at the front translucent surface and snowflakes behind translucent blocks failed the depth test.
2. Embeddium defines `DefaultTerrainRenderPasses.TRANSLUCENT` with `allowFragmentDiscard = false` while `RenderType.translucent()` writes depth, so transparent padding texels (`alpha == 0`) on translucent models like `icicle_tip.png` overwrote the depth buffer across full quad rectangles.

**What changed.**
- **Translucent chunk shader (`TranslucentFogShader`, `TranslucentFogChunkShaderInterface`, `TranslucentFogChunkShaderMixin`)**:
  - Replaces Embeddium's `TRANSLUCENT` chunk shader variant with a composed version of `block_layer_opaque.vsh/.fsh` that unconditionally discards fragments with `diffuseColor.a < 1/512` (fixing icicle padding depth holes even in clear weather) and applies exact per-fragment froxel weather fog (`mix(fhSnowColor, diffuseColor.rgb, exp(-fhTau))`, preserving `diffuseColor.a`) while `WeatherFogRenderer.isTranslucentFogActive()` is true.
  - Because `T(0, d_B) = T(0, d_T) * T(d_T, d_B)` and `I(0, d_B) = I(0, d_T) + T(0, d_T) * I(d_T, d_B)`, fogging the opaque scene first and fogging each translucent layer at its own depth with Minecraft's standard `(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)` blend is algebraically exact for arbitrary numbers of overlapping translucent layers.
  - Falls back cleanly to Embeddium's default translucent shader on any shader build error, and leaves Oculus shader packs untouched when active.
- **Offscreen translucent target and Embeddium pass hooks (`TranslucentFogTarget`, `TranslucentFogChunkLayerMixin`, `SodiumWorldRendererAccessor`, `ChunkRenderListAccessor`, `SortedRenderListsAccessor`, `LevelRendererMixin`, `WeatherFogRenderer`)**:
  - Hooks `LevelRenderer.renderChunkLayer` immediately before `RenderDevice.enterManagedCode()` and immediately after `RenderDevice.exitManagedCode()` (priority 1100) for `RenderType.translucent()`.
  - Builds the frame's froxel excesses before translucent chunks draw, then scans Embeddium's `SortedRenderLists` without allocating to check whether any visible section has `TRANSLUCENT` geometry.
  - When no translucent chunks are visible (or `debugMode != 0`), skips the offscreen target completely with zero extra passes. When translucent chunks are visible, blits `mainTarget`'s opaque depth into an `RGBA16F` + matching-depth-format `TranslucentFogTarget`, clears color to `(0, 0, 0, 0)`, and redirects only Embeddium's `drawChunkLayer(TRANSLUCENT)` into it.
  - After Embeddium finishes, restores `mainTarget`, runs the split lamp glow march and opaque fog composite using opaque-only depth, triggers behind-translucent snowfall, composites `translucentTarget` over `mainTarget` with `weather_fog_translucent_composite.fsh` (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`), blits combined depth back to `mainTarget`, and fits vanilla eye fog before `AFTER_TRANSLUCENT_BLOCKS`, Flywheel, particles, clouds, and foreground snow run.
- **Single-march MRT lamp glow (`weather_fog_lamp.fsh`, `LampFogPass`, `LampFogMarch`, `weather_fog_translucent_composite.fsh`)**:
  - Extended `weather_fog_lamp.fsh` and its Java twin `LampFogMarch.marchSplit` to snapshot lamp light at `translucentDepth` during the same raymarch and scale both endpoints independently under falling optical depth.
  - `LampFogPass` attaches a second `RGBA16F` output (`translucentFragColor`) when translucent depth is present and keeps single-output drawing when absent or in self-test mode.
  - `weather_fog_translucent_composite.fsh` adds `color.a * lampGlow` with bilateral depth-aware upsampling and a thin-geometry opacity clamp (`LAMP_THIN_DEPTH_RATIO = 1.25`) so sub-divisor translucent silhouettes (like 1-pixel icicle tips) cannot pick up distant background lamp glow.
- **Two-pass depth-split snowfall (`SnowfallRenderer`, `snow_flakes_draw.glsl`)**:
  - Added `translucentDepth` (reusing unit 4, which is free during the draw pass) and `clipAgainstTranslucent` to `snow_flakes_draw.glsl`, with early fragment discard `if (!(gl_FragCoord.z > tDepth)) { discard; }`.
  - Pass 1 (`SnowfallRenderer.renderBehindTranslucent`) runs once translucent depth is known and before translucent terrain composites over `mainTarget`: updates the flake simulation once for the frame and draws flakes and splats strictly behind translucent surfaces (`(tDepth, opaqueDepth]`).
  - Pass 2 (`AFTER_WEATHER`) reuses the already-updated flake state and draws foreground flakes and front-face splats (`[0, tDepth]`) against the synced combined depth buffer. The two passes form a disjoint partition with zero double-drawing.
- **Per-segment timing (`FogPassTimer`, `FogPassTiming`)**:
  - Switched `FogPassTimer` from contiguous boundaries to explicit per-segment `beginSegment`/`endSegment` pairs across 7 fog passes (`setup`, `share volume`, `inject`, `integrate`, `lamp glow`, `opaque composite`, `translucent composite`) so Embeddium's translucent draw and Pass 1 snowfall are never charged to fog GPU or CPU time.
  - Extracted the pure-Java segment state machine and rolling window into `FogPassTiming` so `FogPassTimer` keeps `@OnlyIn(Dist.CLIENT)` while GameTests test the timing logic directly on the dedicated test server.

## Validation

- `agent-tools/glsl-check.sh`:
  - `weather_fog_translucent_composite.fsh` (`--prepend weather_fog.glsl --prepend weather_fog_excess.glsl`): passed.
  - `weather_fog_lamp.fsh` (`--define LAMP_LIGHT_FIELD --prepend weather_fog.glsl --prepend weather_fog_excess.glsl --prepend lamp_light.glsl`): passed.
  - `snow_flakes_draw.glsl` (`--define LAMP_LIGHT_FIELD` with its 5 prepended includes, both vertex and fragment): passed.
  - Composed Embeddium `#version 330 core` `block_layer_opaque.vsh` + `.fsh` transformed by `TranslucentFogShader` linked with `glslangValidator -l`: passed with exit code 0.
- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL` (`scratch/logs/build-2026-11-27_19-54-34.log`).
- `agent-tools/gametest.sh`:
  - `frostedheart_weather,frostedheart_calculated_sky` (`scratch/logs/gametest-frostedheart_weather+frostedheart_calculated_sky-2026-11-27_19-55-48-server.log`): all 112 `frostedheart_calculated_sky` tests passed, and 162 of 163 `frostedheart_weather` tests passed (caught `@OnlyIn(Dist.CLIENT)` loading on `FogPassTimer`, fixed via `FogPassTiming`).
  - `frostedheart_weather` after extracting `FogPassTiming` and fixing the frame-count guard when GPU queries are off (`scratch/logs/gametest-frostedheart_weather-2026-11-27_20-09-35-server.log`): `All 163 required tests passed :)`.
  - New and extended GameTests:
    - `LampLightGameTests.lampShaderConstantsMatchTheField`: now also checks `LAMP_SKY_DISTANCE` and `LEAST_ALPHA` in `weather_fog_translucent_composite.fsh`.
    - `LampLightGameTests.lampSplitMarchMatchesIndependentMarches`: checks 200 rays across split fractions `[0, 0.15, 0.45, 0.75, 1.0]`, falling shelter optical depth scaling at both endpoints, and zero allocation once warm.
    - `TranslucentFogGameTests.translucentFogShaderTransformsEmbeddiumSource`: checks `TranslucentFogShader.vertex` and `fragment` against Embeddium's shader source and verifies missing-anchor error handling.
    - `TranslucentFogGameTests.fogPassTimerMeasuresNonContiguousSegments`: checks non-contiguous segment summation, skipped passes, GPU sample totals, aborted and unclosed frames, `describe`/`describeTotal`, and zero allocation once warm.
- Pending in-game verification in `runClient` by empireu.

## Documentation

No living doc describes the weather fog yet (still scheduled for the wrap-up stage of the weather plan). `docs/climate/calculated-sky.md` needs no change.

## Remaining

- In-game visual and command verification in `runClient` (stained glass indoors/outdoors, icicles/eave icicles, frozen lakes/thin ice/water near lamps and in blizzards, double-layer stained glass, splats on glass/ice, clear-day icicles, underwater transition, resize, `F3+T`, `/calculatedSky fog selftest`, `/calculatedSky lamps selftest`, `/calculatedSky fog passes`, `/calculatedSky snow`).
