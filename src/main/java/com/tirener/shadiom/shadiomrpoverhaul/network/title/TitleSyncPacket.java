package com.tirener.shadiom.shadiomrpoverhaul.network.title;

import com.tirener.shadiom.shadiomrpoverhaul.client.title.ClientTitleCache;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: a player's active cosmetic title changed. Populates
 * {@link ClientTitleCache} so other clients can render it above that player's head.
 *
 * @param entityId the player's entity id
 * @param titleId the new title id, or empty string to clear
 */
public record TitleSyncPacket(int entityId, String titleId) {

    public static void encode(TitleSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entityId);
        buf.writeUtf(pkt.titleId);
    }

    public static TitleSyncPacket decode(FriendlyByteBuf buf) {
        return new TitleSyncPacket(buf.readVarInt(), buf.readUtf());
    }

    public static void handle(TitleSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientTitleCache.apply(pkt.entityId(), pkt.titleId());
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> refreshDisplayName(pkt.entityId()));
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Player.getDisplayName() caches its composed component and only refreshDisplayName()
     * repopulates it, so updating the cache alone leaves an already-rendered nameplate stale.
     * The refresh re-runs PlayerEvent.NameFormat, which rebuilds the player's chosen name and
     * this title together rather than either one on its own.
     */
    private static void refreshDisplayName(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(entityId) instanceof Player player) {
            player.refreshDisplayName();
        }
    }
}
