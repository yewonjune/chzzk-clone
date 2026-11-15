package com.chichijik;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 서버를 임시 폴더로 띄워 등록 → 목록 → 상세 → 재생(Range) → 재시작 후 유지를 확인합니다.
 */
class ReplayFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dataDir;

    @Test
    void 업로드_목록_상세_재생_재시작후_유지() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("samples/sample.mp4"));
        long id;
        String videoUrl;

        try (var app = start()) {
            String base = baseUrl(app);

            Map<String, Object> form = new LinkedHashMap<>();
            form.put("title", "테스트 방송");
            form.put("streamer", "치치");
            form.put("durationSec", "5");
            form.put("video", new FilePart("sample.mp4", "video/mp4", sample));
            HttpResponse<String> created = postMultipart(base + "/api/replays", form);
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode body = JSON.readTree(created.body());
            id = body.get("id").asLong();
            videoUrl = body.get("videoUrl").asString();
            assertThat(body.get("sourceType").asString()).isEqualTo("upload");

            JsonNode list = JSON.readTree(get(base + "/api/replays").body());
            assertThat(list.size()).isEqualTo(1);

            HttpResponse<byte[]> range = http.send(HttpRequest.newBuilder(URI.create(base + videoUrl))
                    .header("Range", "bytes=0-99").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(range.statusCode()).isEqualTo(206);
            assertThat(range.body()).hasSize(100);

            HttpResponse<String> views = http.send(HttpRequest.newBuilder(URI.create(base + "/api/replays/" + id + "/views"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(JSON.readTree(views.body()).get("views").asLong()).isEqualTo(1);
        }

        try (var app = start()) { // 재시작
            String base = baseUrl(app);
            JsonNode again = JSON.readTree(get(base + "/api/replays/" + id).body());
            assertThat(again.get("title").asString()).isEqualTo("테스트 방송");
            assertThat(again.get("views").asLong()).isEqualTo(1);
            assertThat(get(base + videoUrl).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void 영상주소_연결과_입력_검증() throws Exception {
        try (var app = start()) {
            String base = baseUrl(app);
            String url = base + "/api/replays";

            HttpResponse<String> ok = postMultipart(url, Map.of("title", "외부 영상", "videoUrl", "https://example.com/a.mp4"));
            assertThat(ok.statusCode()).isEqualTo(201);
            assertThat(JSON.readTree(ok.body()).get("videoUrl").asString()).isEqualTo("https://example.com/a.mp4");

            assertThat(postMultipart(url, Map.of("title", " ", "videoUrl", "https://example.com/a.mp4")).statusCode()).isEqualTo(400);
            assertThat(postMultipart(url, Map.of("title", "영상 없음")).statusCode()).isEqualTo(400);
            assertThat(postMultipart(url, Map.of("title", "x", "videoUrl", "javascript:alert(1)")).statusCode()).isEqualTo(400);
            HttpResponse<String> txt = postMultipart(url, Map.of("title", "텍스트",
                    "video", new FilePart("a.txt", "text/plain", "hi".getBytes())));
            assertThat(txt.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(txt.body()).get("error").asString()).contains("지원하지 않는 영상 형식");

            assertThat(get(url + "/999").statusCode()).isEqualTo(404);
            assertThat(get(url + "/abc").statusCode()).isEqualTo(404);
        }
    }

    // ---- helpers ----

    private ConfigurableApplicationContext start() {
        // 명령행 인자는 application.properties 보다 우선하므로 임시 폴더만 사용합니다.
        return new SpringApplicationBuilder(ChichijikApplication.class)
                .run("--server.port=0", "--app.data-dir=" + dataDir.toString().replace('\\', '/'));
    }

    private static String baseUrl(ConfigurableApplicationContext app) {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    record FilePart(String filename, String contentType, byte[] data) {
    }

    private HttpResponse<String> postMultipart(String url, Map<String, Object> fields) throws Exception {
        String boundary = "----chichijik" + UUID.randomUUID();
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
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
