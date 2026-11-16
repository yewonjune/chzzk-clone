package com.chzzk.replay;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayRepository extends JpaRepository<Replay, Long> {

    /** 목록에서 스트리머 닉네임을 함께 가져와 N+1 쿼리를 피합니다. */
    @EntityGraph(attributePaths = "owner")
    List<Replay> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = "owner")
    Optional<Replay> findWithOwnerById(Long id);
}
