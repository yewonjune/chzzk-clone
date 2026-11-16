package com.chichijik.broadcast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 방송 실시간 상태의 메모리 구현(기본 프로필, 단일 인스턴스). cluster 프로필에서는 {@link RedisBroadcastLiveState}.
 * 방송 중인 방송마다 송출자 1명과 시청자 연결(WebSocket)을 이 인스턴스 메모리에 들고 있습니다.
 */
@Component
@Profile("!cluster")
public class InMemoryBroadcastLiveState implements BroadcastLiveState {

    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();

    static final class Room {
        WebSocketSession host;
        final Map<String, WebSocketSession> viewers = new LinkedHashMap<>();
    }

    private Room room(long id) {
        return rooms.computeIfAbsent(id, k -> new Room());
    }

    /** 송출자 연결. 이미 연결된 송출자가 있으면 이전 연결을 끊고 교체합니다. 대기 중인 시청자를 알려줍니다. */
    @Override
    public void attachHost(long id, WebSocketSession host) {
        Room r = room(id);
        WebSocketSession previous;
        List<String> waiting;
        synchronized (r) {
            previous = r.host;
            r.host = host;
            waiting = List.copyOf(r.viewers.keySet());
        }
        if (previous != null && previous.isOpen()) WebSockets.closeQuietly(previous, CloseStatus.POLICY_VIOLATION);
        for (String viewerId : waiting) WebSockets.send(host, Map.of("type", "viewer-joined", "viewerId", viewerId));
        broadcastViewerCount(id);
    }

    @Override
    public JoinResult attachViewer(long id, String viewerId, WebSocketSession viewer, int maxViewers) {
        Room r = room(id);
        WebSocketSession host;
        synchronized (r) {
            if (r.viewers.size() >= maxViewers) return JoinResult.FULL;
            r.viewers.put(viewerId, viewer);
            host = r.host;
        }
        if (host != null) WebSockets.send(host, Map.of("type", "viewer-joined", "viewerId", viewerId));
        broadcastViewerCount(id);
        return JoinResult.OK;
    }

    /** @return 이 세션이 현재 송출자였으면 true (송출 종료 처리 필요) */
    @Override
    public boolean detachHost(long id, WebSocketSession session) {
        Room r = rooms.get(id);
        if (r == null) return false;
        synchronized (r) {
            if (r.host != session) return false;
            r.host = null;
            return true;
        }
    }

    @Override
    public void detachViewer(long id, String viewerId) {
        Room r = rooms.get(id);
        if (r == null) return;
        WebSocketSession host;
        synchronized (r) {
            if (r.viewers.remove(viewerId) == null) return;
            host = r.host;
        }
        if (host != null) WebSockets.send(host, Map.of("type", "viewer-left", "viewerId", viewerId));
        broadcastViewerCount(id);
    }

    @Override
    public void relayToViewer(long id, String viewerId, Object data) {
        Room r = rooms.get(id);
        if (r == null) return;
        WebSocketSession viewer;
        synchronized (r) {
            viewer = r.viewers.get(viewerId);
        }
        if (viewer != null) WebSockets.send(viewer, Map.of("type", "signal", "data", data));
    }

    @Override
    public void relayToHost(long id, String viewerId, Object data) {
        Room r = rooms.get(id);
        if (r == null) return;
        WebSocketSession host;
        synchronized (r) {
            host = r.host;
        }
        if (host != null) WebSockets.send(host, Map.of("type", "signal", "from", viewerId, "data", data));
    }

    /** 방송 종료: 시청자에게 ended를 보내고 모든 연결을 닫은 뒤 방을 없앱니다. */
    @Override
    public void ended(long id) {
        Room r = rooms.remove(id);
        if (r == null) return;
        List<WebSocketSession> all;
        WebSocketSession host;
        synchronized (r) {
            all = List.copyOf(r.viewers.values());
            host = r.host;
            r.viewers.clear();
            r.host = null;
        }
        for (WebSocketSession v : all) {
            WebSockets.send(v, Map.of("type", "ended"));
            WebSockets.closeQuietly(v, CloseStatus.NORMAL);
        }
        if (host != null) {
            WebSockets.send(host, Map.of("type", "ended"));
            WebSockets.closeQuietly(host, CloseStatus.NORMAL);
        }
    }

    /** 방송 중인 방의 송출자·시청자 모두에게 보냅니다 (댓글, 제목 변경 등) */
    @Override
    public void sendToAll(long id, Map<String, ?> message) {
        Room r = rooms.get(id);
        if (r == null) return;
        List<WebSocketSession> targets;
        synchronized (r) {
            targets = new ArrayList<>(r.viewers.values());
            if (r.host != null) targets.add(r.host);
        }
        for (WebSocketSession s : targets) WebSockets.send(s, message);
    }

    @Override
    public int viewerCount(long id) {
        Room r = rooms.get(id);
        if (r == null) return 0;
        synchronized (r) {
            return r.viewers.size();
        }
    }

    @Override
    public boolean hasHost(long id) {
        Room r = rooms.get(id);
        if (r == null) return false;
        synchronized (r) {
            return r.host != null && r.host.isOpen();
        }
    }

    private void broadcastViewerCount(long id) {
        Room r = rooms.get(id);
        if (r == null) return;
        List<WebSocketSession> targets;
        int count;
        synchronized (r) {
            targets = new ArrayList<>(r.viewers.values());
            if (r.host != null) targets.add(r.host);
            count = r.viewers.size();
        }
        Map<String, Object> msg = Map.of("type", "viewers", "count", count);
        for (WebSocketSession s : targets) WebSockets.send(s, msg);
    }
}
