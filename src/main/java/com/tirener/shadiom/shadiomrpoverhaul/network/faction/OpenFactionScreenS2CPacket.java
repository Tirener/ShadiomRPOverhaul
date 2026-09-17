package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.FactionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: opens (or refreshes, after every action) the faction screen with a full
 * snapshot. Each row-shaped concept (a member, an invitable player, a pending invite, another
 * faction's relation, a territory) is its own nested record instead of several index-aligned
 * parallel lists, so there's nothing that can drift out of sync between two lists that are
 * supposed to describe the same rows.
 * <p>
 * {@code MemberEntry.name}/{@code InvitableEntry.name} are account usernames - used as the action
 * identifier sent back in the C2S action packet, since that's what {@code PlayerList.getPlayerByName}
 * resolves. {@code displayName} is what's actually shown (the RP name, if picked).
 * <p>
 * {@code mustCreateFaction} forces the create-only view (a Faction Center was placed by a
 * faction-less player, but they haven't submitted a name yet). {@code currentChunkOwner},
 * {@code canManageClaims}, {@code ownClaimedChunkKeys}, {@code otherFactions},
 * {@code incomingProposalNames}, {@code canManageDiplomacy} and {@code territories} are only
 * meaningful when {@code hasFaction} is true. {@code TerritoryInfo.name} is empty for a territory
 * that hasn't been named yet.
 */
public record OpenFactionScreenS2CPacket(
        boolean hasFaction,
        boolean mustCreateFaction,
        String factionName,
        String viewerRole,
        List<MemberEntry> members,
        List<InvitableEntry> invitablePlayers,
        List<PendingInvite> pendingInvites,
        String currentChunkOwner,
        boolean canManageClaims,
        List<String> ownClaimedChunkKeys,
        List<OtherFaction> otherFactions,
        List<String> incomingProposalNames,
        boolean canManageDiplomacy,
        List<TerritoryInfo> territories,
        String capitalTerritoryId
) {

    public record MemberEntry(String name, String displayName, String role) {}
    public record InvitableEntry(String name, String displayName) {}
    public record PendingInvite(String factionId, String factionName) {}
    public record OtherFaction(String name, String relation) {}
    public record TerritoryInfo(String id, String name, int chunkCount) {}

    public static void encode(OpenFactionScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasFaction());
        buf.writeBoolean(pkt.mustCreateFaction());
        buf.writeUtf(pkt.factionName());
        buf.writeUtf(pkt.viewerRole());
        buf.writeCollection(pkt.members(), (b, m) -> {
            b.writeUtf(m.name());
            b.writeUtf(m.displayName());
            b.writeUtf(m.role());
        });
        buf.writeCollection(pkt.invitablePlayers(), (b, i) -> {
            b.writeUtf(i.name());
            b.writeUtf(i.displayName());
        });
        buf.writeCollection(pkt.pendingInvites(), (b, i) -> {
            b.writeUtf(i.factionId());
            b.writeUtf(i.factionName());
        });
        buf.writeUtf(pkt.currentChunkOwner());
        buf.writeBoolean(pkt.canManageClaims());
        buf.writeCollection(pkt.ownClaimedChunkKeys(), FriendlyByteBuf::writeUtf);
        buf.writeCollection(pkt.otherFactions(), (b, o) -> {
            b.writeUtf(o.name());
            b.writeUtf(o.relation());
        });
        buf.writeCollection(pkt.incomingProposalNames(), FriendlyByteBuf::writeUtf);
        buf.writeBoolean(pkt.canManageDiplomacy());
        buf.writeCollection(pkt.territories(), (b, t) -> {
            b.writeUtf(t.id());
            b.writeUtf(t.name());
            b.writeVarInt(t.chunkCount());
        });
        buf.writeUtf(pkt.capitalTerritoryId());
    }

    public static OpenFactionScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenFactionScreenS2CPacket(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readList(b -> new MemberEntry(b.readUtf(), b.readUtf(), b.readUtf())),
                buf.readList(b -> new InvitableEntry(b.readUtf(), b.readUtf())),
                buf.readList(b -> new PendingInvite(b.readUtf(), b.readUtf())),
                buf.readUtf(),
                buf.readBoolean(),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(b -> new OtherFaction(b.readUtf(), b.readUtf())),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readBoolean(),
                buf.readList(b -> new TerritoryInfo(b.readUtf(), b.readUtf(), b.readVarInt())),
                buf.readUtf()
        );
    }

    public static void handle(OpenFactionScreenS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Minecraft.getInstance().setScreen(new FactionScreen(pkt))));
        ctx.get().setPacketHandled(true);
    }
}
