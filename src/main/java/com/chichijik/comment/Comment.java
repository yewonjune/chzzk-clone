package com.chichijik.comment;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.chichijik.broadcast.Broadcast;
import com.chichijik.replay.Replay;
import com.chichijik.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 댓글: 방송 하나 또는 다시보기 하나에 달립니다 (둘 중 정확히 하나만 값이 있음).
 * 대상이 삭제되면 DB의 ON DELETE CASCADE 로 함께 삭제됩니다.
 */
@Entity
@Table(name = "comments", indexes = {
        // 방송별/다시보기별 최신순 목록(키셋 페이지네이션)을 인덱스로 처리
        @Index(name = "idx_comments_broadcast", columnList = "broadcast_id, id"),
        @Index(name = "idx_comments_replay", columnList = "replay_id, id")
})
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Broadcast broadcast;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replay_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Replay replay;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User author;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Comment() {
    }

    private Comment(Broadcast broadcast, Replay replay, User author, String content, Instant now) {
        this.broadcast = broadcast;
        this.replay = replay;
        this.author = author;
        this.content = content;
        this.createdAt = now;
    }

    static Comment onBroadcast(Broadcast b, User author, String content) {
        return new Comment(b, null, author, content, Instant.now());
    }

    static Comment onReplay(Replay r, User author, String content) {
        return new Comment(null, r, author, content, Instant.now());
    }

    void edit(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }

    /** 이 댓글이 달린 방송·다시보기의 소유자 (댓글 삭제 권한 판단용) */
    User targetOwner() {
        return broadcast != null ? broadcast.getOwner() : replay.getOwner();
    }

    public Long getId() { return id; }
    public Broadcast getBroadcast() { return broadcast; }
    public Replay getReplay() { return replay; }
    public User getAuthor() { return author; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
