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

import com.teammoeg.frostedheart.content.climate.render.fog.FlakeShapeSet.ShapeCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Makes the shapes of falling snowflakes from crystals grown by ReiterCrystalSimulation, mostly as real snow falls:
 * aggregates of several crystals, rimed flakes, broken fragments, and a few single plates, dendrites and columns seen
 * from the side. The same seed always gives the same shapes.
 * Every shape is centered by its bounding box and scaled into its tile, leaving a margin of an eighth of the tile empty
 * on every side.
 * Allocates, and takes about 0.2 seconds for the default set, so it runs off the render thread.
 */
public final class FlakeShapeGenerator {
    public static final int DEFAULT_COUNT = 32;
    public static final int DEFAULT_SIZE = 64;
    public static final long DEFAULT_SEED = 20261126L;
    private static final int SIMULATION_GRID_SIZE = 64;
    private static final int SIMULATION_BOUNDARY_RADIUS = 28;
    private static final int SIMULATION_TARGET_RADIUS = 24;
    private static final int SMALLEST_POOL = 28;

    private FlakeShapeGenerator() {
    }

    public static FlakeShapeSet generate(long seed) {
        return generate(DEFAULT_COUNT, DEFAULT_SIZE, seed);
    }

    /**
     * @param size the side of each tile in pixels, at least 16
     */
    public static FlakeShapeSet generate(int count, int size, long seed) {
        if (count <= 0) {
            throw new IllegalArgumentException("Shape count must be positive");
        }
        if (size < 16) {
            throw new IllegalArgumentException("Shape size must be at least 16");
        }
        Random random = new Random(seed);
        FlakeShapeNoise noise = new FlakeShapeNoise(seed ^ 0x5DEECE66DL);
        List<ShapeCategory> categoryPlan = buildCategoryPlan(count, random);
        List<ReiterCrystalSimulation> crystalPool = buildCrystalPool(Math.max(SMALLEST_POOL, count), random, noise);
        byte[] outputData = new byte[count * size * size];
        double[] means = new double[count];
        ShapeCategory[] categories = new ShapeCategory[count];
        float[] workBuffer = new float[size * size];
        float[] scratchBuffer = new float[size * size];
        for (int shapeIndex = 0; shapeIndex < count; shapeIndex++) {
            ShapeCategory category = categoryPlan.get(shapeIndex);
            categories[shapeIndex] = category;
            clearBuffer(workBuffer);
            switch (category) {
                case AGGREGATE -> buildAggregate(workBuffer, scratchBuffer, size, random, noise, crystalPool);
                case RIMED -> buildRimedFlake(workBuffer, scratchBuffer, size, random, noise, crystalPool);
                case FRAGMENT -> buildBrokenFragment(workBuffer, scratchBuffer, size, random, noise, crystalPool);
                case PLATE -> buildSingleCrystal(workBuffer, size, random, noise, true);
                case DENDRITE -> buildSingleCrystal(workBuffer, size, random, noise, false);
                case COLUMN -> buildColumn(workBuffer, size, random, noise);
            }
            means[shapeIndex] = finalizeShape(workBuffer, scratchBuffer, size, outputData, shapeIndex * size * size);
        }
        return new FlakeShapeSet(count, size, outputData, means, categories);
    }

    /**
     * The category of each shape, 14 aggregates, 6 rimed flakes, 6 fragments and 2 of each single kind for the default
     * count and the same shares for another count, in an order shuffled by the seed.
     */
    private static List<ShapeCategory> buildCategoryPlan(int count, Random random) {
        List<ShapeCategory> plan = new ArrayList<>(count);
        if (count == DEFAULT_COUNT) {
            addCategory(plan, ShapeCategory.AGGREGATE, 14, count);
            addCategory(plan, ShapeCategory.RIMED, 6, count);
            addCategory(plan, ShapeCategory.FRAGMENT, 6, count);
            addCategory(plan, ShapeCategory.PLATE, 2, count);
            addCategory(plan, ShapeCategory.DENDRITE, 2, count);
            addCategory(plan, ShapeCategory.COLUMN, 2, count);
        } else {
            int aggregates = Math.max(1, (int) Math.round(count * 0.44));
            int rimed = Math.max(0, (int) Math.round(count * 0.19));
            int fragments = Math.max(0, (int) Math.round(count * 0.19));
            int plates = Math.max(0, (int) Math.round(count * 0.06));
            int dendrites = Math.max(0, (int) Math.round(count * 0.06));
            int columns = Math.max(0, count - aggregates - rimed - fragments - plates - dendrites);
            addCategory(plan, ShapeCategory.AGGREGATE, aggregates, count);
            addCategory(plan, ShapeCategory.RIMED, rimed, count);
            addCategory(plan, ShapeCategory.FRAGMENT, fragments, count);
            addCategory(plan, ShapeCategory.PLATE, plates, count);
            addCategory(plan, ShapeCategory.DENDRITE, dendrites, count);
            addCategory(plan, ShapeCategory.COLUMN, columns, count);
            while (plan.size() < count) {
                plan.add(ShapeCategory.AGGREGATE);
            }
        }
        for (int index = plan.size() - 1; index > 0; index--) {
            int swapIndex = random.nextInt(index + 1);
            ShapeCategory swapped = plan.get(index);
            plan.set(index, plan.get(swapIndex));
            plan.set(swapIndex, swapped);
        }
        return plan;
    }

    private static void addCategory(List<ShapeCategory> plan, ShapeCategory category, int times, int count) {
        for (int index = 0; index < times && plan.size() < count; index++) {
            plan.add(category);
        }
    }

    /**
     * Grows the crystals that aggregates, rimed flakes and fragments are built from, cycling through five regimes:
     * dendrites, broad stellar and sectored crystals, hollow sectored plates, plates that switch to dendritic tips, and
     * asymmetric clusters.
     */
    private static List<ReiterCrystalSimulation> buildCrystalPool(int poolSize, Random random, FlakeShapeNoise noise) {
        List<ReiterCrystalSimulation> pool = new ArrayList<>(poolSize);
        for (int index = 0; index < poolSize; index++) {
            ReiterCrystalSimulation simulation = new ReiterCrystalSimulation(SIMULATION_GRID_SIZE,
                    SIMULATION_BOUNDARY_RADIUS);
            ReiterCrystalSimulation.Parameters parameters = createPoolParameters(index % 5, random);
            simulation.run(parameters, random, noise);
            pool.add(simulation);
        }
        return pool;
    }

    private static ReiterCrystalSimulation.Parameters createPoolParameters(int regime, Random random) {
        return switch (regime) {
            case 0 -> new ReiterCrystalSimulation.Parameters(0.35 + random.nextDouble() * 0.14,
                    0.0004 + random.nextDouble() * 0.0020, 1.05 + random.nextDouble() * 0.65,
                    0.995 + random.nextDouble() * 0.015, 0.08 + random.nextDouble() * 0.10,
                    0.04 + random.nextDouble() * 0.07, random.nextBoolean() ? 3 : 0, 380, SIMULATION_TARGET_RADIUS, 0,
                    0.0, 1.0, false);
            case 1 -> new ReiterCrystalSimulation.Parameters(0.52 + random.nextDouble() * 0.22,
                    0.003 + random.nextDouble() * 0.010, 0.95 + random.nextDouble() * 0.40,
                    0.995 + random.nextDouble() * 0.015, 0.08 + random.nextDouble() * 0.08,
                    0.03 + random.nextDouble() * 0.07, random.nextBoolean() ? 3 : 5, 260, SIMULATION_TARGET_RADIUS, 0,
                    0.0, 1.0, false);
            case 2 -> new ReiterCrystalSimulation.Parameters(0.42 + random.nextDouble() * 0.35,
                    0.014 + random.nextDouble() * 0.040, 1.0, 1.0, 0.07 + random.nextDouble() * 0.06,
                    0.02 + random.nextDouble() * 0.06, random.nextInt(4) == 0 ? 3 : 0, 220, SIMULATION_TARGET_RADIUS, 0,
                    0.0, 1.0, true);
            case 3 -> new ReiterCrystalSimulation.Parameters(0.40 + random.nextDouble() * 0.18,
                    0.025 + random.nextDouble() * 0.030, 1.05, 1.0, 0.09 + random.nextDouble() * 0.06,
                    0.04 + random.nextDouble() * 0.06, 0, 280, SIMULATION_TARGET_RADIUS, 40 + random.nextInt(35),
                    0.001 + random.nextDouble() * 0.003, 0.85 + random.nextDouble() * 0.20, true);
            default -> new ReiterCrystalSimulation.Parameters(0.48 + random.nextDouble() * 0.22,
                    0.002 + random.nextDouble() * 0.008, 1.0 + random.nextDouble() * 0.50,
                    0.99 + random.nextDouble() * 0.025, 0.10 + random.nextDouble() * 0.10,
                    0.06 + random.nextDouble() * 0.08, 5, 280, SIMULATION_TARGET_RADIUS, 0, 0.0, 1.0, false);
        };
    }

    /**
     * Three to five crystals, sometimes with a column among them, stuck together near points of the ones before.
     */
    private static void buildAggregate(float[] target, float[] layerBuffer, int size, Random random,
            FlakeShapeNoise noise, List<ReiterCrystalSimulation> crystalPool) {
        int componentCount = 3 + random.nextInt(3);
        double center = size * 0.5;
        double baseScale = size * 0.33;
        for (int component = 0; component < componentCount; component++) {
            clearBuffer(layerBuffer);
            double offsetX;
            double offsetY;
            if (component == 0) {
                offsetX = center + (random.nextDouble() - 0.5) * size * 0.06;
                offsetY = center + (random.nextDouble() - 0.5) * size * 0.06;
            } else {
                double[] anchor = pickOccupiedPoint(target, size, random);
                double attachAngle = random.nextDouble() * Math.PI * 2.0;
                double attachRadius = size * (0.04 + random.nextDouble() * 0.07);
                offsetX = anchor[0] + Math.cos(attachAngle) * attachRadius;
                offsetY = anchor[1] + Math.sin(attachAngle) * attachRadius;
            }
            if (component > 0 && random.nextDouble() < 0.16) {
                double columnLength = size * (0.30 + random.nextDouble() * 0.16);
                double columnWidth = size * (0.09 + random.nextDouble() * 0.04);
                double columnAngle = random.nextDouble() * Math.PI * 2.0;
                drawColumnInto(layerBuffer, size, offsetX, offsetY, columnLength, columnWidth, columnAngle, false,
                        random, noise);
            } else {
                ReiterCrystalSimulation crystal = crystalPool.get(random.nextInt(crystalPool.size()));
                double scale = baseScale * (0.58 + random.nextDouble() * 0.38);
                double angle = random.nextDouble() * Math.PI * 2.0;
                boolean mirror = random.nextBoolean();
                rasterizeCrystal(layerBuffer, size, crystal, offsetX, offsetY, scale, angle, mirror);
                if (component == 0 && random.nextDouble() < 0.45) {
                    applyFractureCut(layerBuffer, size, offsetX, offsetY, scale, random.nextDouble() * Math.PI * 2.0,
                            random, noise, false);
                }
            }
            double layerStrength = 0.74 + random.nextDouble() * 0.26;
            compositeOpticalLayer(target, layerBuffer, size, layerStrength);
        }
        if (random.nextDouble() < 0.30) {
            int lightDroplets = 8 + random.nextInt(12);
            applyRiming(target, size, lightDroplets, 0.72, random, noise);
        }
        applyInteriorTexture(target, size, noise, random, 0.16);
    }

    /**
     * A single crystal or a small aggregate covered in frozen droplets.
     */
    private static void buildRimedFlake(float[] target, float[] layerBuffer, int size, Random random,
            FlakeShapeNoise noise, List<ReiterCrystalSimulation> crystalPool) {
        boolean aggregateBase = random.nextDouble() < 0.65;
        if (aggregateBase) {
            int componentCount = 2 + random.nextInt(3);
            double center = size * 0.5;
            double baseScale = size * 0.33;
            for (int component = 0; component < componentCount; component++) {
                clearBuffer(layerBuffer);
                double offsetX = center;
                double offsetY = center;
                if (component > 0) {
                    double[] anchor = pickOccupiedPoint(target, size, random);
                    double attachAngle = random.nextDouble() * Math.PI * 2.0;
                    double attachRadius = size * (0.04 + random.nextDouble() * 0.07);
                    offsetX = anchor[0] + Math.cos(attachAngle) * attachRadius;
                    offsetY = anchor[1] + Math.sin(attachAngle) * attachRadius;
                }
                ReiterCrystalSimulation crystal = crystalPool.get(random.nextInt(crystalPool.size()));
                double scale = baseScale * (0.60 + random.nextDouble() * 0.35);
                rasterizeCrystal(layerBuffer, size, crystal, offsetX, offsetY, scale,
                        random.nextDouble() * Math.PI * 2.0, random.nextBoolean());
                compositeOpticalLayer(target, layerBuffer, size, 0.85);
            }
        } else {
            ReiterCrystalSimulation crystal = crystalPool.get(random.nextInt(crystalPool.size()));
            rasterizeCrystal(target, size, crystal, size * 0.5, size * 0.5, size * 0.36,
                    random.nextDouble() * Math.PI * 2.0, random.nextBoolean());
        }
        int dropletCount = 16 + random.nextInt(26);
        double dropletOpacity = 0.78 + random.nextDouble() * 0.22;
        applyRiming(target, size, dropletCount, dropletOpacity, random, noise);
        applyInteriorTexture(target, size, noise, random, 0.14);
    }

    /**
     * A crystal cut by one or two jagged cleavage planes, sometimes with a smaller broken shard stuck to it.
     */
    private static void buildBrokenFragment(float[] target, float[] layerBuffer, int size, Random random,
            FlakeShapeNoise noise, List<ReiterCrystalSimulation> crystalPool) {
        ReiterCrystalSimulation primary = crystalPool.get(random.nextInt(crystalPool.size()));
        double center = size * 0.5;
        double primaryScale = size * (0.35 + random.nextDouble() * 0.07);
        rasterizeCrystal(target, size, primary, center, center, primaryScale, random.nextDouble() * Math.PI * 2.0,
                random.nextBoolean());
        double firstCutAngle = random.nextDouble() * Math.PI * 2.0;
        applyFractureCut(target, size, center, center, primaryScale, firstCutAngle, random, noise, true);
        if (random.nextDouble() < 0.55) {
            double secondCutAngle = firstCutAngle
                    + (random.nextBoolean() ? 1.0 : -1.0) * (0.55 + random.nextDouble() * 0.85);
            applyFractureCut(target, size, center, center, primaryScale, secondCutAngle, random, noise, true);
        }
        if (random.nextDouble() < 0.35) {
            clearBuffer(layerBuffer);
            double[] anchor = pickOccupiedPoint(target, size, random);
            ReiterCrystalSimulation shard = crystalPool.get(random.nextInt(crystalPool.size()));
            double shardScale = size * (0.24 + random.nextDouble() * 0.10);
            rasterizeCrystal(layerBuffer, size, shard, anchor[0], anchor[1], shardScale,
                    random.nextDouble() * Math.PI * 2.0, random.nextBoolean());
            applyFractureCut(layerBuffer, size, anchor[0], anchor[1], shardScale, random.nextDouble() * Math.PI * 2.0,
                    random, noise, true);
            compositeOpticalLayer(target, layerBuffer, size, 0.85);
        }
        applyInteriorTexture(target, size, noise, random, 0.15);
    }

    /**
     * One crystal grown for this shape, a plate with ribs and a nicked edge or a dendrite.
     */
    private static void buildSingleCrystal(float[] target, int size, Random random, FlakeShapeNoise noise,
            boolean isPlate) {
        ReiterCrystalSimulation simulation = new ReiterCrystalSimulation(SIMULATION_GRID_SIZE,
                SIMULATION_BOUNDARY_RADIUS);
        ReiterCrystalSimulation.Parameters parameters;
        if (isPlate) {
            boolean twoPhase = random.nextBoolean();
            parameters = new ReiterCrystalSimulation.Parameters(0.45 + random.nextDouble() * 0.32,
                    0.018 + random.nextDouble() * 0.038, 1.0, 1.0, 0.08 + random.nextDouble() * 0.06,
                    0.03 + random.nextDouble() * 0.04, random.nextBoolean() ? 3 : 0, 220, SIMULATION_TARGET_RADIUS,
                    twoPhase ? 45 + random.nextInt(25) : 0, 0.004 + random.nextDouble() * 0.008, 0.95, true);
        } else {
            parameters = new ReiterCrystalSimulation.Parameters(0.36 + random.nextDouble() * 0.12,
                    0.0003 + random.nextDouble() * 0.0014, 1.15 + random.nextDouble() * 0.55,
                    0.998 + random.nextDouble() * 0.012, 0.08 + random.nextDouble() * 0.07,
                    0.04 + random.nextDouble() * 0.05, random.nextBoolean() ? 3 : 0, 380, SIMULATION_TARGET_RADIUS, 0,
                    0.0, 1.0, false);
        }
        simulation.run(parameters, random, noise);
        rasterizeCrystal(target, size, simulation, size * 0.5, size * 0.5, size * 0.36,
                random.nextDouble() * Math.PI * 2.0, random.nextBoolean());
        if (isPlate && random.nextDouble() < 0.70) {
            double nickAngle = random.nextDouble() * Math.PI * 2.0;
            double nickX = size * 0.5 + Math.cos(nickAngle) * size * 0.27;
            double nickY = size * 0.5 + Math.sin(nickAngle) * size * 0.27;
            erodeCircularSpot(target, size, nickX, nickY, size * (0.06 + random.nextDouble() * 0.05), noise, random);
        }
        applyInteriorTexture(target, size, noise, random, isPlate ? 0.20 : 0.12);
    }

    private static void buildColumn(float[] target, int size, Random random, FlakeShapeNoise noise) {
        double center = size * 0.5;
        double length = size * (0.58 + random.nextDouble() * 0.10);
        double width = size * (0.19 + random.nextDouble() * 0.07);
        double angle = random.nextDouble() * Math.PI * 2.0;
        boolean capped = random.nextBoolean();
        drawColumnInto(target, size, center, center, length, width, angle, capped, random, noise);
        if (random.nextDouble() < 0.60) {
            int specks = 6 + random.nextInt(10);
            applyRiming(target, size, specks, 0.75, random, noise);
        }
    }

    /**
     * A hexagonal prism seen from the side, with dense walls, a lighter middle and hollow ends, and plates capping its
     * ends when capped.
     */
    private static void drawColumnInto(float[] target, int size, double centerX, double centerY, double length,
            double width, double angle, boolean capped, Random random, FlakeShapeNoise noise) {
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);
        double halfLength = length * 0.5;
        double halfWidth = width * 0.5;
        double noiseSeed = random.nextDouble() * 100.0;
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                double deltaX = pixelX + 0.5 - centerX;
                double deltaY = pixelY + 0.5 - centerY;
                double along = deltaX * cosAngle + deltaY * sinAngle;
                double across = -deltaX * sinAngle + deltaY * cosAngle;
                double absoluteAlong = Math.abs(along);
                double absoluteAcross = Math.abs(across);
                double value = 0.0;
                double roughness = noise.noise2D(pixelX * 0.22 + noiseSeed, pixelY * 0.22 + noiseSeed) * 0.9;
                double localHalfLength = halfLength + roughness;
                double localHalfWidth = halfWidth + roughness * 0.4;
                if (absoluteAlong <= localHalfLength && absoluteAcross <= localHalfWidth) {
                    double edgeSoftAlong = clamp01((localHalfLength - absoluteAlong) * 1.1);
                    double edgeSoftAcross = clamp01((localHalfWidth - absoluteAcross) * 1.1);
                    double edgeMask = Math.min(edgeSoftAlong, edgeSoftAcross);
                    double normalizedAcross = absoluteAcross / Math.max(1.0, localHalfWidth);
                    double normalizedAlong = absoluteAlong / Math.max(1.0, localHalfLength);
                    double wallDensity = 0.52 + 0.42 * Math.pow(normalizedAcross, 1.4);
                    if (normalizedAcross < 0.25) {
                        wallDensity += 0.14 * (1.0 - normalizedAcross * 4.0);
                    }
                    double hollowZone = clamp01((normalizedAlong - 0.50) / 0.45)
                            * (1.0 - clamp01((normalizedAcross - 0.20) / 0.65));
                    wallDensity -= 0.42 * hollowZone;
                    double internalGrain = noise.fractalNoise2D(along * 0.25 + noiseSeed, across * 0.35 + noiseSeed,
                            2);
                    wallDensity += 0.14 * internalGrain;
                    value = clamp01(wallDensity) * edgeMask;
                }
                if (capped) {
                    double capThickness = Math.max(2.2, width * 0.28);
                    double capHalfHeight = halfWidth * (1.35 + 0.25 * Math.sin(noiseSeed));
                    double capCenterDistance = halfLength - capThickness * 0.35;
                    double distanceToEnd = Math.abs(absoluteAlong - capCenterDistance);
                    if (distanceToEnd <= capThickness && absoluteAcross <= capHalfHeight) {
                        double capSoftAlong = clamp01((capThickness - distanceToEnd) * 1.0);
                        double capSoftAcross = clamp01((capHalfHeight - absoluteAcross) * 1.0);
                        double capValue = (0.72 + 0.22 * (absoluteAcross / Math.max(1.0, capHalfHeight)))
                                * Math.min(capSoftAlong, capSoftAcross);
                        value = Math.max(value, capValue);
                    }
                }
                int index = pixelY * size + pixelX;
                if (value > target[index]) {
                    target[index] = (float) value;
                }
            }
        }
    }

    /**
     * Draws a grown crystal turned by the angle and scaled to the radius in pixels into the target, keeping the larger
     * value where it overlaps what is there.
     */
    private static void rasterizeCrystal(float[] target, int size, ReiterCrystalSimulation simulation,
            double centerX, double centerY, double pixelRadius, double angle, boolean mirror) {
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);
        double simulationRadius = SIMULATION_TARGET_RADIUS;
        double scaleToSimulation = simulationRadius / Math.max(1.0, pixelRadius);
        double reach = (simulationRadius + 2.5) * (simulationRadius + 2.5);
        for (int pixelY = 0; pixelY < size; pixelY++) {
            double deltaY = pixelY + 0.5 - centerY;
            for (int pixelX = 0; pixelX < size; pixelX++) {
                double deltaX = pixelX + 0.5 - centerX;
                double rotatedX = deltaX * cosAngle + deltaY * sinAngle;
                double rotatedY = -deltaX * sinAngle + deltaY * cosAngle;
                if (mirror) {
                    rotatedX = -rotatedX;
                }
                double simulationX = rotatedX * scaleToSimulation;
                double simulationY = rotatedY * scaleToSimulation;
                if (simulationX * simulationX + simulationY * simulationY > reach) {
                    continue;
                }
                double sample = simulation.sampleCartesian(simulationX, simulationY);
                if (sample > 0.01) {
                    double boosted = Math.min(1.0, sample * 1.10);
                    int index = pixelY * size + pixelX;
                    if (boosted > target[index]) {
                        target[index] = (float) boosted;
                    }
                }
            }
        }
    }

    /**
     * Removes the part of the buffer beyond a jagged line through it, keeping the larger part when preserveMajority.
     */
    private static void applyFractureCut(float[] buffer, int size, double centerX, double centerY, double scale,
            double cutAngle, Random random, FlakeShapeNoise noise, boolean preserveMajority) {
        double normalX = Math.cos(cutAngle);
        double normalY = Math.sin(cutAngle);
        double offset = preserveMajority
                ? (random.nextDouble() - 0.35) * scale * 0.36
                : (random.nextDouble() - 0.5) * scale * 0.25;
        double noiseShift = random.nextDouble() * 100.0;
        double jagAmplitude = Math.max(1.5, size * 0.04);
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                int index = pixelY * size + pixelX;
                if (buffer[index] <= 0.0F) {
                    continue;
                }
                double deltaX = pixelX + 0.5 - centerX;
                double deltaY = pixelY + 0.5 - centerY;
                double signedDistance = deltaX * normalX + deltaY * normalY - offset;
                double jag = noise.fractalNoise2D(pixelX * 0.16 + noiseShift, pixelY * 0.16 + noiseShift, 2)
                        * jagAmplitude;
                double adjusted = signedDistance + jag;
                if (adjusted > 0.8) {
                    buffer[index] = 0.0F;
                } else if (adjusted > -0.8) {
                    double fade = clamp01((0.8 - adjusted) / 1.6);
                    buffer[index] = (float) (buffer[index] * fade);
                }
            }
        }
    }

    private static void erodeCircularSpot(float[] buffer, int size, double spotX, double spotY, double radius,
            FlakeShapeNoise noise, Random random) {
        double noiseShift = random.nextDouble() * 100.0;
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                int index = pixelY * size + pixelX;
                if (buffer[index] <= 0.0F) {
                    continue;
                }
                double distance = Math.hypot(pixelX + 0.5 - spotX, pixelY + 0.5 - spotY);
                double localRadius = radius
                        + noise.noise2D(pixelX * 0.25 + noiseShift, pixelY * 0.25 + noiseShift) * (radius * 0.35);
                if (distance < localRadius) {
                    buffer[index] = 0.0F;
                }
            }
        }
    }

    /**
     * Adds frozen droplets as small crisp beads near points of the ice, thickening where they overlap it.
     */
    private static void applyRiming(float[] buffer, int size, int dropletCount, double peakOpacity, Random random,
            FlakeShapeNoise noise) {
        double minRadius = Math.max(1.2, size * 0.022);
        double maxRadius = Math.max(2.2, size * 0.052);
        double noiseShift = random.nextDouble() * 100.0;
        for (int droplet = 0; droplet < dropletCount; droplet++) {
            double[] anchor = pickOccupiedPoint(buffer, size, random);
            double jitterAngle = random.nextDouble() * Math.PI * 2.0;
            double jitterDistance = random.nextDouble() * size * 0.032;
            double dropX = anchor[0] + Math.cos(jitterAngle) * jitterDistance;
            double dropY = anchor[1] + Math.sin(jitterAngle) * jitterDistance;
            double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);
            double density = peakOpacity * (0.78 + random.nextDouble() * 0.22);
            int startX = Math.max(0, (int) Math.floor(dropX - radius - 1.0));
            int endX = Math.min(size - 1, (int) Math.ceil(dropX + radius + 1.0));
            int startY = Math.max(0, (int) Math.floor(dropY - radius - 1.0));
            int endY = Math.min(size - 1, (int) Math.ceil(dropY + radius + 1.0));
            for (int pixelY = startY; pixelY <= endY; pixelY++) {
                for (int pixelX = startX; pixelX <= endX; pixelX++) {
                    double distance = Math.hypot(pixelX + 0.5 - dropX, pixelY + 0.5 - dropY);
                    double warpedDistance = distance
                            + noise.noise2D(pixelX * 0.55 + noiseShift, pixelY * 0.55 + noiseShift) * 0.55;
                    if (warpedDistance >= radius) {
                        continue;
                    }
                    double normalized = warpedDistance / radius;
                    double edge = clamp01((1.0 - normalized) / 0.36);
                    double beadMask = edge * edge * (3.0 - 2.0 * edge);
                    double beadAlpha = density * beadMask * (0.85 + 0.15 * (1.0 - normalized));
                    int index = pixelY * size + pixelX;
                    double existing = buffer[index];
                    double combined = Math.max(existing, beadAlpha);
                    if (existing > 0.18 && beadAlpha > 0.18) {
                        combined = Math.min(1.0, combined + 0.18 * Math.min(existing, beadAlpha));
                    }
                    buffer[index] = (float) clamp01(combined);
                }
            }
        }
    }

    /**
     * Varies the thickness of the ice with noise and opens holes where the noise is low and the ice thin.
     */
    private static void applyInteriorTexture(float[] buffer, int size, FlakeShapeNoise noise, Random random,
            double holeStrength) {
        double shiftX = random.nextDouble() * 150.0;
        double shiftY = random.nextDouble() * 150.0;
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                int index = pixelY * size + pixelX;
                double value = buffer[index];
                if (value <= 0.0F) {
                    continue;
                }
                double detail = noise.fractalNoise2D(pixelX * 0.34 + shiftX, pixelY * 0.34 + shiftY, 2);
                double modulated = value + detail * holeStrength;
                if (detail < -0.34 && value < 0.72) {
                    modulated *= 0.20;
                }
                buffer[index] = (float) clamp01(modulated);
            }
        }
    }

    /**
     * Lays a layer over the target, keeping the larger value and thickening the knots where both hold ice.
     */
    private static void compositeOpticalLayer(float[] target, float[] layer, int size, double strength) {
        int pixelCount = size * size;
        for (int index = 0; index < pixelCount; index++) {
            double layerValue = layer[index] * strength;
            if (layerValue <= 0.005) {
                continue;
            }
            double existing = target[index];
            double combined = Math.max(existing, layerValue);
            if (existing > 0.18 && layerValue > 0.18) {
                combined = Math.min(1.0, combined + 0.28 * Math.min(existing, layerValue));
            }
            target[index] = (float) combined;
        }
    }

    /**
     * A random point of the buffer that holds ice, or the weighted middle of its ice when 48 tries find none, or the
     * middle of the tile when it holds no ice.
     */
    private static double[] pickOccupiedPoint(float[] buffer, int size, Random random) {
        for (int attempt = 0; attempt < 48; attempt++) {
            int pixelX = random.nextInt(size);
            int pixelY = random.nextInt(size);
            if (buffer[pixelY * size + pixelX] > 0.22F) {
                return new double[]{pixelX + 0.5, pixelY + 0.5};
            }
        }
        double weightSum = 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                double value = buffer[pixelY * size + pixelX];
                if (value > 0.10) {
                    weightSum += value;
                    sumX += (pixelX + 0.5) * value;
                    sumY += (pixelY + 0.5) * value;
                }
            }
        }
        if (weightSum > 0.0) {
            return new double[]{sumX / weightSum, sumY / weightSum};
        }
        return new double[]{size * 0.5, size * 0.5};
    }

    /**
     * Clears faint and lone pixels, centers the shape by its bounding box, scales it into the tile inside the margin,
     * brings its densest knot close to opaque, and writes it as bytes.
     * @return the mean of the written bytes
     */
    private static double finalizeShape(float[] source, float[] resampled, int size, byte[] outputData,
            int outputOffset) {
        int minX = size;
        int maxX = -1;
        int minY = size;
        int maxY = -1;
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                int index = pixelY * size + pixelX;
                float value = source[index];
                if (value <= 0.06F) {
                    source[index] = 0.0F;
                    continue;
                }
                int neighborSupport = 0;
                if (pixelX > 0 && source[index - 1] > 0.06F) {
                    neighborSupport++;
                }
                if (pixelX < size - 1 && source[index + 1] > 0.06F) {
                    neighborSupport++;
                }
                if (pixelY > 0 && source[index - size] > 0.06F) {
                    neighborSupport++;
                }
                if (pixelY < size - 1 && source[index + size] > 0.06F) {
                    neighborSupport++;
                }
                if (neighborSupport == 0 && value < 0.35F) {
                    source[index] = 0.0F;
                    continue;
                }
                minX = Math.min(minX, pixelX);
                maxX = Math.max(maxX, pixelX);
                minY = Math.min(minY, pixelY);
                maxY = Math.max(maxY, pixelY);
            }
        }
        int margin = (int) Math.ceil(size / 8.0);
        int activeSpan = size - 2 * margin;
        if (maxX < minX || maxY < minY) {
            for (int index = 0; index < size * size; index++) {
                outputData[outputOffset + index] = 0;
            }
            return 0.0;
        }
        double boxWidth = (maxX - minX) + 1.0;
        double boxHeight = (maxY - minY) + 1.0;
        double boxCenterX = (minX + maxX + 1.0) * 0.5;
        double boxCenterY = (minY + maxY + 1.0) * 0.5;
        double targetCenter = size * 0.5;
        double targetSpan = Math.max(1.0, activeSpan * 0.94);
        double scale = targetSpan / Math.max(boxWidth, boxHeight);
        double maxValue = 0.0;
        for (int pixelY = 0; pixelY < size; pixelY++) {
            for (int pixelX = 0; pixelX < size; pixelX++) {
                if (pixelX < margin || pixelX >= size - margin || pixelY < margin || pixelY >= size - margin) {
                    resampled[pixelY * size + pixelX] = 0.0F;
                    continue;
                }
                double sourceX = (pixelX + 0.5 - targetCenter) / scale + boxCenterX - 0.5;
                double sourceY = (pixelY + 0.5 - targetCenter) / scale + boxCenterY - 0.5;
                double sampled = sampleBilinear(source, size, sourceX, sourceY);
                if (sampled < 0.025) {
                    sampled = 0.0;
                }
                maxValue = Math.max(maxValue, sampled);
                resampled[pixelY * size + pixelX] = (float) sampled;
            }
        }
        double normalizeFactor = maxValue > 0.15 && maxValue < 0.92 ? 0.96 / maxValue : 1.0;
        long sum = 0L;
        int pixelCount = size * size;
        for (int index = 0; index < pixelCount; index++) {
            int quantized = (int) Math.round(clamp01(resampled[index] * normalizeFactor) * 255.0);
            outputData[outputOffset + index] = (byte) quantized;
            sum += quantized;
        }
        return (double) sum / (double) pixelCount;
    }

    private static double sampleBilinear(float[] buffer, int size, double coordinateX, double coordinateY) {
        int baseX = (int) Math.floor(coordinateX);
        int baseY = (int) Math.floor(coordinateY);
        double fractionX = coordinateX - baseX;
        double fractionY = coordinateY - baseY;
        double value00 = readClampedZero(buffer, size, baseX, baseY);
        double value10 = readClampedZero(buffer, size, baseX + 1, baseY);
        double value01 = readClampedZero(buffer, size, baseX, baseY + 1);
        double value11 = readClampedZero(buffer, size, baseX + 1, baseY + 1);
        double top = value00 + fractionX * (value10 - value00);
        double bottom = value01 + fractionX * (value11 - value01);
        return top + fractionY * (bottom - top);
    }

    private static double readClampedZero(float[] buffer, int size, int pixelX, int pixelY) {
        if (pixelX < 0 || pixelX >= size || pixelY < 0 || pixelY >= size) {
            return 0.0;
        }
        return buffer[pixelY * size + pixelX];
    }

    private static void clearBuffer(float[] buffer) {
        Arrays.fill(buffer, 0.0F);
    }

    private static double clamp01(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }
}
