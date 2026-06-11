package com.wenz.radiomod.network;

import com.wenz.radiomod.block.RadioBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetUrlPacket {
    private final BlockPos pos;
    private final String url;
    private final boolean playing;
    private final float volume;

    public SetUrlPacket(BlockPos pos, String url, boolean playing, float volume) {
        this.pos = pos;
        this.url = url;
        this.playing = playing;
        this.volume = volume;
    }

    public static void encode(SetUrlPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.url, 512);
        buf.writeBoolean(pkt.playing);
        buf.writeFloat(pkt.volume);
    }

    public static SetUrlPacket decode(FriendlyByteBuf buf) {
        return new SetUrlPacket(
                buf.readBlockPos(),
                buf.readUtf(512),
                buf.readBoolean(),
                buf.readFloat());
    }

    public static void handle(SetUrlPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Security: verify player is near the block
            if (player.blockPosition().distSqr(pkt.pos) > 64 * 64) return;

            BlockEntity be = player.level().getBlockEntity(pkt.pos);
            if (be instanceof RadioBlockEntity radio) {
                radio.setStreamUrl(pkt.url);
                radio.setPlaying(pkt.playing);
                radio.setVolume(pkt.volume);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
