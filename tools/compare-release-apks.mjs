#!/usr/bin/env node
/**
 * 两份 release APK 的功能指纹对比：判断"除了刻意改的地方，行为是否一致"。
 *
 * 为什么比 DEX 字符串而不是比字节：release 开了 R8，类名会被混淆、行号表会变，
 * 逐字节或逐类对比必然全是噪声；而字符串常量（中文文案、协议字段名、工具名、
 * 备份容器 magic）不受混淆影响，差集能直接指到"哪个功能变了"。
 *
 * 用法：
 *   node tools/compare-release-apks.mjs <A.apk> <B.apk>
 *
 * 输出约定与 tools/verify-release.mjs 一致：每行 PASS/FAIL，任何 FAIL 则非零退出。
 * 它只回答"两份包的内容差异是什么"，不回答"在老机器上跑不跑得动"—— 后者要真机。
 */
import fs from 'node:fs';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

const [apkA, apkB] = process.argv.slice(2);
if (!apkA || !apkB) {
  console.error('用法: node tools/compare-release-apks.mjs <A.apk> <B.apk>');
  process.exit(2);
}

let failures = 0;
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
};

/** 最小 ZIP 读取：只够把 classes*.dex 取出来，不引第三方依赖 */
function readEntries(apkPath) {
  const buf = fs.readFileSync(apkPath);
  // 从尾部找 EOCD（0x06054b50），签名允许带 comment
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66_000); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`${apkPath}: 找不到 ZIP 目录结尾记录`);
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error(`${apkPath}: 中央目录结构异常`);
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    // 本地头里的 name/extra 长度可以与中央目录不同，必须就地再读一次
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lNameLen + lExtraLen;
    const raw = buf.subarray(dataStart, dataStart + compSize);
    entries.push({ name, method, raw, compSize });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return { buf, entries };
}

const dexBytes = (z) => z.entries
  .filter(e => /^classes\d*\.dex$/.test(e.name))
  .map(e => (e.method === 0 ? e.raw : zlib.inflateRawSync(e.raw)));

/**
 * 按 DEX 头部的 string_ids 表逐条取出字符串，而不是对整块字节做正则刮取：
 * 刮取会把类型描述符、字段名切成碎片，差集里全是无法解释的噪声。
 */
function dexStrings(dex) {
  const stringIdsSize = dex.readUInt32LE(0x38);
  const stringIdsOff = dex.readUInt32LE(0x3c);
  const out = [];
  const uleb = (p) => {
    let v = 0, shift = 0, i = p;
    for (; ; i++) {
      const b = dex[i];
      v |= (b & 0x7f) << shift;
      if ((b & 0x80) === 0) break;
      shift += 7;
    }
    return { value: v, next: i + 1 };
  };
  for (let s = 0; s < stringIdsSize; s++) {
    const dataOff = dex.readUInt32LE(stringIdsOff + s * 4);
    const { next } = uleb(dataOff);
    let end = next;
    while (dex[end] !== 0x00) end++;
    // MUTF-8：中文等 BMP 字符与 UTF-8 同码，够用；0xC0 0x80 的真零字节不参与本项目字符串
    out.push(dex.toString('utf8', next, end));
  }
  return out;
}

const load = (path) => {
  const z = readEntries(path);
  const strings = new Set();
  for (const dex of dexBytes(z)) for (const s of dexStrings(dex)) strings.add(s);
  const zipSha = crypto.createHash('sha256').update(z.buf).digest('hex');
  return { strings, size: z.buf.length, sha256: zipSha };
};

const A = load(apkA);
const B = load(apkB);

console.log(`A = ${apkA}`);
console.log(`B = ${apkB}`);
console.log(`    大小 ${A.size} / ${B.size}，字符串 ${A.strings.size} / ${B.strings.size}`);
console.log(`    sha256 A=${A.sha256}`);
console.log(`    sha256 B=${B.sha256}`);

const onlyA = [...A.strings].filter(s => !B.strings.has(s));
const onlyB = [...B.strings].filter(s => !A.strings.has(s));

// 中文文案是用户能直接看见的东西：两份包必须一字不差
const cjk = (s) => /[\u3400-\u9fff]/.test(s);
const cjkOnlyA = onlyA.filter(cjk);
const cjkOnlyB = onlyB.filter(cjk);
check('中文文案完全一致', cjkOnlyA.length === 0 && cjkOnlyB.length === 0,
  cjkOnlyA.length + cjkOnlyB.length === 0 ? `${A.strings.size} 条字符串里所有中文两边都有`
    : `仅 A: ${JSON.stringify(cjkOnlyA)} | 仅 B: ${JSON.stringify(cjkOnlyB)}`);

// 功能指纹：三套协议、Agent 工具、备份容器、应用内更新，任何一条在某一侧缺失都说明功能缩水
const FINGERPRINTS = [
  ['OpenAI 兼容端点', 'chat/completions'],
  ['Anthropic 端点', 'messages'],
  ['Gemini 端点', 'generativelanguage'],
  ['思考档位', 'reasoning_effort'],
  ['时间工具', 'get_current_time'],
  ['备份容器 magic', 'SONORUSBAK1'],
  ['PBKDF2 算法名', 'PBKDF2WithHmacSHA256'],
  ['SSE 分帧', 'data:'],
  ['更新接口', 'releases/latest'],
];
for (const [label, needle] of FINGERPRINTS) {
  const hit = (set) => [...set].some(s => s.includes(needle));
  check(`功能指纹「${label}」两侧都在`, hit(A.strings) && hit(B.strings),
    `A=${hit(A.strings)} B=${hit(B.strings)} (${needle})`);
}

console.log('');
console.log(`非中文差异字符串：仅 A ${onlyA.filter(s => !cjk(s)).length} 条 / 仅 B ${onlyB.filter(s => !cjk(s)).length} 条`);
for (const s of onlyA.filter(x => !cjk(x))) console.log(`  仅 A: ${JSON.stringify(s)}`);
for (const s of onlyB.filter(x => !cjk(x))) console.log(`  仅 B: ${JSON.stringify(s)}`);
console.log('');
console.log(failures === 0 ? '结论：除上面列出的差异外，两份包的功能字符串一致' : `结论：${failures} 项不通过`);
process.exit(failures === 0 ? 0 : 1);
