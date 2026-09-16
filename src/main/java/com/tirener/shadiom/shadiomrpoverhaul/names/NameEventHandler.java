package com.tirener.shadiom.shadiomrpoverhaul.names;

import com.mojang.logging.LogUtils;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.client.names.ClientNameCache;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.names.NameSyncPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.names.OpenNamePickerS2CPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Appends the player's chosen name (if any) to their display name, ahead of
 * {@code TitleEventHandler}'s prefix/suffix wrapping (see the HIGH priority below). Also drives
 * the whole pick lifecycle: opening the picker on first join, replaying names to
 * newly-tracking/newly-joined clients, and the pending-pick timeout fallback.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class NameEventHandler {

    private NameEventHandler() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PENDING_PICK_TIMEOUT_TICKS = 1200; // 60s
    private static final Random RANDOM = new Random();

    /** Players currently mid-pick, mapped to the game time they became pending. Small and
     *  self-pruning: entries are removed the moment a pick lands (manual or auto-assigned). */
    private static final Map<UUID, Long> PENDING_PICKS = new ConcurrentHashMap<>();

    // -- Login: open the picker for a new player, replay names to them for everyone else --

    @SubscribeEvent(priority = EventPriority.LOW) // after BloodlineEventHandler's default-priority roll
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joiner)) return;

        MinecraftServer server = joiner.getServer();
        if (server == null) return;

        if (!NamePlayerData.hasPicked(joiner)) openPicker(joiner, server);

        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == joiner) continue;
            if (!other.level().dimension().equals(joiner.level().dimension())) continue;
            if (!NamePlayerData.hasPicked(other)) continue;
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> joiner),
                    new NameSyncPacket(other.getId(), ShadiomNameAPI.displayName(other)));
        }
    }

    /**
     * The picker cannot be dismissed, so it must never be opened onto something unpickable. With
     * either pool empty there is nothing to choose and no way back out, so skip it entirely and
     * say so in the log rather than trapping the player at a dead screen. They keep their account
     * name and get the picker on their next login, once a mod has registered some pool content.
     */
    private static void openPicker(ServerPlayer joiner, MinecraftServer server) {
        List<String> firstNames = NameRegistry.candidateFirstNames(joiner);
        List<String> surnames = NameRegistry.candidateSurnames(joiner);

        if (firstNames.isEmpty() || surnames.isEmpty()) {
            LOGGER.warn("Skipping the name picker for {}: no candidates available ({} first names, "
                            + "{} surnames). They keep their account name for now.",
                    joiner.getGameProfile().getName(), firstNames.size(), surnames.size());
            return;
        }

        PENDING_PICKS.put(joiner.getUUID(), server.overworld().getGameTime());
        sendOpenPacket(joiner, firstNames, surnames, "");
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer targetPlayer)) return;
        if (!(event.getEntity() instanceof ServerPlayer tracker)) return;
        if (!NamePlayerData.hasPicked(targetPlayer)) return;

        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> tracker),
                new NameSyncPacket(targetPlayer.getId(), ShadiomNameAPI.displayName(targetPlayer)));
    }

    private static void sendOpenPacket(ServerPlayer player, String error) {
        sendOpenPacket(player,
                NameRegistry.candidateFirstNames(player),
                NameRegistry.candidateSurnames(player),
                error);
    }

    private static void sendOpenPacket(ServerPlayer player, List<String> firstNames,
                                       List<String> surnames, String error) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenNamePickerS2CPacket(firstNames, surnames, error));
    }

    // -- Composing with the display name, ahead of titles --

    @SubscribeEvent(priority = EventPriority.HIGH) // titles stay at default NORMAL, so this resolves first
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        String name = displayNameFor(event.getEntity());
        if (name == null) return;
        event.setDisplayname(Component.literal(name));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        String name = displayNameFor(event.getEntity());
        if (name == null) return;
        event.setDisplayName(Component.literal(name));
    }

    private static String displayNameFor(Player player) {
        if (player instanceof ServerPlayer sp) {
            return NamePlayerData.hasPicked(sp) ? ShadiomNameAPI.displayName(sp) : null;
        }
        return ClientNameCache.get(player.getId());
    }

    // -- Submit handling (called by SubmitNamePickC2SPacket) and the timeout fallback --

    /** Entry point for {@code SubmitNamePickC2SPacket}. Re-validates against this player's
     *  current candidate lists and the uniqueness store before applying. */
    public static void handleSubmit(ServerPlayer player, String firstName, String surname) {
        // Only a player who is actually mid-pick may submit. There is no rename feature, so
        // without this a modified client could keep resubmitting after picking to rename itself at
        // will, marking a fresh combo taken every time and draining a pool nothing ever frees.
        if (NamePlayerData.hasPicked(player) || !PENDING_PICKS.containsKey(player.getUUID())) return;
        attemptApply(player, firstName, surname, true);
    }

    private static boolean attemptApply(ServerPlayer player, String firstName, String surname,
                                         boolean sendErrorOnFailure) {
        List<String> validFirst = NameRegistry.candidateFirstNames(player);
        List<String> validSurnames = NameRegistry.candidateSurnames(player);
        NamesData data = NamesData.get(player.serverLevel());

        if (!validFirst.contains(firstName) || !validSurnames.contains(surname)
                || data.isTaken(firstName, surname)) {
            if (sendErrorOnFailure) {
                sendOpenPacket(player, validFirst, validSurnames,
                        "That name is no longer available. Please choose another.");
            }
            return false;
        }

        NamePlayerData.set(player, firstName, surname);
        data.markTaken(firstName, surname);
        PENDING_PICKS.remove(player.getUUID());
        player.refreshDisplayName();
        player.refreshTabListName();
        broadcast(player, ShadiomNameAPI.displayName(player));
        return true;
    }

    private static void broadcast(ServerPlayer player, String displayName) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.DIMENSION.with(player.level()::dimension),
                new NameSyncPacket(player.getId(), displayName));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (PENDING_PICKS.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();

        for (Map.Entry<UUID, Long> entry : new ArrayList<>(PENDING_PICKS.entrySet())) {
            if (now - entry.getValue() < PENDING_PICK_TIMEOUT_TICKS) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                PENDING_PICKS.remove(entry.getKey());
                continue;
            }
            assignRandomName(player);
        }
    }

    /** The pending set is in-memory only and keyed by game time, so a stale entry left over from
     *  one world would be measured against another world's clock. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_PICKS.clear();
    }

    /** Safety net for a picker bypassed via a modified client: after the timeout, assign a
     *  random still-available combo through the same path a manual pick uses.
     *  ponytail: full first x surname cross product, fine for the small curated pools this
     *  feature is designed for; switch to weighted sampling if pools grow large enough for this
     *  to matter. */
    private static void assignRandomName(ServerPlayer player) {
        List<String> firsts = NameRegistry.candidateFirstNames(player);
        List<String> surnames = NameRegistry.candidateSurnames(player);
        NamesData data = NamesData.get(player.serverLevel());

        List<String[]> combos = new ArrayList<>();
        for (String first : firsts) {
            for (String surname : surnames) {
                if (!data.isTaken(first, surname)) combos.add(new String[] { first, surname });
            }
        }

        if (combos.isEmpty()) {
            LOGGER.warn("No available first+surname combo for {} - every candidate for this player "
                            + "is already taken. They will remain at the name picker until the pool "
                            + "is expanded (see ShadiomNameAPI.registerFirstName/registerSurname).",
                    player.getGameProfile().getName());
            return;
        }

        String[] chosen = combos.get(RANDOM.nextInt(combos.size()));
        attemptApply(player, chosen[0], chosen[1], false);
    }
}
