# Weather Translucent Composite Depth Sync Optimization

- Time: `2026-11-28 14:24:00 +0200`
- Author: `Gemini 4 Neon, supervised by empireu (outside contributor)`
- Status: `done` (verified in game)
- Scope:
  - Added: `weather_fog_depth_copy.fsh`
  - Changed: `weather_fog_translucent_composite.fsh`, `WeatherFogRenderer`, `TranslucentFogTarget`, `FHShaders`, `FogPassTiming`, `FogPassTimer`, `TranslucentFogGameTests`

## Change

- **Split-timer measurement before the fix** (RTX 4070 Laptop GPU, 1080p fullscreen, blizzard over river ice with 3 torches and 131072 flakes, 3 readouts of 120 frames each):
  - `translucent composite`: GPU mean `0.031 ms`, median `0.031 ms`, max `0.033` to `0.034 ms`, CPU `0.018` to `0.019 ms`.
  - `translucent depth` (`TranslucentFogTarget.copyDepthToMain`): GPU mean `0.075` / `0.143` / `0.300 ms`, median `0.051` / `0.051` / `0.190 ms`, max `0.487` / `1.029` / `1.850 ms`, CPU `0.031` to `0.035 ms`.
- **Single-pass depth sync in `weather_fog_translucent_composite.fsh` and `WeatherFogRenderer.drawTranslucentCompositeAndDepthSync`**:
  - Wrote `gl_FragDepth = depth` directly after the `LEAST_ALPHA` discard guard in `weather_fog_translucent_composite.fsh`, so opaque and sky pixels are discarded without touching `mainTarget` depth and surviving translucent pixels update color and depth in one pass.
  - Bound `mainTarget.frameBufferId` directly with `GL_DEPTH_TEST` enabled (`GL_ALWAYS`, `depthMask(true)`) instead of attaching/detaching `colorFramebuffer`, and removed `TranslucentFogTarget.copyDepthToMain` and its `glBlitFramebuffer(GL_DEPTH_BUFFER_BIT)`.
- **Intermediate measurement after removing `copyDepthToMain` and timing `beginCapture`** (3 readouts of 120 frames):
  - `translucent composite` (with `gl_FragDepth` sync): GPU mean `0.035` to `0.036 ms`, median `0.036 ms`, max `0.037` to `0.041 ms`, CPU `0.006` to `0.007 ms`.
  - `translucent capture` (`TranslucentFogTarget.beginCapture`, still using `glBlitFramebuffer`): GPU mean `0.553` / `0.632` / `0.695 ms`, median `0.514` / `0.621` / `0.634 ms`, max `2.226` / `2.354` / `2.469 ms`, CPU `0.047` to `0.051 ms`.
- **Shader depth copy in `TranslucentFogTarget.beginCapture` (`weather_fog_depth_copy.fsh`, `FHShaders`)**:
  - Replaced `beginCapture`'s `glBlitFramebuffer` with a full-screen depth-copy shader (`gl_FragDepth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;`) drawn with `GL_DRAW_BUFFER = GL_NONE`, `colorMask(false, false, false, false)`, `GL_DEPTH_TEST` (`GL_ALWAYS`, `depthMask(true)`), after `glClearBufferfv` fast-clears `colorTexture`.
  - Simplified `TranslucentFogTarget` to a depth-only attachment (`GL_DEPTH_COMPONENT24`, `GL_DEPTH_COMPONENT32F`, or `GL_DEPTH_COMPONENT16` matching `mainTarget`'s depth precision) and cached `sourceDepthTextureId` so `queryDepthInternalFormat` runs only on allocation, resize, or depth texture change.
  - Added `PASS_TRANSLUCENT_CAPTURE` (`translucent capture`) to `FogPassTiming` / `FogPassTimer` so `beginCapture` is measured alongside the other fog passes.

## Validation

- `agent-tools/glsl-check.sh`:
  - `weather_fog_translucent_composite.fsh` (`--prepend weather_fog.glsl --prepend weather_fog_excess.glsl`): `All 1 checks passed.`
  - `weather_fog_depth_copy.fsh`: `All 1 checks passed.`
- `agent-tools/build.sh compileJava compileGameTestJava`: `BUILD SUCCESSFUL` (`scratch/logs/build-2026-11-28_14-14-16.log`).
- `agent-tools/gametest.sh frostedheart_weather,frostedheart_calculated_sky`: `All 275 required tests passed :)` (`scratch/logs/gametest-frostedheart_weather+frostedheart_calculated_sky-2026-11-28_14-16-23-server.log`).
- Final in-game `/calculatedSky fog passes` measurement (3 readouts of 120 frames, `integrate` `0.027` ms):
  - `translucent capture`: GPU mean `0.042` to `0.043 ms`, median `0.042` to `0.043 ms`, max `0.043` to `0.044 ms`, CPU `0.024` to `0.030 ms`.
  - `translucent composite`: GPU mean `0.041 ms`, median `0.041 ms`, max `0.042 ms`, CPU `0.009` to `0.010 ms`.
  - Combined translucent capture + composite: `0.083` to `0.084 ms` steady; total fog GPU mean `0.539` to `0.540 ms` (median `0.539` to `0.540 ms`, max `0.563` to `0.607 ms`).

## Documentation

No living doc describes the weather fog yet. `docs/climate/calculated-sky.md` needs no change.
