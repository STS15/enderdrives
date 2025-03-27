package com.sts15.enderdrives;

import net.fabricmc.api.ModInitializer;

public class Enderdrives implements ModInitializer {
    
    @Override
    public void onInitialize() {
        Constants.LOG.info("Hello Fabric world!");
        CommonClass.init();
    }
}
