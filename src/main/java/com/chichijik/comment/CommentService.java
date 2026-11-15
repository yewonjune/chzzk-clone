package com.chichijik.comment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chichijik.broadcast.Broadcast;
import com.chichijik.broadcast.BroadcastRepository;
import com.chichijik.broadcast.LiveRooms;
import com.chichijik.common.AfterCommit;
import com.chichijik.common.ApiException;
import com.chichijik.common.Ownership;
import com.chichijik.replay.Replay;
import com.chichijik.replay.ReplayRepository;
import com.chichijik.user.LoginUser;
import com.chichijik.user.User;
import com.chichijik.user.UserRepository;

@Service
public class CommentService {

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 100;
    static final int MAX_LENGTH = 500;

    public enum Target { BROADCAST, REPLAY }

    /** 목록 한 페이지: 최신순, nextBefore 로 더 오래된 댓글을 이어서 조회 */
    public record Page(List<CommentResponse> comments, Long nextBefore) {
    }

    private final CommentRepository comments;
    private final BroadcastRepository broadcasts;
    private final ReplayRepository replays;
    private final UserRepository users;
    private final LiveRooms rooms;

    public CommentService(CommentRepository comments, BroadcastRepository broadcasts, ReplayRepository replays,
                          UserRepository users, LiveRooms rooms) {
        this.comments = comments;
        this.broadcasts = broadcasts;
        this.replays = replays;
        this.users = users;
        this.rooms = rooms;
    }

    /** 목록 조회는 공개 */
    @Transactional(readOnly = true)
    public Page list(Target target, long targetId, Long before, Integer size) {
        requireTargetExists(target, targetId);
        int limit = size == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, size));
        long cursor = before == null ? Long.MAX_VALUE : before;
        List<Comment> page = target == Target.BROADCAST
                ? comments.pageForBroadcast(targetId, cursor, Limit.of(limit))
                : comments.pageForReplay(targetId, cursor, Limit.of(limit));
        Long next = page.size() == limit ? page.get(page.size() - 1).getId() : null;
        return new Page(page.stream().map(CommentResponse::from).toList(), next);
    }

    /** 작성: 로그인 사용자 누구나 (방송 중·종료, 다시보기 모두) */
    @Transactional
    public CommentResponse create(Target target, long targetId, LoginUser me, String rawContent) {
        Ownership.requireLogin(me);
        String content = validContent(rawContent);
        User author = users.findById(me.id()).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        Comment c;
        if (target == Target.BROADCAST) {
            Broadcast b = broadcasts.findById(targetId).orElseThrow(() -> ApiException.notFound("방송을 찾을 수 없습니다."));
            c = comments.save(Comment.onBroadcast(b, author, content));
        } else {
            Replay r = replays.findById(targetId).orElseThrow(() -> ApiException.notFound("다시보기를 찾을 수 없습니다."));
            c = comments.save(Comment.onReplay(r, author, content));
        }
        CommentResponse res = CommentResponse.from(c);
        pushIfLive(c, Map.of("type", "comment", "action", "created", "comment", res));
        return res;
    }

    /** 수정: 작성자만 */
    @Transactional
    public CommentResponse edit(long commentId, LoginUser me, String rawContent) {
        Comment c = find(commentId);
        Ownership.requireOwner(c.getAuthor(), me, "댓글은 작성자만 수정할 수 있습니다.");
        c.edit(validContent(rawContent));
        CommentResponse res = CommentResponse.from(c);
        pushIfLive(c, Map.of("type", "comment", "action", "updated", "comment", res));
        return res;
    }

    /** 삭제: 작성자, 또는 그 방송·다시보기의 소유자 */
    @Transactional
    public void delete(long commentId, LoginUser me) {
        Comment c = find(commentId);
        Ownership.requireLogin(me);
        if (!Ownership.isOwner(c.getAuthor(), me) && !Ownership.isOwner(c.targetOwner(), me)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "댓글은 작성자나 방송·다시보기의 스트리머만 삭제할 수 있습니다.");
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "comment");
        msg.put("action", "deleted");
        msg.put("commentId", c.getId());
        pushIfLive(c, msg);
        comments.delete(c);
    }

    /** 방송 중인 방송의 댓글이면, 커밋 후 방의 모든 연결(송출자·시청자)에 바로 알립니다. */
    private void pushIfLive(Comment c, Map<String, ?> message) {
        Broadcast b = c.getBroadcast();
        if (b == null || !b.isLive()) return;
        long id = b.getId();
        AfterCommit.run(() -> rooms.sendToAll(id, message));
    }

    private void requireTargetExists(Target target, long id) {
        boolean exists = target == Target.BROADCAST ? broadcasts.existsById(id) : replays.existsById(id);
        if (!exists) throw ApiException.notFound(target == Target.BROADCAST ? "방송을 찾을 수 없습니다." : "다시보기를 찾을 수 없습니다.");
    }

    private Comment find(long id) {
        return comments.findForPermission(id).orElseThrow(() -> ApiException.notFound("댓글을 찾을 수 없습니다."));
    }

    private static String validContent(String raw) {
        String content = raw == null ? "" : raw.strip();
        if (content.isEmpty()) throw ApiException.badRequest("댓글 내용을 입력해 주세요.");
        if (content.length() > MAX_LENGTH) throw ApiException.badRequest("댓글은 " + MAX_LENGTH + "자 이하로 입력해 주세요.");
        return content;
    }
}
