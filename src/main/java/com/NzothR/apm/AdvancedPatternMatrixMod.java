package com.NzothR.apm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = AdvancedPatternMatrixMod.MODID,
    name = AdvancedPatternMatrixMod.NAME,
    version = Tags.VERSION,
    dependencies = "required-after:appliedenergistics2",
    acceptedMinecraftVersions = "[1.7.10]")
public class AdvancedPatternMatrixMod {

    public static final String MODID = "apm";
    public static final String NAME = "Advanced Pattern Matrix";
    public static final Logger LOG = LogManager.getLogger(MODID);

    /** AE2 version required by mixins — targeting rv3-beta-695 API surface */
    public static final String REQUIRED_AE2_VERSION = "rv3-beta-695";

    @Mod.Instance(MODID)
    public static AdvancedPatternMatrixMod instance;

    @SidedProxy(clientSide = "com.NzothR.apm.ClientProxy", serverSide = "com.NzothR.apm.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
