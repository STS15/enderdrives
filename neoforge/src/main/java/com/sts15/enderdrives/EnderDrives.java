package com.sts15.enderdrives;

import appeng.api.client.StorageCellModels;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import com.sts15.enderdrives.commands.ModCommands;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.init.CreativeTabRegistry;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.items.ItemInit;
import com.sts15.enderdrives.network.NetworkHandler;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.Objects;
import static com.sts15.enderdrives.Constants.MOD_ID;

@Mod(MOD_ID)
public class EnderDrives {

    private static boolean isDatabaseInitialized = false;

    public EnderDrives(IEventBus modEventBus, ModContainer modContainer) {
        Objects.requireNonNull(modContainer.getEventBus()).addListener(this::registerPayloads);
        ItemInit.register(modEventBus);
        CreativeTabRegistry.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        NetworkHandler.registerPackets(event);
    }

    public void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StorageCells.addCellHandler(EnderDiskInventory.HANDLER);
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onWorldLoad(LevelEvent.Load event) {
        if (!event.getLevel().isClientSide() && event.getLevel() instanceof ServerLevel) {
            if (!isDatabaseInitialized) {
                EnderDBManager.init();
                isDatabaseInitialized = true;
                System.out.println("[EnderDrives] Database initialized.");
            }
        }
    }

    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            StorageCellModels.registerModel(ItemInit.ENDER_DISK_1K.get(), EnderDrives.id("block/drive/ender_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_DISK_4K.get(), EnderDrives.id("block/drive/ender_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_DISK_16K.get(), EnderDrives.id("block/drive/ender_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_DISK_64K.get(), EnderDrives.id("block/drive/ender_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_DISK_256K.get(), EnderDrives.id("block/drive/ender_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_DISK_creative.get(), EnderDrives.id("block/drive/ender_cell"));

            event.enqueueWork(() -> {
                for (var disk : new Item[]{
                        ItemInit.ENDER_DISK_1K.get(),
                        ItemInit.ENDER_DISK_4K.get(),
                        ItemInit.ENDER_DISK_16K.get(),
                        ItemInit.ENDER_DISK_64K.get(),
                        ItemInit.ENDER_DISK_256K.get(),
                        ItemInit.ENDER_DISK_creative.get()
                }) {
                    ItemProperties.register(disk, EnderDrives.id("status"), (stack, level, entity, seed) -> {
                        var state = EnderDiskInventory.getCellStateForStack(stack);
                        return switch (state) {
                            case ABSENT, EMPTY -> 0.0f;
                            case NOT_EMPTY -> 1.0f;
                            case TYPES_FULL, FULL -> 2.0f;
                        };
                    });

                }
            });
        }

        @SubscribeEvent
        public static void registerColorHandlers(RegisterColorHandlersEvent.Item event) {
            event.register((stack, tintIndex) -> {
                if (tintIndex == 1) {
                    CellState state = EnderDiskInventory.getCellStateForStack(stack);
                    return switch (state) {
                        case ABSENT -> 0x000000;
                        case EMPTY -> 0x00FF00;
                        case NOT_EMPTY -> 0x0000FF;
                        case TYPES_FULL, FULL -> 0xFFA500;
                    };
                }
                return 0xFFFFFFFF;
            }, ItemInit.ENDER_DISK_1K.get(), ItemInit.ENDER_DISK_4K.get(),
                    ItemInit.ENDER_DISK_16K.get(), ItemInit.ENDER_DISK_64K.get(),
                    ItemInit.ENDER_DISK_256K.get(), ItemInit.ENDER_DISK_creative.get());
        }

    }

    public static ResourceLocation id(String id) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, id);
    }
}