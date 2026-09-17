package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.names.ShadiomNameAPI;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** All faction membership and claim rules live here - validates the actor's role against
 *  {@link FactionPermissions}, mutates {@link FactionsData}/{@link ClaimsData}, and refreshes
 *  the acting player's screen afterward. Every branch below is a silent no-op on failure
 *  (offline target, wrong role, stale state, ...) per the design spec's error-handling
 *  section - the refreshed snapshot sent at the end simply shows the actor nothing changed. */
public final class FactionEventHandler {

    private FactionEventHandler() {}

    /** A faction-less player's not-yet-submitted capital, from placing a Faction Center before
     *  a faction exists to claim it for. In-memory only, like {@code NameEventHandler}'s
     *  PENDING_PICKS - see the design spec for why that's fine here. */
    private record PendingCapital(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

    private static final Map<UUID, PendingCapital> PENDING_CAPITALS = new ConcurrentHashMap<>();

    public static boolean hasPendingCapital(UUID player) {
        return PENDING_CAPITALS.containsKey(player);
    }

    public static void recordPendingCapital(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos chunk) {
        PENDING_CAPITALS.put(player.getUUID(), new PendingCapital(dimension, chunk.x, chunk.z));
        openScreen(player);
    }

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
            case CLAIM -> claim(player);
            case UNCLAIM -> unclaim(player);
        }
        send(player);
    }

    private static void create(ServerPlayer player, String name) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        PendingCapital pending = PENDING_CAPITALS.get(player.getUUID());
        if (pending == null) return; // must place a Faction Center first
        if (name == null || name.isBlank()) return;

        String id = slug(name);
        if (data.exists(id)) return;

        data.put(new Faction(id, name, player.getUUID()));
        ClaimsData.get(player.serverLevel()).claim(
                ClaimsData.chunkKey(pending.dimension(), pending.chunkX(), pending.chunkZ()), id, true);
        PENDING_CAPITALS.remove(player.getUUID());
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
        target.sendSystemMessage(Component.literal(
                "You've been invited to join " + faction.name() + ". Run /faction to respond."));
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
        target.sendSystemMessage(Component.literal("You've been kicked from " + faction.name() + "."));
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

        ClaimsData.get(player.serverLevel()).releaseAll(faction.id());
        data.remove(faction.id());
    }

    private static void claim(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageClaims(faction.roleOf(player.getUUID()))) return;

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos chunk = player.chunkPosition();
        ResourceKey<Level> dimension = player.level().dimension();
        String key = ClaimsData.chunkKey(dimension, chunk.x, chunk.z);
        if (claims.get(key) != null) return; // already claimed by someone
        if (!isAdjacentToOwnClaim(claims, faction.id(), dimension, chunk)) return;

        claims.claim(key, faction.id(), false);
    }

    private static void unclaim(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageClaims(faction.roleOf(player.getUUID()))) return;

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos chunk = player.chunkPosition();
        String key = ClaimsData.chunkKey(player.level().dimension(), chunk.x, chunk.z);
        ClaimsData.ClaimEntry entry = claims.get(key);
        if (entry == null || !entry.factionId().equals(faction.id())) return;
        if (entry.capital()) return; // must break the Faction Center instead

        claims.unclaim(key);
    }

    private static boolean isAdjacentToOwnClaim(ClaimsData claims, String factionId,
                                                 ResourceKey<Level> dimension, ChunkPos chunk) {
        int[][] deltas = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] d : deltas) {
            String neighborKey = ClaimsData.chunkKey(dimension, chunk.x + d[0], chunk.z + d[1]);
            ClaimsData.ClaimEntry neighbor = claims.get(neighborKey);
            if (neighbor != null && neighbor.factionId().equals(factionId)) return true;
        }
        return false;
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

    /** The RP name (Name & Surname), or the account username if the player hasn't picked one -
     *  same fallback {@code ProximityChatEventHandler} already uses. */
    private static String displayNameOf(ServerPlayer player) {
        return ShadiomNameAPI.hasPicked(player)
                ? ShadiomNameAPI.displayName(player)
                : player.getGameProfile().getName();
    }

    // -- Snapshot --

    private static void send(ServerPlayer player) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
    }

    private static OpenFactionScreenS2CPacket buildSnapshot(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());

        if (faction == null) {
            boolean mustCreate = PENDING_CAPITALS.containsKey(player.getUUID());
            List<String> inviteIds = new ArrayList<>();
            List<String> inviteNames = new ArrayList<>();
            if (!mustCreate) {
                for (String id : data.invitesOf(player.getUUID())) {
                    Faction invited = data.get(id);
                    if (invited == null) continue;
                    inviteIds.add(id);
                    inviteNames.add(invited.name());
                }
            }
            return new OpenFactionScreenS2CPacket(false, mustCreate, "", "", List.of(), List.of(),
                    List.of(), List.of(), List.of(), inviteIds, inviteNames, "", false, List.of());
        }

        List<String> memberNames = new ArrayList<>();
        List<String> memberDisplayNames = new ArrayList<>();
        List<String> memberRoles = new ArrayList<>();
        MinecraftServer server = player.getServer();
        for (UUID uuid : faction.allMembers()) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member == null) continue; // offline members aren't listed - see design spec
            memberNames.add(member.getGameProfile().getName());
            memberDisplayNames.add(displayNameOf(member));
            memberRoles.add(faction.roleOf(uuid).name());
        }

        List<String> invitable = new ArrayList<>();
        List<String> invitableDisplayNames = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (data.factionOf(online.getUUID()) != null) continue;
            invitable.add(online.getGameProfile().getName());
            invitableDisplayNames.add(displayNameOf(online));
        }

        ClaimsData claims = ClaimsData.get(player.serverLevel());
        ChunkPos here = player.chunkPosition();
        ResourceKey<Level> dimension = player.level().dimension();
        ClaimsData.ClaimEntry hereClaim = claims.get(ClaimsData.chunkKey(dimension, here.x, here.z));
        String currentChunkOwner = "";
        if (hereClaim != null) {
            Faction owner = data.get(hereClaim.factionId());
            currentChunkOwner = owner == null ? "" : owner.name();
        }

        List<String> ownClaims = new ArrayList<>();
        String dimensionPrefix = dimension.location() + ",";
        for (String key : claims.claimsOf(faction.id())) {
            if (key.startsWith(dimensionPrefix)) ownClaims.add(key);
        }

        String viewerRole = faction.roleOf(player.getUUID()).name();
        return new OpenFactionScreenS2CPacket(true, false, faction.name(), viewerRole,
                memberNames, memberDisplayNames, memberRoles, invitable, invitableDisplayNames,
                List.of(), List.of(), currentChunkOwner,
                FactionPermissions.canManageClaims(faction.roleOf(player.getUUID())), ownClaims);
    }
}
