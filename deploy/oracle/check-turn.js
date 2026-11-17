#!/usr/bin/env node
'use strict';
/**
 * Kiểm tra TURN relay TỪ BÊN NGOÀI (chạy trên máy tính của bạn hoặc bất kỳ máy nào khác, không phải
 * chính máy Oracle): STUN → cấp relay (allocate) → gói UDP từ ngoài đi vào cổng relay.
 *
 *   node deploy/oracle/check-turn.js 161.118.218.91 <user> <pass> [port]
 *
 * inbound=false nghĩa là dải cổng relay (UDP 49152-65535) đang bị chặn: mở trong Security List của
 * VCN và iptables của máy (setup.sh đã mở iptables).
 */
const { probeTurn } = require('../../server/turnprobe');

const [host, username, credential, port] = process.argv.slice(2);
if (!host || !username || !credential) {
  console.error('Cách dùng: node deploy/oracle/check-turn.js <host> <user> <pass> [port=3478]');
  process.exit(2);
}
probeTurn({ host, port: Number(port || 3478), username, credential, timeoutMs: 4000 }).then((r) => {
  const mark = (b) => (b ? 'OK  ' : 'LỖI ');
  console.log(`${mark(r.stun)} STUN tới ${host}:${port || 3478}${r.ms != null ? ` (${r.ms} ms)` : ''}`);
  console.log(`${mark(r.allocate)} Cấp relay (đăng nhập TURN)${r.relayed ? ' → ' + r.relayed : ''}`);
  console.log(`${mark(r.inbound)} Gói từ ngoài đi vào cổng relay`);
  if (r.error) console.log('Chi tiết: ' + r.error);
  console.log(r.ok ? '\nTURN dùng được.' : '\nTURN CHƯA dùng được — xem README mục "Kiểm tra TURN".');
  process.exit(r.ok ? 0 : 1);
});
