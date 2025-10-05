package com.sts15.enderdrives.items;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.items.contents.CellConfig;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.ConfigInventory;
import appeng.util.Platform;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.ClientFluidDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import com.sts15.enderdrives.integration.FTBTeamsCompat;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.screen.EnderDiskFrequencyScreen;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
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
import java.util.Set;
import java.util.UUID;

/**
 * Fluid-only EnderDrive disk item.
 * - Uses AEKeyType.fluids() for partitioning/workbench config
 * - Shares frequency/scope/owner/team/transfer-mode semantics with item disks
 */
public class EnderFluidDiskItem extends Item implements ICellWorkbenchItem, IMenuItem {

    private static final String FREQ_KEY           = "ender_freq";
    private static final String SCOPE_KEY          = "ender_scope";
    private static final String OWNER_KEY          = "ender_owner";
    private static final String TEAM_KEY           = "ender_team";
    private static final String TEAM_NAME_KEY      = "ender_team_name";
    private static final String TRANSFER_MODE_KEY  = "ender_transfer_mode";

    private final java.util.function.Supplier<Integer> typeLimit;

    public EnderFluidDiskItem(Properties props, java.util.function.Supplier<Integer> typeLimit) {
        super(props.stacksTo(1));
        this.typeLimit = typeLimit;
    }

    public int getTypeLimit() {
        return typeLimit.get();
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack,
                                @NotNull TooltipContext context,
                                @NotNull List<Component> lines,
                                @NotNull TooltipFlag advancedTooltips) {
        if (Platform.isClient()) {
            if (isDisabled(stack)) {
                lines.add(Component.literal("§cThis item is disabled on the server."));
                return;
            }
            addCellInformationToTooltip(stack, lines);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {
        Player player = Minecraft.getInstance().player;
        int freq = getFrequency(stack);
        String scopePrefix = getSafeScopePrefix(stack);
        String key = scopePrefix + "|" + freq;

        // Ask server for fluid type count; client cache mirrors the item version.
        // Implement NetworkHandler.requestFluidDiskTypeCount on your side to reply with typeCount + limit.
        NetworkHandler.requestFluidDiskTypeCount(scopePrefix, freq, getTypeLimit());

        FluidDiskTypeInfo info = ClientFluidDiskCache.get(key);
        int typeCount = info.typeCount();
        int limit     = info.typeLimit();
        int percent   = (limit == 0) ? 0 : (typeCount * 100 / limit);

        int limitColor = 0x866dfc;
        int usageColor;
        if (typeCount >= limit) {
            usageColor = 0xFF5555;
        } else if (percent >= 75) {
            usageColor = 0xFFAA00;
        } else {
            usageColor = 0x55FF55;
        }

        // Reuse your existing translation keys (they’re generic)
        lines.add(Component.translatable("tooltip.enderdrives.fluid_types",
                Component.literal(String.valueOf(typeCount)).withStyle(s -> s.withColor(usageColor)),
                Component.literal(String.valueOf(limit)).withStyle(s -> s.withColor(limitColor))
        ));
        lines.add(Component.translatable("tooltip.enderdrives.frequency", freq)
                .withStyle(s -> s.withColor(0xFFFF55)));

        int mode = getTransferMode(stack);
        String modeKey = switch (mode) {
            case 1 -> "tooltip.enderdrives.mode.input";
            case 2 -> "tooltip.enderdrives.mode.output";
            default -> "tooltip.enderdrives.mode.bidirectional";
        };
        lines.add(Component.translatable(modeKey));

        FrequencyScope scope = getScope(stack);
        Component scopeLine = switch (scope) {
            case PERSONAL -> {
                UUID owner = getOwnerUUID(stack);
                String name = (player != null && owner != null && player.getUUID().equals(owner))
                        ? player.getName().getString()
                        : (owner != null ? owner.toString() : Component.translatable("tooltip.enderdrives.unknown").getString());
                yield Component.translatable("tooltip.enderdrives.scope.private", name);
            }
            case TEAM -> {
                String teamName = getStoredTeamName(stack);
                yield Component.translatable("tooltip.enderdrives.scope.team",
                        teamName != null ? teamName : Component.translatable("tooltip.enderdrives.unknown"));
            }
            default -> Component.translatable("tooltip.enderdrives.scope.global");
        };
        lines.add(scopeLine);

        // Partition count: fluids keytype
        var config = CellConfig.create(Set.of(AEKeyType.fluids()), stack);
        int partitionCount = config.keySet().size();
        if (partitionCount > 0) {
            String plural = (partitionCount == 1) ? "" : "s";
            lines.add(Component.translatable("tooltip.enderdrives.partitioned", partitionCount, plural));
        }
    }

    // ====== Team metadata (same helpers as item disk) ================================================================

    public static void setTeamInfo(ItemStack stack, String teamId, String teamName) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
            tag.putString(TEAM_KEY, teamId);
            tag.putString(TEAM_NAME_KEY, teamName);
            return CustomData.of(tag);
        });
    }

    public static void updateTeamInfo(ItemStack stack, Player player) {
        if (!ModList.get().isLoaded("ftbteams")) return;
        try {
            FTBTeamsCompat.updateTeamInfo(stack, player);
        } catch (Throwable ignored) {}
    }

    @Nullable
    public static String getStoredTeamName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        return tag.contains(TEAM_NAME_KEY) ? tag.getString(TEAM_NAME_KEY) : null;
    }

    // ====== Scope & frequency ========================================================================================

    public static String getSafeScopePrefix(ItemStack stack) {
        FrequencyScope scope = getScope(stack);
        return switch (scope) {
            case PERSONAL -> {
                UUID owner = getOwnerUUID(stack);
                yield (owner != null) ? "player_" + owner : "player_unknown";
            }
            case TEAM -> {
                String teamId = getStoredTeamId(stack);
                yield (teamId != null && !teamId.isEmpty()) ? "team_" + teamId : "global";
            }
            default -> "global";
        };
    }

    public int getTypeLimit(ItemStack stack) {
        return this.typeLimit.get();
    }

    public static int getFrequency(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.getInt(FREQ_KEY);
    }

    public static void setFrequency(ItemStack stack, int freq) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
            tag.putInt(FREQ_KEY, freq);
            return CustomData.of(tag);
        });
    }

    public static FrequencyScope getScope(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return FrequencyScope.getDefault();
        CompoundTag tag = data.copyTag();
        return FrequencyScope.fromId(tag.getInt(SCOPE_KEY));
    }

    public static void setScope(ItemStack stack, FrequencyScope scope) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
            tag.putInt(SCOPE_KEY, scope.id);
            return CustomData.of(tag);
        });
    }

    // ====== Owner / team ids =========================================================================================

    public static void setOwnerUUID(ItemStack stack, UUID uuid) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
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
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
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

    // ====== Cell / workbench config (FLUIDS) =========================================================================

    @Override
    public ConfigInventory getConfigInventory(ItemStack stack) {
        // FLUIDS, not items:
        return CellConfig.create(Set.of(AEKeyType.fluids()), stack);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack stack) {
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public void setFuzzyMode(ItemStack stack, FuzzyMode mode) {
        // no-op
    }

    // ====== Use & GUI ================================================================================================

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);

        if (isDisabled(itemStack)) {
            if (level.isClientSide) {
                player.displayClientMessage(Component.literal("§cThis EnderFluidDisk is disabled on the server."), true);
            }
            return InteractionResultHolder.fail(itemStack);
        }

        if (level.isClientSide) {
            int freq = getFrequency(itemStack);
            FrequencyScope scope = getScope(itemStack);
            int transferMode = getTransferMode(itemStack);
            // Reuse the same screen—it only edits freq/scope/transfer mode.
            EnderDiskFrequencyScreen.open(freq, scope, transferMode);
        } else if (player instanceof ServerPlayer sp) {
            resolveAndCacheTeamInfo(itemStack, sp);
        }
        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }

    public boolean isDisabled(ItemStack stack) {
        int index = getDriveIndex(this);
        return index >= 0 && com.sts15.enderdrives.client.ClientConfigCache.isDriveDisabled(index);
    }

    /**
     * Map this item to a “drive index” for client config toggles/tinting.
     * Adjust these to your actual registry objects.
     */
    public static int getDriveIndex(Item item) {
        if (item == ItemInit.ENDER_FLUID_DISK_1K.get())     return 7;
        if (item == ItemInit.ENDER_FLUID_DISK_4K.get())     return 8;
        if (item == ItemInit.ENDER_FLUID_DISK_16K.get())    return 9;
        if (item == ItemInit.ENDER_FLUID_DISK_64K.get())    return 10;
        if (item == ItemInit.ENDER_FLUID_DISK_256K.get())   return 11;
        if (item == ItemInit.ENDER_FLUID_DISK_creative.get()) return 12;
        return -1;
    }

    public static void resolveAndCacheTeamInfo(ItemStack stack, ServerPlayer player) {
        if (!ModList.get().isLoaded("ftbteams")) return;
        try {
            FTBTeamsCompat.updateTeamInfo(stack, player);
        } catch (Throwable ignored) {}
    }

    // ====== IMenuItem (not used; return null like the item disk) =====================================================

    @Override
    public @Nullable ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator, @Nullable BlockHitResult hit) {
        return null;
    }

    // ====== Transfer mode ============================================================================================

    /** 0 = bidirectional, 1 = input-only, 2 = output-only */
    public static int getTransferMode(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.getInt(TRANSFER_MODE_KEY);
    }

    public static void setTransferMode(ItemStack stack, int mode) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
            tag.putInt(TRANSFER_MODE_KEY, mode);
            return CustomData.of(tag);
        });
    }
}
