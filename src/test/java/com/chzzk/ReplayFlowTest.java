package com.chzzk;

import static com.chzzk.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chzzk.ApiClient.FilePart;

import tools.jackson.databind.JsonNode;

/**
 * 1단계 다시보기 기능 유지: 등록 → 목록 → 상세 → 재생(Range) → 재시작 후 유지.
 * (3단계부터 등록은 로그인 사용자만 가능하고, 등록한 사용자가 스트리머가 됩니다.)
 */
class ReplayFlowTest {

    @TempDir
    Path dataDir;

    @Test
    void 업로드_목록_상세_재생_재시작후_유지() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("samples/sample.mp4"));
        long id;
        String videoUrl;

        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient chichi = ApiClient.signUp(base, "chichi", "치치");

            Map<String, Object> form = new LinkedHashMap<>();
            form.put("title", "테스트 방송");
            form.put("durationSec", "5");
            form.put("video", new FilePart("sample.mp4", "video/mp4", sample));
            HttpResponse<String> created = chichi.postMultipart("/api/replays", form);
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode body = json(created);
            id = body.get("id").asLong();
            videoUrl = body.get("videoUrl").asString();
            assertThat(body.get("sourceType").asString()).isEqualTo("upload");
            assertThat(body.get("streamer").asString()).isEqualTo("치치");
            assertThat(body.get("owner").get("id").asLong()).isEqualTo(chichi.userId());

            // 목록·상세·재생은 비로그인도 가능
            ApiClient guest = ApiClient.anonymous(base);
            assertThat(json(guest.get("/api/replays")).size()).isEqualTo(1);
            HttpResponse<byte[]> range = guest.http.send(HttpRequest.newBuilder(URI.create(base + videoUrl))
                    .header("Range", "bytes=0-99").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(range.statusCode()).isEqualTo(206);
            assertThat(range.body()).hasSize(100);
            assertThat(json(guest.post("/api/replays/" + id + "/views")).get("views").asLong()).isEqualTo(1);
        }

        try (var app = ApiClient.startServer(dataDir)) { // 재시작
            ApiClient guest = ApiClient.anonymous(ApiClient.baseUrl(app));
            JsonNode again = json(guest.get("/api/replays/" + id));
            assertThat(again.get("title").asString()).isEqualTo("테스트 방송");
            assertThat(again.get("streamer").asString()).isEqualTo("치치");
            assertThat(again.get("views").asLong()).isEqualTo(1);
            assertThat(guest.get(videoUrl).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void 영상주소_연결과_입력_검증() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            ApiClient u = ApiClient.signUp(ApiClient.baseUrl(app), "uploader", "업로더");
            String url = "/api/replays";

            HttpResponse<String> ok = u.postMultipart(url, Map.of("title", "외부 영상", "videoUrl", "https://example.com/a.mp4"));
            assertThat(ok.statusCode()).isEqualTo(201);
            assertThat(json(ok).get("videoUrl").asString()).isEqualTo("https://example.com/a.mp4");

            assertThat(u.postMultipart(url, Map.of("title", " ", "videoUrl", "https://example.com/a.mp4")).statusCode()).isEqualTo(400);
            assertThat(u.postMultipart(url, Map.of("title", "영상 없음")).statusCode()).isEqualTo(400);
            assertThat(u.postMultipart(url, Map.of("title", "x", "videoUrl", "javascript:alert(1)")).statusCode()).isEqualTo(400);
            HttpResponse<String> txt = u.postMultipart(url, Map.of("title", "텍스트",
                    "video", new FilePart("a.txt", "text/plain", "hi".getBytes())));
            assertThat(txt.statusCode()).isEqualTo(400);
            assertThat(json(txt).get("error").asString()).contains("지원하지 않는 영상 형식");

            assertThat(u.get(url + "/999").statusCode()).isEqualTo(404);
            assertThat(u.get(url + "/abc").statusCode()).isEqualTo(404);
        }
    }
}
