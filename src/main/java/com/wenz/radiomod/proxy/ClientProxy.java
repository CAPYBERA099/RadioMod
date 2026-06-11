package com.wenz.radiomod.proxy;

import com.wenz.radiomod.block.TileRadio;
import com.wenz.radiomod.client.audio.RadioAudioManager;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
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
        TileRadio.syncHandler = new TileRadio.IRadioSyncHandler() {
            @Override
            public void onSync(BlockPos pos, String url, boolean playing, float volume, boolean repeat,
                               boolean wasPlaying, String oldUrl) {
                if (playing && (!wasPlaying || !url.equals(oldUrl))) {
                    if (!url.isEmpty()) {
                        RadioAudioManager.play(pos, url, volume);
                    }
                } else if (!playing && wasPlaying) {
                    RadioAudioManager.stop(pos);
                } else if (playing) {
                    RadioAudioManager.setVolume(pos, volume);
                }
            }
        };

        // Register repeat callback
        RadioAudioManager.repeatCallback = new RadioAudioManager.RepeatCallback() {
            @Override
            public void onTrackFinished(final BlockPos pos, final String url) {
                // Check if the TileEntity still exists and has repeat enabled
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (Minecraft.getMinecraft().world == null) return;
                        TileEntity te = Minecraft.getMinecraft().world.getTileEntity(pos);
                        if (te instanceof TileRadio) {
                            TileRadio radio = (TileRadio) te;
                            if (radio.isRepeat() && radio.isPlaying()) {
                                RadioAudioManager.play(pos, radio.getStreamUrl(), radio.getVolume());
                            }
                        }
                    }
                });
            }
        };
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            RadioAudioManager.tick();
        }
    }

    /** Stop all audio when player leaves world */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            RadioAudioManager.stopAll();
        }
    }
}
