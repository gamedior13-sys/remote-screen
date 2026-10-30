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
const crypto = require('crypto');
const express = require('express');
const { WebSocketServer } = require('ws');

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
const HOSTED_TURN = Boolean((METERED_APP && METERED_API_KEY) || (CF_TURN_KEY_ID && CF_TURN_TOKEN));
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

let hostedCache = { list: null, until: 0 };
async function hostedTurn() {
  if (hostedCache.list && Date.now() < hostedCache.until) return hostedCache.list;
  let list = null;
  try {
    if (CF_TURN_KEY_ID && CF_TURN_TOKEN) {
      const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${CF_TURN_KEY_ID}/credentials/generate-ice-servers`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${CF_TURN_TOKEN}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ ttl: 86400 }),
      });
      const j = await r.json();
      list = Array.isArray(j.iceServers) ? j.iceServers : j.iceServers ? [j.iceServers] : null;
    } else if (METERED_APP && METERED_API_KEY) {
      const r = await fetch(`https://${METERED_APP}.metered.live/api/v1/turn/credentials?apiKey=${encodeURIComponent(METERED_API_KEY)}`);
      const j = await r.json();
      list = Array.isArray(j) ? j : null;
    }
  } catch (e) {
    console.warn('hosted TURN fetch failed:', e.message);
  }
  if (list && list.length) hostedCache = { list, until: Date.now() + 6 * 3600e3 };
  return list || [];
}

async function iceServersFor(req) {
  if (CUSTOM_ICE) return CUSTOM_ICE;
  const list = [{ urls: 'stun:stun.l.google.com:19302' }];
  if (HOSTED_TURN) list.push(...(await hostedTurn()));
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
app.get('/config', async (req, res) => res.json({ iceServers: await iceServersFor(req) }));
app.get('/healthz', (_req, res) => res.send('ok'));
app.use(express.static(path.join(__dirname, 'public'), { maxAge: 0 }));

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

/** id -> { ws, pwHash, controller } */
const devices = new Map();
/** ip -> { count, resetAt } */
const fails = new Map();

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
      devices.set(id, { ws, pwHash, controller: null });
      return send(ws, { type: 'registered', id });
    }

    // ---- controller connects ----
    if (m.type === 'connect' && !ws.role) {
      if (isBlocked(ip)) return send(ws, { type: 'error', code: 'rate_limited', message: 'Too many failed attempts. Try again later.' });
      const dev = devices.get(String(m.id || '').replace(/\s+/g, ''));
      const ok = dev && safeEqual(dev.pwHash, sha(m.password || ''));
      if (!ok) {
        noteFail(ip);
        // same message for unknown id and wrong password: do not leak which IDs exist
        return send(ws, { type: 'error', code: 'denied', message: 'Wrong ID or password' });
      }
      if (dev.controller) return send(ws, { type: 'error', code: 'busy', message: 'Device is already being controlled' });
      ws.role = 'controller';
      ws.deviceId = String(m.id).replace(/\s+/g, '');
      dev.controller = ws;
      send(ws, { type: 'connected' });
      return send(dev.ws, { type: 'peer-joined' });
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
