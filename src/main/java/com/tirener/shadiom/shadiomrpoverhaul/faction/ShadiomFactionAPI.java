package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public, read-only entry point for the factions feature - membership, the faction directory,
 * claims/territories, and diplomacy. No mutation methods in this pass (create/kick/declare war/
 * etc. already exist as the in-game GUI flow with permission checks tied to the acting player -
 * see the design spec for why a programmatic mutation surface is a separate question). Mirrors
 * {@code ShadiomNameAPI}/{@code ShadiomTitleAPI}'s role as the public facade over this package's
 * otherwise package-private storage.
 */
public final class ShadiomFactionAPI {

    private ShadiomFactionAPI() {}

    // -- Membership --

    public static boolean hasFaction(ServerPlayer player) {
        return FactionsData.get(player.serverLevel()).factionOf(player.getUUID()) != null;
    }

    public static String factionOf(ServerPlayer player) {
        Faction faction = FactionsData.get(player.serverLevel()).factionOf(player.getUUID());
        return faction == null ? null : faction.name();
    }

    public static String factionIdOf(ServerPlayer player) {
        Faction faction = FactionsData.get(player.serverLevel()).factionOf(player.getUUID());
        return faction == null ? null : faction.id();
    }

    public static String roleOf(ServerPlayer player) {
        Faction faction = FactionsData.get(player.serverLevel()).factionOf(player.getUUID());
        if (faction == null) return null;
        Faction.Role role = faction.roleOf(player.getUUID());
        return role == null ? null : role.name();
    }

    /** Convenience over {@link #roleOf} - a faction-less player is never the leader of anything. */
    public static boolean isLeader(ServerPlayer player) {
        return "LEADER".equals(roleOf(player));
    }

    public static UUID leaderOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? null : faction.leader();
    }

    public static List<UUID> memberIdsOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? List.of() : List.copyOf(faction.allMembers());
    }

    public static int memberCountOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? 0 : faction.allMembers().size();
    }

    // -- Directory --

    public static boolean factionExists(MinecraftServer server, String factionId) {
        return FactionsData.get(server.overworld()).exists(factionId);
    }

    public static List<String> allFactionIds(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        for (Faction faction : FactionsData.get(server.overworld()).all()) ids.add(faction.id());
        return ids;
    }

    public static List<String> allFactionNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (Faction faction : FactionsData.get(server.overworld()).all()) names.add(faction.name());
        return names;
    }

    public static String factionName(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? null : faction.name();
    }

    // -- Claims --

    public static String claimOwner(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry == null ? null : entry.factionId();
    }

    /** True only if the chunk is claimed and inside the *capital* territory of its owning faction
     *  - not merely a chunk holding a Faction Center, since a faction can have several territories
     *  (each anchored by its own Faction Center) but only one designated capital. Use
     *  {@link #isFactionCenter} for "does this chunk have a Faction Center block". */
    public static boolean isCapital(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        if (entry == null || entry.factionId() == null) return false;
        Faction faction = FactionsData.get(server.overworld()).get(entry.factionId());
        return faction != null && entry.territoryId().equals(faction.capitalTerritoryId());
    }

    /** True if this exact chunk holds a Faction Center block - the anchor of whichever territory
     *  it belongs to, capital or not. */
    public static boolean isFactionCenter(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry != null && entry.factionCenter();
    }

    /** True if the chunk belongs to a territory whose Faction Center was broken without that
     *  being the faction's capital - open to build like wilderness, but still reserved against
     *  new territories founding too close, until some faction repossesses it. */
    public static boolean isAbandoned(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry != null && entry.factionId() == null;
    }

    /** The territoryId a chunk belongs to - claimed or abandoned, either has one - or {@code null}
     *  for plain unclaimed wilderness (no entry at all). */
    public static String territoryIdOfChunk(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry == null ? null : entry.territoryId();
    }

    /** A faction's claimed chunks in one dimension - abandoned chunks are excluded, since they no
     *  longer belong to any faction. */
    public static List<ChunkPos> ownedChunksOf(MinecraftServer server, String factionId, ResourceKey<Level> dimension) {
        String prefix = dimension.location() + ",";
        List<ChunkPos> chunks = new ArrayList<>();
        for (String key : ClaimsData.get(server.overworld()).claimsOf(factionId)) {
            if (key.startsWith(prefix)) chunks.add(ClaimsData.parseChunkPos(key));
        }
        return chunks;
    }

    // -- Territories --

    public static List<String> territoryIdsOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? List.of() : List.copyOf(faction.territories().keySet());
    }

    /** A territory's player-given name, or {@code null} if it hasn't been named. */
    public static String territoryName(MinecraftServer server, String factionId, String territoryId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        if (faction == null) return null;
        Faction.Territory territory = faction.territory(territoryId);
        return territory == null ? null : territory.name();
    }

    public static int territoryChunkCount(MinecraftServer server, String territoryId) {
        return ClaimsData.get(server.overworld()).territoryChunkCount(territoryId);
    }

    /** The territoryId whose destruction disbands the faction, or {@code null} if the faction
     *  doesn't exist. */
    public static String capitalTerritoryIdOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? null : faction.capitalTerritoryId();
    }

    // -- Diplomacy --

    public static String relationBetween(MinecraftServer server, String factionIdA, String factionIdB) {
        DiplomacyData.Relation relation = DiplomacyData.get(server.overworld())
                .relationBetween(factionIdA, factionIdB);
        return relation == null ? "NEUTRAL" : relation.name();
    }

    public static boolean areAllied(MinecraftServer server, String factionIdA, String factionIdB) {
        return DiplomacyData.get(server.overworld()).relationBetween(factionIdA, factionIdB)
                == DiplomacyData.Relation.ALLY;
    }

    public static boolean areAtWar(MinecraftServer server, String factionIdA, String factionIdB) {
        return DiplomacyData.get(server.overworld()).relationBetween(factionIdA, factionIdB)
                == DiplomacyData.Relation.WAR;
    }

    public static List<String> alliesOf(MinecraftServer server, String factionId) {
        return relatedFactionIds(server, factionId, DiplomacyData.Relation.ALLY);
    }

    public static List<String> enemiesOf(MinecraftServer server, String factionId) {
        return relatedFactionIds(server, factionId, DiplomacyData.Relation.WAR);
    }

    private static List<String> relatedFactionIds(MinecraftServer server, String factionId, DiplomacyData.Relation wanted) {
        List<String> result = new ArrayList<>();
        for (var entry : DiplomacyData.get(server.overworld()).allRelations()) {
            if (entry.getValue() != wanted) continue;
            String[] ids = entry.getKey().split("\\|", 2);
            if (ids.length != 2) continue;
            if (ids[0].equals(factionId)) result.add(ids[1]);
            else if (ids[1].equals(factionId)) result.add(ids[0]);
        }
        return result;
    }
}
