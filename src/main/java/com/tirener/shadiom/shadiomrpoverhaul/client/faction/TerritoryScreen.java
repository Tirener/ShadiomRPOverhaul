package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket.Action;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenTerritoryScreenS2CPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Rename a territory and/or set it as the faction's capital. No live refresh after acting -
 *  unlike FactionScreen there's no packet loop keeping this open, so both actions just close the
 *  screen; right-clicking the Faction Center again shows the updated state. */
@OnlyIn(Dist.CLIENT)
public class TerritoryScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;

    private final OpenTerritoryScreenS2CPacket state;
    private EditBox nameField;
    private int left, top;

    public TerritoryScreen(OpenTerritoryScreenS2CPacket state) {
        super(Component.literal("Territory"));
        this.state = state;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - 120) / 2;

        nameField = new EditBox(font, left, top + 30, PANEL_W, ROW_H, Component.literal("Territory name"));
        nameField.setValue(state.name());
        nameField.setMaxLength(48);
        addRenderableWidget(nameField);

        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> onRename())
                .pos(left, top + 30 + ROW_GAP).size(PANEL_W, ROW_H).build());

        Button capitalButton = Button.builder(Component.literal("Set as Capital"), b -> onSetCapital())
                .pos(left, top + 30 + ROW_GAP * 2).size(PANEL_W, ROW_H).build();
        capitalButton.active = !state.isCapital();
        addRenderableWidget(capitalButton);
    }

    private void onRename() {
        String name = nameField.getValue();
        if (name.isBlank()) return;
        send(Action.RENAME_TERRITORY, state.territoryId() + "|" + name);
        onClose();
    }

    private void onSetCapital() {
        send(Action.SET_CAPITAL_TERRITORY, state.territoryId());
        onClose();
    }

    private void send(Action action, String arg) {
        ModNetwork.CHANNEL.sendToServer(new FactionActionC2SPacket(action, arg));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        int cx = left + PANEL_W / 2;
        g.drawCenteredString(font, "Territory", cx, top, 0xFFFFFF);
        g.drawCenteredString(font, state.chunkCount() + " / 100 chunks claimed",
                cx, top + 14, ChatFormatting.GRAY.getColor());
        if (state.isCapital()) {
            g.drawCenteredString(font, "Capital Territory", cx, top + 30 + ROW_GAP * 3 + 4, ChatFormatting.GOLD.getColor());
        }
        super.render(g, mx, my, partial);
    }
}
