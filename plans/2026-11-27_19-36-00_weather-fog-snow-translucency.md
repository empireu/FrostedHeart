# Weather Fog and Falling Snow Translucency Fix

- Time: `2026-11-27 19:36:00 +0200`
- Authors: `Gemini 4 Neon, supervised by empireu (outside contributor)`
- Status: `completed`
- Scope: `FrostedHeart` weather fog, lamp-lit fog, falling snow, and Embeddium translucent terrain rendering (`content/climate/render/fog`, `mixin/client/embeddium`, `mixin/client/LevelRendererMixin`, `bootstrap/client/FHShaders`, `assets/frostedheart/shaders`)
- Related: `WeatherFogRenderer`, `LampFogPass`, `LampFogMarch`, `SnowfallRenderer`, `FogPassTimer`, `FHShaders`, `InfraredChunkRenderer`, `diary/2026-11-27_15-56-53_weather-lamp-lit-fog.md`

## Goal

Fix translucent blocks (stained glass, glass panes, tinted glass, icicles, eave icicles, ice, thin ice, water, slime, honey, portals, and modded translucent blocks) cutting holes in weather fog, lamp-lit fog glow, and falling snow behind them, while keeping distant translucent blocks properly fogged and lamp-lit, keeping foreground snow and splats in front of translucent blocks, and preventing invisible `alpha == 0` texels on translucent models (such as icicles) from corrupting depth for any post-translucent pass.

## Verified Current State

- `WeatherFogRenderer.onRenderLevelStage` runs at `RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS` (after `RenderType.translucent()` has already rendered and written to `mainTarget`'s depth buffer). `weather_fog_composite.fsh` and `weather_fog_lamp.fsh` sample `mainTarget.getDepthTextureId()`, so fog and lamp glow stop at the front translucent surface and leave the background behind it unfogged.
- `SnowfallRenderer.onRenderLevelStage` runs at `RenderLevelStageEvent.Stage.AFTER_WEATHER` with `GL_DEPTH_TEST` (`GL_LEQUAL`) against `mainTarget`'s depth buffer, so all snowflakes and splats behind translucent surfaces fail the depth test and disappear.
- Embeddium `0.3.31-beta.53` defines `DefaultTerrainRenderPasses.TRANSLUCENT` with `allowFragmentDiscard = false` (no `USE_FRAGMENT_DISCARD` in `block_layer_opaque.fsh`) and `RenderType.translucent()` writes depth (`COLOR_DEPTH_WRITE`). Models using `minecraft:translucent` with transparent padding (e.g., `icicle_tip.png`, where 226 of 256 texels have alpha `0`) write depth across their full quad rectangles, cutting rectangular holes in fog, snow, particles, clouds, diamond dust, and light pillars.
- `RenderStateShard.TRANSLUCENT_TARGET` is a no-op when `!Minecraft.useShaderTransparency()` (non-Fabulous graphics, which `WeatherFogRenderer.isActive` already requires).
- `SnowFaces` marks fluid blocks closed on all six faces and `SnowShapes` marks fluids with `FLUID_FLAG`, so underwater terrain accumulates no extra snow fog and flakes stop at water surfaces.

## Decisions

1. **Exact layered fog via per-fragment froxel fog in Embeddium's translucent shader.**
   - Opaque scene $B$ is fogged first to $\tilde{B} = T(0, d_B) B + I(0, d_B)$.
   - Each translucent layer $k$ at distance $d_k$ is fogged at its own distance to $\tilde{C}_k = T(0, d_k) C_k + I(0, d_k)$ (preserving $\alpha_k$) and blended back-to-front with Minecraft's standard `(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)`.
   - By $T(0, d_B) = T(0, d_T) T(d_T, d_B)$ and $I(0, d_B) = I(0, d_T) + T(0, d_T) I(d_T, d_B)$, this is algebraically exact for any number of overlapping translucent layers (e.g., looking indoors through stained glass at a distant frozen lake or greenhouse).
2. **Unconditional zero-alpha discard in Embeddium's translucent chunk shader.**
   - Discard `diffuseColor.a < (1.0 / 512.0)` in the `TRANSLUCENT` chunk shader even when weather fog is off, so `alpha == 0` texels on icicles never write depth in clear weather or Fabulous graphics either.
3. **Offscreen `RGBA16F` + matching-depth `translucentTarget` scoped strictly to Embeddium's `drawChunkLayer(TRANSLUCENT)`.**
   - Hook `LevelRenderer.renderChunkLayer` immediately before `RenderDevice.enterManagedCode()` and immediately after `RenderDevice.exitManagedCode()` (priority 1100, outside Embeddium's managed GL state tracker).
   - Skip `translucentTarget` completely (zero overhead) when no visible chunk section has `TRANSLUCENT` geometry.
   - Keep `AFTER_TRANSLUCENT_BLOCKS`, Flywheel `RenderLayerEvent`, and `MultiBufferSource.endBatch()` drawing directly onto the already-fogged `mainTarget` with `fitFogAtEye()` active, so custom additive/multiplicative mod `RenderType`s never blend against a cleared `(0,0,0,0)` target and never punch fog holes.
4. **Single-march MRT in `LampFogPass` / `weather_fog_lamp.fsh`.**
   - When `translucentTarget` was captured, bind `translucentDepth` and attach a second `RGBA16F` color target (`lampFogTranslucent`).
   - Split the single raymarch cleanly at `translucentDepth` (when `< opaqueDepth`), applying falling-depth scaling independently to both endpoints.
   - In `weather_fog_translucent_composite.fsh`, add `translucentColor.a * lampGlowTranslucent` using depth-aware bilateral upsampling plus a thin-geometry opacity clamp so sub-`fogLampDivisor` silhouettes (like 1-pixel icicle tips) cannot pick up background lamp glow when all four coarse sample centers miss them.
5. **Two-pass depth-split snowfall in `SnowfallRenderer`.**
   - Pass 1 (before translucent composite, when `translucentTarget` was captured): step flake simulation once for the frame, then draw splats and flakes onto `mainTarget` with `GL_LEQUAL` against `opaqueDepth` and early fragment discard `if (!(gl_FragCoord.z > tDepth)) discard;` using `translucentDepth` on free unit 4 (`TRANSLUCENT_DEPTH_UNIT`). Draws strictly `(tDepth, opaqueDepth]`.
   - After translucent composite, blit `translucentDepth` into `mainTarget`'s depth buffer.
   - Pass 2 (at `AFTER_WEATHER`): skip duplicate physics step if Pass 1 updated this frame, and draw onto `mainTarget` with `GL_LEQUAL` against synced `fullDepth` (`[0, tDepth]`). Disjoint partition, zero double-drawing, foreground flakes and front-face splats stay crisp in front of glass/ice/water/icicles.
6. **Accurate pass timing in `FogPassTimer`.**
   - Measure individual fog segments with explicit begin/end boundaries so Embeddium's translucent chunk draw and Pass 1 snowfall are never charged to fog GPU time.

## Steps

1. Add `weather_fog_translucent_composite.fsh`, update `weather_fog_lamp.fsh` for MRT split march, update `snow_flakes_draw.glsl` for translucent depth clipping, and register loader/include helpers in `FHShaders`.
2. Add `TranslucentFogShader`, `TranslucentFogChunkShaderInterface`, `TranslucentFogTarget`, and Embeddium mixins (`TranslucentFogChunkShaderMixin`, `TranslucentFogChunkLayerMixin`, `SodiumWorldRendererAccessor`, `ChunkRenderListAccessor`, plus frame camera/partialTick capture in `LevelRendererMixin`), registered in `frostedheart.mixins.json`.
3. Update `LampFogMarch` (Java twin `marchSplit`) and `LampFogPass` (MRT dual-output target and translucent depth binding).
4. Update `FogPassTimer` (per-segment begin/end timing), `WeatherFogRenderer` (translucent capture, opaque composite, behind-translucent snow trigger, translucent composite, depth sync, fallback safety), and `SnowfallRenderer` (two-pass split draw with single per-frame update and separate behind-translucent timer).
5. Add GameTests in `frostedheart_weather` covering `LampFogMarch.marchSplit`, `TranslucentFogShader` transforms/constants, `weather_fog_translucent_composite.fsh` constants, and `FogPassTimer` segment accounting.
6. Validate with `agent-tools/glsl-check.sh`, `agent-tools/build.sh compileJava compileGameTestJava`, and `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`.
7. Write diary entry and update this plan's outcome, then stop for user review and in-game verification.

## Validation

- `agent-tools/glsl-check.sh` on `weather_fog_translucent_composite.fsh`, `weather_fog_lamp.fsh`, `weather_fog_composite.fsh`, `snow_flakes_draw.glsl`, and the transformed translucent chunk vertex/fragment shaders.
- `agent-tools/build.sh compileJava compileGameTestJava`.
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`.
- User in-game verification in `runClient`: stained glass indoors/outdoors, icicles/eave icicles, frozen lakes/thin ice/water near lamps and in blizzards, double-layer stained glass + distant lake, splats on glass/ice, clear-day icicles vs particles/clouds/pillars, underwater transition, resize, `F3+T`, `/calculatedSky fog selftest`, `/calculatedSky lamps selftest`, `/calculatedSky fog passes`, `/calculatedSky snow`.

## Documentation Impact

No living doc describes the new weather fog yet (still scheduled for the wrap-up stage of the weather plan). Record in the diary entry that no living doc update was needed.

## Outcome

Implemented in working tree on `feature/weather` (uncommitted, ready for review and in-game verification). All planned steps completed, plus two small refinements discovered during implementation and GameTest execution:
- Added `SortedRenderListsAccessor` alongside `SodiumWorldRendererAccessor` and `ChunkRenderListAccessor` for zero-allocation scanning of Embeddium's visible translucent sections.
- Extracted pure-Java `FogPassTiming` from `@OnlyIn(Dist.CLIENT)` `FogPassTimer` so non-contiguous segment timing is directly covered by server-side GameTests without loading client-only LWJGL classes.

Validation passed:
- `agent-tools/glsl-check.sh` passed on `weather_fog_translucent_composite.fsh`, `weather_fog_lamp.fsh`, and `snow_flakes_draw.glsl`, and `glslangValidator -l` linked the composed Embeddium `#version 330 core` translucent vertex/fragment pair cleanly.
- `agent-tools/build.sh compileJava compileGameTestJava` succeeded (`BUILD SUCCESSFUL`).
- `agent-tools/gametest.sh`: all 112 `frostedheart_calculated_sky` tests passed and all 163 `frostedheart_weather` tests passed (including the 3 new/extended translucency tests).

See diary entry: `diary/2026-11-27_20-15-00_weather-fog-snow-translucency.md`.
