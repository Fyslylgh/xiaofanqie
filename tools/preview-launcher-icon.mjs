// 预览启动图标在启动器蒙版下的样子，检查环有没有被切。
//
// 自适应图标的前景是 108dp 画布，系统只显示中间直径 66dp 的安全区（各家 ROM 略有出入）。
// 这个脚本把 ic_launcher_foreground.png 按这个规则合成出来：
//   黑色底 + 前景 -> 裁出中间的蒙版圆 -> 放大成一张 PNG
// 于是"环会不会被切"在图上直接就能看出来，不用装机试。
//
// 顺便打印前景墨迹到圆心的最大距离，和蒙版半径对比 —— 这是可以自动判定的那一半。
//
// 用法: node tools/preview-launcher-icon.mjs

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FG = path.join(root, 'app/src/main/res/drawable-nodpi/ic_launcher_foreground.png');
const OUT = path.join(root, 'tools/icon-preview.png');

const MASK_DP = 66; // 安全区直径（保守值）
const TYPICAL_MASK_DP = 72; // 常见启动器蒙版直径
const CANVAS_DP = 108; // 前景画布尺寸
const OUT_SIZE = 512; // 预览图边长

// ---------- PNG 解码 ----------

function decodePng(buf) {
  let off = 8;
  let ihdr = null;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const payload = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      ihdr = {
        w: payload.readUInt32BE(0),
        h: payload.readUInt32BE(4),
        depth: payload[8],
        color: payload[9],
        interlace: payload[12],
      };
    } else if (type === 'IDAT') idat.push(payload);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  const ch = ihdr.color === 6 ? 4 : ihdr.color === 2 ? 3 : 1;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const w = ihdr.w;
  const h = ihdr.h;
  const stride = w * ch;
  const out = Buffer.alloc(w * h * 4);
  let prev = Buffer.alloc(stride);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[p++];
    const line = Buffer.from(raw.subarray(p, p + stride));
    p += stride;
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? line[i - ch] : 0;
      const b = prev[i];
      const c = i >= ch ? prev[i - ch] : 0;
      let v = line[i];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const pp = a + b - c;
        const pa = Math.abs(pp - a);
        const pb = Math.abs(pp - b);
        const pc = Math.abs(pp - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      line[i] = v & 0xff;
    }
    prev = line;
    for (let x = 0; x < w; x++) {
      const s = x * ch;
      const d = (y * w + x) * 4;
      out[d] = line[s];
      out[d + 1] = ch === 1 ? line[s] : line[s + 1];
      out[d + 2] = ch === 1 ? line[s] : line[s + 2];
      out[d + 3] = ch === 4 ? line[s + 3] : 255;
    }
  }
  return { w, h, data: out };
}

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunk(type, payload) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(payload.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), payload]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePng(w, h, rgb) {
  const stride = w * 3;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0;
    rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2; // truecolor
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------- 合成 ----------

const fg = decodePng(fs.readFileSync(FG));
console.log('前景 ' + fg.w + 'x' + fg.h + 'px（对应 108dp 画布）');
if (fg.w !== fg.h) throw new Error('前景不是正方形');

const pxPerDp = fg.w / CANVAS_DP;
const maskRadiusPx = (MASK_DP / 2) * pxPerDp;
console.log('每 dp ' + pxPerDp.toFixed(2) + 'px，蒙版半径 ' + maskRadiusPx.toFixed(2) + 'px（' + MASK_DP + 'dp）');

// 前景墨迹到圆心最远的距离
const cx = fg.w / 2;
const cy = fg.h / 2;
let maxInk = 0;
for (let y = 0; y < fg.h; y++) {
  for (let x = 0; x < fg.w; x++) {
    if (fg.data[(y * fg.w + x) * 4 + 3] > 24) {
      const r = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
      if (r > maxInk) maxInk = r;
    }
  }
}
const maxInkDp = maxInk / pxPerDp;
console.log('墨迹最远处 ' + maxInk.toFixed(2) + 'px = ' + maxInkDp.toFixed(1) + 'dp（蒙版半径 ' + (MASK_DP / 2) + 'dp）');
const clearance = MASK_DP / 2 - maxInkDp;
console.log(clearance > 0
  ? '  ok 墨迹在安全区内，余量 ' + clearance.toFixed(1) + 'dp'
  : '  x 墨迹超出安全区 ' + (-clearance).toFixed(1) + 'dp，蒙版会切到图案');

// 输出预览：整块 108dp 画布按比例画出来，再叠一个蒙版圆。
// 关键点：蒙版之外必须画满（而不是留白），否则"蒙版到底切掉了什么"根本看不出来。
const pxPerOutDp = OUT_SIZE / CANVAS_DP;
const out = Buffer.alloc(OUT_SIZE * OUT_SIZE * 3);
const outCenter = OUT_SIZE / 2;
const maskR66 = (OUT_SIZE * MASK_DP) / CANVAS_DP / 2;
const maskR72 = (OUT_SIZE * TYPICAL_MASK_DP) / CANVAS_DP / 2;
for (let y = 0; y < OUT_SIZE; y++) {
  for (let x = 0; x < OUT_SIZE; x++) {
    const d = (y * OUT_SIZE + x) * 3;
    const rad = Math.hypot(x + 0.5 - outCenter, y + 0.5 - outCenter);
    // 前景：432px 对应 108dp，逐点采样
    const sx = Math.min(fg.w - 1, Math.max(0, Math.floor((x + 0.5) / pxPerOutDp * (fg.w / CANVAS_DP))));
    const sy = Math.min(fg.h - 1, Math.max(0, Math.floor((y + 0.5) / pxPerOutDp * (fg.h / CANVAS_DP))));
    const a = fg.data[(sy * fg.w + sx) * 4 + 3] / 255;
    // 黑色背景层 + 白色前景 = 启动器里实际看到的像素
    let r = Math.round(255 * a);
    let g = r;
    let b = r;

    // 蒙版之外画成蓝灰，直观地表示"这部分会被裁掉"
    if (rad > maskR72) {
      r = 24; g = 44; b = 74;
    } else if (rad > maskR66) {
      r = 40; g = 66; b = 104;
    }

    // 66dp 安全区边界线
    if (Math.abs(rad - maskR66) <= 1) {
      r = 255;
      g = 170;
      b = 60;
    }
    out[d] = r;
    out[d + 1] = g;
    out[d + 2] = b;
  }
}
fs.writeFileSync(OUT, encodePng(OUT_SIZE, OUT_SIZE, out));
console.log('\n写出 ' + path.relative(root, OUT));
console.log('  橙色圈 = ' + MASK_DP + 'dp 安全区边界；蓝圈以内的暗区 = 常见启动器蒙版（' + TYPICAL_MASK_DP + 'dp）之外');
console.log('  图案若越过橙圈，就说明有蒙版会切到它');
