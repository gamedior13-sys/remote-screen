# Chuyển server signaling từ Render sang máy Oracle (Singapore)

Lý do (#11): Render free đặt ở Mỹ và ngủ khi rảnh. Mỗi message bắt tay WebRTC (SDP, ICE) đi vòng
Việt Nam ↔ Mỹ, nên kết nối và kết nối lại chậm. Máy Oracle ở Singapore đã chạy coturn và luôn bật.

Thứ tự bên dưới giữ cho điện thoại **không lúc nào mất đường kết nối**. Điện thoại chỉ đổi sang máy
chủ mới sau khi máy chủ mới đã chạy và đã được kiểm tra.

## 1. Dựng máy chủ (trên máy Oracle, qua SSH)

```bash
git clone https://github.com/gamedior13-sys/remote-screen.git ~/remote-screen
sudo TURN_USER=<user-turn> TURN_PASS='<mật-khẩu-turn>' bash ~/remote-screen/deploy/oracle/setup.sh
```

- Bỏ `TURN_USER`/`TURN_PASS` nếu muốn **giữ nguyên** cấu hình coturn đang chạy.
- Script sẽ cài Node 22, Caddy (HTTPS tự động qua `<ip>.sslip.io`), mở iptables, tạo dịch vụ
  `remote-screen` và bộ hẹn giờ tự `git pull` mỗi 5 phút. Bộ hẹn giờ thay cho cơ chế auto-deploy
  của Render; nhờ nó máy chủ luôn có APK mới cho điện thoại.

## 2. Mở cổng ở Oracle Cloud (bắt buộc, script không làm được)

Networking → Virtual Cloud Networks → (VCN của máy) → Security Lists → Default → **Add Ingress Rules**,
nguồn `0.0.0.0/0`:

| Giao thức | Cổng |
|---|---|
| TCP | 80, 443 |
| TCP + UDP | 3478 |
| UDP | **49152-65535** ← dải cổng relay. Thiếu dải này là nguyên nhân hay gặp nhất của hình chậm, mờ, ping cao |

## 3. Điền biến môi trường

`sudo nano /etc/remote-screen.env`: chép `CF_TURN_KEY_ID`, `CF_TURN_TOKEN`, `FIREBASE_SERVICE_ACCOUNT`
từ Render (Dashboard → service → Environment). Kiểm tra `SG_TURN_USER`/`SG_TURN_PASS` khớp với coturn.
Sau đó chạy: `sudo systemctl restart remote-screen`

## 4. Kiểm tra TURN

Chạy trên **máy tính của bạn**, không chạy trên chính máy Oracle:

```bash
node deploy/oracle/check-turn.js <IP-Oracle> <SG_TURN_USER> <SG_TURN_PASS>
```

Cần thấy cả 3 dòng `OK`. Nếu dòng "Gói từ ngoài đi vào cổng relay" báo `LỖI` thì xem lại bước 2.

Mở thêm `https://<ip>.sslip.io/healthz` (phải ra `ok`) và `/fcmcheck` (`db: ok`).

## 5. Thử với một điện thoại trước khi chuyển hẳn

Trong app: **Nâng cao** → địa chỉ máy chủ = `https://<ip>.sslip.io` → Bắt đầu chia sẻ.
Trên trình duyệt mở `https://<ip>.sslip.io`, kết nối, xem badge (ping, fps, UDP hay TCP).

## 6. Chuyển mặc định

1. Merge nhánh `migrate/oracle-default`. Nhánh này đổi `DEFAULT_SERVER` của app Android và PC.
   CI build APK, Render và Oracle đều nhận bản mới, điện thoại tự cập nhật, và sau khi cập nhật thì dùng Oracle.
2. Trên Render đặt `REDIRECT_TO=https://<ip>.sslip.io`. Trình duyệt mở link cũ sẽ được chuyển sang
   trang mới. APK, `version.json` và WebSocket ở Render vẫn chạy cho máy chưa kịp cập nhật.
3. Khi `/fcmcheck` trên Render không còn máy nào mới đăng ký thì có thể tắt Render.

## Vận hành

| Việc | Lệnh |
|---|---|
| Log server | `journalctl -u remote-screen -f` |
| Log coturn | `sudo tail -f /var/log/turnserver/turnserver.log` |
| Cập nhật ngay | `sudo systemctl start remote-screen-update` |
| Lịch sử chẩn đoán một máy | `curl -s -X POST https://<ip>.sslip.io/diag -H 'Content-Type: application/json' -d '{"id":"<ID>","password":"<mk>"}'` |
