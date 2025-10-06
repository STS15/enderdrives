package com.sts15.enderdrives.datagen.data;

import appeng.recipes.game.StorageCellDisassemblyRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.conditions.IConditionBuilder;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.pedroksl.advanced_ae.common.definitions.AAEItems;
import com.glodblock.github.extendedae.common.EAESingletons;
import com.sts15.enderdrives.items.ItemInit;
import org.jetbrains.annotations.NotNull;
import appeng.api.util.AEColor;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import gripe._90.megacells.definition.MEGAItems;
import java.util.concurrent.*;

public class EDRecipeProvider extends RecipeProvider implements IConditionBuilder {

    public EDRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(@NotNull RecipeOutput recipeOutput) {

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_STORAGE_COMPONENT_1K.get())
                .pattern("aba")
                .pattern("bcb")
                .pattern("aba")
                .define('a', AEItems.SINGULARITY)
                .define('b', Items.ENDER_PEARL)
                .define('c', AEItems.SPATIAL_128_CELL_COMPONENT)
                .unlockedBy(String.format("has_%s", AEItems.SPATIAL_128_CELL_COMPONENT.id().getPath()), has(AEItems.SPATIAL_128_CELL_COMPONENT))
                .save(recipeOutput.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_vanilla", ItemInit.ENDER_STORAGE_COMPONENT_1K.getId()));

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_STORAGE_COMPONENT_1K.get())
                .pattern("aba")
                .pattern("bcb")
                .pattern("aba")
                .define('a', AAEItems.SHATTERED_SINGULARITY)
                .define('b', Items.ENDER_PEARL)
                .define('c', AAEItems.QUANTUM_STORAGE_COMPONENT)
                .unlockedBy(String.format("has_%s", AEItems.SPATIAL_128_CELL_COMPONENT.id().getPath()), has(AEItems.SPATIAL_128_CELL_COMPONENT))
                .save(recipeOutput.withConditions(
                        modLoaded("advanced_ae")
                ), String.format("%s_advanced", ItemInit.ENDER_STORAGE_COMPONENT_1K.getId()));

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_STORAGE_COMPONENT_1K.get())
                .pattern("aba")
                .pattern("bcb")
                .pattern("aba")
                .define('a', AEItems.SINGULARITY)
                .define('b', Items.ENDER_PEARL)
                .define('c', MEGAItems.CELL_COMPONENT_256M)
                .unlockedBy(String.format("has_%s", AEItems.SPATIAL_128_CELL_COMPONENT.id().getPath()), has(AEItems.SPATIAL_128_CELL_COMPONENT))
                .save(recipeOutput.withConditions(
                        modLoaded("megacells"),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_mega", ItemInit.ENDER_STORAGE_COMPONENT_1K.getId()));

        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_4K, ItemInit.ENDER_STORAGE_COMPONENT_1K, recipeOutput);
        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_16K, ItemInit.ENDER_STORAGE_COMPONENT_4K, recipeOutput);
        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_64K, ItemInit.ENDER_STORAGE_COMPONENT_16K, recipeOutput);
        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_256K, ItemInit.ENDER_STORAGE_COMPONENT_64K, recipeOutput);

        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_DISK_1K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_1K.get(),   Items.ENDER_CHEST);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_DISK_4K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_4K.get(),   Items.ENDER_CHEST);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_DISK_16K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_16K.get(),  Items.ENDER_CHEST);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_DISK_64K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_64K.get(),  Items.ENDER_CHEST);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), Items.ENDER_CHEST);

        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_FLUID_DISK_1K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_1K.get(),   AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_FLUID_DISK_4K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_4K.get(),   AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_FLUID_DISK_16K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_16K.get(),  AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_FLUID_DISK_64K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_64K.get(),  AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(recipeOutput, ItemInit.ENDER_FLUID_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), AEBlocks.SKY_STONE_TANK);

        addHousingCombine(recipeOutput, ItemInit.ENDER_ITEM_HOUSING.get(),  ItemInit.ENDER_STORAGE_COMPONENT_1K.get(),   ItemInit.ENDER_DISK_1K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_ITEM_HOUSING.get(),  ItemInit.ENDER_STORAGE_COMPONENT_4K.get(),   ItemInit.ENDER_DISK_4K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_ITEM_HOUSING.get(),  ItemInit.ENDER_STORAGE_COMPONENT_16K.get(),  ItemInit.ENDER_DISK_16K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_ITEM_HOUSING.get(),  ItemInit.ENDER_STORAGE_COMPONENT_64K.get(),  ItemInit.ENDER_DISK_64K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_ITEM_HOUSING.get(),  ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_DISK_256K.get());

        addHousingCombine(recipeOutput, ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(),   ItemInit.ENDER_FLUID_DISK_1K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(),   ItemInit.ENDER_FLUID_DISK_4K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(),  ItemInit.ENDER_FLUID_DISK_16K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(),  ItemInit.ENDER_FLUID_DISK_64K.get());
        addHousingCombine(recipeOutput, ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_FLUID_DISK_256K.get());

        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_DISK_1K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_1K.get(),   ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_DISK_4K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_4K.get(),   ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_DISK_16K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_16K.get(),  ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_DISK_64K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_64K.get(),  ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_ITEM_HOUSING.get());

        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_FLUID_DISK_1K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_1K.get(),   ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_FLUID_DISK_4K.get(),   ItemInit.ENDER_STORAGE_COMPONENT_4K.get(),   ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_FLUID_DISK_16K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_16K.get(),  ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_FLUID_DISK_64K.get(),  ItemInit.ENDER_STORAGE_COMPONENT_64K.get(),  ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(recipeOutput, ItemInit.ENDER_FLUID_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_FLUID_HOUSING.get());

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.TAPE_DISK.get())
                .pattern("aba")
                .pattern("bcb")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.SKY_DUST)
                .define('c', AEItems.CELL_COMPONENT_256K)
                .define('d', Items.NETHERITE_INGOT)
                .define('e', AEItems.COLORED_PAINT_BALL.item(AEColor.LIGHT_BLUE))
                .unlockedBy(String.format("has_%s", AEItems.CELL_COMPONENT_256K.id().getPath()), has(AEItems.CELL_COMPONENT_256K))
                .save(recipeOutput, ItemInit.TAPE_DISK.getId());

        makeEnderItemHousing(recipeOutput);
        makeEnderFluidHousing(recipeOutput);

    }

    private void makeEnderItemHousing(RecipeOutput out) {
        // Vanilla
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_ITEM_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', Items.NETHERITE_INGOT)
                .define('e', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("extendedae")),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_vanilla", ItemInit.ENDER_ITEM_HOUSING.getId()));

        // Mega
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_ITEM_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', MEGAItems.ACCUMULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', MEGAItems.SKY_STEEL_INGOT)
                .define('e', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        modLoaded("megacells"),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_mega", ItemInit.ENDER_ITEM_HOUSING.getId()));

        // Extended AE
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_ITEM_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', EAESingletons.CONCURRENT_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', EAESingletons.ENTRO_BLOCK)
                .define('e', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        modLoaded("extendedae"),
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_extended", ItemInit.ENDER_ITEM_HOUSING.getId()));

        // Advanced AE
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_ITEM_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AAEItems.QUANTUM_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', AAEItems.QUANTUM_ALLOY_PLATE)
                .define('e', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        modLoaded("advanced_ae")
                ), String.format("%s_advanced", ItemInit.ENDER_ITEM_HOUSING.getId()));
    }

    private void makeEnderFluidHousing(RecipeOutput out) {
        // Vanilla
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_FLUID_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', Items.NETHERITE_INGOT)
                .define('e', AEBlocks.SKY_STONE_TANK)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("extendedae")),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_vanilla", ItemInit.ENDER_FLUID_HOUSING.getId()));

        // Mega
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_FLUID_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', MEGAItems.ACCUMULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', MEGAItems.SKY_STEEL_INGOT)
                .define('e', AEBlocks.SKY_STONE_TANK)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        modLoaded("megacells"),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_mega", ItemInit.ENDER_FLUID_HOUSING.getId()));

        // Extended AE
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_FLUID_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', EAESingletons.CONCURRENT_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', EAESingletons.ENTRO_BLOCK)
                .define('e', AEBlocks.SKY_STONE_TANK)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        modLoaded("extendedae"),
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_extended", ItemInit.ENDER_FLUID_HOUSING.getId()));

        // Advanced AE
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ItemInit.ENDER_FLUID_HOUSING.get())
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AAEItems.QUANTUM_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', AAEItems.QUANTUM_ALLOY_PLATE)
                .define('e', AEBlocks.SKY_STONE_TANK)
                .unlockedBy(String.format("has_%s", AEItems.SKY_DUST.id().getPath()), has(AEItems.SKY_DUST))
                .save(out.withConditions(
                        modLoaded("advanced_ae")
                ), String.format("%s_advanced", ItemInit.ENDER_FLUID_HOUSING.getId()));
    }

    private void makeEnderDriveSet(RecipeOutput out, ItemLike resultDrive, ItemLike component, ItemLike catalyst) {
        // Vanilla
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, resultDrive)
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component)
                .define('e', Items.NETHERITE_INGOT)
                .define('f', catalyst)
                .unlockedBy("has_" + component.asItem().builtInRegistryHolder().key().location().getPath(), has(component))
                .save(out.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("extendedae")),
                        not(modLoaded("advanced_ae"))
                ), resultDrive.asItem().builtInRegistryHolder().key().location().withSuffix("_vanilla"));

        // Mega
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, resultDrive)
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', MEGAItems.ACCUMULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component)
                .define('e', MEGAItems.SKY_STEEL_INGOT)
                .define('f', catalyst)
                .unlockedBy("has_" + component.asItem().builtInRegistryHolder().key().location().getPath(), has(component))
                .save(out.withConditions(
                        modLoaded("megacells"),
                        not(modLoaded("advanced_ae"))
                ), resultDrive.asItem().builtInRegistryHolder().key().location().withSuffix("_mega"));

        // ExtendedAE
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, resultDrive)
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', EAESingletons.CONCURRENT_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component)
                .define('e', EAESingletons.ENTRO_BLOCK)
                .define('f', catalyst)
                .unlockedBy("has_" + component.asItem().builtInRegistryHolder().key().location().getPath(), has(component))
                .save(out.withConditions(
                        modLoaded("extendedae"),
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), resultDrive.asItem().builtInRegistryHolder().key().location().withSuffix("_extended"));

        // Advanced AE
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, resultDrive)
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AAEItems.QUANTUM_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component)
                .define('e', AAEItems.QUANTUM_ALLOY_PLATE)
                .define('f', catalyst)
                .unlockedBy("has_" + component.asItem().builtInRegistryHolder().key().location().getPath(), has(component))
                .save(out.withConditions(
                        modLoaded("advanced_ae")
                ), resultDrive.asItem().builtInRegistryHolder().key().location().withSuffix("_advanced"));
    }

    private void makeComponent(DeferredHolder<Item, ? extends Item> component, DeferredHolder<Item, ? extends Item> prev, RecipeOutput output) {
        // Vanilla
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, component.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("aca")
                .define('a', AEItems.SINGULARITY)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', prev.get())
                .define('d', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .unlockedBy(String.format("has_%s", prev.getId().getPath()), has(prev.get()))
                .save(output.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_vanilla", component.getId()));

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, component.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("aca")
                .define('a', AAEItems.SHATTERED_SINGULARITY)
                .define('b', AAEItems.QUANTUM_PROCESSOR)
                .define('c', prev.get())
                .define('d', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .unlockedBy(String.format("has_%s", prev.getId().getPath()), has(prev.get()))
                .save(output.withConditions(
                        modLoaded("advanced_ae")
                ), String.format("%s_advanced", component.getId()));

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, component.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("aca")
                .define('a', AEItems.SINGULARITY)
                .define('b', MEGAItems.ACCUMULATION_PROCESSOR)
                .define('c', prev.get())
                .define('d', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .unlockedBy(String.format("has_%s", prev.getId().getPath()), has(prev.get()))
                .save(output.withConditions(
                        modLoaded("megacells"),
                        not(modLoaded("advanced_ae"))
                ), String.format("%s_mega", component.getId()));

    }

    private void addDisassemblyRecipe(RecipeOutput out, ItemLike cell, ItemLike component, ItemLike housing) {
        var returns = new java.util.ArrayList<ItemStack>(2);
        returns.add(housing.asItem().getDefaultInstance());
        returns.add(component.asItem().getDefaultInstance());
        var recipe = new StorageCellDisassemblyRecipe(cell.asItem(), returns);
        ResourceLocation id = cell.asItem()
                .builtInRegistryHolder()
                .key()
                .location()
                .withPrefix("cell_upgrade/");
        out.accept(id, recipe, null);
    }

    private void addHousingCombine(RecipeOutput out, ItemLike housing, ItemLike component, ItemLike result) {
        var resultId = result.asItem()
                .builtInRegistryHolder()
                .key()
                .location()
                .withSuffix("_storage");

        net.minecraft.data.recipes.ShapelessRecipeBuilder
                .shapeless(RecipeCategory.MISC, result)
                .requires(housing)
                .requires(component)
                .unlockedBy("has_" + housing.asItem().builtInRegistryHolder().key().location().getPath(), has(housing))
                .unlockedBy("has_" + component.asItem().builtInRegistryHolder().key().location().getPath(), has(component))
                .save(out, resultId);
    }

}
