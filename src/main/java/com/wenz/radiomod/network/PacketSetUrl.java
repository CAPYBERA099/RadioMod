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
    private boolean repeat;
    private String trackTitle;

    public PacketSetUrl() {}

    public PacketSetUrl(BlockPos pos, String url, boolean playing, float volume, boolean repeat) {
        this(pos, url, playing, volume, repeat, "");
    }

    public PacketSetUrl(BlockPos pos, String url, boolean playing, float volume, boolean repeat, String trackTitle) {
        this.pos = pos;
        this.url = url;
        this.playing = playing;
        this.volume = volume;
        this.repeat = repeat;
        this.trackTitle = trackTitle != null ? trackTitle : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
        url = ByteBufUtils.readUTF8String(buf);
        playing = buf.readBoolean();
        volume = buf.readFloat();
        repeat = buf.readableBytes() > 0 ? buf.readBoolean() : false;
        trackTitle = buf.readableBytes() > 0 ? ByteBufUtils.readUTF8String(buf) : "";
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, url);
        buf.writeBoolean(playing);
        buf.writeFloat(volume);
        buf.writeBoolean(repeat);
        ByteBufUtils.writeUTF8String(buf, trackTitle);
    }

    public static class Handler implements IMessageHandler<PacketSetUrl, IMessage> {
        @Override
        public IMessage onMessage(PacketSetUrl msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer world = player.getServerWorld();

            world.addScheduledTask(() -> {
                if (player.getDistanceSq(msg.pos) > 64 * 64) return;

                TileEntity te = world.getTileEntity(msg.pos);
                if (te instanceof TileRadio) {
                    TileRadio radio = (TileRadio) te;
                    radio.setStateWithTitle(msg.url, msg.playing, msg.volume, msg.repeat, msg.trackTitle);
                }
            });
            return null;
        }
    }
}
