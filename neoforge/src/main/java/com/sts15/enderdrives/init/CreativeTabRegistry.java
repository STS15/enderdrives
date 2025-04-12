package com.sts15.enderdrives.init;

import com.sts15.enderdrives.Constants;
import com.sts15.enderdrives.items.ItemInit;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CreativeTabRegistry {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Constants.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ENDERDRIVES_TAB = CREATIVE_MODE_TABS.register("enderdrives_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("key.categories.enderdrives"))
            .icon(() -> ItemInit.ENDER_DISK_creative.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(ItemInit.ENDER_STORAGE_COMPONENT_1K.get());
                output.accept(ItemInit.ENDER_STORAGE_COMPONENT_4K.get());
                output.accept(ItemInit.ENDER_STORAGE_COMPONENT_16K.get());
                output.accept(ItemInit.ENDER_STORAGE_COMPONENT_64K.get());
                output.accept(ItemInit.ENDER_STORAGE_COMPONENT_256K.get());
                output.accept(ItemInit.ENDER_DISK_1K.get());
                output.accept(ItemInit.ENDER_DISK_4K.get());
                output.accept(ItemInit.ENDER_DISK_16K.get());
                output.accept(ItemInit.ENDER_DISK_64K.get());
                output.accept(ItemInit.ENDER_DISK_256K.get());
                output.accept(ItemInit.ENDER_DISK_creative.get());
                output.accept(ItemInit.TAPE_DISK.get());
            })
            .build());

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
