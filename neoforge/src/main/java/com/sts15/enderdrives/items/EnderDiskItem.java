package com.sts15.enderdrives.items;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import com.sts15.enderdrives.db.ClientDiskCache;
import com.sts15.enderdrives.db.DiskTypeInfo;
import com.sts15.enderdrives.network.NetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Supplier;

public class EnderDiskItem extends AbstractEnderDiskItem implements ICellWorkbenchItem, IMenuItem {

    public EnderDiskItem(Properties props, Supplier<Integer> typeLimit) {
        super(props, typeLimit, AEKeyType.items(), "tooltip.enderdrives.enderdisk.disabled");
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void addCellInformationToTooltip(ItemStack stack, List<Component> lines) {
        String scopePrefix = getSafeScopePrefix(stack);
        int freq = getFrequency(stack);
        String key = scopePrefix + "|" + freq;
        NetworkHandler.requestDiskTypeCount(scopePrefix, freq, getTypeLimit());
        DiskTypeInfo info = ClientDiskCache.get(key);
        addCellInformationToTooltip(info.typeLimit(), info.typeCount(), lines, stack, freq);
    }

}
