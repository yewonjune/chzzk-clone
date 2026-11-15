package com.chichijik.broadcast;

import java.time.Instant;

/** 방송 API 응답. 송출 키(해시 포함)는 절대 담지 않습니다. */
public record BroadcastResponse(
        Long id,
        String title,
        String streamer,
        String status,
        Instant startedAt,
        Instant endedAt,
        int viewerCount
) {
    static BroadcastResponse from(Broadcast b, int viewerCount) {
        return new BroadcastResponse(
                b.getId(), b.getTitle(), b.getStreamer(),
                b.getStatus().name().toLowerCase(),
                b.getStartedAt(), b.getEndedAt(),
                b.isLive() ? viewerCount : 0);
    }

    /** 방송 시작 응답: 송출 키는 이 응답에서 단 한 번만 내려갑니다. */
    public record Started(BroadcastResponse broadcast, String streamKey) {
    }
}
