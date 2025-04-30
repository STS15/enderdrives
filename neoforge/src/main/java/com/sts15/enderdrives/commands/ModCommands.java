package com.sts15.enderdrives.commands;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import appeng.me.cells.BasicCellInventory;
import appeng.me.helpers.BaseActionSource;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.AEKeyCacheEntry;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.db.TapeDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.TapeDiskItem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.io.*;
import java.util.*;

public class ModCommands {

    private static final Map<UUID, Integer> pendingBenchmarkRequests = new HashMap<>();
    private static final Map<UUID, String> pendingClearRequests = new HashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("enderdrives")
                        .then(Commands.literal("setfreq")
                                .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                                        .executes(ctx -> {
                                            int freq = IntegerArgumentType.getInteger(ctx, "frequency");
                                            CommandSourceStack source = ctx.getSource();
                                            if (!validateFrequency(freq, source)) return 0;

                                            ItemStack heldItem = source.getPlayerOrException().getMainHandItem();
                                            if (heldItem.getItem() instanceof EnderDiskItem) {
                                                EnderDiskItem.setFrequency(heldItem, freq);
                                                source.sendSuccess(() -> Component.literal("Frequency set to " + freq), true);
                                            } else {
                                                source.sendFailure(Component.literal("Hold an EnderDisk in your hand."));
                                            }
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("clear")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("private");
                                            builder.suggest("team");
                                            builder.suggest("global");
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                                    if (!validateFrequency(frequency, source)) return 0;

                                                    String type = StringArgumentType.getString(ctx, "type").toLowerCase();
                                                    UUID playerId = player.getUUID();

                                                    String scopePrefix;
                                                    switch (type) {
                                                        case "private" -> scopePrefix = "player_" + playerId;
                                                        case "team" -> scopePrefix = "team_" + playerId;
                                                        case "global" -> {
                                                            if (!source.hasPermission(4)) {
                                                                source.sendFailure(Component.literal("§cYou must be a server operator to clear general channels."));
                                                                return 0;
                                                            }
                                                            scopePrefix = "global";
                                                        }
                                                        default -> {
                                                            source.sendFailure(Component.literal("§cInvalid channel type. Use 'private', 'team', or 'global'."));
                                                            return 0;
                                                        }
                                                    }

                                                    String key = playerId + ":" + type + ":" + frequency;
                                                    if (!key.equals(pendingClearRequests.get(playerId))) {
                                                        pendingClearRequests.put(playerId, key);
                                                        source.sendSuccess(() -> Component.literal(
                                                                "§c⚠ This will permanently delete all items stored in frequency §e" + frequency +
                                                                        "§c under the §6" + type + "§c scope.\n§7If you are sure, run the same command again to confirm."
                                                        ), false);
                                                        return 1;
                                                    }

                                                    pendingClearRequests.remove(playerId);
                                                    EnderDBManager.clearFrequency(scopePrefix, frequency);
                                                    EnderDBManager.commitDatabase();

                                                    source.sendSuccess(() -> Component.literal(
                                                            "§a✔ Cleared frequency §b" + frequency + "§a for scope §6" + type
                                                    ), true);
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("stats")
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    source.sendSuccess(() -> Component.literal(
                                            "EnderDB Stats:\n" +
                                                    " - DB Entries: " + EnderDBManager.getDatabaseSize() + "\n" +
                                                    " - Items Written: " + EnderDBManager.getTotalItemsWritten() + "\n" +
                                                    " - Commits: " + EnderDBManager.getTotalCommits() + "\n" +
                                                    " - DB File Size: " + EnderDBManager.getDatabaseFileSizeBytes() + " bytes"
                                    ), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("autobenchmark")
                                .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                                        .executes(ctx -> {
                                            int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                            CommandSourceStack source = ctx.getSource();
                                            if (!validateFrequency(frequency, source)) return 0;
                                            ServerPlayer player = source.getPlayerOrException();
                                            UUID playerId = player.getUUID();

                                            if (!pendingBenchmarkRequests.containsKey(playerId)) {
                                                pendingBenchmarkRequests.put(playerId, frequency);
                                                source.sendSuccess(() -> Component.literal(
                                                        "§b[EnderDrives Autobenchmark]\n" +
                                                                "§7This command will insert items until §f2,000,000 §7types are added to the database.\n" +
                                                                "§7It will automatically stop if server TPS drops below §c18§7.\n\n" +
                                                                "§fOnce the test starts, open an AE2 terminal with an EnderDrive installed at frequency §a" + frequency + "§f and scope §aPrivate§f.\n\n" +
                                                                "§eRe-run §6/enderdrives autobenchmark " + frequency + " §eto confirm and begin the test."
                                                ), false);
                                                return 1;
                                            }

                                            int confirmedFrequency = pendingBenchmarkRequests.get(playerId);
                                            if (confirmedFrequency != frequency) {
                                                source.sendFailure(Component.literal("Frequency mismatch. Use the same frequency you confirmed."));
                                                return 0;
                                            }

                                            MinecraftServer server = player.getServer();
                                            String scopePrefix = "player_" + player.getUUID();

                                            int typeCount_check = EnderDBManager.getTypeCount(scopePrefix, frequency);
                                            if (typeCount_check > 0) {
                                                source.sendFailure(Component.literal("Frequency " + frequency + " is not empty. Autobenchmark requires a completely empty frequency."));
                                                pendingBenchmarkRequests.remove(playerId);
                                                return 0;
                                            }

                                            pendingBenchmarkRequests.remove(playerId);

                                            Thread t = new Thread(() -> {
                                                try {

                                                    final int step = serverConfig.AUTO_BENCHMARK_STEP.get();
                                                    final int maxSize = serverConfig.AUTO_BENCHMARK_MAX_TYPES.get();
                                                    final double minSafeTPS = serverConfig.AUTO_BENCHMARK_MIN_TPS.get();
                                                    int bestSize = 0;
                                                    byte[] serialized;
                                                    int currentSize = serverConfig.AUTO_BENCHMARK_INITIAL_SIZE.get();
                                                    boolean continueTesting = true;

                                                    while (continueTesting && currentSize <= maxSize) {
                                                        EnderDBManager.clearFrequency(scopePrefix, frequency);

                                                        long insertStart = System.currentTimeMillis();
                                                        for (int i = 1; i <= currentSize; i++) {
                                                            ItemStack paper = new ItemStack(Items.PAPER);
                                                            paper.set(DataComponents.CUSTOM_NAME, Component.literal(String.valueOf(i)));

                                                            AEItemKey key = AEItemKey.of(paper);
                                                            serialized = EnderDiskInventory.serializeItemStackToBytes(key.toStack(1));
                                                            EnderDBManager.saveItem(scopePrefix, frequency, serialized, 1);
                                                        }
                                                        long insertEnd = System.currentTimeMillis();
                                                        Thread.sleep(serverConfig.AUTO_BENCHMARK_MS_SLEEP.get());

                                                        long[] tickTimes = server.getTickTime(Level.OVERWORLD);
                                                        double avgTick = Arrays.stream(tickTimes).average().orElse(0) / 1_000_000.0;
                                                        double tps = Math.min(1000.0 / avgTick, 20.0);

                                                        long queryStart = System.currentTimeMillis();
                                                        int typeCount = EnderDBManager.getTypeCount(scopePrefix, frequency);
                                                        long queryEnd = System.currentTimeMillis();

                                                        long usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

                                                        double insertTime = insertEnd - insertStart;
                                                        double queryTime = queryEnd - queryStart;

                                                        int finalCurrentSize = currentSize;
                                                        source.sendSuccess(() -> Component.literal(
                                                                "§b[AutoBenchmark]\n" +
                                                                        "§7Tested Size: §a" + finalCurrentSize + " types\n" +
                                                                        "§7Insert: §e" + insertTime + "ms\n" +
                                                                        "§7Query: §e" + queryTime + "ms\n" +
                                                                        "§7Types: §a" + typeCount + "\n" +
                                                                        "§7Memory: §b" + usedMem + "MB\n" +
                                                                        "§7TPS: §a" + String.format("%.2f", tps) +
                                                                        " §7| Tick: §a" + String.format("%.2f", avgTick) + "ms"
                                                        ), false);

                                                        if (tps >= minSafeTPS) {
                                                            bestSize = currentSize;
                                                            currentSize += step;
                                                        } else {
                                                            source.sendSuccess(() -> Component.literal("§c⚠ TPS dropped below " + minSafeTPS + ". Stopping."), false);
                                                            break;
                                                        }
                                                    }

                                                    int finalBestSize = bestSize;
                                                    source.sendSuccess(() -> Component.literal(
                                                            "§a✅ Best stable entry count: §b" + finalBestSize + " types"
                                                    ), false);

                                                    EnderDBManager.clearFrequency(scopePrefix, frequency);
                                                    EnderDBManager.commitDatabase();

                                                } catch (Exception ex) {
                                                    ex.printStackTrace();
                                                }
                                            }, "EnderDB-autobenchmark");

                                            t.setDaemon(true);
                                            t.start();
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("stress")
                                .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1,2000000))
                                                .executes(ctx -> {
                                                    int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                                    if (!validateFrequency(frequency, ctx.getSource())) return 0;
                                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                    CommandSourceStack source = ctx.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    String scopePrefix = "player_" + player.getUUID().toString();

                                                    Set<Integer> seenHashes = new HashSet<>();
                                                    int duplicates = 0;

                                                    long insertStart = System.currentTimeMillis();
                                                    for (int i = 1; i <= amount; i++) {
                                                        ItemStack paper = new ItemStack(Items.PAPER, 1);
                                                        paper.set(DataComponents.CUSTOM_NAME, Component.literal(String.valueOf(i)));

                                                        AEItemKey key = AEItemKey.of(paper);
                                                        byte[] serialized = EnderDiskInventory.serializeItemStackToBytes(key.toStack(1));

                                                        int hash = Arrays.hashCode(serialized);
                                                        if (!seenHashes.add(hash)) {
                                                            int finalI = i;
                                                            source.sendSuccess(() -> Component.literal("Duplicate detected for: " + finalI), false);
                                                            duplicates++;
                                                        }

                                                        EnderDBManager.saveItem(scopePrefix, frequency, serialized, 1);
                                                    }
                                                    long insertEnd = System.currentTimeMillis();

                                                    int typeCount = EnderDBManager.getTypeCount(scopePrefix, frequency);
                                                    long insertTime = insertEnd - insertStart;
                                                    int finalDuplicates = duplicates;

                                                    source.sendSuccess(() -> Component.literal("Stress test complete on your private channel:"), false);
                                                    source.sendSuccess(() -> Component.literal("Inserted " + amount + " items in " + insertTime + " ms."), false);
                                                    source.sendSuccess(() -> Component.literal("Unique types: " + typeCount), false);
                                                    source.sendSuccess(() -> Component.literal("Duplicates: " + finalDuplicates), false);

                                                    return 1;
                                                })
                                        ))
                        )
                        .then(Commands.literal("dumpcell")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("private");
                                            builder.suggest("team");
                                            builder.suggest("global");
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                                    if (!validateFrequency(frequency, source)) return 0;

                                                    String type = StringArgumentType.getString(ctx, "type").toLowerCase();
                                                    UUID playerId = player.getUUID();
                                                    String scopePrefix;

                                                    switch (type) {
                                                        case "private" -> scopePrefix = "player_" + playerId;
                                                        case "team" -> scopePrefix = "team_" + playerId;
                                                        case "global" -> {
                                                            if (!source.hasPermission(4)) {
                                                                source.sendFailure(Component.literal("§cYou must be a server operator to dump global channels."));
                                                                return 0;
                                                            }
                                                            scopePrefix = "global";
                                                        }
                                                        default -> {
                                                            source.sendFailure(Component.literal("§cInvalid channel type. Use 'private', 'team', or 'global'."));
                                                            return 0;
                                                        }
                                                    }

                                                    List<AEKeyCacheEntry> entries = EnderDBManager.queryItemsByFrequency(scopePrefix, frequency);

                                                    if (entries.isEmpty()) {
                                                        source.sendFailure(Component.literal("§cNo items found in frequency " + frequency + "."));
                                                        return 0;
                                                    }

                                                    List<ItemStack> createdCells = new ArrayList<>();
                                                    ItemStack currentCell = null;
                                                    BasicCellInventory handler = null;

                                                    long totalInserted = 0;

                                                    for (AEKeyCacheEntry entry : entries) {
                                                        AEItemKey key = entry.aeKey();
                                                        long remaining = entry.count();

                                                        while (remaining > 0) {
                                                            if (handler == null) {
                                                                currentCell = new ItemStack(AEItems.ITEM_CELL_256K.get());
                                                                var inventory = StorageCells.getCellInventory(currentCell, null);

                                                                if (!(inventory instanceof BasicCellInventory h)) {
                                                                    source.sendFailure(Component.literal("§cFailed to access 256k storage cell."));
                                                                    return 0;
                                                                }

                                                                handler = h;
                                                                createdCells.add(currentCell);
                                                                int currentDriveIndex = createdCells.size();
                                                                String prettyType = type.substring(0, 1).toUpperCase() + type.substring(1);
                                                                Component customName = Component.literal("EnderDrives " + prettyType + ":" + frequency + " Drive:" + currentDriveIndex);
                                                                currentCell.set(DataComponents.CUSTOM_NAME, customName);

                                                            }

                                                            long insertAmount = Math.min(remaining, Integer.MAX_VALUE);
                                                            long inserted = handler.insert(key, insertAmount, Actionable.MODULATE, new BaseActionSource());

                                                            if (inserted <= 0) {
                                                                handler = null;
                                                                continue;
                                                            }

                                                            remaining -= inserted;
                                                            totalInserted += inserted;
                                                        }
                                                    }


                                                    for (ItemStack c : createdCells) {
                                                        player.getInventory().placeItemBackInInventory(c);
                                                    }

                                                    long finalTotalInserted = totalInserted;
                                                    source.sendSuccess(() -> Component.literal("§a✔ Dumped §e" + finalTotalInserted + "§a items into §b" + createdCells.size() + "§a 256k drive(s)."), true);
                                                    source.sendSuccess(() -> Component.literal("§a✔ If drive creation was successful, you can run clear on that frequency now."), true);
                                                    return 1;
                                                })
                                        )
                                )
                        )


                        .then(Commands.literal("tape")
                                .then(Commands.literal("release")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds().forEach(uuid -> builder.suggest(uuid.toString()));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                                    try {
                                                        UUID uuid = UUID.fromString(uuidStr);
                                                        if (!com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds().contains(uuid)) {
                                                            source.sendFailure(Component.literal("§cTape " + uuid + " is not currently cached in RAM."));
                                                            return 0;
                                                        }

                                                        com.sts15.enderdrives.db.TapeDBManager.releaseFromRAM(uuid);
                                                        source.sendSuccess(() -> Component.literal("§a✔ Released tape " + uuid + " from RAM."), true);
                                                        return 1;
                                                    } catch (IllegalArgumentException e) {
                                                        source.sendFailure(Component.literal("§cInvalid UUID format: " + uuidStr));
                                                        return 0;
                                                    }
                                                })
                                        )
                                )
                                .then(Commands.literal("setid")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    TapeDBManager.getSortedBinFilesOldestFirst()
                                                            .forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    ItemStack held = player.getMainHandItem();

                                                    if (!(held.getItem() instanceof TapeDiskItem)) {
                                                        source.sendFailure(Component.literal("§cHold a Tape Disk in your main hand."));
                                                        return 0;
                                                    }

                                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                                    try {
                                                        UUID newId = UUID.fromString(uuidStr);

                                                        held.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
                                                            CompoundTag tag = old.copyTag();
                                                            tag.putString("tape_disk_id", newId.toString());
                                                            return CustomData.of(tag);
                                                        });

                                                        source.sendSuccess(() -> Component.literal("§a✔ TapeDisk ID set to " + newId), true);
                                                        return 1;

                                                    } catch (IllegalArgumentException e) {
                                                        source.sendFailure(Component.literal("§cInvalid UUID format: " + uuidStr));
                                                        return 0;
                                                    }
                                                })
                                        )
                                )
                                .then(Commands.literal("list")
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            Set<UUID> cached = com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds();

                                            if (cached.isEmpty()) {
                                                source.sendSuccess(() -> Component.literal("§7No tape drives are currently cached in RAM."), false);
                                                return 1;
                                            }

                                            source.sendSuccess(() -> Component.literal("§bCached Tape Drives:"), false);

                                            for (UUID id : cached) {
                                                int typeCount = com.sts15.enderdrives.db.TapeDBManager.getTypeCount(id);
                                                source.sendSuccess(() ->
                                                                Component.literal(" §8- §f" + id.toString() + " §7| Types: §a" + typeCount),
                                                        false
                                                );
                                            }

                                            return 1;
                                        })
                                )
                                .then(Commands.literal("export")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds().forEach(uuid -> builder.suggest(uuid.toString()));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                                    try {
                                                        UUID uuid = UUID.fromString(uuidStr);
                                                        boolean success = com.sts15.enderdrives.db.TapeDBManager.exportToJson(uuid);
                                                        if (!success) {
                                                            source.sendFailure(Component.literal("§cFailed to export. Tape might not exist or is corrupted."));
                                                            return 0;
                                                        }
                                                        source.sendSuccess(() -> Component.literal("§a✔ Exported tape " + uuid + " to JSON."), false);
                                                        return 1;
                                                    } catch (IllegalArgumentException e) {
                                                        source.sendFailure(Component.literal("§cInvalid UUID format: " + uuidStr));
                                                        return 0;
                                                    }
                                                })
                                        )
                                )
                                .then(Commands.literal("import")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    TapeDBManager.getExportedJsonTapeIds().forEach(uuid -> builder.suggest(uuid.toString()));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                                    try {
                                                        UUID uuid = UUID.fromString(uuidStr);
                                                        boolean success = com.sts15.enderdrives.db.TapeDBManager.importFromJson(uuid);
                                                        if (!success) {
                                                            source.sendFailure(Component.literal("§cFailed to import. File missing or invalid format."));
                                                            return 0;
                                                        }
                                                        source.sendSuccess(() -> Component.literal("§a✔ Imported tape " + uuid + " from JSON."), false);
                                                        return 1;
                                                    } catch (IllegalArgumentException e) {
                                                        source.sendFailure(Component.literal("§cInvalid UUID format: " + uuidStr));
                                                        return 0;
                                                    }
                                                })
                                        )
                                )
                                .then(Commands.literal("oldest")
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            List<File> files = com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst();
                                            if (files.isEmpty()) {
                                                source.sendSuccess(() -> Component.literal("§7No saved tape drives found."), false);
                                                return 1;
                                            }

                                            source.sendSuccess(() -> Component.literal("§bOldest Tape Drives:"), false);
                                            for (File f : files) {
                                                String name = f.getName().replace(".bin", "");
                                                long lastMod = f.lastModified();
                                                long size = f.length();
                                                String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastMod));
                                                source.sendSuccess(() -> Component.literal(" §8- §f" + name + " §7| Modified: §6" + time + " §7| Size: §e" + size + " bytes"), false);
                                            }

                                            return 1;
                                        })
                                )
                                .then(Commands.literal("delete")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst().forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                                                    CommandSourceStack source = ctx.getSource();
                                                    try {
                                                        UUID uuid = UUID.fromString(uuidStr);
                                                        if (com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds().contains(uuid)) {
                                                            com.sts15.enderdrives.db.TapeDBManager.releaseFromRAM(uuid);
                                                            source.sendSuccess(() -> Component.literal("§eTape " + uuid + " was cached in RAM and has been released."), false);
                                                        }

                                                        boolean success = com.sts15.enderdrives.db.TapeDBManager.deleteTape(uuid);
                                                        if (!success) {
                                                            source.sendFailure(Component.literal("§cFailed to delete tape " + uuid + ". File may not exist."));
                                                            return 0;
                                                        }

                                                        source.sendSuccess(() -> Component.literal("§a✔ Deleted tape " + uuid + " from disk."), true);
                                                        return 1;
                                                    } catch (IllegalArgumentException e) {
                                                        source.sendFailure(Component.literal("§cInvalid UUID: " + uuidStr));
                                                        return 0;
                                                    }
                                                })
                                        )
                                )
                                .then(Commands.literal("diagnose")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst().forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String uuidStr = StringArgumentType.getString(ctx, "uuid");

                                                    try {
                                                        UUID uuid = UUID.fromString(uuidStr);
                                                        File file = com.sts15.enderdrives.db.TapeDBManager.getDiskFile(uuid);
                                                        if (!file.exists()) {
                                                            source.sendFailure(Component.literal("§cNo .bin file exists for tape " + uuid));
                                                            return 0;
                                                        }

                                                        int total = 0;
                                                        int failed = 0;
                                                        long bytes = file.length();

                                                        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                                                            while (true) {
                                                                int len = dis.readInt();
                                                                byte[] data = new byte[len];
                                                                dis.readFully(data);
                                                                dis.readLong(); // count
                                                                total++;

                                                                var stack = com.sts15.enderdrives.items.TapeDiskItem.deserializeItemStackFromBytes(data);
                                                                if (stack.isEmpty()) failed++;
                                                            }
                                                        } catch (EOFException ignored) {
                                                        } catch (IOException e) {
                                                            source.sendFailure(Component.literal("§cError while scanning tape file: " + e.getMessage()));
                                                            return 0;
                                                        }

                                                        source.sendSuccess(() -> Component.literal("§b[Diagnosis for " + uuid + "]"), false);
                                                        int finalTotal = total;
                                                        source.sendSuccess(() -> Component.literal(" §7Total entries: §a" + finalTotal), false);
                                                        int finalFailed = failed;
                                                        source.sendSuccess(() -> Component.literal(" §7Malformed: §c" + finalFailed), false);
                                                        source.sendSuccess(() -> Component.literal(" §7Size: §e" + bytes + " bytes"), false);

                                                        if (failed > 0) {
                                                            source.sendSuccess(() -> Component.literal("§e⚠ Suggest exporting backup with /enderdrives tape export " + uuid), false);
                                                        }

                                                        return 1;
                                                    } catch (IllegalArgumentException e) {
                                                        source.sendFailure(Component.literal("§cInvalid UUID format: " + uuidStr));
                                                        return 0;
                                                    }
                                                })
                                        )
                                )
                                .then(Commands.literal("diagnose-all")
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            List<File> files = com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst();
                                            if (files.isEmpty()) {
                                                source.sendSuccess(() -> Component.literal("§7No saved tape drives to verify."), false);
                                                return 1;
                                            }

                                            int badCount = 0;
                                            for (File file : files) {
                                                UUID id = UUID.fromString(file.getName().replace(".bin", ""));
                                                int total = 0;
                                                int failed = 0;
                                                try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                                                    while (true) {
                                                        int len = dis.readInt();
                                                        byte[] data = new byte[len];
                                                        dis.readFully(data);
                                                        dis.readLong(); // count
                                                        total++;
                                                        var stack = com.sts15.enderdrives.items.TapeDiskItem.deserializeItemStackFromBytes(data);
                                                        if (stack.isEmpty()) failed++;
                                                    }
                                                } catch (EOFException ignored) {
                                                } catch (Exception e) {
                                                    source.sendFailure(Component.literal("§cError verifying tape " + id + ": " + e.getMessage()));
                                                    continue;
                                                }

                                                int finalTotal;
                                                if (failed > 0) {
                                                    badCount++;
                                                    int finalFailed = failed;
                                                    finalTotal = total;
                                                    source.sendSuccess(() -> Component.literal("§c" + id + " — " + finalFailed + "/" + finalTotal + " entries failed"), false);
                                                } else {
                                                    finalTotal = 0;
                                                    source.sendSuccess(() -> Component.literal("§a" + id + " — OK (" + finalTotal + " entries)"), false);
                                                }
                                            }

                                            int finalBadCount = badCount;
                                            source.sendSuccess(() -> Component.literal("§b✔ Finished verifying " + files.size() + " tape(s). Bad tapes: §c" + finalBadCount), false);
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("stats")
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            Set<UUID> cached = com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds();
                                            int cachedDrives = cached.size();
                                            int totalTypes = 0;
                                            long totalBytes = 0;

                                            for (UUID id : cached) {
                                                totalTypes += com.sts15.enderdrives.db.TapeDBManager.getTypeCount(id);
                                                totalBytes += com.sts15.enderdrives.db.TapeDBManager.getTotalStoredBytes(id);
                                            }

                                            long totalFiles = com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst().stream().count();
                                            long totalDiskSize = com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst().stream()
                                                    .mapToLong(File::length).sum();

                                            source.sendSuccess(() -> Component.literal("§b[EnderDrives Tape Stats]"), false);
                                            source.sendSuccess(() -> Component.literal(" §7Cached Drives: §a" + cachedDrives), false);
                                            int finalTotalTypes = totalTypes;
                                            source.sendSuccess(() -> Component.literal(" §7Total Types Cached: §e" + finalTotalTypes), false);
                                            long finalTotalBytes = totalBytes;
                                            source.sendSuccess(() -> Component.literal(" §7RAM Usage (Est.): §d" + finalTotalBytes + " bytes"), false);
                                            source.sendSuccess(() -> Component.literal(" §7Stored .bin Files: §b" + totalFiles), false);
                                            source.sendSuccess(() -> Component.literal(" §7Disk Usage: §6" + totalDiskSize + " bytes"), false);

                                            return 1;
                                        })
                                )
                                .then(Commands.literal("cleanup-empty")
                                        .executes(ctx -> {
                                            CommandSourceStack source = ctx.getSource();
                                            List<File> files = com.sts15.enderdrives.db.TapeDBManager.getSortedBinFilesOldestFirst();
                                            int removed = 0;

                                            for (File file : files) {
                                                UUID id = UUID.fromString(file.getName().replace(".bin", ""));
                                                int total = 0;
                                                try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                                                    while (true) {
                                                        int len = dis.readInt();
                                                        dis.skipBytes(len);
                                                        dis.readLong();
                                                        total++;
                                                    }
                                                } catch (EOFException ignored) {
                                                } catch (Exception e) {
                                                    source.sendFailure(Component.literal("§cFailed to read tape " + id + ": " + e.getMessage()));
                                                    continue;
                                                }

                                                if (total == 0) {
                                                    if (com.sts15.enderdrives.db.TapeDBManager.getActiveTapeIds().contains(id)) {
                                                        com.sts15.enderdrives.db.TapeDBManager.releaseFromRAM(id);
                                                    }
                                                    com.sts15.enderdrives.db.TapeDBManager.deleteTape(id);
                                                    removed++;
                                                }
                                            }

                                            int finalRemoved = removed;
                                            source.sendSuccess(() -> Component.literal("§a✔ Removed §b" + finalRemoved + "§a empty tape(s)."), false);
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("pin")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    TapeDBManager.getActiveTapeIds().forEach(id -> builder.suggest(id.toString()));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    UUID uuid = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
                                                    TapeDBManager.pin(uuid);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("§a✔ Tape " + uuid + " pinned to RAM."), true);
                                                    return 1;
                                                })
                                        )
                                )
                                .then(Commands.literal("unpin")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    TapeDBManager.getPinnedTapes().forEach(id -> builder.suggest(id.toString()));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    UUID uuid = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
                                                    TapeDBManager.unpin(uuid);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("§e✔ Tape " + uuid + " unpinned."), true);
                                                    return 1;
                                                })
                                        )
                                )
                                .then(Commands.literal("info")
                                        .then(Commands.argument("uuid", StringArgumentType.string())
                                                .suggests((ctx, builder) -> {
                                                    TapeDBManager.getSortedBinFilesOldestFirst().forEach(f -> builder.suggest(f.getName().replace(".bin", "")));
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    UUID uuid = UUID.fromString(StringArgumentType.getString(ctx, "uuid"));
                                                    boolean cached = TapeDBManager.getCache(uuid) != null;
                                                    int typeCount = TapeDBManager.getTypeCount(uuid);
                                                    long byteSize = TapeDBManager.getTotalStoredBytes(uuid);
                                                    boolean pinned = TapeDBManager.isPinned(uuid);
                                                    long lastAccessed = cached ? TapeDBManager.getCache(uuid).lastAccessed : -1;
                                                    String accessed = lastAccessed > 0
                                                            ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastAccessed))
                                                            : "§7(Not in RAM)";

                                                    ctx.getSource().sendSuccess(() -> Component.literal("§b[Info for Tape " + uuid + "]"), false);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(" §7In RAM: " + (cached ? "§aYes" : "§cNo")), false);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(" §7Pinned: " + (pinned ? "§aYes" : "§cNo")), false);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(" §7Types: §e" + typeCount), false);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(" §7Bytes: §d" + byteSize), false);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(" §7Last Accessed: " + accessed), false);
                                                    return 1;
                                                })
                                        )
                                )



                        )


        );
    }

    private static boolean validateFrequency(int freq, CommandSourceStack source) {
        int min = serverConfig.FREQ_MIN.get();
        int max = serverConfig.FREQ_MAX.get();
        if (freq < min || freq > max) {
            source.sendFailure(Component.literal("§cFrequency must be between §e" + min + "§c and §e" + max + "§c."));
            return false;
        }
        return true;
    }

}
