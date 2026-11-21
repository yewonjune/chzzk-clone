package com.chzzk.desktop;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 데스크톱 앱은 서버가 내려준 화면이 아니라 앱 안의 화면(출처 app://chzzk)에서 API 를 부릅니다.
 * 그래서 그 출처에 한해 "조회"만 교차 출처로 허용합니다.
 *  - GET/HEAD 만, 쿠키 없이 (allowCredentials=false) → 로그인·후원 같은 상태 변경은 데스크톱 앱에서 불가
 *  - 대상: /api/**, /media/**
 * Origin 이 데스크톱 출처가 아닌 요청(서버가 내려준 웹 화면 등)에는 CORS 처리를 하지 않아 이전 단계와 똑같이 동작합니다.
 * Spring Security 의 .cors() 가 이 Bean 을 사용합니다.
 */
@Configuration
public class DesktopCorsConfig {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    @Bean
    CorsConfigurationSource corsConfigurationSource(DesktopProperties props) {
        CorsConfiguration readOnly = new CorsConfiguration();
        readOnly.setAllowedOrigins(props.origins());
        readOnly.setAllowedMethods(List.of("GET", "HEAD"));
        readOnly.setAllowedHeaders(List.of("Accept", "Range"));
        readOnly.setAllowCredentials(false);
        readOnly.setMaxAge(3600L);
        return request -> {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin == null || !props.origins().contains(origin)) return null;
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return PATHS.match("/api/**", path) || PATHS.match("/media/**", path) ? readOnly : null;
        };
    }
}
