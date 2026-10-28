import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const adb = process.env.ADB || path.join(process.env.LOCALAPPDATA, 'Android/Sdk/platform-tools/adb.exe');
const serial = process.env.ANDROID_SERIAL || 'emulator-5554';
const run = (...args) => execFileSync(adb, ['-s', serial, ...args], { timeout: 20000 });
const [action = 'dump', value, matchIndex = '0'] = process.argv.slice(2);
if (action === 'screenshot') {
  assert.ok(value, '需要输出 PNG 路径');
  writeFileSync(value, run('exec-out', 'screencap', '-p'));
} else if (action === 'input') {
  assert.ok(value, '需要输入文字（adb input text 适用于 ASCII）');
  assert.match(value, /^[a-zA-Z0-9 .,:/_?=+@\-]+$/, '输入仅用于固定验收数据');
  run('shell', 'input', 'text', value.replaceAll(' ', '%s'));
} else {
  run('shell', 'uiautomator', 'dump', '/sdcard/sonorus-ui.xml');
  const xml = run('exec-out', 'cat', '/sdcard/sonorus-ui.xml').toString();
  const decode = text => text.replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
  const nodes = [...xml.matchAll(/<node\s+([^>]+)>/g)].map(match => Object.fromEntries(
    [...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(([, key, val]) => [key, decode(val)])
  ));
  if (action === 'dump') {
    console.log(nodes.filter(node => node.text || node['content-desc'] || node.class?.endsWith('EditText')).map(node => ({
      text: node.text, label: node['content-desc'], class: node.class, bounds: node.bounds, enabled: node.enabled,
    })));
  } else {
    const matches = nodes.filter(item => item.text === value || item['content-desc'] === value);
    const node = matches[Number(matchIndex)];
    assert.ok(node, `找不到界面节点：${value}（第 ${matchIndex} 个）`);
    if (action === 'tap') {
      const [, left, top, right, bottom] = node.bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
      run('shell', 'input', 'tap', String(Math.floor((Number(left) + Number(right)) / 2)), String(Math.floor((Number(top) + Number(bottom)) / 2)));
    } else if (action === 'assert') console.log(`界面断言通过：${value}`);
    else throw new Error(`未知操作：${action}`);
  }
}
