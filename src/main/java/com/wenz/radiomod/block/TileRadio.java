package com.wenz.radiomod.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

public class TileRadio extends TileEntity {

    private String streamUrl = "";
    private boolean playing = false;
    private float volume = 1.0f;

    /**
     * Client-side callback for audio sync.
     * Set by ClientProxy. Called when tile state is synced from server.
     */
    public static IRadioSyncHandler syncHandler = null;

    public interface IRadioSyncHandler {
        void onSync(BlockPos pos, String url, boolean playing, float volume,
                    boolean wasPlaying, String oldUrl);
    }

    /* ---------- getters / setters ---------- */

    public String getStreamUrl()  { return streamUrl; }
    public boolean isPlaying()    { return playing; }
    public float   getVolume()    { return volume; }

    public void setStreamUrl(String url) {
        this.streamUrl = url != null ? url : "";
        markDirtyAndSync();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        markDirtyAndSync();
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        markDirtyAndSync();
    }

    /** Set all fields and sync once. */
    public void setState(String url, boolean playing, float volume) {
        this.streamUrl = url != null ? url : "";
        this.playing = playing;
        this.volume = Math.max(0f, Math.min(1f, volume));
        markDirtyAndSync();
    }

    private void markDirtyAndSync() {
        markDirty();
        if (world != null) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    /* ---------- NBT ---------- */

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("StreamUrl", streamUrl);
        compound.setBoolean("Playing", playing);
        compound.setFloat("Volume", volume);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        streamUrl = compound.getString("StreamUrl");
        playing   = compound.getBoolean("Playing");
        volume    = compound.hasKey("Volume") ? compound.getFloat("Volume") : 1.0f;
    }

    /* ---------- client sync ---------- */

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        // Save old state before reading new
        boolean wasPlaying = this.playing;
        String oldUrl = this.streamUrl;

        readFromNBT(pkt.getNbtCompound());

        // Trigger client-side audio sync
        if (world != null && world.isRemote && syncHandler != null) {
            syncHandler.onSync(pos, streamUrl, playing, volume, wasPlaying, oldUrl);
        }
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        boolean wasPlaying = this.playing;
        String oldUrl = this.streamUrl;

        readFromNBT(tag);

        // Also sync on initial chunk load
        if (world != null && world.isRemote && syncHandler != null) {
            syncHandler.onSync(pos, streamUrl, playing, volume, wasPlaying, oldUrl);
        }
    }
}
