package com.sts15.enderdrives.datagen.data;

import appeng.api.util.AEColor;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.recipes.game.StorageCellDisassemblyRecipe;
import com.sts15.enderdrives.items.ItemInit;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.neoforged.neoforge.common.conditions.NeoForgeConditions.modLoaded;
import static net.neoforged.neoforge.common.conditions.NeoForgeConditions.not;

public final class EDRecipeProvider extends RecipeProvider {
    private EDRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        shaped(RecipeCategory.MISC, ItemInit.ENDER_STORAGE_COMPONENT_1K.get())
                .pattern("aba")
                .pattern("bcb")
                .pattern("aba")
                .define('a', AEItems.SINGULARITY)
                .define('b', Items.ENDER_PEARL)
                .define('c', AEItems.SPATIAL_128_CELL_COMPONENT)
                .unlockedBy("has_" + AEItems.SPATIAL_128_CELL_COMPONENT.id().getPath(), has(AEItems.SPATIAL_128_CELL_COMPONENT))
                .save(output.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), recipeKey(ItemInit.ENDER_STORAGE_COMPONENT_1K.getId().withSuffix("_vanilla")));

        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_4K, ItemInit.ENDER_STORAGE_COMPONENT_1K);
        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_16K, ItemInit.ENDER_STORAGE_COMPONENT_4K);
        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_64K, ItemInit.ENDER_STORAGE_COMPONENT_16K);
        makeComponent(ItemInit.ENDER_STORAGE_COMPONENT_256K, ItemInit.ENDER_STORAGE_COMPONENT_64K);

        makeEnderDriveSet(ItemInit.ENDER_DISK_1K.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(), Items.ENDER_CHEST);
        makeEnderDriveSet(ItemInit.ENDER_DISK_4K.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(), Items.ENDER_CHEST);
        makeEnderDriveSet(ItemInit.ENDER_DISK_16K.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(), Items.ENDER_CHEST);
        makeEnderDriveSet(ItemInit.ENDER_DISK_64K.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(), Items.ENDER_CHEST);
        makeEnderDriveSet(ItemInit.ENDER_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), Items.ENDER_CHEST);

        makeEnderDriveSet(ItemInit.ENDER_FLUID_DISK_1K.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(), AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(ItemInit.ENDER_FLUID_DISK_4K.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(), AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(ItemInit.ENDER_FLUID_DISK_16K.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(), AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(ItemInit.ENDER_FLUID_DISK_64K.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(), AEBlocks.SKY_STONE_TANK);
        makeEnderDriveSet(ItemInit.ENDER_FLUID_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), AEBlocks.SKY_STONE_TANK);

        addHousingCombine(ItemInit.ENDER_ITEM_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(), ItemInit.ENDER_DISK_1K.get());
        addHousingCombine(ItemInit.ENDER_ITEM_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(), ItemInit.ENDER_DISK_4K.get());
        addHousingCombine(ItemInit.ENDER_ITEM_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(), ItemInit.ENDER_DISK_16K.get());
        addHousingCombine(ItemInit.ENDER_ITEM_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(), ItemInit.ENDER_DISK_64K.get());
        addHousingCombine(ItemInit.ENDER_ITEM_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_DISK_256K.get());

        addHousingCombine(ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(), ItemInit.ENDER_FLUID_DISK_1K.get());
        addHousingCombine(ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(), ItemInit.ENDER_FLUID_DISK_4K.get());
        addHousingCombine(ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(), ItemInit.ENDER_FLUID_DISK_16K.get());
        addHousingCombine(ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(), ItemInit.ENDER_FLUID_DISK_64K.get());
        addHousingCombine(ItemInit.ENDER_FLUID_HOUSING.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_FLUID_DISK_256K.get());

        addDisassemblyRecipe(ItemInit.ENDER_DISK_1K.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(), ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_DISK_4K.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(), ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_DISK_16K.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(), ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_DISK_64K.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(), ItemInit.ENDER_ITEM_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_ITEM_HOUSING.get());

        addDisassemblyRecipe(ItemInit.ENDER_FLUID_DISK_1K.get(), ItemInit.ENDER_STORAGE_COMPONENT_1K.get(), ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_FLUID_DISK_4K.get(), ItemInit.ENDER_STORAGE_COMPONENT_4K.get(), ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_FLUID_DISK_16K.get(), ItemInit.ENDER_STORAGE_COMPONENT_16K.get(), ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_FLUID_DISK_64K.get(), ItemInit.ENDER_STORAGE_COMPONENT_64K.get(), ItemInit.ENDER_FLUID_HOUSING.get());
        addDisassemblyRecipe(ItemInit.ENDER_FLUID_DISK_256K.get(), ItemInit.ENDER_STORAGE_COMPONENT_256K.get(), ItemInit.ENDER_FLUID_HOUSING.get());

        shaped(RecipeCategory.MISC, ItemInit.TAPE_DISK.get())
                .pattern("aba")
                .pattern("bcb")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.SKY_DUST)
                .define('c', AEItems.CELL_COMPONENT_256K)
                .define('d', Items.NETHERITE_INGOT)
                .define('e', AEItems.COLORED_PAINT_BALL.item(AEColor.LIGHT_BLUE))
                .unlockedBy("has_" + AEItems.CELL_COMPONENT_256K.id().getPath(), has(AEItems.CELL_COMPONENT_256K))
                .save(output);

        makeEnderHousing(ItemInit.ENDER_ITEM_HOUSING.get(), Items.ENDER_CHEST);
        makeEnderHousing(ItemInit.ENDER_FLUID_HOUSING.get(), AEBlocks.SKY_STONE_TANK);
    }

    private void makeEnderHousing(ItemLike result, ItemLike catalyst) {
        shaped(RecipeCategory.MISC, result)
                .pattern("aba")
                .pattern("c c")
                .pattern("ded")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', Items.NETHERITE_INGOT)
                .define('e', catalyst)
                .unlockedBy("has_" + AEItems.SKY_DUST.id().getPath(), has(AEItems.SKY_DUST))
                .save(output.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("extendedae")),
                        not(modLoaded("advanced_ae"))
                ), recipeKey(itemId(result).withSuffix("_vanilla")));
    }

    private void makeEnderDriveSet(ItemLike resultDrive, ItemLike component, ItemLike catalyst) {
        shaped(RecipeCategory.MISC, resultDrive)
                .pattern("aba")
                .pattern("cdc")
                .pattern("efe")
                .define('a', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', AEItems.SKY_DUST)
                .define('d', component)
                .define('e', Items.NETHERITE_INGOT)
                .define('f', catalyst)
                .unlockedBy("has_" + itemId(component).getPath(), has(component))
                .save(output.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("extendedae")),
                        not(modLoaded("advanced_ae"))
                ), recipeKey(itemId(resultDrive).withSuffix("_vanilla")));
    }

    private void makeComponent(DeferredHolder<Item, ? extends Item> component, DeferredHolder<Item, ? extends Item> previous) {
        shaped(RecipeCategory.MISC, component.get())
                .pattern("aba")
                .pattern("cdc")
                .pattern("aca")
                .define('a', AEItems.SINGULARITY)
                .define('b', AEItems.CALCULATION_PROCESSOR)
                .define('c', previous.get())
                .define('d', AEBlocks.QUARTZ_VIBRANT_GLASS)
                .unlockedBy("has_" + previous.getId().getPath(), has(previous.get()))
                .save(output.withConditions(
                        not(modLoaded("megacells")),
                        not(modLoaded("advanced_ae"))
                ), recipeKey(component.getId().withSuffix("_vanilla")));
    }

    private void addDisassemblyRecipe(ItemLike cell, ItemLike component, ItemLike housing) {
        StorageCellDisassemblyRecipe recipe = new StorageCellDisassemblyRecipe(
                cell.asItem(),
                List.of(new ItemStackTemplate(housing.asItem()), new ItemStackTemplate(component.asItem()))
        );
        output.accept(recipeKey(itemId(cell).withPrefix("cell_upgrade/")), recipe, null);
    }

    private void addHousingCombine(ItemLike housing, ItemLike component, ItemLike result) {
        shapeless(RecipeCategory.MISC, result)
                .requires(housing)
                .requires(component)
                .unlockedBy("has_" + itemId(housing).getPath(), has(housing))
                .unlockedBy("has_" + itemId(component).getPath(), has(component))
                .save(output, recipeKey(itemId(result).withSuffix("_storage")));
    }

    private static Identifier itemId(ItemLike item) {
        return BuiltInRegistries.ITEM.getKey(item.asItem());
    }

    private static ResourceKey<Recipe<?>> recipeKey(Identifier id) {
        return ResourceKey.create(Registries.RECIPE, id);
    }

    public static final class Runner extends RecipeProvider.Runner {
        public Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new EDRecipeProvider(registries, output);
        }

        @Override
        public String getName() {
            return "Ender Drives Recipes";
        }
    }
}
