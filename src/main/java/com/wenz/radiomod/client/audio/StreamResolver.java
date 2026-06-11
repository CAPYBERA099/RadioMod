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
 * Resolution chain:
 * 1. Direct audio URLs (.mp3, .ogg, etc.) — pass through
 * 2. YouTube — built-in extractor (no external tools), then yt-dlp fallback
 * 3. HTTP probe — check Content-Type, detect audio/playlist/HTML
 * 4. yt-dlp — generic fallback for unknown sites
 * 5. HTML parsing — extract audio URLs from page source
 *
 * Returned URL may be MP3, AAC/M4A, Opus/WebM, or other format.
 * RadioAudioManager handles format detection and playback.
 */
public class StreamResolver {
    private static final Logger LOG = LogManager.getLogger("RadioMod");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    /** Result with URL and metadata about the resolved stream. */
    public static class ResolveResult {
        public String url;
        public boolean needsFfmpeg; // true if the audio is non-MP3 (AAC, Opus, etc.)

        public ResolveResult(String url, boolean needsFfmpeg) {
            this.url = url;
            this.needsFfmpeg = needsFfmpeg;
        }
    }

    /**
     * Main entry: takes any URL, returns a ResolveResult with direct audio URL.
     * Returns null if extraction fails.
     */
    public static ResolveResult resolve(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        url = url.trim();

        LOG.info("[RadioMod] Resolving: {}", url);

        // Step 1: Direct audio file by extension
        String lower = url.toLowerCase();
        if (isDirectAudioExtension(lower)) {
            LOG.info("[RadioMod] Direct audio URL detected");
            boolean nonMp3 = !lower.split("\\?")[0].endsWith(".mp3");
            return new ResolveResult(url, nonMp3);
        }

        // Step 2: YouTube — built-in extractor first
        if (isYouTube(url)) {
            LOG.info("[RadioMod] YouTube detected, trying built-in extractor...");
            String yt = YouTubeExtractor.extract(url);
            if (yt != null) {
                return new ResolveResult(yt, true); // YouTube audio is AAC/Opus → needs ffmpeg
            }

            // Fallback to yt-dlp
            LOG.info("[RadioMod] Built-in failed, trying yt-dlp...");
            String ytdlp = tryYtDlp(url);
            if (ytdlp != null) {
                return new ResolveResult(ytdlp, true);
            }

            LOG.error("[RadioMod] YouTube extraction failed.");
            LOG.error("[RadioMod] Try installing yt-dlp: winget install yt-dlp");
            return null;
        }

        // Step 3: Probe URL for content type
        ProbeResult probe = probeUrl(url);
        if (probe == null) {
            // Connection failed — try yt-dlp
            String yt = tryYtDlp(url);
            if (yt != null) return new ResolveResult(yt, true);
            return null;
        }

        // Direct audio response
        if (probe.isAudio) {
            LOG.info("[RadioMod] Audio content-type: {}", probe.contentType);
            boolean nonMp3 = !probe.contentType.contains("mpeg")
                          || probe.contentType.contains("mp4")
                          || probe.contentType.contains("webm");
            return new ResolveResult(url, nonMp3);
        }

        // Playlist
        if (probe.isPlaylist) {
            LOG.info("[RadioMod] Playlist detected: {}", probe.contentType);
            String stream = parsePlaylist(probe.body, lower);
            if (stream != null) return new ResolveResult(stream, false);
        }

        // Step 4: HTML page — try yt-dlp first (supports 1000+ sites)
        LOG.info("[RadioMod] HTML page, trying yt-dlp...");
        String ytResult = tryYtDlp(url);
        if (ytResult != null) return new ResolveResult(ytResult, true);

        // Step 5: HTML audio extraction fallback
        if (probe.body != null) {
            LOG.info("[RadioMod] Trying HTML audio extraction...");
            String htmlResult = extractFromHtml(probe.body, url);
            if (htmlResult != null) {
                boolean nonMp3 = !htmlResult.toLowerCase().contains(".mp3");
                return new ResolveResult(htmlResult, nonMp3);
            }
        }

        LOG.error("[RadioMod] Could not extract audio from: {}", url);
        LOG.error("[RadioMod] Supported: direct MP3/OGG links, internet radio, YouTube, M3U/PLS playlists");
        LOG.error("[RadioMod] For more sites install yt-dlp: winget install yt-dlp");
        return null;
    }

    /* ======== URL helpers ======== */

    private static boolean isDirectAudioExtension(String url) {
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

    static class ProbeResult {
        String contentType;
        boolean isAudio;
        boolean isPlaylist;
        String body;
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
                     || r.contentType.contains("octet-stream");

            r.isPlaylist = r.contentType.contains("mpegurl")
                        || r.contentType.contains("x-scpls")
                        || r.contentType.contains("x-mpegurl");

            if (r.isAudio && !r.isPlaylist) {
                conn.disconnect();
                return r;
            }

            // Read body
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

            // Detect binary audio in octet-stream
            if (r.contentType.contains("octet-stream") && total >= 3) {
                if ((buf[0] & 0xFF) == 0xFF && (buf[1] & 0xE0) == 0xE0) {
                    r.isAudio = true;
                    return r;
                }
                if (buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') {
                    r.isAudio = true;
                    return r;
                }
            }

            // HTML overrides audio detection
            if (r.body.trim().startsWith("<") || r.body.trim().startsWith("<!")) {
                r.isAudio = false;
            }

            // Detect playlist by content
            String trimmed = r.body.trim();
            if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("[playlist]")) {
                r.isPlaylist = true;
            }

            return r;

        } catch (Exception e) {
            LOG.warn("[RadioMod] Probe failed: {}", e.getMessage());
            return null;
        }
    }

    /* ======== Playlist parsing ======== */

    private static String parsePlaylist(String body, String urlLower) {
        if (body == null) return null;

        if (body.trim().startsWith("#EXTM3U") || urlLower.endsWith(".m3u") || urlLower.endsWith(".m3u8")) {
            for (String line : body.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.startsWith("http")) {
                    LOG.info("[RadioMod] M3U stream: {}", line);
                    return line;
                }
            }
        }

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

    /* ======== HTML extraction ======== */

    private static String extractFromHtml(String html, String pageUrl) {
        // <audio>/<source> tags
        List<Pattern> patterns = new ArrayList<Pattern>();
        patterns.add(Pattern.compile("<audio[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile("<source[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE));

        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                String src = m.group(1);
                if (isAudioUrl(src)) {
                    LOG.info("[RadioMod] Found audio tag: {}", src);
                    return makeAbsolute(src, pageUrl);
                }
            }
        }

        // Direct audio URLs in page
        Matcher urlMatcher = Pattern.compile(
            "(https?://[^\"'\\s<>]+\\.(?:mp3|ogg|wav|aac|m4a|opus)(?:[?][^\"'\\s<>]*)?)",
            Pattern.CASE_INSENSITIVE
        ).matcher(html);
        if (urlMatcher.find()) {
            LOG.info("[RadioMod] Found audio URL: {}", urlMatcher.group(1));
            return urlMatcher.group(1);
        }

        // CDN patterns
        Matcher cdnMatcher = Pattern.compile(
            "(https?://(?:cdn|media|audio|stream|music)[^\"'\\s<>]+)",
            Pattern.CASE_INSENSITIVE
        ).matcher(html);
        while (cdnMatcher.find()) {
            String c = cdnMatcher.group(1);
            if (c.contains(".mp3") || c.contains("/audio/") || c.contains("/stream/")) {
                LOG.info("[RadioMod] Found CDN audio: {}", c);
                return c;
            }
        }

        // data-* attributes
        Matcher dataMatcher = Pattern.compile(
            "data-(?:url|src|file|mp3|audio)\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html);
        while (dataMatcher.find()) {
            String src = dataMatcher.group(1);
            if (src.startsWith("http")) {
                LOG.info("[RadioMod] Found data attr audio: {}", src);
                return src;
            }
        }

        return null;
    }

    private static boolean isAudioUrl(String url) {
        String l = url.toLowerCase();
        return l.contains(".mp3") || l.contains(".ogg") || l.contains(".wav")
            || l.contains(".aac") || l.contains(".m4a") || l.contains(".opus")
            || l.contains("audio") || l.contains("stream");
    }

    private static String makeAbsolute(String src, String pageUrl) {
        if (src.startsWith("http")) return src;
        try { return new URL(new URL(pageUrl), src).toString(); }
        catch (MalformedURLException e) { return src; }
    }

    /* ======== yt-dlp ======== */

    private static String tryYtDlp(String url) {
        String[] browsers = {"chrome", "firefox", "edge", "opera", "brave"};
        for (String browser : browsers) {
            String r = runYtDlp(url, browser);
            if (r != null) return r;
        }
        return runYtDlp(url, null);
    }

    private static String runYtDlp(String url, String browser) {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add("yt-dlp");
            cmd.add("--no-warnings");
            cmd.add("-f"); cmd.add("bestaudio/best");
            cmd.add("--get-url");
            cmd.add("--no-playlist");
            cmd.add("--geo-bypass");
            if (browser != null) {
                cmd.add("--cookies-from-browser");
                cmd.add(browser);
            }
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            BufferedReader out = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line = out.readLine();
            out.close();

            // Drain stderr
            BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            StringBuilder errBuf = new StringBuilder();
            String errLine;
            while ((errLine = err.readLine()) != null) errBuf.append(errLine).append("\n");
            err.close();

            int exit = proc.waitFor();
            if (exit == 0 && line != null && line.startsWith("http")) {
                LOG.info("[RadioMod] yt-dlp OK (browser={})", browser);
                return line;
            }

            String errStr = errBuf.toString();
            if (errStr.contains("not recognized") || errStr.contains("No such file")) {
                if (browser == null) LOG.debug("[RadioMod] yt-dlp not installed");
                return null;
            }

        } catch (IOException e) {
            // yt-dlp not found
        } catch (Exception e) {
            LOG.debug("[RadioMod] yt-dlp error: {}", e.getMessage());
        }
        return null;
    }
}
