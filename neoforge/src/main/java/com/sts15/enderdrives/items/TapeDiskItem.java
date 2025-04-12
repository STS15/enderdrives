package com.sts15.enderdrives.items;

import appeng.api.config.FuzzyMode;
import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.items.contents.CellConfig;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.util.ConfigInventory;
import com.sts15.enderdrives.client.ClientTapeCache;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.network.packet.RequestTapeTypeCountPacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.sts15.enderdrives.db.TapeDBManager.getByteLimit;

public class TapeDiskItem extends Item implements ICellWorkbenchItem, IMenuItem {

    private static final String TAPE_ID_KEY = "tape_disk_id";
    private final Supplier<Integer> typeLimit;

    public TapeDiskItem(Properties props, Supplier<Integer> typeLimit) {
        super(props.stacksTo(1));
        this.typeLimit = typeLimit;
    }

    public static boolean isDisabled(ItemStack stack) {
        return !serverConfig.TAPE_DISK_TOGGLE.get();
    }

    public int getTypeLimit() {
        return typeLimit.get();
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context, @NotNull List<Component> lines, @NotNull TooltipFlag advancedTooltips) {
        if (isDisabled(stack)) {
            lines.add(Component.literal("§cThis item is disabled on the server."));
            return;
        }
        addCellInformationToTooltip(stack, lines);
    }

    @OnlyIn(Dist.CLIENT)
    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {
        UUID id = getTapeId(stack);
        int limitColor = 0x866dfc;
        int goodColor = 0x55FF55;
        int warnColor = 0xFFAA00;
        int fullColor = 0xFF5555;
        int labelColor = 0xAAAAAA;

        lines.add(Component.literal("Ideal for tools, armor, and NBT-heavy items.")
                .withStyle(s -> s.withColor(labelColor).withItalic(true)));

        if (id != null) {
            int typeCount = ClientTapeCache.getTypeCount(id);
            long byteCount = ClientTapeCache.getByteCount(id);
            int typeLimit = getTypeLimit(stack);
            long byteLimit = getByteLimit(id);
            int typePercent = (typeLimit == 0) ? 0 : (typeCount * 100 / typeLimit);
            int bytePercent = (byteLimit == 0) ? 0 : (int) (byteCount * 100 / byteLimit);
            int typeColor = (typePercent >= 99) ? fullColor : (typePercent >= 75 ? warnColor : goodColor);
            int byteColor = (bytePercent >= 99) ? fullColor : (bytePercent >= 75 ? warnColor : goodColor);

            lines.add(Component.translatable("tooltip.enderdrives.tape.bytes",
                    Component.literal(String.valueOf(byteCount)).withStyle(s -> s.withColor(byteColor)),
                    Component.literal(String.valueOf(byteLimit)).withStyle(s -> s.withColor(limitColor))
            ).withStyle(s -> s.withColor(labelColor)));

            lines.add(Component.translatable("tooltip.enderdrives.tape.types",
                    Component.literal(String.valueOf(typeCount)).withStyle(s -> s.withColor(typeColor)),
                    Component.literal(String.valueOf(typeLimit)).withStyle(s -> s.withColor(limitColor))
            ).withStyle(s -> s.withColor(labelColor)));

            lines.add(Component.literal("Tape ID: " + id.toString().substring(0, 8))
                    .withStyle(s -> s.withColor(labelColor)));

            NetworkHandler.sendToServer(id);
        } else {
            // Use configured values instead of "??"
            int typeLimit = getTypeLimit(stack);
            long byteLimit = serverConfig.TAPE_DISK_BYTE_LIMIT.get();

            int typeColor = 0x55FF55;
            int byteColor = 0x55FF55;

            lines.add(Component.translatable("tooltip.enderdrives.tape.bytes",
                    Component.literal("0").withStyle(s -> s.withColor(byteColor)),
                    Component.literal(String.valueOf(byteLimit)).withStyle(s -> s.withColor(limitColor))
            ).withStyle(s -> s.withColor(labelColor)));

            lines.add(Component.translatable("tooltip.enderdrives.tape.types",
                    Component.literal("0").withStyle(s -> s.withColor(typeColor)),
                    Component.literal(String.valueOf(typeLimit)).withStyle(s -> s.withColor(limitColor))
            ).withStyle(s -> s.withColor(labelColor)));
        }

    }


    @Nullable
    public static UUID getTapeId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(TAPE_ID_KEY)) return null;
        try {
            return UUID.fromString(tag.getString(TAPE_ID_KEY));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static UUID getOrCreateTapeId(ItemStack stack) {
        UUID id = getTapeId(stack);
        if (id != null) return id;

        UUID newId = UUID.randomUUID();
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, oldData -> {
            CompoundTag tag = oldData.copyTag();
            tag.putString(TAPE_ID_KEY, newId.toString());
            return CustomData.of(tag);
        });
        return newId;
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        // Ensure each disk has a unique ID
        getOrCreateTapeId(stack);
        super.onCraftedBy(stack, level, player);
    }

    public int getTypeLimit(ItemStack stack) {
        return this.typeLimit.get();
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
        // No fuzzy config needed
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public @Nullable ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator, @Nullable BlockHitResult hitResult) {
        return null;
    }

    private static final ThreadLocal<ByteArrayOutputStream> LOCAL_BAOS =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(512));
    private static final ThreadLocal<DataOutputStream> LOCAL_DOS =
            ThreadLocal.withInitial(() -> new DataOutputStream(LOCAL_BAOS.get()));

    public static byte[] serializeItemStackToBytes(ItemStack stack) {
        try {
            HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
            CompoundTag tag = (CompoundTag) stack.save(provider);
            if (tag == null) {
                System.err.println("[EnderDrives] ItemStack failed to serialize: null tag.");
                return new byte[0];
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                NbtIo.write(tag, dos);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[EnderDrives] Failed to serialize ItemStack: " + stack);
            e.printStackTrace();
            return new byte[0];
        }
    }


    public static ItemStack deserializeItemStackFromBytes(byte[] data) {
        if (data == null || data.length == 0) return ItemStack.EMPTY;

        CompoundTag tag;
        try {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
                tag = NbtIo.read(dis);
            }
        } catch (Exception e) {
            System.err.println("[EnderDrives] Failed to read raw NBT data from ItemStack bytes.");
            e.printStackTrace();
            return ItemStack.EMPTY;
        }

        HolderLookup.Provider provider = ServerLifecycleHooks.getCurrentServer().registryAccess();
        try {
            return ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            System.err.println("[EnderDrives] ItemStack parse failed. Attempting to clean invalid components.");
            e.printStackTrace();
        }
        try {
            if (tag.contains("tag", 10)) {
                CompoundTag tagTag = tag.getCompound("tag");
                for (String key : List.of("Enchantments", "StoredEnchantments", "AttributeModifiers", "CustomModelData")) {
                    if (tagTag.contains(key)) {
                        tagTag.remove(key);
                        System.err.println("[EnderDrives] Removed problematic NBT tag: " + key);
                    }
                }
                if (tagTag.contains("apotheosis") || tagTag.contains("tetra")) {
                    tagTag.remove("apotheosis");
                    tagTag.remove("tetra");
                    System.err.println("[EnderDrives] Removed mod-specific tag data (apotheosis/tetra).");
                }
            }
            return ItemStack.parse(provider, tag).orElse(ItemStack.EMPTY);
        } catch (Exception recoveryException) {
            System.err.println("[EnderDrives] Final fallback failed during deserialization.");
            recoveryException.printStackTrace();
            return ItemStack.EMPTY;
        }
    }



}
