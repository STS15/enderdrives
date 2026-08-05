package com.sts15.enderdrives.integration;

import com.sts15.enderdrives.items.AbstractEnderDiskItem;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.db.EnderFluidDBManager;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class FTBTeamsCompat {

    public static Optional<String> getTeamId(ServerPlayer player) {
        TeamManager manager = FTBTeamsAPI.api().getManager();
        if (manager == null) return Optional.empty();
        var team = manager.getTeamForPlayer(player);
        if (team.isEmpty() || !migrateLegacyOwnerScope(team.get(), null)) return Optional.empty();
        return Optional.of(team.get().getTeamId().toString());
    }

    public static boolean updateTeamInfo(ItemStack stack, ServerPlayer player) {
        if (stack == null || player == null) return false;

        TeamManager manager = FTBTeamsAPI.api().getManager();
        if (manager == null) return false;

        var team = manager.getTeamForPlayer(player);
        if (team.isEmpty()) return false;

        Team resolvedTeam = team.get();
        String previousTeamId = AbstractEnderDiskItem.getStoredTeamId(stack);
        if (!migrateLegacyOwnerScope(resolvedTeam, previousTeamId)) return false;

        UUID ownerUUID = resolvedTeam.getOwner();
        String displayName = resolvedTeam.getProperty(TeamProperties.DISPLAY_NAME);
        String teamId = resolvedTeam.getTeamId().toString();
        AbstractEnderDiskItem.setTeamInfo(stack, teamId, displayName != null ? displayName : "Unknown");
        AbstractEnderDiskItem.setOwnerUUID(stack, ownerUUID);
        return true;
    }

    public static void updateTeamInfo(ItemStack stack, Player player) {
        if (stack == null || !(player instanceof ServerPlayer serverPlayer)) return;
        updateTeamInfo(stack, serverPlayer);
    }

    private static boolean migrateLegacyOwnerScope(Team team, String previousTeamId) {
        String stableId = team.getTeamId().toString();
        Set<String> legacyIds = new LinkedHashSet<>();
        legacyIds.add(team.getOwner().toString());

        if (previousTeamId != null && !previousTeamId.equals(stableId)) {
            try {
                UUID previousOwner = UUID.fromString(previousTeamId);
                if (team.getMembers().contains(previousOwner)) legacyIds.add(previousTeamId);
            } catch (IllegalArgumentException ignored) {
                // Stable IDs from a previous version are not legacy owner scopes.
            }
        }

        for (String legacyId : legacyIds) {
            if (legacyId.equals(stableId)) continue;
            String oldScope = "team_" + legacyId;
            String newScope = "team_" + stableId;
            boolean itemsMigrated = EnderDBManager.migrateScope(oldScope, newScope);
            boolean fluidsMigrated = EnderFluidDBManager.migrateScope(oldScope, newScope);
            if (!itemsMigrated || !fluidsMigrated) return false;
        }
        return true;
    }
}
