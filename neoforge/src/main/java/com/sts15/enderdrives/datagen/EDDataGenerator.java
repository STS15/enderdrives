package com.sts15.enderdrives.datagen;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.registries.VanillaRegistries;

import com.sts15.enderdrives.datagen.assets.EDBlockModelProvider;
import com.sts15.enderdrives.datagen.assets.EDItemModelProvider;
import com.sts15.enderdrives.datagen.data.EDRecipeProvider;

import java.util.concurrent.*;

import static com.sts15.enderdrives.Constants.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class EDDataGenerator {

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {

        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        ExistingFileHelper fileHelper = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookupProvider = CompletableFuture.supplyAsync(VanillaRegistries::createLookup, Util.backgroundExecutor());

        EDDataProvider provider = new EDDataProvider();

        // Assets
        provider.addSubProvider(event.includeClient(), new EDBlockModelProvider(packOutput, fileHelper));
        provider.addSubProvider(event.includeClient(), new EDItemModelProvider(packOutput, fileHelper));

        //data
        generator.addProvider(event.includeServer(), new EDRecipeProvider(packOutput, lookupProvider));

        generator.addProvider(true, provider);
    }
}
