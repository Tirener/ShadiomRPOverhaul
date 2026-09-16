package com.tirener.shadiom.shadiomrpoverhaul;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import net.minecraftforge.fml.common.Mod;

/**
 * Shared roleplay layer for Shadiom: cosmetic titles and chosen names, both purely display-side.
 * Consuming mods go through {@code ShadiomTitleAPI} and {@code ShadiomNameAPI}; everything else
 * is wired up by the event subscribers in the title and names packages.
 */
@Mod(Shadiomrpoverhaul.MODID)
public class Shadiomrpoverhaul {

    public static final String MODID = "shadiomrpoverhaul";

    public Shadiomrpoverhaul() {
        ModNetwork.register();
    }
}
