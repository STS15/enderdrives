package com.sts15.enderdrives.datagen.data;

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

        makeEnderCell(ItemInit.ENDER_DISK_1K, ItemInit.ENDER_STORAGE_COMPONENT_1K, recipeOutput);
        makeEnderCell(ItemInit.ENDER_DISK_4K, ItemInit.ENDER_STORAGE_COMPONENT_4K, recipeOutput);
        makeEnderCell(ItemInit.ENDER_DISK_16K, ItemInit.ENDER_STORAGE_COMPONENT_16K, recipeOutput);
        makeEnderCell(ItemInit.ENDER_DISK_64K, ItemInit.ENDER_STORAGE_COMPONENT_64K, recipeOutput);
        makeEnderCell(ItemInit.ENDER_DISK_256K, ItemInit.ENDER_STORAGE_COMPONENT_256K, recipeOutput);

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
    }

    private void makeEnderCell(DeferredHolder<Item, ? extends Item> disk,
                               DeferredHolder<Item, ? extends Item> component,
                               RecipeOutput output
    ) {

        // Vanilla
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, disk.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component.get())
                .define('e', Items.NETHERITE_INGOT)
                .define('f', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", component.getId().getPath()), has(component.get()))
                .save(output.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("extendedae")),
                        not(modLoaded("advanced_ae"))
                        ), String.format("%s_vanilla", disk.getId())
                );

        // Mega
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, disk.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', MEGAItems.ACCUMULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component.get())
                .define('e', MEGAItems.SKY_STEEL_INGOT)
                .define('f', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", component.getId().getPath()), has(component.get()))
                .save(output.withConditions(
                        modLoaded("megacells"),
                        not(modLoaded("advanced_ae"))
                        ), String.format("%s_mega", disk.getId())
                );

        // Extended
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, disk.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', EAESingletons.CONCURRENT_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component.get())
                .define('e', EAESingletons.ENTRO_BLOCK)
                .define('f', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", component.getId().getPath()), has(component.get()))
                .save(output.withConditions(
                        modLoaded("extendedae"),
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                        ), String.format("%s_extended", disk.getId())
                );

        // Advanced
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, disk.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AAEItems.QUANTUM_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component.get())
                .define('e', AAEItems.QUANTUM_ALLOY_PLATE)
                .define('f', Items.ENDER_CHEST)
                .unlockedBy(String.format("has_%s", component.getId().getPath()), has(component.get()))
                .save(output.withConditions(
                        modLoaded("advanced_ae")
                        ), String.format("%s_advanced", disk.getId())
                );

    }

    private void makeComponent(DeferredHolder<Item, ? extends Item> component,
                               DeferredHolder<Item, ? extends Item> prev,
                               RecipeOutput output
    ) {
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
}
