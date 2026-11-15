package com.chichijik.replay;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.chichijik.user.LoginUser;

@RestController
@RequestMapping("/api/replays")
public class ReplayController {

    private final ReplayService service;

    public ReplayController(ReplayService service) {
        this.service = service;
    }

    public record EditRequest(String title, String description) {
    }

    @GetMapping
    public List<ReplayResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ReplayResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/views")
    public Map<String, Long> increaseViews(@PathVariable long id) {
        return Map.of("views", service.increaseViews(id));
    }

    /** 등록: 로그인 필요, 등록한 사용자가 소유자(스트리머)가 됩니다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReplayResponse create(
            @AuthenticationPrincipal LoginUser me,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String videoUrl,
            @RequestParam(required = false) String durationSec,
            @RequestPart(required = false) MultipartFile video) {
        return service.create(me, title, description, videoUrl, durationSec, video);
    }

    /** 수정: 소유자만 (아니면 403) */
    @PatchMapping("/{id}")
    public ReplayResponse edit(@AuthenticationPrincipal LoginUser me, @PathVariable long id, @RequestBody EditRequest body) {
        return service.edit(id, me, body.title(), body.description());
    }

    /** 삭제: 소유자만 (아니면 403) */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal LoginUser me, @PathVariable long id) {
        service.delete(id, me);
    }
}
