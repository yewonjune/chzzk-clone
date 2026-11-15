package com.chichijik.payment;

import java.time.Instant;

/**
 * PG가 알려준 결제 한 건의 상태.
 * status 는 토스페이먼츠 Payment 객체와 같은 값을 씁니다:
 * READY, IN_PROGRESS, DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED
 */
public record PaymentInfo(
        String paymentKey,
        String orderId,
        String status,
        long totalAmount,
        Instant approvedAt
) {
    public boolean isDone() {
        return "DONE".equals(status);
    }

    public boolean isCanceled() {
        return "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status);
    }

    public boolean isFailed() {
        return "ABORTED".equals(status) || "EXPIRED".equals(status);
    }
}
