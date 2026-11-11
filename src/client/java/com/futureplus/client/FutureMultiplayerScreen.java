package com.futureplus.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.HashMap;
import java.util.Map;

/**
 * Server list (replaces vanilla JoinMultiplayerScreen, MinecraftScreenMixin): the functions are vanilla's, the layout
 * is new. Emblem, title and server count at the top center, the list in the middle, two rows of buttons at the bottom (Join highlighted in the top row).
 */
public final class FutureMultiplayerScreen extends JoinMultiplayerScreen {
    private static final int ROW_W = 404, BTN_H = 20, GAP = 6, TOP = 52;

    public FutureMultiplayerScreen(Screen parent) { super(parent); }

    @Override
    protected void init() {
        super.init();
        arrange();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        arrange();
    }

    private void arrange() {
        Map<String, Button> b = new HashMap<>();
        for (GuiEventListener c : children()) {
            if (c instanceof StringWidget s) s.visible = false;              // we draw the title ourselves
            if (c instanceof Button btn && btn.getMessage().getContents() instanceof TranslatableContents tc) b.put(tc.getKey(), btn);
        }
        int rowW = Math.min(ROW_W, width - 24);
        int x0 = (width - rowW) / 2;
        int y2 = height - 10 - BTN_H, y1 = y2 - GAP - BTN_H;
        // top row: Join (wide) | Direct | Add
        int small = (rowW - 2 * GAP) * 2 / 7, wide = rowW - 2 * GAP - 2 * small;
        place(b.get("selectServer.select"), x0, y1, wide);
        place(b.get("selectServer.direct"), x0 + wide + GAP, y1, small);
        place(b.get("selectServer.add"), x0 + wide + small + 2 * GAP, y1, rowW - wide - small - 2 * GAP);
        // bottom row: Edit | Delete | Refresh | Back
        int q = (rowW - 3 * GAP) / 4;
        place(b.get("selectServer.edit"), x0, y2, q);
        place(b.get("selectServer.delete"), x0 + q + GAP, y2, q);
        place(b.get("selectServer.refresh"), x0 + 2 * (q + GAP), y2, q);
        place(b.get("gui.back"), x0 + 3 * (q + GAP), y2, rowW - 3 * (q + GAP));
        if (serverSelectionList != null)
            serverSelectionList.updateSizeAndPosition(width, Math.max(40, y1 - 10 - TOP), 0, TOP);
    }

    private static void place(Button b, int x, int y, int w) {
        if (b == null) return;
        b.setRectangle(w, BTN_H, x, y);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
        FutureUi.backdrop(g, width, height, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
        super.extractRenderState(g, mouseX, mouseY, pt);
        // title: small emblem + title in the middle, server count and a thin accent line under it
        int logo = 16, gap = 6;
        int tw = Math.round(font.width(title) * 1.5f);
        int lx = (width - logo - gap - tw) / 2, ly = 12;
        int px = (int) Math.round(logo * minecraft.getWindow().getGuiScale());
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FutureLogo.texture(px), lx, ly, 0f, 0f,
            logo, logo, px, px, px, px, 0xFFFFFFFF);
        g.pose().pushMatrix();
        g.pose().translate(lx + logo + gap, ly + 2);
        g.pose().scale(1.5f, 1.5f);
        g.text(font, title, 0, 0, 0xFFF4F1F1, false);
        g.pose().popMatrix();
        int n = getServers() == null ? 0 : getServers().size();
        String count = n + (n == 1 ? " server" : " servers");
        g.text(font, count, (width - font.width(count)) / 2, ly + logo + 5, FutureUi.argb(0.6f, 0xD8D0D0), false);
        FutureUi.round(g, width / 2f - 30, TOP - 7, 60, 1f, 0.5f, FutureUi.argb(0.8f, FutureUi.accent()));
    }
}
