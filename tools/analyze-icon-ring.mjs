// 量出番茄图标外圈那个环（圆角方形轮廓）的几何，并重新生成一份四周有留白的
// 源图 tools/icon-source-padded.png。
//
// 为什么需要这一步：tools/icon-source.png 是把原图裁到墨迹外接框得到的，
// 环在四个方向都被切掉了一截（左右约 160px，上下更多，四个角切得最狠）。
// 于是无论把这张图缩到多大放进自适应图标，环的外圈总是贴着画布边缘，
// 蒙版一切就没了 —— 这就是"裁切太狠、外面一圈都没了"。
//
// 做法：
//   1. 量出环的外缘半径 r(theta) 与线宽。正上方一段被花萼压住、量到的其实是花萼，
//      用低阶傅里叶级数把 r(theta) 平滑过去（环是规则图形，这个先验成立）；
//   2. 由 r(theta) 反解出圆角半径，算出环上离圆心最远的点——那是四个角，不是四条边；
//   3. 按"自适应图标安全区"反推画布尺寸，把源图整体贴到画布中央；
//   4. 用径向距离场把整圈环补画一遍，源图里已经有墨迹的地方保留原像素，
//      已经画好的那大半圈不会被二次覆盖，接缝才均匀。
//
// 用法: node tools/analyze-icon-ring.mjs

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(root, 'tools/icon-source.png');
const OUT_PNG = path.join(root, 'tools/icon-source-padded.png');

// 自适应图标：前景 108dp 画布，系统只显示中间的安全区圆（标准直径 66dp）。
// 各家 ROM 的蒙版在 66~72dp 之间，取 66dp 并按 1.12 倍留余量。
const CANVAS_DP = 108;
const SAFE_DP = 66;
const SAFE_MARGIN = 1.12;
const SAFE_RADIUS_RATIO = (SAFE_DP / 2 / CANVAS_DP) * SAFE_MARGIN; // 蒙版半径 / 画布边长

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
  if (ihdr.depth !== 8 || ihdr.interlace !== 0) throw new Error('unsupported png');
  const ch = ihdr.color === 6 ? 4 : ihdr.color === 2 ? 3 : 1;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const pw = ihdr.w;
  const ph = ihdr.h;
  const rowLen = pw * ch;
  const rgba = Buffer.alloc(pw * ph * 4);
  let prev = Buffer.alloc(rowLen);
  let cursor = 0;
  for (let y = 0; y < ph; y++) {
    const filter = raw[cursor++];
    const line = Buffer.from(raw.subarray(cursor, cursor + rowLen));
    cursor += rowLen;
    for (let i = 0; i < rowLen; i++) {
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
    for (let x = 0; x < pw; x++) {
      const s = x * ch;
      const d = (y * pw + x) * 4;
      rgba[d] = line[s];
      rgba[d + 1] = ch === 1 ? line[s] : line[s + 1];
      rgba[d + 2] = ch === 1 ? line[s] : line[s + 2];
      rgba[d + 3] = ch === 4 ? line[s + 3] : 255;
    }
  }
  return { w: pw, h: ph, rgba };
}

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function pngChunk(type, payload) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(payload.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), payload]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePng(size, rgba) {
  const rowLen = size * 4;
  const raw = Buffer.alloc((rowLen + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (rowLen + 1)] = 0;
    rgba.copy(raw, y * (rowLen + 1) + 1, y * rowLen, (y + 1) * rowLen);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 对称高斯消元，解 A x = b */
function solve(A, rhs) {
  const n = rhs.length;
  const m = A.map((row, i) => [...row, rhs[i]]);
  for (let i = 0; i < n; i++) {
    let piv = i;
    for (let k = i + 1; k < n; k++) if (Math.abs(m[k][i]) > Math.abs(m[piv][i])) piv = k;
    const tmp = m[i];
    m[i] = m[piv];
    m[piv] = tmp;
    if (Math.abs(m[i][i]) < 1e-12) continue;
    for (let k = i + 1; k < n; k++) {
      const f = m[k][i] / m[i][i];
      for (let j = i; j <= n; j++) m[k][j] -= f * m[i][j];
    }
  }
  const x = new Float64Array(n);
  for (let i = n - 1; i >= 0; i--) {
    let v = m[i][n];
    for (let j = i + 1; j < n; j++) v -= m[i][j] * x[j];
    x[i] = Math.abs(m[i][i]) < 1e-12 ? 0 : v / m[i][i];
  }
  return x;
}

// ---------- 读源图 ----------

const src = decodePng(fs.readFileSync(SRC));
const SRC_W = src.w;
const SRC_H = src.h;
const SRC_RGBA = src.rgba;
const SRC_LUM = new Uint8Array(SRC_W * SRC_H);
for (let i = 0, j = 0; i < SRC_W * SRC_H; i++, j += 4) {
  SRC_LUM[i] = (0.2126 * SRC_RGBA[j] + 0.7152 * SRC_RGBA[j + 1] + 0.0722 * SRC_RGBA[j + 2]) | 0;
}

// ---------- 1. 定中心与外接框 ----------

let edgeLeft = SRC_W;
let edgeRight = -1;
let edgeBottom = -1;
for (let y = 0; y < SRC_H; y++) {
  for (let x = 0; x < SRC_W; x++) {
    if (SRC_LUM[y * SRC_W + x] > 128) {
      if (x < edgeLeft) edgeLeft = x;
      break;
    }
  }
  for (let x = SRC_W - 1; x >= 0; x--) {
    if (SRC_LUM[y * SRC_W + x] > 128) {
      if (x > edgeRight) edgeRight = x;
      break;
    }
  }
}
for (let x = 0; x < SRC_W; x++) {
  for (let y = SRC_H - 1; y >= 0; y--) {
    if (SRC_LUM[y * SRC_W + x] > 128) {
      if (y > edgeBottom) edgeBottom = y;
      break;
    }
  }
}

const CENTER_X = (edgeLeft + edgeRight) / 2;
const HALF = (edgeRight - edgeLeft) / 2;
// 环是圆角方形：底部那条直边离中心正好一个半宽，用它反推中心 y
const CENTER_Y = edgeBottom - HALF;

console.log('量几何');
console.log('  左右最外侧白像素 x = ' + edgeLeft + ' / ' + edgeRight + '  -> 中心 x = ' + CENTER_X.toFixed(1) + '，半宽 ' + HALF.toFixed(1) + 'px');
console.log('  最下方白像素 y = ' + edgeBottom + '  -> 中心 y = ' + CENTER_Y.toFixed(1));
console.log('  环外接框 ' + (HALF * 2).toFixed(0) + 'x' + (HALF * 2).toFixed(0) + 'px（源图画布 ' + SRC_W + 'x' + SRC_H + '）');

// ---------- 2. 按角度量外缘半径 ----------

const BINS = 1440;

function angleOf(k) {
  return ((k / BINS) * 2 - 1) * Math.PI;
}

const RADII = new Float64Array(BINS).fill(-1);
for (let k = 0; k < BINS; k++) {
  const th = angleOf(k);
  const dx = Math.cos(th);
  const dy = Math.sin(th);
  let last = -1;
  for (let t = 40; t < 1400; t += 0.25) {
    const x = Math.round(CENTER_X + dx * t);
    const y = Math.round(CENTER_Y + dy * t);
    if (x < 0 || x >= SRC_W || y < 0 || y >= SRC_H) break;
    if (SRC_LUM[y * SRC_W + x] > 128) last = t;
  }
  RADII[k] = last;
}

let measuredCount = 0;
for (let k = 0; k < BINS; k++) if (RADII[k] > 0) measuredCount++;
console.log('\n外缘半径：' + measuredCount + '/' + BINS + ' 个方向量到值');

// ---------- 3. 迭代拟合 + 离群点剔除 ----------

const HARMONICS = 4;
const KEEP = new Uint8Array(BINS);
for (let k = 0; k < BINS; k++) KEEP[k] = RADII[k] > 0 ? 1 : 0;

function fourierCoeffs() {
  const dim = 1 + HARMONICS * 2;
  const A = Array.from({ length: dim }, () => new Float64Array(dim));
  const rhs = new Float64Array(dim);
  for (let k = 0; k < BINS; k++) {
    if (!KEEP[k] || !(RADII[k] > 0)) continue;
    const th = angleOf(k);
    const f = new Float64Array(dim);
    f[0] = 1;
    for (let n = 1; n <= HARMONICS; n++) {
      f[n * 2 - 1] = Math.cos(n * th);
      f[n * 2] = Math.sin(n * th);
    }
    for (let i = 0; i < dim; i++) {
      for (let j = 0; j < dim; j++) A[i][j] += f[i] * f[j];
      rhs[i] += f[i] * RADII[k];
    }
  }
  return solve(A, rhs);
}

function radiusAt(coeffs, th) {
  let v = coeffs[0];
  for (let n = 1; n <= HARMONICS; n++) {
    v += coeffs[n * 2 - 1] * Math.cos(n * th) + coeffs[n * 2] * Math.sin(n * th);
  }
  return v;
}

// 环是规则图形，r(theta) 能被低阶傅里叶级数很好地描述；而花萼、指针压住环的地方，
// 量到的是压住它的那个图形的外缘。先全量拟合一次，把偏差过大的方向剔掉再拟合，重复几轮。
let coeffs = null;
let usedCount = 0;
let droppedCount = 0;
for (let round = 0; round < 8; round++) {
  coeffs = fourierCoeffs();
  const errs = [];
  for (let k = 0; k < BINS; k++) {
    if (!KEEP[k]) continue;
    errs.push(Math.abs(radiusAt(coeffs, angleOf(k)) - RADII[k]));
  }
  errs.sort((a, b) => a - b);
  const limit = Math.max(2.5, errs[errs.length >> 1] * 1.4826 * 4);
  usedCount = 0;
  droppedCount = 0;
  for (let k = 0; k < BINS; k++) {
    if (!KEEP[k]) continue;
    if (Math.abs(radiusAt(coeffs, angleOf(k)) - RADII[k]) > limit) {
      KEEP[k] = 0;
      droppedCount++;
    } else {
      usedCount++;
    }
  }
  if (droppedCount === 0) break;
}
console.log('  拟合用 ' + usedCount + ' 个方向，剔除 ' + droppedCount + ' 个离群方向（被花萼压住的位置）');

let errSum = 0;
let errMax = 0;
let errN = 0;
for (let k = 0; k < BINS; k++) {
  if (!KEEP[k]) continue;
  const e = radiusAt(coeffs, angleOf(k)) - RADII[k];
  errSum += e * e;
  errMax = Math.max(errMax, Math.abs(e));
  errN++;
}
console.log('  拟合残差 rms ' + Math.sqrt(errSum / errN).toFixed(2) + 'px，最大 ' + errMax.toFixed(2) + 'px');

let rMin = Infinity;
let rMax = 0;
for (let k = 0; k < BINS; k++) {
  const r = radiusAt(coeffs, angleOf(k));
  rMin = Math.min(rMin, r);
  rMax = Math.max(rMax, r);
}
console.log('  拟合曲线：最小 ' + rMin.toFixed(1) + 'px（对角方向），最大 ' + rMax.toFixed(1) + 'px（正左右 / 正上下）');

// ---------- 4. 线宽 ----------

// 只在左右两条"直边"上量：环是圆角方形，直边只占竖向中间的一段；
// 沿半径扫一圈会被圆角带偏，也会在正上方撞上花萼。
// 直边是竖直的，所以"这一行落在环上的白像素个数"就等于线宽。
const straightHalf = HALF * 0.3;
let runSum = 0;
let runRows = 0;
for (let y = Math.round(CENTER_Y - straightHalf); y <= Math.round(CENTER_Y + straightHalf); y++) {
  let c = 0;
  for (let x = edgeLeft - 4; x <= edgeLeft + 300; x++) {
    if (x >= 0 && x < SRC_W && SRC_LUM[y * SRC_W + x] > 128) c++;
  }
  if (c > 20) {
    runSum += c;
    runRows++;
  }
}
const STROKE = runSum / runRows;
console.log('\n线宽：' + STROKE.toFixed(1) + 'px（沿左右直边逐行量，共 ' + runRows + ' 行）');

// ---------- 5. 由圆角半径推出画布尺寸 ----------

// 环是圆角方形：半宽 HALF、圆角半径 rc。最角上的点在 (HALF-rc, HALF-rc) 再往外 rc 处，
// 到圆心的距离是 (HALF-rc)*sqrt(2) + rc —— 这才是离圆心最远的地方，四条边不是。
// rc 由「对角方向量到的半径」反解：对角射线与外缘的交点满足
//   t^2 - sqrt(2)(HALF-rc) t + (HALF-rc)^2 - rc^2 = 0
const SQRT2 = Math.SQRT2;
const rDiag = radiusAt(coeffs, (-3 * Math.PI) / 4);
const disc = 2 * HALF * HALF - rDiag * rDiag;
if (disc <= 0) throw new Error('对角半径无法解释（' + rDiag + '），几何数据有问题');
const cornerRadius = (SQRT2 * HALF - Math.sqrt(disc)) / (SQRT2 - 1);
const farCorner = HALF - cornerRadius + cornerRadius * SQRT2;

const CANVAS = Math.round(farCorner / SAFE_RADIUS_RATIO);
const CENTER_OUT = CANVAS / 2;
const PASTE_X = Math.round(CENTER_OUT - CENTER_X);
const PASTE_Y = Math.round(CENTER_OUT - CENTER_Y);
const MASK_RADIUS_PX = CANVAS * SAFE_RADIUS_RATIO;

console.log('\n画布');
console.log('  源图 ' + SRC_W + 'x' + SRC_H + ' -> ' + CANVAS + 'x' + CANVAS + '（留白系数 ' + (CANVAS / (HALF * 2)).toFixed(2) + '）');
console.log('  圆角半径 ' + cornerRadius.toFixed(0) + 'px（半宽的 ' + ((cornerRadius / HALF) * 100).toFixed(0) + '%），环上离圆心最远 ' + farCorner.toFixed(1) + 'px');
console.log('  蒙版半径 ' + MASK_RADIUS_PX.toFixed(1) + 'px（安全区 ' + SAFE_DP + 'dp，余量 x' + SAFE_MARGIN + '）');

// ---------- 6. 合成 ----------

const OUT = Buffer.alloc(CANVAS * CANVAS * 4);
for (let i = 0; i < CANVAS * CANVAS; i++) OUT[i * 4 + 3] = 255; // 纯黑底

// 源图整体贴到中央（只平移，不缩放），并把黑底噪点压平：
// 源图的黑底亮度在 0..4 之间抖动，噪点的分布边界正好是源图边界，
// 不压平的话放大后会显出一道极淡的直角边。
const FLOOR = 24;
let floored = 0;
for (let y = 0; y < SRC_H; y++) {
  const ty = y + PASTE_Y;
  if (ty < 0 || ty >= CANVAS) continue;
  for (let x = 0; x < SRC_W; x++) {
    const tx = x + PASTE_X;
    if (tx < 0 || tx >= CANVAS) continue;
    const s = (y * SRC_W + x) * 4;
    const d = (ty * CANVAS + tx) * 4;
    let r = SRC_RGBA[s];
    let g = SRC_RGBA[s + 1];
    let b = SRC_RGBA[s + 2];
    if (Math.max(r, g, b) < FLOOR) {
      r = 0;
      g = 0;
      b = 0;
      floored++;
    }
    OUT[d] = r;
    OUT[d + 1] = g;
    OUT[d + 2] = b;
    OUT[d + 3] = SRC_RGBA[s + 3];
  }
}
console.log('  压平源图黑底噪点 ' + floored + ' 像素（阈值 ' + FLOOR + '）');

// 补画整圈环：用「到拟合曲线的径向距离」换算覆盖率做抗锯齿。
// 落在环带上 -> 白；源图那里已经有墨迹 -> 保留原像素（跳过）。
const AA = 1.2;
let painted = 0;
let skipped = 0;
for (let y = 0; y < CANVAS; y++) {
  const dy = y + 0.5 - CENTER_OUT;
  for (let x = 0; x < CANVAS; x++) {
    const dx = x + 0.5 - CENTER_OUT;
    const r = Math.hypot(dx, dy);
    if (r > rMax + AA + 2 || r < rMin - STROKE - AA - 2) continue;
    const th = Math.atan2(dy, dx);
    const rOuter = radiusAt(coeffs, th);
    const signed = Math.max(r - rOuter, rOuter - STROKE - r);
    if (signed > AA) continue;
    const coverage = signed <= -AA ? 1 : (AA - signed) / (2 * AA);
    const sx = Math.round(x - PASTE_X);
    const sy = Math.round(y - PASTE_Y);
    const srcHere = sx >= 0 && sx < SRC_W && sy >= 0 && sy < SRC_H ? SRC_LUM[sy * SRC_W + sx] : 0;
    if (srcHere > FLOOR) {
      skipped++;
      continue;
    }
    const v = Math.round(255 * coverage);
    const o = (y * CANVAS + x) * 4;
    OUT[o] = v;
    OUT[o + 1] = v;
    OUT[o + 2] = v;
    OUT[o + 3] = 255;
    if (coverage > 0.5) painted++;
  }
}
console.log('  补画 ' + painted + ' 个环像素，跳过 ' + skipped + ' 个已有墨迹的像素');

fs.writeFileSync(OUT_PNG, encodePng(CANVAS, OUT));
console.log('  写出 ' + path.relative(root, OUT_PNG) + '  ' + (fs.statSync(OUT_PNG).size / 1024).toFixed(0) + ' KB');

// ---------- 7. 校验 ----------

let inkMaxRadius = 0;
let inkMinX = CANVAS;
let inkMaxX = -1;
let inkMinY = CANVAS;
let inkMaxY = -1;
for (let y = 0; y < CANVAS; y++) {
  for (let x = 0; x < CANVAS; x++) {
    if (OUT[(y * CANVAS + x) * 4] > FLOOR) {
      if (x < inkMinX) inkMinX = x;
      if (x > inkMaxX) inkMaxX = x;
      if (y < inkMinY) inkMinY = y;
      if (y > inkMaxY) inkMaxY = y;
      const r = Math.hypot(x + 0.5 - CENTER_OUT, y + 0.5 - CENTER_OUT);
      if (r > inkMaxRadius) inkMaxRadius = r;
    }
  }
}

const padPx = Math.min(inkMinX, inkMinY, CANVAS - 1 - inkMaxX, CANVAS - 1 - inkMaxY);
console.log('\n校验');
console.log('  墨迹外接框 x ' + inkMinX + '..' + inkMaxX + '  y ' + inkMinY + '..' + inkMaxY + '，四周最少留白 ' + padPx + 'px');
console.log(padPx > 0 ? '  ok 墨迹完整落在画布内，不会再被画布本身切掉' : '  x 墨迹贴到画布边缘');

const inkDp = (inkMaxRadius / CANVAS) * CANVAS_DP;
const ringDp = ((HALF * 2) / CANVAS) * CANVAS_DP;
const marginDp = SAFE_DP / 2 - inkDp;
console.log('\n自适应图标换算（前景 ' + CANVAS_DP + 'dp 画布）');
console.log('  环外接框 ' + ringDp.toFixed(1) + 'dp；墨迹离圆心最远 ' + inkDp.toFixed(1) + 'dp（安全区半径 ' + SAFE_DP / 2 + 'dp）');
console.log(marginDp > 0
  ? '  ok 环整体落在安全区内，余量 ' + marginDp.toFixed(1) + 'dp —— 圆形蒙版不会再切到它'
  : '  x 环超出安全区 ' + (-marginDp).toFixed(1) + 'dp，圆形蒙版会切到它');
