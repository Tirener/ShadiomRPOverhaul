package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.block.ModBlocks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Build/break protection inside claimed chunks, and the Faction Center's special
 *  placement/break rules (see the design spec) - everyone else's build/break rule is simply
 *  "member of the owning faction, or the chunk isn't claimed". */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class ClaimProtectionHandler {

    private ClaimProtectionHandler() {}

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;

        ServerLevel level = sp.serverLevel();
        ChunkPos chunk = new ChunkPos(event.getPos());
        ClaimsData claims = ClaimsData.get(level);
        String key = ClaimsData.chunkKey(level.dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null) return; // unclaimed, vanilla rules apply

        FactionsData factions = FactionsData.get(level);
        Faction faction = factions.factionOf(sp.getUUID());
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());

        if (event.getState().is(ModBlocks.FACTION_CENTER.get())) {
            boolean canBreak = isMember && FactionPermissions.canBreakFactionCenter(faction.roleOf(sp.getUUID()));
            if (!canBreak) {
                event.setCanceled(true);
                return;
            }
            claims.unclaim(key);
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
            ClaimsData.ClaimEntry existing = claims.get(key);

            if (faction == null) {
                if (existing != null) { event.setCanceled(true); return; }
                if (FactionEventHandler.hasPendingCapital(sp.getUUID())) { event.setCanceled(true); return; }
                FactionEventHandler.recordPendingCapital(sp, dimension, chunk);
                return;
            }

            if (!FactionPermissions.canPlaceFactionCenter(faction.roleOf(sp.getUUID()))) {
                event.setCanceled(true);
                return;
            }
            if (existing != null && !existing.factionId().equals(faction.id())) {
                event.setCanceled(true);
                return;
            }
            claims.claim(key, faction.id(), true);
            return;
        }

        ClaimsData.ClaimEntry claimEntry = claims.get(key);
        if (claimEntry == null) return;
        boolean isMember = faction != null && faction.id().equals(claimEntry.factionId());
        if (!isMember) event.setCanceled(true);
    }
}
