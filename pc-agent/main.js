'use strict';
/**
 * Remote Screen - PC partner (Windows), Electron main process.
 *
 * Giai đoạn 1: kết nối tới server signaling, đăng ký ID + mật khẩu, và chia sẻ màn hình PC
 * (WebRTC, PC là bên tạo offer) để điều khiển từ điện thoại qua trang web.
 * Điều khiển chuột/bàn phím, clipboard và gửi file sẽ thêm ở các giai đoạn sau.
 */
const { app, BrowserWindow, ipcMain, session, desktopCapturer } = require('electron');
const path = require('path');
const fs = require('fs');

// Chỉ cho chạy một bản.
if (!app.requestSingleInstanceLock()) { app.quit(); }

let win = null;
const cfgPath = () => path.join(app.getPath('userData'), 'config.json');
function loadCfg() { try { return JSON.parse(fs.readFileSync(cfgPath(), 'utf8')); } catch { return {}; } }
function saveCfg(c) { try { fs.writeFileSync(cfgPath(), JSON.stringify(c)); } catch (_) {} }

function createWindow() {
  win = new BrowserWindow({
    width: 760,
    height: 660,
    resizable: true,
    backgroundColor: '#0b0f17',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.setMenuBarVisibility(false);
  win.loadFile('index.html');
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

app.whenReady().then(() => {
  // Tự cấp quyền chia sẻ TOÀN BỘ màn hình chính cho getDisplayMedia — không hiện hộp chọn,
  // để sau này có thể tự chia sẻ không cần thao tác tay.
  session.defaultSession.setDisplayMediaRequestHandler((request, callback) => {
    desktopCapturer.getSources({ types: ['screen'] }).then((sources) => {
      if (sources && sources.length) callback({ video: sources[0], audio: false });
      else callback({});
    }).catch(() => callback({}));
  }, { useSystemPicker: false });

  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });

ipcMain.handle('cfg-get', () => loadCfg());
ipcMain.handle('cfg-set', (_e, c) => { saveCfg(c || {}); return true; });
