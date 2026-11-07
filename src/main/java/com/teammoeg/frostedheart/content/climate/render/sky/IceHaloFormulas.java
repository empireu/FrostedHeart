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

import java.util.ArrayList;
import java.util.List;

/**
 * Halo arcs of oriented ice crystals from formulas, splatted into the bins of IceHaloMap.
 * Light leaving through side faces lies on the cone around the crystal axis through the sun, at the in-plane
 * deviation of the side face table for Bravais' effective index. Light entering an end face is traced exactly in 3D,
 * and light passing from end face to end face is followed through its side face reflections in the plane.
 * Each axis is weighted by its projected side area. Single threaded and deterministic.
 */
final class IceHaloFormulas {
    private static final int CHANNELS = SkyAtmosphere.CHANNEL_COUNT;
    private static final double COLUMN_AZIMUTH_STEP_RADIANS = Math.toRadians(0.25);
    private static final int DEVIATION_STEPS_PER_TABLE_COLUMN = 2;
    private static final int FACE_ANGLE_STEPS = 360;
    private static final int PARRY_ENTRY_SAMPLES = 32;
    private static final int MIRROR_ENTRY_SAMPLES = 32;
    private static final int PARRY_MIRROR_ENTRY_SAMPLES = 64;
    private static final int PLATE_MIRROR_ROTATIONS = 8;
    private static final int COLUMN_MIRROR_ROTATIONS = 2;
    private static final int MAXIMUM_PLATE_AXES = 196;
    private static final double PLATE_TILT_STEP_RADIANS = Math.toRadians(0.5);
    private static final double[] REFRACTIVE_INDEX = new double[CHANNELS];

    static {
        for (int channel = 0; channel < CHANNELS; channel++) {
            double micrometers = SkyParticleOptics.CHANNEL_CENTER_NANOMETERS[channel] / 1000.0;
            REFRACTIVE_INDEX[channel] = 1.3005 + 0.0030 / (micrometers * micrometers);
        }
    }

    private record Axis(double x, double y, double z, double weight, double rotation) {
    }

    private final IceCrystalData data;
    private final double deviationStep;
    private final double[] deviationCosines;
    private final double[] deviationSines;
    private final double[][] sideFaceDensities;
    private final double[] sideWeights = new double[CHANNELS];
    private final IcePrism.SplitMixRandom random = new IcePrism.SplitMixRandom();

    IceHaloFormulas(IceCrystalData data) {
        this.data = data;
        int columns = data.sideFaceDeviationColumns();
        int steps = columns * DEVIATION_STEPS_PER_TABLE_COLUMN;
        this.deviationStep = data.sideFaceDeviationStep() / DEVIATION_STEPS_PER_TABLE_COLUMN;
        this.deviationCosines = new double[steps];
        this.deviationSines = new double[steps];
        for (int step = 0; step < steps; step++) {
            double deviation = (step + 0.5) * deviationStep;
            deviationCosines[step] = Math.cos(deviation);
            deviationSines[step] = Math.sin(deviation);
        }
        this.sideFaceDensities = new double[CHANNELS][columns];
    }

    static double refractiveIndex(int channel) {
        return REFRACTIVE_INDEX[channel];
    }

    /**
     * Adds one oriented crystal kind into maps of phase per steradian, indexed by channel and then by bin.
     * Path shares are indexed by channel and then by IceCrystalData path class.
     */
    void addCrystal(double[][] maps, IceCrystalData.Crystal crystal, double[] sun, double weight,
            double[][] pathShares) {
        List<Axis> axes = axisSamples(crystal, sun);
        boolean parry = crystal.orientation() == IceCrystalData.Orientation.PARRY;
        int mirrorRotations = crystal.orientation() == IceCrystalData.Orientation.PLATE
                ? PLATE_MIRROR_ROTATIONS : COLUMN_MIRROR_ROTATIONS;
        for (int axisIndex = 0; axisIndex < axes.size(); axisIndex++) {
            Axis axis = axes.get(axisIndex);
            double shared = weight * axis.weight();
            if (!parry) {
                for (int mirrored = 0; mirrored <= 1; mirrored++) {
                    int path = mirrored == 0 ? IceCrystalData.SIDE_PATH : IceCrystalData.SIDE_MIRRORED_PATH;
                    for (int channel = 0; channel < CHANNELS; channel++) {
                        sideWeights[channel] = shared * pathShares[channel][path];
                    }
                    addSideCones(maps, sun, axis, mirrored == 1);
                }
            }
            for (int channel = 0; channel < CHANNELS; channel++) {
                double index = REFRACTIVE_INDEX[channel];
                double[] shares = pathShares[channel];
                random.seed(43, axisIndex, channel, 0);
                if (parry) {
                    for (int mirrored = 0; mirrored <= 1; mirrored++) {
                        int path = mirrored == 0 ? IceCrystalData.SIDE_PATH : IceCrystalData.SIDE_MIRRORED_PATH;
                        addFixedSidePaths(maps[channel], sun, axis, index, shared * shares[path], mirrored == 1);
                    }
                }
                addEndToSide(maps[channel], sun, axis, index, shared * shares[IceCrystalData.END_TO_SIDE_PATH],
                        parry);
                addEndToEnd(maps[channel], sun, axis, index, shared * shares[IceCrystalData.END_TO_END_PATH],
                        crystal.halfHeightOverRadius(), parry ? 1 : mirrorRotations, parry);
            }
        }
        int sunBin = IceHaloMap.binOf(sun[0], sun[1], sun[2]);
        if (sunBin >= 0) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                maps[channel][sunBin] += weight * pathShares[channel][IceCrystalData.OTHER_PATH]
                        / IceHaloMap.binSolidAngle(sunBin);
            }
        }
    }

    private static double gaussian(IcePrism.SplitMixRandom random) {
        return Math.sqrt(-2.0 * Math.log(1.0 - random.next())) * Math.cos(2.0 * Math.PI * random.next());
    }

    private static List<Axis> axisSamples(IceCrystalData.Crystal crystal, double[] sun) {
        List<Axis> axes = new ArrayList<>();
        double tilt = Math.toRadians(crystal.tiltDegrees());
        IcePrism.SplitMixRandom random = new IcePrism.SplitMixRandom();
        random.seed(47, crystal.name().hashCode(), 0, 0);
        if (crystal.orientation() == IceCrystalData.Orientation.PLATE) {
            if (tilt <= 0.0) {
                axes.add(new Axis(0.0, 1.0, 0.0, 1.0, 0.0));
                return axes;
            }
            int side = (int) Math.min(Math.sqrt(MAXIMUM_PLATE_AXES),
                    Math.max(7.0, Math.ceil(6.0 * tilt / PLATE_TILT_STEP_RADIANS)));
            for (int row = 0; row < side; row++) {
                for (int column = 0; column < side; column++) {
                    double first = (row + random.next()) / side;
                    double second = (column + random.next()) / side;
                    double angle = tilt * Math.sqrt(-2.0 * Math.log(1.0 - first));
                    double direction = 2.0 * Math.PI * second;
                    axes.add(new Axis(Math.sin(angle) * Math.cos(direction), Math.cos(angle),
                            Math.sin(angle) * Math.sin(direction), 1.0 / (side * side), 0.0));
                }
            }
            return axes;
        }
        boolean parry = crystal.orientation() == IceCrystalData.Orientation.PARRY;
        int azimuthSteps = (int) Math.round(Math.PI / COLUMN_AZIMUTH_STEP_RADIANS);
        double rotationSpread = Math.toRadians(crystal.rotationDegrees());
        double total = 0.0;
        for (int step = 0; step < azimuthSteps; step++) {
            double azimuth = (step + random.next()) * COLUMN_AZIMUTH_STEP_RADIANS;
            double elevation = tilt > 0.0 ? tilt * gaussian(random) : 0.0;
            double x = Math.cos(elevation) * Math.cos(azimuth);
            double y = Math.sin(elevation);
            double z = Math.cos(elevation) * Math.sin(azimuth);
            double along = x * sun[0] + y * sun[1] + z * sun[2];
            double projected = Math.sqrt(Math.max(0.0, 1.0 - along * along));
            double rotation = rotationSpread > 0.0 && parry ? rotationSpread * gaussian(random) : 0.0;
            axes.add(new Axis(x, y, z, projected, rotation));
            total += projected;
        }
        List<Axis> normalized = new ArrayList<>(axes.size());
        for (Axis axis : axes) {
            normalized.add(new Axis(axis.x(), axis.y(), axis.z(), axis.weight() / total, axis.rotation()));
        }
        return normalized;
    }

    private static void splat(double[] map, double x, double y, double z, double weight) {
        int bin = IceHaloMap.binOf(x, y, z);
        if (bin >= 0) {
            map[bin] += weight / IceHaloMap.binSolidAngle(bin);
        }
    }

    /**
     * The cone base for side face light, the sun itself or its mirror image in the end faces, followed by the
     * component along the axis.
     */
    private static double[] coneBase(double[] sun, Axis axis, boolean mirrored) {
        double along = axis.x() * sun[0] + axis.y() * sun[1] + axis.z() * sun[2];
        if (!mirrored) {
            return new double[] {sun[0], sun[1], sun[2], along};
        }
        return new double[] {sun[0] - 2.0 * along * axis.x(), sun[1] - 2.0 * along * axis.y(),
                sun[2] - 2.0 * along * axis.z(), -along};
    }

    private static void splatOnCone(double[] map, double[] base, Axis axis, double crossX, double crossY,
            double crossZ, double cosine, double sine, double weight) {
        double parallel = base[3] * (1.0 - cosine);
        splat(map, base[0] * cosine + crossX * sine + axis.x() * parallel,
                base[1] * cosine + crossY * sine + axis.y() * parallel,
                base[2] * cosine + crossZ * sine + axis.z() * parallel, weight);
    }

    private void addSideCones(double[][] maps, double[] sun, Axis axis, boolean mirrored) {
        double along = axis.x() * sun[0] + axis.y() * sun[1] + axis.z() * sun[2];
        double radialSquared = 1.0 - along * along;
        if (radialSquared < 1.0e-8) {
            return;
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            double index = REFRACTIVE_INDEX[channel];
            double effectiveIndex = Math.sqrt(index * index - along * along) / Math.sqrt(radialSquared);
            data.sideFaceDensities(effectiveIndex, sideFaceDensities[channel]);
        }
        double[] base = coneBase(sun, axis, mirrored);
        double crossX = axis.y() * sun[2] - axis.z() * sun[1];
        double crossY = axis.z() * sun[0] - axis.x() * sun[2];
        double crossZ = axis.x() * sun[1] - axis.y() * sun[0];
        for (int step = 0; step < deviationCosines.length; step++) {
            double cosine = deviationCosines[step];
            double parallel = base[3] * (1.0 - cosine);
            double centerX = base[0] * cosine + axis.x() * parallel;
            double centerY = base[1] * cosine + axis.y() * parallel;
            double centerZ = base[2] * cosine + axis.z() * parallel;
            for (int sign = -1; sign <= 1; sign += 2) {
                double sine = sign * deviationSines[step];
                int bin = IceHaloMap.binOf(centerX + crossX * sine, centerY + crossY * sine, centerZ + crossZ * sine);
                if (bin < 0) {
                    continue;
                }
                double scale = 0.5 * deviationStep / IceHaloMap.binSolidAngle(bin);
                for (int channel = 0; channel < CHANNELS; channel++) {
                    maps[channel][bin] += scale * sideWeights[channel]
                            * sideFaceDensities[channel][step / DEVIATION_STEPS_PER_TABLE_COLUMN];
                }
            }
        }
    }

    /**
     * The sun's angle around the axis, measured from the given up direction toward the axis cross up.
     */
    private static double sunAngleAroundAxis(double[] sun, Axis axis, double[] up) {
        double upCrossX = axis.y() * up[2] - axis.z() * up[1];
        double upCrossY = axis.z() * up[0] - axis.x() * up[2];
        double upCrossZ = axis.x() * up[1] - axis.y() * up[0];
        return Math.atan2(sun[0] * upCrossX + sun[1] * upCrossY + sun[2] * upCrossZ,
                sun[0] * up[0] + sun[1] * up[1] + sun[2] * up[2]);
    }

    private void addFixedSidePaths(double[] map, double[] sun, Axis axis, double index, double weight,
            boolean mirrored) {
        double along = axis.x() * sun[0] + axis.y() * sun[1] + axis.z() * sun[2];
        double radialSquared = 1.0 - along * along;
        if (radialSquared < 1.0e-8 || weight <= 0.0) {
            return;
        }
        double effectiveIndex = Math.sqrt(index * index - along * along) / Math.sqrt(radialSquared);
        double sunAngle = sunAngleAroundAxis(sun, axis, perpendicularUp(axis));
        double[] base = coneBase(sun, axis, mirrored);
        double crossX = axis.y() * sun[2] - axis.z() * sun[1];
        double crossY = axis.z() * sun[0] - axis.x() * sun[2];
        double crossZ = axis.x() * sun[1] - axis.y() * sun[0];
        IcePrism.trace(effectiveIndex, axis.rotation() - sunAngle, PARRY_ENTRY_SAMPLES, random,
                (deviation, pathWeight) -> splatOnCone(map, base, axis, crossX, crossY, crossZ, Math.cos(deviation),
                        Math.sin(deviation), weight * pathWeight));
    }

    private void addEndToEnd(double[] map, double[] sun, Axis axis, double index, double weight, double halfHeight,
            int rotations, boolean parry) {
        double along = axis.x() * sun[0] + axis.y() * sun[1] + axis.z() * sun[2];
        double cosineIncident = Math.abs(along);
        if (cosineIncident < 1.0e-6 || weight <= 0.0) {
            return;
        }
        double horizontalInside = Math.sqrt(1.0 - cosineIncident * cosineIncident) / index;
        double axialInside = Math.sqrt(1.0 - horizontalInside * horizontalInside);
        double travel = 2.0 * halfHeight * horizontalInside / axialInside;
        double sunAngle = sunAngleAroundAxis(sun, axis, perpendicularUp(axis));
        double[] base = coneBase(sun, axis, false);
        double crossX = axis.y() * sun[2] - axis.z() * sun[1];
        double crossY = axis.z() * sun[0] - axis.x() * sun[2];
        double crossZ = axis.x() * sun[1] - axis.y() * sun[0];
        IcePrism.ReflectanceFunction reflectance = inPlaneCosine -> {
            double cosine = inPlaneCosine * horizontalInside;
            double sineSquared = index * index * (1.0 - cosine * cosine);
            if (sineSquared >= 1.0) {
                return 1.0;
            }
            return IcePrism.fresnelReflectance(index, 1.0, cosine, Math.sqrt(1.0 - sineSquared));
        };
        List<double[]> outputs = new ArrayList<>();
        double[] total = new double[1];
        for (int rotationSample = 0; rotationSample < rotations; rotationSample++) {
            double rotation = parry ? axis.rotation() - sunAngle : random.next() * Math.PI / 3.0;
            int entrySamples = parry ? PARRY_MIRROR_ENTRY_SAMPLES : MIRROR_ENTRY_SAMPLES;
            IcePrism.traceMirrors(rotation, travel, entrySamples, random, reflectance, (deviation, pathWeight) -> {
                outputs.add(new double[] {deviation, pathWeight});
                total[0] += pathWeight;
            });
        }
        if (total[0] <= 0.0) {
            return;
        }
        for (double[] output : outputs) {
            splatOnCone(map, base, axis, crossX, crossY, crossZ, Math.cos(output[0]), Math.sin(output[0]),
                    weight * output[1] / total[0]);
        }
    }

    private static double[] perpendicularUp(Axis axis) {
        double x = -axis.y() * axis.x();
        double y = 1.0 - axis.y() * axis.y();
        double z = -axis.y() * axis.z();
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0e-9) {
            return new double[] {1.0, 0.0, 0.0};
        }
        return new double[] {x / length, y / length, z / length};
    }

    private static void addEndToSide(double[] map, double[] sun, Axis axis, double index, double weight,
            boolean parry) {
        double along = axis.x() * sun[0] + axis.y() * sun[1] + axis.z() * sun[2];
        if (Math.abs(along) < 1.0e-6 || weight <= 0.0) {
            return;
        }
        double sign = Math.signum(along);
        double normalX = sign * axis.x();
        double normalY = sign * axis.y();
        double normalZ = sign * axis.z();
        double cosineIncident = Math.abs(along);
        double inverseIndex = 1.0 / index;
        double cosineTransmitted = Math.sqrt(1.0 - inverseIndex * inverseIndex
                * (1.0 - cosineIncident * cosineIncident));
        double scale = inverseIndex * cosineIncident - cosineTransmitted;
        double insideX = -inverseIndex * sun[0] + scale * normalX;
        double insideY = -inverseIndex * sun[1] + scale * normalY;
        double insideZ = -inverseIndex * sun[2] + scale * normalZ;
        double[] first = perpendicularUp(axis);
        double secondX = axis.y() * first[2] - axis.z() * first[1];
        double secondY = axis.z() * first[0] - axis.x() * first[2];
        double secondZ = axis.x() * first[1] - axis.y() * first[0];
        int faceCount = parry ? 6 : FACE_ANGLE_STEPS;
        double[] viewX = new double[faceCount];
        double[] viewY = new double[faceCount];
        double[] viewZ = new double[faceCount];
        double[] raw = new double[faceCount];
        double total = 0.0;
        for (int face = 0; face < faceCount; face++) {
            double angle = parry ? axis.rotation() + face * Math.PI / 3.0 : (face + 0.5) * 2.0 * Math.PI / faceCount;
            double faceX = Math.cos(angle) * first[0] + Math.sin(angle) * secondX;
            double faceY = Math.cos(angle) * first[1] + Math.sin(angle) * secondY;
            double faceZ = Math.cos(angle) * first[2] + Math.sin(angle) * secondZ;
            double cosine = insideX * faceX + insideY * faceY + insideZ * faceZ;
            if (cosine <= 0.0) {
                continue;
            }
            double sineSquared = index * index * (1.0 - cosine * cosine);
            if (sineSquared >= 1.0) {
                continue;
            }
            double cosineOut = Math.sqrt(1.0 - sineSquared);
            double transmitted = 1.0 - IcePrism.fresnelReflectance(index, 1.0, cosine, cosineOut);
            double exitScale = cosineOut - index * cosine;
            viewX[face] = -(index * insideX + exitScale * faceX);
            viewY[face] = -(index * insideY + exitScale * faceY);
            viewZ[face] = -(index * insideZ + exitScale * faceZ);
            raw[face] = cosine * transmitted;
            total += raw[face];
        }
        if (total <= 0.0) {
            return;
        }
        for (int face = 0; face < faceCount; face++) {
            if (raw[face] > 0.0) {
                splat(map, viewX[face], viewY[face], viewZ[face], weight * raw[face] / total);
            }
        }
    }
}
