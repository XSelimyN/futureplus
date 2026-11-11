package com.futureplus.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommonButtons;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Main menu (replaces vanilla TitleScreen, MinecraftScreenMixin). Minecraft's familiar layout: a title in the middle, under it
 * a 200 px button column, Options / Quit at the bottom and the language / accessibility icons on the sides. Instead of the title, the Future
 * emblem + Future+ text; the background is FutureUi.backdrop (theme colored block grid, breathing emblem). Items appear in turn on open.
 */
public final class FutureTitleScreen extends Screen {
    private static final int BTN_W = 200, BTN_H = 20, GAP = 4, HEAD = 72;
    private final List<AbstractWidget> staged = new ArrayList<>();
    private long openNs = -1;
    private int top;

    public FutureTitleScreen() { super(Component.translatable("narrator.screen.title")); }

    @Override
    protected void init() {
        staged.clear();
        boolean mods = FabricLoader.getInstance().isModLoaded("modmenu");
        int rows = 2 + (mods ? 1 : 0);
        int blockH = HEAD + rows * (BTN_H + GAP) + 8 + BTN_H;
        top = Math.max(10, (height - blockH) / 2 - 6);
        int x = (width - BTN_W) / 2;
        int y = top + HEAD;
        y = add(Component.translatable("menu.singleplayer"), x, y, b -> minecraft.setScreen(new SelectWorldScreen(this)));
        y = add(Component.translatable("menu.multiplayer"), x, y, b -> minecraft.setScreen(new JoinMultiplayerScreen(this)));
        if (mods) y = add(Component.literal("Mods"), x, y, b -> openModMenu());
        y += 8;
        int half = (BTN_W - GAP) / 2;
        stage(addRenderableWidget(CommonButtons.language(BTN_H,
            b -> minecraft.setScreen(new LanguageSelectScreen(this, minecraft.options, minecraft.getLanguageManager())), true)))
            .setPosition(x - BTN_H - GAP, y);
        stage(addRenderableWidget(Button.builder(Component.translatable("menu.options"),
            b -> minecraft.setScreen(new OptionsScreen(this, minecraft.options, false))).bounds(x, y, half, BTN_H).build()));
        stage(addRenderableWidget(Button.builder(Component.translatable("menu.quit"), b -> minecraft.stop())
            .bounds(x + half + GAP, y, BTN_W - half - GAP, BTN_H).build()));
        stage(addRenderableWidget(CommonButtons.accessibility(BTN_H,
            b -> minecraft.setScreen(new AccessibilityOptionsScreen(this, minecraft.options)), true)))
            .setPosition(x + BTN_W + GAP, y);
        if (openNs < 0) openNs = System.nanoTime();
    }

    private int add(Component label, int x, int y, Button.OnPress press) {
        stage(addRenderableWidget(Button.builder(label, press).bounds(x, y, BTN_W, BTN_H).build()));
        return y + BTN_H + GAP;
    }

    private AbstractWidget stage(AbstractWidget w) { staged.add(w); return w; }

    private void openModMenu() {
        try {
            Class<?> c = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
            minecraft.setScreen((Screen) c.getConstructor(Screen.class).newInstance(this));
        } catch (Throwable ignored) { }
    }

    /** Opening: item i appears over 280 ms with an i * 40 ms delay (easeOutCubic). */
    private float appear(int i) {
        float t = ((System.nanoTime() - openNs) / 1e6f - 60 - i * 40) / 280f;
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
        FutureUi.backdrop(g, width, height, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
        for (int i = 0; i < staged.size(); i++) {
            float a = appear(i + 2);
            AbstractWidget w = staged.get(i);
            w.setAlpha(a);
            w.visible = a > 0.01f;
        }
        super.extractRenderState(g, mouseX, mouseY, pt);

        // title: emblem + FUTUREPLUS (2x) + subtitle, centered
        float a0 = appear(0), a1 = appear(1);
        int logo = 34, gap = 8;
        String name = "Future+";
        int nameW = font.width(name) * 2;
        int totalW = logo + gap + nameW;
        int lx = (width - totalW) / 2, ly = top + Math.round((1f - a0) * 6);
        int px = (int) Math.round(logo * minecraft.getWindow().getGuiScale());
        if (a0 > 0.01f) g.blit(RenderPipelines.GUI_TEXTURED, FutureLogo.texture(px), lx, ly, 0f, 0f, logo, logo,
            px, px, px, px, FutureUi.argb(a0, 0xFFFFFF));
        if (a1 > 0.01f) {
            int tx = lx + logo + gap, ty = ly + (logo - 16) / 2;
            g.pose().pushMatrix();
            g.pose().translate(tx, ty + (1f - a1) * 3);
            g.pose().scale(2f, 2f);
            g.text(font, name, 1, 1, FutureUi.argb(a1 * 0.55f, 0x2A0A0C), false);     // soft shadow
            g.text(font, name, 0, 0, FutureUi.argb(a1, 0xF4F1F1), false);
            g.pose().popMatrix();
            String sub = "Minecraft " + SharedConstants.getCurrentVersion().name() + " Edition";
            int sw = font.width(sub);
            int sy = top + logo + 10;
            g.text(font, sub, (width - sw) / 2, sy, FutureUi.argb(a1 * 0.75f, FutureUi.mix(0xE8C9CB, FutureUi.accent(), 0.35f)), false);
            float lw = 46 * a1;
            FutureUi.round(g, width / 2f - lw, sy + 14, lw * 2, 1f, 0.5f, FutureUi.argb(a1 * 0.8f, FutureUi.accent()));
        }

        float af = appear(staged.size() + 2);
        if (af > 0.01f) {
            String left = "Future+  ·  " + minecraft.getUser().getName();
            g.text(font, left, 4, height - 10, FutureUi.argb(af * 0.7f, 0xD8D0D0), false);
            String c = "Copyright Mojang AB. Do not distribute!";
            g.text(font, c, width - font.width(c) - 3, height - 10, FutureUi.argb(af * 0.7f, 0xD8D0D0), false);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean isPauseScreen() { return false; }
}
