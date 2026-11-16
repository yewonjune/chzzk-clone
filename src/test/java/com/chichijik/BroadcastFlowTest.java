package com.chichijik;

import static com.chichijik.ApiClient.json;
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

import com.chichijik.ApiClient.Socket;

import tools.jackson.databind.JsonNode;

/**
 * 2단계 방송 기능 유지 + 3단계 소유자 송출 제어:
 * 시작 → 목록(방송 중) → 소유자만 송출자 연결 → 시그널링 → 시청자 상한 → 방송 중 댓글 실시간 전달
 * → 송출 끊김 시 즉시 종료 → 재시작 시 정리.
 */
class BroadcastFlowTest {

    @TempDir
    Path dataDir;

    @Test
    void 소유자만_송출_시그널링_댓글푸시_시청자상한_끊김시_종료() throws Exception {
        try (var app = ApiClient.startServer(dataDir, "--app.live.max-viewers=1")) {
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

            // 송출자 연결이 끊기면 즉시 종료 → 시청자에게 ended
            host.close();
            assertThat(viewer.next("ended")).isNotNull();
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
    void 소유자가_종료_그리고_재시작하면_방송중이_남지_않음() throws Exception {
        long leftoverId;
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "streamer2", "방송인");
            ApiClient other = ApiClient.signUp(base, "someone", "다른사람");

            long a = json(owner.postJson("/api/broadcasts", "{\"title\":\"A\"}")).get("id").asLong();
            assertThat(other.post("/api/broadcasts/" + a + "/end").statusCode()).isEqualTo(403);
            var end = owner.post("/api/broadcasts/" + a + "/end");
            assertThat(end.statusCode()).isEqualTo(200);
            assertThat(json(end).get("status").asString()).isEqualTo("ended");

            leftoverId = json(owner.postJson("/api/broadcasts", "{\"title\":\"B\"}")).get("id").asLong();
        }
        try (var app = ApiClient.startServer(dataDir)) { // 재시작: 송출 연결이 사라졌으므로 B도 종료
            ApiClient guest = ApiClient.anonymous(ApiClient.baseUrl(app));
            JsonNode list = json(guest.get("/api/broadcasts"));
            assertThat(list.size()).isEqualTo(2);
            for (JsonNode n : list) assertThat(n.get("status").asString()).isEqualTo("ended");
            assertThat(json(guest.get("/api/broadcasts/" + leftoverId)).get("streamer").asString()).isEqualTo("방송인");
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
