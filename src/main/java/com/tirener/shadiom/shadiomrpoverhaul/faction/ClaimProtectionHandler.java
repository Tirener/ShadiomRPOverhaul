package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.block.ModBlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/** Build/break protection inside claimed chunks, and the Faction Center's special
 *  placement/break rules (see the design spec) - everyone else's build/break rule is simply
 *  "member of the owning faction, or the chunk isn't claimed". */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class ClaimProtectionHandler {

    private ClaimProtectionHandler() {}

    /** Faction Centers - and so capitals/claims - may only be founded in a vanilla dimension. */
    private static final Set<ResourceKey<Level>> CIVILIZED_DIMENSIONS =
            Set.of(Level.OVERWORLD, Level.NETHER, Level.END);

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ClaimsData claims = ClaimsData.get(level);
        String key = ClaimsData.chunkKey(level.dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null || claimEntry.factionId() == null) return; // unclaimed or abandoned - open

        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());

        if (event.getState().is(ModBlocks.FACTION_CENTER.get())) {
            boolean canBreak = isMember && FactionPermissions.canBreakFactionCenter(faction.roleOf(sp.getUUID()));
            if (!canBreak) {
                event.setCanceled(true);
                return;
            }
            FactionEventHandler.destroyFactionCenter(sp.getServer(), faction, claimEntry.territoryId());
            return;
        }

        if (!isMember) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ResourceKey<Level> dimension = level.dimension();
        String key = ClaimsData.chunkKey(dimension, chunk.x, chunk.z);
        ClaimsData claims = ClaimsData.get(level);
        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());

        if (event.getPlacedBlock().is(ModBlocks.FACTION_CENTER.get())) {
            if (!CIVILIZED_DIMENSIONS.contains(dimension)) {
                event.setCanceled(true);
                sp.sendSystemMessage(Component.literal("This is no place for civilization."));
                return;
            }

            ClaimsData.ClaimEntry existing = claims.get(key);

            if (faction == null) {
                if (existing != null) { event.setCanceled(true); return; } // claimed or abandoned land
                if (FactionEventHandler.hasPendingCapital(sp.getUUID())) { event.setCanceled(true); return; }
                if (TerritoryRules.anyTerritoryAdjacent(claims, dimension, chunk)) { event.setCanceled(true); return; }
                FactionEventHandler.recordPendingCapital(sp, dimension, chunk, event.getPos());
                return;
            }

            if (!FactionPermissions.canPlaceFactionCenter(faction.roleOf(sp.getUUID()))) {
                event.setCanceled(true);
                return;
            }

            if (existing == null) {
                if (TerritoryRules.anyTerritoryAdjacent(claims, dimension, chunk)) { event.setCanceled(true); return; }
                String territoryId = java.util.UUID.randomUUID().toString();
                faction.addTerritory(new Faction.Territory(territoryId, null));
                factions.setDirty();
                claims.claim(key, faction.id(), territoryId, true);
                claims.recordFactionCenter(territoryId, dimension.location().toString(), event.getPos().asLong());
                FactionEventHandler.broadcastTerritoryMap(sp.getServer());
                return;
            }

            if (existing.factionId() == null) {
                // Abandoned land - repossess the whole former territory, not just this chunk.
                Set<String> blob = claims.chunksOfTerritory(existing.territoryId());
                String territoryId = java.util.UUID.randomUUID().toString();
                faction.addTerritory(new Faction.Territory(territoryId, null));
                factions.setDirty();
                claims.repossess(blob, faction.id(), territoryId, key);
                claims.recordFactionCenter(territoryId, dimension.location().toString(), event.getPos().asLong());
                FactionEventHandler.broadcastTerritoryMap(sp.getServer());
                return;
            }

            event.setCanceled(true); // already claimed by someone, including your own faction
            return;
        }

        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null || claimEntry.factionId() == null) return;
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());
        if (!isMember) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(ModBlocks.FACTION_CENTER.get())) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ClaimsData claims = ClaimsData.get(level);
        String key = ClaimsData.chunkKey(level.dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry entry = claims.get(key);
        if (entry == null || entry.factionId() == null) return;

        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());
        if (faction == null || !faction.id().equals(entry.factionId())) return;
        if (!FactionPermissions.canManageTerritory(faction.roleOf(sp.getUUID()))) return;

        event.setCanceled(true);
        FactionEventHandler.openTerritoryScreen(sp, faction, entry.territoryId());
    }
}
