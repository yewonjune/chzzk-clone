package com.chzzk.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.chzzk.user.UserService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 권한 정책의 1차 관문.
 *  - 조회(GET)·영상 파일·화면·WebSocket 연결은 공개 (시청은 로그인 없이 가능)
 *  - 회원가입·로그인 외에 상태를 바꾸는 API(POST/PATCH/DELETE)는 로그인 필수 → 아니면 401
 *  - "누구의 것인지"(소유자·작성자) 검사는 각 서비스에서 수행 → 아니면 403
 */
@Configuration
public class SecurityConfig {

    static final String WEBHOOK = "/api/payments/webhook";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserService userService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** CSRF 토큰은 세션에 저장하고 /api/auth/me 응답으로 화면에 전달합니다. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityContextRepository contextRepository,
                                            CsrfTokenRepository csrfTokenRepository) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                // 다시보기 조회수는 시청 행위라 비로그인도 허용
                .requestMatchers(HttpMethod.POST, "/api/replays/*/views").permitAll()
                // PG 서버가 보내는 결제 웹훅: 로그인 세션이 없음. 본문은 믿지 않고 PG에 다시 조회해 처리
                .requestMatchers(HttpMethod.POST, WEBHOOK).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())   // 정적 화면, /media/**, /ws/** (송출자 권한은 시그널링에서 검사)
            // 데스크톱 앱 출처의 조회(GET)만 교차 출처 허용 (DesktopCorsConfig)
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(WEBHOOK) // 브라우저가 아닌 PG 서버의 호출이라 CSRF 토큰이 없음
                .csrfTokenRepository(csrfTokenRepository)
                // 화면이 받은 토큰 값을 그대로 헤더로 보내는 방식 (XOR 마스킹 없이)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .securityContext(sc -> sc.securityContextRepository(contextRepository))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT)))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    writeError(res, HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다."))
                .accessDeniedHandler((req, res, e) -> writeError(res, HttpServletResponse.SC_FORBIDDEN,
                    e instanceof CsrfException
                        ? "보안 토큰이 없거나 만료되었습니다. 새로고침 후 다시 시도해 주세요."
                        : "권한이 없습니다.")));
        return http.build();
    }

    private static void writeError(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
    }
}
