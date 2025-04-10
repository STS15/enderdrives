package com.sts15.enderdrives.commands;

import appeng.api.stacks.AEItemKey;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.AEKeyCacheEntry;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
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
import java.io.File;
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
                                                    " - WAL Queue: " + EnderDBManager.getWalQueueSize() + "\n" +
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
                                                        EnderDBManager.flushDeltaBuffer();
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
