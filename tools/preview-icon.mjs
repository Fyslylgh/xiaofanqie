// 校验启动图标并打印 ASCII 预览。
//
// 直接解析 res/drawable/ic_launcher_foreground.xml 里的 pathData，而不是在脚本里抄一份坐标——
// 抄一份的话脚本永远是对的，校验不到任何东西。
//
// 为什么要校验：手写的 SVG 圆弧路径写错了不会报错，只会渲染成一片空白，
// 而图标是启动器里第一眼看到的东西，等到装机才发现就太晚了。
//
// 用法: node tools/preview-icon.mjs

// 可以带上文件路径，默认校验启动图标：
//   node tools/preview-icon.mjs
//   node tools/preview-icon.mjs app/src/main/res/drawable/ic_app_mark.xml

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ICON = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(root, 'app/src/main/res/drawable/ic_launcher_foreground.xml');

const COLS = 54;
const ROWS = 54;

// 视口尺寸从 XML 里读，这样同一套工具既能校验 108dp 的启动图标，
// 也能校验 24dp 的通知/磁贴标记
let VIEW = 108;

// ---------- 解析 ----------

const xml = fs.readFileSync(ICON, 'utf8');

/** 取出每个 <path> 的 pathData、描边宽度、是否有描边。 */
function parsePaths(source) {
  const out = [];
  for (const m of source.matchAll(/<path\b([\s\S]*?)\/>/g)) {
    const attrs = m[1];
    const pick = (name) => {
      const r = new RegExp(`android:${name}="([^"]*)"`).exec(attrs);
      return r ? r[1] : null;
    };
    const data = pick('pathData');
    if (!data) continue;
    out.push({
      data,
      strokeWidth: Number(pick('strokeWidth') || 0),
      filled: pick('fillColor') != null,
      stroked: pick('strokeColor') != null,
    });
  }
  return out;
}

/** SVG 圆弧的端点参数化 -> 圆心参数化（F.6.5 / F.6.6）。 */
function arcToCenter(x1, y1, rx, ry, phiDeg, largeArc, sweep, x2, y2) {
  const phi = (phiDeg * Math.PI) / 180;
  const cosP = Math.cos(phi);
  const sinP = Math.sin(phi);
  const dx2 = (x1 - x2) / 2;
  const dy2 = (y1 - y2) / 2;
  const x1p = cosP * dx2 + sinP * dy2;
  const y1p = -sinP * dx2 + cosP * dy2;

  const lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  let rxx = rx;
  let ryy = ry;
  if (lambda > 1) {
    const s = Math.sqrt(lambda);
    rxx = s * rx;
    ryy = s * ry;
  }

  const sign = largeArc !== sweep ? 1 : -1;
  const num = rxx * rxx * ryy * ryy - rxx * rxx * y1p * y1p - ryy * ryy * x1p * x1p;
  const den = rxx * rxx * y1p * y1p + ryy * ryy * x1p * x1p;
  const co = sign * Math.sqrt(Math.max(0, num / den));
  const cxp = (co * rxx * y1p) / ryy;
  const cyp = (-co * ryy * x1p) / rxx;

  const cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2;
  const cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2;

  const angle = (ux, uy, vx, vy) => {
    const dot = ux * vx + uy * vy;
    const len = Math.hypot(ux, uy) * Math.hypot(vx, vy);
    let a = Math.acos(Math.min(1, Math.max(-1, dot / len)));
    if (ux * vy - uy * vx < 0) a = -a;
    return a;
  };

  const ux = (x1p - cxp) / rxx;
  const uy = (y1p - cyp) / ryy;
  const vx = (-x1p - cxp) / rxx;
  const vy = (-y1p - cyp) / ryy;

  const theta1 = angle(1, 0, ux, uy);
  let delta = angle(ux, uy, vx, vy);
  if (!sweep && delta > 0) delta -= 2 * Math.PI;
  if (sweep && delta < 0) delta += 2 * Math.PI;

  return { cx, cy, rx: rxx, ry: ryy, theta1, delta };
}

/** 把 pathData 拆成图元：折线、圆弧、整圆。只支持本图标用到的几种命令。 */
function buildShapes(p) {
  const shapes = [];
  const tokens = p.data.match(/[MLAaZ]|-?\d*\.?\d+/g) || [];
  let i = 0;
  let cursor = { x: 0, y: 0 };
  let start = null;
  let polyline = [];

  const flush = () => {
    if (polyline.length >= 2) {
      shapes.push({ type: 'stroke', points: polyline, w: p.strokeWidth });
    }
    polyline = [];
  };

  const num = () => Number(tokens[i++]);
  // 记录本子路径里连续两段 a 圆弧，用于识别"整圆"
  let circles = [];

  while (i < tokens.length) {
    const cmd = tokens[i];
    if (/[A-Za-z]/.test(cmd)) i++;

    if (cmd === 'M') {
      flush();
      circles = [];
      cursor = { x: num(), y: num() };
      start = { ...cursor };
      polyline = [{ ...cursor }];
    } else if (cmd === 'L') {
      cursor = { x: num(), y: num() };
      polyline.push({ ...cursor });
    } else if (cmd === 'A') {
      const rx = num();
      const ry = num();
      const rot = num();
      const large = num();
      const sweep = num();
      const x = num();
      const y = num();
      const arc = arcToCenter(cursor.x, cursor.y, rx, ry, rot, large, sweep, x, y);
      shapes.push({ type: 'arc', ...arc, w: p.strokeWidth, x1: cursor.x, y1: cursor.y, x2: x, y2: y });
      circles.push({ rx, ry });
      if (circles.length === 2 && circles[0].rx === circles[1].rx) {
        // 两段等半径半圆拼成一个整圆：填充圆点
        shapes.push({ type: 'dot', cx: (cursor.x + x) / 2, cy: (cursor.y + y) / 2, r: rx });
      }
      cursor = { x, y };
      polyline.push({ ...cursor });
    } else if (cmd === 'a') {
      const rx = num();
      const ry = num();
      const rot = num();
      const large = num();
      const sweep = num();
      const dx = num();
      const dy = num();
      const x = cursor.x + dx;
      const y = cursor.y + dy;
      const arc = arcToCenter(cursor.x, cursor.y, rx, ry, rot, large, sweep, x, y);
      shapes.push({ type: 'arc', ...arc, w: p.strokeWidth, x1: cursor.x, y1: cursor.y, x2: x, y2: y });
      circles.push({ rx, ry });
      if (circles.length === 2 && circles[0].rx === circles[1].rx) {
        shapes.push({ type: 'dot', cx: (cursor.x + x) / 2, cy: (cursor.y + y) / 2, r: rx });
      }
      cursor = { x, y };
    } else if (cmd === 'Z' || cmd === 'z') {
      if (start) polyline.push({ ...start });
      flush();
    } else {
      // 未知命令，跳过一个参数以免死循环
      num();
    }
  }
  flush();
  return shapes;
}

// ---------- 光栅化 ----------

const distToSegment = (px, py, ax, ay, bx, by) => {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
};

function covers(shape, px, py) {
  if (shape.type === 'dot') {
    return Math.hypot(px - shape.cx, py - shape.cy) <= shape.r;
  }
  if (shape.type === 'stroke') {
    const pts = shape.points;
    for (let k = 0; k + 1 < pts.length; k++) {
      if (distToSegment(px, py, pts[k].x, pts[k].y, pts[k + 1].x, pts[k + 1].y) <= shape.w / 2) {
        return true;
      }
    }
    return false;
  }
  if (shape.type === 'arc') {
    const { cx, cy, rx, theta1, delta } = shape;
    const d = Math.hypot(px - cx, py - cy);
    let ang = Math.atan2(py - cy, px - cx) - theta1;
    const span = Math.abs(delta);
    while (ang < 0) ang += 2 * Math.PI;
    while (ang >= 2 * Math.PI) ang -= 2 * Math.PI;
    if (ang <= span) return Math.abs(d - rx) <= shape.w / 2;
    // 端点之外：只算圆头端帽
    return Math.hypot(px - shape.x1, py - shape.y1) <= shape.w / 2 ||
      Math.hypot(px - shape.x2, py - shape.y2) <= shape.w / 2;
  }
  return false;
}

// ---------- 主流程 ----------

const paths = parsePaths(xml);
const shapes = paths.flatMap(buildShapes);

const viewport = /android:viewportWidth="([\d.]+)"/.exec(xml);
if (viewport) VIEW = Number(viewport[1]);

console.log(`解析 ${path.relative(root, ICON)}`);
console.log(`  viewport ${VIEW}   path 数: ${paths.length}，图元数: ${shapes.length}`);

// 圆心参数化校验：所有圆弧的圆心应当一致（果身与蒂共心不算，这里只报告）
const arcs = shapes.filter((s) => s.type === 'arc');
console.log('\n圆弧解析结果:');
for (const a of arcs) {
  const sweepDeg = (a.delta * 180) / Math.PI;
  console.log(
    `  圆心(${a.cx.toFixed(2)}, ${a.cy.toFixed(2)})  半径 ${a.rx.toFixed(2)}  ` +
      `起始 ${((a.theta1 * 180) / Math.PI).toFixed(1)}°  扫过 ${sweepDeg.toFixed(1)}°`,
  );
}

// 墨迹范围与安全区
// 注意：不能用包围盒的四角去算离中心多远——圆环的包围盒四角上根本没有墨迹，
// 那样会得出 42 这种把图案本身吓一跳的数字。这里统计的是真实着墨像素。
let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
let maxInkDist = 0;
for (let row = 0; row < ROWS * 4; row++) {
  for (let col = 0; col < COLS * 4; col++) {
    const x = ((col + 0.5) / (COLS * 4)) * VIEW;
    const y = ((row + 0.5) / (ROWS * 4)) * VIEW;
    if (shapes.some((s) => covers(s, x, y))) {
      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minY = Math.min(minY, y); maxY = Math.max(maxY, y);
      maxInkDist = Math.max(maxInkDist, Math.hypot(x - 54, y - 54));
    }
  }
}
console.log(`\n墨迹范围: x ${minX.toFixed(1)}..${maxX.toFixed(1)}  y ${minY.toFixed(1)}..${maxY.toFixed(1)}`);

// 安全区只对自适应图标有意义（108dp 视口）；24dp 的标记整块都是图标，没有这个约束
const SAFE_RADIUS = 36;
const center = VIEW / 2;
const maxInkDistFromCenter = maxInkDist;
if (VIEW === 108) {
  console.log(`  着墨像素距中心最远 ${maxInkDistFromCenter.toFixed(1)}（自适应图标安全区半径 ${SAFE_RADIUS}）`);
}
const safe = VIEW !== 108 || maxInkDistFromCenter <= SAFE_RADIUS;

// ASCII 预览
const grid = Array.from({ length: ROWS }, () => Array(COLS).fill(' '));
for (let row = 0; row < ROWS; row++) {
  for (let col = 0; col < COLS; col++) {
    const x = ((col + 0.5) / COLS) * VIEW;
    const y = ((row + 0.5) / ROWS) * VIEW;
    if (shapes.some((s) => covers(s, x, y))) grid[row][col] = '#';
  }
}
console.log('\n预览（# 为白色图案，空白为黑色底）:');
console.log('+' + '-'.repeat(COLS) + '+');
for (const row of grid) console.log('|' + row.join('') + '|');
console.log('+' + '-'.repeat(COLS) + '+');

console.log(safe ? '\n✓ 形状解析成功，且落在安全区内' : '\n✗ 超出安全区，蒙版可能切到图案');
process.exit(safe ? 0 : 1);
