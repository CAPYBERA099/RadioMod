package com.wenz.radiomod.gui;

import com.wenz.radiomod.block.TileRadio;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

public class ContainerRadio extends Container {

    private final TileRadio tile;
    private final EntityPlayer player;

    public ContainerRadio(TileRadio tile, EntityPlayer player) {
        this.tile = tile;
        this.player = player;
    }

    public TileRadio getTile() {
        return tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        // Release the GUI lock when player closes the interface
        tile.unlock(player);
    }
}
