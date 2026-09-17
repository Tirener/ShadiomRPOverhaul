package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.TerritoryHudRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: announce a new top-center HUD label because the player just crossed into a
 *  different territory (or wilderness). Sent at most once per actual change - see
 *  TerritoryHudHandler for the tick-based crossing detection. */
public record TerritoryHudS2CPacket(String text) {

    public static void encode(TerritoryHudS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.text());
    }

    public static TerritoryHudS2CPacket decode(FriendlyByteBuf buf) {
        return new TerritoryHudS2CPacket(buf.readUtf());
    }

    public static void handle(TerritoryHudS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TerritoryHudRenderer.show(pkt.text())));
        ctx.get().setPacketHandled(true);
    }
}
