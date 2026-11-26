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

import java.util.Random;

/**
 * Grows one snow crystal with the hexagonal cellular model of Reiter, "A local cellular model for snow crystal growth"
 * (2005), on an axial grid, from a vapor field perturbed by noise, and then samples how much light it blocks at any
 * point.
 * A cell freezes once its water reaches 1. Cells next to frozen ones are receptive: they keep their water and gain the
 * addition constant each step, while the water of the others diffuses between neighbors.
 */
public final class ReiterCrystalSimulation {
    /**
     * The growth parameters of one crystal.
     * The background level is Reiter's beta, the addition constant his gamma, and the diffusion weight his alpha. The
     * humidity factor scales the water that diffuses from neighbors, and the noise period and magnitude perturb the
     * background. A seed patch radius of 3 or 5 freezes some cells around the center at the start. A positive switch
     * step changes the addition constant to the second one at that step and scales the unfrozen water by the second
     * background scale. Hollow plate modulation thins fully surrounded cells into ribs and rings.
     */
    public static final class Parameters {
        public final double backgroundLevel;
        public final double additionConstant;
        public final double diffusionWeight;
        public final double humidityFactor;
        public final double noisePeriod;
        public final double noiseMagnitude;
        public final int seedPatchRadius;
        public final int maxSteps;
        public final int targetRadius;
        public final int switchStep;
        public final double secondAdditionConstant;
        public final double secondBackgroundScale;
        public final boolean hollowPlateModulation;

        public Parameters(double backgroundLevel, double additionConstant, double diffusionWeight,
                double humidityFactor, double noisePeriod, double noiseMagnitude, int seedPatchRadius, int maxSteps,
                int targetRadius, int switchStep, double secondAdditionConstant, double secondBackgroundScale,
                boolean hollowPlateModulation) {
            this.backgroundLevel = backgroundLevel;
            this.additionConstant = additionConstant;
            this.diffusionWeight = diffusionWeight;
            this.humidityFactor = humidityFactor;
            this.noisePeriod = noisePeriod;
            this.noiseMagnitude = noiseMagnitude;
            this.seedPatchRadius = seedPatchRadius;
            this.maxSteps = maxSteps;
            this.targetRadius = targetRadius;
            this.switchStep = switchStep;
            this.secondAdditionConstant = secondAdditionConstant;
            this.secondBackgroundScale = secondBackgroundScale;
            this.hollowPlateModulation = hollowPlateModulation;
        }
    }

    private static final double HEX_HEIGHT_FACTOR = Math.sqrt(3.0) * 0.5;
    private static final byte STATE_OUT_OF_BOUNDS = 0;
    private static final byte STATE_NON_RECEPTIVE = 1;
    private static final byte STATE_RECEPTIVE = 2;

    private final int gridSize;
    private final int centerIndex;
    private final int boundaryRadius;
    private float[] currentField;
    private float[] nextField;
    private final float[] diffusionField;
    private final byte[] cellState;
    private final int[] freezeStep;
    private final int[] hexDistances;
    private final float[] coverageGrid;
    private final float[] opacityGrid;

    /**
     * @param boundaryRadius the hexagonal distance from the center at which cells stay at the background level
     */
    public ReiterCrystalSimulation(int gridSize, int boundaryRadius) {
        this.gridSize = gridSize;
        this.centerIndex = gridSize / 2;
        this.boundaryRadius = boundaryRadius;
        int cellCount = gridSize * gridSize;
        this.currentField = new float[cellCount];
        this.nextField = new float[cellCount];
        this.diffusionField = new float[cellCount];
        this.cellState = new byte[cellCount];
        this.freezeStep = new int[cellCount];
        this.hexDistances = new int[cellCount];
        this.coverageGrid = new float[cellCount];
        this.opacityGrid = new float[cellCount];
        for (int row = 0; row < gridSize; row++) {
            int offsetI = row - centerIndex;
            for (int column = 0; column < gridSize; column++) {
                int offsetJ = column - centerIndex;
                int offsetK = -offsetI - offsetJ;
                int hexDistance = Math.max(Math.abs(offsetI), Math.max(Math.abs(offsetJ), Math.abs(offsetK)));
                this.hexDistances[row * gridSize + column] = hexDistance;
            }
        }
    }

    /**
     * Grows the crystal until it reaches the target radius or runs out of steps.
     */
    public void run(Parameters parameters, Random random, FlakeShapeNoise noise) {
        int cellCount = gridSize * gridSize;
        double noiseOffsetX = random.nextDouble() * 200.0;
        double noiseOffsetY = random.nextDouble() * 200.0;
        for (int row = 0; row < gridSize; row++) {
            int rowBase = row * gridSize;
            for (int column = 0; column < gridSize; column++) {
                int index = rowBase + column;
                if (hexDistances[index] >= boundaryRadius || row <= 1 || row >= gridSize - 2 || column <= 1
                        || column >= gridSize - 2) {
                    cellState[index] = STATE_OUT_OF_BOUNDS;
                    currentField[index] = (float) parameters.backgroundLevel;
                } else {
                    cellState[index] = STATE_NON_RECEPTIVE;
                    double baseValue = parameters.backgroundLevel;
                    if (parameters.noiseMagnitude > 0.0) {
                        double noiseValue = noise.noise2D(row * parameters.noisePeriod + noiseOffsetX,
                                column * parameters.noisePeriod + noiseOffsetY);
                        baseValue += noiseValue * parameters.noiseMagnitude;
                    }
                    if (baseValue < 0.0) {
                        baseValue = 0.0;
                    }
                    if (baseValue > 0.98) {
                        baseValue = 0.98;
                    }
                    currentField[index] = (float) baseValue;
                }
                freezeStep[index] = -1;
            }
        }
        seedCrystal(parameters, random);
        double activeAddition = parameters.additionConstant;
        int totalSteps = 0;
        boolean switched = false;
        for (int stepIndex = 1; stepIndex <= parameters.maxSteps; stepIndex++) {
            if (!switched && parameters.switchStep > 0 && stepIndex >= parameters.switchStep) {
                switched = true;
                activeAddition = parameters.secondAdditionConstant;
                if (parameters.secondBackgroundScale != 1.0) {
                    for (int index = 0; index < cellCount; index++) {
                        if (cellState[index] != STATE_OUT_OF_BOUNDS && currentField[index] < 1.0F) {
                            currentField[index] = (float) (currentField[index] * parameters.secondBackgroundScale);
                        }
                    }
                }
            }
            int maxFrozenDistance = markReceptiveSites(stepIndex);
            totalSteps = stepIndex;
            if (maxFrozenDistance >= parameters.targetRadius) {
                break;
            }
            stepDiffusion(activeAddition, parameters.diffusionWeight, parameters.humidityFactor);
        }
        buildOpacityGrid(parameters, totalSteps, noise, noiseOffsetX, noiseOffsetY);
    }

    private void seedCrystal(Parameters parameters, Random random) {
        int centerCell = centerIndex * gridSize + centerIndex;
        currentField[centerCell] = 1.0F;
        freezeStep[centerCell] = 0;
        if (parameters.seedPatchRadius <= 0) {
            return;
        }
        int patchRadius = parameters.seedPatchRadius == 3 ? 1 : 2;
        for (int deltaI = -patchRadius; deltaI <= patchRadius; deltaI++) {
            for (int deltaJ = -patchRadius; deltaJ <= patchRadius; deltaJ++) {
                int deltaK = -deltaI - deltaJ;
                int distance = Math.max(Math.abs(deltaI), Math.max(Math.abs(deltaJ), Math.abs(deltaK)));
                if (distance > 0 && distance <= patchRadius) {
                    int index = (centerIndex + deltaI) * gridSize + (centerIndex + deltaJ);
                    if (random.nextDouble() < 0.45) {
                        currentField[index] = 1.0F;
                        freezeStep[index] = 0;
                    }
                }
            }
        }
    }

    /**
     * Records the step at which each cell froze and marks the cells next to frozen ones receptive.
     * @return the largest hexagonal distance of a frozen cell from the center
     */
    private int markReceptiveSites(int stepIndex) {
        int maxFrozenDistance = 0;
        for (int row = 1; row < gridSize - 1; row++) {
            int rowBase = row * gridSize;
            for (int column = 1; column < gridSize - 1; column++) {
                int index = rowBase + column;
                if (cellState[index] == STATE_OUT_OF_BOUNDS) {
                    continue;
                }
                if (currentField[index] >= 1.0F && freezeStep[index] < 0) {
                    freezeStep[index] = stepIndex;
                }
                if (freezeStep[index] >= 0) {
                    int distance = hexDistances[index];
                    if (distance > maxFrozenDistance) {
                        maxFrozenDistance = distance;
                    }
                }
            }
        }
        for (int row = 1; row < gridSize - 1; row++) {
            int rowBase = row * gridSize;
            for (int column = 1; column < gridSize - 1; column++) {
                int index = rowBase + column;
                if (cellState[index] == STATE_OUT_OF_BOUNDS) {
                    continue;
                }
                boolean isReceptive = currentField[index] >= 1.0F
                        || currentField[index - gridSize] >= 1.0F
                        || currentField[index - gridSize + 1] >= 1.0F
                        || currentField[index - 1] >= 1.0F
                        || currentField[index + 1] >= 1.0F
                        || currentField[index + gridSize - 1] >= 1.0F
                        || currentField[index + gridSize] >= 1.0F;
                if (isReceptive) {
                    cellState[index] = STATE_RECEPTIVE;
                    diffusionField[index] = 0.0F;
                } else {
                    cellState[index] = STATE_NON_RECEPTIVE;
                    diffusionField[index] = currentField[index];
                }
            }
        }
        return maxFrozenDistance;
    }

    private void stepDiffusion(double additionConstant, double diffusionWeight, double humidityFactor) {
        float neighborFactor = (float) (diffusionWeight * humidityFactor / 12.0);
        float centerFactor = (float) (1.0 - 0.5 * diffusionWeight);
        float addition = (float) additionConstant;
        for (int row = 1; row < gridSize - 1; row++) {
            int rowBase = row * gridSize;
            for (int column = 1; column < gridSize - 1; column++) {
                int index = rowBase + column;
                byte state = cellState[index];
                if (state == STATE_OUT_OF_BOUNDS) {
                    nextField[index] = currentField[index];
                    continue;
                }
                float neighborSum = diffusionField[index - gridSize]
                        + diffusionField[index - gridSize + 1]
                        + diffusionField[index - 1]
                        + diffusionField[index + 1]
                        + diffusionField[index + gridSize - 1]
                        + diffusionField[index + gridSize];
                float diffusedValue = centerFactor * diffusionField[index] + neighborFactor * neighborSum;
                if (state == STATE_RECEPTIVE) {
                    nextField[index] = currentField[index] + addition + diffusedValue;
                } else {
                    nextField[index] = diffusedValue;
                }
            }
        }
        float[] swapped = currentField;
        currentField = nextField;
        nextField = swapped;
    }

    /**
     * Gives each frozen cell an opacity that grows with its age and its frozen neighbors.
     */
    private void buildOpacityGrid(Parameters parameters, int totalSteps, FlakeShapeNoise noise, double noiseOffsetX,
            double noiseOffsetY) {
        int cellCount = gridSize * gridSize;
        for (int index = 0; index < cellCount; index++) {
            coverageGrid[index] = 0.0F;
            opacityGrid[index] = 0.0F;
        }
        double safeSteps = Math.max(1.0, totalSteps);
        for (int row = 1; row < gridSize - 1; row++) {
            int rowBase = row * gridSize;
            for (int column = 1; column < gridSize - 1; column++) {
                int index = rowBase + column;
                if (currentField[index] < 1.0F) {
                    continue;
                }
                coverageGrid[index] = 1.0F;
                int frozenNeighbors = 0;
                if (currentField[index - gridSize] >= 1.0F) {
                    frozenNeighbors++;
                }
                if (currentField[index - gridSize + 1] >= 1.0F) {
                    frozenNeighbors++;
                }
                if (currentField[index - 1] >= 1.0F) {
                    frozenNeighbors++;
                }
                if (currentField[index + 1] >= 1.0F) {
                    frozenNeighbors++;
                }
                if (currentField[index + gridSize - 1] >= 1.0F) {
                    frozenNeighbors++;
                }
                if (currentField[index + gridSize] >= 1.0F) {
                    frozenNeighbors++;
                }
                int stepFrozen = freezeStep[index];
                double ageRatio = stepFrozen >= 0 ? (safeSteps - stepFrozen) / safeSteps : 0.5;
                double densityRatio = frozenNeighbors / 6.0;
                double opacity = 0.36 + 0.36 * ageRatio + 0.26 * densityRatio;
                if (parameters.hollowPlateModulation) {
                    int offsetI = row - centerIndex;
                    int offsetJ = column - centerIndex;
                    double cartesianX = offsetI + 0.5 * offsetJ;
                    double cartesianY = offsetJ * HEX_HEIGHT_FACTOR;
                    double radius = Math.hypot(cartesianX, cartesianY);
                    double angle = Math.atan2(cartesianY, cartesianX);
                    double ribFactor = Math.pow(Math.abs(Math.cos(3.0 * angle)), 2.2);
                    double ringWave = 0.5 + 0.5 * Math.cos(radius * 0.95);
                    if (frozenNeighbors == 6 && radius > 2.2) {
                        double hollowMask = 0.28 + 0.45 * ribFactor + 0.32 * ringWave;
                        opacity *= hollowMask;
                    }
                }
                double microVariation = noise.noise2D(row * 0.42 + noiseOffsetX, column * 0.42 + noiseOffsetY);
                opacity += 0.10 * microVariation;
                if (opacity < 0.20) {
                    opacity = 0.20;
                }
                if (opacity > 1.0) {
                    opacity = 1.0;
                }
                opacityGrid[index] = (float) opacity;
            }
        }
    }

    /**
     * How much light the grown crystal blocks at a point given in cells from its center, from 0 to 1, with crisp edges.
     */
    public double sampleCartesian(double coordinateX, double coordinateY) {
        double axialJ = coordinateY / HEX_HEIGHT_FACTOR;
        double axialI = coordinateX - 0.5 * axialJ;
        double gridRow = axialI + centerIndex;
        double gridColumn = axialJ + centerIndex;
        int baseRow = (int) Math.floor(gridRow);
        int baseColumn = (int) Math.floor(gridColumn);
        if (baseRow < 0 || baseRow >= gridSize - 1 || baseColumn < 0 || baseColumn >= gridSize - 1) {
            return 0.0;
        }
        double fractionRow = gridRow - baseRow;
        double fractionColumn = gridColumn - baseColumn;
        int index00 = baseRow * gridSize + baseColumn;
        int index10 = index00 + gridSize;
        int index01 = index00 + 1;
        int index11 = index10 + 1;
        double weight00 = (1.0 - fractionRow) * (1.0 - fractionColumn);
        double weight10 = fractionRow * (1.0 - fractionColumn);
        double weight01 = (1.0 - fractionRow) * fractionColumn;
        double weight11 = fractionRow * fractionColumn;
        double rawCoverage = weight00 * coverageGrid[index00] + weight10 * coverageGrid[index10]
                + weight01 * coverageGrid[index01] + weight11 * coverageGrid[index11];
        if (rawCoverage <= 0.18) {
            return 0.0;
        }
        double edgeFactor = (rawCoverage - 0.18) / (0.68 - 0.18);
        if (edgeFactor > 1.0) {
            edgeFactor = 1.0;
        }
        double crispCoverage = edgeFactor * edgeFactor * (3.0 - 2.0 * edgeFactor);
        double weightedOpacity = (weight00 * opacityGrid[index00] + weight10 * opacityGrid[index10]
                + weight01 * opacityGrid[index01] + weight11 * opacityGrid[index11]) / rawCoverage;
        return crispCoverage * weightedOpacity;
    }
}
