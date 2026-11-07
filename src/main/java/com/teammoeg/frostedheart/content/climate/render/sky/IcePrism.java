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
 * Light paths through the hexagonal cross section of an ice prism, in the plane perpendicular to its axis.
 * The hexagon has side 1, and light enters travelling along minus x. Deviations are signed angles from the
 * incoming direction to the outgoing one.
 */
final class IcePrism {
    private static final int SIDE_COUNT = 6;
    private static final int MAXIMUM_INTERACTIONS = 12;
    private static final int MAXIMUM_MIRROR_REFLECTIONS = 40;
    private static final double MINIMUM_WEIGHT = 1.0e-5;
    private static final double APOTHEM = Math.cos(Math.PI / 6.0);
    private static final double MEAN_PROJECTED_WIDTH = 6.0 / Math.PI;

    interface OutputConsumer {
        void accept(double deviation, double weight);
    }

    interface ReflectanceFunction {
        double reflectance(double inPlaneCosine);
    }

    /**
     * SplitMix64, seeded from four numbers so that results do not depend on the order of work.
     */
    static final class SplitMixRandom {
        private long state;

        void seed(long seed, long row, long column, long sample) {
            state = seed * 0x9E3779B97F4A7C15L + row * 0xBF58476D1CE4E5B9L
                    + column * 0x94D049BB133111EBL + sample * 0xD6E8FEB86659FD93L;
        }

        double next() {
            state += 0x9E3779B97F4A7C15L;
            long mixed = state;
            mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
            mixed = mixed ^ (mixed >>> 31);
            return (mixed >>> 11) * 0x1.0p-53;
        }
    }

    private IcePrism() {
    }

    /**
     * Traces light that enters and leaves through side faces, splitting at every face by Fresnel weights.
     * The weights of one call add up to the prism's projected width over its mean projected width.
     */
    static void trace(double index, double rotation, int entrySamples, SplitMixRandom random,
            OutputConsumer consumer) {
        double[] normalX = new double[SIDE_COUNT];
        double[] normalY = new double[SIDE_COUNT];
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int side = 0; side < SIDE_COUNT; side++) {
            double angle = rotation + side * Math.PI / 3.0;
            normalX[side] = Math.cos(angle);
            normalY[side] = Math.sin(angle);
            double vertexY = Math.sin(angle + Math.PI / 6.0);
            lowest = Math.min(lowest, vertexY);
            highest = Math.max(highest, vertexY);
        }
        double width = highest - lowest;
        double sampleWeight = width / entrySamples / MEAN_PROJECTED_WIDTH;
        for (int sample = 0; sample < entrySamples; sample++) {
            double height = lowest + (sample + random.next()) / entrySamples * width;
            int entrySide = -1;
            double entryX = Double.MAX_VALUE;
            for (int side = 0; side < SIDE_COUNT; side++) {
                if (normalX[side] > 1.0e-12) {
                    double x = (APOTHEM - normalY[side] * height) / normalX[side];
                    if (x < entryX) {
                        entryX = x;
                        entrySide = side;
                    }
                }
            }
            if (entrySide < 0) {
                continue;
            }
            double cosineIncident = normalX[entrySide];
            double inverseIndex = 1.0 / index;
            double cosineTransmitted = Math.sqrt(1.0 - inverseIndex * inverseIndex
                    * (1.0 - cosineIncident * cosineIncident));
            double reflectance = fresnelReflectance(1.0, index, cosineIncident, cosineTransmitted);
            double reflectedX = -1.0 + 2.0 * cosineIncident * normalX[entrySide];
            double reflectedY = 2.0 * cosineIncident * normalY[entrySide];
            consumer.accept(Math.atan2(-reflectedY, -reflectedX), sampleWeight * reflectance);
            double scale = inverseIndex * cosineIncident - cosineTransmitted;
            double directionX = -inverseIndex + scale * normalX[entrySide];
            double directionY = scale * normalY[entrySide];
            inside(index, normalX, normalY, entryX, height, directionX, directionY,
                    sampleWeight * (1.0 - reflectance), 0, consumer);
        }
    }

    private static void inside(double index, double[] normalX, double[] normalY, double positionX, double positionY,
            double directionX, double directionY, double weight, int depth, OutputConsumer consumer) {
        if (depth >= MAXIMUM_INTERACTIONS || weight < MINIMUM_WEIGHT) {
            return;
        }
        int exitSide = -1;
        double nearest = Double.MAX_VALUE;
        for (int side = 0; side < SIDE_COUNT; side++) {
            double along = directionX * normalX[side] + directionY * normalY[side];
            if (along > 1.0e-12) {
                double distance = (APOTHEM - positionX * normalX[side] - positionY * normalY[side]) / along;
                if (distance < nearest) {
                    nearest = distance;
                    exitSide = side;
                }
            }
        }
        if (exitSide < 0) {
            return;
        }
        nearest = Math.max(0.0, nearest);
        double nextX = positionX + nearest * directionX;
        double nextY = positionY + nearest * directionY;
        double cosine = directionX * normalX[exitSide] + directionY * normalY[exitSide];
        double sineSquared = index * index * (1.0 - cosine * cosine);
        double reflectedX = directionX - 2.0 * cosine * normalX[exitSide];
        double reflectedY = directionY - 2.0 * cosine * normalY[exitSide];
        if (sineSquared >= 1.0) {
            inside(index, normalX, normalY, nextX, nextY, reflectedX, reflectedY, weight, depth + 1, consumer);
            return;
        }
        double cosineOut = Math.sqrt(1.0 - sineSquared);
        double reflectance = fresnelReflectance(index, 1.0, cosine, cosineOut);
        double scale = cosineOut - index * cosine;
        double exitX = index * directionX + scale * normalX[exitSide];
        double exitY = index * directionY + scale * normalY[exitSide];
        consumer.accept(Math.atan2(-exitY, -exitX), weight * (1.0 - reflectance));
        inside(index, normalX, normalY, nextX, nextY, reflectedX, reflectedY, weight * reflectance, depth + 1,
                consumer);
    }

    /**
     * Follows light that entered an end face as it reflects off the side faces on its way to the other end face.
     * Travel is the in-plane distance covered between the two end faces. Entry points are uniform over the hexagon.
     */
    static void traceMirrors(double rotation, double travel, int entrySamples, SplitMixRandom random,
            ReflectanceFunction reflectance, OutputConsumer consumer) {
        double[] normalX = new double[SIDE_COUNT];
        double[] normalY = new double[SIDE_COUNT];
        for (int side = 0; side < SIDE_COUNT; side++) {
            normalX[side] = Math.cos(rotation + side * Math.PI / 3.0);
            normalY[side] = Math.sin(rotation + side * Math.PI / 3.0);
        }
        for (int sample = 0; sample < entrySamples; sample++) {
            int triangle = Math.min(SIDE_COUNT - 1, (int) (random.next() * SIDE_COUNT));
            double firstAngle = rotation + Math.PI / 6.0 + triangle * Math.PI / 3.0;
            double secondAngle = firstAngle + Math.PI / 3.0;
            double root = Math.sqrt(random.next());
            double split = random.next();
            double positionX = root * (1.0 - split) * Math.cos(firstAngle) + root * split * Math.cos(secondAngle);
            double positionY = root * (1.0 - split) * Math.sin(firstAngle) + root * split * Math.sin(secondAngle);
            double directionX = -1.0;
            double directionY = 0.0;
            double remaining = travel;
            double weight = 1.0 / entrySamples;
            for (int interaction = 0; interaction <= MAXIMUM_MIRROR_REFLECTIONS; interaction++) {
                int nearestSide = -1;
                double nearest = Double.MAX_VALUE;
                for (int side = 0; side < SIDE_COUNT; side++) {
                    double along = directionX * normalX[side] + directionY * normalY[side];
                    if (along > 1.0e-12) {
                        double distance = (APOTHEM - positionX * normalX[side] - positionY * normalY[side]) / along;
                        if (distance < nearest) {
                            nearest = Math.max(0.0, distance);
                            nearestSide = side;
                        }
                    }
                }
                if (nearestSide < 0 || nearest >= remaining) {
                    consumer.accept(Math.atan2(-directionY, -directionX), weight);
                    break;
                }
                positionX += nearest * directionX;
                positionY += nearest * directionY;
                remaining -= nearest;
                double cosine = directionX * normalX[nearestSide] + directionY * normalY[nearestSide];
                weight *= reflectance.reflectance(cosine);
                if (weight < MINIMUM_WEIGHT) {
                    break;
                }
                directionX -= 2.0 * cosine * normalX[nearestSide];
                directionY -= 2.0 * cosine * normalY[nearestSide];
            }
        }
    }

    static double fresnelReflectance(double fromIndex, double toIndex, double cosineIncident,
            double cosineTransmitted) {
        double perpendicular = (fromIndex * cosineIncident - toIndex * cosineTransmitted)
                / (fromIndex * cosineIncident + toIndex * cosineTransmitted);
        double parallel = (toIndex * cosineIncident - fromIndex * cosineTransmitted)
                / (toIndex * cosineIncident + fromIndex * cosineTransmitted);
        return 0.5 * (perpendicular * perpendicular + parallel * parallel);
    }
}
