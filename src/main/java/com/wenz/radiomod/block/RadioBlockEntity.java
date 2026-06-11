package com.wenz.radiomod.block;

import com.wenz.radiomod.RadioMod;
import com.wenz.radiomod.menu.RadioMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class RadioBlockEntity extends BlockEntity implements MenuProvider {
    private String streamUrl = "";
    private boolean playing = false;
    private float volume = 1.0f;

    public RadioBlockEntity(BlockPos pos, BlockState state) {
        super(RadioMod.RADIO_BE.get(), pos, state);
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
        setChanged();
    }

    private void markDirtyAndSync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /* ---------- NBT ---------- */

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("StreamUrl", streamUrl);
        tag.putBoolean("Playing", playing);
        tag.putFloat("Volume", volume);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        streamUrl = tag.getString("StreamUrl");
        playing   = tag.getBoolean("Playing");
        volume    = tag.contains("Volume") ? tag.getFloat("Volume") : 1.0f;
    }

    /* ---------- client sync ---------- */

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /* ---------- menu ---------- */

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.radiomod.radio");
    }

    @Nullable @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new RadioMenu(id, inv, this);
    }
}
