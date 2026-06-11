package com.wenz.radiomod.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.util.regex.*;

/**
 * Resolves special URLs (YouTube, etc.) into direct audio stream URLs.
 * Direct HTTP links (radio stations, hitmos.me, etc.) pass through unchanged.
 */
public class StreamResolver {
    private static final Logger LOG = LoggerFactory.getLogger("RadioMod");

    public static String resolve(String url) {
        if (url == null || url.isBlank()) return url;

        if (isYouTube(url)) {
            LOG.info("[RadioMod] YouTube detected, extracting audio...");

            // Try yt-dlp first (most reliable)
            String ytdlp = tryYtDlp(url);
            if (ytdlp != null) return ytdlp;

            // Try built-in extractor
            String builtin = tryBuiltinYouTube(url);
            if (builtin != null) return builtin;

            LOG.warn("[RadioMod] YouTube extraction failed. Install yt-dlp for YouTube support.");
        }

        // Direct URL — pass through
        return url;
    }

    /* -------- YouTube detection -------- */

    private static boolean isYouTube(String url) {
        return url.contains("youtube.com") || url.contains("youtu.be");
    }

    private static String extractVideoId(String url) {
        Matcher m = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(1);

        m = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(1);

        return null;
    }

    /* -------- yt-dlp (external tool) -------- */

    private static String tryYtDlp(String url) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp", "--no-warnings", "-f", "bestaudio",
                    "--get-url", "--no-playlist", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            String result;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                result = r.readLine();
            }

            int exit = proc.waitFor();
            if (exit == 0 && result != null && result.startsWith("http")) {
                LOG.info("[RadioMod] yt-dlp resolved OK");
                return result;
            }
        } catch (IOException e) {
            LOG.debug("[RadioMod] yt-dlp not found: {}", e.getMessage());
        } catch (Exception e) {
            LOG.warn("[RadioMod] yt-dlp error: {}", e.getMessage());
        }
        return null;
    }

    /* -------- Built-in YouTube extractor (fallback) -------- */

    private static String tryBuiltinYouTube(String url) {
        try {
            String videoId = extractVideoId(url);
            if (videoId == null) return null;

            HttpURLConnection conn = (HttpURLConnection)
                    new URI("https://www.youtube.com/watch?v=" + videoId)
                            .toURL().openConnection();
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            String html = sb.toString();

            // Look for streamingData in ytInitialPlayerResponse
            Matcher m = Pattern.compile(
                    "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;\\s*var"
            ).matcher(html);
            if (!m.find()) return null;

            String json = m.group(1);

            // Extract audio URL — prefer itag 140 (M4A 128k) or 251 (Opus)
            for (String itag : new String[]{"140", "251", "250", "249"}) {
                Pattern p = Pattern.compile(
                        "\"itag\"\\s*:\\s*" + itag + ".*?\"url\"\\s*:\\s*\"([^\"]+)\"");
                Matcher um = p.matcher(json);
                if (um.find()) {
                    String audioUrl = um.group(1)
                            .replace("\\u0026", "&")
                            .replace("\\/", "/");
                    LOG.info("[RadioMod] Extracted itag {} URL", itag);
                    return audioUrl;
                }
            }
        } catch (Exception e) {
            LOG.warn("[RadioMod] Built-in extractor failed: {}", e.getMessage());
        }
        return null;
    }
}
