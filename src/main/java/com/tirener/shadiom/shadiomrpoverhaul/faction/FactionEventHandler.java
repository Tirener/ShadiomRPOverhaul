package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.names.ShadiomNameAPI;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionClaimsSyncS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenTerritoryScreenS2CPacket;
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

/** All faction membership, claim, and diplomacy rules live here - validates the actor's role
 *  against {@link FactionPermissions}, mutates {@link FactionsData}/{@link ClaimsData}/
 *  {@link DiplomacyData}, and refreshes the acting player's screen afterward. Every branch below
 *  is a silent no-op on failure (offline target, wrong role, stale state, ...) per the design
 *  spec's error-handling section - the refreshed snapshot sent at the end simply shows the actor
 *  nothing changed. */
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

    /** Opens the territory management screen for one territory - only call after already
     *  verifying the player is that faction's leader (see ClaimProtectionHandler's right-click
     *  handler, the only caller). */
    static void openTerritoryScreen(ServerPlayer player, Faction faction, String territoryId) {
        Faction.Territory territory = faction.territory(territoryId);
        if (territory == null) return;
        int chunkCount = ClaimsData.get(player.serverLevel()).chunksOfTerritory(territoryId).size();
        boolean isCapital = territoryId.equals(faction.capitalTerritoryId());

        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenTerritoryScreenS2CPacket(territoryId,
                        territory.name() == null ? "" : territory.name(), chunkCount, isCapital));
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
            case DECLARE_WAR -> declareWar(player, arg);
            case MAKE_PEACE -> makePeace(player, arg);
            case PROPOSE_ALLIANCE -> proposeAlliance(player, arg);
            case ACCEPT_ALLIANCE -> acceptAlliance(player, arg);
            case DECLINE_ALLIANCE -> declineAlliance(player, arg);
            case BREAK_ALLIANCE -> breakAlliance(player, arg);
            case RENAME_TERRITORY -> renameTerritory(player, arg);
            case SET_CAPITAL_TERRITORY -> setCapitalTerritory(player, arg);
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

        Faction faction = new Faction(id, name, player.getUUID());
        String territoryId = UUID.randomUUID().toString();
        faction.addTerritory(new Faction.Territory(territoryId, null));
        faction.setCapitalTerritoryId(territoryId);
        data.put(faction);

        ClaimsData.get(player.serverLevel()).claim(
                ClaimsData.chunkKey(pending.dimension(), pending.chunkX(), pending.chunkZ()),
                id, territoryId, true);
        PENDING_CAPITALS.remove(player.getUUID());
        DiplomacyReportWriter.write(player.getServer());
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

        disbandFaction(player.getServer(), faction);
    }

    private static void disbandFaction(MinecraftServer server, Faction faction) {
        ClaimsData.get(server.overworld()).releaseAll(faction.id());
        DiplomacyData.get(server.overworld()).releaseAll(faction.id());
        FactionsData.get(server.overworld()).remove(faction.id());
        DiplomacyReportWriter.write(server);
    }

    /** Breaking a Faction Center block: the capital territory's disbands the whole faction (same
     *  outcome as before territories existed); any other territory's just abandons that
     *  territory - its land stays reserved (still counts for the gap rule, see TerritoryRules)
     *  but opens up to build like wilderness until some faction plants a new Faction Center
     *  inside it and repossesses the whole blob. Called from ClaimProtectionHandler. */
    static void destroyFactionCenter(MinecraftServer server, Faction faction, String territoryId) {
        if (territoryId.equals(faction.capitalTerritoryId())) {
            disbandFaction(server, faction);
            return;
        }

        ClaimsData claims = ClaimsData.get(server.overworld());
        for (String chunkKey : claims.chunksOfTerritory(territoryId)) claims.abandon(chunkKey);
        faction.removeTerritory(territoryId);
        FactionsData.get(server.overworld()).setDirty();
        DiplomacyReportWriter.write(server);
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
        if (claims.get(key) != null) return; // already claimed, or abandoned, by someone

        String territoryId = TerritoryRules.territoryToExtend(claims, faction.id(), dimension, chunk);
        if (territoryId == null) return; // not touching exactly one of the faction's own territories
        if (TerritoryRules.anyOtherTerritoryAdjacent(claims, territoryId, dimension, chunk)) return;
        if (TerritoryRules.territoryChunkCount(claims, territoryId) >= TerritoryRules.MAX_TERRITORY_CHUNKS) return;

        claims.claim(key, faction.id(), territoryId, false);
        broadcastClaims(player.getServer(), faction);
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
        if (entry == null || !faction.id().equals(entry.factionId())) return;
        if (entry.factionCenter()) return; // must break the Faction Center instead

        claims.unclaim(key);
        broadcastClaims(player.getServer(), faction);
    }

    /** Pushes every online member's own-claims (filtered to their current dimension, same as
     *  {@code buildSnapshot} does for the acting player) so {@code ClaimBorderRenderer} stays
     *  live for whoever's watching it, not just whoever claimed/unclaimed. */
    private static void broadcastClaims(MinecraftServer server, Faction faction) {
        ClaimsData claims = ClaimsData.get(server.overworld());
        for (UUID uuid : faction.allMembers()) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member == null) continue;

            String dimensionPrefix = member.level().dimension().location() + ",";
            List<String> ownClaims = new ArrayList<>();
            for (String key : claims.claimsOf(faction.id())) {
                if (key.startsWith(dimensionPrefix)) ownClaims.add(key);
            }
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> member),
                    new FactionClaimsSyncS2CPacket(ownClaims));
        }
    }


    private static void declareWar(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null || target.id().equals(faction.id())) return;

        DiplomacyData.get(actor.serverLevel()).setRelation(faction.id(), target.id(), DiplomacyData.Relation.WAR);
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void makePeace(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (diplomacy.relationBetween(faction.id(), target.id()) != DiplomacyData.Relation.WAR) return;

        diplomacy.clearRelation(faction.id(), target.id());
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void proposeAlliance(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null || target.id().equals(faction.id())) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (diplomacy.relationBetween(faction.id(), target.id()) == DiplomacyData.Relation.ALLY) return;

        diplomacy.propose(faction.id(), target.id());
    }

    private static void acceptAlliance(ServerPlayer actor, String proposerName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction proposer = data.get(slug(proposerName));
        if (proposer == null) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (!diplomacy.proposalsFor(faction.id()).contains(proposer.id())) return;

        diplomacy.setRelation(faction.id(), proposer.id(), DiplomacyData.Relation.ALLY);
        diplomacy.clearProposal(proposer.id(), faction.id());
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void declineAlliance(ServerPlayer actor, String proposerName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction proposer = data.get(slug(proposerName));
        if (proposer == null) return;

        DiplomacyData.get(actor.serverLevel()).clearProposal(proposer.id(), faction.id());
    }

    private static void breakAlliance(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageDiplomacy(faction.roleOf(actor.getUUID()))) return;

        Faction target = data.get(slug(targetName));
        if (target == null) return;

        DiplomacyData diplomacy = DiplomacyData.get(actor.serverLevel());
        if (diplomacy.relationBetween(faction.id(), target.id()) != DiplomacyData.Relation.ALLY) return;

        diplomacy.clearRelation(faction.id(), target.id());
        DiplomacyReportWriter.write(actor.getServer());
    }

    private static void renameTerritory(ServerPlayer actor, String arg) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageTerritory(faction.roleOf(actor.getUUID()))) return;

        String[] parts = arg.split("\\|", 2);
        if (parts.length != 2) return;
        String territoryId = parts[0];
        String name = parts[1];
        if (faction.territory(territoryId) == null || name.isBlank()) return;

        faction.renameTerritory(territoryId, name);
        data.setDirty();
    }

    private static void setCapitalTerritory(ServerPlayer actor, String territoryId) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canManageTerritory(faction.roleOf(actor.getUUID()))) return;
        if (faction.territory(territoryId) == null) return;

        faction.setCapitalTerritoryId(territoryId);
        data.setDirty();
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
                    List.of(), List.of(), List.of(), inviteIds, inviteNames, "", false, List.of(),
                    List.of(), List.of(), List.of(), false);
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

        DiplomacyData diplomacy = DiplomacyData.get(player.serverLevel());
        List<String> otherFactionNames = new ArrayList<>();
        List<String> otherFactionRelations = new ArrayList<>();
        for (Faction other : data.all()) {
            if (other.id().equals(faction.id())) continue;
            DiplomacyData.Relation relation = diplomacy.relationBetween(faction.id(), other.id());
            otherFactionNames.add(other.name());
            otherFactionRelations.add(relation == null ? "NEUTRAL" : relation.name());
        }

        List<String> incomingProposals = new ArrayList<>();
        for (String proposerId : diplomacy.proposalsFor(faction.id())) {
            Faction proposer = data.get(proposerId);
            if (proposer != null) incomingProposals.add(proposer.name());
        }

        Faction.Role role = faction.roleOf(player.getUUID());
        return new OpenFactionScreenS2CPacket(true, false, faction.name(), role.name(),
                memberNames, memberDisplayNames, memberRoles, invitable, invitableDisplayNames,
                List.of(), List.of(), currentChunkOwner,
                FactionPermissions.canManageClaims(role), ownClaims,
                otherFactionNames, otherFactionRelations, incomingProposals,
                FactionPermissions.canManageDiplomacy(role));
    }
}
