package com.sts15.enderdrives.datagen.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.sts15.enderdrives.Constants.MOD_ID;

/**
 * Writes conditional recipes by identifier so optional integration classes never enter the compile classpath.
 */
public final class EDOptionalRecipeProvider implements DataProvider {
    private static final List<LoadCondition> ADVANCED = List.of(loaded("advanced_ae"));
    private static final List<LoadCondition> MEGA = List.of(loaded("megacells"), notLoaded("advanced_ae"));
    private static final List<LoadCondition> EXTENDED = List.of(
            loaded("extendedae"),
            notLoaded("megacells"),
            notLoaded("advanced_ae")
    );

    private final PackOutput.PathProvider recipePath;
    private final PackOutput.PathProvider advancementPath;

    public EDOptionalRecipeProvider(PackOutput output) {
        this.recipePath = output.createRegistryElementsPathProvider(Registries.RECIPE);
        this.advancementPath = output.createRegistryElementsPathProvider(Registries.ADVANCEMENT);
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        for (RecipeSpec recipe : createRecipes()) {
            tasks.add(DataProvider.saveStable(cache, recipeJson(recipe), recipePath.json(recipe.id())));
            Identifier advancementId = recipe.id().withPrefix("recipes/misc/");
            tasks.add(DataProvider.saveStable(cache, advancementJson(recipe), advancementPath.json(advancementId)));
        }
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Ender Drives Optional Integration Recipes";
    }

    private static List<RecipeSpec> createRecipes() {
        List<RecipeSpec> recipes = new ArrayList<>();

        recipes.add(recipe(
                "ender_storage_component_1k_advanced",
                "enderdrives:ender_storage_component_1k",
                List.of("aba", "bcb", "aba"),
                Map.of(
                        "a", "advanced_ae:shattered_singularity",
                        "b", "minecraft:ender_pearl",
                        "c", "advanced_ae:quantum_storage_component"
                ),
                "ae2:spatial_cell_component_128",
                ADVANCED
        ));
        recipes.add(recipe(
                "ender_storage_component_1k_mega",
                "enderdrives:ender_storage_component_1k",
                List.of("aba", "bcb", "aba"),
                Map.of(
                        "a", "ae2:singularity",
                        "b", "minecraft:ender_pearl",
                        "c", "megacells:cell_component_256m"
                ),
                "ae2:spatial_cell_component_128",
                MEGA
        ));

        addComponentRecipes(recipes, "ender_storage_component_4k", "ender_storage_component_1k");
        addComponentRecipes(recipes, "ender_storage_component_16k", "ender_storage_component_4k");
        addComponentRecipes(recipes, "ender_storage_component_64k", "ender_storage_component_16k");
        addComponentRecipes(recipes, "ender_storage_component_256k", "ender_storage_component_64k");

        addHousingRecipes(recipes, "ender_item_housing", "minecraft:ender_chest");
        addHousingRecipes(recipes, "ender_fluid_housing", "ae2:sky_stone_tank");

        addDriveRecipes(recipes, "ender_disk_1k", "ender_storage_component_1k", "minecraft:ender_chest");
        addDriveRecipes(recipes, "ender_disk_4k", "ender_storage_component_4k", "minecraft:ender_chest");
        addDriveRecipes(recipes, "ender_disk_16k", "ender_storage_component_16k", "minecraft:ender_chest");
        addDriveRecipes(recipes, "ender_disk_64k", "ender_storage_component_64k", "minecraft:ender_chest");
        addDriveRecipes(recipes, "ender_disk_256k", "ender_storage_component_256k", "minecraft:ender_chest");

        addDriveRecipes(recipes, "ender_fluid_disk_1k", "ender_storage_component_1k", "ae2:sky_stone_tank");
        addDriveRecipes(recipes, "ender_fluid_disk_4k", "ender_storage_component_4k", "ae2:sky_stone_tank");
        addDriveRecipes(recipes, "ender_fluid_disk_16k", "ender_storage_component_16k", "ae2:sky_stone_tank");
        addDriveRecipes(recipes, "ender_fluid_disk_64k", "ender_storage_component_64k", "ae2:sky_stone_tank");
        addDriveRecipes(recipes, "ender_fluid_disk_256k", "ender_storage_component_256k", "ae2:sky_stone_tank");

        return recipes;
    }

    private static void addComponentRecipes(List<RecipeSpec> recipes, String result, String previous) {
        String resultId = "enderdrives:" + result;
        String previousId = "enderdrives:" + previous;
        recipes.add(recipe(
                result + "_advanced",
                resultId,
                List.of("aba", "cdc", "aca"),
                Map.of(
                        "a", "advanced_ae:shattered_singularity",
                        "b", "advanced_ae:quantum_processor",
                        "c", previousId,
                        "d", "ae2:quartz_vibrant_glass"
                ),
                previousId,
                ADVANCED
        ));
        recipes.add(recipe(
                result + "_mega",
                resultId,
                List.of("aba", "cdc", "aca"),
                Map.of(
                        "a", "ae2:singularity",
                        "b", "megacells:accumulation_processor",
                        "c", previousId,
                        "d", "ae2:quartz_vibrant_glass"
                ),
                previousId,
                MEGA
        ));
    }

    private static void addHousingRecipes(List<RecipeSpec> recipes, String result, String catalyst) {
        addHousingRecipe(
                recipes,
                result,
                "advanced",
                "advanced_ae:quantum_processor",
                "advanced_ae:quantum_alloy_plate",
                catalyst,
                ADVANCED
        );
        addHousingRecipe(
                recipes,
                result,
                "mega",
                "megacells:accumulation_processor",
                "megacells:sky_steel_ingot",
                catalyst,
                MEGA
        );
        addHousingRecipe(
                recipes,
                result,
                "extended",
                "extendedae:concurrent_processor",
                "extendedae:entro_block",
                catalyst,
                EXTENDED
        );
    }

    private static void addHousingRecipe(
            List<RecipeSpec> recipes,
            String result,
            String suffix,
            String processor,
            String material,
            String catalyst,
            List<LoadCondition> conditions
    ) {
        recipes.add(recipe(
                result + "_" + suffix,
                "enderdrives:" + result,
                List.of("aba", "c c", "ded"),
                Map.of(
                        "a", "ae2:quartz_vibrant_glass",
                        "b", processor,
                        "c", "ae2:sky_dust",
                        "d", material,
                        "e", catalyst
                ),
                "ae2:sky_dust",
                conditions
        ));
    }

    private static void addDriveRecipes(List<RecipeSpec> recipes, String result, String component, String catalyst) {
        addDriveRecipe(
                recipes,
                result,
                component,
                catalyst,
                "advanced",
                "advanced_ae:quantum_processor",
                "advanced_ae:quantum_alloy_plate",
                ADVANCED
        );
        addDriveRecipe(
                recipes,
                result,
                component,
                catalyst,
                "mega",
                "megacells:accumulation_processor",
                "megacells:sky_steel_ingot",
                MEGA
        );
        addDriveRecipe(
                recipes,
                result,
                component,
                catalyst,
                "extended",
                "extendedae:concurrent_processor",
                "extendedae:entro_block",
                EXTENDED
        );
    }

    private static void addDriveRecipe(
            List<RecipeSpec> recipes,
            String result,
            String component,
            String catalyst,
            String suffix,
            String processor,
            String material,
            List<LoadCondition> conditions
    ) {
        String componentId = "enderdrives:" + component;
        recipes.add(recipe(
                result + "_" + suffix,
                "enderdrives:" + result,
                List.of("aba", "cdc", "efe"),
                Map.of(
                        "a", "ae2:quartz_vibrant_glass",
                        "b", processor,
                        "c", "ae2:sky_dust",
                        "d", componentId,
                        "e", material,
                        "f", catalyst
                ),
                componentId,
                conditions
        ));
    }

    private static RecipeSpec recipe(
            String path,
            String result,
            List<String> pattern,
            Map<String, String> key,
            String unlockItem,
            List<LoadCondition> conditions
    ) {
        return new RecipeSpec(Identifier.fromNamespaceAndPath(MOD_ID, path), result, pattern, key, unlockItem, conditions);
    }

    private static JsonObject recipeJson(RecipeSpec recipe) {
        JsonObject json = new JsonObject();
        json.add("neoforge:conditions", conditionsJson(recipe.conditions()));
        json.addProperty("type", "minecraft:crafting_shaped");
        json.addProperty("category", "misc");

        JsonObject key = new JsonObject();
        recipe.key().forEach(key::addProperty);
        json.add("key", key);

        JsonArray pattern = new JsonArray();
        recipe.pattern().forEach(pattern::add);
        json.add("pattern", pattern);

        JsonObject result = new JsonObject();
        result.addProperty("id", recipe.result());
        json.add("result", result);
        return json;
    }

    private static JsonObject advancementJson(RecipeSpec recipe) {
        JsonObject json = new JsonObject();
        json.add("neoforge:conditions", conditionsJson(recipe.conditions()));
        json.addProperty("parent", "minecraft:recipes/root");

        String unlockName = "has_" + Identifier.parse(recipe.unlockItem()).getPath().replace('/', '_');
        JsonObject criteria = new JsonObject();

        JsonObject recipeUnlocked = new JsonObject();
        recipeUnlocked.addProperty("trigger", "minecraft:recipe_unlocked");
        JsonObject recipeUnlockedConditions = new JsonObject();
        recipeUnlockedConditions.addProperty("recipe", recipe.id().toString());
        recipeUnlocked.add("conditions", recipeUnlockedConditions);
        criteria.add("has_the_recipe", recipeUnlocked);

        JsonObject inventoryChanged = new JsonObject();
        inventoryChanged.addProperty("trigger", "minecraft:inventory_changed");
        JsonObject inventoryConditions = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("items", recipe.unlockItem());
        items.add(item);
        inventoryConditions.add("items", items);
        inventoryChanged.add("conditions", inventoryConditions);
        criteria.add(unlockName, inventoryChanged);
        json.add("criteria", criteria);

        JsonArray requirement = new JsonArray();
        requirement.add("has_the_recipe");
        requirement.add(unlockName);
        JsonArray requirements = new JsonArray();
        requirements.add(requirement);
        json.add("requirements", requirements);

        JsonArray recipeRewards = new JsonArray();
        recipeRewards.add(recipe.id().toString());
        JsonObject rewards = new JsonObject();
        rewards.add("recipes", recipeRewards);
        json.add("rewards", rewards);
        return json;
    }

    private static JsonArray conditionsJson(List<LoadCondition> conditions) {
        JsonArray result = new JsonArray();
        for (LoadCondition condition : conditions) {
            JsonObject loaded = new JsonObject();
            loaded.addProperty("type", "neoforge:mod_loaded");
            loaded.addProperty("modid", condition.modId());
            if (condition.negated()) {
                JsonObject not = new JsonObject();
                not.addProperty("type", "neoforge:not");
                not.add("value", loaded);
                result.add(not);
            } else {
                result.add(loaded);
            }
        }
        return result;
    }

    private static LoadCondition loaded(String modId) {
        return new LoadCondition(modId, false);
    }

    private static LoadCondition notLoaded(String modId) {
        return new LoadCondition(modId, true);
    }

    private record LoadCondition(String modId, boolean negated) {
    }

    private record RecipeSpec(
            Identifier id,
            String result,
            List<String> pattern,
            Map<String, String> key,
            String unlockItem,
            List<LoadCondition> conditions
    ) {
    }
}
