package com.chzzk.broadcast;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chzzk.user.LoginUser;

@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    private final BroadcastService service;

    public BroadcastController(BroadcastService service) {
        this.service = service;
    }

    public record TitleRequest(String title) {
    }

    @GetMapping
    public List<BroadcastResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public BroadcastResponse get(@PathVariable long id) {
        return service.get(id);
    }

    /** 방송 시작: 로그인 필요, 시작한 사용자가 소유자가 됩니다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastResponse start(@AuthenticationPrincipal LoginUser me, @RequestBody TitleRequest req) {
        return service.start(me, req.title());
    }

    /** 제목 수정: 소유자만 */
    @PatchMapping("/{id}")
    public BroadcastResponse rename(@AuthenticationPrincipal LoginUser me, @PathVariable long id, @RequestBody TitleRequest req) {
        return service.rename(id, me, req.title());
    }

    /** 송출 종료: 소유자만 */
    @PostMapping("/{id}/end")
    public BroadcastResponse end(@AuthenticationPrincipal LoginUser me, @PathVariable long id) {
        return service.end(id, me);
    }

    /** 종료된 방송 기록 삭제: 소유자만 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal LoginUser me, @PathVariable long id) {
        service.delete(id, me);
    }
}
