package com.chzzk.desktop;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다운로드 페이지(/download.html)가 쓰는 설치 파일 목록.
 * 파일 이름은 desktop/package.json 의 electron-builder artifactName 과 같아야 합니다.
 * (버전이 이름에 없어서 GitHub 의 releases/latest/download/<파일> 주소가 항상 최신 파일을 가리킴)
 */
@RestController
public class DownloadController {

    public record Asset(String os, String arch, String kind, String file, String url, String note) {
    }

    static final List<String[]> FILES = List.of(
            // os, arch, kind, file, note
            new String[]{"windows", "x64", "installer", "Chzzk-Setup-win-x64.exe", "설치 프로그램 (권장)"},
            new String[]{"windows", "x64", "portable", "Chzzk-win-x64.zip", "설치 없이 압축 풀고 실행"},
            new String[]{"windows", "arm64", "installer", "Chzzk-Setup-win-arm64.exe", "ARM 노트북(Snapdragon 등)"},
            new String[]{"macos", "arm64", "installer", "Chzzk-mac-arm64.dmg", "Apple Silicon (M1~M4)"},
            new String[]{"macos", "arm64", "portable", "Chzzk-mac-arm64.zip", "Apple Silicon, 압축 파일"},
            new String[]{"macos", "x64", "installer", "Chzzk-mac-x64.dmg", "Intel Mac"},
            new String[]{"macos", "x64", "portable", "Chzzk-mac-x64.zip", "Intel Mac, 압축 파일"});

    private final DesktopProperties props;

    public DownloadController(DesktopProperties props) {
        this.props = props;
    }

    @GetMapping("/api/downloads")
    public Map<String, Object> downloads() {
        List<Asset> assets = FILES.stream()
                .map(f -> new Asset(f[0], f[1], f[2], f[3], props.downloadBase() + "/" + f[3], f[4]))
                .toList();
        return Map.of("releasesPage", props.releasesPage(), "assets", assets);
    }
}
