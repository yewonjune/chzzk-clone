# 치지직 (Chzzk)

**휴대폰으로 라이브 방송을 하고**, 방송과 **녹화 영상(다시보기)을 시청**하며, 로그인한 사용자가 **댓글**을 달고 스트리머에게 **후원(도네이션)** 하는 스트리밍 사이트입니다.

| 단계 | 내용 |
| --- | --- |
| 1단계 | 다시보기(녹화 영상) 등록 · 목록 · 상세 · 재생 · 영구 저장 |
| 2단계 | 휴대폰 카메라·마이크 라이브 송출, 방송 목록·시청, 방송 중/종료 상태 표시 |
| 3단계 | 회원가입·로그인·로그아웃, 소유자 권한(등록·수정·삭제·송출 제어), 댓글(작성·목록·수정·삭제) |
| 4단계 | 도네이션: 방송자·금액 선택 → **테스트 모드 결제** → 서버가 승인·금액·받는 사람 확인 후 확정, 중복 승인 통지 방지, 방송별 후원 내역·합계 |
| 5단계 | 로컬 오토스케일: 컨테이너 이미지, Docker Desktop Kubernetes 에서 2~5개 인스턴스 + HPA, 상태를 PostgreSQL·Redis·공유 볼륨으로 외부화, 서버가 바뀌어도 방송이 이어지는 재연결 유예 |
| **6단계 (현재)** | 도메인·OCI Always Free VM 배포(Docker Compose + Caddy 자동 HTTPS), **Windows·macOS 데스크톱 앱**(Electron, GitHub Actions 로 빌드 → Releases), 다운로드 페이지 `/download.html` |

- 백엔드: Java 17+ · Spring Boot 4.1 (Spring MVC, Spring Data JPA, Spring WebSocket, **Spring Security**)
- 인증: 서버 세션(쿠키) + BCrypt 비밀번호 + CSRF 토큰 ([ADR-0003](docs/adr/0003-session-auth-with-spring-security.md))
- 결제: **토스페이먼츠 테스트 모드**(테스트 키만 허용, 실제 돈이 나가지 않음). 키가 없으면 같은 2단계 흐름을 흉내 내는 **내장 테스트 결제창**으로 동작 ([ADR-0005](docs/adr/0005-donation-confirmed-by-server.md))
- 라이브: **WebRTC** (휴대폰 → 시청자 P2P 직접 전송, 서버는 WebSocket으로 연결 정보와 댓글 알림만 중계)
- 저장소: 기본은 H2 파일 DB(별도 설치 없이 `data/` 폴더). 여러 인스턴스(`cluster` 프로필)는 **PostgreSQL + Redis(세션·방송 실시간 상태) + 공유 볼륨** ([ADR-0006](docs/adr/0006-shared-state-for-multiple-instances.md))
- 배포: Docker 이미지 + Docker Desktop 내장 Kubernetes(Deployment·LoadBalancer Service·HPA), 부하는 k6
- 공개 배포(6단계): OCI VM 한 대에 Docker Compose — Caddy(Let's Encrypt 자동 HTTPS) → 앱(`cluster` 프로필) + PostgreSQL + Redis (+ 선택 coturn) ([ADR-0007](docs/adr/0007-single-vm-compose-with-caddy.md))
- 데스크톱 앱(6단계): Electron 44 + electron-builder, Windows(x64·arm64)·macOS(arm64·x64), GitHub Actions 빌드 → GitHub Releases
- 빌드: Gradle (Wrapper 포함, Gradle 설치 불필요)
- 프론트엔드: 정적 HTML/CSS/JS (`src/main/resources/static`, 빌드 과정 없음)

용어는 [GLOSSARY.md](GLOSSARY.md)를 따릅니다. **방송**은 휴대폰으로 하는 실시간 송출, **다시보기**는 등록된 녹화 영상, **스트리머**는 그 방송·다시보기를 만든 사용자(소유자)입니다.

## 실행 방법

필요 환경: **JDK 17 이상** (개발·검증은 JDK 25에서 진행). 서버 한 대로 실행할 때는 ffmpeg·Docker·DB 서버 등 다른 설치가 필요 없습니다.
여러 인스턴스·오토스케일은 **Docker Desktop(Kubernetes 켜기)** 이 필요합니다 → [여러 인스턴스로 실행하기 (5단계)](#여러-인스턴스로-실행하기-5단계).

### IntelliJ IDEA

1. `File → Open` 으로 이 폴더(`build.gradle`이 있는 폴더)를 엽니다. Gradle 프로젝트로 자동 인식됩니다.
2. `File → Project Structure → SDK` 에서 JDK 17 이상을 선택합니다.
3. `src/main/java/com/chzzk/ChzzkApplication.java` 의 ▶ 버튼으로 실행합니다.
4. 브라우저에서 http://localhost:8080 접속 → 오른쪽 위 **로그인 → 회원가입**.

휴대폰 송출까지 하려면 아래 [휴대폰으로 송출하기](#휴대폰으로-송출하기)처럼 HTTPS를 켜야 합니다.

### 터미널

```bash
./gradlew bootRun        # Windows: gradlew.bat bootRun
```

| 명령 | 설명 |
| --- | --- |
| `./gradlew bootRun` | 서버 실행 |
| `./gradlew test` | 자동 테스트 21개 (도네이션 결제 흐름 · 권한 매트릭스 · 다시보기 흐름 · 방송 흐름 · 상태 확인), Docker 불필요 |
| `./gradlew clusterTest` | 서버 2대 테스트 17개 (Testcontainers 로 PostgreSQL·Redis, **Docker 필요**) |
| `./gradlew bootJar` | 실행 jar 생성 → `java -jar build/libs/chzzk.jar` |
| `docker build -t chzzk:local .` | 컨테이너 이미지 (JDK 설치 없이 Docker 만으로 빌드) |

> 계정은 처음에 하나도 없습니다. 회원가입 화면에서 직접 만드세요(관리자·기본 계정·기본 비밀번호 없음).

## 환경변수 (`.env.example`)

모두 선택 사항이며 기본값이 있습니다. **실제 비밀번호는 저장소에 넣지 말고** 실행할 때만 설정하세요.
Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로, IntelliJ에서는 **Run/Debug Configurations → Environment variables**에, 터미널에서는 셸 환경변수로 설정합니다.

```dotenv
# 공통
PORT=8080                       # 서버 포트
DATA_DIR=./data                 # H2 DB 파일(chzzk.mv.db) 저장 폴더
UPLOAD_DIR=./data/uploads       # 업로드된 다시보기 영상 저장 폴더
MAX_UPLOAD_SIZE=1GB             # 다시보기 업로드 최대 크기

# 로그인 세션
SESSION_TIMEOUT=12h             # 마지막 요청 이후 로그인 유지 시간

# HTTPS (휴대폰 카메라·마이크 사용에 필요, 켜면 세션 쿠키도 HTTPS 전용)
SSL_ENABLED=false
SSL_KEYSTORE=./certs/chzzk.p12
SSL_KEYSTORE_PASSWORD=<keytool 로 만들 때 정한 비밀번호>

# 방송(WebRTC)
MAX_VIEWERS_PER_BROADCAST=5     # 방송 하나당 동시 시청자 상한
STUN_URLS=stun:stun.l.google.com:19302   # 쉼표로 여러 개, 비우면 같은 네트워크에서만 연결
TURN_URL=                       # (선택) 막힌 네트워크용 중계 서버. 예: turn:turn.example.com:3478
TURN_USERNAME=
TURN_PASSWORD=

# 도네이션 결제 (테스트·샌드박스 전용)
PAYMENT_PROVIDER=auto           # auto: 아래 키가 있으면 토스페이먼츠 테스트 모드, 없으면 내장 테스트 결제창 / toss / fake
TOSS_CLIENT_KEY=                # 토스페이먼츠 "API 개별 연동 키" 클라이언트 키 (test_ck_ 로 시작)
TOSS_SECRET_KEY=                # 같은 화면의 시크릿 키 (test_sk_ 로 시작) — 절대 저장소에 넣지 마세요

# 5단계: 방송 재연결·여러 인스턴스 (기본값으로 충분)
RECONNECT_GRACE=20s             # 송출 연결이 끊긴 뒤 방송을 종료하기 전까지 기다리는 시간 (재연결 유예)
SWEEP_INTERVAL_MS=10000         # 송출 연결 없는 방송·만료된 시청자 정리 주기
HEARTBEAT_INTERVAL=5s           # (cluster) 인스턴스 하트비트 갱신 주기
HEARTBEAT_TTL=15s               # (cluster) 하트비트가 이만큼 끊기면 그 인스턴스의 연결 기록을 정리
SHUTDOWN_PHASE_TIMEOUT=90s      # 종료 신호를 받은 뒤 진행 중인 요청(업로드 등)을 마무리하는 최대 시간

# 6단계: 공개 배포·데스크톱 앱
FORWARD_HEADERS_STRATEGY=native # 앞단 프록시(Caddy·터널)의 X-Forwarded-* 반영 (사설망·localhost 프록시만 신뢰)
SESSION_COOKIE_SECURE=          # 비우면 SSL_ENABLED 와 같음. TLS 를 프록시가 끝내면 true
DESKTOP_ORIGINS=app://chzzk     # 데스크톱 앱 화면 출처: 조회 API·시청 WebSocket 허용
DESKTOP_DOWNLOAD_BASE=https://github.com/yewonjune/chzzk-clone/releases/latest/download   # 설치 파일 위치
DESKTOP_RELEASES_PAGE=https://github.com/yewonjune/chzzk-clone/releases
```

OCI 배포(Compose)용 값 — 도메인, 인증서 알림 이메일, DB 비밀번호, TURN 등 — 은 [`deploy/oci/.env.example`](deploy/oci/.env.example)을 VM 에서 `deploy/oci/.env` 로 복사해 채웁니다(git 제외).

**`cluster` 프로필** (`SPRING_PROFILES_ACTIVE=cluster`, Kubernetes 에서는 `k8s/app.yaml` 이 설정)에서만 쓰는 값입니다. 빠지면 서버가 시작하지 않습니다.

```dotenv
DB_URL=jdbc:postgresql://postgres:5432/chzzk
DB_USERNAME=chzzk
DB_PASSWORD=<직접 정한 값>
REDIS_HOST=redis
REDIS_PORT=6379                 # 기본 6379
REDIS_PASSWORD=                 # (선택)
REDIS_TIMEOUT=2s                # Redis 응답 대기 (readiness 가 빨리 DOWN 이 되도록)
UPLOAD_DIR=/data/uploads        # 모든 인스턴스가 같이 쓰는 폴더
```

Kubernetes 용 비밀 값은 [`.env.cluster.example`](.env.cluster.example)을 `.env.cluster` 로 복사해 채웁니다(`.env.cluster` 는 git 에서 제외).

> 결제 키는 **테스트 키(`test_`로 시작)만** 받습니다. `live_` 키를 넣으면 서버가 시작하지 않습니다. 실제 금전 결제가 일어날 수 있는 설정은 없습니다.

## 여러 인스턴스로 실행하기 (5단계)

필요 환경: **Docker Desktop** (Settings → Kubernetes → *Enable Kubernetes* 켜기), `kubectl`. JDK 는 필요 없습니다(이미지 안에서 빌드). 아래 명령은 저장소 루트에서 실행합니다. Windows 는 Git Bash 기준입니다.

```bash
kubectl config use-context docker-desktop
```

**1) metrics-server 설치 (한 번만, HPA 의 CPU 지표용)** — Docker Desktop 의 kubelet 인증서가 자체 서명이라 `--kubelet-insecure-tls` 를 추가합니다.

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

**2) 이미지 빌드** (Docker Desktop Kubernetes 는 로컬 이미지를 그대로 씁니다. 레지스트리 불필요)

```bash
docker build -t chzzk:local .
```

**3) 비밀 값 Secret** — 예시를 복사해 `DB_PASSWORD` 등을 직접 정한 값으로 바꿉니다. `.env.cluster` 는 git 에서 제외되어 있습니다.

```bash
cp .env.cluster.example .env.cluster
kubectl apply -f k8s/00-namespace.yaml
kubectl -n chzzk create secret generic chzzk-env --from-env-file=.env.cluster
```

**4) 배포**

```bash
kubectl apply -f k8s/
kubectl -n chzzk rollout status deploy/chzzk
kubectl -n chzzk get pods,svc,hpa
```

서버 Pod 2개가 `Running 1/1`, HPA `TARGETS` 가 `cpu: 3%/50%` 처럼 숫자로 보이면 준비된 것입니다(지표는 1분쯤 걸림). 브라우저에서 http://localhost:8080 으로 접속합니다.

- 휴대폰으로 송출하려면 HTTPS 가 필요합니다: 아래 [휴대폰으로 송출하기](#휴대폰으로-송출하기)처럼 키스토어를 만든 뒤 `kubectl -n chzzk create secret generic chzzk-tls --from-file=keystore.p12=certs/chzzk.p12`, `.env.cluster` 에 `SSL_ENABLED=true`·`SSL_KEYSTORE_PASSWORD` 를 넣고 Secret 을 다시 만든 다음 `kubectl -n chzzk rollout restart deployment/chzzk`. 휴대폰에서는 `https://<PC IP>:8080` (Windows 방화벽에서 8080 허용).
- 코드를 바꾼 뒤: 2) 의 이미지 빌드 → `kubectl -n chzzk rollout restart deployment/chzzk`
- 전부 지우기(DB·업로드 데이터 포함): `kubectl delete namespace chzzk`

## 공개 서비스 배포 (6단계: 도메인 · OCI Always Free VM · HTTPS)

### 공개 주소와 도메인

| 항목 | 내용 |
| --- | --- |
| 서비스 주소 | `https://yewonjune.com` |
| 다운로드 페이지 | `https://yewonjune.com/download.html` |
| 상태 확인 | `https://yewonjune.com/actuator/health` (`{"status":"UP"}`), `/actuator/health/readiness`, `/actuator/health/liveness` |
| 데스크톱 앱 파일 | https://github.com/yewonjune/chzzk-clone/releases/latest |
| 도메인 등록 여부 | **등록함** (`yewonjune.com`) — 등록처: ___ , 등록일: ___ , 네임서버: `ns1~ns4.hosting.co.kr` |
| 최초 등록 비용 | ___ 원 (구매 영수증 기준으로 기입. 결제 정보는 적지 않음) |
| 갱신 비용 | ___ 원/년 (등록처의 갱신 가격 표 기준으로 기입) |

> 도메인은 등록했고, VM 생성·배포는 아직 진행 전입니다. 비어 있는 값은 아래 절차대로 진행한 뒤 실제 값으로 바꿉니다. 가격은 등록처·TLD·환율·프로모션에 따라 달라서 예상값을 적지 않았습니다(처음 1년 할인 뒤 갱신가가 오르는 경우가 많으니 **갱신 가격을 꼭 같이 확인**).

### 구성

```
 인터넷 ── DNS: yewonjune.com A 레코드 → VM 공인 IP
   │ 80/443 (OCI 보안 목록 + VM iptables 허용)
   ▼
 OCI VM (Ubuntu, Docker Compose)  deploy/oci/compose.yaml
   caddy  :80 → https 이동, :443 TLS 종료 (Let's Encrypt 자동 발급·갱신)
     └─▶ app:8080  Spring Boot (cluster 프로필, 프록시 헤더로 https 인식, 세션 쿠키 Secure)
           ├─ postgres  기록 (볼륨 pgdata)
           ├─ redis     로그인 세션·방송 방 상태
           └─ uploads   업로드 영상 (볼륨)
   (선택) coturn  3478 + 49160-49200/udp  WebRTC 중계
```

### 1) OCI Always Free VM 만들기

1. OCI 콘솔 → 오른쪽 위 리전이 **홈 리전**인지 확인 (Always Free 자원은 홈 리전에서만 생성).
2. **거버넌스 → 한도, 할당량 및 사용량**에서 `Compute` 서비스의 *Standard A1 core / memory* 잔여 한도와, 이미 쓰고 있는 A1 인스턴스가 있는지 확인합니다.
3. **컴퓨트 → 인스턴스 → 인스턴스 생성**
   - 이미지: **Canonical Ubuntu 24.04** (aarch64 빌드가 자동 선택됨)
   - 구성(Shape): **Ampere → VM.Standard.A1.Flex, 2 OCPU · 12 GB** (Always Free 지원 표시 확인)
   - 네트워킹: 새 VCN·공용 서브넷, **공용 IPv4 주소 할당**
   - SSH 키: 내 공개 키 붙여넣기(또는 키 쌍 내려받기 — 개인 키는 저장소에 넣지 않음)
   - 부트 볼륨: 기본 50GB (Always Free 블록 스토리지 범위 안)
4. "**Out of capacity / 용량 부족**"이 나오면: 다른 가용성 도메인(AD)으로 다시 시도 → 시간을 두고 재시도 → 그래도 안 되면 아래 **Micro 대안**.
5. 인스턴스의 **공인 IP**를 기록합니다.
6. **네트워킹 → VCN → 보안 목록(Default Security List) → 수신 규칙 추가**: 소스 `0.0.0.0/0`, TCP **80**, TCP **443**, UDP **443**(HTTP/3). TURN 을 쓰면 TCP/UDP **3478**, UDP **49160-49200**.

**선택 기록** (배포 후 기입): 홈 리전 ___, A1 잔여 한도 ___, 생성한 구성 ___, 재고 문제 여부 ___.

**Micro 대안** — A1 재고가 계속 없을 때: `VM.Standard.E2.1.Micro`(AMD, 1/8 OCPU, 1GB, Always Free) 한 대에서 PostgreSQL·Redis 없이 H2 로 실행합니다. 메모리가 1GB 라 이미지 빌드 전에 스왑을 만들고, `compose.micro.yaml` 을 덧씌웁니다.
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
docker compose -f deploy/oci/compose.yaml -f deploy/oci/compose.micro.yaml --env-file deploy/oci/.env up -d --build
```
A1 을 우선하는 이유: 서버(JVM)·PostgreSQL·Redis·Caddy·이미지 빌드를 한 대에서 돌리기에 1GB 는 빠듯하고, ARM 이미지도 같은 Dockerfile 로 빌드되기 때문입니다.

### 2) 도메인 구매와 DNS

1. 도메인 등록처(예: 가비아, Cloudflare Registrar, Porkbun, Namecheap 등)에서 도메인을 구매합니다. **결제는 본인이 직접** 하고, 영수증의 최초 등록 비용과 등록처의 갱신 비용을 위 표에 기입합니다.
2. 등록처(또는 사용하는 DNS 서비스)의 DNS 관리에서 레코드를 추가합니다.

| 유형 | 이름 | 값 | TTL |
| --- | --- | --- | --- |
| A | `@` | VM 공인 IP | 300 |
| CNAME | `www` | `yewonjune.com` | 300 |

   Cloudflare DNS 를 쓰면 처음에는 **프록시 끔(회색 구름, DNS only)** 으로 두세요. Caddy 가 80번 포트로 직접 인증서를 받아야 하고, WebSocket·업로드 크기 제한도 피할 수 있습니다.
3. 반영 확인: `nslookup yewonjune.com` 결과가 VM 공인 IP 인지 확인합니다(보통 수 분, 길면 수 시간).

### 3) VM 에 배포

```bash
ssh ubuntu@<VM 공인 IP>
curl -fsSL https://raw.githubusercontent.com/yewonjune/chzzk-clone/main/deploy/oci/setup-vm.sh -o setup-vm.sh
bash setup-vm.sh            # Docker 설치 + iptables 80/443 허용 + 저장소 받기 (TURN 도 열려면 --turn)
exit                        # docker 그룹 반영을 위해 다시 접속
ssh ubuntu@<VM 공인 IP>
cd ~/chzzk-clone
nano deploy/oci/.env        # DOMAIN, ACME_EMAIL, DB_PASSWORD (+ 선택 TURN_*, TOSS_* 테스트 키)
docker compose -f deploy/oci/compose.yaml --env-file deploy/oci/.env up -d --build
docker compose -f deploy/oci/compose.yaml --env-file deploy/oci/.env ps        # app 이 healthy, caddy 가 running
docker compose -f deploy/oci/compose.yaml --env-file deploy/oci/.env logs -f caddy   # "certificate obtained successfully"
```

- 첫 빌드는 A1 에서 수 분 걸립니다(Gradle 의존성). 이후 코드 갱신: `git pull && docker compose ... up -d --build app`
- **비밀 값**: `deploy/oci/.env`(권한 600, git 제외)에만 둡니다. 저장소에는 [`deploy/oci/.env.example`](deploy/oci/.env.example)(예시 값)만 있습니다.
- **TLS 구성**: Caddy 가 `DOMAIN` 으로 Let's Encrypt 인증서를 자동 발급(HTTP-01, 80번 포트)·갱신하고 볼륨 `caddy_data` 에 보관합니다. 앱은 내부 HTTP 로만 받고, `X-Forwarded-Proto` 로 HTTPS 를 인식합니다. 설정: [`deploy/oci/Caddyfile`](deploy/oci/Caddyfile)
- **TURN(선택)**: 휴대폰(LTE)과 시청자가 다른 네트워크라 영상이 안 붙으면 `.env` 에 `TURN_URL=turn:yewonjune.com:3478`, `TURN_USERNAME`, `TURN_PASSWORD`, `TURN_EXTERNAL_IP=<공인IP>/<사설IP>` 를 넣고 `docker compose ... --profile turn up -d`
- 데이터 백업: `docker compose ... exec postgres pg_dump -U chzzk chzzk > backup.sql`

## 데스크톱 클라이언트 (6단계)

### 내려받기·설치

공개 다운로드 페이지 **`https://yewonjune.com/download.html`** 에서 OS 에 맞는 파일을 받습니다(파일은 GitHub Releases 에 있음).

| OS | 아키텍처 | 파일 | 설치·실행 |
| --- | --- | --- | --- |
| Windows 10/11 | x64 | `Chzzk-Setup-win-x64.exe` | 실행 → "Windows의 PC 보호" 창에서 **추가 정보 → 실행** → 설치 → 바탕화면 **치지직** |
| Windows 10/11 | x64 | `Chzzk-win-x64.zip` | 압축 풀고 `Chzzk.exe` 실행 (설치 없음) |
| Windows 11 | arm64 | `Chzzk-Setup-win-arm64.exe` | x64 와 같음 |
| macOS 12+ | arm64 (Apple Silicon) | `Chzzk-mac-arm64.dmg` / `.zip` | dmg 열기 → Chzzk 를 Applications 로 → 처음 실행이 막히면 **시스템 설정 → 개인정보 보호 및 보안 → 그래도 열기** (14 이하는 Control-클릭 → 열기). "손상됨"이 뜨면 `xattr -dr com.apple.quarantine /Applications/Chzzk.app` |
| macOS 12+ | x64 (Intel) | `Chzzk-mac-x64.dmg` / `.zip` | arm64 와 같음 |

코드 서명 인증서가 없어(유료) 처음 실행할 때 OS 확인이 한 번 필요합니다. 앱은 처음부터 빌드 때 지정한 서버에 연결되며, 오른쪽 위 **서버 설정**에서 바꿀 수 있습니다.

### 빌드·릴리스

```bash
# 저장소 Settings → Secrets and variables → Actions → Variables 에 CHZZK_SERVER_URL=https://yewonjune.com 추가 (비밀 아님)
git tag desktop-v1.0.0
git push origin desktop-v1.0.0
# → Actions "Desktop app" 이 windows-latest·macos-latest 에서 빌드 → Releases 에 7개 파일 첨부
```

로컬에서 직접 빌드 (Node.js 22+):
```bash
cd desktop
npm ci                      # npm 11 이 설치 스크립트를 막으면 package.json 의 allowScripts(electron) 로 허용됨
npm start                   # 개발 실행 (기본 서버는 package.json 의 chzzkServerUrl)
npm run dist:win            # Windows 에서 → desktop/dist/*.exe, *.zip
npm run dist:mac            # macOS 에서 → desktop/dist/*.dmg, *.zip (macOS 패키지는 macOS 에서만 빌드 가능)
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
| 방송별 후원 내역·합계 보기 | ✅ | ✅ | ✅ |
| 후원하기 (주문 생성) | ❌ 401 | ✅ | ❌ 400 자기 방송에는 후원 불가 |
| 후원 승인 확인·취소 처리, 주문 상세 | ❌ 401 | ❌ 403 (남의 주문) | ✅ 후원한 본인만 |
| 결제 웹훅 `/api/payments/webhook` | PG 서버가 호출 (로그인·CSRF 없음). 본문은 믿지 않고 PG에 다시 조회한 결과만 반영 | | |

- 401 = 로그인 필요, 403 = 권한 없음. 응답은 `{"error": "로그인이 필요합니다."}`처럼 이유를 담습니다.
- 상태를 바꾸는 모든 요청에는 CSRF 토큰이 필요합니다(없으면 403 "보안 토큰이 없거나 만료되었습니다"). 화면은 `/api/auth/me`로 받은 토큰을 자동으로 붙입니다.
- 송출 권한은 WebSocket 연결 요청에 실려 오는 로그인 세션으로 확인합니다. 2단계의 "송출 키"는 없앴습니다([ADR-0004](docs/adr/0004-owner-check-replaces-stream-key.md)).
- 3단계 이전에 만들어진(소유자가 없는) 기록은 "익명"으로 보이며, 누구도 수정·삭제할 수 없습니다(댓글 작성은 가능).

## 이번 단계 구현 범위

**6단계에서 구현함 — 도메인·OCI 배포와 Windows/macOS 클라이언트**
- **배포 설정** ([`deploy/oci/`](deploy/oci/)): VM 한 대에서 `docker compose up -d --build` 로 전체 실행
  - Caddy: 도메인으로 Let's Encrypt 인증서 **자동 발급·갱신**, HTTP→HTTPS 이동, HSTS, WebSocket·영상 Range 요청 전달, `/actuator/*` 중 health 외 차단
  - 앱: 5단계 `cluster` 프로필(PostgreSQL 기록, Redis 세션·방 상태, 업로드 볼륨) 그대로 → 계정·방송·댓글·후원 기능 유지
  - (선택) coturn TURN 서버: 휴대폰 LTE ↔ 다른 집 PC 처럼 직접 연결이 안 되는 시청을 중계
  - `setup-vm.sh`: Docker 설치, OCI Ubuntu 이미지의 iptables 에 80/443(+TURN) 허용, 저장소 받기
- **서버 변경**
  - 프록시 뒤 HTTPS 인식: `server.forward-headers-strategy=native` (사설망·localhost 프록시의 `X-Forwarded-*` 만 신뢰), 세션 쿠키 `Secure` 를 `SESSION_COOKIE_SECURE` 로 따로 지정
  - 데스크톱 앱 출처(`app://chzzk`, `DESKTOP_ORIGINS`)에 한해 **조회(GET)만 CORS 허용**(쿠키 없음)·**시청 WebSocket 허용**. 다른 출처·상태 변경은 이전과 같이 막힘
  - 상태 확인 경로: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`(DB·Redis 포함) — Compose healthcheck 가 readiness 로 판단하고, Caddy 는 앱이 healthy 가 된 뒤 시작
  - 다운로드 목록 API `GET /api/downloads` + 다운로드 페이지 **`/download.html`** (OS별 파일 링크, 지원 아키텍처, 설치·실행 방법, 접속한 기기에 맞는 파일 추천). 메인 화면 오른쪽 위 **⬇ 앱**
  - 배포 주소·비밀 값은 모두 환경변수(`deploy/oci/.env`, git 제외)
- **데스크톱 앱** ([`desktop/`](desktop/), Electron)
  - 방송 목록(라이브·다시보기·지난 방송, 10초마다 갱신) → 카드를 누르면 재생
  - 다시보기: 서버 영상 재생(구간 탐색), 라이브: 웹 시청 화면과 같은 시그널링으로 **WebRTC 시청**(자동 재연결, 시청자 수, 방송 종료 표시)
  - 서버 주소: 빌드할 때 넣은 기본값(`CHZZK_SERVER_URL`) + 앱의 **서버 설정**에서 변경 가능
  - 보안: 화면은 `app://chzzk` 로만 로드, contextIsolation·sandbox, CSP, 외부 링크는 기본 브라우저로. 댓글·후원·송출은 웹으로 안내(앱은 시청 전용)
  - 패키지: Windows `Chzzk-Setup-win-x64.exe`·`Chzzk-Setup-win-arm64.exe`(NSIS 설치), `Chzzk-win-x64.zip`(설치 없이), macOS `Chzzk-mac-arm64.dmg`·`Chzzk-mac-x64.dmg`(+zip). 코드 서명 인증서 없음 — macOS 는 ad-hoc 서명(Apple Silicon 실행용), 공증 없음
  - [`.github/workflows/desktop.yml`](.github/workflows/desktop.yml): `desktop-v*` 태그를 push 하면 Windows·macOS 러너가 빌드해 **GitHub Releases** 에 올림 (파일 이름에 버전이 없어 `releases/latest/download/<파일>` 이 항상 최신)
- **자동 테스트**: 기본 23개 (+ `DesktopAccessTest` 2개: 데스크톱 출처 조회·시청 허용, 다른 출처·상태 변경 차단, 프록시 뒤 HTTPS 요청의 같은 출처 인식)

**5단계에서 구현함 — 로컬 오토스케일 (유지)**
- **컨테이너 이미지**: 멀티스테이지 `Dockerfile` (JDK 17 로 빌드 → JRE 17 로 실행, root 아닌 사용자). Docker 만 있으면 빌드
- **여러 인스턴스 실행**: Docker Desktop 내장 Kubernetes 에 `k8s/` 적용 → 서버 Deployment(`cluster` 프로필), PostgreSQL, Redis, 업로드 공유 볼륨
- **자동 확장·축소**: HPA — **평균 CPU 50%** 목표, **최소 2 ~ 최대 5개**, 부하가 사라지면 60초 지켜본 뒤 축소 (metrics-server 사용)
- **요청 분산**: `LoadBalancer` Service(8080)가 Pod 들에 나눠 보냄. WebSocket 도 같은 Service 로
- **상태를 인스턴스 밖에** ([ADR-0006](docs/adr/0006-shared-state-for-multiple-instances.md))
  - 기록(사용자·방송·다시보기·댓글·후원, 가짜 PG 결제) → PostgreSQL
  - 로그인 세션·CSRF 토큰 → Redis (Spring Session). 어느 Pod 로 가도 로그인 유지, 로그아웃도 모든 Pod 에 즉시
  - 업로드 영상 → 모든 Pod 가 마운트하는 공유 볼륨
  - 방송 실시간 상태(송출자 연결, 시청자 목록·수·상한) → Redis. 방송 중 메시지(시그널·댓글·후원·제목·종료)는 **Redis pub/sub 으로 모든 Pod 에 중계** → 송출자와 시청자가 다른 Pod 에 붙어도 방송됨
- **스케일 인·장애에도 방송 유지 — 재연결 유예**
  - 송출 연결이 끊겨도 바로 종료하지 않고 **20초** 기다림. 그 안에 다시 연결되면 같은 방송이 이어짐
  - 내려가는 Pod 는 열린 연결을 **4001**("다른 서버로 재연결")로 닫고, 송출·시청 화면은 **자동으로 다른 Pod 에 재연결**(시청자는 같은 viewerId 로 이어 붙어 시청자 수가 늘지 않음, 영상 P2P 는 그대로)
  - 강제로 죽은 Pod 는 하트비트(15초)가 끊기면 다른 Pod 가 그 시청자 기록을 정리하고, 송출자는 유예 뒤 종료
  - 서버가 새로 떠도 방송을 끝내지 않음 (1~4단계의 "기동 시 방송 중인 방송 전부 종료" 제거)
- **운영**: Actuator readiness(DB·Redis 포함)·liveness probe, graceful shutdown(최대 90초 마무리, 120초 뒤 강제 종료)
- **부하 스크립트**: [`load/autoscale.js`](load/autoscale.js) (k6, Docker 로 실행) — 부하 시나리오 + 같은 로그인 사용자로 상태 유지를 계속 확인하는 시나리오
- **자동 테스트**: 기본 21개 + 서버 2대 `clusterTest` 17개 (서버 종료·강제 종료 시나리오 포함)

**이전 단계 기능 (유지)**
- 4단계: 도네이션(테스트 모드 결제 → 서버 확정, 중복 승인 통지 방지, 방송별 후원 내역·합계)
- 3단계: 회원가입·로그인·로그아웃, 소유자만 다시보기 수정·삭제 / 방송 제목 수정·종료·기록 삭제 / 송출 연결, 댓글(작성·목록·수정·삭제, 방송 중 실시간)
- 2단계: 휴대폰 송출(카메라 전환·마이크 끄기·종료), WebRTC 시청, 방송 중/종료 상태, 끊김 시 종료(5단계부터 재연결 유예 뒤), 시청자 상한, HTTPS·STUN/TURN 설정
- 1단계: 다시보기 등록(파일 업로드·외부 주소), 목록·상세·재생(구간 탐색), 조회수, 재시작 후 유지

**6단계에서 하지 않음**: 코드 서명·Apple 공증(유료 인증서 필요), 앱 자동 업데이트, 데스크톱 앱에서 로그인·댓글·후원·송출, 여러 VM·클라우드 로드밸런서, DB 자동 백업, Linux 데스크톱 패키지

**5단계에서 하지 않음**: 원격 레지스트리·Ingress, 오브젝트 스토리지(MinIO/S3), PostgreSQL·Redis 고가용성(Redis 재시작 시 모두 로그아웃), 요청 수 등 사용자 정의 지표 스케일링, 스키마 마이그레이션 도구, H2 → PostgreSQL 데이터 이전, 송출 화면 새로고침 후 같은 방송 이어받기

## 도네이션 결제 설정

### 기본: 키 없이 내장 테스트 결제창

아무 설정 없이 실행하면 **내장 테스트 결제창**(`/fake-pg.html`)이 열립니다. 실제 PG처럼 "결제창 인증 → 서버 승인" 두 단계로 동작하고, 인증 때 정해진 금액과 다른 금액으로 승인을 요청하면 거절합니다. 결과를 버튼으로 고를 수 있어 성공·실패·취소를 바로 확인할 수 있습니다.

### 토스페이먼츠 테스트 모드

1. [토스페이먼츠 개발자센터](https://developers.tosspayments.com)에 이메일로 가입합니다(사업자 등록 불필요).
2. **API 키** 메뉴의 **API 개별 연동 키**에서 테스트 클라이언트 키(`test_ck_…`)와 테스트 시크릿 키(`test_sk_…`)를 확인합니다.
3. 환경변수로 넣고 실행합니다. **시크릿 키는 파일이나 저장소에 적지 마세요.**
   ```powershell
   # Windows PowerShell
   $env:TOSS_CLIENT_KEY="test_ck_..."; $env:TOSS_SECRET_KEY="test_sk_..."; .\gradlew.bat bootRun
   ```
   ```bash
   # macOS / Linux / Git Bash
   TOSS_CLIENT_KEY=test_ck_... TOSS_SECRET_KEY=test_sk_... ./gradlew bootRun
   ```
   IntelliJ: Run Configuration → Environment variables에 `TOSS_CLIENT_KEY=test_ck_...;TOSS_SECRET_KEY=test_sk_...`
4. 서버 로그에 `결제: 토스페이먼츠 테스트 모드`가 나오고, 후원 화면 상단 안내가 "토스페이먼츠 테스트 모드"로 바뀝니다. 테스트 모드에서는 카드 정보를 넣어도 **실제로 돈이 빠져나가지 않습니다**.
5. (선택) 웹훅: 개발자센터 **웹훅** 메뉴에 `PAYMENT_STATUS_CHANGED` 이벤트로 `https://<공개 주소>/api/payments/webhook`을 등록합니다. PG 서버가 내 PC로 들어와야 하므로 [cloudflared](#2-b-대안-터널로-정식-https-주소-받기) 같은 터널 주소가 필요합니다. 웹훅이 없어도 후원 확정에는 영향이 없습니다(확정은 승인 API 응답으로 함).

> 테스트 결제창은 `localhost`/`127.0.0.1` 주소에서도 동작합니다. 결제가 끝나면 PG가 `successUrl`/`failUrl`(이 서버의 `/donate-result.html`)로 돌려보냅니다.

## 휴대폰으로 송출하기

### 1) 왜 HTTPS가 필요한가

휴대폰 브라우저는 **HTTPS 주소(또는 localhost)에서만 카메라·마이크를 허용**합니다. PC에서 `http://localhost:8080`은 되지만, 휴대폰에서 `http://192.168.0.10:8080`으로 접속하면 송출 화면에 "이 주소에서는 카메라·마이크를 쓸 수 없습니다"가 뜹니다. 아래 방법 A(기본) 또는 B 중 하나로 HTTPS 주소를 만드세요.

### 2-A) 자체 서명 인증서로 HTTPS 켜기 (기본, 추가 설치 없음)

JDK에 들어 있는 `keytool`로 인증서를 만듭니다. `192.168.0.10`은 PC의 실제 IP로 바꾸세요.
PC IP 확인: Windows `ipconfig` (Wi-Fi 어댑터의 IPv4 주소) / macOS `ipconfig getifaddr en0`.

```bash
mkdir certs
keytool -genkeypair -alias chzzk -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore certs/chzzk.p12 -dname "CN=chzzk" -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.0.10"
```

비밀번호를 물으면 원하는 값을 입력합니다(이 값은 저장소에 올리지 마세요. `certs/`는 git에서 제외됨). 그다음 환경변수를 넣고 실행합니다.

```bash
# macOS / Linux / Git Bash
PORT=8443 SSL_ENABLED=true SSL_KEYSTORE=certs/chzzk.p12 SSL_KEYSTORE_PASSWORD=<비밀번호> ./gradlew bootRun
```

```powershell
# Windows PowerShell
$env:PORT="8443"; $env:SSL_ENABLED="true"; $env:SSL_KEYSTORE="certs/chzzk.p12"; $env:SSL_KEYSTORE_PASSWORD="<비밀번호>"; .\gradlew.bat bootRun
```

IntelliJ라면 Run Configuration의 Environment variables에 `PORT=8443;SSL_ENABLED=true;SSL_KEYSTORE=certs/chzzk.p12;SSL_KEYSTORE_PASSWORD=<비밀번호>`를 넣습니다.

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

### F. 공개 배포·데스크톱 앱 (6단계) — 외부 네트워크에서 도메인·HTTPS·다운로드·재생

**서버 (휴대폰 LTE 처럼 VM 과 다른 네트워크에서)**
1. `nslookup yewonjune.com` → VM 공인 IP.
2. 브라우저로 `http://yewonjune.com` → 자동으로 `https://yewonjune.com` 으로 이동, 주소창 자물쇠 → 인증서 발급자 **Let's Encrypt**, 만료일 확인.
3. `https://yewonjune.com/actuator/health` → `{"status":"UP"}`.
4. 웹에서 회원가입 → 다시보기 등록 → 재생, 댓글, 후원(테스트 결제) → 1~5단계 기능이 도메인에서 동작하는지 확인.
5. 휴대폰에서 `https://yewonjune.com/go-live.html` → 로그인 → 방송 시작 (정식 인증서라 경고 없이 카메라 허용 창이 뜸).

**데스크톱 앱 (Windows PC 와 Mac 각각)**
1. `https://yewonjune.com/download.html` → 내 기기에 맞는 추천 버튼, 표에 OS별 파일·아키텍처, 설치 방법이 보이는지 확인.
2. 파일 링크로 내려받기 → 위 [데스크톱 클라이언트](#데스크톱-클라이언트-6단계) 표대로 설치·실행.
3. 앱 오른쪽 위에 `yewonjune.com` 이 보이고 **라이브·다시보기·지난 방송** 목록이 웹과 같은지 확인.
4. 다시보기 카드 → 영상 재생, 진행 막대로 구간 이동.
5. 휴대폰으로 방송을 켠 상태에서 앱의 라이브 카드 → 영상·소리 재생, 시청자 수 증가, 휴대폰에서 종료하면 앱에 "방송이 종료되었습니다".

**결과 기록**

| 확인 | OS / 아키텍처 | 네트워크 | 결과 | 날짜 |
| --- | --- | --- | --- | --- |
| 자동 테스트 `./gradlew test` (23개, 데스크톱 출처 CORS·WebSocket·프록시 HTTPS 인식 포함) | Windows 11 / x64 (JDK 25) | 로컬 | ✅ 통과 | 2026-11-21 |
| Windows 패키지 빌드 (`npm run dist:win`): `Chzzk-Setup-win-x64.exe`·`Chzzk-Setup-win-arm64.exe`·`Chzzk-win-x64.zip` | Windows 11 / x64 | 로컬 | ✅ 생성됨 | 2026-11-21 |
| 빌드된 Windows 앱(`win-unpacked/Chzzk.exe`) → 로컬 서버: 목록 조회, 다시보기 재생(5초 영상 끝까지), 라이브 WebRTC 시청(시청자 1명) | Windows 11 / x64 | 로컬 (`http://localhost:8080`) | ✅ 동작 | 2026-11-21 |
| 도메인 HTTP→HTTPS, 인증서, `/actuator/health` | ___ | 외부 (___) | ⬜ 배포 후 기입 | |
| 다운로드 페이지 → Windows 설치 파일 받기·설치·실행, 목록·다시보기·라이브 | Windows ___ / ___ | 외부 | ⬜ 배포 후 기입 | |
| 다운로드 페이지 → macOS dmg 받기·실행(Gatekeeper 허용), 목록·다시보기·라이브 | macOS ___ / ___ | 외부 | ⬜ 배포 후 기입 | |
| GitHub Actions 빌드 → Releases 7개 파일 | windows-latest, macos-latest | — | ⬜ 태그 push 후 기입 | |

> 위 ✅ 세 줄은 실제로 실행한 결과입니다. 도메인·VM·macOS 는 아직 준비되지 않아 ⬜ 로 남겨 두었고, 진행한 뒤 OS 버전·아키텍처·결과를 채웁니다. 앱이 서버와 실제로 통신하는지는 사람 없이도 확인할 수 있습니다: `CHZZK_SMOKE=1 CHZZK_SMOKE_OPEN=replay/1 Chzzk.exe` → 표준 출력에 목록 개수와 재생 상태(`readyState`, `currentTime`)가 찍히고 종료됩니다.

### E. 로컬 오토스케일 (5단계) — 부하에 따라 늘고 줄기, 그동안 상태 유지

준비: [여러 인스턴스로 실행하기](#여러-인스턴스로-실행하기-5단계)의 1)~4) 를 마치고 서버 Pod 2개가 Ready 인 상태.

1. 터미널 1에서 Pod 수를 지켜봅니다.
   ```bash
   kubectl -n chzzk get hpa,pods -w
   ```
2. 터미널 2에서 k6 로 부하를 겁니다(설치 없이 Docker 이미지로, 약 10분).
   ```bash
   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6:0.54.0 run - < load/autoscale.js
   ```
   - `load` 시나리오: 비로그인 시청자 최대 60명이 방송·다시보기 목록·상세·댓글을 계속 조회 (1분 램프업 → 4분 유지 → 30초 감소)
   - `watch` 시나리오: 시작할 때 가입한 사용자 한 명이 **같은 세션 쿠키로** 10분 동안 1초마다 로그인 상태·방송 목록·댓글을 확인. 부하가 끝난 뒤 4분 더 지켜보므로 **스케일 인 중**도 포함됨
3. 확인할 것
   - ✅ 부하가 걸리면 HPA `TARGETS` 가 50% 를 넘고 `REPLICAS` 가 2 → 5 로 늘어남
   - ✅ 부하가 끝나면 60초 지켜본 뒤 5 → 2 로 줄어듦
   - ✅ k6 결과에서 `로그인 상태 유지`·`방송 목록 유지`·`댓글 유지` 가 모두 ✓ (`checks{scenario:watch}` 100%)
   - (선택) 그동안 브라우저에서 로그인한 채 새로고침해도 로그아웃되지 않고, 방송 중인 방송을 보던 시청 화면이 끊기지 않는지

**실제로 확인한 결과** (Docker Desktop Kubernetes v1.36, 노드 1개 16 CPU / 2026-11-16)

15초마다 `kubectl -n chzzk get hpa` 와 서버 Pod 목록을 찍은 기록에서 옮겼습니다(Pod 이름 앞부분 `chzzk-5c7dfc895c-` 생략).

```text
시각      HPA TARGETS     REPLICAS  서버 Pod
22:42:44  cpu: 12%/50%    2         bth5p tf7j9                            ← k6 램프업 중
22:43:00  cpu: 200%/50%   2 → 5     bth5p tf7j9 + bkqvq hpxtr j88nr(0/1 기동 중)   ← 스케일 아웃
22:44:17  cpu: 199%/50%   5         5개 모두 1/1 Running
22:45:03  cpu: 121%/50%   5         5개 (최대 부하 유지)
22:47:53  cpu: 148%/50%   5         5개 (부하 종료 직전)
22:48:08  cpu: 7%/50%     5         bth5p tf7j9   (bkqvq hpxtr j88nr 종료: 60초 안정화 뒤 스케일 인 5 → 2)
22:49:09  cpu: 5%/50%     2         bth5p tf7j9
22:53:30  cpu: 1%/50%     2         bth5p tf7j9   (watch 끝까지 2개 유지)
```

```text
$ kubectl -n chzzk describe hpa chzzk   (Events 일부)
  Normal  SuccessfulRescale  New size: 5; reason: cpu resource utilization (percentage of request) above target
  Normal  SuccessfulRescale  New size: 2; reason: All metrics below target
```

k6 결과 요약:

```text
     ✓ 로그인 상태 유지
     ✓ 방송 목록 유지
     ✓ 댓글 유지
     checks.........................: 100.00% 1425 out of 1425
     ✓ { scenario:watch }...........: 100.00% 1425 out of 1425
     http_req_duration..............: avg=101.38ms med=95.72ms p(90)=204.84ms p(95)=300.22ms max=2.15s
     http_req_failed................: 0.02%   27 out of 129190
     ✓ { scenario:load }............: 0.02%   27 out of 127756
     http_reqs......................: 129190  214.970863/s
load  ✓ [ 100% ] 00/60 VUs  5m30s
watch ✓ [ 100% ] 1 VUs      10m0s
```

- 스케일 아웃(2 → 5)과 스케일 인(5 → 2)이 모두 일어나는 동안 **같은 로그인 세션으로 1425번 확인해 한 번도 로그아웃되지 않았고**, 방송 목록·댓글 개수도 줄지 않았습니다. 요청은 매번 Service 가 고른 Pod 로 갔습니다.
- 부하 요청 27건(0.02%)은 k6 컨테이너 → `host.docker.internal:8080` 연결을 새로 맺다가 `dial: i/o timeout` 으로 실패한 것입니다(앱 오류 응답은 0건). 노트북 한 대에서 k6·Pod 5개·Docker Desktop 포트 전달이 함께 돌 때 생긴 것으로 보입니다.
- 기록 시작 직후(22:41~22:42)에도 2 → 5 → 2 가 한 번 있었는데, 직전에 스크립트를 확인하려고 짧게 돌린 시험 실행(5명, 1분)의 부하가 HPA 에 늦게 반영된 것입니다. 위 표는 본 실행부터입니다.

### D. 도네이션 (4단계) — 성공 · 실패 · 취소 · 승인 통지 중복

준비: 서버를 실행하고, 브라우저 1에서 👑 **스트리머**(예: `owner1`/`주인장`), 브라우저 2(또는 `http://127.0.0.1:8080`)에서 💰 **후원자**(예: `fan1`/`팬일호`)로 가입합니다. 👑가 다시보기를 하나 등록하거나 방송을 시작합니다.
아래는 키 없이 실행한 **내장 테스트 결제창** 기준이며, 토스페이먼츠 테스트 모드에서 다른 점은 각 항목의 *(토스)* 에 적었습니다.

**① 결제 성공**
1. 💰 👑의 다시보기(또는 방송 시청 화면) → **후원** 영역의 **♥ 후원하기** → 새 창에 후원 화면.
2. "받는 스트리머: 주인장" 확인 → **5,000원** 선택 → 메시지 입력 → **5,000원 후원하기**.
3. 테스트 결제창에서 **결제하기 (승인 성공)** → 결과 화면에 잠시 "서버에서 확인하는 중" 뒤 **✓ 후원이 완료되었습니다**, 상태 `PAID`.
   *(토스)* 토스 결제창에서 카드사를 고르고 테스트 결제를 진행합니다(실제 출금 없음).
4. 다시보기 화면으로 돌아가면 **후원 합계 5,000원 · 1건**과 후원자 닉네임·메시지가 보입니다.
5. 👑 자기 방송 화면에는 **후원하기 버튼이 없고**, Console에서 `await chi.api('/api/donations', {method:'POST', json:{targetType:'replay', targetId:1, amount:5000}})` → **400 자기 방송에는 후원할 수 없습니다.**
6. (방송 중 실시간) 👑가 방송 중이면, 💰의 후원이 확정되는 순간 👑 송출 화면과 다른 시청 화면에 **"팬일호 님이 5,000원 후원!"** 알림이 뜨고 합계가 바로 바뀝니다.

**② 승인 통지 중복 — 한 번만 적립되는지**
1. ①의 결과 화면에서 **F5(새로고침)를 여러 번** → 매번 같은 "후원이 완료되었습니다"가 나오고, 다시보기 화면의 합계는 **5,000원 · 1건 그대로**입니다. (새로고침할 때마다 같은 `paymentKey/orderId/amount`로 승인 요청이 다시 갑니다.)
2. 결과 화면 주소창의 `paymentKey=` 값을 복사해 Console에서 **PG 웹훅을 흉내 내 여러 번** 보냅니다(로그인·CSRF 없이 받는 경로).
   ```js
   const key = new URLSearchParams(location.search).get('paymentKey');
   for (let i = 0; i < 3; i++) {
     await fetch('/api/payments/webhook', { method: 'POST', headers: { 'Content-Type': 'application/json' },
       body: JSON.stringify({ eventType: 'PAYMENT_STATUS_CHANGED', data: { paymentKey: key, status: 'DONE', totalAmount: 99999999 } }) });
   }
   (await chi.api('/api/replays/1/donations')).total   // → 5000 그대로 (1 은 다시보기 번호)
   ```
   본문의 `totalAmount: 99999999`는 무시됩니다. 서버는 `paymentKey`로 PG에 다시 조회한 결과만 쓰며, 이미 PAID인 주문은 그대로 둡니다.
3. 같은 승인 요청을 Console에서 **동시에** 보내도 결과는 같습니다.
   ```js
   const q = new URLSearchParams(location.search);
   const body = { paymentKey: q.get('paymentKey'), orderId: q.get('orderId'), amount: Number(q.get('amount')) };
   (await Promise.all([1,2,3,4,5].map(() => chi.api('/api/donations/confirm', { method: 'POST', json: body })))).map(d => d.status)
   // → ['PAID','PAID','PAID','PAID','PAID'], 합계는 여전히 1건
   ```
   *(토스)* 실제 토스 웹훅은 개발자센터에 웹훅 주소를 등록하면 결제 완료 시 자동으로 옵니다. 위 Console 방법은 토스 모드에서도 똑같이 동작합니다(서버가 토스 결제 조회 API로 다시 확인).

**③ 결제 실패**
1. 💰 다시 **♥ 후원하기** → 테스트 결제창에서 **결제창에서 실패** → 결과 화면 **✕ 결제에 실패했습니다**, 사유 `카드 정보를 다시 확인해주세요. (REJECT_CARD_COMPANY)`, 상태 `FAILED`.
2. 한 번 더 → **카드사 승인 거절 (승인 단계 실패)** → 결제창 인증은 통과했지만 **서버가 PG에 승인을 요청하는 단계에서 거절** → `FAILED (REJECT_CARD_PAYMENT)`.
3. **금액 변조**: 한 번 더 5,000원 후원을 눌러 테스트 결제창이 열리면, **결제창 주소의 `amount=5000`을 `amount=1000`으로 고쳐 Enter** → **결제하기** → 결제창은 1,000원으로 인증했지만 서버의 주문 금액은 5,000원이므로 **"결제 금액이 주문 금액과 달라 후원을 확정하지 않았습니다"**, 상태 `FAILED (AMOUNT_MISMATCH)`. 그 뒤 같은 주문을 원래 금액으로 다시 보내도 409로 확정되지 않습니다.
   토스 모드에서도 쓸 수 있는 Console 방법(주문은 5,000원인데 승인 요청은 100원):
   ```js
   const o = await chi.api('/api/donations', { method: 'POST', json: { targetType: 'replay', targetId: 1, amount: 5000 } });
   await chi.api('/api/donations/confirm', { method: 'POST', json: { paymentKey: 'tampered', orderId: o.orderId, amount: 100 } })
   // → 400 결제 금액이 주문 금액과 달라 후원을 확정하지 않았습니다.  (PG 승인 API 는 호출되지 않음)
   ```
4. 세 경우 모두 합계·내역에 **들어가지 않습니다**.
   *(토스)* 결제창에서 카드사 인증을 중단하거나 실패하면 `failUrl`로 돌아와 같은 실패 화면이 나옵니다.

**④ 결제 취소**
1. 💰 **♥ 후원하기** → 테스트 결제창에서 **결제 취소 (창 닫기)** → 결과 화면 **– 결제를 취소했습니다**, `PAY_PROCESS_CANCELED`, 상태 `CANCELED`. 합계 변화 없음.
   *(토스)* 토스 결제창의 닫기(X)를 누르면 같은 결과입니다.
2. 결제창을 연 채 30분이 지나면 그 주문은 `EXPIRED`로 정리됩니다.

**⑤ 남의 주문**: 👑(또는 다른 사용자)가 💰의 결과 화면 주소를 그대로 열면 **403 본인의 후원 주문만 승인할 수 있습니다.** 로그아웃 상태면 로그인 안내가 나옵니다.

자동 검증: `./gradlew test`의 `DonationFlowTest`(6)가 위 ①~⑤(성공, 새로고침·웹훅 재전송·동시 요청 10건에도 1회 적립, 금액 변조 2종, 결제창 실패·승인 거절·취소, 확정 후 취소 요청 무시, 웹훅 PG 재조회·환불 반영, 남의 주문 403, 방송 중 실시간 알림)를 내장 결제창으로 실제 서버에 요청해 확인합니다. 토스페이먼츠 테스트 서버와의 실제 연동은 키가 필요해 자동 테스트에 포함하지 않았습니다.

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
9. (끊김 처리) 새 방송을 시작한 뒤 휴대폰에서 **탭을 닫거나 새로고침** → **재연결 유예(기본 20초)** 가 지나면 시청 화면이 "방송이 종료되었습니다"로 바뀌는지 확인합니다(정리 주기 때문에 최대 30초 정도).
10. (시청자 상한) `MAX_VIEWERS_PER_BROADCAST=1`로 실행 후 시청 화면을 두 개 열면 두 번째에 "시청자가 가득 찼습니다"가 뜹니다.

> 휴대폰 없이 PC 한 대로 흐름만 확인하려면: PC에 웹캠이 있으면 `http://localhost:8080/go-live.html`(localhost는 HTTPS 없이도 카메라 허용)에서 로그인 후 송출하고, 다른 브라우저 창에서 `http://127.0.0.1:8080`으로 시청하면 됩니다.

### B. 다시보기 (1단계 기능 유지 확인)

테스트용 5초 영상이 `samples/sample.mp4`에 들어 있습니다.

1. 로그인 → 메인 → **＋ 다시보기 등록** → 제목 입력 → *파일 업로드* 탭에서 `samples/sample.mp4` 선택 → **등록하기**.
2. 상세 화면(`/replay.html`)으로 이동해 재생·구간 이동이 되는지 확인합니다. (재생은 로그아웃 상태에서도 됩니다.)
3. 메인 아래쪽 **다시보기** 영역에 카드(썸네일, `0:05`, 조회수)가 보이는지 확인합니다.
4. 서버를 종료하고 다시 실행 → 계정·다시보기·종료된 방송 기록·댓글이 그대로 남아 있는지 확인합니다. (재시작 전에 방송 중이던 방송은 송출 화면이 다시 붙지 않으면 재연결 유예 뒤 **종료**로 바뀌고, 기본 프로필의 로그인 세션은 재시작 시 초기화되어 다시 로그인해야 합니다.)

자동 검증: `./gradlew test` — 실제 서버를 임시 폴더로 띄워 확인합니다(총 23개, Docker 불필요).
- `DesktopAccessTest`(2): 데스크톱 출처(`app://chzzk`)의 조회 CORS·시청 WebSocket 허용, 다른 출처 WebSocket 거절·상태 변경 사전 요청 거절, 다운로드 목록, 프록시 헤더(`X-Forwarded-Proto: https`) 뒤 같은 출처 POST 허용
- `DonationFlowTest`(6): 주문 검증·권한, 성공 후 중복 승인 통지 1회 적립, 동시 승인 요청, 금액 변조, 실패·취소, 웹훅 PG 재조회·환불
- `PermissionTest`(6): 가입·로그인·로그아웃, CSRF, 다시보기·방송·댓글 권한 매트릭스, 댓글 페이지네이션
- `BroadcastFlowTest`(6): 소유자만 송출, 시그널링, 방송 중 댓글·제목 실시간 전달, 시청자 상한, 끊김 뒤 재연결 유예·종료, viewerId 로 이어 붙기, 동시 종료 한 번, 서버 종료 시 4001
- `HealthProbeTest`(1): liveness·readiness 가 로그인 없이 200
- `ReplayFlowTest`(2): 등록 → 목록 → Range 재생 → 재시작 후 유지, 입력 검증

`./gradlew clusterTest` — Testcontainers 로 PostgreSQL·Redis 를 띄우고 `cluster` 프로필 서버 2대(일부는 별도 JVM 으로 강제 종료)로 확인합니다(총 17개, **Docker 필요**). 내용은 [5단계 설계 문서 3-5](docs/stage5-design.md#3-5-시험-방법).

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
| `GET` | `/api/downloads` | 데스크톱 앱 설치 파일 목록 `{releasesPage, assets:[{os, arch, kind, file, url, note}]}` (6단계, 다운로드 페이지가 사용) |
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

**도네이션**

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/payments/config` | 결제 방식(`toss`/`fake`)·공개 클라이언트 키·금액 규칙 |
| `POST` | `/api/donations` 🔒 | 주문 생성 `{"targetType":"broadcast"\|"replay","targetId","amount","message"}` → 201 `{orderId, orderName, amount, provider, clientKey, recipient}` (자기 방송 400) |
| `POST` | `/api/donations/confirm` 🔒 | 승인 확인 `{"paymentKey","orderId","amount"}` → 서버가 대조·PG 승인 후 `{status:"PAID",…}`. 같은 요청 반복 시 같은 결과. 금액 불일치·거절 400, 남의 주문 403, 이미 실패한 주문 409 |
| `POST` | `/api/donations/{orderId}/abort` 🔒 | 결제창 실패·취소 `{"code","message"}` → `CANCELED`/`FAILED` (확정된 주문은 바뀌지 않음) |
| `GET` | `/api/donations/{orderId}` 🔒 | 내 주문 상태 (본인만) |
| `GET` | `/api/broadcasts/{id}/donations`, `/api/replays/{id}/donations` | 확정된 후원 합계·건수·최근 30건 `{total, count, donations}` |
| `POST` | `/api/payments/webhook` | PG 웹훅 (`PAYMENT_STATUS_CHANGED`). 로그인·CSRF 없음, `paymentKey`로 PG에 재조회해 반영 |
| `POST` | `/api/fake-pg/authorize` 🔒 | 내장 테스트 결제창 전용 (토스 모드에서는 404) |
| `WS` | `/ws/broadcasts/{id}` | 방송 중 후원 확정 시 `{"type":"donation", donation, total, count}` 알림 |

상태를 바꾸는 요청(POST/PATCH/DELETE)에는 `/api/auth/me`로 받은 CSRF 토큰을 `X-CSRF-TOKEN` 헤더로 보내야 합니다. 오류는 모두 `{"error": "메시지"}` 형태입니다.

## 설계 문서

- [docs/stage6-design.md](docs/stage6-design.md) — 6단계 배포 구조(OCI·Caddy·DNS·TLS), 데스크톱 앱 구조·보안, 빌드·배포 파이프라인
- [docs/stage5-design.md](docs/stage5-design.md) — 5단계 아키텍처, 상태별 저장 위치, 스케일·장애 시나리오, 배포 구성
- [docs/stage4-design.md](docs/stage4-design.md) — 4단계 아키텍처, ERD, 기술적 고민 및 개선 (결제 확정·중복 방지)
- [docs/stage3-design.md](docs/stage3-design.md) — 3단계 설계 (인증·인가·댓글)
- [docs/stage2-design.md](docs/stage2-design.md) — 2단계 설계 (라이브 송출)
- [docs/stage1-design.md](docs/stage1-design.md) — 1단계 설계 (다시보기)
- [GLOSSARY.md](GLOSSARY.md) — 용어집
- [docs/adr/](docs/adr/) — 주요 설계 결정 기록 (WebRTC P2P, 방송/다시보기 분리, 세션 인증, 송출 키 → 소유자 검사, 서버 확정 도네이션, 상태 외부화·Redis 중계)

## 폴더 구조

```
src/main/java/com/chzzk/
  ChzzkApplication.java     진입점
  user/                         사용자: 엔티티·가입·로그인(AuthController)·세션 사용자(LoginUser)
  broadcast/                    방송: 엔티티·서비스(소유자 검사, 재연결 유예 정리)·컨트롤러, WebSocket 시그널링,
                                방송 실시간 상태(BroadcastLiveState: 메모리 / Redis 구현)
  replay/                       다시보기: 엔티티·서비스(검증, 파일 저장, 소유자 검사)·컨트롤러
  comment/                      댓글: 엔티티·서비스(작성자/스트리머 검사, 실시간 알림)·컨트롤러
  donation/                     도네이션: 주문 엔티티·서비스(승인 대조, 행 잠금 멱등 확정, 웹훅)·컨트롤러
  payment/                      결제 서비스 연동: PaymentGateway, 토스페이먼츠 테스트 모드, 내장 테스트 PG(DB 저장)
  config/                       Spring Security, /media/** 영상 제공, WebSocket, 이전 단계 DB 호환
  common/                       공통 예외·오류 응답, 소유자 검사(Ownership), 커밋 후 실행(AfterCommit)
  desktop/                      데스크톱 앱 출처 설정·조회 전용 CORS, 다운로드 목록 API
src/main/resources/
  application.properties        설정 (환경변수 기본값)
  application-cluster.properties  여러 인스턴스용: PostgreSQL · Redis 세션 · 공유 업로드 폴더
  static/                       메인(index) · 로그인(auth) · 방송 시작(go-live) · 방송 시청(live) · 다시보기(replay, upload) · 댓글 위젯(comments.js)
                                데스크톱 앱 다운로드(download)
                                후원(donate) · 후원 결과(donate-result) · 내장 테스트 결제창(fake-pg) · 후원 위젯(donations.js)
src/test/java/                  통합 테스트 (ApiClient: 사용자별 세션·CSRF 를 가진 테스트용 클라이언트)
                                Cluster*Test: Testcontainers 로 PostgreSQL·Redis + 서버 2대 (./gradlew clusterTest)
Dockerfile, .dockerignore       컨테이너 이미지
k8s/                            Kubernetes 배포 (namespace · PostgreSQL · Redis · 업로드 PVC · 서버 · Service · HPA)
load/autoscale.js               k6 부하 스크립트
deploy/oci/                     6단계 OCI VM 배포: compose.yaml(Caddy·앱·PostgreSQL·Redis·coturn) · Caddyfile · .env.example · setup-vm.sh · compose.micro.yaml
desktop/                        6단계 데스크톱 앱 (Electron): main.js · preload.js · renderer/(목록·재생 화면) · build/adhoc-sign.cjs
.github/workflows/desktop.yml   Windows·macOS 패키지 빌드 → GitHub Releases
samples/                        확인용 샘플 영상
data/, certs/                   (git 제외) DB·업로드 파일, HTTPS 인증서
```
