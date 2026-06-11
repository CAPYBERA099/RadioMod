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

/**
 * Converts YouTube video IDs into direct MP3 download URLs
 * using mp3juice.sc / thetacloud.org backend.
 *
 * Flow: auth → init → convert (follow redirects) → poll progress → download URL
 * Returns a direct audio/mpeg URL that JLayer can play.
 *
 * Uses Apache HttpClient (bundled with Minecraft) instead of HttpURLConnection
 * because Java's HttpURLConnection silently strips the Origin header.
 */
public class Mp3JuiceConverter {
    private static final Logger LOG = LogManager.getLogger("RadioMod");

    private static final String AUTH_URL = "https://theta.thetacloud.org/api/v1/auth";
    private static final String INIT_URL = "https://theta.thetacloud.org/api/v1/init";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String REFERER = "https://mp3juice.sc/";
    private static final String ORIGIN = "https://mp3juice.sc";

    private static final int MAX_RETRIES = 3;
    private static final int MAX_REDIRECTS = 5;
    private static final int PROGRESS_TIMEOUT = 45;

    private static final RequestConfig REQ_CONFIG = RequestConfig.custom()
            .setConnectTimeout(15000)
            .setSocketTimeout(20000)
            .setConnectionRequestTimeout(5000)
            .build();

    /** Last error message for UI display. */
    public static volatile String lastError = null;

    /**
     * Convert a YouTube video ID to a direct MP3 download URL.
     * Blocking — call from a background thread.
     * @return direct MP3 URL or null on failure
     */
    public static String convert(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) return null;
        lastError = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                LOG.info("[RadioMod] Mp3Juice: retry #{} with new server...", attempt);
                // Delay between retries: 1s, 2s, 3s
                try { Thread.sleep(attempt * 1000L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            String result = tryConvert(videoId);
            if (result != null) return result;

            if (lastError != null && lastError.contains("unavailable")) break;
        }

        LOG.error("[RadioMod] Mp3Juice: all attempts failed for {}", videoId);
        return null;
    }

    private static String tryConvert(String videoId) {
        CloseableHttpClient client = null;
        try {
            client = HttpClients.createDefault();

            // Step 1: Auth
            LOG.info("[RadioMod] Mp3Juice: authenticating...");
            JsonObject authResp = doGet(client, AUTH_URL + "?_=" + ts(), null);
            if (authResp == null || hasError(authResp)) {
                lastError = "Auth failed";
                return null;
            }
            String key = getStr(authResp, "key");
            if (key == null || key.isEmpty()) {
                lastError = "No auth key";
                return null;
            }
            LOG.info("[RadioMod] Mp3Juice: auth OK, key={}", key.substring(0, Math.min(6, key.length())) + "...");

            // Step 2: Init — gets a convert URL on a random server
            LOG.info("[RadioMod] Mp3Juice: initializing...");
            JsonObject initResp = doGet(client, INIT_URL + "?_=" + ts(), key);
            if (initResp == null) {
                LOG.warn("[RadioMod] Mp3Juice: init request failed (doGet returned null, see above for details)");
                lastError = "Init failed";
                return null;
            }

            // Check init error field
            int initError = getInt(initResp, "error");
            if (initError > 0) {
                LOG.warn("[RadioMod] Mp3Juice: init returned error={}", initError);
                lastError = "Init error " + initError;
                return null;
            }

            String convertUrl = getStr(initResp, "convertURL");
            if (convertUrl == null || convertUrl.isEmpty()) {
                LOG.warn("[RadioMod] Mp3Juice: init response has no convertURL: {}", initResp.toString().substring(0, Math.min(200, initResp.toString().length())));
                lastError = "No convert URL";
                return null;
            }
            LOG.info("[RadioMod] Mp3Juice: got convert server");

            // Step 3: Convert (follow redirects, poll progress)
            LOG.info("[RadioMod] Mp3Juice: converting {}...", videoId);
            String downloadUrl = doConvert(client, convertUrl, videoId, key, 0);
            if (downloadUrl != null) {
                LOG.info("[RadioMod] Mp3Juice: success — {}", videoId);
                return downloadUrl;
            }
            return null;

        } catch (Exception e) {
            LOG.error("[RadioMod] Mp3Juice error: {}", e.getMessage());
            lastError = "Error: " + e.getMessage();
            return null;
        } finally {
            closeQuietly(client);
        }
    }

    private static String doConvert(CloseableHttpClient client, String convertUrl,
                                    String videoId, String key, int depth) {
        if (depth > MAX_REDIRECTS) {
            lastError = "Too many redirects";
            return null;
        }

        try {
            String url = convertUrl + "&v=" + videoId + "&f=mp3&_=" + ts();
            JsonObject resp = doGet(client, url, key);
            if (resp == null) {
                lastError = "Convert request failed";
                return null;
            }

            int error = getInt(resp, "error");
            if (error > 0) {
                LOG.warn("[RadioMod] Mp3Juice: convert error={}", error);
                lastError = "Convert error " + error;
                return null;
            }

            // Follow redirect to different server
            if (getInt(resp, "redirect") == 1) {
                String redirectUrl = getStr(resp, "redirectURL");
                if (redirectUrl != null && !redirectUrl.isEmpty()) {
                    LOG.info("[RadioMod] Mp3Juice: redirecting (depth={})...", depth);
                    return doConvert(client, redirectUrl, videoId, key, depth + 1);
                }
                lastError = "Redirect with no URL";
                return null;
            }

            // Poll progress
            String progressUrl = getStr(resp, "progressURL");
            String downloadUrl = getStr(resp, "downloadURL");

            if (progressUrl == null || progressUrl.isEmpty()) {
                return (downloadUrl != null && !downloadUrl.isEmpty()) ? downloadUrl : null;
            }

            LOG.info("[RadioMod] Mp3Juice: waiting for conversion...");
            long deadline = System.currentTimeMillis() + PROGRESS_TIMEOUT * 1000L;

            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(2500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                JsonObject prog = doGet(client, progressUrl, null);
                if (prog == null) continue;

                int progress = getInt(prog, "progress");

                if (progress >= 3) {
                    String dlFromProg = getStr(prog, "downloadURL");
                    if (dlFromProg != null && !dlFromProg.isEmpty()) downloadUrl = dlFromProg;
                    return downloadUrl; // Return signed URL as-is
                }

                if (progress < 0) {
                    int progError = getInt(prog, "error");
                    lastError = (progError == 645) ? "Track unavailable on converter"
                                                   : "Conversion error " + progError;
                    return null;
                }
            }

            lastError = "Conversion timeout";
            return null;

        } catch (Exception e) {
            lastError = "Error: " + e.getMessage();
            return null;
        }
    }

    /* ======== HTTP via Apache HttpClient (no restricted headers!) ======== */

    private static JsonObject doGet(CloseableHttpClient client, String url, String bearerToken) {
        CloseableHttpResponse response = null;
        try {
            HttpGet req = new HttpGet(url);
            req.setConfig(REQ_CONFIG);
            req.setHeader("User-Agent", USER_AGENT);
            req.setHeader("Referer", REFERER);
            req.setHeader("Origin", ORIGIN);
            req.setHeader("Accept", "application/json, text/plain, */*");
            req.setHeader("Accept-Language", "en-US,en;q=0.9");
            if (bearerToken != null) {
                req.setHeader("Authorization", "Bearer " + bearerToken);
            }

            response = client.execute(req);
            int code = response.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(response.getEntity(), "UTF-8");

            if (code != 200) {
                String shortUrl = url.length() > 80 ? url.substring(0, 80) + "..." : url;
                String shortBody = body.length() > 200 ? body.substring(0, 200) + "..." : body;
                LOG.warn("[RadioMod] Mp3Juice: HTTP {} for {} — body: {}", code, shortUrl, shortBody);
                return null;
            }

            return new JsonParser().parse(body).getAsJsonObject();

        } catch (Exception e) {
            String shortUrl = url.length() > 80 ? url.substring(0, 80) + "..." : url;
            LOG.warn("[RadioMod] Mp3Juice: request error for {}: {} ({})",
                    shortUrl, e.getMessage(), e.getClass().getSimpleName());
            return null;
        } finally {
            closeQuietly(response);
        }
    }

    /* ======== Helpers ======== */

    private static String getStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive()) {
                JsonPrimitive p = el.getAsJsonPrimitive();
                if (p.isNumber()) return p.getAsInt();
                if (p.isString()) {
                    try { return Integer.parseInt(p.getAsString()); }
                    catch (NumberFormatException e) { return 0; }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    private static boolean hasError(JsonObject obj) {
        return getInt(obj, "err") > 0 || getInt(obj, "error") > 0;
    }

    private static String ts() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }
}
