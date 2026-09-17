package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** All faction membership rules live here - validates the actor's role against
 *  {@link FactionPermissions}, mutates {@link FactionsData}, and refreshes the acting player's
 *  screen afterward. {@link FactionsData} itself never validates anything; this is the one place
 *  that does, same division of responsibility as {@code NameEventHandler} vs {@code NamesData}.
 *  Every branch below is a silent no-op on failure (offline target, wrong role, stale state, ...)
 *  per the design spec's error-handling section - the refreshed snapshot sent at the end simply
 *  shows the actor nothing changed. */
public final class FactionEventHandler {

    private FactionEventHandler() {}

    public static void openScreen(ServerPlayer player) {
        send(player);
    }

    public static void handleAction(ServerPlayer player, FactionActionC2SPacket.Action action, String arg) {
        switch (action) {
            case CREATE -> create(player, arg);
            case INVITE -> invite(player, arg);
            case ACCEPT -> acceptInvite(player, arg);
            case DECLINE -> declineInvite(player, arg);
            case KICK -> kick(player, arg);
            case PROMOTE -> promote(player, arg);
            case DEMOTE -> demote(player, arg);
            case LEAVE -> leave(player);
            case DISBAND -> disband(player);
        }
        send(player);
    }

    private static void create(ServerPlayer player, String name) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        if (name == null || name.isBlank()) return;

        String id = slug(name);
        if (data.exists(id)) return;

        data.put(new Faction(id, name, player.getUUID()));
    }

    private static void invite(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canInvite(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (data.factionOf(target.getUUID()) != null) return; // also rejects self-invite

        data.addInvite(target.getUUID(), faction.id());
        send(target);
    }

    private static void acceptInvite(ServerPlayer player, String factionId) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        if (!data.invitesOf(player.getUUID()).contains(factionId)) return;

        Faction faction = data.get(factionId);
        if (faction == null) {
            data.removeInvite(player.getUUID(), factionId);
            return;
        }

        faction.addMember(player.getUUID());
        data.reindex(faction);
        clearInvites(data, player.getUUID());
    }

    private static void declineInvite(ServerPlayer player, String factionId) {
        data(player).removeInvite(player.getUUID(), factionId);
    }

    private static void kick(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        Faction.Role targetRole = faction.roleOf(target.getUUID());
        if (targetRole == null) return;
        if (!FactionPermissions.canKick(faction.roleOf(actor.getUUID()), targetRole)) return;

        faction.removeMember(target.getUUID());
        data.reindex(faction);
        send(target);
    }

    private static void promote(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canPromote(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (faction.roleOf(target.getUUID()) != Faction.Role.MEMBER) return;

        faction.addOfficer(target.getUUID());
        data.reindex(faction);
    }

    private static void demote(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDemote(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (faction.roleOf(target.getUUID()) != Faction.Role.OFFICER) return;

        faction.demoteToMember(target.getUUID());
        data.reindex(faction);
    }

    private static void leave(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (faction.roleOf(player.getUUID()) == Faction.Role.LEADER) return; // must disband instead

        faction.removeMember(player.getUUID());
        data.reindex(faction);
    }

    private static void disband(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDisband(faction.roleOf(player.getUUID()))) return;

        data.remove(faction.id());
    }

    private static void clearInvites(FactionsData data, UUID player) {
        for (String id : List.copyOf(data.invitesOf(player))) data.removeInvite(player, id);
    }

    private static String slug(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private static FactionsData data(ServerPlayer player) {
        return FactionsData.get(player.serverLevel());
    }

    // -- Snapshot --

    private static void send(ServerPlayer player) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
    }

    private static OpenFactionScreenS2CPacket buildSnapshot(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());

        if (faction == null) {
            List<String> inviteIds = new ArrayList<>();
            List<String> inviteNames = new ArrayList<>();
            for (String id : data.invitesOf(player.getUUID())) {
                Faction invited = data.get(id);
                if (invited == null) continue;
                inviteIds.add(id);
                inviteNames.add(invited.name());
            }
            return new OpenFactionScreenS2CPacket(false, "", "", List.of(), List.of(), List.of(),
                    inviteIds, inviteNames);
        }

        List<String> memberNames = new ArrayList<>();
        List<String> memberRoles = new ArrayList<>();
        MinecraftServer server = player.getServer();
        for (UUID uuid : faction.allMembers()) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member == null) continue; // offline members aren't listed - see design spec
            memberNames.add(member.getGameProfile().getName());
            memberRoles.add(faction.roleOf(uuid).name());
        }

        List<String> invitable = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (data.factionOf(online.getUUID()) != null) continue;
            invitable.add(online.getGameProfile().getName());
        }

        String viewerRole = faction.roleOf(player.getUUID()).name();
        return new OpenFactionScreenS2CPacket(true, faction.name(), viewerRole,
                memberNames, memberRoles, invitable, List.of(), List.of());
    }
}
