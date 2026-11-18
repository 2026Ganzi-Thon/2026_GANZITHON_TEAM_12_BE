package com.comedown.experience.accountlifecycle;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.account.application.GoogleAccountService;
import com.comedown.account.application.GoogleAccountService.TokenGrant;
import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventWriter;
import com.comedown.calendar.contract.CalendarEventWriter.DeleteResult;
import com.comedown.integration.google.GoogleOAuthClient;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 시연 모드(comedown.demo.reset-on-logout=true)의 로그아웃 초기화. 구글 일정 삭제·자격은 대체 응답이다. */
@SpringBootTest(properties = "comedown.demo.reset-on-logout=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class LogoutResetIntegrationTest {

    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar.events";
    private static final Instant NOW = Instant.parse("2026-11-11T22:30:00Z");
    private static final List<String> USER_TABLES = List.of("oauth_tokens", "user_consents", "user_settings",
            "subscriptions", "cbi_assessments", "calendar_events", "weekly_loads", "suggestions", "morning_checks",
            "api_idempotency_keys");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GoogleAccountService accounts;

    @MockitoBean
    CalendarEventWriter writer;

    @MockitoBean
    CalendarCredentialProvider credentials;

    /** 구글 권한 철회를 부르지 않는지 확인한다. */
    @MockitoBean
    GoogleOAuthClient google;

    @BeforeEach
    void setUp() {
        reset(writer, credentials, google);
        when(credentials.credentialFor(anyLong())).thenReturn(new CalendarCredential("access"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM suggestions WHERE title = 'reset-test'");
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'reset-%'");
    }

    @Test
    void 로그아웃하면_승인한_앱_일정을_지우고_사용자와_모든_데이터를_지우며_구글_철회는_하지_않는다() throws Exception {
        long user = onboardedUserWithData();
        long event = event(user, "g1");
        suggestion(user, "APPROVED", event, "cdapprovedevent00000000000000001");
        suggestion(user, "PROPOSED", event, null);
        when(writer.deleteAppEvent(any(), eq("cdapprovedevent00000000000000001"))).thenReturn(DeleteResult.DELETED);
        long other = onboardedUserWithData(); // 다른 사용자는 그대로다

        mvc.perform(post("/api/auth/logout").with(loginAs(user)).with(csrf())).andExpect(status().isNoContent());

        verify(writer).deleteAppEvent(any(), eq("cdapprovedevent00000000000000001"));
        verifyNoInteractions(google);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, user)).isZero();
        for (String table : USER_TABLES) {
            assertThat(count(table, user)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, other)).isOne();
        assertThat(count("oauth_tokens", other)).isOne();
        assertThat(count("cbi_assessments", other)).isOne();
    }

    @Test
    void 구글_일정_삭제가_실패해도_데이터_초기화는_끝나고_로그아웃은_204() throws Exception {
        long user = onboardedUserWithData();
        long event = event(user, "g1");
        suggestion(user, "APPROVED", event, "cdapprovedevent00000000000000002");
        when(writer.deleteAppEvent(any(), any())).thenThrow(new ExternalServiceException(Kind.FAILURE, "x"));

        mvc.perform(post("/api/auth/logout").with(loginAs(user)).with(csrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, user)).isZero();
        assertThat(count("suggestions", user)).isZero();
    }

    @Test
    void 승인한_제안이_없으면_구글을_부르지_않고_초기화한다() throws Exception {
        long user = onboardedUserWithData();

        mvc.perform(post("/api/auth/logout").with(loginAs(user)).with(csrf())).andExpect(status().isNoContent());

        verifyNoInteractions(writer, credentials, google);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, user)).isZero();
    }

    // ---------- 도우미 ----------

    /** 온보딩을 마치고 캘린더·동의·설정·구독·CBI·아침 체크(호흡만)·재시도 키가 있는 사용자. */
    private long onboardedUserWithData() {
        String sub = "reset-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-01 00:00:00', '2026-09-01 00:00:00', "
                + "'2026-09-01 00:00:00')", sub);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        accounts.saveCalendarTokens(id, sub, new TokenGrant("access-" + id, "refresh-" + id, NOW,
                NOW.plusSeconds(3500), Set.of("openid", CALENDAR)));
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, 'CALENDAR', "
                + "'2026-11-01', '2026-09-01 00:00:00')", id);
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, "
                + "'2026-11-01 00:00:00')", id);
        jdbc.update("INSERT INTO subscriptions (user_id, plan, trial_started_at, trial_ends_at, updated_at) VALUES "
                + "(?, 'TRIAL', '2026-11-01 00:00:00', '2026-11-29 00:00:00', '2026-11-01 00:00:00')", id);
        jdbc.update("INSERT INTO cbi_assessments (user_id, round, questionnaire_version, personal_score, "
                + "work_score, high_risk, taken_at) VALUES (?, 'INITIAL', 'cbi-ko-2026.11.2', 50, 50, FALSE, "
                + "'2026-11-01 00:00:00')", id);
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, breathing_completed_at, created_at) "
                + "VALUES (?, '2026-11-11', 1, '2026-11-11 00:00:00', '2026-11-11 00:00:00')", id);
        jdbc.update("INSERT INTO weekly_loads (user_id, week_start, total_hours, load_score, deadline_count, "
                + "late_night_count, weekend_hours, rest_days, risk_level, risk_reasons, calculation_version, "
                + "computed_at) VALUES (?, '2026-11-09', 1, 1, 0, 0, 0, 6, 'NONE', '[]', 'load-v1', "
                + "'2026-11-11 00:00:00')", id);
        return id;
    }

    private long event(long user, String googleId) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, category, synced_at) VALUES (?, ?, "
                + "'2026-11-18 05:00:00', '2026-11-18 06:00:00', FALSE, 'confirmed', 'opaque', FALSE, 'ASSIGNMENT', "
                + "'2026-11-11 22:30:00.000000')", user, googleId);
        return jdbc.queryForObject("SELECT id FROM calendar_events WHERE user_id = ? AND google_event_id = ?",
                Long.class, user, googleId);
    }

    private void suggestion(long user, String status, long relatedEvent, String googleEventId) {
        String key = googleEventId != null ? googleEventId
                : "cd" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        jdbc.update("INSERT INTO suggestions (user_id, type, status, target_date, start_at, end_at, title, "
                + "reason_code, calculation_version, registration_key, google_event_id, related_event_id, "
                + "expires_at, created_at, updated_at) VALUES (?, 'PRE_START', ?, '2026-11-17', "
                + "'2026-11-17 05:00:00', '2026-11-17 05:30:00', 'reset-test', 'R', 'v', ?, ?, ?, "
                + "'2026-11-20 00:00:00', '2026-11-12 00:00:00', '2026-11-12 00:00:00')",
                user, status, key, googleEventId, relatedEvent);
    }

    private int count(String table, long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
    }
}
