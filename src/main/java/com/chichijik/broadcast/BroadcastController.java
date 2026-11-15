package com.chichijik.broadcast;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    /** 송출 키를 전달하는 헤더 (URL에 넣으면 로그·기록에 남을 수 있어 헤더로 받습니다) */
    static final String STREAM_KEY_HEADER = "X-Stream-Key";

    private final BroadcastService service;

    public BroadcastController(BroadcastService service) {
        this.service = service;
    }

    public record StartRequest(String title, String streamer) {
    }

    @GetMapping
    public List<BroadcastResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public BroadcastResponse get(@PathVariable long id) {
        return service.get(id);
    }

    /** 방송 시작: 응답의 streamKey 는 이 한 번만 내려갑니다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastResponse.Started start(@RequestBody StartRequest req) {
        return service.start(req.title(), req.streamer());
    }

    @PostMapping("/{id}/end")
    public BroadcastResponse end(@PathVariable long id,
                                 @RequestHeader(value = STREAM_KEY_HEADER, required = false) String streamKey) {
        return service.end(id, streamKey);
    }
}
