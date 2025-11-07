package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.buildingfitter.*;
import com.magmaguy.betterstructures.buildingfitter.util.FitUndergroundDeepBuilding;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.ValidWorldsConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.modules.WFCGenerator;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class NewChunkLoadEvent implements Listener {

    private static final Set<Chunk> LOADING_CHUNKS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, List<ModuleGeneratorsConfigFields>> WORLD_GENERATORS_CACHE = new ConcurrentHashMap<>();
    private static final Map<World.Environment, List<ModuleGeneratorsConfigFields>> ENVIRONMENT_GENERATORS_CACHE = new ConcurrentHashMap<>();
    private static final Map<GeneratorConfigFields.StructureType, Boolean> HAS_SCHEMATICS_CACHE = new ConcurrentHashMap<>();
    private static List<ModuleGeneratorsConfigFields> UNRESTRICTED_GENERATORS = null;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!event.isNewChunk() || !ValidWorldsConfig.isValidWorld(world)) return;

        Chunk chunk = event.getChunk();
        if (!LOADING_CHUNKS.add(chunk)) return;

        // Schedule cleanup (removes from loading set)
        new BukkitRunnable() {
            @Override
            public void run() {
                LOADING_CHUNKS.remove(chunk);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 20L);

        processChunkScanners(chunk);
    }

    /**
     * Executes different structure scans on chunk load.
     */
    private void processChunkScanners(Chunk chunk) {
        scanSurface(chunk);
        scanUndergroundShallow(chunk);
        scanUndergroundDeep(chunk);
        // Uncomment if needed:
        // scanSky(chunk);
        // scanLiquidSurface(chunk);
        // scanDungeon(chunk);
    }

    /* ============================ STRUCTURE SCANNERS ============================ */

    private void scanSurface(Chunk chunk) {
        if (hasSchematics(GeneratorConfigFields.StructureType.SURFACE)
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SURFACE,
                DefaultConfig.getDistanceSurface(), DefaultConfig.getMaxOffsetSurface())) {
            new FitSurfaceBuilding(chunk);
        }
    }

    private void scanUndergroundShallow(Chunk chunk) {
        if (hasSchematics(GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW)
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW,
                DefaultConfig.getDistanceShallow(), DefaultConfig.getMaxOffsetShallow())) {
            FitUndergroundShallowBuilding.fit(chunk);
        }
    }

    private void scanUndergroundDeep(Chunk chunk) {
        if (hasSchematics(GeneratorConfigFields.StructureType.UNDERGROUND_DEEP)
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_DEEP,
                DefaultConfig.getDistanceDeep(), DefaultConfig.getMaxOffsetDeep())) {
            FitUndergroundDeepBuilding.fit(chunk);
        }
    }

    private void scanSky(Chunk chunk) {
        if (hasSchematics(GeneratorConfigFields.StructureType.SKY)
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SKY,
                DefaultConfig.getDistanceSky(), DefaultConfig.getMaxOffsetSky())) {
            new FitAirBuilding(chunk);
        }
    }

    private void scanLiquidSurface(Chunk chunk) {
        if (hasSchematics(GeneratorConfigFields.StructureType.LIQUID_SURFACE)
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.LIQUID_SURFACE,
                DefaultConfig.getDistanceLiquid(), DefaultConfig.getMaxOffsetLiquid())) {
            new FitLiquidBuilding(chunk);
        }
    }

    private void scanDungeon(Chunk chunk) {
        if (ModuleGeneratorsConfig.getModuleGenerators().isEmpty()) return;
        if (!isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.DUNGEON,
                DefaultConfig.getDistanceDungeon(), DefaultConfig.getMaxOffsetDungeon())) return;

        List<ModuleGeneratorsConfigFields> validatedGenerators = getValidatedGenerators(chunk);
        if (validatedGenerators.isEmpty()) return;

        ModuleGeneratorsConfigFields selected = validatedGenerators.get(
                ThreadLocalRandom.current().nextInt(validatedGenerators.size()));
        new WFCGenerator(selected, chunk.getBlock(8, 0, 8).getLocation());
    }

    /* ============================ STRUCTURE PLACEMENT LOGIC ============================ */

    private boolean hasSchematics(GeneratorConfigFields.StructureType type) {
        return HAS_SCHEMATICS_CACHE.computeIfAbsent(type, t -> !SchematicContainer.getSchematics().get(t).isEmpty());
    }

    /**
     * Determines if the chunk is valid for this structure type.
     * Uses deterministic randomization seeded from world + structure type.
     */
    private boolean isValidStructurePosition(Chunk chunk, GeneratorConfigFields.StructureType type,
                                             int gridDistance, int maxOffset) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        long seed = chunk.getWorld().getSeed() + type.name().hashCode() * 7919L;

        int gridX = chunkX / gridDistance;
        int gridZ = chunkZ / gridDistance;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isChunkInStructurePosition(chunkX, chunkZ, gridX + dx, gridZ + dz, gridDistance, maxOffset, seed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isChunkInStructurePosition(int chunkX, int chunkZ, int gridX, int gridZ,
                                               int gridDistance, int maxOffset, long seed) {
        int baseX = gridX * gridDistance;
        int baseZ = gridZ * gridDistance;

        // Diamond pattern adjustment
        if ((gridZ & 1) != 0) baseX += gridDistance / 2;

        long combinedSeed = seed ^ (((long) baseX << 32) ^ (baseZ & 0xFFFFFFFFL));
        Random random = ThreadLocalRandom.current();
        random.setSeed(combinedSeed);

        int offsetX = maxOffset > 0 ? random.nextInt(maxOffset * 2 + 1) - maxOffset : 0;
        int offsetZ = maxOffset > 0 ? random.nextInt(maxOffset * 2 + 1) - maxOffset : 0;

        return chunkX == baseX + offsetX && chunkZ == baseZ + offsetZ;
    }

    /* ============================ GENERATOR VALIDATION & CACHING ============================ */

    private List<ModuleGeneratorsConfigFields> getValidatedGenerators(Chunk chunk) {
        World world = chunk.getWorld();
        String worldName = world.getName();
        World.Environment env = world.getEnvironment();

        // 1. World-level cache
        List<ModuleGeneratorsConfigFields> cached = WORLD_GENERATORS_CACHE.get(worldName);
        if (cached != null) return cached;

        // 2. Environment-level cache
        cached = ENVIRONMENT_GENERATORS_CACHE.get(env);
        if (cached != null) return cached;

        // 3. Unrestricted cache
        if (UNRESTRICTED_GENERATORS != null) return UNRESTRICTED_GENERATORS;

        // Build and cache
        List<ModuleGeneratorsConfigFields> result = new ArrayList<>();
        boolean hasWorldRestrictions = false, hasEnvRestrictions = false;

        for (ModuleGeneratorsConfigFields generator : ModuleGeneratorsConfig.getModuleGenerators().values()) {
            if (!isGeneratorValidForWorld(generator, worldName, env)) continue;
            result.add(generator);
            if (!generator.getValidWorlds().isEmpty()) hasWorldRestrictions = true;
            if (!generator.getValidWorldEnvironments().isEmpty()) hasEnvRestrictions = true;
        }

        if (!hasWorldRestrictions && !hasEnvRestrictions) {
            UNRESTRICTED_GENERATORS = result;
        } else if (!hasWorldRestrictions) {
            ENVIRONMENT_GENERATORS_CACHE.put(env, result);
        } else {
            WORLD_GENERATORS_CACHE.put(worldName, result);
        }

        return result;
    }

    private boolean isGeneratorValidForWorld(ModuleGeneratorsConfigFields generator,
                                             String worldName, World.Environment env) {
        List<String> validWorlds = generator.getValidWorlds();
        if (!validWorlds.isEmpty() && !validWorlds.contains(worldName)) return false;

        List<World.Environment> validEnvs = generator.getValidWorldEnvironments();
        return validEnvs.isEmpty() || validEnvs.contains(env);
    }

    public static void clearCaches() {
        HAS_SCHEMATICS_CACHE.clear();
        WORLD_GENERATORS_CACHE.clear();
        ENVIRONMENT_GENERATORS_CACHE.clear();
        UNRESTRICTED_GENERATORS = null;
    }
}
