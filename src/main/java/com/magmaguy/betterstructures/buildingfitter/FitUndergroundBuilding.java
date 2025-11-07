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

import java.util.concurrent.ThreadLocalRandom;

public class FitUndergroundBuilding extends FitAnything {

    private final int lowestY;
    private final int highestY;

    // Reused mutable objects
    private final Location workLoc = new Location(null, 0, 0, 0);

    public FitUndergroundBuilding(Chunk chunk, SchematicContainer schematicContainer, int lowestY, int highestY, GeneratorConfigFields.StructureType type) {
        super(schematicContainer);
        this.structureType = type;
        this.lowestY = lowestY;
        this.highestY = highestY;
        this.schematicContainer = schematicContainer;
        this.schematicClipboard = schematicContainer.getClipboard();
        scan(chunk);
    }

    public FitUndergroundBuilding(Chunk chunk, int lowestY, int highestY, GeneratorConfigFields.StructureType type) {
        super();
        this.structureType = type;
        this.lowestY = lowestY;
        this.highestY = highestY;
        scan(chunk);
    }

    private void scan(Chunk chunk) {
        World world = chunk.getWorld();
        Location base = getChunkCenter(chunk, world);

        if (!determineY(base, world)) return;

        randomizeSchematicContainer(base, structureType);
        if (schematicClipboard == null) return;

        schematicOffset = WorldEditUtils.getSchematicOffset(schematicClipboard);
        fixWorldBounds(base, world);
        searchOptimalPlacement(base, world);

        if (location != null) paste(location);
    }

    private static Location getChunkCenter(Chunk chunk, World world) {
        double x = (chunk.getX() << 4) + 8.0;
        double z = (chunk.getZ() << 4) + 8.0;
        return new Location(world, x, 0, z);
    }

    /**
     * Determines initial Y placement based on world type.
     */
    private boolean determineY(Location base, World world) {
        return switch (world.getEnvironment()) {
            case NORMAL, CUSTOM -> {
                base.setY(ThreadLocalRandom.current().nextInt(lowestY, highestY));
                yield true;
            }
            case NETHER -> findNetherY(base);
            case THE_END -> structureType != GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW || findEndY(base);
        };
    }

    private boolean findNetherY(Location base) {
        final boolean shallow = structureType == GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW;
        final int dir = shallow ? 1 : -1;
        int y = shallow ? lowestY : highestY;

        int low = 0, high = 0, tolerance = 3;
        boolean inSolid = false;

        World world = base.getWorld();
        workLoc.setWorld(world);
        workLoc.setX(base.getX());
        workLoc.setZ(base.getZ());

        while (shallow ? y < highestY : y > lowestY) {
            workLoc.setY(y);
            Material mat = workLoc.getBlock().getType();

            if (mat.isSolid()) {
                if (!inSolid) {
                    inSolid = true;
                    if (shallow) low = y; else high = y;
                } else {
                    if (shallow) high = y; else low = y;
                }
            } else if (inSolid) {
                if (mat == Material.VOID_AIR || mat == Material.BEDROCK || tolerance == 0) {
                    inSolid = false;
                    if (Math.abs(high - low) >= 20) break;
                    if (mat == Material.VOID_AIR || mat == Material.BEDROCK) return false;
                    tolerance = 3;
                } else {
                    tolerance--;
                    if (shallow) high = y; else low = y;
                }
            }
            y += dir;
        }

        int diff = Math.abs(high - low);
        if (diff < 20) return false;

        if (diff > 30) {
            int min = Math.min(low, high) + 1;
            int max = Math.max(low, high) - 20;
            base.setY(ThreadLocalRandom.current().nextInt(min, max));
        } else {
            base.setY(Math.min(low, high) + 1.0);
        }
        return true;
    }

    private boolean findEndY(Location base) {
        int low = 0, high = 0, tolerance = 3;
        boolean inSolid = false;

        World world = base.getWorld();
        workLoc.setWorld(world);
        workLoc.setX(base.getX());
        workLoc.setZ(base.getZ());

        for (int y = lowestY; y < highestY; y++) {
            workLoc.setY(y);
            Material mat = workLoc.getBlock().getType();

            if (mat.isSolid()) {
                if (!inSolid) {
                    inSolid = true;
                    low = y;
                }
                high = y;
            } else if (inSolid) {
                if (mat == Material.VOID_AIR || mat == Material.BEDROCK || tolerance == 0) {
                    inSolid = false;
                    if (high - low >= 20) break;
                    if (mat == Material.VOID_AIR || mat == Material.BEDROCK) return false;
                    tolerance = 3;
                } else {
                    tolerance--;
                    high = y;
                }
            }
        }

        int diff = high - low;
        if (diff < 20) return false;

        base.setY(diff > 30
                ? ThreadLocalRandom.current().nextInt(low + 1, high - 20)
                : low + 1.0);
        return true;
    }

    private void fixWorldBounds(Location base, World world) {
        double y = base.getY();
        double height = schematicClipboard.getRegion().getHeight();
        double offsetY = Math.abs(schematicOffset.getY());

        double minY, maxY;
        switch (world.getEnvironment()) {
            case NORMAL:
            case CUSTOM:
                minY = DefaultConfig.getLowestYNormalCustom();
                maxY = DefaultConfig.getHighestYNormalCustom();
                break;
            case NETHER:
                minY = DefaultConfig.getLowestYNether();
                maxY = DefaultConfig.getHighestYNether();
                break;
            case THE_END:
                minY = DefaultConfig.getLowestYEnd();
                maxY = DefaultConfig.getHighestYEnd();
                break;
            default:
                return;
        }

        if (y - offsetY < minY) {
            base.setY(minY + 1 + offsetY);
        } else if (y + offsetY - height > maxY) {
            base.setY(maxY - height + offsetY);
        }
    }

    private void searchOptimalPlacement(Location base, World world) {
        chunkScan(base, 0, 0);
        if (highestScore >= 90) return;

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                if (x == 0 && z == 0) continue;
                chunkScan(base, x, z);
                if (highestScore >= 90) return;
            }
        }
    }

    private void chunkScan(Location base, int cx, int cz) {
        World world = base.getWorld();
        workLoc.setWorld(world);
        workLoc.setX(base.getX() + (cx << 4));
        workLoc.setY(base.getY());
        workLoc.setZ(base.getZ() + (cz << 4));

        double score = TerrainAdequacy.scan(scanStep, schematicClipboard, workLoc, schematicOffset, TerrainAdequacy.ScanType.UNDERGROUND);
        double minScore = (world.getEnvironment() == World.Environment.NETHER) ? 50 : 70;

        if (score < minScore) return;

        if (score > highestScore) {
            highestScore = score;
            location = workLoc.clone(); // only clone when storing
        }
    }
}
