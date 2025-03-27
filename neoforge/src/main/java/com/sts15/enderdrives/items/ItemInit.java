package com.sts15.enderdrives.items;

import com.sts15.enderdrives.Constants;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ItemInit {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Constants.MOD_ID);

    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_1K = ITEMS.register("ender_disk_1k", () -> new EnderDiskItem(new Item.Properties(), 1));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_4K = ITEMS.register("ender_disk_4k", () -> new EnderDiskItem(new Item.Properties(), 31));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_16K = ITEMS.register("ender_disk_16k", () -> new EnderDiskItem(new Item.Properties(), 63));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_64K = ITEMS.register("ender_disk_64k", () -> new EnderDiskItem(new Item.Properties(), 127));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_256K = ITEMS.register("ender_disk_256k", () -> new EnderDiskItem(new Item.Properties(), 255));
    public static final DeferredHolder<Item, EnderDiskItem> ENDER_DISK_creative = ITEMS.register("ender_disk_creative", () -> new EnderDiskItem(new Item.Properties(), Integer.MAX_VALUE));

    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_1K = ITEMS.register("ender_storage_component_1k", () -> new Item(new Item.Properties()) {});
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_4K = ITEMS.register("ender_storage_component_4k", () -> new Item(new Item.Properties()) {});
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_16K = ITEMS.register("ender_storage_component_16k", () -> new Item(new Item.Properties()) {});
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_64K = ITEMS.register("ender_storage_component_64k", () -> new Item(new Item.Properties()) {});
    public static final DeferredHolder<Item, Item> ENDER_STORAGE_COMPONENT_256K = ITEMS.register("ender_storage_component_256k", () -> new Item(new Item.Properties()) {});

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}