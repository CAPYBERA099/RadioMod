package com.wenz.radiomod.init;

import com.wenz.radiomod.RadioMod;
import com.wenz.radiomod.block.BlockRadio;
import com.wenz.radiomod.block.TileRadio;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@Mod.EventBusSubscriber(modid = RadioMod.MODID)
public class ModBlocks {

    public static final BlockRadio RADIO = new BlockRadio();

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(RADIO);
        GameRegistry.registerTileEntity(TileRadio.class,
                new ResourceLocation(RadioMod.MODID, "radio"));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(new ItemBlock(RADIO)
                .setRegistryName(RADIO.getRegistryName()));
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void registerModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(
                Item.getItemFromBlock(RADIO), 0,
                new ModelResourceLocation(RADIO.getRegistryName(), "inventory"));
    }
}
