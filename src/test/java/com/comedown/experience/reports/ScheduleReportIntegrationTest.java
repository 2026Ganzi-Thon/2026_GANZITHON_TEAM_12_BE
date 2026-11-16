package com.comedown.experience.reports;

import static com.comedown.support.TestLogin.loginAs;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventSource;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.LocalDate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 일정 리포트 조합(기준 시각 2026-11-12 목 07:30 서울, 지난 한 달 = 10-12 ~ 11-11). 구글은 대체 응답. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class ScheduleReportIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    WeekLoads weekLoads;

    @MockitoBean
    CalendarEventSource source;

    @MockitoBean
    CalendarCredentialProvider credentials;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        when(credentials.credentialFor(anyLong())).thenReturn(new CalendarCredential("token"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'report-%'");
    }

    @Test
    void 캘린더가_연결되지_않았으면_409() throws Exception {
        long user = newUser(false);

        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CALENDAR_NOT_CONNECTED"));
    }

    @Test
    void 일정이_있으면_지난_한_달_요약과_이번_주_다음_주들을_준다() throws Exception {
        long user = newUser(true);
        // 화 11-10 14:00~16:00(수업), 토 11-07 21:00~23:00(마감, 늦은 밤·주말)
        event(user, "r1", "2026-11-10 05:00:00", "2026-11-10 07:00:00", "CLASS", false);
        event(user, "r2", "2026-11-07 12:00:00", "2026-11-07 14:00:00", "ASSIGNMENT", true);
        event(user, "r3", "2026-11-18 05:00:00", "2026-11-18 06:00:00", "CLASS", false);
        weekLoads.recalculate(user, List.of(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 23)));

        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncedAt").value("2026-11-12T07:30:00+09:00"))
                .andExpect(jsonPath("$.classification.total").value(3))
                .andExpect(jsonPath("$.classification.classified").value(3))
                .andExpect(jsonPath("$.classification.pending").value(0))
                .andExpect(jsonPath("$.pastMonth.from").value("2026-10-12"))
                .andExpect(jsonPath("$.pastMonth.to").value("2026-11-11"))
                .andExpect(jsonPath("$.pastMonth.totalHours").value(4.0))
                .andExpect(jsonPath("$.pastMonth.weekendHours").value(2.0))
                .andExpect(jsonPath("$.pastMonth.lateNightCount").value(1))
                .andExpect(jsonPath("$.pastMonth.deadlineCount").value(1))
                .andExpect(jsonPath("$.pastMonth.restDays").value(29))
                .andExpect(jsonPath("$.thisWeek.weekStart").value("2026-11-09"))
                .andExpect(jsonPath("$.upcomingWeeks.length()").value(2))
                .andExpect(jsonPath("$.upcomingWeeks[0].weekStart").value("2026-11-16"))
                .andExpect(jsonPath("$.enoughData").value(true));
    }

    @Test
    void 일정_0건으로_동기화한_사용자도_syncedAt과_주간_부하가_함께_나온다() throws Exception {
        long user = newUser(true);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, 'CALENDAR', "
                + "'2026-11-01', '2026-09-01 00:00:00')", user);
        when(source.fetch(any(), any())).thenReturn(List.of());
        clock.set("2026-11-12T08:00:00+09:00");
        mvc.perform(post("/api/calendar/sync").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(0));
        clock.set("2026-11-12T08:05:00+09:00");

        // 일정 행이 하나도 없어도 동기화가 남긴 주간 부하의 계산 시각으로 syncedAt을 채운다.
        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncedAt").value("2026-11-12T08:00:00+09:00"))
                .andExpect(jsonPath("$.classification.total").value(0))
                .andExpect(jsonPath("$.thisWeek.weekStart").value("2026-11-09"))
                .andExpect(jsonPath("$.thisWeek.vsUsualPct").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.thisWeek.riskLevel").value("NONE"))
                .andExpect(jsonPath("$.upcomingWeeks.length()").value(4))
                .andExpect(jsonPath("$.upcomingWeeks[0].weekStart").value("2026-11-16"))
                .andExpect(jsonPath("$.upcomingWeeks[3].weekStart").value("2026-12-07"))
                .andExpect(jsonPath("$.enoughData").value(false));
    }

    @Test
    void syncedAt은_일정_동기화_시각과_주간_부하_계산_시각_중_늦은_쪽이다() throws Exception {
        long user = newUser(true);
        // 일정은 07:30에 동기화된 것만 남아 있고, 주간 부하는 08:00에 다시 계산됐다.
        event(user, "s1", "2026-11-10 05:00:00", "2026-11-10 07:00:00", "CLASS", false);
        clock.set("2026-11-12T08:00:00+09:00");
        weekLoads.recalculate(user, List.of(LocalDate.of(2026, 11, 9)));

        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncedAt").value("2026-11-12T08:00:00+09:00"));

        // 그 뒤 08:10에 동기화된 일정이 있으면 그 시각이 더 늦다.
        jdbc.update("UPDATE calendar_events SET synced_at = '2026-11-11 23:10:00.000000' WHERE user_id = ?", user);

        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncedAt").value("2026-11-12T08:10:00+09:00"));
    }

    @Test
    void syncedAt은_응답에_싣지_않는_지난_주의_계산_시각은_보지_않는다() throws Exception {
        long user = newUser(true);
        weekLoads.recalculate(user, List.of(LocalDate.of(2026, 11, 2)));

        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.thisWeek").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.upcomingWeeks.length()").value(0));
    }

    @Test
    void 일정이_없으면_enoughData는_false이고_주간_부하는_비어_있다() throws Exception {
        // 한 번도 동기화하지 않은 사용자(동기화 전): syncedAt·thisWeek는 null, upcomingWeeks는 빈 배열.
        long user = newUser(true);

        mvc.perform(get("/api/schedule/report").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.classification.total").value(0))
                .andExpect(jsonPath("$.thisWeek").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.upcomingWeeks.length()").value(0))
                .andExpect(jsonPath("$.pastMonth.restDays").value(31))
                .andExpect(jsonPath("$.enoughData").value(false));
    }

    private long newUser(boolean connected) {
        String sub = "report-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'SCHEDULE_REPORT', '2026-09-01 00:00:00', '2026-09-01 00:00:00')", sub);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        if (connected) {
            jdbc.update("INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc, scopes, "
                    + "updated_at) VALUES (?, 'google', ?, 'enc', 'openid https://www.googleapis.com/auth/calendar.events', "
                    + "'2026-11-11 22:30:00.000000')", id, sub);
        }
        return id;
    }

    private void event(long user, String googleId, String startUtc, String endUtc, String category,
            boolean deadline) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, category, is_deadline, synced_at) "
                + "VALUES (?, ?, ?, ?, FALSE, 'confirmed', 'opaque', FALSE, ?, ?, '2026-11-11 22:30:00.000000')",
                user, googleId, startUtc, endUtc, category, deadline);
    }
}
