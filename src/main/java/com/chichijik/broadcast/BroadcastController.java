package com.chichijik.broadcast;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    private final BroadcastService service;

    public BroadcastController(BroadcastService service) {
        this.service = service;
    }

    @GetMapping
    public List<BroadcastResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public BroadcastResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/views")
    public Map<String, Long> increaseViews(@PathVariable long id) {
        return Map.of("views", service.increaseViews(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastResponse create(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String streamer,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String videoUrl,
            @RequestParam(required = false) String durationSec,
            @RequestPart(required = false) MultipartFile video) {
        return service.create(title, streamer, description, videoUrl, durationSec, video);
    }
}
