package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
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
    private final Map<String, AnchorLocation> factionCenterLocations = new HashMap<>();

    record ClaimEntry(String factionId, String territoryId, boolean factionCenter) {}

    /** The exact block a territory's Faction Center sits at - chunk-level data isn't precise
     *  enough to remove the actual block, which disbanding needs to do. {@code blockPos} is a
     *  packed {@code BlockPos.asLong()}. */
    record AnchorLocation(String dimension, long blockPos) {}

    ClaimEntry get(String chunkKey) { return claims.get(chunkKey); }

    /** Every claim in the world, abandoned included - used to build the multi-faction territory
     *  map, unlike {@link #claimsOf} which is scoped to one faction. */
    Map<String, ClaimEntry> all() {
        return Collections.unmodifiableMap(claims);
    }

    Set<String> claimsOf(String factionId) {
        return claimsByFaction.getOrDefault(factionId, Set.of());
    }

    void recordFactionCenter(String territoryId, String dimension, long blockPos) {
        factionCenterLocations.put(territoryId, new AnchorLocation(dimension, blockPos));
        setDirty();
    }

    AnchorLocation factionCenterLocation(String territoryId) {
        return factionCenterLocations.get(territoryId);
    }

    void removeFactionCenterLocation(String territoryId) {
        factionCenterLocations.remove(territoryId);
        setDirty();
    }

    void claim(String chunkKey, String factionId, String territoryId, boolean factionCenter) {
        unindexPrevious(chunkKey);
        claims.put(chunkKey, new ClaimEntry(factionId, territoryId, factionCenter));
        claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(chunkKey);
        setDirty();
    }

    void unclaim(String chunkKey) {
        ClaimEntry entry = claims.remove(chunkKey);
        if (entry != null && entry.factionId() != null) {
            Set<String> owned = claimsByFaction.get(entry.factionId());
            if (owned != null) owned.remove(chunkKey);
        }
        setDirty();
    }

    /** Abandons a territory's chunk: keeps its territoryId (so a repossessing Faction Center can
     *  find the rest of the blob later) but clears ownership and the factionCenter flag, since
     *  the block that made it an anchor no longer exists. */
    void abandon(String chunkKey) {
        ClaimEntry entry = claims.get(chunkKey);
        if (entry == null) return;
        unindexPrevious(chunkKey);
        claims.put(chunkKey, new ClaimEntry(null, entry.territoryId(), false));
        setDirty();
    }

    /** Rewrites a chunk's territoryId in place without changing its owner - used only by
     *  territory migration, to merge per-chunk placeholder ids into one shared id per connected
     *  blob read from a pre-territories save. */
    void reassignTerritoryId(String chunkKey, String territoryId) {
        ClaimEntry entry = claims.get(chunkKey);
        if (entry == null) return;
        claims.put(chunkKey, new ClaimEntry(entry.factionId(), territoryId, entry.factionCenter()));
        setDirty();
    }

    /** Transfers every chunk of an abandoned territory to a new faction/territory at once - used
     *  when a Faction Center is planted inside abandoned land. */
    void repossess(Set<String> chunkKeys, String newFactionId, String newTerritoryId, String factionCenterChunkKey) {
        for (String chunkKey : chunkKeys) {
            boolean isAnchor = chunkKey.equals(factionCenterChunkKey);
            claims.put(chunkKey, new ClaimEntry(newFactionId, newTerritoryId, isAnchor));
        }
        claimsByFaction.computeIfAbsent(newFactionId, k -> new HashSet<>()).addAll(chunkKeys);
        setDirty();
    }

    /** Every chunk sharing a territoryId, regardless of owner (including abandoned chunks) - a
     *  plain scan, same "fine at this scale" precedent as {@link #releaseAll}. */
    Set<String> chunksOfTerritory(String territoryId) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, ClaimEntry> entry : claims.entrySet()) {
            if (territoryId.equals(entry.getValue().territoryId())) result.add(entry.getKey());
        }
        return result;
    }

    private void unindexPrevious(String chunkKey) {
        ClaimEntry previous = claims.get(chunkKey);
        if (previous != null && previous.factionId() != null) {
            Set<String> previousOwned = claimsByFaction.get(previous.factionId());
            if (previousOwned != null) previousOwned.remove(chunkKey);
        }
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
            if (entry.getValue().factionId() != null) value.putString("factionId", entry.getValue().factionId());
            value.putString("territoryId", entry.getValue().territoryId());
            value.putBoolean("factionCenter", entry.getValue().factionCenter());
            claimsTag.put(entry.getKey(), value);
        }
        nbt.put("claims", claimsTag);

        CompoundTag anchorsTag = new CompoundTag();
        for (Map.Entry<String, AnchorLocation> entry : factionCenterLocations.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putString("dimension", entry.getValue().dimension());
            value.putLong("pos", entry.getValue().blockPos());
            anchorsTag.put(entry.getKey(), value);
        }
        nbt.put("factionCenterLocations", anchorsTag);
        return nbt;
    }

    static ClaimsData load(CompoundTag nbt) {
        ClaimsData data = new ClaimsData();
        CompoundTag claimsTag = nbt.getCompound("claims");
        for (String key : claimsTag.getAllKeys()) {
            CompoundTag value = claimsTag.getCompound(key);
            String factionId = value.contains("factionId") ? value.getString("factionId") : null;
            // Pre-territories saves have "capital" instead of "factionCenter", and no
            // "territoryId" at all - each such chunk gets its own placeholder id (its own chunk
            // key), later merged per connected blob by TerritoryMigration.
            boolean factionCenter = value.contains("factionCenter")
                    ? value.getBoolean("factionCenter")
                    : value.getBoolean("capital");
            String territoryId = value.contains("territoryId") ? value.getString("territoryId") : key;
            data.claims.put(key, new ClaimEntry(factionId, territoryId, factionCenter));
            if (factionId != null) data.claimsByFaction.computeIfAbsent(factionId, k -> new HashSet<>()).add(key);
        }

        CompoundTag anchorsTag = nbt.getCompound("factionCenterLocations");
        for (String territoryId : anchorsTag.getAllKeys()) {
            CompoundTag value = anchorsTag.getCompound(territoryId);
            data.factionCenterLocations.put(territoryId, new AnchorLocation(value.getString("dimension"), value.getLong("pos")));
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
