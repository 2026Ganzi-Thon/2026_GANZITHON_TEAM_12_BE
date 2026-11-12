# Come-down(캄다운) Backend

[![CI](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_12_BE/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_12_BE/actions/workflows/ci.yml)

> 2026 간지톤 TEAM 12 · 백엔드 저장소

**Come-down(캄다운)** 은 수업·과제·시험·동아리·아르바이트로 일정이 몰릴 때 번아웃을 미리 막도록 돕는 서비스입니다.
구글 캘린더로 **일정이 얼마나 몰려 있는지**, 아침 1분 손가락 측정(HRV)으로 **몸이 얼마나 회복됐는지**를 함께 보고, 남는 시간에 맞춘 **회복 활동이나 미리 시작할 작업을 추천해 캘린더에 바로 넣어 줍니다.**

- 회복 수치와 안전 안내는 서비스의 표시 기준이며, 의료 진단이 아닙니다.
- 프론트엔드는 별도 저장소이며, 이 저장소의 [API 명세](docs/API.md)로 연결합니다.

---

## 목차

1. [핵심 기능](#1-핵심-기능)
2. [사용자 흐름](#2-사용자-흐름)
3. [기술 스택](#3-기술-스택)
4. [AI 활용](#4-ai-활용)
5. [아키텍처](#5-아키텍처)
6. [폴더 및 파일 구조](#6-폴더-및-파일-구조)
7. [시작하기](#7-시작하기)
8. [협업 규칙](#8-협업-규칙)
9. [설계 문서](#9-설계-문서)
10. [진행 현황](#10-진행-현황)

---

## 1. 핵심 기능

| 축 | 기능 | 설명 |
|---|---|---|
| 일정 | 캘린더 연동·분류 | 구글 캘린더 일정을 가져와 수업·과제·시험·발표 등으로 분류합니다. 분류는 사용자가 직접 고칠 수 있고, 고친 규칙은 다음 동기화에도 유지됩니다. |
| 일정 | 주간 일정 부하·위험 주차 | 시험·발표, 심야·주말 일정에 가중치를 둔 주간 부하와 마감 몰림·쉬는 날·연속 일정일로 앞으로 4주의 위험한 주를 알려 줍니다. |
| 일정 | 미리 시작하기(PRE_START) | 위험한 주의 마감 일정을 대비해 그 전 빈 시간에 작업 블록을 제안합니다. |
| 신체 | 아침 1분 체크 | 휴대폰 카메라에 손가락을 대고 측정 → 컨디션 체크 → 1분 호흡 → 오늘의 결과 순서로 진행합니다. |
| 신체 | 회복 상태 | 측정값으로 나만의 기준선(평소 범위)을 만들고, 오늘 회복이 평소 대비 몇 % 낮은지와 상태(`NORMAL`/`LOW`)를 보여 줍니다. |
| 신체 | 번아웃 체크(CBI) | 가입 때와 28일차에 13문항 설문으로 몸과 마음의 피로, 일·학업으로 인한 피로를 비교합니다. |
| 연결 | 회복 추천 → 캘린더 등록 | 회복이 낮거나 일정이 몰린 날, 빈 시간에 맞춘 걷기·스트레칭 등을 추천하고 승인하면 캘린더에 등록합니다(되돌리기 가능). |
| 연결 | 리포트·안전 안내 | 7일차 회복 리포트, 28일차 4주 비교 리포트, 회복이 2주 이상 낮거나 CBI 점수가 높으면 도움 받을 수 있는 곳을 안내합니다. |

## 2. 사용자 흐름

```text
구글 로그인 → 가입 정보·동의 → CBI 13문항 → 생활 정보(측정 시간·수면·선호 활동)
          → 캘린더 분석(일정 리포트) → 홈
홈 ─┬─ 아침 체크: 측정 → 컨디션 → 호흡 → 오늘 결과·추천 → 승인 시 캘린더 등록
    ├─ 캘린더: 일정 분류 수정 · 4주 위험 주차 · 미리 시작하기 제안
    ├─ 리포트: 7일차 회복 리포트 · 28일차 비교 리포트
    └─ 마이페이지: 생활 정보·동의·연결 관리 · 건강 기록 삭제 · 탈퇴
```

## 3. 기술 스택

| 분류 | 사용 기술 |
|---|---|
| 언어·런타임 | Java 21 |
| 프레임워크 | Spring Boot 4.1.2 (Web MVC, Validation, Data JPA, Security, OAuth2 Client, Session JDBC) |
| 데이터베이스 | MySQL 8.4 (utf8mb4), Flyway 마이그레이션, JPA `ddl-auto=validate` |
| 인증 | Google OAuth2 로그인 + 세션 쿠키(Spring Session JDBC), CSRF 쿠키(`XSRF-TOKEN`) |
| 외부 연동 | Google Calendar API, OpenAI API(핵심 기능 완성 후 도입) |
| 빌드 | Gradle Wrapper 9.8.2 |
| 테스트 | JUnit, Spring Boot Test(MockMvc), Testcontainers(MySQL 8.4) |
| 로컬 환경 | Docker Compose(MySQL) |
| CI | GitHub Actions (PR·main마다 빌드·테스트·문서 동기화 검사) |
| 문서 | Markdown 명세 → `docs/build_spec_html.py`로 오프라인 HTML 생성 |

## 4. AI 활용

Come-down은 **숫자는 코드가 계산하고, AI는 분류·해석·문구를 보조**합니다. 회복 상태·기준선·CBI 점수·일정 부하·위험 주차는 모두 [계산 정책](docs/계산_정책.md)의 규칙으로 계산하며 AI가 수치를 정하지 않습니다.

| 어디에 | 무엇을 | AI가 실패하면 | 저장 위치 |
|---|---|---|---|
| 일정 분류 | 구글 일정의 카테고리(수업·과제·시험·발표 등), 부담 정도(1~3), 미리 시작할 수 있는 일인지, 마감 여부 | 규칙 기반 분류(`category_source = RULE`) | `calendar_events` |
| 추천 카드 문구 | 회복 추천·미리 시작하기 카드의 설명 문구 | 5초 안에 응답이 없으면 규칙 기반 문구 | `suggestions.message` |
| 회복 리포트 해석 | 회복 그래프와 일정 부하를 2~3문장으로 풀어 설명 | 규칙 기반 요약 문구 | `ai_outputs` (`RECOVERY_INTERPRETATION`) |
| 홈 요약 | 오늘 회복·이번 주 일정을 한 줄로 요약 | 규칙 기반 문구 | `ai_outputs` (`HOME_SUMMARY`) |
| 28일차 리포트 요약 | 4주 전후 CBI·회복·부하 변화 요약 | 규칙 기반 문구 | `ai_outputs` (`FOUR_WEEK_SUMMARY`) |

- **표시:** AI 문구는 항상 `{ "text", "aiGenerated", "fallback" }` 형태로 내려가며, `aiGenerated: true`면 화면에 "AI 생성"을 표시합니다.
- **고지:** 가입 시 AI 활용 사전 고지(`AI_NOTICE`) 확인이 필수입니다(인공지능기본법상 사전 고지).
- **개인정보:** 일정 제목은 분류 요청에만 쓰고 DB에 저장하지 않습니다. AI 결과는 입력 해시로 캐시해 같은 입력에 다시 호출하지 않습니다.
- **도입 순서:** 핵심 기능을 규칙 기반 문구로 먼저 완성한 뒤 AI를 연결합니다. 그 전에도 API 응답 형태는 같습니다(`fallback: true`).

## 5. 아키텍처

단일 Spring Boot 애플리케이션 안에서 기능별로 패키지를 나눈 **모듈형 모놀리스**입니다.

```text
HTTP 요청
 ├─ 단일 기능 API ───────────→ 각 기능(account, cbi, calendar, …)의 application
 └─ 여러 기능을 묶는 API → experience ─→ 각 기능의 contract(공개 계약) → application

기능 application → 자기 domain / persistence (자기 테이블만 쓰기)
외부 연동이 필요한 기능 → 자신이 정의한 port ← integration(Google/OpenAI 어댑터)이 구현
모든 기능 → shared(오류·시간·보안·재시도 키 같은 기술 공통 코드)
```

- 테이블마다 **쓰기 소유 기능이 하나**입니다(18개 테이블 → [책임 매핑](docs/책임_매핑.md)).
- 기능끼리 Repository/Entity를 직접 가져다 쓰지 않고 `contract`로만 요청합니다.
- `shared`는 어떤 기능에도 의존하지 않고, `integration`에는 비즈니스 규칙을 두지 않습니다.
- 시간은 DB에 UTC로 저장하고, 서비스 날짜는 서울 기준·주 시작은 월요일입니다. 응답 시각은 `+09:00`을 붙인 ISO 8601입니다.

## 6. 폴더 및 파일 구조

```text
.
├── AGENTS.md                         # 팀(사람·AI) 공통 작업 규칙 — 작업 전 필독
├── README.md
├── build.gradle / settings.gradle    # Gradle 설정 (프로젝트명 come-down-backend)
├── gradlew / gradlew.bat / gradle/wrapper/
├── docker-compose.yml                # 로컬 MySQL 8.4 (localhost:3307)
├── .env.example                      # 환경 변수 예시 (실제 .env는 커밋 금지)
├── .editorconfig / .gitattributes / .gitignore
├── .github/
│   ├── pull_request_template.md
│   └── workflows/ci.yml              # GitHub Actions CI
├── scripts/
│   └── check-backend-env.ps1         # JDK·Gradle·Docker 등 개발 환경 점검
├── docs/                             # 설계 문서 (MD가 원본, HTML은 생성물)
│   ├── 서비스_요구사항.md
│   ├── API.md / API.html             # API 명세 v0.3 (39개)
│   ├── ERD.md / ERD.html             # 데이터 설계 v0.3 (18개 테이블)
│   ├── 계산_정책.md / 계산_정책.html  # 일정 부하·CBI·HRV·회복·안전·범위 위치 산식
│   ├── 검토결과.md / 검토결과.html    # 변경 이력 · 확인 필요 항목
│   ├── 백엔드_구조설계.md / .html     # 패키지 구조 · 의존 방향 · 담당 분담
│   ├── 책임_매핑.md / .html · architecture-map.json  # API·테이블 소유 기능
│   ├── 백엔드_협업가이드.md / .html
│   ├── IA_초안.png
│   └── build_spec_html.py            # MD → HTML 생성기
└── src/
    ├── main/
    │   ├── java/com/comedown/
    │   │   ├── ComeDownApplication.java
    │   │   ├── account/              # 회원·동의·생활 정보·Google 자격정보·온보딩 상태
    │   │   ├── cbi/                  # CBI 설문 버전·응답·채점
    │   │   ├── calendar/             # 구글 일정 동기화·분류·빈 시간·앱 일정 등록/삭제
    │   │   ├── schedule/             # 주간 일정 부하·위험 주차 계산
    │   │   ├── checkin/              # 아침 체크·HRV 측정·컨디션·호흡
    │   │   │   └── domain/measurement/HrvAnalyzer.java   # 카메라 밝기 신호 → RMSSD·품질
    │   │   ├── recovery/             # 하루 대표값·기준선·7일 추세·회복 상태
    │   │   ├── recommendation/       # 회복 추천·미리 시작하기 제안의 상태
    │   │   ├── safety/               # 도움 안내 조건·노출/확인 기록
    │   │   ├── subscription/         # 28일 무료 체험·이용 자격
    │   │   ├── ai/                   # AI 문구 캐시·생성/대체 표시
    │   │   ├── experience/           # 여러 기능을 묶는 흐름 (소유 테이블 없음)
    │   │   │   ├── onboarding/  assessments/  home/  morning/
    │   │   │   └── calendarflow/  reports/  suggestions/  accountlifecycle/
    │   │   ├── integration/          # 외부 전송 어댑터 (google/, openai/)
    │   │   └── shared/               # 기술 공통
    │   │       ├── config/           # WebMvcConfig (CurrentUser 주입)
    │   │       ├── error/            # ErrorCode · ApiException · 공통 오류 응답 · 예외 처리기
    │   │       ├── security/         # 보안 필터 체인 · CSRF · 로그아웃 · CurrentUser
    │   │       ├── time/             # ServiceTime(서울 날짜·주 시작) · InstantRange · Clock
    │   │       └── idempotency/      # Idempotency-Key 재시도 처리 (구현 예정)
    │   └── resources/
    │       ├── application.yml / application-local.yml
    │       └── db/migration/         # Flyway: 기준선 스키마 · Spring Session 테이블
    └── test/
        ├── java/com/comedown/
        │   ├── BaselineSchemaIntegrationTest.java   # MySQL에서 스키마 제약 검증
        │   ├── checkin/domain/measurement/HrvAnalyzerTest.java
        │   ├── shared/               # 오류 응답·보안·시간 테스트
        │   └── support/              # 고정 Clock · MySQL 컨테이너 설정
        └── resources/application-test.yml
```

각 기능 패키지 안은 필요한 만큼 `api / contract / application / domain / infrastructure`로 나눕니다. 빈 폴더를 미리 만들지 않고, 기능을 구현하는 PR에서 필요한 파일만 추가합니다. 각 패키지의 역할과 쓰기 소유 테이블은 `package-info.java`에 적혀 있습니다.

## 7. 시작하기

### 준비물
- JDK 21, Docker Desktop
- (문서 HTML을 다시 만들 때만) Python 3 + `markdown-it-py`

### 로컬 실행

```powershell
copy .env.example .env      # 값은 팀 채널로 따로 전달받기
docker compose up -d        # MySQL 8.4 → localhost:3307
.\gradlew.bat bootRun       # local 프로필로 실행 → http://localhost:8080
```

실행하면 Flyway가 기준선 스키마를 자동으로 만듭니다.

### 테스트·검증

```powershell
.\gradlew.bat test                                        # 전체 테스트 (MySQL 테스트는 Docker 필요)
.\gradlew.bat check bootJar                               # 테스트 + 실행 파일 빌드
.\scripts\check-backend-env.ps1 -RequireDocker -RunGradle # 개발 환경 점검
python docs/build_spec_html.py                            # 문서 HTML 재생성
```

## 8. 협업 규칙

자세한 내용은 [AGENTS.md](AGENTS.md)와 [협업 가이드](docs/백엔드_협업가이드.md)를 따릅니다.

| 담당 | 맡는 부분 |
|---|---|
| HRV축 (신체) | cbi, checkin, recovery, safety, recommendation(상태·회복 추천 규칙) · 아침 체크·홈·회복/4주 리포트 흐름 · 공통 영역(shared, DB 마이그레이션, 빌드) 편집 |
| 일정축 (구글 캘린더) | account, calendar, schedule, subscription, 미리 시작하기 규칙, Google 연동 · 온보딩·캘린더 동기화·추천 승인·탈퇴 흐름 · 일정 리포트 |

- 브랜치: `feat/이슈-기능`, `fix/이슈-수정`, `docs/주제`. **main 직접 push 금지**, PR + 상대 1명 리뷰 후 squash merge.
- PR을 올리면 **CI가 자동으로** 빌드·전체 테스트(MySQL 포함)·문서 HTML 동기화를 검사합니다. ❌가 뜨면 PR의 Checks 탭에서 원인을 보고 같은 브랜치에서 고쳐 다시 올립니다. CI가 통과한 PR만 merge합니다.
- API·스키마를 바꾸면 같은 PR에서 명세(MD)와 HTML을 함께 갱신합니다.
- Flyway 파일명은 `VYYYY_MM_DD_HHmmssSSS__설명.sql`(UTC). **공유된 migration은 수정하지 않고** 새 파일로 보정합니다.
- `.env`, 토큰, 키, 실사용자 건강정보·일정 제목, 측정 원신호는 커밋·로그에 남기지 않습니다.

## 9. 설계 문서

| 문서 | 내용 |
|---|---|
| [서비스 요구사항](docs/서비스_요구사항.md) | 사용자 흐름 · 백엔드 데이터 책임 · 제품 제약 |
| [API 명세](docs/API.md) | 공통 규칙(오류·인증·시간) · 39개 API 요청/응답 |
| [ERD](docs/ERD.md) | 18개 테이블 정의 · 코드 값 · 무결성·삭제 규칙 |
| [계산 정책](docs/계산_정책.md) | `load-v1` · `cbi-ko-2026.11.2` · `hrv-cam-v1` · `recovery-v1` |
| [백엔드 구조 설계](docs/백엔드_구조설계.md) | 패키지 구조 · 의존 방향 · 업무 흐름 · 담당 분담 |
| [책임 매핑](docs/책임_매핑.md) | API별 담당 위치 · 테이블별 쓰기 소유 기능 |
| [검토 결과](docs/검토결과.md) | 변경 이력 · **팀 확인이 필요한 항목** |

`docs/*.html`은 같은 내용을 브라우저에서 검색·펼쳐 보기 좋게 만든 생성물입니다(직접 수정 금지).

## 10. 진행 현황

- [x] 프로젝트 골격 · 기능별 패키지 구조
- [x] 공통 기반: 오류 응답 형식, 서울 기준 시간, 로그인 사용자·CSRF·로그아웃
- [x] 기준선 DB 스키마(18개 테이블 + 세션) · MySQL 제약 검증 테스트
- [x] HRV 측정 계산 참고 구현(`HrvAnalyzer`) · 합성 신호 테스트
- [x] CI(GitHub Actions): 빌드·테스트·문서 동기화 자동 검사
- [ ] CD: 배포 대상 결정 후 자동 배포
- [ ] 재시도 키 처리(`shared.idempotency`) · local 전용 테스트 로그인
- [ ] 일정축: Google 로그인·캘린더 동기화·분류·주간 부하·미리 시작하기·추천 승인
- [ ] HRV축: CBI·아침 체크·회복 계산·회복 추천·홈·리포트·안전 안내
- [ ] AI 연동(일정 분류·문구·리포트 해석)
