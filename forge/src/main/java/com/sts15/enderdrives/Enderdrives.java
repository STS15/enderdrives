package com.sts15.enderdrives;

import net.minecraftforge.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class Enderdrives {

    public Enderdrives() {
        Constants.LOG.info("Hello Forge world!");
        CommonClass.init();

    }
}