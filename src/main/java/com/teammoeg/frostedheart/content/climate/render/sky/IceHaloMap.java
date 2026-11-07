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

package com.teammoeg.frostedheart.content.climate.render.sky;

/**
 * Phase function per steradian of the oriented ice crystals for one sun elevation, as an RGB table of
 * AZIMUTH_BINS by ELEVATION_BINS bins. Columns are the absolute azimuth from the sun over 0 to pi, since the display
 * is mirror symmetric, and rows are elevation from LOWEST_ELEVATION to the zenith in equal steps.
 * The table is blurred by the sun disc and by part of a sky texel, so one lookup per texel is enough.
 * Light of the oriented crystals that leaves below LOWEST_ELEVATION is dropped.
 * Instances are immutable after construction. The lookup is repeated in calculated_sky.fsh and must stay equal.
 */
public final class IceHaloMap {
    public static final int AZIMUTH_BINS = 360;
    public static final double BIN_STEP = Math.PI / AZIMUTH_BINS;
    public static final double LOWEST_ELEVATION = Math.toRadians(-2.0);
    public static final int ELEVATION_BINS = (int) Math.ceil((Math.PI / 2.0 - LOWEST_ELEVATION) / BIN_STEP);
    public static final double SUN_RADIUS_RADIANS = Math.toRadians(0.2665);
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;
    private static final int BIN_COUNT = AZIMUTH_BINS * ELEVATION_BINS;
    private static final double TEXEL_BLUR_FRACTION = 0.45;
    private static final double BLUR_SPAN_SIGMAS = 3.0;
    private static final int LOOKUP_CELLS = 65536;
    private static final double[] ROW_SOLID_ANGLES = new double[ELEVATION_BINS];
    private static final double LOWEST_SINE = Math.sin(LOWEST_ELEVATION);
    private static final double[] ROW_BOUNDARY_SINES = new double[ELEVATION_BINS + 1];
    private static final int[] ROW_LOOKUP = new int[LOOKUP_CELLS];
    private static final double[] COLUMN_BOUNDARY_KEYS = new double[AZIMUTH_BINS + 1];
    private static final int[] COLUMN_LOOKUP = new int[LOOKUP_CELLS];

    static {
        for (int row = 0; row < ELEVATION_BINS; row++) {
            double lower = LOWEST_ELEVATION + row * BIN_STEP;
            double upper = Math.min(Math.PI / 2.0, lower + BIN_STEP);
            ROW_SOLID_ANGLES[row] = 2.0 * BIN_STEP * (Math.sin(upper) - Math.sin(lower));
        }
        for (int row = 0; row <= ELEVATION_BINS; row++) {
            ROW_BOUNDARY_SINES[row] = Math.sin(Math.min(Math.PI / 2.0, LOWEST_ELEVATION + row * BIN_STEP));
        }
        for (int column = 0; column <= AZIMUTH_BINS; column++) {
            double azimuth = column * BIN_STEP;
            COLUMN_BOUNDARY_KEYS[column] = azimuthKey(Math.cos(azimuth), Math.abs(Math.sin(azimuth)));
        }
        int row = 0;
        int column = 0;
        for (int cell = 0; cell < LOOKUP_CELLS; cell++) {
            double height = LOWEST_SINE + (double) cell / LOOKUP_CELLS * (1.0 - LOWEST_SINE);
            while (row + 1 < ELEVATION_BINS && height >= ROW_BOUNDARY_SINES[row + 1]) {
                row++;
            }
            ROW_LOOKUP[cell] = row;
            double key = 2.0 * cell / LOOKUP_CELLS;
            while (column + 1 < AZIMUTH_BINS && key >= COLUMN_BOUNDARY_KEYS[column + 1]) {
                column++;
            }
            COLUMN_LOOKUP[cell] = column;
        }
    }

    private final double sunElevationRadians;
    private final float[] values = new float[BIN_COUNT * CHANNELS];

    /**
     * Builds the table for a sun elevation, with the oriented crystals of the data set sharing the light that is
     * not diffracted.
     */
    public IceHaloMap(IceCrystalData data, double sunElevationRadians, double blurSigma) {
        this.sunElevationRadians = sunElevationRadians;
        double[] sun = {Math.cos(sunElevationRadians), Math.sin(sunElevationRadians), 0.0};
        double sunElevationDegrees = Math.toDegrees(sunElevationRadians);
        double[][] combined = new double[CHANNELS][BIN_COUNT];
        double[][] pathShares = new double[CHANNELS][IceCrystalData.PATH_COUNT];
        double refracted = 1.0 - SkyAtmosphere.ICE_DIFFRACTION_FRACTION;
        IceHaloFormulas formulas = new IceHaloFormulas(data);
        for (IceCrystalData.Crystal crystal : data.crystals()) {
            if (crystal.orientation() == IceCrystalData.Orientation.RANDOM) {
                continue;
            }
            data.pathShares(crystal, sunElevationDegrees, pathShares);
            formulas.addCrystal(combined, crystal, sun, refracted * data.shareOf(crystal), pathShares);
        }
        double[][] blurred = blurAzimuth(blurElevation(combined, blurSigma), blurSigma);
        for (int bin = 0; bin < BIN_COUNT; bin++) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                values[bin * CHANNELS + channel] = (float) blurred[channel][bin];
            }
        }
    }

    /**
     * The blur that keeps arcs about equally bright as they move across sky texels of the given size in degrees,
     * combined with the sun disc. Sizes below the bin size, including 0 for a smooth sky, blur as the bin size, since
     * the map holds no finer detail.
     */
    public static double blurSigma(double texelDegrees) {
        double texelSigma = TEXEL_BLUR_FRACTION * Math.max(Math.toRadians(texelDegrees), BIN_STEP);
        double sunDiscSigma = SUN_RADIUS_RADIANS / 2.0;
        return Math.sqrt(texelSigma * texelSigma + sunDiscSigma * sunDiscSigma);
    }

    public double sunElevationRadians() {
        return sunElevationRadians;
    }

    /**
     * The table as interleaved RGB, by column and then by row, as it is uploaded.
     */
    public float[] values() {
        return values;
    }

    public double value(int channel, int column, int row) {
        return values[(column + row * AZIMUTH_BINS) * CHANNELS + channel];
    }

    static int binOf(double x, double y, double z) {
        if (y < LOWEST_SINE) {
            return -1;
        }
        int row = ROW_LOOKUP[Math.min(LOOKUP_CELLS - 1, (int) ((y - LOWEST_SINE) / (1.0 - LOWEST_SINE) * LOOKUP_CELLS))];
        while (row + 1 < ELEVATION_BINS && y >= ROW_BOUNDARY_SINES[row + 1]) {
            row++;
        }
        double key = azimuthKey(x, Math.abs(z));
        int column = COLUMN_LOOKUP[Math.min(LOOKUP_CELLS - 1, (int) (key * 0.5 * LOOKUP_CELLS))];
        while (column + 1 < AZIMUTH_BINS && key >= COLUMN_BOUNDARY_KEYS[column + 1]) {
            column++;
        }
        return column + row * AZIMUTH_BINS;
    }

    /**
     * A number that grows with the azimuth from 0 to pi, from 0 to 2, without trigonometry.
     */
    private static double azimuthKey(double x, double absoluteZ) {
        if (x >= 0.0) {
            double sum = x + absoluteZ;
            return sum > 0.0 ? absoluteZ / sum : 0.0;
        }
        return 1.0 - x / (absoluteZ - x);
    }

    /**
     * The solid angle of a bin, counting both mirrored halves of the sky.
     */
    static double binSolidAngle(int bin) {
        return ROW_SOLID_ANGLES[bin / AZIMUTH_BINS];
    }

    public static int rowOfElevation(double elevation) {
        return (int) Math.floor((elevation - LOWEST_ELEVATION) / BIN_STEP);
    }

    /**
     * The total of the table over the sphere, which is the share of ice light the oriented crystals keep above
     * LOWEST_ELEVATION.
     */
    public double total(int channel) {
        double sum = 0.0;
        for (int bin = 0; bin < BIN_COUNT; bin++) {
            sum += values[bin * CHANNELS + channel] * binSolidAngle(bin);
        }
        return sum;
    }

    /**
     * Phase per steradian toward a direction given in the frame of the sky view tables, where x points to the
     * sun's azimuth. Interpolated bilinearly, mirrored at azimuth 0 and pi, and clamped at the lowest and highest row.
     */
    public double phase(int channel, double[] direction) {
        double elevation = Math.asin(Math.max(-1.0, Math.min(1.0, direction[1])));
        double rowPosition = (elevation - LOWEST_ELEVATION) / BIN_STEP - 0.5;
        double columnPosition = Math.abs(Math.atan2(direction[2], direction[0])) / BIN_STEP - 0.5;
        int row = (int) Math.floor(rowPosition);
        int column = (int) Math.floor(columnPosition);
        double rowFraction = Math.max(0.0, Math.min(1.0, rowPosition - row));
        double columnFraction = columnPosition - column;
        int rowLow = Math.max(0, Math.min(ELEVATION_BINS - 1, row));
        int rowHigh = Math.max(0, Math.min(ELEVATION_BINS - 1, row + 1));
        int columnLow = mirroredColumn(column);
        int columnHigh = mirroredColumn(column + 1);
        double low = value(channel, columnLow, rowLow) * (1.0 - columnFraction)
                + value(channel, columnHigh, rowLow) * columnFraction;
        double high = value(channel, columnLow, rowHigh) * (1.0 - columnFraction)
                + value(channel, columnHigh, rowHigh) * columnFraction;
        return low * (1.0 - rowFraction) + high * rowFraction;
    }

    /**
     * Irradiance on level ground from the oriented crystals per unit of the ice table, summed bin by bin above
     * the horizon.
     */
    double irradiance(int channel, float[] iceTable) {
        double result = 0.0;
        for (int row = 0; row < ELEVATION_BINS; row++) {
            double elevation = LOWEST_ELEVATION + (row + 0.5) * BIN_STEP;
            if (elevation <= 0.0) {
                continue;
            }
            double height = Math.sin(elevation);
            for (int column = 0; column < AZIMUTH_BINS; column++) {
                double phase = value(channel, column, row);
                if (phase == 0.0) {
                    continue;
                }
                result += phase * ROW_SOLID_ANGLES[row] * height
                        * SkyView.sample(iceTable, channel, (column + 0.5) * BIN_STEP, elevation);
            }
        }
        return result;
    }

    private static int mirroredColumn(int column) {
        int period = Math.floorMod(column, 2 * AZIMUTH_BINS);
        return period < AZIMUTH_BINS ? period : 2 * AZIMUTH_BINS - period - 1;
    }

    private static double[][] blurElevation(double[][] maps, double blurSigma) {
        double[][] result = new double[CHANNELS][BIN_COUNT];
        double sigmaBins = blurSigma / BIN_STEP;
        int span = (int) Math.ceil(BLUR_SPAN_SIGMAS * sigmaBins);
        double[] kernel = new double[2 * span + 1];
        for (int offset = -span; offset <= span; offset++) {
            kernel[offset + span] = Math.exp(-0.5 * offset * offset / (sigmaBins * sigmaBins));
        }
        for (int row = 0; row < ELEVATION_BINS; row++) {
            int first = Math.max(0, row - span);
            int last = Math.min(ELEVATION_BINS - 1, row + span);
            double weightSum = 0.0;
            for (int other = first; other <= last; other++) {
                weightSum += kernel[other - row + span] * ROW_SOLID_ANGLES[other];
            }
            int targetOffset = row * AZIMUTH_BINS;
            for (int other = first; other <= last; other++) {
                double weight = kernel[other - row + span] * ROW_SOLID_ANGLES[other] / weightSum;
                int sourceOffset = other * AZIMUTH_BINS;
                for (int channel = 0; channel < CHANNELS; channel++) {
                    double[] source = maps[channel];
                    double[] target = result[channel];
                    for (int column = 0; column < AZIMUTH_BINS; column++) {
                        target[targetOffset + column] += weight * source[sourceOffset + column];
                    }
                }
            }
        }
        return result;
    }

    private static double[][] blurAzimuth(double[][] maps, double blurSigma) {
        double[][] result = new double[CHANNELS][BIN_COUNT];
        double[] extended = new double[3 * AZIMUTH_BINS];
        for (int row = 0; row < ELEVATION_BINS; row++) {
            double elevation = LOWEST_ELEVATION + (row + 0.5) * BIN_STEP;
            double sigmaBins = blurSigma / BIN_STEP / Math.max(1.0e-6, Math.cos(elevation));
            int rowOffset = row * AZIMUTH_BINS;
            if (sigmaBins > 0.5 * AZIMUTH_BINS) {
                for (int channel = 0; channel < CHANNELS; channel++) {
                    double sum = 0.0;
                    for (int column = 0; column < AZIMUTH_BINS; column++) {
                        sum += maps[channel][rowOffset + column];
                    }
                    for (int column = 0; column < AZIMUTH_BINS; column++) {
                        result[channel][rowOffset + column] = sum / AZIMUTH_BINS;
                    }
                }
                continue;
            }
            int span = Math.min(AZIMUTH_BINS, (int) Math.ceil(BLUR_SPAN_SIGMAS * sigmaBins));
            double[] kernel = new double[2 * span + 1];
            double kernelSum = 0.0;
            for (int offset = -span; offset <= span; offset++) {
                kernel[offset + span] = Math.exp(-0.5 * offset * offset / (sigmaBins * sigmaBins));
                kernelSum += kernel[offset + span];
            }
            for (int channel = 0; channel < CHANNELS; channel++) {
                double[] source = maps[channel];
                for (int position = 0; position < extended.length; position++) {
                    extended[position] = source[rowOffset + mirroredColumn(position - AZIMUTH_BINS)];
                }
                double[] target = result[channel];
                for (int column = 0; column < AZIMUTH_BINS; column++) {
                    double sum = 0.0;
                    int center = column + AZIMUTH_BINS;
                    for (int offset = -span; offset <= span; offset++) {
                        sum += kernel[offset + span] * extended[center + offset];
                    }
                    target[rowOffset + column] = sum / kernelSum;
                }
            }
        }
        return result;
    }
}
