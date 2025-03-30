package com.sts15.enderdrives.integration;

import com.sts15.enderdrives.items.EnderDiskItem;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.UUID;

public class FTBTeamsCompat {

    public static void updateTeamInfo(ItemStack stack, ServerPlayer player) {
        if (stack == null || player == null) return;

        TeamManager manager = FTBTeamsAPI.api().getManager();
        if (manager == null) return;

        manager.getTeamForPlayer(player).ifPresent(team -> {
            UUID ownerUUID = team.getOwner();
            String displayName = team.getProperty(TeamProperties.DISPLAY_NAME);
            String teamId = ownerUUID.toString();
            EnderDiskItem.setTeamInfo(stack, teamId, displayName != null ? displayName : "Unknown");
            EnderDiskItem.setOwnerUUID(stack, ownerUUID);
        });
    }

    public static void updateTeamInfo(ItemStack stack, Player player) {
        if (stack == null || player == null || player.level().isClientSide) return;

        UUID playerUUID = player.getUUID();
        TeamManager manager = FTBTeamsAPI.api().getManager();
        if (manager == null) return;

        manager.getTeamForPlayerID(playerUUID).ifPresent(team -> {
            UUID ownerUUID = team.getOwner();
            String displayName = team.getProperty(TeamProperties.DISPLAY_NAME);
            String teamId = ownerUUID.toString();
            EnderDiskItem.setTeamId(stack, teamId);
            EnderDiskItem.setOwnerUUID(stack, ownerUUID);

            stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
                CompoundTag tag = oldData.copyTag();
                tag.putString("ender_team_name", displayName != null ? displayName : "Unknown");
                return CustomData.of(tag);
            });
        });
    }
}
