package com.chichijik.replay;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.chichijik.user.User;

/** 다시보기: 언제든 처음부터 재생할 수 있는 녹화 영상. 영상 출처(sourceType)에 따라 업로드 파일명 또는 외부 영상 주소를 가집니다. */
@Entity
@Table(name = "replays")
public class Replay {

    public enum SourceType { UPLOAD, URL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자(스트리머). 3단계 이전에 등록된 기록은 비어 있으며 누구도 수정·삭제할 수 없습니다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SourceType sourceType;

    /** UPLOAD: 업로드 폴더 내 파일명, URL: 외부 영상 주소 */
    @Column(nullable = false, length = 2048)
    private String videoPath;

    private String mimeType;
    private Long sizeBytes;
    private Double durationSec;

    @Column(nullable = false)
    private long views;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Replay() {
    }

    public Replay(User owner, String title, String description, SourceType sourceType,
                  String videoPath, String mimeType, Long sizeBytes, Double durationSec) {
        this.owner = owner;
        this.title = title;
        this.description = description;
        this.sourceType = sourceType;
        this.videoPath = videoPath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.durationSec = durationSec;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public void increaseViews() {
        views++;
    }

    public void edit(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public SourceType getSourceType() { return sourceType; }
    public String getVideoPath() { return videoPath; }
    public String getMimeType() { return mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public Double getDurationSec() { return durationSec; }
    public long getViews() { return views; }
    public Instant getCreatedAt() { return createdAt; }
}
