package com.wenz.radiomod.client.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens a native file picker, uploads the selected audio file to the VDS proxy,
 * and returns a URL that can be played through the radio.
 *
 * Flow: file dialog → read file → HTTP POST to proxy /upload → get URL back.
 * Other players hear it too because the URL is synced via TileRadio.
 */
public class FileUploader {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final String PROXY_HOST = "166.1.144.133";
    private static final int PROXY_PORT = 8300;
    private static final String UPLOAD_URL = "http://" + PROXY_HOST + ":" + PROXY_PORT + "/upload";
    private static final long MAX_SIZE = 50L * 1024 * 1024; // 50 MB

    /** Current status for GUI display. */
    public static volatile String status = null;
    /** Last error for GUI display. */
    public static volatile String lastError = null;

    /**
     * Open a file picker dialog and upload the selected file.
     * Blocking — call from a background thread only.
     * @return the proxy URL to play, or null if cancelled/failed
     */
    public static String openAndUpload() {
        lastError = null;
        status = null;

        // Step 1: Open native file dialog
        File selected = openFileDialog();
        if (selected == null) {
            return null; // user cancelled
        }

        // Validate
        if (!selected.exists() || !selected.isFile()) {
            lastError = "File not found";
            return null;
        }
        if (selected.length() > MAX_SIZE) {
            lastError = "File too large (max 50 MB)";
            return null;
        }
        if (selected.length() == 0) {
            lastError = "File is empty";
            return null;
        }

        // Step 2: Upload
        status = "Uploading " + selected.getName() + "...";
        LOG.info("[RadioMod] Uploading: {} ({} KB)", selected.getName(), selected.length() / 1024);
        String url = upload(selected);

        status = null;
        return url;
    }

    /** Show a native file picker, return selected File or null. */
    private static File openFileDialog() {
        final AtomicReference<File> result = new AtomicReference<File>(null);
        try {
            // Must run on AWT Event Dispatch Thread to avoid LWJGL conflicts
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    } catch (Exception ignored) {}

                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("Select audio file / Выберите аудиофайл");
                    chooser.setFileFilter(new FileNameExtensionFilter(
                            "Audio (mp3, wav, ogg, flac, m4a, aac)",
                            "mp3", "wav", "ogg", "flac", "m4a", "aac", "wma", "opus"));
                    chooser.setAcceptAllFileFilterUsed(true);
                    chooser.setMultiSelectionEnabled(false);

                    int ret = chooser.showOpenDialog(null);
                    if (ret == JFileChooser.APPROVE_OPTION) {
                        result.set(chooser.getSelectedFile());
                    }
                }
            });
        } catch (Exception e) {
            LOG.error("[RadioMod] File dialog error: {}", e.getMessage());
            lastError = "Could not open file dialog";
        }
        return result.get();
    }

    /** Upload a file to the proxy, return the playback URL or null. */
    private static String upload(File file) {
        CloseableHttpClient client = null;
        CloseableHttpResponse resp = null;
        try {
            // Read file into memory
            byte[] data = new byte[(int) file.length()];
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.readFully(data);
            raf.close();

            // Build upload URL with filename
            String encodedName = URLEncoder.encode(file.getName(), "UTF-8");
            String url = UPLOAD_URL + "?name=" + encodedName;

            client = HttpClients.createDefault();
            HttpPost post = new HttpPost(url);
            post.setConfig(RequestConfig.custom()
                    .setConnectTimeout(10000)
                    .setSocketTimeout(120000) // 2 min for large files
                    .setConnectionRequestTimeout(5000)
                    .build());
            post.setEntity(new ByteArrayEntity(data));
            post.setHeader("Content-Type", "application/octet-stream");

            resp = client.execute(post);
            int code = resp.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(resp.getEntity(), "UTF-8");

            if (code != 200) {
                LOG.warn("[RadioMod] Upload HTTP {}: {}", code, body);
                lastError = "Upload failed (HTTP " + code + ")";
                return null;
            }

            // Parse JSON: {"url": "http://...", "size": 1234}
            JsonObject json = new JsonParser().parse(body).getAsJsonObject();
            String fileUrl = json.get("url").getAsString();

            LOG.info("[RadioMod] Upload OK: {}", fileUrl);
            return fileUrl;

        } catch (java.net.ConnectException e) {
            LOG.error("[RadioMod] Upload: proxy unreachable — {}", e.getMessage());
            lastError = "Proxy unreachable";
            return null;
        } catch (Exception e) {
            LOG.error("[RadioMod] Upload error: {}", e.getMessage());
            lastError = "Upload error: " + e.getMessage();
            return null;
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }
    }
}
