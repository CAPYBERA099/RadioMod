package com.wenz.radiomod.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javazoom.jl.decoder.*;

/**
 * Client-side audio engine.
 *
 * Streams MP3/Icecast audio from a URL, decodes via JLayer,
 * and plays through javax.sound with distance-based volume.
 *
 * YouTube URLs are resolved via yt-dlp if installed.
 */
public class RadioAudioManager {
    private static final Logger LOG = LoggerFactory.getLogger("RadioMod");
    private static final Map<BlockPos, RadioSource> SOURCES = new ConcurrentHashMap<>();
    private static final float MAX_DISTANCE = 48f;

    /* ======== public API ======== */

    public static void play(BlockPos pos, String url, float volume) {
        stop(pos);

        RadioSource src = new RadioSource();
        src.pos = pos;
        src.baseVolume = volume;
        src.running = true;

        SOURCES.put(pos, src);

        Thread t = new Thread(() -> stream(src, url), "RadioMod-" + pos.toShortString());
        t.setDaemon(true);
        t.start();
        src.thread = t;
    }

    public static void stop(BlockPos pos) {
        RadioSource src = SOURCES.remove(pos);
        if (src != null) {
            src.running = false;
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        for (var entry : SOURCES.entrySet()) {
            RadioSource src = entry.getValue();
            if (!src.running) { SOURCES.remove(entry.getKey()); continue; }
            if (src.line == null) continue;

            double dist = Math.sqrt(mc.player.blockPosition().distSqr(src.pos));
            float gain = calcGain(dist, src.baseVolume);
            applyGain(src.line, gain);
        }
    }

    /* ======== streaming core ======== */

    private static void stream(RadioSource src, String rawUrl) {
        try {
            String url = StreamResolver.resolve(rawUrl);
            LOG.info("[RadioMod] Connecting: {}", url);

            HttpURLConnection conn = openConnection(url);
            if (conn.getResponseCode() / 100 != 2) {
                LOG.error("[RadioMod] HTTP {}", conn.getResponseCode());
                return;
            }

            InputStream in = new BufferedInputStream(conn.getInputStream(), 16384);
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();

            // Read first frame to discover format
            Header hdr = bitstream.readFrame();
            if (hdr == null) { LOG.warn("[RadioMod] No frames"); return; }

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

            LOG.info("[RadioMod] Playing {}Hz {}ch", sampleRate, channels);

            // Write first decoded frame
            writeFrame(line, sb);

            // Main loop
            while (src.running && !Thread.interrupted()) {
                hdr = bitstream.readFrame();
                if (hdr == null) {
                    // For live streams this may mean a network hiccup — retry
                    Thread.sleep(200);
                    hdr = bitstream.readFrame();
                    if (hdr == null) break; // truly ended
                }
                sb = (SampleBuffer) decoder.decodeFrame(hdr, bitstream);
                writeFrame(line, sb);
                bitstream.closeFrame();
            }

        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            LOG.error("[RadioMod] Error: {}", e.getMessage());
        } finally {
            if (src.line != null) {
                try { src.line.drain(); } catch (Exception ignored) {}
                src.line.stop();
                src.line.close();
            }
            src.running = false;
            LOG.info("[RadioMod] Stopped");
        }
    }

    /* ======== helpers ======== */

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URI(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (RadioMod/1.0)");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Icy-MetaData", "0"); // suppress icecast metadata
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
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
        } catch (IllegalArgumentException ignored) {} // control not available
    }

    /* ======== inner class ======== */

    private static class RadioSource {
        BlockPos pos;
        volatile float baseVolume;
        volatile boolean running;
        Thread thread;
        SourceDataLine line;
    }
}
