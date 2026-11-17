#!/usr/bin/env bash
# Dựng server signaling Remote Screen trên máy Oracle (Ubuntu 22.04/24.04, ARM hoặc x86).
# Chạy lại nhiều lần cũng an toàn (idempotent).
#
#   sudo bash deploy/oracle/setup.sh
#   sudo TURN_USER=remote TURN_PASS='mật-khẩu-mạnh' bash deploy/oracle/setup.sh   # ghi lại cấu hình coturn
#
# Biến tuỳ chọn: DOMAIN (mặc định <ip>.sslip.io), REPO_URL, BRANCH, APP_DIR.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/gamedior13-sys/remote-screen.git}"
BRANCH="${BRANCH:-main}"
APP_DIR="${APP_DIR:-/opt/remote-screen}"
ENV_FILE=/etc/remote-screen.env
SVC_USER=remote-screen

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mLỖI: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Chạy bằng sudo."
command -v apt-get >/dev/null || die "Script này dành cho Ubuntu (apt). Oracle Linux: làm tay theo README."

# ---------------------------------------------------------------- địa chỉ IP
log "Xác định IP"
PUBLIC_IP="${PUBLIC_IP:-}"
if [ -z "$PUBLIC_IP" ]; then
  # Metadata của OCI trước (không phụ thuộc dịch vụ ngoài), rồi mới hỏi Internet.
  PUBLIC_IP="$(curl -fsS -m 5 -H 'Authorization: Bearer Oracle' http://169.254.169.254/opc/v2/vnics/ 2>/dev/null \
    | grep -o '"publicIp" *: *"[0-9.]*"' | head -n1 | grep -o '[0-9.]*$' || true)"
fi
[ -n "$PUBLIC_IP" ] || PUBLIC_IP="$(curl -fsS -m 5 https://api.ipify.org || true)"
[ -n "$PUBLIC_IP" ] || die "Không tìm được IP công khai. Chạy lại với PUBLIC_IP=x.x.x.x"
PRIVATE_IP="$(hostname -I | awk '{print $1}')"
DOMAIN="${DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
echo "IP công khai: $PUBLIC_IP · IP nội bộ: $PRIVATE_IP · tên miền: $DOMAIN"

# ---------------------------------------------------------------- gói cài đặt
log "Cài Node.js 22, Caddy, coturn, iptables-persistent"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl git ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi
echo iptables-persistent iptables-persistent/autosave_v4 boolean true | debconf-set-selections
echo iptables-persistent iptables-persistent/autosave_v6 boolean true | debconf-set-selections
apt-get install -y coturn iptables-persistent

# ---------------------------------------------------------------- mã nguồn
log "Lấy mã nguồn vào $APP_DIR ($BRANCH)"
id "$SVC_USER" >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$SVC_USER"
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" fetch --depth 1 origin "$BRANCH"
  git -C "$APP_DIR" checkout -B "$BRANCH" "origin/$BRANCH"
else
  git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi
(cd "$APP_DIR/server" && npm install --omit=dev --no-audit --no-fund)
chown -R "$SVC_USER:$SVC_USER" "$APP_DIR/server"

# ---------------------------------------------------------------- biến môi trường
if [ ! -f "$ENV_FILE" ]; then
  log "Tạo $ENV_FILE (nhớ điền SG_TURN_USER/SG_TURN_PASS, FIREBASE_SERVICE_ACCOUNT…)"
  sed "s/PUBLIC_IP/$PUBLIC_IP/g" "$APP_DIR/deploy/oracle/remote-screen.env.example" > "$ENV_FILE"
fi
if [ -n "${TURN_USER:-}" ] && [ -n "${TURN_PASS:-}" ]; then
  sed -i "s|^SG_TURN_USER=.*|SG_TURN_USER=$TURN_USER|; s|^SG_TURN_PASS=.*|SG_TURN_PASS=$TURN_PASS|" "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

# ---------------------------------------------------------------- coturn
if [ -n "${TURN_USER:-}" ] && [ -n "${TURN_PASS:-}" ]; then
  log "Ghi cấu hình coturn (bản cũ lưu ở /etc/turnserver.conf.bak.*)"
  [ -f /etc/turnserver.conf ] && cp /etc/turnserver.conf "/etc/turnserver.conf.bak.$(date +%s)"
  cat > /etc/turnserver.conf <<EOF
# managed by remote-screen deploy/oracle/setup.sh
listening-port=3478
fingerprint
lt-cred-mech
user=${TURN_USER}:${TURN_PASS}
realm=remote-screen
# Máy Oracle nằm sau NAT 1:1: báo cho client IP công khai, relay bằng IP nội bộ.
external-ip=${PUBLIC_IP}/${PRIVATE_IP}
# Dải cổng relay — PHẢI mở UDP dải này ở Security List VCN (setup đã mở iptables).
min-port=49152
max-port=65535
no-cli
no-tls
no-dtls
no-multicast-peers
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
simple-log
log-file=/var/log/turnserver/turnserver.log
EOF
  mkdir -p /var/log/turnserver && chown turnserver:turnserver /var/log/turnserver 2>/dev/null || true
  [ -f /etc/default/coturn ] && sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
  systemctl enable coturn
  systemctl restart coturn
else
  log "Giữ nguyên cấu hình coturn hiện có (truyền TURN_USER/TURN_PASS để ghi lại)"
  grep -E '^(min-port|max-port|external-ip)' /etc/turnserver.conf 2>/dev/null || \
    echo "  Lưu ý: /etc/turnserver.conf chưa khai báo external-ip / min-port / max-port."
fi

# ---------------------------------------------------------------- tường lửa trong máy
# Ảnh Ubuntu của Oracle có sẵn luật REJECT cuối chuỗi INPUT: mọi cổng ngoài 22 bị chặn dù Security
# List đã mở. Chèn luật ACCEPT trước REJECT.
log "Mở iptables: 80,443/tcp · 3478/tcp+udp · 49152-65535/udp"
allow() {
  iptables -C INPUT "$@" -j ACCEPT 2>/dev/null && return 0
  local pos
  pos="$(iptables -L INPUT --line-numbers -n | awk '$2=="REJECT"{print $1; exit}')"
  if [ -n "$pos" ]; then iptables -I INPUT "$pos" "$@" -j ACCEPT; else iptables -A INPUT "$@" -j ACCEPT; fi
}
allow -p tcp -m state --state NEW --dport 80
allow -p tcp -m state --state NEW --dport 443
allow -p tcp -m state --state NEW --dport 3478
allow -p udp --dport 3478
allow -p udp --dport 49152:65535
netfilter-persistent save

# ---------------------------------------------------------------- dịch vụ Node
log "Tạo dịch vụ systemd remote-screen"
cat > /etc/systemd/system/remote-screen.service <<EOF
[Unit]
Description=Remote Screen signaling server
After=network-online.target
Wants=network-online.target

[Service]
User=${SVC_USER}
WorkingDirectory=${APP_DIR}/server
EnvironmentFile=${ENV_FILE}
ExecStart=$(command -v node) ${APP_DIR}/server/server.js
Restart=always
RestartSec=2
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable remote-screen
systemctl restart remote-screen

# ---------------------------------------------------------------- tự cập nhật theo main
# Render tự deploy mỗi lần push; máy Oracle thì không. Không có bước này, version.json và
# remote-screen.apk trên máy chủ đứng yên và điện thoại không bao giờ nhận bản mới.
log "Tạo bộ hẹn giờ tự cập nhật (5 phút/lần)"
cat > /usr/local/bin/remote-screen-update <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd ${APP_DIR}
git fetch --depth 1 origin ${BRANCH} -q
[ "\$(git rev-parse HEAD)" = "\$(git rev-parse origin/${BRANCH})" ] && exit 0
git checkout -B ${BRANCH} origin/${BRANCH} -q
(cd server && npm install --omit=dev --no-audit --no-fund --silent)
chown -R ${SVC_USER}:${SVC_USER} server
systemctl restart remote-screen
echo "updated to \$(git rev-parse --short HEAD)"
EOF
chmod 755 /usr/local/bin/remote-screen-update
cat > /etc/systemd/system/remote-screen-update.service <<EOF
[Unit]
Description=Pull the newest Remote Screen server/APK from git
[Service]
Type=oneshot
ExecStart=/usr/local/bin/remote-screen-update
EOF
cat > /etc/systemd/system/remote-screen-update.timer <<EOF
[Unit]
Description=Update Remote Screen every 5 minutes
[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now remote-screen-update.timer

# ---------------------------------------------------------------- HTTPS
log "Cấu hình Caddy (HTTPS tự động) cho $DOMAIN"
cat > /etc/caddy/Caddyfile <<EOF
${DOMAIN} {
	encode gzip
	reverse_proxy 127.0.0.1:8080
}
EOF
systemctl enable caddy
systemctl reload caddy || systemctl restart caddy

# ---------------------------------------------------------------- kiểm tra
log "Kiểm tra"
sleep 3
if curl -fsS -m 5 http://127.0.0.1:8080/healthz >/dev/null; then echo "OK   server Node chạy (127.0.0.1:8080)"; else echo "LỖI server Node: journalctl -u remote-screen -n 50"; fi
if curl -fsS -m 30 "https://${DOMAIN}/healthz" >/dev/null; then echo "OK   https://${DOMAIN}/healthz"; else
  echo "CHƯA https://${DOMAIN}: kiểm tra Security List đã mở TCP 80/443 chưa, rồi: journalctl -u caddy -n 50"; fi

cat <<EOF

Xong. Việc còn lại (làm trên web Oracle Cloud — script không làm được):
  Networking → Virtual Cloud Networks → VCN → Security Lists → Default → Add Ingress Rules (nguồn 0.0.0.0/0):
    TCP 80, TCP 443, TCP 3478, UDP 3478, UDP 49152-65535

Sau đó, trên máy tính của bạn (không phải máy Oracle):
    node deploy/oracle/check-turn.js ${PUBLIC_IP} <SG_TURN_USER> <SG_TURN_PASS>
    mở https://${DOMAIN}/turncheck và https://${DOMAIN}/fcmcheck

Địa chỉ máy chủ mới cho app/web: https://${DOMAIN}
EOF
