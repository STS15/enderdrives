package com.sts15.enderdrives.mixins;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.api.storage.cells.StorageCell;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IOPortBlockEntity.class)
public abstract class IOPortBlockEntityMixin {

    @Shadow
    private AppEngInternalInventory inputCells;

    @Shadow
    private AppEngInternalInventory outputCells;

    @Shadow
    private IActionSource mySrc;

    @Unique
    private long enderdrives$lastSoundTick = 0;

    @Unique
    private long enderdrives$lastMessageTick = 0;

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void enderdrives$preventSameFrequencyTransfer(IGridNode node, int ticksSinceLastCall, CallbackInfoReturnable<TickRateModulation> cir) {
        IGrid grid = node.getGrid();
        if (grid == null) return;

        Level level = ((IOPortBlockEntity)(Object)this).getLevel();
        BlockPos pos = ((IOPortBlockEntity)(Object)this).getBlockPos();

        for (DriveBlockEntity drive : grid.getMachines(DriveBlockEntity.class)) {
            for (int j = 0; j < drive.getCellCount(); j++) {
                ItemStack driveStack = drive.getInternalInventory().getStackInSlot(j);
                if (!(driveStack.getItem() instanceof EnderDiskItem)) continue;

                int driveFreq = EnderDiskItem.getFrequency(driveStack);
                String driveScope = EnderDiskItem.getSafeScopePrefix(driveStack);

                for (int i = 0; i < inputCells.size(); i++) {
                    ItemStack inputStack = inputCells.getStackInSlot(i);
                    if (!(inputStack.getItem() instanceof EnderDiskItem)) continue;

                    int inputFreq = EnderDiskItem.getFrequency(inputStack);
                    String inputScope = EnderDiskItem.getSafeScopePrefix(inputStack);

                    if (inputFreq == driveFreq && inputScope.equals(driveScope)) {
                        enderdrives$playLoopWarning(level, pos);
                        cir.setReturnValue(TickRateModulation.IDLE);
                        return;
                    }
                }

                for (int i = 0; i < outputCells.size(); i++) {
                    ItemStack outputStack = outputCells.getStackInSlot(i);
                    if (!(outputStack.getItem() instanceof EnderDiskItem)) continue;

                    int outputFreq = EnderDiskItem.getFrequency(outputStack);
                    String outputScope = EnderDiskItem.getSafeScopePrefix(outputStack);

                    if (outputFreq == driveFreq && outputScope.equals(driveScope)) {
                        enderdrives$playLoopWarning(level, pos);
                        cir.setReturnValue(TickRateModulation.IDLE);
                        return;
                    }
                }
            }
        }
    }

    @Inject(method = "transferContents", at = @At("HEAD"), cancellable = true)
    private void enderdrives$patchTransfer(IGrid grid, StorageCell sourceInv, long itemsToMove, CallbackInfoReturnable<Long> cir) {
        if (!(sourceInv instanceof EnderDiskInventory sourceEnderDisk)) return;

        for (DriveBlockEntity drive : grid.getMachines(DriveBlockEntity.class)) {
            for (int slot = 0; slot < drive.getCellCount(); slot++) {
                ItemStack destinationStack = drive.getInternalInventory().getStackInSlot(slot);
                if (!(destinationStack.getItem() instanceof EnderDiskItem)) continue;

                EnderDiskInventory destinationInv = new EnderDiskInventory(destinationStack);

                boolean sameFreq = EnderDiskItem.getFrequency(destinationStack) == EnderDiskItem.getFrequency(sourceEnderDisk.getContainerItem());
                boolean sameScope = EnderDiskItem.getSafeScopePrefix(destinationStack).equals(EnderDiskItem.getSafeScopePrefix(sourceEnderDisk.getContainerItem()));

                if (sameFreq && sameScope && destinationInv != sourceEnderDisk) {
                    cir.setReturnValue(enderdrives$transferOneTypeSynced(grid, sourceEnderDisk));
                    return;
                }
            }
        }
    }

    @Unique
    private long enderdrives$transferOneTypeSynced(IGrid grid, EnderDiskInventory sourceInv) {
        KeyCounter kc = new KeyCounter();
        sourceInv.getAvailableStacks(kc);
        if (kc.isEmpty()) return 0;

        MEStorage networkStorage = grid.getStorageService().getInventory();
        IEnergyService energy = grid.getEnergyService();
        EnderDBManager.flushWALQueue();
        long totalTransferred = 0;

        for (AEKey key : kc.keySet()) {
            long want = kc.get(key);
            long extracted = sourceInv.extract(key, want, Actionable.MODULATE, this.mySrc);
            if (extracted <= 0) {continue;}
            long inserted = StorageHelper.poweredInsert(energy, networkStorage, key, extracted, this.mySrc);
            if (inserted < extracted) {sourceInv.insert(key, extracted - inserted, Actionable.MODULATE, this.mySrc);}
            if (inserted > 0) {totalTransferred += inserted;}
        }
        return totalTransferred;
    }

    @Unique
    private void enderdrives$playLoopWarning(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) return;

        long gameTime = level.getGameTime();
        if (gameTime - enderdrives$lastSoundTick >= 60) {
            level.playSound(
                    null, pos, SoundEvents.ENDERMAN_STARE,
                    SoundSource.BLOCKS, 0.6f, 0.6f + level.random.nextFloat() * 0.4f
            );
            enderdrives$lastSoundTick = gameTime;
        }

        if (gameTime - enderdrives$lastMessageTick >= 100) {
            enderdrives$lastMessageTick = gameTime;
            Player nearestPlayer = level.getNearestPlayer(pos.getX(), pos.getY(), pos.getZ(), 10, false);
            if (nearestPlayer != null) {
                nearestPlayer.sendSystemMessage(Component.literal("§5[EnderDrives] Transfer blocked: Infinite loop detected between linked drives."));
            }
        }
    }

}
