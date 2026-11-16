package com.comedown.experience.onboarding;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 온보딩(프로필 → CBI 건너뛰기 → 생활 정보 → 완료)과 내 정보·동의. 기준 시각 2026-11-12 07:30 서울. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class OnboardingFlowIntegrationTest {

    private static final String ALL_REQUIRED = """
            {"nickname":"아리","consentVersion":"2026-11-01","consents":
             {"TERMS":true,"PRIVACY":true,"AGE_14":true,"CALENDAR":true,"AI_NOTICE":true,"SENSITIVE_HRV":%s}}
            """;
    private static final String SETTINGS = """
            {"measureTime":"07:30","sleepStart":"23:00","sleepEnd":"07:00",
             "preferredActivities":["WALK","STRETCH"]}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'onb-%'");
    }

    @Test
    void 처음_로그인한_사용자의_내_정보는_프로필_단계다() throws Exception {
        long user = newUser();

        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStep").value("PROFILE"))
                .andExpect(jsonPath("$.dayIndex").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.plan").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.calendar.connected").value(false))
                .andExpect(jsonPath("$.consents.TERMS").value(false))
                .andExpect(jsonPath("$.hrvEnabled").value(false));
    }

    @Test
    void 건강정보에_동의하고_끝까지_온보딩하면_체험이_시작된다() throws Exception {
        long user = newUser();

        send(put("/api/onboarding/profile"), user, ALL_REQUIRED.formatted(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStep").value("CBI"))
                .andExpect(jsonPath("$.calendarAuthUrl").value("/oauth2/authorization/google-calendar"));
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.nickname").value("아리"))
                .andExpect(jsonPath("$.consents.SENSITIVE_HRV").value(true))
                .andExpect(jsonPath("$.hrvEnabled").value(true));

        // CBI 전에는 생활 정보를 저장할 수 없고, 동의한 사용자는 CBI를 건너뛸 수 없다.
        send(put("/api/me/settings"), user, SETTINGS).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_INVALID"));
        mvc.perform(post("/api/onboarding/cbi/skip").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // CBI 제출은 다른 흐름이므로 여기서는 단계를 직접 맞춘다.
        jdbc.update("UPDATE users SET onboarding_step = 'LIFESTYLE' WHERE id = ?", user);
        mvc.perform(post("/api/onboarding/complete").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ONBOARDING_STEP_INVALID"));

        send(put("/api/me/settings"), user, SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStep").value("SCHEDULE_REPORT"))
                .andExpect(jsonPath("$.measureTime").value("07:30"));
        mvc.perform(get("/api/me/settings").with(loginAs(user)))
                .andExpect(jsonPath("$.sleepStart").value("23:00"))
                .andExpect(jsonPath("$.preferredActivities.length()").value(2))
                .andExpect(jsonPath("$.hrvEnabled").value(true));

        // 완료는 캘린더 권한과 일정 분석 결과를 다시 확인한다.
        mvc.perform(post("/api/onboarding/complete").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALENDAR_NOT_CONNECTED"));
        connectCalendar(user);
        mvc.perform(post("/api/onboarding/complete").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ONBOARDING_STEP_INVALID"))
                .andExpect(jsonPath("$.message").value("일정 분석을 먼저 끝내 주세요."));
        assertThat(jdbc.queryForObject("SELECT onboarding_completed_at FROM users WHERE id = ?", Object.class, user))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscriptions WHERE user_id = ?", Integer.class, user))
                .isZero();
        analyzedThisWeek(user); // 일정이 0건이어도 동기화하면 이번 주 부하 행이 생긴다

        mvc.perform(post("/api/onboarding/complete").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingStep").value("DONE"))
                .andExpect(jsonPath("$.dayIndex").value(1));
        mvc.perform(post("/api/onboarding/complete").with(loginAs(user)).with(csrf())) // 재시도
                .andExpect(status().isOk()).andExpect(jsonPath("$.dayIndex").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscriptions WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);

        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.onboardingStep").value("DONE"))
                .andExpect(jsonPath("$.dayIndex").value(1))
                .andExpect(jsonPath("$.plan.plan").value("TRIAL"))
                .andExpect(jsonPath("$.plan.daysLeft").value(28))
                .andExpect(jsonPath("$.onboardingCompletedAt").value("2026-11-12T07:30:00+09:00"));
        // 체험 28일이 지나면 FREE로 본다.
        clock.set(java.time.OffsetDateTime.parse(FixedClockTestConfig.DEFAULT_NOW).plusDays(29).toInstant());
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.plan.plan").value("FREE")).andExpect(jsonPath("$.plan.daysLeft").value(0));
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        // 완료한 뒤에는 캘린더를 해제해도 온보딩을 되돌리지 않는다(다시 불러도 같은 결과).
        jdbc.update("DELETE FROM oauth_tokens WHERE user_id = ?", user);
        jdbc.update("DELETE FROM weekly_loads WHERE user_id = ?", user);
        mvc.perform(post("/api/onboarding/complete").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStep").value("DONE"))
                .andExpect(jsonPath("$.dayIndex").value(1));
        // 완료 후 생활 정보 수정은 단계를 바꾸지 않는다.
        send(put("/api/me/settings"), user, SETTINGS.replace("23:00", "00:30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStep").doesNotExist());
    }

    @Test
    void 건강정보_동의는_가입_때_필수이고_가입_뒤_철회하면_측정이_꺼지며_CBI를_건너뛸_수_있다() throws Exception {
        long user = newUser();

        send(put("/api/onboarding/profile"), user, ALL_REQUIRED.formatted(false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("consents.SENSITIVE_HRV"));
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.onboardingStep").value("PROFILE"))
                .andExpect(jsonPath("$.consents.TERMS").value(false)); // 거절되면 아무 동의도 기록하지 않는다

        send(put("/api/onboarding/profile"), user, ALL_REQUIRED.formatted(true)).andExpect(status().isOk());
        mvc.perform(delete("/api/me/consents/SENSITIVE_HRV").with(loginAs(user)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.consents.SENSITIVE_HRV").value(false))
                .andExpect(jsonPath("$.hrvEnabled").value(false));

        mvc.perform(post("/api/onboarding/cbi/skip").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboardingStep").value("LIFESTYLE"));

        // 나중에 다시 동의하면 측정이 켜진다.
        send(post("/api/me/consents"), user, "{\"type\":\"SENSITIVE_HRV\",\"version\":\"2026-11-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents[?(@.type=='SENSITIVE_HRV')].agreed").value(true));
        mvc.perform(get("/api/me").with(loginAs(user))).andExpect(jsonPath("$.hrvEnabled").value(true));
    }

    @Test
    void 프로필은_필수_동의와_닉네임을_검증한다() throws Exception {
        long user = newUser();

        send(put("/api/onboarding/profile"), user,
                "{\"nickname\":\"\",\"consentVersion\":\"v1\",\"consents\":{\"TERMS\":true}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='nickname')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='consents.PRIVACY')]").exists());
        mvc.perform(get("/api/me").with(loginAs(user))).andExpect(jsonPath("$.onboardingStep").value("PROFILE"));
    }

    @Test
    void 캘린더가_이미_연결됐으면_calendarAuthUrl이_없다() throws Exception {
        long user = newUser();
        jdbc.update("INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc, scopes, "
                + "updated_at) VALUES (?, 'google', ?, 'enc', 'openid https://www.googleapis.com/auth/calendar.events', "
                + "'2026-11-11 22:30:00.000000')", user, "onb-" + UUID.randomUUID());

        send(put("/api/onboarding/profile"), user, ALL_REQUIRED.formatted(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.calendarAuthUrl").doesNotExist());
        mvc.perform(get("/api/me").with(loginAs(user)))
                .andExpect(jsonPath("$.calendar.connected").value(true))
                .andExpect(jsonPath("$.calendar.scopeGranted").value(true));
    }

    @Test
    void 생활_정보_검증_실패() throws Exception {
        long user = newUser();
        jdbc.update("UPDATE users SET onboarding_step = 'LIFESTYLE' WHERE id = ?", user);

        send(put("/api/me/settings"), user, SETTINGS.replace("\"07:00\"", "\"23:00\""))
                .andExpect(status().isBadRequest());
        send(put("/api/me/settings"), user, SETTINGS.replace("\"WALK\",\"STRETCH\"", "\"WALK\",\"WALK\""))
                .andExpect(status().isBadRequest());
        send(put("/api/me/settings"), user, SETTINGS.replace("\"WALK\",\"STRETCH\"", "\"SWIM\""))
                .andExpect(status().isBadRequest());
        send(put("/api/me/settings"), user, SETTINGS.replace("\"WALK\",\"STRETCH\"", ""))
                .andExpect(status().isBadRequest());
        send(put("/api/me/settings"), user, SETTINGS.replace("07:30", "7:3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("measureTime"));
        mvc.perform(get("/api/me").with(loginAs(user))).andExpect(jsonPath("$.onboardingStep").value("LIFESTYLE"));
    }

    @Test
    void 닉네임_수정() throws Exception {
        long user = newUser();

        send(patch("/api/me"), user, "{\"nickname\":\"새이름\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("새이름"));
        send(patch("/api/me"), user, "{\"nickname\":\"" + "가".repeat(21) + "\"}")
                .andExpect(status().isBadRequest());
    }

    // ---------- 도우미 ----------

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            long user, String body) throws Exception {
        return mvc.perform(builder.with(loginAs(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void connectCalendar(long user) {
        jdbc.update("INSERT INTO oauth_tokens (user_id, provider, principal_name, access_token_enc, scopes, "
                + "updated_at) VALUES (?, 'google', ?, 'enc', 'openid https://www.googleapis.com/auth/calendar.events', "
                + "'2026-11-11 22:30:00.000000')", user, "onb-" + UUID.randomUUID());
    }

    /** 동기화가 저장하는 이번 주(2026-11-09 월요일 시작) 주간 부하 행. */
    private void analyzedThisWeek(long user) {
        jdbc.update("INSERT INTO weekly_loads (user_id, week_start, total_hours, load_score, deadline_count, "
                + "late_night_count, weekend_hours, rest_days, risk_level, calculation_version, computed_at) "
                + "VALUES (?, '2026-11-09', 0, 0, 0, 0, 0, 7, 'NONE', 'load-v1', '2026-11-11 22:30:00')", user);
    }

    private long newUser() {
        String sub = "onb-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'PROFILE', '2026-11-11 22:00:00', '2026-11-11 22:00:00')", sub);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }
}
