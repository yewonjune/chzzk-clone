package com.chichijik.comment;

import java.time.Instant;

import com.chichijik.user.OwnerResponse;

/** 댓글 응답. 수정·삭제 버튼 표시는 화면이 author.id 와 대상 소유자로 판단하지만, 실제 허용은 서버가 결정합니다. */
public record CommentResponse(
        Long id,
        String content,
        OwnerResponse author,
        Instant createdAt,
        Instant updatedAt
) {
    static CommentResponse from(Comment c) {
        return new CommentResponse(c.getId(), c.getContent(), OwnerResponse.of(c.getAuthor()),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
