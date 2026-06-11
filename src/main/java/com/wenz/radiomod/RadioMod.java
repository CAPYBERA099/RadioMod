package com.wenz.radiomod;

import com.wenz.radiomod.network.PacketHandler;
import com.wenz.radiomod.proxy.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = RadioMod.MODID, name = RadioMod.NAME, version = RadioMod.VERSION)
public class RadioMod {
    public static final String MODID = "radiomod";
    public static final String NAME = "RadioMod";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.Instance(MODID)
    public static RadioMod instance;

    @SidedProxy(
        clientSide = "com.wenz.radiomod.proxy.ClientProxy",
        serverSide = "com.wenz.radiomod.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PacketHandler.register();
        proxy.init(event);
    }
}
