'use strict';
/**
 * Signaling server.
 *
 * Roles
 *  - device     : Android phone being controlled. Sends {type:'register', id, password}.
 *  - controller : Web / iOS client. Sends {type:'connect', id, password}.
 *
 * The server only passes SDP/ICE messages between the two peers. Video and control
 * data travel peer-to-peer over WebRTC (via TURN only when a direct path is impossible).
 */
const http = require('http');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const express = require('express');
const { WebSocketServer } = require('ws');
const dgram = require('dgram');

const PORT = Number(process.env.PORT || 8080);
const os = require('os');
const TURN_PORT = Number(process.env.TURN_PORT || 3478);
const TURN_USER = 'remote';
const TURN_PASS = 'remote-screen';
// Custom ICE servers from env override everything else.
const CUSTOM_ICE = process.env.ICE_SERVERS ? JSON.parse(process.env.ICE_SERVERS) : null;
// Hosted TURN providers (used on a cloud host, where UDP relaying is not possible).
const METERED_APP = process.env.METERED_APP || '';       // e.g. "myapp" for myapp.metered.live
const METERED_API_KEY = process.env.METERED_API_KEY || '';
const CF_TURN_KEY_ID = process.env.CF_TURN_KEY_ID || '';
const CF_TURN_TOKEN = process.env.CF_TURN_TOKEN || '';
// Any TURN service with fixed credentials (e.g. ExpressTURN free: 1000 GB/month).
// EXTRA_TURN_URLS is comma separated, e.g. "turn:relay1.expressturn.com:3478,turn:relay1.expressturn.com:3478?transport=tcp"
const EXTRA_TURN_URLS = (process.env.EXTRA_TURN_URLS || '').split(',').map((s) => s.trim()).filter(Boolean);
const EXTRA_TURN_USER = (process.env.EXTRA_TURN_USER || '').trim();
const EXTRA_TURN_PASS = (process.env.EXTRA_TURN_PASS || '').trim();
const EXTRA_TURN_NAME = process.env.EXTRA_TURN_NAME || 'TURN';
const EXTRA_TURN_QUOTA_GB = Number(process.env.EXTRA_TURN_QUOTA_GB || 0);
const EXTRA_TURN = EXTRA_TURN_URLS.length > 0 && !!EXTRA_TURN_USER && !!EXTRA_TURN_PASS;
// UDP relaying has the lowest latency: list UDP urls first, TCP/TLS only as fallback.
const isUdpUrl = (u) => !/transport=tcp/i.test(u) && !/^turns:/i.test(u);
EXTRA_TURN_URLS.sort((a, b) => Number(isUdpUrl(b)) - Number(isUdpUrl(a)));
const turnHost = (u) => { const m = /^turns?:([^:?]+)/i.exec(u); return m ? m[1].toLowerCase() : ''; };
const baseDomain = (h) => h.split('.').slice(-2).join('.');
const EXTRA_DOMAIN = EXTRA_TURN ? baseDomain(turnHost(EXTRA_TURN_URLS[0])) : '';
// Other relay servers of the same provider that the web client may test for the lowest ping.
// (Tested: a free ExpressTURN account only works on free.expressturn.com, so nothing extra to probe.)
const PROBE_HOSTS = [];
// Our own relay box in the region (coturn on the Oracle A1 instance in Singapore). It is advertised
// only while it answers a STUN probe, so a wedged box never slows a connection down.
// Activate it with SG_TURN_USER / SG_TURN_PASS in the environment; SG_TURN_URLS='' switches it off.
const SG_TURN_URLS = (process.env.SG_TURN_URLS === undefined
  ? 'turn:129.221.213.86:3478?transport=udp,turn:129.221.213.86:3478?transport=tcp'
  : process.env.SG_TURN_URLS).split(',').map((s) => s.trim()).filter(Boolean);
const SG_TURN_USER = (process.env.SG_TURN_USER || '').trim();
const SG_TURN_PASS = (process.env.SG_TURN_PASS || '').trim();
const SG_TURN_NAME = process.env.SG_TURN_NAME || 'Singapore';
const SG_TURN = SG_TURN_URLS.length > 0 && Boolean(SG_TURN_USER && SG_TURN_PASS);
const HOSTED_TURN = EXTRA_TURN || Boolean((METERED_APP && METERED_API_KEY) || (CF_TURN_KEY_ID && CF_TURN_TOKEN));
// Built-in TURN only makes sense when running on a PC in the LAN (not on Render & co).
const LOCAL_TURN = !HOSTED_TURN && !process.env.RENDER && process.env.LOCAL_TURN !== '0';

if (LOCAL_TURN) {
  const Turn = require('node-turn');
  new Turn({
    listeningPort: TURN_PORT,
    authMech: 'long-term',
    credentials: { [TURN_USER]: TURN_PASS },
    realm: 'remote-screen',
    debugLevel: 'OFF',
  }).start();
}

function localIPv4s() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' || a.family === 4) out.push(a.address);
  }
  return out;
}

// Each hosted provider has its own short-lived credentials, cached separately.
const turnCache = { cf: { list: null, until: 0 }, metered: { list: null, until: 0 } };

async function cloudflareTurn() {
  if (!(CF_TURN_KEY_ID && CF_TURN_TOKEN)) return [];
  const c = turnCache.cf;
  if (c.list && Date.now() < c.until) return c.list;
  try {
    const auth = 'Bearer ' + CF_TURN_TOKEN.trim();
    const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${CF_TURN_KEY_ID.trim()}/credentials/generate-ice-servers`, {
      method: 'POST',
      headers: { Authorization: auth, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ttl: 86400 }),
    });
    const text = await r.text();
    let j = null; try { j = JSON.parse(text); } catch {}
    let list = j && (Array.isArray(j.iceServers) ? j.iceServers : j.iceServers ? [j.iceServers] : null);
    if (!list) { console.warn(`Cloudflare TURN answered HTTP ${r.status}: ${text.slice(0, 200)}`); return []; }
    // Port 53 is blocked by browsers and only slows down gathering.
    list = list.map((e) => ({ ...e, urls: [].concat(e.urls || []).filter((u) => !/:53(\?|$)/.test(u)) })).filter((e) => e.urls.length);
    turnCache.cf = { list, until: Date.now() + 6 * 3600e3 };
    return list;
  } catch (e) { console.warn('Cloudflare TURN fetch failed:', e.message); return []; }
}

async function meteredTurn() {
  if (!(METERED_APP && METERED_API_KEY)) return [];
  const c = turnCache.metered;
  if (c.list && Date.now() < c.until) return c.list;
  try {
    // Accept either the bare key or the whole URL copied from the Metered dashboard.
    const raw = METERED_API_KEY.trim();
    const m = raw.match(/apiKey=([^&\s"']+)/);
    const key = m ? m[1] : raw;
    const host = raw.match(/https:\/\/([a-z0-9-]+)\.metered\.live/i);
    const app = host ? host[1] : METERED_APP.replace(/\.metered\.live.*$/, '');
    const r = await fetch(`https://${app}.metered.live/api/v1/turn/credentials?apiKey=${encodeURIComponent(key)}`);
    const text = await r.text();
    let j = null; try { j = JSON.parse(text); } catch {}
    const list = Array.isArray(j) ? j : Array.isArray(j && j.iceServers) ? j.iceServers : null;
    if (!list) { console.warn(`Metered answered HTTP ${r.status}: ${text.slice(0, 200)}`); return []; }
    turnCache.metered = { list, until: Date.now() + 6 * 3600e3 };
    return list;
  } catch (e) { console.warn('Metered fetch failed:', e.message); return []; }
}

async function hostedTurn() {
  const cf = await cloudflareTurn();
  return cf.length ? cf : meteredTurn();
}

async function iceServersFor(req) {
  if (CUSTOM_ICE) return CUSTOM_ICE;
  const list = [{ urls: 'stun:stun.l.google.com:19302' }];
  // A fixed-credential TURN (bigger free quota) replaces the metered one when configured.
  const cf = await cloudflareTurn();
  if (cf.length) list.push(...cf.filter((e) => e.username));
  if (EXTRA_TURN) list.push({ urls: EXTRA_TURN_URLS, username: EXTRA_TURN_USER, credential: EXTRA_TURN_PASS });
  else if (!cf.length && HOSTED_TURN) list.push(...(await hostedTurn()));
  if (LOCAL_TURN) {
    const host = String(req.headers.host || '').replace(/:\d+$/, '').replace(/^\[|\]$/g, '');
    const hosts = [...new Set([host, ...localIPv4s()].filter(Boolean))];
    list.push({ urls: hosts.map((h) => `turn:${h}:${TURN_PORT}?transport=udp`), username: TURN_USER, credential: TURN_PASS });
  }
  return list;
}

const MAX_FAILS = 8;               // wrong-password attempts allowed ...
const FAIL_WINDOW_MS = 10 * 60e3;  // ... per IP within this window

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', true);
app.use(express.json());
// Every TURN relay the web client may measure, one entry per host, from all configured providers.
// The client keeps the one with the lowest ping and the device is given the same one.
async function relayCandidates() {
  const out = [];
  // Our regional relay first: the client keeps whichever answers with the lowest ping.
  if (SG_TURN) {
    const h = turnHost(SG_TURN_URLS[0]);
    if (h && (await relayAnswers(h, 3478, 1200))) {
      const urls = [...SG_TURN_URLS].sort((a, b) => Number(isUdpUrl(b)) - Number(isUdpUrl(a)));
      out.push({ name: SG_TURN_NAME, url: urls[0], ice: { urls, username: SG_TURN_USER, credential: SG_TURN_PASS } });
    }
  }
  if (EXTRA_TURN) {
    const hosts = [...new Set([...EXTRA_TURN_URLS.map(turnHost), ...PROBE_HOSTS])];
    for (const h of hosts) {
      out.push({ name: EXTRA_TURN_NAME, url: `turn:${h}:3478?transport=udp`,
        ice: { urls: [`turn:${h}:3478?transport=udp`, `turn:${h}:3478?transport=tcp`], username: EXTRA_TURN_USER, credential: EXTRA_TURN_PASS } });
    }
  }
  // Cloudflare (anycast, 1000 GB free) and Metered (geo-routed, small free quota) relays.
  const providers = [['Cloudflare', await cloudflareTurn()], ['Metered', await meteredTurn()]];
  for (const [name, entries] of providers) {
    const byHost = new Map();
    for (const e of entries) {
      if (!e || !e.username) continue;
      for (const u of [].concat(e.urls || [])) {
        const h = turnHost(u); if (!h) continue;
        const g = byHost.get(h) || { urls: [], username: e.username, credential: e.credential };
        g.urls.push(u); byHost.set(h, g);
      }
    }
    for (const [h, g] of byHost) {
      g.urls.sort((a, b) => Number(isUdpUrl(b)) - Number(isUdpUrl(a)));
      const udp = g.urls.find(isUdpUrl);
      if (udp) out.push({ name, url: udp, ice: g });
    }
  }
  return out;
}

// A relay is only useful while it is reachable: probe it with a plain STUN binding request over UDP
// and cache the answer for a few minutes, so /config stays cheap and a dead host disappears.
const relayHealth = new Map();
function stunBindingRequest() {
  const b = Buffer.alloc(20);
  b.writeUInt16BE(0x0001, 0);            // Binding request
  b.writeUInt16BE(0x0000, 2);            // attributes length
  b.writeUInt32BE(0x2112a442, 4);        // magic cookie
  crypto.randomBytes(12).copy(b, 8);     // transaction id
  return b;
}
function relayAnswers(host, port, timeoutMs = 1200) {
  const key = `${host}:${port}`;
  const hit = relayHealth.get(key);
  if (hit && Date.now() < hit.until) return Promise.resolve(hit.ok);
  return new Promise((resolve) => {
    const sock = dgram.createSocket('udp4');
    let done = false;
    const end = (ok) => {
      if (done) return;
      done = true;
      try { sock.close(); } catch {}
      relayHealth.set(key, { ok, until: Date.now() + 5 * 60e3 });
      resolve(ok);
    };
    sock.on('error', () => end(false));
    sock.on('message', (m) => end(m.length >= 20 && m.readUInt16BE(0) === 0x0101));
    try { sock.send(stunBindingRequest(), port, host, (e) => { if (e) end(false); }); } catch { end(false); }
    setTimeout(() => end(false), timeoutMs);
  });
}

app.get('/config', async (req, res) => {
  const out = { iceServers: await iceServersFor(req) };
  const relays = await relayCandidates();
  if (relays.length) out.relays = relays;
  res.json(out);
});

// The controller measured the TURN relay with the lowest ping: give the device the same one.
async function pickedIce(pick) {
  if (typeof pick !== 'string') return null;
  const r = (await relayCandidates()).find((x) => x.url === pick);
  return r ? [{ urls: 'stun:stun.l.google.com:19302' }, r.ice] : null;
}
app.get('/healthz', (_req, res) => res.send('ok'));
// Which relays exist right now, and which ones really answer (diagnostics for "TURN is slow").
app.get('/turncheck', async (_req, res) => {
  const rows = [];
  if (SG_TURN) {
    const h = turnHost(SG_TURN_URLS[0]);
    rows.push({ name: SG_TURN_NAME, host: h, port: 3478, answers: h ? await relayAnswers(h, 3478, 1500) : false });
  }
  const relays = (await relayCandidates()).map((r) => ({ name: r.name, url: r.url }));
  res.set('Cache-Control', 'no-store').json({ configured: SG_TURN, own: rows, relays });
});

// Phone reports its Firebase push token so the server can wake it while its socket is dead.
app.post('/fcm', (req, res) => {
  const b = req.body || {};
  const id = String(b.id || '').replace(/\s+/g, '');
  const fcm = String(b.fcm || '').trim();
  const pr = presence[id];
  if (!pr || !fcm || !safeEqual(pr.pwHash, sha(String(b.password || '')))) return res.status(403).json({ ok: false });
  pr.fcm = fcm;
  savePresence();
  res.json({ ok: true });
});

// FCM diagnostics for the wake path: is the service account loaded, and how many phones reported a token?
app.get('/fcmcheck', (_req, res) => {
  const ids = Object.keys(presence);
  res.set('Cache-Control', 'no-store').json({
    configured: !!fcmCreds,
    registered: ids.length,
    withToken: ids.filter((k) => presence[k] && presence[k].fcm).length,
  });
});
// Versions for the "sync" buttons: the web page (hash of index.html) and the newest Android build
// (version.json is written by the CI next to remote-screen.apk).
const WEB_VERSION = (() => {
  try { return crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname, 'public', 'index.html'))).digest('hex').slice(0, 8); }
  catch { return String(Date.now()); }
})();
app.get('/version', (_req, res) => {
  let apk = null;
  try { apk = JSON.parse(fs.readFileSync(path.join(__dirname, 'public', 'version.json'), 'utf8')); } catch {}
  res.set('Cache-Control', 'no-store').json({ web: WEB_VERSION, apk });
});

// TURN quota for the web UI (needs METERED_SECRET_KEY from the Metered dashboard).
const METERED_SECRET_KEY = process.env.METERED_SECRET_KEY || '';
let usageCache = { data: null, until: 0 };
app.get('/usage', async (_req, res) => {
  if (EXTRA_TURN) return res.json({ available: !!EXTRA_TURN_QUOTA_GB, provider: EXTRA_TURN_NAME, quotaMB: EXTRA_TURN_QUOTA_GB * 1024, usedMB: null });
  if (!METERED_APP || !METERED_SECRET_KEY) return res.json({ available: false });
  if (usageCache.data && Date.now() < usageCache.until) return res.json(usageCache.data);
  try {
    const app_ = METERED_APP.replace(/\.metered\.live.*$/, '');
    const r = await fetch(`https://${app_}.metered.live/api/v1/turn/current_usage?secretKey=${encodeURIComponent(METERED_SECRET_KEY.trim())}`);
    const j = await r.json();
    if (typeof j.quotaInGB !== 'number') {
      console.warn(`usage: Metered answered HTTP ${r.status}: ${JSON.stringify(j).slice(0, 200)}`);
      return res.json({ available: false });
    }
    const data = { available: true, provider: 'Metered', quotaMB: j.quotaInGB * 1024, usedMB: (j.usageInGB || 0) * 1024 };
    usageCache = { data, until: Date.now() + 60e3 };
    res.json(data);
  } catch (e) {
    console.warn('usage fetch failed:', e.message);
    res.json({ available: false });
  }
});
app.use(express.static(path.join(__dirname, 'public'), { maxAge: 0 }));

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

/** id -> { ws, pwHash, controller } */
const devices = new Map();
/** ip -> { count, resetAt } */
const fails = new Map();

// Persisted reachability per device: password hash + FCM token, so a phone whose standby
// socket is DEAD (Android killed the app) can still be woken via Firebase push.
const presenceFile = path.join(__dirname, 'presence.json');
let presence = {};
try { presence = JSON.parse(fs.readFileSync(presenceFile, 'utf8')); } catch {}
const savePresence = () => { try { fs.writeFileSync(presenceFile, JSON.stringify(presence)); } catch (e) { console.warn('presence save', e.message); } };

// ---- Firebase Cloud Messaging (wake a phone whose standby socket is dead) ----
let fcmCreds = null;
try { fcmCreds = process.env.FIREBASE_SERVICE_ACCOUNT ? JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT) : null; } catch {}
let fcmAuth = { token: null, until: 0 };

async function fcmAccessToken() {
  if (!fcmCreds) return null;
  if (fcmAuth.token && Date.now() < fcmAuth.until) return fcmAuth.token;
  const now = Math.floor(Date.now() / 1000);
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = b64({ alg: 'RS256', typ: 'JWT' });
  const claim = b64({ iss: fcmCreds.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 });
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(head + '.' + claim);
  const sig = signer.sign(String(fcmCreds.private_key).replace(/\\n/g, '\n')).toString('base64url');
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=' + encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer') + '&assertion=' + encodeURIComponent(head + '.' + claim + '.' + sig),
  });
  if (!r.ok) { console.warn('fcm auth', r.status, (await r.text()).slice(0, 200)); return null; }
  const j = await r.json();
  fcmAuth = { token: j.access_token, until: Date.now() + 45 * 60e3 };
  return j.access_token;
}

async function fcmWake(id, token) {
  try {
    if (!fcmCreds || !token) return false;
    const tok = await fcmAccessToken();
    if (!tok) return false;
    const auth = 'Bearer ' + tok;
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${fcmCreds.project_id}/messages:send`, {
      method: 'POST',
      headers: { Authorization: auth, 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: { token, data: { action: 'wake', id }, android: { priority: 'HIGH' } } }),
    });
    if (!r.ok) console.warn('fcm wake', id, r.status, (await r.text()).slice(0, 200));
    return r.ok;
  } catch (t) { console.warn('fcm wake', id, t.message); return false; }
}

const sha = (s) => crypto.createHash('sha256').update(String(s)).digest();
const safeEqual = (a, b) => crypto.timingSafeEqual(a, b);
const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };

function clientIp(req) {
  const xf = req.headers['x-forwarded-for'];
  return (xf ? String(xf).split(',')[0].trim() : req.socket.remoteAddress) || 'unknown';
}

function isBlocked(ip) {
  const f = fails.get(ip);
  if (!f) return false;
  if (Date.now() > f.resetAt) { fails.delete(ip); return false; }
  return f.count >= MAX_FAILS;
}

function noteFail(ip) {
  const f = fails.get(ip);
  if (!f || Date.now() > f.resetAt) fails.set(ip, { count: 1, resetAt: Date.now() + FAIL_WINDOW_MS });
  else f.count += 1;
}

function newId() {
  for (let i = 0; i < 20; i++) {
    const id = String(crypto.randomInt(100000000, 999999999));
    if (!devices.has(id)) return id;
  }
  throw new Error('no free id');
}

wss.on('connection', (ws, req) => {
  const ip = clientIp(req);
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.role = null;

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw.toString()); } catch { return; }
    if (!m || typeof m.type !== 'string') return;

    // ---- device registers ----
    if (m.type === 'register' && !ws.role) {
      const password = String(m.password || '');
      if (password.length < 6) return send(ws, { type: 'error', code: 'weak_password', message: 'Password must be at least 6 characters' });
      let id = /^\d{9}$/.test(String(m.id || '')) ? String(m.id) : null;
      const pwHash = sha(password);
      const existing = id && devices.get(id);
      if (existing) {
        // Allow a reconnecting device to take over its own ID only with the same password.
        if (!safeEqual(existing.pwHash, pwHash)) id = null;
        else { existing.ws.role = null; try { existing.ws.terminate(); } catch {} }
      }
      if (!id) id = newId();
      ws.role = 'device';
      ws.deviceId = id;
      // Giữ controller cũ khi máy đăng ký lại cùng mật khẩu, để nối lại mượt sau khi rớt mạng.
      const keepController = (existing && existing.ws && existing.controller) || null;
      devices.set(id, { ws, pwHash, controller: keepController, standby: !!m.standby, lastWake: 0 });
      // Nhớ hash mật khẩu + token FCM xuống đĩa: còn gọi dậy khi socket chết.
      presence[id] = { pwHash, fcm: String(m.fcm || (presence[id] && presence[id].fcm) || ''), ts: Date.now() };
      savePresence();
      return send(ws, { type: 'registered', id });
    }

    // ---- controller connects ----
    if (m.type === 'connect' && !ws.role) {
      const idc = String(m.id || '').replace(/\s+/g, '');
      const dev = devices.get(idc);
      const ok = dev && safeEqual(dev.pwHash, sha(m.password || ''));
      if (!ok) {
        // Socket máy đích đã chết (Android giết app): thử đẩy FCM gọi dậy — nhưng chỉ khi
        // mật khẩu khớp với hash đã lưu, và không bao giờ tiết lộ ID nào tồn tại.
        const pr = presence[idc];
        if (pr && pr.fcm && safeEqual(pr.pwHash, sha(m.password || ''))) {
          fcmWake(idc, pr.fcm);
          return send(ws, { type: 'error', code: 'waking', message: 'Waking device' });
        }
        // No lock-out any more; a short pause per wrong attempt still makes guessing impractical.
        // Same message for unknown id and wrong password: do not leak which IDs exist.
        return setTimeout(() => send(ws, { type: 'error', code: 'denied', message: 'Wrong ID or password' }), 1000);
      }
      if (dev.standby) {
        // Máy đang ở chế độ chờ, chưa chia sẻ: đánh thức nó, bảo controller chờ rồi thử lại.
        const nowT = Date.now();
        if (!dev.lastWake || nowT - dev.lastWake > 8000) { dev.lastWake = nowT; send(dev.ws, { type: 'wake' }); }
        return send(ws, { type: 'error', code: 'waking', message: 'Waking device' });
      }
      if (dev.controller && dev.controller !== ws) {
        // A newer session with the right password takes over (e.g. the same phone reconnecting
        // after a network change while its old socket is not yet detected as dead).
        const old = dev.controller;
        old.role = null;
        send(old, { type: 'error', code: 'taken_over', message: 'Another session took over this device' });
        try { old.terminate(); } catch {}
        dev.controller = null;
      }
      ws.role = 'controller';
      ws.deviceId = idc;
      dev.controller = ws;
      send(ws, { type: 'connected' });
      return pickedIce(m.turn).catch(() => null)
        .then((ice) => send(dev.ws, ice ? { type: 'peer-joined', ice } : { type: 'peer-joined' }));
    }

    // ---- relay SDP / ICE ----
    if (m.type === 'signal' && ws.role) {
      const dev = devices.get(ws.deviceId);
      if (!dev) return;
      if (ws.role === 'device' && dev.ws === ws) send(dev.controller, { type: 'signal', data: m.data });
      else if (ws.role === 'controller' && dev.controller === ws) send(dev.ws, { type: 'signal', data: m.data });
    }
  });

  ws.on('close', () => {
    if (ws.role === 'device') {
      const dev = devices.get(ws.deviceId);
      if (dev && dev.ws === ws) {
        send(dev.controller, { type: 'peer-left' });
        devices.delete(ws.deviceId);
      }
    } else if (ws.role === 'controller') {
      const dev = devices.get(ws.deviceId);
      if (dev && dev.controller === ws) {
        dev.controller = null;
        send(dev.ws, { type: 'peer-left' });
      }
    }
  });
});

// Drop dead sockets.
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 25000);

server.listen(PORT, () => console.log(`Remote Screen server on :${PORT}  (web client at /), ${LOCAL_TURN ? 'built-in TURN on udp :' + TURN_PORT : HOSTED_TURN ? 'hosted TURN' : 'no TURN'}`));

// Render's free plan sleeps after 15 minutes without incoming HTTP traffic, which makes the next
// connection wait 30-50 s. Ping our own public URL (through Render's proxy) every 10 minutes.
// One service running 24/7 fits inside the free 750 instance-hours per month.
const SELF_URL = process.env.RENDER_EXTERNAL_URL || process.env.SELF_URL;
if (SELF_URL && process.env.KEEP_AWAKE !== '0') {
  const ping = () => fetch(SELF_URL.replace(/\/$/, '') + '/healthz', { cache: 'no-store' })
    .then((r) => r.status !== 200 && console.warn('keep-awake ping status', r.status))
    .catch((e) => console.warn('keep-awake ping failed', e.message));
  setInterval(ping, 10 * 60 * 1000).unref();
  setTimeout(ping, 30 * 1000).unref();
  console.log('keep-awake: pinging', SELF_URL, 'every 10 min');
}
