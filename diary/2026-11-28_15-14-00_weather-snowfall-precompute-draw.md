# Weather Snowfall Draw Precomputation and Allocation Fix

- Time: `2026-11-28 15:56:00 +0200`
- Author: `Gemini 4 Neon, supervised by empireu (outside contributor)`
- Status: `done` (verified in game)
- Scope:
  - Added: `snow_flakes_compact.glsl`
  - Changed: `snow_flake_update.fsh`, `snow_flakes_draw.glsl`, `SnowfallRenderer`, `SnowFlakes`, `FlakeShapeTexture`, `LampLightBinding`, `LampLightTextures`, `SnowShapeTextures`, `SnowFieldTextures`, `SkyDrawTimer`, `FHShaders`, `SnowLeeGameTests`

## Change

- **Baseline measurement before the fix** (RTX 4070 Laptop GPU, 1080p fullscreen, blizzard over river ice with 3 torches and 131072 flakes at cap):
  - `update`: GPU mean `0.039` to `0.042 ms`, CPU `0.023` to `0.025 ms`.
  - `behind`: GPU mean `0.221` to `0.225 ms`, CPU `0.023` to `0.024 ms`.
  - `draw`: GPU mean `0.215` to `0.261 ms`, CPU `0.016` to `0.022 ms`.
  - Per-frame allocations in `/calculatedSky snow`: `112` to `136 bytes` in runs where HotSpot C2 did not scalar-replace LWJGL `MemoryStack` buffer wrappers (`DirectByteBuffer` / `DirectIntBufferU` / `DirectLongBufferU`, 56 bytes each) created by single-value `GL11.glGetBoolean(int)`, `GL11.glGetInteger(int)`, `GL15C.glGetQueryObjecti`, and `GL33C.glGetQueryObjecti64`.
- **Single-pass flake and splat look precomputation in `snow_flake_update.fsh` and `SnowfallRenderer`**:
  - Added four shared MRT draw textures attached to both ping-pong `stateFramebuffers` (`COLOR_ATTACHMENT2..5`): `flakeDrawColor` (`RGBA16F`: lit fogged RGB and weight), `flakeDrawPos` (`RGBA32F`: camera-relative center and flutter phase), `flakeDrawStreak` (`RGBA16F`: half-streak vector and true radius in blocks), and `splatDrawColor` (`RGBA16F`: lit fogged RGB and weight).
  - Moved shelter (`shareVolume`), lee (`leeMap`, `smoothHeights`, `noLeavesHeights`), cavity (`cavityMap`), lamp light (`lampCascade0..4`, `lampLightmap`), and froxel fog (`excesses`) lookups into `snow_flake_update.fsh` (`prepareFlakeDraw`, `prepareSplatDraw`) once per frame, guarded by conservative clip-space frustum culling (`SnowFlakes.frustumPlaneScales` / `isOutsideFrustum`) and exact screen-space quad bounds before the lamp and fog lookups.
  - Kept paused frames (`stepSeconds == 0`) refreshing all six targets by losslessly copying `previousState` and `previousSplats` without advancing physics, `updateFrameCounter`, `lastUpdateCamera`, or shape uploads.
- **Intermediate measurement before stream compaction** (2 readouts of 120 frames, fog `integrate` `0.027` to `0.028 ms`, `0 bytes allocated in the last frame`):
  - `update`: GPU mean `0.062` to `0.063 ms`, CPU `0.031` to `0.034 ms`.
  - `behind`: GPU mean `0.145` to `0.147 ms`, CPU `0.017 ms`.
  - `draw`: GPU mean `0.124 ms`, CPU `0.009` to `0.010 ms`.
- **Visible-entry transform feedback compaction (`snow_flakes_compact.glsl`, `snow_flakes_draw.glsl`, `SnowfallRenderer`, `SnowFlakes`, `FHShaders`)**:
  - Added a GLSL `#version 150` / OpenGL 4.0 transform-feedback compaction pass (`snow_flakes_compact.glsl`, run right after `update` with `GL_RASTERIZER_DISCARD`) that emits packed `uint` indices (bit 31 marking splats) for visible splats followed by visible flakes into a 1 MiB `GL_DYNAMIC_COPY` buffer (`visibleTransformFeedback`).
  - Switched `snow_flakes_draw.glsl` to a single `GL40.glDrawTransformFeedback(GL11.GL_POINTS, visibleTransformFeedback)` call per pass: the vertex stage computes quad origin/step vectors and sprite attributes once per visible item (instead of 4x per corner), a `#ifdef GEOMETRY_SHADER` stage expands each visible point into a 4-vertex `triangle_strip`, and the fragment stage is unchanged.
  - Intermediate measurement with 1 texel per compaction point (`262,144` points): `behind` dropped to `0.053` to `0.054 ms` and `draw` dropped to `0.075` to `0.077 ms` (`0.128` to `0.131 ms` combined), while `update` rose to `0.190 ms` (`~0.128 ms` spent in `262,144` single-texel geometry-shader invocations).
  - Batched `snow_flakes_compact.glsl` to `SnowFlakes.COMPACT_GROUP_SIZE = 32` consecutive texels per compaction point (`8,192` points total instead of `262,144`), packing a 32-bit visibility bitmask in the vertex stage and skipping empty 8-bit chunks in the geometry stage (`max_vertices = 32`).
  - Reduced the second snowfall pass (`AFTER_WEATHER`, when `update` and `compact` already ran for the frame) to save/restore only draw texture units `0..6` and skip weather, lee, heightmap, and lamp field bindings.
- **Per-frame allocation and raw-GL texture unit fixes**:
  - Switched `LampLightBinding` (`units 11..15`) to raw `GL13.glActiveTexture` with save and restore of `GL_ACTIVE_TEXTURE`, avoiding `RenderSystem.activeTexture` on units 12 and up.
  - Replaced `GL11.glGetBoolean(int)`, `GL11.glGetInteger(int)`, `GL15C.glGetQueryObjecti(int, int)`, and `GL33C.glGetQueryObjecti64(int, int)` in `SnowfallRenderer`, `LampLightBinding`, `SkyDrawTimer`, `SnowShapeTextures`, `LampLightTextures`, and `SnowFieldTextures.uploadNoLeavesHeights` with `glGetBooleanv` / `glGetIntegerv` / `glGetQueryObjectiv` / `glGetQueryObjecti64v` writing into pre-allocated 1-element direct buffers so zero heap wrappers are allocated even before C2 escape analysis.
  - Switched warm-frame shader binds in `SnowfallRenderer` to direct `GL20.glUseProgram`.

## Validation

- `agent-tools/glsl-check.sh` and `glslangValidator -S geom`:
  - `snow_flake_update.fsh` (`--define LAMP_LIGHT_FIELD` + fog/snow/lee/lamp prepends): `All 1 checks passed.`
  - `snow_flakes_compact.glsl` (`--prepend snow_flakes.glsl`): `vert` and `geom` passed.
  - `snow_flakes_draw.glsl` (`--prepend snow_flakes.glsl`): `vert`, `geom`, and `frag` passed.
- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL` (`scratch/logs/build-2026-11-28_15-42-45.log`).
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 277 required tests passed :)` (`scratch/logs/gametest-frostedheart_weather+frostedheart_calculated_sky-2026-11-28_15-44-18-server.log`).
- Final in-game `/calculatedSky snow` measurement (3 readouts of 120 frames, fog `integrate` `0.027 ms`, total fog `0.543 ms`):
  - `update` (including 32-wide compaction): GPU mean `0.090` to `0.091 ms`, CPU `0.041` to `0.043 ms`.
  - `behind`: GPU mean `0.050` to `0.053 ms`, CPU `0.016` to `0.017 ms`.
  - `draw`: GPU mean `0.067` to `0.075 ms`, CPU `0.009` to `0.010 ms`.
  - Combined `behind + draw`: `0.117` to `0.128 ms` (down from `0.436` to `0.486 ms`); total snowfall `0.207` to `0.219 ms` (down from `~0.52 ms`); `0 bytes allocated in the last frame` on every readout.

## Documentation

No living doc describes the snowfall renderer internals yet. `docs/climate/calculated-sky.md` needs no change.
