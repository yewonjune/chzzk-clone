package com.chichijik.broadcast;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.chichijik.user.LoginUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * /ws/broadcasts/{id} WebRTC 시그널링.
 * 연결 직후 첫 메시지로 역할을 밝힙니다.
 *   송출자: {"type":"join","role":"host"}   ← 로그인 세션의 사용자가 방송 소유자여야 함
 *   시청자: {"type":"join","role":"viewer"}
 * 이후에는 signal 메시지(SDP offer/answer, ICE candidate)를 상대에게 그대로 전달합니다.
 */
@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ATTR_ID = "broadcastId";
    private static final String ATTR_ROLE = "role";
    private static final String ATTR_VIEWER_ID = "viewerId";
    private static final String ATTR_SAFE = "safeSession";

    private final BroadcastService service;
    private final BroadcastLiveState liveState;
    private final int maxViewers;

    public SignalingHandler(BroadcastService service, BroadcastLiveState liveState,
                            @Value("${app.live.max-viewers}") int maxViewers) {
        this.service = service;
        this.liveState = liveState;
        this.maxViewers = maxViewers;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long id = parseId(session);
        if (id == null) {
            WebSockets.closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        session.getAttributes().put(ATTR_ID, id);
        // 여러 스레드가 같은 세션에 보낼 수 있으므로 동시 전송을 직렬화합니다.
        session.getAttributes().put(ATTR_SAFE, new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WebSocketSession session = safe(raw);
        Long id = (Long) raw.getAttributes().get(ATTR_ID);
        if (id == null) return;

        JsonNode msg;
        try {
            msg = JSON.readTree(message.getPayload());
        } catch (RuntimeException e) {
            return; // 잘못된 메시지는 무시
        }
        String type = text(msg, "type");
        String role = (String) raw.getAttributes().get(ATTR_ROLE);

        if (role == null) {
            if ("join".equals(type)) join(raw, session, id, msg);
            return;
        }
        if (!"signal".equals(type) || !msg.has("data")) return;

        Object data = JSON.convertValue(msg.get("data"), Object.class);
        if ("host".equals(role)) {
            String to = text(msg, "to");
            if (to != null) liveState.relayToViewer(id, to, data);
        } else {
            liveState.relayToHost(id, (String) raw.getAttributes().get(ATTR_VIEWER_ID), data);
        }
    }

    private void join(WebSocketSession raw, WebSocketSession session, long id, JsonNode msg) {
        if ("host".equals(text(msg, "role"))) {
            // 송출자 권한: WebSocket 연결 요청에 실려 온 로그인 세션의 사용자가 이 방송의 소유자인지 서버에서 확인
            LoginUser me = loginUser(raw);
            if (me == null || !service.canHost(id, me)) {
                String reason = me == null ? "로그인이 필요합니다." : "이 방송의 소유자만 송출할 수 있습니다. (또는 이미 종료된 방송)";
                WebSockets.send(session, Map.of("type", "error", "message", reason));
                WebSockets.closeQuietly(raw, CloseStatus.POLICY_VIOLATION);
                return;
            }
            raw.getAttributes().put(ATTR_ROLE, "host");
            liveState.attachHost(id, session);
            return;
        }

        if (!service.isLive(id)) {
            WebSockets.send(session, Map.of("type", "ended"));
            WebSockets.closeQuietly(raw, CloseStatus.NORMAL);
            return;
        }
        String viewerId = WebSockets.newViewerId();
        if (liveState.attachViewer(id, viewerId, session, maxViewers) == BroadcastLiveState.JoinResult.FULL) {
            WebSockets.send(session, Map.of("type", "full", "max", maxViewers));
            WebSockets.closeQuietly(raw, CloseStatus.NORMAL);
            return;
        }
        raw.getAttributes().put(ATTR_ROLE, "viewer");
        raw.getAttributes().put(ATTR_VIEWER_ID, viewerId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Long id = (Long) raw.getAttributes().get(ATTR_ID);
        String role = (String) raw.getAttributes().get(ATTR_ROLE);
        if (id == null || role == null) return;
        if ("host".equals(role)) {
            // 송출자 연결이 끊기면 즉시 종료 (교체된 이전 연결이면 무시)
            if (liveState.detachHost(id, safe(raw))) service.endBecauseHostLeft(id);
        } else {
            liveState.detachViewer(id, (String) raw.getAttributes().get(ATTR_VIEWER_ID));
        }
    }

    private static LoginUser loginUser(WebSocketSession raw) {
        if (raw.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof LoginUser u) return u;
        return null;
    }

    private static WebSocketSession safe(WebSocketSession raw) {
        Object s = raw.getAttributes().get(ATTR_SAFE);
        return s instanceof WebSocketSession ws ? ws : raw;
    }

    private static Long parseId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String path = session.getUri().getPath();
        try {
            long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
