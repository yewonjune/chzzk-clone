package com.chichijik.payment;

/**
 * PG 호출 실패.
 *  - definite = true : PG가 명확히 거절함 (카드 거절, 이미 처리됨 등) → 결과가 확정됨
 *  - definite = false: 네트워크 오류·시간 초과·PG 5xx → 실제로는 승인됐을 수도 있어 결과를 모름
 * 결과를 모르는 경우 도네이션을 실패로 바꾸지 않고, 웹훅이나 재시도로 다시 확인합니다.
 */
public class PaymentException extends RuntimeException {

    public static final String ALREADY_PROCESSED = "ALREADY_PROCESSED_PAYMENT";

    private final String code;
    private final boolean definite;

    public PaymentException(String code, String message, boolean definite) {
        super(message);
        this.code = code;
        this.definite = definite;
    }

    public String getCode() {
        return code;
    }

    public boolean isDefinite() {
        return definite;
    }
}
