package com.chzzk.broadcast;

import java.util.Map;

/**
 * 시그널링 프로토콜에서 서버가 보내는 메시지 (프로토콜 전체는 {@link BroadcastLiveState}).
 * 송출자·시청자 연결로 보내는 JSON 의 모양은 모두 여기서 만듭니다.
 */
final class SignalingMessages {

    /** 다른 화면이 같은 방송의 송출을 이어받아 이전 송출 연결을 닫을 때의 안내 */
    static final String HOST_REPLACED = "다른 화면에서 송출을 이어받았습니다.";

    private SignalingMessages() {
    }

    /** 서버 → 송출자: 시청자가 입장함 (송출자는 이 시청자에게 offer 를 보냄) */
    static Map<String, Object> viewerJoined(String viewerId) {
        return Map.of("type", "viewer-joined", "viewerId", viewerId);
    }

    /** 서버 → 송출자: 시청자가 나감 */
    static Map<String, Object> viewerLeft(String viewerId) {
        return Map.of("type", "viewer-left", "viewerId", viewerId);
    }

    /** 서버 → 송출자: 시청자가 보낸 시그널 */
    static Map<String, Object> signalFromViewer(String viewerId, Object data) {
        return Map.of("type", "signal", "from", viewerId, "data", data);
    }

    /** 서버 → 시청자: 송출자가 보낸 시그널 */
    static Map<String, Object> signalFromHost(Object data) {
        return Map.of("type", "signal", "data", data);
    }

    /** 서버 → 모두: 시청자 수 */
    static Map<String, Object> viewers(int count) {
        return Map.of("type", "viewers", "count", count);
    }

    /** 서버 → 시청자: 입장 확인. resumed 면 같은 시청자로 이어 붙음 */
    static Map<String, Object> joined(String viewerId, boolean resumed) {
        return Map.of("type", "joined", "viewerId", viewerId, "resumed", resumed);
    }

    /** 서버 → 시청자: 시청자 수 상한에 이름 */
    static Map<String, Object> full(int max) {
        return Map.of("type", "full", "max", max);
    }

    /** 서버 → 모두: 방송 종료 */
    static Map<String, Object> ended() {
        return Map.of("type", "ended");
    }

    /** 서버 → 모두: 방송 제목이 바뀜 */
    static Map<String, Object> title(String title) {
        return Map.of("type", "title", "title", title);
    }

    /** 서버 → 송출자: 송출할 수 없음 (재연결하지 않음) */
    static Map<String, Object> error(String message) {
        return Map.of("type", "error", "message", message);
    }
}
