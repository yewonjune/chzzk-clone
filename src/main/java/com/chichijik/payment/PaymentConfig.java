package com.chichijik.payment;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 어떤 PG를 쓸지 정합니다 (환경변수 PAYMENT_PROVIDER).
 *  - auto (기본): TOSS_SECRET_KEY 가 있으면 토스페이먼츠 테스트 모드, 없으면 내장 가짜 PG
 *  - toss: 토스페이먼츠 테스트 모드 (키가 없으면 기동 실패)
 *  - fake: 내장 가짜 PG
 * 실제 결제가 일어나지 않도록 live_ 키는 거부합니다.
 */
@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    PaymentGateway paymentGateway(@Value("${app.payment.provider}") String provider,
                                  @Value("${app.payment.toss.client-key}") String clientKey,
                                  @Value("${app.payment.toss.secret-key}") String secretKey,
                                  @Value("${app.payment.toss.api-base}") String apiBase,
                                  @Value("${app.payment.toss.read-timeout}") Duration readTimeout) {
        String p = provider == null ? "auto" : provider.strip().toLowerCase();
        boolean hasKeys = !clientKey.isBlank() && !secretKey.isBlank();
        boolean useToss = switch (p) {
            case "toss" -> true;
            case "fake" -> false;
            case "auto" -> hasKeys;
            default -> throw new IllegalStateException("PAYMENT_PROVIDER 는 auto, toss, fake 중 하나여야 합니다: " + provider);
        };
        if (!useToss) {
            log.info("결제: 내장 가짜 PG 사용 (토스페이먼츠 테스트 결제를 쓰려면 TOSS_CLIENT_KEY / TOSS_SECRET_KEY 설정)");
            return new FakePaymentGateway();
        }
        if (!hasKeys) {
            throw new IllegalStateException("PAYMENT_PROVIDER=toss 이면 TOSS_CLIENT_KEY 와 TOSS_SECRET_KEY 가 필요합니다.");
        }
        if (!clientKey.startsWith("test_") || !secretKey.startsWith("test_")) {
            throw new IllegalStateException("토스페이먼츠는 테스트 키(test_ 로 시작)만 사용할 수 있습니다. 실제 결제 키는 넣지 마세요.");
        }
        log.info("결제: 토스페이먼츠 테스트 모드");
        return new TossPaymentsGateway(clientKey.strip(), secretKey.strip(), apiBase, readTimeout);
    }
}
