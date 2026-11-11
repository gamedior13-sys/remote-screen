'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getCfg: () => ipcRenderer.invoke('cfg-get'),
  setCfg: (c) => ipcRenderer.invoke('cfg-set', c),
  // Lệnh điều khiển (fire-and-forget, không cần đợi kết quả)
  exec: (cmd) => { try { ipcRenderer.send('ctrl-exec', cmd); } catch (_) {} },
  // Bật/tắt tự khởi động cùng Windows
  autostart: () => { try { ipcRenderer.send('enable-autostart'); } catch (_) {} },
  // Báo trạng thái ra file (standby/sharing/...) để kiểm chứng từ ngoài
  report: (s) => { try { ipcRenderer.send('status-report', s); } catch (_) {} },
  // Nhận lệnh từ khay hệ thống (standby...)
  onCmd: (cb) => { try { ipcRenderer.on('cmd', (_e, c) => cb(c)); } catch (_) {} },
});
