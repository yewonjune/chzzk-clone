package com.chzzk.broadcast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 WebRTC 설정: STUN/TURN 서버 목록과 시청자 상한.
 * TURN 계정은 브라우저가 직접 써야 하므로 응답에 포함됩니다(정적 TURN 계정의 한계, README 참고).
 */
@RestController
public class LiveConfigController {

    private final Map<String, Object> config;

    public LiveConfigController(@Value("${app.live.stun-urls}") String stunUrls,
                                @Value("${app.live.turn-url}") String turnUrl,
                                @Value("${app.live.turn-username}") String turnUsername,
                                @Value("${app.live.turn-password}") String turnPassword,
                                @Value("${app.live.max-viewers}") int maxViewers) {
        List<Map<String, Object>> iceServers = new ArrayList<>();
        List<String> stun = Arrays.stream(stunUrls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!stun.isEmpty()) iceServers.add(Map.of("urls", stun));
        if (!turnUrl.isBlank()) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("urls", turnUrl.trim());
            turn.put("username", turnUsername);
            turn.put("credential", turnPassword);
            iceServers.add(turn);
        }
        this.config = Map.of("iceServers", iceServers, "maxViewers", maxViewers);
    }

    @GetMapping("/api/live-config")
    public Map<String, Object> liveConfig() {
        return config;
    }
}
