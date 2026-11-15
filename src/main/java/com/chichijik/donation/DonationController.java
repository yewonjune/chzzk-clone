package com.chichijik.donation;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chichijik.common.ApiException;
import com.chichijik.donation.DonationResponses.Checkout;
import com.chichijik.donation.DonationResponses.Detail;
import com.chichijik.donation.DonationResponses.Summary;
import com.chichijik.donation.DonationService.Target;
import com.chichijik.payment.PaymentGateway;
import com.chichijik.user.LoginUser;

import tools.jackson.databind.JsonNode;

@RestController
public class DonationController {

    private final DonationService service;
    private final PaymentGateway gateway;

    public DonationController(DonationService service, PaymentGateway gateway) {
        this.service = service;
        this.gateway = gateway;
    }

    /** targetType: "broadcast" | "replay" */
    public record CreateRequest(String targetType, Long targetId, Long amount, String message) {
    }

    public record ConfirmRequest(String paymentKey, String orderId, Long amount) {
    }

    public record AbortRequest(String code, String message) {
    }

    /** 결제 화면 설정 (공개): 어떤 PG인지, 금액 규칙 */
    @GetMapping("/api/payments/config")
    public Map<String, Object> config() {
        return Map.of(
                "provider", gateway.provider(),
                "clientKey", gateway.clientKey() == null ? "" : gateway.clientKey(),
                "minAmount", DonationService.MIN_AMOUNT,
                "maxAmount", DonationService.MAX_AMOUNT,
                "amountUnit", DonationService.AMOUNT_UNIT);
    }

    // ---- 후원 주문 (로그인 필요) ----

    @PostMapping("/api/donations")
    @ResponseStatus(HttpStatus.CREATED)
    public Checkout create(@AuthenticationPrincipal LoginUser me, @RequestBody CreateRequest body) {
        return service.create(me, target(body.targetType()), body.targetId(), body.amount(), body.message());
    }

    /** successUrl 로 돌아온 값으로 승인 확인 → 확정 */
    @PostMapping("/api/donations/confirm")
    public Detail confirm(@AuthenticationPrincipal LoginUser me, @RequestBody ConfirmRequest body) {
        return service.confirm(me, body.paymentKey(), body.orderId(), body.amount());
    }

    /** failUrl 로 돌아온 경우 (사용자 취소·결제 실패) */
    @PostMapping("/api/donations/{orderId}/abort")
    public Detail abort(@AuthenticationPrincipal LoginUser me, @PathVariable String orderId,
                        @RequestBody(required = false) AbortRequest body) {
        return service.abort(me, orderId, body == null ? null : body.code(), body == null ? null : body.message());
    }

    @GetMapping("/api/donations/{orderId}")
    public Detail detail(@AuthenticationPrincipal LoginUser me, @PathVariable String orderId) {
        return service.detail(me, orderId);
    }

    // ---- 방송별 후원 내역·합계 (공개) ----

    @GetMapping("/api/broadcasts/{id}/donations")
    public Summary broadcastDonations(@PathVariable long id) {
        return service.summary(Target.BROADCAST, id);
    }

    @GetMapping("/api/replays/{id}/donations")
    public Summary replayDonations(@PathVariable long id) {
        return service.summary(Target.REPLAY, id);
    }

    // ---- PG 웹훅 (로그인·CSRF 없이 PG 서버가 호출) ----

    @PostMapping("/api/payments/webhook")
    public Map<String, String> webhook(@RequestBody JsonNode body) {
        JsonNode data = body.path("data");
        String paymentKey = data.path("paymentKey").isString() ? data.path("paymentKey").asString() : null;
        service.onWebhook(body.path("eventType").asString(""), paymentKey);
        return Map.of("result", "ok");
    }

    private static Target target(String type) {
        if ("broadcast".equals(type)) return Target.BROADCAST;
        if ("replay".equals(type)) return Target.REPLAY;
        throw ApiException.badRequest("후원 대상 종류는 broadcast 또는 replay 여야 합니다.");
    }
}
