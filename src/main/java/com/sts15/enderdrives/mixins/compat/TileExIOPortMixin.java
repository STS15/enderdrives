package com.sts15.enderdrives.mixins.compat;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.blockentity.storage.IOPortBlockEntity;
import com.sts15.enderdrives.items.AbstractEnderDiskItem;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileExIOPort", remap = false)
public abstract class TileExIOPortMixin {

    @Unique
    private long enderdrives$ExLastSoundTick = 0;

    @Unique
    private long enderdrives$ExLastMessageTick = 0;

    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true)
    private void enderdrives$ExPreventSameFrequencyTransfer(IGridNode node, int ticksSinceLastCall, CallbackInfoReturnable<TickRateModulation> cir) {
        IGrid grid = node.getGrid();
        if (grid == null) return;

        IOPortBlockEntity ioPort = (IOPortBlockEntity) (Object) this;
        Level level = ioPort.getLevel();
        if (level == null || level.isClientSide()) return;

        BlockPos pos = ioPort.getBlockPos();

        for (DriveBlockEntity drive : grid.getMachines(DriveBlockEntity.class)) {
            for (int j = 0; j < drive.getCellCount(); j++) {
                ItemStack driveStack = drive.getInternalInventory().getStackInSlot(j);
                if (!enderdrives$isLinkedDisk(driveStack)) continue;

                // Check input cells
                for (int i = 0; i < 6; i++) {
                    ItemStack inputStack = ioPort.getInternalInventory().getStackInSlot(i);
                    if (enderdrives$isSameLinkedStorage(driveStack, inputStack)) {
                        enderdrives$ExPlayLoopWarning(level, pos);
                        cir.setReturnValue(TickRateModulation.IDLE);
                        return;
                    }
                }

                // Check output cells
                for (int i = 6; i < 12; i++) {
                    ItemStack outputStack = ioPort.getInternalInventory().getStackInSlot(i);
                    if (enderdrives$isSameLinkedStorage(driveStack, outputStack)) {
                        enderdrives$ExPlayLoopWarning(level, pos);
                        cir.setReturnValue(TickRateModulation.IDLE);
                        return;
                    }
                }
            }
        }
    }

    @Unique
    private static boolean enderdrives$isLinkedDisk(ItemStack stack) {
        return stack.getItem() instanceof EnderDiskItem
                || stack.getItem() instanceof EnderFluidDiskItem;
    }

    @Unique
    private static boolean enderdrives$isSameLinkedStorage(ItemStack first, ItemStack second) {
        boolean bothItems = first.getItem() instanceof EnderDiskItem
                && second.getItem() instanceof EnderDiskItem;
        boolean bothFluids = first.getItem() instanceof EnderFluidDiskItem
                && second.getItem() instanceof EnderFluidDiskItem;
        return (bothItems || bothFluids)
                && AbstractEnderDiskItem.getFrequency(first) == AbstractEnderDiskItem.getFrequency(second)
                && AbstractEnderDiskItem.getSafeScopePrefix(first)
                        .equals(AbstractEnderDiskItem.getSafeScopePrefix(second));
    }

    @Unique
    private void enderdrives$ExPlayLoopWarning(Level level, BlockPos pos) {
        long gameTime = level.getGameTime();

        if (gameTime - enderdrives$ExLastSoundTick >= 60) {
            level.playSound(null, pos, SoundEvents.ENDERMAN_STARE, SoundSource.BLOCKS, 0.6f, 0.6f + level.getRandom().nextFloat() * 0.4f);
            enderdrives$ExLastSoundTick = gameTime;
        }

        if (gameTime - enderdrives$ExLastMessageTick >= 100) {
            enderdrives$ExLastMessageTick = gameTime;
            Player nearestPlayer = level.getNearestPlayer(pos.getX(), pos.getY(), pos.getZ(), 10, false);
            if (nearestPlayer != null) {
                nearestPlayer.sendSystemMessage(Component.literal("[EnderDrives] Transfer blocked: Infinite loop detected between linked drives.")
                        .withStyle(ChatFormatting.DARK_PURPLE));
            }
        }
    }
}
