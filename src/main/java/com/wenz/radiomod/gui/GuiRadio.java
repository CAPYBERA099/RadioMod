package com.wenz.radiomod.gui;

import com.wenz.radiomod.block.TileRadio;
import com.wenz.radiomod.client.audio.Mp3JuiceConverter;
import com.wenz.radiomod.client.audio.Mp3JuiceSearch;
import com.wenz.radiomod.client.audio.RadioAudioManager;
import com.wenz.radiomod.network.PacketHandler;
import com.wenz.radiomod.network.PacketSetUrl;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Radio GUI with two modes:
 *   1. URL mode — paste a direct URL and play
 *   2. Search mode — search mp3juice.sc, browse results, click to play via mp3juice converter
 *
 * Features: volume slider, state saving, server sync for all players.
 */
public class GuiRadio extends GuiContainer {

    private final ContainerRadio container;
    private GuiTextField inputField;

    /* ======== Persistent state per radio block ======== */
    private static final Map<BlockPos, SearchState> SAVED_STATE = new HashMap<BlockPos, SearchState>();

    private static class SearchState {
        boolean searchMode = false;
        String query = "";
        List<Mp3JuiceSearch.SearchResult> results = new ArrayList<Mp3JuiceSearch.SearchResult>();
        int scrollOffset = 0;
        int selectedIndex = -1;
    }

    // Current state (loaded from SAVED_STATE on init, saved back on close)
    private boolean searchMode = false;
    private List<Mp3JuiceSearch.SearchResult> searchResults = new ArrayList<Mp3JuiceSearch.SearchResult>();
    private int scrollOffset = 0;
    private int selectedIndex = -1;

    // Search/convert status
    private boolean searching = false;
    private boolean converting = false;
    private String statusMessage = null; // shown in results area
    private String statusColor = "gray"; // "gray", "red", "green", "yellow"

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
    private static final int RED        = 0xFFff4444;
    private static final int SLIDER_BG  = 0xFF333355;
    private static final int SLIDER_FG  = 0xFFe94560;
    private static final int SLIDER_KNB = 0xFFFFFFFF;

    // Button IDs
    private static final int BTN_PLAY    = 1;
    private static final int BTN_STOP    = 2;
    private static final int BTN_MODE    = 5;
    private static final int BTN_SEARCH  = 6;
    private static final int BTN_MUTE    = 7;

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

        // Restore saved state for this radio block
        BlockPos pos = container.getTile().getPos();
        SearchState saved = SAVED_STATE.get(pos);
        if (saved != null) {
            searchMode = saved.searchMode;
            searchResults = saved.results;
            scrollOffset = saved.scrollOffset;
            selectedIndex = saved.selectedIndex;
        }

        rebuildGui(saved != null ? saved.query : null);
    }

    private void rebuildGui(String restoreQuery) {
        int x, y;

        // Resize based on mode
        if (searchMode && !searchResults.isEmpty()) {
            this.ySize = 115 + Math.min(searchResults.size(), RESULTS_VISIBLE) * RESULT_HEIGHT + 28;
        } else if (searchMode && statusMessage != null) {
            this.ySize = 130;
        } else {
            this.ySize = 115;
        }
        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;
        x = guiLeft;
        y = guiTop;

        // Input field
        String prevText = (inputField != null) ? inputField.getText() : restoreQuery;
        inputField = new GuiTextField(0, fontRenderer, x + 10, y + 30, 197, 18);
        inputField.setMaxStringLength(512);

        if (prevText != null && !prevText.isEmpty()) {
            inputField.setText(prevText);
        } else if (!searchMode) {
            inputField.setText(container.getTile().getStreamUrl());
        }
        inputField.setFocused(true);

        // Buttons
        buttonList.clear();

        String modeLabel = searchMode ? "\u266B URL" : "\u26A1 Search";
        buttonList.add(new GuiButton(BTN_MODE, x + 210, y + 30, 40, 18, modeLabel));

        // Mute button — local only, doesn't affect other players
        boolean muted = RadioAudioManager.isLocalMuted(container.getTile().getPos());
        String muteLabel = muted ? "\uD83D\uDD0A" : "\uD83D\uDD07";
        buttonList.add(new GuiButton(BTN_MUTE, x + 210, y + 82, 40, 14, muteLabel));

        if (searchMode) {
            buttonList.add(new GuiButton(BTN_SEARCH, x + 10, y + 56, 105, 20, "\u26A1 Search"));
            buttonList.add(new GuiButton(BTN_STOP,   x + 125, y + 56, 105, 20, "\u23F9 Stop"));
        } else {
            buttonList.add(new GuiButton(BTN_PLAY, x + 10, y + 56, 105, 20, "\u25B6 Play"));
            buttonList.add(new GuiButton(BTN_STOP, x + 125, y + 56, 105, 20, "\u23F9 Stop"));
        }
    }

    /* ======== Volume slider ======== */

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

        TileRadio tile = container.getTile();
        BlockPos pos = tile.getPos();

        // Save search state
        SearchState state = new SearchState();
        state.searchMode = searchMode;
        state.query = inputField.getText();
        state.results = searchResults;
        state.scrollOffset = scrollOffset;
        state.selectedIndex = selectedIndex;
        SAVED_STATE.put(pos, state);

        // Save URL in URL mode
        if (!searchMode) {
            String currentText = inputField.getText().trim();
            if (!currentText.isEmpty() && !currentText.equals(tile.getStreamUrl())) {
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), currentText, tile.isPlaying(), tile.getVolume()));
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        TileRadio tile = container.getTile();

        switch (button.id) {
            case BTN_MODE:
                searchMode = !searchMode;
                if (searchMode) {
                    // Keep existing query/results if switching back
                    if (searchResults.isEmpty()) {
                        inputField.setText("");
                    }
                } else {
                    inputField.setText(tile.getStreamUrl());
                }
                rebuildGui(null);
                break;

            case BTN_PLAY:
                String url = inputField.getText().trim();
                if (!url.isEmpty()) {
                    PacketHandler.INSTANCE.sendToServer(
                            new PacketSetUrl(tile.getPos(), url, true, tile.getVolume()));
                    RadioAudioManager.play(tile.getPos(), url, tile.getVolume());
                }
                break;

            case BTN_SEARCH:
                doSearch();
                break;

            case BTN_STOP:
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), false, tile.getVolume()));
                RadioAudioManager.stop(tile.getPos());
                break;

            case BTN_MUTE:
                if (RadioAudioManager.isLocalMuted(tile.getPos())) {
                    // Unmute — resume playback if radio is playing on server
                    RadioAudioManager.unmuteLocal(tile.getPos());
                    if (tile.isPlaying() && !tile.getStreamUrl().isEmpty()) {
                        RadioAudioManager.play(tile.getPos(), tile.getStreamUrl(), tile.getVolume());
                    }
                } else {
                    // Mute locally — stops audio but doesn't tell server
                    RadioAudioManager.muteLocal(tile.getPos());
                }
                rebuildGui(null);
                break;
        }
    }

    /* ======== Search ======== */

    private void doSearch() {
        final String query = inputField.getText().trim();
        if (query.isEmpty()) return;

        searching = true;
        statusMessage = "Searching...";
        statusColor = "yellow";
        searchResults.clear();
        scrollOffset = 0;
        selectedIndex = -1;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Mp3JuiceSearch.SearchResult> results = Mp3JuiceSearch.search(query);
                    searchResults = results;
                    if (results.isEmpty()) {
                        statusMessage = "Nothing found :(";
                        statusColor = "red";
                    } else {
                        statusMessage = null;
                    }
                } catch (Exception e) {
                    statusMessage = "Search error";
                    statusColor = "red";
                } finally {
                    searching = false;
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() { rebuildGui(null); }
                    });
                }
            }
        }, "RadioMod-Search").start();
    }

    /**
     * Play a search result: convert via mp3juice.sc → direct MP3 → play.
     * NO YouTube URL created — everything goes through mp3juice converter.
     */
    private void playResult(final Mp3JuiceSearch.SearchResult result) {
        if (result == null || converting) return;

        converting = true;
        statusMessage = "Converting...";
        statusColor = "yellow";

        final TileRadio tile = container.getTile();
        final BlockPos pos = tile.getPos();
        final float volume = tile.getVolume();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Convert via mp3juice.sc backend → direct MP3 URL
                    String mp3Url = Mp3JuiceConverter.convert(result.videoId);

                    if (mp3Url != null) {
                        // Success! Play the direct MP3
                        statusMessage = "\u25B6 " + result.title;
                        statusColor = "green";

                        // Send MP3 URL to server → syncs to all players
                        PacketHandler.INSTANCE.sendToServer(
                                new PacketSetUrl(pos, mp3Url, true, volume));
                        // Play locally for instant feedback
                        RadioAudioManager.play(pos, mp3Url, volume);
                    } else {
                        // Failed
                        String err = Mp3JuiceConverter.lastError;
                        if (err != null && err.contains("unavailable")) {
                            statusMessage = "Track unavailable — try another";
                        } else {
                            statusMessage = "Conversion failed — try another";
                        }
                        statusColor = "red";
                    }
                } catch (Exception e) {
                    statusMessage = "Error: " + e.getMessage();
                    statusColor = "red";
                } finally {
                    converting = false;
                }
            }
        }, "RadioMod-Convert").start();
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

        // Volume slider
        int sx = guiLeft + sliderX();
        int sy = guiTop + sliderY();
        if (mouseX >= sx && mouseX <= sx + SLIDER_W && mouseY >= sy && mouseY <= sy + SLIDER_H) {
            draggingVolume = true;
            updateVolume(mouseX);
            return;
        }

        // Search result click
        if (searchMode && !searchResults.isEmpty() && !converting) {
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
        if (draggingVolume) updateVolume(mouseX);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingVolume) {
            draggingVolume = false;
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

        // Background + border
        drawRect(x, y, x + w, y + h, BG);
        drawRect(x, y, x + w, y + 1, BORDER);
        drawRect(x, y + h - 1, x + w, y + h, BORDER);
        drawRect(x, y, x + 1, y + h, BORDER);
        drawRect(x + w - 1, y, x + w, y + h, BORDER);
        drawRect(x + 1, y + 1, x + w - 1, y + 20, TITLE_BG);

        // Volume slider
        int sx = x + sliderX();
        int sy = y + sliderY();
        TileRadio tile = container.getTile();
        float vol = tile.getVolume();

        drawRect(sx, sy, sx + SLIDER_W, sy + SLIDER_H, SLIDER_BG);
        int fillW = (int)(vol * SLIDER_W);
        drawRect(sx, sy, sx + fillW, sy + SLIDER_H, SLIDER_FG);
        int knobX = sx + fillW - KNOB_W / 2;
        drawRect(knobX, sy - 1, knobX + KNOB_W, sy + SLIDER_H + 1, SLIDER_KNB);
        drawRect(sx, sy, sx + SLIDER_W, sy + 1, 0xFF555577);
        drawRect(sx, sy + SLIDER_H - 1, sx + SLIDER_W, sy + SLIDER_H, 0xFF555577);

        // Search results background
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
        String title = searchMode ? "\u26A1 Radio — mp3juice.sc" : "\u266B Radio";
        fontRenderer.drawString(title, 8, 6, TEXT_COL);

        // Playing indicator
        TileRadio tile = container.getTile();
        boolean isOn = RadioAudioManager.isPlaying(tile.getPos());
        String status = isOn ? "\u25CF ON" : "\u25CB OFF";
        int statusCol = isOn ? GREEN : GRAY;
        fontRenderer.drawString(status, xSize - fontRenderer.getStringWidth(status) - 8, 6, statusCol);

        // Volume label
        int pct = Math.round(tile.getVolume() * 100);
        fontRenderer.drawString("Vol: " + pct + "%", sliderX() + SLIDER_W + 8, sliderY() + 1, 0xCCCCCC);

        // Search mode content
        if (searchMode) {
            if (searching) {
                fontRenderer.drawString("Searching mp3juice.sc...", 10, 95, YELLOW);
            } else if (converting) {
                fontRenderer.drawString("Converting to MP3...", 10, 95, YELLOW);
            } else if (statusMessage != null && searchResults.isEmpty()) {
                int col = statusColor.equals("red") ? RED :
                          statusColor.equals("green") ? GREEN :
                          statusColor.equals("yellow") ? YELLOW : GRAY;
                fontRenderer.drawString(statusMessage, 10, 95, col);
            } else if (!searchResults.isEmpty()) {
                // Results header
                String info = (scrollOffset + 1) + "-"
                        + Math.min(scrollOffset + RESULTS_VISIBLE, searchResults.size())
                        + " / " + searchResults.size();
                fontRenderer.drawString(info, 10, 95, GRAY);

                // Status or "click to play"
                if (statusMessage != null) {
                    int col = statusColor.equals("red") ? RED :
                              statusColor.equals("green") ? GREEN :
                              statusColor.equals("yellow") ? YELLOW : GRAY;
                    String msg = statusMessage;
                    int maxW = xSize - fontRenderer.getStringWidth(info) - 25;
                    if (fontRenderer.getStringWidth(msg) > maxW) {
                        while (fontRenderer.getStringWidth(msg + "...") > maxW && msg.length() > 0) {
                            msg = msg.substring(0, msg.length() - 1);
                        }
                        msg += "...";
                    }
                    fontRenderer.drawString(msg, xSize - fontRenderer.getStringWidth(msg) - 10, 95, col);
                } else {
                    fontRenderer.drawString("Click to play", xSize - fontRenderer.getStringWidth("Click to play") - 10, 95, GRAY);
                }

                // Result items
                int visibleCount = Math.min(searchResults.size() - scrollOffset, RESULTS_VISIBLE);
                for (int i = 0; i < visibleCount; i++) {
                    int idx = scrollOffset + i;
                    Mp3JuiceSearch.SearchResult r = searchResults.get(idx);
                    int itemY = 105 + i * RESULT_HEIGHT;
                    boolean selected = idx == selectedIndex;

                    // Truncate title
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

                    // Source icon
                    String icon = r.source.equals("yt") ? "\u25B6" : "\u266A";
                    int iconCol = r.source.equals("yt") ? 0xFFFF0000 : 0xFFFF5500;
                    fontRenderer.drawString(icon, xSize - fontRenderer.getStringWidth(r.duration) - 25, itemY + 3, iconCol);
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
