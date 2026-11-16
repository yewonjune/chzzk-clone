package com.chzzk;

import static com.chzzk.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chzzk.ApiClient.Socket;

import tools.jackson.databind.JsonNode;

/**
 * 2단계 방송 기능 유지 + 3단계 소유자 송출 제어:
 * 시작 → 목록(방송 중) → 소유자만 송출자 연결 → 시그널링 → 시청자 상한 → 방송 중 댓글 실시간 전달
 * → 송출이 끊겨도 재연결 유예 안에 돌아오면 이어짐, 유예가 지나면 종료 → 재시작해도 바로 종료하지 않음.
 */
class BroadcastFlowTest {

    /** 테스트에서는 재연결 유예와 정리 주기를 짧게 둡니다. */
    static final long GRACE_MS = 2_000;
    static final String GRACE = "--app.live.reconnect-grace=" + GRACE_MS + "ms";
    static final String SWEEP = "--app.live.sweep-interval-ms=200";

    @TempDir
    Path dataDir;

    @Test
    void 소유자만_송출_시그널링_댓글푸시_시청자상한_끊긴뒤_유예안에_돌아오면_이어지고_유예가_지나면_종료() throws Exception {
        try (var app = ApiClient.startServer(dataDir, "--app.live.max-viewers=1", GRACE, SWEEP)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "streamer1", "스트리머");
            ApiClient other = ApiClient.signUp(base, "viewer01", "시청자");
            ApiClient guest = ApiClient.anonymous(base);

            // 검증: 제목 필수
            assertThat(owner.postJson("/api/broadcasts", "{\"title\":\"  \"}").statusCode()).isEqualTo(400);

            // 시작: 시작한 사용자가 소유자(스트리머)
            JsonNode b = json(owner.postJson("/api/broadcasts", "{\"title\":\"퇴근길 방송\"}"));
            long id = b.get("id").asLong();
            assertThat(b.get("status").asString()).isEqualTo("live");
            assertThat(b.get("streamer").asString()).isEqualTo("스트리머");
            assertThat(json(guest.get("/api/broadcasts")).get(0).get("status").asString()).isEqualTo("live");

            // 송출자 연결: 비로그인·다른 사용자는 거절, 소유자만 허용
            Socket guestHost = guest.socket("/ws/broadcasts/" + id);
            guestHost.send("{\"type\":\"join\",\"role\":\"host\"}");
            assertThat(guestHost.next("error").get("message").asString()).contains("로그인");

            Socket otherHost = other.socket("/ws/broadcasts/" + id);
            otherHost.send("{\"type\":\"join\",\"role\":\"host\"}");
            assertThat(otherHost.next("error").get("message").asString()).contains("소유자");

            // 시청은 비로그인도 가능 (시청자가 먼저 들어와 있어도 송출자 연결 시 viewer-joined)
            Socket viewer = guest.socket("/ws/broadcasts/" + id);
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(viewer.next("viewers").get("count").asInt()).isEqualTo(1);

            Socket host = owner.socket("/ws/broadcasts/" + id);
            host.send("{\"type\":\"join\",\"role\":\"host\"}");
            String viewerId = host.next("viewer-joined").get("viewerId").asString();

            // 시그널 전달
            host.send("{\"type\":\"signal\",\"to\":\"" + viewerId + "\",\"data\":{\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0\"}}}");
            assertThat(viewer.next("signal").get("data").get("sdp").get("type").asString()).isEqualTo("offer");
            viewer.send("{\"type\":\"signal\",\"data\":{\"sdp\":{\"type\":\"answer\",\"sdp\":\"v=0\"}}}");
            assertThat(host.next("signal").get("from").asString()).isEqualTo(viewerId);

            // 방송 중 댓글: REST 로 쓰면 송출자·시청자에게 실시간 전달
            assertThat(other.postJson("/api/broadcasts/" + id + "/comments", "{\"content\":\"안녕하세요!\"}").statusCode()).isEqualTo(201);
            JsonNode pushed = viewer.next("comment");
            assertThat(pushed.get("action").asString()).isEqualTo("created");
            assertThat(pushed.get("comment").get("content").asString()).isEqualTo("안녕하세요!");
            assertThat(pushed.get("comment").get("author").get("nickname").asString()).isEqualTo("시청자");
            assertThat(host.next("comment").get("comment").get("content").asString()).isEqualTo("안녕하세요!");

            // 제목 수정도 실시간 반영
            assertThat(owner.patchJson("/api/broadcasts/" + id, "{\"title\":\"제목 바꿈\"}").statusCode()).isEqualTo(200);
            assertThat(viewer.next("title").get("title").asString()).isEqualTo("제목 바꿈");

            // 상한(1명) 초과 시청자 → full
            Socket second = other.socket("/ws/broadcasts/" + id);
            second.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(second.next("full").get("max").asInt()).isEqualTo(1);

            // 방송 중에는 기록 삭제 불가 (409)
            assertThat(owner.delete("/api/broadcasts/" + id).statusCode()).isEqualTo(409);

            // 송출자 연결이 끊겨도 바로 종료되지 않음: 재연결 유예(테스트 2초) 안에 다시 붙으면 같은 방송이 이어짐
            host.close();
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("status").asString()).isEqualTo("live");
            Socket hostAgain = owner.socket("/ws/broadcasts/" + id);
            hostAgain.send("{\"type\":\"join\",\"role\":\"host\"}");
            // 다시 붙은 송출자에게 지금 있는 시청자를 다시 알려 줌 (송출 화면은 이미 P2P 가 있으면 그대로 둠)
            assertThat(hostAgain.next("viewer-joined").get("viewerId").asString()).isEqualTo(viewerId);
            Thread.sleep(GRACE_MS + 1_000);
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("status").asString()).isEqualTo("live");
            assertThat(viewer.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));

            // 다시 끊기고 유예가 지나도록 돌아오지 않으면 종료 → 시청자·송출자 알림은 한 번
            hostAgain.close();
            assertThat(viewer.next("ended")).isNotNull();
            Thread.sleep(500);
            assertThat(viewer.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));
            JsonNode ended = json(guest.get("/api/broadcasts/" + id));
            assertThat(ended.get("status").asString()).isEqualTo("ended");
            assertThat(ended.get("endedAt").isNull()).isFalse();

            // 종료된 방송: 시청 연결은 ended, 댓글은 남아 있고 계속 조회 가능
            Socket late = guest.socket("/ws/broadcasts/" + id);
            late.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(late.next("ended")).isNotNull();
            assertThat(json(guest.get("/api/broadcasts/" + id + "/comments")).get("comments").size()).isEqualTo(1);

            // 종료 후 소유자는 기록 삭제 가능 → 댓글도 함께 삭제
            assertThat(owner.delete("/api/broadcasts/" + id).statusCode()).isEqualTo(204);
            assertThat(guest.get("/api/broadcasts/" + id).statusCode()).isEqualTo(404);
            assertThat(guest.get("/api/broadcasts/" + id + "/comments").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void 재시작해도_방송중은_바로_종료되지_않고_송출이_돌아오면_이어지고_안돌아오면_유예뒤_종료() throws Exception {
        long back, gone;
        Socket oldHost, oldViewer;
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "streamer2", "방송인");
            ApiClient other = ApiClient.signUp(base, "someone", "다른사람");

            // 소유자만 종료 가능
            long a = json(owner.postJson("/api/broadcasts", "{\"title\":\"A\"}")).get("id").asLong();
            assertThat(other.post("/api/broadcasts/" + a + "/end").statusCode()).isEqualTo(403);
            var end = owner.post("/api/broadcasts/" + a + "/end");
            assertThat(end.statusCode()).isEqualTo(200);
            assertThat(json(end).get("status").asString()).isEqualTo("ended");

            back = json(owner.postJson("/api/broadcasts", "{\"title\":\"돌아올 방송\"}")).get("id").asLong();
            gone = json(owner.postJson("/api/broadcasts", "{\"title\":\"안 돌아올 방송\"}")).get("id").asLong();
            oldHost = owner.socket("/ws/broadcasts/" + back);
            oldHost.send("{\"type\":\"join\",\"role\":\"host\"}");
            owner.socket("/ws/broadcasts/" + gone).send("{\"type\":\"join\",\"role\":\"host\"}");
            oldViewer = other.socket("/ws/broadcasts/" + back);
            oldViewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            oldHost.next("viewer-joined");
        }
        // 서버가 내려가면 열린 연결을 4001("다른 서버로 재연결")로 닫음
        assertThat(oldHost.awaitCloseCode()).isEqualTo(4001);
        assertThat(oldViewer.awaitCloseCode()).isEqualTo(4001);
        assertThat(oldViewer.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));

        try (var app = ApiClient.startServer(dataDir, GRACE, SWEEP)) { // 재시작: 송출 연결은 사라졌지만 바로 종료하지 않음
            String base = ApiClient.baseUrl(app);
            ApiClient guest = ApiClient.anonymous(base);
            assertThat(json(guest.get("/api/broadcasts/" + back)).get("status").asString()).isEqualTo("live");
            assertThat(json(guest.get("/api/broadcasts/" + gone)).get("status").asString()).isEqualTo("live");
            assertThat(json(guest.get("/api/broadcasts/" + back)).get("streamer").asString()).isEqualTo("방송인");

            // 송출 화면이 다시 붙음 (기본 프로필은 세션이 메모리에 있어 재시작 뒤 다시 로그인)
            ApiClient owner2 = ApiClient.anonymous(base);
            owner2.applySession(json(owner2.postJson("/api/auth/login", "{\"username\":\"streamer2\",\"password\":\"password123\"}")));
            Socket host = owner2.socket("/ws/broadcasts/" + back);
            host.send("{\"type\":\"join\",\"role\":\"host\"}");
            Socket viewer = guest.socket("/ws/broadcasts/" + back);
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            host.next("viewer-joined");

            Thread.sleep(GRACE_MS + 1_000);
            assertThat(json(guest.get("/api/broadcasts/" + back)).get("status").asString()).isEqualTo("live");
            assertThat(json(guest.get("/api/broadcasts/" + gone)).get("status").asString()).isEqualTo("ended");
            assertThat(viewer.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));
        }
    }

    @Test
    void 시청자는_이전_viewerId로_재연결하면_같은_시청자로_이어지고_유예안에_안돌아오면_퇴장() throws Exception {
        try (var app = ApiClient.startServer(dataDir, GRACE, SWEEP)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "streamer4", "이어보기");
            ApiClient guest = ApiClient.anonymous(base);
            long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"재연결\"}")).get("id").asLong();
            Socket host = owner.socket("/ws/broadcasts/" + id);
            host.send("{\"type\":\"join\",\"role\":\"host\"}");

            // 입장하면 서버가 viewerId 를 내려줌
            Socket viewer = guest.socket("/ws/broadcasts/" + id);
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            JsonNode joined = viewer.next("joined");
            String viewerId = joined.get("viewerId").asString();
            assertThat(joined.get("resumed").asBoolean()).isFalse();
            assertThat(host.next("viewer-joined").get("viewerId").asString()).isEqualTo(viewerId);
            assertThat(host.next("viewers").get("count").asInt()).isEqualTo(1);

            // 시그널링 연결만 비정상으로 끊김 → 같은 viewerId 로 다시 붙음: 시청자 수 그대로, 송출자에게 새 입장 알림 없음
            viewer.drop();
            Socket resumed = guest.socket("/ws/broadcasts/" + id);
            resumed.send("{\"type\":\"join\",\"role\":\"viewer\",\"viewerId\":\"" + viewerId + "\"}");
            JsonNode again = resumed.next("joined");
            assertThat(again.get("viewerId").asString()).isEqualTo(viewerId);
            assertThat(again.get("resumed").asBoolean()).isTrue();
            assertThat(resumed.next("viewers").get("count").asInt()).isEqualTo(1);
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("viewerCount").asInt()).isEqualTo(1);

            // 이어 붙은 연결로 시그널이 오감
            host.send("{\"type\":\"signal\",\"to\":\"" + viewerId + "\",\"data\":{\"candidate\":\"c1\"}}");
            assertThat(resumed.next("signal").get("data").get("candidate").asString()).isEqualTo("c1");
            resumed.send("{\"type\":\"signal\",\"data\":{\"candidate\":\"c2\"}}");
            assertThat(host.next("signal").get("from").asString()).isEqualTo(viewerId);
            assertThat(host.inbox).noneMatch(m -> "viewer-joined".equals(m.get("type").asString()));

            // 집합에 없는 viewerId 면 새 시청자로 입장
            Socket stranger = guest.socket("/ws/broadcasts/" + id);
            stranger.send("{\"type\":\"join\",\"role\":\"viewer\",\"viewerId\":\"no-such-viewer\"}");
            JsonNode fresh = stranger.next("joined");
            assertThat(fresh.get("resumed").asBoolean()).isFalse();
            String strangerId = fresh.get("viewerId").asString();
            assertThat(strangerId).isNotEqualTo("no-such-viewer");
            assertThat(host.next("viewer-joined").get("viewerId").asString()).isEqualTo(strangerId);

            // 비정상으로 끊긴 뒤 유예 안에 돌아오지 않으면 퇴장 → 송출자에게 viewer-left
            stranger.drop();
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("viewerCount").asInt()).isEqualTo(2);
            Thread.sleep(GRACE_MS);
            assertThat(host.next("viewer-left").get("viewerId").asString()).isEqualTo(strangerId);
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("viewerCount").asInt()).isEqualTo(1);

            // 정상 종료(페이지를 떠남)는 바로 퇴장
            resumed.close();
            assertThat(host.next("viewer-left").get("viewerId").asString()).isEqualTo(viewerId);
        }
    }

    @Test
    void 영상이_끊겨_처음부터_다시_입장하는_시청자는_이전_자리를_바로_비우고_새_자리를_받는다() throws Exception {
        try (var app = ApiClient.startServer(dataDir, "--app.live.max-viewers=1", GRACE, SWEEP)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "streamer6", "다시입장");
            ApiClient guest = ApiClient.anonymous(base);
            long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"다시 입장\"}")).get("id").asLong();
            Socket host = owner.socket("/ws/broadcasts/" + id);
            host.send("{\"type\":\"join\",\"role\":\"host\"}");

            Socket viewer = guest.socket("/ws/broadcasts/" + id);
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            String oldId = viewer.next("joined").get("viewerId").asString();
            host.nextWhere("viewers", m -> m.get("count").asInt() == 1);

            // 비정상으로 끊기고 영상도 끊김 → 이전 viewerId 와 "처음부터" 표시로 다시 입장 (상한 1명이어도 가득 차지 않음)
            viewer.drop();
            Socket again = guest.socket("/ws/broadcasts/" + id);
            again.send("{\"type\":\"join\",\"role\":\"viewer\",\"viewerId\":\"" + oldId + "\",\"startOver\":true}");
            JsonNode joined = again.next("joined");
            assertThat(joined.get("resumed").asBoolean()).isFalse();
            String newId = joined.get("viewerId").asString();
            assertThat(newId).isNotEqualTo(oldId);
            assertThat(host.next("viewer-left").get("viewerId").asString()).isEqualTo(oldId);
            assertThat(host.next("viewer-joined").get("viewerId").asString()).isEqualTo(newId);
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("viewerCount").asInt()).isEqualTo(1);

            // 유예가 지나도 이전 자리 때문에 다시 퇴장 알림이 오거나 시청자 수가 바뀌지 않음
            assertThat(host.countWithin("viewer-left", java.time.Duration.ofMillis(GRACE_MS + 500))).isZero();
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("viewerCount").asInt()).isEqualTo(1);
        }
    }

    @Test
    void 같은_방송을_동시에_여러번_종료해도_종료는_한번만_일어나고_종료알림도_한번() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "streamer3", "동시종료");
            ApiClient guest = ApiClient.anonymous(base);
            long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"곧 끝남\"}")).get("id").asLong();

            Socket host = owner.socket("/ws/broadcasts/" + id);
            host.send("{\"type\":\"join\",\"role\":\"host\"}");
            Socket viewer = guest.socket("/ws/broadcasts/" + id);
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            host.next("viewer-joined");

            // 소유자가 종료 버튼을 여러 번(동시에) 누름
            int n = 8;
            var pool = Executors.newFixedThreadPool(n);
            var start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return owner.post("/api/broadcasts/" + id + "/end");
                }));
            }
            start.countDown();
            Set<String> endedAts = new HashSet<>();
            for (var c : calls) {
                HttpResponse<String> res = c.get();
                assertThat(res.statusCode()).isEqualTo(200);
                endedAts.add(json(res).get("endedAt").asString());
            }
            pool.shutdown();

            // 종료 시각은 하나뿐이고, 조회해도 같은 시각
            assertThat(endedAts).hasSize(1);
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("endedAt").asString()).isEqualTo(endedAts.iterator().next());

            // 이미 종료된 방송을 다시 종료해도 결과는 그대로
            JsonNode again = json(owner.post("/api/broadcasts/" + id + "/end"));
            assertThat(again.get("status").asString()).isEqualTo("ended");
            assertThat(again.get("endedAt").asString()).isEqualTo(endedAts.iterator().next());

            // 송출자·시청자 모두 ended 를 정확히 한 번 받음
            assertThat(viewer.next("ended")).isNotNull();
            assertThat(host.next("ended")).isNotNull();
            Thread.sleep(500);
            assertThat(viewer.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));
            assertThat(host.inbox).noneMatch(m -> "ended".equals(m.get("type").asString()));
        }
    }

    @Test
    void 라이브_설정은_STUN과_시청자상한을_내려줌() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            JsonNode c = json(ApiClient.anonymous(ApiClient.baseUrl(app)).get("/api/live-config"));
            assertThat(c.get("maxViewers").asInt()).isEqualTo(5);
            assertThat(c.get("iceServers").get(0).get("urls").get(0).asString()).startsWith("stun:");
        }
    }
}
