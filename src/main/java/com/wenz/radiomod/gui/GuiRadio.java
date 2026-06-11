package com.wenz.radiomod.gui;

import com.wenz.radiomod.block.TileRadio;
import com.wenz.radiomod.client.audio.Mp3JuiceSearch;
import com.wenz.radiomod.client.audio.RadioAudioManager;
import com.wenz.radiomod.network.PacketHandler;
import com.wenz.radiomod.network.PacketSetUrl;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Radio GUI with two modes:
 *   1. URL mode — paste a direct URL and play
 *   2. Search mode — search mp3juice.sc, browse results, click to play
 *
 * Features: volume slider, state saving, server sync for all players.
 */
public class GuiRadio extends GuiContainer {

    private final ContainerRadio container;
    private GuiTextField inputField;

    // Mode
    private boolean searchMode = false;

    // Search state
    private List<Mp3JuiceSearch.SearchResult> searchResults = new ArrayList<Mp3JuiceSearch.SearchResult>();
    private int scrollOffset = 0;
    private boolean searching = false;
    private String searchError = null;
    private int selectedIndex = -1;

    // Volume slider
    private boolean draggingVolume = false;
    private static final int SLIDER_W = 120;
    private static final int SLIDER_H = 10;
    private static final int KNOB_W = 6;

    // Layout
    private static final int RESULTS_VISIBLE = 5;
    private static final int RESULT_HEIGHT = 22;

    // Colors
    private static final int BG         = 0xDD1a1a2e;
    private static final int BORDER     = 0xFFe94560;
    private static final int TITLE_BG   = 0xFF0f3460;
    private static final int TEXT_COL   = 0xFFe94560;
    private static final int GREEN      = 0xFF00ff88;
    private static final int GRAY       = 0xFF777777;
    private static final int RESULT_BG  = 0xFF16213e;
    private static final int RESULT_HOV = 0xFF1a1a40;
    private static final int RESULT_SEL = 0xFF0f3460;
    private static final int DUR_COL    = 0xFF888888;
    private static final int WHITE      = 0xFFFFFFFF;
    private static final int YELLOW     = 0xFFffd700;
    private static final int SLIDER_BG  = 0xFF333355;
    private static final int SLIDER_FG  = 0xFFe94560;
    private static final int SLIDER_KNB = 0xFFFFFFFF;

    // Button IDs
    private static final int BTN_PLAY    = 1;
    private static final int BTN_STOP    = 2;
    private static final int BTN_MODE    = 5;
    private static final int BTN_SEARCH  = 6;

    public GuiRadio(ContainerRadio container) {
        super(container);
        this.container = container;
        this.xSize = 260;
        this.ySize = 115;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        rebuildGui();
    }

    private void rebuildGui() {
        int x = guiLeft;
        int y = guiTop;

        // Resize based on mode
        if (searchMode && !searchResults.isEmpty()) {
            this.ySize = 115 + Math.min(searchResults.size(), RESULTS_VISIBLE) * RESULT_HEIGHT + 28;
        } else {
            this.ySize = 115;
        }
        // Recenter
        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;
        x = guiLeft;
        y = guiTop;

        // Input field
        String prevText = (inputField != null) ? inputField.getText() : null;
        inputField = new GuiTextField(0, fontRenderer, x + 10, y + 30, 197, 18);
        inputField.setMaxStringLength(512);

        if (prevText != null) {
            inputField.setText(prevText);
        } else if (!searchMode) {
            inputField.setText(container.getTile().getStreamUrl());
        }
        inputField.setFocused(true);

        // Buttons
        buttonList.clear();

        // Mode toggle (top right of text field)
        String modeLabel = searchMode ? "\u266B URL" : "\u26A1 Search";
        buttonList.add(new GuiButton(BTN_MODE, x + 210, y + 30, 40, 18, modeLabel));

        if (searchMode) {
            buttonList.add(new GuiButton(BTN_SEARCH, x + 10, y + 56, 105, 20, "\u26A1 Search"));
            buttonList.add(new GuiButton(BTN_STOP,   x + 125, y + 56, 105, 20, "\u23F9 Stop"));
        } else {
            buttonList.add(new GuiButton(BTN_PLAY, x + 10, y + 56, 105, 20, "\u25B6 Play"));
            buttonList.add(new GuiButton(BTN_STOP, x + 125, y + 56, 105, 20, "\u23F9 Stop"));
        }
    }

    /* ======== Volume slider geometry (relative to guiLeft/guiTop) ======== */

    private int sliderX() { return 10; }
    private int sliderY() { return 84; }

    private float getSliderValue(int mouseX) {
        int sx = guiLeft + sliderX();
        float raw = (float)(mouseX - sx) / (float) SLIDER_W;
        return Math.max(0f, Math.min(1f, raw));
    }

    /* ======== GUI close — save state ======== */

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);

        // Save current URL to tile entity even if not playing
        TileRadio tile = container.getTile();
        if (!searchMode) {
            String currentText = inputField.getText().trim();
            if (!currentText.isEmpty() && !currentText.equals(tile.getStreamUrl())) {
                // Send to server to persist
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), currentText, tile.isPlaying(), tile.getVolume()));
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        TileRadio tile = container.getTile();

        switch (button.id) {
            case BTN_MODE: // Toggle mode
                searchMode = !searchMode;
                if (searchMode) {
                    inputField.setText("");
                } else {
                    inputField.setText(tile.getStreamUrl());
                    searchResults.clear();
                    scrollOffset = 0;
                }
                rebuildGui();
                break;

            case BTN_PLAY: // Play URL
                String url = inputField.getText().trim();
                if (!url.isEmpty()) {
                    // Send to server → server syncs to ALL clients → everyone plays
                    PacketHandler.INSTANCE.sendToServer(
                            new PacketSetUrl(tile.getPos(), url, true, tile.getVolume()));
                    // Also play locally for instant feedback
                    RadioAudioManager.play(tile.getPos(), url, tile.getVolume());
                }
                break;

            case BTN_SEARCH: // Search
                doSearch();
                break;

            case BTN_STOP: // Stop
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), false, tile.getVolume()));
                RadioAudioManager.stop(tile.getPos());
                break;
        }
    }

    /* ======== Search ======== */

    private void doSearch() {
        final String query = inputField.getText().trim();
        if (query.isEmpty()) return;

        searching = true;
        searchError = null;
        searchResults.clear();
        scrollOffset = 0;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Mp3JuiceSearch.SearchResult> results = Mp3JuiceSearch.search(query);
                    searchResults = results;
                    if (results.isEmpty()) {
                        searchError = "Nothing found :(";
                    }
                } catch (Exception e) {
                    searchError = "Search error";
                } finally {
                    searching = false;
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            rebuildGui();
                        }
                    });
                }
            }
        }, "RadioMod-Search").start();
    }

    private void playResult(Mp3JuiceSearch.SearchResult result) {
        if (result == null) return;
        TileRadio tile = container.getTile();
        String url = result.toYouTubeUrl();

        // Send to server → syncs to all players
        PacketHandler.INSTANCE.sendToServer(
                new PacketSetUrl(tile.getPos(), url, true, tile.getVolume()));
        // Also play locally for instant feedback
        RadioAudioManager.play(tile.getPos(), url, tile.getVolume());
    }

    /* ======== Input ======== */

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (inputField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.player.closeScreen();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                if (searchMode) {
                    doSearch();
                } else {
                    actionPerformed(new GuiButton(BTN_PLAY, 0, 0, 0, 0, ""));
                }
                return;
            }
            inputField.textboxKeyTyped(typedChar, keyCode);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        inputField.mouseClicked(mouseX, mouseY, mouseButton);

        // Check volume slider click
        int sx = guiLeft + sliderX();
        int sy = guiTop + sliderY();
        if (mouseX >= sx && mouseX <= sx + SLIDER_W && mouseY >= sy && mouseY <= sy + SLIDER_H) {
            draggingVolume = true;
            updateVolume(mouseX);
            return;
        }

        // Check search result click
        if (searchMode && !searchResults.isEmpty()) {
            int listX = guiLeft + 10;
            int listY = guiTop + 105;
            int listW = xSize - 20;

            int visibleCount = Math.min(searchResults.size() - scrollOffset, RESULTS_VISIBLE);
            for (int i = 0; i < visibleCount; i++) {
                int itemY = listY + i * RESULT_HEIGHT;
                if (mouseX >= listX && mouseX <= listX + listW
                        && mouseY >= itemY && mouseY <= itemY + RESULT_HEIGHT - 2) {
                    int idx = scrollOffset + i;
                    if (idx < searchResults.size()) {
                        selectedIndex = idx;
                        playResult(searchResults.get(idx));
                    }
                    return;
                }
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingVolume) {
            updateVolume(mouseX);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingVolume) {
            draggingVolume = false;
            // Send final volume to server
            TileRadio tile = container.getTile();
            PacketHandler.INSTANCE.sendToServer(
                    new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), tile.isPlaying(), tile.getVolume()));
        }
    }

    private void updateVolume(int mouseX) {
        float vol = getSliderValue(mouseX);
        TileRadio tile = container.getTile();
        tile.setVolume(vol);
        RadioAudioManager.setVolume(tile.getPos(), vol);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        // Scroll wheel for results
        if (searchMode && !searchResults.isEmpty()) {
            int scroll = Mouse.getEventDWheel();
            if (scroll > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else if (scroll < 0) {
                if (scrollOffset + RESULTS_VISIBLE < searchResults.size()) {
                    scrollOffset++;
                }
            }
        }
    }

    /* ======== Rendering ======== */

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

        // Volume slider
        int sx = x + sliderX();
        int sy = y + sliderY();
        TileRadio tile = container.getTile();
        float vol = tile.getVolume();

        // Slider track
        drawRect(sx, sy, sx + SLIDER_W, sy + SLIDER_H, SLIDER_BG);
        // Filled portion
        int fillW = (int)(vol * SLIDER_W);
        drawRect(sx, sy, sx + fillW, sy + SLIDER_H, SLIDER_FG);
        // Knob
        int knobX = sx + fillW - KNOB_W / 2;
        drawRect(knobX, sy - 1, knobX + KNOB_W, sy + SLIDER_H + 1, SLIDER_KNB);
        // Slider border
        drawRect(sx, sy, sx + SLIDER_W, sy + 1, 0xFF555577);
        drawRect(sx, sy + SLIDER_H - 1, sx + SLIDER_W, sy + SLIDER_H, 0xFF555577);

        // Draw search results
        if (searchMode && !searchResults.isEmpty()) {
            int listX = x + 10;
            int listY = y + 105;
            int listW = w - 20;

            int visibleCount = Math.min(searchResults.size() - scrollOffset, RESULTS_VISIBLE);
            drawRect(listX - 2, listY - 2,
                    listX + listW + 2, listY + visibleCount * RESULT_HEIGHT + 2,
                    0xFF0d1117);

            for (int i = 0; i < visibleCount; i++) {
                int idx = scrollOffset + i;
                int itemY = listY + i * RESULT_HEIGHT;

                boolean hovered = mouseX >= listX && mouseX <= listX + listW
                        && mouseY >= itemY && mouseY <= itemY + RESULT_HEIGHT - 2;
                boolean selected = idx == selectedIndex;

                int bg = selected ? RESULT_SEL : (hovered ? RESULT_HOV : RESULT_BG);
                drawRect(listX, itemY, listX + listW, itemY + RESULT_HEIGHT - 2, bg);

                if (selected) {
                    drawRect(listX, itemY, listX + 2, itemY + RESULT_HEIGHT - 2, GREEN);
                }
            }
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // Title
        String title = searchMode ? "\u26A1 Radio Search" : "\u266B Radio";
        fontRenderer.drawString(title, 8, 6, TEXT_COL);

        // Playing indicator
        TileRadio tile = container.getTile();
        boolean isOn = RadioAudioManager.isPlaying(tile.getPos());
        String status = isOn ? "\u25CF ON" : "\u25CB OFF";
        int statusColor = isOn ? GREEN : GRAY;
        fontRenderer.drawString(status, xSize - fontRenderer.getStringWidth(status) - 8, 6, statusColor);

        // Volume label
        int pct = Math.round(tile.getVolume() * 100);
        fontRenderer.drawString("Vol: " + pct + "%", sliderX() + SLIDER_W + 8, sliderY() + 1, 0xCCCCCC);

        // Search mode extras
        if (searchMode) {
            if (searching) {
                fontRenderer.drawString("Searching...", 10, 95, YELLOW);
            } else if (searchError != null) {
                fontRenderer.drawString(searchError, 10, 95, BORDER);
            } else if (!searchResults.isEmpty()) {
                String info = (scrollOffset + 1) + "-"
                        + Math.min(scrollOffset + RESULTS_VISIBLE, searchResults.size())
                        + " / " + searchResults.size();
                fontRenderer.drawString(info, 10, 95, GRAY);
                fontRenderer.drawString("Click to play", xSize - fontRenderer.getStringWidth("Click to play") - 10, 95, GRAY);

                int visibleCount = Math.min(searchResults.size() - scrollOffset, RESULTS_VISIBLE);
                for (int i = 0; i < visibleCount; i++) {
                    int idx = scrollOffset + i;
                    Mp3JuiceSearch.SearchResult r = searchResults.get(idx);

                    int itemY = 105 + i * RESULT_HEIGHT;
                    boolean selected = idx == selectedIndex;

                    // Truncate title to fit
                    String titleText = r.title;
                    int maxTitleW = xSize - 65;
                    if (fontRenderer.getStringWidth(titleText) > maxTitleW) {
                        while (fontRenderer.getStringWidth(titleText + "...") > maxTitleW && titleText.length() > 0) {
                            titleText = titleText.substring(0, titleText.length() - 1);
                        }
                        titleText += "...";
                    }

                    fontRenderer.drawString(titleText, 15, itemY + 3, selected ? GREEN : WHITE);
                    fontRenderer.drawString(r.duration, xSize - fontRenderer.getStringWidth(r.duration) - 15, itemY + 3, DUR_COL);
                    fontRenderer.drawString(r.source.equals("yt") ? "\u25B6" : "\u266A",
                            xSize - fontRenderer.getStringWidth(r.duration) - 25, itemY + 3,
                            r.source.equals("yt") ? 0xFFFF0000 : 0xFFFF5500);
                }
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        inputField.drawTextBox();
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
