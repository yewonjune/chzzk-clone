package com.chichijik.donation;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.chichijik.broadcast.Broadcast;
import com.chichijik.replay.Replay;
import com.chichijik.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 도네이션(후원) 한 건 = 결제 주문 한 건.
 *
 * 결제창을 열기 전에 서버가 PENDING 으로 만들고, 금액·받는 사람을 여기에 고정합니다.
 * 이후 승인 단계에서는 화면이 보낸 값이 아니라 이 행의 값과 PG 응답을 비교해 확정(PAID)합니다.
 *
 * 상태 전이 (PAID 로 가는 길은 markPaid 하나뿐):
 *   PENDING ─승인 확인→ PAID ─PG에서 취소→ REFUNDED
 *   PENDING ─거절/금액 불일치→ FAILED,  ─사용자 취소→ CANCELED,  ─30분 방치→ EXPIRED
 *   EXPIRED ─(늦게 도착한) 승인 완료 웹훅→ PAID
 *
 * 방송·다시보기가 삭제되어도 결제 기록은 남도록 대상 FK는 ON DELETE SET NULL 입니다.
 */
@Entity
@Table(name = "donations", indexes = {
        // 방송별/다시보기별 확정 후원 목록·합계
        @Index(name = "idx_donations_broadcast", columnList = "broadcast_id, status, id"),
        @Index(name = "idx_donations_replay", columnList = "replay_id, status, id"),
        @Index(name = "idx_donations_status_created", columnList = "status, created_at")
})
public class Donation {

    public enum Status { PENDING, PAID, FAILED, CANCELED, EXPIRED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PG에 넘기는 주문번호. 추측할 수 없는 값, 중복 불가 */
    @Column(name = "order_id", nullable = false, unique = true, length = 64, updatable = false)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Broadcast broadcast;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replay_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Replay replay;

    /** 대상 제목 (대상이 삭제된 뒤에도 내역에 보이도록 주문 시점 값을 보관) */
    @Column(name = "target_title", nullable = false, length = 100)
    private String targetTitle;

    /** 받는 사람: 주문 시점의 방송·다시보기 소유자 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id", nullable = false, updatable = false)
    private User donor;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(length = 100)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, length = 10, updatable = false)
    private String provider;

    /** PG의 결제 키. 승인 확정 시 저장, 같은 결제가 두 주문에 적립되지 않도록 UNIQUE */
    @Column(name = "payment_key", unique = true, length = 200)
    private String paymentKey;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 300)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Donation() {
    }

    Donation(String orderId, Broadcast broadcast, Replay replay, String targetTitle, User recipient, User donor,
             long amount, String message, String provider) {
        this.orderId = orderId;
        this.broadcast = broadcast;
        this.replay = replay;
        this.targetTitle = targetTitle;
        this.recipient = recipient;
        this.donor = donor;
        this.amount = amount;
        this.message = message;
        this.provider = provider;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    /** 승인 확인이 끝난 경우에만 호출. PENDING(또는 만료 후 늦은 승인)에서만 PAID 로 */
    void markPaid(String paymentKey, Instant approvedAt) {
        if (status != Status.PENDING && status != Status.EXPIRED) {
            throw new IllegalStateException("확정할 수 없는 상태: " + status);
        }
        this.status = Status.PAID;
        this.paymentKey = paymentKey;
        this.paidAt = approvedAt != null ? approvedAt : Instant.now();
        this.failureCode = null;
        this.failureMessage = null;
        touch();
    }

    void markFailed(Status to, String code, String message) {
        if (status != Status.PENDING) return; // 확정된 결과는 덮어쓰지 않음
        this.status = to;
        this.failureCode = truncate(code, 60);
        this.failureMessage = truncate(message, 300);
        touch();
    }

    void markRefunded() {
        if (status == Status.PAID) {
            status = Status.REFUNDED;
            touch();
        }
    }

    /** 이 결제가 이 주문의 것인지 */
    boolean isSamePayment(String key) {
        return paymentKey != null && paymentKey.equals(key);
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public Broadcast getBroadcast() { return broadcast; }
    public Replay getReplay() { return replay; }
    public String getTargetTitle() { return targetTitle; }
    public User getRecipient() { return recipient; }
    public User getDonor() { return donor; }
    public long getAmount() { return amount; }
    public String getMessage() { return message; }
    public Status getStatus() { return status; }
    public String getProvider() { return provider; }
    public String getPaymentKey() { return paymentKey; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
}
