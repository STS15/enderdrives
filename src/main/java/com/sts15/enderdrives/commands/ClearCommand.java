package com.sts15.enderdrives.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.db.EnderFluidDBManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * /enderdrives clear
 */
public final class ClearCommand {

    private static final long CONFIRMATION_TIMEOUT_NANOS = 30_000_000_000L;
    private static final Map<UUID, PendingClear> pendingClearRequests = new HashMap<>();

    private ClearCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("clear")
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
                                    if (!CommandUtils.validateFrequency(frequency, source)) return 0;

                                    String type = StringArgumentType.getString(ctx, "type");
                                    Optional<String> scopePrefix = CommandUtils.resolveScopePrefix(
                                            source,
                                            player,
                                            type,
                                            "commands.enderdrives.clear.no_permission"
                                    );
                                    if (scopePrefix.isEmpty()) return 0;

                                    UUID playerId = player.getUUID();
                                    String key = playerId + ":" + type.toLowerCase() + ":" + frequency;
                                    MinecraftServer server = player.level().getServer();
                                    long now = System.nanoTime();
                                    PendingClear pending = pendingClearRequests.get(playerId);
                                    if (pending == null
                                            || pending.server() != server
                                            || now > pending.expiresAtNanos()
                                            || !key.equals(pending.key())) {
                                        pendingClearRequests.put(playerId,
                                                new PendingClear(server, key, now + CONFIRMATION_TIMEOUT_NANOS));
                                        source.sendSuccess(() -> Component.translatable(
                                                "commands.enderdrives.clear.confirm", frequency, type
                                        ), false);
                                        return 1;
                                    }

                                    pendingClearRequests.remove(playerId);
                                    EnderDBManager.clearFrequency(scopePrefix.get(), frequency);
                                    EnderFluidDBManager.clearFrequency(scopePrefix.get(), frequency);
                                    EnderDBManager.commitDatabase();
                                    EnderFluidDBManager.commitDatabase();

                                    source.sendSuccess(() -> Component.translatable(
                                            "commands.enderdrives.clear.success", frequency, type
                                    ), true);
                                    return 1;
                                })
                        )
                );
    }

    public static void clearPendingRequest(UUID playerId) {
        pendingClearRequests.remove(playerId);
    }

    public static void clearPendingRequests() {
        pendingClearRequests.clear();
    }

    private record PendingClear(MinecraftServer server, String key, long expiresAtNanos) {}
}
