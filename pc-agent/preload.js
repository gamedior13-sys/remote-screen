'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getCfg: () => ipcRenderer.invoke('cfg-get'),
  setCfg: (c) => ipcRenderer.invoke('cfg-set', c),
  // Lệnh điều khiển (fire-and-forget, không cần đợi kết quả)
  exec: (cmd) => { try { ipcRenderer.send('ctrl-exec', cmd); } catch (_) {} },
});
