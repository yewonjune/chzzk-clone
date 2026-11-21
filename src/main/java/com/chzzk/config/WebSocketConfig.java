package com.chzzk.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.chzzk.broadcast.SignalingHandler;
import com.chzzk.desktop.DesktopProperties;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingHandler signalingHandler;
    private final DesktopProperties desktop;

    public WebSocketConfig(SignalingHandler signalingHandler, DesktopProperties desktop) {
        this.signalingHandler = signalingHandler;
        this.desktop = desktop;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 같은 출처(서버가 내려준 화면)는 항상 허용되고, 그 외에는 데스크톱 앱 출처(DESKTOP_ORIGINS)만 허용합니다.
        // 데스크톱 앱은 시청자로만 접속하며(쿠키 없음), 송출자 연결은 여전히 로그인한 소유자만 가능합니다.
        registry.addHandler(signalingHandler, "/ws/broadcasts/*")
                .setAllowedOrigins(desktop.origins().toArray(String[]::new));
    }
}
