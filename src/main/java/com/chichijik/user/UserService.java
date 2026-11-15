package com.chichijik.user;

import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chichijik.common.ApiException;

@Service
public class UserService implements UserDetailsService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{4,20}$");

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return repository.findByUsername(username == null ? "" : username.trim())
                .map(LoginUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("no user"));
    }

    @Transactional
    public LoginUser signUp(String rawUsername, String rawPassword, String rawNickname) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        String password = rawPassword == null ? "" : rawPassword;

        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("아이디는 영문 소문자·숫자·_ 로 4~20자여야 합니다.");
        }
        if (nickname.length() < 2 || nickname.length() > 20) {
            throw ApiException.badRequest("닉네임은 2~20자로 입력해 주세요.");
        }
        if (password.length() < 8 || password.length() > 72) {
            // BCrypt 는 72바이트까지만 사용하므로 상한을 둡니다.
            throw ApiException.badRequest("비밀번호는 8~72자로 입력해 주세요.");
        }
        if (repository.existsByUsername(username)) throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        if (repository.existsByNickname(nickname)) throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");

        try {
            User u = repository.saveAndFlush(new User(username, nickname, passwordEncoder.encode(password)));
            return LoginUser.from(u);
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 아이디/닉네임으로 가입한 경우 (UNIQUE 제약이 최종 방어선)
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디 또는 닉네임입니다.");
        }
    }
}
