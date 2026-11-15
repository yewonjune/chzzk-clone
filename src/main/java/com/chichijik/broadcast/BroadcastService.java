package com.chichijik.broadcast;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chichijik.common.ApiException;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);
    private static final String NOT_FOUND = "방송을 찾을 수 없습니다.";
    /** 방송을 시작하고 이 시간 안에 송출자 연결이 없으면 종료로 처리합니다. */
    static final Duration HOST_CONNECT_GRACE = Duration.ofSeconds(30);

    private final BroadcastRepository repository;
    private final LiveRooms rooms;

    public BroadcastService(BroadcastRepository repository, LiveRooms rooms) {
        this.repository = repository;
        this.rooms = rooms;
    }

    /** 방송 중을 먼저(최근 시작 순), 그다음 최근 종료된 방송 30개 */
    @Transactional(readOnly = true)
    public List<BroadcastResponse> list() {
        List<BroadcastResponse> result = new ArrayList<>();
        for (Broadcast b : repository.findAllByStatusOrderByStartedAtDesc(Broadcast.Status.LIVE)) {
            result.add(BroadcastResponse.from(b, rooms.viewerCount(b.getId())));
        }
        for (Broadcast b : repository.findTop30ByStatusOrderByEndedAtDesc(Broadcast.Status.ENDED)) {
            result.add(BroadcastResponse.from(b, 0));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public BroadcastResponse get(long id) {
        Broadcast b = find(id);
        return BroadcastResponse.from(b, rooms.viewerCount(id));
    }

    @Transactional
    public BroadcastResponse.Started start(String rawTitle, String rawStreamer) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String streamer = rawStreamer == null ? "" : rawStreamer.trim();
        if (title.isEmpty()) throw ApiException.badRequest("방송 제목을 입력해 주세요.");
        if (title.length() > 100) throw ApiException.badRequest("방송 제목은 100자 이하로 입력해 주세요.");
        if (streamer.length() > 40) throw ApiException.badRequest("스트리머 이름은 40자 이하로 입력해 주세요.");

        String key = StreamKeys.generate();
        Broadcast b = repository.save(new Broadcast(title, streamer, StreamKeys.hash(key), Instant.now()));
        return new BroadcastResponse.Started(BroadcastResponse.from(b, 0), key);
    }

    /** 송출 키를 가진 쪽(방송을 시작한 기기)만 종료할 수 있습니다. 이미 종료됐으면 그대로 성공. */
    @Transactional
    public BroadcastResponse end(long id, String streamKey) {
        Broadcast b = find(id);
        if (!StreamKeys.matches(streamKey, b.getStreamKeyHash())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "이 방송을 종료할 권한이 없습니다.");
        }
        endAndClose(b);
        return BroadcastResponse.from(b, 0);
    }

    /** 송출 키가 이 방송의 것인지 (시그널링 연결 시 송출자 확인용) */
    @Transactional(readOnly = true)
    public boolean isLiveAndKeyMatches(long id, String streamKey) {
        return repository.findById(id)
                .filter(Broadcast::isLive)
                .filter(b -> StreamKeys.matches(streamKey, b.getStreamKeyHash()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public boolean isLive(long id) {
        return repository.findById(id).map(Broadcast::isLive).orElse(false);
    }

    /** 송출자 연결이 끊기면 즉시 종료합니다. */
    @Transactional
    public void endBecauseHostLeft(long id) {
        repository.findById(id).ifPresent(this::endAndClose);
    }

    /** 방송을 시작만 하고 송출 화면이 연결되지 않은 경우(바로 창을 닫는 등)를 정리합니다. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @Transactional
    public void endOrphans() {
        Instant cutoff = Instant.now().minus(HOST_CONNECT_GRACE);
        for (Broadcast b : repository.findAllByStatusOrderByStartedAtDesc(Broadcast.Status.LIVE)) {
            if (b.getStartedAt().isBefore(cutoff) && !rooms.hasHost(b.getId())) {
                log.info("송출 연결이 없는 방송 {} 종료", b.getId());
                endAndClose(b);
            }
        }
    }

    /** 서버가 재시작되면 송출 연결이 모두 사라지므로, 남아 있는 방송 중을 모두 종료합니다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void endLeftoversOnStartup() {
        int n = repository.endAllLive(Instant.now());
        if (n > 0) log.info("재시작 전 방송 중이던 방송 {}개를 종료로 바꿨습니다.", n);
    }

    /**
     * 종료 기록이 DB에 커밋된 뒤에 시청자·송출자에게 ended 를 보냅니다.
     * (커밋 전에 보내면 클라이언트가 곧바로 다시 조회했을 때 아직 방송 중으로 보입니다.)
     */
    private void endAndClose(Broadcast b) {
        b.end(Instant.now());
        long id = b.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rooms.close(id);
                }
            });
        } else {
            rooms.close(id);
        }
    }

    private Broadcast find(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }
}
