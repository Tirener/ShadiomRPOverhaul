package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.TerritoryHudS2CPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Announces the current territory's name at the top center of the HUD whenever a player crosses
 *  into a different one. Checked once per second per player (not every tick - chunk crossings
 *  are rare relative to the tick rate) by comparing against the last chunk key announced for
 *  them; a login/first tick always announces, since there's no prior entry to match. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class TerritoryHudHandler {

    private TerritoryHudHandler() {}

    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final Map<UUID, String> LAST_ANNOUNCED_CHUNK = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        ChunkPos chunk = player.chunkPosition();
        String chunkKey = ClaimsData.chunkKey(player.level().dimension(), chunk.x, chunk.z);
        if (chunkKey.equals(LAST_ANNOUNCED_CHUNK.get(player.getUUID()))) return;
        LAST_ANNOUNCED_CHUNK.put(player.getUUID(), chunkKey);

        String text = describe(player, chunkKey);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TerritoryHudS2CPacket(text));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ANNOUNCED_CHUNK.remove(event.getEntity().getUUID());
    }

    private static String describe(ServerPlayer player, String chunkKey) {
        ClaimsData.ClaimEntry entry = ClaimsData.get(player.serverLevel()).get(chunkKey);
        if (entry == null) return "Wilderness";
        if (entry.factionId() == null) return "Abandoned Territory";

        Faction faction = FactionsData.get(player.serverLevel()).get(entry.factionId());
        if (faction == null) return "Wilderness"; // orphaned entry, shouldn't normally happen

        Faction.Territory territory = faction.territory(entry.territoryId());
        if (territory != null && territory.name() != null) return territory.name();
        return faction.name();
    }
}
