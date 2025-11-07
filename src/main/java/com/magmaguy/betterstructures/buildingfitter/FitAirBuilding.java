package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.buildingfitter.util.TerrainAdequacy;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

public class FitAirBuilding extends FitAnything {

    // Reusable objects to avoid allocation
    private final Location reusableLocation = new Location(null, 0, 0, 0);
    private static final int NETHER_LOWEST_Y = 45;
    private static final int NETHER_HIGHEST_Y = 100;

    public FitAirBuilding(Chunk chunk, SchematicContainer schematicContainer) {
        super(schematicContainer);
        super.structureType = GeneratorConfigFields.StructureType.SKY;
        this.schematicContainer = schematicContainer;
        this.schematicClipboard = schematicContainer.getClipboard();
        scan(chunk);
    }

    public FitAirBuilding(Chunk chunk) {
        super();
        super.structureType = GeneratorConfigFields.StructureType.SKY;
        scan(chunk);
    }

    private void scan(Chunk chunk) {
        World world = chunk.getWorld();

        // Calculate base location with altitude
        Location originalLocation = calculateBaseLocationWithAltitude(chunk, world);
        if (originalLocation == null) {
            return;
        }

        // Handle environment-specific logic
        if (!processEnvironmentSpecifics(originalLocation, world)) {
            return;
        }

        randomizeSchematicContainer(originalLocation, GeneratorConfigFields.StructureType.SKY);
        if (schematicClipboard == null) {
            return;
        }

        schematicOffset = WorldEditUtils.getSchematicOffset(schematicClipboard);

        // Search for optimal placement
        searchOptimalPlacement(originalLocation);

        if (location == null) {
            return;
        }

        paste(location);
    }

    private Location calculateBaseLocationWithAltitude(Chunk chunk, World world) {
        int centerX = chunk.getX() * 16 + 8;
        int centerZ = chunk.getZ() * 16 + 8;

        // Get highest block location without creating temporary objects
        World.Environment environment = world.getEnvironment();
        int altitude;

        switch (environment) {
            case NORMAL:
            case CUSTOM:
                altitude = ThreadLocalRandom.current().nextInt(
                        DefaultConfig.getNormalCustomAirBuildingMinAltitude(),
                        DefaultConfig.getNormalCustomAirBuildingMaxAltitude() + 1
                );
                break;
            case NETHER:
                altitude = 0; // Will be set later in processEnvironmentSpecifics
                break;
            case THE_END:
                int endMinAlt = DefaultConfig.getEndAirBuildMinAltitude();
                altitude = ThreadLocalRandom.current().nextInt(endMinAlt, endMinAlt + 1);
                break;
            default:
                altitude = 0;
        }

        int highestY = world.getHighestBlockYAt(centerX, centerZ);
        return new Location(world, centerX, highestY + altitude, centerZ);
    }

    private boolean processEnvironmentSpecifics(Location originalLocation, World world) {
        World.Environment environment = world.getEnvironment();

        switch (environment) {
            case CUSTOM:
            case NORMAL:
            case THE_END:
                // No special handling needed for these environments
                return true;

            case NETHER:
                return processNetherEnvironment(originalLocation);

            default:
                return false;
        }
    }

    private boolean processNetherEnvironment(Location originalLocation) {
        int lowPoint = 0;
        int highPoint = 0;
        boolean streak = false;
        int tolerance = 3;

        reusableLocation.setWorld(originalLocation.getWorld());
        reusableLocation.setX(originalLocation.getX());
        reusableLocation.setZ(originalLocation.getZ());

        for (int y = NETHER_LOWEST_Y; y < NETHER_HIGHEST_Y; y++) {
            reusableLocation.setY(y);
            Material blockType = reusableLocation.getBlock().getType();

            if (blockType.isAir()) {
                if (streak) {
                    highPoint = y;
                } else {
                    lowPoint = y;
                    streak = true;
                }
            } else {
                if (blockType == Material.VOID_AIR || blockType == Material.BEDROCK || tolerance == 0) {
                    if (streak) {
                        streak = false;
                        if (highPoint - lowPoint >= 40) break;
                        if (blockType == Material.VOID_AIR || blockType == Material.BEDROCK) return false;
                        tolerance = 3;
                    }
                } else if (streak) {
                    tolerance--;
                    highPoint = y;
                }
            }
        }

        if (highPoint - lowPoint < 20) {
            return false;
        }

        if (highPoint - lowPoint > 30) {
            originalLocation.setY(ThreadLocalRandom.current().nextInt(lowPoint + 1, highPoint - 20));
        } else {
            originalLocation.setY(lowPoint + 1.0);
        }

        return true;
    }

    private void searchOptimalPlacement(Location originalLocation) {
        // First try center chunk
        if (chunkScan(originalLocation, 0, 0)) {
            return;
        }

        // Search in expanding ring pattern
        for (int radius = 1; radius <= searchRadius; radius++) {
            for (int chunkX = -radius; chunkX <= radius; chunkX++) {
                for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                    // Only check the outer ring of this radius
                    if (Math.abs(chunkX) != radius && Math.abs(chunkZ) != radius) {
                        continue;
                    }

                    if (chunkScan(originalLocation, chunkX, chunkZ)) {
                        return;
                    }
                }
            }
        }
    }

    private boolean chunkScan(Location originalLocation, int chunkX, int chunkZ) {
        // Calculate offset directly without creating new Vector
        double offsetX = chunkX * 16.0;
        double offsetZ = chunkZ * 16.0;

        reusableLocation.setWorld(originalLocation.getWorld());
        reusableLocation.setX(originalLocation.getX() + offsetX);
        reusableLocation.setY(originalLocation.getY());
        reusableLocation.setZ(originalLocation.getZ() + offsetZ);

        double newScore = TerrainAdequacy.scan(scanStep, schematicClipboard, reusableLocation, schematicOffset, TerrainAdequacy.ScanType.AIR);

        // Original logic: if score equals startingScore, use this location
        if (newScore == startingScore) {
            location = reusableLocation.clone(); // Clone for storage
            return true;
        }

        return false;
    }
}