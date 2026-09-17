package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.TerritoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: opens the territory management screen for one territory the viewer's
 *  faction owns - only ever sent after the server has already verified they're that faction's
 *  leader (see ClaimProtectionHandler's right-click handler). */
public record OpenTerritoryScreenS2CPacket(
        String territoryId,
        String name,
        int chunkCount,
        boolean isCapital
) {

    public static void encode(OpenTerritoryScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.territoryId());
        buf.writeUtf(pkt.name());
        buf.writeVarInt(pkt.chunkCount());
        buf.writeBoolean(pkt.isCapital());
    }

    public static OpenTerritoryScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenTerritoryScreenS2CPacket(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(OpenTerritoryScreenS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        Minecraft.getInstance().setScreen(new TerritoryScreen(pkt))));
        ctx.get().setPacketHandled(true);
    }
}
