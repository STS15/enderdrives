package com.sts15.enderdrives.commands;

import appeng.api.stacks.AEItemKey;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * /enderdrives autobenchmark
 */
public final class AutoBenchmarkCommand {

    private static final long CONFIRMATION_TIMEOUT_NANOS = 30_000_000_000L;
    private static final Map<UUID, PendingBenchmark> pendingBenchmarkRequests = new HashMap<>();
    private static final ExecutorService BENCHMARK_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "EnderDB-autobenchmark");
        thread.setDaemon(true);
        return thread;
    });

    private AutoBenchmarkCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("autobenchmark")
                .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                        .executes(ctx -> {
                            int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                            CommandSourceStack source = ctx.getSource();
                            if (!CommandUtils.validateFrequency(frequency, source)) return 0;
                            ServerPlayer player = source.getPlayerOrException();
                            UUID playerId = player.getUUID();
                            MinecraftServer server = player.level().getServer();
                            long now = System.nanoTime();
                            PendingBenchmark pending = pendingBenchmarkRequests.get(playerId);

                            if (pending == null
                                    || pending.server() != server
                                    || now > pending.expiresAtNanos()) {
                                pendingBenchmarkRequests.put(playerId,
                                        new PendingBenchmark(server, frequency, now + CONFIRMATION_TIMEOUT_NANOS));
                                source.sendSuccess(() -> Component.translatable(
                                        "commands.enderdrives.autobench.pending", frequency, frequency
                                ), false);
                                return 1;
                            }

                            int confirmedFrequency = pending.frequency();
                            if (confirmedFrequency != frequency) {
                                source.sendFailure(Component.translatable("commands.enderdrives.autobench.freq_mismatch"));
                                return 0;
                            }

                            String scopePrefix = "player_" + player.getUUID();
                            BenchmarkSettings settings = new BenchmarkSettings(
                                    serverConfig.AUTO_BENCHMARK_STEP.get(),
                                    serverConfig.AUTO_BENCHMARK_MAX_TYPES.get(),
                                    serverConfig.AUTO_BENCHMARK_MIN_TPS.get(),
                                    serverConfig.AUTO_BENCHMARK_INITIAL_SIZE.get(),
                                    serverConfig.AUTO_BENCHMARK_MS_SLEEP.get());

                            pendingBenchmarkRequests.remove(playerId);
                            CompletableFuture.runAsync(
                                    () -> runBenchmark(server, playerId, scopePrefix, frequency, settings),
                                    BENCHMARK_EXECUTOR);
                            return 1;
                        }));
    }

    public static void clearPendingRequest(UUID playerId) {
        pendingBenchmarkRequests.remove(playerId);
    }

    public static void clearPendingRequests() {
        pendingBenchmarkRequests.clear();
    }

    private static void runBenchmark(
            MinecraftServer server,
            UUID playerId,
            String scopePrefix,
            int frequency,
            BenchmarkSettings settings) {
        try {
            sendFeedback(server, playerId,
                    Component.translatable("commands.enderdrives.autobench.waiting_ae2_terminal"));
            if (!waitForAe2Terminal(server, playerId)) {
                sendFeedback(server, playerId,
                        Component.translatable("commands.enderdrives.autobench.cancel.no_ae2_terminal"));
                return;
            }

            int bestSize = 0;
            int currentSize = settings.initialSize();
            boolean canceled = false;

            while (currentSize <= settings.maxSize()) {
                if (!isCurrentServer(server)) {
                    canceled = true;
                    break;
                }
                PlayerState state = queryPlayerState(server, playerId);
                if (!state.connected()) {
                    sendFeedback(server, playerId,
                            Component.translatable("commands.enderdrives.autobench.cancel.offline"));
                    canceled = true;
                    break;
                }

                if (!state.terminalOpen()) {
                    sendFeedback(server, playerId,
                            Component.translatable("commands.enderdrives.autobench.waiting_ae2_terminal"));
                    if (!waitForAe2Terminal(server, playerId)) {
                        sendFeedback(server, playerId,
                                Component.translatable("commands.enderdrives.autobench.cancel.no_ae2_terminal"));
                        canceled = true;
                        break;
                    }
                }

                EnderDBManager.clearFrequency(scopePrefix, frequency);
                sendFeedback(server, playerId,
                        Component.translatable("commands.enderdrives.autobench.starting", currentSize));

                long insertStart = System.currentTimeMillis();
                for (int i = 1; i <= currentSize; i++) {
                    if ((i & 4095) == 0 && !isCurrentServer(server)) {
                        canceled = true;
                        break;
                    }
                    ItemStack paper = new ItemStack(Items.PAPER);
                    paper.set(DataComponents.CUSTOM_NAME, Component.literal(String.valueOf(i)));
                    AEItemKey key = AEItemKey.of(paper);
                    byte[] serialized = EnderDiskInventory.serializeItemStackToBytes(key.toStack(1));
                    EnderDBManager.saveItem(scopePrefix, frequency, serialized, 1);
                }
                if (canceled) break;
                long insertEnd = System.currentTimeMillis();
                Thread.sleep(settings.sleepMillis());

                double avgTick = callOnServerThread(server, () -> {
                    long[] tickTimes = server.getTickTime(Level.OVERWORLD);
                    return Arrays.stream(tickTimes).average().orElse(0) / 1_000_000.0;
                });
                double tps = Math.min(1000.0 / avgTick, 20.0);

                long queryStart = System.currentTimeMillis();
                int typeCount = EnderDBManager.getTypeCount(scopePrefix, frequency);
                long queryEnd = System.currentTimeMillis();
                long usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        / (1024 * 1024);

                sendFeedback(server, playerId, Component.translatable(
                        "commands.enderdrives.autobench.loop_summary",
                        currentSize,
                        (double) (insertEnd - insertStart),
                        (double) (queryEnd - queryStart),
                        typeCount,
                        usedMem,
                        String.format("%.2f", tps),
                        String.format("%.2f", avgTick)));

                if (tps >= settings.minSafeTps()) {
                    bestSize = currentSize;
                    currentSize += settings.step();
                } else {
                    sendFeedback(server, playerId,
                            Component.translatable("commands.enderdrives.autobench.tps_drop", settings.minSafeTps()));
                    break;
                }
            }

            if (!canceled) {
                sendFeedback(server, playerId,
                        Component.translatable("commands.enderdrives.autobench.best", bestSize));
            }

            if (isCurrentServer(server)) {
                EnderDBManager.clearFrequency(scopePrefix, frequency);
                EnderDBManager.commitDatabase();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean hasAnyGuiOpen(ServerPlayer player) {
        return player.containerMenu != player.inventoryMenu;
    }

    private static boolean waitForAe2Terminal(MinecraftServer server, UUID playerId)
            throws InterruptedException, ExecutionException, TimeoutException {
        final int maxWaitMs = 15000;
        final int stepMs = 250;
        int waited = 0;
        while (waited < maxWaitMs) {
            if (!isCurrentServer(server)) return false;
            PlayerState state = queryPlayerState(server, playerId);
            if (!state.connected()) return false;
            if (state.terminalOpen()) return true;
            Thread.sleep(stepMs);
            waited += stepMs;
        }
        return false;
    }

    private static PlayerState queryPlayerState(MinecraftServer server, UUID playerId)
            throws InterruptedException, ExecutionException, TimeoutException {
        return callOnServerThread(server, () -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            boolean connected = player != null
                    && !player.isRemoved()
                    && player.isAlive()
                    && player.connection != null
                    && player.connection.getConnection().isConnected();
            return new PlayerState(connected, connected && isAe2TerminalGui(player));
        });
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

    private static <T> T callOnServerThread(MinecraftServer server, Supplier<T> action)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (server.isSameThread()) return action.get();

        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static boolean isAe2TerminalGui(ServerPlayer player) {
        if (!hasAnyGuiOpen(player)) return false;
        MenuType<?> type = player.containerMenu.getType();
        Identifier key = BuiltInRegistries.MENU.getKey(type);

        // Primary: match AE2 registry ids that clearly denote terminals
        if (key != null && "ae2".equals(key.getNamespace())) {
            String path = key.getPath();
            if (path.contains("terminal")) return true; // catches terminal, crafting_terminal, wireless/crafting variants
        }

        // Fallback: match by class name in case registry key lookup fails
        String className = type.getClass().getSimpleName().toLowerCase();
        return className.contains("terminal");
    }

    private record PlayerState(boolean connected, boolean terminalOpen) {}

    private record BenchmarkSettings(
            int step,
            int maxSize,
            double minSafeTps,
            int initialSize,
            int sleepMillis) {}

    private record PendingBenchmark(
            MinecraftServer server,
            int frequency,
            long expiresAtNanos) {}
}
