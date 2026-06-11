package com.wenz.radiomod.client.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * Resolves various URL types into direct audio stream URLs.
 *
 * Supported:
 * - Direct audio URLs (MP3/OGG/AAC streams, internet radio) — pass through
 * - YouTube — via yt-dlp (must be installed, needs browser cookies)
 * - Other sites — via yt-dlp generic extractor, then HTML audio extraction fallback
 * - .m3u / .pls playlists — parsed to extract first stream URL
 */
public class StreamResolver {
    private static final Logger LOG = LogManager.getLogger("RadioMod");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    /**
     * Main entry: takes any URL, returns a direct audio stream URL.
     * Returns null if extraction fails.
     */
    public static String resolve(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        url = url.trim();

        LOG.info("[RadioMod] Resolving: {}", url);

        // Step 1: Check if it's a direct audio link (by extension)
        String lower = url.toLowerCase();
        if (isDirectAudioExtension(lower)) {
            LOG.info("[RadioMod] Direct audio URL detected");
            return url;
        }

        // Step 2: For YouTube, try yt-dlp (best method)
        if (isYouTube(url)) {
            LOG.info("[RadioMod] YouTube detected, trying yt-dlp...");
            String result = tryYtDlp(url);
            if (result != null) return result;
            LOG.warn("[RadioMod] yt-dlp failed for YouTube. Install yt-dlp and log into YouTube in Chrome.");
            LOG.warn("[RadioMod] Download: https://github.com/yt-dlp/yt-dlp/releases");
            return null;
        }

        // Step 3: Probe the URL — check Content-Type
        ProbeResult probe = probeUrl(url);
        if (probe == null) {
            // Connection failed, try yt-dlp as last resort
            String yt = tryYtDlp(url);
            return yt;
        }

        // If the server returned audio content → use directly
        if (probe.isAudio) {
            LOG.info("[RadioMod] Server returned audio content-type: {}", probe.contentType);
            return url;
        }

        // If it's a playlist, parse it
        if (probe.isPlaylist) {
            LOG.info("[RadioMod] Playlist detected ({}), parsing...", probe.contentType);
            String stream = parsePlaylist(probe.body, lower);
            if (stream != null) return stream;
        }

        // Step 4: It's probably HTML — try yt-dlp for generic extraction
        LOG.info("[RadioMod] HTML page detected, trying yt-dlp...");
        String ytResult = tryYtDlp(url);
        if (ytResult != null) return ytResult;

        // Step 5: Try to extract audio URL from HTML
        if (probe.body != null) {
            LOG.info("[RadioMod] Trying HTML audio extraction...");
            String htmlResult = extractFromHtml(probe.body, url);
            if (htmlResult != null) return htmlResult;
        }

        LOG.error("[RadioMod] Could not extract audio from this URL.");
        LOG.error("[RadioMod] Tip: use a direct MP3/stream link, or install yt-dlp.");
        return null;
    }

    /* ======== URL classification ======== */

    private static boolean isDirectAudioExtension(String url) {
        // Remove query params for extension check
        String path = url.split("\\?")[0].split("#")[0];
        return path.endsWith(".mp3") || path.endsWith(".ogg") || path.endsWith(".wav")
            || path.endsWith(".aac") || path.endsWith(".flac") || path.endsWith(".opus")
            || path.endsWith(".m4a") || path.endsWith(".wma");
    }

    private static boolean isYouTube(String url) {
        return url.contains("youtube.com") || url.contains("youtu.be")
            || url.contains("music.youtube.com");
    }

    /* ======== HTTP probe ======== */

    private static class ProbeResult {
        String contentType;
        boolean isAudio;
        boolean isPlaylist;
        String body; // first ~64KB if text
    }

    private static ProbeResult probeUrl(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8");
            conn.setRequestProperty("Icy-MetaData", "0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                LOG.warn("[RadioMod] HTTP {} for {}", code, url);
                conn.disconnect();
                return null;
            }

            ProbeResult r = new ProbeResult();
            r.contentType = conn.getContentType() != null ? conn.getContentType().toLowerCase() : "";

            r.isAudio = r.contentType.startsWith("audio/")
                     || r.contentType.contains("mpeg")
                     || r.contentType.contains("ogg")
                     || r.contentType.contains("opus")
                     || r.contentType.contains("aac")
                     || r.contentType.contains("octet-stream"); // many streams use this

            r.isPlaylist = r.contentType.contains("mpegurl")   // .m3u
                        || r.contentType.contains("x-scpls")   // .pls
                        || r.contentType.contains("x-mpegurl");

            // If audio or binary, don't read body
            if (r.isAudio && !r.isPlaylist) {
                conn.disconnect();
                return r;
            }

            // Read body for HTML/playlist parsing
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[65536];
            int total = 0;
            int read;
            while (total < buf.length && (read = in.read(buf, total, buf.length - total)) != -1) {
                total += read;
            }
            in.close();
            conn.disconnect();

            r.body = new String(buf, 0, total, "UTF-8");

            // Detect octet-stream that's actually audio (check for MP3 frame sync)
            if (r.contentType.contains("octet-stream") && total >= 3) {
                if ((buf[0] & 0xFF) == 0xFF && (buf[1] & 0xE0) == 0xE0) {
                    r.isAudio = true; // MP3 frame sync word
                    return r;
                }
                if (buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') {
                    r.isAudio = true; // ID3 tag → MP3
                    return r;
                }
            }

            // Override: if body looks like HTML, it's not audio
            if (r.body.trim().startsWith("<") || r.body.trim().startsWith("<!")) {
                r.isAudio = false;
            }

            // Detect playlist by content even if content-type is wrong
            String trimmed = r.body.trim();
            if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("[playlist]")) {
                r.isPlaylist = true;
            }

            return r;

        } catch (Exception e) {
            LOG.warn("[RadioMod] Probe failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /* ======== Playlist parsing ======== */

    private static String parsePlaylist(String body, String urlLower) {
        if (body == null) return null;

        // M3U format
        if (body.trim().startsWith("#EXTM3U") || urlLower.endsWith(".m3u") || urlLower.endsWith(".m3u8")) {
            for (String line : body.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.startsWith("http")) {
                    LOG.info("[RadioMod] M3U stream: {}", line);
                    return line;
                }
            }
        }

        // PLS format
        if (body.trim().startsWith("[playlist]") || urlLower.endsWith(".pls")) {
            Matcher m = Pattern.compile("File\\d+=(.+)", Pattern.CASE_INSENSITIVE).matcher(body);
            if (m.find()) {
                String stream = m.group(1).trim();
                LOG.info("[RadioMod] PLS stream: {}", stream);
                return stream;
            }
        }

        return null;
    }

    /* ======== HTML audio extraction ======== */

    private static String extractFromHtml(String html, String pageUrl) {
        // 1. <audio src="..."> or <source src="...">
        List<Pattern> patterns = new ArrayList<Pattern>();
        patterns.add(Pattern.compile("<audio[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile("<source[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE));

        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                String src = m.group(1);
                if (isAudioUrl(src)) {
                    LOG.info("[RadioMod] Found audio in HTML tag: {}", src);
                    return makeAbsolute(src, pageUrl);
                }
            }
        }

        // 2. Look for direct MP3/audio URLs anywhere in the page
        Matcher urlMatcher = Pattern.compile(
            "(https?://[^\"'\\s<>]+\\.(?:mp3|ogg|wav|aac|m4a|opus)(?:[?][^\"'\\s<>]*)?)",
            Pattern.CASE_INSENSITIVE
        ).matcher(html);
        if (urlMatcher.find()) {
            String found = urlMatcher.group(1);
            LOG.info("[RadioMod] Found audio URL in HTML: {}", found);
            return found;
        }

        // 3. Look for CDN URLs that look like audio (common patterns)
        Matcher cdnMatcher = Pattern.compile(
            "(https?://(?:cdn|media|audio|stream|music)[^\"'\\s<>]+)",
            Pattern.CASE_INSENSITIVE
        ).matcher(html);
        while (cdnMatcher.find()) {
            String candidate = cdnMatcher.group(1);
            if (candidate.contains(".mp3") || candidate.contains("/audio/")
                || candidate.contains("/stream/") || candidate.contains("/music/")) {
                LOG.info("[RadioMod] Found CDN audio URL: {}", candidate);
                return candidate;
            }
        }

        // 4. Look for data-url or data-src attributes with audio
        Matcher dataMatcher = Pattern.compile(
            "data-(?:url|src|file|mp3|audio)\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html);
        while (dataMatcher.find()) {
            String src = dataMatcher.group(1);
            if (src.startsWith("http")) {
                LOG.info("[RadioMod] Found data attribute audio: {}", src);
                return src;
            }
        }

        return null;
    }

    private static boolean isAudioUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".mp3") || lower.contains(".ogg") || lower.contains(".wav")
            || lower.contains(".aac") || lower.contains(".m4a") || lower.contains(".opus")
            || lower.contains("audio") || lower.contains("stream");
    }

    private static String makeAbsolute(String src, String pageUrl) {
        if (src.startsWith("http")) return src;
        try {
            return new URL(new URL(pageUrl), src).toString();
        } catch (MalformedURLException e) {
            return src;
        }
    }

    /* ======== yt-dlp ======== */

    private static String tryYtDlp(String url) {
        // Try with browser cookies first (needed for YouTube)
        String[] browsers = {"chrome", "firefox", "edge", "opera", "brave"};

        for (String browser : browsers) {
            String result = runYtDlp(url, browser);
            if (result != null) return result;
        }

        // Try without cookies
        String result = runYtDlp(url, null);
        return result;
    }

    private static String runYtDlp(String url, String browserCookies) {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add("yt-dlp");
            cmd.add("--no-warnings");
            cmd.add("-f");
            cmd.add("bestaudio/best");
            cmd.add("--get-url");
            cmd.add("--no-playlist");
            cmd.add("--geo-bypass");

            if (browserCookies != null) {
                cmd.add("--cookies-from-browser");
                cmd.add(browserCookies);
            }

            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            // Read stdout
            BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String resultLine = stdout.readLine();
            stdout.close();

            // Read stderr for diagnostics
            BufferedReader stderr = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            StringBuilder errBuf = new StringBuilder();
            String errLine;
            while ((errLine = stderr.readLine()) != null) errBuf.append(errLine).append("\n");
            stderr.close();

            int exit = proc.waitFor();

            if (exit == 0 && resultLine != null && resultLine.startsWith("http")) {
                LOG.info("[RadioMod] yt-dlp resolved OK (browser={})", browserCookies);
                return resultLine;
            }

            if (errBuf.length() > 0) {
                String err = errBuf.toString().trim();
                if (err.contains("not recognized") || err.contains("No such file")) {
                    // yt-dlp not installed — only log once
                    if (browserCookies == null) {
                        LOG.warn("[RadioMod] yt-dlp is not installed.");
                    }
                    return null; // Don't try more browsers
                }
                if (err.contains("cookies") && browserCookies != null) {
                    // Browser not available, try next
                    return null;
                }
                if (browserCookies == null) {
                    LOG.debug("[RadioMod] yt-dlp error: {}", err.substring(0, Math.min(200, err.length())));
                }
            }

        } catch (IOException e) {
            if (browserCookies == null) {
                LOG.debug("[RadioMod] yt-dlp not found: {}", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warn("[RadioMod] yt-dlp error: {}", e.getMessage());
        }
        return null;
    }
}
