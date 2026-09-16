package com.tirener.shadiom.shadiomrpoverhaul.client.names;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.names.SubmitNamePickC2SPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Mandatory first-join name picker. Vanilla widgets only, no background texture, two scrollable
 * button lists (first name / surname), same list-scroll pattern as ShadiomMod's
 * ElderLineageScreen. Cannot be dismissed: the only way out is a successful pick, closed by
 * {@code NameSyncPacket}'s handler when the sync for the local player's own entity id arrives.
 */
@OnlyIn(Dist.CLIENT)
public class NamePickerScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 22;
    private static final int VISIBLE_ROWS = 6;
    private static final int COL_GAP = 10;
    private static final int COL_W = (PANEL_W - COL_GAP) / 2;

    private static final int TITLE_Y = 8;
    private static final int SUB_Y = TITLE_Y + 12;
    private static final int LBL_Y = SUB_Y + 18;
    private static final int LIST_Y0 = LBL_Y + 12;
    private static final int BTN_Y0 = LIST_Y0 + ROW_GAP * VISIBLE_ROWS + 14;
    private static final int PANEL_H = BTN_Y0 + ROW_H + 16;

    private final List<String> firstNames;
    private final List<String> surnames;

    private final List<AbstractWidget> firstNameWidgets = new ArrayList<>();
    private final List<AbstractWidget> surnameWidgets = new ArrayList<>();
    private Button confirmButton;

    private String selectedFirst;
    private String selectedSurname;
    private int firstScroll = 0;
    private int surnameScroll = 0;
    private Component error;

    private int left, top;

    public NamePickerScreen(List<String> firstNames, List<String> surnames, String error) {
        super(Component.literal("Choose Your Name"));
        this.firstNames = firstNames;
        this.surnames = surnames;
        this.error = Component.literal(error == null ? "" : error);
        if (firstNames.size() == 1) this.selectedFirst = firstNames.get(0);
        if (surnames.size() == 1) this.selectedSurname = surnames.get(0);
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        confirmButton = Button.builder(Component.literal("Confirm"), b -> onConfirm())
                .pos(left, top + BTN_Y0).size(PANEL_W, ROW_H).build();
        addRenderableWidget(confirmButton);

        rebuildFirstNames();
        rebuildSurnames();
        updateConfirmState();
    }

    private void rebuildFirstNames() {
        for (AbstractWidget w : firstNameWidgets) removeWidget(w);
        firstNameWidgets.clear();

        int maxScroll = Math.max(0, firstNames.size() - VISIBLE_ROWS);
        firstScroll = Math.max(0, Math.min(firstScroll, maxScroll));

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = row + firstScroll;
            if (index >= firstNames.size()) break;
            String name = firstNames.get(index);
            int y = top + LIST_Y0 + row * ROW_GAP;
            String label = (name.equals(selectedFirst) ? "> " : "") + name;

            Button btn = Button.builder(Component.literal(label), b -> selectFirst(name))
                    .pos(left, y).size(COL_W, ROW_H).build();
            addRenderableWidget(btn);
            firstNameWidgets.add(btn);
        }
    }

    private void rebuildSurnames() {
        for (AbstractWidget w : surnameWidgets) removeWidget(w);
        surnameWidgets.clear();

        int maxScroll = Math.max(0, surnames.size() - VISIBLE_ROWS);
        surnameScroll = Math.max(0, Math.min(surnameScroll, maxScroll));

        int colX = left + COL_W + COL_GAP;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = row + surnameScroll;
            if (index >= surnames.size()) break;
            String name = surnames.get(index);
            int y = top + LIST_Y0 + row * ROW_GAP;
            String label = (name.equals(selectedSurname) ? "> " : "") + name;

            Button btn = Button.builder(Component.literal(label), b -> selectSurname(name))
                    .pos(colX, y).size(COL_W, ROW_H).build();
            addRenderableWidget(btn);
            surnameWidgets.add(btn);
        }
    }

    private void selectFirst(String name) {
        selectedFirst = name;
        rebuildFirstNames();
        updateConfirmState();
    }

    private void selectSurname(String name) {
        selectedSurname = name;
        rebuildSurnames();
        updateConfirmState();
    }

    private void updateConfirmState() {
        confirmButton.active = selectedFirst != null && selectedSurname != null;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        boolean overFirstColumn = mx < left + COL_W;
        if (overFirstColumn && firstNames.size() > VISIBLE_ROWS) {
            firstScroll -= (int) Math.signum(delta);
            rebuildFirstNames();
            return true;
        }
        if (!overFirstColumn && surnames.size() > VISIBLE_ROWS) {
            surnameScroll -= (int) Math.signum(delta);
            rebuildSurnames();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void onConfirm() {
        if (selectedFirst == null || selectedSurname == null) return;
        confirmButton.active = false;
        error = Component.empty();
        ModNetwork.CHANNEL.sendToServer(new SubmitNamePickC2SPacket(selectedFirst, selectedSurname));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // Deliberately empty: the only way out is a successful pick. NameSyncPacket's handler
        // closes this screen once the server confirms it for the local player's own entity id.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        int cx = left + PANEL_W / 2;
        g.drawCenteredString(font, "Choose Your Name", cx, top + TITLE_Y, 0xFFFFFF);
        g.drawCenteredString(font, "This will be your name in this world.",
                cx, top + SUB_Y, ChatFormatting.GRAY.getColor());
        g.drawCenteredString(font, "First Name", left + COL_W / 2, top + LBL_Y, ChatFormatting.GRAY.getColor());
        g.drawCenteredString(font, "Surname", left + COL_W + COL_GAP + COL_W / 2, top + LBL_Y, ChatFormatting.GRAY.getColor());

        super.render(g, mx, my, partial);

        if (!error.getString().isEmpty()) {
            g.drawCenteredString(font, error, cx, top + PANEL_H - 10, 0xFF5555);
        }
    }
}
