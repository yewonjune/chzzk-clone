package com.chichijik.broadcast;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.json.JsonMapper;

/** 시그널링 WebSocket 에 JSON 메시지 보내기·닫기 */
final class WebSockets {

    private static final Logger log = LoggerFactory.getLogger(WebSockets.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WebSockets() {
    }

    static String newViewerId() {
        return UUID.randomUUID().toString();
    }

    static void send(WebSocketSession session, Map<String, ?> message) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(JSON.writeValueAsString(message)));
        } catch (IOException | IllegalStateException e) {
            log.debug("WebSocket 전송 실패: {}", e.getMessage());
        }
    }

    static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // 이미 닫힌 연결
        }
    }
}
