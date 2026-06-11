package com.wenz.radiomod.network;

import com.wenz.radiomod.block.TileRadio;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSetUrl implements IMessage {

    private BlockPos pos;
    private String url;
    private boolean playing;
    private float volume;

    public PacketSetUrl() {} // Required for deserialization

    public PacketSetUrl(BlockPos pos, String url, boolean playing, float volume) {
        this.pos = pos;
        this.url = url;
        this.playing = playing;
        this.volume = volume;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
        url = ByteBufUtils.readUTF8String(buf);
        playing = buf.readBoolean();
        volume = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, url);
        buf.writeBoolean(playing);
        buf.writeFloat(volume);
    }

    public static class Handler implements IMessageHandler<PacketSetUrl, IMessage> {
        @Override
        public IMessage onMessage(PacketSetUrl msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer world = player.getServerWorld();

            world.addScheduledTask(() -> {
                // Security: verify player is near the block
                if (player.getDistanceSq(msg.pos) > 64 * 64) return;

                TileEntity te = world.getTileEntity(msg.pos);
                if (te instanceof TileRadio) {
                    TileRadio radio = (TileRadio) te;
                    radio.setStreamUrl(msg.url);
                    radio.setPlaying(msg.playing);
                    radio.setVolume(msg.volume);
                }
            });
            return null;
        }
    }
}
