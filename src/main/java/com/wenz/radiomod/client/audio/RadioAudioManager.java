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
 * Client-side audio engine for 1.12.2 — v1.3.4-debug.
 * 
 * Key fixes:
 * - Manual ID3v2 skip before JLayer
 * - Skip to first MP3 sync word (0xFF 0xE0+)
 * - NonClosingInputStream wrapper so bitstream.close() doesn't kill HTTP stream
 * - Auto-resync on BitstreamException: create new Bitstream+Decoder, continue
 * - Extensive debug logging for diagnosis
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

        if (existing != null) {
            boolean urlMatch = url.equals(existing.currentUrl);
            LOG.info("[RadioMod] play() called: pos={} running={} urlMatch={} vol={}",
                    pos, existing.running, urlMatch, volume);
            if (urlMatch) {
                existing.baseVolume = volume;
                LOG.info("[RadioMod] play() → same URL, updating volume only");
                return;
            }
        } else {
            LOG.info("[RadioMod] play() called: pos={} (new source) vol={}", pos, volume);
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
            LOG.info("[RadioMod] stop() called for pos={} running={}", pos, src.running);
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < Math.min(stack.length, 8); i++) {
                sb.append("\n    at ").append(stack[i]);
            }
            LOG.info("[RadioMod] stop() caller:{}", sb.toString());
            src.running = false;
            if (src.ffmpegProcess != null) src.ffmpegProcess.destroyForcibly();
            if (src.thread != null) src.thread.interrupt();
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

        if (resolved.needsFfmpeg) {
            if (isFfmpegAvailable()) {
                streamFfmpeg(src, resolved.url, rawUrl);
            } else {
                if (!streamJLayer(src, resolved.url, rawUrl)) {
                    LOG.error("[RadioMod] Playback failed");
                }
            }
        } else {
            if (!streamJLayer(src, resolved.url, rawUrl)) {
                if (isFfmpegAvailable()) {
                    streamFfmpeg(src, resolved.url, rawUrl);
                }
            }
        }
        LOG.info("[RadioMod] stream() ended");
    }

    /* ======== NonClosingInputStream ======== */

    /**
     * Wrapper that prevents close() from propagating to the underlying stream.
     * Needed because JLayer's Bitstream.close() closes the source InputStream,
     * which would kill our HTTP connection during resync.
     */
    private static class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) { super(in); }
        @Override
        public void close() throws IOException {
            // Do NOT close underlying stream — we manage its lifecycle ourselves
        }
    }

    /* ======== JLayer streaming with resync ======== */

    private static boolean streamJLayer(RadioSource src, String url, String displayUrl) {
        CloseableHttpClient httpClient = null;
        CloseableHttpResponse httpResp = null;
        try {
            LOG.info("[RadioMod] streamJLayer: opening HTTP...");
            httpClient = HttpClients.createDefault();
            HttpGet req = new HttpGet(url);
            req.setConfig(HTTP_CONFIG);
            req.setHeader("User-Agent", USER_AGENT);
            req.setHeader("Referer", "https://mp3juice.sc/");
            req.setHeader("Origin", "https://mp3juice.sc");
            req.setHeader("Accept", "*/*");

            httpResp = httpClient.execute(req);
            int code = httpResp.getStatusLine().getStatusCode();
            LOG.info("[RadioMod] HTTP {}", code);
            if (code / 100 != 2) {
                LOG.error("[RadioMod] HTTP error {} from {}", code, url);
                return false;
            }

            org.apache.http.Header ctHeader = httpResp.getFirstHeader("Content-Type");
            String ct = ctHeader != null ? ctHeader.getValue() : "";
            long contentLength = httpResp.getEntity().getContentLength();
            LOG.info("[RadioMod] Content-Type={} Content-Length={}", ct, contentLength);

            if (ct.contains("text/html")) {
                LOG.error("[RadioMod] Server returned HTML, not audio");
                return false;
            }

            if (!src.running) {
                LOG.info("[RadioMod] Stopped before decode");
                return true;
            }

            // 512KB buffered stream for network reads
            InputStream rawIn = new BufferedInputStream(httpResp.getEntity().getContent(), 524288);

            // Step 1: Manually skip ID3v2 header
            rawIn = skipID3v2(rawIn);

            // Step 2: Skip to first MP3 sync word
            rawIn = skipToSync(rawIn);

            // Wrap in NonClosingInputStream so bitstream.close() won't kill the HTTP stream
            NonClosingInputStream safeIn = new NonClosingInputStream(rawIn);

            Bitstream bitstream = new Bitstream(safeIn);
            Decoder decoder = new Decoder();

            LOG.info("[RadioMod] Reading first frame...");
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
            int audioBufferSize = sampleRate * channels * 2 * 2; // 2 seconds
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, audioBufferSize);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] Playing {}Hz {}ch {}kbps [{}] — {}",
                    sampleRate, channels, bitrate / 1000, durationStr, displayUrl);

            writeFrame(line, sb);

            // Decode loop
            int frameCount = 1;
            int resyncCount = 0;
            int consecutiveErrors = 0;

            while (true) {
                if (!src.running) {
                    LOG.info("[RadioMod] Decode loop: src.running=false at frame {}", frameCount);
                    break;
                }
                if (Thread.interrupted()) {
                    LOG.info("[RadioMod] Decode loop: thread interrupted at frame {}", frameCount);
                    break;
                }

                try {
                    hdr = bitstream.readFrame();

                    if (hdr == null) {
                        // Try resync before giving up
                        if (resyncCount < 5) {
                            LOG.info("[RadioMod] readFrame=null at frame {}, resyncing (attempt {})",
                                    frameCount, resyncCount + 1);
                            // Don't call bitstream.close() — NonClosingInputStream prevents damage,
                            // but we still create a fresh Bitstream to reset JLayer state
                            rawIn = skipToSync(rawIn);
                            safeIn = new NonClosingInputStream(rawIn);
                            bitstream = new Bitstream(safeIn);
                            decoder = new Decoder();
                            resyncCount++;

                            hdr = bitstream.readFrame();
                            if (hdr == null) {
                                LOG.info("[RadioMod] Resync failed — true EOF at frame {}", frameCount);
                                break;
                            }
                            LOG.info("[RadioMod] Resync OK! Continuing from frame {}", frameCount);
                            // fall through to decode
                        } else {
                            LOG.info("[RadioMod] EOF at frame {} (max resyncs reached)", frameCount);
                            break;
                        }
                    }

                    sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                    writeFrame(line, sb);
                    bitstream.closeFrame();
                    frameCount++;
                    consecutiveErrors = 0;

                    // Log progress every 100 frames (~2.6s at 44.1kHz)
                    if (frameCount % 100 == 0) {
                        LOG.info("[RadioMod] ... {} frames decoded, running={}", frameCount, src.running);
                    }

                } catch (BitstreamException e) {
                    consecutiveErrors++;
                    LOG.warn("[RadioMod] BitstreamException at frame {}: {} (consecutive={})",
                            frameCount, e.getMessage(), consecutiveErrors);
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}

                    if (consecutiveErrors > 10) {
                        // Too many consecutive errors — try resync
                        if (resyncCount < 5) {
                            LOG.info("[RadioMod] Too many errors, resyncing (attempt {})", resyncCount + 1);
                            rawIn = skipToSync(rawIn);
                            safeIn = new NonClosingInputStream(rawIn);
                            bitstream = new Bitstream(safeIn);
                            decoder = new Decoder();
                            resyncCount++;
                            consecutiveErrors = 0;
                        } else {
                            LOG.error("[RadioMod] Max resyncs reached, stopping");
                            break;
                        }
                    }
                } catch (DecoderException e) {
                    consecutiveErrors++;
                    LOG.warn("[RadioMod] DecoderException at frame {}: {}", frameCount, e.getMessage());
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}
                    if (consecutiveErrors > 50) {
                        LOG.error("[RadioMod] Too many decoder errors, stopping");
                        break;
                    }
                }
            }

            LOG.info("[RadioMod] Decode loop finished: {} frames, {} resyncs, running={}",
                    frameCount, resyncCount, src.running);

            if (src.running && src.line != null) {
                LOG.info("[RadioMod] Draining audio line...");
                try { src.line.drain(); } catch (Exception ignored) {}
                LOG.info("[RadioMod] Drain complete");
            }

            return true;

        } catch (Exception e) {
            LOG.error("[RadioMod] streamJLayer EXCEPTION: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            LOG.info("[RadioMod] streamJLayer cleanup");
            cleanupLine(src);
            if (httpResp != null) try { httpResp.close(); } catch (Exception ignored) {}
            if (httpClient != null) try { httpClient.close(); } catch (Exception ignored) {}
        }
    }

    /* ======== ID3v2 skip ======== */

    private static InputStream skipID3v2(InputStream in) throws IOException {
        if (!in.markSupported()) {
            in = new BufferedInputStream(in, 524288);
        }
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
            if ((header[5] & 0x10) != 0) size += 10; // footer
            LOG.info("[RadioMod] Skipping ID3v2 header: {} bytes", size + 10);

            long remaining = size;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    if (in.read() < 0) break;
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
            return in;
        } else {
            in.reset();
            return in;
        }
    }

    /* ======== Sync word finder ======== */

    private static InputStream skipToSync(InputStream in) throws IOException {
        if (!in.markSupported()) {
            in = new BufferedInputStream(in, 524288);
        }

        int skipped = 0;
        int maxSkip = 65536;
        in.mark(maxSkip + 2);

        int prev = -1;
        while (skipped < maxSkip) {
            int b = in.read();
            if (b < 0) {
                in.reset();
                return in;
            }

            if (prev == 0xFF && (b & 0xE0) == 0xE0) {
                in.reset();
                long toSkip = skipped - 1;
                while (toSkip > 0) {
                    long s = in.skip(toSkip);
                    if (s <= 0) { in.read(); toSkip--; }
                    else toSkip -= s;
                }
                if (skipped > 1) {
                    LOG.info("[RadioMod] Skipped {} bytes to find MP3 sync", skipped - 1);
                }
                return in;
            }

            prev = b;
            skipped++;
        }

        in.reset();
        LOG.warn("[RadioMod] No MP3 sync found in first 64KB");
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
            line.open(fmt, 44100 * 2 * 2 * 2);
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
