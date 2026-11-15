package com.chichijik.user;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 세션에 저장되는 로그인 사용자 정보. 엔티티 대신 필요한 값만 담아 세션을 가볍게 유지합니다.
 * 컨트롤러에서는 {@code @AuthenticationPrincipal LoginUser me} 로 받습니다.
 */
public record LoginUser(Long id, String username, String nickname, String passwordHash)
        implements UserDetails, Serializable {

    static LoginUser from(User u) {
        return new LoginUser(u.getId(), u.getUsername(), u.getNickname(), u.getPasswordHash());
    }

    /** 세션에는 비밀번호 해시를 남기지 않습니다(인증이 끝난 뒤 사용). */
    public LoginUser withoutPassword() {
        return new LoginUser(id, username, nickname, null);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
