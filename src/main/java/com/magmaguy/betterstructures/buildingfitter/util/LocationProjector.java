package com.magmaguy.betterstructures.buildingfitter.util;

import org.bukkit.Location;
import org.bukkit.util.Vector;

public class LocationProjector {

    /**
     * Projects a location to where it will be after paste, accounting for schematic anchor point.
     *
     * @param worldAnchorPoint The real paste location
     * @param schematicOffset  The schematic's offset from its lowest point
     * @return Projected location
     */
    public static Location project(Location worldAnchorPoint, Vector schematicOffset) {
        return worldAnchorPoint.clone().add(schematicOffset);
    }

    /**
     * Projects a location to where it will be after paste, accounting for schematic point and arbitrary offsets.
     * Intended for use with iterators where points are offset by a distance.
     *
     * @param worldAnchorPoint      The real paste location
     * @param relativeBlockLocation Arbitrary distance the point is from the anchor point
     * @param schematicOffset       The schematic's offset from its lowest point
     * @return Projected location
     */
    public static Location project(Location worldAnchorPoint, Vector relativeBlockLocation, Vector schematicOffset) {
        // Single operation: worldAnchor + schematicOffset + relativeBlockLocation
        return worldAnchorPoint.clone().add(schematicOffset).add(relativeBlockLocation);
    }

    /**
     * Optimized version that reuses an existing Location object to reduce object creation
     *
     * @param result Location to store the result (will be modified)
     * @param worldAnchorPoint The real paste location
     * @param relativeBlockLocation Arbitrary distance from anchor point
     * @param schematicOffset The schematic's offset from its lowest point
     */
    public static void project(Location result, Location worldAnchorPoint, Vector relativeBlockLocation, Vector schematicOffset) {
        result.setWorld(worldAnchorPoint.getWorld());
        result.setX(worldAnchorPoint.getX() + schematicOffset.getX() + relativeBlockLocation.getX());
        result.setY(worldAnchorPoint.getY() + schematicOffset.getY() + relativeBlockLocation.getY());
        result.setZ(worldAnchorPoint.getZ() + schematicOffset.getZ() + relativeBlockLocation.getZ());
    }
}