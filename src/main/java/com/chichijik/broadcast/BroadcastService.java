package com.chichijik.broadcast;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chichijik.common.AfterCommit;
import com.chichijik.common.ApiException;
import com.chichijik.common.Ownership;
import com.chichijik.user.LoginUser;
import com.chichijik.user.User;
import com.chichijik.user.UserRepository;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);
    private static final String NOT_FOUND = "방송을 찾을 수 없습니다.";
    /** 방송을 시작하고 이 시간 안에 송출자 연결이 없으면 종료로 처리합니다. */
    static final Duration HOST_CONNECT_GRACE = Duration.ofSeconds(30);

    private final BroadcastRepository repository;
    private final UserRepository users;
    private final LiveRooms rooms;

    public BroadcastService(BroadcastRepository repository, UserRepository users, LiveRooms rooms) {
        this.repository = repository;
        this.users = users;
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
        return BroadcastResponse.from(find(id), rooms.viewerCount(id));
    }

    /** 방송 시작: 로그인 사용자가 소유자(스트리머)가 됩니다. */
    @Transactional
    public BroadcastResponse start(LoginUser me, String rawTitle) {
        Ownership.requireLogin(me);
        String title = validTitle(rawTitle);
        User owner = users.findById(me.id()).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        Broadcast b = repository.save(new Broadcast(owner, title, Instant.now()));
        return BroadcastResponse.from(b, 0);
    }

    /** 제목 수정: 소유자만. 방송 중이면 시청자 화면에도 바로 반영 */
    @Transactional
    public BroadcastResponse rename(long id, LoginUser me, String rawTitle) {
        Broadcast b = find(id);
        Ownership.requireOwner(b.getOwner(), me, "이 방송을 수정할 권한이 없습니다.");
        String title = validTitle(rawTitle);
        b.rename(title);
        if (b.isLive()) AfterCommit.run(() -> rooms.sendToAll(id, Map.of("type", "title", "title", title)));
        return BroadcastResponse.from(b, rooms.viewerCount(id));
    }

    /** 종료: 소유자만. 이미 종료됐으면 그대로 성공 */
    @Transactional
    public BroadcastResponse end(long id, LoginUser me) {
        Broadcast b = find(id);
        Ownership.requireOwner(b.getOwner(), me, "이 방송을 종료할 권한이 없습니다.");
        endAndClose(b);
        return BroadcastResponse.from(b, 0);
    }

    /** 기록 삭제: 소유자만, 종료된 방송만. 댓글은 DB에서 함께 삭제(ON DELETE CASCADE) */
    @Transactional
    public void delete(long id, LoginUser me) {
        Broadcast b = find(id);
        Ownership.requireOwner(b.getOwner(), me, "이 방송을 삭제할 권한이 없습니다.");
        if (b.isLive()) throw new ApiException(HttpStatus.CONFLICT, "방송 중에는 삭제할 수 없습니다. 먼저 방송을 종료해 주세요.");
        repository.delete(b);
    }

    /** 송출자 연결 허용 여부: 방송 중이고, 연결한 사용자가 소유자일 때만 */
    @Transactional(readOnly = true)
    public boolean canHost(long id, LoginUser me) {
        return repository.findWithOwnerById(id)
                .filter(Broadcast::isLive)
                .filter(b -> Ownership.isOwner(b.getOwner(), me))
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
        AfterCommit.run(() -> rooms.close(id));
    }

    private static String validTitle(String raw) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) throw ApiException.badRequest("방송 제목을 입력해 주세요.");
        if (title.length() > 100) throw ApiException.badRequest("방송 제목은 100자 이하로 입력해 주세요.");
        return title;
    }

    private Broadcast find(long id) {
        return repository.findWithOwnerById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }
}
