package com.chichijik.replay;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** API 응답. videoUrl 은 업로드 영상이면 /media/..., 연결한 영상이면 원래 주소입니다. */
public record ReplayResponse(
        Long id,
        String title,
        String streamer,
        String description,
        String sourceType,
        String videoUrl,
        String mimeType,
        Long sizeBytes,
        Double durationSec,
        long views,
        Instant createdAt
) {
    static ReplayResponse from(Replay b) {
        String videoUrl = b.getSourceType() == Replay.SourceType.UPLOAD
                ? "/media/" + URLEncoder.encode(b.getVideoPath(), StandardCharsets.UTF_8)
                : b.getVideoPath();
        return new ReplayResponse(
                b.getId(), b.getTitle(), b.getStreamer(), b.getDescription(),
                b.getSourceType().name().toLowerCase(), videoUrl,
                b.getMimeType(), b.getSizeBytes(), b.getDurationSec(),
                b.getViews(), b.getCreatedAt());
    }
}
