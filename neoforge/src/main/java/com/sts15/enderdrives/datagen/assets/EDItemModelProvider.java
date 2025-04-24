package com.sts15.enderdrives.datagen.assets;

import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredHolder;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import com.sts15.enderdrives.items.ItemInit;

import java.util.*;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class EDItemModelProvider extends ItemModelProvider {

    public EDItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerModels() {

        enderDiskColors(ItemInit.ENDER_DISK_1K);
        enderDiskColors(ItemInit.ENDER_DISK_4K);
        enderDiskColors(ItemInit.ENDER_DISK_16K);
        enderDiskColors(ItemInit.ENDER_DISK_64K);
        enderDiskColors(ItemInit.ENDER_DISK_256K);
        enderDiskColors(ItemInit.ENDER_DISK_creative);
        enderDiskColors(ItemInit.TAPE_DISK);

        generated(ItemInit.ENDER_STORAGE_COMPONENT_1K);
        generated(ItemInit.ENDER_STORAGE_COMPONENT_4K);
        generated(ItemInit.ENDER_STORAGE_COMPONENT_16K);
        generated(ItemInit.ENDER_STORAGE_COMPONENT_64K);
        generated(ItemInit.ENDER_STORAGE_COMPONENT_256K);
    }

    private void generated(DeferredHolder<Item, ? extends Item> item) {
        getBuilder(item.getId().getPath())
                .parent(getExistingFile(mcLoc("item/generated")))
                .texture("layer0", modLoc(String.format("item/%s", item.getId().getPath())));
    }

    private void enderDiskColors(DeferredHolder<Item, ? extends Item> disk) {

        ArrayList<ModelFile> colors = new ArrayList<>();
        ResourceLocation status = ResourceLocation.fromNamespaceAndPath(MOD_ID, "status");

        // 0 = green, 1 = blue, 2 = yellow, 3 = red
        for (String color : Arrays.asList("green", "blue", "yellow", "red")) {
            colors.add(getBuilder(String.format("%s_%s", disk.getId().getPath(), color))
                    .parent(getExistingFile(mcLoc("item/generated")))
                    .texture("layer0", modLoc(String.format("item/%s", disk.getId().getPath())))
                    .texture("layer1", modLoc(String.format("item/ender_disk_led_%s", color))));
        }

        getBuilder(disk.getId().getPath())
                .parent(getExistingFile(mcLoc("item/generated")))
                .texture("layer0", modLoc(String.format("item/%s", disk.getId().getPath())))
                .texture("layer1", modLoc("item/ender_disk_led_green"))
                .override().predicate(status, 1).model(colors.get(1)).end()  // blue
                .override().predicate(status, 2).model(colors.get(2)).end()  // yellow
                .override().predicate(status, 3).model(colors.get(3)).end(); // red
    }
}
