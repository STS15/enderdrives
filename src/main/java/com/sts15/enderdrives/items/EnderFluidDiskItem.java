package com.sts15.enderdrives.items;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import com.sts15.enderdrives.clientbridge.ClientHooks;
import com.sts15.enderdrives.db.FluidDiskTypeInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * Fluid-only EnderDrive disk item.
 *
 * Uses AEKeyType.fluids() for partitioning and workbench configuration.
 */
public class EnderFluidDiskItem extends AbstractEnderDiskItem
        implements ICellWorkbenchItem, IMenuItem {

    public EnderFluidDiskItem(
            Properties properties,
            Supplier<Integer> typeLimit
    ) {
        super(
                properties,
                typeLimit,
                AEKeyType.fluids(),
                "tooltip.enderdrives.fluidenderdisk.disabled"
        );
    }

    @Override
    public void addCellInformationToTooltip(
            ItemStack stack,
            List<Component> lines
    ) {
        String scopePrefix = getSafeScopePrefix(stack);
        int frequency = getFrequency(stack);

        FluidDiskTypeInfo info = ClientHooks.getFluidDiskInfo(
                scopePrefix,
                frequency,
                getTypeLimit()
        );

        addCellInformationToTooltip(
                getTypeLimit(),
                info.typeCount(),
                lines,
                stack,
                frequency
        );
    }
}