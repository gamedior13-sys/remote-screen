'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getCfg: () => ipcRenderer.invoke('cfg-get'),
  setCfg: (c) => ipcRenderer.invoke('cfg-set', c),
});
