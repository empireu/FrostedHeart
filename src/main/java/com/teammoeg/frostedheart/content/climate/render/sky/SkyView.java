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
 * Sky radiance for one sun elevation, as two RGB tables of SkyTables.VIEW_WIDTH by SkyTables.VIEW_HEIGHT texels.
 * Columns are azimuth from the sun, and rows follow the latitude mapping of Hillaire 2020.
 * The sky table already contains light reflected between snow and atmosphere, and no single scattering by ice.
 * The ice table holds single scattering by ice per unit phase function, which the ice phase of IceHaloProfile and
 * IceHaloMap scales per sky texel.
 * The mean horizon radiance is the sky table at elevation zero, averaged over all azimuths.
 * SkyTables.computeView fills an instance, and a view that anything still reads must not be filled again.
 * The texel lookup is repeated in calculated_sky.fsh and must stay equal.
 */
public final class SkyView {
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;
    private static final int TABLE_LENGTH = SkyTables.VIEW_WIDTH * SkyTables.VIEW_HEIGHT * CHANNELS;
    /**
     * The lowest sky darkness that ClientLevel.getSkyDarken returns, at night.
     */
    private static final float NIGHT_SKY_DARKEN = 0.2F;

    private double sunElevationRadians;
    private final float[] sky = new float[TABLE_LENGTH];
    private final float[] ice = new float[TABLE_LENGTH];
    private final double[] groundRadiance = new double[CHANNELS];
    private final double[] meanHorizonRadiance = new double[CHANNELS];

    public double sunElevationRadians() {
        return sunElevationRadians;
    }

    void setSunElevationRadians(double sunElevationRadians) {
        this.sunElevationRadians = sunElevationRadians;
    }

    public float[] sky() {
        return sky;
    }

    public float[] ice() {
        return ice;
    }

    public double[] groundRadiance() {
        return groundRadiance;
    }

    public double[] meanHorizonRadiance() {
        return meanHorizonRadiance;
    }

    /**
     * Writes the displayed sky color at the horizon into the result, tone mapped and grayed as calculated_sky.fsh does.
     * The radiance of the two views is blended by the high view's weight, as the shader blends them.
     * Single scattering by ice is left out, so the color stays smooth near the sun and across halo arcs.
     * A horizontal fraction of 1 uses the given azimuth, and 0 uses the mean over all azimuths.
     */
    public static void horizonDisplayColor(SkyView lowView, SkyView highView, double highWeight, double azimuth,
            double horizontalFraction, double exposure, float rainLevel, float thunderLevel, float[] result) {
        for (int channel = 0; channel < CHANNELS; channel++) {
            double lowRadiance = lowView.horizonRadiance(channel, azimuth, horizontalFraction);
            double highRadiance = highView.horizonRadiance(channel, azimuth, horizontalFraction);
            double radiance = lowRadiance + (highRadiance - lowRadiance) * highWeight;
            result[channel] = (float) encodeSrgb(1.0 - Math.exp(-exposure * Math.max(0.0, radiance)));
        }
        greyByWeather(result, 0.6F, rainLevel);
        greyByWeather(result, 0.2F, thunderLevel);
    }

    /**
     * Writes the sky light on the terrain into the result, as the lightmap color at full sky light.
     * In clear weather it is the ground as the sky's exposure displays it, relative to snow under a sun of unit
     * irradiance at the zenith, scaled down where a channel would pass 1, and at least vanilla's sky light at night.
     * The ground radiance of the two views is blended by the high view's weight, and rain blends the result into
     * vanilla's sky light for the given sky darkness.
     */
    public static void daylightColor(SkyView lowView, SkyView highView, double highWeight, double exposure,
            float rainLevel, float skyDarken, float[] result) {
        double brightest = encodeSrgb(1.0 - Math.exp(-exposure * SkyAtmosphere.GROUND_ALBEDO / Math.PI));
        for (int channel = 0; channel < CHANNELS; channel++) {
            double lowRadiance = lowView.groundRadiance[channel];
            double radiance = lowRadiance + (highView.groundRadiance[channel] - lowRadiance) * highWeight;
            result[channel] = (float) encodeSrgb(1.0 - Math.exp(-exposure * Math.max(0.0, radiance)));
            brightest = Math.max(brightest, result[channel]);
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            float clear = Math.max((float) (result[channel] / brightest), vanillaSkyLight(channel, NIGHT_SKY_DARKEN));
            result[channel] = clear + (vanillaSkyLight(channel, skyDarken) - clear) * rainLevel;
        }
    }

    /**
     * Vanilla's lightmap color at full sky light for a sky darkness from ClientLevel.getSkyDarken, outside lightning
     * flashes.
     */
    public static float vanillaSkyLight(int channel, float skyDarken) {
        float tint = channel == 2 ? 1.0F : skyDarken + (1.0F - skyDarken) * 0.35F;
        return tint * (skyDarken * 0.95F + 0.05F);
    }

    private double horizonRadiance(int channel, double azimuth, double horizontalFraction) {
        return horizontalFraction * sample(sky, channel, azimuth, 0.0)
                + (1.0 - horizontalFraction) * meanHorizonRadiance[channel];
    }

    private static void greyByWeather(float[] color, float greyScale, float weatherLevel) {
        float grey = (0.3F * color[0] + 0.59F * color[1] + 0.11F * color[2]) * greyScale;
        float kept = 1.0F - weatherLevel * 0.75F;
        for (int channel = 0; channel < CHANNELS; channel++) {
            color[channel] = grey + (color[channel] - grey) * kept;
        }
    }

    private static double encodeSrgb(double linear) {
        double clamped = Math.max(0.0, Math.min(1.0, linear));
        if (clamped < 0.0031308) {
            return clamped * 12.92;
        }
        return 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
    }

    /**
     * Radiance toward a direction given by azimuth from the sun and elevation, with the ice phase of the given
     * profile and map.
     */
    public double radiance(int channel, double azimuth, double elevation, IceHaloProfile haloProfile,
            IceHaloMap haloMap) {
        double directionY = Math.sin(elevation);
        if (SkyAtmosphere.distanceToGroundOrInfinity(SkyAtmosphere.CAMERA_RADIUS, directionY)
                < Double.POSITIVE_INFINITY) {
            return groundRadiance[channel];
        }
        double[] direction = {
                Math.cos(elevation) * Math.cos(azimuth), directionY, Math.cos(elevation) * Math.sin(azimuth)
        };
        double sunCosine = direction[0] * Math.cos(sunElevationRadians) + directionY * Math.sin(sunElevationRadians);
        double icePhase = haloProfile.phase(channel, sunCosine) + haloMap.phase(channel, direction);
        return sample(sky, channel, azimuth, elevation) + icePhase * sample(ice, channel, azimuth, elevation);
    }

    static double latitudeToElevation(double latitude) {
        double scale = 1.0 - Math.abs(latitude) * 2.0 / Math.PI;
        return latitude - SkyAtmosphere.HORIZON_DIP_RADIANS * scale;
    }

    static double elevationToLatitude(double elevation) {
        double offset = elevation + SkyAtmosphere.HORIZON_DIP_RADIANS;
        if (offset >= 0.0) {
            return offset / (1.0 + SkyAtmosphere.HORIZON_DIP_RADIANS * 2.0 / Math.PI);
        }
        return offset / (1.0 - SkyAtmosphere.HORIZON_DIP_RADIANS * 2.0 / Math.PI);
    }

    static double sample(float[] table, int channel, double azimuth, double elevation) {
        int width = SkyTables.VIEW_WIDTH;
        int height = SkyTables.VIEW_HEIGHT;
        double latitude = elevationToLatitude(elevation);
        double position = Math.signum(latitude) * Math.sqrt(Math.abs(latitude) * 2.0 / Math.PI);
        double rowPosition = (0.5 + 0.5 * position) * height - 0.5;
        rowPosition = Math.max(0.0, Math.min(height - 1.0, rowPosition));
        double columnPosition = (azimuth + Math.PI) / (2.0 * Math.PI) * width - 0.5;
        int columnFloor = (int) Math.floor(columnPosition);
        double columnFraction = columnPosition - columnFloor;
        int column = Math.floorMod(columnFloor, width);
        int columnNext = Math.floorMod(columnFloor + 1, width);
        int row = Math.min((int) rowPosition, height - 2);
        double rowFraction = rowPosition - row;
        double top = table[(column + row * width) * CHANNELS + channel] * (1.0 - columnFraction)
                + table[(columnNext + row * width) * CHANNELS + channel] * columnFraction;
        double bottom = table[(column + (row + 1) * width) * CHANNELS + channel] * (1.0 - columnFraction)
                + table[(columnNext + (row + 1) * width) * CHANNELS + channel] * columnFraction;
        return top * (1.0 - rowFraction) + bottom * rowFraction;
    }

    /**
     * Writes every channel of the table toward a direction into the result, with the same values as sample.
     */
    static void sampleChannels(float[] table, double azimuth, double elevation, double[] result) {
        int width = SkyTables.VIEW_WIDTH;
        int height = SkyTables.VIEW_HEIGHT;
        double latitude = elevationToLatitude(elevation);
        double position = Math.signum(latitude) * Math.sqrt(Math.abs(latitude) * 2.0 / Math.PI);
        double rowPosition = (0.5 + 0.5 * position) * height - 0.5;
        rowPosition = Math.max(0.0, Math.min(height - 1.0, rowPosition));
        double columnPosition = (azimuth + Math.PI) / (2.0 * Math.PI) * width - 0.5;
        int columnFloor = (int) Math.floor(columnPosition);
        double columnFraction = columnPosition - columnFloor;
        int column = Math.floorMod(columnFloor, width);
        int columnNext = Math.floorMod(columnFloor + 1, width);
        int row = Math.min((int) rowPosition, height - 2);
        double rowFraction = rowPosition - row;
        int topLeft = (column + row * width) * CHANNELS;
        int topRight = (columnNext + row * width) * CHANNELS;
        int bottomLeft = (column + (row + 1) * width) * CHANNELS;
        int bottomRight = (columnNext + (row + 1) * width) * CHANNELS;
        for (int channel = 0; channel < CHANNELS; channel++) {
            double top = table[topLeft + channel] * (1.0 - columnFraction) + table[topRight + channel] * columnFraction;
            double bottom = table[bottomLeft + channel] * (1.0 - columnFraction)
                    + table[bottomRight + channel] * columnFraction;
            result[channel] = top * (1.0 - rowFraction) + bottom * rowFraction;
        }
    }
}
