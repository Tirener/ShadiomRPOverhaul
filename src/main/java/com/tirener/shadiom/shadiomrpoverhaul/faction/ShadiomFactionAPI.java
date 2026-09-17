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
 * claim ownership, and diplomacy. No mutation methods in this pass (create/kick/declare war/
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

    public static UUID leaderOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? null : faction.leader();
    }

    public static List<UUID> memberIdsOf(MinecraftServer server, String factionId) {
        Faction faction = FactionsData.get(server.overworld()).get(factionId);
        return faction == null ? List.of() : List.copyOf(faction.allMembers());
    }

    // -- Directory --

    public static List<String> allFactionIds(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        for (Faction faction : FactionsData.get(server.overworld()).all()) ids.add(faction.id());
        return ids;
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

    public static boolean isCapital(MinecraftServer server, ResourceKey<Level> dimension, ChunkPos chunk) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(server.overworld())
                .get(ClaimsData.chunkKey(dimension, chunk.x, chunk.z));
        return entry != null && entry.factionCenter();
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
}
