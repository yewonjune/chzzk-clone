package com.chichijik.broadcast;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 방송: 스트리머가 휴대폰으로 시작해서 종료할 때까지 이어지는 실시간 송출 한 번.
 * 영상은 저장하지 않고 기록(제목·시각·상태)만 남깁니다.
 */
@Entity
@Table(name = "live_broadcasts")
public class Broadcast {

    public enum Status { LIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 40)
    private String streamer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /** 송출 키의 SHA-256 해시(hex). 원래 키는 저장하지 않습니다. */
    @Column(nullable = false, length = 64)
    private String streamKeyHash;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected Broadcast() {
    }

    public Broadcast(String title, String streamer, String streamKeyHash, Instant startedAt) {
        this.title = title;
        this.streamer = streamer;
        this.streamKeyHash = streamKeyHash;
        this.startedAt = startedAt;
        this.status = Status.LIVE;
    }

    /** 종료는 한 번만 일어나며 되돌릴 수 없습니다. 이미 종료됐으면 false. */
    public boolean end(Instant at) {
        if (status == Status.ENDED) return false;
        status = Status.ENDED;
        endedAt = at;
        return true;
    }

    public boolean isLive() {
        return status == Status.LIVE;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getStreamer() { return streamer; }
    public Status getStatus() { return status; }
    public String getStreamKeyHash() { return streamKeyHash; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
}
