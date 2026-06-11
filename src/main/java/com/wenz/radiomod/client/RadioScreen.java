package com.wenz.radiomod.client;

import com.wenz.radiomod.client.audio.RadioAudioManager;
import com.wenz.radiomod.menu.RadioMenu;
import com.wenz.radiomod.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class RadioScreen extends AbstractContainerScreen<RadioMenu> {

    private EditBox urlField;

    private static final int BG      = 0xDD1a1a2e;
    private static final int BORDER  = 0xFFe94560;
    private static final int TITLE_BG = 0xFF0f3460;
    private static final int TEXT     = 0xFFe94560;
    private static final int GREEN    = 0xFF00ff88;
    private static final int GRAY     = 0xFF777777;

    public RadioScreen(RadioMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = 240;
        this.imageHeight = 110;
        this.inventoryLabelY = -999; // hide inventory label
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        // URL input
        urlField = new EditBox(font, x + 10, y + 30, 220, 18,
                Component.literal("URL"));
        urlField.setMaxLength(512);
        urlField.setValue(menu.getBlockEntity().getStreamUrl());
        urlField.setTextColor(0xFFFFFFFF);
        addRenderableWidget(urlField);

        // ▶ Play
        addRenderableWidget(Button.builder(
                Component.literal("\u25B6 Play"), btn -> onPlay()
        ).bounds(x + 10, y + 58, 105, 20).build());

        // ⏹ Stop
        addRenderableWidget(Button.builder(
                Component.literal("\u23F9 Stop"), btn -> onStop()
        ).bounds(x + 125, y + 58, 105, 20).build());

        // Volume –
        addRenderableWidget(Button.builder(
                Component.literal("Vol \u2013"), btn -> adjustVolume(-0.1f)
        ).bounds(x + 10, y + 84, 50, 18).build());

        // Volume +
        addRenderableWidget(Button.builder(
                Component.literal("Vol +"), btn -> adjustVolume(0.1f)
        ).bounds(x + 65, y + 84, 50, 18).build());
    }

    private void onPlay() {
        String url = urlField.getValue().trim();
        if (url.isEmpty()) return;

        var be = menu.getBlockEntity();
        ModNetwork.sendSetUrl(be.getBlockPos(), url, true, be.getVolume());
        RadioAudioManager.play(be.getBlockPos(), url, be.getVolume());
    }

    private void onStop() {
        var be = menu.getBlockEntity();
        ModNetwork.sendSetUrl(be.getBlockPos(), be.getStreamUrl(), false, be.getVolume());
        RadioAudioManager.stop(be.getBlockPos());
    }

    private void adjustVolume(float delta) {
        var be = menu.getBlockEntity();
        float newVol = Math.max(0f, Math.min(1f, be.getVolume() + delta));
        be.setVolume(newVol);
        RadioAudioManager.setVolume(be.getBlockPos(), newVol);
        ModNetwork.sendSetUrl(be.getBlockPos(), be.getStreamUrl(), be.isPlaying(), newVol);
    }

    /* ---------- rendering ---------- */

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        int x = leftPos, y = topPos;
        int w = imageWidth, h = imageHeight;

        // Background
        g.fill(x, y, x + w, y + h, BG);
        // Border
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);
        // Title bar
        g.fill(x + 1, y + 1, x + w - 1, y + 20, TITLE_BG);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, "\u266B Radio", 8, 6, TEXT, false);

        // Playing indicator
        var be = menu.getBlockEntity();
        boolean playing = RadioAudioManager.isPlaying(be.getBlockPos());
        String status = playing ? "\u25CF ON" : "\u25CB OFF";
        int color = playing ? GREEN : GRAY;
        g.drawString(font, status, imageWidth - font.width(status) - 8, 6, color, false);

        // Volume display
        int pct = Math.round(be.getVolume() * 100);
        g.drawString(font, "Vol: " + pct + "%", 125, 89, 0xFFCCCCCC, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        super.render(g, mx, my, partial);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (urlField.isFocused()) {
            if (key == 256) { // ESC
                assert minecraft != null && minecraft.player != null;
                minecraft.player.closeContainer();
                return true;
            }
            return urlField.keyPressed(key, scan, mod);
        }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
