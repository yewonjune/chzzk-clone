package com.chzzk.user;

/** 응답에 담는 사용자 공개 정보 (아이디는 로그인 정보라 노출하지 않음) */
public record OwnerResponse(Long id, String nickname) {

    public static OwnerResponse of(User u) {
        return u == null ? null : new OwnerResponse(u.getId(), u.getNickname());
    }
}
