package com.magmaguy.betterstructures.buildingfitter.util;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class Topology {

    private static final int MAX_HEIGHT_DIFF = 20;
    private static final int NETHER_SCAN_MIN_Y = 30;
    private static final int NETHER_SCAN_MAX_Y = 100;

    public static double scan(double startingScore, int scanStep, Clipboard clipboard, Location origin, Vector offset) {
        int width = clipboard.getDimensions().x();
        int depth = clipboard.getDimensions().z();

        // Preallocate exact expected capacity to prevent resizes
        int estimatedSize = ((width / scanStep) + 1) * ((depth / scanStep) + 1);
        List<Integer> heights = new ArrayList<>(estimatedSize);

        double score = scanHighestLocations(width, depth, scanStep, origin, offset, heights, startingScore);
        if (score <= 75) return score;

        if (hasExtremeHeightDifferences(heights)) return 0;

        int avgY = computeAverageHeight(heights, origin);
        return applyHeightVariationPenalty(heights, avgY, score);
    }

    private static double scanHighestLocations(int width, int depth, int step, Location origin, Vector offset, List<Integer> heights, double score) {
        World world = origin.getWorld();
        int totalPoints = (width / step) * (depth / step);
        double penaltyPerPoint = 50.0 / totalPoints;

        // Reusable objects
        Location base = origin.clone();
        Location loc = new Location(world, 0, 0, 0);
        Vector delta = new Vector();

        for (int x = 0; x < width; x += step) {
            for (int z = 0; z < depth; z += step) {
                delta.setX(x);
                delta.setY(0);
                delta.setZ(z);

                LocationProjector.project(loc, base, delta, offset);
                int y = getHighestBlockYAt(world, loc);

                if (y == Integer.MIN_VALUE) return 0; // Invalid
                Material type = world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ()).getType();

                // Water/lava penalty
                if (type == Material.WATER || type == Material.LAVA) {
                    score -= penaltyPerPoint;
                    if (score < 75) return score;
                }

                heights.add(y);
            }
        }
        return score;
    }

    /**
     * Optimized: directly returns Y value (avoids new Location allocations)
     */
    private static int getHighestBlockYAt(World world, Location loc) {
        if (world.getEnvironment() != World.Environment.NETHER) {
            return world.getHighestBlockYAt(loc);
        }
        return getHighestBlockYAtNether(world, loc.getBlockX(), loc.getBlockZ());
    }

    /**
     * Nether-specific terrain probing, returns surface Y or Integer.MIN_VALUE if not found.
     */
    private static int getHighestBlockYAtNether(World world, int x, int z) {
        // Middle starting point
        int y = 63;
        Material initial = world.getBlockAt(x, y, z).getType();

        if (SurfaceMaterials.ignorable(initial)) {
            for (y = 63; y > NETHER_SCAN_MIN_Y; y--) {
                if (isValidNetherSurface(world, x, y, z)) return y;
            }
        } else {
            for (y = 63; y < NETHER_SCAN_MAX_Y; y++) {
                if (isValidNetherSurface(world, x, y, z)) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isValidNetherSurface(World world, int x, int y, int z) {
        Material current = world.getBlockAt(x, y, z).getType();
        Material above = world.getBlockAt(x, y + 1, z).getType();

        // must be solid ground with air/ignorable above
        if (SurfaceMaterials.ignorable(current) || !SurfaceMaterials.ignorable(above)) return false;

        // ensure at least 10 blocks of air above
        for (int i = y + 2; i < y + 12 && i <= 255; i++) {
            if (!SurfaceMaterials.ignorable(world.getBlockAt(x, i, z).getType())) return false;
        }
        return true;
    }

    private static boolean hasExtremeHeightDifferences(List<Integer> heights) {
        if (heights.size() < 2) return false;

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int h : heights) {
            if (h < min) min = h;
            if (h > max) max = h;
        }
        return (max - min) >= MAX_HEIGHT_DIFF;
    }

    private static int computeAverageHeight(List<Integer> heights, Location origin) {
        long sum = 0;
        for (int h : heights) sum += h;
        int avg = (int) (sum / heights.size());
        origin.setY(avg + 1.0);
        return avg;
    }

    private static double applyHeightVariationPenalty(List<Integer> heights, int avgY, double score) {
        final int n = heights.size();
        if (n == 0) return score;

        final double maxImpact = score * 0.5 / n; // 50% max penalty distributed evenly

        for (int y : heights) {
            int diff = Math.abs(y - avgY);
            if (diff < 3) continue;

            // Exponential penalty (clamped)
            double penalty = (1 - diff * diff * 0.04) * maxImpact;
            score -= penalty;
            if (score < 85) return 0;
        }
        return score;
    }
}
