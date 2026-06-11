/**
 * RadioMod Audio Proxy — runs on VDS
 * 
 * Downloads MP3 from thetacloud, re-encodes via ffmpeg to raw PCM,
 * streams back to the Minecraft client. Eliminates all JLayer issues.
 * 
 * Usage: pm2 start radio-proxy.js --name radio-proxy
 * Requires: Node.js 14+, ffmpeg installed
 */

const http = require('http');
const https = require('https');
const { spawn, execSync } = require('child_process');

const PORT = 8300;

// Check ffmpeg on startup
try {
    execSync('ffmpeg -version', { stdio: 'pipe' });
    console.log('[OK] ffmpeg found');
} catch (e) {
    console.error('[ERROR] ffmpeg not found! Install: sudo apt install -y ffmpeg');
    process.exit(1);
}

let activeStreams = 0;

function streamAudio(audioUrl, req, res) {
    const parsed = new URL(audioUrl);
    const httpMod = parsed.protocol === 'https:' ? https : http;

    const dlReq = httpMod.request(audioUrl, {
        method: 'GET',
        headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
            'Origin': 'https://mp3juice.sc',
            'Referer': 'https://mp3juice.sc/',
            'Accept': '*/*'
        }
    }, (dlRes) => {
        if (dlRes.statusCode !== 200) {
            console.error(`[ERROR] Upstream HTTP ${dlRes.statusCode}`);
            res.writeHead(502);
            res.end(`Upstream error: ${dlRes.statusCode}`);
            dlRes.resume();
            return;
        }

        activeStreams++;
        console.log(`[STREAM] Starting ffmpeg (active: ${activeStreams})`);

        const ffmpeg = spawn('ffmpeg', [
            '-hide_banner', '-loglevel', 'error',
            '-i', 'pipe:0',          // read MP3 from stdin
            '-vn',                    // no video
            '-f', 's16le',            // raw PCM output
            '-acodec', 'pcm_s16le',   // 16-bit signed little-endian
            '-ar', '44100',           // 44.1 kHz
            '-ac', '2',               // stereo
            'pipe:1'                  // write PCM to stdout
        ]);

        res.writeHead(200, {
            'Content-Type': 'audio/pcm',
            'X-Sample-Rate': '44100',
            'X-Channels': '2',
            'X-Bit-Depth': '16'
        });

        // Pipeline: thetacloud MP3 → ffmpeg stdin → pcm stdout → HTTP response
        dlRes.pipe(ffmpeg.stdin);
        ffmpeg.stdout.pipe(res);

        ffmpeg.stdin.on('error', () => {}); // ignore EPIPE on early close

        ffmpeg.stderr.on('data', (d) => {
            console.error(`[ffmpeg] ${d.toString().trim()}`);
        });

        const cleanup = () => {
            activeStreams = Math.max(0, activeStreams - 1);
            dlRes.destroy();
            ffmpeg.kill('SIGTERM');
            if (!res.writableEnded) res.end();
        };

        ffmpeg.on('close', (code) => {
            if (code !== 0 && code !== null) console.error(`[ffmpeg] exited: ${code}`);
            cleanup();
        });

        req.on('close', cleanup);
    });

    dlReq.on('error', (err) => {
        console.error(`[ERROR] Download: ${err.message}`);
        if (!res.headersSent) res.writeHead(502);
        res.end('Download error');
    });

    dlReq.end();
}

http.createServer((req, res) => {
    const parsed = new URL(req.url, `http://localhost:${PORT}`);

    if (parsed.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok', active: activeStreams }));
        return;
    }

    if (parsed.pathname === '/stream') {
        const audioUrl = parsed.searchParams.get('url');
        if (!audioUrl) {
            res.writeHead(400);
            res.end('Missing url parameter');
            return;
        }

        const short = audioUrl.length > 60 ? audioUrl.substring(0, 60) + '...' : audioUrl;
        console.log(`[${new Date().toLocaleTimeString()}] Request: ${short}`);

        streamAudio(audioUrl, req, res);
        return;
    }

    res.writeHead(404);
    res.end();
}).listen(PORT, '0.0.0.0', () => {
    console.log(`RadioMod audio proxy running on :${PORT}`);
    console.log(`Test: curl http://localhost:${PORT}/health`);
});
