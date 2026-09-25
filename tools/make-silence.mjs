// 生成一段静音 WAV，放进 res/raw 供 MediaPlayer 循环播放。
//
// 用途：让计时器拥有一个真实的媒体会话，从而出现在锁屏播放器 / 各家系统的音乐流体云里。
// 会话必须是"真的在播放"才会被系统当成活跃媒体，所以需要一条实际的音轨——内容是静音。
//
// 用法: node tools/make-silence.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'app', 'src', 'main', 'res', 'raw', 'silence.wav');

const SAMPLE_RATE = 8000;   // 够用即可，采样率越低越省电
const CHANNELS = 1;
const BITS = 16;
const SECONDS = 2;          // 短一点，循环开销可忽略；再长只是白占体积

const frames = SAMPLE_RATE * SECONDS;
const dataBytes = frames * CHANNELS * (BITS / 8);
const byteRate = SAMPLE_RATE * CHANNELS * (BITS / 8);
const blockAlign = CHANNELS * (BITS / 8);

const header = Buffer.alloc(44);
header.write('RIFF', 0, 'ascii');
header.writeUInt32LE(36 + dataBytes, 4);
header.write('WAVE', 8, 'ascii');
header.write('fmt ', 12, 'ascii');
header.writeUInt32LE(16, 16);            // fmt chunk 大小
header.writeUInt16LE(1, 20);             // PCM
header.writeUInt16LE(CHANNELS, 22);
header.writeUInt32LE(SAMPLE_RATE, 24);
header.writeUInt32LE(byteRate, 28);
header.writeUInt16LE(blockAlign, 32);
header.writeUInt16LE(BITS, 34);
header.write('data', 36, 'ascii');
header.writeUInt32LE(dataBytes, 40);

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, Buffer.concat([header, Buffer.alloc(dataBytes)]));

console.log(`已写入 ${path.relative(root, out)}`);
console.log(`  ${SAMPLE_RATE} Hz / ${CHANNELS} 声道 / ${BITS} bit / ${SECONDS} 秒`);
console.log(`  ${(dataBytes / 1024).toFixed(0)} KB 静音数据`);
