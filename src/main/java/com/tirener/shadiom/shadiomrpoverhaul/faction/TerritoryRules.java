package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure geometry rules shared by claim-extension ({@link FactionEventHandler#handleAction}) and
 * Faction Center placement ({@link ClaimProtectionHandler}): the universal 1-chunk gap between
 * any two territories (own or foreign, active or abandoned), enforced via 8-way adjacency, and
 * 4-way growth-adjacency for extending your own land (unchanged from the pre-territories rule).
 * See the territories design spec for why one 8-way "no other territoryId nearby" check covers
 * both the anti-slither case against enemies and the "can't bridge two of my own territories"
 * case without needing to special-case either.
 */
final class TerritoryRules {

    private TerritoryRules() {}

    static final int MAX_TERRITORY_CHUNKS = 100;

    private static final int[][] ORTHOGONAL = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
    private static final int[][] SURROUNDING_8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** The one faction-owned territory adjacent (4-dir) to this chunk, or null if zero or more
     *  than one match - either way there's no single territory to unambiguously extend. */
    static String territoryToExtend(ClaimsData claims, String factionId,
                                     ResourceKey<Level> dimension, ChunkPos chunk) {
        Set<String> ownTerritories = new HashSet<>();
        for (int[] d : ORTHOGONAL) {
            ClaimsData.ClaimEntry neighbor = claims.get(ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1]));
            if (neighbor != null && factionId.equals(neighbor.factionId())) ownTerritories.add(neighbor.territoryId());
        }
        return ownTerritories.size() == 1 ? ownTerritories.iterator().next() : null;
    }

    /** True if any of the 8 surrounding chunks belongs to a territory other than
     *  {@code territoryId} - own or foreign, active or abandoned. The universal gap rule. */
    static boolean anyOtherTerritoryAdjacent(ClaimsData claims, String territoryId,
                                              ResourceKey<Level> dimension, ChunkPos chunk) {
        for (int[] d : SURROUNDING_8) {
            ClaimsData.ClaimEntry neighbor = claims.get(ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1]));
            if (neighbor != null && !territoryId.equals(neighbor.territoryId())) return true;
        }
        return false;
    }

    /** True if any of the 8 surrounding chunks belongs to any territory at all - used when
     *  founding a brand new territory, which has no chunks of its own yet to be the exception. */
    static boolean anyTerritoryAdjacent(ClaimsData claims, ResourceKey<Level> dimension, ChunkPos chunk) {
        for (int[] d : SURROUNDING_8) {
            if (claims.get(ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1])) != null) return true;
        }
        return false;
    }

    static int territoryChunkCount(ClaimsData claims, String territoryId) {
        return claims.chunksOfTerritory(territoryId).size();
    }
}
