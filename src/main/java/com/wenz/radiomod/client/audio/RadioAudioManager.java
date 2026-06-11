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
 * Client-side audio engine for 1.12.2.
 * Streams MP3 via JLayer with automatic resync on decode errors.
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
                if (!streamJLayer(src, resolved.url, rawUrl)) {
                    LOG.error("[RadioMod] Playback failed. Install ffmpeg for non-MP3.");
                }
            }
        } else {
            if (!streamJLayer(src, resolved.url, rawUrl)) {
                if (isFfmpegAvailable()) {
                    streamFfmpeg(src, resolved.url, rawUrl);
                }
            }
        }
    }

    /* ======== JLayer streaming with resync ======== */

    /**
     * Streams MP3 via Apache HttpClient → JLayer.
     * Key fix: on BitstreamException, creates a NEW Bitstream + Decoder
     * to resync from the current InputStream position instead of stopping.
     * Also manually skips the ID3v2 tag before creating the first Bitstream.
     */
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

            // 512KB buffer for network reads
            InputStream rawIn = new BufferedInputStream(httpResp.getEntity().getContent(), 524288);

            // Manually skip ID3v2 header — JLayer sometimes misparses it
            rawIn = skipID3v2(rawIn);

            // Skip to first valid MP3 sync word
            rawIn = skipToSync(rawIn);

            Bitstream bitstream = new Bitstream(rawIn);
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
            int audioBufferSize = sampleRate * channels * 2 * 2; // 2 seconds
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, audioBufferSize);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] Playing {}Hz {}ch {}kbps [{}] — {}",
                    sampleRate, channels, bitrate / 1000, durationStr, displayUrl);

            writeFrame(line, sb);

            int frameCount = 1;
            int resyncCount = 0;

            while (src.running && !Thread.interrupted()) {
                try {
                    hdr = bitstream.readFrame();
                    if (hdr == null) {
                        // Before assuming EOF, try resync (up to 5 times)
                        if (resyncCount < 5) {
                            LOG.info("[RadioMod] readFrame null at frame {}, resyncing... (attempt {})",
                                    frameCount, resyncCount + 1);
                            try { bitstream.close(); } catch (Exception ignored) {}
                            rawIn = skipToSync(rawIn);
                            bitstream = new Bitstream(rawIn);
                            decoder = new Decoder();
                            resyncCount++;

                            hdr = bitstream.readFrame();
                            if (hdr == null) {
                                LOG.info("[RadioMod] Resync failed — true EOF at frame {}", frameCount);
                                break;
                            }
                            LOG.info("[RadioMod] Resync OK — continuing from frame {}", frameCount);
                            // Fall through to decode
                        } else {
                            LOG.info("[RadioMod] EOF at frame {} (max resyncs reached)", frameCount);
                            break;
                        }
                    }

                    sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                    writeFrame(line, sb);
                    bitstream.closeFrame();
                    frameCount++;

                    if (frameCount % 1000 == 0) {
                        LOG.info("[RadioMod] ... {} frames decoded", frameCount);
                    }

                } catch (BitstreamException e) {
                    LOG.info("[RadioMod] BitstreamException at frame {}: {} — resyncing",
                            frameCount, e.getMessage());
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}

                    // RESYNC: create new Bitstream + Decoder from current stream position
                    if (resyncCount < 5) {
                        try { bitstream.close(); } catch (Exception ignored) {}
                        rawIn = skipToSync(rawIn);
                        bitstream = new Bitstream(rawIn);
                        decoder = new Decoder();
                        resyncCount++;
                        LOG.info("[RadioMod] Resynced (attempt {}), continuing...", resyncCount);
                    } else {
                        LOG.error("[RadioMod] Too many resyncs, stopping");
                        break;
                    }
                } catch (DecoderException e) {
                    LOG.info("[RadioMod] DecoderException at frame {}: {}", frameCount, e.getMessage());
                    try { bitstream.closeFrame(); } catch (Exception ignored) {}
                    // Decoder errors are per-frame, just skip
                }
            }

            if (src.running && src.line != null) {
                try { src.line.drain(); } catch (Exception ignored) {}
            }

            LOG.info("[RadioMod] Playback finished — {} frames, {} resyncs", frameCount, resyncCount);
            return true;

        } catch (Exception e) {
            LOG.error("[RadioMod] Playback error: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        } finally {
            cleanupLine(src);
            if (httpResp != null) try { httpResp.close(); } catch (Exception ignored) {}
            if (httpClient != null) try { httpClient.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Skip ID3v2 header if present at the start of the stream.
     * ID3v2 header: "ID3" + 2 version bytes + 1 flag byte + 4 syncsafe size bytes.
     */
    private static InputStream skipID3v2(InputStream in) throws IOException {
        // We need to peek at the first 3 bytes
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

        // Check for "ID3" marker
        if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            // Syncsafe integer: 4 bytes, 7 bits each
            int size = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14) |
                       ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
            // Check for footer flag (bit 4 of flags byte)
            if ((header[5] & 0x10) != 0) size += 10;

            LOG.info("[RadioMod] Skipping ID3v2 header: {} bytes", size + 10);

            // Skip the tag body (we already read 10 bytes of header)
            long remaining = size;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    // skip() returned 0, try reading
                    int b = in.read();
                    if (b < 0) break;
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
            return in;
        } else {
            // Not an ID3 tag, reset
            in.reset();
            return in;
        }
    }

    /**
     * Skip bytes until we find an MP3 sync word (0xFF 0xE0+).
     * Reads up to 64KB looking for sync. If not found, returns stream as-is.
     */
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
                // EOF before finding sync
                in.reset();
                return in;
            }

            if (prev == 0xFF && (b & 0xE0) == 0xE0) {
                // Found sync! We need to "unread" these 2 bytes.
                // Reset and skip to position (skipped - 1)
                in.reset();
                long toSkip = skipped - 1; // position of 0xFF byte
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

        // Didn't find sync within 64KB, reset and let JLayer handle it
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
