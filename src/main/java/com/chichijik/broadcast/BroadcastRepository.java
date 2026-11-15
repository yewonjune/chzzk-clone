package com.chichijik.broadcast;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    /** 목록에서 스트리머 닉네임을 함께 가져와 N+1 쿼리를 피합니다. */
    @EntityGraph(attributePaths = "owner")
    List<Broadcast> findAllByStatusOrderByStartedAtDesc(Broadcast.Status status);

    @EntityGraph(attributePaths = "owner")
    List<Broadcast> findTop30ByStatusOrderByEndedAtDesc(Broadcast.Status status);

    @EntityGraph(attributePaths = "owner")
    Optional<Broadcast> findWithOwnerById(Long id);

    /** 서버 재시작 시: 송출 연결이 모두 사라졌으므로 방송 중인 방송을 일괄 종료합니다. */
    @Modifying
    @Query("update Broadcast b set b.status = com.chichijik.broadcast.Broadcast.Status.ENDED, b.endedAt = :at "
            + "where b.status = com.chichijik.broadcast.Broadcast.Status.LIVE")
    int endAllLive(@Param("at") Instant at);
}
