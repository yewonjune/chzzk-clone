package com.chichijik.broadcast;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.chichijik.user.User;

/**
 * 방송: 스트리머(소유자)가 휴대폰으로 시작해서 종료할 때까지 이어지는 실시간 송출 한 번.
 * 영상은 저장하지 않고 기록(제목·시각·상태)과 댓글만 남깁니다.
 */
@Entity
@Table(name = "live_broadcasts")
public class Broadcast {

    public enum Status { LIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자(스트리머). 3단계 이전 기록은 비어 있으며 누구도 제어할 수 없습니다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected Broadcast() {
    }

    public Broadcast(User owner, String title, Instant startedAt) {
        this.owner = owner;
        this.title = title;
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

    public void rename(String title) {
        this.title = title;
    }

    public boolean isLive() {
        return status == Status.LIVE;
    }

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
}
