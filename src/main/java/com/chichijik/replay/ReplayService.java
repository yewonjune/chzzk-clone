package com.chichijik.replay;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.chichijik.common.ApiException;
import com.chichijik.common.Ownership;
import com.chichijik.config.MediaConfig;
import com.chichijik.user.LoginUser;
import com.chichijik.user.User;
import com.chichijik.user.UserRepository;

@Service
public class ReplayService {

    private static final Set<String> ALLOWED_EXT = Set.of(".mp4", ".webm", ".ogg", ".ogv", ".mov", ".m4v");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NOT_FOUND = "다시보기를 찾을 수 없습니다.";

    private final ReplayRepository repository;
    private final UserRepository users;
    private final Path uploadDir;

    public ReplayService(ReplayRepository repository, UserRepository users, MediaConfig mediaConfig) {
        this.repository = repository;
        this.users = users;
        this.uploadDir = mediaConfig.uploadDir();
    }

    @Transactional(readOnly = true)
    public List<ReplayResponse> list() {
        return repository.findAllByOrderByIdDesc().stream().map(ReplayResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReplayResponse get(long id) {
        return ReplayResponse.from(find(id));
    }

    @Transactional
    public long increaseViews(long id) {
        Replay b = repository.findById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        b.increaseViews();
        return b.getViews();
    }

    /** 수정(제목·설명): 소유자만 */
    @Transactional
    public ReplayResponse edit(long id, LoginUser me, String rawTitle, String rawDescription) {
        Replay r = find(id);
        Ownership.requireOwner(r.getOwner(), me, "이 다시보기를 수정할 권한이 없습니다.");
        String title = trim(rawTitle);
        String description = trim(rawDescription);
        validateText(title, description);
        r.edit(title, description);
        return ReplayResponse.from(r);
    }

    /** 삭제: 소유자만. 댓글은 DB에서 함께 삭제(ON DELETE CASCADE), 업로드 파일은 커밋 후 삭제 */
    @Transactional
    public void delete(long id, LoginUser me) {
        Replay r = find(id);
        Ownership.requireOwner(r.getOwner(), me, "이 다시보기를 삭제할 권한이 없습니다.");
        repository.delete(r);
        if (r.getSourceType() == Replay.SourceType.UPLOAD) {
            Path file = uploadDir.resolve(r.getVideoPath());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(file);
                }
            });
        }
    }

    private Replay find(long id) {
        return repository.findWithOwnerById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    private static void validateText(String title, String description) {
        if (title.isEmpty()) throw ApiException.badRequest("제목을 입력해 주세요.");
        if (title.length() > 100) throw ApiException.badRequest("제목은 100자 이하로 입력해 주세요.");
        if (description.length() > 2000) throw ApiException.badRequest("설명은 2000자 이하로 입력해 주세요.");
    }

    /** 등록: 로그인 사용자가 소유자가 됩니다. 영상 파일(video) 업로드 또는 영상 주소(videoUrl) 연결 중 하나 */
    @Transactional
    public ReplayResponse create(LoginUser me, String rawTitle, String rawDescription,
                                 String rawVideoUrl, String rawDurationSec, MultipartFile video) {
        Ownership.requireLogin(me);
        String title = trim(rawTitle);
        String description = trim(rawDescription);
        String videoUrl = trim(rawVideoUrl);
        boolean hasFile = video != null && !video.isEmpty();

        validateText(title, description);
        if (hasFile && !videoUrl.isEmpty()) throw ApiException.badRequest("영상 파일과 영상 주소 중 하나만 입력해 주세요.");
        if (!hasFile && videoUrl.isEmpty()) throw ApiException.badRequest("영상 파일을 올리거나 영상 주소를 입력해 주세요.");

        Double duration = parseDuration(rawDurationSec);
        User owner = users.findById(me.id()).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));

        if (!hasFile) {
            String url = validateUrl(videoUrl);
            Replay b = new Replay(owner, title, description, Replay.SourceType.URL, url, null, null, duration);
            return ReplayResponse.from(repository.save(b));
        }

        String ext = extension(video.getOriginalFilename());
        String contentType = video.getContentType() == null ? "" : video.getContentType();
        if (!contentType.startsWith("video/") || !ALLOWED_EXT.contains(ext)) {
            throw ApiException.badRequest("지원하지 않는 영상 형식입니다. (" + String.join(", ", ALLOWED_EXT.stream().sorted().toList()) + ")");
        }

        String fileName = System.currentTimeMillis() + "-" + HexFormat.of().formatHex(randomBytes()) + ext;
        Path target = uploadDir.resolve(fileName);
        try (InputStream in = video.getInputStream()) {
            Files.copy(in, target);
        } catch (IOException e) {
            throw new IllegalStateException("영상 파일 저장 실패", e);
        }

        try {
            Replay b = new Replay(owner, title, description, Replay.SourceType.UPLOAD,
                    fileName, contentType, video.getSize(), duration);
            return ReplayResponse.from(repository.saveAndFlush(b));
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
    }

    private static String validateUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw ApiException.badRequest("영상 주소는 http(s)만 지원합니다.");
            }
            if (uri.getHost() == null) throw ApiException.badRequest("올바른 영상 주소가 아닙니다.");
            return uri.toString();
        } catch (java.net.URISyntaxException e) {
            throw ApiException.badRequest("올바른 영상 주소가 아닙니다.");
        }
    }

    /** 브라우저가 측정한 재생 길이. 잘못된 값이면 저장하지 않습니다. */
    private static Double parseDuration(String value) {
        try {
            double d = Double.parseDouble(trim(value));
            return Double.isFinite(d) && d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 정리 실패는 무시
        }
    }
}
