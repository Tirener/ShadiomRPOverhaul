package com.tirener.shadiom.shadiomrpoverhaul.title;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * All known {@link Title}s, keyed by id. Empty by default. Internal storage only — consuming
 * mods should call the {@code register*} methods on {@link ShadiomTitleAPI} instead of this
 * class directly.
 */
final class TitleRegistry {

    private TitleRegistry() {
    }

    private static final Map<String, Title> TITLES = new LinkedHashMap<>();

    /** No tag text — just recolors the player's name as a gradient from {@code colorFrom} to {@code colorTo}. */
    public static void registerNameGradient(String id, int colorFrom, int colorTo) {
        register(id, Component.literal(""), Title.Position.NONE, colorFrom, colorTo);
    }

    /** Solid-colored (or otherwise {@link Style}-customized) title text. */
    public static void register(String id, String text, UnaryOperator<Style> style,
                                 Title.Position position, Integer nameColor) {
        register(id, Component.literal(text).withStyle(style), position, nameColor, null);
    }

    /** Title text rendered as a gradient from {@code colorFrom} to {@code colorTo}. */
    public static void registerGradient(String id, String text, int colorFrom, int colorTo,
                                         Title.Position position, Integer nameColor, Integer nameGradientTo) {
        register(id, GradientText.build(text, colorFrom, colorTo), position, nameColor, nameGradientTo);
    }

    public static void register(String id, Component display, Title.Position position,
                                 Integer nameColor, Integer nameGradientTo) {
        if (TITLES.containsKey(id)) {
            throw new IllegalStateException("Duplicate title id: " + id);
        }
        TITLES.put(id, new Title(id, display, position, nameColor, nameGradientTo));
    }

    public static Title get(String id) {
        return TITLES.get(id);
    }

    public static Map<String, Title> all() {
        return Collections.unmodifiableMap(TITLES);
    }
}
