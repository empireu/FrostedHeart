/*
 * Copyright (c) 2026 TeamMoeg
 *
 * This file is part of Frosted Heart.
 *
 * Frosted Heart is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Frosted Heart is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Frosted Heart. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.teammoeg.frostedheart.content.climate.render.fog;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * Checks the translucent weather fog shader transformation and non-contiguous FogPassTimer accounting.
 */
@GameTestHolder("frostedheart_weather")
@PrefixGameTestTemplate(false)
public final class TranslucentFogGameTests {

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void translucentFogShaderTransformsEmbeddiumSource(GameTestHelper helper) {
        String weatherFogInclude = readResource(helper, "/assets/frostedheart/shaders/weather_fog.glsl");
        String excessInclude = readResource(helper, "/assets/frostedheart/shaders/weather_fog_excess.glsl");

        String originalVertex = readOptionalResource("/assets/sodium/shaders/blocks/block_layer_opaque.vsh");
        if (originalVertex == null) {
            originalVertex = "#version 330 core\n"
                    + "void main() {\n"
                    + "    vec3 translation = u_RegionOffset;\n"
                    + "    vec3 position = _vert_position + translation;\n"
                    + "}\n";
        }
        String originalFragment = readOptionalResource("/assets/sodium/shaders/blocks/block_layer_opaque.fsh");
        if (originalFragment == null) {
            originalFragment = "#version 330 core\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    vec4 diffuseColor = vec4(1.0);\n"
                    + "    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);\n"
                    + "}\n";
        }

        String composedVertex = TranslucentFogShader.vertex(originalVertex);
        helper.assertTrue(composedVertex.contains("uniform vec3 fhFogEyeOffset;"),
                "composed vertex shader is missing fhFogEyeOffset uniform");
        helper.assertTrue(composedVertex.contains("out vec3 fhFogOffset;"),
                "composed vertex shader is missing fhFogOffset output");
        helper.assertTrue(composedVertex.contains("fhFogOffset = position - fhFogEyeOffset;"),
                "composed vertex shader does not compute fhFogOffset from position");

        String composedFragment = TranslucentFogShader.fragment(originalFragment, weatherFogInclude, excessInclude);
        helper.assertTrue(composedFragment.contains("float fogOpticalDepth("),
                "composed fragment shader is missing weather_fog.glsl include");
        helper.assertTrue(composedFragment.contains("float froxelExcessAtDistance("),
                "composed fragment shader is missing weather_fog_excess.glsl include");
        helper.assertTrue(composedFragment.contains("if (diffuseColor.a < FH_TRANSLUCENT_LEAST_ALPHA)"),
                "composed fragment shader is missing zero-alpha discard guard");
        helper.assertTrue(composedFragment.contains("if (fhWeatherFogActive != 0)"),
                "composed fragment shader is missing weather fog guard");
        helper.assertTrue(composedFragment.contains("diffuseColor.rgb = mix(fhSnowColor, diffuseColor.rgb, exp(-fhTau));"),
                "composed fragment shader is missing weather fog mix");
        helper.assertTrue(composedFragment.contains(
                "fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);"),
                "composed fragment shader dropped Embeddium's _linearFog call");

        boolean vertexFailedOnMissingAnchor = false;
        try {
            TranslucentFogShader.vertex("#version 330 core\nvoid main() {}\n");
        } catch (IllegalStateException expected) {
            vertexFailedOnMissingAnchor = true;
        }
        helper.assertTrue(vertexFailedOnMissingAnchor, "vertex transform did not fail when position anchor was absent");

        boolean fragmentFailedOnMissingAnchor = false;
        try {
            TranslucentFogShader.fragment("#version 330 core\nout vec4 fragColor;\nvoid main() {}\n",
                    weatherFogInclude, excessInclude);
        } catch (IllegalStateException expected) {
            fragmentFailedOnMissingAnchor = true;
        }
        helper.assertTrue(fragmentFailedOnMissingAnchor,
                "fragment transform did not fail when _linearFog anchor was absent");

        String compositeShader = readResource(helper,
                "/assets/frostedheart/shaders/weather_fog_translucent_composite.fsh");
        int discardIndex = compositeShader.indexOf("discard;");
        int depthWriteIndex = compositeShader.indexOf("gl_FragDepth = depth;");
        helper.assertTrue(discardIndex >= 0 && depthWriteIndex > discardIndex,
                "translucent composite shader must write gl_FragDepth after the LEAST_ALPHA discard guard");

        String depthCopyShader = readResource(helper, "/assets/frostedheart/shaders/weather_fog_depth_copy.fsh");
        helper.assertTrue(depthCopyShader.contains("gl_FragDepth = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;"),
                "translucent depth copy shader must write gl_FragDepth from sceneDepth");

        helper.succeed();
    }

    @GameTest(template = "phase0a_empty", timeoutTicks = 100)
    public static void fogPassTimerMeasuresNonContiguousSegments(GameTestHelper helper) {
        FogPassTiming timing = new FogPassTiming();
        long clock = 1_000_000L;
        // Frame 1: all passes with large gaps in between (simulating Embeddium translucent draw and Pass 1 snow):
        timing.beginFrame();
        long expectedFrame1Total = 0L;
        for (int pass = 0; pass < FogPassTiming.PASS_COUNT; pass++) {
            long duration = (pass + 1) * 100_000L;
            helper.assertTrue(timing.beginSegment(pass, clock), "beginSegment failed for pass " + pass);
            clock += duration;
            helper.assertTrue(timing.endSegment(pass, clock), "endSegment failed for pass " + pass);
            expectedFrame1Total += duration;
            if (pass == FogPassTiming.PASS_TRANSLUCENT_CAPTURE || pass == FogPassTiming.PASS_OPAQUE_COMPOSITE) {
                clock += 5_000_000L;
            }
        }
        int frame1Mask = timing.endFrame();
        helper.assertTrue(frame1Mask == (1 << FogPassTiming.PASS_COUNT) - 1,
                "expected full pass mask, got " + frame1Mask);
        helper.assertTrue(timing.cpuFrameCount() == 1, "expected 1 recorded frame, got " + timing.cpuFrameCount());
        helper.assertTrue(timing.cpuSumNanos(FogPassTiming.PASS_COUNT) == expectedFrame1Total,
                "total nanos " + timing.cpuSumNanos(FogPassTiming.PASS_COUNT)
                        + " did not match sum of segments " + expectedFrame1Total);

        // Frame 2: opaque-only frame where PASS_TRANSLUCENT_CAPTURE and PASS_TRANSLUCENT_COMPOSITE are skipped:
        long captureBefore = timing.cpuSumNanos(FogPassTiming.PASS_TRANSLUCENT_CAPTURE);
        long translucentBefore = timing.cpuSumNanos(FogPassTiming.PASS_TRANSLUCENT_COMPOSITE);
        timing.beginFrame();
        int expectedOpaqueMask = 0;
        for (int pass = 0; pass < FogPassTiming.PASS_COUNT; pass++) {
            if (pass == FogPassTiming.PASS_TRANSLUCENT_CAPTURE || pass == FogPassTiming.PASS_TRANSLUCENT_COMPOSITE) {
                continue;
            }
            timing.beginSegment(pass, clock);
            clock += 50_000L;
            timing.endSegment(pass, clock);
            expectedOpaqueMask |= 1 << pass;
        }
        int frame2Mask = timing.endFrame();
        helper.assertTrue(frame2Mask == expectedOpaqueMask,
                "expected opaque-only mask " + expectedOpaqueMask + ", got " + frame2Mask);
        helper.assertTrue(timing.cpuFrameCount() == 2, "expected 2 recorded frames, got " + timing.cpuFrameCount());
        helper.assertTrue(timing.cpuSumNanos(FogPassTiming.PASS_TRANSLUCENT_CAPTURE) == captureBefore
                && timing.cpuSumNanos(FogPassTiming.PASS_TRANSLUCENT_COMPOSITE) == translucentBefore,
                "skipped translucent passes changed their accumulated sums");

        // GPU sample collection matches sum of per-pass GPU durations:
        long[] gpuFrame = {10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L, 70_000L, 80_000L};
        timing.recordGpuFrame(gpuFrame);
        helper.assertTrue(timing.gpuFrameCount() == 1 && timing.gpuSumNanos(FogPassTiming.PASS_COUNT) == 360_000L,
                "GPU frame total mismatch: " + timing.gpuSumNanos(FogPassTiming.PASS_COUNT));

        // Frame 3: aborted frame must not increment frame count:
        timing.beginFrame();
        timing.beginSegment(FogPassTiming.PASS_SETUP, clock);
        clock += 10_000L;
        timing.endSegment(FogPassTiming.PASS_SETUP, clock);
        timing.abortFrame();
        helper.assertTrue(timing.endFrame() == 0 && timing.cpuFrameCount() == 2,
                "aborted frame incremented count to " + timing.cpuFrameCount());

        // Frame 4: unclosed segment must drop the frame cleanly:
        timing.beginFrame();
        timing.beginSegment(FogPassTiming.PASS_SETUP, clock);
        helper.assertTrue(timing.endFrame() == 0 && timing.cpuFrameCount() == 2,
                "frame with unclosed segment incremented count to " + timing.cpuFrameCount());

        String description = timing.describe(true);
        helper.assertTrue(description.contains("translucent capture") && description.contains("opaque composite")
                && description.contains("translucent composite"),
                "describe() missing expected pass names: " + description);
        String totalDescription = timing.describeTotal(false);
        helper.assertTrue(totalDescription.contains("over 2 frames"),
                "describeTotal() missing frame count: " + totalDescription);

        // Warm frames must allocate nothing:
        com.sun.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean counter && counter.isThreadAllocatedMemorySupported()
                && counter.isThreadAllocatedMemoryEnabled() ? counter : null;
        if (threads != null) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int frame = 0; frame < 10; frame++) {
                timing.beginFrame();
                for (int pass = 0; pass < FogPassTiming.PASS_COUNT; pass++) {
                    timing.beginSegment(pass, clock);
                    clock += 1_000L;
                    timing.endSegment(pass, clock);
                }
                timing.endFrame();
                timing.recordGpuFrame(gpuFrame);
            }
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            helper.assertTrue(allocated == 0L, "warm FogPassTiming allocated " + allocated + " bytes");
        }

        timing.reset();
        helper.assertTrue(timing.cpuFrameCount() == 0 && timing.gpuFrameCount() == 0,
                "reset() did not clear frame counts");
        helper.succeed();
    }

    private static String readResource(GameTestHelper helper, String resourcePath) {
        String text = readOptionalResource(resourcePath);
        if (text == null) {
            helper.fail("Resource " + resourcePath + " was not found on the classpath");
            return "";
        }
        return text;
    }

    private static String readOptionalResource(String resourcePath) {
        try (InputStream stream = TranslucentFogShader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read resource " + resourcePath, exception);
        }
    }
}
