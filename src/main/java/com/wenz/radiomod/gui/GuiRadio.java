package com.wenz.radiomod.gui;

import com.wenz.radiomod.block.TileRadio;
import com.wenz.radiomod.client.audio.RadioAudioManager;
import com.wenz.radiomod.network.PacketHandler;
import com.wenz.radiomod.network.PacketSetUrl;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

public class GuiRadio extends GuiContainer {

    private final ContainerRadio container;
    private GuiTextField urlField;

    // Colors
    private static final int BG       = 0xDD1a1a2e;
    private static final int BORDER   = 0xFFe94560;
    private static final int TITLE_BG = 0xFF0f3460;
    private static final int TEXT_COL = 0xFFe94560;
    private static final int GREEN    = 0xFF00ff88;
    private static final int GRAY     = 0xFF777777;

    public GuiRadio(ContainerRadio container) {
        super(container);
        this.container = container;
        this.xSize = 240;
        this.ySize = 110;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);

        int x = guiLeft;
        int y = guiTop;

        // URL text field
        urlField = new GuiTextField(0, fontRenderer, x + 10, y + 30, 220, 18);
        urlField.setMaxStringLength(512);
        urlField.setText(container.getTile().getStreamUrl());
        urlField.setFocused(true);

        // Buttons
        buttonList.clear();
        buttonList.add(new GuiButton(1, x + 10,  y + 58, 105, 20, "\u25B6 Play"));
        buttonList.add(new GuiButton(2, x + 125, y + 58, 105, 20, "\u23F9 Stop"));
        buttonList.add(new GuiButton(3, x + 10,  y + 84, 50, 18, "Vol -"));
        buttonList.add(new GuiButton(4, x + 65,  y + 84, 50, 18, "Vol +"));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        TileRadio tile = container.getTile();

        switch (button.id) {
            case 1: // Play
                String url = urlField.getText().trim();
                if (!url.isEmpty()) {
                    PacketHandler.INSTANCE.sendToServer(
                            new PacketSetUrl(tile.getPos(), url, true, tile.getVolume()));
                    RadioAudioManager.play(tile.getPos(), url, tile.getVolume());
                }
                break;
            case 2: // Stop
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), false, tile.getVolume()));
                RadioAudioManager.stop(tile.getPos());
                break;
            case 3: // Vol -
                float vDown = Math.max(0f, tile.getVolume() - 0.1f);
                tile.setVolume(vDown);
                RadioAudioManager.setVolume(tile.getPos(), vDown);
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), tile.isPlaying(), vDown));
                break;
            case 4: // Vol +
                float vUp = Math.min(1f, tile.getVolume() + 0.1f);
                tile.setVolume(vUp);
                RadioAudioManager.setVolume(tile.getPos(), vUp);
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), tile.isPlaying(), vUp));
                break;
        }
    }

    /* ---------- input ---------- */

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (urlField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.player.closeScreen();
                return;
            }
            urlField.textboxKeyTyped(typedChar, keyCode);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        urlField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /* ---------- rendering ---------- */

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int x = guiLeft, y = guiTop;
        int w = xSize, h = ySize;

        // Background
        drawRect(x, y, x + w, y + h, BG);
        // Border
        drawRect(x, y, x + w, y + 1, BORDER);
        drawRect(x, y + h - 1, x + w, y + h, BORDER);
        drawRect(x, y, x + 1, y + h, BORDER);
        drawRect(x + w - 1, y, x + w, y + h, BORDER);
        // Title bar
        drawRect(x + 1, y + 1, x + w - 1, y + 20, TITLE_BG);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString("\u266B Radio", 8, 6, TEXT_COL);

        // Playing indicator
        TileRadio tile = container.getTile();
        boolean isOn = RadioAudioManager.isPlaying(tile.getPos());
        String status = isOn ? "\u25CF ON" : "\u25CB OFF";
        int statusColor = isOn ? GREEN : GRAY;
        fontRenderer.drawString(status, xSize - fontRenderer.getStringWidth(status) - 8, 6, statusColor);

        // Volume
        int pct = Math.round(tile.getVolume() * 100);
        fontRenderer.drawString("Vol: " + pct + "%", 125, 89, 0xCCCCCC);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        urlField.drawTextBox();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
