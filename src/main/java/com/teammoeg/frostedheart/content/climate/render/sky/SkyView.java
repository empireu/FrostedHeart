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
 * The sky table already contains light reflected between snow and atmosphere.
 * The ice table holds single scattering by ice per unit phase function, which SkyPhase.narrowIce scales per pixel.
 * The mean horizon radiance is the sky table at elevation zero, averaged over all azimuths.
 * The texel lookup is repeated in calculated_sky.fsh and must stay equal.
 */
public record SkyView(double sunElevationRadians, float[] sky, float[] ice, double[] groundRadiance,
        double[] meanHorizonRadiance) {
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;

    /**
     * Writes the displayed sky colour at the horizon into the result, tone mapped and greyed as calculated_sky.fsh does.
     * The diffraction peak and the halo ring are left out, so the colour stays smooth near the sun.
     * A horizontal fraction of 1 uses the given azimuth, and 0 uses the mean over all azimuths.
     */
    public void horizonDisplayColor(double azimuth, double horizontalFraction, double exposure, float rainLevel,
            float thunderLevel, float[] result) {
        for (int channel = 0; channel < CHANNELS; channel++) {
            double radiance = horizontalFraction * sample(sky, channel, azimuth, 0.0)
                    + (1.0 - horizontalFraction) * meanHorizonRadiance[channel];
            result[channel] = (float) encodeSrgb(1.0 - Math.exp(-exposure * Math.max(0.0, radiance)));
        }
        greyByWeather(result, 0.6F, rainLevel);
        greyByWeather(result, 0.2F, thunderLevel);
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

    public double radiance(int channel, double azimuth, double elevation) {
        double directionY = Math.sin(elevation);
        if (SkyAtmosphere.distanceToGroundOrInfinity(SkyAtmosphere.CAMERA_RADIUS, directionY)
                < Double.POSITIVE_INFINITY) {
            return groundRadiance[channel];
        }
        double sunCosine = Math.cos(elevation) * Math.cos(azimuth) * Math.cos(sunElevationRadians)
                + directionY * Math.sin(sunElevationRadians);
        return sample(sky, channel, azimuth, elevation)
                + SkyPhase.narrowIce(sunCosine, channel) * sample(ice, channel, azimuth, elevation);
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
}
