package com.chichijik.broadcast;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 방송 실시간 상태의 메모리 구현(기본 프로필, 단일 인스턴스). cluster 프로필에서는 {@link RedisBroadcastLiveState}.
 * 방송 중인 방송마다 송출자 기록 1개와 시청자 집합(WebSocket 연결)을 이 인스턴스 메모리에 들고 있습니다.
 * 서버가 재시작되면 기록이 사라지므로, 재시작 직후의 방송은 {@link #recoverAfterRestart}로 유예를 받습니다.
 */
@Component
@Profile("!cluster")
public class InMemoryBroadcastLiveState implements BroadcastLiveState {

    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();

    static final class Room {
        /** 지금 연결된 송출자 (없으면 null) */
        WebSocketSession host;
        /** 송출자 연결이 끊긴 시각 (연결 중이거나 한 번도 연결된 적이 없으면 null) */
        Instant hostDisconnectedAt;
        final Map<String, Viewer> viewers = new LinkedHashMap<>();
    }

    /** 시청자 집합의 한 자리. 연결이 끊겨 유예 중이면 session 이 null 이고 disconnectedAt 이 있음 */
    static final class Viewer {
        WebSocketSession session;
        Instant disconnectedAt;

        Viewer(WebSocketSession session) {
            this.session = session;
        }
    }

    private Room room(long id) {
        return rooms.computeIfAbsent(id, k -> new Room());
    }

    @Override
    public void attachHost(long id, WebSocketSession host) {
        Room r = room(id);
        WebSocketSession previous;
        List<String> present;
        synchronized (r) {
            previous = r.host;
            r.host = host;
            r.hostDisconnectedAt = null;
            present = List.copyOf(r.viewers.keySet());
        }
        if (previous != null && previous != host && previous.isOpen()) {
            WebSockets.send(previous, Map.of("type", "error", "message", "다른 화면에서 송출을 이어받았습니다."));
            WebSockets.closeQuietly(previous, CloseStatus.POLICY_VIOLATION);
        }
        for (String viewerId : present) WebSockets.send(host, Map.of("type", "viewer-joined", "viewerId", viewerId));
        broadcastViewerCount(id);
    }

    @Override
    public JoinResult attachViewer(long id, String viewerId, WebSocketSession viewer, int maxViewers, String startingOver) {
        Room r = room(id);
        WebSocketSession host;
        boolean released;
        boolean full;
        synchronized (r) {
            released = startingOver != null && r.viewers.remove(startingOver) != null;
            full = r.viewers.size() >= maxViewers;
            if (!full) r.viewers.put(viewerId, new Viewer(viewer));
            host = r.host;
        }
        if (host != null && released) WebSockets.send(host, Map.of("type", "viewer-left", "viewerId", startingOver));
        if (host != null && !full) WebSockets.send(host, Map.of("type", "viewer-joined", "viewerId", viewerId));
        if (released || !full) broadcastViewerCount(id);
        return full ? JoinResult.FULL : JoinResult.OK;
    }

    @Override
    public boolean resumeViewer(long id, String viewerId, WebSocketSession viewer) {
        Room r = rooms.get(id);
        if (r == null) return false;
        WebSocketSession previous;
        synchronized (r) {
            Viewer v = r.viewers.get(viewerId);
            if (v == null) return false;
            previous = v.session;
            v.session = viewer;
            v.disconnectedAt = null;
        }
        // 서버가 아직 끊김을 모르는 이전 연결이 남아 있으면 닫음 (현재 연결이 아니므로 퇴장 처리되지 않음)
        if (previous != null && previous != viewer) WebSockets.closeQuietly(previous, CloseStatus.NORMAL);
        return true;
    }

    @Override
    public boolean hasViewer(long id, String viewerId) {
        Room r = rooms.get(id);
        if (r == null) return false;
        synchronized (r) {
            return r.viewers.containsKey(viewerId);
        }
    }

    @Override
    public boolean detachHost(long id, WebSocketSession session) {
        Room r = rooms.get(id);
        if (r == null) return false;
        synchronized (r) {
            if (r.host != session) return false;
            r.host = null;
            r.hostDisconnectedAt = Instant.now();
            return true;
        }
    }

    /** 재시작으로 기록을 모두 잃었으므로, 송출자 기록이 없는 방송은 이 시각에 끊긴 것으로 보고 재연결 유예를 줍니다. */
    @Override
    public void recoverAfterRestart(long id, Instant at) {
        Room r = room(id);
        synchronized (r) {
            if (r.host == null && r.hostDisconnectedAt == null) r.hostDisconnectedAt = at;
        }
    }

    @Override
    public Optional<Instant> hostDisconnectedAt(long id) {
        Room r = rooms.get(id);
        if (r == null) return Optional.empty();
        synchronized (r) {
            return Optional.ofNullable(r.hostDisconnectedAt);
        }
    }

    @Override
    public void detachViewer(long id, String viewerId, WebSocketSession session, boolean leaving) {
        Room r = rooms.get(id);
        if (r == null) return;
        synchronized (r) {
            Viewer v = r.viewers.get(viewerId);
            if (v == null || v.session != session) return; // 다른 연결로 이미 이어 붙은 시청자
            if (!leaving) {
                v.session = null;
                v.disconnectedAt = Instant.now();
                return;
            }
        }
        removeViewers(id, List.of(viewerId));
    }

    /** 서버가 하나뿐이고 기록이 이 서버 메모리에 있으므로, 다른 서버가 죽어 남긴 기록은 없습니다. */
    @Override
    public void releaseDeadInstances(long id) {
    }

    @Override
    public void dropViewersDisconnectedBefore(long id, Instant cutoff) {
        Room r = rooms.get(id);
        if (r == null) return;
        List<String> expired = new ArrayList<>();
        synchronized (r) {
            r.viewers.forEach((viewerId, v) -> {
                if (v.session == null && v.disconnectedAt != null && v.disconnectedAt.isBefore(cutoff)) expired.add(viewerId);
            });
        }
        if (!expired.isEmpty()) removeViewers(id, expired);
    }

    private void removeViewers(long id, List<String> viewerIds) {
        Room r = rooms.get(id);
        if (r == null) return;
        List<String> removed = new ArrayList<>();
        WebSocketSession host;
        synchronized (r) {
            for (String viewerId : viewerIds) if (r.viewers.remove(viewerId) != null) removed.add(viewerId);
            host = r.host;
        }
        if (removed.isEmpty()) return;
        if (host != null) for (String viewerId : removed) WebSockets.send(host, Map.of("type", "viewer-left", "viewerId", viewerId));
        broadcastViewerCount(id);
    }

    @Override
    public void relayToViewer(long id, String viewerId, Object data) {
        Room r = rooms.get(id);
        if (r == null) return;
        WebSocketSession viewer;
        synchronized (r) {
            Viewer v = r.viewers.get(viewerId);
            viewer = v == null ? null : v.session;
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

    /** 방송 종료: 송출자·시청자에게 ended 를 보내고 모든 연결을 닫은 뒤 기록을 없앱니다. */
    @Override
    public void ended(long id) {
        Room r = rooms.remove(id);
        if (r == null) return;
        List<WebSocketSession> targets;
        synchronized (r) {
            targets = connected(r);
            r.viewers.clear();
            r.host = null;
        }
        for (WebSocketSession s : targets) {
            WebSockets.send(s, Map.of("type", "ended"));
            WebSockets.closeQuietly(s, CloseStatus.NORMAL);
        }
    }

    /** 방송 중인 방의 송출자·시청자 모두에게 보냅니다 (댓글, 제목 변경 등) */
    @Override
    public void sendToAll(long id, Map<String, ?> message) {
        Room r = rooms.get(id);
        if (r == null) return;
        List<WebSocketSession> targets;
        synchronized (r) {
            targets = connected(r);
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
            targets = connected(r);
            count = r.viewers.size();
        }
        Map<String, Object> msg = Map.of("type", "viewers", "count", count);
        for (WebSocketSession s : targets) WebSockets.send(s, msg);
    }

    /** 지금 연결된 시청자들과 송출자 (synchronized (r) 안에서 호출) */
    private static List<WebSocketSession> connected(Room r) {
        List<WebSocketSession> targets = new ArrayList<>();
        for (Viewer v : r.viewers.values()) if (v.session != null) targets.add(v.session);
        if (r.host != null) targets.add(r.host);
        return targets;
    }
}
