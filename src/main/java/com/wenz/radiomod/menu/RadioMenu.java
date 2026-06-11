package com.wenz.radiomod.menu;

import com.wenz.radiomod.RadioMod;
import com.wenz.radiomod.block.RadioBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public class RadioMenu extends AbstractContainerMenu {
    private final RadioBlockEntity blockEntity;

    /** Server-side constructor (called from RadioBlockEntity.createMenu). */
    public RadioMenu(int containerId, Inventory inv, RadioBlockEntity be) {
        super(RadioMod.RADIO_MENU.get(), containerId);
        this.blockEntity = be;
    }

    /** Client-side factory (called by IForgeMenuType). */
    public static RadioMenu createClient(int containerId, Inventory inv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof RadioBlockEntity radio) {
            return new RadioMenu(containerId, inv, radio);
        }
        throw new IllegalStateException("RadioBlockEntity not found at " + pos);
    }

    public RadioBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
