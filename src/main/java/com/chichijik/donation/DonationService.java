package com.chichijik.donation;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chichijik.broadcast.Broadcast;
import com.chichijik.broadcast.BroadcastRepository;
import com.chichijik.broadcast.BroadcastLiveState;
import com.chichijik.common.AfterCommit;
import com.chichijik.common.ApiException;
import com.chichijik.common.Ownership;
import com.chichijik.donation.Donation.Status;
import com.chichijik.donation.DonationResponses.Checkout;
import com.chichijik.donation.DonationResponses.Detail;
import com.chichijik.donation.DonationResponses.Item;
import com.chichijik.donation.DonationResponses.Summary;
import com.chichijik.payment.PaymentException;
import com.chichijik.payment.PaymentGateway;
import com.chichijik.payment.PaymentInfo;
import com.chichijik.replay.Replay;
import com.chichijik.replay.ReplayRepository;
import com.chichijik.user.LoginUser;
import com.chichijik.user.OwnerResponse;
import com.chichijik.user.User;
import com.chichijik.user.UserRepository;

/**
 * 도네이션 흐름
 *  1) create  : 로그인 사용자가 대상(방송·다시보기)과 금액을 고르면 PENDING 주문을 만든다.
 *               받는 사람(대상 소유자)과 금액은 이때 서버에 고정된다.
 *  2) (브라우저) PG 결제창에서 결제 인증 → successUrl / failUrl 로 돌아옴
 *  3) confirm : successUrl 의 paymentKey·orderId·amount 를 받아
 *               저장된 금액·받는 사람과 대조 → PG 승인 API 호출 → PG 응답을 다시 대조 → PAID
 *     abort   : failUrl 로 돌아온 경우(사용자 취소·결제 실패) → CANCELED / FAILED
 *  4) webhook : PG의 상태 변경 통지. 본문은 믿지 않고 PG에 결제를 다시 조회해 반영한다.
 *
 * 중복 적립 방지: 주문 행을 잠그고(PESSIMISTIC_WRITE) PENDING → PAID 전이를 한 번만 허용한다.
 * 같은 승인 통지(새로고침, 웹훅 재전송, 동시 요청)가 반복되면 이미 PAID 인 주문을 그대로 돌려준다.
 * 합계는 PAID 행의 SUM 이라, 상태 전이가 한 번이면 적립도 한 번이다.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    public static final long MIN_AMOUNT = 1_000;
    public static final long MAX_AMOUNT = 1_000_000;
    public static final long AMOUNT_UNIT = 100;
    static final int MAX_MESSAGE = 100;
    static final int LIST_SIZE = 30;
    static final Duration PENDING_TTL = Duration.ofMinutes(30);
    /** 결제창에서 사용자가 닫거나 취소한 경우의 코드 */
    static final Set<String> CANCEL_CODES = Set.of("PAY_PROCESS_CANCELED", "USER_CANCEL");

    public enum Target { BROADCAST, REPLAY }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DonationRepository donations;
    private final BroadcastRepository broadcasts;
    private final ReplayRepository replays;
    private final UserRepository users;
    private final PaymentGateway gateway;
    private final BroadcastLiveState liveState;

    public DonationService(DonationRepository donations, BroadcastRepository broadcasts, ReplayRepository replays,
                           UserRepository users, PaymentGateway gateway, BroadcastLiveState liveState) {
        this.donations = donations;
        this.broadcasts = broadcasts;
        this.replays = replays;
        this.users = users;
        this.gateway = gateway;
        this.liveState = liveState;
    }

    // ---- 1) 주문 만들기 ----

    @Transactional
    public Checkout create(LoginUser me, Target target, Long targetId, Long amount, String rawMessage) {
        Ownership.requireLogin(me);
        if (target == null || targetId == null) throw ApiException.badRequest("후원할 방송을 선택해 주세요.");
        long value = validAmount(amount);
        String message = validMessage(rawMessage);

        Broadcast broadcast = null;
        Replay replay = null;
        User recipient;
        String title;
        if (target == Target.BROADCAST) {
            broadcast = broadcasts.findWithOwnerById(targetId).orElseThrow(() -> ApiException.notFound("방송을 찾을 수 없습니다."));
            recipient = broadcast.getOwner();
            title = broadcast.getTitle();
        } else {
            replay = replays.findWithOwnerById(targetId).orElseThrow(() -> ApiException.notFound("다시보기를 찾을 수 없습니다."));
            recipient = replay.getOwner();
            title = replay.getTitle();
        }
        if (recipient == null) throw ApiException.badRequest("스트리머 정보가 없는 이전 기록에는 후원할 수 없습니다.");
        if (Objects.equals(recipient.getId(), me.id())) throw ApiException.badRequest("자기 방송에는 후원할 수 없습니다.");
        User donor = users.findById(me.id()).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));

        Donation d = donations.save(new Donation(newOrderId(), broadcast, replay, truncate(title, 100), recipient, donor,
                value, message, gateway.provider()));
        String orderName = truncate("치치직 후원 - " + recipient.getNickname(), 100);
        return new Checkout(d.getOrderId(), orderName, value, gateway.provider(), gateway.clientKey(),
                OwnerResponse.of(recipient), d.getTargetTitle());
    }

    // ---- 3) 승인 확인 ----

    /**
     * successUrl 로 돌아온 값으로 결제를 승인하고 도네이션을 확정합니다.
     * 실패로 판정한 경우에도 그 상태(FAILED)는 저장해야 하므로 ApiException 으로는 롤백하지 않습니다.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Detail confirm(LoginUser me, String paymentKey, String orderId, Long amount) {
        Ownership.requireLogin(me);
        if (isBlank(paymentKey) || isBlank(orderId) || amount == null) {
            throw ApiException.badRequest("결제 승인 정보가 올바르지 않습니다.");
        }
        Donation d = donations.lockByOrderId(orderId).orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (!Ownership.isOwner(d.getDonor(), me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인의 후원 주문만 승인할 수 있습니다.");
        }

        // 같은 승인 통지가 다시 온 경우: 이미 확정된 결과를 그대로 돌려준다 (다시 적립하지 않음)
        if (d.getStatus() == Status.PAID) {
            if (d.isSamePayment(paymentKey)) return Detail.from(d);
            throw new ApiException(HttpStatus.CONFLICT, "이미 다른 결제로 완료된 주문입니다.");
        }
        if (d.getStatus() != Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 " + statusText(d.getStatus()) + " 주문입니다. 다시 후원해 주세요.");
        }

        // 화면(쿼리 파라미터)의 금액은 바뀔 수 있으므로, 서버에 저장된 주문 금액과 대조
        if (amount != d.getAmount()) {
            d.markFailed(Status.FAILED, "AMOUNT_MISMATCH", "결제 금액이 주문 금액과 다릅니다.");
            throw ApiException.badRequest("결제 금액이 주문 금액과 달라 후원을 확정하지 않았습니다.");
        }
        requireSameRecipient(d);

        PaymentInfo info;
        try {
            info = gateway.confirm(paymentKey, orderId, d.getAmount());
        } catch (PaymentException e) {
            if (!e.isDefinite()) {
                // 승인이 됐는지 알 수 없음 → 실패로 바꾸지 않고 PENDING 유지 (웹훅이나 재시도로 확정)
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "결제 결과를 아직 확인하지 못했습니다. 잠시 후 이 화면을 새로고침해 주세요.");
            }
            if (PaymentException.ALREADY_PROCESSED.equals(e.getCode())) {
                info = gateway.get(paymentKey); // 이미 승인된 결제 → 실제 상태를 조회해 확인
            } else {
                d.markFailed(Status.FAILED, e.getCode(), e.getMessage());
                throw ApiException.badRequest("결제가 승인되지 않았습니다: " + e.getMessage());
            }
        }

        // PG가 승인했다고 한 결제가 정말 이 주문·이 금액인지 다시 대조
        String problem = mismatch(info, d, paymentKey);
        if (problem != null) {
            log.warn("결제 검증 실패 order={} key={} : {}", orderId, paymentKey, problem);
            d.markFailed(Status.FAILED, "VERIFY_FAILED", problem);
            throw ApiException.badRequest("결제 정보가 주문과 일치하지 않아 후원을 확정하지 않았습니다.");
        }
        paid(d, info);
        return Detail.from(d);
    }

    // ---- 3') 결제창에서 실패·취소로 돌아온 경우 ----

    @Transactional
    public Detail abort(LoginUser me, String orderId, String code, String message) {
        Ownership.requireLogin(me);
        Donation d = donations.lockByOrderId(orderId).orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (!Ownership.isOwner(d.getDonor(), me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인의 후원 주문만 처리할 수 있습니다.");
        }
        // PENDING 일 때만 바뀜. 이미 확정(PAID)된 주문은 화면이 실패라고 보내도 그대로 둔다.
        String c = isBlank(code) ? "PAY_PROCESS_ABORTED" : code;
        d.markFailed(CANCEL_CODES.contains(c) ? Status.CANCELED : Status.FAILED, c,
                isBlank(message) ? "결제가 완료되지 않았습니다." : message);
        return Detail.from(d);
    }

    // ---- 4) PG 웹훅 ----

    /**
     * 결제 상태 변경 통지. 보낸 쪽을 확인할 수 없으므로 본문의 상태·금액은 쓰지 않고
     * paymentKey 로 PG에 직접 조회한 결과만 반영합니다. 여러 번 와도 결과는 같습니다.
     */
    @Transactional
    public void onWebhook(String eventType, String paymentKey) {
        if (!"PAYMENT_STATUS_CHANGED".equals(eventType) || isBlank(paymentKey)) return;
        PaymentInfo info;
        try {
            info = gateway.get(paymentKey);
        } catch (PaymentException e) {
            if (e.isDefinite()) return; // PG에 없는 결제 → 무시
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "결제 조회 실패, 재전송해 주세요.");
        }
        if (info.orderId() == null) return;
        Donation d = donations.lockByOrderId(info.orderId()).orElse(null);
        if (d == null) return; // 이 서비스의 주문이 아님

        if (info.isDone()) {
            if (d.getStatus() == Status.PAID) return; // 중복 통지 → 그대로
            if (d.getStatus() != Status.PENDING && d.getStatus() != Status.EXPIRED) return;
            String problem = mismatch(info, d, paymentKey);
            if (problem == null && recipientChanged(d) == null) {
                paid(d, info);
            } else {
                log.warn("웹훅 결제 검증 실패 order={} : {}", d.getOrderId(), problem);
            }
        } else if (info.isCanceled()) {
            if (d.isSamePayment(paymentKey)) d.markRefunded();
        } else if (info.isFailed()) {
            d.markFailed(Status.FAILED, info.status(), "결제가 완료되지 않았습니다.");
        }
    }

    // ---- 조회 ----

    /** 방송·다시보기별 후원 합계와 최근 내역 (공개) */
    @Transactional(readOnly = true)
    public Summary summary(Target target, long id) {
        boolean exists = target == Target.BROADCAST ? broadcasts.existsById(id) : replays.existsById(id);
        if (!exists) throw ApiException.notFound(target == Target.BROADCAST ? "방송을 찾을 수 없습니다." : "다시보기를 찾을 수 없습니다.");
        List<Donation> list = target == Target.BROADCAST
                ? donations.paidForBroadcast(id, Limit.of(LIST_SIZE))
                : donations.paidForReplay(id, Limit.of(LIST_SIZE));
        long[] t = totals(target, id);
        return new Summary(t[0], t[1], list.stream().map(Item::from).toList());
    }

    /** 내 주문 상태 (결과 화면용) */
    @Transactional(readOnly = true)
    public Detail detail(LoginUser me, String orderId) {
        Ownership.requireLogin(me);
        Donation d = donations.findWithUsersByOrderId(orderId).orElseThrow(() -> ApiException.notFound("주문을 찾을 수 없습니다."));
        if (!Ownership.isOwner(d.getDonor(), me)) throw new ApiException(HttpStatus.FORBIDDEN, "본인의 후원 주문만 볼 수 있습니다.");
        return Detail.from(d);
    }

    /** 결제창을 연 뒤 30분이 지나도 돌아오지 않은 주문 정리 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void expireStale() {
        Instant now = Instant.now();
        int n = donations.expirePending(now.minus(PENDING_TTL), now);
        if (n > 0) log.info("미완료 후원 주문 {}건 만료", n);
    }

    // ---- 내부 ----

    private void paid(Donation d, PaymentInfo info) {
        d.markPaid(info.paymentKey(), info.approvedAt());
        donations.flush(); // payment_key UNIQUE 위반을 여기서 바로 드러냄
        Broadcast b = d.getBroadcast();
        if (b != null && b.isLive()) {
            long id = b.getId();
            long[] t = totals(Target.BROADCAST, id);
            Map<String, Object> msg = Map.of("type", "donation", "donation", Item.from(d), "total", t[0], "count", t[1]);
            AfterCommit.run(() -> liveState.sendToAll(id, msg));
        }
        log.info("후원 확정 order={} amount={} recipient={}", d.getOrderId(), d.getAmount(), d.getRecipient().getId());
    }

    /** PG 결제 정보가 이 주문과 맞는지. 맞으면 null, 아니면 이유 */
    private static String mismatch(PaymentInfo info, Donation d, String paymentKey) {
        if (!info.isDone()) return "결제가 완료 상태가 아닙니다 (" + info.status() + ")";
        if (!d.getOrderId().equals(info.orderId())) return "주문번호 불일치";
        if (info.totalAmount() != d.getAmount()) return "금액 불일치 (주문 " + d.getAmount() + ", 결제 " + info.totalAmount() + ")";
        if (info.paymentKey() != null && !info.paymentKey().equals(paymentKey)) return "결제 키 불일치";
        return null;
    }

    /** 주문 후 대상의 소유자가 바뀌었다면 확정하지 않음 (받는 사람은 주문 시점에 고정) */
    private void requireSameRecipient(Donation d) {
        String problem = recipientChanged(d);
        if (problem != null) {
            d.markFailed(Status.FAILED, "RECIPIENT_MISMATCH", problem);
            throw ApiException.badRequest("받는 스트리머 정보가 주문과 달라 후원을 확정하지 않았습니다.");
        }
    }

    private static String recipientChanged(Donation d) {
        User current = d.getBroadcast() != null ? d.getBroadcast().getOwner()
                : d.getReplay() != null ? d.getReplay().getOwner() : d.getRecipient(); // 대상이 삭제됐으면 주문 값 유지
        if (current == null || !Objects.equals(current.getId(), d.getRecipient().getId())) return "받는 사람 불일치";
        return null;
    }

    private long[] totals(Target target, long id) {
        Object[] row = (target == Target.BROADCAST ? donations.totalForBroadcast(id) : donations.totalForReplay(id)).get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    private static long validAmount(Long amount) {
        if (amount == null) throw ApiException.badRequest("후원 금액을 선택해 주세요.");
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw ApiException.badRequest(String.format("후원 금액은 %,d원 이상 %,d원 이하로 선택해 주세요.", MIN_AMOUNT, MAX_AMOUNT));
        }
        if (amount % AMOUNT_UNIT != 0) throw ApiException.badRequest("후원 금액은 " + AMOUNT_UNIT + "원 단위로 입력해 주세요.");
        return amount;
    }

    private static String validMessage(String raw) {
        String m = raw == null ? "" : raw.strip();
        if (m.length() > MAX_MESSAGE) throw ApiException.badRequest("후원 메시지는 " + MAX_MESSAGE + "자 이하로 입력해 주세요.");
        return m.isEmpty() ? null : m;
    }

    /** PG 규칙: 6~64자, 영문·숫자·-_= . 추측할 수 없도록 무작위 128비트 */
    private static String newOrderId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "chi_" + HexFormat.of().formatHex(bytes);
    }

    private static String statusText(Status s) {
        return switch (s) {
            case FAILED -> "실패한";
            case CANCELED -> "취소된";
            case EXPIRED -> "만료된";
            case REFUNDED -> "환불된";
            default -> "처리된";
        };
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
