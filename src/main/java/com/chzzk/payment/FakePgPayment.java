package com.chzzk.payment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 내장 가짜 PG가 발급한 결제 한 건. 서버가 여러 대여도 같은 결제를 보도록 DB에 둡니다.
 * (실제 PG라면 PG 회사 쪽에 있는 기록입니다.)
 */
@Entity
@Table(name = "fake_pg_payments")
class FakePgPayment {

    /** 토스페이먼츠 Payment.status 와 같은 이름 (DB 에는 이름 문자열로 저장) */
    enum Status { IN_PROGRESS, DONE, CANCELED, ABORTED }

    @Id
    @Column(length = 64)
    private String paymentKey;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private boolean rejectOnConfirm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Instant approvedAt;

    protected FakePgPayment() {
    }

    FakePgPayment(String paymentKey, String orderId, long amount, boolean rejectOnConfirm) {
        this.paymentKey = paymentKey;
        this.orderId = orderId;
        this.amount = amount;
        this.rejectOnConfirm = rejectOnConfirm;
        this.status = Status.IN_PROGRESS;
    }

    String getOrderId() {
        return orderId;
    }

    long getAmount() {
        return amount;
    }

    boolean isRejectOnConfirm() {
        return rejectOnConfirm;
    }

    Status getStatus() {
        return status;
    }

    void abort() {
        status = Status.ABORTED;
    }

    void approve(Instant at) {
        status = Status.DONE;
        approvedAt = at;
    }

    void cancel() {
        if (status == Status.DONE) status = Status.CANCELED;
    }

    PaymentInfo info() {
        return new PaymentInfo(paymentKey, orderId, status.name(), amount, approvedAt);
    }
}
