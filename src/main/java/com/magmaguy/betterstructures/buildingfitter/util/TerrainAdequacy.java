package com.magmaguy.betterstructures.buildingfitter.util;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

public class TerrainAdequacy {
    public enum ScanType {
        SURFACE,
        UNDERGROUND,
        AIR,
        LIQUID
    }

    public static double scan(int scanStep, Clipboard schematicClipboard, Location iteratedLocation, Vector schematicOffset, ScanType scanType) {
        BlockVector3 dimensions = schematicClipboard.getDimensions();
        int width = dimensions.x();
        int depth = dimensions.z();
        int height = dimensions.y();

        int floorY = iteratedLocation.getBlockY() - 1;

        int totalCount = 0;
        int negativeCount = 0;

        // Reusable location object to avoid excessive object creation
        Location projectedLocation = iteratedLocation.clone();

        for (int x = 0; x < width; x += scanStep) {
            for (int y = 0; y < height; y += scanStep) {
                for (int z = 0; z < depth; z += scanStep) {
                    Material schematicMaterial = BukkitAdapter.adapt(schematicClipboard.getBlock(BlockVector3.at(x, y, z)).getBlockType());
                    LocationProjector.project(projectedLocation, iteratedLocation, new Vector(x, y, z), schematicOffset);
                    if (!isBlockAdequate(projectedLocation, schematicMaterial, floorY, scanType)) {
                        negativeCount++;
                    }
                    totalCount++;
                }
            }
        }

        return 100.0 - (negativeCount * 100.0) / totalCount;
    }

    // Alternative version with direct coordinate calculation for maximum performance
    public static double scanOptimized(int scanStep, Clipboard schematicClipboard, Location iteratedLocation, Vector schematicOffset, ScanType scanType) {
        BlockVector3 dimensions = schematicClipboard.getDimensions();
        int width = dimensions.x();
        int depth = dimensions.z();
        int height = dimensions.y();

        int floorY = iteratedLocation.getBlockY() - 1;
        double anchorX = iteratedLocation.getX();
        double anchorY = iteratedLocation.getY();
        double anchorZ = iteratedLocation.getZ();
        double offsetX = schematicOffset.getX();
        double offsetY = schematicOffset.getY();
        double offsetZ = schematicOffset.getZ();

        int totalCount = 0;
        int negativeCount = 0;

        // Single reusable location object
        Location projectedLocation = new Location(iteratedLocation.getWorld(), 0, 0, 0);

        for (int x = 0; x < width; x += scanStep) {
            for (int y = 0; y < height; y += scanStep) {
                for (int z = 0; z < depth; z += scanStep) {
                    Material schematicMaterial = BukkitAdapter.adapt(
                            schematicClipboard.getBlock(BlockVector3.at(x, y, z)).getBlockType());

                    // Direct coordinate calculation - fastest option
                    projectedLocation.setX(anchorX + offsetX + x);
                    projectedLocation.setY(anchorY + offsetY + y);
                    projectedLocation.setZ(anchorZ + offsetZ + z);

                    if (!isBlockAdequate(projectedLocation, schematicMaterial, floorY, scanType)) {
                        negativeCount++;
                    }
                    totalCount++;
                }
            }
        }

        return 100.0 - (negativeCount * 100.0) / totalCount;
    }

    private static boolean isBlockAdequate(Location projectedWorldLocation, Material schematicBlockMaterial, int floorHeight, ScanType scanType) {
        Block worldBlock = projectedWorldLocation.getBlock();
        Material worldMaterial = worldBlock.getType();
        int blockY = projectedWorldLocation.getBlockY();

        // Early return for void air
        if (worldMaterial == Material.VOID_AIR) {
            return false;
        }

        switch (scanType) {
            case SURFACE:
                if (blockY > floorHeight) {
                    // For air level - check if world block is ignorable OR schematic block is not air
                    return SurfaceMaterials.ignorable(worldMaterial) || !schematicBlockMaterial.isAir();
                } else {
                    // For underground level - world block should not be air
                    return !worldMaterial.isAir();
                }
            case AIR:
                return worldMaterial.isAir();
            case UNDERGROUND:
                return worldMaterial.isSolid();
            case LIQUID:
                if (blockY > floorHeight) {
                    // For air level
                    return worldMaterial.isAir();
                } else {
                    // For underwater level - only check liquid if schematic expects liquid
                    if (schematicBlockMaterial == Material.WATER || schematicBlockMaterial == Material.LAVA) {
                        return worldBlock.isLiquid();
                    }
                    return true;
                }
            default:
                return false;
        }
    }
}