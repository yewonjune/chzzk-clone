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
  views: (n) => `조회수 ${Number(n || 0).toLocaleString('ko-KR')}회`,
  viewers: (n) => `시청자 ${Number(n || 0).toLocaleString('ko-KR')}명`,
  // 방송 시간: 시작~종료(또는 지금)
  elapsed(startIso, endIso) {
    const ms = (endIso ? new Date(endIso) : new Date()) - new Date(startIso);
    return chi.duration(Math.max(1, ms / 1000)) || '0:00';
  },
  time: (iso) => new Date(iso).toLocaleString('ko-KR'),
  // 같은 서버의 WebSocket 주소 (https 이면 wss)
  wsUrl: (path) => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${path}`,
};

chi.ready = fetch('/api/auth/me', { credentials: 'same-origin' })
  .then((r) => r.json())
  .then((body) => { chi.me = body.user || null; chi.csrf = body.csrf || null; })
  .catch(() => {})
  .then(() => {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', chi.renderAuth);
    else chi.renderAuth();
  });
