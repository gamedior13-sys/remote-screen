'use strict';
// #7: a TURN relay must be judged by a REAL allocation + an inbound packet through the relay port,
// not only by a STUN answer on :3478 (which passes even when the relay port range is firewalled).
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const Turn = require('node-turn');
const { probeTurn } = require('../turnprobe');

const PORT = 34780 + Math.floor(Math.random() * 100);
let turn;

before(() => {
  turn = new Turn({
    listeningPort: PORT,
    listeningIps: ['127.0.0.1'],
    relayIps: ['127.0.0.1'],
    authMech: 'long-term',
    credentials: { user1: 'pass1' },
    realm: 'test',
    debugLevel: 'OFF',
  });
  turn.start();
});
after(() => { try { turn.stop(); } catch {} });

test('healthy relay: STUN ok, allocation ok, inbound packet reaches the client through the relay', async () => {
  const r = await probeTurn({ host: '127.0.0.1', port: PORT, username: 'user1', credential: 'pass1', timeoutMs: 2000 });
  assert.strictEqual(r.stun, true, JSON.stringify(r));
  assert.strictEqual(r.allocate, true, JSON.stringify(r));
  assert.match(r.relayed, /^127\.0\.0\.1:\d+$/);
  assert.strictEqual(r.inbound, true, JSON.stringify(r));
  assert.strictEqual(r.ok, true);
});

test('wrong credentials: STUN answers but allocation is refused, so the relay is NOT ok', async () => {
  const r = await probeTurn({ host: '127.0.0.1', port: PORT, username: 'user1', credential: 'nope', timeoutMs: 2000 });
  assert.strictEqual(r.stun, true);
  assert.strictEqual(r.allocate, false);
  assert.strictEqual(r.ok, false);
  assert.match(r.error, /auth|401|allocate/i);
});

test('dead host: everything false, returns within the timeout', async () => {
  const t0 = Date.now();
  const r = await probeTurn({ host: '127.0.0.1', port: 9, username: 'u', credential: 'p', timeoutMs: 800 });
  assert.strictEqual(r.ok, false);
  assert.strictEqual(r.stun, false);
  assert.ok(Date.now() - t0 < 3000);
});

test('relay port range firewalled: allocation succeeds but nothing comes back, so the relay is NOT ok', async () => {
  // Advertise a relayed address nobody listens on: same effect as Oracle blocking UDP 49152-65535.
  const port2 = PORT + 200;
  const blocked = new Turn({
    listeningPort: port2, listeningIps: ['127.0.0.1'], relayIps: ['127.0.0.1'], externalIps: '127.0.0.3',
    authMech: 'long-term', credentials: { user1: 'pass1' }, realm: 'test', debugLevel: 'OFF',
  });
  blocked.start();
  try {
    const r = await probeTurn({ host: '127.0.0.1', port: port2, username: 'user1', credential: 'pass1', timeoutMs: 1500 });
    assert.strictEqual(r.stun, true, JSON.stringify(r));
    assert.strictEqual(r.allocate, true, JSON.stringify(r));
    assert.strictEqual(r.inbound, false, JSON.stringify(r));
    assert.strictEqual(r.ok, false);
    assert.match(r.error, /unreachable/);
  } finally { try { blocked.stop(); } catch {} }
});
