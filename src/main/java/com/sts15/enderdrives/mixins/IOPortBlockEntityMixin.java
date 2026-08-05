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
import com.sts15.enderdrives.db.EnderFluidDBManager;
import com.sts15.enderdrives.inventory.EnderDiskInventory;
import com.sts15.enderdrives.inventory.EnderFluidDiskInventory;
import com.sts15.enderdrives.inventory.TapeDiskInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.items.EnderFluidDiskItem;
import net.minecraft.ChatFormatting;
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

import java.util.Map;

@Mixin(value = IOPortBlockEntity.class, remap = false)
public abstract class IOPortBlockEntityMixin {
    @Shadow private AppEngInternalInventory inputCells;
    @Shadow private AppEngInternalInventory outputCells;
    @Shadow private IActionSource mySrc;
    @Unique private long enderdrives$lastSoundTick = 0, enderdrives$lastMessageTick = 0;

    @Inject(method="transferContents", at=@At("HEAD"), cancellable=true)
    private void patchTransfer(IGrid grid, StorageCell sourceInv, long toMove, CallbackInfoReturnable<Long> cir) {
        OperationMode mode = ((IOPortBlockEntity)(Object)this)
                .getConfigManager().getSetting(Settings.OPERATION_MODE);

        // 1) IO Port tape cell
        if (sourceInv instanceof TapeDiskInventory tape) {
            long rem = enderdrives$transferOneTypeSynced(grid, tape, toMove, mode);
            cir.setReturnValue(rem);
            return;
        }

        // 2) Ender disk sync
        if (sourceInv instanceof EnderDiskInventory ed) {
            EnderDBManager.flushWALQueue();
            long rem = enderdrives$transferOneTypeSynced(grid, ed, toMove, mode);
            if (rem < toMove) {
                cir.setReturnValue(rem);
                return;
            }
        } else if (sourceInv instanceof EnderFluidDiskInventory fluidDisk) {
            EnderFluidDBManager.flushWALQueue();
            long rem = enderdrives$transferOneTypeSynced(grid, fluidDisk, toMove, mode);
            if (rem < toMove) {
                cir.setReturnValue(rem);
                return;
            }
        }

        // 3) Normal IO port cell → network tape drives
        // All other cells use AE2's normal priority-based routing.
    }

    @Unique private long enderdrives$transferOneTypeSynced(
            IGrid grid,
            StorageCell cell,
            long budget,
            OperationMode mode
    ) {
        MEStorage net = grid.getStorageService().getInventory();
        IEnergyService en = grid.getEnergyService();
        MEStorage src = mode == OperationMode.EMPTY ? cell : net;
        MEStorage destination = mode == OperationMode.EMPTY ? net : cell;
        KeyCounter kc = mode == OperationMode.EMPTY
                ? cell.getAvailableStacks()
                : grid.getStorageService().getCachedInventory();
        if (kc.isEmpty()) return budget;

        for (Map.Entry<AEKey, Long> e : kc) {
            AEKey key = e.getKey(); long have = e.getValue();
            if (have <= 0 || budget <= 0) break;
            long simIns = destination.insert(key, have, Actionable.SIMULATE, mySrc);
            if (simIns <= 0) continue;
            long perOp = key.getAmountPerOperation();
            long transferLimit = budget > Long.MAX_VALUE / perOp
                    ? Long.MAX_VALUE
                    : budget * perOp;
            long requested = Math.min(simIns, transferLimit);
            long ext = src.extract(key, requested, Actionable.MODULATE, mySrc);
            if (ext <= 0) continue;
            long ins = StorageHelper.poweredInsert(en, destination, key, ext, mySrc, Actionable.MODULATE);
            if (ins < ext) src.insert(key, ext - ins, Actionable.MODULATE, mySrc);
            if (ins <= 0) continue;
            long used = Math.max(1L, ins / perOp);
            return Math.max(0L, budget - used);
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

                final boolean driveIsItem  = driveStack.getItem() instanceof EnderDiskItem;
                final boolean driveIsFluid = driveStack.getItem() instanceof EnderFluidDiskItem;
                if (!driveIsItem && !driveIsFluid) continue;

                final int driveFreq;
                final String driveScope;
                if (driveIsItem) {
                    driveFreq  = EnderDiskItem.getFrequency(driveStack);
                    driveScope = EnderDiskItem.getSafeScopePrefix(driveStack);
                } else { // fluid
                    driveFreq  = EnderFluidDiskItem.getFrequency(driveStack);
                    driveScope = EnderFluidDiskItem.getSafeScopePrefix(driveStack);
                }

                // ---- inputs (same type only)
                for (int i = 0; i < inputCells.size(); i++) {
                    ItemStack inputStack = inputCells.getStackInSlot(i);

                    if (driveIsItem) {
                        if (!(inputStack.getItem() instanceof EnderDiskItem)) continue;
                        int inputFreq = EnderDiskItem.getFrequency(inputStack);
                        String inputScope = EnderDiskItem.getSafeScopePrefix(inputStack);
                        if (inputFreq == driveFreq && inputScope.equals(driveScope)) {
                            enderdrives$playLoopWarning(level, pos);
                            cir.setReturnValue(TickRateModulation.IDLE);
                            return;
                        }
                    } else { // driveIsFluid
                        if (!(inputStack.getItem() instanceof EnderFluidDiskItem)) continue;
                        int inputFreq = EnderFluidDiskItem.getFrequency(inputStack);
                        String inputScope = EnderFluidDiskItem.getSafeScopePrefix(inputStack);
                        if (inputFreq == driveFreq && inputScope.equals(driveScope)) {
                            enderdrives$playLoopWarning(level, pos);
                            cir.setReturnValue(TickRateModulation.IDLE);
                            return;
                        }
                    }
                }

                // ---- outputs (same type only)
                for (int i = 0; i < outputCells.size(); i++) {
                    ItemStack outputStack = outputCells.getStackInSlot(i);

                    if (driveIsItem) {
                        if (!(outputStack.getItem() instanceof EnderDiskItem)) continue;
                        int outputFreq = EnderDiskItem.getFrequency(outputStack);
                        String outputScope = EnderDiskItem.getSafeScopePrefix(outputStack);
                        if (outputFreq == driveFreq && outputScope.equals(driveScope)) {
                            enderdrives$playLoopWarning(level, pos);
                            cir.setReturnValue(TickRateModulation.IDLE);
                            return;
                        }
                    } else { // driveIsFluid
                        if (!(outputStack.getItem() instanceof EnderFluidDiskItem)) continue;
                        int outputFreq = EnderFluidDiskItem.getFrequency(outputStack);
                        String outputScope = EnderFluidDiskItem.getSafeScopePrefix(outputStack);
                        if (outputFreq == driveFreq && outputScope.equals(driveScope)) {
                            enderdrives$playLoopWarning(level, pos);
                            cir.setReturnValue(TickRateModulation.IDLE);
                            return;
                        }
                    }
                }
            }
        }
    }


    @Unique
    private void enderdrives$playLoopWarning(Level level, BlockPos pos) {
        if (level == null || level.isClientSide()) return;

        long gameTime = level.getGameTime();
        if (gameTime - enderdrives$lastSoundTick >= 60) {
            level.playSound(
                    null, pos, SoundEvents.ENDERMAN_STARE,
                    SoundSource.BLOCKS, 0.6f, 0.6f + level.getRandom().nextFloat() * 0.4f
            );
            enderdrives$lastSoundTick = gameTime;
        }

        if (gameTime - enderdrives$lastMessageTick >= 100) {
            enderdrives$lastMessageTick = gameTime;
            Player nearestPlayer = level.getNearestPlayer(pos.getX(), pos.getY(), pos.getZ(), 10, false);
            if (nearestPlayer != null) {
                nearestPlayer.sendSystemMessage(Component.literal("[EnderDrives] Transfer blocked: Infinite loop detected between linked drives.")
                        .withStyle(ChatFormatting.DARK_PURPLE));
            }
        }
    }

}
