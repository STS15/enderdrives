package com.sts15.enderdrives.client;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public record EnderDiskTooltipComponent(List<ItemStack> topItems) implements TooltipComponent {
    public List<ItemStack> getItems() {
        return topItems;
    }
}
