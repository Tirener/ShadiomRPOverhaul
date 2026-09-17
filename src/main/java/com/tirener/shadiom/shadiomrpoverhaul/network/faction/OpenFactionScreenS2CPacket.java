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
 * snapshot. {@code memberNames}/{@code invitablePlayerNames} are account usernames - used as the
 * action identifier sent back in the C2S action packet, since that's what
 * {@code PlayerList.getPlayerByName} resolves. {@code memberDisplayNames}/
 * {@code invitableDisplayNames} are what's actually shown (the RP name, if picked).
 * <p>
 * {@code mustCreateFaction} forces the create-only view (a Faction Center was placed by a
 * faction-less player, but they haven't submitted a name yet). {@code currentChunkOwner},
 * {@code canManageClaims} and {@code ownClaimedChunkKeys} are only meaningful when
 * {@code hasFaction} is true.
 */
public record OpenFactionScreenS2CPacket(
        boolean hasFaction,
        boolean mustCreateFaction,
        String factionName,
        String viewerRole,
        List<String> memberNames,
        List<String> memberDisplayNames,
        List<String> memberRoles,
        List<String> invitablePlayerNames,
        List<String> invitableDisplayNames,
        List<String> pendingInviteIds,
        List<String> pendingInviteNames,
        String currentChunkOwner,
        boolean canManageClaims,
        List<String> ownClaimedChunkKeys
) {

    public static void encode(OpenFactionScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasFaction());
        buf.writeBoolean(pkt.mustCreateFaction());
        buf.writeUtf(pkt.factionName());
        buf.writeUtf(pkt.viewerRole());
        writeStringList(buf, pkt.memberNames());
        writeStringList(buf, pkt.memberDisplayNames());
        writeStringList(buf, pkt.memberRoles());
        writeStringList(buf, pkt.invitablePlayerNames());
        writeStringList(buf, pkt.invitableDisplayNames());
        writeStringList(buf, pkt.pendingInviteIds());
        writeStringList(buf, pkt.pendingInviteNames());
        buf.writeUtf(pkt.currentChunkOwner());
        buf.writeBoolean(pkt.canManageClaims());
        writeStringList(buf, pkt.ownClaimedChunkKeys());
    }

    public static OpenFactionScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenFactionScreenS2CPacket(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                buf.readUtf(),
                buf.readBoolean(),
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
