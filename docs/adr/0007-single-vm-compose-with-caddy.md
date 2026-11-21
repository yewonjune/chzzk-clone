# 공개 배포는 VM 한 대의 Docker Compose + Caddy, 데스크톱 앱은 Electron 시청 전용으로 한다

Always Free VM 은 한 대(A1 2 OCPU·12GB)라 5단계의 Kubernetes(HPA·여러 Pod)를 그대로 올리면 제어 평면이 자원을 먼저 쓰고, 노드가 하나라 스케일 아웃의 이점도 없다. 그래서 같은 이미지와 `cluster` 프로필(PostgreSQL·Redis·업로드 볼륨)을 Docker Compose 로 한 대에 띄운다. 상태가 이미 인스턴스 밖에 있으므로 나중에 VM 을 늘리면 앱 컨테이너만 추가하면 된다. TLS 는 Caddy 가 Let's Encrypt 인증서를 자동 발급·갱신하며 끝내고, 앱은 내부 HTTP 로 받되 `X-Forwarded-*`(사설망 프록시만 신뢰)로 HTTPS 를 인식한다. Nginx + certbot 도 검토했지만 갱신 작업·설정 파일이 늘어나 Caddy 를 택했다.

데스크톱 앱은 라이브가 WebRTC 라 브라우저 엔진이 필요하다. Tauri 는 작지만 macOS(WKWebView)·Windows(WebView2)의 WebRTC 동작이 달라질 수 있어, 두 OS 에서 같은 Chromium 을 쓰는 Electron 을 택했다(설치 파일이 약 120MB 로 큼). 앱 화면은 서버가 내려주는 웹 화면을 그대로 띄우지 않고 앱 안의 화면(`app://chzzk`)에서 공개 조회 API 와 시청 WebSocket 만 쓴다. 그래서 서버는 이 출처에 한해 GET 만 CORS 로 열고(쿠키 없음) 시청 WebSocket 을 허용하며, 로그인·댓글·후원·송출 같은 상태 변경은 웹으로 안내한다. 쿠키를 앱과 공유하지 않아 CSRF·세션 탈취 면이 늘지 않는다.

macOS 패키지는 macOS 에서만 만들 수 있어 GitHub Actions(windows-latest·macos-latest)로 빌드해 Releases 에 올리고, 파일 이름에 버전을 넣지 않아 다운로드 페이지가 `releases/latest/download/<파일>` 로 항상 최신을 가리키게 한다. 코드 서명·공증은 유료 인증서가 필요해 하지 않고, Apple Silicon 에서 실행되도록 ad-hoc 서명만 붙이며 첫 실행 허용 방법을 다운로드 페이지에 안내한다.
