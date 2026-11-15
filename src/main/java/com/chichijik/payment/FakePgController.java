package com.chichijik.payment;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chichijik.common.ApiException;

/**
 * 내장 가짜 PG의 "결제창" API. fake-pg.html 이 호출합니다.
 * 토스페이먼츠 테스트 모드로 실행 중이면 404 입니다.
 */
@RestController
public class FakePgController {

    private final PaymentGateway gateway;

    public FakePgController(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * @param reject true 면 결제창 인증은 통과하지만 상점 서버의 승인 단계에서 카드사가 거절 (승인 실패 시나리오)
     */
    public record AuthorizeRequest(String orderId, Long amount, Boolean reject) {
    }

    @PostMapping("/api/fake-pg/authorize")
    public Map<String, String> authorize(@RequestBody AuthorizeRequest req) {
        if (!(gateway instanceof FakePaymentGateway fake)) throw ApiException.notFound("가짜 PG를 사용하지 않는 설정입니다.");
        if (req.orderId() == null || req.amount() == null || req.amount() <= 0) {
            throw ApiException.badRequest("주문번호와 금액이 필요합니다.");
        }
        String key = fake.authorize(req.orderId(), req.amount(), Boolean.TRUE.equals(req.reject()));
        return Map.of("paymentKey", key);
    }
}
