package com.wenz.radiomod;

import com.wenz.radiomod.block.RadioBlock;
import com.wenz.radiomod.block.RadioBlockEntity;
import com.wenz.radiomod.client.ClientSetup;
import com.wenz.radiomod.menu.RadioMenu;
import com.wenz.radiomod.network.ModNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RadioMod.MODID)
public class RadioMod {
    public static final String MODID = "radiomod";
    public static final Logger LOGGER = LoggerFactory.getLogger("RadioMod");

    // --- Deferred Registers ---
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // --- Objects ---
    public static final RegistryObject<Block> RADIO_BLOCK = BLOCKS.register("radio",
            () -> new RadioBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.5f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final RegistryObject<Item> RADIO_ITEM = ITEMS.register("radio",
            () -> new BlockItem(RADIO_BLOCK.get(), new Item.Properties()));

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<RadioBlockEntity>> RADIO_BE =
            BLOCK_ENTITIES.register("radio",
                    () -> BlockEntityType.Builder.of(RadioBlockEntity::new, RADIO_BLOCK.get())
                            .build(null));

    public static final RegistryObject<MenuType<RadioMenu>> RADIO_MENU = MENUS.register("radio",
            () -> IForgeMenuType.create(RadioMenu::createClient));

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register("radiomod_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.radiomod"))
                    .icon(() -> RADIO_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> output.accept(RADIO_ITEM.get()))
                    .build());

    public RadioMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        CREATIVE_TABS.register(bus);

        bus.addListener(this::commonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.register(bus);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }
}
