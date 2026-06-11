package com.wenz.radiomod.gui;

import com.wenz.radiomod.block.TileRadio;
import com.wenz.radiomod.client.audio.FileUploader;
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

public class GuiRadio extends GuiContainer {

    private final ContainerRadio container;
    private GuiTextField inputField;

    /* ======== Persistent search state per radio block ======== */
    private static final Map<BlockPos, SearchState> SAVED_STATE = new HashMap<BlockPos, SearchState>();

    private static class SearchState {
        boolean searchMode = false;
        String query = "";
        List<Mp3JuiceSearch.SearchResult> results = new ArrayList<Mp3JuiceSearch.SearchResult>();
        int scrollOffset = 0;
        int selectedIndex = -1;
    }

    private boolean searchMode = false;
    private List<Mp3JuiceSearch.SearchResult> searchResults = new ArrayList<Mp3JuiceSearch.SearchResult>();
    private int scrollOffset = 0;
    private int selectedIndex = -1;

    private boolean searching = false;
    private boolean converting = false;
    private boolean uploading = false;
    private String statusMessage = null;
    private String statusColor = "gray";

    private boolean draggingVolume = false;
    private boolean draggingSeek = false;

    /* ======== Layout constants ======== */
    private static final int SLIDER_W = 120;
    private static final int SLIDER_H = 10;
    private static final int KNOB_W = 6;

    private static final int RESULTS_VISIBLE = 5;
    private static final int RESULT_HEIGHT = 22;

    // Player preview panel dimensions
    private static final int PREVIEW_H = 38;
    private static final int SEEK_BAR_H = 6;

    /* ======== Colors ======== */
    private static final int BG          = 0xDD1a1a2e;
    private static final int BORDER      = 0xFFe94560;
    private static final int TITLE_BG    = 0xFF0f3460;
    private static final int TEXT_COL    = 0xFFe94560;
    private static final int GREEN       = 0xFF00ff88;
    private static final int GRAY        = 0xFF777777;
    private static final int RESULT_BG   = 0xFF16213e;
    private static final int RESULT_HOV  = 0xFF1a1a40;
    private static final int RESULT_SEL  = 0xFF0f3460;
    private static final int DUR_COL     = 0xFF888888;
    private static final int WHITE       = 0xFFFFFFFF;
    private static final int YELLOW      = 0xFFffd700;
    private static final int RED         = 0xFFff4444;
    private static final int SLIDER_BG   = 0xFF333355;
    private static final int SLIDER_FG   = 0xFFe94560;
    private static final int SLIDER_KNB  = 0xFFFFFFFF;
    private static final int PREVIEW_BG  = 0xFF0d1117;
    private static final int SEEK_BG     = 0xFF333355;
    private static final int SEEK_FG     = 0xFF00ff88;
    private static final int SEEK_KNOB   = 0xFFFFFFFF;

    /* ======== Button IDs ======== */
    private static final int BTN_PLAY    = 1;
    private static final int BTN_STOP    = 2;
    private static final int BTN_MODE    = 5;
    private static final int BTN_SEARCH  = 6;
    private static final int BTN_MUTE    = 7;
    private static final int BTN_REPEAT  = 8;
    private static final int BTN_PAUSE   = 9;
    private static final int BTN_UPLOAD  = 10;

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

    private boolean isPreviewVisible() {
        BlockPos pos = container.getTile().getPos();
        return RadioAudioManager.isPlaying(pos) || RadioAudioManager.isPaused(pos);
    }

    private void rebuildGui(String restoreQuery) {
        // Calculate height
        int baseH = 100;

        if (searchMode && !searchResults.isEmpty()) {
            baseH = 100 + Math.min(searchResults.size(), RESULTS_VISIBLE) * RESULT_HEIGHT + 28;
        } else if (searchMode && statusMessage != null) {
            baseH = 115;
        }

        // Add player preview panel if something is playing/paused
        if (isPreviewVisible()) {
            baseH += PREVIEW_H + 4;
        }

        this.ySize = baseH;
        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;
        int x = guiLeft;
        int y = guiTop;

        String prevText = (inputField != null) ? inputField.getText() : restoreQuery;
        inputField = new GuiTextField(0, fontRenderer, x + 10, y + 30, 197, 18);
        inputField.setMaxStringLength(512);

        if (prevText != null && !prevText.isEmpty()) {
            inputField.setText(prevText);
        } else if (!searchMode) {
            inputField.setText(container.getTile().getStreamUrl());
        }
        inputField.setFocused(true);

        buttonList.clear();

        String modeLabel = searchMode ? "\u266B URL" : "\u26A1 Search";
        buttonList.add(new GuiButton(BTN_MODE, x + 210, y + 30, 40, 18, modeLabel));

        if (searchMode) {
            buttonList.add(new GuiButton(BTN_SEARCH, x + 10, y + 56, 105, 20, "\u26A1 Search"));
            buttonList.add(new GuiButton(BTN_STOP,   x + 125, y + 56, 105, 20, "\u23F9 Stop"));
        } else {
            buttonList.add(new GuiButton(BTN_PLAY,   x + 10,  y + 56, 72, 20, "\u25B6 Play"));
            buttonList.add(new GuiButton(BTN_UPLOAD,  x + 85,  y + 56, 72, 20, "\u2191 Upload"));
            buttonList.add(new GuiButton(BTN_STOP,   x + 160, y + 56, 72, 20, "\u23F9 Stop"));
        }

        // Mute button
        boolean muted = RadioAudioManager.isLocalMuted(container.getTile().getPos());
        String muteLabel = muted ? "\uD83D\uDD0A" : "\uD83D\uDD07";
        buttonList.add(new GuiButton(BTN_MUTE, x + 210, y + 82, 20, 14, muteLabel));

        // Repeat button — highlighted when active
        boolean rep = container.getTile().isRepeat();
        String repLabel = "\u21BB";
        buttonList.add(new GuiButton(BTN_REPEAT, x + 232, y + 82, 20, 14, repLabel));
    }

    /* ======== Volume slider coords ======== */

    private int sliderX() { return 10; }
    private int sliderY() { return 82; }

    private float getSliderValue(int mouseX) {
        int sx = guiLeft + sliderX();
        float raw = (float)(mouseX - sx) / (float) SLIDER_W;
        return Math.max(0f, Math.min(1f, raw));
    }

    /* ======== Player preview coords ======== */

    /** Y position of the player preview panel (absolute screen coords). */
    private int previewY() {
        // preview sits at the bottom of the GUI
        return guiTop + ySize - PREVIEW_H - 2;
    }

    private int seekBarX() { return guiLeft + 30; }
    private int seekBarY() { return previewY() + 24; }
    private int seekBarW() { return xSize - 60; }

    /* ======== Close ======== */

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);

        TileRadio tile = container.getTile();
        BlockPos pos = tile.getPos();

        SearchState state = new SearchState();
        state.searchMode = searchMode;
        state.query = inputField.getText();
        state.results = searchResults;
        state.scrollOffset = scrollOffset;
        state.selectedIndex = selectedIndex;
        SAVED_STATE.put(pos, state);

        if (!searchMode) {
            String currentText = inputField.getText().trim();
            if (!currentText.isEmpty() && !currentText.equals(tile.getStreamUrl())) {
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), currentText, tile.isPlaying(), tile.getVolume(), tile.isRepeat(), tile.getTrackTitle()));
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
                    if (searchResults.isEmpty()) inputField.setText("");
                } else {
                    inputField.setText(tile.getStreamUrl());
                }
                rebuildGui(null);
                break;

            case BTN_PLAY: {
                String url = inputField.getText().trim();
                if (!url.isEmpty()) {
                    String title = url; // For direct URL, use URL as title
                    if (title.length() > 50) title = title.substring(title.lastIndexOf('/') + 1);
                    PacketHandler.INSTANCE.sendToServer(
                            new PacketSetUrl(tile.getPos(), url, true, tile.getVolume(), tile.isRepeat(), title));
                    RadioAudioManager.play(tile.getPos(), url, tile.getVolume());
                    rebuildGui(null);
                }
                break;
            }

            case BTN_SEARCH:
                doSearch();
                break;

            case BTN_UPLOAD:
                doUpload();
                break;

            case BTN_STOP:
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), false, tile.getVolume(), tile.isRepeat(), ""));
                RadioAudioManager.stop(tile.getPos());
                rebuildGui(null);
                break;

            case BTN_MUTE:
                if (RadioAudioManager.isLocalMuted(tile.getPos())) {
                    RadioAudioManager.unmuteLocal(tile.getPos());
                    if (tile.isPlaying() && !tile.getStreamUrl().isEmpty()) {
                        RadioAudioManager.play(tile.getPos(), tile.getStreamUrl(), tile.getVolume());
                    }
                } else {
                    RadioAudioManager.muteLocal(tile.getPos());
                }
                rebuildGui(null);
                break;

            case BTN_REPEAT: {
                boolean newRepeat = !tile.isRepeat();
                tile.setRepeat(newRepeat);
                PacketHandler.INSTANCE.sendToServer(
                        new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), tile.isPlaying(), tile.getVolume(), newRepeat, tile.getTrackTitle()));
                rebuildGui(null);
                break;
            }
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

    private void playResult(final Mp3JuiceSearch.SearchResult result) {
        if (result == null || converting) return;

        converting = true;
        statusMessage = "Converting...";
        statusColor = "yellow";

        final TileRadio tile = container.getTile();
        final BlockPos pos = tile.getPos();
        final float volume = tile.getVolume();
        final boolean repeat = tile.isRepeat();
        final String title = result.title;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String mp3Url = Mp3JuiceConverter.convert(result.videoId);

                    if (mp3Url != null) {
                        statusMessage = "\u25B6 " + title;
                        statusColor = "green";

                        PacketHandler.INSTANCE.sendToServer(
                                new PacketSetUrl(pos, mp3Url, true, volume, repeat, title));
                        RadioAudioManager.play(pos, mp3Url, volume);

                        mc.addScheduledTask(new Runnable() {
                            @Override
                            public void run() { rebuildGui(null); }
                        });
                    } else {
                        String err = Mp3JuiceConverter.lastError;
                        if (err != null && err.contains("unavailable")) {
                            statusMessage = "Track unavailable \u2014 try another";
                        } else {
                            statusMessage = "Conversion failed \u2014 try another";
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

    /* ======== File upload ======== */

    private void doUpload() {
        if (uploading || converting) return;

        uploading = true;
        statusMessage = "Opening file picker...";
        statusColor = "yellow";

        final TileRadio tile = container.getTile();
        final BlockPos pos = tile.getPos();
        final float volume = tile.getVolume();
        final boolean repeat = tile.isRepeat();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String fileUrl = FileUploader.openAndUpload();

                    if (fileUrl != null) {
                        // Extract filename for track title
                        String title = fileUrl;
                        int lastSlash = fileUrl.lastIndexOf('/');
                        if (lastSlash >= 0) title = fileUrl.substring(lastSlash + 1);
                        // Remove the id prefix (e.g., "m1abc_song.mp3" → "song.mp3")
                        int underscore = title.indexOf('_');
                        if (underscore > 0) title = title.substring(underscore + 1);
                        // URL-decode the title
                        try { title = java.net.URLDecoder.decode(title, "UTF-8"); }
                        catch (Exception ignored) {}
                        final String trackTitle = title;

                        statusMessage = "\u25B6 " + trackTitle;
                        statusColor = "green";

                        PacketHandler.INSTANCE.sendToServer(
                                new PacketSetUrl(pos, fileUrl, true, volume, repeat, trackTitle));
                        RadioAudioManager.play(pos, fileUrl, volume);

                        mc.addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                inputField.setText(fileUrl);
                                rebuildGui(null);
                            }
                        });
                    } else {
                        String err = FileUploader.lastError;
                        if (err != null) {
                            statusMessage = err;
                            statusColor = "red";
                        } else {
                            statusMessage = null; // user cancelled — no error
                        }
                    }
                } catch (Exception e) {
                    statusMessage = "Upload error";
                    statusColor = "red";
                } finally {
                    uploading = false;
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() { rebuildGui(null); }
                    });
                }
            }
        }, "RadioMod-Upload").start();
    }

    /* ======== Input handling ======== */

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (inputField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.player.closeScreen();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                if (searchMode) doSearch();
                else actionPerformed(new GuiButton(BTN_PLAY, 0, 0, 0, 0, ""));
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

        // Player preview panel interactions
        if (isPreviewVisible()) {
            BlockPos pos = container.getTile().getPos();
            int py = previewY();

            // Pause/Play button area (left side of preview)
            if (mouseX >= guiLeft + 8 && mouseX <= guiLeft + 26 && mouseY >= py + 4 && mouseY <= py + 20) {
                if (RadioAudioManager.isPaused(pos)) {
                    RadioAudioManager.resume(pos);
                } else {
                    RadioAudioManager.pause(pos);
                }
                return;
            }

            // Seek bar
            int sbX = seekBarX();
            int sbY = seekBarY();
            int sbW = seekBarW();
            float total = RadioAudioManager.getTotalSeconds(pos);
            if (total > 0 && mouseX >= sbX && mouseX <= sbX + sbW && mouseY >= sbY - 3 && mouseY <= sbY + SEEK_BAR_H + 3) {
                draggingSeek = true;
                updateSeek(mouseX);
                return;
            }
        }

        // Search results
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
        if (draggingSeek) updateSeek(mouseX);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingVolume) {
            draggingVolume = false;
            TileRadio tile = container.getTile();
            PacketHandler.INSTANCE.sendToServer(
                    new PacketSetUrl(tile.getPos(), tile.getStreamUrl(), tile.isPlaying(), tile.getVolume(), tile.isRepeat(), tile.getTrackTitle()));
        }
        if (draggingSeek) {
            draggingSeek = false;
        }
    }

    private void updateVolume(int mouseX) {
        float vol = getSliderValue(mouseX);
        TileRadio tile = container.getTile();
        tile.setVolume(vol);
        RadioAudioManager.setVolume(tile.getPos(), vol);
    }

    private void updateSeek(int mouseX) {
        BlockPos pos = container.getTile().getPos();
        float total = RadioAudioManager.getTotalSeconds(pos);
        if (total <= 0) return;

        int sbX = seekBarX();
        int sbW = seekBarW();
        float frac = (float)(mouseX - sbX) / (float) sbW;
        frac = Math.max(0f, Math.min(1f, frac));
        float seekSec = frac * total;
        RadioAudioManager.seek(pos, seekSec);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (searchMode && !searchResults.isEmpty()) {
            int scroll = Mouse.getEventDWheel();
            if (scroll > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else if (scroll < 0) {
                if (scrollOffset + RESULTS_VISIBLE < searchResults.size()) scrollOffset++;
            }
        }
    }

    /* ======== Time formatting ======== */

    private static String formatTime(float seconds) {
        int s = Math.max(0, (int) seconds);
        int min = s / 60;
        int sec = s % 60;
        return String.format("%d:%02d", min, sec);
    }

    /* ======== Rendering ======== */

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        int x = guiLeft, y = guiTop;
        int w = xSize, h = ySize;

        // Main background
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

        // Search results
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
                if (selected) drawRect(listX, itemY, listX + 2, itemY + RESULT_HEIGHT - 2, GREEN);
            }
        }

        // ======== Player preview panel ========
        if (isPreviewVisible()) {
            BlockPos pos = tile.getPos();
            int py = previewY();
            int pw = w - 4;
            int px = x + 2;

            // Panel background
            drawRect(px, py, px + pw, py + PREVIEW_H, PREVIEW_BG);
            drawRect(px, py, px + pw, py + 1, 0xFF333355);

            // Seek bar
            float elapsed = RadioAudioManager.getElapsedSeconds(pos);
            float total = RadioAudioManager.getTotalSeconds(pos);

            int sbX = seekBarX();
            int sbY = seekBarY();
            int sbW = seekBarW();

            drawRect(sbX, sbY, sbX + sbW, sbY + SEEK_BAR_H, SEEK_BG);

            if (total > 0) {
                float frac = Math.min(1f, elapsed / total);
                int seekFillW = (int)(frac * sbW);
                drawRect(sbX, sbY, sbX + seekFillW, sbY + SEEK_BAR_H, SEEK_FG);

                // Seek knob
                int seekKnobX = sbX + seekFillW - 3;
                drawRect(seekKnobX, sbY - 2, seekKnobX + 6, sbY + SEEK_BAR_H + 2, SEEK_KNOB);
            } else {
                // Unknown duration — show indeterminate
                int animW = 30;
                int animX = (int)((System.currentTimeMillis() / 20) % (sbW + animW)) - animW;
                int ax1 = Math.max(sbX, sbX + animX);
                int ax2 = Math.min(sbX + sbW, sbX + animX + animW);
                if (ax2 > ax1) drawRect(ax1, sbY, ax2, sbY + SEEK_BAR_H, 0xFF555577);
            }

            // Pause/Play button highlight on hover
            if (mouseX >= px + 6 && mouseX <= px + 24 && mouseY >= py + 4 && mouseY <= py + 18) {
                drawRect(px + 5, py + 3, px + 25, py + 19, 0x33FFFFFF);
            }
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        TileRadio tile = container.getTile();
        BlockPos pos = tile.getPos();

        // Title bar
        String title;
        int titleCol = TEXT_COL;
        if (uploading) {
            String upMsg = FileUploader.status != null ? FileUploader.status : "Uploading...";
            title = "\u2191 " + truncateRight(upMsg, xSize / 2);
            titleCol = YELLOW;
        } else if (searchMode) {
            title = "\u26A1 Radio \u2014 mp3juice.sc";
        } else {
            title = "\u266B Radio";
        }
        fontRenderer.drawString(title, 8, 6, titleCol);

        boolean isOn = RadioAudioManager.isPlaying(pos) || RadioAudioManager.isPaused(pos);
        String status;
        int statusCol;
        if (RadioAudioManager.isPaused(pos)) {
            status = "\u23F8 PAUSE";
            statusCol = YELLOW;
        } else if (isOn) {
            status = "\u25CF ON";
            statusCol = GREEN;
        } else {
            status = "\u25CB OFF";
            statusCol = GRAY;
        }
        if (tile.isRepeat()) status = "\u21BB " + status;
        fontRenderer.drawString(status, xSize - fontRenderer.getStringWidth(status) - 8, 6, statusCol);

        // Volume label
        int pct = Math.round(tile.getVolume() * 100);
        fontRenderer.drawString("Vol: " + pct + "%", sliderX() + SLIDER_W + 8, sliderY() + 1, 0xCCCCCC);

        // Repeat indicator color
        boolean rep = tile.isRepeat();
        // Find the repeat button and update its display
        for (GuiButton btn : buttonList) {
            if (btn.id == BTN_REPEAT) {
                btn.packedFGColour = rep ? 0x00ff88 : 0x777777;
            }
        }

        // Search mode content
        if (searchMode) {
            if (searching) {
                fontRenderer.drawString("Searching mp3juice.sc...", 10, 95, YELLOW);
            } else if (converting) {
                fontRenderer.drawString("Converting to MP3...", 10, 95, YELLOW);
            } else if (statusMessage != null && searchResults.isEmpty()) {
                int col = colorFromName(statusColor);
                fontRenderer.drawString(statusMessage, 10, 95, col);
            } else if (!searchResults.isEmpty()) {
                String info = (scrollOffset + 1) + "-"
                        + Math.min(scrollOffset + RESULTS_VISIBLE, searchResults.size())
                        + " / " + searchResults.size();
                fontRenderer.drawString(info, 10, 95, GRAY);

                if (statusMessage != null) {
                    int col = colorFromName(statusColor);
                    String msg = truncateRight(statusMessage, xSize - fontRenderer.getStringWidth(info) - 25);
                    fontRenderer.drawString(msg, xSize - fontRenderer.getStringWidth(msg) - 10, 95, col);
                } else {
                    fontRenderer.drawString("Click to play", xSize - fontRenderer.getStringWidth("Click to play") - 10, 95, GRAY);
                }

                int visibleCount = Math.min(searchResults.size() - scrollOffset, RESULTS_VISIBLE);
                for (int i = 0; i < visibleCount; i++) {
                    int idx = scrollOffset + i;
                    Mp3JuiceSearch.SearchResult r = searchResults.get(idx);
                    int itemY = 105 + i * RESULT_HEIGHT;
                    boolean selected = idx == selectedIndex;

                    String titleText = truncateRight(r.title, xSize - 65);
                    fontRenderer.drawString(titleText, 15, itemY + 3, selected ? GREEN : WHITE);
                    fontRenderer.drawString(r.duration, xSize - fontRenderer.getStringWidth(r.duration) - 15, itemY + 3, DUR_COL);

                    String icon = r.source.equals("yt") ? "\u25B6" : "\u266A";
                    int iconCol = r.source.equals("yt") ? 0xFFFF0000 : 0xFFFF5500;
                    fontRenderer.drawString(icon, xSize - fontRenderer.getStringWidth(r.duration) - 25, itemY + 3, iconCol);
                }
            }
        }

        // ======== Player preview panel ========
        if (isPreviewVisible()) {
            int pyRel = previewY() - guiTop; // relative to GUI top

            // Pause/Play icon
            boolean paused = RadioAudioManager.isPaused(pos);
            String ppIcon = paused ? "\u25B6" : "\u23F8";
            int ppCol = paused ? GREEN : WHITE;
            fontRenderer.drawString(ppIcon, 9, pyRel + 6, ppCol);

            // Track title
            String trackTitle = tile.getTrackTitle();
            if (trackTitle == null || trackTitle.isEmpty()) {
                String url = tile.getStreamUrl();
                if (url.length() > 40) {
                    trackTitle = "..." + url.substring(url.length() - 37);
                } else {
                    trackTitle = url;
                }
            }
            String displayTitle = truncateRight(trackTitle, xSize - 60);
            fontRenderer.drawString(displayTitle, 28, pyRel + 6, WHITE);

            // Time display
            float elapsed = RadioAudioManager.getElapsedSeconds(pos);
            float total = RadioAudioManager.getTotalSeconds(pos);

            String timeStr;
            if (total > 0) {
                timeStr = formatTime(elapsed) + " / " + formatTime(total);
            } else {
                timeStr = formatTime(elapsed);
            }
            int timeW = fontRenderer.getStringWidth(timeStr);

            // Time on the right of the seek bar
            fontRenderer.drawString(timeStr, xSize - timeW - 8, pyRel + 24, GRAY);

            // Elapsed on the left
            fontRenderer.drawString(formatTime(elapsed), 8, pyRel + 24, GRAY);
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

    /* ======== Utility ======== */

    private int colorFromName(String name) {
        if ("red".equals(name)) return RED;
        if ("green".equals(name)) return GREEN;
        if ("yellow".equals(name)) return YELLOW;
        return GRAY;
    }

    private String truncateRight(String text, int maxWidth) {
        if (fontRenderer.getStringWidth(text) <= maxWidth) return text;
        while (fontRenderer.getStringWidth(text + "...") > maxWidth && text.length() > 0) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }
}
