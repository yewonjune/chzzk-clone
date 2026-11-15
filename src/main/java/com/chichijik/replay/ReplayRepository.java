package com.chichijik.replay;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayRepository extends JpaRepository<Replay, Long> {

    List<Replay> findAllByOrderByIdDesc();
}
