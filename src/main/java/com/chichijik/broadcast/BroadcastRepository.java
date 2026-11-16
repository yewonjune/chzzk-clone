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

    /**
     * 방송 중일 때만 종료로 바꾸는 조건부 갱신. 같은 방송을 여러 곳에서 동시에 종료해도 1을 받는 쪽은 하나뿐이고,
     * 그쪽만 종료 알림을 보냅니다. (영속성 컨텍스트에 남은 이전 상태로 덮어쓰지 않도록 비웁니다.)
     * @return 실제로 종료로 바꿨으면 1, 이미 종료였거나 없으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Broadcast b set b.status = com.chichijik.broadcast.Broadcast.Status.ENDED, b.endedAt = :at "
            + "where b.id = :id and b.status = com.chichijik.broadcast.Broadcast.Status.LIVE")
    int endIfLive(@Param("id") long id, @Param("at") Instant at);
}
