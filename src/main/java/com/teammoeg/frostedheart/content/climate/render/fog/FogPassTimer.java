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

/**
 * Measures the CPU and GPU time of each pass of the fog's draw, as means, medians and maxima over the frames kept. The
 * GPU times come from timestamp queries, read some frames later so that the CPU never waits for them. Without timer
 * queries only the CPU times are measured.
 * <p>
 * Passes need not be contiguous: each pass that runs in a frame wraps its work in beginSegment and endSegment, so
 * work drawn between fog passes (such as Embeddium's translucent terrain or behind-translucent snowfall) is not
 * charged to the fog, and skipped passes record 0. The total is the sum of the measured fog passes. Nothing that runs
 * every frame allocates.
 * <p>
 * Render thread only.
 */
@OnlyIn(Dist.CLIENT)
public final class FogPassTimer {
    public static final int PASS_SETUP = FogPassTiming.PASS_SETUP;
    public static final int PASS_SHARE_VOLUME = FogPassTiming.PASS_SHARE_VOLUME;
    public static final int PASS_INJECT = FogPassTiming.PASS_INJECT;
    public static final int PASS_INTEGRATE = FogPassTiming.PASS_INTEGRATE;
    public static final int PASS_TRANSLUCENT_CAPTURE = FogPassTiming.PASS_TRANSLUCENT_CAPTURE;
    public static final int PASS_LAMP_GLOW = FogPassTiming.PASS_LAMP_GLOW;
    public static final int PASS_OPAQUE_COMPOSITE = FogPassTiming.PASS_OPAQUE_COMPOSITE;
    public static final int PASS_TRANSLUCENT_COMPOSITE = FogPassTiming.PASS_TRANSLUCENT_COMPOSITE;
    /**
     * The number of passes measured per frame.
     */
    public static final int PASS_COUNT = FogPassTiming.PASS_COUNT;
    /**
     * The number of query sets in flight, of which the oldest is collected every frame.
     */
    private static final int QUERY_SETS = 4;
    private static final int QUERIES_PER_SET = PASS_COUNT * 2;

    private final FogPassTiming timing = new FogPassTiming();
    private final int[][] segmentQueries = new int[QUERY_SETS][QUERIES_PER_SET];
    private final boolean[] pending = new boolean[QUERY_SETS];
    private final int[] pendingMask = new int[QUERY_SETS];
    private final int[] pendingLastQuery = new int[QUERY_SETS];
    private final long[] collectedGpuNanos = new long[PASS_COUNT];
    private int nextQuerySet;
    private int frameLastQuery;
    private boolean measuringGpu;
    private boolean checked;
    private boolean supported;

    /**
     * Starts a frame. A frame whose endFrame never came is dropped here without its queries being read. Allocates
     * nothing.
     */
    public void beginFrame() {
        timing.beginFrame();
        frameLastQuery = 0;
        measuringGpu = false;
        if (!checked) {
            GLCapabilities capabilities = GL.getCapabilities();
            supported = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query;
            if (supported) {
                for (int querySet = 0; querySet < QUERY_SETS; querySet++) {
                    GL15C.glGenQueries(segmentQueries[querySet]);
                }
            }
            checked = true;
        }
        if (!supported) {
            return;
        }
        collect();
        measuringGpu = !pending[nextQuerySet];
    }

    /**
     * Starts timing one fog pass. A pass may run at most once per frame, and passes may not overlap. Allocates nothing.
     * @param pass the pass index, from 0 to PASS_COUNT - 1
     */
    public void beginSegment(int pass) {
        if (!timing.beginSegment(pass, System.nanoTime())) {
            return;
        }
        if (measuringGpu) {
            GL33C.glQueryCounter(segmentQueries[nextQuerySet][2 * pass], GL33C.GL_TIMESTAMP);
        }
    }

    /**
     * Finishes timing the active fog pass. A mismatched call is ignored. Allocates nothing.
     * @param pass the pass index passed to beginSegment
     */
    public void endSegment(int pass) {
        if (!timing.endSegment(pass, System.nanoTime())) {
            return;
        }
        if (measuringGpu) {
            int endQuery = segmentQueries[nextQuerySet][2 * pass + 1];
            GL33C.glQueryCounter(endQuery, GL33C.GL_TIMESTAMP);
            frameLastQuery = endQuery;
        }
    }

    /**
     * Records the times of the frame and hands its query set to collection. A frame with an unclosed pass or no
     * measured pass is dropped. Allocates nothing.
     */
    public void endFrame() {
        int completedMask = timing.endFrame();
        if (completedMask == 0) {
            measuringGpu = false;
            return;
        }
        if (measuringGpu && frameLastQuery != 0) {
            pendingMask[nextQuerySet] = completedMask;
            pendingLastQuery[nextQuerySet] = frameLastQuery;
            pending[nextQuerySet] = true;
            nextQuerySet = (nextQuerySet + 1) % QUERY_SETS;
        }
        measuringGpu = false;
    }

    /**
     * Drops the in-progress frame without recording samples or queueing GPU queries. Allocates nothing.
     */
    public void abortFrame() {
        timing.abortFrame();
        frameLastQuery = 0;
        measuringGpu = false;
    }

    /**
     * Reads the queries of every set whose last recorded timestamp is available, which makes all earlier timestamps in
     * that set available, and keeps the time of each pass and of the total. Allocates nothing.
     */
    private void collect() {
        for (int querySet = 0; querySet < QUERY_SETS; querySet++) {
            if (!pending[querySet]) {
                continue;
            }
            if (GL15C.glGetQueryObjecti(pendingLastQuery[querySet], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            pending[querySet] = false;
            int mask = pendingMask[querySet];
            for (int pass = 0; pass < PASS_COUNT; pass++) {
                long nanos = 0L;
                if ((mask & (1 << pass)) != 0) {
                    long startNanos = GL33C.glGetQueryObjecti64(segmentQueries[querySet][2 * pass],
                            GL15C.GL_QUERY_RESULT);
                    long endNanos = GL33C.glGetQueryObjecti64(segmentQueries[querySet][2 * pass + 1],
                            GL15C.GL_QUERY_RESULT);
                    nanos = endNanos > startNanos ? endNanos - startNanos : 0L;
                }
                collectedGpuNanos[pass] = nanos;
            }
            timing.recordGpuFrame(collectedGpuNanos);
        }
    }

    /**
     * Clears the samples kept so far and drops the frames still in flight, so that a new measurement starts clean, for
     * example after a setting changed the passes. The queries are kept and are written again by the following frames.
     */
    public void reset() {
        timing.reset();
        Arrays.fill(pending, false);
        Arrays.fill(pendingMask, 0);
        Arrays.fill(pendingLastQuery, 0);
    }

    /**
     * The GPU mean, median and maximum and the CPU mean of each pass and of the total, in milliseconds with three
     * decimals, for the client command. Allocates.
     * @return the passes separated by semicolons, then the number of frames they stand for
     */
    public String describe() {
        return timing.describe(supported);
    }

    /**
     * Describes only the total segment of the fog draw in the same format as SkyDrawTimer.describe(), so the total
     * draw time excludes non-fog passes drawn between froxel build and translucent composite. Allocates.
     */
    public String describeTotal() {
        return timing.describeTotal(supported);
    }
}
