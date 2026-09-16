package com.tirener.shadiom.shadiomrpoverhaul.network.names;

import com.tirener.shadiom.shadiomrpoverhaul.client.names.ClientNameCache;
import com.tirener.shadiom.shadiomrpoverhaul.client.names.NamePickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: a player's resolved display name changed (or was set for the first time).
 * Populates {@link ClientNameCache} so other clients can render it, and closes the local picker
 * screen if this sync is for the local player themselves.
 */
public record NameSyncPacket(int entityId, String displayName) {

    public static void encode(NameSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entityId());
        buf.writeUtf(pkt.displayName());
    }

    public static NameSyncPacket decode(FriendlyByteBuf buf) {
        return new NameSyncPacket(buf.readVarInt(), buf.readUtf());
    }

    public static void handle(NameSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientNameCache.apply(pkt.entityId(), pkt.displayName());
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> onClient(pkt.entityId()));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void onClient(int entityId) {
        Minecraft mc = Minecraft.getInstance();

        // Updating the cache alone is not enough: Player.getDisplayName() caches the composed
        // component the first time anything asks for it (nameplate rendering, typically), and only
        // refreshDisplayName() repopulates it. A client that already drew this player's nameplate
        // during their pick would otherwise keep showing the raw account name until that entity is
        // destroyed and respawned. The refresh re-runs PlayerEvent.NameFormat, so the name and any
        // title this player holds are both recomposed together.
        if (mc.level != null && mc.level.getEntity(entityId) instanceof Player player) {
            player.refreshDisplayName();
        }

        if (mc.player != null && mc.player.getId() == entityId && mc.screen instanceof NamePickerScreen) {
            mc.setScreen(null);
        }
    }
}
