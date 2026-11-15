package com.chichijik.common;

import java.util.Objects;

import org.springframework.http.HttpStatus;

import com.chichijik.user.LoginUser;
import com.chichijik.user.User;

/** 소유자·작성자 검사. 화면에서 버튼을 숨기는 것과 별개로, 서버는 항상 여기서 판단합니다. */
public final class Ownership {

    private Ownership() {
    }

    /** 로그인 사용자가 owner 본인인가. owner 가 없는 이전 기록은 누구의 것도 아닙니다. */
    public static boolean isOwner(User owner, LoginUser me) {
        return owner != null && me != null && Objects.equals(owner.getId(), me.id());
    }

    public static void requireLogin(LoginUser me) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }

    public static void requireOwner(User owner, LoginUser me, String message) {
        requireLogin(me);
        if (!isOwner(owner, me)) throw new ApiException(HttpStatus.FORBIDDEN, message);
    }
}
