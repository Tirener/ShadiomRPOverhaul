package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.ClaimBorderRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: every claimed (or abandoned) chunk in the recipient's current dimension,
 * across every faction - not just their own, unlike the rest of the faction snapshot packets.
 * {@code factionIds} has an empty string for an abandoned chunk. Sent both as the direct response
 * to a {@link RequestTerritoryMapC2SPacket} ({@code forceShow=true}, turns the map on) and as a
 * standing broadcast to every online player whenever any claim changes anywhere
 * ({@code forceShow=false}, applied only by clients who already have the map open - see
 * {@link ClaimBorderRenderer#applyUpdate}).
 */
public record AllTerritoriesS2CPacket(
        List<String> chunkKeys,
        List<String> factionIds,
        List<String> territoryIds,
        boolean forceShow
) {

    public static void encode(AllTerritoriesS2CPacket pkt, FriendlyByteBuf buf) {
        writeStringList(buf, pkt.chunkKeys());
        writeStringList(buf, pkt.factionIds());
        writeStringList(buf, pkt.territoryIds());
        buf.writeBoolean(pkt.forceShow());
    }

    public static AllTerritoriesS2CPacket decode(FriendlyByteBuf buf) {
        return new AllTerritoriesS2CPacket(readStringList(buf), readStringList(buf), readStringList(buf), buf.readBoolean());
    }

    public static void handle(AllTerritoriesS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (pkt.forceShow()) {
                ClaimBorderRenderer.show(pkt.chunkKeys(), pkt.factionIds(), pkt.territoryIds());
            } else {
                ClaimBorderRenderer.applyUpdate(pkt.chunkKeys(), pkt.factionIds(), pkt.territoryIds());
            }
        }));
        ctx.get().setPacketHandled(true);
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(buf.readUtf());
        return list;
    }
}
