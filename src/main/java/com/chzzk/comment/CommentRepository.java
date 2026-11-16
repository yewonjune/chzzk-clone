package com.chzzk.comment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 키셋 페이지네이션: id < before 인 댓글을 최신순으로 limit 개.
     * OFFSET 을 쓰지 않아 댓글이 많아져도 (broadcast_id, id) 인덱스 범위만 읽습니다.
     */
    @EntityGraph(attributePaths = "author")
    @Query("select c from Comment c where c.broadcast.id = :targetId and c.id < :before order by c.id desc")
    List<Comment> pageForBroadcast(@Param("targetId") long targetId, @Param("before") long before, Limit limit);

    @EntityGraph(attributePaths = "author")
    @Query("select c from Comment c where c.replay.id = :targetId and c.id < :before order by c.id desc")
    List<Comment> pageForReplay(@Param("targetId") long targetId, @Param("before") long before, Limit limit);

    /** 권한 검사에 필요한 작성자·대상·대상 소유자를 한 번에 가져옵니다. */
    @Query("select c from Comment c join fetch c.author "
            + "left join fetch c.broadcast b left join fetch b.owner "
            + "left join fetch c.replay r left join fetch r.owner where c.id = :id")
    Optional<Comment> findForPermission(@Param("id") long id);
}
