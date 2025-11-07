package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.betterstructures.util.distributedload.WorkloadRunnable;
import com.magmaguy.magmacore.util.Logger;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.*;
import com.sk89q.worldedit.extent.clipboard.io.*;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

public final class Schematic {
    private static final Queue<PasteBlockOperation> PASTE_QUEUE = new ConcurrentLinkedQueue<>();
    private static boolean isDistributedPasting = false;
    private static boolean erroredOnce = false;

    private Schematic() {}

    /** Loads a schematic from a file */
    public static Clipboard load(File schematicFile) {
        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            Logger.warn("Unknown schematic format: " + schematicFile.getName());
            return null;
        }

        try (FileInputStream fis = new FileInputStream(schematicFile);
             ClipboardReader reader = format.getReader(fis)) {
            return reader.read();
        } catch (IOException | NoSuchElementException e) {
            Logger.warn("Failed to load schematic: " + schematicFile.getName());
            e.printStackTrace();
        } catch (Exception e) {
            if (!erroredOnce) {
                Logger.warn("Likely WorldEdit version mismatch while loading " + schematicFile.getName());
                e.printStackTrace();
                erroredOnce = true;
            } else {
                Logger.warn("Repeated schematic load error suppressed.");
            }
        }
        return null;
    }

    /** Simple synchronous paste */
    public static void paste(Clipboard clipboard, Location location) {
        World world = BukkitAdapter.adapt(location.getWorld());
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(world)) {
            Operation op = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                    .build();
            Operations.complete(op);
        } catch (WorldEditException e) {
            throw new RuntimeException("Failed to paste schematic", e);
        }
    }

    /** Creates PasteBlock objects from a schematic */
    private static List<PasteBlock> createPasteBlocks(
            Clipboard schematicClipboard,
            Location location,
            Vector offset,
            Function<Boolean, Material> pedestalMaterialProvider) {

        List<PasteBlock> pasteBlocks = new ArrayList<>();
        Location base = location.clone().add(offset);
        int width = schematicClipboard.getDimensions().x();
        int height = schematicClipboard.getDimensions().y();
        int length = schematicClipboard.getDimensions().z();
        BlockVector3 min = schematicClipboard.getMinimumPoint();

        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                for (int z = 0; z < length; z++) {
                    BlockVector3 vec = BlockVector3.at(min.x() + x, min.y() + y, min.z() + z);
                    BaseBlock baseBlock = schematicClipboard.getFullBlock(vec);
                    Material material = BukkitAdapter.adapt(baseBlock.getBlockType());
                    Block worldBlock = base.clone().add(x, y, z).getBlock();

                    // Skip barriers
                    if (material == Material.BARRIER) continue;

                    String matName = material.name();
                    boolean isGround = !BukkitAdapter.adapt(
                            schematicClipboard.getBlock(vec.add(0, 1, 0)).getBlockType()
                    ).isSolid();

                    // If complex block type → use WorldEdit paste
                    if (requiresWorldEditPaste(matName)) {
                        pasteBlocks.add(new PasteBlock(worldBlock, null,
                                WorldEditUtils.createSingleBlockClipboard(base, baseBlock, baseBlock.toImmutableState())));
                        continue;
                    }

                    // Handle bedrock pedestal replacement
                    if (material == Material.BEDROCK && !worldBlock.getType().isSolid()) {
                        Material pedestalMat = pedestalMaterialProvider.apply(isGround);
                        worldBlock.setType(pedestalMat);
                        pasteBlocks.add(new PasteBlock(worldBlock, pedestalMat.createBlockData(), null));
                        continue;
                    }

                    // Regular block placement
                    BlockData data = Bukkit.createBlockData(baseBlock.toImmutableState().getAsString());
                    pasteBlocks.add(new PasteBlock(worldBlock, data, null));
                }

        return pasteBlocks;
    }

    /** Determines if a block type requires WorldEdit paste */
    private static boolean requiresWorldEditPaste(String name) {
        name = name.toUpperCase(Locale.ROOT);
        return name.endsWith("SIGN") || name.endsWith("STAIRS") || name.endsWith("BOX")
                || name.contains("CHEST") || name.contains("SPAWNER") || name.contains("COMMAND_BLOCK")
                || name.contains("CAMPFIRE") || name.contains("SCULK") || name.contains("RAIL")
                || name.equals("BEACON") || name.equals("CAULDRON") || name.equals("ANVIL")
                || name.equals("DISPENSER") || name.equals("DROPPER") || name.equals("FURNACE")
                || name.equals("ENCHANTING_TABLE") || name.equals("BARREL") || name.equals("HOPPER")
                || name.equals("JUKEBOX") || name.equals("LOOM") || name.equals("LEVER")
                || name.equals("STONECUTTER") || name.equals("CRAFTER") || name.equals("LODESTONE")
                || name.startsWith("POTTED");
    }

    /** Paste schematic in distributed workload */
    public static void pasteSchematic(
            Clipboard clipboard,
            Location location,
            Vector offset,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {

        List<PasteBlock> blocks = createPasteBlocks(clipboard, location, offset, pedestalMaterialProvider);
        pasteDistributed(blocks, location, onComplete);
    }

    /** Adds operation to queue and processes asynchronously */
    public static void pasteDistributed(List<PasteBlock> blocks, Location location, Runnable onComplete) {
        PASTE_QUEUE.add(new PasteBlockOperation(blocks, location, onComplete));
        if (!isDistributedPasting) processNextPaste();
    }

    private static void processNextPaste() {
        PasteBlockOperation op = PASTE_QUEUE.poll();
        if (op == null) {
            isDistributedPasting = false;
            return;
        }

        isDistributedPasting = true;
        WorkloadRunnable workload = new WorkloadRunnable(
                DefaultConfig.getPercentageOfTickUsedForPasting(),
                () -> {
                    if (op.onComplete != null) op.onComplete.run();
                    processNextPaste();
                });

        for (PasteBlock block : op.blocks) {
            workload.addWorkload(() -> {
                if (block.blockData() != null) {
                    block.block().setBlockData(block.blockData());
                } else if (block.clipboard() != null) {
                    try (EditSession session = WorldEdit.getInstance()
                            .newEditSession(BukkitAdapter.adapt(block.block().getWorld()))) {
                        Operation worldeditPaste = new ClipboardHolder(block.clipboard())
                                .createPaste(session)
                                .to(BlockVector3.at(block.block().getX(), block.block().getY(), block.block().getZ()))
                                .build();
                        Operations.complete(worldeditPaste);
                    } catch (WorldEditException e) {
                        Logger.warn("Failed pasting block at " + block.block().getLocation());
                    }
                }
            });
        }

        workload.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    /** Record definitions */
    private record PasteBlockOperation(List<PasteBlock> blocks, Location location, Runnable onComplete) {}
    public record PasteBlock(Block block, BlockData blockData, Clipboard clipboard) {}
}
