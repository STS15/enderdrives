package com.sts15.enderdrives.items;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import com.sts15.enderdrives.clientbridge.ClientHooks;
import com.sts15.enderdrives.db.DiskTypeInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;

public class EnderDiskItem extends AbstractEnderDiskItem
        implements ICellWorkbenchItem, IMenuItem {

    public EnderDiskItem(
            Properties properties,
            Supplier<Integer> typeLimit
    ) {
        super(
                properties,
                typeLimit,
                AEKeyType.items(),
                "tooltip.enderdrives.enderdisk.disabled"
        );
    }

    @Override
    public void addCellInformationToTooltip(
            ItemStack stack,
            List<Component> lines
    ) {
        String scopePrefix = getSafeScopePrefix(stack);
        int frequency = getFrequency(stack);

        DiskTypeInfo info = ClientHooks.getItemDiskInfo(
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