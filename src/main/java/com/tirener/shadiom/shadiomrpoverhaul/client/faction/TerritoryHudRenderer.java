package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.mojang.blaze3d.systems.RenderSystem;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Top-center fading text shown for a few seconds whenever the server announces a territory
 *  crossing (see TerritoryHudHandler). Fade timing mirrors a vanilla advancement toast: fully
 *  visible for a couple of seconds, then a short fade-out, rather than a persistent element. */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TerritoryHudRenderer {

    private TerritoryHudRenderer() {}

    private static final long VISIBLE_MS = 2500;
    private static final long FADE_MS = 800;

    private static String text = "";
    private static long shownAt = 0;

    public static void show(String newText) {
        text = newText;
        shownAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("territory_hud", TerritoryHudRenderer::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics,
                                float partialTick, int screenWidth, int screenHeight) {
        if (text.isEmpty()) return;
        long elapsed = System.currentTimeMillis() - shownAt;
        if (elapsed > VISIBLE_MS + FADE_MS) return;

        float alpha = elapsed <= VISIBLE_MS ? 1f : 1f - (float) (elapsed - VISIBLE_MS) / FADE_MS;
        int argb = ((int) (alpha * 255) << 24) | 0xFFFFFF;

        RenderSystem.enableBlend();
        graphics.drawCenteredString(gui.getFont(), text, screenWidth / 2, 10, argb);
        RenderSystem.disableBlend();
    }
}
