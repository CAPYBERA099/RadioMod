package com.wenz.radiomod.gui;

import com.wenz.radiomod.RadioMod;
import com.wenz.radiomod.block.TileRadio;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import javax.annotation.Nullable;

public class GuiHandler implements IGuiHandler {

    public static void register() {
        NetworkRegistry.INSTANCE.registerGuiHandler(RadioMod.instance, new GuiHandler());
    }

    @Nullable
    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == 0) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileRadio) {
                return new ContainerRadio((TileRadio) te, player);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == 0) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileRadio) {
                return new GuiRadio(new ContainerRadio((TileRadio) te, player));
            }
        }
        return null;
    }
}
