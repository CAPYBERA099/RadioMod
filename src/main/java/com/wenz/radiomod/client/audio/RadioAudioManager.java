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
 * Client-side audio engine for 1.12.2 — v1.4.0.
 *
 * Audio pipeline:
 *   1. Try proxy: URL → VDS (ffmpeg re-encode) → raw PCM stream → SourceDataLine
 *   2. Fallback:  URL → JLayer MP3 decode (with ID3v2 skip + resync)
 *
 * Proxy eliminates ALL JLayer/MP3 parsing issues by decoding server-side.
 */
public class RadioAudioManager {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final Map<BlockPos, RadioSource> SOURCES = new ConcurrentHashMap<BlockPos, RadioSource>();
    private static final float MAX_DISTANCE = 48f;

    /** VDS audio proxy base URL. Set to null/empty to disable proxy. */
    private static final String PROXY_BASE = "http://166.1.144.133:8300/stream?url=";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private static final RequestConfig HTTP_CONFIG = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(60000)
            .setConnectionRequestTimeout(5000)
            .build();

    /** Shorter timeouts for proxy — fail fast if VDS is down. */
    private static final RequestConfig PROXY_CONFIG = RequestConfig.custom()
            .setConnectTimeout(3000)
            .setSocketTimeout(30000)
            .setConnectionRequestTimeout(3000)
            .build();

    private static Boolean ffmpegAvailable = null;

    /* ======== public API ======== */

    public static void play(BlockPos pos, String url, float volume) {
        RadioSource existing = SOURCES.get(pos);
        if (existing != null && existing.running && url.equals(existing.currentUrl)) {
            existing.baseVolume = volume;
            return;
        }

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
            src.running = false;
            if (src.ffmpegProcess != null) src.ffmpegProcess.destroyForcibly();
            if (src.thread != null) src.thread.interrupt();
        }
    }

    public static void stopAll() {
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

        String audioUrl = resolved.url;
        LOG.info("[RadioMod] Resolved: {} (needsFfmpeg={})", audioUrl, resolved.needsFfmpeg);

        // === Try proxy first (PCM stream, no JLayer needed) ===
        if (PROXY_BASE != null && !PROXY_BASE.isEmpty()) {
            try {
                String encoded = URLEncoder.encode(audioUrl, "UTF-8");
                String proxyUrl = PROXY_BASE + encoded;
                LOG.info("[RadioMod] Trying proxy...");
                if (streamPCM(src, proxyUrl, rawUrl)) {
                    LOG.info("[RadioMod] Proxy playback complete");
                    return;
                }
                LOG.warn("[RadioMod] Proxy failed, falling back to direct");
            } catch (Exception e) {
                LOG.warn("[RadioMod] Proxy error: {}, falling back", e.getMessage());
            }
        }

        // === Fallback: direct streaming ===
        if (resolved.needsFfmpeg) {
            if (isFfmpegAvailable()) {
                streamFfmpeg(src, audioUrl, rawUrl);
            } else {
                streamJLayer(src, audioUrl, rawUrl);
            }
        } else {
            if (!streamJLayer(src, audioUrl, rawUrl)) {
                if (isFfmpegAvailable()) {
                    streamFfmpeg(src, audioUrl, rawUrl);
                }
            }
        }
        LOG.info("[RadioMod] stream() ended");
    }

    /* ======== PCM streaming (via proxy — no JLayer!) ======== */

    /**
     * Streams raw PCM from the VDS proxy.
     * The proxy downloads the MP3 and re-encodes via ffmpeg to s16le 44100Hz stereo.
     * We just read bytes and write them straight to the audio line.
     */
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

            if (!src.running) return true;

            // PCM format: 44100Hz, 16-bit, stereo, signed, little-endian
            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            int bufferSize = 44100 * 2 * 2 * 2; // 2 seconds of audio
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, bufferSize);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] ♫ Playing (proxy) 44100Hz stereo — {}", displayUrl);

            InputStream in = new BufferedInputStream(httpResp.getEntity().getContent(), 65536);
            byte[] buf = new byte[8192]; // 8192 is divisible by 4 (frameSize)
            int read;
            long totalBytes = 0;
            int remainder = 0; // leftover bytes from non-aligned reads

            while (src.running && !Thread.interrupted() && (read = in.read(buf, remainder, buf.length - remainder)) != -1) {
                read += remainder;
                int aligned = read - (read % 4); // align to frame boundary (4 bytes = 1 frame)
                if (aligned > 0) {
                    line.write(buf, 0, aligned);
                }
                remainder = read - aligned;
                if (remainder > 0) {
                    System.arraycopy(buf, aligned, buf, 0, remainder);
                }
                totalBytes += aligned;

                // Log progress every ~10 seconds (44100*2*2*10 = 1,764,000 bytes)
                if (totalBytes % 1764000 < 8192) {
                    int secs = (int)(totalBytes / (44100 * 2 * 2));
                    LOG.info("[RadioMod] ... {}s streamed ({} KB)", secs, totalBytes / 1024);
                }
            }

            if (src.running && src.line != null) {
                LOG.info("[RadioMod] Draining audio...");
                src.line.drain();
            }

            int totalSecs = (int)(totalBytes / (44100 * 2 * 2));
            LOG.info("[RadioMod] Proxy playback finished: {}s ({} KB)",
                    totalSecs, totalBytes / 1024);
            return true;

        } catch (java.net.ConnectException e) {
            LOG.warn("[RadioMod] Proxy unreachable: {}", e.getMessage());
            return false;
        } catch (java.net.SocketTimeoutException e) {
            LOG.warn("[RadioMod] Proxy timeout: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.error("[RadioMod] Proxy stream error: {} — {}",
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
        @Override public void close() { /* don't close underlying stream */ }
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
            if (code / 100 != 2) {
                LOG.error("[RadioMod] HTTP {} from {}", code, url);
                return false;
            }

            org.apache.http.Header ctHeader = httpResp.getFirstHeader("Content-Type");
            String ct = ctHeader != null ? ctHeader.getValue() : "";
            long contentLength = httpResp.getEntity().getContentLength();

            if (ct.contains("text/html")) {
                LOG.error("[RadioMod] Server returned HTML, not audio");
                return false;
            }

            if (!src.running) return true;

            InputStream rawIn = new BufferedInputStream(httpResp.getEntity().getContent(), 524288);
            rawIn = skipID3v2(rawIn);
            rawIn = skipToSync(rawIn);

            NonClosingInputStream safeIn = new NonClosingInputStream(rawIn);
            Bitstream bitstream = new Bitstream(safeIn);
            Decoder decoder = new Decoder();

            Header hdr = bitstream.readFrame();
            if (hdr == null) {
                LOG.warn("[RadioMod] No MP3 frames found");
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
            int audioBufferSize = sampleRate * channels * 2 * 2;
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, audioBufferSize);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] ♫ Playing (direct) {}Hz {}ch {}kbps [{}] — {}",
                    sampleRate, channels, bitrate / 1000, durationStr, displayUrl);

            writeFrame(line, sb);

            int frameCount = 1;
            int resyncCount = 0;

            while (src.running && !Thread.interrupted()) {
                try {
                    hdr = bitstream.readFrame();
                    if (hdr == null) {
                        if (resyncCount < 5) {
                            LOG.info("[RadioMod] readFrame=null at frame {}, resyncing ({})",
                                    frameCount, resyncCount + 1);
                            rawIn = skipToSync(rawIn);
                            safeIn = new NonClosingInputStream(rawIn);
                            bitstream = new Bitstream(safeIn);
                            decoder = new Decoder();
                            resyncCount++;
                            hdr = bitstream.readFrame();
                            if (hdr == null) {
                                LOG.info("[RadioMod] Resync failed — EOF at frame {}", frameCount);
                                break;
                            }
                        } else {
                            break;
                        }
                    }

                    sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                    writeFrame(line, sb);
                    bitstream.closeFrame();
                    frameCount++;

                    if (frameCount % 500 == 0) {
                        LOG.info("[RadioMod] ... {} frames decoded", frameCount);
                    }
                } catch (BitstreamException e) {
                    LOG.warn("[RadioMod] BitstreamException at frame {}: {}", frameCount, e.getMessage());
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

            LOG.info("[RadioMod] Direct playback finished: {} frames, {} resyncs", frameCount, resyncCount);
            return true;

        } catch (Exception e) {
            LOG.error("[RadioMod] JLayer error: {} — {}", e.getClass().getSimpleName(), e.getMessage());
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
            LOG.info("[RadioMod] Skipping ID3v2: {} bytes", size + 10);
            long remaining = size;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) { if (in.read() < 0) break; remaining--; }
                else remaining -= skipped;
            }
        } else {
            in.reset();
        }
        return in;
    }

    /* ======== Sync word finder ======== */

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
                if (skipped > 1) LOG.info("[RadioMod] Skipped {} bytes to sync", skipped - 1);
                return in;
            }
            prev = b;
            skipped++;
        }
        in.reset();
        return in;
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
            LOG.info("[RadioMod] ♫ Playing (ffmpeg) 44100Hz 2ch — {}", displayUrl);

            InputStream in = new BufferedInputStream(proc.getInputStream(), 16384);
            byte[] buf = new byte[8192];
            int read;
            while (src.running && (read = in.read(buf)) != -1) { line.write(buf, 0, read); }
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
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            byte[] buf = new byte[1024];
            InputStream is = p.getInputStream();
            while (is.read(buf) != -1) {}
            ffmpegAvailable = (p.waitFor() == 0);
        } catch (Exception e) { ffmpegAvailable = false; }
        return ffmpegAvailable;
    }

    /* ======== helpers ======== */

    private static void writeFrame(SourceDataLine line, SampleBuffer sb) {
        short[] samples = sb.getBuffer();
        int len = sb.getBufferLength();
        byte[] bytes = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            bytes[i * 2] = (byte)(samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte)((samples[i] >> 8) & 0xFF);
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
        volatile String currentUrl;
        Thread thread;
        SourceDataLine line;
        Process ffmpegProcess;
    }
}
