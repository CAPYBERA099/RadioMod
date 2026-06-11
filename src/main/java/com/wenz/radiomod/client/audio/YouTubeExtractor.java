package com.wenz.radiomod.client.audio;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;
import java.util.regex.*;

/**
 * Built-in YouTube audio URL extractor.
 * Uses YouTube's innertube API (Android/iOS client) to get direct audio stream URLs.
 * No external tools (yt-dlp) needed.
 */
public class YouTubeExtractor {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final String INNERTUBE_API = "https://www.youtube.com/youtubei/v1/player";
    private static final String ANDROID_UA =
            "com.google.android.youtube/19.09.37 (Linux; U; Android 12; US) gzip";

    /**
     * Extracts a direct audio stream URL from a YouTube video URL.
     * Returns the URL string, or null on failure.
     */
    public static String extract(String youtubeUrl) {
        String videoId = extractVideoId(youtubeUrl);
        if (videoId == null) {
            LOG.warn("[RadioMod] Could not parse YouTube video ID from: {}", youtubeUrl);
            return null;
        }
        LOG.info("[RadioMod] YouTube video ID: {}", videoId);

        // Try Android client first (usually returns unsigned URLs)
        String url = tryAndroidClient(videoId);
        if (url != null) return url;

        // Try iOS client as fallback
        url = tryIosClient(videoId);
        if (url != null) return url;

        // Try TV embedded client
        url = tryTvClient(videoId);
        if (url != null) return url;

        // Try web page scraping as last resort
        url = tryWebScrape(videoId);
        return url;
    }

    /* ======== Video ID extraction ======== */

    private static String extractVideoId(String url) {
        // youtube.com/watch?v=ID, youtu.be/ID, youtube.com/embed/ID, etc.
        Matcher m = Pattern.compile(
            "(?:youtube\\.com/watch[^\"]*[?&]v=|youtu\\.be/|youtube\\.com/embed/|" +
            "youtube\\.com/shorts/|music\\.youtube\\.com/watch[^\"]*[?&]v=)" +
            "([a-zA-Z0-9_-]{11})"
        ).matcher(url);
        if (m.find()) return m.group(1);

        // Plain video ID
        if (url.matches("^[a-zA-Z0-9_-]{11}$")) return url;

        return null;
    }

    /* ======== Client attempts ======== */

    private static String tryAndroidClient(String videoId) {
        String body = "{"
            + "\"videoId\":\"" + videoId + "\","
            + "\"context\":{"
            +   "\"client\":{"
            +     "\"clientName\":\"ANDROID\","
            +     "\"clientVersion\":\"19.09.37\","
            +     "\"androidSdkVersion\":30,"
            +     "\"hl\":\"en\",\"gl\":\"US\""
            +   "}"
            + "},"
            + "\"contentCheckOk\":true,"
            + "\"racyCheckOk\":true"
            + "}";
        return callApi(body, "Android", ANDROID_UA);
    }

    private static String tryIosClient(String videoId) {
        String body = "{"
            + "\"videoId\":\"" + videoId + "\","
            + "\"context\":{"
            +   "\"client\":{"
            +     "\"clientName\":\"IOS\","
            +     "\"clientVersion\":\"19.09.3\","
            +     "\"deviceModel\":\"iPhone14,3\","
            +     "\"hl\":\"en\",\"gl\":\"US\""
            +   "}"
            + "},"
            + "\"contentCheckOk\":true,"
            + "\"racyCheckOk\":true"
            + "}";
        return callApi(body, "iOS",
            "com.google.ios.youtube/19.09.3 (iPhone14,3; U; CPU iOS 17_4 like Mac OS X)");
    }

    private static String tryTvClient(String videoId) {
        String body = "{"
            + "\"videoId\":\"" + videoId + "\","
            + "\"context\":{"
            +   "\"client\":{"
            +     "\"clientName\":\"TVHTML5_SIMPLY_EMBEDDED_PLAYER\","
            +     "\"clientVersion\":\"2.0\","
            +     "\"hl\":\"en\",\"gl\":\"US\""
            +   "},"
            +   "\"thirdParty\":{\"embedUrl\":\"https://www.youtube.com\"}"
            + "},"
            + "\"contentCheckOk\":true,"
            + "\"racyCheckOk\":true"
            + "}";
        return callApi(body, "TV",
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version");
    }

    /* ======== Innertube API call ======== */

    private static String callApi(String jsonBody, String clientName, String userAgent) {
        try {
            URL apiUrl = new URL(INNERTUBE_API);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("X-YouTube-Client-Name", "3");
            conn.setRequestProperty("X-YouTube-Client-Version", "19.09.37");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                LOG.debug("[RadioMod] YouTube {} client: HTTP {}", clientName, code);
                conn.disconnect();
                return null;
            }

            // Read response
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            return parsePlayerResponse(sb.toString(), clientName);

        } catch (Exception e) {
            LOG.debug("[RadioMod] YouTube {} client error: {}", clientName, e.getMessage());
            return null;
        }
    }

    /* ======== Web scrape fallback ======== */

    private static String tryWebScrape(String videoId) {
        try {
            String pageUrl = "https://www.youtube.com/watch?v=" + videoId;
            HttpURLConnection conn = (HttpURLConnection) new URL(pageUrl).openConnection();
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.connect();

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            String html = sb.toString();

            // Find ytInitialPlayerResponse in the HTML
            Matcher m = Pattern.compile(
                "var ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});\\s*(?:var|<)"
            ).matcher(html);

            if (!m.find()) {
                // Try alternative pattern
                m = Pattern.compile(
                    "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});\\s*(?:var|<)"
                ).matcher(html);
                if (!m.find()) {
                    LOG.debug("[RadioMod] Could not find ytInitialPlayerResponse in page");
                    return null;
                }
            }

            return parsePlayerResponse(m.group(1), "WebScrape");

        } catch (Exception e) {
            LOG.debug("[RadioMod] YouTube web scrape failed: {}", e.getMessage());
            return null;
        }
    }

    /* ======== JSON response parser ======== */

    private static String parsePlayerResponse(String json, String source) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();

            // Check playability
            if (root.has("playabilityStatus")) {
                JsonObject ps = root.getAsJsonObject("playabilityStatus");
                String status = ps.has("status") ? ps.get("status").getAsString() : "UNKNOWN";
                if (!"OK".equals(status)) {
                    String reason = ps.has("reason") ? ps.get("reason").getAsString() : status;
                    LOG.warn("[RadioMod] YouTube {}: video not playable — {}", source, reason);
                    return null;
                }
            }

            // Get streaming data
            if (!root.has("streamingData")) {
                LOG.debug("[RadioMod] YouTube {}: no streamingData", source);
                return null;
            }

            JsonObject sd = root.getAsJsonObject("streamingData");
            JsonArray formats = null;

            if (sd.has("adaptiveFormats")) {
                formats = sd.getAsJsonArray("adaptiveFormats");
            }
            if ((formats == null || formats.size() == 0) && sd.has("formats")) {
                formats = sd.getAsJsonArray("formats");
            }
            if (formats == null || formats.size() == 0) {
                LOG.debug("[RadioMod] YouTube {}: no formats found", source);
                return null;
            }

            // Find best audio-only stream
            String bestUrl = null;
            int bestBitrate = 0;
            String bestMime = "";

            for (JsonElement elem : formats) {
                JsonObject fmt = elem.getAsJsonObject();
                String mime = fmt.has("mimeType") ? fmt.get("mimeType").getAsString() : "";

                // Only audio streams
                if (!mime.startsWith("audio/")) continue;

                // Must have a direct URL (no signatureCipher)
                if (!fmt.has("url")) continue;

                int bitrate = fmt.has("bitrate") ? fmt.get("bitrate").getAsInt() : 0;

                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate;
                    bestUrl = fmt.get("url").getAsString();
                    bestMime = mime;
                }
            }

            if (bestUrl != null) {
                LOG.info("[RadioMod] YouTube {} OK: {}kbps ({})", source,
                    bestBitrate / 1000, bestMime);
                return bestUrl;
            }

            // If no direct URL found, all formats may need signature decryption
            LOG.debug("[RadioMod] YouTube {}: all audio formats need signature cipher", source);
            return null;

        } catch (Exception e) {
            LOG.debug("[RadioMod] YouTube {} JSON parse error: {}", source, e.getMessage());
            return null;
        }
    }
}
