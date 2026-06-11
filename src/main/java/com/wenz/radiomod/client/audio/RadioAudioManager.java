package com.wenz.radiomod.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javazoom.jl.decoder.*;

/**
 * Client-side audio engine for 1.12.2 — v1.6.0.
 *
 * Features: proxy PCM streaming, JLayer fallback, pause/resume,
 * seek via proxy restart, repeat, auto-retry, local mute, progress tracking.
 */
public class RadioAudioManager {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final Map<BlockPos, RadioSource> SOURCES = new ConcurrentHashMap<BlockPos, RadioSource>();
    private static final Map<BlockPos, Boolean> LOCAL_MUTED = new ConcurrentHashMap<BlockPos, Boolean>();
    private static final float MAX_DISTANCE = 48f;

    private static final String PROXY_BASE = "http://166.1.144.133:8300/stream?url=";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final RequestConfig HTTP_CONFIG = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(60000)
            .setConnectionRequestTimeout(5000)
            .build();

    /** Long socket timeout to allow pausing without killing the HTTP stream. */
    private static final RequestConfig PROXY_CONFIG = RequestConfig.custom()
            .setConnectTimeout(3000)
            .setSocketTimeout(600000)
            .setConnectionRequestTimeout(3000)
            .build();

    private static Boolean ffmpegAvailable = null;

    public static RepeatCallback repeatCallback = null;

    public interface RepeatCallback {
        void onTrackFinished(BlockPos pos, String url);
    }

    /* ======== public API ======== */

    public static void play(BlockPos pos, String url, float volume) {
        if (LOCAL_MUTED.containsKey(pos)) return;

        RadioSource existing = SOURCES.get(pos);
        if (existing != null && existing.running && url.equals(existing.currentUrl) && !existing.stopped) {
            existing.baseVolume = volume;
            return;
        }

        stopInternal(pos);

        final RadioSource src = new RadioSource();
        src.pos = pos;
        src.baseVolume = volume;
        src.running = true;
        src.currentUrl = url;

        SOURCES.put(pos, src);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                streamWithRetry(src, url);
            }
        }, "RadioMod-" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        t.setDaemon(true);
        t.start();
        src.thread = t;
    }

    public static void stop(BlockPos pos) {
        RadioSource src = SOURCES.get(pos);
        if (src != null) src.stopped = true;
        stopInternal(pos);
    }

    private static void stopInternal(BlockPos pos) {
        RadioSource src = SOURCES.remove(pos);
        if (src != null) {
            src.running = false;
            src.paused = false;
            if (src.ffmpegProcess != null) src.ffmpegProcess.destroyForcibly();
            if (src.thread != null) src.thread.interrupt();
        }
    }

    public static void stopAll() {
        for (BlockPos p : SOURCES.keySet().toArray(new BlockPos[0])) stop(p);
        LOCAL_MUTED.clear();
    }

    public static boolean isPlaying(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        return s != null && s.running;
    }

    public static String getCurrentUrl(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        return s != null ? s.currentUrl : null;
    }

    public static void setVolume(BlockPos pos, float vol) {
        RadioSource s = SOURCES.get(pos);
        if (s != null) s.baseVolume = Math.max(0, Math.min(1, vol));
    }

    /* ======== Pause / Resume ======== */

    public static void pause(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        if (s != null && s.running && !s.paused && s.line != null) {
            s.paused = true;
            try { s.line.stop(); } catch (Exception ignored) {}
            LOG.info("[RadioMod] Paused at {}", pos);
        }
    }

    public static void resume(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        if (s != null && s.running && s.paused && s.line != null) {
            s.paused = false;
            try { s.line.start(); } catch (Exception ignored) {}
            LOG.info("[RadioMod] Resumed at {}", pos);
        }
    }

    public static boolean isPaused(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        return s != null && s.paused;
    }

    /* ======== Seek ======== */

    public static void seek(BlockPos pos, float seconds) {
        RadioSource existing = SOURCES.get(pos);
        if (existing == null || !existing.running) return;

        String url = existing.currentUrl;
        float vol = existing.baseVolume;

        LOG.info("[RadioMod] Seeking to {}s", seconds);

        // Stop current playback (not user-stop, so don't set stopped flag)
        existing.running = false;
        if (existing.ffmpegProcess != null) existing.ffmpegProcess.destroyForcibly();
        if (existing.thread != null) existing.thread.interrupt();
        SOURCES.remove(pos);

        // Start new playback with seek offset
        final RadioSource src = new RadioSource();
        src.pos = pos;
        src.baseVolume = vol;
        src.running = true;
        src.currentUrl = url;
        src.seekOffsetSec = seconds;
        src.totalDurationSec = existing.totalDurationSec; // preserve total

        SOURCES.put(pos, src);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                streamWithRetry(src, url);
            }
        }, "RadioMod-seek-" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        t.setDaemon(true);
        t.start();
        src.thread = t;
    }

    /* ======== Progress ======== */

    /** Current elapsed time in seconds (includes seek offset). */
    public static float getElapsedSeconds(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        if (s == null) return 0;
        return s.seekOffsetSec + s.bytesPlayed / (44100f * 2 * 2);
    }

    /** Total track duration in seconds (0 if unknown). */
    public static float getTotalSeconds(BlockPos pos) {
        RadioSource s = SOURCES.get(pos);
        return s != null ? s.totalDurationSec : 0;
    }

    /* ======== Local mute ======== */

    public static void muteLocal(BlockPos pos) {
        LOCAL_MUTED.put(pos, true);
        stop(pos);
    }

    public static void unmuteLocal(BlockPos pos) {
        LOCAL_MUTED.remove(pos);
    }

    public static boolean isLocalMuted(BlockPos pos) {
        return LOCAL_MUTED.containsKey(pos);
    }

    /* ======== Tick ======== */

    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        for (Map.Entry<BlockPos, RadioSource> entry : SOURCES.entrySet()) {
            RadioSource src = entry.getValue();
            if (!src.running) { SOURCES.remove(entry.getKey()); continue; }
            if (src.line == null || src.paused) continue;

            double dist = Math.sqrt(mc.player.getDistanceSq(src.pos));
            float gain = calcGain(dist, src.baseVolume);
            applyGain(src.line, gain);
        }
    }

    /* ======== streaming with retry ======== */

    private static void streamWithRetry(RadioSource src, String url) {
        boolean success = stream(src, url);

        if (!success && src.running && !src.stopped) {
            LOG.info("[RadioMod] First attempt failed, retrying in 1s...");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            if (src.running && !src.stopped) {
                success = stream(src, url);
            }
        }

        if (success && !src.stopped && repeatCallback != null) {
            SOURCES.remove(src.pos);
            src.running = false;
            LOG.info("[RadioMod] Track finished, checking repeat...");
            repeatCallback.onTrackFinished(src.pos, url);
        }
    }

    /* ======== streaming core ======== */

    private static boolean stream(RadioSource src, String rawUrl) {
        LOG.info("[RadioMod] stream() started for: {} (seek={}s)", rawUrl, src.seekOffsetSec);
        StreamResolver.ResolveResult resolved = StreamResolver.resolve(rawUrl);

        if (resolved == null) {
            LOG.error("[RadioMod] Could not resolve: {}", rawUrl);
            src.running = false;
            return false;
        }

        String audioUrl = resolved.url;

        // === Try proxy first ===
        if (PROXY_BASE != null && !PROXY_BASE.isEmpty()) {
            try {
                String encoded = URLEncoder.encode(audioUrl, "UTF-8");
                String proxyUrl = PROXY_BASE + encoded;
                if (src.seekOffsetSec > 0) {
                    proxyUrl += "&start=" + String.format("%.1f", src.seekOffsetSec);
                }
                LOG.info("[RadioMod] Trying proxy...");
                if (streamPCM(src, proxyUrl, rawUrl)) {
                    return true;
                }
                LOG.warn("[RadioMod] Proxy failed, falling back to direct");
            } catch (Exception e) {
                LOG.warn("[RadioMod] Proxy error: {}, falling back", e.getMessage());
            }
        }

        // === Fallback: direct ===
        if (resolved.needsFfmpeg) {
            if (isFfmpegAvailable()) { streamFfmpeg(src, audioUrl, rawUrl); return true; }
            else { return streamJLayer(src, audioUrl, rawUrl); }
        } else {
            if (streamJLayer(src, audioUrl, rawUrl)) return true;
            if (isFfmpegAvailable()) { streamFfmpeg(src, audioUrl, rawUrl); return true; }
        }
        return false;
    }

    /* ======== PCM streaming (via proxy) ======== */

    private static boolean streamPCM(RadioSource src, String url, String displayUrl) {
        CloseableHttpClient httpClient = null;
        CloseableHttpResponse httpResp = null;
        try {
            httpClient = HttpClients.createDefault();
            HttpGet req = new HttpGet(url);
            req.setConfig(PROXY_CONFIG);
            req.setHeader("User-Agent", USER_AGENT);

            httpResp = httpClient.execute(req);
            int code = httpResp.getStatusLine().getStatusCode();
            if (code != 200) {
                LOG.warn("[RadioMod] Proxy HTTP {}", code);
                return false;
            }

            // Parse duration header from proxy
            org.apache.http.Header durHeader = httpResp.getFirstHeader("X-Duration-Seconds");
            if (durHeader != null) {
                try {
                    src.totalDurationSec = Float.parseFloat(durHeader.getValue());
                    LOG.info("[RadioMod] Track duration: {}s", src.totalDurationSec);
                } catch (NumberFormatException ignored) {}
            }

            if (!src.running) return true;

            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            int bufferSize = 44100 * 2 * 2 * 2; // 2 sec buffer
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, bufferSize);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] \u266b Playing (proxy) \u2014 {}", displayUrl);

            InputStream in = new BufferedInputStream(httpResp.getEntity().getContent(), 131072);
            byte[] buf = new byte[8192];
            int read;
            int remainder = 0;
            src.bytesPlayed = 0;

            while (src.running && !Thread.interrupted() && (read = in.read(buf, remainder, buf.length - remainder)) != -1) {
                read += remainder;
                int aligned = read - (read % 4);
                if (aligned > 0) {
                    line.write(buf, 0, aligned);
                    src.bytesPlayed += aligned;
                }
                remainder = read - aligned;
                if (remainder > 0) {
                    System.arraycopy(buf, aligned, buf, 0, remainder);
                }
            }

            if (src.running && src.line != null) {
                src.line.drain();
            }

            long totalSecs = src.bytesPlayed / (44100 * 2 * 2);
            LOG.info("[RadioMod] Proxy playback finished: {}s", totalSecs);
            return src.bytesPlayed > 0;

        } catch (java.net.ConnectException e) {
            LOG.warn("[RadioMod] Proxy unreachable: {}", e.getMessage());
            return false;
        } catch (java.net.SocketTimeoutException e) {
            LOG.warn("[RadioMod] Proxy timeout: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.error("[RadioMod] Proxy stream error: {} \u2014 {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        } finally {
            cleanupLine(src);
            if (httpResp != null) try { httpResp.close(); } catch (Exception ignored) {}
            if (httpClient != null) try { httpClient.close(); } catch (Exception ignored) {}
        }
    }

    /* ======== JLayer fallback ======== */

    private static class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) { super(in); }
        @Override public void close() {}
    }

    private static boolean streamJLayer(RadioSource src, String url, String displayUrl) {
        CloseableHttpClient httpClient = null;
        CloseableHttpResponse httpResp = null;
        try {
            httpClient = HttpClients.createDefault();
            HttpGet req = new HttpGet(url);
            req.setConfig(HTTP_CONFIG);
            req.setHeader("User-Agent", USER_AGENT);
            req.setHeader("Referer", "https://mp3juice.sc/");
            req.setHeader("Origin", "https://mp3juice.sc");
            req.setHeader("Accept", "*/*");

            httpResp = httpClient.execute(req);
            int code = httpResp.getStatusLine().getStatusCode();
            if (code / 100 != 2) return false;

            org.apache.http.Header ctHeader = httpResp.getFirstHeader("Content-Type");
            String ct = ctHeader != null ? ctHeader.getValue() : "";
            long contentLength = httpResp.getEntity().getContentLength();
            if (ct.contains("text/html")) return false;
            if (!src.running) return true;

            InputStream rawIn = new BufferedInputStream(httpResp.getEntity().getContent(), 524288);
            rawIn = skipID3v2(rawIn);
            rawIn = skipToSync(rawIn);

            NonClosingInputStream safeIn = new NonClosingInputStream(rawIn);
            Bitstream bitstream = new Bitstream(safeIn);
            Decoder decoder = new Decoder();

            Header hdr = bitstream.readFrame();
            if (hdr == null) return false;

            int sampleRate = hdr.frequency();
            int channels = (hdr.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
            int bitrate = hdr.bitrate();

            if (contentLength > 0 && bitrate > 0) {
                src.totalDurationSec = (float)(contentLength * 8.0 / bitrate);
            }

            SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
            bitstream.closeFrame();

            AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, sampleRate * channels * 2 * 2);
            line.start();
            src.line = line;
            src.bytesPlayed = 0;

            writeFrame(line, sb);

            int frameCount = 1;
            int resyncCount = 0;

            while (src.running && !Thread.interrupted()) {
                try {
                    hdr = bitstream.readFrame();
                    if (hdr == null) {
                        if (resyncCount < 5) {
                            rawIn = skipToSync(rawIn);
                            safeIn = new NonClosingInputStream(rawIn);
                            bitstream = new Bitstream(safeIn);
                            decoder = new Decoder();
                            resyncCount++;
                            hdr = bitstream.readFrame();
                            if (hdr == null) break;
                        } else break;
                    }
                    sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                    int written = writeFrame(line, sb);
                    src.bytesPlayed += written;
                    bitstream.closeFrame();
                    frameCount++;
                } catch (BitstreamException e) {
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}
                    if (resyncCount < 5) {
                        rawIn = skipToSync(rawIn);
                        safeIn = new NonClosingInputStream(rawIn);
                        bitstream = new Bitstream(safeIn);
                        decoder = new Decoder();
                        resyncCount++;
                    }
                } catch (DecoderException e) {
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}
                }
            }

            if (src.running && src.line != null) {
                try { src.line.drain(); } catch (Exception ignored) {}
            }
            return true;

        } catch (Exception e) {
            LOG.error("[RadioMod] JLayer error: {}", e.getMessage());
            return false;
        } finally {
            cleanupLine(src);
            if (httpResp != null) try { httpResp.close(); } catch (Exception ignored) {}
            if (httpClient != null) try { httpClient.close(); } catch (Exception ignored) {}
        }
    }

    /* ======== ID3v2 skip ======== */

    private static InputStream skipID3v2(InputStream in) throws IOException {
        if (!in.markSupported()) in = new BufferedInputStream(in, 524288);
        in.mark(10);
        byte[] header = new byte[10];
        int read = 0;
        while (read < 10) {
            int r = in.read(header, read, 10 - read);
            if (r < 0) { in.reset(); return in; }
            read += r;
        }
        if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            int size = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14) |
                       ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
            if ((header[5] & 0x10) != 0) size += 10;
            long remaining = size;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) { if (in.read() < 0) break; remaining--; }
                else remaining -= skipped;
            }
        } else { in.reset(); }
        return in;
    }

    private static InputStream skipToSync(InputStream in) throws IOException {
        if (!in.markSupported()) in = new BufferedInputStream(in, 524288);
        in.mark(65538);
        int prev = -1, skipped = 0;
        while (skipped < 65536) {
            int b = in.read();
            if (b < 0) { in.reset(); return in; }
            if (prev == 0xFF && (b & 0xE0) == 0xE0) {
                in.reset();
                long toSkip = skipped - 1;
                while (toSkip > 0) {
                    long s = in.skip(toSkip);
                    if (s <= 0) { in.read(); toSkip--; } else toSkip -= s;
                }
                return in;
            }
            prev = b;
            skipped++;
        }
        in.reset();
        return in;
    }

    /* ======== FFmpeg pipe ======== */

    private static void streamFfmpeg(RadioSource src, String url, String displayUrl) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-reconnect", "1", "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5", "-i", url, "-vn",
                "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
                "-loglevel", "error", "pipe:1"
            );
            proc = pb.start();
            src.ffmpegProcess = proc;
            final Process fProc = proc;
            Thread errThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader err = new BufferedReader(new InputStreamReader(fProc.getErrorStream()));
                        String line; while ((line = err.readLine()) != null) LOG.warn("[RadioMod] ffmpeg: {}", line);
                    } catch (Exception ignored) {}
                }
            }, "RadioMod-ffmpeg-err");
            errThread.setDaemon(true);
            errThread.start();

            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, 44100 * 2 * 2 * 2);
            line.start();
            src.line = line;

            InputStream in = new BufferedInputStream(proc.getInputStream(), 16384);
            byte[] buf = new byte[8192];
            int read;
            while (src.running && (read = in.read(buf)) != -1) {
                line.write(buf, 0, read);
                src.bytesPlayed += read;
            }
            in.close();
        } catch (Exception e) {
            LOG.error("[RadioMod] ffmpeg error: {}", e.getMessage());
        } finally {
            if (proc != null) proc.destroyForcibly();
            src.ffmpegProcess = null;
            cleanupLine(src);
        }
    }

    private static boolean isFfmpegAvailable() {
        if (ffmpegAvailable != null) return ffmpegAvailable;
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            byte[] buf = new byte[1024];
            InputStream is = p.getInputStream();
            while (is.read(buf) != -1) {}
            ffmpegAvailable = (p.waitFor() == 0);
        } catch (Exception e) { ffmpegAvailable = false; }
        return ffmpegAvailable;
    }

    /* ======== helpers ======== */

    private static int writeFrame(SourceDataLine line, SampleBuffer sb) {
        short[] samples = sb.getBuffer();
        int len = sb.getBufferLength();
        byte[] bytes = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            bytes[i * 2] = (byte)(samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte)((samples[i] >> 8) & 0xFF);
        }
        line.write(bytes, 0, bytes.length);
        return bytes.length;
    }

    private static void cleanupLine(RadioSource src) {
        if (src.line != null) {
            try { src.line.stop(); } catch (Exception ignored) {}
            try { src.line.close(); } catch (Exception ignored) {}
            src.line = null;
        }
        src.running = false;
    }

    private static float calcGain(double dist, float base) {
        if (dist >= MAX_DISTANCE) return 0f;
        if (dist <= 1.0) return base;
        return (float)(base * (1.0 - dist / MAX_DISTANCE));
    }

    private static void applyGain(SourceDataLine line, float linear) {
        try {
            FloatControl vol = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (linear <= 0.001f) ? vol.getMinimum() : (float)(20.0 * Math.log10(linear));
            vol.setValue(Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), dB)));
        } catch (IllegalArgumentException ignored) {}
    }

    /* ======== inner class ======== */

    private static class RadioSource {
        BlockPos pos;
        volatile float baseVolume;
        volatile boolean running;
        volatile boolean stopped;
        volatile boolean paused;
        volatile String currentUrl;
        volatile long bytesPlayed;
        volatile float seekOffsetSec;
        volatile float totalDurationSec;
        Thread thread;
        SourceDataLine line;
        Process ffmpegProcess;
    }
}
