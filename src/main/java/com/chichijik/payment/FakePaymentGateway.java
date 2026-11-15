package com.chichijik.payment;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키 없이 실행할 때 쓰는 내장 가짜 PG (자동 테스트·로컬 시연용).
 * 실제 PG처럼 "결제창 인증(authorize) → 상점 서버의 승인(confirm)" 두 단계를 흉내 내며,
 * 인증 때 정해진 금액과 승인 요청 금액이 다르면 거절합니다. 결제 정보는 메모리에만 있습니다.
 */
public class FakePaymentGateway implements PaymentGateway {

    private static final class Payment {
        final String paymentKey;
        final String orderId;
        final long amount;
        final boolean rejectOnConfirm;
        volatile String status = "IN_PROGRESS";
        volatile Instant approvedAt;

        Payment(String paymentKey, String orderId, long amount, boolean rejectOnConfirm) {
            this.paymentKey = paymentKey;
            this.orderId = orderId;
            this.amount = amount;
            this.rejectOnConfirm = rejectOnConfirm;
        }

        PaymentInfo info() {
            return new PaymentInfo(paymentKey, orderId, status, amount, approvedAt);
        }
    }

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String clientKey() {
        return null;
    }

    /** 가짜 결제창에서 "결제하기"를 눌렀을 때: 결제 인증 완료 상태(IN_PROGRESS)를 만들고 paymentKey 발급 */
    public String authorize(String orderId, long amount, boolean rejectOnConfirm) {
        String key = "fake_" + UUID.randomUUID().toString().replace("-", "");
        payments.put(key, new Payment(key, orderId, amount, rejectOnConfirm));
        return key;
    }

    @Override
    public synchronized PaymentInfo confirm(String paymentKey, String orderId, long amount) {
        Payment p = payments.get(paymentKey);
        if (p == null || !p.orderId.equals(orderId)) {
            throw new PaymentException("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.", true);
        }
        if ("DONE".equals(p.status)) {
            throw new PaymentException(PaymentException.ALREADY_PROCESSED, "이미 처리된 결제 입니다.", true);
        }
        if (!"IN_PROGRESS".equals(p.status)) {
            throw new PaymentException("NOT_AVAILABLE_PAYMENT", "결제가 불가능한 상태입니다.", true);
        }
        if (p.amount != amount) {
            throw new PaymentException("INVALID_REQUEST", "결제 금액이 인증된 금액과 다릅니다.", true);
        }
        if (p.rejectOnConfirm) {
            p.status = "ABORTED";
            throw new PaymentException("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.", true);
        }
        p.status = "DONE";
        p.approvedAt = Instant.now();
        return p.info();
    }

    @Override
    public PaymentInfo get(String paymentKey) {
        Payment p = payments.get(paymentKey);
        if (p == null) throw new PaymentException("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.", true);
        return p.info();
    }

    /** 가짜 PG 관리자 화면에서 승인된 결제를 취소했다고 가정 (웹훅 환불 처리 확인용) */
    public PaymentInfo cancel(String paymentKey) {
        Payment p = payments.get(paymentKey);
        if (p == null) throw new PaymentException("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.", true);
        if ("DONE".equals(p.status)) p.status = "CANCELED";
        return p.info();
    }
}
