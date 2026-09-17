package com.tirener.shadiom.shadiomrpoverhaul.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight RP chat markup: **bold**, *italic*, ~~strikethrough~~, __underline__. No nesting,
 *  no escaping - delimiters are just stripped and the enclosed text styled. */
final class ChatMarkup {

    private ChatMarkup() {}

    // ** checked before * so a bold run isn't read as two italic delimiters.
    private static final Pattern PATTERN = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|~~(.+?)~~|__(.+?)__");

    static Component parse(String text) {
        MutableComponent result = Component.literal("");
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                result.append(Component.literal(text.substring(lastEnd, matcher.start())));
            }
            result.append(styledGroup(matcher));
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            result.append(Component.literal(text.substring(lastEnd)));
        }
        return result;
    }

    private static Component styledGroup(Matcher matcher) {
        if (matcher.group(1) != null) {
            return Component.literal(matcher.group(1)).withStyle(ChatFormatting.BOLD);
        }
        if (matcher.group(2) != null) {
            return Component.literal(matcher.group(2)).withStyle(ChatFormatting.ITALIC);
        }
        if (matcher.group(3) != null) {
            return Component.literal(matcher.group(3)).withStyle(ChatFormatting.STRIKETHROUGH);
        }
        return Component.literal(matcher.group(4)).withStyle(ChatFormatting.UNDERLINE);
    }
}
