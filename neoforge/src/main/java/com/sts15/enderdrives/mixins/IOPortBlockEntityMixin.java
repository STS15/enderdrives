package com.sts15.enderdrives.mixins;

import appeng.api.config.Actionable;
import appeng.api.config.OperationMode;
import appeng.api.config.Settings;
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
import com.sts15.enderdrives.inventory.TapeDiskInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.TapeDiskItem;
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
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;

@Mixin(IOPortBlockEntity.class)
public abstract class IOPortBlockEntityMixin {
    @Unique private static final Logger LOGGER = LogManager.getLogger("EnderDrives:IOPortMixin");
    @Shadow private AppEngInternalInventory inputCells;
    @Shadow private AppEngInternalInventory outputCells;
    @Shadow private IActionSource mySrc;
    @Invoker("transferContents") public abstract long invokeTransferContents(IGrid grid, StorageCell cellInv, long toMove);
    @Unique private long enderdrives$lastSoundTick = 0, enderdrives$lastMessageTick = 0;

    @Inject(method="transferContents", at=@At("HEAD"), cancellable=true)
    private void patchTransfer(IGrid grid, StorageCell sourceInv, long toMove, CallbackInfoReturnable<Long> cir) {
        OperationMode mode = ((IOPortBlockEntity)(Object)this)
                .getConfigManager().getSetting(Settings.OPERATION_MODE);

        // 1) IO Port tape cell
        if (sourceInv instanceof TapeDiskInventory tape) {
            long rem = mode == OperationMode.EMPTY
                    ? enderdrives$transferFromTapeToNetwork(grid, tape, toMove)
                    : enderdrives$transferFromNetworkToTapeCell(grid, tape, toMove);
            cir.setReturnValue(rem);
            return;
        }

        // 2) Ender disk sync
        if (sourceInv instanceof EnderDiskInventory ed) {
            long rem = enderdrives$transferOneTypeSynced(grid, ed, toMove);
            if (rem < toMove) {
                cir.setReturnValue(rem);
                return;
            }
        }

        // 3) Normal IO port cell → network tape drives
        if (mode == OperationMode.EMPTY && !(sourceInv instanceof TapeDiskInventory)) {
            for (DriveBlockEntity drive : grid.getMachines(DriveBlockEntity.class)) {
                for (int slot = 0; slot < drive.getCellCount(); slot++) {
                    ItemStack ds = drive.getInternalInventory().getStackInSlot(slot);
                    if (!(ds.getItem() instanceof TapeDiskItem)) continue;
                    TapeDiskInventory dest = new TapeDiskInventory(ds);
                    long rem = enderdrives$transferOneItemFromCellToTape(grid, sourceInv, dest, toMove);
                    if (rem < toMove) {
                        cir.setReturnValue(rem);
                        return;
                    }
                }
            }
        }
        // else fall through to vanilla AE2
    }

    @Unique private long enderdrives$transferFromTapeToNetwork(IGrid grid, TapeDiskInventory tape, long budget) {
        MEStorage net = grid.getStorageService().getInventory();
        IEnergyService en = grid.getEnergyService();
        KeyCounter kc = new KeyCounter(); tape.getAvailableStacks(kc);
        for (Map.Entry<AEKey, Long> e : kc) {
            AEKey key = e.getKey(); long have = e.getValue();
            long simIns = StorageHelper.poweredInsert(en, net, key, have, mySrc, Actionable.SIMULATE);
            if (simIns <= 0) continue;
            long perOp = key.getAmountPerOperation();
            long use = Math.min(simIns, budget * perOp);
            long simExt = tape.extract(key, use, Actionable.SIMULATE, mySrc);
            if (simExt <= 0) continue;
            long ext = tape.extract(key, simExt, Actionable.MODULATE, mySrc);
            long ins = StorageHelper.poweredInsert(en, net, key, ext, mySrc);
            if (ins < ext) tape.insert(key, ext - ins, Actionable.MODULATE, mySrc);
            budget -= Math.max(1L, ins / perOp);
            return budget;
        }
        return budget;
    }

    @Unique private long enderdrives$transferFromNetworkToTapeCell(IGrid grid, TapeDiskInventory tape, long budget) {
        MEStorage net = grid.getStorageService().getInventory();
        IEnergyService en = grid.getEnergyService();
        KeyCounter kc = grid.getStorageService().getCachedInventory();
        for (Map.Entry<AEKey, Long> e : kc) {
            AEKey key = e.getKey(); long have = e.getValue();
            if (have <= 0 || budget <= 0) break;
            long perOp = key.getAmountPerOperation();
            long simExt = StorageHelper.poweredExtraction(en, net, key, perOp, mySrc, Actionable.SIMULATE);
            if (simExt <= 0) continue;
            long simIns = tape.insert(key, simExt, Actionable.SIMULATE, mySrc);
            if (simIns <= 0) continue;
            long ext = StorageHelper.poweredExtraction(en, net, key, simIns, mySrc, Actionable.MODULATE);
            if (ext <= 0) continue;
            long ins = tape.insert(key, ext, Actionable.MODULATE, mySrc);
            if (ins < ext) StorageHelper.poweredInsert(en, net, key, ext - ins, mySrc, Actionable.MODULATE);
            budget--;
            return budget;
        }
        return budget;
    }

    @Unique private long enderdrives$transferOneTypeSynced(IGrid grid, EnderDiskInventory src, long budget) {
        KeyCounter kc = new KeyCounter(); src.getAvailableStacks(kc);
        if (kc.isEmpty()) return budget;
        MEStorage net = grid.getStorageService().getInventory();
        IEnergyService en = grid.getEnergyService();
        EnderDBManager.flushWALQueue();
        for (Map.Entry<AEKey, Long> e : kc) {
            AEKey key = e.getKey(); long have = e.getValue();
            long simExt = src.extract(key, have, Actionable.SIMULATE, mySrc);
            if (simExt <= 0) continue;
            long simIns = StorageHelper.poweredInsert(en, net, key, simExt, mySrc, Actionable.SIMULATE);
            if (simIns <= 0) continue;
            long ext = src.extract(key, simIns, Actionable.MODULATE, mySrc);
            if (ext <= 0) continue;
            long ins = StorageHelper.poweredInsert(en, net, key, ext, mySrc, Actionable.MODULATE);
            if (ins < ext) src.insert(key, ext - ins, Actionable.MODULATE, mySrc);
            long perOp = key.getAmountPerOperation(), used = Math.max(1L, ins / perOp);
            return budget - used;
        }
        return budget;
    }

    @Unique private long enderdrives$transferOneItemFromCellToTape(IGrid grid, StorageCell src, TapeDiskInventory dest, long budget) {
        IEnergyService en = grid.getEnergyService();
        KeyCounter kc = new KeyCounter(); src.getAvailableStacks(kc);
        for (Map.Entry<AEKey, Long> e : kc) {
            AEKey key = e.getKey(); long have = e.getValue();
            if (have <= 0 || budget <= 0) break;
            long perOp = key.getAmountPerOperation();
            long simExt = src.extract(key, perOp, Actionable.SIMULATE, mySrc);
            if (simExt <= 0) continue;
            long simIns = dest.insert(key, simExt, Actionable.SIMULATE, mySrc);
            if (simIns <= 0) continue;
            long ext = src.extract(key, simIns, Actionable.MODULATE, mySrc);
            if (ext <= 0) continue;
            long ins = dest.insert(key, ext, Actionable.MODULATE, mySrc);
            if (ins < ext) src.insert(key, ext - ins, Actionable.MODULATE, mySrc);
            budget--;
            return budget;
        }
        return budget;
    }

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
