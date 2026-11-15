package com.comedown.experience.accountlifecycle;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.experience.accountlifecycle.application.AccountLifecycleFlow;
import com.comedown.integration.google.GoogleOAuthClient;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 캘린더 연결 해제·탈퇴·건강정보 동의 철회·건강 기록 삭제. 구글 철회는 대체 응답(성공·실패)이다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class AccountLifecycleIntegrationTest {

    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar.events";
    private static final Instant NOW = Instant.parse("2026-11-11T22:30:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    GoogleAccountService accounts;

    @Autowired
    AccountLifecycleFlow flow;

    @Autowired
    @SuppressWarnings("rawtypes")
    FindByIndexNameSessionRepository sessions;

    @MockitoBean
    GoogleOAuthClient google;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        reset(google);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM suggestions WHERE title = 'lifecycle-test'");
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'life-%'");
    }

    @Test
    void 캘린더_연결_해제는_일정과_파생_데이터를_지우고_승인_이력은_유지한다() throws Exception {
        long user = userWithCalendar();
        long event = event(user, "g1");
        suggestion(user, "PROPOSED", event);
        long approved = suggestion(user, "APPROVED", event);
        jdbc.update("INSERT INTO event_category_overrides (user_id, scope, match_key, category, burden, adjustable, "
                + "is_deadline, created_at, updated_at) VALUES (?, 'THIS', 'g1', 'EXAM', 2, TRUE, FALSE, "
                + "'2026-11-11 00:00:00', '2026-11-11 00:00:00')", user);
        jdbc.update("INSERT INTO weekly_loads (user_id, week_start, total_hours, load_score, deadline_count, "
                + "late_night_count, weekend_hours, rest_days, risk_level, risk_reasons, calculation_version, "
                + "computed_at) VALUES (?, '2026-11-09', 1, 1, 0, 0, 0, 6, 'NONE', '[]', 'load-v1', "
                + "'2026-11-11 00:00:00')", user);
        doNothing().when(google).revoke(any());

        mvc.perform(delete("/api/calendar/connection").with(loginAs(user)).with(csrf()))
                .andExpect(status().isNoContent());

        verify(google).revoke("refresh-" + user);
        assertThat(count("calendar_events", user)).isZero();
        assertThat(count("event_category_overrides", user)).isZero();
        assertThat(count("weekly_loads", user)).isZero();
        assertThat(count("oauth_tokens", user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suggestions WHERE user_id = ? AND status = 'PROPOSED'",
                Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT related_event_id FROM suggestions WHERE id = ?", Long.class,
                approved)).isNull();
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.calendar.connected").value(false));
    }

    @Test
    void 구글_철회가_실패하면_202이고_연결은_끊긴_채_재시도로_끝난다() throws Exception {
        long user = userWithCalendar();
        event(user, "g1");
        doThrow(new ExternalServiceException(Kind.FAILURE, "x")).when(google).revoke(any());

        mvc.perform(delete("/api/calendar/connection").with(loginAs(user)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETING"));

        // 데이터는 지워졌고, 토큰은 철회가 끝날 때까지 보관되며 연결은 끊겨 보인다.
        assertThat(count("calendar_events", user)).isZero();
        assertThat(count("oauth_tokens", user)).isEqualTo(1);
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.calendar.connected").value(false));
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CALENDAR_NOT_CONNECTED"));
        assertThat(count("calendar_events", user)).isZero(); // 삭제한 데이터가 다시 생기지 않는다

        // 재시도 작업이 이어서 끝낸다.
        reset(google);
        flow.retryPending();
        verify(google).revoke("refresh-" + user);
        assertThat(count("oauth_tokens", user)).isZero();
    }

    @Test
    void 다시_연결하면_철회_요청이_풀리고_새_토큰으로_연결된다() throws Exception {
        long user = userWithCalendar();
        doThrow(new ExternalServiceException(Kind.FAILURE, "x")).when(google).revoke(any());
        mvc.perform(delete("/api/calendar/connection").with(loginAs(user)).with(csrf()))
                .andExpect(status().isAccepted());

        accounts.saveTokens(user, "life-sub-" + user, new TokenGrant("access-new", "refresh-new",
                NOW, NOW.plusSeconds(3500), Set.of("openid", CALENDAR)));

        mvc.perform(get("/api/me").with(loginAs(user))).andExpect(jsonPath("$.calendar.connected").value(true));
        reset(google);
        flow.retryPending();
        org.mockito.Mockito.verifyNoInteractions(google); // 더는 철회 대기가 아니다
    }

    @Test
    void 탈퇴는_세션과_모든_데이터를_지우고_쿠키를_비운다() throws Exception {
        long user = userWithCalendar();
        long event = event(user, "g1");
        suggestion(user, "APPROVED", event);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', 'v1', '2026-11-01 00:00:00')", user);
        insertCbi(user);
        @SuppressWarnings("unchecked")
        Session session = (Session) sessions.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, String.valueOf(user));
        saveSession(session);
        doNothing().when(google).revoke(any());

        mvc.perform(delete("/api/me").with(loginAs(user)).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItems(
                                org.hamcrest.Matchers.startsWith("SESSION=; Path=/; Max-Age=0"),
                                org.hamcrest.Matchers.startsWith("XSRF-TOKEN=; Path=/; Max-Age=0"))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, user)).isZero();
        for (String table : new String[] {"calendar_events", "suggestions", "cbi_assessments", "user_consents",
                "oauth_tokens"}) {
            assertThat(count(table, user)).as(table).isZero();
        }
        assertThat(sessions.findById(session.getId())).isNull();
    }

    @Test
    void 탈퇴_중_구글_철회가_실패하면_202이고_재시도가_끝낸다() throws Exception {
        long user = userWithCalendar();
        doThrow(new ExternalServiceException(Kind.FAILURE, "x")).when(google).revoke(any());

        mvc.perform(delete("/api/me").with(loginAs(user)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETING"));
        assertThat(jdbc.queryForObject("SELECT deletion_status FROM users WHERE id = ?", String.class, user))
                .isEqualTo("DELETING");

        reset(google);
        doNothing().when(google).revoke(any());
        flow.retryPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, user)).isZero();
    }

    @Test
    void 건강정보_동의_철회는_측정을_끄고_deleteData면_건강_기록도_지운다() throws Exception {
        long user = newUser();
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', 'v1', '2026-11-01 00:00:00')", user);
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, "
                + "'2026-11-01 00:00:00')", user);
        insertCbi(user);

        mvc.perform(delete("/api/me/consents/SENSITIVE_HRV").with(loginAs(user)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.consents.SENSITIVE_HRV").value(false))
                .andExpect(jsonPath("$.hrvEnabled").value(false));
        assertThat(count("cbi_assessments", user)).isEqualTo(1); // deleteData 없이는 기록을 지우지 않는다

        mvc.perform(delete("/api/me/consents/SENSITIVE_HRV?deleteData=true").with(loginAs(user)).with(csrf()))
                .andExpect(status().isNoContent()); // 이미 철회돼도 재시도 안전
        assertThat(count("cbi_assessments", user)).isZero();
    }

    @Test
    void 건강_기록_삭제는_동의는_유지하고_기록만_지운다() throws Exception {
        long user = newUser();
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', 'v1', '2026-11-01 00:00:00')", user);
        insertCbi(user);

        mvc.perform(delete("/api/hrv/data").with(loginAs(user)).with(csrf())).andExpect(status().isNoContent());

        assertThat(count("cbi_assessments", user)).isZero();
        mvc.perform(get("/api/me").with(loginAs(user))).andExpect(jsonPath("$.consents.SENSITIVE_HRV").value(true));
    }

    // ---------- 도우미 ----------

    @SuppressWarnings("unchecked")
    private void saveSession(Session session) {
        sessions.save(session);
    }

    private long newUser() {
        String sub = "life-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-01 00:00:00', '2026-09-01 00:00:00', "
                + "'2026-09-01 00:00:00')", sub);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    /** 캘린더 권한과 refresh token("refresh-{id}")이 있는 사용자. */
    private long userWithCalendar() {
        long id = newUser();
        String sub = jdbc.queryForObject("SELECT google_sub FROM users WHERE id = ?", String.class, id);
        accounts.saveTokens(id, sub, new TokenGrant("access-" + id, "refresh-" + id, NOW, NOW.plusSeconds(3500),
                Set.of("openid", CALENDAR)));
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, 'CALENDAR', "
                + "'2026-11-01', '2026-09-01 00:00:00')", id);
        return id;
    }

    private long event(long user, String googleId) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, category, synced_at) VALUES (?, ?, "
                + "'2026-11-18 05:00:00', '2026-11-18 06:00:00', FALSE, 'confirmed', 'opaque', FALSE, 'CLASS', "
                + "'2026-11-11 22:30:00.000000')", user, googleId);
        return jdbc.queryForObject("SELECT id FROM calendar_events WHERE user_id = ? AND google_event_id = ?",
                Long.class, user, googleId);
    }

    private long suggestion(long user, String status, long relatedEvent) {
        String key = "cd" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        jdbc.update("INSERT INTO suggestions (user_id, type, status, target_date, start_at, end_at, title, "
                + "reason_code, calculation_version, registration_key, related_event_id, expires_at, created_at, "
                + "updated_at) VALUES (?, 'PRE_START', ?, '2026-11-17', '2026-11-17 05:00:00', "
                + "'2026-11-17 05:30:00', 'lifecycle-test', 'R', 'v', ?, ?, '2026-11-20 00:00:00', "
                + "'2026-11-12 00:00:00', '2026-11-12 00:00:00')", user, status, key, relatedEvent);
        return jdbc.queryForObject("SELECT id FROM suggestions WHERE registration_key = ?", Long.class, key);
    }

    private void insertCbi(long user) {
        jdbc.update("INSERT INTO cbi_assessments (user_id, round, questionnaire_version, personal_score, "
                + "work_score, high_risk, taken_at) VALUES (?, 'INITIAL', 'cbi-ko-2026.11.2', 50, 50, FALSE, "
                + "'2026-11-01 00:00:00')", user);
    }

    private int count(String table, long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
    }
}
