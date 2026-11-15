# 치치직 (ChiChiJik)

로그인 없이 **녹화된 방송을 등록하고 시청**하는 스트리밍 사이트입니다. (1단계)

- 백엔드: Java 17+ · Spring Boot 4.1 (Spring MVC, Spring Data JPA)
- 저장소: H2 파일 DB — 별도 DB 설치 없이 `data/` 폴더에 영구 저장
- 빌드: Gradle (Wrapper 포함, Gradle 설치 불필요)
- 프론트엔드: 정적 HTML/CSS/JS (`src/main/resources/static`, 빌드 과정 없음)

## 실행 방법

필요 환경: **JDK 17 이상** (개발·검증은 JDK 25에서 진행)

### IntelliJ IDEA

1. `File → Open` 으로 이 폴더(`build.gradle`이 있는 폴더)를 엽니다. Gradle 프로젝트로 자동 인식됩니다.
2. `File → Project Structure → SDK` 에서 JDK 17 이상을 선택합니다.
3. `src/main/java/com/chichijik/ChichijikApplication.java` 의 ▶ 버튼으로 실행합니다.
4. 브라우저에서 http://localhost:8080 접속.

### 터미널

```bash
./gradlew bootRun        # Windows: gradlew.bat bootRun
```

| 명령 | 설명 |
| --- | --- |
| `./gradlew bootRun` | 서버 실행 |
| `./gradlew test` | 자동 테스트 (등록·목록·상세·재생·재시작 후 유지·입력 검증) |
| `./gradlew bootJar` | 실행 jar 생성 → `java -jar build/libs/chichijik-0.1.0.jar` |

## 환경변수 (`.env.example`)

비밀 값은 없습니다. 모두 선택 사항이며 기본값이 있습니다.
Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로, IntelliJ에서는 **Run/Debug Configurations → Environment variables**에, 터미널에서는 셸 환경변수로 설정하세요.

```dotenv
PORT=8080                 # 서버 포트
DATA_DIR=./data           # H2 DB 파일(chichijik.mv.db) 저장 폴더
UPLOAD_DIR=./data/uploads # 업로드된 영상 저장 폴더 (기본: DATA_DIR/uploads)
MAX_UPLOAD_SIZE=1GB       # 업로드 최대 크기
```

## 이번 단계 구현 범위

**구현함**
- 녹화 방송 등록 (`/upload.html`): 제목(필수, 100자), 스트리머 이름, 설명, 영상
  - 영상은 **파일 업로드**(mp4/webm/ogg/mov/m4v, 진행률 표시) 또는 **영상 주소(http/https) 연결** 중 선택
  - 등록 전 미리보기, 재생 길이를 자동 측정해 목록에 표시
- 방송 목록 (`/`): 최신순 카드, 영상 첫 장면 썸네일, 재생시간·조회수·등록 시각
- 방송 상세 (`/watch.html?id=N`): 영상 재생(구간 탐색 가능 — HTTP Range 지원), 정보·설명, 조회수 증가
- 영구 저장: 방송 정보와 영상 위치는 H2 파일 DB(`data/chichijik.mv.db`), 업로드 파일은 `data/uploads/`에 저장되어 재시작 후에도 유지
- 서버 측 입력 검증과 한국어 오류 메시지 (빈 제목, 영상 누락, 허용되지 않은 형식/주소, 용량 초과 등)

**이번 단계에서 하지 않음**: 로그인·권한, 라이브 송출, 댓글/채팅, 도네이션, 방송 수정·삭제

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `GET` | `/api/broadcasts` | 방송 목록 (최신순) |
| `GET` | `/api/broadcasts/{id}` | 방송 상세 |
| `POST` | `/api/broadcasts` | 등록. `multipart/form-data`: `title`(필수), `streamer`, `description`, `durationSec`, 그리고 `video`(파일) **또는** `videoUrl` 중 하나 |
| `POST` | `/api/broadcasts/{id}/views` | 조회수 1 증가 |
| `GET` | `/media/{filename}` | 업로드된 영상 파일 (Range 요청 지원) |

응답의 `videoUrl`은 업로드 영상이면 `/media/...`, 연결한 영상이면 원래 주소입니다. 오류는 `{"error": "메시지"}` 형태입니다.

## 직접 확인 절차

테스트용 5초 영상이 `samples/sample.mp4`에 들어 있습니다.

1. 서버 실행 → http://localhost:8080 접속. 처음엔 "아직 등록된 방송이 없습니다"가 보입니다.
2. **＋ 방송 등록** → 제목 입력 → *파일 업로드* 탭에서 `samples/sample.mp4` 선택(미리보기가 뜸) → **등록하기**.
3. 등록이 끝나면 상세 화면으로 이동합니다. 영상이 재생되는지, 탐색 바로 구간 이동이 되는지 확인합니다.
4. **← 목록으로** → 방금 등록한 카드(썸네일, `0:05` 배지, 조회수 1회)가 보이는지 확인하고, 카드를 눌러 다시 재생합니다.
5. (선택) *영상 주소 연결* 탭에서 공개 mp4 주소를 넣어 등록하고 재생되는지 확인합니다.
6. (선택) 제목을 비우거나 영상 없이 등록하면 오류 메시지가 뜨는지 확인합니다.
7. 서버를 종료(IntelliJ ■ 또는 `Ctrl+C`)하고 다시 실행 → 목록·상세·재생과 조회수가 그대로 남아 있는지 확인합니다.

자동 검증: `./gradlew test` — 임시 폴더로 실제 서버를 두 번 띄워 등록 → 목록 → Range 재생 → 재시작 후 조회까지 확인합니다.

## 설계 문서

시스템 아키텍처, ERD, 기술적 고민 및 개선 과제는 [docs/stage1-design.md](docs/stage1-design.md)에 정리했습니다.

## 폴더 구조

```
src/main/java/com/chichijik/
  ChichijikApplication.java     진입점
  broadcast/                    엔티티 · 리포지토리 · 서비스(검증, 파일 저장) · 컨트롤러
  config/MediaConfig.java       업로드 폴더 생성, /media/** 영상 제공
  common/                       공통 예외와 오류 응답
src/main/resources/
  application.properties        설정 (환경변수 기본값)
  static/                       목록(index) · 상세(watch) · 등록(upload) 화면
src/test/java/                  통합 테스트
samples/                        확인용 샘플 영상
data/                           (실행 시 생성, git 제외) DB와 업로드 파일
```
