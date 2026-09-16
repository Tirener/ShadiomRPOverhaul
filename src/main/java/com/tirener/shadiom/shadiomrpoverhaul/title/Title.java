package com.tirener.shadiom.shadiomrpoverhaul.title;

import net.minecraft.network.chat.Component;

/**
 * A cosmetic title definition: a stable id, the styled text shown for it, whether it renders
 * before or after the player's name, and an optional override for the name's own color.
 * <p>
 * If {@code nameGradientTo} is set, {@code nameColor} is the gradient's start color and the
 * name is rendered as a gradient toward {@code nameGradientTo}; otherwise {@code nameColor}
 * (if non-null) is a plain solid override.
 */
public record Title(String id, Component display, Position position, Integer nameColor, Integer nameGradientTo) {

    /** Where the title sits relative to the player's name. {@code NONE} recolors the name only, with no tag text. */
    public enum Position { PREFIX, SUFFIX, NONE }
}
