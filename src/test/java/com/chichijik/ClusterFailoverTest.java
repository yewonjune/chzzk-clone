package com.chichijik;

import static com.chichijik.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chichijik.ApiClient.Socket;

/**
 * 5단계 cluster 프로필: 서버 한 대가 내려가도(스케일 인, 강제 종료) 방송이 이어집니다.
 * 각 서버는 하트비트를 Redis 에 갱신하고, 정리 작업은 하트비트가 사라진 서버 소속 기록을 정리합니다.
 *
 * 이 클래스의 테스트는 내려갈 서버를 테스트마다 따로 띄우고, 살아남는 쪽은 {@code cluster.b} 를 씁니다.
 * (다른 cluster 테스트 클래스의 서버는 건드리지 않음)
 */
@Tag("cluster")
class ClusterFailoverTest {

    static final int MAX_VIEWERS = 3;
    /** 테스트에서는 하트비트를 짧게: 0.5초마다 갱신, 3초 동안 갱신이 없으면 그 서버는 죽은 것으로 봄 */
    static final long HEARTBEAT_TTL_MS = 3_000;
    /** 하트비트 TTL·재연결 유예·정리 주기를 모두 지나고도 남는 시간 */
    static final Duration LONG_WAIT = Duration.ofSeconds(15);

    @TempDir
    static Path tempDir;

    static Cluster cluster;
    static final AtomicInteger seq = new AtomicInteger();

    @BeforeAll
    static void startCluster() throws Exception {
        cluster = Cluster.start(tempDir, "--app.payment.provider=fake", "--app.live.max-viewers=" + MAX_VIEWERS,
                BroadcastFlowTest.GRACE, BroadcastFlowTest.SWEEP,
                "--app.live.heartbeat-interval=500ms", "--app.live.heartbeat-ttl=" + HEARTBEAT_TTL_MS + "ms");
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) cluster.close();
    }

    static String unique(String prefix) {
        return prefix + "_" + seq.incrementAndGet();
    }

    record Live(ApiClient owner, long id, Socket host) {
    }

    /** base 서버에서 방송을 시작하고 송출자를 같은 서버에 연결 */
    static Live liveOn(String base) throws Exception {
        ApiClient owner = ApiClient.signUp(base, unique("host"), "스트리머" + seq.get());
        long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"서버가 내려가도\"}")).get("id").asLong();
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

    static int viewerCount(String base, long id) throws Exception {
        return json(ApiClient.anonymous(base).get("/api/broadcasts/" + id)).get("viewerCount").asInt();
    }

    static String status(String base, long id) throws Exception {
        return json(ApiClient.anonymous(base).get("/api/broadcasts/" + id)).get("status").asString();
    }

    /** 서버가 내려갈 때 받은 viewerId 를 들고 다른 서버로 다시 붙는 시청자 (클라이언트 JS 의 재연결과 같음) */
    static Socket resumeOn(String base, long id, String viewerId) throws Exception {
        Socket v = ApiClient.anonymous(base).socket("/ws/broadcasts/" + id);
        v.send("{\"type\":\"join\",\"role\":\"viewer\",\"viewerId\":\"" + viewerId + "\"}");
        return v;
    }

    static boolean received(Socket s, String type) {
        return s.inbox.stream().anyMatch(m -> type.equals(m.get("type").asString()));
    }

    @Test
    void 송출자가_붙은_서버_A를_종료하면_송출자와_시청자가_B로_재연결해_같은_방송_같은_시청자로_이어진다() throws Exception {
        Cluster.Server a = cluster.startServer();
        Live live = liveOn(a.base());
        Socket viewerOnA = viewer(a.base(), live.id());
        String viewerId = viewerOnA.next("joined").get("viewerId").asString();
        Socket viewerOnB = viewer(cluster.b, live.id());
        viewerOnB.next("joined");
        live.host().nextWhere("viewers", m -> m.get("count").asInt() == 2);

        a.stop(); // 스케일 인: A 의 연결은 4001("다른 서버로 재연결")로 닫힘
        assertThat(live.host().awaitCloseCode()).isEqualTo(4001);
        assertThat(viewerOnA.awaitCloseCode()).isEqualTo(4001);

        // 송출자는 같은 로그인 세션으로 B 에 다시 붙고, 지금 있는 시청자(재연결을 기다리는 A 의 시청자 포함)를 다시 알림 받음
        Socket hostOnB = live.owner().on(cluster.b).socket("/ws/broadcasts/" + live.id());
        hostOnB.send("{\"type\":\"join\",\"role\":\"host\"}");
        assertThat(hostOnB.next("viewer-joined")).isNotNull();
        assertThat(hostOnB.next("viewer-joined")).isNotNull();

        // A 의 시청자는 이전 viewerId 로 B 에 다시 붙음: 같은 시청자, 시청자 수 그대로
        Socket resumed = resumeOn(cluster.b, live.id(), viewerId);
        var joined = resumed.next("joined");
        assertThat(joined.get("viewerId").asString()).isEqualTo(viewerId);
        assertThat(joined.get("resumed").asBoolean()).isTrue();
        assertThat(resumed.next("viewers").get("count").asInt()).isEqualTo(2);
        hostOnB.send("{\"type\":\"signal\",\"to\":\"" + viewerId + "\",\"data\":{\"candidate\":\"c1\"}}");
        assertThat(resumed.next("signal").get("data").get("candidate").asString()).isEqualTo("c1");

        // 재연결 유예와 A 의 하트비트 TTL 이 모두 지나도 방송 중, 시청자 수 그대로, 누구도 퇴장·종료되지 않음
        Thread.sleep(Math.max(BroadcastFlowTest.GRACE_MS, HEARTBEAT_TTL_MS) + 1_500);
        assertThat(status(cluster.b, live.id())).isEqualTo("live");
        assertThat(viewerCount(cluster.b, live.id())).isEqualTo(2);
        assertThat(received(hostOnB, "viewer-left")).isFalse();
        for (Socket s : List.of(hostOnB, resumed, viewerOnB)) assertThat(received(s, "ended")).isFalse();
    }

    @Test
    void 서버_A가_내려간_뒤_송출자가_돌아오지_않으면_유예_뒤_종료되고_모든_연결이_종료_알림을_정확히_한_번_받는다() throws Exception {
        Cluster.Server a = cluster.startServer();
        Live live = liveOn(a.base());
        Socket viewerOnA = viewer(a.base(), live.id());
        String viewerId = viewerOnA.next("joined").get("viewerId").asString();
        Socket viewerOnB = viewer(cluster.b, live.id());
        viewerOnB.next("joined");

        a.stop();
        assertThat(live.host().awaitCloseCode()).isEqualTo(4001);
        Socket resumed = resumeOn(cluster.b, live.id(), viewerId);
        assertThat(resumed.next("joined").get("resumed").asBoolean()).isTrue();
        assertThat(status(cluster.b, live.id())).isEqualTo("live"); // 바로 종료하지 않음 (재연결 유예)

        for (Socket s : List.of(resumed, viewerOnB)) assertThat(s.next("ended", LONG_WAIT)).isNotNull();
        for (Socket s : List.of(resumed, viewerOnB)) assertThat(s.countWithin("ended", Duration.ofSeconds(1))).isZero();
        assertThat(status(cluster.b, live.id())).isEqualTo("ended");
        assertThat(received(live.host(), "ended")).isFalse(); // A 의 송출 연결은 4001 로 닫혔을 뿐
    }

    @Test
    void 새_서버가_기동해도_다른_서버에서_진행_중인_방송은_종료되지_않고_기록도_정리되지_않는다() throws Exception {
        Cluster.Server a = cluster.startServer();
        Live live = liveOn(a.base());
        Socket viewerOnA = viewer(a.base(), live.id());
        viewerOnA.next("joined");

        Cluster.Server c = cluster.startServer(); // 스케일 아웃
        Thread.sleep(Math.max(BroadcastFlowTest.GRACE_MS, HEARTBEAT_TTL_MS) + 1_500);

        assertThat(status(c.base(), live.id())).isEqualTo("live");
        assertThat(viewerCount(c.base(), live.id())).isEqualTo(1);
        assertThat(received(live.host(), "viewer-left")).isFalse();
        assertThat(received(viewerOnA, "ended")).isFalse();
        // 새 서버로 들어온 시청자도 A 의 송출자와 이어짐
        Socket viewerOnC = viewer(c.base(), live.id());
        String viewerId = viewerOnC.next("joined").get("viewerId").asString();
        assertThat(live.host().nextWhere("viewer-joined", m -> m.get("viewerId").asString().equals(viewerId))).isNotNull();
        c.stop();
    }

    @Test
    void 새_서버가_기동해도_송출자가_아직_붙지_않은_방송은_시작_후_30초_규칙을_그대로_받는다() throws Exception {
        ApiClient owner = ApiClient.signUp(cluster.b, unique("host"), "스트리머" + seq.get());
        long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"막 시작한 방송\"}")).get("id").asLong();

        Cluster.Server c = cluster.startServer(); // 송출 화면이 붙기 전에 스케일 아웃
        // 재연결 유예(2초)가 지나도 종료되지 않음: 시작 후 30초 안에 송출자가 붙을 수 있어야 함
        Thread.sleep(BroadcastFlowTest.GRACE_MS + 1_500);
        assertThat(status(c.base(), id)).isEqualTo("live");

        Socket host = owner.socket("/ws/broadcasts/" + id);
        host.send("{\"type\":\"join\",\"role\":\"host\"}");
        assertThat(host.next("viewers")).isNotNull();
        assertThat(received(host, "error")).isFalse();
        c.stop();
    }

    @Test
    void 강제로_죽은_서버의_시청자_기록은_하트비트_TTL_뒤_정리되어_시청자_수와_상한이_돌아오고_그_서버의_송출자는_유예_뒤_종료된다() throws Exception {
        Live live = liveOn(cluster.b);
        Cluster.ProcessServer doomed = cluster.startProcess();

        // 죽을 서버에 시청자 2명, B 에 1명 → 상한(3)에 참
        viewer(doomed.base(), live.id());
        viewer(doomed.base(), live.id());
        Socket onB = viewer(cluster.b, live.id());
        live.host().nextWhere("viewers", m -> m.get("count").asInt() == 3);
        assertThat(viewer(cluster.b, live.id()).next("full")).isNotNull();

        // 죽을 서버에 송출자가 붙은 방송도 하나 (시청자는 B)
        Live doomedLive = liveOn(doomed.base());
        Socket watchingDoomed = viewer(cluster.b, doomedLive.id());
        watchingDoomed.next("joined");

        doomed.kill();
        // 하트비트 TTL 전에는 그대로 (끊긴 시청자가 다른 서버로 이어 붙을 수 있도록)
        assertThat(viewerCount(cluster.b, live.id())).isEqualTo(3);

        // TTL 뒤 죽은 서버 소속 시청자 2명이 퇴장 처리되어 시청자 수와 상한이 돌아옴
        assertThat(live.host().nextWhere("viewers", m -> m.get("count").asInt() == 1, LONG_WAIT)).isNotNull();
        assertThat(onB.nextWhere("viewers", m -> m.get("count").asInt() == 1)).isNotNull();
        assertThat(viewerCount(cluster.b, live.id())).isEqualTo(1);
        viewer(cluster.b, live.id()).next("joined");
        viewer(cluster.b, live.id()).next("joined");
        assertThat(viewer(cluster.b, live.id()).next("full")).isNotNull();
        assertThat(status(cluster.b, live.id())).isEqualTo("live");

        // 죽은 서버에 붙어 있던 송출자는 끊김으로 바뀌고, 돌아오지 않으면 유예 뒤 종료 알림이 정확히 한 번
        assertThat(watchingDoomed.next("ended", LONG_WAIT)).isNotNull();
        assertThat(watchingDoomed.countWithin("ended", Duration.ofSeconds(1))).isZero();
        assertThat(status(cluster.b, doomedLive.id())).isEqualTo("ended");
    }
}
