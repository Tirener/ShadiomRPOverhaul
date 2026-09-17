package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket.Action;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.RequestTerritoryMapC2SPacket;
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
        if (state.mustCreateFaction()) {
            nameField = new EditBox(font, left, y, PANEL_W - 70, ROW_H, Component.literal("Faction name"));
            addRenderableWidget(nameField);
            addRenderableWidget(Button.builder(Component.literal("Create"), b -> onCreate())
                    .pos(left + PANEL_W - 65, y).size(65, ROW_H).build());
            return;
        }

        int shown = 0;
        for (OpenFactionScreenS2CPacket.PendingInvite invite : state.pendingInvites()) {
            if (shown++ >= MAX_ROWS) break;
            addRow(Component.literal(invite.factionName()), List.of(
                    new RowButton("Accept", b -> send(Action.ACCEPT, invite.factionId())),
                    new RowButton("Decline", b -> send(Action.DECLINE, invite.factionId()))));
        }
    }

    private void initFactionView() {
        boolean canManage = state.viewerRole().equals("LEADER") || state.viewerRole().equals("OFFICER");
        boolean isLeader = state.viewerRole().equals("LEADER");
        String selfName = minecraft.player.getGameProfile().getName();

        y += 12;
        int shown = 0;
        for (OpenFactionScreenS2CPacket.MemberEntry member : state.members()) {
            if (shown++ >= MAX_ROWS) break;
            String name = member.name();
            String role = member.role();
            boolean self = name.equals(selfName);

            List<RowButton> buttons = new ArrayList<>();
            boolean canKickThis = !self && ((isLeader && !role.equals("LEADER"))
                    || (state.viewerRole().equals("OFFICER") && role.equals("MEMBER")));
            if (canKickThis) buttons.add(new RowButton("Kick", b -> send(Action.KICK, name)));
            if (isLeader && !self && role.equals("MEMBER")) {
                buttons.add(new RowButton("Promote", b -> send(Action.PROMOTE, name)));
            }
            if (isLeader && !self && role.equals("OFFICER")) {
                buttons.add(new RowButton("Demote", b -> send(Action.DEMOTE, name)));
            }
            addRow(Component.literal(member.displayName() + " - " + role), buttons);
        }

        if (canManage) {
            y += 12;
            shown = 0;
            for (OpenFactionScreenS2CPacket.InvitableEntry invitable : state.invitablePlayers()) {
                if (shown++ >= MAX_ROWS) break;
                String name = invitable.name();
                addRow(Component.literal(invitable.displayName()), List.of(new RowButton("Invite", b -> send(Action.INVITE, name))));
            }
        }

        y += 12;
        if (state.canManageClaims()) {
            boolean ownedByMe = state.currentChunkOwner().equals(state.factionName());
            String label = ownedByMe ? "Unclaim this chunk" : "Claim this chunk";
            Action action = ownedByMe ? Action.UNCLAIM : Action.CLAIM;
            addRenderableWidget(Button.builder(Component.literal(label), b -> send(action, ""))
                    .pos(left, y).size(PANEL_W, ROW_H).build());
            y += ROW_GAP;
        }

        y += 12;
        shown = 0;
        for (OpenFactionScreenS2CPacket.TerritoryInfo territory : state.territories()) {
            if (shown++ >= MAX_ROWS) break;
            boolean isCapital = territory.id().equals(state.capitalTerritoryId());

            String label = (territory.name().isEmpty() ? "Unnamed Territory" : territory.name())
                    + " - " + territory.chunkCount() + "/100" + (isCapital ? " (Capital)" : "");
            addRow(Component.literal(label), List.of());
        }

        y += 12;
        String areaLabel = ClaimBorderRenderer.isEnabled() ? "Hide Territory Map" : "Show Territory Map";
        addRenderableWidget(Button.builder(Component.literal(areaLabel),
                b -> {
                    if (ClaimBorderRenderer.isEnabled()) {
                        ClaimBorderRenderer.hide();
                    } else {
                        ModNetwork.CHANNEL.sendToServer(new RequestTerritoryMapC2SPacket());
                    }
                })
                .pos(left, y).size(PANEL_W, ROW_H).build());
        y += ROW_GAP;

        y += 12;
        shown = 0;
        for (OpenFactionScreenS2CPacket.OtherFaction otherFaction : state.otherFactions()) {
            if (shown++ >= MAX_ROWS) break;
            String name = otherFaction.name();
            String relation = otherFaction.relation();

            List<RowButton> buttons = new ArrayList<>();
            if (state.canManageDiplomacy()) {
                if (relation.equals("WAR")) {
                    buttons.add(new RowButton("Make Peace", b -> send(Action.MAKE_PEACE, name)));
                } else {
                    buttons.add(new RowButton("Declare War", b -> send(Action.DECLARE_WAR, name)));
                }
                if (relation.equals("ALLY")) {
                    buttons.add(new RowButton("Break Alliance", b -> send(Action.BREAK_ALLIANCE, name)));
                } else {
                    buttons.add(new RowButton("Propose Alliance", b -> send(Action.PROPOSE_ALLIANCE, name)));
                }
            }
            addRow(Component.literal(name + " - " + relation), buttons);
        }

        if (!state.incomingProposalNames().isEmpty()) {
            y += 12;
            shown = 0;
            for (int i = 0; i < state.incomingProposalNames().size() && shown < MAX_ROWS; i++, shown++) {
                String proposer = state.incomingProposalNames().get(i);
                addRow(Component.literal(proposer + " proposes an alliance"), List.of(
                        new RowButton("Accept", b -> send(Action.ACCEPT_ALLIANCE, proposer)),
                        new RowButton("Decline", b -> send(Action.DECLINE_ALLIANCE, proposer))));
            }
        }

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

    @Override
    public boolean shouldCloseOnEsc() {
        return !state.mustCreateFaction();
    }

    @Override
    public void onClose() {
        // Deliberately empty while mustCreateFaction is true - same "only way out is completing
        // it" pattern as NamePickerScreen. Otherwise fall through to the normal close.
        if (!state.mustCreateFaction()) super.onClose();
    }

    private record Label(Component text, int x, int y) {}
    private record RowButton(String label, Button.OnPress onPress) {}
}
