package com.magmaguy.betterstructures.util;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class WeighedProbability {

    /**
     * Optimized weighted probability selection using entry set iteration
     * to avoid multiple map lookups and reduce object creation.
     */
    public static Integer pickWeightedProbability(Map<Integer, Double> weighedValues) {
        if (weighedValues == null || weighedValues.isEmpty()) {
            return null;
        }

        // Calculate total weight in a single pass
        double totalWeight = 0.0;
        for (double weight : weighedValues.values()) {
            totalWeight += weight;
        }

        // Early return for zero total weight
        if (totalWeight <= 0.0) {
            return null;
        }

        // Use ThreadLocalRandom for better performance than Math.random()
        double random = ThreadLocalRandom.current().nextDouble(totalWeight);

        // Iterate using entry set to avoid multiple map lookups
        for (Map.Entry<Integer, Double> entry : weighedValues.entrySet()) {
            random -= entry.getValue();
            if (random <= 0.0) {
                return entry.getKey();
            }
        }

        // Fallback: return the last element (should rarely happen due to floating point precision)
        return weighedValues.keySet().iterator().next();
    }

    /**
     * Alternative implementation that precomputes cumulative weights for better performance
     * when the same weighted values are used repeatedly.
     */
    public static class CachedWeightedProbability {
        private final Map<Integer, Double> originalWeights;
        private final Integer[] keys;
        private final double[] cumulativeWeights;
        private final double totalWeight;

        public CachedWeightedProbability(Map<Integer, Double> weighedValues) {
            this.originalWeights = weighedValues;
            int size = weighedValues.size();
            this.keys = new Integer[size];
            this.cumulativeWeights = new double[size];

            double cumulative = 0.0;
            int index = 0;
            for (Map.Entry<Integer, Double> entry : weighedValues.entrySet()) {
                keys[index] = entry.getKey();
                cumulative += entry.getValue();
                cumulativeWeights[index] = cumulative;
                index++;
            }
            this.totalWeight = cumulative;
        }

        public Integer pick() {
            if (totalWeight <= 0.0) {
                return null;
            }

            double random = ThreadLocalRandom.current().nextDouble(totalWeight);

            // Binary search for O(log n) performance instead of O(n)
            int low = 0;
            int high = cumulativeWeights.length - 1;

            while (low < high) {
                int mid = (low + high) >>> 1;
                if (random < cumulativeWeights[mid]) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }

            return keys[low];
        }

        public Map<Integer, Double> getOriginalWeights() {
            return originalWeights;
        }
    }

    /**
     * Ultra-fast version for when you need maximum performance and can precompute arrays.
     * This version avoids object creation entirely during picking.
     */
    public static Integer pickWeightedProbabilityFast(Integer[] keys, double[] weights, double totalWeight) {
        double random = ThreadLocalRandom.current().nextDouble(totalWeight);

        for (int i = 0; i < weights.length; i++) {
            random -= weights[i];
            if (random <= 0.0) {
                return keys[i];
            }
        }

        return keys[keys.length - 1];
    }

    /**
     * Version that returns a default value instead of null for safer usage.
     */
    public static Integer pickWeightedProbability(Map<Integer, Double> weighedValues, Integer defaultValue) {
        Integer result = pickWeightedProbability(weighedValues);
        return result != null ? result : defaultValue;
    }
}