package com.chichijik.donation;

import java.time.Instant;
import java.util.List;

import com.chichijik.user.OwnerResponse;

/** 도네이션 API 응답 모음 */
public final class DonationResponses {

    private DonationResponses() {
    }

    /** 공개 후원 내역의 한 줄 (확정된 후원만) */
    public record Item(Long id, OwnerResponse donor, long amount, String message, Instant paidAt) {
        static Item from(Donation d) {
            return new Item(d.getId(), OwnerResponse.of(d.getDonor()), d.getAmount(), d.getMessage(), d.getPaidAt());
        }
    }

    /** 방송별 후원 합계와 최근 내역 */
    public record Summary(long total, long count, List<Item> donations) {
    }

    /** 결제창을 열기 위한 주문 정보 (금액·주문번호는 서버가 정한 값) */
    public record Checkout(
            String orderId,
            String orderName,
            long amount,
            String provider,
            String clientKey,
            OwnerResponse recipient,
            String targetTitle
    ) {
    }

    /** 후원자 본인에게 보여주는 주문 상태 */
    public record Detail(
            String orderId,
            String status,
            long amount,
            String message,
            String targetKind,
            Long targetId,
            String targetTitle,
            OwnerResponse recipient,
            String failureCode,
            String failureMessage,
            Instant createdAt,
            Instant paidAt
    ) {
        static Detail from(Donation d) {
            String kind = d.getBroadcast() != null ? "broadcast" : d.getReplay() != null ? "replay" : null;
            Long targetId = d.getBroadcast() != null ? d.getBroadcast().getId()
                    : d.getReplay() != null ? d.getReplay().getId() : null;
            return new Detail(d.getOrderId(), d.getStatus().name(), d.getAmount(), d.getMessage(), kind, targetId,
                    d.getTargetTitle(), OwnerResponse.of(d.getRecipient()), d.getFailureCode(), d.getFailureMessage(),
                    d.getCreatedAt(), d.getPaidAt());
        }
    }
}
