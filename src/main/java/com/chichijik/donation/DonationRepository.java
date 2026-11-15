package com.chichijik.donation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface DonationRepository extends JpaRepository<Donation, Long> {

    /**
     * 승인·웹훅 처리용: 행 잠금(SELECT ... FOR UPDATE).
     * 같은 주문의 승인 요청과 웹훅이 동시에 와도 한 번에 하나만 상태를 바꾸고,
     * 뒤에 온 요청은 이미 바뀐 상태(PAID)를 보고 그냥 돌아갑니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donation d where d.orderId = :orderId")
    Optional<Donation> lockByOrderId(@Param("orderId") String orderId);

    @EntityGraph(attributePaths = {"donor", "recipient"})
    Optional<Donation> findWithUsersByOrderId(String orderId);

    @EntityGraph(attributePaths = "donor")
    @Query("select d from Donation d where d.broadcast.id = :id and d.status = com.chichijik.donation.Donation.Status.PAID order by d.id desc")
    List<Donation> paidForBroadcast(@Param("id") long id, Limit limit);

    @EntityGraph(attributePaths = "donor")
    @Query("select d from Donation d where d.replay.id = :id and d.status = com.chichijik.donation.Donation.Status.PAID order by d.id desc")
    List<Donation> paidForReplay(@Param("id") long id, Limit limit);

    /** [합계, 건수] — 확정(PAID)된 후원만 */
    @Query("select coalesce(sum(d.amount), 0), count(d) from Donation d "
            + "where d.broadcast.id = :id and d.status = com.chichijik.donation.Donation.Status.PAID")
    List<Object[]> totalForBroadcast(@Param("id") long id);

    @Query("select coalesce(sum(d.amount), 0), count(d) from Donation d "
            + "where d.replay.id = :id and d.status = com.chichijik.donation.Donation.Status.PAID")
    List<Object[]> totalForReplay(@Param("id") long id);

    /** 결제창을 열고 30분 넘게 돌아오지 않은 주문을 만료 처리 */
    @Modifying
    @Query("update Donation d set d.status = com.chichijik.donation.Donation.Status.EXPIRED, d.updatedAt = :now "
            + "where d.status = com.chichijik.donation.Donation.Status.PENDING and d.createdAt < :before")
    int expirePending(@Param("before") Instant before, @Param("now") Instant now);
}
