# Come-down API 명세 (v0.3, 2026-11-12)

> v0.3: 팀장 "백엔드 설계 수정 요청 1" 반영 — 측정 원신호 서버 계산, 아침 체크 순서, 재측정 사유, CBI 보기, 회복 상태 이름(NORMAL), AI 고지 필수, 계산 정책 확정([계산_정책.md](계산_정책.md)). 확인이 필요한 항목은 [검토 결과](검토결과.md) 마지막 절.

기준: 기획안 1차 수정본 + IA 차트 초안. 테이블은 [ERD.md](ERD.md), 기획 요약은 [서비스_요구사항.md](서비스_요구사항.md).
서비스명은 Come-down(캄다운)입니다. 이 문서는 서비스의 목표 계약이며 내부 폴더 설계는 [백엔드_구조설계.md](백엔드_구조설계.md)를 따릅니다.

---

## 검토 결과 (v0.2)

기획 요약 문서와 첨부 IA 차트에 대조한 **설계 명세**입니다. 실행 중인 API와는 구분합니다. 주요 수정은 [검토 결과](검토결과.md)에 정리했습니다. HTML 요약은 [API.html](API.html)입니다.

## 0. 공통 규칙

### 주소·형식
- 프론트와 백엔드는 **같은 출처**에서 동작합니다(로컬: Vite가 `/api`, `/oauth2`, `/login/oauth2`를 백엔드로 전달 / 운영: Firebase Hosting rewrite).
- 요청·응답은 JSON, 필드 이름은 camelCase.
- 시각은 서울 시간대를 붙인 ISO 8601 (`2026-11-11T07:30:00+09:00`). 날짜는 서울 기준 `YYYY-MM-DD`. 주는 **월요일 시작**.
- 값이 없으면 필드를 빼지 않고 `null`로 보냅니다.

### 인증
- 구글 로그인은 세션 쿠키 방식입니다. 프론트는 토큰을 다루지 않습니다.
- **로그인 시작**: `GET /oauth2/authorization/google`로 이동(페이지 이동, fetch 아님). 성공하면 `/`로 돌아오고, 프론트는 `GET /api/me`의 `onboardingStep`으로 화면을 정합니다.
- **캘린더 권한 받기**(권장 흐름): 로그인 때는 기본 정보만 받고, 가입 화면에서 캘린더 동의를 체크한 뒤 `GET /oauth2/authorization/google-calendar`로 이동해 캘린더 권한을 추가로 받습니다. 성공 시 `/onboarding/cbi`로 돌아옵니다. 마이페이지 "다시 연결"도 같은 주소입니다.
- **CSRF**: 앱을 처음 열 때 `GET /api/auth/csrf`를 한 번 부르면 `XSRF-TOKEN` 쿠키가 생깁니다. `POST`·`PUT`·`PATCH`·`DELETE` 요청에는 이 값을 `X-XSRF-TOKEN` 헤더에 넣습니다.

### 접근 조건
| 조건 | 해당 API | 어기면 |
|---|---|---|
| 로그인 | `/api/**` 전부(`/api/auth/csrf` 제외) | 401 `UNAUTHENTICATED` |
| 온보딩 완료 | 홈·아침 측정·회복 리포트·RECOVERY 추천 | 403 `ONBOARDING_REQUIRED` |
| 온보딩 중 허용 | 내 정보·설정·동의, CBI, 캘린더 연결·동기화·조회, 일정 리포트·주간 부하, PRE_START 조회·승인·건너뛰기, 안전 안내 | 현재 단계의 선행 조건은 별도 검증 |
| 건강정보 동의 | 측정·CBI·컨디션 저장 | 403 `HRV_CONSENT_REQUIRED` |
| 측정 켜짐 | 측정 저장 | 403 `HRV_DISABLED` |
| 유료(체험 종료 후) | 위험 주차 예측·과부하 대비 제안, 회복 일정 추천, 4주 리포트, 내 범위 중 현재 위치 | 403 `PREMIUM_REQUIRED` (**MVP에서는 막지 않고 `plan`만 내려줌**) |

### 오류 응답
```json
{ "code": "VALIDATION_FAILED", "message": "입력값을 확인하세요.", "fieldErrors": [{ "field": "nickname", "message": "1~20자여야 합니다." }] }
```
`fieldErrors`는 400 검증 실패일 때만 들어갑니다.

| HTTP | code | 언제 | 프론트 처리 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | 값 범위·형식 오류 | 필드 옆에 메시지 |
| 401 | `UNAUTHENTICATED` | 로그인 안 됨/세션 만료 | 시작 화면(A1) |
| 401 | `CALENDAR_REAUTH_REQUIRED` | 구글 재인증 필요(토큰 갱신 실패·권한 철회 등) | "캘린더 다시 연결" |
| 403 | `ONBOARDING_REQUIRED` | 온보딩 미완료 | `onboardingStep` 화면으로 |
| 403 | `CALENDAR_SCOPE_MISSING` | 구글 화면에서 캘린더 체크 해제 | "캘린더 권한이 필요해요" |
| 403 | `HRV_CONSENT_REQUIRED` / `HRV_DISABLED` | 건강정보 미동의 / 측정 꺼짐 | 동의 화면 / 마이페이지 |
| 403 | `PREMIUM_REQUIRED` | 유료 기능 | 구독 안내 |
| 404 | `NOT_FOUND` | 없는 리소스(남의 것 포함) | |
| 409 | `CALENDAR_NOT_CONNECTED` | 토큰 없음 | "캘린더 연결하기" |
| 409 | `SUGGESTION_NOT_PROPOSED` | 승인할 수 없는 제안(동일 승인 재시도 제외) | 목록 새로고침 |
| 409 | `SLOT_CONFLICT` | 그 사이 같은 시간에 일정이 생김 | "다른 시간 보기" |
| 409 | `CBI_NOT_DUE` / `CBI_ALREADY_TAKEN` | 28일 전 재응답 / 같은 회차 중복 | |
| 409 | `ONBOARDING_STEP_INVALID` / `INVALID_STATE` | 선행 단계 미완료 / 잘못된 상태 전이 | 현재 상태 다시 조회 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 재시도 키에 다른 요청 | 새 작업에 새 키 사용 |
| 422 | `CALCULATION_POLICY_PENDING` | 미확정 계산 정책이 필요한 기능 | 준비 중 안내 |
| 502 | `CALENDAR_API_ERROR` | 구글 API 실패 | 다시 시도 |

### 요청·응답 보완 규칙
- 모든 리소스는 로그인 사용자 소유인지 확인합니다. 다른 사용자 id는 404. 외부 Google 인증 문제는 앱 로그인 만료와 구분합니다.
- 아래 상태 코드가 생략된 갱신 요청은 200, 본문 없는 삭제·로그아웃·동의 변경은 204입니다. 컬렉션은 빈 배열, 계산 불가 값은 null. 400의 `fieldErrors` 등 조건부 필드는 예외입니다.
- 시각 범위는 `[start, end)`이며 start < end. 조회의 날짜 `to`는 포함하되 내부적으로 다음 날 00시 미만으로 변환합니다. 종일 일정은 `startDate`, `endDateExclusive`도 반환합니다(서울 시간 정규화). 종일 일정의 부하 가중치는 미확정이며 임의로 24시간으로 계산하지 않습니다.
- `vsUsualPct`와 내 범위 위치는 분모가 0이거나 표본이 부족하면 null + `unavailableReason`(`NO_BASELINE`, `ZERO_BASELINE`, `INSUFFICIENT_DATA`, `POLICY_PENDING`)입니다. 내 범위 최고=최저인 경우도 null(`INSUFFICIENT_DATA`). 위치는 유효할 때 0~100으로 제한합니다.
- 일정 부하(`load-v1`)·회복 계산(`recovery-v1`)·2주 연속 낮음·내 범위 위치의 산식은 [계산_정책.md](계산_정책.md)로 확정했습니다. 모든 수치는 초기값이며 결과 행의 `calculation_version`으로 버전을 남기고 실제 데이터를 보며 조정합니다. `POLICY_PENDING`/422 `CALCULATION_POLICY_PENDING`은 앞으로 새로 추가되는 미확정 정책에만 씁니다.
- POST 측정 저장·CBI 제출·추천 생성·승인에는 `Idempotency-Key`(UUID)를 필수로 보냅니다. 사용자+작업+키를 고유하게 저장하고 본문 해시와 결과를 비교합니다. 같은 키·같은 본문은 원래 결과, 같은 키·다른 본문은 409. 키 보관은 최소 24시간입니다. 측정 재측정은 새 키입니다.
- 추천 승인 시 사용자별 잠금으로 중복 승인과 겹친 승인을 직렬화하고 Google의 최신 빈 시간을 재확인합니다. 동일한 Google event id로 재시도하며 타임아웃은 조회로 확인합니다. 외부 앱이 확인 직후 일정을 추가하는 경합까지 막을 수는 없습니다.
- `onboardingStep`은 서버가 전진시킵니다. 프로필·초기 CBI·생활 정보·캘린더 권한 및 일정 분석 결과가 선행 조건이며 `/onboarding/complete`에서 다시 검사합니다. 동기화 성공+일정 0건도 완료 가능합니다. 설정 수정은 완료된 온보딩을 되돌리지 않습니다.
- CSRF 쿠키는 로그인·로그아웃 후 다시 발급받습니다. 읽기용 GET은 데이터 동기화·추천 생성 같은 도메인 변경을 하지 않습니다.

### AI 문구 표시
AI가 만든 문구는 항상 이 모양으로 내려갑니다. `aiGenerated: true`면 화면에 "AI 생성" 표시, `fallback: true`면 AI 실패로 규칙 기반 문구를 쓴 것(표시는 하지 않음).
```json
{ "text": "이번 주 일정이 평소보다 많고 회복이 평소보다 낮아요. 가볍게 움직여 보세요.", "aiGenerated": true, "fallback": false }
```

### 자주 쓰는 객체

**WeekLoad** — 주간 일정 부하
```json
{
  "weekStart": "2026-11-16", "weekEnd": "2026-11-22",
  "totalHours": 41.5, "loadScore": 52.0, "vsUsualPct": 25.0,
  "deadlineCount": 3, "lateNightCount": 2, "restDays": 0,
  "riskLevel": "RISK", "riskReasons": ["LOAD_HIGH", "DEADLINES", "NO_REST_DAY"],
  "vsUsualUnavailableReason": null
}
```
- `riskLevel`은 조건 점수 합으로 정합니다(0~1점 `NONE`, 2~3점 `WATCH`, 4점 이상 `RISK`). `riskReasons`: `LOAD_HIGH` `DEADLINES` `NO_REST_DAY` `LATE_NIGHT` `LONG_STREAK`. 산식은 [계산_정책.md](계산_정책.md) 1절.
- `vsUsualPct`가 null이면 `vsUsualUnavailableReason`이 `ZERO_BASELINE`(직전 4주 평균 0) 또는 `INSUFFICIENT_DATA`(직전 데이터 2주 미만).

**Suggestion** — 과부하 대비 제안 / 회복 일정 추천
```json
{
  "id": 31, "type": "RECOVERY", "status": "PROPOSED",
  "targetDate": "2026-11-12",
  "start": "2026-11-12T16:00:00+09:00", "end": "2026-11-12T16:20:00+09:00", "durationMin": 20,
  "title": "20분 걷기", "activityCode": "WALK",
  "reasonCode": "LOW_RECOVERY", "judgmentState": "OVERLOAD_LOW",
  "message": { "text": "회의 사이 40분이 비어 있어요. 20분만 걸어 볼까요?", "aiGenerated": true, "fallback": false },
  "relatedEvent": null, "riskWeekStart": null,
  "googleEventId": null, "doneAt": null
}
```
- `type: PRE_START`(미리 시작하기)면 `relatedEvent`에 대비할 마감 일정이 들어갑니다: `{ "id": 812, "title": "캡스톤 중간발표", "start": "...", "category": "PRESENTATION" }` (`title`은 구글에서 실시간으로 읽은 값, 못 읽으면 null).

**CalendarEvent** — 캘린더 탭 일정
```json
{
  "id": 812, "googleEventId": "abc_20261118", "recurring": true, "startDate": null, "endDateExclusive": null,
  "start": "2026-11-18T14:00:00+09:00", "end": "2026-11-18T15:30:00+09:00", "allDay": false,
  "title": "캡스톤 중간발표",
  "category": "PRESENTATION", "categorySource": "AI", "burden": 3, "adjustable": true, "isDeadline": true,
  "createdByApp": false
}
```
- `title`은 **DB에 저장하지 않고** 응답할 때 구글에서 읽어 붙입니다(열린 항목 1).

---

## 1. 인증·내 정보

### `GET /api/auth/csrf` → 204
`XSRF-TOKEN` 쿠키만 내려줍니다.

### `GET /api/me` → 200
앱을 열 때마다 불러 화면 분기에 씁니다.
```json
{
  "id": 1, "email": "ari@example.com", "nickname": "아리",
  "onboardingStep": "DONE", "onboardingCompletedAt": "2026-11-03T21:10:00+09:00",
  "dayIndex": 9,
  "calendar": { "connected": true, "scopeGranted": true },
  "consents": { "TERMS": true, "PRIVACY": true, "AGE_14": true, "SENSITIVE_HRV": true, "CALENDAR": true, "AI_NOTICE": true },
  "hrvEnabled": true,
  "plan": { "plan": "TRIAL", "trialEndsAt": "2026-12-01T21:10:00+09:00", "daysLeft": 20 }
}
```
- `dayIndex`: 온보딩 완료일을 1일차로 센 오늘 n일차. 온보딩 전이면 null.
- `onboardingStep`: `PROFILE` `CBI` `LIFESTYLE` `SCHEDULE_REPORT` `DONE`.

### `POST /api/auth/logout` → 204

### `DELETE /api/me` → 204 — 회원 탈퇴
모든 사용자 데이터·로그인 세션 및 CSRF 인증 상태를 제거합니다. 구글 토큰 철회는 실패 시 재시도합니다. 외부 정리가 진행 중이면 202 + `{ "status": "DELETING" }`, 완료 후 204. 새 로그인으로 삭제 작업을 취소하지 않습니다. Come-down이 캘린더에 넣은 일정을 지울지는 열린 항목 4.

---

## 2. 온보딩 (A1~A5)

### `PUT /api/onboarding/profile` — A2 회원가입 정보
```json
{
  "nickname": "아리",
  "consents": { "TERMS": true, "PRIVACY": true, "AGE_14": true, "SENSITIVE_HRV": true, "CALENDAR": true, "AI_NOTICE": true },
  "consentVersion": "2026-11-01"
}
```
- 검증: 닉네임 1~20자. `TERMS` `PRIVACY` `AGE_14` `CALENDAR` `AI_NOTICE`는 true 필수(`AI_NOTICE`: 인공지능기본법상 AI 기반 운용 사실 사전 고지). `SENSITIVE_HRV`가 false면 가입은 되지만 측정이 꺼집니다(열린 항목 3).
- 응답 200: `{ "onboardingStep": "CBI", "calendarAuthUrl": "/oauth2/authorization/google-calendar" }` — `calendarAuthUrl`이 있으면(아직 캘린더 권한이 없으면) 그 주소로 이동합니다.

### `GET /api/cbi/questions` → 200 — A3 문항
```json
{
  "version": "cbi-ko-2026.11.2",
  "scales": {
    "FREQUENCY": ["항상", "자주", "가끔", "드물게", "전혀 또는 거의 없음"],
    "DEGREE": ["매우 많이", "많이", "어느 정도", "조금", "거의 없음"]
  },
  "items": [
    { "no": 1, "subscale": "PERSONAL", "scale": "FREQUENCY", "text": "얼마나 자주 피곤함을 느끼나요?" }
  ]
}
```
- 13문항: 1~6 `PERSONAL`(몸과 마음의 피로), 7~13 `WORK`(일·공부로 인한 피로). 빈도형(FREQUENCY) 10문항, 정도형(DEGREE) 3문항(7·8·9).
- 보기 option 0→4의 점수는 100 / 75 / 50 / 25 / 0. 13번은 역채점(서버가 뒤집음). 하위척도 점수 = 응답 점수 평균(0~100).
- 화면은 문항 순서를 섞어서 보여줍니다(원 매뉴얼 권고). 서버의 `no`는 아래 번호를 유지합니다.

| no | 하위척도 | 척도 | 문항 |
|---|---|---|---|
| 1 | PERSONAL | FREQUENCY | 얼마나 자주 피곤함을 느끼나요? |
| 2 | PERSONAL | FREQUENCY | 얼마나 자주 몸이 지쳐 있나요? |
| 3 | PERSONAL | FREQUENCY | 얼마나 자주 마음이 지쳐 있나요? |
| 4 | PERSONAL | FREQUENCY | 얼마나 자주 '더는 못 버티겠다'고 생각하나요? |
| 5 | PERSONAL | FREQUENCY | 얼마나 자주 기진맥진하다고 느끼나요? |
| 6 | PERSONAL | FREQUENCY | 얼마나 자주 몸이 약해져 병에 걸리기 쉽다고 느끼나요? |
| 7 | WORK | DEGREE | 지금 하는 일·학업이 감정적으로 지치게 하나요? |
| 8 | WORK | DEGREE | 일·학업 때문에 다 타버린 느낌이 드나요? |
| 9 | WORK | DEGREE | 일·학업 때문에 좌절감을 느끼나요? |
| 10 | WORK | FREQUENCY | 하루 일과가 끝나면 기진맥진한가요? |
| 11 | WORK | FREQUENCY | 아침에 또 하루를 보낼 생각만 해도 지치나요? |
| 12 | WORK | FREQUENCY | 일·학업을 하는 매 시간이 힘들게 느껴지나요? |
| 13 | WORK | FREQUENCY | 여가 시간에 가족·친구와 보낼 에너지가 충분한가요? (역채점) |

### `POST /api/cbi/assessments` — CBI 제출 (가입 때·28일차 공용)
```json
{ "round": "INITIAL", "questionnaireVersion": "cbi-ko-2026.11.2", "answers": [{ "no": 1, "option": 1 }, { "no": 2, "option": 0 }] }
```
- 요청에 `questionnaireVersion`을 필수로 포함합니다(문항 조회의 version). `option`은 보기 순서(0~4). 서로 다른 번호 1~13의 응답을 정확히 13개 보내며 중복·누락·버전 불일치는 400. 역문항·환산은 해당 문항 버전으로 서버가 처리합니다.
- `round: DAY28`은 `dayIndex ≥ 28`일 때만(아니면 409 `CBI_NOT_DUE`). 같은 회차 두 번은 409 `CBI_ALREADY_TAKEN`.
- MVP의 round는 INITIAL·DAY28만 허용합니다. FOLLOW_UP은 미지원입니다.
- 응답 201:
```json
{
  "id": 5, "round": "INITIAL", "takenAt": "2026-11-03T21:02:00+09:00",
  "personal": { "score": 62.5, "level": "HIGH" },
  "work": { "score": 55.4, "level": "HIGH" },
  "highRisk": false,
  "safety": { "show": false },
  "onboardingStep": "LIFESTYLE"
}
```
- `level`: `LOW`(50 미만) `HIGH`(50 이상 75 미만) `VERY_HIGH`(75 이상). 이는 프로젝트 표시 기준이며 진단을 뜻하지 않습니다. `highRisk`(어느 하나 75 이상)면 `safety.show: true` → C3 안전 안내를 띄웁니다.

### `GET /api/cbi/assessments` → 200
내 응답 이력(최신순). 원점수 없이 점수·level만.

### `PUT /api/me/settings` — A4 생활 정보 (마이페이지 수정과 공용)
```json
{
  "measureTime": "07:30",
  "sleepStart": "23:00", "sleepEnd": "07:00",
  "preferredActivities": ["WALK", "STRETCH", "MEDITATION"]
}
```
- 활동 1개 이상. 온보딩 중이면 응답에 `"onboardingStep": "SCHEDULE_REPORT"`.
- 저장 직후 프론트는 `POST /api/calendar/sync`를 불러 A5용 분석을 시작합니다.

### `GET /api/schedule/report` → 200 — A5 일정 리포트 (홈·캘린더 탭에서도 사용)
```json
{
  "syncedAt": "2026-11-03T21:05:10+09:00",
  "classification": { "total": 48, "classified": 46, "pending": 2 },
  "pastMonth": {
    "from": "2026-10-03", "to": "2026-11-02",
    "restDays": 2, "lateNightCount": 9, "totalHours": 132.5, "weekendHours": 18.0, "deadlineCount": 7
  },
  "thisWeek": { "weekStart": "2026-11-02", "vsUsualPct": 18.0, "riskLevel": "WATCH" },
  "upcomingWeeks": [ "WeekLoad × 4 (다음 주부터)" ],
  "enoughData": true
}
```
- `enoughData: false`면 일정이 너무 적은 경우 → 빈 상태 화면.
- 과부하 대비 제안은 `GET /api/suggestions?type=PRE_START`로 따로 받습니다.

### `POST /api/onboarding/complete` → 200 — A5 "홈으로"
`{ "onboardingStep": "DONE", "dayIndex": 1 }`. 이때부터 1일차·무료 체험이 시작됩니다.

---

## 3. 홈 (B1)

### `GET /api/home` → 200
홈에 필요한 값을 한 번에 줍니다.
```json
{
  "nickname": "아리", "dayIndex": 9, "phase": "ESTABLISHED",
  "todayCheck": { "status": "DONE" },
  "recovery": {
    "status": "LOW", "changePct": -6.0,
    "baseline": { "validDays": 9, "required": 7, "established": true },
    "conditionScore": 3, "excludedToday": false
  },
  "thisWeekLoad": { "vsUsualPct": 18.0, "riskLevel": "WATCH" },
  "judgment": "OVERLOAD_LOW",
  "todaySuggestions": [ "Suggestion" ],
  "safety": { "show": false },
  "plan": { "plan": "TRIAL", "daysLeft": 20 }
}
```
- `phase`: `BUILDING`(적격 측정 7일 미만) / `ESTABLISHED`(7일 확보 후). `dayIndex`와 구분합니다. 빠짐없이 유효 측정했다면 7일차 체크 완료 후 리포트가 열리고, 누락·교란·품질 실패가 있으면 늦어질 수 있습니다.
- `todayCheck.status`: `NOT_STARTED` `IN_PROGRESS` `DONE` → `NOT_STARTED`면 "측정하러 가기"를 위에 크게.
- `recovery.status`: `BUILDING` `NORMAL` `LOW` `INSUFFICIENT`. 0~100 회복 점수는 쓰지 않고 "평소 대비 %"(`changePct`) + 상태로 표시합니다(예: "평소보다 6% 낮아요").
- `judgment`: `OK` `OVERLOAD_OK` `OVERLOAD_LOW` `NORMAL_LOW` `BUILDING` `INSUFFICIENT`. 기준선이 있어도 최근 자료가 부족하면 `INSUFFICIENT`이며 HRV에 근거한 LOW/NORMAL 판단을 하지 않습니다.

---

## 4. 캘린더·일정 (B2)

### `POST /api/calendar/sync` → 200
구글에서 일정을 읽어 저장 → 분류(직접 수정 규칙 → AI → 실패 시 규칙) → 주간 부하 계산 → 과부하 대비 제안 갱신까지 한 번에 합니다.
```json
{ "from": "2026-10-11T00:00:00+09:00", "to": "2026-12-14T00:00:00+09:00",
  "fetched": 52, "kept": 48, "created": 3, "updated": 1, "deleted": 0, "classified": 4 }
```
- 범위: 시작 = min(오늘 − 1개월, 이번 주 월요일 − 4주), 끝 = 이번 주 월요일 + 5주.
- 홈·캘린더 탭에 들어올 때 마지막 동기화가 10분 넘었으면 부릅니다.

### `GET /api/calendar/events?from=2026-11-16&to=2026-11-22` → 200
`{ "events": [CalendarEvent], "week": WeekLoad }`
- 기간 최대 35일. 일 단위 날짜(`from`, `to` 포함). 복수 주를 포함하면 `week`는 null이며 해당 주 목록은 `/api/schedule/weeks`로 조회합니다. 구글 제목 조회가 실패하면 `title: null`, `titleUnavailable: true`로 저장된 일정·부하를 계속 보여줍니다.

### `PATCH /api/calendar/events/{id}/category` — 분류 직접 수정
```json
{ "category": "ASSIGNMENT", "burden": 2, "adjustable": true, "isDeadline": true, "scope": "SERIES" }
```
- `scope`: `THIS`(이 일정만) `SERIES`(반복 일정 전체, 반복 일정일 때만) `TITLE`(같은 제목 일정 전체).
- 필드 category·burden(1~3)·adjustable·isDeadline은 필수입니다. 우선순위는 THIS > SERIES > TITLE > AI > RULE. 범위 규칙은 현재 동기화된 일정과 이후 들어오는 일정에 적용하며 원본 Google 일정은 변경하지 않습니다.
- 응답 200: `{ "updatedCount": 12, "weeks": [WeekLoad] }` — 바뀐 주의 부하를 다시 계산해 돌려줍니다.

### `GET /api/schedule/weeks?from=2026-10-12&count=9` → 200
`{ "weeks": [WeekLoad] }`. 캘린더 탭 맨 위 4주 요약, 리포트 비교에 사용.

### `DELETE /api/calendar/connection` → 204 — 마이페이지 "캘린더 연결 해제"
캘린더 연결 해제 + 동기화한 일정·분류 수정 규칙·주간 부하·미승인 제안 및 해당 일정 기반 AI 캐시 삭제. 승인 이력은 유지하며 relatedEvent는 null. 외부 연결 해제는 캘린더 권한에 한정하고 로그인 세션은 유지합니다. 실패 시 재시도하며 정리 중이면 202, 완료 후 204. 다시 쓰려면 `/oauth2/authorization/google-calendar`.

---

## 5. 제안·추천 (A5, B1, B2, B4 공용)

### `GET /api/suggestions?type=RECOVERY&status=PROPOSED&date=2026-11-12` → 200
`{ "suggestions": [Suggestion] }`. 모든 파라미터는 선택.
- 홈 "오늘의 추천": `date=오늘`
- 일정 리포트·캘린더 탭 "과부하 대비 제안": `type=PRE_START&status=PROPOSED`

### `POST /api/suggestions/generate` — 추천 다시 만들기
```json
{ "type": "RECOVERY", "date": "2026-11-12" }
```
- 보통은 서버가 알아서 만듭니다(동기화 후 PRE_START, 컨디션 체크 후 RECOVERY). 이 API는 "다른 추천 보기"용. 응답 200 `{ "suggestions": [Suggestion] }`.
- 빈 시간 탐색: 수면 시간대 제외, 앞뒤 일정과 10분 이상 간격, 하루 추천 최대 2개(설정값).

### `GET /api/suggestions/{id}/slots` → 200 — "다른 시간 보기"
`{ "slots": [{ "start": "...", "end": "..." }] }`. 같은 날·같은 길이로 넣을 수 있는 빈 시간 최대 5개.

### `POST /api/suggestions/{id}/approve` — 승인 → 구글 캘린더에 등록
```json
{ "start": "2026-11-12T17:00:00+09:00", "end": "2026-11-12T17:20:00+09:00" }
```
- 본문은 선택(시간을 바꿀 때만). 응답 200: 상태가 `APPROVED`이고 `googleEventId`가 채워진 Suggestion.
- 변경 시간은 같은 targetDate·같은 길이·수면 제외 조건을 만족해야 합니다(400). 이미 승인된 동일 요청은 원래 결과 200. 확정 실패는 `FAILED`, 외부 결과 확인 중은 `APPROVING` + 202. 오류: 409 `SUGGESTION_NOT_PROPOSED`, 409 `SLOT_CONFLICT`, 캘린더 오류들.

### `POST /api/suggestions/{id}/dismiss` → 200 — 건너뛰기/나중에

### `POST /api/suggestions/{id}/undo` → 200 — "되돌리기"
Come-down이 넣은 그 일정만 구글에서 지우고 `UNDONE`으로 바꿉니다.

### `POST /api/suggestions/{id}/done` → 200 — "했어요" (다음 날 효과 확인용)

- 상태 규칙: `PROPOSED → APPROVING → APPROVED`; `PROPOSED → DISMISSED/EXPIRED`; 확정 등록 실패만 `APPROVING → FAILED`(재시도는 FAILED → APPROVING). 타임아웃은 FAILED로 확정하지 않습니다.
- dismiss는 PROPOSED에서만, undo는 APPROVED에서만 허용합니다. undo는 앱 소유 Google 일정임을 확인하고 삭제 후 UNDONE(이미 삭제된 404도 성공). done은 APPROVED에서 일정 종료 후 허용하고 `doneAt`만 채우며 상태는 유지합니다. 완료한 일정은 undo 불가입니다. 반복 dismiss/undo/done은 같은 결과 200, 그 외 전이는 409 `INVALID_STATE`입니다.
- generate는 기존 APPROVED를 보존하고 미승인 중복을 만들지 않습니다. 모든 갱신 응답은 최신 Suggestion입니다.

---

## 6. 아침 측정 (B3~B6)

순서: **측정 → 컨디션 → 호흡 → 오늘 결과·추천**(B6). 하루 1회(`morning_checks`), 측정은 여러 번(재측정) 가능. 결과·추천은 컨디션 저장(`PUT …/condition`) 때 계산해 돌려주고, 프론트는 호흡 훈련이 끝난 뒤(또는 건너뛴 뒤) 결과 화면에 보여줍니다. 호흡은 건너뛸 수 있으므로 결과 계산과 `DONE` 기준은 컨디션 저장(`completed_at`)입니다.

### `GET /api/morning-checks/today` → 200 — 측정 탭 진입
```json
{
  "date": "2026-11-12", "dayIndex": 3, "phase": "BUILDING",
  "steps": {
    "measurement": { "done": true, "measurementId": 77, "qualityLevel": "GOOD" },
    "condition": { "done": false },
    "breathing": { "done": false }
  },
  "baseline": { "validDays": 2, "required": 7 },
  "result": null
}
```
- 컨디션까지 끝났으면 `result`에 아래 TodayResult가 들어옵니다(다시 들어와도 결과 화면). `steps`는 화면 순서대로 measurement → condition → breathing입니다. GET은 체크 행을 만들지 않습니다. 측정·컨디션·호흡 중 첫 저장에서 생성합니다. `DONE`은 completed_at 기준이며 측정·호흡을 못 한 날도 컨디션 완료로 마칠 수 있습니다.

### `POST /api/hrv/measurements` → 201 — ① 손가락 측정 저장
**CAMERA**: 프론트는 약 70초(준비 10초 포함) 동안 수집한 프레임별 밝기 배열을 그대로 보내고, **백엔드가 RMSSD와 품질을 계산**합니다. 원신호(t·r·g)는 계산에만 쓰고 DB에 저장하지 않습니다(최소 수집).
```json
{
  "source": "CAMERA",
  "startedAt": "2026-11-12T07:12:03+09:00",
  "torch": true,
  "timeSource": "captureTime",
  "t": [0, 16.7, 33.4],
  "r": [212.41, 211.83, 212.06],
  "g": [41.2, 41.52, 41.31]
}
```
| 필드 | 설명 |
|---|---|
| startedAt | 측정 시작(손가락 감지) 시각. 서울 날짜 판정 기준이며 `measured_at`으로 저장 |
| torch | 플래시가 실제로 켜졌는지 |
| timeSource | `captureTime` / `mediaTime` / `performance` |
| t | 시작 = 0 기준 ms, 증가 순서. 준비 10초 포함 총 약 70초 |
| r, g | 프레임별 빨강·초록 평균 밝기(0~255) |

- 검증(400): t·r·g 길이 동일, 각 배열 최대 10,000개, 모든 값 유한수, r·g는 0~255. 요청 본문 상한 512KB(60fps 기준 약 100KB). **측정 시간 부족·프레임 부족은 400이 아니라 품질 판정(`RETRY`)으로 처리**합니다.
- 계산 순서·품질 기준은 [계산_정책.md](계산_정책.md) 3절(참고 구현 `checkin.domain.measurement.HrvAnalyzer`, `algorithm_version: hrv-cam-v1`). 프레임 수·길이·fps·유효/제외 박동 간격·잡음비·채널은 서버 계산값으로 저장합니다.
- local 프로필에서만 임계값 조정용으로 원신호를 파일로 남기는 설정 플래그를 둡니다(기본 꺼짐, Git 제외 경로). 운영에서는 켤 수 없습니다.
- **MANUAL**(카메라를 못 쓰는 기기에서 워치 값 입력): 기존대로 `{ "source": "MANUAL", "measuredAt": "...", "rmssdMs": 42.5 }`. **RMSSD(ms)**만 허용(SDNN 불가), qualityLevel `UNVERIFIED`, 기준선·자동 회복 판정에서 제외하고 참고용으로만 표시합니다.
- 공통: **측정 날짜는 측정 시작 시각(CAMERA `startedAt`, MANUAL `measuredAt`)의 서울 날짜**로 정합니다(자정 직전에 시작해 자정 뒤에 저장돼도 시작한 날의 체크). 시작 시각은 오늘 또는 어제여야 하고 미래 시각은 허용하지 않으며, 폰 시계 오류·조작을 막기 위해 서버 수신 시각과 5분 넘게 차이 나면 400입니다. 운영에서 과거 측정 입력은 지원하지 않습니다.
- 응답:
```json
{
  "measurement": { "id": 78, "qualityLevel": "RETRY", "retryRequired": true, "retryReason": "TOO_NOISY", "rmssdMs": null, "meanHrBpm": null },
  "morningCheckId": 40
}
```
- `RETRY`면 `retryRequired: true`와 `retryReason` → 사유별 재측정 안내(기준선 계산에는 안 씀). 성공이면 `retryReason: null`.

| retryReason | 조건 |
|---|---|
| `TOO_SHORT` | 앞 10초 제외 후 분석 구간 50초 미만 |
| `LOW_FPS` | 초당 20프레임 미만 |
| `NO_PULSE` | 반복성(자기상관) 0.5 미만, 또는 박동 3개 미만 |
| `TOO_NOISY` | 유효 간격 쌍·유효 비율·잡음비 기준 미달 |
- `BUILDING`에도 적격 측정은 기준선 구축에 씁니다. 7일 확보 전 HRV를 회복 판정·추천 근거로 쓰지 않습니다(IA "반영은 X"). 재측정은 컨디션 저장 후에도 같은 아침 체크에 연결하고 결과·리포트·미승인 추천을 다시 계산합니다.
- 그날 컨디션·교란 요인을 아직 입력하지 않았다면 무교란으로 간주하지 않습니다. 컨디션 저장 전 측정은 기준선 적격일에 포함하지 않습니다.

### `PUT /api/morning-checks/today/condition` → 200 — ② 컨디션 체크 → 오늘 결과 계산
```json
{ "score": 3, "alcohol": false, "sick": false, "caffeine": true }
```
- 측정 없이도 저장 가능(카메라 실패한 날). 저장 후 그날 회복 상태를 다시 계산하고 RECOVERY 추천을 만들어 함께 돌려줍니다. 프론트는 이 응답을 들고 호흡 훈련으로 넘어간 뒤 결과 화면에서 보여줍니다.
- 응답 **TodayResult**:
```json
{
  "date": "2026-11-12", "phase": "ESTABLISHED",
  "recovery": {
    "status": "LOW", "changePct": -6.0,
    "trendRmssdMs": 39.8,
    "normalRange": { "lowerMs": 38.1, "upperMs": 51.6 }
  },
  "qualityLevel": "GOOD", "heartRateBpm": 61.2,
  "excludedFromBaseline": true,
  "baseline": { "validDays": 9, "required": 7, "established": true, "frozen": false },
  "conditionScore": 3,
  "judgment": "OVERLOAD_LOW",
  "suggestions": [ "Suggestion (type RECOVERY)" ]
}
```
- `excludedFromBaseline: true` → "오늘은 계산에서 빠졌어요". `phase: BUILDING`이면 `recovery`는 `status: BUILDING`이고 나머지는 null, `baseline.validDays`로 "n/7".
- `recovery.status`: `BUILDING` `NORMAL` `LOW` `INSUFFICIENT`. `trendRmssdMs`·`normalRange`는 ln 단위 계산값을 exp로 변환한 표시값입니다([계산_정책.md](계산_정책.md) 4절).
- 추천 문구는 AI가 5초 안에 응답하지 않으면 규칙 기반 문구로 대체합니다(측정 후 5초 안에 결과 표시).

### `POST /api/morning-checks/today/breathing` → 204 — ③ 호흡 1분 완료 (건너뛰기 가능)
```json
{ "durationSec": 60 }
```
- 컨디션 저장 뒤에 부릅니다. 건너뛰면 부르지 않으며 결과·완료 여부에 영향이 없습니다.

---

## 7. 회복 리포트 (C1) · 28일차

### `GET /api/reports/recovery?days=14` → 200
```json
{
  "locked": false,
  "unlock": { "requiredDays": 7, "validDays": 9 },
  "graph": {
    "from": "2026-10-30", "to": "2026-11-12",
    "points": [{ "date": "2026-11-05", "rmssdMs": 44.1, "excluded": false, "qualityLevel": "GOOD" }],
    "trend": [{ "date": "2026-11-05", "rmssdMs": 43.0 }],
    "normalBand": [{ "date": "2026-11-05", "lowerMs": 38.3, "upperMs": 51.2 }],
    "normalRange": { "lowerMs": 38.1, "upperMs": 51.6 },
    "loadWeeks": [
      { "weekStart": "2026-10-26", "weekEnd": "2026-11-01", "loadScore": 41.0, "vsUsualPct": -5.0, "riskLevel": "NONE" },
      { "weekStart": "2026-11-02", "weekEnd": "2026-11-08", "loadScore": 52.0, "vsUsualPct": 25.0, "riskLevel": "WATCH" },
      { "weekStart": "2026-11-09", "weekEnd": "2026-11-15", "loadScore": 48.5, "vsUsualPct": 12.0, "riskLevel": "NONE" }
    ]
  },
  "hrvVsUsualPct": -6.0,
  "latestQuality": "GOOD",
  "loadVsUsualPct": 18.0,
  "interpretation": { "text": "...", "aiGenerated": true, "fallback": false },
  "rangePosition": {
    "available": false, "availableFrom": "2026-12-01",
    "recovery": null,
    "load": null
  }
}
```
- 기준선 확정 전이면 200 + `locked: true` → "유효 측정 7일이 모이면 공개돼요 · {validDays}/7". 원칙적으로 7일차 체크 완료 후 공개되며 달력 일수만으로 잠금을 풀지 않습니다.
- **그래프**: HRV는 하루 단위, 일정 부하는 주 단위라 단위가 다르므로 **X축(날짜)만 공유하고 표현을 다르게** 합니다. 부하가 높은 주에 회복이 떨어졌는지를 한눈에 보기 위한 구성입니다.

| 요소 | 필드 | 표현 |
|---|---|---|
| 날짜 축 | `from`·`to` | 서울 날짜, 오늘 포함 `days`일 |
| HRV 점 | `points` | 날마다 점(하루 대표값). `excluded: true`(교란일)는 회색. 측정 없는 날은 항목 없음 |
| 7일 평균선 | `trend` | exp(`trend_ln`). 추세가 없는 날은 항목 없음 |
| 정상 범위 띠 | `normalBand` | 날짜별 기준선 평균 ± 1SD(ms 변환). 기준선이 매일 갱신되므로 날짜별 값 |
| 일정 부하 막대 | `loadWeeks` | 주 단위 막대를 해당 주 월~일 폭으로 깔기. `from`~`to`와 겹치는 모든 주(첫·마지막 주는 범위 밖 날짜 포함) |

- 두 그래프를 겹쳐 그리거나 위아래로 나눠 X축만 맞추는 것은 프론트 선택입니다. `normalRange`는 오늘 기준 정상 범위(요약 표시용)입니다. `loadWeeks`는 캘린더 미연결이면 빈 배열입니다.
- `rangePosition`(dayIndex ≥ 28이면서 7일 추세 값이 20개 이상일 때): `recovery`·`load` 각각 `{ "position": 35, "low": 33.2, "high": 55.0 }`. `position` = (현재 − 최저) ÷ (최고 − 최저) × 100, 0~100으로 제한. 최저·최고 = 지금까지의 7일 추세 값(회복은 ln, 부하는 주간 부하 점수)의 하위 5%·상위 5% 백분위. 최저 = 최고면 null + `INSUFFICIENT_DATA`. 회복과 부하는 따로 계산합니다([계산_정책.md](계산_정책.md) 6절).
- `days`는 7~90.

### `GET /api/reports/four-week` → 200 — 28일차 비교 리포트
```json
{
  "available": true,
  "cbi": {
    "initial": { "personal": 62.5, "work": 55.4 },
    "day28": { "personal": 50.0, "work": 46.4 },
    "diff": { "personal": -12.5, "work": -9.0 }
  },
  "recovery": { "firstWeekAvgMs": 41.0, "lastWeekAvgMs": 44.2, "changePct": 7.8 },
  "load": { "firstWeekVsUsualPct": 22.0, "lastWeekVsUsualPct": 5.0 },
  "suggestions": { "approved": 11, "done": 8 },
  "interpretation": { "text": "...", "aiGenerated": true, "fallback": false },
  "plan": { "plan": "TRIAL", "daysLeft": 0 }
}
```
- dayIndex < 28이면 200 + `available: false`, `cbiDue: false`, `availableFrom`(온보딩 서울 날짜 + 27일), 나머지 보고서 항목은 null입니다. 28일차이나 DAY28 CBI를 아직 안 했으면 `available: false` + `"cbiDue": true` → 재응답 화면으로(`POST /api/cbi/assessments`, round `DAY28`).

---

## 8. 마이페이지 (C2)

| 메서드 | 경로 | 내용 |
|---|---|---|
| PATCH | `/api/me` | 닉네임 수정 `{ "nickname": "아리" }` |
| GET | `/api/me/settings` | 생활 정보 조회 (`PUT /api/me/settings`와 같은 모양) |
| PUT | `/api/me/settings` | 측정 시간·수면 시간대·선호 활동 수정 (2절) |
| GET | `/api/me/consents` | `{ "consents": [{ "type": "SENSITIVE_HRV", "agreed": true, "agreedAt": "...", "version": "..." }] }` |
| POST | `/api/me/consents` | 다시 동의 `{ "type": "SENSITIVE_HRV", "version": "2026-11-01" }` → 측정 켜짐 |
| DELETE | `/api/me/consents/SENSITIVE_HRV?deleteData=true` | 건강정보 동의 철회 → 측정 꺼짐, `deleteData`면 측정값 삭제 |
| DELETE | `/api/hrv/data` | 건강 기록 전체 삭제(아래 범위 참고) |
| DELETE | `/api/calendar/connection` | 캘린더 연결 해제 (4절) |
| POST | `/api/auth/logout` | 로그아웃 |
| DELETE | `/api/me` | 회원 탈퇴 |

- `GET /api/me/settings`는 measureTime·sleepStart·sleepEnd·preferredActivities·hrvEnabled를 반환합니다. `PUT /api/me/settings`는 앞의 생활 정보 4필드 전체 교체이며 hrvEnabled 변경은 동의 API로만 처리합니다. 시간 HH:mm, 수면 시작≠종료, 활동 중복·미등록 코드 불가입니다.
- `DELETE /api/hrv/data`와 `deleteData=true`는 측정·일별 회복 상태·컨디션 및 교란 요인·CBI 응답·회복 AI 캐시·회복 기반 미승인 추천·안전 안내를 삭제하고 기준선과 카운터를 초기화합니다. morning_checks에는 호흡 완료 등 비건강 메타데이터만 남길 수 있습니다. 승인된 일정·이력의 건강 근거는 제거합니다. CBI로 이미 통과한 온보딩 단계는 되돌리지 않습니다. "건강 기록 전체 삭제"로 UI에 표시하며 사용자가 삭제 범위를 확인하게 합니다.
- 민감정보 비동의/철회 사용자는 일정 기능·호흡·일반 추천만 사용합니다. CBI 응답·컨디션·측정 저장은 동일한 `SENSITIVE_HRV` 동의가 필요합니다. 동의 없이 CBI 단계는 명시적 건너뛰기로 통과할 수 있습니다: `POST /api/onboarding/cbi/skip` → 200 `{ "onboardingStep": "LIFESTYLE" }` (PROFILE 완료·해당 동의 없음인 경우만). 회복 리포트는 200 + locked와 `unavailableReason: CONSENT_REQUIRED`, 4주 리포트는 200 + available:false·cbiDue:false·같은 사유로 표시합니다. 캘린더만 쓰는 흐름을 막지 않습니다.

용어 사전(C4)은 프론트 정적 데이터로 둡니다(API 없음).

---

## 9. 도움 받을 수 있는 곳 (C3)

### `GET /api/safety` → 200
```json
{
  "show": true,
  "alerts": [{ "id": 3, "triggerType": "LOW_RECOVERY_2W", "triggeredAt": "...", "message": "최근 2주 동안 회복이 평소보다 낮게 이어졌어요." }],
  "resources": [
    { "id": "verified-support-resource", "name": "공식 확인 후 등록한 지원 기관", "phone": null, "url": null, "verifiedAt": null }
  ],
  "disclaimer": "Come-down은 의료 서비스가 아니며 질병 유무를 판단하지 않아요."
}
```
- resources 예시는 구조용 placeholder입니다. 실제 연락처·링크는 출시 전에 공식 기관 정보로 확인하고 verifiedAt을 기록해야 하며 미확인 항목에는 전화 버튼을 노출하지 않습니다.
- 조건: 회복 낮음 2주 연속(`LOW_RECOVERY_2W`) 또는 CBI 75점 이상(`CBI_HIGH`). 마이페이지 메뉴에서는 `show`와 상관없이 언제든 볼 수 있습니다.

### `POST /api/safety/alerts/{id}/acknowledge` → 204 — "닫기"
- `LOW_RECOVERY_2W`: 최근 14일 중 대표값이 있는 날이 7일 이상이고 그 날들의 회복 상태가 모두 `LOW`. 측정하지 않은 날은 판정에서 빠집니다(낮음으로도 정상으로도 세지 않음).
- 단순 acknowledge는 조건 해제와 다릅니다. 닫은 안내는 같은 연속 구간에서는 다시 띄우지 않고, 조건이 해제(`resolved_at`)됐다가 다시 충족되면 새 안내로 노출합니다([계산_정책.md](계산_정책.md) 5절).

---

## 10. 화면 → API

| 화면 | 불러오는 API | 버튼·동작 |
|---|---|---|
| 앱 시작 | `GET /api/auth/csrf`, `GET /api/me` | `onboardingStep`으로 분기 |
| A1 시작 | — | Google로 계속하기 → `/oauth2/authorization/google` |
| A2 회원가입 | — | 다음 → `PUT /api/onboarding/profile` → (필요하면) `calendarAuthUrl`로 이동 |
| A3 CBI | `GET /api/cbi/questions` | 완료 → `POST /api/cbi/assessments` (`highRisk`면 C3) |
| A4 생활 정보 | — | 일정 분석 시작 → `PUT /api/me/settings` → `POST /api/calendar/sync` (로딩) |
| A5 일정 리포트 | `GET /api/schedule/report`, `GET /api/suggestions?type=PRE_START` | 승인 `POST …/approve` · 나중에 `…/dismiss` · 홈으로 `POST /api/onboarding/complete` |
| B1 홈 | `GET /api/home` | 추천 승인/건너뛰기, 측정하러 가기 |
| B2 캘린더 | `POST /api/calendar/sync`(10분 지났으면), `GET /api/calendar/events`, `GET /api/schedule/weeks`, `GET /api/suggestions?type=PRE_START` | 분류 수정 `PATCH …/category` |
| B3 측정 | `GET /api/morning-checks/today` | 측정 `POST /api/hrv/measurements` → `RETRY`면 `retryReason`별 재측정 안내 |
| B4 컨디션 체크 | — | `PUT …/condition` (결과 계산, 응답 보관) |
| B5 호흡 훈련 | — | 완료 `POST …/breathing` (건너뛰기 가능) |
| B6 결과 | 컨디션 응답의 TodayResult 또는 `GET /api/morning-checks/today`의 `result` | 추천 승인 |
| C1 회복 리포트 | `GET /api/reports/recovery` | |
| 28일차 | `GET /api/reports/four-week` | CBI 재응답 `POST /api/cbi/assessments` (DAY28) |
| C2 마이페이지 | `GET /api/me`, `GET /api/me/settings` | 8절 |
| C3 안전 안내 | `GET /api/safety` | 닫기 `…/acknowledge`, 전화 `tel:` |
| C4 용어 사전 | (정적) | |

## 11. 개발용 (local 프로필에서만)
| 메서드 | 경로 | 내용 |
|---|---|---|
| POST | `/api/dev/login` | 테스트 로그인 → 204 `{ "userId": 1 }`. Google 로그인 없이 그 사용자로 세션을 만듦(로그인 없이 호출 가능, CSRF 필요). 사용자 행은 만들지 않으므로 DB에 있어야 함. 로그인 뒤 `GET /api/auth/csrf` 다시 호출 |
| POST | `/api/dev/seed` | 4주치 시연 데이터(일정 + HRV + CBI) `{ "scenario": "DEMO_OVERLOAD" }` |
| POST | `/api/dev/clock` | 서버 기준 날짜 이동(28일차 시연) `{ "date": "2026-12-01" }` |
| DELETE | `/api/dev/data` | 모든 사용자 데이터 삭제 |

## 12. 백엔드 내부 책임 연결

API URL은 유지하며 내부 패키지는 서비스 책임으로 구분합니다. HTTP 책임 39개와 ERD 테이블의 쓰기 소유권은 [책임_매핑.md](책임_매핑.md), 신규 폴더 구조와 의존 방향은 [백엔드_구조설계.md](백엔드_구조설계.md)를 봅니다. 현재 구현의 패키지나 클래스명으로 설계를 결정하지 않습니다.

## 13. 열린 항목
1. 캘린더 탭에서 일정 제목을 보여줄지, 보여준다면 매번 구글에서 읽을지(지금 명세) 저장할지.
2. 측정 알림을 캘린더 반복 일정으로 할지 → 하면 `PUT /api/me/settings`에 `reminder: true` 추가.
3. 건강정보 동의를 필수로 할지. 현재 선택으로 유지하고 CBI 건너뛰기·일정 전용 흐름을 v0.2 제안으로 명시.
4. 탈퇴할 때 Come-down이 넣은 캘린더 일정도 지울지.
5. ~~회복 점수를 숫자로 보여줄지~~ → v0.3 확정: 0~100 점수를 쓰지 않음(평소 대비 % + 상태).
6. ~~안전 안내를 닫은 뒤 다시 보여줄 기준~~ → v0.3 확정: 조건 해제 후 재충족 시 새 안내.


## 14. 추가 결정 항목
v0.3에서 확정(→ [계산_정책.md](계산_정책.md)): 일정 부하 가중치·임계값·OTHER·미분류·종일·겹친 일정 규칙, 회복 계산 단위·기준선·LOW 판정·확정 당일, 2주 연속 낮음, 4주 범위 위치의 최소 표본·백분위, CBI 문항·보기 버전(`cbi-ko-2026.11.2`).

아직 남은 항목:
- 최근 표본 부족·민감정보 비동의 시 일반 추천 규칙, 컨디션 점수 임계값.
- 구글 토큰 철회가 로그인용 권한까지 영향을 줄 때 캘린더만 해제하는 처리, 탈퇴 시 앱 생성 일정 보존/삭제 선택.
- 본 검토는 임상적 유효성이나 법적 적합성을 검증한 것이 아닙니다.
