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
 * Streams MP3/Icecast audio, decodes via JLayer, plays through javax.sound
 * with distance-based volume.
 */
public class RadioAudioManager {
    private static final Logger LOG = LogManager.getLogger("RadioMod");
    private static final Map<BlockPos, RadioSource> SOURCES = new ConcurrentHashMap<BlockPos, RadioSource>();
    private static final float MAX_DISTANCE = 48f;

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
        try {
            String url = StreamResolver.resolve(rawUrl);

            if (url == null) {
                LOG.error("[RadioMod] Could not resolve audio URL from: {}", rawUrl);
                LOG.error("[RadioMod] Tip: use a direct MP3/stream link, or install yt-dlp for YouTube.");
                return;
            }

            LOG.info("[RadioMod] Connecting: {}", url);

            HttpURLConnection conn = openConnection(url);
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                LOG.error("[RadioMod] HTTP {} from {}", code, url);
                return;
            }

            // Check content type to avoid decoding HTML as MP3
            String ct = conn.getContentType();
            if (ct != null && ct.contains("text/html")) {
                LOG.error("[RadioMod] Server returned HTML, not audio! URL: {}", url);
                LOG.error("[RadioMod] This is a web page, not a direct audio stream.");
                conn.disconnect();
                return;
            }

            InputStream in = new BufferedInputStream(conn.getInputStream(), 16384);

            // Skip ID3v2 tag if present (some streams have it)
            in.mark(10);
            byte[] id3check = new byte[3];
            int r = in.read(id3check);
            in.reset();

            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();

            // Read first frame to discover format
            Header hdr = bitstream.readFrame();
            if (hdr == null) {
                LOG.error("[RadioMod] No MP3 frames found in stream!");
                LOG.error("[RadioMod] The URL may not point to an MP3 audio source.");
                if (ct != null) LOG.error("[RadioMod] Content-Type was: {}", ct);
                return;
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

            LOG.info("[RadioMod] Playing {}Hz {}ch — {}", sampleRate, channels, rawUrl);

            // Write first decoded frame
            writeFrame(line, sb);

            // Main loop
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

        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            LOG.error("[RadioMod] Playback error: {}", e.getMessage());
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
    }
}
