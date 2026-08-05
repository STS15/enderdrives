package com.sts15.enderdrives.commands;

import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.integration.FTBTeamsCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.fml.ModList;

import java.util.Locale;
import java.util.Optional;

/**
 * Shared helpers for command handling.
 */
public final class CommandUtils {

    private CommandUtils() {
    }

    public static boolean validateFrequency(int freq, CommandSourceStack source) {
        int min = serverConfig.FREQ_MIN.get();
        int max = serverConfig.FREQ_MAX.get();
        if (freq < min || freq > max) {
            source.sendFailure(Component.translatable("commands.enderdrives.freq.invalid", min, max));
            return false;
        }
        return true;
    }

    /**
     * Resolves scope type to prefix while performing permission checks and emitting user feedback.
     */
    public static Optional<String> resolveScopePrefix(CommandSourceStack source, ServerPlayer player, String type, String globalPermissionMessage) {
        String normalized = type.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "private" -> Optional.of("player_" + player.getUUID());
            case "team" -> {
                if (!ModList.get().isLoaded("ftbteams")) {
                    source.sendFailure(Component.translatable("commands.enderdrives.team.unavailable"));
                    yield Optional.empty();
                }
                try {
                    Optional<String> teamId = FTBTeamsCompat.getTeamId(player);
                    if (teamId.isEmpty()) {
                        source.sendFailure(Component.translatable("commands.enderdrives.team.none"));
                    }
                    yield teamId.map(id -> "team_" + id);
                } catch (RuntimeException exception) {
                    source.sendFailure(Component.translatable("commands.enderdrives.team.unavailable"));
                    yield Optional.empty();
                }
            }
            case "global" -> {
                if (!source.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
                    source.sendFailure(Component.translatable(globalPermissionMessage));
                    yield Optional.empty();
                }
                yield Optional.of("global");
            }
            default -> {
                source.sendFailure(Component.translatable("commands.enderdrives.scope.invalid"));
                yield Optional.empty();
            }
        };
    }
}
