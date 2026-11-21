package com.chzzk.desktop;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 6단계 데스크톱 클라이언트 관련 설정 (환경변수).
 *  - DESKTOP_ORIGINS        : 데스크톱 앱 화면의 출처. 이 출처에서 오는 조회(GET) API·시그널링 WebSocket 을 허용
 *  - DESKTOP_DOWNLOAD_BASE  : 설치 파일이 올라가 있는 주소 (기본: GitHub Releases 의 최신 릴리스)
 *  - DESKTOP_RELEASES_PAGE  : 전체 릴리스 목록 페이지 (버전·변경 내역 확인용)
 */
@Component
public class DesktopProperties {

    private final List<String> origins;
    private final String downloadBase;
    private final String releasesPage;

    public DesktopProperties(@Value("${app.desktop.origins}") String origins,
                             @Value("${app.desktop.download-base}") String downloadBase,
                             @Value("${app.desktop.releases-page}") String releasesPage) {
        this.origins = Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.downloadBase = downloadBase.replaceAll("/+$", "");
        this.releasesPage = releasesPage;
    }

    public List<String> origins() {
        return origins;
    }

    public String downloadBase() {
        return downloadBase;
    }

    public String releasesPage() {
        return releasesPage;
    }
}
