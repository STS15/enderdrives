package com.sts15.enderdrives.items;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.items.contents.CellConfig;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.ConfigInventory;
import appeng.util.Platform;
import com.sts15.enderdrives.client.EnderDiskTooltipComponent;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.screen.EnderDiskFrequencyScreen;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class EnderDiskItem extends Item implements ICellWorkbenchItem, IMenuItem {

    private static final String FREQ_KEY = "ender_freq";
    private static final String SCOPE_KEY = "ender_scope";
    private static final String OWNER_KEY = "ender_owner";
    private static final String TEAM_KEY = "ender_team";
    private static final String TEAM_NAME_KEY = "ender_team_name";
    private static final String TRANSFER_MODE_KEY = "ender_transfer_mode";
    private final int typeLimit;

    public EnderDiskItem(Properties props, int typeLimit) {
        super(props.stacksTo(1));
        this.typeLimit = typeLimit;
    }

    public int getTypeLimit() {
        return this.typeLimit;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context, @NotNull List<Component> lines, @NotNull TooltipFlag advancedTooltips) {
        if (Platform.isClient()) {
            addCellInformationToTooltip(stack, lines);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {
        Player player = Minecraft.getInstance().player;
        int freq = getFrequency(stack);
        int scope = getScope(stack);
        String scopePrefix = getSafeScopePrefix(stack);
        String key = scopePrefix + "|" + freq;
        NetworkHandler.requestDiskTypeCount(scopePrefix, freq, getTypeLimit());
        DiskTypeInfo info = ClientDiskCache.get(key);
        int typeCount = info.typeCount();
        int typeLimit = info.typeLimit();
        int percentFull = (int) ((typeLimit == 0) ? 0 : ((typeCount * 100.0) / typeLimit));
        String color;
        if (typeCount >= typeLimit) {
            color = "§e"; // Red
        } else if (percentFull >= 75) {
            color = "§e"; // Yellow
        } else {
            color = "§a"; // Green
        }
        lines.add(Component.literal(color + typeCount + "§7 of §9" + typeLimit + "§7 Types"));
        lines.add(Component.literal("§7Frequency: §e" + freq));
        int mode = getTransferMode(stack);
        String modeText = switch (mode) {
            case 1 -> "§7Mode: §9Input Only";
            case 2 -> "§7Mode: §cOutput Only";
            default -> "§7Mode: §aBidirectional";
        };
        lines.add(Component.literal(modeText));
        String scopeName = switch (scope) {
            case 1 -> {
                UUID owner = getOwnerUUID(stack);
                String name = (player != null && owner != null && player.getUUID().equals(owner))
                        ? player.getName().getString()
                        : (owner != null ? owner.toString() : "Unknown");
                yield "§7Private: §e" + name;
            }
            case 2 -> {
                String teamName = getStoredTeamName(stack);
                yield "§7Team: §e" + (teamName != null ? teamName : "Unknown");
            }
            default -> "§7Global";
        };
        lines.add(Component.literal(scopeName));
    }

    public static void setTeamInfo(ItemStack stack, String teamId, String teamName) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putString(TEAM_KEY, teamId);
            tag.putString(TEAM_NAME_KEY, teamName);
            return CustomData.of(tag);
        });
    }

    public static void updateTeamInfo(ItemStack stack, Player player) {
        if (player == null || player.level().isClientSide) return;
        UUID playerUUID = player.getUUID();
        TeamManager manager = FTBTeamsAPI.api().getManager();
        if (manager == null) return;
        manager.getTeamForPlayerID(playerUUID).ifPresent(team -> {
            UUID ownerUUID = team.getOwner();
            String displayName = team.getProperty(TeamProperties.DISPLAY_NAME);
            String teamId = ownerUUID.toString();
            setTeamId(stack, teamId);
            setOwnerUUID(stack, ownerUUID);
            stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
                CompoundTag tag = oldData.copyTag();
                tag.putString(TEAM_NAME_KEY, displayName != null ? displayName : "Unknown");
                return CustomData.of(tag);
            });
        });
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        String scopePrefix = getSafeScopePrefix(stack);
        int freq = getFrequency(stack);
        var topItems = ClientDiskCache.getTopStacks(scopePrefix, freq);
        if (topItems.isEmpty()) return Optional.empty();
        return Optional.of(new EnderDiskTooltipComponent(topItems));
    }

    @Nullable
    public static String getStoredTeamName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        return tag.contains(TEAM_NAME_KEY) ? tag.getString(TEAM_NAME_KEY) : null;
    }

    public static String getSafeScopePrefix(ItemStack stack) {
        int scope = getScope(stack);
        return switch (scope) {
            case 1 -> {
                UUID owner = getOwnerUUID(stack);
                yield (owner != null) ? "player_" + owner : "player_unknown";
            }
            case 2 -> {
                String teamId = getStoredTeamId(stack);
                yield (teamId != null && !teamId.isEmpty()) ? "team_" + teamId : "global";
            }
            default -> "global";
        };
    }

    public int getTypeLimit(ItemStack stack) {
        return this.typeLimit;
    }

    public static int getFrequency(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.getInt(FREQ_KEY);
    }

    public static void setFrequency(ItemStack stack, int freq) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putInt(FREQ_KEY, freq);
            return CustomData.of(tag);
        });
    }

    public static int getScope(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.getInt(SCOPE_KEY);
    }

    public static void setScope(ItemStack stack, int scope) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putInt(SCOPE_KEY, scope);
            return CustomData.of(tag);
        });
    }

    public static void setOwnerUUID(ItemStack stack, UUID uuid) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putString(OWNER_KEY, uuid.toString());
            return CustomData.of(tag);
        });
    }

    @Nullable
    public static UUID getOwnerUUID(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(OWNER_KEY)) return null;
        try {
            return UUID.fromString(tag.getString(OWNER_KEY));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void setTeamId(ItemStack stack, String teamId) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putString(TEAM_KEY, teamId);
            return CustomData.of(tag);
        });
    }

    @Nullable
    public static String getStoredTeamId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        return tag.contains(TEAM_KEY) ? tag.getString(TEAM_KEY) : null;
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack stack) {
        return CellConfig.create(stack);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack stack) {
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public void setFuzzyMode(ItemStack stack, FuzzyMode mode) {
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (level.isClientSide) {
            int freq = getFrequency(itemStack);
            int scope = getScope(itemStack);
            int transferMode = getTransferMode(itemStack);
            EnderDiskFrequencyScreen.open(freq, scope, transferMode);
        } else if (player instanceof ServerPlayer serverPlayer) {
            resolveAndCacheTeamInfo(itemStack, serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }


    public static void resolveAndCacheTeamInfo(ItemStack stack, ServerPlayer serverPlayer) {
        if (!ModList.get().isLoaded("ftbteams")) return;
        var manager = FTBTeamsAPI.api().getManager();
        if (manager == null) return;
        manager.getTeamForPlayer(serverPlayer).ifPresent(team -> {
            UUID ownerUUID = team.getOwner();
            String displayName = team.getProperty(TeamProperties.DISPLAY_NAME);
            String teamId = ownerUUID.toString();
            setTeamInfo(stack, teamId, displayName != null ? displayName : "Unknown");
            setOwnerUUID(stack, ownerUUID);
        });
    }

    @Override
    public @Nullable ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator, @Nullable BlockHitResult hitResult) {
        return null;
    }

    public static int getTransferMode(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0; // Default to bidirectional
        CompoundTag tag = data.copyTag();
        return tag.getInt(TRANSFER_MODE_KEY); // defaults to 0 if missing
    }

    public static void setTransferMode(ItemStack stack, int mode) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putInt(TRANSFER_MODE_KEY, mode);
            return CustomData.of(tag);
        });
    }


}
