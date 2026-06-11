package com.wenz.radiomod.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javazoom.jl.decoder.*;

/**
 * Client-side audio engine for 1.12.2.
 * Supports two playback paths:
 *   1. JLayer — for MP3 streams (internet radio, .mp3 files)
 *   2. FFmpeg pipe — for any other format (YouTube AAC/Opus, OGG, FLAC, etc.)
 *      Runs: ffmpeg -i URL -f s16le -ar 44100 -ac 2 pipe:1
 *      Reads raw PCM from stdout → SourceDataLine
 */
public class RadioAudioManager {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final Map<BlockPos, RadioSource> SOURCES = new ConcurrentHashMap<BlockPos, RadioSource>();
    private static final float MAX_DISTANCE = 48f;

    // FFmpeg availability (cached after first check)
    private static Boolean ffmpegAvailable = null;

    /* ======== public API ======== */

    public static void play(BlockPos pos, String url, float volume) {
        stop(pos);

        final RadioSource src = new RadioSource();
        src.pos = pos;
        src.baseVolume = volume;
        src.running = true;

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
            if (src.ffmpegProcess != null) {
                src.ffmpegProcess.destroyForcibly();
            }
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

    public static void setVolume(BlockPos pos, float vol) {
        RadioSource s = SOURCES.get(pos);
        if (s != null) s.baseVolume = Math.max(0, Math.min(1, vol));
    }

    /** Called every client tick — updates volume based on player distance. */
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
        // Resolve the URL
        StreamResolver.ResolveResult resolved = StreamResolver.resolve(rawUrl);

        if (resolved == null) {
            LOG.error("[RadioMod] Could not resolve: {}", rawUrl);
            return;
        }

        LOG.info("[RadioMod] Resolved: {} (needsFfmpeg={})", resolved.url, resolved.needsFfmpeg);

        if (resolved.needsFfmpeg) {
            // Non-MP3 audio (YouTube AAC, Opus, etc.) — use ffmpeg pipe
            if (isFfmpegAvailable()) {
                streamFfmpeg(src, resolved.url, rawUrl);
            } else {
                // No ffmpeg — try JLayer anyway (might work for some formats)
                LOG.warn("[RadioMod] ffmpeg not found, trying JLayer for non-MP3...");
                if (!streamJLayer(src, resolved.url, rawUrl)) {
                    LOG.error("[RadioMod] Playback failed. Install ffmpeg for YouTube/non-MP3 audio:");
                    LOG.error("[RadioMod]   winget install ffmpeg");
                    LOG.error("[RadioMod] After install, restart Minecraft.");
                }
            }
        } else {
            // MP3 stream — use JLayer directly
            if (!streamJLayer(src, resolved.url, rawUrl)) {
                // JLayer failed — try ffmpeg as fallback
                if (isFfmpegAvailable()) {
                    LOG.info("[RadioMod] JLayer failed, trying ffmpeg...");
                    streamFfmpeg(src, resolved.url, rawUrl);
                }
            }
        }
    }

    /* ======== JLayer (MP3) path ======== */

    private static boolean streamJLayer(RadioSource src, String url, String displayUrl) {
        try {
            HttpURLConnection conn = openConnection(url);
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                LOG.error("[RadioMod] HTTP {} from {}", code, url);
                return false;
            }

            String ct = conn.getContentType();
            if (ct != null && ct.contains("text/html")) {
                LOG.error("[RadioMod] Server returned HTML, not audio");
                conn.disconnect();
                return false;
            }

            InputStream in = new BufferedInputStream(conn.getInputStream(), 16384);
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();

            Header hdr = bitstream.readFrame();
            if (hdr == null) {
                LOG.warn("[RadioMod] No MP3 frames (not an MP3 stream)");
                return false;
            }

            int sampleRate = hdr.frequency();
            int channels = (hdr.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;

            SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
            bitstream.closeFrame();

            AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, sampleRate * channels * 2);
            line.start();
            src.line = line;

            LOG.info("[RadioMod] Playing (JLayer) {}Hz {}ch — {}", sampleRate, channels, displayUrl);

            writeFrame(line, sb);

            while (src.running && !Thread.interrupted()) {
                hdr = bitstream.readFrame();
                if (hdr == null) {
                    Thread.sleep(200);
                    hdr = bitstream.readFrame();
                    if (hdr == null) break;
                }
                sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                writeFrame(line, sb);
                bitstream.closeFrame();
            }

            return true;

        } catch (InterruptedException ignored) {
            return true;
        } catch (Exception e) {
            LOG.debug("[RadioMod] JLayer error: {}", e.getMessage());
            return false;
        } finally {
            cleanupLine(src);
        }
    }

    /* ======== FFmpeg pipe path ======== */

    /**
     * Plays any audio format by piping through ffmpeg.
     * Command: ffmpeg -i <url> -f s16le -ar 44100 -ac 2 -loglevel error pipe:1
     * Outputs raw 16-bit signed little-endian PCM at 44100 Hz stereo.
     */
    private static void streamFfmpeg(RadioSource src, String url, String displayUrl) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5",
                "-i", url,
                "-vn",                      // no video
                "-f", "s16le",              // raw PCM output
                "-acodec", "pcm_s16le",     // 16-bit signed LE
                "-ar", "44100",             // 44.1 kHz
                "-ac", "2",                 // stereo
                "-loglevel", "error",
                "pipe:1"                    // output to stdout
            );
            pb.redirectErrorStream(false);
            proc = pb.start();
            src.ffmpegProcess = proc;

            // Read stderr in background for error logging
            final Process fProc = proc;
            Thread errThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader err = new BufferedReader(
                            new InputStreamReader(fProc.getErrorStream()));
                        String line;
                        while ((line = err.readLine()) != null) {
                            LOG.warn("[RadioMod] ffmpeg: {}", line);
                        }
                        err.close();
                    } catch (Exception ignored) {}
                }
            }, "RadioMod-ffmpeg-err");
            errThread.setDaemon(true);
            errThread.start();

            // Set up audio output: 44100 Hz, 16-bit, stereo, signed, little-endian
            AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, 44100 * 2 * 2); // 1 second buffer
            line.start();
            src.line = line;

            LOG.info("[RadioMod] Playing (ffmpeg) 44100Hz 2ch — {}", displayUrl);

            // Read raw PCM from ffmpeg stdout and write to audio line
            InputStream in = new BufferedInputStream(proc.getInputStream(), 16384);
            byte[] buf = new byte[8192]; // ~46ms of audio at 44100Hz stereo 16-bit
            int read;

            while (src.running && (read = in.read(buf)) != -1) {
                if (!src.running) break;
                line.write(buf, 0, read);
            }

            in.close();

        } catch (Exception e) {
            LOG.error("[RadioMod] ffmpeg playback error: {}", e.getMessage());
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
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
            // Read output to prevent blocking
            InputStream is = p.getInputStream();
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) {}
            is.close();
            int exit = p.waitFor();
            ffmpegAvailable = (exit == 0);
        } catch (Exception e) {
            ffmpegAvailable = false;
        }

        if (ffmpegAvailable) {
            LOG.info("[RadioMod] ffmpeg detected — all audio formats supported");
        } else {
            LOG.warn("[RadioMod] ffmpeg not found — only MP3 streams available");
            LOG.warn("[RadioMod] Install ffmpeg for YouTube support: winget install ffmpeg");
        }
        return ffmpegAvailable;
    }

    /* ======== helpers ======== */

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (RadioMod/1.0)");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Icy-MetaData", "0");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.connect();
        return c;
    }

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
            try { src.line.drain(); } catch (Exception ignored) {}
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
        Thread thread;
        SourceDataLine line;
        Process ffmpegProcess;
    }
}
