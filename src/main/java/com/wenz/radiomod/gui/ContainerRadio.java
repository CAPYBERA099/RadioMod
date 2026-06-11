package com.wenz.radiomod.gui;

import com.wenz.radiomod.block.TileRadio;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

public class ContainerRadio extends Container {

    private final TileRadio tile;

    public ContainerRadio(TileRadio tile) {
        this.tile = tile;
    }

    public TileRadio getTile() {
        return tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }
}
