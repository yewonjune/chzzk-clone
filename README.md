# 치치직 (ChiChiJik)

**휴대폰으로 라이브 방송을 하고**, 방송과 **녹화 영상(다시보기)을 시청**하며, 로그인한 사용자가 **댓글**을 다는 스트리밍 사이트입니다.

| 단계 | 내용 |
| --- | --- |
| 1단계 | 다시보기(녹화 영상) 등록 · 목록 · 상세 · 재생 · 영구 저장 |
| 2단계 | 휴대폰 카메라·마이크 라이브 송출, 방송 목록·시청, 방송 중/종료 상태 표시 |
| **3단계 (현재)** | 회원가입·로그인·로그아웃, 소유자 권한(등록·수정·삭제·송출 제어), 댓글(작성·목록·수정·삭제) |

- 백엔드: Java 17+ · Spring Boot 4.1 (Spring MVC, Spring Data JPA, Spring WebSocket, **Spring Security**)
- 인증: 서버 세션(쿠키) + BCrypt 비밀번호 + CSRF 토큰 ([ADR-0003](docs/adr/0003-session-auth-with-spring-security.md))
- 라이브: **WebRTC** (휴대폰 → 시청자 P2P 직접 전송, 서버는 WebSocket으로 연결 정보와 댓글 알림만 중계)
- 저장소: H2 파일 DB — 별도 DB 설치 없이 `data/` 폴더에 영구 저장
- 빌드: Gradle (Wrapper 포함, Gradle 설치 불필요)
- 프론트엔드: 정적 HTML/CSS/JS (`src/main/resources/static`, 빌드 과정 없음)

용어는 [GLOSSARY.md](GLOSSARY.md)를 따릅니다. **방송**은 휴대폰으로 하는 실시간 송출, **다시보기**는 등록된 녹화 영상, **스트리머**는 그 방송·다시보기를 만든 사용자(소유자)입니다.

## 실행 방법

필요 환경: **JDK 17 이상** (개발·검증은 JDK 25에서 진행). ffmpeg·Docker·DB 서버 등 다른 설치는 필요 없습니다.

### IntelliJ IDEA

1. `File → Open` 으로 이 폴더(`build.gradle`이 있는 폴더)를 엽니다. Gradle 프로젝트로 자동 인식됩니다.
2. `File → Project Structure → SDK` 에서 JDK 17 이상을 선택합니다.
3. `src/main/java/com/chichijik/ChichijikApplication.java` 의 ▶ 버튼으로 실행합니다.
4. 브라우저에서 http://localhost:8080 접속 → 오른쪽 위 **로그인 → 회원가입**.

휴대폰 송출까지 하려면 아래 [휴대폰으로 송출하기](#휴대폰으로-송출하기)처럼 HTTPS를 켜야 합니다.

### 터미널

```bash
./gradlew bootRun        # Windows: gradlew.bat bootRun
```

| 명령 | 설명 |
| --- | --- |
| `./gradlew bootRun` | 서버 실행 |
| `./gradlew test` | 자동 테스트 11개 (권한 매트릭스 · 다시보기 흐름 · 방송 흐름) |
| `./gradlew bootJar` | 실행 jar 생성 → `java -jar build/libs/chichijik-0.1.0.jar` |

> 계정은 처음에 하나도 없습니다. 회원가입 화면에서 직접 만드세요(관리자·기본 계정·기본 비밀번호 없음).

## 환경변수 (`.env.example`)

모두 선택 사항이며 기본값이 있습니다. **실제 비밀번호는 저장소에 넣지 말고** 실행할 때만 설정하세요.
Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로, IntelliJ에서는 **Run/Debug Configurations → Environment variables**에, 터미널에서는 셸 환경변수로 설정합니다.

```dotenv
# 공통
PORT=8080                       # 서버 포트
DATA_DIR=./data                 # H2 DB 파일(chichijik.mv.db) 저장 폴더
UPLOAD_DIR=./data/uploads       # 업로드된 다시보기 영상 저장 폴더
MAX_UPLOAD_SIZE=1GB             # 다시보기 업로드 최대 크기

# 로그인 세션
SESSION_TIMEOUT=12h             # 마지막 요청 이후 로그인 유지 시간

# HTTPS (휴대폰 카메라·마이크 사용에 필요, 켜면 세션 쿠키도 HTTPS 전용)
SSL_ENABLED=false
SSL_KEYSTORE=./certs/chichijik.p12
SSL_KEYSTORE_PASSWORD=<keytool 로 만들 때 정한 비밀번호>

# 방송(WebRTC)
MAX_VIEWERS_PER_BROADCAST=5     # 방송 하나당 동시 시청자 상한
STUN_URLS=stun:stun.l.google.com:19302   # 쉼표로 여러 개, 비우면 같은 네트워크에서만 연결
TURN_URL=                       # (선택) 막힌 네트워크용 중계 서버. 예: turn:turn.example.com:3478
TURN_USERNAME=
TURN_PASSWORD=
```

## 공개 범위와 권한

**시청과 목록 조회는 로그인 없이 누구나** 할 수 있습니다. 방송·다시보기 목록과 상세, 영상 재생, 라이브 시청, **댓글 목록 읽기**가 모두 공개입니다. 스트리밍 사이트로서 처음 온 사람도 볼 수 있어야 하고, 시청 자체로는 누구의 데이터도 바뀌지 않기 때문입니다. 응답에는 사용자의 **닉네임과 번호만** 담기고, 로그인 아이디·비밀번호 해시는 어떤 응답에도 포함되지 않습니다.

무엇인가를 **만들거나 바꾸는 동작은 로그인이 필요**하고, **남의 것을 바꾸는 동작은 서버가 거절**합니다. 화면에서 버튼을 숨기는 것은 편의일 뿐이며, 모든 판단은 서버에서 다시 합니다(API를 직접 호출해도 같은 결과).

| 동작 | 비로그인 | 로그인 (다른 사용자) | 소유자 / 작성자 |
| --- | --- | --- | --- |
| 목록·상세·시청 (방송, 다시보기), 댓글 목록 | ✅ | ✅ | ✅ |
| 다시보기 등록, 방송 시작 | ❌ 401 | ✅ 본인이 소유자가 됨 | — |
| 다시보기 수정(제목·설명)·삭제 | ❌ 401 | ❌ 403 | ✅ |
| 방송 제목 수정 | ❌ 401 | ❌ 403 | ✅ (방송 중·종료 모두) |
| 방송 **송출 연결**(WebSocket)·**종료** | ❌ | ❌ | ✅ |
| 종료된 방송 기록 삭제 | ❌ 401 | ❌ 403 | ✅ (방송 중이면 409, 먼저 종료) |
| 댓글 작성 | ❌ 401 | ✅ | ✅ |
| 댓글 수정 | ❌ 401 | ❌ 403 | ✅ 작성자만 |
| 댓글 삭제 | ❌ 401 | ❌ 403 | ✅ 작성자 **또는 그 방송·다시보기의 스트리머** |

- 401 = 로그인 필요, 403 = 권한 없음. 응답은 `{"error": "로그인이 필요합니다."}`처럼 이유를 담습니다.
- 상태를 바꾸는 모든 요청에는 CSRF 토큰이 필요합니다(없으면 403 "보안 토큰이 없거나 만료되었습니다"). 화면은 `/api/auth/me`로 받은 토큰을 자동으로 붙입니다.
- 송출 권한은 WebSocket 연결 요청에 실려 오는 로그인 세션으로 확인합니다. 2단계의 "송출 키"는 없앴습니다([ADR-0004](docs/adr/0004-owner-check-replaces-stream-key.md)).
- 3단계 이전에 만들어진(소유자가 없는) 기록은 "익명"으로 보이며, 누구도 수정·삭제할 수 없습니다(댓글 작성은 가능).

## 이번 단계 구현 범위

**3단계에서 구현함**
- **회원가입·로그인·로그아웃** (`/auth.html`)
  - 아이디(영문 소문자·숫자·`_` 4~20자, 중복 불가), 닉네임(2~20자, 중복 불가), 비밀번호(8~72자, BCrypt 저장)
  - 가입하면 바로 로그인, 로그인 후 원래 보던 화면으로 돌아감(사이트 내부 주소만 허용)
  - 로그인 시 세션 ID 교체(세션 고정 공격 방지), 로그아웃 시 세션 무효화
  - 화면 오른쪽 위에 닉네임·로그아웃 표시
- **소유자 구분과 서버 권한 검사**
  - 다시보기 등록·방송 시작 시 로그인 사용자가 소유자(스트리머)가 됨. 기존의 자유 입력 "스트리머 이름" 칸은 없애고 닉네임을 사용
  - 다시보기 수정(제목·설명)·삭제(영상 파일·댓글 함께 삭제), 방송 제목 수정, 방송 종료, 종료된 방송 기록 삭제 — 모두 소유자만
  - 방송 송출(WebSocket 송출자 연결)도 소유자만
- **댓글** — 방송(방송 중·종료)과 다시보기 모두
  - 로그인 사용자 작성(1~500자), 방송별·다시보기별 목록(최신순 50개 + "이전 댓글 더 보기")
  - 작성자 표시(닉네임, 스트리머 배지), 수정 시 "수정됨" 표시
  - 수정: 작성자만 / 삭제: 작성자 또는 스트리머
  - **방송 중에는 실시간**: 새 댓글·수정·삭제가 시청 화면과 휴대폰 송출 화면에 바로 반영(제목 변경도 반영)

**이전 단계 기능 (유지)**
- 2단계: 휴대폰 송출(카메라 전환·마이크 끄기·종료), WebRTC 시청, 방송 중/종료 상태, 끊김 즉시 종료, 시청자 상한, HTTPS·STUN/TURN 설정
- 1단계: 다시보기 등록(파일 업로드·외부 주소), 목록·상세·재생(구간 탐색), 조회수, 재시작 후 유지

**이번 단계에서 하지 않음**: 도네이션, 프로필·비밀번호 변경, 회원 탈퇴, 관리자 기능, 방송 녹화

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
- 방송을 시작하려면 휴대폰에서도 **로그인**해야 합니다(시청은 로그인 불필요).

## 직접 확인 절차

### C. 인증·권한·댓글 (3단계) — 비로그인 / 소유자 / 다른 사용자

같은 브라우저의 탭들은 로그인을 공유하므로, **사람마다 다른 브라우저(또는 시크릿 창)** 를 쓰세요. PC 한 대라면 `http://localhost:8080`과 `http://127.0.0.1:8080`도 서로 다른 로그인으로 취급됩니다.

| 역할 | 준비 |
| --- | --- |
| 🙂 **비로그인** | 시크릿 창에서 로그인하지 않은 상태 |
| 👑 **소유자** | 브라우저 1에서 회원가입(예: 아이디 `owner1`, 닉네임 `주인장`) |
| 👤 **다른 사용자** | 브라우저 2에서 회원가입(예: 아이디 `other1`, 닉네임 `지나가던이`) |

**회원가입·로그인·로그아웃**
1. 👑 오른쪽 위 **로그인 → 회원가입** 탭 → 가입 → 오른쪽 위에 닉네임과 **로그아웃**이 보이는지 확인.
2. 같은 아이디·닉네임으로 다시 가입하면 "이미 사용 중" 오류, 틀린 비밀번호로 로그인하면 "아이디 또는 비밀번호가 올바르지 않습니다".
3. **로그아웃** → 오른쪽 위가 **로그인** 버튼으로 바뀌는지 확인 → 다시 로그인.

**다시보기 권한**

4. 🙂 **＋ 다시보기 등록** → "로그인해야 합니다" 안내만 보입니다.
5. 👑 **＋ 다시보기 등록** → `samples/sample.mp4` 등록 → 상세 화면에 스트리머 `주인장`, 아래에 **내 다시보기 [수정] [삭제]** 가 보입니다. **수정**으로 제목 변경.
6. 👤 같은 다시보기를 열면 수정·삭제 버튼이 없습니다. 이제 **버튼을 우회해 서버에 직접 요청**합니다. F12 → Console에서:
   ```js
   await chi.api('/api/replays/1', { method: 'PATCH', json: { title: '해킹', description: '' } })   // 1 은 다시보기 번호
   await chi.api('/api/replays/1', { method: 'DELETE' })
   ```
   → 각각 **`이 다시보기를 수정할 권한이 없습니다.` / `…삭제할 권한이 없습니다.` (403)** 오류가 나고, 새로고침해도 제목이 그대로입니다.
7. 🙂 시크릿 창 Console에서 같은 명령 → **`로그인이 필요합니다.` (401)**.

**댓글 권한**

8. 🙂 다시보기·방송 상세 화면에서 댓글 목록은 보이지만 입력칸 대신 "댓글을 쓰려면 로그인하세요"가 보입니다.
9. 👤 댓글 작성 → 내 댓글에만 **수정·삭제**가 보입니다. 👑의 댓글에는 버튼이 없습니다.
10. 👑 새로고침 → 👤의 댓글에 **삭제만** 보입니다(스트리머는 자기 다시보기의 댓글을 지울 수 있지만, 남의 댓글을 수정할 수는 없음).
11. 👤 Console에서 👑 댓글을 직접 수정·삭제 시도(댓글 번호는 F12 → Elements의 `data-id`):
    ```js
    await chi.api('/api/comments/1', { method: 'PATCH', json: { content: '조작' } })   // → 403 댓글은 작성자만 수정할 수 있습니다.
    await chi.api('/api/comments/1', { method: 'DELETE' })                          // → 403 …작성자나 …스트리머만 삭제할 수 있습니다.
    ```

**방송 제어 권한**

12. 🙂 **● 방송 시작** → "로그인해야 합니다" 안내만 보입니다.
13. 👑 방송 시작(휴대폰 또는 PC 웹캠) → 👤가 라이브 영역에서 시청. 👤 화면에는 **내 방송** 제어가 없습니다.
14. 👤 Console에서 남의 방송을 직접 제어 시도(방송 번호는 주소창의 `live.html?id=`):
    ```js
    await chi.api('/api/broadcasts/1/end', { method: 'POST' })                       // → 403 이 방송을 종료할 권한이 없습니다.
    await chi.api('/api/broadcasts/1', { method: 'PATCH', json: { title: '탈취' } })  // → 403 이 방송을 수정할 권한이 없습니다.
    ```
    방송은 계속 진행됩니다.
15. 👤 방송 시청 화면에서 댓글 → 👑 송출 화면 아래 댓글 목록에 **바로** 나타나는지 확인 → 👑가 그 댓글 **삭제** → 👤 화면에서도 바로 사라집니다.
16. 👑 **방송 종료** → 종료 후 👑가 방송 화면(`live.html?id=`)을 열면 **[제목 수정] [기록 삭제]** 가 보입니다. 👤에게는 보이지 않으며 Console에서 `DELETE /api/broadcasts/1`을 보내면 403입니다.

자동 검증: `./gradlew test`의 `PermissionTest`가 위 표의 모든 칸(비로그인 401 / 다른 사용자 403 / 소유자·작성자 허용)과 CSRF 거절, 비밀번호 해시 미노출을 실제 서버에 요청해 확인합니다. `BroadcastFlowTest`는 WebSocket 송출자 연결이 비로그인·다른 사용자에게 거절되는지 확인합니다.

### A. 휴대폰 송출 → 다른 화면에서 시청 (2단계)

1. 위 **2-A**대로 HTTPS로 서버를 실행합니다(`https://<PC IP>:8443`).
2. **휴대폰**에서 `https://<PC IP>:8443` 접속 → 인증서 경고 통과 → **로그인**(없으면 회원가입) → **● 방송 시작**.
3. **카메라·마이크 켜기** → 권한 **허용** → 미리보기에 내 얼굴(전면 카메라, 거울 모드)이 나오는지 확인.
4. 제목 입력(예: "퇴근길 방송") → **방송 시작**.
   - ✅ **송출 시작 확인**: 영상 왼쪽 위에 빨간 **LIVE**, 경과 시간이 1초씩 증가, `시청자 0명`, 아래에 시청 주소와 댓글 영역이 표시됩니다. 탭 제목에 🔴가 붙습니다.
5. **PC 브라우저**(또는 다른 휴대폰)에서 `https://<PC IP>:8443` 접속(로그인 불필요) → **라이브** 영역에 방금 방송이 **LIVE** 배지와 함께 맨 앞에 보이는지 확인 → 카드 클릭.
6. 시청 화면에서 휴대폰 영상이 1초 안팎의 지연으로 나오는지 확인 → **🔊 소리 켜기**를 눌러 소리 확인.
   - 휴대폰 송출 화면의 시청자 수가 `시청자 1명`으로 바뀌는지 확인합니다.
7. 휴대폰에서 **카메라 전환**(후면) · **마이크 끄기**를 눌러 시청 화면에 바로 반영되는지 확인합니다.
8. 휴대폰에서 **방송 종료** → 확인.
   - ✅ **송출 종료 확인**: 휴대폰에 "방송을 종료했습니다"와 방송 시간·시작/종료 시각이 나옵니다.
   - 시청 화면은 새로고침 없이 **"방송이 종료되었습니다"**와 **종료** 배지로 바뀝니다.
   - 메인 목록의 라이브 영역에서 해당 방송이 **종료**(흐린 카드, 방송 시간 표시)로 바뀝니다(최대 5초).
9. (끊김 처리) 새 방송을 시작한 뒤 휴대폰에서 **탭을 닫거나 새로고침** → 시청 화면이 바로 "방송이 종료되었습니다"로 바뀌는지 확인합니다.
10. (시청자 상한) `MAX_VIEWERS_PER_BROADCAST=1`로 실행 후 시청 화면을 두 개 열면 두 번째에 "시청자가 가득 찼습니다"가 뜹니다.

> 휴대폰 없이 PC 한 대로 흐름만 확인하려면: PC에 웹캠이 있으면 `http://localhost:8080/go-live.html`(localhost는 HTTPS 없이도 카메라 허용)에서 로그인 후 송출하고, 다른 브라우저 창에서 `http://127.0.0.1:8080`으로 시청하면 됩니다.

### B. 다시보기 (1단계 기능 유지 확인)

테스트용 5초 영상이 `samples/sample.mp4`에 들어 있습니다.

1. 로그인 → 메인 → **＋ 다시보기 등록** → 제목 입력 → *파일 업로드* 탭에서 `samples/sample.mp4` 선택 → **등록하기**.
2. 상세 화면(`/replay.html`)으로 이동해 재생·구간 이동이 되는지 확인합니다. (재생은 로그아웃 상태에서도 됩니다.)
3. 메인 아래쪽 **다시보기** 영역에 카드(썸네일, `0:05`, 조회수)가 보이는지 확인합니다.
4. 서버를 종료하고 다시 실행 → 계정·다시보기·종료된 방송 기록·댓글이 그대로 남아 있는지 확인합니다. (재시작 전에 방송 중이던 방송은 **종료**로 표시되고, 로그인 세션은 재시작 시 초기화되어 다시 로그인해야 합니다.)

자동 검증: `./gradlew test` — 실제 서버를 임시 폴더로 띄워 확인합니다(총 11개).
- `PermissionTest`(6): 가입·로그인·로그아웃, CSRF, 다시보기·방송·댓글 권한 매트릭스, 댓글 페이지네이션
- `BroadcastFlowTest`(3): 소유자만 송출, 시그널링, 방송 중 댓글·제목 실시간 전달, 시청자 상한, 끊김 시 즉시 종료, 종료 후 기록 삭제, 재시작 정리
- `ReplayFlowTest`(2): 등록 → 목록 → Range 재생 → 재시작 후 유지, 입력 검증

## API

**인증**

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/auth/me` | 현재 사용자(없으면 `null`)와 CSRF 토큰 `{user, csrf:{headerName, token}}` |
| `POST` | `/api/auth/signup` | `{"username","password","nickname"}` → 201, 가입 후 바로 로그인 (중복 409, 형식 오류 400) |
| `POST` | `/api/auth/login` | `{"username","password"}` → 200 (틀리면 401) |
| `POST` | `/api/auth/logout` | 세션 무효화 → 204 |

**방송** (`🔒` = 로그인 필요, `👑` = 소유자만)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/broadcasts` | 목록: 방송 중(최근 시작 순) → 종료(최근 30개) |
| `GET` | `/api/broadcasts/{id}` | 상세 (`status`: `live`/`ended`, `owner{id,nickname}`, `viewerCount`) |
| `POST` | `/api/broadcasts` 🔒 | 방송 시작 `{"title"}` → 201 |
| `PATCH` | `/api/broadcasts/{id}` 👑 | 제목 수정 `{"title"}` |
| `POST` | `/api/broadcasts/{id}/end` 👑 | 방송 종료 |
| `DELETE` | `/api/broadcasts/{id}` 👑 | 종료된 방송 기록 삭제(댓글 포함) → 204, 방송 중이면 409 |
| `GET` | `/api/live-config` | WebRTC ICE 서버(STUN/TURN)·시청자 상한 |
| `WS` | `/ws/broadcasts/{id}` | 시그널링. 첫 메시지 `{"type":"join","role":"host"}` 👑 또는 `{"type":"join","role":"viewer"}`. 방송 중 `comment`·`title` 알림 수신 |

**다시보기**

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/replays` | 목록 (최신순) |
| `GET` | `/api/replays/{id}` | 상세 (`owner{id,nickname}`) |
| `POST` | `/api/replays` 🔒 | 등록. `multipart/form-data`: `title`(필수), `description`, `durationSec`, 그리고 `video`(파일) **또는** `videoUrl` |
| `PATCH` | `/api/replays/{id}` 👑 | 수정 `{"title","description"}` |
| `DELETE` | `/api/replays/{id}` 👑 | 삭제(영상 파일·댓글 포함) → 204 |
| `POST` | `/api/replays/{id}/views` | 조회수 1 증가 (비로그인 가능, CSRF 토큰 필요) |
| `GET` | `/media/{filename}` | 업로드된 영상 파일 (Range 요청 지원) |

**댓글**

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/broadcasts/{id}/comments`, `/api/replays/{id}/comments` | 최신순 목록 `?before={댓글id}&size={1~100, 기본 50}` → `{comments, nextBefore}` |
| `POST` | 위 두 경로 🔒 | 작성 `{"content"}` (1~500자) → 201 |
| `PATCH` | `/api/comments/{id}` | 수정 — 작성자만 |
| `DELETE` | `/api/comments/{id}` | 삭제 — 작성자 또는 스트리머 → 204 |

상태를 바꾸는 요청(POST/PATCH/DELETE)에는 `/api/auth/me`로 받은 CSRF 토큰을 `X-CSRF-TOKEN` 헤더로 보내야 합니다. 오류는 모두 `{"error": "메시지"}` 형태입니다.

## 설계 문서

- [docs/stage3-design.md](docs/stage3-design.md) — 3단계 아키텍처, ERD, 기술적 고민 및 개선
- [docs/stage2-design.md](docs/stage2-design.md) — 2단계 설계 (라이브 송출)
- [docs/stage1-design.md](docs/stage1-design.md) — 1단계 설계 (다시보기)
- [GLOSSARY.md](GLOSSARY.md) — 용어집
- [docs/adr/](docs/adr/) — 주요 설계 결정 기록 (WebRTC P2P, 방송/다시보기 분리, 세션 인증, 송출 키 → 소유자 검사)

## 폴더 구조

```
src/main/java/com/chichijik/
  ChichijikApplication.java     진입점
  user/                         사용자: 엔티티·가입·로그인(AuthController)·세션 사용자(LoginUser)
  broadcast/                    방송: 엔티티·서비스(소유자 검사)·컨트롤러, 방(LiveRooms), WebSocket 시그널링
  replay/                       다시보기: 엔티티·서비스(검증, 파일 저장, 소유자 검사)·컨트롤러
  comment/                      댓글: 엔티티·서비스(작성자/스트리머 검사, 실시간 알림)·컨트롤러
  config/                       Spring Security, /media/** 영상 제공, WebSocket, 이전 단계 DB 호환
  common/                       공통 예외·오류 응답, 소유자 검사(Ownership), 커밋 후 실행(AfterCommit)
src/main/resources/
  application.properties        설정 (환경변수 기본값)
  static/                       메인(index) · 로그인(auth) · 방송 시작(go-live) · 방송 시청(live) · 다시보기(replay, upload) · 댓글 위젯(comments.js)
src/test/java/                  통합 테스트 (ApiClient: 사용자별 세션·CSRF 를 가진 테스트용 클라이언트)
samples/                        확인용 샘플 영상
data/, certs/                   (git 제외) DB·업로드 파일, HTTPS 인증서
```
