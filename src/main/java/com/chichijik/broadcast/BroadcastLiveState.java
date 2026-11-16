package com.chichijik.broadcast;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.socket.WebSocketSession;

/**
 * 방송 실시간 상태: 방송 중인 방송마다 송출자 기록 1개와 시청자 집합, 시청자 수, 방송 중 메시지 전달.
 * 방송·댓글·후원 서비스와 시그널링은 이 인터페이스만 쓰고, 상태가 어디에 있는지(인스턴스 메모리, Redis 등)는 모릅니다.
 * 서버는 영상을 받지 않고, 송출자와 시청자 사이의 WebRTC 연결 정보만 전달합니다(ADR-0001).
 *
 * 재연결 유예(용어집): 연결이 끊겨도 기록은 바로 지우지 않습니다.
 *   - 송출자 기록은 "끊긴 시각"을 남기고, 정리 작업이 {@link #hostDisconnectedAt}으로 유예가 지났는지 판단해 방송을 종료합니다.
 *   - 비정상으로 끊긴 시청자는 집합에 남아 있다가({@link #hasViewer}), 같은 viewerId 로 다시 붙으면({@link #resumeViewer})
 *     같은 시청자로 이어집니다. 유예 안에 돌아오지 않으면 {@link #dropViewersDisconnectedBefore}로 퇴장 처리합니다.
 *
 * 프로토콜 (모두 JSON 텍스트 메시지)
 *   서버 → 송출자 : viewer-joined{viewerId}, viewer-left{viewerId}, signal{from, data}, viewers{count}, ended, error{message}
 *   송출자 → 서버 : join{role:host}, signal{to, data}
 *   서버 → 시청자 : joined{viewerId, resumed}, signal{data}, viewers{count}, ended, full{max}
 *   시청자 → 서버 : join{role:viewer, viewerId?, startOver?}, signal{data}
 *   서버 → 모두   : comment, donation, title (방송 중 메시지)
 *   서버가 내려갈 때는 열린 연결을 4001("다른 서버로 재연결")로 닫습니다.
 */
public interface BroadcastLiveState {

    enum JoinResult { OK, FULL }

    /**
     * 송출자 연결. 이미 연결된 송출자가 있으면 이전 연결을 끊고 교체합니다. 끊긴 기록이 있으면 지웁니다(같은 방송이 이어짐).
     * 집합에 있는 시청자를 송출자에게 viewer-joined 로 알려 줍니다(재연결한 송출 화면은 P2P 가 살아 있는 시청자는 그대로 둠).
     */
    void attachHost(long broadcastId, WebSocketSession host);

    /**
     * 새 시청자 연결. 시청자 수가 상한에 이르렀으면 FULL 이고 연결을 추가하지 않습니다.
     * startingOver 가 있으면 같은 시청자 화면이 영상이 끊겨 처음부터 다시 입장하는 것이므로, 그 viewerId 의 이전 자리를
     * 먼저 비웁니다(송출자에게 viewer-left, 시청자 수·상한에서 바로 빠짐). 이 방송에 그 자리가 없으면 아무것도 비우지 않습니다.
     */
    JoinResult attachViewer(long broadcastId, String viewerId, WebSocketSession viewer, int maxViewers, String startingOver);

    /**
     * 같은 시청자로 다시 연결. viewerId 가 아직 집합에 있으면 연결만 바꾸고 true(시청자 수 그대로, 송출자에게 알리지 않음),
     * 없으면 false(새 시청자로 입장해야 함).
     */
    boolean resumeViewer(long broadcastId, String viewerId, WebSocketSession viewer);

    /** viewerId 가 아직 이 방송의 시청자 집합에 있는지(연결이 끊겨 유예 중인 시청자 포함) */
    boolean hasViewer(long broadcastId, String viewerId);

    /**
     * 송출자 연결이 닫힘. 이 연결이 현재 송출자였으면 끊긴 시각(지금)을 기록하고 true.
     * 교체된 이전 연결이면 아무것도 하지 않고 false. 방송은 종료하지 않습니다(재연결 유예).
     */
    boolean detachHost(long broadcastId, WebSocketSession host);

    /**
     * 이 서버가 뜬 직후, 방송 중인 방송마다 한 번 불립니다.
     * 기록을 서버 메모리에만 두어 재시작으로 잃는 구현은, 송출자 기록이 없는 방송을 이 시각에 끊긴 것으로 기록해
     * 바로 종료하지 않고 재연결 유예를 줍니다.
     * 기록을 서버 밖에 함께 두는 구현은 아무것도 하지 않습니다. 서버 하나가 뜬다고 기록이 사라지지 않으므로
     * 서버를 늘려도(스케일 아웃) 어떤 방송의 결과도 바뀌지 않아야 합니다(송출자가 아직 붙지 않은 방송은 시작 후 30초 규칙 그대로).
     */
    void recoverAfterRestart(long broadcastId, Instant at);

    /** 송출자 연결이 끊긴 상태면 끊긴 시각. 연결 중이거나 한 번도 연결된 적이 없으면 비어 있음 */
    Optional<Instant> hostDisconnectedAt(long broadcastId);

    /**
     * 시청자 연결이 닫힘(현재 연결일 때만 반영). leaving 이면 바로 퇴장(viewer-left, 시청자 수 갱신),
     * 아니면(비정상 종료·서버 종료 등) 끊긴 시각을 남기고 재연결 유예 동안 자리를 남겨 둡니다.
     */
    void detachViewer(long broadcastId, String viewerId, WebSocketSession viewer, boolean leaving);

    /** 연결이 끊긴 시각이 cutoff 이전인 시청자를 퇴장 처리합니다 (재연결 유예가 지난 시청자). */
    void dropViewersDisconnectedBefore(long broadcastId, Instant cutoff);

    /**
     * 하트비트가 사라진 서버(강제 종료 등) 소속 기록 정리. 그 서버에 연결된 것으로 남은 시청자는 퇴장 처리하고(시청자 수·상한 회복),
     * 송출자 기록은 지금 끊긴 것으로 바꿔 재연결 유예 판단으로 넘깁니다. 이미 끊김으로 기록된 시청자는 재연결 유예가 판단합니다.
     * 여러 서버에서 동시에 돌아도 결과는 한 번 처리한 것과 같습니다. 서버가 하나뿐인 구현에서는 할 일이 없습니다.
     */
    void releaseDeadInstances(long broadcastId);

    /** 송출자 → 시청자 한 명에게 시그널 전달 */
    void relayToViewer(long broadcastId, String viewerId, Object data);

    /** 시청자 → 송출자에게 시그널 전달 */
    void relayToHost(long broadcastId, String viewerId, Object data);

    /** 방송 중인 방송의 송출자·시청자 모두에게 보냅니다 (댓글, 후원, 제목 변경 등). */
    void sendToAll(long broadcastId, Map<String, ?> message);

    /**
     * 방송이 종료됐음을 알립니다: 송출자·시청자에게 ended 를 보내고 연결을 모두 닫고 기록을 지웁니다.
     * 종료로 실제로 바꾼 쪽(방송 중 → 종료 조건부 갱신에 성공한 쪽)만 호출하므로 방송마다 한 번만 불립니다.
     */
    void ended(long broadcastId);

    /** 시청자 수 (재연결 유예 중인 시청자 포함) */
    int viewerCount(long broadcastId);

    /** 송출자가 지금 연결되어 있는지 */
    boolean hasHost(long broadcastId);
}
