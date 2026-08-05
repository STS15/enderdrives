package com.sts15.enderdrives.items;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableItem;
import appeng.items.contents.CellConfig;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.recipes.game.StorageCellDisassemblyRecipe;
import appeng.util.ConfigInventory;
import appeng.util.InteractionUtil;
import appeng.util.Platform;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.integration.FTBTeamsCompat;
import com.sts15.enderdrives.screen.FrequencyScope;
import com.sts15.enderdrives.clientbridge.ClientHooks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
        String teamName = tag.getStringOr(TEAM_NAME_KEY, "");
        return teamName.isEmpty() ? null : teamName;
    }

    // ====== Scope & frequency ========================================================================================

    public static String getSafeScopePrefix(ItemStack stack) {
        FrequencyScope scope = getScope(stack);
        return switch (scope) {
            case PERSONAL -> {
                UUID owner = getOwnerUUID(stack);
                yield (owner != null) ? "player_" + owner : "player_unbound";
            }
            case TEAM -> {
                String teamId = getStoredTeamId(stack);
                yield (teamId != null && !teamId.isEmpty()) ? "team_" + teamId : "team_unknown";
            }
            default -> "global";
        };

    }

    public static boolean isScopeBound(ItemStack stack) {
        return switch (getScope(stack)) {
            case GLOBAL -> true;
            case PERSONAL -> getOwnerUUID(stack) != null;
            case TEAM -> ModList.get().isLoaded("ftbteams") && getStoredTeamId(stack) != null;
        };
    }

    public int getTypeLimit(ItemStack stack) {
        return this.typeLimit.get();
    }

    public static int getFrequency(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        CompoundTag tag = data.copyTag();
        return tag.getIntOr(FREQ_KEY, 0);
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
        return FrequencyScope.fromId(tag.getIntOr(SCOPE_KEY, FrequencyScope.getDefault().getId()));
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
            return UUID.fromString(tag.getStringOr(OWNER_KEY, ""));
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
        String teamId = tag.getStringOr(TEAM_KEY, "");
        return teamId.isEmpty() ? null : teamId;
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
    public void appendHoverText(
            @NotNull ItemStack stack,
            @NotNull TooltipContext context,
            @NotNull TooltipDisplay display,
            @NotNull Consumer<Component> builder,
            @NotNull TooltipFlag advancedTooltips
    ) {
        List<Component> lines = new ArrayList<>();
        if (Platform.isClient()) {
            if (isDisabled(stack)) {
                lines.add(Component.translatable("tooltip.enderdrives.disabled"));
            } else {
                this.addCellInformationToTooltip(stack, lines);
            }
        }
        lines.forEach(builder);
    }

    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {}

    public void addCellInformationToTooltip(int typeLimit, int typeCount, List<Component> lines, ItemStack stack, int freq) {
        int percentFull = (typeLimit == 0) ? 0 : (typeCount * 100 / typeLimit);

        int limitColor = 0x866dfc;
        int midColor = 0x00AAFF;
        int usageColor;
        if (typeCount <= 0) {
            usageColor = 0x55FF55;
        } else if (typeLimit > 0 && typeCount >= typeLimit) {
            usageColor = 0xFF5555;
        } else if (typeLimit > 0 && percentFull >= 75) {
            usageColor = 0xFFAA00;
        } else {
            usageColor = midColor;
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

        Component scopeLine = switch (scope) {
            case PERSONAL -> {
                UUID owner = getOwnerUUID(stack);

                String name = owner != null
                        ? ClientHooks.getOwnerDisplayName(owner)
                        : Component.translatable(
                        "tooltip.enderdrives.unknown"
                ).getString();

                yield Component.translatable(
                        "tooltip.enderdrives.scope.private",
                        name
                );
            }

            case TEAM -> {
                String teamName = getStoredTeamName(stack);

                yield Component.translatable(
                        "tooltip.enderdrives.scope.team",
                        teamName != null
                                ? teamName
                                : Component.translatable(
                                "tooltip.enderdrives.unknown"
                        )
                );
            }

            default -> Component.translatable(
                    "tooltip.enderdrives.scope.global"
            );
        };

        lines.add(scopeLine);
        var config = CellConfig.create(Set.of(aeKeyType), stack);
        int partitionCount = config.keySet().size();
        if (partitionCount > 0) {
            String plural = (partitionCount == 1) ? "" : "s";
            if (stack.getItem() instanceof EnderDiskItem) {
                lines.add(Component.translatable("tooltip.enderdrives.partitioned_item", partitionCount, plural));
            } else {
                lines.add(Component.translatable("tooltip.enderdrives.partitioned_fluid", partitionCount, plural));
            }

        }
    }

    @Override
    public InteractionResult use(@NotNull Level level, Player player, @NotNull InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown() && InteractionUtil.isInAlternateUseMode(player)) {
            if (level.isClientSide()) {
                return InteractionResult.CONSUME;
            }

            if (!InteractionUtil.isInAlternateUseMode(player)) {
                return InteractionResult.FAIL;
            }

            var parts = StorageCellDisassemblyRecipe.getDisassemblyResult((ServerLevel) level, stack.getItem());
            if (parts.isEmpty()) {
                return InteractionResult.FAIL;
            }

            var inv = player.getInventory();
            player.setItemInHand(hand, ItemStack.EMPTY);
            for (ItemStack part : parts) {
                inv.placeItemBackInInventory(part.copy());
            }

            if (stack.getItem() instanceof IUpgradeableItem upg) {
                IUpgradeInventory upgInv = upg.getUpgrades(stack);
                upgInv.forEach(inv::placeItemBackInInventory);
            }

            return InteractionResult.SUCCESS_SERVER;
        }

        if (isDisabled(stack)) {
            if (level.isClientSide()) {
                player.sendOverlayMessage(Component.translatable(disabledMessage));
            }
            return InteractionResult.FAIL;
        }

        if (level.isClientSide()) {
            FrequencyScope scope = getScope(stack);
            int transferMode = getTransferMode(stack);

            ClientHooks.openFrequencyScreen(
                    getFrequency(stack),
                    scope,
                    transferMode,
                    hand,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    ItemStack.hashItemAndComponents(stack)
            );
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    public boolean isDisabled(ItemStack stack) {
        int index = getDriveIndex(this);
        return switch (index) {
            case 0 -> !serverConfig.ENDER_DISK_1K_TOGGLE.get();
            case 1 -> !serverConfig.ENDER_DISK_4K_TOGGLE.get();
            case 2 -> !serverConfig.ENDER_DISK_16K_TOGGLE.get();
            case 3 -> !serverConfig.ENDER_DISK_64K_TOGGLE.get();
            case 4 -> !serverConfig.ENDER_DISK_256K_TOGGLE.get();
            case 5 -> !serverConfig.ENDER_DISK_CREATIVE_TOGGLE.get();
            case 7 -> !serverConfig.ENDER_FLUID_DISK_1K_TOGGLE.get();
            case 8 -> !serverConfig.ENDER_FLUID_DISK_4K_TOGGLE.get();
            case 9 -> !serverConfig.ENDER_FLUID_DISK_16K_TOGGLE.get();
            case 10 -> !serverConfig.ENDER_FLUID_DISK_64K_TOGGLE.get();
            case 11 -> !serverConfig.ENDER_FLUID_DISK_256K_TOGGLE.get();
            case 12 -> !serverConfig.ENDER_FLUID_DISK_CREATIVE_TOGGLE.get();
            default -> false;
        };
    }

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

    public static boolean resolveAndCacheTeamInfo(ItemStack stack, ServerPlayer player) {
        if (!ModList.get().isLoaded("ftbteams")) return false;
        try {
            return FTBTeamsCompat.updateTeamInfo(stack, player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clearTeamInfo(ItemStack stack) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.remove(TEAM_KEY);
            tag.remove(TEAM_NAME_KEY);
            return CustomData.of(tag);
        });
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
        return tag.getIntOr(TRANSFER_MODE_KEY, 0);
    }

    public static void setTransferMode(ItemStack stack, int mode) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, old -> {
            CompoundTag tag = old.copyTag();
            tag.putInt(TRANSFER_MODE_KEY, mode);
            return CustomData.of(tag);
        });
    }

}
