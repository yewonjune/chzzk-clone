package com.chichijik.broadcast;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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
 *   시청자: {"type":"join","role":"viewer"}               ← 서버가 joined{viewerId, resumed:false} 로 응답
 *   재연결한 시청자: {"type":"join","role":"viewer","viewerId":"이전 id"}
 *       → 아직 시청자 집합에 있으면 joined{viewerId, resumed:true} (같은 시청자, 시청자 수 그대로, 송출자에게 알리지 않음)
 *       → 없으면 새 viewerId 로 새로 입장 (resumed:false)
 * 이후에는 signal 메시지(SDP offer/answer, ICE candidate)를 상대에게 그대로 전달합니다.
 * 송출자 연결이 끊겨도 방송을 바로 종료하지 않습니다(재연결 유예). 서버가 내려갈 때는 열린 연결을 4001로 닫습니다.
 */
@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ATTR_ID = "broadcastId";
    private static final String ATTR_ROLE = "role";
    private static final String ATTR_VIEWER_ID = "viewerId";
    private static final String ATTR_SAFE = "safeSession";
    /** 서버가 내려갈 때 쓰는 종료 코드: 클라이언트는 기다리지 않고 바로 (다른 서버로) 다시 연결합니다. */
    static final CloseStatus RECONNECT_ELSEWHERE = new CloseStatus(4001, "다른 서버로 재연결");

    /** 이 서버에 열려 있는 시그널링 연결 (서버 종료 시 4001로 닫기 위해) */
    private final Set<WebSocketSession> open = ConcurrentHashMap.newKeySet();

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
        open.add(session);
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
        // 재연결: 이전 viewerId 가 아직 시청자 집합에 있으면 같은 시청자로 이어 붙음 (시청자 수·송출자 알림 없음)
        String previousId = text(msg, "viewerId");
        if (previousId != null) {
            // 응답 전에 이 연결의 역할을 정해 두어야, 이어 붙자마자 오는 시그널을 받아 전달할 수 있습니다.
            raw.getAttributes().put(ATTR_ROLE, "viewer");
            raw.getAttributes().put(ATTR_VIEWER_ID, previousId);
            if (liveState.resumeViewer(id, previousId, session)) {
                WebSockets.send(session, Map.of("type", "joined", "viewerId", previousId, "resumed", true));
                WebSockets.send(session, Map.of("type", "viewers", "count", liveState.viewerCount(id)));
                return;
            }
            raw.getAttributes().remove(ATTR_ROLE);
            raw.getAttributes().remove(ATTR_VIEWER_ID);
        }
        String viewerId = WebSockets.newViewerId();
        raw.getAttributes().put(ATTR_ROLE, "viewer");
        raw.getAttributes().put(ATTR_VIEWER_ID, viewerId);
        if (liveState.attachViewer(id, viewerId, session, maxViewers) == BroadcastLiveState.JoinResult.FULL) {
            raw.getAttributes().remove(ATTR_ROLE);
            raw.getAttributes().remove(ATTR_VIEWER_ID);
            WebSockets.send(session, Map.of("type", "full", "max", maxViewers));
            WebSockets.closeQuietly(raw, CloseStatus.NORMAL);
            return;
        }
        WebSockets.send(session, Map.of("type", "joined", "viewerId", viewerId, "resumed", false));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        open.remove(raw);
        Long id = (Long) raw.getAttributes().get(ATTR_ID);
        String role = (String) raw.getAttributes().get(ATTR_ROLE);
        if (id == null || role == null) return;
        if ("host".equals(role)) {
            // 송출자 연결이 끊겨도 바로 종료하지 않고 끊긴 시각만 남깁니다 (재연결 유예 뒤 정리 작업이 종료)
            liveState.detachHost(id, safe(raw));
        } else {
            liveState.detachViewer(id, (String) raw.getAttributes().get(ATTR_VIEWER_ID), safe(raw), isLeaving(status));
        }
    }

    /**
     * 서버 종료 신호를 받으면(웹 서버가 멈추기 전) 열린 연결을 모두 4001로 닫습니다.
     * 송출자·시청자 기록은 끊김으로 남아 재연결 유예를 받습니다(방송을 종료하지 않음).
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAllForShutdown() {
        for (WebSocketSession s : List.copyOf(open)) WebSockets.closeQuietly(s, RECONNECT_ELSEWHERE);
    }

    /** 정상 종료(페이지를 떠남·화면이 닫음)면 퇴장. 비정상 종료나 서버 종료(4001)면 재연결을 기다립니다. */
    private static boolean isLeaving(CloseStatus status) {
        int code = status.getCode();
        return code == CloseStatus.NORMAL.getCode() || code == CloseStatus.GOING_AWAY.getCode();
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
