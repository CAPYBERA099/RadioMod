/**
 * RadioMod Audio Proxy v3 — runs on VDS
 *
 * v2: Full MP3 pre-download → temp file → ffprobe duration → ffmpeg PCM stream.
 *     Eliminates stuttering. Supports seeking via ?start= parameter.
 * v3: File upload support. Clients can upload local audio files and share via radio.
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
const UPLOADS_DIR = path.join(os.homedir(), 'radio-uploads');
const MAX_UPLOAD_SIZE = 50 * 1024 * 1024; // 50 MB

// Ensure uploads directory exists
if (!fs.existsSync(UPLOADS_DIR)) {
    fs.mkdirSync(UPLOADS_DIR, { recursive: true });
    console.log(`[OK] Created uploads dir: ${UPLOADS_DIR}`);
} else {
    console.log(`[OK] Uploads dir: ${UPLOADS_DIR}`);
}

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

/**
 * Resolve an audio URL to a local file path.
 * If the URL points to our own /files/ endpoint, use the local file directly.
 */
function resolveToLocalFile(audioUrl) {
    try {
        const parsed = new URL(audioUrl);
        // Check if URL points to our own /files/ endpoint
        if (parsed.pathname.startsWith('/files/')) {
            const filename = path.basename(parsed.pathname);
            const localPath = path.join(UPLOADS_DIR, filename);
            if (fs.existsSync(localPath)) {
                return localPath;
            }
        }
    } catch (e) { /* not a valid URL */ }
    return null;
}

async function streamAudio(audioUrl, startSec, req, res) {
    let tmpFile;
    let needsCleanup = true;

    // Check if it's a local uploaded file — skip download
    const localFile = resolveToLocalFile(audioUrl);
    if (localFile) {
        tmpFile = localFile;
        needsCleanup = false; // don't delete uploaded files
        console.log(`[LOCAL] Using uploaded file: ${localFile}`);
    } else {
        // Download from remote
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
        tmpFile = path.join(os.tmpdir(), `radio_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.mp3`);
        try {
            fs.writeFileSync(tmpFile, mp3Buffer);
        } catch (e) {
            console.error(`[ERROR] Temp write: ${e.message}`);
            if (!res.headersSent) res.writeHead(500);
            res.end('Temp file error');
            return;
        }
    }

    // Get duration
    const duration = getDuration(tmpFile);
    if (duration) console.log(`[INFO] Duration: ${duration.toFixed(1)}s`);

    activeStreams++;
    const fileSize = fs.statSync(tmpFile).size;
    console.log(`[STREAM] ffmpeg start=${startSec}s, size=${(fileSize / 1024).toFixed(0)} KB (active: ${activeStreams})`);

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
        // Clean up temp file (but NOT uploaded files)
        if (needsCleanup) {
            try { fs.unlinkSync(tmpFile); } catch (_) {}
        }
    };

    ffmpeg.on('close', (code) => {
        if (code !== 0 && code !== null) console.error(`[ffmpeg] exited: ${code}`);
        cleanup();
    });

    req.on('close', cleanup);
}

/**
 * Handle file upload: POST /upload?name=filename.mp3
 * Body is raw file bytes (application/octet-stream).
 * Returns JSON: {"url": "http://host:port/files/id_name.mp3", "size": 12345, "name": "..."}
 */
function handleUpload(req, res, parsedUrl) {
    const rawName = parsedUrl.searchParams.get('name') || 'file.mp3';
    // Sanitize filename: keep only safe chars
    const safeName = rawName.replace(/[^a-zA-Z0-9._\-\u0400-\u04FF]/g, '_').substring(0, 100);
    const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const filename = `${id}_${safeName}`;

    const chunks = [];
    let totalLen = 0;
    let aborted = false;

    req.on('data', (chunk) => {
        totalLen += chunk.length;
        if (totalLen > MAX_UPLOAD_SIZE) {
            aborted = true;
            res.writeHead(413, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'File too large', max: MAX_UPLOAD_SIZE }));
            req.destroy();
            return;
        }
        chunks.push(chunk);
    });

    req.on('end', () => {
        if (aborted) return;

        const buf = Buffer.concat(chunks, totalLen);
        if (buf.length === 0) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Empty file' }));
            return;
        }

        const filepath = path.join(UPLOADS_DIR, filename);
        try {
            fs.writeFileSync(filepath, buf);
        } catch (e) {
            console.error(`[ERROR] Save upload: ${e.message}`);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Save failed' }));
            return;
        }

        const url = `http://166.1.144.133:${PORT}/files/${filename}`;
        console.log(`[UPLOAD] ${rawName} → ${filename} (${(buf.length / 1024).toFixed(0)} KB)`);

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ url, size: buf.length, name: rawName }));
    });

    req.on('error', (e) => {
        console.error(`[ERROR] Upload stream: ${e.message}`);
        if (!res.headersSent) res.writeHead(500);
        if (!res.writableEnded) res.end('Upload error');
    });
}

/**
 * Serve uploaded files: GET /files/filename.mp3
 */
function serveFile(res, parsedUrl) {
    const filename = path.basename(parsedUrl.pathname.slice(7)); // strip "/files/"
    const filepath = path.join(UPLOADS_DIR, filename);

    if (!fs.existsSync(filepath)) {
        res.writeHead(404);
        res.end('Not found');
        return;
    }

    const stat = fs.statSync(filepath);
    const ext = path.extname(filename).toLowerCase();
    const mimeTypes = {
        '.mp3': 'audio/mpeg',
        '.wav': 'audio/wav',
        '.ogg': 'audio/ogg',
        '.flac': 'audio/flac',
        '.m4a': 'audio/mp4',
        '.aac': 'audio/aac',
        '.opus': 'audio/opus',
        '.wma': 'audio/x-ms-wma'
    };
    const contentType = mimeTypes[ext] || 'application/octet-stream';

    console.log(`[FILES] Serving: ${filename} (${(stat.size / 1024).toFixed(0)} KB)`);

    res.writeHead(200, {
        'Content-Type': contentType,
        'Content-Length': stat.size,
        'Accept-Ranges': 'bytes'
    });

    fs.createReadStream(filepath).pipe(res);
}

/**
 * Clean up old uploaded files (older than 7 days).
 */
function cleanupOldUploads() {
    try {
        const files = fs.readdirSync(UPLOADS_DIR);
        const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
        let cleaned = 0;
        for (const file of files) {
            const fp = path.join(UPLOADS_DIR, file);
            const stat = fs.statSync(fp);
            if (stat.mtimeMs < cutoff) {
                fs.unlinkSync(fp);
                cleaned++;
            }
        }
        if (cleaned > 0) console.log(`[CLEANUP] Removed ${cleaned} old uploads`);
    } catch (e) {
        console.warn(`[WARN] Cleanup error: ${e.message}`);
    }
}

// Run cleanup every 6 hours
setInterval(cleanupOldUploads, 6 * 60 * 60 * 1000);
// And once on startup
cleanupOldUploads();

http.createServer((req, res) => {
    const parsed = new URL(req.url, `http://localhost:${PORT}`);

    if (parsed.pathname === '/health') {
        // Count uploaded files
        let uploadCount = 0;
        let uploadSize = 0;
        try {
            const files = fs.readdirSync(UPLOADS_DIR);
            uploadCount = files.length;
            for (const f of files) {
                uploadSize += fs.statSync(path.join(UPLOADS_DIR, f)).size;
            }
        } catch (e) { /* ignore */ }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'ok',
            version: 3,
            active: activeStreams,
            ffprobe: ffprobeAvailable,
            uploads: { count: uploadCount, sizeMB: (uploadSize / 1024 / 1024).toFixed(1) }
        }));
        return;
    }

    // File upload
    if (parsed.pathname === '/upload' && req.method === 'POST') {
        handleUpload(req, res, parsed);
        return;
    }

    // Serve uploaded files
    if (parsed.pathname.startsWith('/files/') && req.method === 'GET') {
        serveFile(res, parsed);
        return;
    }

    // Audio stream (existing)
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
    console.log(`RadioMod audio proxy v3 running on :${PORT}`);
    console.log(`Uploads dir: ${UPLOADS_DIR}`);
    console.log(`Test: curl http://localhost:${PORT}/health`);
});
