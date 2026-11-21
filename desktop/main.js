// 치지직 데스크톱 클라이언트 (Electron 메인 프로세스)
//  - 화면 파일(renderer/)은 app://chzzk/ 출처로 제공 → 서버의 DESKTOP_ORIGINS 와 같아야 조회 API·시청 WebSocket 이 허용됨
//  - 서버 주소: 빌드 때 package.json 의 chzzkServerUrl (CI 에서 저장소 변수로 주입), 사용자가 설정에서 바꾸면 userData/settings.json 에 저장
const { app, BrowserWindow, protocol, net, ipcMain, shell, Menu } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const pkg = require('./package.json');

const SCHEME = 'app';
const HOST = 'chzzk';
const RENDERER = path.join(__dirname, 'renderer');
const DEFAULT_SERVER = pkg.chzzkServerUrl;

// standard + secure: app://chzzk 가 https 와 같은 보안 출처로 취급되어 fetch·WebSocket·미디어 재생이 가능
protocol.registerSchemesAsPrivileged([
  { scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } },
]);

// 라이브는 음소거 없이 바로 재생 (사용자가 방송 카드를 눌러 들어온 화면이므로)
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');

function readSettings() {
  try {
    return JSON.parse(fs.readFileSync(settingsFile(), 'utf8'));
  } catch {
    return {};
  }
}

function normalizeServer(raw) {
  const u = new URL(String(raw || '').trim());
  if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new Error('http:// 또는 https:// 주소를 입력하세요.');
  return u.origin; // 경로·끝 슬래시 제거
}

function currentServer() {
  const saved = readSettings().serverUrl;
  try {
    return normalizeServer(saved || DEFAULT_SERVER);
  } catch {
    return DEFAULT_SERVER;
  }
}

ipcMain.handle('config:get', () => ({
  serverUrl: currentServer(),
  defaultServerUrl: DEFAULT_SERVER,
  version: app.getVersion(),
  platform: `${process.platform}-${process.arch}`,
}));

ipcMain.handle('config:set', (_e, raw) => {
  const serverUrl = raw ? normalizeServer(raw) : null;
  const next = { ...readSettings(), serverUrl };
  fs.mkdirSync(path.dirname(settingsFile()), { recursive: true });
  fs.writeFileSync(settingsFile(), JSON.stringify(next, null, 2));
  return currentServer();
});

ipcMain.handle('open:external', (_e, url) => {
  const u = new URL(url);
  if (u.protocol === 'https:' || u.protocol === 'http:') return shell.openExternal(u.toString());
  return undefined;
});

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 720,
    minHeight: 520,
    title: '치지직',
    backgroundColor: '#0e0f12',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  // 앱 화면 밖으로의 이동·새 창은 기본 브라우저로
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(`${SCHEME}://${HOST}/`)) {
      e.preventDefault();
      if (/^https?:/i.test(url)) shell.openExternal(url);
    }
  });
  // 카메라·마이크 등은 요청하지 않음 (시청 전용)
  win.webContents.session.setPermissionRequestHandler((_wc, permission, cb) => cb(permission === 'fullscreen'));
  win.loadURL(`${SCHEME}://${HOST}/index.html`);
  if (process.env.CHZZK_SMOKE) smokeTest(win);
}

// 자동 점검 (CHZZK_SMOKE=1): 목록을 불러온 뒤 화면 상태를 출력하고, CHZZK_SMOKE_OPEN(예: replay/1, live/2)을 열어
// 영상이 재생되는지 확인한 다음 종료합니다. 빌드한 패키지가 실제로 서버와 통신하는지 사람 없이 확인하는 용도.
function smokeTest(win) {
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const js = (code) => win.webContents.executeJavaScript(code);
  win.webContents.once('did-finish-load', async () => {
    const out = {};
    try {
      await wait(3000);
      out.list = await js(`({ server: document.getElementById('server-label').textContent,
        live: document.querySelectorAll('#live-grid .card').length,
        replays: document.querySelectorAll('#replay-grid .card').length,
        ended: document.querySelectorAll('#ended-grid .card').length,
        error: document.getElementById('error').textContent })`);
      if (process.env.CHZZK_SMOKE_OPEN) {
        await js(`location.hash = '#/${process.env.CHZZK_SMOKE_OPEN}'`);
        await wait(Number(process.env.CHZZK_SMOKE_WAIT || 6000));
        out.watch = await js(`(() => { const v = document.getElementById('player');
          return { title: document.getElementById('title').textContent,
            readyState: v.readyState, currentTime: v.currentTime, paused: v.paused,
            hasStream: !!v.srcObject, videoWidth: v.videoWidth,
            overlay: document.getElementById('overlay').hidden ? '' : document.getElementById('overlay').textContent }; })()`);
      }
      if (process.env.CHZZK_SMOKE_SHOT) {
        const img = await win.webContents.capturePage();
        fs.writeFileSync(process.env.CHZZK_SMOKE_SHOT, img.toPNG());
      }
    } catch (e) {
      out.failure = String(e);
    }
    console.log('CHZZK_SMOKE ' + JSON.stringify(out));
    app.exit(0);
  });
}

app.whenReady().then(() => {
  // app://chzzk/<경로> → renderer/<경로> (renderer 폴더 밖은 거부)
  protocol.handle(SCHEME, (request) => {
    const { host, pathname } = new URL(request.url);
    const file = path.normalize(path.join(RENDERER, decodeURIComponent(pathname)));
    if (host !== HOST || !file.startsWith(RENDERER + path.sep)) return new Response('Not found', { status: 404 });
    return net.fetch(pathToFileURL(file).toString());
  });

  if (process.platform === 'darwin') {
    Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }, { role: 'viewMenu' }, { role: 'windowMenu' }]));
  } else {
    Menu.setApplicationMenu(null);
  }
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
