package com.tirener.shadiom.shadiomrpoverhaul.network.names;

import com.tirener.shadiom.shadiomrpoverhaul.names.NameEventHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: the player confirmed a first name and surname from the picker. */
public record SubmitNamePickC2SPacket(String firstName, String surname) {

    public static void encode(SubmitNamePickC2SPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.firstName());
        buf.writeUtf(pkt.surname());
    }

    public static SubmitNamePickC2SPacket decode(FriendlyByteBuf buf) {
        return new SubmitNamePickC2SPacket(buf.readUtf(), buf.readUtf());
    }

    public static void handle(SubmitNamePickC2SPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) {
            ctx.get().setPacketHandled(true);
            return;
        }
        ctx.get().enqueueWork(() -> NameEventHandler.handleSubmit(player, pkt.firstName(), pkt.surname()));
        ctx.get().setPacketHandled(true);
    }
}
