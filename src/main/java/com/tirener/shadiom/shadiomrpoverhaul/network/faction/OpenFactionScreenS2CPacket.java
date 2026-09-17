package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.FactionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: opens (or refreshes, after every action) the faction screen with a full
 * snapshot. {@code pendingInviteIds}/{@code pendingInviteNames} and
 * {@code memberNames}/{@code memberDisplayNames}/{@code memberRoles} are parallel lists rather
 * than a nested record type, to keep encode/decode to the same flat {@code writeStringList}
 * helper used everywhere else in this mod's networking.
 * <p>
 * {@code memberNames}/{@code invitablePlayerNames} are account usernames - used as the action
 * identifier sent back in the C2S action packet, since that's what
 * {@code PlayerList.getPlayerByName} resolves. {@code memberDisplayNames}/
 * {@code invitableDisplayNames} are what's actually shown (the RP name, if picked).
 */
public record OpenFactionScreenS2CPacket(
        boolean hasFaction,
        String factionName,
        String viewerRole,
        List<String> memberNames,
        List<String> memberDisplayNames,
        List<String> memberRoles,
        List<String> invitablePlayerNames,
        List<String> invitableDisplayNames,
        List<String> pendingInviteIds,
        List<String> pendingInviteNames
) {

    public static void encode(OpenFactionScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasFaction());
        buf.writeUtf(pkt.factionName());
        buf.writeUtf(pkt.viewerRole());
        writeStringList(buf, pkt.memberNames());
        writeStringList(buf, pkt.memberDisplayNames());
        writeStringList(buf, pkt.memberRoles());
        writeStringList(buf, pkt.invitablePlayerNames());
        writeStringList(buf, pkt.invitableDisplayNames());
        writeStringList(buf, pkt.pendingInviteIds());
        writeStringList(buf, pkt.pendingInviteNames());
    }

    public static OpenFactionScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenFactionScreenS2CPacket(
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf)
        );
    }

    public static void handle(OpenFactionScreenS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> openScreen(pkt)));
        ctx.get().setPacketHandled(true);
    }

    private static void openScreen(OpenFactionScreenS2CPacket pkt) {
        Minecraft.getInstance().setScreen(new FactionScreen(pkt));
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
