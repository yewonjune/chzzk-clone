package com.chzzk.replay;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.chzzk.user.OwnerResponse;

/** API 응답. videoUrl 은 업로드 영상이면 /media/..., 연결한 영상이면 원래 주소입니다. */
public record ReplayResponse(
        Long id,
        String title,
        OwnerResponse owner,
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
        OwnerResponse owner = OwnerResponse.of(b.getOwner());
        return new ReplayResponse(
                b.getId(), b.getTitle(), owner, owner == null ? null : owner.nickname(),
                b.getDescription(), b.getSourceType().name().toLowerCase(), videoUrl,
                b.getMimeType(), b.getSizeBytes(), b.getDurationSec(),
                b.getViews(), b.getCreatedAt());
    }
}
