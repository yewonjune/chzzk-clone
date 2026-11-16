package com.chzzk.comment;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chzzk.comment.CommentService.Target;
import com.chzzk.user.LoginUser;

@RestController
public class CommentController {

    private final CommentService service;

    public CommentController(CommentService service) {
        this.service = service;
    }

    public record ContentRequest(String content) {
    }

    // ---- 방송 댓글 ----

    @GetMapping("/api/broadcasts/{id}/comments")
    public CommentService.Page broadcastComments(@PathVariable long id,
                                                 @RequestParam(required = false) Long before,
                                                 @RequestParam(required = false) Integer size) {
        return service.list(Target.BROADCAST, id, before, size);
    }

    @PostMapping("/api/broadcasts/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse commentOnBroadcast(@AuthenticationPrincipal LoginUser me, @PathVariable long id,
                                              @RequestBody ContentRequest body) {
        return service.create(Target.BROADCAST, id, me, body.content());
    }

    // ---- 다시보기 댓글 ----

    @GetMapping("/api/replays/{id}/comments")
    public CommentService.Page replayComments(@PathVariable long id,
                                              @RequestParam(required = false) Long before,
                                              @RequestParam(required = false) Integer size) {
        return service.list(Target.REPLAY, id, before, size);
    }

    @PostMapping("/api/replays/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse commentOnReplay(@AuthenticationPrincipal LoginUser me, @PathVariable long id,
                                           @RequestBody ContentRequest body) {
        return service.create(Target.REPLAY, id, me, body.content());
    }

    // ---- 댓글 하나 ----

    /** 수정: 작성자만 */
    @PatchMapping("/api/comments/{id}")
    public CommentResponse edit(@AuthenticationPrincipal LoginUser me, @PathVariable long id,
                                @RequestBody ContentRequest body) {
        return service.edit(id, me, body.content());
    }

    /** 삭제: 작성자 또는 방송·다시보기 소유자 */
    @DeleteMapping("/api/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal LoginUser me, @PathVariable long id) {
        service.delete(id, me);
    }
}
