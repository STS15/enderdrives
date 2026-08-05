package com.sts15.enderdrives.commands;

import appeng.api.stacks.AEItemKey;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /enderdrives stress
 */
public final class StressTestCommand {

    private static final int WORK_BATCH_SIZE = 5_000;
    private static final ExecutorService STRESS_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "EnderDB-stress-test");
        thread.setDaemon(true);
        return thread;
    });

    private StressTestCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("stress")
                .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2_000_000))
                                .executes(ctx -> {
                                    int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                    if (!CommandUtils.validateFrequency(frequency, ctx.getSource())) return 0;
                                     int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                     CommandSourceStack source = ctx.getSource();
                                     ServerPlayer player = source.getPlayerOrException();
                                     String scopePrefix = "player_" + player.getUUID();
                                     MinecraftServer server = source.getServer();
                                     UUID playerId = player.getUUID();

                                     CompletableFuture.runAsync(
                                             () -> runStressTest(server, playerId, scopePrefix, frequency, amount),
                                             STRESS_EXECUTOR);

                                     return 1;
                                 })
                         ));
    }

    private static void runStressTest(
            MinecraftServer server,
            UUID playerId,
            String scopePrefix,
            int frequency,
            int amount) {
        try {
            Set<Integer> seenHashes = new HashSet<>();
            int duplicates = 0;
            long insertStart = System.currentTimeMillis();

            for (int batchStart = 1; batchStart <= amount; batchStart += WORK_BATCH_SIZE) {
                if (!isCurrentServer(server)) return;
                int batchEnd = Math.min(amount, batchStart + WORK_BATCH_SIZE - 1);
                for (int i = batchStart; i <= batchEnd; i++) {
                    ItemStack paper = new ItemStack(Items.PAPER, 1);
                    paper.set(DataComponents.CUSTOM_NAME, Component.literal(String.valueOf(i)));

                    AEItemKey key = AEItemKey.of(paper);
                    byte[] serialized = EnderDiskInventory.serializeItemStackToBytes(key.toStack(1));

                    int hash = java.util.Arrays.hashCode(serialized);
                    if (!seenHashes.add(hash)) {
                        sendFeedback(server, playerId,
                                Component.translatable("commands.enderdrives.stress.duplicate", i));
                        duplicates++;
                    }

                    EnderDBManager.saveItem(scopePrefix, frequency, serialized, 1);
                }

                // Yield between bounded batches so multiple diagnostics can share
                // the worker pool without ever consuming the server tick thread.
                Thread.yield();
            }

            long insertTime = System.currentTimeMillis() - insertStart;
            int typeCount = EnderDBManager.getTypeCount(scopePrefix, frequency);

            sendFeedback(server, playerId, Component.translatable("commands.enderdrives.stress.complete"));
            sendFeedback(server, playerId,
                    Component.translatable("commands.enderdrives.stress.inserted", amount, insertTime));
            sendFeedback(server, playerId,
                    Component.translatable("commands.enderdrives.stress.unique_types", typeCount));
            sendFeedback(server, playerId,
                    Component.translatable("commands.enderdrives.stress.duplicates", duplicates));
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    private static void sendFeedback(MinecraftServer server, UUID playerId, Component message) {
        if (!isCurrentServer(server)) return;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) player.sendSystemMessage(message);
        });
    }

    private static boolean isCurrentServer(MinecraftServer server) {
        return ServerLifecycleHooks.getCurrentServer() == server && server.isRunning();
    }
}
