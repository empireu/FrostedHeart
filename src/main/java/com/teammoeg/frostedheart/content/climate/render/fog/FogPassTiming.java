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

import java.util.Arrays;
import java.util.Locale;

/**
 * Pure-Java segment state machine and rolling-window accumulator for FogPassTimer. Tracks non-contiguous fog passes so
 * work rendered between fog passes (such as Embeddium's translucent terrain or behind-translucent snowfall) is never
 * charged to the fog, records 0 for skipped passes, and sums only measured fog passes into the total. Allocates
 * nothing once created.
 */
final class FogPassTiming {
    static final int PASS_SETUP = 0;
    static final int PASS_SHARE_VOLUME = 1;
    static final int PASS_INJECT = 2;
    static final int PASS_INTEGRATE = 3;
    static final int PASS_LAMP_GLOW = 4;
    static final int PASS_OPAQUE_COMPOSITE = 5;
    static final int PASS_TRANSLUCENT_COMPOSITE = 6;
    static final int PASS_COUNT = 7;
    private static final int AVERAGED_FRAMES = 120;
    private static final String[] SEGMENT_NAMES = {"setup", "share volume", "inject", "integrate", "lamp glow",
            "opaque composite", "translucent composite", "total"};

    private final long[] frameCpuStart = new long[PASS_COUNT];
    private final long[] frameCpuNanos = new long[PASS_COUNT];
    private final long[] gpuSamples = new long[(PASS_COUNT + 1) * AVERAGED_FRAMES];
    private final long[] cpuSamples = new long[(PASS_COUNT + 1) * AVERAGED_FRAMES];
    private final long[] gpuSums = new long[PASS_COUNT + 1];
    private final long[] cpuSums = new long[PASS_COUNT + 1];
    private final long[] sortedSamples = new long[AVERAGED_FRAMES];
    private int gpuCount;
    private int gpuNext;
    private int cpuCount;
    private int cpuNext;
    private int frameMask;
    private int activePass = -1;
    private boolean active;

    void beginFrame() {
        active = true;
        activePass = -1;
        frameMask = 0;
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            frameCpuNanos[pass] = 0L;
        }
    }

    /**
     * Marks a fog pass as started if no pass is currently active and the pass has not run yet this frame.
     * @return true when the pass started and a matching GPU start timestamp should be emitted
     */
    boolean beginSegment(int pass, long nowNanos) {
        if (!active || activePass != -1 || pass < 0 || pass >= PASS_COUNT || (frameMask & (1 << pass)) != 0) {
            return false;
        }
        activePass = pass;
        frameCpuStart[pass] = nowNanos;
        return true;
    }

    /**
     * Marks the active fog pass as finished and records its CPU duration.
     * @return true when the pass ended and a matching GPU end timestamp should be emitted
     */
    boolean endSegment(int pass, long nowNanos) {
        if (!active || activePass != pass) {
            return false;
        }
        activePass = -1;
        long elapsed = nowNanos - frameCpuStart[pass];
        frameCpuNanos[pass] = elapsed > 0L ? elapsed : 0L;
        frameMask |= 1 << pass;
        return true;
    }

    /**
     * Closes the frame and records CPU samples if at least one pass completed and no pass was left open.
     * @return the bitmask of completed passes, or 0 if the frame was dropped
     */
    int endFrame() {
        if (!active) {
            return 0;
        }
        active = false;
        if (activePass != -1 || frameMask == 0) {
            activePass = -1;
            return 0;
        }
        int completedMask = frameMask;
        recordCpuSamples();
        return completedMask;
    }

    void abortFrame() {
        active = false;
        activePass = -1;
        frameMask = 0;
    }

    /**
     * Records one collected frame of per-pass GPU durations (length PASS_COUNT) and their sum into the rolling window.
     */
    void recordGpuFrame(long[] passNanos) {
        long totalNanos = 0L;
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            long nanos = passNanos[pass] > 0L ? passNanos[pass] : 0L;
            recordGpuSample(pass, nanos);
            totalNanos += nanos;
        }
        recordGpuSample(PASS_COUNT, totalNanos);
        gpuNext = (gpuNext + 1) % AVERAGED_FRAMES;
        gpuCount = Math.min(gpuCount + 1, AVERAGED_FRAMES);
    }

    private void recordGpuSample(int pass, long nanos) {
        int index = pass * AVERAGED_FRAMES + gpuNext;
        gpuSums[pass] += nanos - gpuSamples[index];
        gpuSamples[index] = nanos;
    }

    private void recordCpuSamples() {
        long totalNanos = 0L;
        for (int pass = 0; pass < PASS_COUNT; pass++) {
            int index = pass * AVERAGED_FRAMES + cpuNext;
            long nanos = frameCpuNanos[pass];
            cpuSums[pass] += nanos - cpuSamples[index];
            cpuSamples[index] = nanos;
            totalNanos += nanos;
        }
        int totalIndex = PASS_COUNT * AVERAGED_FRAMES + cpuNext;
        cpuSums[PASS_COUNT] += totalNanos - cpuSamples[totalIndex];
        cpuSamples[totalIndex] = totalNanos;
        cpuNext = (cpuNext + 1) % AVERAGED_FRAMES;
        cpuCount = Math.min(cpuCount + 1, AVERAGED_FRAMES);
    }

    void reset() {
        Arrays.fill(gpuSamples, 0L);
        Arrays.fill(cpuSamples, 0L);
        Arrays.fill(gpuSums, 0L);
        Arrays.fill(cpuSums, 0L);
        gpuCount = 0;
        gpuNext = 0;
        cpuCount = 0;
        cpuNext = 0;
    }

    int cpuFrameCount() {
        return cpuCount;
    }

    int gpuFrameCount() {
        return gpuCount;
    }

    long cpuSumNanos(int pass) {
        return cpuSums[pass];
    }

    long gpuSumNanos(int pass) {
        return gpuSums[pass];
    }

    String describe(boolean gpuSupported) {
        if (cpuCount == 0) {
            return "no pass timed yet";
        }
        StringBuilder line = new StringBuilder();
        for (int pass = 0; pass <= PASS_COUNT; pass++) {
            if (pass > 0) {
                line.append("; ");
            }
            appendPass(line, pass, gpuSupported);
        }
        line.append(String.format(Locale.ROOT, "; over %d frames",
                gpuSupported && gpuCount > 0 ? gpuCount : cpuCount));
        return line.toString();
    }

    String describeTotal(boolean gpuSupported) {
        if (cpuCount == 0) {
            return "not timed yet";
        }
        StringBuilder line = new StringBuilder();
        if (gpuSupported && gpuCount > 0) {
            for (int sample = 0; sample < gpuCount; sample++) {
                sortedSamples[sample] = gpuSamples[PASS_COUNT * AVERAGED_FRAMES + sample];
            }
            Arrays.sort(sortedSamples, 0, gpuCount);
            line.append(String.format(Locale.ROOT, "GPU mean %.3f, median %.3f, max %.3f ms",
                    gpuSums[PASS_COUNT] / 1.0e6 / gpuCount, sortedSamples[gpuCount / 2] / 1.0e6,
                    sortedSamples[gpuCount - 1] / 1.0e6));
        } else if (gpuSupported) {
            line.append("GPU not read yet");
        } else {
            line.append("GPU not measured without timer queries");
        }
        line.append(String.format(Locale.ROOT, ", CPU %.3f ms over %d frames",
                cpuSums[PASS_COUNT] / 1.0e6 / cpuCount, gpuSupported && gpuCount > 0 ? gpuCount : cpuCount));
        return line.toString();
    }

    private void appendPass(StringBuilder line, int pass, boolean gpuSupported) {
        line.append(SEGMENT_NAMES[pass]);
        line.append(' ');
        if (gpuSupported && gpuCount > 0) {
            for (int sample = 0; sample < gpuCount; sample++) {
                sortedSamples[sample] = gpuSamples[pass * AVERAGED_FRAMES + sample];
            }
            Arrays.sort(sortedSamples, 0, gpuCount);
            line.append(String.format(Locale.ROOT, "GPU mean %.3f, median %.3f, max %.3f ms",
                    gpuSums[pass] / 1.0e6 / gpuCount, sortedSamples[gpuCount / 2] / 1.0e6,
                    sortedSamples[gpuCount - 1] / 1.0e6));
        } else if (gpuSupported) {
            line.append("GPU not read yet");
        } else {
            line.append("GPU not measured without timer queries");
        }
        line.append(String.format(Locale.ROOT, ", CPU %.3f ms", cpuSums[pass] / 1.0e6 / cpuCount));
    }
}
