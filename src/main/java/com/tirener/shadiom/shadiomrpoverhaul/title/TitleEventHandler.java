package com.tirener.shadiom.shadiomrpoverhaul.title;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.client.title.ClientTitleCache;
import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.title.TitleSyncPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * Appends the active title (if any) to a player's display name and tab-list entry.
 * Runs on both sides: server reads straight from {@link TitlePlayerData}; client reads
 * from {@link ClientTitleCache}, populated by {@link TitleSyncPacket}.
 */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class TitleEventHandler {

    private TitleEventHandler() {}

    private static String titleIdFor(Player player) {
        if (player instanceof ServerPlayer sp) return ShadiomTitleAPI.activeTitleId(sp);
        return ClientTitleCache.get(player.getId());
    }

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        String titleId = titleIdFor(event.getEntity());
        Title title = titleId == null ? null : TitleRegistry.get(titleId);
        if (title == null) return;
        event.setDisplayname(compose(event.getDisplayname(), title));
    }

    @SubscribeEvent
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        String titleId = titleIdFor(event.getEntity());
        Title title = titleId == null ? null : TitleRegistry.get(titleId);
        if (title == null) return;
        Component base = event.getDisplayName() != null
                ? event.getDisplayName()
                : Component.literal(event.getEntity().getGameProfile().getName());
        event.setDisplayName(compose(base, title));
    }

    /**
     * Builds the combined name+title component under a neutral, un-styled root so neither side
     * bleeds its color into the other via style inheritance — each side keeps its own explicit
     * style (or the ambient default if it doesn't set one).
     */
    private static Component compose(Component name, Title title) {
        Component namePart;
        if (title.nameGradientTo() != null) {
            namePart = GradientText.build(name.getString(), title.nameColor(), title.nameGradientTo());
        } else if (title.nameColor() != null) {
            namePart = name.copy().withStyle(style -> style.withColor(title.nameColor()));
        } else {
            namePart = name.copy();
        }

        MutableComponent result = Component.literal("");
        return switch (title.position()) {
            case PREFIX -> result.append(title.display()).append(Component.literal(" ")).append(namePart);
            case SUFFIX -> result.append(namePart).append(Component.literal(", ")).append(title.display());
            case NONE -> result.append(namePart);
        };
    }

    /**
     * A client that was already tracking a player before that player's title changed has no
     * other chance to learn about it — {@link ShadiomTitleAPI#apply}/{@link ShadiomTitleAPI#clear}
     * broadcast exactly once, at the moment of change, and that's it. If that one packet doesn't land (or
     * the tracking client wasn't fully set up yet — e.g. the title changed while the target was
     * still logging in), the mismatch sticks around indefinitely with nothing to self-correct it.
     * Replay the current title whenever anyone starts tracking a titled player, the same way
     * {@link #onLogin} already replays the full snapshot for a fresh login.
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (!(target instanceof ServerPlayer targetPlayer)) return;
        if (!(event.getEntity() instanceof ServerPlayer tracker)) return;

        String titleId = ShadiomTitleAPI.activeTitleId(targetPlayer);
        if (titleId == null) return;

        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> tracker),
                new TitleSyncPacket(targetPlayer.getId(), titleId));
    }

    /**
     * Late joiners otherwise miss everyone else's title until that player's title changes
     * again — replay the current snapshot for online, same-dimension players.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joiner)) return;
        MinecraftServer server = joiner.getServer();
        if (server == null) return;

        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == joiner) continue;
            if (!other.level().dimension().equals(joiner.level().dimension())) continue;
            String titleId = ShadiomTitleAPI.activeTitleId(other);
            if (titleId == null) continue;
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> joiner),
                    new TitleSyncPacket(other.getId(), titleId));
        }
    }
}
