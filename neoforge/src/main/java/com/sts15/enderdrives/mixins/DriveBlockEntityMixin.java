package com.sts15.enderdrives.mixins;

import appeng.blockentity.storage.DriveBlockEntity;
import com.sts15.enderdrives.integration.DriveBlockEntityAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DriveBlockEntity.class)
public class DriveBlockEntityMixin implements DriveBlockEntityAccessor {

    @Shadow
    private void onCellContentChanged() {}

    @Shadow
    private void updateState() {}

    @Shadow
    private boolean isCached;

    @Unique
    @Override
    public void enderdrives$triggerVisualUpdate() {
        this.onCellContentChanged();
    }

    @Unique
    @Override
    public void enderdrives$recalculateIdlePower() {
        this.isCached = false;
        this.updateState();
    }
}
