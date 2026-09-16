package com.tirener.shadiom.shadiomrpoverhaul.title;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.function.UnaryOperator;

/** Builds text where each character's color is interpolated between two endpoints. */
final class GradientText {

    private GradientText() {}

    static MutableComponent build(String text, int colorFrom, int colorTo) {
        return build(text, colorFrom, colorTo, UnaryOperator.identity());
    }

    static MutableComponent build(String text, int colorFrom, int colorTo, UnaryOperator<Style> extraStyle) {
        MutableComponent result = Component.literal("");
        int last = text.length() - 1;
        for (int i = 0; i < text.length(); i++) {
            float t = last == 0 ? 0f : (float) i / last;
            int color = lerpColor(colorFrom, colorTo, t);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(style -> extraStyle.apply(style).withColor(color)));
        }
        return result;
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
