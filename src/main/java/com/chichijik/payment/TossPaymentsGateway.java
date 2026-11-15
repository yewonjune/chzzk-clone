package com.chichijik.payment;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토스페이먼츠 코어 API (결제창 · API 개별 연동 키).
 *  - 승인: POST /v1/payments/confirm {paymentKey, orderId, amount}
 *  - 조회: GET  /v1/payments/{paymentKey}
 * 인증은 "시크릿키:" 를 base64 로 인코딩한 Basic 헤더입니다.
 * 테스트 키(test_)만 허용하므로 실제 금전 결제는 일어나지 않습니다.
 */
public class TossPaymentsGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentsGateway.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String clientKey;
    private final String authorization;
    private final String apiBase;
    private final HttpClient http;
    private final Duration readTimeout;

    public TossPaymentsGateway(String clientKey, String secretKey, String apiBase, Duration readTimeout) {
        this.clientKey = clientKey;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.readTimeout = readTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String provider() {
        return "toss";
    }

    @Override
    public String clientKey() {
        return clientKey;
    }

    @Override
    public PaymentInfo confirm(String paymentKey, String orderId, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentKey", paymentKey);
        body.put("orderId", orderId);
        body.put("amount", amount);
        HttpRequest req = base("/v1/payments/confirm")
                .header("Content-Type", "application/json")
                // 같은 주문의 승인 요청이 재전송되어도 PG가 한 번만 처리하도록 (멱등키)
                .header("Idempotency-Key", "confirm-" + orderId)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        return send(req);
    }

    @Override
    public PaymentInfo get(String paymentKey) {
        String path = "/v1/payments/" + URLEncoder.encode(paymentKey, StandardCharsets.UTF_8);
        return send(base(path).GET().build());
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(readTimeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json");
    }

    private PaymentInfo send(HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("토스페이먼츠 호출 실패 {}: {}", req.uri().getPath(), e.toString());
            throw new PaymentException("NETWORK_ERROR", "결제 서비스와 통신하지 못했습니다.", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentException("NETWORK_ERROR", "결제 서비스 호출이 중단되었습니다.", false);
        }
        JsonNode json;
        try {
            json = JSON.readTree(res.body());
        } catch (RuntimeException e) {
            json = JSON.createObjectNode();
        }
        int status = res.statusCode();
        if (status >= 200 && status < 300) return toInfo(json);
        String code = text(json, "code", "HTTP_" + status);
        String message = text(json, "message", "결제 서비스 오류 (" + status + ")");
        // 4xx 는 PG가 명확히 거절한 것, 5xx 는 처리 결과를 알 수 없음
        throw new PaymentException(code, message, status < 500);
    }

    static PaymentInfo toInfo(JsonNode p) {
        Instant approvedAt = null;
        String approved = text(p, "approvedAt", null);
        if (approved != null) {
            try {
                approvedAt = OffsetDateTime.parse(approved).toInstant();
            } catch (RuntimeException ignored) {
                approvedAt = Instant.now();
            }
        }
        return new PaymentInfo(
                text(p, "paymentKey", null),
                text(p, "orderId", null),
                text(p, "status", "UNKNOWN"),
                p.has("totalAmount") ? p.get("totalAmount").asLong() : -1,
                approvedAt);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? fallback : v.asString();
    }
}
