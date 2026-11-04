package com.chichijik.broadcast;

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
import org.springframework.web.multipart.MultipartFile;

import com.chichijik.common.ApiException;
import com.chichijik.config.MediaConfig;

@Service
public class BroadcastService {

    private static final Set<String> ALLOWED_EXT = Set.of(".mp4", ".webm", ".ogg", ".ogv", ".mov", ".m4v");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NOT_FOUND = "방송을 찾을 수 없습니다.";

    private final BroadcastRepository repository;
    private final Path uploadDir;

    public BroadcastService(BroadcastRepository repository, MediaConfig mediaConfig) {
        this.repository = repository;
        this.uploadDir = mediaConfig.uploadDir();
    }

    @Transactional(readOnly = true)
    public List<BroadcastResponse> list() {
        return repository.findAllByOrderByIdDesc().stream().map(BroadcastResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BroadcastResponse get(long id) {
        return repository.findById(id).map(BroadcastResponse::from)
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    @Transactional
    public long increaseViews(long id) {
        Broadcast b = repository.findById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        b.increaseViews();
        return b.getViews();
    }

    /** 등록: 영상 파일(video) 업로드 또는 영상 주소(videoUrl) 연결 중 하나 */
    @Transactional
    public BroadcastResponse create(String rawTitle, String rawStreamer, String rawDescription,
                                    String rawVideoUrl, String rawDurationSec, MultipartFile video) {
        String title = trim(rawTitle);
        String streamer = trim(rawStreamer);
        String description = trim(rawDescription);
        String videoUrl = trim(rawVideoUrl);
        boolean hasFile = video != null && !video.isEmpty();

        if (title.isEmpty()) throw ApiException.badRequest("제목을 입력해 주세요.");
        if (title.length() > 100) throw ApiException.badRequest("제목은 100자 이하로 입력해 주세요.");
        if (streamer.length() > 40) throw ApiException.badRequest("스트리머 이름은 40자 이하로 입력해 주세요.");
        if (description.length() > 2000) throw ApiException.badRequest("설명은 2000자 이하로 입력해 주세요.");
        if (hasFile && !videoUrl.isEmpty()) throw ApiException.badRequest("영상 파일과 영상 주소 중 하나만 입력해 주세요.");
        if (!hasFile && videoUrl.isEmpty()) throw ApiException.badRequest("영상 파일을 올리거나 영상 주소를 입력해 주세요.");

        Double duration = parseDuration(rawDurationSec);

        if (!hasFile) {
            String url = validateUrl(videoUrl);
            Broadcast b = new Broadcast(title, streamer, description, Broadcast.SourceType.URL, url, null, null, duration);
            return BroadcastResponse.from(repository.save(b));
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
            Broadcast b = new Broadcast(title, streamer, description, Broadcast.SourceType.UPLOAD,
                    fileName, contentType, video.getSize(), duration);
            return BroadcastResponse.from(repository.saveAndFlush(b));
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
