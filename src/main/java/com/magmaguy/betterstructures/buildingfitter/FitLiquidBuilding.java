package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.buildingfitter.util.TerrainAdequacy;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

public class FitLiquidBuilding extends FitAnything {

    // Reusable objects to avoid allocation
    private static final Vector CHUNK_CENTER_OFFSET = new Vector(8, 0, 8);
    private static final int NETHER_LAVA_OCEAN_HEIGHT = 31;
    private final Location reusableLocation = new Location(null, 0, 0, 0);

    // For commands
    public FitLiquidBuilding(Chunk chunk, SchematicContainer schematicContainer) {
        super(schematicContainer);
        super.structureType = GeneratorConfigFields.StructureType.LIQUID_SURFACE;
        this.schematicContainer = schematicContainer;
        this.schematicClipboard = schematicContainer.getClipboard();
        scan(chunk);
    }

    public FitLiquidBuilding(Chunk chunk) {
        super();
        super.structureType = GeneratorConfigFields.StructureType.LIQUID_SURFACE;
        scan(chunk);
    }

    private void scan(Chunk chunk) {
        World world = chunk.getWorld();

        // Calculate base location with minimal object creation
        Location originalLocation = calculateBaseLocation(chunk, world);

        // Check if location is valid for liquid structure
        if (!isValidLiquidLocation(originalLocation, world)) {
            return;
        }

        randomizeSchematicContainer(originalLocation, GeneratorConfigFields.StructureType.LIQUID_SURFACE);
        if (schematicClipboard == null) {
            return;
        }

        schematicOffset = WorldEditUtils.getSchematicOffset(schematicClipboard);

        // Search for optimal placement
        searchOptimalPlacement(originalLocation);

        if (location == null) {
            return;
        }

        super.paste(location);
    }

    private Location calculateBaseLocation(Chunk chunk, World world) {
        double x = chunk.getX() * 16.0 + 8.0;
        double z = chunk.getZ() * 16.0 + 8.0;
        Location location = new Location(world, x, 0, z);
        location.setY(world.getHighestBlockYAt(location));
        return location;
    }

    private boolean isValidLiquidLocation(Location location, World world) {
        World.Environment environment = world.getEnvironment();

        switch (environment) {
            case CUSTOM:
            case NORMAL:
                return location.getBlock().isLiquid();

            case NETHER:
                location.setY(NETHER_LAVA_OCEAN_HEIGHT);
                if (location.getBlock().getType() != Material.LAVA) {
                    return false;
                }
                // Check that there's air above for placement
                return hasClearAirAbove(location);

            default:
                return false;
        }
    }

    private boolean hasClearAirAbove(Location location) {
        int startX = location.getBlockX();
        int startZ = location.getBlockZ();
        World world = location.getWorld();
        int startY = location.getBlockY() + 1;

        for (int y = startY; y < startY + 20; y++) {
            if (y > world.getMaxHeight()) break;
            Material blockType = world.getBlockAt(startX, y, startZ).getType();
            if (!blockType.isAir()) {
                return false;
            }
        }
        return true;
    }

    private void searchOptimalPlacement(Location originalLocation) {
        // First try center chunk
        if (chunkScan(originalLocation, 0, 0) && highestScore >= 90) {
            return;
        }

        // Then search surrounding chunks in expanding pattern
        for (int radius = 1; radius <= searchRadius; radius++) {
            for (int chunkX = -radius; chunkX <= radius; chunkX++) {
                for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                    // Only check the outer ring of this radius
                    if (Math.abs(chunkX) != radius && Math.abs(chunkZ) != radius) {
                        continue;
                    }

                    if (chunkScan(originalLocation, chunkX, chunkZ) && highestScore >= 90) {
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
        reusableLocation.setY(originalLocation.getY() + 1); // Add 1 as in original code
        reusableLocation.setZ(originalLocation.getZ() + offsetZ);

        double newScore = TerrainAdequacy.scan(scanStep, schematicClipboard, reusableLocation, schematicOffset, TerrainAdequacy.ScanType.LIQUID);

        if (newScore < 90) {
            return false;
        }

        if (newScore > highestScore) {
            highestScore = newScore;
            location = reusableLocation.clone(); // Clone for storage
        }

        return true;
    }
}