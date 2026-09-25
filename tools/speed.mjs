// 测速：对给定 URL 拉取前 5MB，报告实际下载速度。用于挑选镜像。
import https from 'node:https';
import http from 'node:http';

function get(url, opts = {}, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 8) return reject(new Error('too many redirects'));
    const lib = url.startsWith('https:') ? https : http;
    const req = lib.get(url, { headers: opts.headers || {} }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        return resolve(get(next, opts, depth + 1));
      }
      resolve(res);
    });
    req.on('error', reject);
    req.setTimeout(opts.timeout || 30000, () => req.destroy(new Error('timeout')));
  });
}

const LIMIT = 5 * 1024 * 1024;
const urls = process.argv.slice(2);

for (const u of urls) {
  const started = Date.now();
  try {
    const res = await get(u, {
      headers: { Range: `bytes=0-${LIMIT - 1}`, 'User-Agent': 'Mozilla/5.0' },
      timeout: 25000,
    });
    if (res.statusCode >= 400) {
      console.log(`HTTP ${res.statusCode}  ${u}`);
      res.resume();
      continue;
    }
    let got = 0;
    await new Promise((resolve) => {
      res.on('data', (c) => {
        got += c.length;
        if (got >= LIMIT) { res.destroy(); resolve(); }
      });
      res.on('end', resolve);
      res.on('error', resolve);
    });
    const secs = Math.max((Date.now() - started) / 1000, 0.001);
    const mbs = got / 1048576 / secs;
    console.log(`${mbs.toFixed(2).padStart(7)} MB/s  ${String(res.statusCode).padEnd(3)}  ${(got / 1048576).toFixed(1).padStart(4)}MB in ${secs.toFixed(1)}s  ${u}`);
  } catch (e) {
    console.log(`   FAIL  ${e.message}  ${u}`);
  }
}
