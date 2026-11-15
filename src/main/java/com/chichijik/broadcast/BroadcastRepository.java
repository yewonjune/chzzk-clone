package com.chichijik.broadcast;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    List<Broadcast> findAllByStatusOrderByStartedAtDesc(Broadcast.Status status);

    List<Broadcast> findTop30ByStatusOrderByEndedAtDesc(Broadcast.Status status);

    /** 서버 재시작 시: 송출 연결이 모두 사라졌으므로 방송 중인 방송을 일괄 종료합니다. */
    @Modifying
    @Query("update Broadcast b set b.status = com.chichijik.broadcast.Broadcast.Status.ENDED, b.endedAt = :at "
            + "where b.status = com.chichijik.broadcast.Broadcast.Status.LIVE")
    int endAllLive(@Param("at") Instant at);
}
