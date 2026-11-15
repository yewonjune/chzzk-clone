# 치치직 4단계 설계 문서

> 범위: 로그인 사용자가 방송자(스트리머)와 금액을 골라 **후원**하는 도네이션. 결제 서비스(PG)의 **테스트 모드**로 결제 요청 → 승인 확인 흐름을 구현하고, 서버가 승인·금액·받는 사람을 확인한 뒤에만 확정한다. 같은 승인 통지가 반복되어도 한 번만 적립하고, 방송별 후원 내역과 합계를 보여준다.
> 1~3단계 기능(다시보기, 휴대폰 라이브, 인증·인가, 댓글)은 유지한다.
> 용어는 [GLOSSARY.md](../GLOSSARY.md)의 "후원" 절, 주요 결정은 [ADR-0005](adr/0005-donation-confirmed-by-server.md).

---

## 1. 시스템 아키텍처

### 1-1. 전체 구조

3단계 구조에 **donation**(후원 주문)과 **payment**(PG 연동) 모듈이 추가되었습니다. 도네이션 서비스는 `PaymentGateway` 인터페이스만 알고, 실제 구현은 설정으로 고릅니다.

```
 후원자 브라우저                                     PG (결제 서비스)
 live · replay · go-live ─♥ 후원하기─▶ donate.html    ┌──────────────────────────────┐
                                        │ ① 주문 생성  │ 토스페이먼츠 테스트 서버        │
                                        │ ② 결제창 ───▶│  (js.tosspayments.com SDK)     │
                                        │             │ 또는 내장 테스트 결제창          │
   donate-result.html ◀── successUrl / failUrl ───────│  (fake-pg.html, 키 없을 때)     │
        │ ③ paymentKey·orderId·amount 를 서버로        └──────────┬───────────┬───────┘
        ▼                                                         │ ④ 승인 API │ ⑥ 웹훅
┌──────────────────────────── Spring Boot ─────────────────────────┼───────────┼──────┐
│ Security: /api/donations/** 로그인 필수 · /api/payments/webhook 공개(CSRF 제외)        │
│                                                                  │           │      │
│ DonationController ──▶ DonationService                            │           │      │
│   create  : 대상 소유자 = 받는 사람, 금액 검증 → PENDING 저장        │           │      │
│   confirm : 행 잠금 → 주문 대조 → gateway.confirm() ──────────────┘           │      │
│             → PG 응답 대조 → PAID (⑤ 방송 중이면 커밋 후 WebSocket 알림)        │      │
│   abort   : 실패·취소 코드 → FAILED / CANCELED                                │      │
│   webhook : gateway.get(paymentKey) 로 PG에 재조회 → 같은 규칙으로 반영 ◀──────┘      │
│   summary : PAID 행의 SUM·COUNT + 최근 30건                                          │
│                                                                                     │
│ PaymentGateway ─┬─ TossPaymentsGateway (test_ 키만, Basic 인증, java.net.http)        │
│                 └─ FakePaymentGateway  (메모리, 인증→승인 2단계 흉내)                   │
│ LiveRooms.sendToAll(방송, {type:"donation", donation, total, count})                 │
└──────────────┬──────────────────────────────────────────────────────────────────────┘
               ▼
        H2: users · replays · live_broadcasts · comments · donations
```

### 1-2. 결제 흐름 (성공)

```
donate.html            서버                                PG
   │ POST /api/donations {targetType, targetId, amount, message}
   │──────────────────▶ 로그인? 대상 있음? 자기 방송 아님? 금액 규칙?
   │                    INSERT donations (PENDING, order_id=chi_<128bit>, amount, recipient)
   │◀────────────────── {orderId, orderName, amount, provider, clientKey}
   │ requestPayment({orderId, amount, successUrl, failUrl}) ──────────▶ 결제창·카드 인증
   │◀──────────────────────────── successUrl?paymentKey&orderId&amount ─┘
donate-result.html
   │ POST /api/donations/confirm {paymentKey, orderId, amount}
   │──────────────────▶ SELECT … FOR UPDATE (order_id)
   │                    · 주문자 == 로그인 사용자?         아니면 403
   │                    · 이미 PAID & 같은 paymentKey?    → 그대로 반환 (중복 통지)
   │                    · PENDING 인가?                   아니면 409
   │                    · amount == 주문 금액?             아니면 FAILED(AMOUNT_MISMATCH)
   │                    · 받는 사람 == 현재 대상 소유자?     아니면 FAILED(RECIPIENT_MISMATCH)
   │                    POST /v1/payments/confirm ─────────────────────▶ 승인
   │                    ◀────────── {status:DONE, orderId, totalAmount, paymentKey}
   │                    · DONE · 주문번호 · 금액 · 결제키 재대조   아니면 FAILED(VERIFY_FAILED)
   │                    UPDATE status=PAID, payment_key, paid_at  → COMMIT → WebSocket 알림
   │◀────────────────── {status:"PAID", amount, recipient, …}
```

### 1-3. 실패·취소·결과 불명

| 상황 | 어디서 알게 되나 | 결과 |
| --- | --- | --- |
| 사용자가 결제창을 닫음 | `failUrl?code=PAY_PROCESS_CANCELED` (orderId 없음 → 우리가 넣어 둔 `order` 파라미터 사용) | CANCELED |
| 결제창에서 카드 인증 실패 | `failUrl?code=…` | FAILED |
| 승인 단계에서 카드사 거절 | 승인 API 4xx | FAILED(PG 코드) |
| 화면이 금액을 바꿈 | 주문 금액과 대조 | FAILED(AMOUNT_MISMATCH), PG 승인 API 호출 안 함 |
| 결제창 인증 금액 자체를 바꿈 | PG가 인증 금액 ≠ 승인 요청 금액으로 거절 | FAILED |
| 승인 API 시간 초과·5xx | 결과를 알 수 없음 | **PENDING 유지** → 502 안내, 웹훅·새로고침으로 확정 |
| 결제창을 열고 30분 방치 | 스케줄러(1분 간격) | EXPIRED |
| 확정 뒤 PG에서 결제 취소 | 웹훅 → 재조회 결과 CANCELED | REFUNDED (합계에서 빠짐) |

`abort`는 PENDING일 때만 상태를 바꿉니다. 이미 PAID인 주문에 화면이 "실패"를 보내도 그대로입니다.

---

## 2. ERD

### 2-1. 테이블

```
┌──────────────────────── users ────────────────────────┐
│ id (PK) · username · nickname · password_hash …        │
└──────┬──────────────────────┬──────────────────┬───────┘
       │ 1                    │ 1                │ 1   (3단계와 동일한 owner / author 관계 생략)
       │ donor_id             │ recipient_id     │
       ▼ N                    ▼ N                │
┌─────────────────────────── donations (후원 주문) ───────────────────────────────┐
│ id (PK)          BIGINT IDENTITY                                                │
│ order_id         VARCHAR(64)  NOT NULL UNIQUE   PG 주문번호 (chi_ + 32 hex)       │
│ broadcast_id     BIGINT  FK → live_broadcasts  ON DELETE SET NULL ┐ 둘 중 하나   │
│ replay_id        BIGINT  FK → replays          ON DELETE SET NULL ┘ (삭제 후 NULL)│
│ target_title     VARCHAR(100) NOT NULL   주문 시점 제목 (대상 삭제 후에도 표시)     │
│ recipient_id     BIGINT  NOT NULL FK → users   받는 사람 (주문 시점 소유자, 고정)  │
│ donor_id         BIGINT  NOT NULL FK → users   후원자                            │
│ amount           BIGINT  NOT NULL               원 단위, 변경 불가                 │
│ message          VARCHAR(100)                                                   │
│ status           ENUM(PENDING, PAID, FAILED, CANCELED, EXPIRED, REFUNDED)        │
│ provider         VARCHAR(10)  NOT NULL          toss | fake                      │
│ payment_key      VARCHAR(200) UNIQUE            확정 시 저장                       │
│ failure_code     VARCHAR(60)  · failure_message VARCHAR(300)                     │
│ created_at  NOT NULL · paid_at · updated_at                                     │
│ INDEX idx_donations_broadcast       (broadcast_id, status, id)                   │
│ INDEX idx_donations_replay          (replay_id, status, id)                      │
│ INDEX idx_donations_status_created  (status, created_at)                         │
└──────────────────────────────────────────────────────────────────────────────────┘
       ▲ N                              ▲ N
       │ broadcast_id                   │ replay_id
  live_broadcasts (1)              replays (1)
```

### 2-2. 설계 포인트

- **주문을 먼저 만든다**: 금액과 받는 사람은 결제 전에 서버가 정해 `updatable = false`로 저장합니다. 승인 단계에서 브라우저가 보낸 값은 "대조 대상"일 뿐 저장되지 않습니다.
- **받는 사람 컬럼을 따로 둔다**: 대상의 `owner_id`를 그때그때 따라가지 않고 주문 시점에 복사합니다. 결제 도중 소유자가 바뀌는 경우를 감지할 수 있고(RECIPIENT_MISMATCH), 대상이 삭제돼도 누구에게 간 돈인지 남습니다.
- **댓글과 달리 ON DELETE SET NULL**: 댓글은 대상과 함께 지워도 되지만, 결제 기록은 방송·다시보기가 삭제되어도 남아야 합니다. 그래서 대상 FK만 NULL이 되고 `target_title`로 제목을 보존합니다.
- **잔액 컬럼 없음**: 합계는 `SUM(amount) WHERE status = 'PAID'`. 상태 전이 한 번 = 적립 한 번이 되어, "상태는 PAID인데 합계에 두 번 더해짐" 같은 불일치가 생길 수 없습니다.
- **두 개의 UNIQUE**: `order_id`(주문번호 중복 방지), `payment_key`(한 결제가 두 주문에 쓰이는 것 방지).

---

## 3. 기술적 고민 및 개선

### 3-1. "화면의 성공"을 믿지 않기

PG 결제창은 성공하면 `successUrl?paymentKey&orderId&amount`로 이동합니다. 이 주소는 브라우저 주소창이라 누구나 고칠 수 있습니다. 그래서 결과 화면은 **판단하지 않고 서버로 전달만** 하며, 서버는 세 번 확인합니다.

1. **주문과 대조**: 주문자, 금액, 받는 사람, 상태(PENDING)
2. **PG 승인 API**: 시크릿 키로 서버가 직접 호출 (브라우저는 시크릿 키를 모름)
3. **PG 응답과 대조**: `status == DONE`, 주문번호·금액·결제키 일치

화면의 ✓ 표시는 서버 응답의 `status`가 `PAID`일 때만 나옵니다.

### 3-2. 중복 적립 방지: 행 잠금 + 상태 전이 한 번

같은 승인 통지가 여러 번 오는 경우는 생각보다 흔합니다. 결과 화면 새로고침, 뒤로 가기 후 다시 앞으로, PG 웹훅 재전송(응답이 늦으면 PG가 다시 보냄), 그리고 승인 요청과 웹훅이 **동시에** 도착하는 경우가 있습니다.

검토한 방법:

| 방법 | 문제 |
| --- | --- |
| 처리 전에 `status == PAID`인지 확인만 | 두 요청이 동시에 확인하면 둘 다 PENDING을 보고 둘 다 적립 |
| `@Version` 낙관적 잠금 | 두 번째 요청이 예외로 실패 → 사용자에게 오류 화면, 재시도 로직 필요 |
| 웹훅 이벤트 ID 저장 테이블 | 결과 화면의 승인 요청에는 이벤트 ID가 없고, 토스 결제 웹훅에는 안정적인 이벤트 ID가 없음 |
| **`SELECT … FOR UPDATE` + PENDING → PAID 한 번** (채택) | 뒤에 온 요청은 잠금을 기다렸다가 이미 PAID인 것을 보고 같은 결과를 반환 |

`DonationRepository.lockByOrderId`가 `@Lock(PESSIMISTIC_WRITE)`로 행을 잠그고, `Donation.markPaid`는 PENDING(또는 늦은 승인의 EXPIRED)이 아니면 예외를 던져 두 번째 전이를 코드 수준에서도 막습니다. 마지막 안전망으로 `payment_key` UNIQUE가 있습니다. `DonationFlowTest`는 승인 요청 6개와 웹훅 4개를 동시에 보내 합계가 한 번만 늘어나는지 확인합니다.

잠금을 잡은 채 PG를 호출하므로 잠금 시간이 PG 응답 시간만큼 길어질 수 있습니다. 같은 주문에 대한 요청만 기다리고(행 잠금) 다른 주문에는 영향이 없으며, H2 잠금 대기 시간(`LOCK_TIMEOUT=35000`)을 PG 읽기 제한 시간(30초)보다 길게 맞췄습니다. 토스 승인 요청에는 `Idempotency-Key: confirm-<orderId>` 헤더도 붙여 PG 쪽에서도 같은 승인이 두 번 처리되지 않게 했습니다.

### 3-3. 실패로 처리하면 안 되는 실패

승인 API가 시간 초과되면 PG에서는 승인됐을 수도 있습니다. 이때 FAILED로 바꾸면 "돈은 나갔는데 후원은 실패"가 됩니다. 그래서 `PaymentException`을 **definite(PG가 명확히 거절, 4xx)** 와 **indefinite(네트워크 오류·5xx)** 로 나누고, indefinite면 PENDING을 유지한 채 "잠시 후 새로고침" 안내(502)만 보냅니다. 다시 요청하면 PG가 `ALREADY_PROCESSED_PAYMENT`를 돌려주고, 서버는 결제를 조회해 DONE이면 확정합니다. 30분이 지나 EXPIRED가 된 뒤에 승인 웹훅이 오면 그때도 PAID로 바꿉니다(늦게 도착한 진실을 버리지 않음).

### 3-4. 웹훅을 믿지 않는 방법: 재조회

웹훅 주소는 공개되어 있어 누구나 가짜 본문을 보낼 수 있습니다. 서명 검증 대신 **본문에서 `paymentKey`만 꺼내 PG 결제 조회 API로 다시 묻고**, 그 결과에 승인과 같은 대조 규칙을 적용합니다. 가짜 웹훅이 `status: DONE, totalAmount: 99999999`를 보내도 PG 조회 결과가 기준이 되므로 영향이 없습니다(테스트로 확인). 서명 검증 방식(토스 보안 키 HMAC)은 API 개별 연동 키 설정이 추가로 필요해 다음 과제로 남겼습니다.

### 3-5. 결제 연동을 인터페이스 뒤로: 테스트 가능성

`PaymentGateway`(confirm, get) 하나로 PG를 감싸 두 구현을 둡니다.

- `TossPaymentsGateway`: 토스페이먼츠 코어 API. `test_` 키가 아니면 서버가 기동하지 않아 실제 결제 가능성을 원천 차단합니다. 외부 라이브러리 없이 `java.net.http.HttpClient`와 Jackson으로 구현했고, 연결 5초·응답 30초 제한 시간을 둡니다.
- `FakePaymentGateway`: 메모리에서 "결제창 인증(IN_PROGRESS) → 승인(DONE)" 두 단계를 흉내 내며, 인증 금액과 승인 금액이 다르면 거절하고, 이미 승인된 결제는 `ALREADY_PROCESSED_PAYMENT`를 돌려주는 등 실제 PG의 규칙을 따릅니다. 키 없이 실행하거나 자동 테스트할 때 쓰입니다.

덕분에 도네이션 서비스의 모든 분기(성공, 거절, 금액 변조, 중복, 동시성, 환불)를 외부 네트워크 없이 실제 서버로 테스트합니다.

### 3-6. 쿼리 최적화

- 후원 내역: `(broadcast_id, status, id)` 복합 인덱스로 "이 방송의 PAID를 최신순 30건"을 인덱스 범위만 읽어 처리하고, `@EntityGraph(donor)`로 후원자 닉네임을 한 번에 가져와 N+1을 막습니다.
- 합계: 같은 인덱스로 `SUM/COUNT`를 한 쿼리에 계산합니다. 후원이 매우 많아지면 방송별 합계 캐시 컬럼을 같은 트랜잭션에서 증가시키는 방식으로 바꿀 수 있습니다(지금은 정합성을 우선해 SUM).
- 만료 정리: `(status, created_at)` 인덱스로 PENDING 중 오래된 것만 한 번의 UPDATE로 처리합니다.

### 3-7. 시청을 끊지 않는 후원 UX

결제창은 페이지 이동(redirect)을 하므로 방송 화면에서 바로 열면 WebRTC 연결이 끊깁니다. 그래서 **♥ 후원하기**는 새 창(탭)으로 후원 화면을 열고, 확정되면 서버가 WebSocket으로 방송 화면에 `donation` 알림을 보냅니다. 알림은 커밋 후(`AfterCommit`)에만 보내 "알림은 왔는데 DB에는 없는" 상황을 막습니다(3단계 댓글과 같은 원칙).

### 3-8. 보안 정리

- 결제 키: 시크릿 키는 환경변수로만, 저장소·응답·로그에 남기지 않음. 브라우저에는 공개용 클라이언트 키만 전달.
- 주문번호: `SecureRandom` 128비트 → 추측 불가. 그래도 승인·취소·조회는 **주문자 본인만**(403).
- 웹훅: 로그인·CSRF 예외는 이 경로 하나뿐이며, 그 대신 PG 재조회로 신뢰를 확보.
- 내장 결제창: `successUrl`/`failUrl`이 같은 사이트일 때만 이동(열린 리다이렉트 방지). 토스 모드에서는 가짜 PG API가 404.

### 3-9. 다음에 할 개선 과제

- 웹훅 서명(`tosspayments-webhook-signature`) 검증 추가 → 재조회 호출 줄이기
- 검증 실패(VERIFY_FAILED)로 판정했지만 PG에서는 승인된 결제를 자동 취소 API로 되돌리기
- 스트리머용 "받은 후원" 화면과 정산
- 방송별 합계 캐시 컬럼 (후원 건수가 매우 많아질 때)
