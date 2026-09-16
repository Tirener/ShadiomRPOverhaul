package com.tirener.shadiom.shadiomrpoverhaul.title;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** Per-player persisted active title id, stored in the player's own NBT. */
final class TitlePlayerData {

    private static final String NAMESPACE = "shadiomrpoverhaul";
    private static final String KEY_TITLE = "active_title";

    private TitlePlayerData() {}

    private static CompoundTag namespace(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(NAMESPACE, Tag.TAG_COMPOUND)) {
            root.put(NAMESPACE, new CompoundTag());
        }
        return root.getCompound(NAMESPACE);
    }

    static String get(ServerPlayer player) {
        CompoundTag ns = namespace(player);
        return ns.contains(KEY_TITLE) ? ns.getString(KEY_TITLE) : null;
    }

    static void set(ServerPlayer player, String titleId) {
        CompoundTag ns = namespace(player);
        if (titleId == null) ns.remove(KEY_TITLE);
        else ns.putString(KEY_TITLE, titleId);
    }
}
