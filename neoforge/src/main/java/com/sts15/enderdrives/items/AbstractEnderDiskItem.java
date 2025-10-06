package com.sts15.enderdrives.items;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableItem;
import appeng.core.localization.PlayerMessages;
import appeng.items.contents.CellConfig;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.recipes.game.StorageCellDisassemblyRecipe;
import appeng.util.ConfigInventory;
import appeng.util.InteractionUtil;
import appeng.util.Platform;
import com.sts15.enderdrives.client.ClientConfigCache;
import com.sts15.enderdrives.integration.FTBTeamsCompat;
import com.sts15.enderdrives.screen.EnderDiskFrequencyScreen;
import com.sts15.enderdrives.screen.FrequencyScope;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public abstract class AbstractEnderDiskItem extends Item implements ICellWorkbenchItem, IMenuItem {

    public static final String FREQ_KEY           = "ender_freq";
    public static final String SCOPE_KEY          = "ender_scope";
    public static final String OWNER_KEY          = "ender_owner";
    public static final String TEAM_KEY           = "ender_team";
    public static final String TEAM_NAME_KEY      = "ender_team_name";
    public static final String TRANSFER_MODE_KEY  = "ender_transfer_mode";
    public Supplier<Integer> typeLimit;
    private final AEKeyType aeKeyType;
    private final String disabledMessage;

    public AbstractEnderDiskItem(Properties properties, Supplier<Integer> typeLimit, AEKeyType aeKeyType, String disabledMessage) {
        super(properties.stacksTo(1));
        this.typeLimit = typeLimit;
        this.aeKeyType = aeKeyType;
        this.disabledMessage = disabledMessage;
    }

    public int getTypeLimit() {
        return typeLimit.get();
    }

    // ====== Team metadata (same helpers as item disk) ================================================================

    public static void setTeamInfo(ItemStack stack, String teamId, String teamName) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
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
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putInt(FREQ_KEY, freq);
            return CustomData.of(tag);
        });
    }

    public static FrequencyScope getScope(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return FrequencyScope.getDefault(); // default from config
        CompoundTag tag = data.copyTag();
        return FrequencyScope.fromId(tag.getInt(SCOPE_KEY));
    }

    public static void setScope(ItemStack stack, FrequencyScope scope) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putInt(SCOPE_KEY, scope.id);
            return CustomData.of(tag);
        });
    }

// ====== Owner / team ids =========================================================================================

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
        return CellConfig.create(Set.of(aeKeyType), stack);
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
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context, @NotNull List<Component> lines, @NotNull TooltipFlag advancedTooltips) {
        if (Platform.isClient()) {
            if (isDisabled(stack)) {
                lines.add(Component.translatable("tooltip.enderdrives.disabled"));
                return;
            }
            this.addCellInformationToTooltip(stack, lines);
        }
    }

    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {}

    public void addCellInformationToTooltip(int typeLimit, int typeCount, List<Component> lines, ItemStack stack, int freq) {
        int percentFull = (typeLimit == 0) ? 0 : (typeCount * 100 / typeLimit);

        int limitColor = 0x866dfc;
        int usageColor;
        if (typeCount >= typeLimit) {
            usageColor = 0xFF5555;
        } else if (percentFull >= 75) {
            usageColor = 0xFFAA00;
        } else {
            usageColor = 0x55FF55;
        }
        lines.add(Component.translatable("tooltip.enderdrives.types",
                Component.literal(String.valueOf(typeCount)).withStyle(style -> style.withColor(usageColor)),
                Component.literal(String.valueOf(typeLimit)).withStyle(style -> style.withColor(limitColor))
        ));
        lines.add(Component.translatable("tooltip.enderdrives.frequency", freq)
                .withStyle(style -> style.withColor(0xFFFF55)));
        int mode = getTransferMode(stack);
        String modeKey = switch (mode) {
            case 1 -> "tooltip.enderdrives.mode.input";
            case 2 -> "tooltip.enderdrives.mode.output";
            default -> "tooltip.enderdrives.mode.bidirectional";
        };
        lines.add(Component.translatable(modeKey));
        FrequencyScope scope = getScope(stack);
        Player player = Minecraft.getInstance().player;
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
        var config = CellConfig.create(Set.of(aeKeyType), stack);
        int partitionCount = config.keySet().size();
        if (partitionCount > 0) {
            String plural = (partitionCount == 1) ? "" : "s";
            lines.add(Component.translatable("tooltip.enderdrives.partitioned", partitionCount, plural));
        }
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, Player player, @NotNull InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown() && InteractionUtil.isInAlternateUseMode(player)) {
            if (level.isClientSide) {
                return InteractionResultHolder.consume(stack);
            }

            if (!InteractionUtil.isInAlternateUseMode(player)) {
                return InteractionResultHolder.fail(stack);
            }

            var parts = StorageCellDisassemblyRecipe.getDisassemblyResult(level, stack.getItem());
            if (parts.isEmpty()) {
                return InteractionResultHolder.fail(stack);
            }

            var inv = player.getInventory();
            if (inv.getSelected() != stack) {
                return InteractionResultHolder.fail(stack);
            }
            // Enderdrive items are not stored in the items data so disassembly with items on frequency is not a problem
//            boolean isEnderDrive = stack.getItem() instanceof EnderDiskItem;
//            var cell = StorageCells.getCellInventory(stack, null);
//            if (cell != null && !isEnderDrive && !cell.getAvailableStacks().isEmpty()) {
//                player.displayClientMessage(PlayerMessages.OnlyEmptyCellsCanBeDisassembled.text(), true);
//                return InteractionResultHolder.fail(stack);
//            }

            inv.setItem(inv.selected, ItemStack.EMPTY);
            for (ItemStack part : parts) {
                inv.placeItemBackInInventory(part.copy());
            }

            if (stack.getItem() instanceof IUpgradeableItem upg) {
                IUpgradeInventory upgInv = upg.getUpgrades(stack);
                upgInv.forEach(inv::placeItemBackInInventory);
            }

            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (isDisabled(stack)) {
            if (level.isClientSide) {
                player.displayClientMessage(Component.translatable(disabledMessage), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide) {
            int freq = getFrequency(stack);
            FrequencyScope scope = getScope(stack);
            int transferMode = getTransferMode(stack);
            EnderDiskFrequencyScreen.open(freq, scope, transferMode);
        } else if (player instanceof ServerPlayer sp) {
            resolveAndCacheTeamInfo(stack, sp);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    public boolean isDisabled(ItemStack stack) {
        int index = getDriveIndex(this);
        return index >= 0 && ClientConfigCache.isDriveDisabled(index);
    }

    /**
     * Map this item to a “drive index” for client config toggles/tinting.
     * Adjust these to your actual registry objects.
     */
    public static int getDriveIndex(Item item) {
        if (item == ItemInit.ENDER_DISK_1K.get()) return 0;
        if (item == ItemInit.ENDER_DISK_4K.get()) return 1;
        if (item == ItemInit.ENDER_DISK_16K.get()) return 2;
        if (item == ItemInit.ENDER_DISK_64K.get()) return 3;
        if (item == ItemInit.ENDER_DISK_256K.get()) return 4;
        if (item == ItemInit.ENDER_DISK_creative.get()) return 5;
        if (item == ItemInit.TAPE_DISK.get()) return 6;
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
