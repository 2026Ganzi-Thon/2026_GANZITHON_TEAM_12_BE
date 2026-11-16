package com.comedown.experience.calendarflow;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventSource;
import com.comedown.calendar.contract.SyncedEvent;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 동기화·분류 수정·일정 조회 흐름(구글은 대체 응답). 기준 시각 2026-11-12 07:30 서울(목). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class CalendarFlowIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @MockitoBean
    CalendarEventSource source;

    @MockitoBean
    CalendarCredentialProvider credentials;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        when(credentials.credentialFor(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new CalendarCredential("token"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'calflow-%'");
    }

    @Test
    void 동기화는_일정을_저장하고_주간_부하를_계산한다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("e1", null, "캡스톤 회의", "2026-11-18T05:00:00Z", "2026-11-18T06:30:00Z"),
                timed("e2", "series-1", "스터디", "2026-11-19T05:00:00Z", "2026-11-19T07:00:00Z")));

        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-12T00:00:00+09:00"))
                .andExpect(jsonPath("$.to").value("2026-12-14T00:00:00+09:00"))
                .andExpect(jsonPath("$.fetched").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.deleted").value(0));

        assertThat(count("calendar_events", user)).isEqualTo(2);
        assertThat(count("weekly_loads", user)).isEqualTo(9); // 10-12 ~ 12-07 월요일 9개
        // 직접 수정 규칙이 없으면 제목 규칙으로 분류한다(출처 RULE).
        assertThat(category(user, "e1")).isEqualTo("WORK_MEETING");
        assertThat(category(user, "e2")).isEqualTo("STUDY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calendar_events WHERE user_id = ? "
                + "AND category_source = 'RULE'", Integer.class, user)).isEqualTo(2);
    }

    @Test
    void 과제_시험_발표는_마감으로_분류되고_제목이_바뀌면_다시_분류한다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("d1", null, "운영체제 과제 제출", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z"),
                timed("d2", null, "기말고사", "2026-11-19T05:00:00Z", "2026-11-19T06:00:00Z"),
                timed("d3", null, "시험 공부", "2026-11-20T05:00:00Z", "2026-11-20T06:00:00Z"),
                timed("d4", null, "헬스", "2026-11-20T09:00:00Z", "2026-11-20T10:00:00Z")));

        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classified").value(4));

        assertThat(category(user, "d1")).isEqualTo("ASSIGNMENT");
        assertThat(category(user, "d2")).isEqualTo("EXAM");
        assertThat(category(user, "d3")).isEqualTo("STUDY");
        assertThat(category(user, "d4")).isEqualTo("EXERCISE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calendar_events WHERE user_id = ? AND is_deadline",
                Integer.class, user)).isEqualTo(2); // 과제·시험
        // 마감 2개가 주간 부하의 마감 수에 반영된다.
        assertThat(jdbc.queryForObject("SELECT deadline_count FROM weekly_loads WHERE user_id = ? "
                + "AND week_start = '2026-11-16'", Integer.class, user)).isEqualTo(2);

        // 다시 동기화: 그대로면 다시 분류하지 않고, 제목이 바뀐 일정만 새로 분류한다.
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("d1", null, "운영체제 과제 제출", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z"),
                timed("d2", null, "기말고사", "2026-11-19T05:00:00Z", "2026-11-19T06:00:00Z"),
                timed("d3", null, "시험 공부", "2026-11-20T05:00:00Z", "2026-11-20T06:00:00Z"),
                timed("d4", null, "팀플 회의", "2026-11-20T09:00:00Z", "2026-11-20T10:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classified").value(1));
        assertThat(category(user, "d4")).isEqualTo("WORK_MEETING");
    }

    @Test
    void 직접_고친_분류는_제목_규칙보다_우선하고_다시_동기화해도_유지된다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("u1", null, "운영체제 과제 제출", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf())).andExpect(status().isOk());
        assertThat(category(user, "u1")).isEqualTo("ASSIGNMENT");

        patchCategory(user, eventId(user, "u1"), "CLUB", "THIS").andExpect(status().isOk());
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classified").value(0));

        assertThat(category(user, "u1")).isEqualTo("CLUB");
        assertThat(jdbc.queryForObject("SELECT category_source FROM calendar_events WHERE user_id = ?",
                String.class, user)).isEqualTo("USER");
    }

    @Test
    void 다시_동기화하면_바뀐_일정은_갱신하고_사라진_일정은_지우며_직접_고친_분류는_지킨다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("e1", null, "회의", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z"),
                timed("e2", null, "스터디", "2026-11-19T05:00:00Z", "2026-11-19T06:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf())).andExpect(status().isOk());
        long e1 = eventId(user, "e1");
        patchCategory(user, e1, "EXAM", "THIS").andExpect(status().isOk());

        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("e1", null, "회의", "2026-11-18T05:00:00Z", "2026-11-18T07:00:00Z"))); // e1 길어짐, e2 삭제
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.created").value(0));

        assertThat(count("calendar_events", user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT category_source FROM calendar_events WHERE id = ?", String.class, e1))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT category FROM calendar_events WHERE id = ?", String.class, e1))
                .isEqualTo("EXAM");
    }

    @Test
    void 제안이_참조하는_일정이_사라지면_지우지_않고_cancelled로_표시한다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("e1", null, "발표", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf())).andExpect(status().isOk());
        long e1 = eventId(user, "e1");
        jdbc.update("INSERT INTO suggestions (user_id, type, status, target_date, start_at, end_at, title, "
                + "reason_code, calculation_version, registration_key, related_event_id, expires_at, created_at, updated_at) "
                + "VALUES (?, 'PRE_START', 'PROPOSED', '2026-11-17', '2026-11-17 05:00:00', '2026-11-17 05:30:00', "
                + "'t', 'R', 'v', ?, ?, '2026-11-20 00:00:00', '2026-11-12 00:00:00', '2026-11-12 00:00:00')",
                user, "cd" + UUID.randomUUID().toString().replace("-", "").substring(0, 30), e1);

        when(source.fetch(any(), any())).thenReturn(List.of());
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        assertThat(jdbc.queryForObject("SELECT event_status FROM calendar_events WHERE id = ?", String.class, e1))
                .isEqualTo("cancelled");
        jdbc.update("DELETE FROM suggestions WHERE user_id = ?", user);
    }

    @Test
    void 캘린더_이용_동의가_없으면_구글을_부르지_않고_아무것도_저장하지_않는다() throws Exception {
        long user = newUser();
        jdbc.update("DELETE FROM user_consents WHERE user_id = ?", user); // 프로필 단계 전: 권한만 받은 상태

        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_INVALID"))
                .andExpect(jsonPath("$.message").value("캘린더 이용 동의를 먼저 완료해 주세요."));

        org.mockito.Mockito.verifyNoInteractions(source, credentials);
        assertThat(count("calendar_events", user)).isZero();
        assertThat(count("weekly_loads", user)).isZero();

        // 동의를 기록하면 동기화된다.
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, 'CALENDAR', "
                + "'2026-11-01', '2026-11-11 22:00:00')", user);
        when(credentials.credentialFor(user)).thenReturn(new CalendarCredential("token"));
        when(source.fetch(any(), any())).thenReturn(List.of());
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void 구글_오류는_API_오류_코드로_바뀐다() throws Exception {
        long user = newUser();
        doThrow(new ExternalServiceException(Kind.UNAUTHORIZED, "x")).when(source).fetch(any(), any());
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CALENDAR_REAUTH_REQUIRED"));

        doThrow(new ExternalServiceException(Kind.FAILURE, "x")).when(source).fetch(any(), any());
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CALENDAR_API_ERROR"));

        doThrow(new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED)).when(credentials).credentialFor(user);
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CALENDAR_NOT_CONNECTED"));
    }

    @Test
    void 분류_수정은_범위별로_적용되고_영향_주_부하를_돌려준다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("a1", "series-1", "스터디", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z"),
                timed("a2", "series-1", "스터디", "2026-11-25T05:00:00Z", "2026-11-25T06:00:00Z"),
                timed("a3", null, "스터디", "2026-11-26T05:00:00Z", "2026-11-26T06:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf())).andExpect(status().isOk());
        long a1 = eventId(user, "a1");

        patchCategory(user, a1, "ASSIGNMENT", "SERIES").andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2))
                .andExpect(jsonPath("$.weeks.length()").value(2));
        assertThat(category(user, "a3")).isEqualTo("STUDY"); // 직접 고치지 않은 일정은 제목 규칙 분류 그대로

        patchCategory(user, a1, "EXAM", "TITLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1)); // a1·a2는 더 높은 SERIES 규칙이 있어 a3만 바뀐다

        // THIS가 SERIES·TITLE보다 우선한다.
        patchCategory(user, a1, "PARTY_NOT_VALID", "THIS").andExpect(status().isBadRequest());
        patchCategory(user, a1, "CLUB", "THIS").andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));
        assertThat(category(user, "a1")).isEqualTo("CLUB");
        assertThat(category(user, "a2")).isEqualTo("ASSIGNMENT");
        assertThat(category(user, "a3")).isEqualTo("EXAM");
    }

    @Test
    void 분류_수정_검증_실패와_남의_일정() throws Exception {
        long owner = newUser();
        long other = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("b1", null, "회의", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(owner)).with(csrf())).andExpect(status().isOk());
        long b1 = eventId(owner, "b1");

        patchCategory(other, b1, "EXAM", "THIS").andExpect(status().isNotFound());
        patchCategory(owner, b1, "EXAM", "SERIES").andExpect(status().isBadRequest()); // 반복 일정 아님
        patchCategory(owner, 999999, "EXAM", "THIS").andExpect(status().isNotFound());
        mvc.perform(patch("/api/calendar/events/" + b1 + "/category").with(loginAs(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"EXAM\",\"burden\":4,\"adjustable\":true,\"isDeadline\":false,"
                                + "\"scope\":\"THIS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 일정_조회는_구글에서_제목을_붙이고_실패하면_제목_없이_준다() throws Exception {
        long user = newUser();
        when(source.fetch(any(), any())).thenReturn(List.of(
                timed("c1", null, "캡스톤 발표", "2026-11-18T05:00:00Z", "2026-11-18T06:00:00Z")));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf())).andExpect(status().isOk());

        mvc.perform(get("/api/calendar/events?from=2026-11-16&to=2026-11-22").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].title").value("캡스톤 발표"))
                .andExpect(jsonPath("$.events[0].titleUnavailable").value(false))
                .andExpect(jsonPath("$.events[0].start").value("2026-11-18T14:00:00+09:00"))
                .andExpect(jsonPath("$.events[0].googleEventId").value("c1"))
                .andExpect(jsonPath("$.week.weekStart").value("2026-11-16"));

        doThrow(new ExternalServiceException(Kind.FAILURE, "x")).when(source).fetch(any(), any());
        mvc.perform(get("/api/calendar/events?from=2026-11-16&to=2026-11-22").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].title").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.events[0].titleUnavailable").value(true))
                .andExpect(jsonPath("$.week.weekStart").value("2026-11-16"));

        // 여러 주에 걸치면 week는 null, 35일을 넘으면 400.
        mvc.perform(get("/api/calendar/events?from=2026-11-16&to=2026-11-29").with(loginAs(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.week").value(Matchers.nullValue()));
        mvc.perform(get("/api/calendar/events?from=2026-11-01&to=2026-12-06").with(loginAs(user)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/calendar/events?from=2026-11-01&to=2026-12-05").with(loginAs(user)))
                .andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    private org.springframework.test.web.servlet.ResultActions patchCategory(long user, long eventId, String category,
            String scope) throws Exception {
        return mvc.perform(patch("/api/calendar/events/" + eventId + "/category").with(loginAs(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"" + category + "\",\"burden\":2,\"adjustable\":true,\"isDeadline\":false,"
                        + "\"scope\":\"" + scope + "\"}"));
    }

    private SyncedEvent timed(String id, String recurring, String title, String startUtc, String endUtc) {
        return new SyncedEvent(id, recurring, title, false, Instant.parse(startUtc), Instant.parse(endUtc), null, null,
                "confirmed", "opaque", false, Instant.parse("2026-11-10T00:00:00Z"));
    }

    private long newUser() {
        String sub = "calflow-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'SCHEDULE_REPORT', '2026-09-01 00:00:00', '2026-09-01 00:00:00')", sub);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc, scopes, "
                + "updated_at) VALUES (?, 'google', ?, 'enc', 'openid https://www.googleapis.com/auth/calendar.events', "
                + "'2026-11-11 22:30:00.000000')", id, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, 'CALENDAR', "
                + "'2026-11-01', '2026-09-01 00:00:00')", id);
        return id;
    }

    private long eventId(long user, String googleId) {
        return jdbc.queryForObject("SELECT id FROM calendar_events WHERE user_id = ? AND google_event_id = ?",
                Long.class, user, googleId);
    }

    private String category(long user, String googleId) {
        return jdbc.queryForObject("SELECT category FROM calendar_events WHERE user_id = ? AND google_event_id = ?",
                String.class, user, googleId);
    }

    private int count(String table, long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
    }
}
