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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javazoom.jl.decoder.*;

/**
 * Client-side audio engine for 1.12.2 — DEBUG BUILD.
 * Extensive logging to diagnose 0.5s audio cutoff.
 */
public class RadioAudioManager {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final Map<BlockPos, RadioSource> SOURCES = new ConcurrentHashMap<BlockPos, RadioSource>();
    private static final float MAX_DISTANCE = 48f;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final RequestConfig HTTP_CONFIG = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(60000)
            .setConnectionRequestTimeout(5000)
            .build();

    private static Boolean ffmpegAvailable = null;

    /* ======== public API ======== */

    public static void play(BlockPos pos, String url, float volume) {
        RadioSource existing = SOURCES.get(pos);

        // DEBUG: log every play() call
        if (existing != null) {
            boolean urlMatch = url.equals(existing.currentUrl);
            LOG.info("[RadioMod] play() called: pos={} running={} urlMatch={} vol={}",
                    pos, existing.running, urlMatch, volume);
            if (urlMatch) {
                LOG.info("[RadioMod] play() URL: {}", url.length() > 80 ? url.substring(0, 80) + "..." : url);
            } else {
                LOG.info("[RadioMod] play() OLD URL: {}", existing.currentUrl != null ?
                        (existing.currentUrl.length() > 80 ? existing.currentUrl.substring(0, 80) + "..." : existing.currentUrl) : "null");
                LOG.info("[RadioMod] play() NEW URL: {}", url.length() > 80 ? url.substring(0, 80) + "..." : url);
            }
        } else {
            LOG.info("[RadioMod] play() called: pos={} (no existing source) vol={}", pos, volume);
            LOG.info("[RadioMod] play() URL: {}", url.length() > 80 ? url.substring(0, 80) + "..." : url);
        }

        // If already playing the same URL, just update volume
        if (existing != null && existing.running && url.equals(existing.currentUrl)) {
            existing.baseVolume = volume;
            LOG.info("[RadioMod] play() → same URL, updating volume only");
            return;
        }

        LOG.info("[RadioMod] play() → stopping old + starting new stream");
        stop(pos);

        final RadioSource src = new RadioSource();
        src.pos = pos;
        src.baseVolume = volume;
        src.running = true;
        src.currentUrl = url;

        SOURCES.put(pos, src);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                stream(src, url);
            }
        }, "RadioMod-" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        t.setDaemon(true);
        t.start();
        src.thread = t;
    }

    public static void stop(BlockPos pos) {
        RadioSource src = SOURCES.remove(pos);
        if (src != null) {
            LOG.info("[RadioMod] stop() called for pos={} running={} url={}",
                    pos, src.running,
                    src.currentUrl != null ? (src.currentUrl.length() > 60 ? src.currentUrl.substring(0, 60) + "..." : src.currentUrl) : "null");
            // Log caller stack trace to find WHO is calling stop
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < Math.min(stack.length, 8); i++) {
                sb.append("\n    at ").append(stack[i]);
            }
            LOG.info("[RadioMod] stop() caller:{}", sb.toString());

            src.running = false;
            if (src.ffmpegProcess != null) {
                src.ffmpegProcess.destroyForcibly();
            }
            if (src.thread != null) src.thread.interrupt();
        } else {
            LOG.info("[RadioMod] stop() called for pos={} — no source found", pos);
        }
    }

    public static void stopAll() {
        LOG.info("[RadioMod] stopAll() called");
        for (BlockPos p : SOURCES.keySet().toArray(new BlockPos[0])) stop(p);
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

    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        for (Map.Entry<BlockPos, RadioSource> entry : SOURCES.entrySet()) {
            RadioSource src = entry.getValue();
            if (!src.running) { SOURCES.remove(entry.getKey()); continue; }
            if (src.line == null) continue;

            double dist = Math.sqrt(mc.player.getDistanceSq(src.pos));
            float gain = calcGain(dist, src.baseVolume);
            applyGain(src.line, gain);
        }
    }

    /* ======== streaming core ======== */

    private static void stream(RadioSource src, String rawUrl) {
        LOG.info("[RadioMod] stream() started for: {}", rawUrl);
        StreamResolver.ResolveResult resolved = StreamResolver.resolve(rawUrl);

        if (resolved == null) {
            LOG.error("[RadioMod] Could not resolve: {}", rawUrl);
            src.running = false;
            return;
        }

        LOG.info("[RadioMod] Resolved: {} (needsFfmpeg={})", resolved.url, resolved.needsFfmpeg);

        if (resolved.needsFfmpeg) {
            if (isFfmpegAvailable()) {
                streamFfmpeg(src, resolved.url, rawUrl);
            } else {
                LOG.warn("[RadioMod] ffmpeg not found, trying JLayer...");
                if (!streamJLayer(src, resolved.url, rawUrl)) {
                    LOG.error("[RadioMod] Playback failed completely");
                }
            }
        } else {
            if (!streamJLayer(src, resolved.url, rawUrl)) {
                if (isFfmpegAvailable()) {
                    LOG.info("[RadioMod] JLayer failed, trying ffmpeg...");
                    streamFfmpeg(src, resolved.url, rawUrl);
                } else {
                    LOG.error("[RadioMod] JLayer failed, ffmpeg not available");
                }
            }
        }
        LOG.info("[RadioMod] stream() ended for: {}",
                rawUrl.length() > 60 ? rawUrl.substring(0, 60) + "..." : rawUrl);
    }

    /* ======== JLayer streaming (MP3) ======== */

    private static boolean streamJLayer(RadioSource src, String url, String displayUrl) {
        CloseableHttpClient httpClient = null;
        CloseableHttpResponse httpResp = null;
        try {
            LOG.info("[RadioMod] streamJLayer: opening HTTP connection...");
            httpClient = HttpClients.createDefault();
            HttpGet req = new HttpGet(url);
            req.setConfig(HTTP_CONFIG);
            req.setHeader("User-Agent", USER_AGENT);
            req.setHeader("Referer", "https://mp3juice.sc/");
            req.setHeader("Origin", "https://mp3juice.sc");
            req.setHeader("Accept", "*/*");

            httpResp = httpClient.execute(req);
            int code = httpResp.getStatusLine().getStatusCode();
            LOG.info("[RadioMod] streamJLayer: HTTP {}", code);
            if (code / 100 != 2) {
                LOG.error("[RadioMod] HTTP {} from {}", code, url);
                return false;
            }

            org.apache.http.Header ctHeader = httpResp.getFirstHeader("Content-Type");
            String ct = ctHeader != null ? ctHeader.getValue() : "";
            long contentLength = httpResp.getEntity().getContentLength();
            LOG.info("[RadioMod] streamJLayer: Content-Type={} Content-Length={}", ct, contentLength);

            if (ct.contains("text/html")) {
                LOG.error("[RadioMod] Server returned HTML, not audio");
                return false;
            }

            // Check running before starting decode
            if (!src.running) {
                LOG.info("[RadioMod] streamJLayer: stopped before decode (src.running=false)");
                return true;
            }

            InputStream in = new BufferedInputStream(httpResp.getEntity().getContent(), 524288);
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();

            LOG.info("[RadioMod] streamJLayer: reading first frame...");
            Header hdr = bitstream.readFrame();
            if (hdr == null) {
                LOG.warn("[RadioMod] No MP3 frames found in stream");
                return false;
            }

            int sampleRate = hdr.frequency();
            int channels = (hdr.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
            int bitrate = hdr.bitrate();

            String durationStr = "?:??";
            if (contentLength > 0 && bitrate > 0) {
                int totalSec = (int) (contentLength * 8 / bitrate);
                durationStr = String.format("%d:%02d", totalSec / 60, totalSec % 60);
            }

            SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
            bitstream.closeFrame();

            AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
            int audioBufferSize = sampleRate * channels * 2 * 2; // 2 seconds
            LOG.info("[RadioMod] streamJLayer: opening audio line {}Hz {}ch buffer={}bytes",
                    sampleRate, channels, audioBufferSize);

            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, audioBufferSize);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] ▶ Playing {}Hz {}ch {}kbps [{}] — {}",
                    sampleRate, channels, bitrate / 1000, durationStr, displayUrl);

            writeFrame(line, sb);

            // Decode loop with frame counting and detailed exit logging
            int frameCount = 1; // already decoded 1 frame
            int errorCount = 0;
            String exitReason = "unknown";

            while (true) {
                // Check running flag
                if (!src.running) {
                    exitReason = "src.running=false (stopped externally)";
                    break;
                }
                // Check thread interrupt
                if (Thread.interrupted()) {
                    exitReason = "thread interrupted";
                    break;
                }

                try {
                    hdr = bitstream.readFrame();
                    if (hdr == null) {
                        exitReason = "readFrame() returned null (EOF)";
                        break;
                    }

                    sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                    writeFrame(line, sb);
                    bitstream.closeFrame();
                    frameCount++;
                    errorCount = 0;

                    // Log progress every 500 frames (~13 seconds at 48kHz)
                    if (frameCount % 500 == 0) {
                        LOG.info("[RadioMod] ... {} frames decoded, running={}", frameCount, src.running);
                    }

                } catch (BitstreamException e) {
                    errorCount++;
                    LOG.warn("[RadioMod] BitstreamException at frame {}: {}", frameCount, e.getMessage());
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}
                    if (errorCount > 50) {
                        exitReason = "too many BitstreamExceptions (" + errorCount + ")";
                        break;
                    }
                } catch (DecoderException e) {
                    errorCount++;
                    LOG.warn("[RadioMod] DecoderException at frame {}: {}", frameCount, e.getMessage());
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}
                    if (errorCount > 50) {
                        exitReason = "too many DecoderExceptions (" + errorCount + ")";
                        break;
                    }
                }
            }

            LOG.info("[RadioMod] ■ Decode loop exited: reason='{}' frames={} running={}",
                    exitReason, frameCount, src.running);

            if (src.running && src.line != null) {
                try { src.line.drain(); } catch (Exception ignored) {}
            }

            LOG.info("[RadioMod] streamJLayer returning true after {} frames", frameCount);
            return true;

        } catch (Exception e) {
            LOG.error("[RadioMod] streamJLayer EXCEPTION: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            cleanupLine(src);
            if (httpResp != null) try { httpResp.close(); } catch (Exception ignored) {}
            if (httpClient != null) try { httpClient.close(); } catch (Exception ignored) {}
        }
    }

    /* ======== FFmpeg pipe path ======== */

    private static void streamFfmpeg(RadioSource src, String url, String displayUrl) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-reconnect", "1", "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5", "-i", url, "-vn",
                "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
                "-loglevel", "error", "pipe:1"
            );
            pb.redirectErrorStream(false);
            proc = pb.start();
            src.ffmpegProcess = proc;

            final Process fProc = proc;
            Thread errThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader err = new BufferedReader(new InputStreamReader(fProc.getErrorStream()));
                        String line;
                        while ((line = err.readLine()) != null) LOG.warn("[RadioMod] ffmpeg: {}", line);
                        err.close();
                    } catch (Exception ignored) {}
                }
            }, "RadioMod-ffmpeg-err");
            errThread.setDaemon(true);
            errThread.start();

            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, 44100 * 2 * 2);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] Playing (ffmpeg) 44100Hz 2ch — {}", displayUrl);

            InputStream in = new BufferedInputStream(proc.getInputStream(), 16384);
            byte[] buf = new byte[8192];
            int read;
            while (src.running && (read = in.read(buf)) != -1) {
                if (!src.running) break;
                line.write(buf, 0, read);
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

    /* ======== FFmpeg detection ======== */

    private static boolean isFfmpegAvailable() {
        if (ffmpegAvailable != null) return ffmpegAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) {}
            is.close();
            ffmpegAvailable = (p.waitFor() == 0);
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
        return ffmpegAvailable;
    }

    /* ======== helpers ======== */

    private static void writeFrame(SourceDataLine line, SampleBuffer sb) {
        short[] samples = sb.getBuffer();
        int len = sb.getBufferLength();
        byte[] bytes = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            bytes[i * 2]     = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        line.write(bytes, 0, bytes.length);
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
        return (float) (base * (1.0 - dist / MAX_DISTANCE));
    }

    private static void applyGain(SourceDataLine line, float linear) {
        try {
            FloatControl vol = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (linear <= 0.001f) ? vol.getMinimum()
                    : (float) (20.0 * Math.log10(linear));
            dB = Math.max(dB, vol.getMinimum());
            dB = Math.min(dB, vol.getMaximum());
            vol.setValue(dB);
        } catch (IllegalArgumentException ignored) {}
    }

    /* ======== inner class ======== */

    private static class RadioSource {
        BlockPos pos;
        volatile float baseVolume;
        volatile boolean running;
        volatile String currentUrl;
        Thread thread;
        SourceDataLine line;
        Process ffmpegProcess;
    }
}
