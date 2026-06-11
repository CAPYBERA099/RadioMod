package com.wenz.radiomod.network;

import com.wenz.radiomod.RadioMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String VERSION = "1";
    public static SimpleChannel CHANNEL;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(RadioMod.MODID, "main"),
                () -> VERSION, VERSION::equals, VERSION::equals);

        CHANNEL.registerMessage(0, SetUrlPacket.class,
                SetUrlPacket::encode, SetUrlPacket::decode, SetUrlPacket::handle);
    }

    /** Convenience: send a Set-URL packet to server. */
    public static void sendSetUrl(BlockPos pos, String url, boolean playing, float volume) {
        CHANNEL.sendToServer(new SetUrlPacket(pos, url, playing, volume));
    }
}
