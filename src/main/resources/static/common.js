// 공통 유틸
window.chi = {
  async api(path, opts) {
    const res = await fetch(path, opts);
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || `요청 실패 (${res.status})`);
    return body;
  },
  esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  },
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
