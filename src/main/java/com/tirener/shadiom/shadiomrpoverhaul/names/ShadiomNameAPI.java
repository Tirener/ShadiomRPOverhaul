package com.tirener.shadiom.shadiomrpoverhaul.names;

import net.minecraft.server.level.ServerPlayer;

/**
 * Public entry point for the names feature. Consuming mods register their pool content and any
 * per-player constraints here, never by touching {@link NameRegistry} or {@link NamePlayerData}
 * directly.
 */
public final class ShadiomNameAPI {

    private ShadiomNameAPI() {}

    public static void registerFirstName(String name) { NameRegistry.registerFirstName(name); }
    public static void registerSurname(String name) { NameRegistry.registerSurname(name); }
    public static void addFirstNameFilter(NameFilter filter) { NameRegistry.addFirstNameFilter(filter); }
    public static void addSurnameFilter(NameFilter filter) { NameRegistry.addSurnameFilter(filter); }

    public static boolean hasPicked(ServerPlayer player) { return NamePlayerData.hasPicked(player); }

    /** "FirstName Surname", or "" if the player hasn't picked yet. */
    public static String displayName(ServerPlayer player) {
        if (!NamePlayerData.hasPicked(player)) return "";
        return NamePlayerData.getFirstName(player) + " " + NamePlayerData.getSurname(player);
    }
}
