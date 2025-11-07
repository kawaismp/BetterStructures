package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.buildingfitter.util.TerrainAdequacy;
import com.magmaguy.betterstructures.buildingfitter.util.Topology;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

public class FitSurfaceBuilding extends FitAnything {

    private static final Vector[] SEARCH_PATTERN = {
            new Vector(0, 0, 0),    // Center
            new Vector(-16, 0, 0),  // West
            new Vector(16, 0, 0),   // East
            new Vector(0, 0, -16),  // North
            new Vector(0, 0, 16),   // South
            new Vector(-32, 0, 0),  // Far West
            new Vector(32, 0, 0),   // Far East
            new Vector(0, 0, -32),  // Far North
            new Vector(0, 0, 32)    // Far South
    };

    public FitSurfaceBuilding(Chunk chunk, SchematicContainer schematicContainer) {
        super(schematicContainer);
        this.structureType = GeneratorConfigFields.StructureType.SURFACE;
        this.schematicContainer = schematicContainer;
        this.schematicClipboard = schematicContainer.getClipboard();
        scan(chunk);
    }

    public FitSurfaceBuilding(Chunk chunk) {
        super();
        this.structureType = GeneratorConfigFields.StructureType.SURFACE;
        scan(chunk);
    }

    private void scan(Chunk chunk) {
        World world = chunk.getWorld();
        Location baseLocation = getChunkCenterLocation(chunk, world);

        randomizeSchematicContainer(baseLocation, GeneratorConfigFields.StructureType.SURFACE);
        if (schematicClipboard == null) return;

        schematicOffset = WorldEditUtils.getSchematicOffset(schematicClipboard);
        findBestFit(baseLocation);

        if (location != null) {
            paste(location);
        }
    }

    private static Location getChunkCenterLocation(Chunk chunk, World world) {
        double x = (chunk.getX() << 4) + 8.0; // bit-shift instead of multiply for speed
        double z = (chunk.getZ() << 4) + 8.0;
        int y = world.getHighestBlockYAt((int) x, (int) z);
        return new Location(world, x, y, z);
    }

    private void findBestFit(Location origin) {
        double bestScore = evaluateLocation(origin);
        if (bestScore > highestScore) {
            highestScore = bestScore;
            location = origin;
        }

        // Stop early if the first (center) location is already very good
        if (highestScore >= 50) return;

        // Try other offsets in order
        for (Vector offset : SEARCH_PATTERN) {
            if (offset.getX() == 0 && offset.getZ() == 0) continue;

            Location loc = origin.clone().add(offset);
            double score = evaluateLocation(loc);

            if (score > highestScore) {
                highestScore = score;
                location = loc;

                if (highestScore >= 50) break; // good enough
            }
        }
    }

    private double evaluateLocation(Location loc) {
        World world = loc.getWorld();
        double start = (world.getEnvironment() == World.Environment.NETHER) ? 200 : this.startingScore;

        double topology = Topology.scan(start, scanStep, schematicClipboard, loc, schematicOffset);
        if (topology <= 0) return 0;

        double adequacy = TerrainAdequacy.scan(scanStep, schematicClipboard, loc, schematicOffset, TerrainAdequacy.ScanType.SURFACE);
        return topology + (0.5 * adequacy);
    }
}
