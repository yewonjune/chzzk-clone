package com.chzzk;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 브라우저 흉내: 사용자마다 세션 쿠키와 CSRF 토큰을 따로 들고 실제 서버에 요청합니다.
 * (비로그인 / 소유자 / 다른 사용자를 각각 별도 ApiClient 로 만듭니다.)
 */
final class ApiClient {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration WAIT = Duration.ofSeconds(5);

    final String base;
    final CookieManager cookies;
    final HttpClient http;
    private String csrfHeader;
    private String csrfToken;

    String csrfHeaderName() {
        return csrfHeader;
    }

    String csrfToken() {
        return csrfToken;
    }
    JsonNode user;

    private ApiClient(String base) {
        this.base = base;
        this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private ApiClient(String base, ApiClient same) {
        this.base = base;
        this.cookies = same.cookies;
        this.http = same.http;
        this.csrfHeader = same.csrfHeader;
        this.csrfToken = same.csrfToken;
        this.user = same.user;
    }

    /**
     * 같은 브라우저(같은 쿠키·CSRF 토큰)가 로드밸런서 뒤의 다른 서버로 보내는 요청.
     * 쿠키는 포트를 구분하지 않으므로 localhost 의 A·B 서버에 같은 세션 쿠키가 실립니다.
     */
    ApiClient on(String otherBase) {
        return new ApiClient(otherBase, this);
    }

    // ---- 서버 기동 ----

    static ConfigurableApplicationContext startServer(Path dataDir, String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--server.port=0";
        args[1] = "--app.data-dir=" + dataDir.toString().replace('\\', '/');
        System.arraycopy(extra, 0, args, 2, extra.length);
        // 명령행 인자는 application.properties 보다 우선하므로 임시 폴더만 사용합니다.
        return new SpringApplicationBuilder(ChzzkApplication.class).run(args);
    }

    static String baseUrl(ConfigurableApplicationContext app) {
        return "http://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    /** 비로그인 사용자 (CSRF 토큰은 받아 둠 → 거절 사유가 CSRF 가 아니라 '로그인 필요'인지 확인 가능) */
    static ApiClient anonymous(String base) throws Exception {
        ApiClient c = new ApiClient(base);
        c.refreshMe();
        return c;
    }

    /** 세션 없이 보내는 호출 (Kubernetes probe 처럼: 세션 저장소가 멈춰도 요청이 막히지 않게 /api/auth/me 를 부르지 않음) */
    static ApiClient anonymousWithoutSession(String base) {
        return new ApiClient(base);
    }

    static ApiClient signUp(String base, String username, String nickname) throws Exception {
        ApiClient c = anonymous(base);
        HttpResponse<String> res = c.postJson("/api/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"password123\",\"nickname\":\"" + nickname + "\"}");
        if (res.statusCode() != 201) throw new AssertionError("가입 실패: " + res.statusCode() + " " + res.body());
        c.applySession(JSON.readTree(res.body()));
        return c;
    }

    void refreshMe() throws Exception {
        applySession(JSON.readTree(get("/api/auth/me").body()));
    }

    void applySession(JsonNode body) {
        user = body.get("user");
        JsonNode csrf = body.get("csrf");
        if (csrf != null && !csrf.isNull()) {
            csrfHeader = csrf.get("headerName").asString();
            csrfToken = csrf.get("token").asString();
        }
    }

    long userId() {
        return user.get("id").asLong();
    }

    // ---- HTTP ----

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postJson(String path, String json) throws Exception {
        return send("POST", path, json);
    }

    HttpResponse<String> patchJson(String path, String json) throws Exception {
        return send("PATCH", path, json);
    }

    HttpResponse<String> post(String path) throws Exception {
        return send("POST", path, null);
    }

    HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    /** CSRF 헤더 없이 보내기 (CSRF 보호 확인용) */
    HttpResponse<String> postJsonWithoutCsrf(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (csrfToken != null) b.header(csrfHeader, csrfToken);
        if (json != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    record FilePart(String filename, String contentType, byte[] data) {
    }

    HttpResponse<String> postMultipart(String path, Map<String, Object> fields) throws Exception {
        String boundary = "----chzzk" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var e : fields.entrySet()) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (e.getValue() instanceof FilePart f) {
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"; filename=\"" + f.filename() + "\"\r\n"
                        + "Content-Type: " + f.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(f.data());
            } else {
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n" + e.getValue())
                        .getBytes(StandardCharsets.UTF_8));
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (csrfToken != null) b.header(csrfHeader, csrfToken);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> res) {
        return JSON.readTree(res.body());
    }

    // ---- WebSocket (이 사용자의 세션 쿠키를 실어 연결) ----

    Socket socket(String path) {
        Socket s = new Socket();
        String cookieHeader = cookies.getCookieStore().getCookies().stream()
                .map(HttpCookie::toString).collect(Collectors.joining("; "));
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (!cookieHeader.isEmpty()) b.header("Cookie", cookieHeader);
        s.ws = b.buildAsync(URI.create(base.replace("http", "ws") + path), s).join();
        return s;
    }

    /** 받은 메시지를 큐에 쌓아 두고 type 으로 기다리는 WebSocket 클라이언트 */
    static final class Socket implements WebSocket.Listener {
        WebSocket ws;
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        /** 서버가 연결을 닫을 때 보낸 종료 코드 */
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        void send(String text) {
            ws.sendText(text, true).join();
        }

        JsonNode next(String type) throws InterruptedException {
            return next(type, WAIT);
        }

        /** 최대 wait 동안 type 메시지를 기다림 (하트비트 TTL·재연결 유예처럼 기본 대기보다 오래 걸리는 경우) */
        JsonNode next(String type, Duration wait) throws InterruptedException {
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").asString())) return m;
            }
            throw new AssertionError("메시지를 받지 못함: " + type);
        }

        /** type 이 같고 조건에 맞는 메시지가 올 때까지 기다림 (중간에 온 다른 값은 건너뜀) */
        JsonNode nextWhere(String type, Predicate<JsonNode> condition) throws InterruptedException {
            return nextWhere(type, condition, WAIT);
        }

        JsonNode nextWhere(String type, Predicate<JsonNode> condition, Duration wait) throws InterruptedException {
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").asString()) && condition.test(m)) return m;
            }
            throw new AssertionError("조건에 맞는 메시지를 받지 못함: " + type);
        }

        /** 지금부터 잠시 기다리는 동안 type 메시지가 몇 개 더 오는지 */
        int countWithin(String type, Duration wait) throws InterruptedException {
            int n = 0;
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode m = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").asString())) n++;
            }
            return n;
        }

        void close() {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        }

        /** 종료 핸드셰이크 없이 TCP 연결을 끊음 (네트워크가 끊긴 것처럼 비정상 종료) */
        void drop() {
            ws.abort();
        }

        int awaitCloseCode() throws Exception {
            return closeCode.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
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
