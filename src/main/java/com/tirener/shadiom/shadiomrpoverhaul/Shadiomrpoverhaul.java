package com.tirener.shadiom.shadiomrpoverhaul;

import com.tirener.shadiom.shadiomrpoverhaul.block.ModBlocks;
import com.tirener.shadiom.shadiomrpoverhaul.names.DefaultNames;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Shared roleplay layer for Shadiom: cosmetic titles and chosen names, both purely display-side.
 * Consuming mods go through {@code ShadiomTitleAPI} and {@code ShadiomNameAPI}; everything else
 * is wired up by the event subscribers in the title and names packages.
 */
@Mod(Shadiomrpoverhaul.MODID)
public class Shadiomrpoverhaul {

    public static final String MODID = "shadiomrpoverhaul";

    public Shadiomrpoverhaul() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModNetwork.register();
        DefaultNames.register();
    }
}
