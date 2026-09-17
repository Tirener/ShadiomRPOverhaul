package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket.Action;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Faction management GUI - branches internally on whether the player has a faction, same
 * single-screen-two-views approach as {@code NamePickerScreen}'s error-retry state. Every button
 * press sends a {@link FactionActionC2SPacket} and waits for the server's refreshed
 * {@link OpenFactionScreenS2CPacket} to replace this screen - no client-side state mutation.
 * <p>
 * ponytail: each list below is capped at {@link #MAX_ROWS} with no scrolling - add scrolling
 * (see {@code NamePickerScreen} for the pattern already used elsewhere in this mod) if a
 * faction or the online-player list actually exceeds it.
 */
@OnlyIn(Dist.CLIENT)
public class FactionScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 22;
    private static final int MAX_ROWS = 5;
    private static final int BUTTON_W = 60;

    private final OpenFactionScreenS2CPacket state;
    private final List<Label> labels = new ArrayList<>();

    private EditBox nameField;
    private int left, top, y;

    public FactionScreen(OpenFactionScreenS2CPacket state) {
        super(Component.literal("Factions"));
        this.state = state;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = 20;
        y = top + 24;
        labels.clear();

        if (state.hasFaction()) initFactionView(); else initNoFactionView();
    }

    private void initNoFactionView() {
        nameField = new EditBox(font, left, y, PANEL_W - 70, ROW_H, Component.literal("Faction name"));
        addRenderableWidget(nameField);
        addRenderableWidget(Button.builder(Component.literal("Create"), b -> onCreate())
                .pos(left + PANEL_W - 65, y).size(65, ROW_H).build());
        y += ROW_GAP + 12;

        int shown = 0;
        for (int i = 0; i < state.pendingInviteIds().size() && shown < MAX_ROWS; i++, shown++) {
            String id = state.pendingInviteIds().get(i);
            String name = state.pendingInviteNames().get(i);
            addRow(Component.literal(name), List.of(
                    new RowButton("Accept", b -> send(Action.ACCEPT, id)),
                    new RowButton("Decline", b -> send(Action.DECLINE, id))));
        }
    }

    private void initFactionView() {
        boolean canManage = state.viewerRole().equals("LEADER") || state.viewerRole().equals("OFFICER");
        boolean isLeader = state.viewerRole().equals("LEADER");
        String selfName = minecraft.player.getGameProfile().getName();

        y += 12;
        int shown = 0;
        for (int i = 0; i < state.memberNames().size() && shown < MAX_ROWS; i++, shown++) {
            String name = state.memberNames().get(i);
            String role = state.memberRoles().get(i);
            boolean self = name.equals(selfName);

            List<RowButton> buttons = new ArrayList<>();
            if (canManage && !self) buttons.add(new RowButton("Kick", b -> send(Action.KICK, name)));
            if (isLeader && !self && role.equals("MEMBER")) {
                buttons.add(new RowButton("Promote", b -> send(Action.PROMOTE, name)));
            }
            if (isLeader && !self && role.equals("OFFICER")) {
                buttons.add(new RowButton("Demote", b -> send(Action.DEMOTE, name)));
            }
            addRow(Component.literal(name + " - " + role), buttons);
        }

        if (canManage) {
            y += 12;
            shown = 0;
            for (int i = 0; i < state.invitablePlayerNames().size() && shown < MAX_ROWS; i++, shown++) {
                String name = state.invitablePlayerNames().get(i);
                addRow(Component.literal(name), List.of(new RowButton("Invite", b -> send(Action.INVITE, name))));
            }
        }

        y += 12;
        if (isLeader) {
            addRenderableWidget(Button.builder(Component.literal("Disband"), b -> send(Action.DISBAND, ""))
                    .pos(left, y).size(PANEL_W, ROW_H).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Leave"), b -> send(Action.LEAVE, ""))
                    .pos(left, y).size(PANEL_W, ROW_H).build());
        }
    }

    /** Lays out a label on the left with 0-3 action buttons packed against the right edge. */
    private void addRow(Component label, List<RowButton> buttons) {
        int x = left + PANEL_W;
        for (RowButton rb : buttons) {
            x -= BUTTON_W + 5;
            addRenderableWidget(Button.builder(Component.literal(rb.label()), rb.onPress())
                    .pos(x, y).size(BUTTON_W, ROW_H).build());
        }
        labels.add(new Label(label, left, y));
        y += ROW_GAP;
    }

    private void onCreate() {
        if (nameField.getValue().isBlank()) return;
        send(Action.CREATE, nameField.getValue());
    }

    private void send(Action action, String arg) {
        ModNetwork.CHANNEL.sendToServer(new FactionActionC2SPacket(action, arg));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        String title = state.hasFaction() ? state.factionName() + " (" + state.viewerRole() + ")" : "Factions";
        g.drawCenteredString(font, title, left + PANEL_W / 2, top, 0xFFFFFF);

        super.render(g, mx, my, partial);

        for (Label label : labels) {
            g.drawString(font, label.text(), label.x(), label.y() + 6, ChatFormatting.GRAY.getColor());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Label(Component text, int x, int y) {}
    private record RowButton(String label, Button.OnPress onPress) {}
}
