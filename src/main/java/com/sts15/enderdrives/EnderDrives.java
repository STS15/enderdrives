package com.sts15.enderdrives;

import appeng.api.client.StorageCellModels;
import appeng.api.storage.StorageCells;
import com.sts15.enderdrives.client.DriveStatusProperty;
import com.sts15.enderdrives.client.ClientConfigCache;
import com.sts15.enderdrives.client.ClientTapeCache;
import com.sts15.enderdrives.client.ClientHooksImpl;
import com.sts15.enderdrives.clientbridge.ClientHooks;
import com.sts15.enderdrives.commands.ModCommands;
import com.sts15.enderdrives.commands.AutoBenchmarkCommand;
import com.sts15.enderdrives.commands.ClearCommand;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.db.*;
import com.sts15.enderdrives.init.CreativeTabRegistry;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.inventory.EnderFluidDiskInventory;
import com.sts15.enderdrives.inventory.TapeDiskInventory;
import com.sts15.enderdrives.items.ItemInit;
import com.sts15.enderdrives.items.TapeDiskItem;
import com.sts15.enderdrives.network.NetworkHandler;
import com.sts15.enderdrives.network.packet.SyncConfigPacket;
import com.sts15.enderdrives.network.packet.SyncDisabledDrivesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.InitializeClientRegistriesEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;

import static com.sts15.enderdrives.Constants.MOD_ID;

@Mod(MOD_ID)
public class EnderDrives {

    public EnderDrives(IEventBus modEventBus, ModContainer modContainer) {
        serverConfig.register(modContainer);
        modEventBus.addListener(this::registerPayloads);
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
    public void onServerStart(net.neoforged.neoforge.event.server.ServerAboutToStartEvent e) {
        EnderDBManager.init();
        EnderFluidDBManager.init();
        TapeDBManager.init();
    }

    @SubscribeEvent
    public void onServerStop(net.neoforged.neoforge.event.server.ServerStoppingEvent e) {
        AutoBenchmarkCommand.clearPendingRequests();
        ClearCommand.clearPendingRequests();
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

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        AutoBenchmarkCommand.clearPendingRequest(playerId);
        ClearCommand.clearPendingRequest(playerId);
    }

    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void initializeClientRegistries(InitializeClientRegistriesEvent event) {
            ClientHooks.install(new ClientHooksImpl());
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

        }

        @SubscribeEvent
        public static void registerItemModelProperties(RegisterRangeSelectItemModelPropertyEvent event) {
            event.register(DriveStatusProperty.ID, DriveStatusProperty.CODEC);
        }

        @SubscribeEvent
        public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientDiskCache.clear();
            ClientFluidDiskCache.clear();
            ClientTapeCache.clear();
            ClientConfigCache.reset();
            DriveStatusProperty.clearRequestCache();
            ClientHooks.clearRequestCache();
        }
    }

    public static Identifier id(String id) {
        return Identifier.fromNamespaceAndPath(MOD_ID, id);
    }
}
