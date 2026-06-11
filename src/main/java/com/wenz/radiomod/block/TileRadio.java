package com.wenz.radiomod.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

public class TileRadio extends TileEntity {

    private String streamUrl = "";
    private boolean playing = false;
    private float volume = 1.0f;
    private boolean repeat = false;
    private String trackTitle = "";

    /* ---------- GUI lock ---------- */
    private UUID lockedByUUID = null;
    private String lockedByName = "";

    public static IRadioSyncHandler syncHandler = null;

    public interface IRadioSyncHandler {
        void onSync(BlockPos pos, String url, boolean playing, float volume, boolean repeat,
                    boolean wasPlaying, String oldUrl);
    }

    /* ---------- getters / setters ---------- */

    public String getStreamUrl()  { return streamUrl; }
    public boolean isPlaying()    { return playing; }
    public float   getVolume()    { return volume; }
    public boolean isRepeat()     { return repeat; }
    public String  getTrackTitle(){ return trackTitle; }

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

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
        markDirtyAndSync();
    }

    public void setTrackTitle(String title) {
        this.trackTitle = title != null ? title : "";
        markDirtyAndSync();
    }

    /** Set all fields and sync once. */
    public void setState(String url, boolean playing, float volume, boolean repeat) {
        this.streamUrl = url != null ? url : "";
        this.playing = playing;
        this.volume = Math.max(0f, Math.min(1f, volume));
        this.repeat = repeat;
        markDirtyAndSync();
    }

    /** Set all fields including title and sync once. */
    public void setStateWithTitle(String url, boolean playing, float volume, boolean repeat, String title) {
        this.streamUrl = url != null ? url : "";
        this.playing = playing;
        this.volume = Math.max(0f, Math.min(1f, volume));
        this.repeat = repeat;
        this.trackTitle = title != null ? title : "";
        markDirtyAndSync();
    }

    /* ---------- GUI lock methods ---------- */

    public boolean isLocked() { return lockedByUUID != null; }

    public boolean isLockedBy(EntityPlayer player) {
        return lockedByUUID != null && lockedByUUID.equals(player.getUniqueID());
    }

    public String getLockedByName() { return lockedByName; }

    public void lock(EntityPlayer player) {
        this.lockedByUUID = player.getUniqueID();
        this.lockedByName = player.getName();
    }

    public void unlock(EntityPlayer player) {
        if (lockedByUUID != null && lockedByUUID.equals(player.getUniqueID())) {
            lockedByUUID = null;
            lockedByName = "";
        }
    }

    public void forceUnlock() {
        lockedByUUID = null;
        lockedByName = "";
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
        compound.setBoolean("Repeat", repeat);
        compound.setString("TrackTitle", trackTitle);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        streamUrl  = compound.getString("StreamUrl");
        playing    = compound.getBoolean("Playing");
        volume     = compound.hasKey("Volume") ? compound.getFloat("Volume") : 1.0f;
        repeat     = compound.getBoolean("Repeat");
        trackTitle = compound.hasKey("TrackTitle") ? compound.getString("TrackTitle") : "";
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
        boolean wasPlaying = this.playing;
        String oldUrl = this.streamUrl;
        readFromNBT(pkt.getNbtCompound());
        if (world != null && world.isRemote && syncHandler != null) {
            syncHandler.onSync(pos, streamUrl, playing, volume, repeat, wasPlaying, oldUrl);
        }
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        boolean wasPlaying = this.playing;
        String oldUrl = this.streamUrl;
        readFromNBT(tag);
        if (world != null && world.isRemote && syncHandler != null) {
            syncHandler.onSync(pos, streamUrl, playing, volume, repeat, wasPlaying, oldUrl);
        }
    }
}
