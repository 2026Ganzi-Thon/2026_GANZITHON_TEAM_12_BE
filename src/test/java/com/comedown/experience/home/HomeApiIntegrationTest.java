package com.comedown.experience.home;

import static com.comedown.support.TestLogin.loginAs;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** {@code GET /api/home}. 기준 시각 2026-11-12 07:30 서울, 온보딩 2026-11-10 → 3일차. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class HomeApiIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RecoveryCommands recoveryCommands;

    @Autowired
    SuggestionCommands suggestions;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser("2026-11-10 01:00:00");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'home-%'");
    }

    @Test
    void 기록이_없으면_측정_전_상태와_빈_추천을_준다() throws Exception {
        home().andExpect(status().isOk())
                .andExpect(content().json("""
                        {"nickname":"아리","dayIndex":3,"phase":"BUILDING",
                         "todayCheck":{"status":"NOT_STARTED"},
                         "recovery":{"status":"BUILDING","changePct":null,
                                     "baseline":{"validDays":0,"required":7,"established":false},
                                     "conditionScore":null,"excludedToday":false},
                         "thisWeekLoad":null,"judgment":null,"todaySuggestions":[],
                         "safety":{"show":false},"plan":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 아침_체크_진행에_따라_오늘_체크_상태가_바뀐다() throws Exception {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, breathing_completed_at, created_at) "
                + "VALUES (?, '2026-11-12', 3, '2026-11-11 22:20:00', '2026-11-11 22:20:00')", userId);
        home().andExpect(jsonPath("$.todayCheck.status").value("IN_PROGRESS"));

        condition("{\"score\":4,\"alcohol\":true,\"sick\":false,\"caffeine\":false}");
        home().andExpect(jsonPath("$.todayCheck.status").value("DONE"))
                .andExpect(jsonPath("$.recovery.conditionScore").value(4))
                .andExpect(jsonPath("$.recovery.excludedToday").value(true));
    }

    @Test
    void 기준선이_확정되면_최근_회복_상태와_평소_대비를_보여준다() throws Exception {
        double[] ln = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85, 3.75};
        for (int i = 0; i < ln.length; i++) {
            seedEligibleDay(LocalDate.of(2026, 11, 5).plusDays(i), ln[i]);
        }
        recoveryCommands.recalculate(userId, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 11));

        home().andExpect(jsonPath("$.phase").value("ESTABLISHED"))
                .andExpect(jsonPath("$.todayCheck.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.recovery.status").value("NORMAL"))
                .andExpect(jsonPath("$.recovery.changePct").value(0.0))
                .andExpect(jsonPath("$.recovery.baseline.validDays").value(7))
                .andExpect(jsonPath("$.recovery.baseline.established").value(true))
                .andExpect(jsonPath("$.recovery.excludedToday").value(false));
    }

    @Test
    void 오늘의_추천은_오늘_날짜에서_건너뜀_만료_되돌림을_뺀다() throws Exception {
        SuggestionView recovery = propose("RECOVERY", TODAY, "2026-11-12T07:00:00Z");
        SuggestionView preStart = propose("PRE_START", TODAY, "2026-11-12T05:00:00Z");
        suggestions.dismiss(userId, propose("RECOVERY", TODAY, "2026-11-12T09:00:00Z").id());
        propose("RECOVERY", TODAY.plusDays(1), "2026-11-13T07:00:00Z");

        home().andExpect(jsonPath("$.todaySuggestions.length()").value(2))
                .andExpect(jsonPath("$.todaySuggestions[0].id").value(preStart.id()))
                .andExpect(jsonPath("$.todaySuggestions[1].id").value(recovery.id()))
                .andExpect(jsonPath("$.todaySuggestions[1].start").value("2026-11-12T16:00:00+09:00"));
    }

    @Test
    void 열린_안전_안내가_있으면_show() throws Exception {
        jdbc.update("INSERT INTO safety_alerts (user_id, trigger_type, triggered_at) VALUES (?, 'CBI_HIGH', "
                + "'2026-11-11 00:00:00')", userId);

        home().andExpect(jsonPath("$.safety.show").value(true));
    }

    @Test
    void 온보딩_전이면_403() throws Exception {
        long onboarding = insertUser(null);

        mvc.perform(get("/api/home").with(loginAs(onboarding)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    // ---------- 도우미 ----------

    private ResultActions home() throws Exception {
        return mvc.perform(get("/api/home").with(loginAs(userId)));
    }

    private void condition(String body) throws Exception {
        mvc.perform(put("/api/morning-checks/today/condition").with(loginAs(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private SuggestionView propose(String type, LocalDate date, String startUtc) {
        Instant start = Instant.parse(startUtc);
        return suggestions.propose(new NewSuggestion(userId, type, date, start, start.plusSeconds(20 * 60),
                "20분 걷기", "WALK", null, "LOW_RECOVERY", "recovery-v1", null, false, false, null, null,
                date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
    }

    private long insertUser(String onboardingCompletedUtc) {
        String sub = "home-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, nickname, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, '아리', ?, ?, '2026-11-01 00:00:00', '2026-11-01 00:00:00')",
                sub, onboardingCompletedUtc == null ? "LIFESTYLE" : "DONE", onboardingCompletedUtc);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-11-01', '2026-11-01 00:00:00')", id);
        return id;
    }

    private void seedEligibleDay(LocalDate date, double ln) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, ?, 1, 3, FALSE, FALSE, FALSE, ?, ?)",
                userId, date, date + " 00:30:00", date + " 00:00:00");
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = ?",
                Long.class, userId, date);
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, mean_hr_bpm, quality_level, algorithm_version, created_at) VALUES (?, ?, ?, 'CAMERA', "
                + "?, ?, 60.0, 'GOOD', 'hrv-cam-v1', '2026-11-12 00:00:00')",
                userId, checkId, date.minusDays(1) + " 22:00:00", Math.exp(ln), ln);
    }
}
