package com.chichijik.broadcast;

import java.util.Map;

import org.springframework.web.socket.WebSocketSession;

/**
 * 방송 실시간 상태: 방송 중인 방송마다 송출자 연결 1개와 시청자 연결들, 시청자 수, 방송 중 메시지 전달.
 * 방송·댓글·후원 서비스와 시그널링은 이 인터페이스만 쓰고, 상태가 어디에 있는지(인스턴스 메모리, Redis 등)는 모릅니다.
 * 서버는 영상을 받지 않고, 송출자와 시청자 사이의 WebRTC 연결 정보만 전달합니다(ADR-0001).
 *
 * 프로토콜 (모두 JSON 텍스트 메시지)
 *   서버 → 송출자 : viewer-joined{viewerId}, viewer-left{viewerId}, signal{from, data}, viewers{count}
 *   송출자 → 서버 : signal{to, data}
 *   서버 → 시청자 : signal{data}, viewers{count}, ended, full
 *   시청자 → 서버 : signal{data}
 *   서버 → 모두   : comment, donation, title (방송 중 메시지)
 */
public interface BroadcastLiveState {

    enum JoinResult { OK, FULL }

    /** 송출자 연결. 이미 연결된 송출자가 있으면 이전 연결을 끊고 교체합니다. 대기 중인 시청자를 송출자에게 알려줍니다. */
    void attachHost(long broadcastId, WebSocketSession host);

    /** 시청자 연결. 시청자 수가 상한에 이르렀으면 FULL 이고 연결을 추가하지 않습니다. */
    JoinResult attachViewer(long broadcastId, String viewerId, WebSocketSession viewer, int maxViewers);

    /** @return 이 연결이 현재 송출자였으면 true (교체된 이전 연결이면 false) */
    boolean detachHost(long broadcastId, WebSocketSession host);

    void detachViewer(long broadcastId, String viewerId);

    /** 송출자 → 시청자 한 명에게 시그널 전달 */
    void relayToViewer(long broadcastId, String viewerId, Object data);

    /** 시청자 → 송출자에게 시그널 전달 */
    void relayToHost(long broadcastId, String viewerId, Object data);

    /** 방송 중인 방송의 송출자·시청자 모두에게 보냅니다 (댓글, 후원, 제목 변경 등). */
    void sendToAll(long broadcastId, Map<String, ?> message);

    /**
     * 방송이 종료됐음을 알립니다: 송출자·시청자에게 ended 를 보내고 연결을 모두 닫습니다.
     * 종료로 실제로 바꾼 쪽(방송 중 → 종료 조건부 갱신에 성공한 쪽)만 호출하므로 방송마다 한 번만 불립니다.
     */
    void ended(long broadcastId);

    int viewerCount(long broadcastId);

    boolean hasHost(long broadcastId);
}
