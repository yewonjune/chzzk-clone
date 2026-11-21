// 치지직 데스크톱 화면: 서버의 공개 조회 API 로 방송·다시보기 목록을 보여주고 재생합니다.
//  - 다시보기: <video> 로 서버의 영상 주소 재생 (구간 탐색 = Range 요청)
//  - 라이브: 웹 시청 화면(live.html)과 같은 시그널링 프로토콜로 WebRTC 시청 (시청자로만 입장, 쿠키 없음)
'use strict';

const $ = (id) => document.getElementById(id);
const state = { server: '', config: null, listTimer: null, watch: null };

// ---- 서버 호출 ----

function abs(url) {
  return new URL(url, state.server).toString();
}

async function api(path) {
  let res;
  try {
    res = await fetch(abs(path), { headers: { Accept: 'application/json' }, credentials: 'omit' });
  } catch {
    throw new Error(`서버(${state.server})에 연결할 수 없습니다. 주소와 인터넷 연결을 확인해 주세요.`);
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `요청 실패 (${res.status})`);
  return body;
}

// ---- 표시 도우미 ----

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const name = (b) => b?.owner?.nickname || b?.streamer || '익명';
function duration(sec) {
  if (!sec || !isFinite(sec)) return '';
  sec = Math.round(sec);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}
const elapsed = (from, to) => duration(Math.max(1, ((to ? new Date(to) : new Date()) - new Date(from)) / 1000)) || '0:00';
const time = (iso) => new Date(iso).toLocaleString('ko-KR');
function setError(msg) { $('error').textContent = msg || ''; }

// ---- 목록 ----

function liveCard(b) {
  const on = b.status === 'live';
  return `<a class="card ${on ? '' : 'ended'}" href="#/live/${b.id}">
    <div class="live-thumb"><span class="who">${esc(name(b))}</span>
      <span class="badge ${on ? 'live' : 'ended'}">${on ? '<span class="dot"></span>LIVE' : '종료'}</span>
      <span class="badge count">${on ? `시청자 ${b.viewerCount || 0}명` : elapsed(b.startedAt, b.endedAt)}</span></div>
    <div class="card-title">${esc(b.title)}</div>
    <div class="meta">${esc(name(b))} · ${on ? `${elapsed(b.startedAt)} 째 방송 중` : `${time(b.startedAt)}`}</div></a>`;
}

function replayCard(r) {
  return `<a class="card" href="#/replay/${r.id}">
    <div class="thumb"><div class="ph">▶</div><span class="badge rec">다시보기</span>
      ${r.durationSec ? `<span class="badge dur">${duration(r.durationSec)}</span>` : ''}</div>
    <div class="card-title">${esc(r.title)}</div>
    <div class="meta">${esc(name(r))} · 조회수 ${Number(r.views || 0).toLocaleString('ko-KR')}회</div></a>`;
}

async function loadList() {
  setError('');
  try {
    const [broadcasts, replays] = await Promise.all([api('/api/broadcasts'), api('/api/replays')]);
    const live = broadcasts.filter((b) => b.status === 'live');
    const ended = broadcasts.filter((b) => b.status !== 'live');
    $('live-grid').innerHTML = live.map(liveCard).join('');
    $('ended-grid').innerHTML = ended.map(liveCard).join('');
    $('replay-grid').innerHTML = replays.map(replayCard).join('');
    $('live-empty').hidden = live.length > 0;
    $('ended-empty').hidden = ended.length > 0;
    $('replay-empty').hidden = replays.length > 0;
    $('live-count').textContent = live.length ? `${live.length}개 방송 중` : '';
    $('replay-count').textContent = replays.length ? `${replays.length}개` : '';
  } catch (e) {
    setError(e.message);
  }
}

// ---- 재생 ----

function resetPlayer() {
  const v = $('player');
  v.pause();
  v.removeAttribute('src');
  v.srcObject = null;
  v.controls = false;
  v.muted = false;
  v.load();
  $('overlay').hidden = true;
  $('viewers').hidden = true;
  $('btn-unmute').hidden = true;
  $('desc').textContent = '';
  $('meta').textContent = '';
}

function overlay(text) {
  $('overlay').hidden = false;
  $('overlay').textContent = text;
}

async function openReplay(id) {
  const r = await api(`/api/replays/${encodeURIComponent(id)}`);
  $('title').textContent = r.title;
  $('streamer').textContent = name(r);
  $('meta').textContent = `${time(r.createdAt)} · 조회수 ${Number(r.views || 0).toLocaleString('ko-KR')}회${r.durationSec ? ` · ${duration(r.durationSec)}` : ''}`;
  $('desc').textContent = r.description || '';
  $('badge').className = 'badge rec';
  $('badge').textContent = '다시보기';
  $('open-web').dataset.url = abs(`/replay.html?id=${r.id}`);
  const v = $('player');
  v.controls = true;
  v.src = abs(r.videoUrl);
  v.onerror = () => overlay('영상을 재생할 수 없습니다. 파일이 없거나 지원하지 않는 형식일 수 있습니다.');
  v.play().catch(() => {});
}

async function openLive(id) {
  const b = await api(`/api/broadcasts/${encodeURIComponent(id)}`);
  $('title').textContent = b.title;
  $('streamer').textContent = name(b);
  $('open-web').dataset.url = abs(`/live.html?id=${b.id}`);
  if (b.status !== 'live') return showEnded(b, '종료된 방송입니다. 방송은 녹화되지 않아 다시 볼 수 없습니다.');
  $('badge').className = 'badge live';
  $('badge').innerHTML = '<span class="dot"></span>LIVE';
  overlay('스트리머와 연결하는 중…');
  const config = await api('/api/live-config').catch(() => ({ iceServers: [] }));
  const w = { id: b.id, startedAt: b.startedAt, iceServers: config.iceServers || [], pc: null, pending: [], viewerId: null, ws: null, stopped: false, attempt: 0, timer: null, tick: null };
  state.watch = w;
  w.tick = setInterval(() => { $('meta').textContent = `${time(b.startedAt)} 시작 · 방송 ${elapsed(b.startedAt)}`; }, 1000);
  connect(w);
}

function showEnded(b, message) {
  $('badge').className = 'badge ended';
  $('badge').textContent = '종료';
  $('meta').textContent = `${time(b.startedAt)} 시작${b.endedAt ? ` · ${time(b.endedAt)} 종료` : ''}`;
  overlay(message);
}

// 시그널링: 비정상 종료면 다시 연결 (4001 = 서버 교체 → 바로), 1000/1008(종료·가득 참·거절)이면 멈춤
function connect(w) {
  if (w.stopped) return;
  const url = state.server.replace(/^http/, 'ws') + `/ws/broadcasts/${w.id}`;
  const ws = new WebSocket(url);
  w.ws = ws;
  const send = (m) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(m)); };
  w.send = send;
  ws.onopen = () => {
    w.attempt = 0;
    const alive = w.pc && ['connected', 'connecting', 'disconnected'].includes(w.pc.connectionState);
    if (w.viewerId && alive) send({ type: 'join', role: 'viewer', viewerId: w.viewerId });
    else if (w.viewerId) { closePeer(w); send({ type: 'join', role: 'viewer', viewerId: w.viewerId, startOver: true }); }
    else send({ type: 'join', role: 'viewer' });
  };
  ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    onMessage(w, m).catch((e) => console.error(e));
  };
  ws.onclose = (ev) => {
    if (w.ws !== ws || w.stopped) return;
    if (ev.code === 1000 || ev.code === 1008) {
      if (!w.endedShown) overlay(ev.code === 1008 ? '서버가 연결을 거절했습니다. (서버의 DESKTOP_ORIGINS 설정을 확인하세요)' : '연결이 종료되었습니다.');
      return;
    }
    if (!(w.pc && w.pc.connectionState === 'connected')) overlay('서버와 다시 연결하는 중…');
    const delay = ev.code === 4001 && w.attempt === 0 ? 0 : Math.min(10000, 1000 * 2 ** Math.min(w.attempt, 3));
    w.attempt++;
    w.timer = setTimeout(() => connect(w), delay);
  };
}

async function onMessage(w, m) {
  if (m.type === 'joined') {
    w.viewerId = m.viewerId;
  } else if (m.type === 'viewers') {
    $('viewers').hidden = false;
    $('viewers').textContent = `시청자 ${Number(m.count || 0).toLocaleString('ko-KR')}명`;
  } else if (m.type === 'full') {
    overlay(`시청자가 가득 찼습니다. (최대 ${m.max}명) 잠시 후 다시 시도해 주세요.`);
    stopWatch();
  } else if (m.type === 'ended') {
    w.endedShown = true;
    const b = await api(`/api/broadcasts/${w.id}`).catch(() => ({ startedAt: w.startedAt }));
    stopWatch();
    $('player').srcObject = null;
    $('viewers').hidden = true;
    showEnded(b, '방송이 종료되었습니다.');
  } else if (m.type === 'title') {
    $('title').textContent = m.title;
  } else if (m.type === 'signal') {
    await onSignal(w, m.data);
  }
}

async function onSignal(w, data) {
  if (data.sdp) {
    closePeer(w);
    const pc = new RTCPeerConnection({ iceServers: w.iceServers });
    w.pc = pc;
    pc.ontrack = (e) => {
      const v = $('player');
      if (v.srcObject !== e.streams[0]) v.srcObject = e.streams[0];
      $('overlay').hidden = true;
      v.play().catch(() => {
        // 자동 재생이 막히면 음소거로 시작하고 버튼으로 소리 켜기
        v.muted = true;
        v.play().catch(() => {});
        $('btn-unmute').hidden = false;
      });
    };
    pc.onicecandidate = (e) => { if (e.candidate) w.send({ type: 'signal', data: { candidate: e.candidate } }); };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed') overlay('영상 연결에 실패했습니다. 스트리머와 다른 네트워크라면 서버에 TURN 설정이 필요할 수 있습니다.');
    };
    await pc.setRemoteDescription(data.sdp);
    for (const c of w.pending.splice(0)) await pc.addIceCandidate(c).catch(() => {});
    await pc.setLocalDescription(await pc.createAnswer());
    w.send({ type: 'signal', data: { sdp: pc.localDescription } });
  } else if (data.candidate) {
    if (w.pc?.remoteDescription) await w.pc.addIceCandidate(data.candidate).catch(() => {});
    else w.pending.push(data.candidate);
  }
}

function closePeer(w) {
  w.pc?.close();
  w.pc = null;
  w.pending = [];
}

function stopWatch() {
  const w = state.watch;
  if (!w) return;
  w.stopped = true;
  clearTimeout(w.timer);
  clearInterval(w.tick);
  closePeer(w);
  if (w.ws && w.ws.readyState <= WebSocket.OPEN) w.ws.close(1000);
  state.watch = null;
}

// ---- 화면 전환 (#/ , #/live/1 , #/replay/1) ----

async function route() {
  stopWatch();
  resetPlayer();
  setError('');
  const [, kind, id] = location.hash.replace(/^#/, '').split('/');
  const watching = kind === 'live' || kind === 'replay';
  $('list-view').hidden = watching;
  $('watch-view').hidden = !watching;
  clearInterval(state.listTimer);
  if (!watching) {
    document.title = '치지직';
    await loadList();
    state.listTimer = setInterval(loadList, 10000); // 새 방송·종료를 10초마다 반영
    return;
  }
  try {
    if (kind === 'live') await openLive(id);
    else await openReplay(id);
    document.title = `${$('title').textContent} - 치지직`;
  } catch (e) {
    overlay(e.message);
  }
}

// ---- 설정 ----

async function applyConfig() {
  state.config = await window.chzzkDesktop.getConfig();
  state.server = state.config.serverUrl;
  $('server-label').textContent = state.server.replace(/^https?:\/\//, '');
  $('server-default').textContent = state.config.defaultServerUrl;
  $('app-version').textContent = `치지직 데스크톱 ${state.config.version} (${state.config.platform})`;
}

function openSettings() {
  $('server-input').value = state.server;
  $('settings-error').textContent = '';
  $('settings').showModal();
}

$('btn-settings').onclick = openSettings;
$('btn-close').onclick = () => $('settings').close();
$('btn-reset').onclick = async () => {
  await window.chzzkDesktop.setServerUrl(null);
  await applyConfig();
  $('settings').close();
  route();
};
$('settings-form').onsubmit = async (e) => {
  e.preventDefault();
  try {
    await window.chzzkDesktop.setServerUrl($('server-input').value);
    await applyConfig();
    $('settings').close();
    location.hash = '#/';
    route();
  } catch (err) {
    $('settings-error').textContent = String(err.message || err).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
  }
};
$('btn-refresh').onclick = () => route();
$('btn-back').onclick = () => { location.hash = '#/'; };
$('btn-unmute').onclick = () => {
  const v = $('player');
  v.muted = false;
  v.play().catch(() => {});
  $('btn-unmute').hidden = true;
};
$('open-web').onclick = (e) => {
  e.preventDefault();
  if (e.currentTarget.dataset.url) window.chzzkDesktop.openExternal(e.currentTarget.dataset.url);
};
window.addEventListener('hashchange', route);

applyConfig().then(route);
