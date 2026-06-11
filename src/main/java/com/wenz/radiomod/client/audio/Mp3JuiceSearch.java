package com.wenz.radiomod.client.audio;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Search for music via mp3juice.sc API.
 * Returns YouTube video IDs and titles which can be played via YouTubeExtractor.
 */
public class Mp3JuiceSearch {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final String SEARCH_URL = "https://mp3juice.sc/api/v1/search";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    /** A single search result. */
    public static class SearchResult {
        public final String videoId;
        public final String title;
        public final String duration;
        public final String source; // "yt" or "sc"

        public SearchResult(String videoId, String title, String duration, String source) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.source = source;
        }

        /** Returns the YouTube URL for playback. */
        public String toYouTubeUrl() {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
    }

    /**
     * Search for music. Returns a list of results (up to ~20).
     * Runs a blocking HTTP request — call from a background thread.
     */
    public static List<SearchResult> search(String query) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        if (query == null || query.trim().isEmpty()) return results;

        try {
            // Encode query: base64(encodeURIComponent(query))
            String encoded = URLEncoder.encode(query.trim(), "UTF-8")
                    .replace("+", "%20");
            String b64 = javax.xml.bind.DatatypeConverter.printBase64Binary(
                    encoded.getBytes("UTF-8"));

            String url = SEARCH_URL + "?y=y&q=" + b64 + "&_=" + System.currentTimeMillis();

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Referer", "https://mp3juice.sc/");
            conn.setRequestProperty("Origin", "https://mp3juice.sc");
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code != 200) {
                LOG.warn("[RadioMod] Search API returned HTTP {}", code);
                conn.disconnect();
                return results;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            JsonObject root = new JsonParser().parse(sb.toString()).getAsJsonObject();

            // Parse YouTube results
            if (root.has("yt") && root.get("yt").isJsonArray()) {
                JsonArray yt = root.getAsJsonArray("yt");
                for (JsonElement elem : yt) {
                    JsonObject item = elem.getAsJsonObject();
                    String id = item.has("id") ? item.get("id").getAsString() : null;
                    String title = item.has("title") ? item.get("title").getAsString() : "Unknown";
                    String dur = item.has("duration") ? item.get("duration").getAsString() : "";
                    if (id != null) {
                        results.add(new SearchResult(id, title, dur, "yt"));
                    }
                }
            }

            // Parse SoundCloud results
            if (root.has("sc") && root.get("sc").isJsonArray()) {
                JsonArray sc = root.getAsJsonArray("sc");
                for (JsonElement elem : sc) {
                    JsonObject item = elem.getAsJsonObject();
                    String id = item.has("id") ? item.get("id").getAsString() : null;
                    String title = item.has("title") ? item.get("title").getAsString() : "Unknown";
                    String dur = item.has("duration") ? item.get("duration").getAsString() : "";
                    if (id != null) {
                        results.add(new SearchResult(id, title, dur, "sc"));
                    }
                }
            }

            LOG.info("[RadioMod] Search '{}': {} results", query, results.size());

        } catch (Exception e) {
            LOG.error("[RadioMod] Search error: {}", e.getMessage());
        }

        return results;
    }
}
