package com.tirener.shadiom.shadiomrpoverhaul.title;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.title.TitleSyncPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.UnaryOperator;

/**
 * Public entry point for cosmetic titles. Consuming mods define their titles with one of the
 * {@code register*} methods (typically during common setup), then grant/revoke them at runtime
 * with {@link #apply} / {@link #clear}. Titles are appended to the player's display name by
 * {@link TitleEventHandler}'s {@code PlayerEvent.NameFormat} / {@code TabListNameFormat}
 * listeners — chat, death messages, the floating nameplate, and the tab list all derive from
 * those, so nothing else needs wiring.
 * <p>
 * {@link TitleRegistry} and {@link TitlePlayerData} are internal storage only; consuming mods
 * should go through this class instead of touching them directly.
 * <p>
 * Both {@code refresh*} calls below are required, not redundant — confirmed by
 * disassembling {@code ServerPlayer}: {@code getTabListDisplayName()} is a
 * one-shot cache (a {@code hasTabListName} flag) that only ever fires {@code
 * TabListNameFormat} once, typically at login, then returns that same stale
 * value for the rest of the session no matter how many times it's called
 * afterward. {@code refreshDisplayName()} only resets the nametag/chat cache
 * (backs {@code getDisplayName()}); it does nothing for the tab list. {@code
 * refreshTabListName()} is the actual counterpart — it recomputes the cached
 * name and, if it changed, broadcasts a {@code ClientboundPlayerInfoUpdatePacket}
 * to every client itself, so no extra packet is needed here for that part.
 * Without it, a title applied/cleared mid-session shows correctly everywhere
 * except the tab list, which stays wrong until the player reconnects.
 */
public final class ShadiomTitleAPI {

    private ShadiomTitleAPI() {}

    /** No tag text — just recolors the player's name as a gradient from {@code colorFrom} to {@code colorTo}. */
    public static void registerNameGradient(String id, int colorFrom, int colorTo) {
        TitleRegistry.registerNameGradient(id, colorFrom, colorTo);
    }

    /** Solid-colored (or otherwise {@link Style}-customized) title text. */
    public static void register(String id, String text, UnaryOperator<Style> style,
                                 Title.Position position, Integer nameColor) {
        TitleRegistry.register(id, text, style, position, nameColor);
    }

    /** Title text rendered as a gradient from {@code colorFrom} to {@code colorTo}. */
    public static void registerGradient(String id, String text, int colorFrom, int colorTo,
                                         Title.Position position, Integer nameColor, Integer nameGradientTo) {
        TitleRegistry.registerGradient(id, text, colorFrom, colorTo, position, nameColor, nameGradientTo);
    }

    public static void register(String id, Component display, Title.Position position,
                                 Integer nameColor, Integer nameGradientTo) {
        TitleRegistry.register(id, display, position, nameColor, nameGradientTo);
    }

    public static void apply(ServerPlayer player, String titleId) {
        if (TitleRegistry.get(titleId) == null) return;
        TitlePlayerData.set(player, titleId);
        player.refreshDisplayName();
        player.refreshTabListName();
        broadcast(player, titleId);
    }

    public static void clear(ServerPlayer player) {
        TitlePlayerData.set(player, null);
        player.refreshDisplayName();
        player.refreshTabListName();
        broadcast(player, "");
    }

    /** The currently active title id for this player, or {@code null} if none. */
    public static String activeTitleId(ServerPlayer player) {
        return TitlePlayerData.get(player);
    }

    private static void broadcast(ServerPlayer player, String titleId) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.DIMENSION.with(player.level()::dimension),
                new TitleSyncPacket(player.getId(), titleId));
    }
}
