package com.wenz.radiomod.network;

import com.wenz.radiomod.RadioMod;
import com.wenz.radiomod.gui.GuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class PacketHandler {

    public static final SimpleNetworkWrapper INSTANCE =
            NetworkRegistry.INSTANCE.newSimpleChannel(RadioMod.MODID);

    public static void register() {
        INSTANCE.registerMessage(PacketSetUrl.Handler.class, PacketSetUrl.class, 0, Side.SERVER);

        // Also register GUI handler
        GuiHandler.register();
    }
}
