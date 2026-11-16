package com.chzzk.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 키 없이 실행할 때 쓰는 내장 가짜 PG (자동 테스트·로컬 시연용).
 * 실제 PG처럼 "결제창 인증(authorize) → 상점 서버의 승인(confirm)" 두 단계를 흉내 내며,
 * 인증 때 정해진 금액과 승인 요청 금액이 다르면 거절합니다.
 * 결제 기록은 DB(fake_pg_payments)에 두어, 결제창을 연 서버와 승인 확인을 받은 서버가 달라도 같은 결제를 봅니다.
 * 승인은 결제 행을 잠그고 결제 키 기준으로 한 번만 처리합니다.
 */
public class FakePaymentGateway implements PaymentGateway {

    private final FakePgPaymentRepository payments;
    private final TransactionTemplate tx;

    FakePaymentGateway(FakePgPaymentRepository payments, PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.tx = new TransactionTemplate(transactionManager); // 호출한 쪽 트랜잭션이 있으면 그 안에서
    }

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
        payments.save(new FakePgPayment(key, orderId, amount, rejectOnConfirm));
        return key;
    }

    @Override
    public PaymentInfo confirm(String paymentKey, String orderId, long amount) {
        // 거절도 결과(ABORTED)를 남겨야 하므로, 예외를 트랜잭션 밖에서 던져 호출한 쪽 트랜잭션을 롤백시키지 않습니다.
        Object result = tx.execute(status -> {
            FakePgPayment p = payments.lockByPaymentKey(paymentKey).orElse(null);
            if (p == null || !p.getOrderId().equals(orderId)) {
                return new PaymentException("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.", true);
            }
            if (p.getStatus() == FakePgPayment.Status.DONE) {
                return new PaymentException(PaymentException.ALREADY_PROCESSED, "이미 처리된 결제 입니다.", true);
            }
            if (p.getStatus() != FakePgPayment.Status.IN_PROGRESS) {
                return new PaymentException("NOT_AVAILABLE_PAYMENT", "결제가 불가능한 상태입니다.", true);
            }
            if (p.getAmount() != amount) {
                return new PaymentException("INVALID_REQUEST", "결제 금액이 인증된 금액과 다릅니다.", true);
            }
            if (p.isRejectOnConfirm()) {
                p.abort();
                return new PaymentException("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.", true);
            }
            p.approve(Instant.now());
            return p.info();
        });
        if (result instanceof PaymentException e) throw e;
        return (PaymentInfo) result;
    }

    @Override
    public PaymentInfo get(String paymentKey) {
        return payments.findById(paymentKey).map(FakePgPayment::info)
                .orElseThrow(() -> new PaymentException("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.", true));
    }

    /** 가짜 PG 관리자 화면에서 승인된 결제를 취소했다고 가정 (웹훅 환불 처리 확인용) */
    public PaymentInfo cancel(String paymentKey) {
        PaymentInfo info = tx.execute(status -> payments.lockByPaymentKey(paymentKey).map(p -> {
            p.cancel();
            return p.info();
        }).orElse(null));
        if (info == null) throw new PaymentException("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.", true);
        return info;
    }
}
