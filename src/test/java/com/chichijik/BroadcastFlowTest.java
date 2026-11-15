package com.chichijik;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 방송: 시작 → 목록(방송 중) → 송출자·시청자 시그널링 → 시청자 상한 → 송출 끊김 시 즉시 종료 → 재시작 시 정리.
 */
class BroadcastFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dataDir;

    @Test
    void 시작_시그널링_시청자상한_송출끊김시_종료() throws Exception {
        try (var app = start("--app.live.max-viewers=1")) {
            String base = baseUrl(app);

            // 검증: 제목 필수
            assertThat(postJson(base + "/api/broadcasts", "{\"title\":\"  \"}").statusCode()).isEqualTo(400);

            // 시작: 송출 키는 시작 응답에서만 내려감
            HttpResponse<String> started = postJson(base + "/api/broadcasts", "{\"title\":\"퇴근길 방송\",\"streamer\":\"치치\"}");
            assertThat(started.statusCode()).isEqualTo(201);
            JsonNode body = JSON.readTree(started.body());
            long id = body.get("broadcast").get("id").asLong();
            String key = body.get("streamKey").asString();
            assertThat(key).hasSizeGreaterThan(20);
            assertThat(body.get("broadcast").get("status").asString()).isEqualTo("live");

            String detail = get(base + "/api/broadcasts/" + id).body();
            assertThat(detail).doesNotContain(key).doesNotContain("streamKey").doesNotContain("Hash");
            assertThat(JSON.readTree(get(base + "/api/broadcasts").body()).get(0).get("status").asString()).isEqualTo("live");

            // 키 없이/틀린 키로 종료 불가
            assertThat(post(base + "/api/broadcasts/" + id + "/end", null).statusCode()).isEqualTo(403);
            assertThat(post(base + "/api/broadcasts/" + id + "/end", "wrong").statusCode()).isEqualTo(403);

            // 틀린 키로 송출자 연결 → 거절
            Client fakeHost = connect(base, id);
            fakeHost.send("{\"type\":\"join\",\"role\":\"host\",\"streamKey\":\"wrong\"}");
            assertThat(fakeHost.next("error")).isNotNull();

            // 시청자가 먼저 들어와 있어도, 송출자가 연결되면 viewer-joined 를 받음
            Client viewer = connect(base, id);
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(viewer.next("viewers").get("count").asInt()).isEqualTo(1);

            Client host = connect(base, id);
            host.send("{\"type\":\"join\",\"role\":\"host\",\"streamKey\":\"" + key + "\"}");
            String viewerId = host.next("viewer-joined").get("viewerId").asString();
            assertThat(JSON.readTree(get(base + "/api/broadcasts/" + id).body()).get("viewerCount").asInt()).isEqualTo(1);

            // 시그널 전달: 송출자 → 시청자, 시청자 → 송출자(from 포함)
            host.send("{\"type\":\"signal\",\"to\":\"" + viewerId + "\",\"data\":{\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0\"}}}");
            assertThat(viewer.next("signal").get("data").get("sdp").get("type").asString()).isEqualTo("offer");
            viewer.send("{\"type\":\"signal\",\"data\":{\"sdp\":{\"type\":\"answer\",\"sdp\":\"v=0\"}}}");
            JsonNode toHost = host.next("signal");
            assertThat(toHost.get("from").asString()).isEqualTo(viewerId);
            assertThat(toHost.get("data").get("sdp").get("type").asString()).isEqualTo("answer");

            // 상한(1명) 초과 시청자 → full
            Client second = connect(base, id);
            second.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(second.next("full").get("max").asInt()).isEqualTo(1);

            // 송출자 연결이 끊기면 즉시 종료 → 시청자에게 ended
            host.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            assertThat(viewer.next("ended")).isNotNull();
            JsonNode ended = JSON.readTree(get(base + "/api/broadcasts/" + id).body());
            assertThat(ended.get("status").asString()).isEqualTo("ended");
            assertThat(ended.get("endedAt").isNull()).isFalse();

            // 종료된 방송에 시청자 연결 → ended
            Client late = connect(base, id);
            late.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(late.next("ended")).isNotNull();
        }
    }

    @Test
    void 송출키로_종료_그리고_재시작하면_방송중이_남지_않음() throws Exception {
        long endedId;
        long leftoverId;
        try (var app = start()) {
            String base = baseUrl(app);
            JsonNode a = JSON.readTree(postJson(base + "/api/broadcasts", "{\"title\":\"A\"}").body());
            endedId = a.get("broadcast").get("id").asLong();
            HttpResponse<String> end = post(base + "/api/broadcasts/" + endedId + "/end", a.get("streamKey").asString());
            assertThat(end.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(end.body()).get("status").asString()).isEqualTo("ended");

            JsonNode b = JSON.readTree(postJson(base + "/api/broadcasts", "{\"title\":\"B\"}").body());
            leftoverId = b.get("broadcast").get("id").asLong();
        }
        try (var app = start()) { // 재시작: 송출 연결이 사라졌으므로 B도 종료
            String base = baseUrl(app);
            JsonNode list = JSON.readTree(get(base + "/api/broadcasts").body());
            assertThat(list.size()).isEqualTo(2);
            for (JsonNode n : list) assertThat(n.get("status").asString()).isEqualTo("ended");
            assertThat(JSON.readTree(get(base + "/api/broadcasts/" + leftoverId).body()).get("title").asString()).isEqualTo("B");
        }
    }

    @Test
    void 라이브_설정은_STUN과_시청자상한을_내려줌() throws Exception {
        try (var app = start()) {
            JsonNode c = JSON.readTree(get(baseUrl(app) + "/api/live-config").body());
            assertThat(c.get("maxViewers").asInt()).isEqualTo(5);
            assertThat(c.get("iceServers").get(0).get("urls").get(0).asString()).startsWith("stun:");
        }
    }

    // ---- helpers ----

    private ConfigurableApplicationContext start(String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--server.port=0";
        args[1] = "--app.data-dir=" + dataDir.toString().replace('\\', '/');
        System.arraycopy(extra, 0, args, 2, extra.length);
        return new SpringApplicationBuilder(ChichijikApplication.class).run(args);
    }

    private static String baseUrl(ConfigurableApplicationContext app) {
        return "http://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String streamKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody());
        if (streamKey != null) b.header("X-Stream-Key", streamKey);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Client connect(String base, long id) {
        Client c = new Client();
        c.ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(base.replace("http", "ws") + "/ws/broadcasts/" + id), c).join();
        return c;
    }

    /** 받은 메시지를 큐에 쌓아 두고 type 으로 기다리는 테스트용 WebSocket 클라이언트 */
    static final class Client implements WebSocket.Listener {
        WebSocket ws;
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        void send(String text) {
            ws.sendText(text, true).join();
        }

        JsonNode next(String type) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").asString())) return m;
            }
            throw new AssertionError("메시지를 받지 못함: " + type);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                inbox.add(JSON.readTree(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
