package com.wenz.radiomod.client.audio;

import com.google.gson.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Search for music via mp3juice.sc API.
 * Uses Apache HttpClient (bundled with Minecraft) to avoid Java's restricted Origin header.
 */
public class Mp3JuiceSearch {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final String SEARCH_URL = "https://mp3juice.sc/api/v1/search";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final RequestConfig REQ_CONFIG = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(15000)
            .setConnectionRequestTimeout(5000)
            .build();

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
    }

    /**
     * Search for music. Returns a list of results (up to ~20).
     * Blocking — call from a background thread.
     */
    public static List<SearchResult> search(String query) {
        List<SearchResult> results = new ArrayList<SearchResult>();
        if (query == null || query.trim().isEmpty()) return results;

        CloseableHttpClient client = null;
        CloseableHttpResponse response = null;
        try {
            // Encode query: base64(encodeURIComponent(query))
            String encoded = URLEncoder.encode(query.trim(), "UTF-8")
                    .replace("+", "%20");
            String b64 = javax.xml.bind.DatatypeConverter.printBase64Binary(
                    encoded.getBytes("UTF-8"));

            String url = SEARCH_URL + "?y=y&q=" + b64 + "&_=" + System.currentTimeMillis();

            client = HttpClients.createDefault();
            HttpGet req = new HttpGet(url);
            req.setConfig(REQ_CONFIG);
            req.setHeader("User-Agent", USER_AGENT);
            req.setHeader("Referer", "https://mp3juice.sc/");
            req.setHeader("Origin", "https://mp3juice.sc");
            req.setHeader("Accept", "application/json, text/javascript, */*; q=0.01");
            req.setHeader("Accept-Language", "en-US,en;q=0.9");

            response = client.execute(req);
            int code = response.getStatusLine().getStatusCode();
            if (code != 200) {
                LOG.warn("[RadioMod] Search API returned HTTP {}", code);
                return results;
            }

            String body = EntityUtils.toString(response.getEntity(), "UTF-8");
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();

            // Parse YouTube results
            if (root.has("yt") && root.get("yt").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("yt")) {
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
                for (JsonElement elem : root.getAsJsonArray("sc")) {
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
        } finally {
            if (response != null) try { response.close(); } catch (Exception ignored) {}
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }

        return results;
    }
}
