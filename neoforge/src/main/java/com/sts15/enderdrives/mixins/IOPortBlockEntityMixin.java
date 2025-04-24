package com.sts15.enderdrives.mixins;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
