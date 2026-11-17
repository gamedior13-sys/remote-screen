'use strict';
// #11 migration: the old Render server sends browsers to the new Oracle URL, but keeps serving the
// APK / version.json / WebSocket so phones that are not updated yet still work.
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

const PORT = 19000 + Math.floor(Math.random() * 1000);
const BASE = `http://127.0.0.1:${PORT}`;
let proc, workdir;

before(async () => {
  workdir = fs.mkdtempSync(path.join(os.tmpdir(), 'rs-redir-'));
  const src = path.join(__dirname, '..');
  for (const f of ['server.js', 'turnprobe.js']) fs.copyFileSync(path.join(src, f), path.join(workdir, f));
  fs.mkdirSync(path.join(workdir, 'public'));
  fs.writeFileSync(path.join(workdir, 'public', 'index.html'), 'old');
  fs.writeFileSync(path.join(workdir, 'public', 'web-index-patched.html'), 'old');
  fs.writeFileSync(path.join(workdir, 'public', 'version.json'), '{"versionCode":1}');
  fs.symlinkSync(path.join(src, 'node_modules'), path.join(workdir, 'node_modules'));
  proc = spawn(process.execPath, ['server.js'], {
    cwd: workdir,
    env: { ...process.env, PORT: String(PORT), LOCAL_TURN: '0', SG_TURN_URLS: '', KEEP_AWAKE: '0', REDIRECT_TO: 'https://new.example.com/' },
    stdio: 'ignore',
  });
  for (let i = 0; i < 50; i++) { try { if ((await fetch(BASE + '/healthz')).ok) return; } catch {} await new Promise((r) => setTimeout(r, 100)); }
  throw new Error('server did not start');
});
after(() => { try { proc.kill(); } catch {} try { fs.rmSync(workdir, { recursive: true, force: true }); } catch {} });

for (const p of ['/', '/index.html', '/web-index-patched.html?id=1']) {
  test(`REDIRECT_TO sends the web page ${p} to the new server`, async () => {
    const r = await fetch(BASE + p, { redirect: 'manual' });
    assert.strictEqual(r.status, 302);
    assert.strictEqual(r.headers.get('location'), 'https://new.example.com' + p);
  });
}

test('REDIRECT_TO keeps version.json for phones that are not updated yet', async () => {
  const r = await fetch(BASE + '/version.json', { redirect: 'manual' });
  assert.strictEqual(r.status, 200);
});
