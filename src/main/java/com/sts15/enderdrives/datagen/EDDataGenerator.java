package com.sts15.enderdrives.datagen;

import com.sts15.enderdrives.datagen.assets.EDBlockModelProvider;
import com.sts15.enderdrives.datagen.assets.EDItemModelProvider;
import com.sts15.enderdrives.datagen.data.EDOptionalRecipeProvider;
import com.sts15.enderdrives.datagen.data.EDRecipeProvider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import static com.sts15.enderdrives.Constants.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public final class EDDataGenerator {
    private EDDataGenerator() {
    }

    @SubscribeEvent
    public static void gatherClientData(GatherDataEvent.Client event) {
        event.createProvider(EDBlockModelProvider::new);
        event.createProvider(EDItemModelProvider::new);
        event.createProvider(EDRecipeProvider.Runner::new);
        event.createProvider(EDOptionalRecipeProvider::new);
    }
}
