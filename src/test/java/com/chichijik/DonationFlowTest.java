package com.chichijik;

import static com.chichijik.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.chichijik.ApiClient.Socket;
import com.chichijik.payment.FakePaymentGateway;
import com.chichijik.payment.PaymentGateway;

import tools.jackson.databind.JsonNode;

/**
 * 4단계 도네이션: 내장 가짜 PG(실제 PG와 같은 "결제창 인증 → 서버 승인" 2단계)로
 * 성공·실패·취소·금액 변조·승인 통지 중복(재요청·웹훅 재전송·동시 요청)을 확인합니다.
 */
class DonationFlowTest {

    @TempDir
    Path dataDir;

    /** 방송 중인 스트리머(alice), 후원자(bob), 다른 사용자(carol) */
    record World(ConfigurableApplicationContext app, String base, ApiClient alice, ApiClient bob, ApiClient carol,
                 long broadcastId) implements AutoCloseable {
        @Override
        public void close() {
            app.close();
        }
    }

    World world() throws Exception {
        var app = ApiClient.startServer(dataDir, "--app.payment.provider=fake");
        String base = ApiClient.baseUrl(app);
        ApiClient alice = ApiClient.signUp(base, "alice_01", "앨리스");
        ApiClient bob = ApiClient.signUp(base, "bob_01", "밥밥");
        ApiClient carol = ApiClient.signUp(base, "carol_01", "캐럴");
        var started = alice.postJson("/api/broadcasts", "{\"title\":\"저녁 방송\"}");
        assertThat(started.statusCode()).isEqualTo(201);
        return new World(app, base, alice, bob, carol, json(started).get("id").asLong());
    }

    static String order(ApiClient c, long broadcastId, long amount, String message) throws Exception {
        var res = c.postJson("/api/donations", "{\"targetType\":\"broadcast\",\"targetId\":" + broadcastId
                + ",\"amount\":" + amount + ",\"message\":\"" + message + "\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        JsonNode body = json(res);
        assertThat(body.get("amount").asLong()).isEqualTo(amount);
        assertThat(body.get("recipient").get("nickname").asString()).isEqualTo("앨리스");
        return body.get("orderId").asString();
    }

    /** 가짜 PG 결제창에서 "결제하기" (결제 인증) → paymentKey */
    static String authorize(ApiClient c, String orderId, long amount, boolean reject) throws Exception {
        var res = c.postJson("/api/fake-pg/authorize",
                "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + ",\"reject\":" + reject + "}");
        assertThat(res.statusCode()).isEqualTo(200);
        return json(res).get("paymentKey").asString();
    }

    static HttpResponse<String> confirm(ApiClient c, String paymentKey, String orderId, long amount) throws Exception {
        return c.postJson("/api/donations/confirm",
                "{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}");
    }

    static JsonNode summary(World w) throws Exception {
        return json(ApiClient.anonymous(w.base()).get("/api/broadcasts/" + w.broadcastId() + "/donations"));
    }

    static HttpResponse<String> webhook(World w, String paymentKey) throws Exception {
        // PG 서버의 호출: 로그인 세션·CSRF 토큰 없음
        return ApiClient.anonymous(w.base()).postJsonWithoutCsrf("/api/payments/webhook",
                "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"" + paymentKey
                        + "\",\"status\":\"DONE\",\"totalAmount\":99999999}}");
    }

    @Test
    void 주문_만들기_검증과_권한() throws Exception {
        try (World w = world()) {
            ApiClient guest = ApiClient.anonymous(w.base());
            String body = "{\"targetType\":\"broadcast\",\"targetId\":" + w.broadcastId() + ",\"amount\":5000}";
            assertThat(guest.postJson("/api/donations", body).statusCode()).isEqualTo(401);          // 비로그인
            assertThat(w.alice().postJson("/api/donations", body).statusCode()).isEqualTo(400);      // 자기 방송
            assertThat(w.bob().postJson("/api/donations", body.replace("5000", "500")).statusCode()).isEqualTo(400);     // 최소 금액 미만
            assertThat(w.bob().postJson("/api/donations", body.replace("5000", "5050")).statusCode()).isEqualTo(400);    // 100원 단위 아님
            assertThat(w.bob().postJson("/api/donations", body.replace("5000", "2000000")).statusCode()).isEqualTo(400); // 최대 초과
            assertThat(w.bob().postJson("/api/donations", body.replace("\"broadcast\"", "\"user\"")).statusCode()).isEqualTo(400);
            assertThat(w.bob().postJson("/api/donations", body.replace(":" + w.broadcastId() + ",", ":999,")).statusCode()).isEqualTo(404);

            assertThat(json(guest.get("/api/payments/config")).get("provider").asString()).isEqualTo("fake");
            // 주문만 만든 상태(PENDING)는 합계에 들어가지 않음
            order(w.bob(), w.broadcastId(), 5000, "hi");
            assertThat(summary(w).get("total").asLong()).isZero();
        }
    }

    @Test
    void 결제_성공_후_같은_승인_통지가_반복되어도_한_번만_적립() throws Exception {
        try (World w = world()) {
            // 방송 화면을 보고 있는 시청자는 후원 알림을 바로 받음
            Socket viewer = ApiClient.anonymous(w.base()).socket("/ws/broadcasts/" + w.broadcastId());
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            viewer.next("viewers");

            String orderId = order(w.bob(), w.broadcastId(), 5000, "응원합니다");
            String key = authorize(w.bob(), orderId, 5000, false);

            // 다른 사람이 주문번호를 알아도 승인할 수 없음
            assertThat(confirm(w.carol(), key, orderId, 5000).statusCode()).isEqualTo(403);
            assertThat(confirm(ApiClient.anonymous(w.base()), key, orderId, 5000).statusCode()).isEqualTo(401);

            var first = confirm(w.bob(), key, orderId, 5000);
            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
            assertThat(json(first).get("status").asString()).isEqualTo("PAID");

            JsonNode pushed = viewer.next("donation");
            assertThat(pushed.get("donation").get("amount").asLong()).isEqualTo(5000);
            assertThat(pushed.get("total").asLong()).isEqualTo(5000);

            // 결과 화면 새로고침 = 같은 승인 요청 재전송 → 같은 결과, 추가 적립 없음
            for (int i = 0; i < 3; i++) {
                var again = confirm(w.bob(), key, orderId, 5000);
                assertThat(again.statusCode()).isEqualTo(200);
                assertThat(json(again).get("status").asString()).isEqualTo("PAID");
            }
            // PG 웹훅 재전송 (본문의 금액은 무시하고 PG에 다시 조회)
            for (int i = 0; i < 3; i++) assertThat(webhook(w, key).statusCode()).isEqualTo(200);

            JsonNode s = summary(w);
            assertThat(s.get("total").asLong()).isEqualTo(5000);
            assertThat(s.get("count").asLong()).isEqualTo(1);
            assertThat(s.get("donations").get(0).get("donor").get("nickname").asString()).isEqualTo("밥밥");
            assertThat(s.get("donations").get(0).get("message").asString()).isEqualTo("응원합니다");

            // 두 번째 후원은 따로 적립
            String order2 = order(w.carol(), w.broadcastId(), 10000, "");
            assertThat(confirm(w.carol(), authorize(w.carol(), order2, 10000, false), order2, 10000).statusCode()).isEqualTo(200);
            s = summary(w);
            assertThat(s.get("total").asLong()).isEqualTo(15000);
            assertThat(s.get("count").asLong()).isEqualTo(2);
            viewer.close();
        }
    }

    @Test
    void 동시에_들어온_승인_요청도_한_번만_적립() throws Exception {
        try (World w = world()) {
            String orderId = order(w.bob(), w.broadcastId(), 3000, "");
            String key = authorize(w.bob(), orderId, 3000, false);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Integer>> jobs = new ArrayList<>();
                for (int i = 0; i < 6; i++) jobs.add(() -> confirm(w.bob(), key, orderId, 3000).statusCode());
                for (int i = 0; i < 4; i++) jobs.add(() -> webhook(w, key).statusCode());
                for (Future<Integer> f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(200);
            } finally {
                pool.shutdown();
            }
            JsonNode s = summary(w);
            assertThat(s.get("total").asLong()).isEqualTo(3000);
            assertThat(s.get("count").asLong()).isEqualTo(1);
        }
    }

    @Test
    void 금액을_바꿔_승인을_요청하면_확정하지_않음() throws Exception {
        try (World w = world()) {
            String orderId = order(w.bob(), w.broadcastId(), 50000, "");
            String key = authorize(w.bob(), orderId, 50000, false);
            // successUrl 의 amount 를 1000 으로 고쳐서 보냄
            var tampered = confirm(w.bob(), key, orderId, 1000);
            assertThat(tampered.statusCode()).isEqualTo(400);
            JsonNode d = json(w.bob().get("/api/donations/" + orderId));
            assertThat(d.get("status").asString()).isEqualTo("FAILED");
            assertThat(d.get("failureCode").asString()).isEqualTo("AMOUNT_MISMATCH");
            // 실패한 주문은 원래 금액으로 다시 보내도 확정되지 않음
            assertThat(confirm(w.bob(), key, orderId, 50000).statusCode()).isEqualTo(409);
            assertThat(summary(w).get("total").asLong()).isZero();

            // PG 쪽 금액이 주문과 다른 경우 (결제창 인증 금액 변조) → PG가 승인 거절
            String order2 = order(w.bob(), w.broadcastId(), 50000, "");
            String key2 = authorize(w.bob(), order2, 1000, false);
            assertThat(confirm(w.bob(), key2, order2, 50000).statusCode()).isEqualTo(400);
            assertThat(json(w.bob().get("/api/donations/" + order2)).get("status").asString()).isEqualTo("FAILED");
            assertThat(summary(w).get("total").asLong()).isZero();
        }
    }

    @Test
    void 결제_실패와_취소() throws Exception {
        try (World w = world()) {
            // 사용자가 결제창을 닫음 → CANCELED
            String canceled = order(w.bob(), w.broadcastId(), 1000, "");
            var res = w.bob().postJson("/api/donations/" + canceled + "/abort", "{\"code\":\"PAY_PROCESS_CANCELED\",\"message\":\"취소\"}");
            assertThat(json(res).get("status").asString()).isEqualTo("CANCELED");

            // 결제창에서 실패 → FAILED
            String failed = order(w.bob(), w.broadcastId(), 1000, "");
            res = w.bob().postJson("/api/donations/" + failed + "/abort", "{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"카드 거절\"}");
            assertThat(json(res).get("status").asString()).isEqualTo("FAILED");
            // 남의 주문은 취소 처리할 수 없음
            String others = order(w.bob(), w.broadcastId(), 1000, "");
            assertThat(w.carol().postJson("/api/donations/" + others + "/abort", "{}").statusCode()).isEqualTo(403);

            // 결제창 인증은 됐지만 승인 단계에서 카드사 거절 → FAILED
            String rejected = order(w.bob(), w.broadcastId(), 2000, "");
            String key = authorize(w.bob(), rejected, 2000, true);
            assertThat(confirm(w.bob(), key, rejected, 2000).statusCode()).isEqualTo(400);
            JsonNode d = json(w.bob().get("/api/donations/" + rejected));
            assertThat(d.get("status").asString()).isEqualTo("FAILED");
            assertThat(d.get("failureCode").asString()).isEqualTo("REJECT_CARD_PAYMENT");

            // 확정된 주문은 화면이 실패라고 보내도 바뀌지 않음
            String paid = order(w.bob(), w.broadcastId(), 1000, "");
            assertThat(confirm(w.bob(), authorize(w.bob(), paid, 1000, false), paid, 1000).statusCode()).isEqualTo(200);
            res = w.bob().postJson("/api/donations/" + paid + "/abort", "{\"code\":\"PAY_PROCESS_CANCELED\"}");
            assertThat(json(res).get("status").asString()).isEqualTo("PAID");

            JsonNode s = summary(w);
            assertThat(s.get("total").asLong()).isEqualTo(1000);
            assertThat(s.get("count").asLong()).isEqualTo(1);
        }
    }

    @Test
    void 웹훅은_PG에_다시_조회한_결과만_반영() throws Exception {
        try (World w = world()) {
            // 모르는 결제 키 → 무시 (200)
            assertThat(webhook(w, "fake_does_not_exist").statusCode()).isEqualTo(200);
            // 승인 전(결제창 인증만 된 상태)에 DONE 이라고 주장하는 웹훅 → PG 조회 결과가 IN_PROGRESS 라 확정 안 됨
            String orderId = order(w.bob(), w.broadcastId(), 7000, "");
            String key = authorize(w.bob(), orderId, 7000, false);
            assertThat(webhook(w, key).statusCode()).isEqualTo(200);
            assertThat(json(w.bob().get("/api/donations/" + orderId)).get("status").asString()).isEqualTo("PENDING");

            assertThat(confirm(w.bob(), key, orderId, 7000).statusCode()).isEqualTo(200);
            assertThat(summary(w).get("total").asLong()).isEqualTo(7000);

            // PG에서 결제가 취소됨 → 웹훅 → 환불 처리, 합계에서 빠짐
            FakePaymentGateway pg = (FakePaymentGateway) w.app().getBean(PaymentGateway.class);
            pg.cancel(key);
            assertThat(webhook(w, key).statusCode()).isEqualTo(200);
            assertThat(json(w.bob().get("/api/donations/" + orderId)).get("status").asString()).isEqualTo("REFUNDED");
            assertThat(summary(w).get("total").asLong()).isZero();
            // 내 주문 상세는 본인만
            assertThat(w.carol().get("/api/donations/" + orderId).statusCode()).isEqualTo(403);
        }
    }
}
