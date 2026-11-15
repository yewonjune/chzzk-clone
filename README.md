# 치치직 (ChiChiJik)

로그인 없이 **휴대폰으로 라이브 방송을 하고**, 방송과 **녹화 영상(다시보기)을 시청**하는 스트리밍 사이트입니다.

| 단계 | 내용 |
| --- | --- |
| 1단계 | 다시보기(녹화 영상) 등록 · 목록 · 상세 · 재생 · 영구 저장 |
| **2단계 (현재)** | 휴대폰 카메라·마이크 라이브 송출, 방송 목록·시청, 방송 중/종료 상태 표시 |

- 백엔드: Java 17+ · Spring Boot 4.1 (Spring MVC, Spring Data JPA, Spring WebSocket)
- 라이브: **WebRTC** (휴대폰 → 시청자 P2P 직접 전송, 서버는 WebSocket으로 연결 정보만 중계)
- 저장소: H2 파일 DB — 별도 DB 설치 없이 `data/` 폴더에 영구 저장
- 빌드: Gradle (Wrapper 포함, Gradle 설치 불필요)
- 프론트엔드: 정적 HTML/CSS/JS (`src/main/resources/static`, 빌드 과정 없음)

용어는 [GLOSSARY.md](GLOSSARY.md)를 따릅니다. **방송**은 휴대폰으로 하는 실시간 송출, **다시보기**는 등록된 녹화 영상입니다.

## 실행 방법

필요 환경: **JDK 17 이상** (개발·검증은 JDK 25에서 진행). ffmpeg·Docker 등 다른 설치는 필요 없습니다.

### IntelliJ IDEA

1. `File → Open` 으로 이 폴더(`build.gradle`이 있는 폴더)를 엽니다. Gradle 프로젝트로 자동 인식됩니다.
2. `File → Project Structure → SDK` 에서 JDK 17 이상을 선택합니다.
3. `src/main/java/com/chichijik/ChichijikApplication.java` 의 ▶ 버튼으로 실행합니다.
4. 브라우저에서 http://localhost:8080 접속.

휴대폰 송출까지 하려면 아래 [휴대폰으로 송출하기](#휴대폰으로-송출하기)처럼 HTTPS를 켜야 합니다.

### 터미널

```bash
./gradlew bootRun        # Windows: gradlew.bat bootRun
```

| 명령 | 설명 |
| --- | --- |
| `./gradlew bootRun` | 서버 실행 |
| `./gradlew test` | 자동 테스트 (다시보기 흐름 · 방송 시작/시그널링/시청자 상한/종료/재시작 정리) |
| `./gradlew bootJar` | 실행 jar 생성 → `java -jar build/libs/chichijik-0.1.0.jar` |

## 환경변수 (`.env.example`)

모두 선택 사항이며 기본값이 있습니다. **실제 비밀번호는 저장소에 넣지 말고** 실행할 때만 설정하세요.
Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로, IntelliJ에서는 **Run/Debug Configurations → Environment variables**에, 터미널에서는 셸 환경변수로 설정합니다.

```dotenv
# 공통
PORT=8080                       # 서버 포트
DATA_DIR=./data                 # H2 DB 파일(chichijik.mv.db) 저장 폴더
UPLOAD_DIR=./data/uploads       # 업로드된 다시보기 영상 저장 폴더
MAX_UPLOAD_SIZE=1GB             # 다시보기 업로드 최대 크기

# HTTPS (휴대폰 카메라·마이크 사용에 필요)
SSL_ENABLED=false               # true 로 켜면 https 로만 응답
SSL_KEYSTORE=./certs/chichijik.p12
SSL_KEYSTORE_PASSWORD=<keytool 로 만들 때 정한 비밀번호>

# 방송(WebRTC)
MAX_VIEWERS_PER_BROADCAST=5     # 방송 하나당 동시 시청자 상한
STUN_URLS=stun:stun.l.google.com:19302   # 쉼표로 여러 개, 비우면 같은 네트워크에서만 연결
TURN_URL=                       # (선택) 막힌 네트워크용 중계 서버. 예: turn:turn.example.com:3478
TURN_USERNAME=
TURN_PASSWORD=
```

## 이번 단계 구현 범위

**2단계에서 구현함**
- **방송 시작** (`/go-live.html`): 카메라·마이크 켜기 → 미리보기 → 제목(필수, 100자)·스트리머 입력 → 방송 시작
  - 방송 중 화면: LIVE 표시, 경과 시간, 현재 시청자 수, **카메라 전환(전면/후면)**, **마이크 끄기**, **방송 종료**(한 번 더 확인)
  - 720p 30fps 요청, 시청자별 영상 비트레이트 상한 1.5Mbps, 방송 중 화면 꺼짐 방지(Wake Lock)
- **방송 시청** (`/live.html?id=N`): 실시간 영상, 시청자 수 표시, 자동재생 정책상 음소거로 시작 → **🔊 소리 켜기**
- **방송 중 / 종료 상태 구분**
  - 목록 위쪽 **라이브** 영역: 방송 중(빨간 LIVE·시청자 수)이 먼저, 종료(흐리게·방송 시간)가 다음. 5초마다 자동 새로고침
  - 보는 도중 방송이 끝나면 화면이 즉시 "방송이 종료되었습니다"로 바뀌고 방송 시간·시작/종료 시각 표시
  - 송출 화면을 닫거나, 새로고침하거나, 네트워크가 끊기면 **즉시 종료** 처리
  - 서버가 재시작되면 남아 있던 "방송 중"은 모두 종료로 정리
- **송출 키**: 방송을 시작한 기기에만 발급되는 비밀 값. 이 키가 있어야 송출자로 연결하거나 종료할 수 있습니다(로그인과 무관). DB에는 해시만 저장
- **시청자 상한**: P2P 특성상 휴대폰 업로드가 시청자 수만큼 늘어나므로 기본 5명. 넘으면 "시청자가 가득 찼습니다" 안내
- HTTPS(자체 서명 인증서) 및 STUN/TURN 설정을 환경변수로 지원

**1단계 기능 (유지)**
- 다시보기 등록 (`/upload.html`): 파일 업로드(mp4/webm/ogg/mov/m4v) 또는 외부 영상 주소 연결, 미리보기·재생 길이 측정
- 다시보기 목록(메인 아래쪽) · 상세(`/replay.html?id=N`, 구간 탐색 가능) · 조회수
- 영구 저장: H2 파일 DB(`data/chichijik.mv.db`) + `data/uploads/`

**이번 단계에서 하지 않음**: 로그인·권한, 댓글/채팅, 도네이션, 방송 녹화(종료된 방송은 기록만 남고 다시 볼 수 없음), 다시보기 수정·삭제

## 휴대폰으로 송출하기

### 1) 왜 HTTPS가 필요한가

휴대폰 브라우저는 **HTTPS 주소(또는 localhost)에서만 카메라·마이크를 허용**합니다. PC에서 `http://localhost:8080`은 되지만, 휴대폰에서 `http://192.168.0.10:8080`으로 접속하면 송출 화면에 "이 주소에서는 카메라·마이크를 쓸 수 없습니다"가 뜹니다. 아래 방법 A(기본) 또는 B 중 하나로 HTTPS 주소를 만드세요.

### 2-A) 자체 서명 인증서로 HTTPS 켜기 (기본, 추가 설치 없음)

JDK에 들어 있는 `keytool`로 인증서를 만듭니다. `192.168.0.10`은 PC의 실제 IP로 바꾸세요.
PC IP 확인: Windows `ipconfig` (Wi-Fi 어댑터의 IPv4 주소) / macOS `ipconfig getifaddr en0`.

```bash
mkdir certs
keytool -genkeypair -alias chichijik -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore certs/chichijik.p12 -dname "CN=chichijik" -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.0.10"
```

비밀번호를 물으면 원하는 값을 입력합니다(이 값은 저장소에 올리지 마세요. `certs/`는 git에서 제외됨). 그다음 환경변수를 넣고 실행합니다.

```bash
# macOS / Linux / Git Bash
PORT=8443 SSL_ENABLED=true SSL_KEYSTORE=certs/chichijik.p12 SSL_KEYSTORE_PASSWORD=<비밀번호> ./gradlew bootRun
```

```powershell
# Windows PowerShell
$env:PORT="8443"; $env:SSL_ENABLED="true"; $env:SSL_KEYSTORE="certs/chichijik.p12"; $env:SSL_KEYSTORE_PASSWORD="<비밀번호>"; .\gradlew.bat bootRun
```

IntelliJ라면 Run Configuration의 Environment variables에 `PORT=8443;SSL_ENABLED=true;SSL_KEYSTORE=certs/chichijik.p12;SSL_KEYSTORE_PASSWORD=<비밀번호>`를 넣습니다.

- 접속 주소: 휴대폰·PC 모두 `https://192.168.0.10:8443`
- 처음 접속하면 **"연결이 비공개로 설정되어 있지 않습니다 / 안전하지 않음"** 경고가 뜹니다. 자체 서명 인증서라서 정상입니다.
  - Android Chrome: **고급 → (주소)(으)로 이동(안전하지 않음)**
  - iPhone Safari: **세부사항 보기 → 이 웹 사이트 방문**
- Windows에서 처음 실행할 때 방화벽 창이 뜨면 **개인 네트워크 허용**을 선택하세요. 이미 막았다면 `Windows 보안 → 방화벽 → 앱 허용`에서 Java(OpenJDK Platform binary)를 개인 네트워크에 허용합니다.

### 2-B) 대안: 터널로 정식 HTTPS 주소 받기

[cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)를 설치했다면 HTTPS 설정 없이 서버를 그대로 실행한 뒤:

```bash
cloudflared tunnel --url http://localhost:8080
```

출력된 `https://xxxx.trycloudflare.com` 주소로 휴대폰과 PC가 모두 접속합니다. 인증서 경고가 없고 서로 다른 네트워크에서도 접속할 수 있습니다. (영상 자체는 터널을 거치지 않고 WebRTC로 직접 연결되므로, 네트워크가 다르면 아래 접속 환경의 STUN/TURN 조건이 그대로 적용됩니다.)

### 3) 카메라·마이크 권한

- 송출 화면에서 **카메라·마이크 켜기**를 누르면 브라우저가 권한을 묻습니다 → **허용**.
- 실수로 거부했다면:
  - Android Chrome: 주소창 왼쪽 자물쇠(또는 ⓘ) → **권한** → 카메라·마이크 허용 후 새로고침
  - iPhone Safari: 주소창의 **가가(aA)** → **웹 사이트 설정** → 카메라·마이크 **허용**, 또는 `설정 → Safari → 카메라/마이크`
- 다른 앱(카메라 앱, 영상 통화)이 카메라를 쓰고 있으면 켜지지 않을 수 있습니다.
- 대상 브라우저: Android Chrome, iOS Safari 14.3+ 등 WebRTC·getUserMedia를 지원하는 최신 모바일 브라우저 (개발 중 자동 검증은 PC Chromium에서 가상 카메라로 진행했습니다)

### 4) 접속 환경

- **권장: 휴대폰과 시청할 PC를 같은 Wi-Fi에 연결**합니다. 영상은 서버를 거치지 않고 휴대폰 → 시청자로 직접(P2P) 전송됩니다.
- 서로 다른 네트워크(예: 휴대폰 LTE ↔ 집 PC)는 기본 STUN 서버(Google 공개 STUN)로 대부분 연결되지만, 회사·학교처럼 막힌 네트워크에서는 **TURN 서버**가 필요할 수 있습니다(`TURN_URL`/`TURN_USERNAME`/`TURN_PASSWORD`). 연결에 실패하면 시청 화면에 안내가 뜹니다.
- 동시 시청자는 방송당 기본 5명입니다(`MAX_VIEWERS_PER_BROADCAST`).

## 직접 확인 절차

### A. 휴대폰 송출 → 다른 화면에서 시청 (2단계)

1. 위 **2-A**대로 HTTPS로 서버를 실행합니다(`https://<PC IP>:8443`).
2. **휴대폰**에서 `https://<PC IP>:8443` 접속 → 인증서 경고 통과 → 오른쪽 위 **● 방송 시작**.
3. **카메라·마이크 켜기** → 권한 **허용** → 미리보기에 내 얼굴(전면 카메라, 거울 모드)이 나오는지 확인.
4. 제목 입력(예: "퇴근길 방송") → **방송 시작**.
   - ✅ **송출 시작 확인**: 영상 왼쪽 위에 빨간 **LIVE**, 경과 시간이 1초씩 증가, `시청자 0명`, 아래에 시청 주소가 표시됩니다. 탭 제목에 🔴가 붙습니다.
5. **PC 브라우저**(또는 다른 휴대폰)에서 `https://<PC IP>:8443` 접속 → **라이브** 영역에 방금 방송이 **LIVE** 배지와 함께 맨 앞에 보이는지 확인 → 카드 클릭.
6. 시청 화면에서 휴대폰 영상이 1초 안팎의 지연으로 나오는지 확인 → **🔊 소리 켜기**를 눌러 소리 확인.
   - 휴대폰 송출 화면의 시청자 수가 `시청자 1명`으로 바뀌는지 확인합니다.
7. 휴대폰에서 **카메라 전환**(후면) · **마이크 끄기**를 눌러 시청 화면에 바로 반영되는지 확인합니다.
8. 휴대폰에서 **방송 종료** → 확인.
   - ✅ **송출 종료 확인**: 휴대폰에 "방송을 종료했습니다"와 방송 시간·시작/종료 시각이 나옵니다.
   - 시청 화면은 새로고침 없이 **"방송이 종료되었습니다"**와 **종료** 배지로 바뀝니다.
   - 메인 목록의 라이브 영역에서 해당 방송이 **종료**(흐린 카드, 방송 시간 표시)로 바뀝니다(최대 5초).
9. (끊김 처리) 새 방송을 시작한 뒤 휴대폰에서 **탭을 닫거나 새로고침** → 시청 화면이 바로 "방송이 종료되었습니다"로 바뀌는지 확인합니다.
10. (시청자 상한) `MAX_VIEWERS_PER_BROADCAST=1`로 실행 후 시청 화면을 두 개 열면 두 번째에 "시청자가 가득 찼습니다"가 뜹니다.

> 휴대폰 없이 PC 한 대로 흐름만 확인하려면: PC에 웹캠이 있으면 `http://localhost:8080/go-live.html`(localhost는 HTTPS 없이도 카메라 허용)에서 송출하고, 다른 브라우저 창에서 `http://localhost:8080`으로 시청하면 됩니다.

### B. 다시보기 (1단계 기능 유지 확인)

테스트용 5초 영상이 `samples/sample.mp4`에 들어 있습니다.

1. 메인 → **＋ 다시보기 등록** → 제목 입력 → *파일 업로드* 탭에서 `samples/sample.mp4` 선택 → **등록하기**.
2. 상세 화면(`/replay.html`)으로 이동해 재생·구간 이동이 되는지 확인합니다.
3. 메인 아래쪽 **다시보기** 영역에 카드(썸네일, `0:05`, 조회수)가 보이는지 확인합니다.
4. 서버를 종료하고 다시 실행 → 다시보기와 종료된 방송 기록이 그대로 남아 있는지 확인합니다. (재시작 전에 방송 중이던 방송은 **종료**로 표시됩니다.)

자동 검증: `./gradlew test` — 실제 서버를 임시 폴더로 띄워 확인합니다.
- 다시보기: 등록 → 목록 → Range 재생 → 재시작 후 유지, 입력 검증
- 방송: 시작(송출 키 발급·응답에 키 미노출) → 잘못된 키로 종료/송출 거절 → 송출자·시청자 시그널링 전달 → 시청자 상한 → 송출 연결 끊김 시 즉시 종료 → 재시작 시 방송 중 정리

## API

**방송**

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/broadcasts` | 방송 목록: 방송 중(최근 시작 순) → 종료(최근 30개) |
| `GET` | `/api/broadcasts/{id}` | 방송 상세 (`status`: `live`/`ended`, `viewerCount`) |
| `POST` | `/api/broadcasts` | 방송 시작. JSON `{"title", "streamer"}` → `201 {broadcast, streamKey}` (송출 키는 이때만 내려감) |
| `POST` | `/api/broadcasts/{id}/end` | 방송 종료. 헤더 `X-Stream-Key` 필요 (틀리면 403) |
| `GET` | `/api/live-config` | WebRTC ICE 서버(STUN/TURN)·시청자 상한 |
| `WS` | `/ws/broadcasts/{id}` | 시그널링. 첫 메시지 `{"type":"join","role":"host","streamKey"}` 또는 `{"type":"join","role":"viewer"}` |

**다시보기**

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/replays` | 다시보기 목록 (최신순) |
| `GET` | `/api/replays/{id}` | 다시보기 상세 |
| `POST` | `/api/replays` | 등록. `multipart/form-data`: `title`(필수), `streamer`, `description`, `durationSec`, 그리고 `video`(파일) **또는** `videoUrl` |
| `POST` | `/api/replays/{id}/views` | 조회수 1 증가 |
| `GET` | `/media/{filename}` | 업로드된 영상 파일 (Range 요청 지원) |

오류는 모두 `{"error": "메시지"}` 형태입니다.

> 1단계의 `/api/broadcasts`(녹화 영상)는 2단계에서 용어를 바로잡으며 `/api/replays`로 옮겼습니다. 이유는 [docs/adr/0002](docs/adr/0002-broadcast-and-replay-are-separate.md) 참고.

## 설계 문서

- [docs/stage2-design.md](docs/stage2-design.md) — 2단계 아키텍처, ERD, 기술적 고민 및 개선
- [docs/stage1-design.md](docs/stage1-design.md) — 1단계 설계
- [GLOSSARY.md](GLOSSARY.md) — 용어집
- [docs/adr/](docs/adr/) — 주요 설계 결정 기록 (WebRTC P2P 선택, 방송/다시보기 분리)

## 폴더 구조

```
src/main/java/com/chichijik/
  ChichijikApplication.java     진입점
  broadcast/                    방송: 엔티티·서비스·컨트롤러, 송출 키, 방(LiveRooms), WebSocket 시그널링
  replay/                       다시보기: 엔티티·리포지토리·서비스(검증, 파일 저장)·컨트롤러
  config/                       /media/** 영상 제공, WebSocket 등록
  common/                       공통 예외와 오류 응답
src/main/resources/
  application.properties        설정 (환경변수 기본값)
  static/                       메인(index) · 방송 시작(go-live) · 방송 시청(live) · 다시보기(replay, upload)
src/test/java/                  통합 테스트
samples/                        확인용 샘플 영상
data/, certs/                   (git 제외) DB·업로드 파일, HTTPS 인증서
```
