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
 * Flow: auth → init → convert (with redirects) → get download URL
 * Returns a direct audio/mpeg URL that JLayer can play. No ffmpeg needed.
 */
public class Mp3JuiceConverter {
    private static final Logger LOG = LogManager.getLogger("RadioMod");

    private static final String AUTH_URL = "https://theta.thetacloud.org/api/v1/auth";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final String REFERER = "https://mp3juice.sc/";
    private static final String ORIGIN = "https://mp3juice.sc";

    /**
     * Convert a YouTube video ID to a direct MP3 download URL.
     * Blocking — call from a background thread.
     * @return direct MP3 URL or null on failure
     */
    public static String convert(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) return null;

        try {
            // Step 1: Auth
            LOG.info("[RadioMod] Mp3Juice: authenticating...");
            JsonObject authResp = getJson(AUTH_URL + "?_=" + ts());
            if (authResp == null || authResp.has("error") && authResp.get("error").getAsInt() > 0) {
                LOG.warn("[RadioMod] Mp3Juice: auth failed");
                return null;
            }
            String key = authResp.get("key").getAsString();

            // Step 2: Init (with Bearer token)
            LOG.info("[RadioMod] Mp3Juice: initializing...");
            JsonObject initResp = getJsonAuth(key);
            if (initResp == null) {
                LOG.warn("[RadioMod] Mp3Juice: init failed");
                return null;
            }
            if (initResp.has("error") && initResp.get("error").getAsInt() > 0) {
                LOG.warn("[RadioMod] Mp3Juice: init error {}", initResp.get("error"));
                return null;
            }
            String convertUrl = initResp.get("convertURL").getAsString();

            // Step 3: Convert (may have redirects)
            LOG.info("[RadioMod] Mp3Juice: converting {}...", videoId);
            String downloadUrl = doConvert(convertUrl, videoId, 0);
            if (downloadUrl == null) {
                LOG.warn("[RadioMod] Mp3Juice: conversion failed for {}", videoId);
                return null;
            }

            // Step 4: Build final download URL
            String finalUrl = downloadUrl + "&v=" + videoId + "&f=mp3&r=mp3juice.sc";
            LOG.info("[RadioMod] Mp3Juice: ready — {}", videoId);
            return finalUrl;

        } catch (Exception e) {
            LOG.error("[RadioMod] Mp3Juice error: {}", e.getMessage());
            return null;
        }
    }

    /** Recursive convert with redirect following (max 3 redirects). */
    private static String doConvert(String convertUrl, String videoId, int depth) {
        if (depth > 3) return null;

        try {
            String url = convertUrl + "&v=" + videoId + "&f=mp3&_=" + ts();
            JsonObject resp = getJson(url);
            if (resp == null) return null;

            // Check for errors
            if (resp.has("error") && resp.get("error").getAsInt() > 0) {
                int err = resp.get("error").getAsInt();
                LOG.warn("[RadioMod] Mp3Juice: convert error {}", err);
                return null;
            }

            // Check for redirect
            if (resp.has("redirect") && resp.get("redirect").getAsInt() == 1) {
                String redirectUrl = resp.get("redirectURL").getAsString();
                LOG.debug("[RadioMod] Mp3Juice: following redirect...");
                return doConvert(redirectUrl, videoId, depth + 1);
            }

            // Got download URL
            if (resp.has("downloadURL")) {
                String dlUrl = resp.get("downloadURL").getAsString();
                if (dlUrl != null && !dlUrl.isEmpty()) {
                    // Wait for progress if needed
                    if (resp.has("progressURL")) {
                        String progressUrl = resp.get("progressURL").getAsString();
                        if (progressUrl != null && !progressUrl.isEmpty()) {
                            waitForProgress(progressUrl);
                        }
                    }
                    return dlUrl;
                }
            }

            return null;
        } catch (Exception e) {
            LOG.debug("[RadioMod] Mp3Juice: convert step error: {}", e.getMessage());
            return null;
        }
    }

    /** Poll progress until complete (max 30 seconds). */
    private static void waitForProgress(String progressUrl) {
        for (int i = 0; i < 10; i++) {
            try {
                JsonObject resp = getJson(progressUrl + "&_=" + ts());
                if (resp != null && resp.has("progress")) {
                    int p = resp.get("progress").getAsInt();
                    if (p >= 3) return; // done
                }
                Thread.sleep(3000);
            } catch (Exception e) {
                break;
            }
        }
    }

    /* ======== HTTP helpers ======== */

    private static JsonObject getJson(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            conn.disconnect();

            return new JsonParser().parse(sb.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject getJsonAuth(String bearerToken) {
        try {
            // Find init URL from auth endpoint domain
            String url = AUTH_URL.replace("/auth", "/init") + "?_=" + ts();
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            conn.disconnect();

            return new JsonParser().parse(sb.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String ts() {
        return String.valueOf(System.currentTimeMillis());
    }
}
