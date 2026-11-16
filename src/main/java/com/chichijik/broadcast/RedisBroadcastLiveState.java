package com.chichijik.broadcast;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 방송 실시간 상태의 Redis 구현(cluster 프로필, 여러 인스턴스, ADR-0006).
 *
 * 각 인스턴스는 자기에게 붙은 WebSocket 연결만 메모리에 들고, 방송 전체의 상태는 Redis 에 둡니다.
 * 방송 메시지는 방송별 채널로 발행하고, 모든 인스턴스가 받아 자기 연결 중 대상에게만 전달합니다.
 *
 * Redis 키 (방송 id = {id}, 접두사 chichijik:live:)
 *   chichijik:live:{id}:host     hash  instance=송출자 연결을 든 인스턴스 id, token=연결마다 새로 만드는 값,
 *                                      disconnectedAt=끊긴 시각(epoch ms). 연결 중이면 token 이 있고 disconnectedAt 이 없음.
 *                                      끊기면 기록을 지우지 않고 token 을 빼고 disconnectedAt 을 남김(재연결 유예 판단용)
 *   chichijik:live:{id}:viewers  hash  viewerId → 시청자 연결을 든 인스턴스 id
 *   chichijik:live:{id}:viewers-disconnected  hash  viewerId → 끊긴 시각(epoch ms). 재연결 유예 중인 시청자
 *   chichijik:live:{id}:ended    문자열  종료 표시 (TTL {@link #ENDED_MARKER_TTL}). 있으면 입장·송출자 연결을 받지 않음
 *   chichijik:live:{id}          채널  봉투(JSON) {"k":대상, ...}
 *     k=host   {m}      송출자에게 m 전달
 *     k=viewer {v, m}   시청자 v 에게 m 전달
 *     k=all    {m}      송출자·시청자 모두에게 m 전달 (시청자 수, 댓글, 후원, 제목)
 *     k=host-attached {t}  새 송출자 연결(t)이 붙음 → 다른 인스턴스가 든 이전 송출자 연결을 닫음
 *     k=ended            종료 알림을 보내고 이 방송의 연결을 모두 닫음
 *
 *   chichijik:instance:{인스턴스 id}  문자열  하트비트. 인스턴스가 살아 있는 동안 주기적으로 TTL 과 함께 갱신
 *
 * 하트비트가 사라진 인스턴스(강제 종료 등) 소속 기록은 정리 작업({@link #releaseDeadInstances})이 정리합니다.
 * 정상 종료하는 인스턴스는 그 전에 연결을 4001로 닫으면서 기록을 "끊김"으로 남기므로, 그 기록은 재연결 유예가 판단합니다.
 *
 * 시청자 입장·퇴장과 시청자 수 알림은 Lua 스크립트 하나로 처리합니다.
 * 상한 검사와 추가가 원자적이고, 시청자 수가 바뀐 순서대로 발행됩니다.
 */
@Component
@Profile("cluster")
public class RedisBroadcastLiveState implements BroadcastLiveState, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisBroadcastLiveState.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PREFIX = "chichijik:live:";
    /**
     * 종료 표시를 남겨 두는 시간. 종료와 겹친 입장(방송 중인지 확인한 뒤 Redis 에 닿기 전)만 막으면 되므로 넉넉히 한 시간.
     * 그 뒤에는 표시도 사라져 종료된 방송의 기록은 하나도 남지 않습니다.
     */
    static final Duration ENDED_MARKER_TTL = Duration.ofHours(1);

    /**
     * KEYS[1]=viewers, KEYS[2]=viewers-disconnected, KEYS[3]=ended,
     * ARGV: 상한, viewerId, 인스턴스 id, 채널, viewer-joined 봉투, 처음부터 다시 입장하는 시청자의 이전 viewerId(없으면 ""),
     *       그 viewer-left 봉투 → 1 이면 입장, 0 이면 FULL, -1 이면 이미 종료된 방송. 이전 자리는 상한 검사 전에 비움
     */
    private static final RedisScript<Long> JOIN = RedisScript.of("""
            if redis.call('EXISTS', KEYS[3]) == 1 then return -1 end
            if ARGV[6] ~= '' and redis.call('HDEL', KEYS[1], ARGV[6]) == 1 then
              redis.call('HDEL', KEYS[2], ARGV[6])
              redis.call('PUBLISH', ARGV[4], ARGV[7])
              if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[1]) then
                redis.call('PUBLISH', ARGV[4], '{"k":"all","m":{"type":"viewers","count":' .. redis.call('HLEN', KEYS[1]) .. '}}')
              end
            end
            if redis.call('HEXISTS', KEYS[1], ARGV[2]) == 0 then
              if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
            end
            redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PUBLISH', ARGV[4], ARGV[5])
            redis.call('PUBLISH', ARGV[4], '{"k":"all","m":{"type":"viewers","count":' .. redis.call('HLEN', KEYS[1]) .. '}}')
            return 1
            """, Long.class);

    /**
     * KEYS[1]=viewers, KEYS[2]=viewers-disconnected, ARGV: viewerId, 채널, viewer-left 봉투, 인스턴스 id
     * → 이 인스턴스 소속 시청자면 빼고 1 (다른 인스턴스로 이어 붙은 시청자는 그대로)
     */
    private static final RedisScript<Long> LEAVE = RedisScript.of("""
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[4] then return 0 end
            redis.call('HDEL', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('PUBLISH', ARGV[2], ARGV[3])
            redis.call('PUBLISH', ARGV[2], '{"k":"all","m":{"type":"viewers","count":' .. redis.call('HLEN', KEYS[1]) .. '}}')
            return 1
            """, Long.class);

    /**
     * KEYS[1]=ended, KEYS[2..]=이 방송의 나머지 기록, ARGV: 종료 표시 TTL(ms), 채널, ended 봉투
     * → 종료 표시를 남기고 기록을 지운 뒤 종료를 알림. 이후의 JOIN·ATTACH_HOST 는 종료 표시를 보고 기록을 다시 만들지 않음
     */
    private static final RedisScript<Long> END = RedisScript.of("""
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            for i = 2, #KEYS do redis.call('DEL', KEYS[i]) end
            redis.call('PUBLISH', ARGV[2], ARGV[3])
            return 1
            """, Long.class);

    /** KEYS[1]=viewers, KEYS[2]=viewers-disconnected, ARGV: viewerId, 인스턴스 id, 지금(ms) → 이 인스턴스 소속이면 끊긴 시각 기록 */
    private static final RedisScript<Long> VIEWER_DISCONNECTED = RedisScript.of("""
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0 end
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
            return 1
            """, Long.class);

    /** KEYS[1]=viewers, KEYS[2]=viewers-disconnected, ARGV: viewerId, 인스턴스 id → 집합에 있으면 소속을 바꾸고 1 */
    private static final RedisScript<Long> RESUME = RedisScript.of("""
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    /**
     * KEYS[1]=viewers, KEYS[2]=viewers-disconnected, ARGV: viewerId, cutoff(ms), 채널, viewer-left 봉투
     * → 끊긴 시각이 cutoff 이전이면(그사이 이어 붙지 않았으면) 퇴장시키고 1. 여러 인스턴스가 동시에 돌아도 한 번만 퇴장
     */
    private static final RedisScript<Long> DROP_DISCONNECTED = RedisScript.of("""
            local t = redis.call('HGET', KEYS[2], ARGV[1])
            if not t or tonumber(t) >= tonumber(ARGV[2]) then return 0 end
            redis.call('HDEL', KEYS[2], ARGV[1])
            if redis.call('HDEL', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('PUBLISH', ARGV[3], ARGV[4])
            redis.call('PUBLISH', ARGV[3], '{"k":"all","m":{"type":"viewers","count":' .. redis.call('HLEN', KEYS[1]) .. '}}')
            return 1
            """, Long.class);

    /** KEYS[1]=host, ARGV: token, 지금(ms) → 지금 송출자가 이 연결이면 끊김으로 바꾸고 1 (기록은 남김) */
    private static final RedisScript<Long> DETACH_HOST = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'token') ~= ARGV[1] then return 0 end
            redis.call('HDEL', KEYS[1], 'token', 'instance')
            redis.call('HSET', KEYS[1], 'disconnectedAt', ARGV[2])
            return 1
            """, Long.class);

    /** KEYS[1]=host, KEYS[2]=ended, ARGV: instance, token → 송출자로 기록하고 끊긴 시각을 지움. 이미 종료된 방송이면 0 */
    private static final RedisScript<Long> ATTACH_HOST = RedisScript.of("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('HDEL', KEYS[1], 'disconnectedAt')
            redis.call('HSET', KEYS[1], 'instance', ARGV[1], 'token', ARGV[2])
            return 1
            """, Long.class);

    /**
     * KEYS[1]=viewers, KEYS[2]=viewers-disconnected, ARGV: viewerId, 죽은 인스턴스 id, 채널, viewer-left 봉투
     * → 아직 그 인스턴스에 연결된 것으로 남은 시청자면 퇴장시키고 1. 그사이 다른 인스턴스로 이어 붙었거나
     *   이미 끊김으로 기록된(재연결 유예 중인) 시청자는 그대로 두므로 여러 인스턴스가 동시에 돌아도 한 번만 퇴장
     */
    private static final RedisScript<Long> RELEASE_VIEWER = RedisScript.of("""
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0 end
            if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then return 0 end
            redis.call('HDEL', KEYS[1], ARGV[1])
            redis.call('PUBLISH', ARGV[3], ARGV[4])
            redis.call('PUBLISH', ARGV[3], '{"k":"all","m":{"type":"viewers","count":' .. redis.call('HLEN', KEYS[1]) .. '}}')
            return 1
            """, Long.class);

    /** KEYS[1]=host, ARGV: 죽은 인스턴스 id, 지금(ms) → 송출자가 아직 그 인스턴스 소속이면 지금 끊긴 것으로 바꾸고 1 */
    private static final RedisScript<Long> RELEASE_HOST = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'instance') ~= ARGV[1] then return 0 end
            redis.call('HDEL', KEYS[1], 'token', 'instance')
            redis.call('HSET', KEYS[1], 'disconnectedAt', ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    /** 채널 메시지를 받은 순서대로 전달 (offer 다음 ICE candidate 순서 유지) */
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> new Thread(r, "live-relay"));
    private final String instanceId = UUID.randomUUID().toString();
    /** 하트비트 갱신 (정리 작업이 오래 걸려도 밀리지 않도록 스케줄러와 따로 둠) */
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "live-heartbeat"));
    private final Duration heartbeatTtl;
    private final Map<Long, Local> locals = new ConcurrentHashMap<>();
    /**
     * 종료 중: 열린 WebSocket 은 그보다 앞선 종료 단계에서 4001로 닫히지만(SignalingHandler), 그 뒤에 늦게 닫히는 연결이
     * 이미 닫힌 Redis 를 건드리지 않도록 막습니다.
     * 이 인스턴스 소속으로 남은 기록은 하트비트가 사라진 인스턴스의 정리 작업이 지웁니다.
     */
    private volatile boolean stopping;

    /** 이 인스턴스에 붙은 방송 하나의 연결 */
    static final class Local {
        WebSocketSession host;
        String hostToken;
        final Map<String, WebSocketSession> viewers = new LinkedHashMap<>();
    }

    public RedisBroadcastLiveState(StringRedisTemplate redis, RedisConnectionFactory connectionFactory,
                                   @Value("${app.live.heartbeat-interval}") Duration heartbeatInterval,
                                   @Value("${app.live.heartbeat-ttl}") Duration heartbeatTtl) {
        this.redis = redis;
        this.heartbeatTtl = heartbeatTtl;
        // 연결을 받기 전에 살아 있음을 먼저 알림 (다른 인스턴스가 이 인스턴스 소속 기록을 죽은 것으로 보지 않도록)
        beat();
        heartbeat.scheduleWithFixedDelay(this::beat, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(dispatcher);
        container.addMessageListener(this, new PatternTopic(PREFIX + "*"));
        container.afterPropertiesSet();
        container.start();
    }

    /**
     * 연결은 이보다 앞선 종료 단계에서 4001로 닫히며 기록이 "끊김"으로 남습니다(SignalingHandler). 그 뒤라서 하트비트를 멈춰도
     * 정리 작업이 이 인스턴스의 연결 중 기록을 지우지 않고, 남은 기록은 재연결 유예가 판단합니다.
     * 하트비트 키는 지우지 않고 TTL 로 사라지게 둡니다(이 시점에는 Redis 연결이 이미 닫혀 있음).
     */
    @PreDestroy
    void stop() throws Exception {
        heartbeat.shutdownNow();
        stopping = true;
        container.destroy();
        dispatcher.shutdown();
    }

    private static String heartbeatKey(String instance) {
        return "chichijik:instance:" + instance;
    }

    private void beat() {
        try {
            redis.opsForValue().set(heartbeatKey(instanceId), "1", heartbeatTtl);
        } catch (RuntimeException e) {
            log.warn("하트비트 갱신 실패: {}", e.getMessage());
        }
    }

    private static String channel(long id) {
        return PREFIX + id;
    }

    private static String hostKey(long id) {
        return PREFIX + id + ":host";
    }

    private static String viewersKey(long id) {
        return PREFIX + id + ":viewers";
    }

    private static String disconnectedViewersKey(long id) {
        return PREFIX + id + ":viewers-disconnected";
    }

    private static String endedKey(long id) {
        return PREFIX + id + ":ended";
    }

    private Local local(long id) {
        return locals.computeIfAbsent(id, k -> new Local());
    }

    // ---- 상태 변경 ----

    @Override
    public boolean attachHost(long id, WebSocketSession host) {
        String token = UUID.randomUUID().toString();
        Local l = local(id);
        WebSocketSession previous;
        synchronized (l) {
            previous = l.host;
            l.host = host;
            l.hostToken = token;
        }
        Long attached = redis.execute(ATTACH_HOST, List.of(hostKey(id), endedKey(id)), instanceId, token);
        if (attached == null || attached != 1) {
            synchronized (l) {
                if (l.host == host) {
                    l.host = null;
                    l.hostToken = null;
                }
            }
            forgetIfEmpty(id, l);
            return false;
        }
        if (previous != null && previous != host && previous.isOpen()) closeReplaced(previous);
        publish(id, Map.of("k", "host-attached", "t", token));
        for (Object viewerId : redis.opsForHash().keys(viewersKey(id))) {
            WebSockets.send(host, Map.of("type", "viewer-joined", "viewerId", viewerId));
        }
        publish(id, Map.of("k", "all", "m", Map.of("type", "viewers", "count", viewerCount(id))));
        return true;
    }

    @Override
    public JoinResult attachViewer(long id, String viewerId, WebSocketSession viewer, int maxViewers, String startingOver) {
        Local l = local(id);
        synchronized (l) {
            l.viewers.put(viewerId, viewer); // 먼저 등록해야 자기 입장으로 바뀐 시청자 수를 받음
        }
        String previous = startingOver == null ? "" : startingOver;
        Long ok = redis.execute(JOIN, List.of(viewersKey(id), disconnectedViewersKey(id), endedKey(id)), String.valueOf(maxViewers),
                viewerId, instanceId, channel(id),
                envelope(Map.of("k", "host", "m", Map.of("type", "viewer-joined", "viewerId", viewerId))), previous,
                envelope(Map.of("k", "host", "m", Map.of("type", "viewer-left", "viewerId", previous))));
        if (ok != null && ok == 1) return JoinResult.OK;
        synchronized (l) {
            l.viewers.remove(viewerId);
        }
        if (ok != null && ok == -1) {
            forgetIfEmpty(id, l);
            return JoinResult.ENDED;
        }
        return JoinResult.FULL;
    }

    /** 종료된 방송에 붙으려다 거절된 뒤 남은 빈 연결 묶음을 지움 (종료 알림을 이미 지나쳐 다시 지워질 일이 없음) */
    private void forgetIfEmpty(long id, Local l) {
        synchronized (l) {
            if (l.host == null && l.viewers.isEmpty()) locals.remove(id, l);
        }
    }

    @Override
    public boolean resumeViewer(long id, String viewerId, WebSocketSession viewer) {
        Local l = local(id);
        WebSocketSession previous;
        synchronized (l) {
            previous = l.viewers.put(viewerId, viewer); // 먼저 등록해야 이어 붙자마자 오는 시그널을 받음
        }
        Long ok = redis.execute(RESUME, List.of(viewersKey(id), disconnectedViewersKey(id)), viewerId, instanceId);
        if (ok != null && ok == 1) {
            // 이 인스턴스가 아직 끊김을 모르는 이전 연결이 남아 있으면 닫음 (현재 연결이 아니므로 퇴장 처리되지 않음)
            if (previous != null && previous != viewer) WebSockets.closeQuietly(previous, CloseStatus.NORMAL);
            return true;
        }
        synchronized (l) {
            if (l.viewers.get(viewerId) == viewer) {
                if (previous != null) l.viewers.put(viewerId, previous);
                else l.viewers.remove(viewerId);
            }
        }
        return false;
    }

    @Override
    public boolean hasViewer(long id, String viewerId) {
        return redis.opsForHash().hasKey(viewersKey(id), viewerId);
    }

    /**
     * 기록이 Redis 에 있어 이 서버가 떴다고 사라지지 않습니다. 송출자 기록이 없는 방송은 송출자가 아직 한 번도 붙지 않은 방송이고
     * 시작 후 30초 규칙을 그대로 받아야 하므로 아무것도 하지 않습니다. 죽은 서버 소속 기록은 {@link #releaseDeadInstances}가 정리합니다.
     */
    @Override
    public void recoverAfterRestart(long id, Instant at) {
    }

    @Override
    public Optional<Instant> hostDisconnectedAt(long id) {
        Object at = redis.opsForHash().get(hostKey(id), "disconnectedAt");
        if (at == null) return Optional.empty();
        try {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(at.toString())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public void dropViewersDisconnectedBefore(long id, Instant cutoff) {
        String cutoffMs = String.valueOf(cutoff.toEpochMilli());
        for (Map.Entry<Object, Object> e : redis.opsForHash().entries(disconnectedViewersKey(id)).entrySet()) {
            String viewerId = e.getKey().toString();
            redis.execute(DROP_DISCONNECTED, List.of(viewersKey(id), disconnectedViewersKey(id)), viewerId, cutoffMs,
                    channel(id), envelope(Map.of("k", "host", "m", Map.of("type", "viewer-left", "viewerId", viewerId))));
        }
    }

    /**
     * 송출자·시청자 기록의 소속 인스턴스 중 하트비트가 없는 인스턴스의 기록을 정리합니다.
     * 송출자의 끊긴 시각은 정리한 시각입니다(죽은 시각은 알 수 없으므로, 그때부터 재연결 유예를 줌).
     */
    @Override
    public void releaseDeadInstances(long id) {
        Map<Object, Object> viewers = redis.opsForHash().entries(viewersKey(id));
        Object hostInstance = redis.opsForHash().get(hostKey(id), "instance");
        Map<String, Boolean> alive = new HashMap<>();
        alive.put(instanceId, true);
        if (hostInstance != null && !isAlive(hostInstance.toString(), alive)) {
            redis.execute(RELEASE_HOST, List.of(hostKey(id)), hostInstance.toString(),
                    String.valueOf(System.currentTimeMillis()));
        }
        for (Map.Entry<Object, Object> e : viewers.entrySet()) {
            String owner = e.getValue().toString();
            if (isAlive(owner, alive)) continue;
            String viewerId = e.getKey().toString();
            redis.execute(RELEASE_VIEWER, List.of(viewersKey(id), disconnectedViewersKey(id)), viewerId, owner, channel(id),
                    envelope(Map.of("k", "host", "m", Map.of("type", "viewer-left", "viewerId", viewerId))));
        }
    }

    private boolean isAlive(String instance, Map<String, Boolean> known) {
        return known.computeIfAbsent(instance, i -> Boolean.TRUE.equals(redis.hasKey(heartbeatKey(i))));
    }

    @Override
    public boolean detachHost(long id, WebSocketSession host) {
        Local l = locals.get(id);
        if (l == null) return false;
        String token;
        synchronized (l) {
            if (l.host != host) return false;
            token = l.hostToken;
            l.host = null;
            l.hostToken = null;
        }
        if (stopping) return false;
        Long current = redis.execute(DETACH_HOST, List.of(hostKey(id)), token, String.valueOf(System.currentTimeMillis()));
        return current != null && current == 1;
    }

    @Override
    public void detachViewer(long id, String viewerId, WebSocketSession viewer, boolean leaving) {
        Local l = locals.get(id);
        if (l == null) return;
        synchronized (l) {
            if (l.viewers.get(viewerId) != viewer) return; // 이 인스턴스에서 다른 연결로 이미 이어 붙음
            l.viewers.remove(viewerId);
        }
        if (stopping) return;
        List<String> keys = List.of(viewersKey(id), disconnectedViewersKey(id));
        if (leaving) {
            redis.execute(LEAVE, keys, viewerId, channel(id),
                    envelope(Map.of("k", "host", "m", Map.of("type", "viewer-left", "viewerId", viewerId))), instanceId);
        } else {
            redis.execute(VIEWER_DISCONNECTED, keys, viewerId, instanceId, String.valueOf(System.currentTimeMillis()));
        }
    }

    // ---- 메시지 ----

    @Override
    public void relayToViewer(long id, String viewerId, Object data) {
        publish(id, Map.of("k", "viewer", "v", viewerId, "m", Map.of("type", "signal", "data", data)));
    }

    @Override
    public void relayToHost(long id, String viewerId, Object data) {
        publish(id, Map.of("k", "host", "m", Map.of("type", "signal", "from", viewerId, "data", data)));
    }

    @Override
    public void sendToAll(long id, Map<String, ?> message) {
        publish(id, Map.of("k", "all", "m", message));
    }

    @Override
    public void ended(long id) {
        redis.execute(END, List.of(endedKey(id), hostKey(id), viewersKey(id), disconnectedViewersKey(id)),
                String.valueOf(ENDED_MARKER_TTL.toMillis()), channel(id), envelope(Map.of("k", "ended")));
    }

    // ---- 조회 ----

    @Override
    public int viewerCount(long id) {
        Long n = redis.opsForHash().size(viewersKey(id));
        return n == null ? 0 : n.intValue();
    }

    /** 어느 인스턴스든 송출자 연결을 들고 있으면 true (끊겨 유예 중인 기록은 token 이 없음) */
    @Override
    public boolean hasHost(long id) {
        return redis.opsForHash().hasKey(hostKey(id), "token");
    }

    // ---- 채널 ----

    private void publish(long id, Map<String, ?> envelope) {
        redis.convertAndSend(channel(id), envelope(envelope));
    }

    private static String envelope(Map<String, ?> envelope) {
        return JSON.writeValueAsString(envelope);
    }

    /** 채널로 받은 봉투를 이 인스턴스의 연결 중 대상에게만 전달 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String ch = new String(message.getChannel(), StandardCharsets.UTF_8);
        long id;
        try {
            id = Long.parseLong(ch.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        Local l = locals.get(id);
        if (l == null) return;
        JsonNode env;
        try {
            env = JSON.readTree(message.getBody());
        } catch (RuntimeException e) {
            log.debug("잘못된 방송 메시지: {}", e.getMessage());
            return;
        }
        switch (env.path("k").asString()) {
            case "host" -> {
                WebSocketSession host;
                synchronized (l) {
                    host = l.host;
                }
                if (host != null) WebSockets.send(host, toMap(env.get("m")));
            }
            case "viewer" -> {
                WebSocketSession viewer;
                synchronized (l) {
                    viewer = l.viewers.get(env.path("v").asString());
                }
                if (viewer != null) WebSockets.send(viewer, toMap(env.get("m")));
            }
            case "all" -> {
                Map<String, ?> m = toMap(env.get("m"));
                for (WebSocketSession s : targets(l)) WebSockets.send(s, m);
            }
            case "host-attached" -> closeReplacedHost(l, env.path("t").asString());
            case "ended" -> endLocal(id);
            default -> { }
        }
    }

    private static List<WebSocketSession> targets(Local l) {
        synchronized (l) {
            List<WebSocketSession> all = new ArrayList<>(l.viewers.values());
            if (l.host != null) all.add(l.host);
            return all;
        }
    }

    /** 다른 연결이 송출자가 됐으면 이 인스턴스가 든 이전 송출자 연결을 닫음 */
    private static void closeReplacedHost(Local l, String newToken) {
        WebSocketSession previous;
        synchronized (l) {
            if (l.host == null || newToken.equals(l.hostToken)) return;
            previous = l.host;
            l.host = null;
            l.hostToken = null;
        }
        closeReplaced(previous);
    }

    /** 교체된 송출자 연결: 재연결하지 않도록 알리고(error) 정책 위반(1008)으로 닫음 */
    private static void closeReplaced(WebSocketSession previous) {
        WebSockets.send(previous, Map.of("type", "error", "message", "다른 화면에서 송출을 이어받았습니다."));
        WebSockets.closeQuietly(previous, CloseStatus.POLICY_VIOLATION);
    }

    private void endLocal(long id) {
        Local l = locals.remove(id);
        if (l == null) return;
        List<WebSocketSession> all = targets(l);
        synchronized (l) {
            l.viewers.clear();
            l.host = null;
            l.hostToken = null;
        }
        for (WebSocketSession s : all) {
            WebSockets.send(s, Map.of("type", "ended"));
            WebSockets.closeQuietly(s, CloseStatus.NORMAL);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> toMap(JsonNode node) {
        return JSON.convertValue(node, Map.class);
    }
}
