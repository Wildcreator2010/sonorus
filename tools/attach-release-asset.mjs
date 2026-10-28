#!/usr/bin/env node
/**
 * 发布挂包：把本机构建的 release APK 作为资源挂到 GitHub Release 上并发布。
 *
 * 为什么需要这个脚本：GitHub 网页的附件上传白名单里**没有 .apk**
 * （选完文件只会得到 "We don't support that file type."，
 * 背后是 POST /upload/policies/assets 返回 422），而应用内更新要求 Release 带
 * content_type=application/vnd.android.package-archive 的资源 —— 只能走 uploads.github.com。
 *
 * 用法：
 *   set GH_TOKEN=<你的 token>
 *   node tools/attach-release-asset.mjs [--tag v0.1.4] [--apk 路径] [--no-publish]
 *
 * token 只从环境变量读：不写盘、不打日志、不进 git。
 * 幂等：同名资源先删再传，脚本可以反复跑；--no-publish 时停在草稿，便于先检查。
 *
 * 断言项（任何一条不过就非零退出）：
 *   1. --tag 与 app/build.gradle.kts 的 versionName 一致（防止把 A 版的包挂到 B 版说明上）
 *   2. Release 正文里的 sha256 与本地产物逐字节算出来的那个相同
 *   3. 上传后 GitHub 记录的 size 与 content_type 与本地一致
 *   4. 发布后 draft 确实变成 false
 */
import fs from 'node:fs';
import crypto from 'node:crypto';
import https from 'node:https';

const OWNER = 'Wildcreator2010';
const REPO = 'sonorus';
const APK_MIME = 'application/vnd.android.package-archive';
const ASSET_NAME = 'app-release.apk';

/** 与 ReleaseParser.kt、verify-release.mjs 同一条正则：改一处必须改另两处 */
const SHA256_REGEX = /sha[-_ ]?256\s*[:=]\s*[`*_]*([0-9a-f]{64})/i;

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};
const apkPath = option('--apk', 'app/build/outputs/apk/release/app-release.apk');
const shouldPublish = !args.includes('--no-publish');

let failures = 0;
const check = (label, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`);
  if (!ok) failures++;
  return ok;
};

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

function request(url, { method = 'GET', headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const req = https.request(
      {
        hostname: target.hostname,
        port: 443,
        path: target.pathname + target.search,
        method,
        headers: {
          'User-Agent': 'sonorus-attach-release-asset',
          Accept: 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28',
          ...headers,
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
      }
    );
    req.on('error', reject);
    // 用 end(body) 而不是 write：Node 会据此自动补 Content-Length
    req.end(body ?? undefined);
  });
}

const asJson = (res) => {
  try {
    return JSON.parse(res.body.toString());
  } catch {
    return null;
  }
};

/** 读构建文件而不是 APK 内的 binary AndroidManifest：后者要解 AXML，不可靠也没必要 */
function readVersionName() {
  try {
    const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');
    const m = /versionName\s*=\s*"([^"]+)"/.exec(gradle);
    return m ? m[1] : null;
  } catch {
    return null;
  }
}

async function main() {
  const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN;
  if (!token) {
    console.error('缺 GH_TOKEN（或 GITHUB_TOKEN）环境变量。脚本只从环境变量读它，不会写盘也不会打印。');
    process.exit(2);
  }
  if (!fs.existsSync(apkPath)) {
    console.error(`本地 APK 不存在：${apkPath}（先跑 run-apk.bat 出包）`);
    process.exit(2);
  }

  const local = fs.readFileSync(apkPath);
  const localHash = sha256(local);
  const declared = readVersionName();
  const tag = option('--tag', declared ? `v${declared}` : '');
  console.log(`本地产物：${apkPath}  ${local.length} 字节  sha256=${localHash}\n`);

  const tagMatches = check('--tag 与 build.gradle.kts 的 versionName 一致',
    !!tag && !!declared && tag === `v${declared}`,
    `tag=${tag || '未给'} / versionName=${declared || '未读到'}`);
  if (!tagMatches) return finish();

  const auth = { Authorization: `Bearer ${token}` };
  const listRes = await request(`https://api.github.com/repos/${OWNER}/${REPO}/releases?per_page=100`, { headers: auth });
  check('Release 列表读取成功', listRes.status === 200, `HTTP ${listRes.status}`);
  if (listRes.status !== 200) return finish();

  const release = (asJson(listRes) ?? []).find((r) => r.tag_name === tag);
  check(`找到 tag ${tag} 的 Release（草稿也算）`, !!release,
    release ? `id=${release.id} draft=${release.draft}` : 'GitHub 上没有这个 tag 的 Release：先在网页存草稿，或用 --tag 指定');
  if (!release) return finish();

  const bodyMatch = SHA256_REGEX.exec(release.body || '');
  const hashMatches = check('正文里的 sha256 与本地产物一致',
    !!bodyMatch && bodyMatch[1].toLowerCase() === localHash,
    bodyMatch ? `正文=${bodyMatch[1].slice(0, 16)}… 本地=${localHash.slice(0, 16)}…` : '正文没有 sha256 —— 应用内更新会拒装，先发正文补上');
  if (!hashMatches) return finish();

  const existing = (release.assets ?? []).find((a) => a.name === ASSET_NAME);
  if (existing) {
    const removed = await request(`https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${existing.id}`, {
      method: 'DELETE',
      headers: auth,
    });
    check('删掉同名旧资源（重传前的幂等清理）', removed.status === 204, `HTTP ${removed.status}`);
    if (removed.status !== 204) return finish();
  }

  const uploaded = await request(
    `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets?name=${encodeURIComponent(ASSET_NAME)}`,
    {
      method: 'POST',
      headers: { ...auth, 'Content-Type': APK_MIME },
      body: local,
    }
  );
  const asset = asJson(uploaded);
  check('apk 资源上传成功', uploaded.status === 201,
    `HTTP ${uploaded.status}${asset?.message ? '：' + asset.message : ''}`);
  if (uploaded.status !== 201) return finish();

  const sizeMatches = check('GitHub 记录的字节数与本地相同', asset.size === local.length, `${asset.size} vs ${local.length}`);
  const typeMatches = check('GitHub 记录的 content_type 是 apk 类型', asset.content_type === APK_MIME, asset.content_type);
  if (!sizeMatches || !typeMatches) return finish();
  console.log(`　下载地址：${asset.browser_download_url}`);

  if (shouldPublish) {
    const patched = await request(`https://api.github.com/repos/${OWNER}/${REPO}/releases/${release.id}`, {
      method: 'PATCH',
      headers: { ...auth, 'Content-Type': 'application/json' },
      body: Buffer.from(JSON.stringify({ draft: false })),
    });
    check('草稿已正式发布', patched.status === 200 && asJson(patched)?.draft === false, `HTTP ${patched.status}`);
  } else {
    console.log('　（--no-publish：资源已挂上，Release 仍是草稿）');
  }

  return finish();
}

function finish() {
  if (failures === 0) {
    console.log('\n挂包完成。接着跑发布校对：node tools/verify-release.mjs');
  } else {
    console.log(`\n未完成：${failures} 项不符。修掉再跑，脚本是幂等的。`);
  }
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('挂包脚本异常：', e.message);
  process.exit(2);
});
