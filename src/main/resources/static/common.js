// 공통 유틸 + 로그인 상태
// - chi.ready: 현재 사용자와 CSRF 토큰을 불러온 뒤 끝나는 Promise
// - chi.api(): 상태를 바꾸는 요청(POST/PATCH/DELETE)에 CSRF 헤더를 자동으로 붙입니다.
// 화면에서 버튼을 숨기는 것은 편의일 뿐이며, 허용·거절은 모두 서버가 판단합니다.
window.chi = {
  me: null,
  csrf: null,

  async api(path, opts = {}) {
    const method = (opts.method || 'GET').toUpperCase();
    const headers = { ...(opts.headers || {}) };
    if (method !== 'GET') {
      await chi.ready;
      if (chi.csrf) headers[chi.csrf.headerName] = chi.csrf.token;
    }
    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      opts = { ...opts, body: JSON.stringify(opts.json) };
    }
    const res = await fetch(path, { ...opts, method, headers, credentials: 'same-origin' });
    const body = res.status === 204 ? {} : await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(body.error || `요청 실패 (${res.status})`);
      err.status = res.status;
      throw err;
    }
    return body;
  },

  /** 로그인·회원가입·현재 사용자 응답을 반영 */
  setSession(body) {
    chi.me = body.user || null;
    chi.csrf = body.csrf || null;
    chi.renderAuth();
  },

  async logout() {
    await chi.api('/api/auth/logout', { method: 'POST' }).catch(() => {});
    chi.setSession(await chi.api('/api/auth/me'));
    location.reload();
  },

  /** 로그인이 필요한 화면: 비로그인이면 로그인 화면으로 보냅니다(돌아올 주소 포함). */
  loginUrl: () => `/auth.html?next=${encodeURIComponent(location.pathname + location.search)}`,

  isMe: (user) => !!(user && chi.me && user.id === chi.me.id),

  renderAuth() {
    const slot = document.getElementById('auth-slot');
    if (!slot) return;
    if (chi.me) {
      slot.innerHTML = `<span class="me">${chi.esc(chi.me.nickname)}</span><button class="btn" type="button" id="btn-logout">로그아웃</button>`;
      document.getElementById('btn-logout').onclick = () => chi.logout();
    } else {
      slot.innerHTML = `<a class="btn" href="${chi.loginUrl()}">로그인</a>`;
    }
  },

  esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  },
  name: (b) => b?.streamer || '익명',
  duration(sec) {
    if (!sec || !isFinite(sec)) return '';
    sec = Math.round(sec);
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    const pad = (n) => String(n).padStart(2, '0');
    return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
  },
  ago(iso) {
    const diff = (Date.now() - new Date(iso).getTime()) / 1000;
    if (diff < 60) return '방금 전';
    if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
    if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
    if (diff < 86400 * 30) return `${Math.floor(diff / 86400)}일 전`;
    return new Date(iso).toLocaleDateString('ko-KR');
  },
  won: (n) => `${Number(n || 0).toLocaleString('ko-KR')}원`,
  views: (n) =>`조회수 ${Number(n || 0).toLocaleString('ko-KR')}회`,
  viewers: (n) => `시청자 ${Number(n || 0).toLocaleString('ko-KR')}명`,
  // 방송 시간: 시작~종료(또는 지금)
  elapsed(startIso, endIso) {
    const ms = (endIso ? new Date(endIso) : new Date()) - new Date(startIso);
    return chi.duration(Math.max(1, ms / 1000)) || '0:00';
  },
  time: (iso) => new Date(iso).toLocaleString('ko-KR'),
  // 같은 서버의 WebSocket 주소 (https 이면 wss)
  wsUrl: (path) => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${path}`,

  /**
   * 자동 재연결하는 시그널링 WebSocket.
   * - 서버가 4001("다른 서버로 재연결")로 닫으면 바로, 그 외 비정상 종료면 백오프(1·2·4·8초, 최대 10초)하며 다시 연결합니다.
   * - 서버가 정상 종료(1000: 방송 종료·시청자 가득 참 등)나 정책 위반(1008: 권한 없음·다른 화면이 송출을 이어받음)으로
   *   닫았거나, 화면이 stop()을 부른 뒤(방송 종료 메시지를 받은 뒤 등)에는 재연결하지 않습니다.
   * handlers.onOpen(reconnected): 연결될 때마다(처음 false, 재연결 true) 불립니다. join 메시지를 여기서 보냅니다.
   * handlers.onMessage(msg), handlers.onDisconnect(): 끊겨서 재연결을 기다리는 동안 화면 표시용.
   */
  signaling(path, handlers) {
    const RECONNECT_NOW = 4001;
    const NO_RECONNECT = [1000, 1008];
    let ws = null, stopped = false, attempt = 0, timer = null, opened = false;

    function open() {
      timer = null;
      if (stopped) return;
      const sock = new WebSocket(chi.wsUrl(path));
      ws = sock;
      sock.onopen = () => {
        if (sock !== ws || stopped) return;
        attempt = 0;
        const reconnected = opened;
        opened = true;
        handlers.onOpen?.(reconnected);
      };
      sock.onmessage = (ev) => {
        if (sock !== ws || stopped) return;
        let m;
        try { m = JSON.parse(ev.data); } catch { return; }
        handlers.onMessage?.(m);
      };
      sock.onclose = (ev) => {
        if (sock !== ws || stopped) return; // 이미 바뀐 이전 연결이거나 화면이 끝낸 연결
        ws = null;
        if (NO_RECONNECT.includes(ev.code)) { stopped = true; handlers.onClosed?.(ev.code); return; }
        handlers.onDisconnect?.(ev.code);
        const delay = ev.code === RECONNECT_NOW && attempt === 0
          ? 0
          : Math.min(10_000, 1000 * 2 ** Math.min(attempt, 3)) + Math.floor(Math.random() * 300);
        attempt++;
        timer = setTimeout(open, delay);
      };
    }
    // 화면을 다시 보거나 네트워크가 돌아오면 기다리지 않고 바로 시도
    const retryNow = () => { if (!stopped && !ws && timer) { clearTimeout(timer); open(); } };
    window.addEventListener('online', retryNow);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') retryNow(); });

    open();
    return {
      send(msg) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg)); },
      get connected() { return ws?.readyState === WebSocket.OPEN; },
      /** 재연결을 멈추고 연결을 닫습니다 (방송 종료·화면 종료 시) */
      stop() {
        stopped = true;
        clearTimeout(timer);
        const sock = ws;
        ws = null;
        sock?.close();
      },
    };
  },
};

chi.ready = fetch('/api/auth/me', { credentials: 'same-origin' })
  .then((r) => r.json())
  .then((body) => { chi.me = body.user || null; chi.csrf = body.csrf || null; })
  .catch(() => {})
  .then(() => {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', chi.renderAuth);
    else chi.renderAuth();
  });
