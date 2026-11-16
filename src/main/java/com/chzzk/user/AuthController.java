package com.chzzk.user;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chzzk.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 회원가입·로그인·현재 사용자. (로그아웃은 Spring Security 가 POST /api/auth/logout 에서 처리)
 * 모든 응답에 다음 요청에 쓸 CSRF 토큰을 함께 내려줍니다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthController(UserService userService, AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    public record SignUpRequest(String username, String password, String nickname) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserResponse(Long id, String username, String nickname) {
        static UserResponse of(LoginUser u) {
            return u == null ? null : new UserResponse(u.id(), u.username(), u.nickname());
        }
    }

    /** 현재 로그인 사용자(없으면 null)와 CSRF 토큰 */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal LoginUser me, HttpServletRequest req) {
        CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
        return body(me, token);
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signUp(@RequestBody SignUpRequest body, HttpServletRequest req, HttpServletResponse res) {
        userService.signUp(body.username(), body.password(), body.nickname());
        return login(new LoginRequest(body.username(), body.password()), req, res);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        LoginUser user = ((LoginUser) auth.getPrincipal()).withoutPassword();

        // 세션 고정 공격 방지: 로그인 시 세션 ID 교체
        if (req.getSession(false) != null) req.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, req, res);

        // 로그인 전후로 CSRF 토큰도 새로 발급
        CsrfToken fresh = csrfTokenRepository.generateToken(req);
        csrfTokenRepository.saveToken(fresh, req, res);
        return body(user, fresh);
    }

    private static Map<String, Object> body(LoginUser user, CsrfToken token) {
        Map<String, Object> m = new HashMap<>();
        m.put("user", UserResponse.of(user));
        if (token != null) m.put("csrf", Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
        return m;
    }
}
