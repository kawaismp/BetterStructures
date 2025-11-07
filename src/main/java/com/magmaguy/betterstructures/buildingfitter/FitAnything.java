package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.buildingfitter.util.FitUndergroundDeepBuilding;
import com.magmaguy.betterstructures.buildingfitter.util.LocationProjector;
import com.magmaguy.betterstructures.buildingfitter.util.SchematicPicker;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.thirdparty.EliteMobs;
import com.magmaguy.betterstructures.thirdparty.MythicMobs;
import com.magmaguy.betterstructures.thirdparty.WorldGuard;
import com.magmaguy.betterstructures.util.SurfaceMaterials;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.betterstructures.worldedit.Schematic;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import com.magmaguy.magmacore.util.VersionChecker;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public class FitAnything {
    public static boolean worldGuardWarn = false;
    protected final int searchRadius = 1;
    protected final int scanStep = 3;
    private final HashMap<Material, Integer> undergroundPedestalMaterials = new HashMap<>();
    private final HashMap<Material, Integer> surfacePedestalMaterials = new HashMap<>();

    // Reusable objects to avoid allocation
    private final Vector reusableVector = new Vector();
    private final Location reusableLocation = new Location(null, 0, 0, 0);
    private static final int MAX_PEDESTAL_DEPTH = 11;
    private static final int MAX_SURFACE_SCAN_HEIGHT = 20;
    private static final int MAX_TREE_CLEAR_HEIGHT = 31;

    @Getter
    protected SchematicContainer schematicContainer;
    protected double startingScore = 100;
    @Getter
    protected Clipboard schematicClipboard = null;
    @Getter
    protected Vector schematicOffset;
    protected int verticalOffset = 0;
    //At 10% it is assumed a fit is so bad it's better just to skip
    protected double highestScore = 10;
    @Getter
    protected Location location = null;
    protected GeneratorConfigFields.StructureType structureType;
    private Material pedestalMaterial = null;

    public FitAnything(SchematicContainer schematicContainer) {
        this.schematicContainer = schematicContainer;
        this.verticalOffset = schematicContainer.getClipboard().getMinimumPoint().y() - schematicContainer.getClipboard().getOrigin().y();
    }

    public FitAnything() {
    }

    public static void commandBasedCreation(Chunk chunk, GeneratorConfigFields.StructureType structureType, SchematicContainer container) {
        switch (structureType) {
            case SKY:
                new FitAirBuilding(chunk, container);
                break;
            case SURFACE:
                new FitSurfaceBuilding(chunk, container);
                break;
            case LIQUID_SURFACE:
                new FitLiquidBuilding(chunk, container);
                break;
            case UNDERGROUND_DEEP:
                FitUndergroundDeepBuilding.fit(chunk, container);
                break;
            case UNDERGROUND_SHALLOW:
                FitUndergroundShallowBuilding.fit(chunk, container);
                break;
            default:
        }
    }

    protected void randomizeSchematicContainer(Location location, GeneratorConfigFields.StructureType structureType) {
        if (schematicClipboard != null) return;
        schematicContainer = SchematicPicker.pick(location, structureType);
        if (schematicContainer != null) {
            schematicClipboard = schematicContainer.getClipboard();
            verticalOffset = schematicContainer.getClipboard().getMinimumPoint().y() - schematicContainer.getClipboard().getOrigin().y();
        }
    }

    protected void paste(Location location) {
        BuildPlaceEvent buildPlaceEvent = new BuildPlaceEvent(this);
        Bukkit.getServer().getPluginManager().callEvent(buildPlaceEvent);
        if (buildPlaceEvent.isCancelled()) return;

        FitAnything fitAnything = this;

        // Set pedestal material before the paste so bedrock blocks get replaced correctly
        assignPedestalMaterial(location);
        if (pedestalMaterial == null)
            switch (location.getWorld().getEnvironment()) {
                case NETHER:
                    pedestalMaterial = Material.NETHERRACK;
                    break;
                case THE_END:
                    pedestalMaterial = Material.END_STONE;
                    break;
                default:
                    pedestalMaterial = Material.STONE;
            }

        // Create a function to provide pedestal material
        Function<Boolean, Material> pedestalMaterialProvider = this::getPedestalMaterial;

        // Paste the schematic with the moved logic
        Schematic.pasteSchematic(
                schematicClipboard,
                location,
                schematicOffset,
                pedestalMaterialProvider,
                onPasteComplete(fitAnything, location)
        );
    }

    private BukkitRunnable onPasteComplete(FitAnything fitAnything, Location location) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (DefaultConfig.isNewBuildingWarn()) {
                    String structureTypeString = fitAnything.structureType.toString().toLowerCase(Locale.ROOT).replace("_", " ");
                    for (Player player : Bukkit.getOnlinePlayers())
                        if (player.hasPermission("betterstructures.warn"))
                            player.spigot().sendMessage(
                                    SpigotMessage.commandHoverMessage("[BetterStructures] New " + structureTypeString + " building generated! Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                                            "Click to teleport to " + location.getWorld().getName() + ", " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "\n Schem name: " + schematicContainer.getConfigFilename(),
                                            "/betterstructures teleport " + location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ())
                            );
                }

                if (!(fitAnything instanceof FitAirBuilding)) {
                    try {
                        addPedestal(location);
                    } catch (Exception exception) {
                        Logger.warn("Failed to correctly assign pedestal material!");
                        exception.printStackTrace();
                    }
                    try {
                        if (fitAnything instanceof FitSurfaceBuilding)
                            clearTrees(location);
                    } catch (Exception exception) {
                        Logger.warn("Failed to correctly clear trees!");
                        exception.printStackTrace();
                    }
                }
                try {
                    fillChests();
                } catch (Exception exception) {
                    Logger.warn("Failed to correctly fill chests!");
                    exception.printStackTrace();
                }
                try {
                    spawnEntities();
                } catch (Exception exception) {
                    Logger.warn("Failed to correctly spawn entities!");
                    exception.printStackTrace();
                }
                try{
                    spawnProps(fitAnything.schematicClipboard);
                } catch (Exception exception) {
                    Logger.warn("Failed to correctly spawn props!");
                    exception.printStackTrace();
                }
            }
        };
    }

    private void spawnProps(Clipboard clipboard) {
        // Don't add schematicOffset here - let pasteArmorStandsOnlyFromTransformed handle the alignment
        WorldEditUtils.pasteArmorStandsOnlyFromTransformed(clipboard, location.clone().add(schematicOffset));
    }

    private void assignPedestalMaterial(Location location) {
        if (this instanceof FitAirBuilding) return;
        pedestalMaterial = schematicContainer.getSchematicConfigField().getPedestalMaterial();
        Location lowestCorner = location.clone().add(schematicOffset);

        int width = schematicClipboard.getDimensions().x();
        int length = schematicClipboard.getDimensions().z();
        int height = schematicClipboard.getDimensions().y();

        // Pre-calculate world coordinates for the lowest corner
        double baseX = lowestCorner.getX();
        double baseY = lowestCorner.getY();
        double baseZ = lowestCorner.getZ();
        World world = lowestCorner.getWorld();

        //get underground pedestal blocks
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                for (int y = 0; y < height; y++) {
                    reusableLocation.setWorld(world);
                    reusableLocation.setX(baseX + x);
                    reusableLocation.setY(baseY + y);
                    reusableLocation.setZ(baseZ + z);

                    Block groundBlock = reusableLocation.getBlock();
                    Block aboveBlock = world.getBlockAt(
                            reusableLocation.getBlockX(),
                            reusableLocation.getBlockY() + 1,
                            reusableLocation.getBlockZ()
                    );

                    if (aboveBlock.getType().isSolid() && groundBlock.getType().isSolid() && !SurfaceMaterials.ignorable(groundBlock.getType()))
                        undergroundPedestalMaterials.merge(groundBlock.getType(), 1, Integer::sum);
                }
            }
        }

        //get above ground pedestal blocks, if any
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                reusableLocation.setWorld(world);
                reusableLocation.setX(baseX + x);
                reusableLocation.setY(baseY + height);
                reusableLocation.setZ(baseZ + z);

                boolean scanUp = reusableLocation.getBlock().getType().isSolid();
                for (int y = 0; y < MAX_SURFACE_SCAN_HEIGHT; y++) {
                    int currentY = scanUp ? y : -y;
                    reusableLocation.setY(baseY + currentY);

                    Block groundBlock = reusableLocation.getBlock();
                    Block aboveBlock = world.getBlockAt(
                            reusableLocation.getBlockX(),
                            reusableLocation.getBlockY() + 1,
                            reusableLocation.getBlockZ()
                    );

                    if (!aboveBlock.getType().isSolid() && groundBlock.getType().isSolid()) {
                        surfacePedestalMaterials.merge(groundBlock.getType(), 1, Integer::sum);
                        break;
                    }
                }
            }
        }
    }

    private Material getPedestalMaterial(boolean isPedestalSurface) {
        if (isPedestalSurface) {
            if (surfacePedestalMaterials.isEmpty()) return pedestalMaterial;
            return getRandomMaterialBasedOnWeight(surfacePedestalMaterials);
        } else {
            if (undergroundPedestalMaterials.isEmpty()) return pedestalMaterial;
            return getRandomMaterialBasedOnWeight(undergroundPedestalMaterials);
        }
    }

    public Material getRandomMaterialBasedOnWeight(HashMap<Material, Integer> weightedMaterials) {
        // Calculate the total weight
        int totalWeight = 0;
        for (int weight : weightedMaterials.values()) {
            totalWeight += weight;
        }

        // Generate a random number in the range of 0 (inclusive) to totalWeight (exclusive)
        int randomNumber = ThreadLocalRandom.current().nextInt(totalWeight);

        // Iterate through the materials and pick one based on the random number
        int cumulativeWeight = 0;
        for (Map.Entry<Material, Integer> entry : weightedMaterials.entrySet()) {
            cumulativeWeight += entry.getValue();
            if (randomNumber < cumulativeWeight) {
                return entry.getKey();
            }
        }

        // Fallback return, should not occur if the map is not empty and weights are positive
        throw new IllegalStateException("Weighted random selection failed.");
    }

    private void addPedestal(Location location) {
        if (this instanceof FitAirBuilding || this instanceof FitLiquidBuilding) return;
        Location lowestCorner = location.clone().add(schematicOffset);

        int width = schematicClipboard.getDimensions().x();
        int length = schematicClipboard.getDimensions().z();

        double baseX = lowestCorner.getX();
        double baseY = lowestCorner.getY();
        double baseZ = lowestCorner.getZ();
        World world = lowestCorner.getWorld();

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                //Only add pedestals for areas with a solid floor, some schematics can have rounded air edges to better fit terrain
                reusableLocation.setWorld(world);
                reusableLocation.setX(baseX + x);
                reusableLocation.setY(baseY);
                reusableLocation.setZ(baseZ + z);

                Block groundBlock = reusableLocation.getBlock();
                if (groundBlock.getType().isAir()) continue;

                for (int y = -1; y > -MAX_PEDESTAL_DEPTH; y--) {
                    reusableLocation.setY(baseY + y);
                    Block block = reusableLocation.getBlock();
                    if (SurfaceMaterials.ignorable(block.getType())) {
                        Block aboveBlock = world.getBlockAt(
                                reusableLocation.getBlockX(),
                                reusableLocation.getBlockY() + 1,
                                reusableLocation.getBlockZ()
                        );
                        block.setType(getPedestalMaterial(!aboveBlock.getType().isSolid()));
                    } else {
                        //Pedestal only fills until it hits the first solid block
                        break;
                    }
                }
            }
        }
    }

    private void clearTrees(Location location) {
        Location highestCorner = location.clone().add(schematicOffset).add(0, schematicClipboard.getDimensions().y() + 1, 0);
        boolean detectedTreeElement = true;

        int width = schematicClipboard.getDimensions().x();
        int length = schematicClipboard.getDimensions().z();

        double baseX = highestCorner.getX();
        double baseY = highestCorner.getY();
        double baseZ = highestCorner.getZ();
        World world = highestCorner.getWorld();

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                for (int y = 0; y < MAX_TREE_CLEAR_HEIGHT; y++) {
                    if (!detectedTreeElement) break;
                    detectedTreeElement = false;

                    reusableLocation.setWorld(world);
                    reusableLocation.setX(baseX + x);
                    reusableLocation.setY(baseY + y);
                    reusableLocation.setZ(baseZ + z);

                    Block block = reusableLocation.getBlock();
                    if (SurfaceMaterials.ignorable(block.getType()) && !block.getType().isAir()) {
                        detectedTreeElement = true;
                        block.setType(Material.AIR);
                    }
                }
            }
        }
    }

    private void fillChests() {
        if (schematicContainer.getGeneratorConfigFields().getChestContents() != null) {
            for (Vector chestPosition : schematicContainer.getChestLocations()) {
                // Use optimized projection
                LocationProjector.project(reusableLocation, location, schematicOffset, chestPosition);

                if (!(reusableLocation.getBlock().getState() instanceof Container container)) {
                    Logger.warn("Expected a container for " + reusableLocation.getBlock().getType() + " but didn't get it. Skipping this loot!");
                    continue;
                }

                if (schematicContainer.getChestContents() != null)
                    schematicContainer.getChestContents().rollChestContents(container);
                else
                    schematicContainer.getGeneratorConfigFields().getChestContents().rollChestContents(container);

                ChestFillEvent chestFillEvent = new ChestFillEvent(container);
                Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
                if (!chestFillEvent.isCancelled())
                    container.update(true);
            }
        }
    }

    private void spawnEntities() {
        World world = location.getWorld();

        // Spawn vanilla entities
        for (Map.Entry<Vector, EntityType> entry : schematicContainer.getVanillaSpawns().entrySet()) {
            LocationProjector.project(reusableLocation, location, schematicOffset, entry.getKey());
            reusableLocation.getBlock().setType(Material.AIR);
            // If mobs spawn in corners they might choke on adjacent walls
            reusableLocation.add(0.5, 0, 0.5);
            // I think FAWE is messing with this
            reusableLocation.getChunk().load();

            Entity entity = world.spawnEntity(reusableLocation, entry.getValue());
            entity.setPersistent(true);
            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.setRemoveWhenFarAway(false);
            }

            if (!VersionChecker.serverVersionOlderThan(21, 0) && entity.getType().equals(EntityType.END_CRYSTAL)) {
                EnderCrystal enderCrystal = (EnderCrystal) entity;
                enderCrystal.setShowingBottom(false);
            }
        }

        // Spawn EliteMobs entities
        for (Map.Entry<Vector, String> entry : schematicContainer.getEliteMobsSpawns().entrySet()) {
            LocationProjector.project(reusableLocation, location, schematicOffset, entry.getKey());
            reusableLocation.getBlock().setType(Material.AIR);
            reusableLocation.add(0.5, 0, 0.5);

            String bossFilename = entry.getValue();
            // If the spawn fails then don't continue
            if (!EliteMobs.Spawn(reusableLocation, bossFilename)) return;

            Location lowestCorner = location.clone().add(schematicOffset);
            Location highestCorner = lowestCorner.clone().add(
                    schematicClipboard.getRegion().getWidth() - 1,
                    schematicClipboard.getRegion().getHeight(),
                    schematicClipboard.getRegion().getLength() - 1
            );

            if (DefaultConfig.isProtectEliteMobsRegions() &&
                    Bukkit.getPluginManager().getPlugin("WorldGuard") != null &&
                    Bukkit.getPluginManager().getPlugin("EliteMobs") != null) {
                WorldGuard.Protect(lowestCorner, highestCorner, bossFilename, reusableLocation);
            } else if (!worldGuardWarn) {
                worldGuardWarn = true;
                Logger.warn("You are not using WorldGuard, so BetterStructures could not protect a boss arena! Using WorldGuard is recommended to guarantee a fair combat experience.");
            }
        }

        // Spawn MythicMobs entities
        for (Map.Entry<Vector, String> entry : schematicContainer.getMythicMobsSpawns().entrySet()) {
            LocationProjector.project(reusableLocation, location, schematicOffset, entry.getKey());
            reusableLocation.getBlock().setType(Material.AIR);

            // If the spawn fails then don't continue
            if (!MythicMobs.Spawn(reusableLocation, entry.getValue())) return;
        }
    }
}