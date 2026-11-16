# 치치직 5단계 설계 문서

> 범위: 1~4단계 서비스를 **로컬 Kubernetes(Docker Desktop)** 에서 여러 인스턴스로 띄우고, CPU 사용률에 따라 **2~5개 사이로 자동 확장·축소**한다. 인스턴스가 늘고 줄어도 로그인 세션·업로드 영상·방송(라이브) 상태·댓글·후원이 유지되도록 상태를 모두 인스턴스 밖에 둔다.
> 1~4단계 기능은 유지하고, `gradlew bootRun` 단일 인스턴스 실행도 그대로 지원한다.
> 용어는 [GLOSSARY.md](../GLOSSARY.md) (특히 **종료**, **재연결 유예**), 주요 결정은 [ADR-0006](adr/0006-shared-state-for-multiple-instances.md).

---

## 1. 시스템 아키텍처

### 1-1. 전체 구조

```
 브라우저·휴대폰                         Docker Desktop Kubernetes (namespace chichijik)
 ─────────────                         ────────────────────────────────────────────────────────────────
 http(s)://localhost:8080 ──▶ Service chichijik (LoadBalancer) ──┬─▶ Pod chichijik #1 ┐
 /ws/broadcasts/{id}                                             ├─▶ Pod chichijik #2 │  HPA: 2~5개
                                                                 └─▶ … #5             ┘  CPU 50% 목표
                                                                        │   │   │
                         ┌──────────────────────────────────────────────┘   │   └──────────────────────┐
                         ▼                                                  ▼                          ▼
              PostgreSQL (StatefulSet+PVC)                    Redis (Deployment)              PVC chichijik-uploads
              사용자·방송·다시보기·댓글·후원 주문,           로그인 세션(Spring Session),       업로드 영상 (/data/uploads)
              가짜 PG 결제 기록                              방송 실시간 상태, pub/sub 중계,     모든 Pod 가 같이 마운트
                                                             인스턴스 하트비트
```

| 상태 | 1~4단계 | 5단계 cluster 프로필 |
| --- | --- | --- |
| 사용자·방송·다시보기·댓글·후원 | H2 파일 DB | **PostgreSQL** |
| 로그인 세션·CSRF 토큰 | Tomcat 메모리 세션 | **Redis** (Spring Session, `chichijik:session`) |
| 업로드 영상 | 로컬 폴더 | **공유 PVC** (`UPLOAD_DIR=/data/uploads`) |
| 가짜 PG 결제 | 메모리 Map | **PostgreSQL** `fake_pg_payments` |
| 송출자·시청자 연결 정보, 시청자 수 | 인스턴스 메모리 (`LiveRooms`) | **Redis** + 인스턴스별 WebSocket |
| 방송 중 알림(시그널·댓글·후원·제목·종료) | 같은 인스턴스의 소켓에만 | **Redis pub/sub** 으로 모든 인스턴스에 |

기본 프로필(`gradlew bootRun`)은 이전과 같습니다(H2·로컬 폴더·메모리). `SPRING_PROFILES_ACTIVE=cluster` 일 때만 PostgreSQL·Redis를 씁니다.

### 1-2. 방송 실시간 상태: 인터페이스 하나, 구현 둘

```
BroadcastService · CommentService · DonationService · SignalingHandler
        │  (방송 id 로 "모두에게 보내기", 송출자/시청자 붙이기·떼기, 시청자 수 …)
        ▼
BroadcastLiveState  ─┬─ InMemoryBroadcastLiveState   기본 프로필
                     └─ RedisBroadcastLiveState      cluster 프로필
SignalingMessages   ── 서버가 보내는 시그널링 메시지를 한 곳에서 만듦
```

서비스는 어느 구현인지 모릅니다. Redis 구현에서 각 인스턴스는 **자기에게 붙은 WebSocket만** 메모리에 들고, 나머지는 Redis에 둡니다.

| Redis 키 (`chichijik:live:` 아래) | 내용 |
| --- | --- |
| `{id}:host` (hash) | 송출자 연결: 소속 인스턴스, 연결 토큰, 끊긴 시각 |
| `{id}:viewers` (hash) | viewerId → 소속 인스턴스 (시청자 수 = 개수) |
| `{id}:viewers-disconnected` (hash) | 비정상으로 끊긴 시청자의 끊긴 시각 (재연결 유예용) |
| `{id}:ended` (TTL 1시간) | 종료 표시. 종료와 동시에 들어온 입장·송출 연결을 거절 |
| `{id}` (pub/sub 채널) | 방송 메시지 봉투 `Envelope(kind, viewerId, hostToken, message)` |
| `chichijik:instance:{instanceId}` (TTL 15초) | 인스턴스 하트비트 (5초마다 갱신) |

입장·퇴장·송출자 교체·종료는 **Lua 스크립트 한 번**으로 원자적으로 처리합니다. 예를 들어 입장은 "종료되지 않았고 상한 미만이면 추가하고, 새 시청자 수를 채널에 발행"이 한 번에 일어나므로, 서버 A·B로 동시에 12명이 들어와도 상한(3명)을 넘지 않습니다.

### 1-3. 서버가 달라도 방송이 이어지는 흐름

```
송출 화면 ─WS─▶ Pod A                     Pod B ◀─WS─ 시청 화면
   │ join(host)                               │ join(viewer)
   │   Redis: {id}:host = A                   │   Lua JOIN: 상한 검사 → {id}:viewers += v1 → 발행 viewer-joined
   │ ◀── 채널 {id}: TO_HOST viewer-joined ────┘
   │ signal(offer → v1) ──▶ 채널: TO_VIEWER v1 ──▶ B 가 자기 소켓 v1 에 전달
   ▼
(B 로 들어온 댓글·확정된 후원·제목 변경) ──▶ 채널: TO_ALL ──▶ A·B 가 각자 소켓에 전달
```

### 1-4. 스케일 인·장애 때: 재연결 유예

```
Pod A 종료 (HPA 스케일 인, SIGTERM)
  preStop 5초 → SignalingHandler(SmartLifecycle) 가 열린 WebSocket 을 4001("다른 서버로 재연결")로 닫음
  → 송출자 기록: 끊긴 시각 기록 (방송은 계속 방송 중)
  → 웹 서버 graceful shutdown: 진행 중인 요청(업로드 등)을 최대 90초 마무리, 120초면 강제 종료
송출 화면: 4001 → 즉시 재연결 → Service 가 Pod B 로 보냄 → 송출자 기록 갱신, 기존 P2P 는 그대로
시청 화면: 4001 → 즉시 재연결 → join(viewer, viewerId) → 같은 시청자로 이어 붙음 (시청자 수 그대로)

Pod A 강제 종료 (OOM, kill -9)
  하트비트 키가 15초 뒤 사라짐 → 다른 인스턴스의 정리 작업이 A 소속 시청자를 지우고(시청자 수·상한 회복)
  A 소속 송출자는 "끊김"으로 바꿈 → 재연결 유예 판단으로 넘어감

정리 작업 (모든 인스턴스가 10초마다, 조건부 UPDATE 라 한 번만 반영)
  ① 시작 후 30초 동안 송출 연결이 한 번도 없던 방송 → 종료
  ② 송출 연결이 끊긴 지 재연결 유예(20초)가 지난 방송 → 종료 (종료 알림은 정확히 한 번)
  ③ 끊긴 지 유예가 지난 시청자 자리 정리
```

1~4단계에서는 "송출 연결이 끊기면 즉시 종료", "서버가 뜨면 방송 중인 방송 전부 종료"였습니다. 여러 인스턴스에서는 이 두 규칙이 **스케일 아웃·인을 곧 방송 종료로** 만들기 때문에 바꿨습니다(용어집 **종료**·**재연결 유예**).

---

## 2. 배포 구성 (`k8s/`)

| 파일 | 내용 |
| --- | --- |
| `00-namespace.yaml` | 네임스페이스 `chichijik`, 머리말에 적용 순서 |
| `postgres.yaml` | PostgreSQL 16 StatefulSet + 2Gi PVC, 계정은 Secret `chichijik-env` 의 `DB_USERNAME`/`DB_PASSWORD` |
| `redis.yaml` | Redis 7 (디스크 저장 없음, `REDIS_PASSWORD` 가 있으면 비밀번호 사용) |
| `uploads-pvc.yaml` | 업로드 영상 5Gi PVC. ReadWriteOnce 지만 Docker Desktop 은 노드가 하나라 모든 Pod 가 함께 마운트 |
| `app.yaml` | 서버 Deployment(`cluster` 프로필, CPU 요청 250m / 상한 500m, 종료 유예 120초, preStop 5초, startup·liveness·readiness probe) + LoadBalancer Service(8080) |
| `hpa.yaml` | HPA: 최소 2, 최대 5, 평균 CPU 50%, 축소 안정화 60초 |

- 이미지: 멀티스테이지 `Dockerfile` (JDK 17 에서 `bootJar` → JRE 17, uid 10001). `imagePullPolicy: Never` 로 로컬 이미지 `chichijik:local` 만 사용.
- probe 는 별도 포트 8081(HTTP)의 Actuator health. readiness 는 DB·Redis 연결을 포함하므로 Redis 가 멈추면 그 Pod 로 요청이 가지 않음.
- `enableServiceLinks: false`: Kubernetes 가 Service 마다 넣는 `REDIS_PORT=tcp://…` 환경변수가 앱의 `REDIS_PORT` 를 덮어써 기동에 실패하는 것을 막음 (실제 배포에서 발견).
- 비밀 값: `.env.cluster.example` 만 저장소에 있고, 실제 `.env.cluster` 는 git 제외. `kubectl create secret generic chichijik-env --from-env-file=.env.cluster`.

---

## 3. 기술적 고민 및 개선

### 3-1. 스티키 라우팅 대신 pub/sub 중계
같은 방송의 연결을 프록시가 한 인스턴스로 모으면 코드 변경이 거의 없지만, 인스턴스 수가 바뀌면 해시 배정이 바뀌어 새 시청자가 송출자와 다른 인스턴스로 가고, 송출자의 인스턴스가 스케일 인되면 방 전체가 사라집니다. 연결은 옮길 수 없으니 연결은 각 인스턴스에 두고 **메시지를 Redis 채널로 중계**했습니다([ADR-0006](adr/0006-shared-state-for-multiple-instances.md)).

### 3-2. "내 인스턴스 기준" 판단 없애기
1~4단계 코드에는 "이 서버에 송출자 연결이 있는가"로 방송을 끝내는 곳이 두 군데 있었습니다(서버 기동 시 일괄 종료, 송출 연결 없는 방송 정리). 여러 인스턴스에서는 송출자가 없는 인스턴스가 남의 방송을 끝냅니다. 판단 기준을 Redis 의 송출자 기록으로 옮기고, 새 인스턴스 기동은 아무 방송도 건드리지 않게 했습니다.

### 3-3. 여러 인스턴스가 같은 정리 작업을 돌려도 한 번만
리더 선출·분산 락 없이, 종료를 `UPDATE … WHERE id = ? AND status = 'LIVE'` 조건부 갱신으로 바꾸고 **실제로 바꾼 쪽만** 종료 메시지를 발행합니다. 같은 방송을 8번 동시에 종료해도 종료 시각과 종료 알림이 하나입니다(이전에는 종료 시각이 둘로 갈리는 버그가 있었음).

### 3-4. 끊김과 퇴장 구분
시청 화면이 페이지를 떠나면(1000/1001) 바로 퇴장, 그 밖의 끊김(1006, 4001 …)은 재연결 유예 동안 자리를 유지합니다. 영상(P2P)이 이미 끊겨 처음부터 다시 들어오는 시청자는 `startOver` 로 이전 자리를 바로 비워, 시청자 수가 부풀지 않습니다.

### 3-5. 시험 방법
기존처럼 **실제 서버를 띄우고 HTTP·WebSocket 으로만** 확인합니다. `@Tag("cluster")` 테스트는 Testcontainers 로 PostgreSQL·Redis 를 띄우고 서버 A·B(필요하면 별도 JVM 으로 강제 종료할 C)를 기동합니다.

| 테스트 | 확인하는 것 |
| --- | --- |
| `ClusterSharingTest` | A 로그인 → B 로그인 상태·로그아웃 공유, A 업로드 → B Range 재생, A 주문 → B 승인 확인 확정·중복 통지 한 번, Redis 정지 시 readiness 503 |
| `ClusterBroadcastTest` | 송출자 A·시청자 B 시그널링, 시청자 수 합계, 동시 12명 입장에 상한 3명, B 의 댓글·후원·제목 → A 로 푸시, 종료 알림 한 번, 종료와 동시 입장 거절 |
| `ClusterFailoverTest` | A 종료 → 4001 → B 로 재연결해 같은 방송·같은 시청자, 송출자가 안 돌아오면 유예 뒤 종료 한 번, 강제 종료된 서버의 기록 정리, 새 서버 기동이 방송을 끝내지 않음 |

실제 오토스케일(Pod 수 변화)은 k6 부하(`load/autoscale.js`)와 `kubectl get hpa,pods` 출력으로 확인했습니다(README "직접 확인 절차 E").

### 3-6. 알려진 한계와 다음 과제
- Redis 는 디스크에 저장하지 않으므로 Redis Pod 가 재시작되면 모두 로그아웃되고, 방송 중인 방송은 송출자 기록이 없어 정리 작업이 종료합니다. 운영에서는 Redis 복제·영속화가 필요합니다.
- 업로드 볼륨은 단일 노드 전제(ReadWriteOnce)입니다. 여러 노드에서는 ReadWriteMany 스토리지나 오브젝트 스토리지가 필요합니다.
- 스케일 인 대기(90초) 안에 끝나지 않은 업로드는 실패하므로 다시 올려야 합니다.
- 송출 화면을 새로고침하면 아직 같은 방송을 이어받지 못하고, 유예 뒤 종료됩니다.
- 스케일 기준은 CPU 하나입니다. 동시 WebSocket 수·요청 수 같은 사용자 정의 지표는 Prometheus Adapter 가 필요해 다음 단계로 남깁니다.
- 영상은 여전히 P2P 라 방송당 시청자 상한이 있습니다(ADR-0001).
