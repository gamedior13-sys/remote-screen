'use strict';
/**
 * Remote Screen - PC partner (Windows), Electron main process.
 *
 * Giai đoạn 2.5:
 *  - Chia sẻ màn hình + THỰC THI lệnh điều khiển từ web (touch -> chuột, phím, text).
 *  - CHẾ ĐỘ CHỜ (standby): app chạy nền ẩn, đăng ký standby:true; web bấm kết nối
 *    -> server gửi 'wake' -> app tự bật chia sẻ, không cần ai đụng vào máy tính.
 *  - Tự khởi động cùng Windows (Run key trỏ tới file exe thật + --standby).
 *  - Chuột ảo: nhận mdbl/mrc/mscroll (đúp, chuột phải, cuộn) ngoài tap/swipe thường.
 * Thực thi qua một tiến trình PowerShell nền bền dùng Win32 SendInput — không cần native module.
 */
const { app, BrowserWindow, ipcMain, session, desktopCapturer, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

// Chỉ cho chạy một bản.
if (!app.requestSingleInstanceLock()) { app.quit(); }

const START_STANDBY = process.argv.includes('--standby') || process.argv.includes('--silent');
let win = null;
let tray = null;
let quitting = false;
const cfgPath = () => path.join(app.getPath('userData'), 'config.json');
const statusPath = () => path.join(app.getPath('userData'), 'status.json');
function loadCfg() { try { return JSON.parse(fs.readFileSync(cfgPath(), 'utf8')); } catch { return {}; } }
function saveCfg(c) { try { fs.writeFileSync(cfgPath(), JSON.stringify(c)); } catch (_) {} }

const TRAY_ICON = 'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAo0lEQVR4nO2XwQmAMAxF048j6Ah2MR1LF9MRdAfFQ0EktRg08ZB3TeH9NNBQImNCrtB2y/amaB4b1hW+FpeCQFPOOaAp51wgY6DdfSI5QcbAOkB1V5yG+jVR7Nd/3gA8APkIjIEHIB/Bn3dBzLzfagEkS+ppaDw6XZBIbgySAJxMOi6QMbj7NJRIXUu6T05IxFwIKbgm0uDsQq6gIT8w/5yaswN2JzXw3WveKAAAAABJRU5ErkJggg==';

function createWindow(hidden) {
  win = new BrowserWindow({
    width: 760,
    height: 660,
    resizable: true,
    show: !hidden,
    backgroundColor: '#0b0f17',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });
  win.setMenuBarVisibility(false);
  win.loadFile('index.html', hidden ? { query: { standby: '1' } } : {});
  // Đóng cửa sổ = ẩn xuống khay, app vẫn chạy để điện thoại đánh thức được bất cứ lúc nào.
  win.on('close', (e) => {
    if (!quitting) { e.preventDefault(); win.hide(); }
  });
}

function showWindow() {
  if (!win) createWindow(false);
  else { win.show(); win.focus(); }
}

function createTray() {
  try {
    tray = new Tray(nativeImage.createFromBuffer(Buffer.from(TRAY_ICON, 'base64')));
    tray.setToolTip('Remote Screen PC');
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Mở Remote Screen PC', click: () => showWindow() },
      { label: 'Chờ kết nối từ xa (standby)', click: () => { try { if (win) win.webContents.send('cmd', 'standby'); win && win.hide(); } catch (_) {} } },
      { type: 'separator' },
      { label: 'Thoát hẳn', click: () => { quitting = true; app.quit(); } },
    ]));
    tray.on('double-click', () => showWindow());
  } catch (e) { console.warn('tray failed', e); }
}

app.on('second-instance', () => { if (win) { win.show(); if (win.isMinimized()) win.restore(); win.focus(); } });

app.whenReady().then(() => {
  // Tự cấp quyền chia sẻ TOÀN BỘ màn hình chính cho getDisplayMedia — không hiện hộp chọn,
  // để chế độ standby tự chia sẻ được khi được đánh thức từ xa.
  session.defaultSession.setDisplayMediaRequestHandler((request, callback) => {
    desktopCapturer.getSources({ types: ['screen'] }).then((sources) => {
      if (sources && sources.length) callback({ video: sources[0], audio: false });
      else callback({});
    }).catch(() => callback({}));
  }, { useSystemPicker: false });

  createWindow(START_STANDBY);
  createTray();
  app.on('activate', () => showWindow());
});

app.on('window-all-closed', () => { /* giữ app chạy nền cho standby — thoát qua khay */ });
app.on('before-quit', () => { quitting = true; try { if (worker) worker.kill(); } catch (_) {} });

ipcMain.handle('cfg-get', () => loadCfg());
ipcMain.handle('cfg-set', (_e, c) => { saveCfg(c || {}); return true; });

// Tự khởi động cùng Windows: Run key trỏ tới file exe ĐANG CHẠY (portable exe
// đặt biến PORTABLE_EXECUTABLE_FILE = đường dẫn exe gốc, không phải bản tạm).
ipcMain.on('enable-autostart', () => {
  try {
    const exe = process.env.PORTABLE_EXECUTABLE_FILE || process.execPath;
    app.setLoginItemSettings({ openAtLogin: true, args: ['--standby'], path: exe });
    console.log('[autostart]', exe);
  } catch (e) { console.warn('autostart failed', e); }
});

ipcMain.on('disable-autostart', () => { try { app.setLoginItemSettings({ openAtLogin: false }); } catch (_) {} });

// Ghi trạng thái ra file để kiểm chứng từ ngoài (test tự động, debug).
ipcMain.on('status-report', (_e, s) => {
  try {
    const st = Object.assign({}, s || {}, { ts: Date.now() });
    fs.writeFileSync(statusPath(), JSON.stringify(st));
    if (tray) tray.setToolTip('Remote Screen PC — ' + (st.state || '?') + (st.id ? ' · ID ' + st.id : ''));
  } catch (_) {}
});

// ---------------------------------------------------------------------------
// Thực thi lệnh điều khiển: một PowerShell ẩn chạy lâu dài, đọc JSON từng dòng
// từ stdin và bắn Win32 SendInput. Không cần module native nên CI build được.
// ---------------------------------------------------------------------------
const WORKER_PS = `
$ErrorActionPreference = 'Continue'
$cs = @'
using System;
using System.Runtime.InteropServices;
public static class Inj {
  [StructLayout(LayoutKind.Sequential)]
  struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)]
  struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Explicit)]
  struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
  [StructLayout(LayoutKind.Sequential)]
  struct INPUT { public uint type; public INPUTUNION u; }
  [DllImport("user32.dll", SetLastError = false)] static extern uint SendInput(uint n, INPUT[] pInput, int cbSize);
  const uint MOUSE_MOVE = 0x0001, MOUSE_ABS = 0x8000, MOUSE_LDOWN = 0x0002, MOUSE_LUP = 0x0004;
  const uint MOUSE_RDOWN = 0x0008, MOUSE_RUP = 0x0010, MOUSE_MDOWN = 0x0020, MOUSE_MUP = 0x0040, MOUSE_WHEEL = 0x0800;
  const uint KEY_UP = 0x0002, KEY_UNICODE = 0x0004;
  static void Send(INPUT[] a) { SendInput((uint)a.Length, a, Marshal.SizeOf(typeof(INPUT))); }
  static INPUT M(uint f, int dx, int dy, uint data) { INPUT i = new INPUT(); i.type = 0; i.u.mi.dx = dx; i.u.mi.dy = dy; i.u.mi.dwFlags = f; i.u.mi.mouseData = data; return i; }
  static INPUT K(ushort vk, ushort scan, uint f) { INPUT i = new INPUT(); i.type = 1; i.u.ki.wVk = vk; i.u.ki.wScan = scan; i.u.ki.dwFlags = f; return i; }
  public static void MoveAbs(double nx, double ny) {
    int x = (int)Math.Round(nx * 65535.0), y = (int)Math.Round(ny * 65535.0);
    if (x < 0) x = 0; if (x > 65535) x = 65535; if (y < 0) y = 0; if (y > 65535) y = 65535;
    INPUT[] a = new INPUT[1]; a[0] = M(MOUSE_MOVE | MOUSE_ABS, x, y, 0); Send(a);
  }
  public static void Button(bool down, string b) {
    uint f = b == "right" ? (down ? MOUSE_RDOWN : MOUSE_RUP) : b == "middle" ? (down ? MOUSE_MDOWN : MOUSE_MUP) : (down ? MOUSE_LDOWN : MOUSE_LUP);
    INPUT[] a = new INPUT[1]; a[0] = M(f, 0, 0, 0); Send(a);
  }
  public static void Wheel(int d) { INPUT[] a = new INPUT[1]; a[0] = M(MOUSE_WHEEL, 0, 0, (uint)d); Send(a); }
  public static void Vk(ushort vk, bool down) { INPUT[] a = new INPUT[1]; a[0] = K(vk, 0, down ? (uint)0 : KEY_UP); Send(a); }
  public static void Uni(string s) {
    foreach (char c in s) { INPUT[] a = new INPUT[2]; a[0] = K(0, (ushort)c, KEY_UNICODE); a[1] = K(0, (ushort)c, KEY_UNICODE | KEY_UP); Send(a); }
  }
}
'@
try { Add-Type -TypeDefinition $cs | Out-Null } catch { [Console]::Out.WriteLine('ERR addtype ' + $_.Exception.Message) }
[Console]::Out.WriteLine('READY')
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  $j = $null
  try { $j = $line | ConvertFrom-Json } catch { continue }
  try {
    $op = [string]$j.op
    if ($op -eq 'move') { [Inj]::MoveAbs([double]$j.x, [double]$j.y) }
    elseif ($op -eq 'button') { [Inj]::Button([bool]$j.down, [string]$j.b) }
    elseif ($op -eq 'wheel') { [Inj]::Wheel([int]$j.d) }
    elseif ($op -eq 'keyvk') { [Inj]::Vk([uint16]$j.vk, [bool]$j.down) }
    elseif ($op -eq 'uni') { [Inj]::Uni([string]$j.s) }
    elseif ($op -eq 'tap') {
      [Inj]::MoveAbs([double]$j.x, [double]$j.y)
      Start-Sleep -Milliseconds 15
      [Inj]::Button($true, 'left')
      $d = [int][Math]::Max(15, [Math]::Min(1500, [double]$j.d))
      Start-Sleep -Milliseconds $d
      [Inj]::Button($false, 'left')
    }
    elseif ($op -eq 'dbl') {
      [Inj]::MoveAbs([double]$j.x, [double]$j.y)
      Start-Sleep -Milliseconds 15
      [Inj]::Button($true, 'left'); Start-Sleep -Milliseconds 40; [Inj]::Button($false, 'left')
      Start-Sleep -Milliseconds 110
      [Inj]::Button($true, 'left'); Start-Sleep -Milliseconds 40; [Inj]::Button($false, 'left')
    }
    elseif ($op -eq 'swipe') {
      $x1 = [double]$j.x1; $y1 = [double]$j.y1; $x2 = [double]$j.x2; $y2 = [double]$j.y2
      $ms = [int][Math]::Max(60, [Math]::Min(1500, [double]$j.ms))
      $steps = [int][Math]::Max(8, [Math]::Min(80, [int]($ms / 20)))
      $dt = [Math]::Max(1, [int]($ms / $steps))
      [Inj]::MoveAbs($x1, $y1)
      Start-Sleep -Milliseconds 15
      [Inj]::Button($true, 'left')
      for ($s = 1; $s -le $steps; $s++) {
        $t = $s / $steps
        [Inj]::MoveAbs($x1 + ($x2 - $x1) * $t, $y1 + ($y2 - $y1) * $t)
        Start-Sleep -Milliseconds $dt
      }
      [Inj]::Button($false, 'left')
    }
    elseif ($op -eq 'del') {
      $n = [int][Math]::Max(1, [Math]::Min(200, [int]$j.n))
      for ($s = 0; $s -lt $n; $s++) { [Inj]::Vk([uint16]8, $true); [Inj]::Vk([uint16]8, $false); Start-Sleep -Milliseconds 4 }
    }
  } catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message) }
}
`;

let worker = null;
const workerPsPath = () => path.join(app.getPath('userData'), 'pc-worker.ps1');

function startWorker() {
  try { if (worker) worker.kill(); } catch (_) {}
  try {
    const p = workerPsPath();
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, WORKER_PS, 'utf8');
    worker = spawn('powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', p],
      { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    worker.stdout.on('data', (d) => { const s = String(d); if (s.trim()) console.log('[pc-worker]', s.trim()); });
    worker.stderr.on('data', (d) => console.warn('[pc-worker!]', String(d).trim()));
    worker.on('exit', () => { worker = null; });
    worker.on('error', () => { worker = null; });
  } catch (e) { console.warn('startWorker failed', e); worker = null; }
}

function workerSend(obj) {
  if (!worker || !worker.stdin.writable) startWorker();
  if (!worker) return;
  try { worker.stdin.write(JSON.stringify(obj) + '\n'); }
  catch (_) { startWorker(); try { worker.stdin.write(JSON.stringify(obj) + '\n'); } catch (_) {} }
}

function tapVk(vk) { return [{ op: 'keyvk', vk, down: true }, { op: 'keyvk', vk, down: false }]; }
function comboVk(a, b) {
  return [{ op: 'keyvk', vk: a, down: true }, { op: 'keyvk', vk: b, down: true },
          { op: 'keyvk', vk: b, down: false }, { op: 'keyvk', vk: a, down: false }];
}

// Phím đặt tên từ web -> phím Windows (ý nghĩa tương đương trên điện thoại).
function keyCmd(k) {
  switch (String(k || '')) {
    case 'back': return tapVk(0x1B);            // Esc = "quay lại" của PC
    case 'backspace': return tapVk(0x08);
    case 'enter': return tapVk(0x0D);
    case 'home': return tapVk(0x5B);            // Win = màn hình chính
    case 'recents': return comboVk(0x5B, 0x09); // Win+Tab = Task View
    case 'notifications': return comboVk(0x5B, 0x4E); // Win+N
    case 'volume_up': return tapVk(0xAF);
    case 'volume_down': return tapVk(0xAE);
    case 'volume_mute': case 'mute': return tapVk(0xAD);
    case 'screenshot': return tapVk(0x2C);      // PrintScreen
    case 'tab': return tapVk(0x09);
    default: return null;                       // power / app riêng của Android: bỏ qua
  }
}

const pointers = new Set(); // các "ngón" đang đặt trên màn hình (PC: 1 con chuột)

function execute(c) {
  if (!c || typeof c !== 'object') return;
  switch (c.op) {
    case 'down':
      if (!pointers.size) { workerSend({ op: 'move', x: c.x, y: c.y }); workerSend({ op: 'button', down: true, b: 'left' }); }
      pointers.add(c.i); break;
    case 'move':
      if (pointers.size) workerSend({ op: 'move', x: c.x, y: c.y }); break;
    case 'up':
      pointers.delete(c.i);
      if (!pointers.size) workerSend({ op: 'button', down: false, b: 'left' }); break;
    case 'tap': workerSend({ op: 'tap', x: c.x, y: c.y, d: c.d || 60 }); break;
    case 'dbl': workerSend({ op: 'dbl', x: c.x, y: c.y }); break;
    case 'mrc':
      workerSend({ op: 'move', x: c.x, y: c.y });
      workerSend({ op: 'button', down: true, b: 'right' });
      workerSend({ op: 'button', down: false, b: 'right' });
      break;
    case 'swipe': workerSend({ op: 'swipe', x1: c.x1, y1: c.y1, x2: c.x2, y2: c.y2, ms: c.d || 300 }); break;
    case 'wheel': workerSend({ op: 'wheel', d: c.d || 0 }); break;
    case 'del': if (c.n > 0) workerSend({ op: 'del', n: Math.min(200, c.n | 0) }); break;
    case 'text': {
      const parts = String(c.s || '').split('\n');
      for (let i = 0; i < parts.length; i++) {
        if (parts[i]) workerSend({ op: 'uni', s: parts[i] });
        if (i < parts.length - 1) workerSend(...tapVk(0x0D));
      }
      break;
    }
    case 'key': { const seq = keyCmd(c.k); if (seq) seq.forEach(workerSend); break; }
    default: break;
  }
}

ipcMain.on('ctrl-exec', (_e, c) => { try { execute(c); } catch (_) {} });
