package com.sts15.enderdrives.mixins.compat;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.blockentity.storage.DriveBlockEntity;
import com.glodblock.github.extendedae.common.tileentities.TileExIOPort;
import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TileExIOPort.class, remap = false)
public abstract class TileExIOPortMixin {

    @Unique
    private long enderdrives$ExLastSoundTick = 0;

    @Unique
    private long enderdrives$ExLastMessageTick = 0;

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void enderdrives$ExPreventSameFrequencyTransfer(IGridNode node, int ticksSinceLastCall, CallbackInfoReturnable<TickRateModulation> cir) {
        IGrid grid = node.getGrid();
        if (grid == null) return;

        Level level = ((TileExIOPort)(Object)this).getLevel();
        if (level == null || level.isClientSide) return;

        BlockPos pos = ((TileExIOPort)(Object)this).getBlockPos();

        for (DriveBlockEntity drive : grid.getMachines(DriveBlockEntity.class)) {
            for (int j = 0; j < drive.getCellCount(); j++) {
                ItemStack driveStack = drive.getInternalInventory().getStackInSlot(j);
                if (!(driveStack.getItem() instanceof EnderDiskItem)) continue;

                int driveFreq = EnderDiskItem.getFrequency(driveStack);
                String driveScope = EnderDiskItem.getSafeScopePrefix(driveStack);

                // Check input cells
                for (int i = 0; i < 6; i++) {
                    ItemStack inputStack = ((TileExIOPort)(Object)this).getInternalInventory().getStackInSlot(i);
                    if (!(inputStack.getItem() instanceof EnderDiskItem)) continue;

                    int inputFreq = EnderDiskItem.getFrequency(inputStack);
                    String inputScope = EnderDiskItem.getSafeScopePrefix(inputStack);

                    if (inputFreq == driveFreq && inputScope.equals(driveScope)) {
                        enderdrives$ExPlayLoopWarning(level, pos);
                        cir.setReturnValue(TickRateModulation.IDLE);
                        return;
                    }
                }

                // Check output cells
                for (int i = 6; i < 12; i++) {
                    ItemStack outputStack = ((TileExIOPort)(Object)this).getInternalInventory().getStackInSlot(i);
                    if (!(outputStack.getItem() instanceof EnderDiskItem)) continue;

                    int outputFreq = EnderDiskItem.getFrequency(outputStack);
                    String outputScope = EnderDiskItem.getSafeScopePrefix(outputStack);

                    if (outputFreq == driveFreq && outputScope.equals(driveScope)) {
                        enderdrives$ExPlayLoopWarning(level, pos);
                        cir.setReturnValue(TickRateModulation.IDLE);
                        return;
                    }
                }
            }
        }
    }

    @Unique
    private void enderdrives$ExPlayLoopWarning(Level level, BlockPos pos) {
        long gameTime = level.getGameTime();

        if (gameTime - enderdrives$ExLastSoundTick >= 60) {
            level.playSound(null, pos, SoundEvents.ENDERMAN_STARE, SoundSource.BLOCKS, 0.6f, 0.6f + level.random.nextFloat() * 0.4f);
            enderdrives$ExLastSoundTick = gameTime;
        }

        if (gameTime - enderdrives$ExLastMessageTick >= 100) {
            enderdrives$ExLastMessageTick = gameTime;
            Player nearestPlayer = level.getNearestPlayer(pos.getX(), pos.getY(), pos.getZ(), 10, false);
            if (nearestPlayer != null) {
                nearestPlayer.sendSystemMessage(Component.literal("§5[EnderDrives] Transfer blocked: Infinite loop detected between linked drives."));
            }
        }
    }
}
