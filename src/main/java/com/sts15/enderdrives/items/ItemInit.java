package com.sts15.enderdrives.items;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.config.serverConfig;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ItemInit {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Constants.MOD_ID);

    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_1K =
            ITEMS.registerItem("ender_disk_1k", properties -> new EnderDiskItem(properties, () -> serverConfig.ENDER_DISK_1K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_4K =
            ITEMS.registerItem("ender_disk_4k", properties -> new EnderDiskItem(properties, () -> serverConfig.ENDER_DISK_4K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_16K =
            ITEMS.registerItem("ender_disk_16k", properties -> new EnderDiskItem(properties, () -> serverConfig.ENDER_DISK_16K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_64K =
            ITEMS.registerItem("ender_disk_64k", properties -> new EnderDiskItem(properties, () -> serverConfig.ENDER_DISK_64K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_256K =
            ITEMS.registerItem("ender_disk_256k", properties -> new EnderDiskItem(properties, () -> serverConfig.ENDER_DISK_256K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_creative =
            ITEMS.registerItem("ender_disk_creative", properties -> new EnderDiskItem(properties, () -> serverConfig.ENDER_DISK_CREATIVE_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderFluidDiskItem> ENDER_FLUID_DISK_1K =
            ITEMS.registerItem("ender_fluid_disk_1k", properties -> new EnderFluidDiskItem(properties, () -> serverConfig.ENDER_FLUID_DISK_1K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderFluidDiskItem> ENDER_FLUID_DISK_4K =
            ITEMS.registerItem("ender_fluid_disk_4k", properties -> new EnderFluidDiskItem(properties, () -> serverConfig.ENDER_FLUID_DISK_4K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderFluidDiskItem> ENDER_FLUID_DISK_16K =
            ITEMS.registerItem("ender_fluid_disk_16k", properties -> new EnderFluidDiskItem(properties, () -> serverConfig.ENDER_FLUID_DISK_16K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderFluidDiskItem> ENDER_FLUID_DISK_64K =
            ITEMS.registerItem("ender_fluid_disk_64k", properties -> new EnderFluidDiskItem(properties, () -> serverConfig.ENDER_FLUID_DISK_64K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderFluidDiskItem> ENDER_FLUID_DISK_256K =
            ITEMS.registerItem("ender_fluid_disk_256k", properties -> new EnderFluidDiskItem(properties, () -> serverConfig.ENDER_FLUID_DISK_256K_TYPE_LIMIT.get()));
    public static final DeferredHolder<Item, EnderFluidDiskItem> ENDER_FLUID_DISK_creative =
            ITEMS.registerItem("ender_fluid_disk_creative", properties -> new EnderFluidDiskItem(properties, () -> serverConfig.ENDER_FLUID_DISK_CREATIVE_TYPE_LIMIT.get()));

    public static final DeferredHolder<Item, TapeDiskItem> TAPE_DISK =
            ITEMS.registerItem("tape_disk", properties -> new TapeDiskItem(properties, () -> serverConfig.TAPE_DISK_TYPE_LIMIT.get()));

    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_1K = ITEMS.registerSimpleItem("ender_storage_component_1k");
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_4K = ITEMS.registerSimpleItem("ender_storage_component_4k");
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_16K = ITEMS.registerSimpleItem("ender_storage_component_16k");
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_64K = ITEMS.registerSimpleItem("ender_storage_component_64k");
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_256K = ITEMS.registerSimpleItem("ender_storage_component_256k");

    public static final DeferredHolder<Item, Item> ENDER_ITEM_HOUSING = ITEMS.registerSimpleItem("ender_item_housing");
    public static final DeferredHolder<Item, Item> ENDER_FLUID_HOUSING = ITEMS.registerSimpleItem("ender_fluid_housing");

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
