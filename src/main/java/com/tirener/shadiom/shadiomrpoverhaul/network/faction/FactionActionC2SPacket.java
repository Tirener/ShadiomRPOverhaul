package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.faction.FactionEventHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: every faction management action goes through this one packet - a
 *  dedicated packet per action (nine of them) would be a lot of boilerplate for what's really
 *  one dispatch on an enum. */
public record FactionActionC2SPacket(Action action, String arg) {

    public enum Action {
        CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND, CLAIM, UNCLAIM,
        DECLARE_WAR, MAKE_PEACE, PROPOSE_ALLIANCE, ACCEPT_ALLIANCE, DECLINE_ALLIANCE, BREAK_ALLIANCE,
        RENAME_TERRITORY, SET_CAPITAL_TERRITORY
    }

    public static void encode(FactionActionC2SPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.action());
        buf.writeUtf(pkt.arg());
    }

    public static FactionActionC2SPacket decode(FriendlyByteBuf buf) {
        return new FactionActionC2SPacket(buf.readEnum(Action.class), buf.readUtf());
    }

    public static void handle(FactionActionC2SPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) {
            ctx.get().setPacketHandled(true);
            return;
        }
        ctx.get().enqueueWork(() -> FactionEventHandler.handleAction(player, pkt.action(), pkt.arg()));
        ctx.get().setPacketHandled(true);
    }
}
