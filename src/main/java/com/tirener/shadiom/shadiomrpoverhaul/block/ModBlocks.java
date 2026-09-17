package com.tirener.shadiom.shadiomrpoverhaul.block;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {

    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Shadiomrpoverhaul.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Shadiomrpoverhaul.MODID);

    public static final RegistryObject<Block> FACTION_CENTER = BLOCKS.register("faction_center",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(3.0F)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> FACTION_CENTER_ITEM = ITEMS.register("faction_center",
            () -> new BlockItem(FACTION_CENTER.get(), new Item.Properties()));

    @Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class CreativeTab {

        private CreativeTab() {}

        @SubscribeEvent
        public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
            if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
                event.accept(FACTION_CENTER_ITEM);
            }
        }
    }
}
