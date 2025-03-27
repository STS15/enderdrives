package com.sts15.enderdrives.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.Arrays;

public class ModCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("enderdrives")
                        .then(Commands.literal("setfreq")
                                .then(Commands.argument("frequency", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            int freq = IntegerArgumentType.getInteger(ctx, "frequency");
                                            CommandSourceStack source = ctx.getSource();
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
                                .then(Commands.argument("frequency", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                            CommandSourceStack source = ctx.getSource();
                                            ServerPlayer player = source.getPlayerOrException();
                                            String scopePrefix = "player_" + player.getUUID().toString();
                                            EnderDBManager.clearFrequency(scopePrefix, frequency);
                                            EnderDBManager.commitDatabase();
                                            source.sendSuccess(() -> Component.literal("Cleared frequency " + frequency + " for your private channel."), true);
                                            return 1;
                                        })
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
                        .then(Commands.literal("benchmark")
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    String scopePrefix = "player_" + player.getUUID();
                                    int[] testSizes = {10_000, 50_000, 100_000, 250_000, 500_000};
                                    int frequency = 999;

                                    for (int size : testSizes) {
                                        EnderDBManager.clearFrequency(scopePrefix, frequency);
                                        long insertStart = System.currentTimeMillis();
                                        for (int i = 1; i <= size; i++) {
                                            ItemStack paper = new ItemStack(Items.PAPER);
                                            int finalI = i;
                                            paper.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
                                                CompoundTag tag = oldData.copyTag();
                                                CompoundTag display = new CompoundTag();
                                                display.putString("Name", "{\"text\":\"" + finalI + "\"}");
                                                tag.put("display", display);
                                                return CustomData.of(tag);
                                            });
                                            byte[] serialized = EnderDiskInventory.serializeItemStackToBytes(paper);
                                            EnderDBManager.saveItem(scopePrefix, frequency, serialized, 1);
                                        }
                                        long insertEnd = System.currentTimeMillis();
                                        long queryStart = System.currentTimeMillis();
                                        int typeCount = EnderDBManager.getTypeCount(scopePrefix + "[" + frequency + "]");
                                        long queryEnd = System.currentTimeMillis();
                                        double insertTime = insertEnd - insertStart;
                                        double queryTime = queryEnd - queryStart;
                                        long[] tickTimes = player.getServer().getTickTime(Level.OVERWORLD);
                                        double avgTick = Arrays.stream(tickTimes).average().orElse(0) / 1_000_000.0;
                                        double tps = Math.min(1000.0 / avgTick, 20.0);
                                        source.sendSuccess(() -> Component.literal(
                                                "§b[EnderDrives Benchmark]\n" +
                                                        "§7Inserted: §a" + size + "§r types\n" +
                                                        "§7Insert Time: §e" + insertTime + "ms\n" +
                                                        "§7Query Time: §e" + queryTime + "ms\n" +
                                                        "§7Returned Types: §a" + typeCount + "\n" +
                                                        "§7Overworld TPS: §a" + String.format("%.2f", tps) +
                                                        " §7| Avg Tick Time: §a" + String.format("%.2f", avgTick) + "ms"
                                        ), false);
                                    }

                                    return 1;
                                })
                        )
                        .then(Commands.literal("stress")
                                .then(Commands.argument("frequency", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                    CommandSourceStack source = ctx.getSource();
                                                    ServerPlayer player = source.getPlayerOrException();
                                                    String scopePrefix = "player_" + player.getUUID().toString();
                                                    long insertStart = System.currentTimeMillis();
                                                    for (int i = 1; i <= amount; i++) {
                                                        final int count = i;
                                                        ItemStack paper = new ItemStack(net.minecraft.world.item.Items.PAPER);
                                                        paper.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY, oldData -> {
                                                            CompoundTag tag = oldData.copyTag();
                                                            CompoundTag display = new CompoundTag();
                                                            display.putString("Name", "{\"text\":\"" + count + "\"}");
                                                            tag.put("display", display);
                                                            return net.minecraft.world.item.component.CustomData.of(tag);
                                                        });
                                                        byte[] serialized = EnderDiskInventory.serializeItemStackToBytes(paper);
                                                        EnderDBManager.saveItem(scopePrefix, frequency, serialized, 1);
                                                    }
                                                    long insertEnd = System.currentTimeMillis();
                                                    long insertTime = insertEnd - insertStart;

                                                    long queryStart = System.currentTimeMillis();
                                                    int typeCount = EnderDBManager.getTypeCount(scopePrefix + "[" + frequency + "]");
                                                    long queryEnd = System.currentTimeMillis();
                                                    long queryTime = queryEnd - queryStart;

                                                    source.sendSuccess(() -> Component.literal("Stress test complete on your private channel:"), false);
                                                    source.sendSuccess(() -> Component.literal("Inserted " + amount + " items at frequency " + frequency + " in " + insertTime + " ms."), false);
                                                    source.sendSuccess(() -> Component.literal("Query returned " + typeCount + " unique types in " + queryTime + " ms."), false);
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );
    }
}
