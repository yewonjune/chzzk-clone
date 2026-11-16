package com.chzzk;

import static com.chzzk.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chzzk.ApiClient.FilePart;

import tools.jackson.databind.JsonNode;

/**
 * 5단계 cluster 프로필: 서버 A·B 가 같은 기록(PostgreSQL)·로그인 세션(Redis)·업로드 폴더·가짜 PG 결제 기록을 공유합니다.
 * 사용자는 로드밸런서 뒤의 브라우저처럼 같은 쿠키로 A·B 에 번갈아 요청합니다.
 */
@Tag("cluster")
class ClusterSharingTest {

    @TempDir
    static Path tempDir;

    static Cluster cluster;
    static final AtomicInteger seq = new AtomicInteger();

    @BeforeAll
    static void startCluster() throws Exception {
        cluster = Cluster.start(tempDir, "--app.payment.provider=fake");
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) cluster.close();
    }

    /** 테스트끼리 같은 DB 를 쓰므로 아이디가 겹치지 않게 */
    static String unique(String prefix) {
        return prefix + "_" + seq.incrementAndGet();
    }

    @Test
    void A에서_로그인하면_B에서도_로그인_상태이고_로그아웃도_함께() throws Exception {
        String username = unique("alice");
        ApiClient onA = ApiClient.signUp(cluster.a, username, "앨리스" + seq.get());
        ApiClient onB = onA.on(cluster.b);

        onB.refreshMe();
        assertThat(onB.user).isNotNull();
        assertThat(onB.user.get("username").asString()).isEqualTo(username);

        // A 에서 받은 CSRF 토큰으로 B 에 쓰기 요청 (댓글)
        long replayId = externalReplay(onA);
        var comment = onB.postJson("/api/replays/" + replayId + "/comments", "{\"content\":\"B에서 씀\"}");
        assertThat(comment.statusCode()).as(comment.body()).isEqualTo(201);
        JsonNode page = json(ApiClient.anonymous(cluster.a).get("/api/replays/" + replayId + "/comments"));
        assertThat(page.toString()).contains("B에서 씀");

        assertThat(onA.post("/api/auth/logout").statusCode()).isEqualTo(204);
        onB.refreshMe();
        assertThat(onB.user == null || onB.user.isNull()).isTrue();
        assertThat(onB.postJson("/api/replays/" + replayId + "/comments", "{\"content\":\"로그아웃 뒤\"}").statusCode())
                .isIn(401, 403);
    }

    @Test
    void A에_올린_업로드_영상을_B에서_목록으로_보고_Range로_재생() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("samples/sample.mp4"));
        ApiClient streamer = ApiClient.signUp(cluster.a, unique("up"), "업로더" + seq.get());
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("title", "A에 올린 영상");
        form.put("durationSec", "5");
        form.put("video", new FilePart("sample.mp4", "video/mp4", sample));
        var created = streamer.postMultipart("/api/replays", form);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long id = json(created).get("id").asLong();
        String videoUrl = json(created).get("videoUrl").asString();

        ApiClient guestOnB = ApiClient.anonymous(cluster.b);
        JsonNode list = json(guestOnB.get("/api/replays"));
        assertThat(list.findValues("id").stream().map(JsonNode::asLong)).contains(id);

        HttpResponse<byte[]> range = guestOnB.http.send(HttpRequest.newBuilder(URI.create(cluster.b + videoUrl))
                .header("Range", "bytes=100-199").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.body()).isEqualTo(Arrays.copyOfRange(sample, 100, 200));
    }

    @Test
    void A에서_만든_후원을_가짜_PG로_결제하고_B로_승인_확인하면_확정되고_중복_통지는_한_번만() throws Exception {
        ApiClient streamer = ApiClient.signUp(cluster.a, unique("st"), "스트리머" + seq.get());
        long replayId = externalReplay(streamer);
        ApiClient donorA = ApiClient.signUp(cluster.a, unique("donor"), "후원자" + seq.get());
        ApiClient donorB = donorA.on(cluster.b);

        // A: 주문 + 내장 테스트 결제창에서 결제 (결제 기록은 A 가 남김)
        var ordered = donorA.postJson("/api/donations",
                "{\"targetType\":\"replay\",\"targetId\":" + replayId + ",\"amount\":5000,\"message\":\"응원\"}");
        assertThat(ordered.statusCode()).as(ordered.body()).isEqualTo(201);
        String orderId = json(ordered).get("orderId").asString();
        var authorized = donorA.postJson("/api/fake-pg/authorize",
                "{\"orderId\":\"" + orderId + "\",\"amount\":5000,\"reject\":false}");
        assertThat(authorized.statusCode()).isEqualTo(200);
        String key = json(authorized).get("paymentKey").asString();

        // B: 결과 화면의 승인 확인 → 확정
        String confirmBody = "{\"paymentKey\":\"" + key + "\",\"orderId\":\"" + orderId + "\",\"amount\":5000}";
        var confirmed = donorB.postJson("/api/donations/confirm", confirmBody);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(json(confirmed).get("status").asString()).isEqualTo("PAID");

        // 같은 결제의 승인 통지가 A·B 로 동시에 여러 번 와도 한 번만 반영
        String webhookBody = "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"" + key
                + "\",\"status\":\"DONE\",\"totalAmount\":99999999}}";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (ApiClient c : List.of(donorA, donorB)) {
                for (int i = 0; i < 3; i++) jobs.add(() -> c.postJson("/api/donations/confirm", confirmBody).statusCode());
                for (int i = 0; i < 2; i++) jobs.add(() -> ApiClient.anonymous(c.base)
                        .postJsonWithoutCsrf("/api/payments/webhook", webhookBody).statusCode());
            }
            for (Future<Integer> f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdown();
        }
        for (String server : List.of(cluster.a, cluster.b)) {
            JsonNode s = json(ApiClient.anonymous(server).get("/api/replays/" + replayId + "/donations"));
            assertThat(s.get("total").asLong()).isEqualTo(5000);
            assertThat(s.get("count").asLong()).isEqualTo(1);
        }
    }

    @Test
    void Redis가_응답하지_않는_동안_readiness가_내려가고_돌아오면_다시_UP() throws Exception {
        for (String server : List.of(cluster.a, cluster.b)) {
            assertThat(readiness(server).statusCode()).isEqualTo(200);
        }
        var docker = cluster.redis.getDockerClient();
        docker.pauseContainerCmd(cluster.redis.getContainerId()).exec();
        try {
            HttpResponse<String> down = readiness(cluster.a);
            assertThat(down.statusCode()).as(down.body()).isEqualTo(503);
            assertThat(json(down).get("status").asString()).isEqualTo("DOWN");
            // liveness 는 외부 의존과 무관: 재시작시키지 않음
            assertThat(ApiClient.anonymousWithoutSession(cluster.a).get("/actuator/health/liveness").statusCode())
                    .isEqualTo(200);
        } finally {
            docker.unpauseContainerCmd(cluster.redis.getContainerId()).exec();
        }
        assertThat(readiness(cluster.a).statusCode()).isEqualTo(200);
    }

    static HttpResponse<String> readiness(String server) throws Exception {
        return ApiClient.anonymousWithoutSession(server).get("/actuator/health/readiness");
    }

    /** 외부 영상 주소로 다시보기 하나 등록 (댓글·후원 대상) */
    static long externalReplay(ApiClient owner) throws Exception {
        var res = owner.postMultipart("/api/replays", Map.of("title", "외부 영상", "videoUrl", "https://example.com/a.mp4"));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return json(res).get("id").asLong();
    }
}
