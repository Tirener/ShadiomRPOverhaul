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

    /** Forces the name picker open so a player can pick a new name, even if they already have one
     *  - e.g. from a staff tool or an in-character event trigger you control. No cooldown, no
     *  permission check, and no trigger of its own: the caller decides when a rename should
     *  happen. The player's current name stays reserved to them until they actually confirm a new
     *  one - nothing is given up just by opening the picker. A no-op if the name pool has nothing
     *  to offer (same guard as the first-join picker). */
    public static void openRenamePicker(ServerPlayer player) { NameEventHandler.openPickerForRename(player); }
}
