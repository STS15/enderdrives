package com.sts15.enderdrives.items;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.items.contents.CellConfig;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.ConfigInventory;
import appeng.util.Platform;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.integration.FTBTeamsCompat;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.screen.EnderDiskFrequencyScreen;
import com.sts15.enderdrives.screen.FrequencyScope;
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
import java.util.UUID;
import java.util.function.Supplier;

public class EnderDiskItem extends Item implements ICellWorkbenchItem, IMenuItem {

    private static final String FREQ_KEY = "ender_freq";
    private static final String SCOPE_KEY = "ender_scope";
    private static final String OWNER_KEY = "ender_owner";
    private static final String TEAM_KEY = "ender_team";
    private static final String TEAM_NAME_KEY = "ender_team_name";
    private static final String TRANSFER_MODE_KEY = "ender_transfer_mode";
    private final Supplier<Integer> typeLimit;

    public EnderDiskItem(Properties props, Supplier<Integer> typeLimit) {
        super(props.stacksTo(1));
        this.typeLimit = typeLimit;
    }

    public int getTypeLimit() {
        return typeLimit.get();
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context, @NotNull List<Component> lines, @NotNull TooltipFlag advancedTooltips) {
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

        NetworkHandler.requestDiskTypeCount(scopePrefix, freq, getTypeLimit());
        DiskTypeInfo info = ClientDiskCache.get(key);
        int typeCount = info.typeCount();
        int typeLimit = info.typeLimit();
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
        if (isDisabled(itemStack)) {
            if (level.isClientSide) {
                player.displayClientMessage(Component.literal("§cThis EnderDisk is disabled on the server."), true);
            }
            return InteractionResultHolder.fail(itemStack);
        }
        if (level.isClientSide) {
            int freq = getFrequency(itemStack);
            FrequencyScope scope = getScope(itemStack);
            int transferMode = getTransferMode(itemStack);
            EnderDiskFrequencyScreen.open(freq, scope, transferMode);
        } else if (player instanceof ServerPlayer serverPlayer) {
            resolveAndCacheTeamInfo(itemStack, serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(itemStack, level.isClientSide());
    }

    public boolean isDisabled(ItemStack stack) {
        int index = getDriveIndex(this);
        return index >= 0 && com.sts15.enderdrives.client.ClientConfigCache.isDriveDisabled(index);
    }

    public static int getDriveIndex(Item item) {
        if (item == ItemInit.ENDER_DISK_1K.get()) return 0;
        if (item == ItemInit.ENDER_DISK_4K.get()) return 1;
        if (item == ItemInit.ENDER_DISK_16K.get()) return 2;
        if (item == ItemInit.ENDER_DISK_64K.get()) return 3;
        if (item == ItemInit.ENDER_DISK_256K.get()) return 4;
        if (item == ItemInit.ENDER_DISK_creative.get()) return 5;
        if (item == ItemInit.TAPE_DISK.get()) return 6;
        return -1;
    }

    public static void resolveAndCacheTeamInfo(ItemStack stack, ServerPlayer player) {
        if (!ModList.get().isLoaded("ftbteams")) return;
        try {
            FTBTeamsCompat.updateTeamInfo(stack, player);
        } catch (Throwable ignored) {}
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
