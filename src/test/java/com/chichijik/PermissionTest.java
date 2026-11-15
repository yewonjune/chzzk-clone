package com.chichijik;

import static com.chichijik.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chichijik.ApiClient.FilePart;

import tools.jackson.databind.JsonNode;

/**
 * 3단계 권한 매트릭스: 비로그인 / 소유자(작성자) / 다른 사용자 각각에 대해
 * 서버가 직접 허용·거절하는지 확인합니다. (화면 버튼과 무관하게 API 를 바로 호출)
 */
class PermissionTest {

    @TempDir
    Path dataDir;

    @Test
    void 회원가입_로그인_로그아웃() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient a = ApiClient.signUp(base, "alice_01", "앨리스");
            assertThat(a.user.get("nickname").asString()).isEqualTo("앨리스");
            assertThat(json(a.get("/api/auth/me")).get("user").get("username").asString()).isEqualTo("alice_01");

            ApiClient guest = ApiClient.anonymous(base);
            // 중복·형식 검증
            assertThat(guest.postJson("/api/auth/signup", "{\"username\":\"alice_01\",\"password\":\"password123\",\"nickname\":\"다른닉\"}").statusCode()).isEqualTo(409);
            assertThat(guest.postJson("/api/auth/signup", "{\"username\":\"bob_01\",\"password\":\"password123\",\"nickname\":\"앨리스\"}").statusCode()).isEqualTo(409);
            assertThat(guest.postJson("/api/auth/signup", "{\"username\":\"Bad Name\",\"password\":\"password123\",\"nickname\":\"밥\"}").statusCode()).isEqualTo(400);
            assertThat(guest.postJson("/api/auth/signup", "{\"username\":\"bob_02\",\"password\":\"short\",\"nickname\":\"밥이\"}").statusCode()).isEqualTo(400);

            // 비밀번호가 틀리면 401, 맞으면 로그인
            assertThat(guest.postJson("/api/auth/login", "{\"username\":\"alice_01\",\"password\":\"wrong-pass\"}").statusCode()).isEqualTo(401);
            var login = guest.postJson("/api/auth/login", "{\"username\":\"alice_01\",\"password\":\"password123\"}");
            assertThat(login.statusCode()).isEqualTo(200);
            guest.applySession(json(login));
            assertThat(json(guest.get("/api/auth/me")).get("user").get("nickname").asString()).isEqualTo("앨리스");

            // 응답에 비밀번호 해시가 없다
            assertThat(login.body()).doesNotContain("password").doesNotContain("$2a$");

            // 로그아웃 후에는 비로그인과 같다
            assertThat(a.post("/api/auth/logout").statusCode()).isEqualTo(204);
            a.refreshMe();
            assertThat(a.user.isNull()).isTrue();
            assertThat(a.postJson("/api/broadcasts", "{\"title\":\"x\"}").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void CSRF_토큰_없이_상태변경_요청은_거절() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            ApiClient a = ApiClient.signUp(ApiClient.baseUrl(app), "csrf_user", "토큰확인");
            var res = a.postJsonWithoutCsrf("/api/broadcasts", "{\"title\":\"x\"}");
            assertThat(res.statusCode()).isEqualTo(403);
            assertThat(json(res).get("error").asString()).contains("보안 토큰");
        }
    }

    @Test
    void 다시보기_등록_수정_삭제_권한() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "owner_01", "소유자");
            ApiClient other = ApiClient.signUp(base, "other_01", "다른이");
            ApiClient guest = ApiClient.anonymous(base);
            byte[] sample = Files.readAllBytes(Path.of("samples/sample.mp4"));

            // 등록: 비로그인 401, 로그인 201
            assertThat(guest.postMultipart("/api/replays", Map.of("title", "x", "videoUrl", "https://example.com/a.mp4")).statusCode()).isEqualTo(401);
            JsonNode r = json(owner.postMultipart("/api/replays", Map.of("title", "원래 제목",
                    "video", new FilePart("s.mp4", "video/mp4", sample))));
            long id = r.get("id").asLong();
            String file = r.get("videoUrl").asString();

            // 조회는 공개
            assertThat(guest.get("/api/replays/" + id).statusCode()).isEqualTo(200);

            // 수정: 비로그인 401, 다른 사용자 403, 소유자 200
            String edit = "{\"title\":\"바뀐 제목\",\"description\":\"설명\"}";
            assertThat(guest.patchJson("/api/replays/" + id, edit).statusCode()).isEqualTo(401);
            assertThat(other.patchJson("/api/replays/" + id, edit).statusCode()).isEqualTo(403);
            assertThat(json(guest.get("/api/replays/" + id)).get("title").asString()).isEqualTo("원래 제목");
            assertThat(owner.patchJson("/api/replays/" + id, edit).statusCode()).isEqualTo(200);
            assertThat(json(guest.get("/api/replays/" + id)).get("title").asString()).isEqualTo("바뀐 제목");

            // 삭제: 비로그인 401, 다른 사용자 403, 소유자 204 (파일·댓글도 함께 삭제)
            assertThat(other.postJson("/api/replays/" + id + "/comments", "{\"content\":\"잘 봤어요\"}").statusCode()).isEqualTo(201);
            assertThat(guest.delete("/api/replays/" + id).statusCode()).isEqualTo(401);
            assertThat(other.delete("/api/replays/" + id).statusCode()).isEqualTo(403);
            assertThat(guest.get("/api/replays/" + id).statusCode()).isEqualTo(200);
            assertThat(owner.delete("/api/replays/" + id).statusCode()).isEqualTo(204);
            assertThat(guest.get("/api/replays/" + id).statusCode()).isEqualTo(404);
            assertThat(guest.get(file).statusCode()).isEqualTo(404);
            assertThat(guest.get("/api/replays/" + id + "/comments").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void 방송_시작_수정_종료_삭제_권한() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "owner_02", "방송주인");
            ApiClient other = ApiClient.signUp(base, "other_02", "구경꾼");
            ApiClient guest = ApiClient.anonymous(base);

            assertThat(guest.postJson("/api/broadcasts", "{\"title\":\"x\"}").statusCode()).isEqualTo(401);
            long id = json(owner.postJson("/api/broadcasts", "{\"title\":\"방송\"}")).get("id").asLong();
            assertThat(guest.get("/api/broadcasts/" + id).statusCode()).isEqualTo(200);

            String rename = "{\"title\":\"새 제목\"}";
            assertThat(guest.patchJson("/api/broadcasts/" + id, rename).statusCode()).isEqualTo(401);
            assertThat(other.patchJson("/api/broadcasts/" + id, rename).statusCode()).isEqualTo(403);
            assertThat(owner.patchJson("/api/broadcasts/" + id, rename).statusCode()).isEqualTo(200);

            assertThat(guest.post("/api/broadcasts/" + id + "/end").statusCode()).isEqualTo(401);
            assertThat(other.post("/api/broadcasts/" + id + "/end").statusCode()).isEqualTo(403);
            assertThat(json(guest.get("/api/broadcasts/" + id)).get("status").asString()).isEqualTo("live");
            assertThat(owner.post("/api/broadcasts/" + id + "/end").statusCode()).isEqualTo(200);

            assertThat(guest.delete("/api/broadcasts/" + id).statusCode()).isEqualTo(401);
            assertThat(other.delete("/api/broadcasts/" + id).statusCode()).isEqualTo(403);
            assertThat(owner.delete("/api/broadcasts/" + id).statusCode()).isEqualTo(204);
        }
    }

    @Test
    void 댓글_작성_수정_삭제_권한() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient owner = ApiClient.signUp(base, "owner_03", "방송주");
            ApiClient writer = ApiClient.signUp(base, "writer_03", "작성자");
            ApiClient third = ApiClient.signUp(base, "third_03", "제삼자");
            ApiClient guest = ApiClient.anonymous(base);

            long b = json(owner.postJson("/api/broadcasts", "{\"title\":\"댓글 방송\"}")).get("id").asLong();
            String path = "/api/broadcasts/" + b + "/comments";

            // 작성: 비로그인 401, 로그인 201, 빈 내용·500자 초과 400
            assertThat(guest.postJson(path, "{\"content\":\"hi\"}").statusCode()).isEqualTo(401);
            assertThat(writer.postJson(path, "{\"content\":\"   \"}").statusCode()).isEqualTo(400);
            assertThat(writer.postJson(path, "{\"content\":\"" + "가".repeat(501) + "\"}").statusCode()).isEqualTo(400);
            long c1 = json(writer.postJson(path, "{\"content\":\"첫 댓글\"}")).get("id").asLong();
            long c2 = json(writer.postJson(path, "{\"content\":\"둘째 댓글\"}")).get("id").asLong();

            // 목록은 비로그인도 조회 가능, 작성자 정보 포함, 최신순
            JsonNode page = json(guest.get(path));
            assertThat(page.get("comments").size()).isEqualTo(2);
            assertThat(page.get("comments").get(0).get("id").asLong()).isEqualTo(c2);
            assertThat(page.get("comments").get(0).get("author").get("nickname").asString()).isEqualTo("작성자");

            // 수정: 작성자만 (방송 소유자도 남의 댓글은 수정 불가)
            String edit = "{\"content\":\"고친 댓글\"}";
            assertThat(guest.patchJson("/api/comments/" + c1, edit).statusCode()).isEqualTo(401);
            assertThat(third.patchJson("/api/comments/" + c1, edit).statusCode()).isEqualTo(403);
            assertThat(owner.patchJson("/api/comments/" + c1, edit).statusCode()).isEqualTo(403);
            JsonNode edited = json(writer.patchJson("/api/comments/" + c1, edit));
            assertThat(edited.get("content").asString()).isEqualTo("고친 댓글");
            assertThat(edited.get("updatedAt").isNull()).isFalse();

            // 삭제: 비로그인 401, 제3자 403, 작성자 204, 방송 소유자 204
            assertThat(guest.delete("/api/comments/" + c1).statusCode()).isEqualTo(401);
            assertThat(third.delete("/api/comments/" + c1).statusCode()).isEqualTo(403);
            assertThat(writer.delete("/api/comments/" + c1).statusCode()).isEqualTo(204);
            assertThat(owner.delete("/api/comments/" + c2).statusCode()).isEqualTo(204);
            assertThat(json(guest.get(path)).get("comments").size()).isZero();

            // 없는 대상
            assertThat(writer.postJson("/api/broadcasts/9999/comments", "{\"content\":\"x\"}").statusCode()).isEqualTo(404);
            assertThat(writer.patchJson("/api/comments/9999", edit).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void 댓글_목록_페이지네이션() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            String base = ApiClient.baseUrl(app);
            ApiClient u = ApiClient.signUp(base, "pager_01", "페이저");
            long r = json(u.postMultipart("/api/replays", Map.of("title", "r", "videoUrl", "https://example.com/a.mp4"))).get("id").asLong();
            String path = "/api/replays/" + r + "/comments";
            for (int i = 1; i <= 5; i++) u.postJson(path, "{\"content\":\"댓글 " + i + "\"}");

            JsonNode first = json(u.get(path + "?size=2"));
            assertThat(first.get("comments").get(0).get("content").asString()).isEqualTo("댓글 5");
            long cursor = first.get("nextBefore").asLong();
            JsonNode second = json(u.get(path + "?size=2&before=" + cursor));
            assertThat(second.get("comments").get(0).get("content").asString()).isEqualTo("댓글 3");
            JsonNode last = json(u.get(path + "?size=2&before=" + second.get("nextBefore").asLong()));
            assertThat(last.get("comments").size()).isEqualTo(1);
            assertThat(last.get("nextBefore").isNull()).isTrue();
        }
    }
}
