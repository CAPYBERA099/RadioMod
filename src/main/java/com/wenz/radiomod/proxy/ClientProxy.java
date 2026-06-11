package com.wenz.radiomod.proxy;

import com.wenz.radiomod.block.TileRadio;
import com.wenz.radiomod.client.audio.RadioAudioManager;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        MinecraftForge.EVENT_BUS.register(this);

        // Register client-side audio sync handler
        // When server syncs TileRadio state to all clients, this starts/stops audio
        TileRadio.syncHandler = new TileRadio.IRadioSyncHandler() {
            @Override
            public void onSync(BlockPos pos, String url, boolean playing, float volume,
                               boolean wasPlaying, String oldUrl) {
                if (playing && (!wasPlaying || !url.equals(oldUrl))) {
                    // Start or restart playback
                    if (!url.isEmpty()) {
                        RadioAudioManager.play(pos, url, volume);
                    }
                } else if (!playing && wasPlaying) {
                    // Stop playback
                    RadioAudioManager.stop(pos);
                } else if (playing) {
                    // Just update volume
                    RadioAudioManager.setVolume(pos, volume);
                }
            }
        };
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            RadioAudioManager.tick();
        }
    }

    /** Stop all audio when player leaves world (disconnect / quit to menu) */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            RadioAudioManager.stopAll();
        }
    }
}
