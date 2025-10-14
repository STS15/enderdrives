package com.sts15.enderdrives;

import appeng.api.client.StorageCellModels;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import com.sts15.enderdrives.client.ClientTapeCache;
import com.sts15.enderdrives.commands.ModCommands;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.db.EnderFluidDBManager;
import com.sts15.enderdrives.db.TapeDBManager;
import com.sts15.enderdrives.init.CreativeTabRegistry;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.inventory.EnderFluidDiskInventory;
import com.sts15.enderdrives.inventory.TapeDiskInventory;
import com.sts15.enderdrives.items.*;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.network.packet.SyncConfigPacket;
import com.sts15.enderdrives.network.packet.SyncDisabledDrivesPacket;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.Objects;
import java.util.UUID;
import static com.sts15.enderdrives.Constants.MOD_ID;

@Mod(MOD_ID)
public class EnderDrives {

    public EnderDrives(IEventBus modEventBus, ModContainer modContainer) {
        serverConfig.register(modContainer);
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
            StorageCells.addCellHandler(EnderFluidDiskInventory.HANDLER);
            StorageCells.addCellHandler(TapeDiskInventory.HANDLER);
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStart(net.neoforged.neoforge.event.server.ServerStartingEvent e) {
        EnderDBManager.init();
        EnderFluidDBManager.init();
        TapeDBManager.init();
    }

    @SubscribeEvent
    public void onServerStop(net.neoforged.neoforge.event.server.ServerStoppingEvent e) {
        EnderDBManager.shutdown();
        EnderFluidDBManager.shutdown();
        TapeDBManager.shutdown();
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int min = serverConfig.FREQ_MIN.get();
        int max = serverConfig.FREQ_MAX.get();
        SyncConfigPacket packet = new SyncConfigPacket(min, max);
        NetworkHandler.sendToClient(player, packet);
        int bitmask = 0;
        if (serverConfig.ENDER_DISK_1K_TOGGLE.get()) bitmask |= 1 << 0;
        if (serverConfig.ENDER_DISK_4K_TOGGLE.get()) bitmask |= 1 << 1;
        if (serverConfig.ENDER_DISK_16K_TOGGLE.get()) bitmask |= 1 << 2;
        if (serverConfig.ENDER_DISK_64K_TOGGLE.get()) bitmask |= 1 << 3;
        if (serverConfig.ENDER_DISK_256K_TOGGLE.get()) bitmask |= 1 << 4;
        if (serverConfig.ENDER_DISK_CREATIVE_TOGGLE.get()) bitmask |= 1 << 5;
        if (serverConfig.TAPE_DISK_TOGGLE.get()) bitmask |= 1 << 6;
        if (serverConfig.ENDER_FLUID_DISK_1K_TOGGLE.get())       bitmask |= 1 << 7;
        if (serverConfig.ENDER_FLUID_DISK_4K_TOGGLE.get())       bitmask |= 1 << 8;
        if (serverConfig.ENDER_FLUID_DISK_16K_TOGGLE.get())      bitmask |= 1 << 9;
        if (serverConfig.ENDER_FLUID_DISK_64K_TOGGLE.get())      bitmask |= 1 << 10;
        if (serverConfig.ENDER_FLUID_DISK_256K_TOGGLE.get())     bitmask |= 1 << 11;
        if (serverConfig.ENDER_FLUID_DISK_CREATIVE_TOGGLE.get()) bitmask |= 1 << 12;
        NetworkHandler.sendToClient(player, new SyncDisabledDrivesPacket(bitmask));
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
            StorageCellModels.registerModel(ItemInit.ENDER_FLUID_DISK_1K.get(), EnderDrives.id("block/drive/ender_fluid_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_FLUID_DISK_4K.get(), EnderDrives.id("block/drive/ender_fluid_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_FLUID_DISK_16K.get(), EnderDrives.id("block/drive/ender_fluid_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_FLUID_DISK_64K.get(), EnderDrives.id("block/drive/ender_fluid_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_FLUID_DISK_256K.get(), EnderDrives.id("block/drive/ender_fluid_cell"));
            StorageCellModels.registerModel(ItemInit.ENDER_FLUID_DISK_creative.get(), EnderDrives.id("block/drive/ender_fluid_cell"));
            StorageCellModels.registerModel(ItemInit.TAPE_DISK.get(), EnderDrives.id("block/drive/tape_cell"));

            event.enqueueWork(() -> {
                for (var disk : new Item[]{
                        ItemInit.ENDER_DISK_1K.get(),
                        ItemInit.ENDER_DISK_4K.get(),
                        ItemInit.ENDER_DISK_16K.get(),
                        ItemInit.ENDER_DISK_64K.get(),
                        ItemInit.ENDER_DISK_256K.get(),
                        ItemInit.ENDER_DISK_creative.get(),
                        ItemInit.ENDER_FLUID_DISK_1K.get(),
                        ItemInit.ENDER_FLUID_DISK_4K.get(),
                        ItemInit.ENDER_FLUID_DISK_16K.get(),
                        ItemInit.ENDER_FLUID_DISK_64K.get(),
                        ItemInit.ENDER_FLUID_DISK_256K.get(),
                        ItemInit.ENDER_FLUID_DISK_creative.get(),
                        ItemInit.TAPE_DISK.get()
                }) {
                    ItemProperties.register(disk, EnderDrives.id("status"), (stack, level, entity, seed) -> {
                        CellState state;
                        if (stack.getItem() instanceof EnderDiskItem  || stack.getItem() instanceof TapeDiskItem) {
                            state = EnderDiskInventory.getCellStateForStack(stack);
                        } else if (stack.getItem() instanceof EnderFluidDiskItem) {
                            state = EnderFluidDiskInventory.getCellStateForStack(stack);
                        } else {
                            state = CellState.ABSENT;
                        }
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
                            if (stack.getItem() instanceof AbstractEnderDiskItem) {
                                CellState state;
                                if (stack.getItem() instanceof EnderDiskItem) {
                                    state = EnderDiskInventory.getCellStateForStack(stack);
                                } else {
                                    state = EnderFluidDiskInventory.getCellStateForStack(stack);
                                }
                                return switch (state) {
                                    case ABSENT -> 0x000000;
                                    case EMPTY -> 0x00FF00;
                                    case NOT_EMPTY -> 0x0000FF;
                                    case TYPES_FULL, FULL -> 0xFFA500;
                                };
                            }
                            if (stack.getItem() instanceof TapeDiskItem) {
                                UUID id = TapeDiskItem.getTapeId(stack);
                                if (id == null) return 0x000000;
                                int typeCount = ClientTapeCache.getTypeCount(id);
                                long byteCount = ClientTapeCache.getByteCount(id);
                                int typeLimit = ((TapeDiskItem) stack.getItem()).getTypeLimit(stack);
                                long byteLimit = TapeDBManager.getByteLimit(id);
                                int typePercent = (typeLimit > 0) ? (typeCount * 100 / typeLimit) : 0;
                                int bytePercent = (byteLimit > 0) ? (int) (byteCount * 100 / byteLimit) : 0;
                                int usagePercent = Math.max(typePercent, bytePercent);
                                usagePercent = Math.min(usagePercent, 100);

                                // Now color logic
                                if (usagePercent >= 99) return 0xFF5555;      // Red
                                if (usagePercent >= 75) return 0xFFAA00;      // Orange
                                if (usagePercent > 0)   return 0x00AAFF;      // Blue
                                return 0x00FF00;                             // Green
                            }
                        }
                        return 0xFFFFFFFF;
                    },
                    ItemInit.ENDER_DISK_1K.get(),
                    ItemInit.ENDER_DISK_4K.get(),
                    ItemInit.ENDER_DISK_16K.get(),
                    ItemInit.ENDER_DISK_64K.get(),
                    ItemInit.ENDER_DISK_256K.get(),
                    ItemInit.ENDER_DISK_creative.get(),
                    ItemInit.ENDER_FLUID_DISK_1K.get(),
                    ItemInit.ENDER_FLUID_DISK_4K.get(),
                    ItemInit.ENDER_FLUID_DISK_16K.get(),
                    ItemInit.ENDER_FLUID_DISK_64K.get(),
                    ItemInit.ENDER_FLUID_DISK_256K.get(),
                    ItemInit.ENDER_FLUID_DISK_creative.get(),
                    ItemInit.TAPE_DISK.get()
            );
        }
    }

    public static ResourceLocation id(String id) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, id);
    }
}