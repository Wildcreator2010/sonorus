import http from 'node:http';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

export function createFixtureServer() {
  const requests = [];
  const server = http.createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/v1/models') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ data: ['fixture-stream', 'fixture-json', 'fixture-slow'].map(id => ({ id })) }));
      return;
    }
    if (req.method !== 'POST' || req.url !== '/v1/chat/completions') {
      res.writeHead(404).end();
      return;
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 4 * 1024 * 1024) {
        res.writeHead(413).end();
        return;
      }
      chunks.push(chunk);
    }
    let payload;
    try { payload = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { res.writeHead(400).end(); return; }
    requests.push(payload);
    const question = payload.messages?.findLast(message => message.role === 'user')?.content;
    const content = `本地验收回答：${typeof question === 'string' ? question : JSON.stringify(question)}`;
    if (payload.model === 'fixture-json') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content }, finish_reason: 'stop' }] }));
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
    res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: '开始回答。' } }] })}\n\n`);
    const finish = () => {
      res.end(`data: ${JSON.stringify({ choices: [{ delta: { content }, finish_reason: 'stop' }] })}\n\ndata: [DONE]\n\n`);
    };
    if (payload.model === 'fixture-slow') {
      const timer = setTimeout(finish, 60_000);
      res.on('close', () => clearTimeout(timer));
    } else finish();
  });
  return { server, requests };
}

async function selfTest() {
  const { server, requests } = createFixtureServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/v1`;
  try {
    const models = await (await fetch(`${base}/models`)).json();
    assert.equal(models.data.length, 3);
    const response = await fetch(`${base}/chat/completions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: 'fixture-json', messages: [{ role: 'user', content: '附件验收' }] }),
    });
    assert.equal((await response.json()).choices[0].message.content, '本地验收回答：附件验收');
    assert.equal(requests.length, 1);
    const controller = new AbortController();
    const slow = await fetch(`${base}/chat/completions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
      body: JSON.stringify({ model: 'fixture-slow', messages: [{ role: 'user', content: '取消验收' }] }),
    });
    const reader = slow.body.getReader();
    assert.match(new TextDecoder().decode((await reader.read()).value), /开始回答/);
    controller.abort();
    console.log('本地接口验收服务自测通过');
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.includes('--self-test')) await selfTest();
  else {
    const port = Number(process.env.PORT || 18765);
    const { server } = createFixtureServer();
    server.listen(port, '127.0.0.1', () => console.log(`本地验收接口 http://127.0.0.1:${port}/v1`));
  }
}
