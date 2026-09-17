package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.faction.FactionEventHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: "show me the current territory map" - a fire-and-forget query, unlike
 *  {@link FactionActionC2SPacket} it never touches faction state or reopens the faction screen,
 *  it only ever triggers an {@link AllTerritoriesS2CPacket} reply. No fields - the server always
 *  answers for the requester's own current dimension. */
public record RequestTerritoryMapC2SPacket() {

    public static void encode(RequestTerritoryMapC2SPacket pkt, FriendlyByteBuf buf) {}

    public static RequestTerritoryMapC2SPacket decode(FriendlyByteBuf buf) {
        return new RequestTerritoryMapC2SPacket();
    }

    public static void handle(RequestTerritoryMapC2SPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) {
            ctx.get().setPacketHandled(true);
            return;
        }
        ctx.get().enqueueWork(() -> FactionEventHandler.sendTerritoryMap(player));
        ctx.get().setPacketHandled(true);
    }
}
