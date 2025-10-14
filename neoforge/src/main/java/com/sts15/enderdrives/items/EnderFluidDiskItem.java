package com.sts15.enderdrives.items;

import appeng.api.stacks.AEKeyType;
import com.sts15.enderdrives.db.ClientFluidDiskCache;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import com.sts15.enderdrives.network.NetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Fluid-only EnderDrive disk item.
 * - Uses AEKeyType.fluids() for partitioning/workbench config
 * - Shares frequency/scope/owner/team/transfer-mode semantics with item disks
 */
public class EnderFluidDiskItem extends AbstractEnderDiskItem {

    public EnderFluidDiskItem(Properties props, java.util.function.Supplier<Integer> typeLimit) {
        super(props, typeLimit, AEKeyType.fluids(), "tooltip.enderdrives.fluidenderdisk.disabled");
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {
        String scopePrefix = getSafeScopePrefix(stack);
        int freq = getFrequency(stack);
        String key = scopePrefix + "|" + freq;
        NetworkHandler.requestFluidDiskTypeCount(scopePrefix, freq, getTypeLimit());
        FluidDiskTypeInfo info = ClientFluidDiskCache.get(key);
        addCellInformationToTooltip(info.typeLimit(), info.typeCount(), lines, stack, freq);
    }

}
