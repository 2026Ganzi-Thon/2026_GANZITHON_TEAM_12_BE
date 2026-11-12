# Come-down(캄다운) 백엔드 협업·AI 작업 규칙

이 폴더가 독립 백엔드 저장소 루트다. 상위 폴더나 프론트 저장소를 전제로 하지 않는다. 사람과 AI 모두 아래 규칙을 따른다.

## 설계 기준과 시작 전 확인

- [서비스 요구사항](docs/서비스_요구사항.md), [API](docs/API.md), [ERD](docs/ERD.md), [계산 정책](docs/계산_정책.md), [백엔드 구조 설계](docs/백엔드_구조설계.md), [책임 매핑](docs/책임_매핑.md)을 먼저 읽는다.
- 계산 산식·임계값은 [계산 정책](docs/계산_정책.md)의 버전 값만 사용한다. "구현 해석(확인 필요)" 항목은 확정되면 문서와 함께 갱신한다.
- 구조는 서비스 전체와 API/ERD로 설계한다. 현재 구현의 패키지·클래스·테스트 위치를 새 구조의 기준으로 삼지 않는다. 기존 구현이 제품의 미확정 정책을 결정하지도 않는다.
- 서비스 표시명은 Come-down(캄다운). 신규 레포 이름 come-down-backend, 신규 Java 패키지 com.comedown을 제안한다. 기존 DB·키·패키지의 변경은 별도 작업으로 검토한다.
- 단일 Spring Boot/Gradle/MySQL로 시작한다. 목표 디렉터리는 구조 설계 문서를 따른다. 기능을 구현하는 PR에서 필요한 파일만 만든다.
- 브랜치·변경 파일·담당 영역을 확인하고 다른 작업자의 수정과 미커밋 파일을 덮어쓰거나 되돌리지 않는다.

## 기능 책임과 의존 방향

- 기능은 account, cbi, calendar, schedule, checkin, recovery, recommendation, safety, subscription, ai로 구분한다. 여러 기능을 묶는 흐름은 experience, 기술 공통은 shared, 외부 전송은 integration에 둔다.
- 기능마다 api/contract/application/domain/infrastructure를 필요에 따라 사용한다. 단순 CRUD에 모델·어댑터를 기계적으로 중복 생성하지 않는다.
- experience → 기능 contract → 각 기능 application 방향을 지킨다. 기능 → experience, 기능 간 Repository/Entity import, shared → 기능, integration → 기능 application 의존을 만들지 않는다.
- 테이블 쓰기 소유권은 architecture-map.json의 tableOwners를 따른다. 다른 기능 테이블을 직접 갱신하지 않는다. 통합 읽기는 계약의 스냅샷으로 조합한다.
- API의 URL이 내부 패키지를 결정하지 않는다. 측정 저장은 checkin, 회복 계산은 recovery, TodayResult 조합은 experience.morning 책임이다.
- HTTP Controller는 API별 하나만 둔다. 직접 기능 API와 복합 흐름 API의 소유자는 책임 매핑을 따른다.
- account는 동의/계정/자격정보, calendar는 일정 쓰기, recommendation은 제안 상태를 소유한다. 순환 호출 대신 experience가 연결한다.

## 구현 규칙

- Controller는 HTTP 변환과 검증, application은 유스케이스·트랜잭션, domain/policy는 상태/순수 계산, persistence는 소유 테이블 조회·저장에 집중한다.
- 생성자 주입, 불변 요청/응답 DTO를 기본으로 하고 Entity를 응답하거나 기능 간 전달하지 않는다. 신규 Java는 공백 4칸, UTF-8·LF, bat는 CRLF.
- 요청 본문의 userId를 신뢰하지 않는다. 인증 사용자 컨텍스트와 서버의 동의/소유권 검증을 사용한다. 오류 응답은 API 명세 형식으로 통일한다.
- 시간은 주입한 Clock, DB 순간은 UTC/Instant, 서비스 날짜는 Asia/Seoul, 주 시작은 월요일이다. 고정 시계로 날짜 경계를 검증한다.
- 회복·CBI·일정 부하는 코드로 계산하고 AI는 분류/해석을 보조한다. 미확정 산식·임계값·안전 조건을 임의로 정하지 않는다.
- Google/OpenAI 호출을 긴 DB 트랜잭션 안에 넣지 않는다. 추천 승인 중간 상태·등록 키·타임아웃 재확인과 재시도를 함께 구현한다.
- 탈퇴/동의 철회/건강 기록 삭제/연결 해제는 서로 다른 유스케이스다. experience가 소유 기능의 삭제 계약을 조율하고 원본·파생·캐시·세션을 함께 정리한다.

## 환경·문서

- 환경의 제안 기준은 Java 21 / Spring Boot 4.1.2 / dependency-management 1.1.7 / Gradle Wrapper 9.8.2 / MySQL 8.4 계열이다. 최신 권장 목록이 아니다. 실제 팀 레포와 다르면 두 사람이 합의해 설정·스크립트·문서를 함께 갱신한다.
- 전역 gradle 대신 Wrapper, IDE SDK와 Gradle JVM은 Java 21을 사용한다. Spring Boot 관리 의존성의 개별 override·도구 업그레이드는 별도 PR로 이유를 남긴다.
- API·ERD·구조·책임 매핑·협업 가이드의 MD/JSON이 원본이다. HTML은 `python docs/build_spec_html.py`로 생성하고 직접 고치지 않는다. Python/markdown-it-py는 문서 생성에만 필요하다.
- API나 스키마를 바꾸면 같은 PR에서 명세·책임 매핑·생성 HTML을 갱신한다. 신규 폴더 책임을 임의로 추가하지 않는다.

## 브랜치·리뷰·DB 변경

- 사람은 feat/이슈-기능, fix/이슈-수정, docs/주제 브랜치. Codex가 새 브랜치를 만들면 codex/주제. 기존 브랜치를 임의 변경하지 않는다.
- main 직접 push와 공유 브랜치 force push 금지. 목적별 작은 PR을 만들고 상대 개발자 1인 리뷰 후 squash merge한다.
- 이슈/PR에 주 담당, 수정할 패키지, 공개 계약, API/DB 영향, 검증 결과를 적는다. 공통 설정/보안/외부 어댑터/migration은 편집 담당 1명을 먼저 정한다.
- Flyway 새 파일은 VYYYY_MM_DD_HHmmssSSS__description.sql(UTC 시각), 버전 중복·순서를 병합 전에 확인한다. 공유/적용한 migration은 수정·삭제·개명하지 않고 새 migration으로 보정한다.
- 아직 공유/적용하지 않은 migration만 main 이후 버전으로 재번호한다. outOfOrder/repair로 충돌을 숨기지 않는다. JPA ddl-auto=validate를 사용한다.
- DB 초기화·docker compose down -v·Flyway clean·운영 배포 등 범위 밖 파괴적 작업을 임의 수행하지 않는다.

## 검증·완료 보고

백엔드 저장소 루트에서 필요한 검증을 실행한다.

```powershell
.\scripts\check-backend-env.ps1 -RequireDocker -RunGradle
.\gradlew.bat test
.\gradlew.bat check bootJar
python docs/build_spec_html.py
```

- 규칙 테스트는 계산 경계/결측/교란, API는 상태/동의/소유권, MySQL은 FK/UK/Flyway, experience는 중간 실패/재시도/삭제 경계를 검증한다.
- Docker가 없거나 테스트가 skip됐으면 통합 검증 완료라고 쓰지 않는다. 기능 패키지와 같은 테스트 위치 및 test/support를 목표로 사용한다. 테스트 구조도 현재 구현을 그대로 복제하지 않는다.
- 실제 Google/OpenAI 대신 대체 응답과 고정 Clock을 사용한다. 실패를 피하려고 테스트를 끄거나 운영 인증/동의를 우회하지 않는다.
- 문서만 바꾸면 매핑·링크·생성 결과를 확인하고 불필요한 서버 테스트를 돌리지 않아도 된다. 설계 문서 검증과 실제 구현 검증을 구분해서 보고한다.
- `.env`·OAuth 토큰·암호화 키·실사용자 건강정보·개인 일정 제목·원신호/영상을 커밋/로그/ZIP/PR에 넣지 않는다. `.env.example`에는 빈 예시만 둔다.

## Code Review Rules

- 다른 사용자 접근·동의 우회·local 전용 기능의 운영 노출을 결함으로 지적한다.
- 기능 간 Repository/Entity 의존, 중복 테이블 쓰기 소유자, experience와 기능의 순환 호출을 확인한다.
- 중복 일정 등록·타임아웃의 잘못된 실패 확정·삭제 데이터의 캐시 재생성을 확인한다.
- 미확정 정책을 기존 구현 동작으로 확정하거나 API·ERD·책임 매핑과 다른 계약을 만드는 변경을 확인한다.
