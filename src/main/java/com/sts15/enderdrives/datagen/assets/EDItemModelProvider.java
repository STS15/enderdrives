package com.sts15.enderdrives.datagen.assets;

import com.sts15.enderdrives.client.DriveStatusProperty;
import com.sts15.enderdrives.items.ItemInit;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Holder;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.List;
import java.util.stream.Stream;

import static com.sts15.enderdrives.Constants.MOD_ID;

public final class EDItemModelProvider extends ModelProvider {
    public EDItemModelProvider(PackOutput output) {
        super(output, MOD_ID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        enderDiskColors(itemModels, ItemInit.ENDER_DISK_1K);
        enderDiskColors(itemModels, ItemInit.ENDER_DISK_4K);
        enderDiskColors(itemModels, ItemInit.ENDER_DISK_16K);
        enderDiskColors(itemModels, ItemInit.ENDER_DISK_64K);
        enderDiskColors(itemModels, ItemInit.ENDER_DISK_256K);
        enderDiskColors(itemModels, ItemInit.ENDER_DISK_creative);

        enderDiskColors(itemModels, ItemInit.ENDER_FLUID_DISK_1K);
        enderDiskColors(itemModels, ItemInit.ENDER_FLUID_DISK_4K);
        enderDiskColors(itemModels, ItemInit.ENDER_FLUID_DISK_16K);
        enderDiskColors(itemModels, ItemInit.ENDER_FLUID_DISK_64K);
        enderDiskColors(itemModels, ItemInit.ENDER_FLUID_DISK_256K);
        enderDiskColors(itemModels, ItemInit.ENDER_FLUID_DISK_creative);

        enderDiskColors(itemModels, ItemInit.TAPE_DISK);

        generated(itemModels, ItemInit.ENDER_STORAGE_COMPONENT_1K);
        generated(itemModels, ItemInit.ENDER_STORAGE_COMPONENT_4K);
        generated(itemModels, ItemInit.ENDER_STORAGE_COMPONENT_16K);
        generated(itemModels, ItemInit.ENDER_STORAGE_COMPONENT_64K);
        generated(itemModels, ItemInit.ENDER_STORAGE_COMPONENT_256K);
        generated(itemModels, ItemInit.ENDER_ITEM_HOUSING);
        generated(itemModels, ItemInit.ENDER_FLUID_HOUSING);
    }

    private static void generated(ItemModelGenerators itemModels, DeferredHolder<Item, ? extends Item> item) {
        itemModels.generateFlatItem(item.get(), ModelTemplates.FLAT_ITEM);
    }

    private static void enderDiskColors(ItemModelGenerators itemModels, DeferredHolder<Item, ? extends Item> diskHolder) {
        Item disk = diskHolder.get();
        Identifier baseModel = ModelLocationUtils.getModelLocation(disk);
        Material body = TextureMapping.getItemTexture(disk);

        Identifier green = layeredModel(itemModels, baseModel, body, "green");
        Identifier blue = layeredModel(itemModels, baseModel, body, "blue");
        Identifier yellow = layeredModel(itemModels, baseModel, body, "yellow");
        Identifier red = layeredModel(itemModels, baseModel, body, "red");

        itemModels.itemModelOutput.accept(
                disk,
                ItemModelUtils.rangeSelect(
                        new DriveStatusProperty(),
                        ItemModelUtils.plainModel(green),
                        List.of(
                                ItemModelUtils.override(ItemModelUtils.plainModel(blue), 1.0F),
                                ItemModelUtils.override(ItemModelUtils.plainModel(yellow), 2.0F),
                                ItemModelUtils.override(ItemModelUtils.plainModel(red), 3.0F)
                        )
                )
        );
    }

    private static Identifier layeredModel(ItemModelGenerators itemModels, Identifier baseModel, Material body, String color) {
        Identifier model = baseModel.withSuffix("_" + color);
        return ModelTemplates.TWO_LAYERED_ITEM.create(
                model,
                TextureMapping.layered(body, new Material(modId("item/ender_disk_led_" + color))),
                itemModels.modelOutput
        );
    }

    private static Identifier modId(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    protected Stream<? extends Holder<Block>> getKnownBlocks() {
        return Stream.empty();
    }

    @Override
    public String getName() {
        return "Ender Drives Item Models";
    }
}
