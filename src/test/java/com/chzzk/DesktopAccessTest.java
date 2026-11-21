package com.chzzk;

import static com.chzzk.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 6단계: 데스크톱 앱(출처 app://chzzk)은 조회 API·시청 WebSocket 만 쓸 수 있고,
 * 다른 출처나 상태 변경 요청은 그대로 막히며, 웹 화면(같은 출처)은 이전처럼 동작하는지 확인합니다.
 */
class DesktopAccessTest {

    static final String APP = "app://chzzk";

    @TempDir
    Path dataDir;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String url, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (origin != null) b.header("Origin", origin);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 데스크톱_출처는_조회와_시청만_허용() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "owner_01", "주인장");
            long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"데스크톱 시청 확인\"}")).get("id").asLong();

            // 조회: 데스크톱 출처에 CORS 허용 헤더
            var list = get(base + "/api/broadcasts", APP);
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.headers().firstValue("Access-Control-Allow-Origin")).hasValue(APP);
            assertThat(list.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

            // 모르는 출처: 허용 헤더가 없어 브라우저가 응답을 읽을 수 없음 (서버 동작은 이전 단계와 같음)
            assertThat(get(base + "/api/broadcasts", "https://evil.example").headers()
                    .firstValue("Access-Control-Allow-Origin")).isEmpty();

            // 데스크톱 출처의 상태 변경 요청: 사전 요청(preflight)에서 허용되지 않음
            var preflight = http.send(HttpRequest.newBuilder(URI.create(base + "/api/broadcasts"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", APP).header("Access-Control-Request-Method", "POST").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

            // 같은 출처의 웹 화면 요청은 이전처럼 동작 (로그인한 사용자의 POST)
            var sameOrigin = owner.postJson("/api/broadcasts/" + id + "/comments", "{\"content\":\"웹에서 댓글\"}");
            assertThat(sameOrigin.statusCode()).isEqualTo(201);

            // 다운로드 목록
            var downloads = json(get(base + "/api/downloads", null));
            assertThat(downloads.get("assets").size()).isGreaterThanOrEqualTo(4);
            assertThat(downloads.get("assets").get(0).get("url").asString())
                    .startsWith("https://github.com/yewonjune/chzzk-clone/releases/latest/download/");

            // 시청 WebSocket: 데스크톱 출처는 연결되어 시청자로 입장
            ApiClient.Socket viewer = new ApiClient.Socket();
            viewer.ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", APP)
                    .buildAsync(URI.create(base.replace("http", "ws") + "/ws/broadcasts/" + id), viewer).join();
            viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
            assertThat(viewer.next("joined").get("viewerId").asString()).isNotBlank();
            viewer.close();

            // 다른 출처의 WebSocket 은 핸드셰이크에서 거절
            ApiClient.Socket evil = new ApiClient.Socket();
            try {
                HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "https://evil.example")
                        .buildAsync(URI.create(base.replace("http", "ws") + "/ws/broadcasts/" + id), evil).join();
                throw new AssertionError("다른 출처 WebSocket 이 허용됨");
            } catch (CompletionException expected) {
                assertThat(expected.getCause()).isInstanceOf(java.net.http.WebSocketHandshakeException.class);
            }
        }
    }

    @Test
    void 프록시_뒤의_HTTPS_요청도_같은_출처로_인식() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient user = ApiClient.signUp(base, "proxy_01", "프록시");
            // Caddy 가 붙여 주는 헤더를 흉내: 브라우저는 https://도메인 에서 보냈고 앱은 http 로 받음
            String host = URI.create(base).getAuthority();
            var res = user.http.send(HttpRequest.newBuilder(URI.create(base + "/api/broadcasts"))
                    .header("Content-Type", "application/json")
                    .header(user.csrfHeaderName(), user.csrfToken())
                    .header("Origin", "https://" + host.replaceAll(":\\d+$", ""))
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", host.replaceAll(":\\d+$", ""))
                    .header("X-Forwarded-Port", "443")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"프록시 뒤\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        }
    }
}
