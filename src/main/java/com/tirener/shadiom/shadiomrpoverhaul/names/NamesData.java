package com.tirener.shadiom.shadiomrpoverhaul.names;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Level-wide record of every taken (firstName, surname) combo, so no two players end up with the
 * same full name. Only ever grows: there is no rename feature, so nothing needs to be freed.
 */
final class NamesData extends SavedData {

    private final Set<String> takenCombos = new HashSet<>();

    /** Lowercased so two pool entries differing only in case can't both claim the same full name.
     *  This set is only ever compared against; the as-typed spelling players actually see lives in
     *  NamePlayerData. */
    private static String comboKey(String firstName, String surname) {
        return (firstName + "|" + surname).toLowerCase(Locale.ROOT);
    }

    boolean isTaken(String firstName, String surname) {
        return takenCombos.contains(comboKey(firstName, surname));
    }

    void markTaken(String firstName, String surname) {
        takenCombos.add(comboKey(firstName, surname));
        setDirty();
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (String combo : takenCombos) list.add(StringTag.valueOf(combo));
        nbt.put("takenCombos", list);
        return nbt;
    }

    static NamesData load(CompoundTag nbt) {
        NamesData data = new NamesData();
        ListTag list = nbt.getList("takenCombos", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) data.takenCombos.add(list.getString(i));
        return data;
    }

    /** Always reads/writes from the overworld's data storage, same convention as ModSavedData in
     *  ShadiomMod, so the record is shared across all dimensions on the same server. */
    static NamesData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                NamesData::load,
                NamesData::new,
                "shadiomrpoverhaul_names"
        );
    }
}
