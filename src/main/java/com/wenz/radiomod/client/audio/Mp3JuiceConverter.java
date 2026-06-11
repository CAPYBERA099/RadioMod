package com.wenz.radiomod.client.audio;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;

/**
 * Converts YouTube video IDs into direct MP3 download URLs
 * using mp3juice.sc / thetacloud.org backend.
 *
 * Flow: auth → init → convert (follow redirects) → poll progress → download URL
 * Returns a direct audio/mpeg URL that JLayer can play. No ffmpeg needed.
 */
public class Mp3JuiceConverter {
    private static final Logger LOG = LogManager.getLogger("RadioMod");

    // Java's HttpURLConnection silently strips "Origin" from restricted headers.
    // This must be enabled or the thetacloud init endpoint returns 403.
    static {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private static final String AUTH_URL = "https://theta.thetacloud.org/api/v1/auth";
    private static final String INIT_URL = "https://theta.thetacloud.org/api/v1/init";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String REFERER = "https://mp3juice.sc/";
    private static final String ORIGIN = "https://mp3juice.sc";

    // Max retries with fresh init (different server each time)
    private static final int MAX_RETRIES = 2;
    // Max redirect depth
    private static final int MAX_REDIRECTS = 3;
    // Progress poll timeout (seconds)
    private static final int PROGRESS_TIMEOUT = 45;

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
            }

            String result = tryConvert(videoId);
            if (result != null) return result;

            // Don't retry if it's a permanent error
            if (lastError != null && lastError.contains("unavailable")) break;
        }

        LOG.error("[RadioMod] Mp3Juice: all attempts failed for {}", videoId);
        return null;
    }

    private static String tryConvert(String videoId) {
        try {
            // Step 1: Auth
            LOG.info("[RadioMod] Mp3Juice: authenticating...");
            String authUrl = AUTH_URL + "?_=" + ts();
            JsonObject authResp = getJson(authUrl, null);
            if (authResp == null) {
                LOG.warn("[RadioMod] Mp3Juice: auth request failed");
                lastError = "Auth failed";
                return null;
            }
            if (hasError(authResp)) {
                LOG.warn("[RadioMod] Mp3Juice: auth error: {}", authResp);
                lastError = "Auth error";
                return null;
            }
            String key = getStr(authResp, "key");
            if (key == null || key.isEmpty()) {
                LOG.warn("[RadioMod] Mp3Juice: no auth key");
                lastError = "No auth key";
                return null;
            }

            // Step 2: Init (with Bearer token) — gets a convert URL on a random server
            LOG.info("[RadioMod] Mp3Juice: initializing...");
            String initUrl = INIT_URL + "?_=" + ts();
            JsonObject initResp = getJson(initUrl, key);
            if (initResp == null) {
                LOG.warn("[RadioMod] Mp3Juice: init request failed (403?)");
                lastError = "Init failed";
                return null;
            }
            String convertUrl = getStr(initResp, "convertURL");
            if (convertUrl == null || convertUrl.isEmpty()) {
                LOG.warn("[RadioMod] Mp3Juice: no convertURL in init response");
                lastError = "No convert URL";
                return null;
            }
            LOG.info("[RadioMod] Mp3Juice: got convert server");

            // Step 3: Convert (follow redirects, poll progress)
            LOG.info("[RadioMod] Mp3Juice: converting {}...", videoId);
            String downloadUrl = doConvert(convertUrl, videoId, key, 0);
            if (downloadUrl != null) {
                LOG.info("[RadioMod] Mp3Juice: success — {}", videoId);
                return downloadUrl;
            }

            return null;

        } catch (Exception e) {
            LOG.error("[RadioMod] Mp3Juice error: {}", e.getMessage());
            lastError = "Error: " + e.getMessage();
            return null;
        }
    }

    /**
     * Perform convert request, follow redirects, poll progress, return download URL.
     */
    private static String doConvert(String convertUrl, String videoId, String key, int depth) {
        if (depth > MAX_REDIRECTS) {
            LOG.warn("[RadioMod] Mp3Juice: too many redirects");
            lastError = "Too many redirects";
            return null;
        }

        try {
            // The convertURL already has ?sig=... so we append with &
            String url = convertUrl + "&v=" + videoId + "&f=mp3&_=" + ts();
            JsonObject resp = getJson(url, key);
            if (resp == null) {
                LOG.warn("[RadioMod] Mp3Juice: convert request failed");
                lastError = "Convert request failed";
                return null;
            }

            // Check for hard error
            int error = getInt(resp, "error");
            if (error > 0) {
                LOG.warn("[RadioMod] Mp3Juice: convert error {}", error);
                lastError = "Convert error " + error;
                return null;
            }

            // Follow redirect to different server
            int redirect = getInt(resp, "redirect");
            if (redirect == 1) {
                String redirectUrl = getStr(resp, "redirectURL");
                if (redirectUrl != null && !redirectUrl.isEmpty()) {
                    LOG.info("[RadioMod] Mp3Juice: redirecting to another server...");
                    return doConvert(redirectUrl, videoId, key, depth + 1);
                }
                LOG.warn("[RadioMod] Mp3Juice: redirect=1 but no redirectURL");
                return null;
            }

            // We have a direct response — get progress and download URLs
            String progressUrl = getStr(resp, "progressURL");
            String downloadUrl = getStr(resp, "downloadURL");

            if (progressUrl == null || progressUrl.isEmpty()) {
                // No progress tracking — download URL should be ready
                if (downloadUrl != null && !downloadUrl.isEmpty()) {
                    return downloadUrl;
                }
                LOG.warn("[RadioMod] Mp3Juice: no progressURL and no downloadURL");
                lastError = "No download URL";
                return null;
            }

            // Poll progress until conversion completes
            LOG.info("[RadioMod] Mp3Juice: waiting for conversion...");
            long startTime = System.currentTimeMillis();
            long timeout = PROGRESS_TIMEOUT * 1000L;

            while (System.currentTimeMillis() - startTime < timeout) {
                try { Thread.sleep(2500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                JsonObject prog = getJson(progressUrl, null);
                if (prog == null) continue;

                int progress = getInt(prog, "progress");
                int progError = getInt(prog, "error");
                String title = getStr(prog, "title");

                if (progress >= 3) {
                    // Conversion done! Get the download URL from progress response
                    String dlFromProgress = getStr(prog, "downloadURL");
                    if (dlFromProgress != null && !dlFromProgress.isEmpty()) {
                        downloadUrl = dlFromProgress;
                    }
                    if (title != null && !title.isEmpty()) {
                        LOG.info("[RadioMod] Mp3Juice: ready — {}", title);
                    }
                    // Return download URL AS-IS (it's a signed URL, don't modify it)
                    return downloadUrl;
                }

                if (progress < 0) {
                    // Conversion failed
                    LOG.warn("[RadioMod] Mp3Juice: conversion failed, progress={} error={}",
                            progress, progError);
                    if (progError == 645) {
                        lastError = "Track unavailable on converter";
                    } else {
                        lastError = "Conversion error " + progError;
                    }
                    return null;
                }

                // Still converting...
                LOG.debug("[RadioMod] Mp3Juice: progress={}", progress);
            }

            LOG.warn("[RadioMod] Mp3Juice: conversion timed out");
            lastError = "Conversion timeout";
            return null;

        } catch (Exception e) {
            LOG.error("[RadioMod] Mp3Juice: convert error: {}", e.getMessage());
            lastError = "Error: " + e.getMessage();
            return null;
        }
    }

    /* ======== HTTP helper ======== */

    private static JsonObject getJson(String url, String bearerToken) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (bearerToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                LOG.debug("[RadioMod] Mp3Juice: HTTP {} for {}", code,
                        url.length() > 80 ? url.substring(0, 80) + "..." : url);
                return null;
            }

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            return new JsonParser().parse(sb.toString()).getAsJsonObject();

        } catch (Exception e) {
            LOG.debug("[RadioMod] Mp3Juice: request error: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /* ======== JSON helpers ======== */

    private static String getStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive()) {
                JsonPrimitive prim = el.getAsJsonPrimitive();
                if (prim.isNumber()) return prim.getAsInt();
                if (prim.isString()) {
                    try { return Integer.parseInt(prim.getAsString()); }
                    catch (NumberFormatException e) { return 0; }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    private static boolean hasError(JsonObject obj) {
        int err = getInt(obj, "err");
        if (err > 0) return true;
        err = getInt(obj, "error");
        return err > 0;
    }

    private static String ts() {
        return String.valueOf(System.currentTimeMillis());
    }
}
