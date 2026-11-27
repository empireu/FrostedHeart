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

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Arrays;
import java.util.Locale;

/**
 * Measures the CPU and GPU time of each pass of the fog's draw, as means, medians and maxima over the frames kept. The
 * GPU times come from timestamp queries, read some frames later so that the CPU never waits for them. Without timer
 * queries only the CPU times are measured.
 * <p>
 * The boundaries of a frame split its draw into the passes. begin writes boundary 0, mark writes the boundaries 1 to
 * PASS_COUNT - 1, and end writes boundary PASS_COUNT, so that pass i lasts from boundary i to boundary i + 1 and the
 * total from boundary 0 to the last boundary. The boundaries of a frame form one query set, of which QUERY_SETS are in
 * flight in turn. Nothing that runs every frame allocates.
 * <p>
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class FogPassTimer {
    /**
     * The number of passes that the boundaries split the draw into.
     */
    public static final int PASS_COUNT = 6;
    /**
     * The number of query sets in flight, of which the oldest is collected every frame.
     */
    private static final int QUERY_SETS = 4;
    private static final int AVERAGED_FRAMES = 120;
    private static final int BOUNDARY_COUNT = PASS_COUNT + 1;
    /**
     * The name of each pass, and of the total, which is the sum of all passes and comes last.
     */
    private static final String[] SEGMENT_NAMES = {"setup", "share volume", "inject", "integrate", "lamp glow",
            "composite", "total"};

    private final int[][] boundaryQueries = new int[QUERY_SETS][BOUNDARY_COUNT];
    private final boolean[] pending = new boolean[QUERY_SETS];
    private final long[] frameCpuNanos = new long[BOUNDARY_COUNT];
    private final long[] gpuSamples = new long[(PASS_COUNT + 1) * AVERAGED_FRAMES];
    private final long[] cpuSamples = new long[(PASS_COUNT + 1) * AVERAGED_FRAMES];
    private final long[] gpuSums = new long[PASS_COUNT + 1];
    private final long[] cpuSums = new long[PASS_COUNT + 1];
    private final long[] sortedSamples = new long[AVERAGED_FRAMES];
    private int gpuCount;
    private int gpuNext;
    private int cpuCount;
    private int cpuNext;
    private int nextQuerySet;
    private int nextBoundary;
    private boolean active;
    private boolean measuringGpu;
    private boolean checked;
    private boolean supported;

    /**
     * Starts a frame and writes its first boundary. A frame whose end never came is dropped here without its queries
     * being read. Allocates nothing.
     */
    public void begin() {
        active = true;
        measuringGpu = false;
        nextBoundary = 1;
        frameCpuNanos[0] = System.nanoTime();
        if (!checked) {
            GLCapabilities capabilities = GL.getCapabilities();
            supported = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query;
            if (supported) {
                for (int querySet = 0; querySet < QUERY_SETS; querySet++) {
                    GL15C.glGenQueries(boundaryQueries[querySet]);
                }
            }
            checked = true;
        }
        if (!supported) {
            return;
        }
        collect();
        measuringGpu = !pending[nextQuerySet];
        if (measuringGpu) {
            GL33C.glQueryCounter(boundaryQueries[nextQuerySet][0], GL33C.GL_TIMESTAMP);
        }
    }

    /**
     * Writes the boundary that ends the pass with the index boundary - 1. Every boundary must come every frame, in
     * order, so that a pass that was not drawn reads about nothing. A call out of order is ignored. Allocates nothing.
     * @param boundary the index of the boundary, from 1 to PASS_COUNT - 1
     */
    public void mark(int boundary) {
        if (!active || boundary < 1 || boundary >= PASS_COUNT || boundary != nextBoundary) {
            return;
        }
        frameCpuNanos[boundary] = System.nanoTime();
        if (measuringGpu) {
            GL33C.glQueryCounter(boundaryQueries[nextQuerySet][boundary], GL33C.GL_TIMESTAMP);
        }
        nextBoundary = boundary + 1;
    }

    /**
     * Writes the last boundary, records the times of the frame and hands its query set to the collection. A frame whose
     * boundaries did not all come, or an end without a begin, is dropped. Allocates nothing.
     */
    public void end() {
        if (!active) {
            return;
        }
        active = false;
        if (nextBoundary != PASS_COUNT) {
            measuringGpu = false;
            return;
        }
        frameCpuNanos[PASS_COUNT] = System.nanoTime();
        if (measuringGpu) {
            GL33C.glQueryCounter(boundaryQueries[nextQuerySet][PASS_COUNT], GL33C.GL_TIMESTAMP);
            pending[nextQuerySet] = true;
            nextQuerySet = (nextQuerySet + 1) % QUERY_SETS;
            measuringGpu = false;
        }
        recordCpuSamples();
    }

    /**
     * Reads the queries of every set whose last boundary is available, which makes all its boundaries available, and
     * keeps the time of each pass and of the total. Allocates nothing.
     */
    private void collect() {
        for (int querySet = 0; querySet < QUERY_SETS; querySet++) {
            if (!pending[querySet]) {
                continue;
            }
            if (GL15C.glGetQueryObjecti(boundaryQueries[querySet][PASS_COUNT], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            pending[querySet] = false;
            long previousNanos = GL33C.glGetQueryObjecti64(boundaryQueries[querySet][0], GL15C.GL_QUERY_RESULT);
            long firstNanos = previousNanos;
            for (int pass = 0; pass < PASS_COUNT; pass++) {
                long boundaryNanos = GL33C.glGetQueryObjecti64(boundaryQueries[querySet][pass + 1],
                        GL15C.GL_QUERY_RESULT);
                recordGpuSample(pass, boundaryNanos - previousNanos);
                previousNanos = boundaryNanos;
            }
            recordGpuSample(PASS_COUNT, previousNanos - firstNanos);
            gpuNext = (gpuNext + 1) % AVERAGED_FRAMES;
            gpuCount = Math.min(gpuCount + 1, AVERAGED_FRAMES);
        }
    }

    /**
     * Keeps the GPU time of one pass of a collected frame in place of the oldest one.
     */
    private void recordGpuSample(int pass, long nanos) {
        int index = pass * AVERAGED_FRAMES + gpuNext;
        gpuSums[pass] += nanos - gpuSamples[index];
        gpuSamples[index] = nanos;
    }

    /**
     * Keeps the CPU times of the passes and of the total of the frame that just ended, each in place of the oldest one.
     */
    private void recordCpuSamples() {
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            int index = pass * AVERAGED_FRAMES + cpuNext;
            long nanos = frameCpuNanos[pass + 1] - frameCpuNanos[pass];
            cpuSums[pass] += nanos - cpuSamples[index];
            cpuSamples[index] = nanos;
        }
        int totalIndex = PASS_COUNT * AVERAGED_FRAMES + cpuNext;
        long totalNanos = frameCpuNanos[PASS_COUNT] - frameCpuNanos[0];
        cpuSums[PASS_COUNT] += totalNanos - cpuSamples[totalIndex];
        cpuSamples[totalIndex] = totalNanos;
        cpuNext = (cpuNext + 1) % AVERAGED_FRAMES;
        cpuCount = Math.min(cpuCount + 1, AVERAGED_FRAMES);
    }

    /**
     * Clears the samples kept so far and drops the frames still in flight, so that a new measurement starts clean, for
     * example after a setting changed the passes. The queries are kept and are written again by the following frames.
     */
    public void reset() {
        Arrays.fill(gpuSamples, 0L);
        Arrays.fill(cpuSamples, 0L);
        Arrays.fill(gpuSums, 0L);
        Arrays.fill(cpuSums, 0L);
        Arrays.fill(pending, false);
        gpuCount = 0;
        gpuNext = 0;
        cpuCount = 0;
        cpuNext = 0;
    }

    /**
     * The GPU mean, median and maximum and the CPU mean of each pass and of the total, in milliseconds with three
     * decimals, for the client command. The median of an even number of samples is the upper of the two middle ones,
     * and a GPU part that is not measured says so instead of giving numbers. Allocates.
     * @return the passes separated by semicolons, then the number of frames they stand for
     */
    public String describe() {
        if (cpuCount == 0) {
            return "no pass timed yet";
        }
        StringBuilder line = new StringBuilder();
        for (int pass = 0; pass <= PASS_COUNT; pass++) {
            if (pass > 0) {
                line.append("; ");
            }
            appendPass(line, pass);
        }
        line.append(String.format(Locale.ROOT, "; over %d frames", gpuCount > 0 ? gpuCount : cpuCount));
        return line.toString();
    }

    /**
     * Appends the means, the median and the maximum of one pass, or of the total when the pass is PASS_COUNT.
     */
    private void appendPass(StringBuilder line, int pass) {
        line.append(SEGMENT_NAMES[pass]);
        line.append(' ');
        if (supported && gpuCount > 0) {
            for (int sample = 0; sample < gpuCount; sample++) {
                sortedSamples[sample] = gpuSamples[pass * AVERAGED_FRAMES + sample];
            }
            Arrays.sort(sortedSamples, 0, gpuCount);
            line.append(String.format(Locale.ROOT, "GPU mean %.3f, median %.3f, max %.3f ms",
                    gpuSums[pass] / 1.0e6 / gpuCount, sortedSamples[gpuCount / 2] / 1.0e6,
                    sortedSamples[gpuCount - 1] / 1.0e6));
        } else if (supported) {
            line.append("GPU not read yet");
        } else {
            line.append("GPU not measured without timer queries");
        }
        line.append(String.format(Locale.ROOT, ", CPU %.3f ms", cpuSums[pass] / 1.0e6 / cpuCount));
    }
}
