package com.chichijik.payment;

/**
 * 결제 서비스(PG) 연동 경계.
 * 도네이션 쪽은 이 인터페이스만 알고, 실제 PG(토스페이먼츠 테스트 모드)인지
 * 키 없이 돌려 보기 위한 내장 가짜 PG인지는 설정으로 정해집니다.
 */
public interface PaymentGateway {

    /** "toss" 또는 "fake" */
    String provider();

    /** 브라우저 SDK 초기화용 공개 키 (가짜 PG는 null) */
    String clientKey();

    /**
     * 결제 승인 요청. 성공하면 PG가 돌려준 결제 정보를 반환합니다.
     * @throws PaymentException PG가 거절했거나(definite) 결과를 알 수 없을 때(indefinite)
     */
    PaymentInfo confirm(String paymentKey, String orderId, long amount);

    /** 결제 조회. 웹훅 본문을 믿지 않고 PG에 직접 다시 물어볼 때 씁니다. */
    PaymentInfo get(String paymentKey);
}
