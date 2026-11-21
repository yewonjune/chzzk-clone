# 치지직 6단계 설계 문서

> 범위: 백엔드를 **OCI Always Free VM** 에 배포하고 구매한 **도메인 + HTTPS** 로 공개, **Windows·macOS 데스크톱 클라이언트**를 빌드해 다운로드 페이지로 배포.
> 1~5단계 기능(다시보기, 휴대폰 라이브, 인증·인가·댓글, 도네이션, 상태 외부화)은 유지한다. 주요 결정은 [ADR-0007](adr/0007-single-vm-compose-with-caddy.md).

---

## 1. 시스템 아키텍처

### 1-1. 배포 구조

```
            ┌───────────── 사용자 ─────────────┐
            │ 웹 브라우저 · 휴대폰 · 데스크톱 앱 │
            └───────┬───────────────┬──────────┘
                    │ https://<도메인>          (데스크톱 앱 설치 파일)
   DNS A 레코드      │ wss://<도메인>/ws/...      ▼
   <도메인> → 공인IP ▼                      GitHub Releases
┌──────────────── OCI VM (Always Free, Ubuntu) ───────────────┐      ▲
│ OCI 보안 목록 + iptables: 22, 80, 443 (+3478, 49160-49200)   │      │ desktop-v* 태그
│                                                              │  GitHub Actions
│  caddy :80 → 301 https   :443 TLS (Let's Encrypt 자동)        │  windows-latest → exe·zip
│    └─ reverse_proxy ─▶ app:8080 (HTTP, X-Forwarded-Proto)    │  macos-latest   → dmg·zip
│                         Spring Boot · cluster 프로필          │
│                           ├─▶ postgres  (볼륨 pgdata)         │
│                           ├─▶ redis     (세션·방 상태)        │
│                           └─  /data/uploads (볼륨)            │
│  coturn (선택, host 네트워크) — WebRTC 중계                    │
└──────────────────────────────────────────────────────────────┘
 영상: 휴대폰 ══WebRTC══▶ 시청자(웹·데스크톱)   직접 연결이 안 되면 coturn 경유
```

- **이미지**: 5단계 `Dockerfile` 그대로. 베이스(eclipse-temurin 17)가 arm64 를 지원해 A1 VM 에서 `docker compose build` 로 직접 빌드한다(레지스트리 불필요).
- **기동 순서**: postgres·redis healthy → app 의 readiness(`/actuator/health/readiness`, DB·Redis 포함)가 UP → caddy 시작. 인증서 발급 전에 앱이 준비되어 첫 요청이 502 가 되지 않는다.
- **재시작**: 모든 서비스 `restart: unless-stopped` → VM 재부팅 후 Docker 가 다시 띄움.

### 1-2. 데스크톱 앱 구조

```
Electron 메인 프로세스 (main.js)
  ├─ app:// 프로토콜 → desktop/renderer/ 파일 제공 (출처 app://chzzk, 폴더 밖 경로 거부)
  ├─ 서버 주소: package.json chzzkServerUrl(빌드 때 CI 변수로 주입) ← userData/settings.json(사용자 변경)
  └─ IPC: config:get / config:set / open:external  (preload.js 의 contextBridge 로만 노출)
렌더러 (renderer/app.js, sandbox·contextIsolation·CSP)
  ├─ GET <서버>/api/broadcasts, /api/replays, /api/live-config   (CORS: app://chzzk 에 GET 만)
  ├─ 다시보기: <video src=<서버>/media/...>  (Range 로 구간 탐색)
  └─ 라이브: wss://<서버>/ws/broadcasts/{id} 에 시청자로 join → offer/answer/candidate → RTCPeerConnection
```

### 1-3. 서버에 추가·변경한 것

| 항목 | 내용 |
| --- | --- |
| 프록시 헤더 | `server.forward-headers-strategy=native` — Tomcat RemoteIpValve 가 사설망·localhost 프록시의 `X-Forwarded-Proto/Host/Port` 만 반영. 같은 출처 판단(WebSocket, CORS)과 리다이렉트가 https 기준이 됨 |
| 세션 쿠키 | `SESSION_COOKIE_SECURE` 를 `SSL_ENABLED` 와 분리 — 앱이 HTTP 로 받아도 쿠키는 HTTPS 전용 |
| 데스크톱 출처 | `DESKTOP_ORIGINS`(기본 `app://chzzk`): 이 Origin 의 `/api/**`·`/media/**` **GET/HEAD 만** CORS 허용(쿠키 없음). 시그널링 WebSocket 허용 출처에 추가 |
| 다운로드 | `GET /api/downloads` + `/download.html`. 파일 위치는 `DESKTOP_DOWNLOAD_BASE` |
| 상태 확인 | 기존 Actuator `/actuator/health{,/liveness,/readiness}` 를 Compose healthcheck 와 외부 점검에 사용. Caddy 가 그 외 `/actuator/*` 는 404 |

---

## 2. ERD

6단계에서 테이블 변경은 없습니다. 기록은 5단계와 같은 PostgreSQL 스키마(`users`, `replays`, `live_broadcasts`, `comments`, `donations`, 가짜 PG 결제)를 쓰고, 데이터는 VM 의 Docker 볼륨 `pgdata`(DB)와 `uploads`(영상)에 남습니다. 데스크톱 앱은 서버 DB 에 쓰지 않으며, 앱 쪽 저장은 `userData/settings.json`(서버 주소) 하나뿐입니다.

---

## 3. 기술적 고민 및 개선

### 3-1. Kubernetes 대신 Compose (VM 한 대)

Always Free VM 은 한 대라 k3s 같은 경량 Kubernetes 를 올려도 HPA 가 늘릴 노드가 없고, 제어 평면이 메모리·CPU 를 먼저 씁니다. 5단계에서 상태를 이미 PostgreSQL·Redis·볼륨으로 뺐으므로, 같은 이미지·프로필을 Compose 로 올리면 기능은 같고 운영 부품은 적습니다. VM 을 늘릴 때는 앱 컨테이너를 다른 VM 에 띄우고 DB·Redis 를 공용으로 두면 됩니다(업로드 볼륨은 그때 오브젝트 스토리지로 옮겨야 함).

### 3-2. TLS 를 앞단에서 끝내기 — 그리고 "같은 출처" 함정

Caddy 가 TLS 를 끝내면 앱이 받는 요청은 `http://app:8080` 입니다. 그대로 두면 브라우저의 `Origin: https://<도메인>` 과 앱이 보는 `http://<도메인>` 이 달라 **같은 출처가 아닌 요청**으로 판단될 수 있습니다(WebSocket 핸드셰이크의 출처 검사, CORS 처리). 그래서 `forward-headers-strategy=native` 로 프록시 헤더를 반영하되, 신뢰는 사설망·localhost 에서 온 요청으로만 제한해 외부에서 헤더를 꾸며 보내는 것은 무시합니다. `DesktopAccessTest` 가 `X-Forwarded-Proto: https` 뒤의 같은 출처 POST 가 통과하는지 확인합니다.

또 CORS 설정을 추가하면 Spring 이 Origin 이 있는 모든 요청을 CORS 대상으로 검사하게 되어, 프록시 설정이 틀린 환경에서 웹 화면의 POST 까지 403 이 날 위험이 있었습니다. 그래서 CORS 설정 소스가 **Origin 이 데스크톱 출처일 때만** 설정을 돌려주도록 해, 웹 화면은 6단계 전과 똑같이 동작하게 했습니다.

### 3-3. 데스크톱 앱이 쓸 수 있는 범위를 좁히기

앱은 쿠키 없이 GET 과 시청 WebSocket 만 씁니다. 로그인까지 앱에서 하려면 교차 출처 쿠키(`SameSite=None`)나 토큰 인증을 새로 열어야 하고, 그만큼 CSRF·토큰 보관 문제가 생깁니다. 과제 요구(목록 조회·재생)에 필요한 만큼만 열고, 상태 변경은 "웹에서 열기"로 안내합니다. 앱 내부도 렌더러에 Node 를 주지 않고(sandbox, contextIsolation) preload 에서 세 가지 기능만 노출하며, CSP 로 앱 파일 외 스크립트를 막습니다.

### 3-4. macOS 빌드와 서명

electron-builder 는 macOS 패키지를 macOS 에서만 만들 수 있어 GitHub Actions 의 macOS 러너를 씁니다. 개발자 인증서 없이 그대로 두면 Apple Silicon 에서 서명 없는 바이너리가 실행되지 않으므로 `afterPack` 에서 ad-hoc 서명(`codesign --sign -`)을 붙입니다. 공증은 하지 않으므로 Gatekeeper 확인을 한 번 거쳐야 하고, 그 방법(그래도 열기 / Control-클릭 / `xattr`)을 다운로드 페이지에 적었습니다.

### 3-5. 항상 최신 파일을 가리키는 다운로드 링크

릴리스 파일 이름에 버전을 넣지 않아(`Chzzk-Setup-win-x64.exe`) `releases/latest/download/<파일>` 이 항상 최신 릴리스의 파일을 가리킵니다. 서버는 새 버전을 낼 때 다시 배포할 필요가 없고, 버전은 릴리스 이름(태그 `desktop-vX.Y.Z`)과 앱의 설정 창에서 확인합니다.

### 3-6. 사람 없이 패키지를 점검하기

빌드한 앱이 실제로 서버와 통신하는지 확인하려고 `CHZZK_SMOKE=1` 모드를 넣었습니다. 앱이 목록을 불러온 뒤 화면 상태(카드 개수, 오류)를 출력하고, `CHZZK_SMOKE_OPEN=replay/1` 이나 `live/1` 을 열어 `<video>` 의 `readyState`·`currentTime`·`srcObject` 로 재생 여부를 확인한 다음 종료합니다. 로컬에서 Windows 패키지로 다시보기 끝까지 재생, 라이브 WebRTC 수신을 이 방법으로 확인했습니다.

### 3-7. 다음에 할 개선 과제

- 코드 서명(Windows Authenticode, Apple Developer ID + 공증)으로 첫 실행 경고 없애기
- electron-updater 로 앱 자동 업데이트
- DB 자동 백업(cron + `pg_dump` → OCI Object Storage), 업로드 영상의 오브젝트 스토리지 이전
- 외부 가동 감시(Uptime 점검이 `/actuator/health` 를 주기적으로 확인)
