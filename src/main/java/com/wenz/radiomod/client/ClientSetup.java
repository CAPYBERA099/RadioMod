package com.wenz.radiomod.client;

import com.wenz.radiomod.RadioMod;
import com.wenz.radiomod.client.audio.RadioAudioManager;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public class ClientSetup {

    public static void register(IEventBus modBus) {
        modBus.addListener(ClientSetup::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
            MenuScreens.register(RadioMod.RADIO_MENU.get(), RadioScreen::new));
    }

    /* Forge bus events (client only) */
    @Mod.EventBusSubscriber(modid = RadioMod.MODID, value = Dist.CLIENT,
                            bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ForgeEvents {

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                RadioAudioManager.tick();
            }
        }

        @SubscribeEvent
        public static void onWorldUnload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                RadioAudioManager.stopAll();
            }
        }
    }
}
