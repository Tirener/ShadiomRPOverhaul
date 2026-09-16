package com.tirener.shadiom.shadiomrpoverhaul.names;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** Per-player persisted chosen name, stored in the player's own NBT. */
final class NamePlayerData {

    private static final String NAMESPACE = "shadiomrpoverhaul";
    private static final String KEY_FIRST = "names_first";
    private static final String KEY_SURNAME = "names_surname";

    private NamePlayerData() {}

    private static CompoundTag namespace(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(NAMESPACE, Tag.TAG_COMPOUND)) {
            root.put(NAMESPACE, new CompoundTag());
        }
        return root.getCompound(NAMESPACE);
    }

    static boolean hasPicked(ServerPlayer player) {
        return namespace(player).contains(KEY_FIRST);
    }

    static String getFirstName(ServerPlayer player) {
        CompoundTag ns = namespace(player);
        return ns.contains(KEY_FIRST) ? ns.getString(KEY_FIRST) : null;
    }

    static String getSurname(ServerPlayer player) {
        CompoundTag ns = namespace(player);
        return ns.contains(KEY_SURNAME) ? ns.getString(KEY_SURNAME) : null;
    }

    static void set(ServerPlayer player, String firstName, String surname) {
        CompoundTag ns = namespace(player);
        ns.putString(KEY_FIRST, firstName);
        ns.putString(KEY_SURNAME, surname);
    }
}
