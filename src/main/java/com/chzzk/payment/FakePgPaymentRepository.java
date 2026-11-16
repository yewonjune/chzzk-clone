package com.chzzk.payment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface FakePgPaymentRepository extends JpaRepository<FakePgPayment, String> {

    /** 승인 처리 중에는 결제 행을 잠가, 여러 서버에서 같은 결제를 동시에 승인해도 한 번만 DONE 이 됩니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FakePgPayment p where p.paymentKey = :paymentKey")
    Optional<FakePgPayment> lockByPaymentKey(@Param("paymentKey") String paymentKey);
}
