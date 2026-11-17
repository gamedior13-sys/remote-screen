'use strict';
/**
 * Real health check for a TURN relay (RFC 5766 over UDP).
 *
 * A plain STUN Binding on :3478 only proves the listener is up. It still passes when the cloud
 * firewall (Oracle Security List / iptables) blocks the RELAY port range (49152-65535): then every
 * relayed UDP packet is lost, ICE falls back to TURN-over-TCP or fails, and video becomes slow,
 * blurry and laggy. This probe therefore:
 *   1. STUN Binding                          -> stun
 *   2. Allocate (long-term credentials)       -> allocate, relayed address
 *   3. CreatePermission for a second socket,  -> inbound: a packet sent from OUTSIDE to the relayed
 *      that socket sends a datagram to the       port really comes back to us as a Data indication
 *      relayed address
 *   4. Refresh(lifetime=0) to free the allocation.
 */
const dgram = require('dgram');
const crypto = require('crypto');

const MAGIC = 0x2112a442;
const T = {
  BINDING_REQ: 0x0001, BINDING_OK: 0x0101,
  ALLOCATE_REQ: 0x0003, ALLOCATE_OK: 0x0103, ALLOCATE_ERR: 0x0113,
  REFRESH_REQ: 0x0004,
  PERMISSION_REQ: 0x0008, PERMISSION_OK: 0x0108,
  DATA_IND: 0x0017,
};
const A = {
  USERNAME: 0x0006, MESSAGE_INTEGRITY: 0x0008, ERROR_CODE: 0x0009, LIFETIME: 0x000d,
  XOR_PEER_ADDRESS: 0x0012, DATA: 0x0013, REALM: 0x0014, NONCE: 0x0015,
  XOR_RELAYED_ADDRESS: 0x0016, REQUESTED_TRANSPORT: 0x0019, XOR_MAPPED_ADDRESS: 0x0020,
};

function attr(type, value) {
  const pad = (4 - (value.length % 4)) % 4;
  const b = Buffer.alloc(4 + value.length + pad);
  b.writeUInt16BE(type, 0);
  b.writeUInt16BE(value.length, 2);
  value.copy(b, 4);
  return b;
}

function build(type, tid, attrs, key) {
  let body = Buffer.concat(attrs);
  const head = Buffer.alloc(20);
  head.writeUInt16BE(type, 0);
  head.writeUInt32BE(MAGIC, 4);
  tid.copy(head, 8);
  if (key) {
    // MESSAGE-INTEGRITY covers the header with a length that already includes the MI attribute.
    head.writeUInt16BE(body.length + 24, 2);
    const mac = crypto.createHmac('sha1', key).update(Buffer.concat([head, body])).digest();
    body = Buffer.concat([body, attr(A.MESSAGE_INTEGRITY, mac)]);
  }
  head.writeUInt16BE(body.length, 2);
  return Buffer.concat([head, body]);
}

function parse(buf) {
  if (buf.length < 20 || buf.readUInt32BE(4) !== MAGIC) return null;
  const msg = { type: buf.readUInt16BE(0), tid: buf.subarray(8, 20), attrs: new Map() };
  const len = buf.readUInt16BE(2);
  let o = 20;
  while (o + 4 <= 20 + len && o + 4 <= buf.length) {
    const t = buf.readUInt16BE(o); const l = buf.readUInt16BE(o + 2);
    msg.attrs.set(t, buf.subarray(o + 4, o + 4 + l));
    o += 4 + l + ((4 - (l % 4)) % 4);
  }
  return msg;
}

function xorAddr(v) {
  if (!v || v.length < 8 || v[1] !== 0x01) return null;   // IPv4 only
  const port = v.readUInt16BE(2) ^ (MAGIC >>> 16);
  const ip = (v.readUInt32BE(4) ^ MAGIC) >>> 0;
  return { ip: [ip >>> 24, (ip >>> 16) & 255, (ip >>> 8) & 255, ip & 255].join('.'), port };
}

function xorAddrValue(ip, port) {
  const b = Buffer.alloc(8);
  b[1] = 0x01;
  b.writeUInt16BE((port ^ (MAGIC >>> 16)) & 0xffff, 2);
  const n = ip.split('.').reduce((acc, x) => ((acc << 8) | Number(x)) >>> 0, 0);
  b.writeUInt32BE((n ^ MAGIC) >>> 0, 4);
  return b;
}

const transportUdp = () => { const b = Buffer.alloc(4); b[0] = 17; return b; };

/** Send a request on `sock` and wait for the response with the same transaction id. */
function request(sock, host, port, buf, ms) {
  return new Promise((resolve) => {
    const tid = buf.subarray(8, 20);
    let done = false;
    const onMsg = (m) => {
      const p = parse(m);
      if (!p || !p.tid.equals(tid)) return;
      finish(p);
    };
    const finish = (v) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      sock.removeListener('message', onMsg);
      resolve(v);
    };
    const timer = setTimeout(() => finish(null), ms);
    sock.on('message', onMsg);
    sock.send(buf, port, host, (e) => { if (e) finish(null); });
  });
}

function bind(sock) {
  return new Promise((resolve, reject) => { sock.once('error', reject); sock.bind(0, () => resolve()); });
}

/**
 * @returns {Promise<{ok:boolean, stun:boolean, allocate:boolean, inbound:boolean, relayed?:string, ms?:number, error?:string}>}
 */
async function probeTurn({ host, port = 3478, username, credential, timeoutMs = 2500 }) {
  const out = { ok: false, stun: false, allocate: false, inbound: false };
  const t0 = Date.now();
  const left = () => Math.max(100, timeoutMs - (Date.now() - t0));
  const a = dgram.createSocket('udp4');
  const b = dgram.createSocket('udp4');
  a.on('error', () => {}); b.on('error', () => {});
  try {
    await bind(a); await bind(b);
    // 1) STUN binding (also learns b's public address for the permission in step 3)
    const bindRes = await request(b, host, port, build(T.BINDING_REQ, crypto.randomBytes(12), []), left());
    if (!bindRes || bindRes.type !== T.BINDING_OK) { out.error = 'stun: no answer'; return out; }
    out.stun = true;
    out.ms = Date.now() - t0;
    const peer = xorAddr(bindRes.attrs.get(A.XOR_MAPPED_ADDRESS));
    if (!peer) { out.error = 'stun: no mapped address'; return out; }

    // 2) Allocate: first unauthenticated (expect 401 + realm/nonce), then with credentials.
    const first = await request(a, host, port, build(T.ALLOCATE_REQ, crypto.randomBytes(12), [attr(A.REQUESTED_TRANSPORT, transportUdp())]), left());
    if (!first) { out.error = 'allocate: no answer'; return out; }
    const realm = first.attrs.get(A.REALM); const nonce = first.attrs.get(A.NONCE);
    if (!realm || !nonce) { out.error = 'allocate: no realm/nonce'; return out; }
    const key = crypto.createHash('md5').update(`${username}:${realm.toString()}:${credential}`).digest();
    const auth = [attr(A.USERNAME, Buffer.from(username)), attr(A.REALM, realm), attr(A.NONCE, nonce)];
    const alloc = await request(a, host, port, build(T.ALLOCATE_REQ, crypto.randomBytes(12), [attr(A.REQUESTED_TRANSPORT, transportUdp()), ...auth], key), left());
    if (!alloc) { out.error = 'allocate: no answer'; return out; }
    if (alloc.type !== T.ALLOCATE_OK) {
      const ec = alloc.attrs.get(A.ERROR_CODE);
      const code = ec && ec.length >= 4 ? ec[2] * 100 + ec[3] : 0;
      out.error = `allocate: refused ${code}${code === 401 ? ' (auth)' : ''}`;
      return out;
    }
    out.allocate = true;
    const relayed = xorAddr(alloc.attrs.get(A.XOR_RELAYED_ADDRESS));
    if (!relayed) { out.error = 'allocate: no relayed address'; return out; }
    out.relayed = `${relayed.ip}:${relayed.port}`;

    // 3) Permission for socket b, then b -> relayed port must arrive at a as a Data indication.
    const perm = await request(a, host, port, build(T.PERMISSION_REQ, crypto.randomBytes(12), [attr(A.XOR_PEER_ADDRESS, xorAddrValue(peer.ip, 0)), ...auth], key), left());
    if (!perm || perm.type !== T.PERMISSION_OK) { out.error = 'permission: refused'; return out; }
    const token = crypto.randomBytes(8);
    out.inbound = await new Promise((resolve) => {
      let done = false;
      const fin = (v) => { if (done) return; done = true; clearTimeout(timer); clearInterval(again); a.removeListener('message', onMsg); resolve(v); };
      const onMsg = (m) => {
        const p = parse(m);
        if (p && p.type === T.DATA_IND) { const d = p.attrs.get(A.DATA); if (d && d.equals(token)) fin(true); }
      };
      a.on('message', onMsg);
      const send = () => b.send(token, relayed.port, relayed.ip, () => {});
      send();
      const again = setInterval(send, 250);   // UDP: a single packet may be lost
      const timer = setTimeout(() => fin(false), left());
    });
    if (!out.inbound) out.error = `relay port ${relayed.port} unreachable from outside (firewall on the relay range?)`;

    // 4) Free the allocation (best effort).
    try {
      const lt = Buffer.alloc(4);
      a.send(build(T.REFRESH_REQ, crypto.randomBytes(12), [attr(A.LIFETIME, lt), ...auth], key), port, host);
    } catch {}
    out.ok = out.stun && out.allocate && out.inbound;
    return out;
  } catch (e) {
    out.error = e.message;
    return out;
  } finally {
    setTimeout(() => { try { a.close(); } catch {} try { b.close(); } catch {} }, 50);
  }
}

module.exports = { probeTurn };
