#!/usr/bin/env node
/**
 * 发预发布（prerelease）版：建 Release、挂 APK、逐条回读校验。
 *
 * 为什么不复用 attach-release-asset.mjs：那个脚本的闸门要求 `tag === v{versionName}`
 * 且只能给已存在的 Release 挂包、发布时强制 draft=false 的正式版语义。
 * 预发布包（如 v0.1.6-compat）的 tag 天生带后缀、且必须保持 prerelease，
 * 所以单独走一条路，原脚本的四道闸门一条不动。
 *
 * 预发布不会抢走 releases/latest —— GitHub 的 latest 端点跳过 prerelease，
 * 所以现有正式版用户的应用内更新链不受影响；这一点是断言 6，不是假设。
 *
 * 用法：
 *   set GH_TOKEN=<你的 token>
 *   node tools/publish-prerelease.mjs --tag v0.1.6-compat [--commit <sha>] [--notes 正文] [--dry]
 *
 * token 只从环境变量读：不写盘、不打日志、不进 git。
 * 幂等：同名 Release 已存在则复用；同名资源先删再传，可反复跑。
 */
import fs from 'node:fs';
import crypto from 'node:crypto';
import https from 'node:https';

const OWNER = 'Wildcreator2010';
const REPO = 'sonorus';
const APK_MIME = 'application/vnd.android.package-archive';
const ASSET_NAME = 'app-release.apk';

/** 与 ReleaseParser.kt、verify-release.mjs、attach-release-asset.mjs 同一条正则：改一处必须改另四处 */
const SHA256_REGEX = /sha[-_ ]?256\s*[:=]\s*[`*_]*([0-9a-f]{64})/i;

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const flag = (name) => args.includes(name);

const tag = option('--tag', '');
const apkPath = option('--apk', 'app/build/outputs/apk/release/app-release.apk');
const commitish = option('--commit', 'HEAD');
const notes = option('--notes', '');
const dry = flag('--dry');

let failures = 0;
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
};
const die = (msg) => { console.error(`✗ ${msg}`); process.exit(1); };

// region 本地断言：先把"这个包配这个 tag"钉死，再谈联网
const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');
const versionName = (/versionName\s*=\s*"([^"]+)"/.exec(gradle) ?? [])[1] ?? '';
const versionCode = (/versionCode\s*=\s*(\d+)/.exec(gradle) ?? [])[1] ?? '';

if (!tag) die('必须给 --tag，例如 --tag v0.1.6-compat');
if (!fs.existsSync(apkPath)) die(`APK 不存在：${apkPath}`);

const apk = fs.readFileSync(apkPath);
const sha256 = crypto.createHash('sha256').update(apk).digest('hex');

// 带后缀才叫预发布：不带后缀就该走 attach-release-asset.mjs 的正式链
const tagVersion = tag.replace(/^v/, '');
const suffix = tagVersion.startsWith(versionName) ? tagVersion.slice(versionName.length) : null;
check('tag 是本机 versionName 的带后缀变体', suffix !== null && suffix.startsWith('-'),
  `tag=${tag} / versionName=${versionName} / versionCode=${versionCode}`);
check('APK 体积可读且非空', apk.length > 1000, `${apk.length} 字节`);

const body = [
  notes || `Android 6.0–7.x 兼容包（minSdk 23）。功能与 v${versionName} 正式版一致，只是把最低版本下调了。`,
  '',
  '本机构建产物直传，核对用：',
  `sha256: ${sha256}`,
  '',
  `versionName ${versionName} / versionCode ${versionCode} / 构建提交 ${commitish}`,
].join('\n');
check('正文里的 sha256 能被应用内更新那条正则抓到', SHA256_REGEX.test(body),
  SHA256_REGEX.exec(body)?.[1] === sha256 ? '抓到的值与本地一致' : '抓到的值与本地不一致');

if (dry) {
  console.log('');
  console.log(failures === 0 ? '结论：本地断言全过（--dry 未联网）' : `结论：${failures} 项不通过`);
  process.exit(failures === 0 ? 0 : 1);
}

const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN;
if (!token) die('缺 GH_TOKEN（或 GITHUB_TOKEN）环境变量。脚本只从环境变量读它，不会写盘也不会打印。');
// endregion

// region 最小 GitHub 客户端
function request(method, url, { headers = {}, body: payload = null } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = https.request({
      method,
      hostname: u.hostname,
      path: u.pathname + u.search,
      headers: {
        'User-Agent': 'sonorus-publish-prerelease',
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        ...headers,
      },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        text: Buffer.concat(chunks).toString('utf8'),
        bytes: Buffer.concat(chunks),
      }));
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}
const api = (method, path, payload = null, extra = {}) => request(method, `https://api.github.com${path}`, {
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra },
  body: payload ? Buffer.from(JSON.stringify(payload)) : null,
});
const asJson = (r) => { try { return JSON.parse(r.text); } catch { return null; } };
// endregion

// 1. 同名 Release：存在就必须本来就是预发布，否则是在篡改正式版
let list = asJson(await api('GET', `/repos/${OWNER}/${REPO}/releases?per_page=100`)) ?? [];
const existing = list.find((r) => r.tag_name === tag);
if (existing) {
  check(`已有的 ${tag} Release 是预发布（不是正式版被改写）`, existing.prerelease === true,
    `id=${existing.id} draft=${existing.draft} prerelease=${existing.prerelease}`);
}

// 2. 建 Release（或复用），并确认 GitHub 落库后的形态
let release = existing;
if (!existing) {
  const created = await api('POST', `/repos/${OWNER}/${REPO}/releases`, {
    tag_name: tag,
    target_commitish: commitish,
    name: `v${tagVersion} 预发布（Android 6.0 起）`,
    body,
    draft: false,
    prerelease: true,
  });
  release = asJson(created);
  check(`创建 Release（HTTP ${created.status}）`, created.status === 201 && !!release?.id,
    release?.id ? `id=${release.id}` : created.text.slice(0, 300));
}
if (!release?.id) die('拿不到 Release id，后面没法挂包');

const after = asJson(await api('GET', `/repos/${OWNER}/${REPO}/releases/${release.id}`));
check('GitHub 上这个 Release 确实是预发布', after?.prerelease === true, `prerelease=${after?.prerelease}`);
check('不是草稿（已对外可见）', after?.draft === false, `draft=${after?.draft}`);
check('正文落库后仍带着本地 sha256', SHA256_REGEX.test(after?.body ?? '') &&
  SHA256_REGEX.exec(after.body)[1] === sha256, SHA256_REGEX.exec(after?.body ?? '')?.[1]?.slice(0, 12) ?? '没抓到');

// 3. 预发布不许抢走 latest —— 抢了就会把老用户的更新链换到这条包上
const latest = asJson(await api('GET', `/repos/${OWNER}/${REPO}/releases/latest`));
check('releases/latest 没有被这个预发布抢走', !!latest && latest.tag_name !== tag,
  `latest 仍是 ${latest?.tag_name ?? '(读不到)'}`);

// 4. 挂包：同名先删再传，保证幂等
for (const a of (after?.assets ?? []).filter((a) => a.name === ASSET_NAME)) {
  await api('DELETE', `/repos/${OWNER}/${REPO}/releases/assets/${a.id}`);
}
const up = await request('POST',
  `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets?name=${ASSET_NAME}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': APK_MIME,
      'Content-Length': String(apk.length),
    },
    body: apk,
  });
const asset = asJson(up);
check(`上传 ${ASSET_NAME}（HTTP ${up.status}）`, up.status === 201 && !!asset?.id,
  asset?.id ? `id=${asset.id} size=${asset.size}` : up.text.slice(0, 300));
check('GitHub 记录的 size 与本地一致', asset?.size === apk.length, `远端=${asset?.size} 本地=${apk.length}`);
check('content_type 是安卓安装包', /android\.package-archive/.test(asset?.content_type ?? ''), asset?.content_type);

// 5. 下载回来逐字节比对：证明传输没被截断或替换
const dl = await request('GET', asset.browser_download_url);
const redirectTarget = dl.status >= 300 && dl.status < 400 ? dl.headers.location : null;
const downloaded = redirectTarget ? (await request('GET', redirectTarget)).bytes : dl.bytes;
const dlSha = crypto.createHash('sha256').update(downloaded).digest('hex');
check('下载回来的字节与本地逐字节相同', downloaded.length === apk.length && dlSha === sha256,
  `下载 ${downloaded.length} 字节，sha256 ${dlSha === sha256 ? '一致' : `不一致（${dlSha.slice(0, 12)}…）`}`);

console.log('');
console.log(`下载地址：${asset?.browser_download_url}`);
console.log(failures === 0 ? '结论：预发布已发布并可下载' : `结论：${failures} 项不通过`);
process.exit(failures === 0 ? 0 : 1);
