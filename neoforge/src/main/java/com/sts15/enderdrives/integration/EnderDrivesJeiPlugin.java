package com.sts15.enderdrives.integration;

import com.sts15.enderdrives.items.ItemInit;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import static com.sts15.enderdrives.Constants.MOD_ID;

@JeiPlugin
public class EnderDrivesJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, "jei_plugin");
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        ItemStack stack = new ItemStack(ItemInit.TAPE_DISK.get());
        registration.addIngredientInfo(
                stack,
                VanillaTypes.ITEM_STACK,
                Component.literal("This drive only accepts items that are armor, tools, weapons, single stack items, or have non standard NBT.  Save the type space on your larger drives for what really matters.")
        );
    }
}