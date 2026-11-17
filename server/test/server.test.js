'use strict';
// Integration tests: start the real server.js as a child process and talk to it over HTTP / WebSocket.
// Run: npm test   (needs only the dev machine, no Android phone)
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const WebSocket = require('ws');

const PORT = 18000 + Math.floor(Math.random() * 1000);
const BASE = `http://127.0.0.1:${PORT}`;
const WS_URL = `ws://127.0.0.1:${PORT}/ws`;
let proc;
let workdir;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function openWs() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    ws.msgs = [];
    ws.on('message', (d) => ws.msgs.push(JSON.parse(d)));
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}
async function waitMsg(ws, pred, ms = 3000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const m = ws.msgs.find(pred);
    if (m) return m;
    await sleep(20);
  }
  throw new Error('timeout waiting for message; got ' + JSON.stringify(ws.msgs));
}
const post = (p, body) => fetch(BASE + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

before(async () => {
  // Copy the server into a temp dir so presence.json from the test never lands in the repo.
  workdir = fs.mkdtempSync(path.join(os.tmpdir(), 'rs-test-'));
  const src = path.join(__dirname, '..');
  for (const f of ['server.js', 'turnprobe.js']) {
    if (fs.existsSync(path.join(src, f))) fs.copyFileSync(path.join(src, f), path.join(workdir, f));
  }
  fs.mkdirSync(path.join(workdir, 'public'));
  fs.writeFileSync(path.join(workdir, 'public', 'index.html'), 'ok');
  fs.symlinkSync(path.join(src, 'node_modules'), path.join(workdir, 'node_modules'));
  proc = spawn(process.execPath, ['server.js'], {
    cwd: workdir,
    env: { ...process.env, PORT: String(PORT), LOCAL_TURN: '0', SG_TURN_URLS: '', KEEP_AWAKE: '0', FIREBASE_SERVICE_ACCOUNT: '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(BASE + '/healthz')).ok) return; } catch {}
    await sleep(100);
  }
  throw new Error('server did not start');
});

after(() => {
  try { proc.kill(); } catch {}
  try { fs.rmSync(workdir, { recursive: true, force: true }); } catch {}
});

test('#5 diagnostics keep a history per device (not only the last line)', async () => {
  const dev = await openWs();
  dev.send(JSON.stringify({ type: 'register', id: '111222333', password: 'secret1' }));
  await waitMsg(dev, (m) => m.type === 'registered');
  for (const t of ['a: capture ok', 'b: signaling rớt', 'c: projection onStop']) {
    assert.strictEqual((await post('/fcm', { id: '111222333', password: 'secret1', diag: t })).status, 200);
  }
  const r = await post('/diag', { id: '111222333', password: 'secret1' });
  assert.strictEqual(r.status, 200);
  const j = await r.json();
  assert.deepStrictEqual(j.log.map((x) => x.text), ['a: capture ok', 'b: signaling rớt', 'c: projection onStop']);
  assert.ok(j.log.every((x) => typeof x.t === 'number'));
  dev.close();
});

test('#5 diagnostic history needs the device password', async () => {
  const r = await post('/diag', { id: '111222333', password: 'wrong-pw' });
  assert.strictEqual(r.status, 403);
});

test('#5 diagnostic history is capped (ring buffer)', async () => {
  const dev = await openWs();
  dev.send(JSON.stringify({ type: 'register', id: '444555666', password: 'secret1' }));
  await waitMsg(dev, (m) => m.type === 'registered');
  for (let i = 0; i < 70; i++) await post('/fcm', { id: '444555666', password: 'secret1', diag: 'line ' + i });
  const j = await (await post('/diag', { id: '444555666', password: 'secret1' })).json();
  assert.strictEqual(j.log.length, 50);
  assert.strictEqual(j.log[49].text, 'line 69');
  dev.close();
});

test('signaling: a live standby socket is woken when a controller connects', async () => {
  const sb = await openWs();
  sb.send(JSON.stringify({ type: 'register', id: '777888999', password: 'secret1', standby: true }));
  await waitMsg(sb, (m) => m.type === 'registered');
  const c = await openWs();
  c.send(JSON.stringify({ type: 'connect', id: '777888999', password: 'secret1' }));
  await waitMsg(sb, (m) => m.type === 'wake');
  await waitMsg(c, (m) => m.type === 'error' && m.code === 'waking');
  // The phone starts real sharing: the controller's retry now connects and the device gets peer-joined.
  const share = await openWs();
  share.send(JSON.stringify({ type: 'register', id: '777888999', password: 'secret1' }));
  await waitMsg(share, (m) => m.type === 'registered');
  const c2 = await openWs();
  c2.send(JSON.stringify({ type: 'connect', id: '777888999', password: 'secret1' }));
  await waitMsg(c2, (m) => m.type === 'connected');
  await waitMsg(share, (m) => m.type === 'peer-joined');
  for (const w of [sb, c, share, c2]) w.close();
});

test('signaling: a sharing device that drops keeps its id for the grace period', async () => {
  const share = await openWs();
  share.send(JSON.stringify({ type: 'register', id: '121212121', password: 'secret1' }));
  await waitMsg(share, (m) => m.type === 'registered');
  const c = await openWs();
  c.send(JSON.stringify({ type: 'connect', id: '121212121', password: 'secret1' }));
  await waitMsg(c, (m) => m.type === 'connected');
  share.terminate();                       // network drop, not a clean stop
  await sleep(200);
  const back = await openWs();
  back.send(JSON.stringify({ type: 'register', id: '121212121', password: 'secret1' }));
  await waitMsg(back, (m) => m.type === 'registered');
  // The same controller is kept and the session is rebuilt without a new wake.
  await waitMsg(back, (m) => m.type === 'peer-joined');
  for (const w of [c, back]) w.close();
});
