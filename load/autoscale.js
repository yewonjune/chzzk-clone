// 치치직 5단계: 오토스케일 확인용 k6 부하 스크립트
//
// 실행 (설치 없이 Docker 로, 저장소 루트에서):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6:0.54.0 run - < load/autoscale.js
// 다른 터미널에서 동시에:
//   kubectl -n chichijik get hpa,pods -w
//
// 시나리오
//   load  : 비로그인 시청자들이 방송·다시보기 목록과 상세를 계속 조회해 CPU 부하를 만든다 (램프업 → 유지 → 종료)
//   watch : 미리 로그인한 사용자 한 명이 부하 중과 부하가 끝난 뒤(스케일 인)까지 1초마다
//           로그인 상태·방송 목록·댓글이 그대로인지 확인한다. 401 이나 개수 감소가 나오면 check 실패.
//
// 환경변수
//   BASE_URL   서버 주소 (기본 http://host.docker.internal:8080)
//   PEAK_VUS   부하 최대 가상 사용자 수 (기본 60)
//   HOLD       최대 부하 유지 시간 (기본 4m)
//   COOLDOWN   부하가 끝난 뒤 watch 가 더 지켜보는 시간 (기본 4m, 스케일 인 확인용)

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PEAK = Number(__ENV.PEAK_VUS || 60);
const HOLD = __ENV.HOLD || '4m';
const COOLDOWN = __ENV.COOLDOWN || '4m';
const RAMP_UP = '1m';
const RAMP_DOWN = '30s';

function minutes(s) {
  const m = /^(\d+)(s|m)$/.exec(s);
  return m[2] === 'm' ? Number(m[1]) : Number(m[1]) / 60;
}
const loadMinutes = minutes(RAMP_UP) + minutes(HOLD) + minutes(RAMP_DOWN);
const watchMinutes = Math.ceil(loadMinutes + minutes(COOLDOWN));

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: PEAK },
        { duration: HOLD, target: PEAK },
        { duration: RAMP_DOWN, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
    watch: {
      executor: 'constant-vus',
      exec: 'watch',
      vus: 1,
      duration: `${watchMinutes}m`,
    },
  },
  thresholds: {
    // 상태 유지 확인은 하나도 실패하면 안 됨
    'checks{scenario:watch}': ['rate==1'],
    'http_req_failed{scenario:load}': ['rate<0.01'],
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function csrfHeaders(csrf, extra) {
  return Object.assign({ [csrf.headerName]: csrf.token }, extra || {});
}

// 부하 전에 한 번: 사용자 가입(로그인), 다시보기 1편 + 댓글 3개, 방송 1개를 만들어 둔다.
export function setup() {
  const jar = http.cookieJar();
  const me = http.get(`${BASE}/api/auth/me`).json();
  const username = `load${Date.now() % 1e9}`;
  const signup = http.post(
    `${BASE}/api/auth/signup`,
    JSON.stringify({ username, password: 'password123', nickname: `부하${username.slice(-5)}` }),
    { headers: csrfHeaders(me.csrf, JSON_HEADERS) },
  );
  if (signup.status !== 201) throw new Error(`가입 실패 ${signup.status} ${signup.body}`);
  const csrf = signup.json().csrf;

  // 다시보기 등록은 multipart 만 받음 (외부 영상 주소로 등록)
  const boundary = '----chichijikload';
  const fields = { title: '오토스케일 확인용 다시보기', videoUrl: 'https://example.com/sample.mp4', durationSec: '60' };
  const form = Object.keys(fields)
    .map((k) => `--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${fields[k]}\r\n`)
    .join('') + `--${boundary}--\r\n`;
  const replay = http.post(`${BASE}/api/replays`, form,
    { headers: csrfHeaders(csrf, { 'Content-Type': `multipart/form-data; boundary=${boundary}` }) });
  if (replay.status !== 201) throw new Error(`다시보기 등록 실패 ${replay.status} ${replay.body}`);
  const replayId = replay.json().id;

  for (let i = 1; i <= 3; i++) {
    const c = http.post(`${BASE}/api/replays/${replayId}/comments`, JSON.stringify({ content: `스케일 전 댓글 ${i}` }),
      { headers: csrfHeaders(csrf, JSON_HEADERS) });
    if (c.status !== 201) throw new Error(`댓글 실패 ${c.status} ${c.body}`);
  }

  const broadcast = http.post(`${BASE}/api/broadcasts`, JSON.stringify({ title: '오토스케일 확인용 방송' }),
    { headers: csrfHeaders(csrf, JSON_HEADERS) });
  if (broadcast.status !== 201) throw new Error(`방송 시작 실패 ${broadcast.status} ${broadcast.body}`);

  const cookies = jar.cookiesForURL(BASE);
  const cookie = Object.keys(cookies).map((k) => `${k}=${cookies[k][0]}`).join('; ');
  return {
    cookie,
    username,
    replayId,
    broadcastCount: http.get(`${BASE}/api/broadcasts`).json().length,
    commentCount: commentsOf(http.get(`${BASE}/api/replays/${replayId}/comments`).json()).length,
  };
}

function commentsOf(body) {
  return body.comments || [];
}

// load 시나리오: 비로그인 시청자의 목록·상세 조회
export function browse(data) {
  http.get(`${BASE}/api/broadcasts`);
  http.get(`${BASE}/api/replays`);
  http.get(`${BASE}/api/replays/${data.replayId}`);
  http.get(`${BASE}/api/replays/${data.replayId}/comments`);
  sleep(0.1);
}

// watch 시나리오: 같은 세션 쿠키로 어느 Pod 에 가든 상태가 그대로인지
export function watch(data) {
  const jar = http.cookieJar();
  jar.clear(BASE);
  const params = { headers: { Cookie: data.cookie } };

  const me = http.get(`${BASE}/api/auth/me`, params);
  check(me, {
    '로그인 상태 유지': (r) => r.status === 200 && r.json().user !== null && r.json().user.username === data.username,
  });

  const list = http.get(`${BASE}/api/broadcasts`, params);
  check(list, {
    '방송 목록 유지': (r) => r.status === 200 && r.json().length >= data.broadcastCount,
  });

  const comments = http.get(`${BASE}/api/replays/${data.replayId}/comments`, params);
  check(comments, {
    '댓글 유지': (r) => r.status === 200 && commentsOf(r.json()).length >= data.commentCount,
  });
  sleep(1);
}
