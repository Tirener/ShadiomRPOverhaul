package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** World-wide relations between factions, keyed by a canonical "idA|idB" pair (sorted so order
 *  doesn't matter), plus pending incoming alliance proposals. Storage only - {@code
 *  FactionEventHandler} holds every rule about who may change what, same division of
 *  responsibility as {@link ClaimsData}. */
final class DiplomacyData extends SavedData {

    enum Relation { ALLY, WAR }

    private final Map<String, Relation> relations = new HashMap<>();
    private final Map<String, Set<String>> allianceProposals = new HashMap<>();

    static String pairKey(String factionIdA, String factionIdB) {
        return factionIdA.compareTo(factionIdB) <= 0
                ? factionIdA + "|" + factionIdB
                : factionIdB + "|" + factionIdA;
    }

    /** {@code null} means NEUTRAL - only ALLY/WAR are ever actually stored. */
    Relation relationBetween(String factionIdA, String factionIdB) {
        return relations.get(pairKey(factionIdA, factionIdB));
    }

    void setRelation(String factionIdA, String factionIdB, Relation relation) {
        relations.put(pairKey(factionIdA, factionIdB), relation);
        setDirty();
    }

    void clearRelation(String factionIdA, String factionIdB) {
        relations.remove(pairKey(factionIdA, factionIdB));
        setDirty();
    }

    Set<String> proposalsFor(String factionId) {
        return allianceProposals.getOrDefault(factionId, Set.of());
    }

    void propose(String fromFactionId, String toFactionId) {
        allianceProposals.computeIfAbsent(toFactionId, k -> new HashSet<>()).add(fromFactionId);
        setDirty();
    }

    void clearProposal(String fromFactionId, String toFactionId) {
        Set<String> proposals = allianceProposals.get(toFactionId);
        if (proposals == null) return;
        proposals.remove(fromFactionId);
        if (proposals.isEmpty()) allianceProposals.remove(toFactionId);
        setDirty();
    }

    Set<Map.Entry<String, Relation>> allRelations() {
        return relations.entrySet();
    }

    /** Removes every relation and proposal involving {@code factionId} - called on disband, same
     *  orphan-prevention concern {@code ClaimsData.releaseAll} solves for claims. A plain linear
     *  scan, not a reverse index - see the design spec for why that's fine at this scale. */
    void releaseAll(String factionId) {
        relations.keySet().removeIf(key -> involves(key, factionId));
        allianceProposals.keySet().removeIf(id -> id.equals(factionId));
        for (Set<String> proposers : allianceProposals.values()) proposers.remove(factionId);
        allianceProposals.values().removeIf(Set::isEmpty);
        setDirty();
    }

    private static boolean involves(String pairKey, String factionId) {
        String[] parts = pairKey.split("\\|", 2);
        return parts.length == 2 && (parts[0].equals(factionId) || parts[1].equals(factionId));
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        CompoundTag relationsTag = new CompoundTag();
        for (Map.Entry<String, Relation> entry : relations.entrySet()) {
            relationsTag.putString(entry.getKey(), entry.getValue().name());
        }
        nbt.put("relations", relationsTag);

        CompoundTag proposalsTag = new CompoundTag();
        for (Map.Entry<String, Set<String>> entry : allianceProposals.entrySet()) {
            ListTag list = new ListTag();
            for (String id : entry.getValue()) list.add(StringTag.valueOf(id));
            proposalsTag.put(entry.getKey(), list);
        }
        nbt.put("allianceProposals", proposalsTag);
        return nbt;
    }

    static DiplomacyData load(CompoundTag nbt) {
        DiplomacyData data = new DiplomacyData();

        CompoundTag relationsTag = nbt.getCompound("relations");
        for (String key : relationsTag.getAllKeys()) {
            data.relations.put(key, Relation.valueOf(relationsTag.getString(key)));
        }

        CompoundTag proposalsTag = nbt.getCompound("allianceProposals");
        for (String key : proposalsTag.getAllKeys()) {
            ListTag list = proposalsTag.getList(key, Tag.TAG_STRING);
            Set<String> set = new HashSet<>();
            for (int i = 0; i < list.size(); i++) set.add(list.getString(i));
            data.allianceProposals.put(key, set);
        }
        return data;
    }

    static DiplomacyData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                DiplomacyData::load,
                DiplomacyData::new,
                "shadiomrpoverhaul_diplomacy"
        );
    }
}
