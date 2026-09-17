package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One-time upgrade for worlds saved before territories existed. {@code ClaimsData.load} already
 * gives every pre-territories chunk its own placeholder territoryId (see that class); this pass
 * merges same-faction, connected chunks into one real territory each, and picks a capital for
 * every faction that doesn't have one yet. Idempotent by construction - a faction that already
 * has a {@code capitalTerritoryId} (migrated on a previous start, or created fresh under the new
 * system) is skipped entirely, so this is safe and cheap to run on every server start.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class TerritoryMigration {

    private TerritoryMigration() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        FactionsData factions = FactionsData.get(overworld);
        ClaimsData claims = ClaimsData.get(overworld);

        for (Faction faction : factions.all()) {
            if (faction.capitalTerritoryId() != null) continue;
            if (migrate(claims, faction)) factions.setDirty();
        }
    }

    /** Returns true if it actually assigned territories/a capital to {@code faction} - false (no
     *  mutation) when the faction has no claims yet, so the caller knows not to mark
     *  {@code FactionsData} dirty for nothing. Without this, a faction with no claims would never
     *  get a capital recorded, and this method would keep re-running - harmlessly, since it's a
     *  no-op - on every subsequent server start. */
    private static boolean migrate(ClaimsData claims, Faction faction) {
        Set<String> remaining = new HashSet<>(claims.claimsOf(faction.id()));
        if (remaining.isEmpty()) return false; // nothing to migrate - leave capital null

        List<String> territoryIdsInOrder = new ArrayList<>();
        String capitalCandidate = null;

        while (!remaining.isEmpty()) {
            String start = remaining.iterator().next();
            Set<String> component = floodFill(remaining, start);

            String territoryId = UUID.randomUUID().toString();
            boolean hasCenter = false;
            for (String chunkKey : component) {
                claims.reassignTerritoryId(chunkKey, territoryId);
                if (claims.get(chunkKey).factionCenter()) hasCenter = true;
            }
            faction.addTerritory(new Faction.Territory(territoryId, null));
            territoryIdsInOrder.add(territoryId);
            if (hasCenter && capitalCandidate == null) capitalCandidate = territoryId;
        }

        faction.setCapitalTerritoryId(capitalCandidate != null ? capitalCandidate : territoryIdsInOrder.get(0));
        return true;
    }

    /** 4-directional BFS over already-claimed chunk keys, matching the adjacency rule these
     *  chunks were originally grown under (see the old {@code isAdjacentToOwnClaim}). Removes
     *  every visited key from {@code remaining}. */
    private static Set<String> floodFill(Set<String> remaining, String start) {
        Set<String> component = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        remaining.remove(start);

        while (!queue.isEmpty()) {
            String key = queue.remove();
            component.add(key);
            for (String neighborKey : orthogonalNeighborKeys(key)) {
                if (remaining.remove(neighborKey)) queue.add(neighborKey);
            }
        }
        return component;
    }

    private static String[] orthogonalNeighborKeys(String chunkKey) {
        String[] parts = chunkKey.split(",");
        String dimension = parts[0];
        int x = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        return new String[] {
                dimension + "," + (x + 1) + "," + z,
                dimension + "," + (x - 1) + "," + z,
                dimension + "," + x + "," + (z + 1),
                dimension + "," + x + "," + (z - 1)
        };
    }
}
