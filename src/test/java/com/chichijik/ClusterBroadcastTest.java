package com.chichijik;

import static com.chichijik.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

import com.chichijik.ApiClient.Socket;

/**
 * 5단계 cluster 프로필: 송출자와 시청자가 서로 다른 서버(A·B)에 붙어도 방송이 동작합니다.
 * 각 서버는 자기 WebSocket 연결만 들고, 방송 메시지는 Redis 로 중계됩니다(ADR-0006).
 */
@Tag("cluster")
class ClusterBroadcastTest {

    static final int MAX_VIEWERS = 3;

    @TempDir
    static Path tempDir;

    static Cluster cluster;
    static final AtomicInteger seq = new AtomicInteger();

    @BeforeAll
    static void startCluster() throws Exception {
        cluster = Cluster.start(tempDir, "--app.payment.provider=fake", "--app.live.max-viewers=" + MAX_VIEWERS,
                BroadcastFlowTest.GRACE, BroadcastFlowTest.SWEEP);
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) cluster.close();
    }

    static String unique(String prefix) {
        return prefix + "_" + seq.incrementAndGet();
    }

    /** A 에서 방송을 시작하고 송출자를 A 에 연결 */
    record Live(ApiClient owner, long id, Socket host) {
    }

    static Live liveOnA() throws Exception {
        ApiClient owner = ApiClient.signUp(cluster.a, unique("host"), "스트리머" + seq.get());
        long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"A에서 송출\"}")).get("id").asLong();
        Socket host = owner.socket("/ws/broadcasts/" + id);
        host.send("{\"type\":\"join\",\"role\":\"host\"}");
        host.next("viewers");
        return new Live(owner, id, host);
    }

    static Socket viewer(String base, long id) throws Exception {
        Socket v = ApiClient.anonymous(base).socket("/ws/broadcasts/" + id);
        v.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        return v;
    }

    @Test
    void 송출자는_A_시청자는_B에_붙어도_입장과_시그널이_오간다() throws Exception {
        Live live = liveOnA();
        Socket viewerOnB = viewer(cluster.b, live.id());

        String viewerId = live.host().next("viewer-joined").get("viewerId").asString();
        live.host().send("{\"type\":\"signal\",\"to\":\"" + viewerId + "\",\"data\":{\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0\"}}}");
        assertThat(viewerOnB.next("signal").get("data").get("sdp").get("type").asString()).isEqualTo("offer");

        viewerOnB.send("{\"type\":\"signal\",\"data\":{\"sdp\":{\"type\":\"answer\",\"sdp\":\"v=0\"}}}");
        var answer = live.host().next("signal");
        assertThat(answer.get("from").asString()).isEqualTo(viewerId);
        assertThat(answer.get("data").get("sdp").get("type").asString()).isEqualTo("answer");
    }

    @Test
    void 시청자_수는_두_서버에_붙은_시청자의_합계로_모든_연결에_전달된다() throws Exception {
        Live live = liveOnA();
        Socket onA = viewer(cluster.a, live.id());
        assertThat(onA.next("viewers").get("count").asInt()).isEqualTo(1);

        Socket onB = viewer(cluster.b, live.id());
        assertThat(onB.next("viewers").get("count").asInt()).isEqualTo(2);
        assertThat(onA.nextWhere("viewers", m -> m.get("count").asInt() == 2)).isNotNull();
        assertThat(live.host().nextWhere("viewers", m -> m.get("count").asInt() == 2)).isNotNull();
        for (String server : List.of(cluster.a, cluster.b)) {
            assertThat(json(ApiClient.anonymous(server).get("/api/broadcasts/" + live.id())).get("viewerCount").asInt())
                    .isEqualTo(2);
        }

        onB.close();
        assertThat(onA.nextWhere("viewers", m -> m.get("count").asInt() == 1)).isNotNull();
        assertThat(live.host().next("viewer-left")).isNotNull();
    }

    @Test
    void B로_쓴_댓글_B에서_확정된_후원_B로_바꾼_제목이_A의_송출자와_시청자에게_전달된다() throws Exception {
        Live live = liveOnA();
        Socket viewerOnA = viewer(cluster.a, live.id());
        viewerOnA.next("viewers");

        ApiClient fanOnB = ApiClient.signUp(cluster.b, unique("fan"), "팬" + seq.get());
        var comment = fanOnB.postJson("/api/broadcasts/" + live.id() + "/comments", "{\"content\":\"B에서 인사\"}");
        assertThat(comment.statusCode()).as(comment.body()).isEqualTo(201);
        for (Socket s : List.of(live.host(), viewerOnA)) {
            assertThat(s.next("comment").get("comment").get("content").asString()).isEqualTo("B에서 인사");
        }

        var ordered = fanOnB.postJson("/api/donations",
                "{\"targetType\":\"broadcast\",\"targetId\":" + live.id() + ",\"amount\":3000,\"message\":\"화이팅\"}");
        assertThat(ordered.statusCode()).as(ordered.body()).isEqualTo(201);
        String orderId = json(ordered).get("orderId").asString();
        String key = json(fanOnB.postJson("/api/fake-pg/authorize",
                "{\"orderId\":\"" + orderId + "\",\"amount\":3000,\"reject\":false}")).get("paymentKey").asString();
        var confirmed = fanOnB.postJson("/api/donations/confirm",
                "{\"paymentKey\":\"" + key + "\",\"orderId\":\"" + orderId + "\",\"amount\":3000}");
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        for (Socket s : List.of(live.host(), viewerOnA)) {
            var pushed = s.next("donation");
            assertThat(pushed.get("total").asLong()).isEqualTo(3000);
            assertThat(pushed.get("count").asLong()).isEqualTo(1);
        }

        var renamed = live.owner().on(cluster.b).patchJson("/api/broadcasts/" + live.id(), "{\"title\":\"B에서 바꾼 제목\"}");
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        for (Socket s : List.of(live.host(), viewerOnA)) {
            assertThat(s.next("title").get("title").asString()).isEqualTo("B에서 바꾼 제목");
        }
    }

    @Test
    void 방송을_종료하면_두_서버의_모든_연결이_종료_알림을_정확히_한_번_받는다() throws Exception {
        Live live = liveOnA();
        Socket onA = viewer(cluster.a, live.id());
        Socket onB = viewer(cluster.b, live.id());
        onB.nextWhere("viewers", m -> m.get("count").asInt() == 2);

        // 종료 요청은 B 로 (송출자 연결은 A 에 있음)
        var end = live.owner().on(cluster.b).post("/api/broadcasts/" + live.id() + "/end");
        assertThat(end.statusCode()).as(end.body()).isEqualTo(200);

        for (Socket s : List.of(live.host(), onA, onB)) assertThat(s.next("ended")).isNotNull();
        for (Socket s : List.of(live.host(), onA, onB)) assertThat(s.countWithin("ended", Duration.ofSeconds(1))).isZero();
        for (String server : List.of(cluster.a, cluster.b)) {
            var b = json(ApiClient.anonymous(server).get("/api/broadcasts/" + live.id()));
            assertThat(b.get("status").asString()).isEqualTo("ended");
        }
    }

    @Test
    void 송출자가_A에서_끊기고_유예_안에_B로_다시_붙으면_방송이_이어지고_시청자는_종료_알림을_받지_않는다() throws Exception {
        Live live = liveOnA();
        Socket viewerOnB = viewer(cluster.b, live.id());
        String viewerId = viewerOnB.next("joined").get("viewerId").asString();
        live.host().next("viewer-joined");

        // A 의 송출 연결이 끊김 → 어느 서버에서 봐도 방송 중
        live.host().close();
        for (String server : List.of(cluster.a, cluster.b)) {
            assertThat(json(ApiClient.anonymous(server).get("/api/broadcasts/" + live.id())).get("status").asString())
                    .isEqualTo("live");
        }

        // 같은 로그인 세션으로 B 에 다시 붙음 → 지금 있는 시청자를 다시 알려 줌
        Socket hostOnB = live.owner().on(cluster.b).socket("/ws/broadcasts/" + live.id());
        hostOnB.send("{\"type\":\"join\",\"role\":\"host\"}");
        assertThat(hostOnB.next("viewer-joined").get("viewerId").asString()).isEqualTo(viewerId);

        Thread.sleep(BroadcastFlowTest.GRACE_MS + 1_000);
        for (String server : List.of(cluster.a, cluster.b)) {
            assertThat(json(ApiClient.anonymous(server).get("/api/broadcasts/" + live.id())).get("status").asString())
                    .isEqualTo("live");
        }
        assertThat(viewerOnB.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));

        // 시청자도 B 에서 비정상으로 끊긴 뒤 A 로 같은 viewerId 를 들고 이어 붙음: 시청자 수 그대로, 송출자에게 새 입장 알림 없음
        hostOnB.inbox.clear();
        viewerOnB.drop();
        Socket viewerOnA = ApiClient.anonymous(cluster.a).socket("/ws/broadcasts/" + live.id());
        viewerOnA.send("{\"type\":\"join\",\"role\":\"viewer\",\"viewerId\":\"" + viewerId + "\"}");
        var resumed = viewerOnA.next("joined");
        assertThat(resumed.get("resumed").asBoolean()).isTrue();
        assertThat(viewerOnA.next("viewers").get("count").asInt()).isEqualTo(1);
        hostOnB.send("{\"type\":\"signal\",\"to\":\"" + viewerId + "\",\"data\":{\"candidate\":\"c1\"}}");
        assertThat(viewerOnA.next("signal").get("data").get("candidate").asString()).isEqualTo("c1");
        Thread.sleep(BroadcastFlowTest.GRACE_MS + 1_000); // 유예가 지나도 이어 붙은 시청자는 퇴장되지 않음
        assertThat(hostOnB.inbox).noneMatch(m -> m.get("type").asString().startsWith("viewer-"));
        assertThat(json(ApiClient.anonymous(cluster.b).get("/api/broadcasts/" + live.id())).get("viewerCount").asInt())
                .isEqualTo(1);

        // 송출자가 돌아오지 않으면 유예 뒤 종료 알림이 정확히 한 번 (두 서버가 모두 정리 작업을 돌려도)
        hostOnB.close();
        assertThat(viewerOnA.next("ended")).isNotNull();
        assertThat(viewerOnA.countWithin("ended", Duration.ofSeconds(1))).isZero();
    }

    @Test
    void 시청자_상한은_방송_전체에_적용되고_A와_B로_동시에_들어와도_넘지_않는다() throws Exception {
        Live live = liveOnA();
        int tries = MAX_VIEWERS * 4;
        ExecutorService pool = Executors.newFixedThreadPool(tries);
        try {
            // 입장이 거절된 연결은 full 을 받는다 (입장 처리 중 다른 시청자의 시청자 수를 먼저 받을 수는 있음)
            List<Callable<Boolean>> joins = new ArrayList<>();
            for (int i = 0; i < tries; i++) {
                String server = i % 2 == 0 ? cluster.a : cluster.b;
                joins.add(() -> viewer(server, live.id()).countWithin("full", Duration.ofSeconds(3)) > 0);
            }
            List<Boolean> rejected = new ArrayList<>();
            for (Future<Boolean> f : pool.invokeAll(joins)) rejected.add(f.get());
            assertThat(rejected).filteredOn(r -> !r).hasSize(MAX_VIEWERS);
        } finally {
            pool.shutdown();
        }
        assertThat(json(ApiClient.anonymous(cluster.b).get("/api/broadcasts/" + live.id())).get("viewerCount").asInt())
                .isEqualTo(MAX_VIEWERS);
    }
}
