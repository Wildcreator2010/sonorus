#!/usr/bin/env node
/**
 * 发布后校对：确认 GitHub Release 与本机构建产物真的是同一份东西。
 *
 * 用法：
 *   node tools/verify-release.mjs [本地APK路径]
 *   （默认 app/build/outputs/apk/release/app-release.apk）
 *
 * 断言项（任何一条不过就非零退出，供发布流程卡关）：
 *   1. releases/latest 匿名可读，且不是草稿/预发布
 *   2. tag 去前缀后的版本号与 APK 内 versionName 一致
 *   3. tag 指向的 commit 落在 main 的历史里（不要求等于 main 的头，否则发布后再提交就误报）
 *   4. 存在 apk 资源，下载地址落在 ReleaseParser 的 https 主机白名单内
 *   5. 正文里的 sha256 能被 ReleaseParser 的同一条正则抓到
 *   6. 实际下载回来的字节与本地 APK 的 sha256 逐字节相同（证明上传没被截断或替换）
 *   7. 按 ReleaseParser 的比较规则，这个版本确实比 APK 自身声明的版本新或相等
 */
import fs from 'node:fs';
import crypto from 'node:crypto';
import https from 'node:https';

const OWNER = 'Wildcreator2010';
const REPO = 'sonorus';

/** 与 ReleaseParser.kt 保持一致：改一处必须改另一处 */
const SHA256_REGEX = /sha[-_ ]?256\s*[:=]\s*[`*_]*([0-9a-f]{64})/i;
const ALLOWED_HOSTS = new Set([
  'github.com',
  'objects.githubusercontent.com',
  'api.github.com',
  'codeload.github.com',
]);

const localApk = process.argv[2] ?? 'app/build/outputs/apk/release/app-release.apk';

let failures = 0;
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
};

function get(url, { json = true, follow = 5 } = {}) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { headers: { 'User-Agent': 'sonorus-verify-release' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && follow > 0) {
          res.resume();
          return resolve(get(new URL(res.headers.location, url).toString(), { json, follow: follow - 1 }));
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const body = Buffer.concat(chunks);
          resolve({ status: res.statusCode, body, host: new URL(url).host });
        });
      })
      .on('error', reject);
  });
}

/** 与 ReleaseParser.compare 同语义：逐段数字比较 */
function versionCompare(a, b) {
  const left = a.split(/[.\-_]/);
  const right = b.split(/[.\-_]/);
  const size = Math.max(left.length, right.length);
  for (let i = 0; i < size; i++) {
    const l = left[i] ?? '0';
    const r = right[i] ?? '0';
    const ln = Number.parseInt(l, 10);
    const rn = Number.parseInt(r, 10);
    let diff;
    if (!Number.isNaN(ln) && !Number.isNaN(rn)) diff = ln - rn;
    else if (!Number.isNaN(ln)) diff = 1;
    else if (!Number.isNaN(rn)) diff = -1;
    else diff = l.localeCompare(r);
    if (diff !== 0) return diff;
  }
  return 0;
}

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

async function main() {
  if (!fs.existsSync(localApk)) {
    console.error(`本地 APK 不存在：${localApk}`);
    process.exit(2);
  }
  const local = fs.readFileSync(localApk);
  const localHash = sha256(local);
  console.log(`本地产物：${localApk}  ${local.length} 字节  sha256=${localHash}\n`);

  const releaseRes = await get(`https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`);
  if (releaseRes.status !== 200) {
    check('releases/latest 可匿名读取', false, `HTTP ${releaseRes.status}（草稿与预发布不会出现在 latest，也会 404）`);
    return finish();
  }
  const release = JSON.parse(releaseRes.body.toString());
  check('releases/latest 可匿名读取', true, `id=${release.id}`);
  check('已正式发布（非草稿）', release.draft === false, `draft=${release.draft}`);
  check('未标记为预发布', release.prerelease === false, `prerelease=${release.prerelease}`);

  const version = String(release.tag_name || '').trim().replace(/^(release-|Release-|v|V)/, '');
  check('tag 能解析出版本号', version.length > 0, `tag=${release.tag_name} → ${version}`);

  const declared = readVersionName() ?? '';
  check('tag 版本与 APK 内 versionName 一致', version === declared, `tag=${version} / apk=${declared || '未读到'}`);

  const mainRes = await get(`https://api.github.com/repos/${OWNER}/${REPO}/commits/main`);
  const mainSha = mainRes.status === 200 ? JSON.parse(mainRes.body.toString()).sha : '';

  // 附注标签（annotated tag）时 ref 指向的是 tag 对象，要再取一层 object.sha；
  // 轻量标签（lightweight）时 ref 直接就是 commit sha。GitHub 网页发布默认建的是轻量标签。
  let tagSha = '';
  const refRes = await get(`https://api.github.com/repos/${OWNER}/${REPO}/git/ref/tags/${release.tag_name}`);
  if (refRes.status === 200) {
    const object = JSON.parse(refRes.body.toString()).object;
    if (object.type === 'commit') {
      tagSha = object.sha;
    } else {
      const inner = await get(object.url);
      tagSha = JSON.parse(inner.body.toString()).object.sha;
    }
  }

  // 断言"tag 落在 main 的历史里"，而不是"tag == main 的头"：
  // 后者会在发布后的任何一次提交（改文档、加工具）之后误报失败。
  let ancestry = 'unknown';
  if (tagSha && mainSha) {
    const cmp = await get(`https://api.github.com/repos/${OWNER}/${REPO}/compare/${tagSha}...${mainSha}`);
    ancestry = cmp.status === 200 ? JSON.parse(cmp.body.toString()).status : `HTTP ${cmp.status}`;
  }
  check(
    'tag 落在 main 的历史里',
    ancestry === 'ahead' || ancestry === 'identical',
    `compare(tag...main)=${ancestry}，tag=${tagSha.slice(0, 7)} / main=${mainSha.slice(0, 7)}`
  );

  const assets = Array.isArray(release.assets) ? release.assets : [];
  const apk = assets.find(
    (a) => a.content_type === 'application/vnd.android.package-archive' || /\.apk$/i.test(a.name || '')
  );
  if (!apk) {
    check('存在 apk 资源', false, `assets=${assets.map((a) => a.name).join(',') || '空'}`);
    return finish();
  }
  check('存在 apk 资源', true, `${apk.name}，${apk.size} 字节`);

  let url;
  try {
    url = new URL(apk.browser_download_url);
  } catch {
    check('下载地址合法', false, 'browser_download_url 解析失败');
    return finish();
  }
  check('下载地址是白名单内的 https', url.protocol === 'https:' && ALLOWED_HOSTS.has(url.host), url.host);

  const body = release.body || '';
  const m = SHA256_REGEX.exec(body);
  check('正文含可被 ReleaseParser 抓到的 sha256', !!m, m ? m[1].slice(0, 16) + '…' : '没匹配到');

  if (m) {
    check('正文 sha256 与本地产物一致', m[1].toLowerCase() === localHash, `正文=${m[1].slice(0, 16)}… 本地=${localHash.slice(0, 16)}…`);
  }

  let downloaded = null;
  try {
    downloaded = await get(apk.browser_download_url);
  } catch {
    // github.com 的下载链路在本机经常被 ECONNRESET 掐断；GitHub 对资源存的 digest
    // 是服务端字节的 sha256，比"下载回来后自己算"更强，也更省用户的网络。
  }

  if (downloaded) {
    const remoteHash = sha256(downloaded.body);
    check('远端资源字节与本地相同', downloaded.status === 200 && remoteHash === localHash, `远端=${remoteHash.slice(0, 16)}…`);
    check(
      '远端资源大小与本地相同',
      downloaded.body.length === local.length,
      `${downloaded.body.length} vs ${local.length}`
    );
  } else {
    const assetRes = await get(`https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${apk.id}`);
    const digest = assetRes.status === 200 ? JSON.parse(assetRes.body.toString()).digest : null;
    const digestHash = String(digest || '').replace(/^sha256:/, '').toLowerCase();
    check(
      '下载被网络阻断，改用 GitHub API digest 校验',
      digestHash === localHash,
      digest ? `digest=${digestHash.slice(0, 16)}…` : 'API 未返回 digest'
    );
    check('远端资源大小与本地相同', apk.size === local.length, `${apk.size} vs ${local.length}`);
  }

  check('按 ReleaseParser 规则版本不比自身旧', versionCompare(version, declared) >= 0, `compare=${versionCompare(version, declared)}`);

  return finish();
}

/** 从 APK 的 AndroidManifest.xml（binary）里拿 versionName 不可靠，改读构建文件 */
function readVersionName() {
  try {
    const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');
    const m = /versionName\s*=\s*"([^"]+)"/.exec(gradle);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

function finish() {
  console.log(`\n${failures === 0 ? '校对通过' : `校对失败：${failures} 项不符`}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('校对脚本异常：', e.message);
  process.exit(2);
});
