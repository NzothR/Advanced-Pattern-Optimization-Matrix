package com.NzothR.apm;

import com.NzothR.apm.block.BlockAdvPatternMatrix;
import com.NzothR.apm.config.APMConfig;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        APMConfig.load(event.getSuggestedConfigurationFile());
    }

    public void init(FMLInitializationEvent event) {
        BlockAdvPatternMatrix.register();
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}

    public void registerRenderers() {}
}
