package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** World-wide chunk claims, keyed by a flat "dimension,chunkX,chunkZ" string. Storage only -
 *  {@code FactionEventHandler} and {@code ClaimProtectionHandler} hold every rule about who may
 *  claim/break what; this class just persists whatever it's told, same division of
 *  responsibility as {@link FactionsData}. */
final class ClaimsData extends SavedData {

    private final Map<String, ClaimEntry> claims = new HashMap<>();
    private final Map<String, Set<String>> claimsByFaction = new HashMap<>();

    record ClaimEntry(String factionId, boolean capital) {}

    ClaimEntry get(String chunkKey) { return claims.get(chunkKey); }

    Set<String> claimsOf(String factionId) {
        return claimsByFaction.getOrDefault(factionId, Set.of());
    }

    void claim(String chunkKey, String factionId, boolean capital) {
        ClaimEntry previous = claims.get(chunkKey);
        if (previous != null) {
            Set<String> previousOwned = claimsByFaction.get(previous.factionId());
            if (previousOwned != null) previousOwned.remove(chunkKey);
        }
        claims.put(chunkKey, new ClaimEntry(factionId, capital));
        claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(chunkKey);
        setDirty();
    }

    void unclaim(String chunkKey) {
        ClaimEntry entry = claims.remove(chunkKey);
        if (entry != null) {
            Set<String> owned = claimsByFaction.get(entry.factionId());
            if (owned != null) owned.remove(chunkKey);
        }
        setDirty();
    }

    void releaseAll(String factionId) {
        for (String chunkKey : Set.copyOf(claimsOf(factionId))) unclaim(chunkKey);
    }

    static String chunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return dimension.location() + "," + chunkX + "," + chunkZ;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        CompoundTag claimsTag = new CompoundTag();
        for (Map.Entry<String, ClaimEntry> entry : claims.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("factionId", entry.getValue().factionId());
            value.putBoolean("capital", entry.getValue().capital());
            claimsTag.put(entry.getKey(), value);
        }
        nbt.put("claims", claimsTag);
        return nbt;
    }

    static ClaimsData load(CompoundTag nbt) {
        ClaimsData data = new ClaimsData();
        CompoundTag claimsTag = nbt.getCompound("claims");
        for (String key : claimsTag.getAllKeys()) {
            CompoundTag value = claimsTag.getCompound(key);
            String factionId = value.getString("factionId");
            boolean capital = value.getBoolean("capital");
            data.claims.put(key, new ClaimEntry(factionId, capital));
            data.claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(key);
        }
        return data;
    }

    /** Same overworld-storage convention as {@link FactionsData#get}. */
    static ClaimsData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                ClaimsData::load,
                ClaimsData::new,
                "shadiomrpoverhaul_claims"
        );
    }
}
