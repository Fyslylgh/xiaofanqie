// 下载器：node dl.mjs <url> <dest> [retries]
// 处理重定向、断线重试（支持 HTTP Range 续传），打印进度与速度。
import https from 'node:https';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

function open(url, headers = {}, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 8) return reject(new Error('too many redirects'));
    const lib = url.startsWith('https:') ? https : http;
    const req = lib.get(url, { headers }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        return resolve(open(next, headers, depth + 1));
      }
      resolve(res);
    });
    req.on('error', reject);
    req.setTimeout(60000, () => req.destroy(new Error('timeout')));
  });
}

async function once(url, dest, expectSize) {
  let start = 0;
  try {
    start = fs.statSync(dest).size;
  } catch { /* 文件不存在 */ }
  if (expectSize && start === expectSize) return 'done';

  const headers = { 'User-Agent': 'Mozilla/5.0' };
  if (start > 0) headers.Range = `bytes=${start}-`;

  const res = await open(url, headers);
  if (res.statusCode >= 400) {
    res.resume();
    throw new Error(`HTTP ${res.statusCode}`);
  }
  // 服务器不支持续传则从头来过
  if (start > 0 && res.statusCode === 200) start = 0;

  const total = res.headers['content-range']
    ? Number(res.headers['content-range'].split('/')[1])
    : Number(res.headers['content-length'] || 0) + start;

  const out = fs.createWriteStream(dest, { flags: start > 0 ? 'a' : 'w' });
  let got = start;
  let last = Date.now();
  let lastBytes = got;
  let lastPrint = 0;

  await new Promise((resolve, reject) => {
    res.on('data', (c) => {
      got += c.length;
      const now = Date.now();
      if (now - lastPrint > 3000) {
        lastPrint = now;
        const speed = (got - lastBytes) / 1048576 / ((now - last) / 1000);
        const pct = total ? ((got / total) * 100).toFixed(1) + '%' : '?';
        process.stdout.write(`  ${pct.padStart(6)}  ${(got / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)}MB  ${speed.toFixed(2)}MB/s\n`);
        last = now;
        lastBytes = got;
      }
    });
    res.on('end', resolve);
    res.on('error', reject);
    out.on('error', reject);
    res.pipe(out);
  });
  await new Promise((r) => out.close(r));

  if (total && got !== total) throw new Error(`incomplete: ${got}/${total}`);
  return 'done';
}

const [url, dest, retriesArg] = process.argv.slice(2);
if (!url || !dest) {
  console.error('usage: node dl.mjs <url> <dest> [retries]');
  process.exit(2);
}
const retries = Number(retriesArg || 5);
fs.mkdirSync(path.dirname(path.resolve(dest)), { recursive: true });

let expectSize = 0;
try {
  const head = await open(url, { 'User-Agent': 'Mozilla/5.0' });
  if (head.statusCode >= 400) throw new Error(`HTTP ${head.statusCode}`);
  expectSize = Number(head.headers['content-length'] || 0);
  head.resume();
} catch (e) {
  console.log(`  (无法预取大小: ${e.message})`);
}

console.log(`↓ ${url}`);
console.log(`  → ${path.resolve(dest)}${expectSize ? '  (' + (expectSize / 1048576).toFixed(1) + ' MB)' : ''}`);

for (let i = 1; i <= retries; i++) {
  try {
    const r = await once(url, dest, expectSize);
    if (r === 'done') {
      const size = fs.statSync(dest).size;
      console.log(`✓ 完成 ${(size / 1048576).toFixed(1)} MB`);
      process.exit(0);
    }
  } catch (e) {
    console.log(`  第 ${i}/${retries} 次失败: ${e.message}`);
    if (i < retries) await new Promise((r) => setTimeout(r, 2000 * i));
  }
}
console.log('✗ 下载失败');
process.exit(1);
