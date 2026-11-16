package com.chzzk.broadcast;

import java.time.Instant;

import com.chzzk.user.OwnerResponse;

/** 방송 API 응답. owner 가 null 이면 소유자가 없는 이전 기록입니다. */
public record BroadcastResponse(
        Long id,
        String title,
        OwnerResponse owner,
        String streamer,
        String status,
        Instant startedAt,
        Instant endedAt,
        int viewerCount
) {
    static BroadcastResponse from(Broadcast b, int viewerCount) {
        OwnerResponse owner = OwnerResponse.of(b.getOwner());
        return new BroadcastResponse(
                b.getId(), b.getTitle(), owner, owner == null ? null : owner.nickname(),
                b.getStatus().name().toLowerCase(),
                b.getStartedAt(), b.getEndedAt(),
                b.isLive() ? viewerCount : 0);
    }
}
