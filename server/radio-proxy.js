/**
 * RadioMod Audio Proxy v2 — runs on VDS
 *
 * v2: Full MP3 pre-download → temp file → ffprobe duration → ffmpeg PCM stream.
 *     Eliminates stuttering. Supports seeking via ?start= parameter.
 *
 * Usage: pm2 start radio-proxy.js --name radio-proxy
 * Requires: Node.js 14+, ffmpeg + ffprobe installed
 */

const http = require('http');
const https = require('https');
const { spawn, execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PORT = 8300;

// Check ffmpeg/ffprobe on startup
try {
    execSync('ffmpeg -version', { stdio: 'pipe' });
    console.log('[OK] ffmpeg found');
} catch (e) {
    console.error('[ERROR] ffmpeg not found! Install: sudo apt install -y ffmpeg');
    process.exit(1);
}

let ffprobeAvailable = false;
try {
    execSync('ffprobe -version', { stdio: 'pipe' });
    ffprobeAvailable = true;
    console.log('[OK] ffprobe found');
} catch (e) {
    console.warn('[WARN] ffprobe not found — duration headers will be unavailable');
}

let activeStreams = 0;

function downloadFull(audioUrl) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(audioUrl);
        const httpMod = parsed.protocol === 'https:' ? https : http;

        const dlReq = httpMod.request(audioUrl, {
            method: 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
                'Origin': 'https://mp3juice.sc',
                'Referer': 'https://mp3juice.sc/',
                'Accept': '*/*'
            },
            timeout: 30000
        }, (dlRes) => {
            if (dlRes.statusCode !== 200) {
                dlRes.resume();
                return reject(new Error(`HTTP ${dlRes.statusCode}`));
            }

            const chunks = [];
            let totalLen = 0;

            dlRes.on('data', (chunk) => {
                chunks.push(chunk);
                totalLen += chunk.length;
            });

            dlRes.on('end', () => {
                const full = Buffer.concat(chunks, totalLen);
                console.log(`[DL] Downloaded ${(totalLen / 1024).toFixed(0)} KB`);
                resolve(full);
            });

            dlRes.on('error', reject);
        });

        dlReq.on('error', reject);
        dlReq.on('timeout', () => {
            dlReq.destroy();
            reject(new Error('Download timeout'));
        });
        dlReq.end();
    });
}

/**
 * Get MP3 duration using ffprobe.
 * @param {string} filePath - path to temp MP3 file
 * @returns {number|null} duration in seconds
 */
function getDuration(filePath) {
    if (!ffprobeAvailable) return null;
    try {
        const result = execSync(
            `ffprobe -v quiet -show_entries format=duration -of csv=p=0 "${filePath}"`,
            { timeout: 5000, encoding: 'utf8' }
        );
        const dur = parseFloat(result.trim());
        return isNaN(dur) ? null : dur;
    } catch (e) {
        console.warn(`[WARN] ffprobe failed: ${e.message}`);
        return null;
    }
}

async function streamAudio(audioUrl, startSec, req, res) {
    let mp3Buffer;
    try {
        mp3Buffer = await downloadFull(audioUrl);
    } catch (err) {
        console.error(`[ERROR] Download: ${err.message}`);
        if (!res.headersSent) res.writeHead(502);
        res.end(`Download error: ${err.message}`);
        return;
    }

    if (mp3Buffer.length < 1000) {
        console.error(`[ERROR] Too small: ${mp3Buffer.length} bytes`);
        if (!res.headersSent) res.writeHead(502);
        res.end('File too small');
        return;
    }

    // Write to temp file for ffprobe + fast seeking
    const tmpFile = path.join(os.tmpdir(), `radio_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.mp3`);
    try {
        fs.writeFileSync(tmpFile, mp3Buffer);
    } catch (e) {
        console.error(`[ERROR] Temp write: ${e.message}`);
        if (!res.headersSent) res.writeHead(500);
        res.end('Temp file error');
        return;
    }

    // Get duration
    const duration = getDuration(tmpFile);
    if (duration) console.log(`[INFO] Duration: ${duration.toFixed(1)}s`);

    activeStreams++;
    console.log(`[STREAM] ffmpeg start=${startSec}s, size=${(mp3Buffer.length / 1024).toFixed(0)} KB (active: ${activeStreams})`);

    // Build ffmpeg args — use temp file for fast seeking
    const ffmpegArgs = [
        '-hide_banner', '-loglevel', 'error'
    ];

    if (startSec > 0) {
        ffmpegArgs.push('-ss', String(startSec));
    }

    ffmpegArgs.push(
        '-i', tmpFile,
        '-vn',
        '-f', 's16le',
        '-acodec', 'pcm_s16le',
        '-ar', '44100',
        '-ac', '2',
        'pipe:1'
    );

    const ffmpeg = spawn('ffmpeg', ffmpegArgs);

    const headers = {
        'Content-Type': 'audio/pcm',
        'X-Sample-Rate': '44100',
        'X-Channels': '2',
        'X-Bit-Depth': '16'
    };
    if (duration) {
        headers['X-Duration-Seconds'] = duration.toFixed(2);
    }

    res.writeHead(200, headers);

    ffmpeg.stdout.pipe(res);

    ffmpeg.stdin.on('error', () => {}); // ignore EPIPE

    ffmpeg.stderr.on('data', (d) => {
        console.error(`[ffmpeg] ${d.toString().trim()}`);
    });

    const cleanup = () => {
        activeStreams = Math.max(0, activeStreams - 1);
        ffmpeg.kill('SIGTERM');
        if (!res.writableEnded) res.end();
        // Clean up temp file
        try { fs.unlinkSync(tmpFile); } catch (_) {}
    };

    ffmpeg.on('close', (code) => {
        if (code !== 0 && code !== null) console.error(`[ffmpeg] exited: ${code}`);
        cleanup();
    });

    req.on('close', cleanup);
}

http.createServer((req, res) => {
    const parsed = new URL(req.url, `http://localhost:${PORT}`);

    if (parsed.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', active: activeStreams, ffprobe: ffprobeAvailable }));
        return;
    }

    if (parsed.pathname === '/stream') {
        const audioUrl = parsed.searchParams.get('url');
        if (!audioUrl) {
            res.writeHead(400);
            res.end('Missing url parameter');
            return;
        }

        const startSec = parseFloat(parsed.searchParams.get('start') || '0') || 0;

        const short_ = audioUrl.length > 60 ? audioUrl.substring(0, 60) + '...' : audioUrl;
        console.log(`[${new Date().toLocaleTimeString()}] Request: ${short_} (start=${startSec}s)`);

        streamAudio(audioUrl, startSec, req, res).catch(err => {
            console.error(`[ERROR] Stream: ${err.message}`);
            if (!res.headersSent) res.writeHead(500);
            if (!res.writableEnded) res.end('Internal error');
        });
        return;
    }

    res.writeHead(404);
    res.end();
}).listen(PORT, '0.0.0.0', () => {
    console.log(`RadioMod audio proxy v2 running on :${PORT}`);
    console.log(`Test: curl http://localhost:${PORT}/health`);
});
