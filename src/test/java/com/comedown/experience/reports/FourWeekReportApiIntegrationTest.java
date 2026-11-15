package com.comedown.experience.reports;

import static com.comedown.support.TestLogin.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionView;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** {@code GET /api/reports/four-week}. 기준 시각 2026-11-12 07:30 서울. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class FourWeekReportApiIntegrationTest {

    /** 서울 10-16 = 1일차 → 오늘(11-12) 28일차. */
    private static final String DAY1_UTC = "2026-10-16 01:00:00";
    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 16);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SuggestionCommands suggestions;

    @Autowired
    SuggestionApprovalCommands approvals;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'fourweek-%'");
    }

    @Test
    void 이십팔일차_전이면_공개_날짜만_준다() throws Exception {
        long user = insertUser("2026-11-10 01:00:00");

        report(user).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"available":false,"cbiDue":false,"availableFrom":"2026-12-07","unavailableReason":null,
                         "cbi":null,"recovery":null,"load":null,"suggestions":null,"interpretation":null,"plan":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 이십팔일차인데_DAY28_CBI를_안_했으면_cbiDue() throws Exception {
        long user = insertUser(DAY1_UTC);

        report(user).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.cbiDue").value(true))
                .andExpect(jsonPath("$.availableFrom").value("2026-11-12"));
    }

    @Test
    void 건강정보_동의가_없으면_CONSENT_REQUIRED() throws Exception {
        long user = insertUser(DAY1_UTC);
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-01 00:00:00' WHERE user_id = ?", user);

        report(user).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.cbiDue").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("CONSENT_REQUIRED"));
    }

    @Test
    void DAY28_CBI까지_끝나면_4주_비교를_준다() throws Exception {
        long user = insertUser(DAY1_UTC);
        insertCbi(user, "INITIAL", "62.50", "53.57");
        insertCbi(user, "DAY28", "50.00", "46.43");
        for (int i = 0; i < 7; i++) {
            insertState(user, DAY1.plusDays(i), 3.6, false);          // 1~7일차
            insertState(user, DAY1.plusDays(21 + i), 3.7, false);     // 22~28일차
        }
        insertState(user, DAY1.plusDays(25), 3.0, true);               // 26일차를 교란일로 바꿈 → 평균에서 빠짐
        SuggestionView done = approve(user, propose(user, DAY1.plusDays(3)));
        approve(user, propose(user, DAY1.plusDays(10)));
        suggestions.dismiss(user, propose(user, DAY1.plusDays(12)).id());
        approve(user, propose(user, DAY1.plusDays(28)));                // 기간 밖
        jdbc.update("UPDATE suggestions SET done_at = '2026-10-19 08:00:00' WHERE id = ?", done.id());

        report(user).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"available":true,"cbiDue":false,"availableFrom":"2026-11-12","unavailableReason":null,
                         "cbi":{"initial":{"personal":62.5,"work":53.6},"day28":{"personal":50.0,"work":46.4},
                                "diff":{"personal":-12.5,"work":-7.1}},
                         "recovery":{"firstWeekAvgMs":36.6,"lastWeekAvgMs":40.4,"changePct":10.5},
                         "load":null,
                         "suggestions":{"approved":2,"done":1},
                         "interpretation":{"text":"몸과 마음의 피로가 12.5점 낮아졌어요. 일·공부로 인한 피로가 7.1점 낮아졌어요. 첫 주보다 마지막 주 회복이 10.5% 높아졌어요.",
                                           "aiGenerated":false,"fallback":true},
                         "plan":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 온보딩_전이면_403() throws Exception {
        long user = insertUser(null);

        report(user).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    // ---------- 도우미 ----------

    private ResultActions report(long user) throws Exception {
        return mvc.perform(get("/api/reports/four-week").with(loginAs(user)));
    }

    private long insertUser(String onboardingCompletedUtc) {
        String sub = "fourweek-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, ?, ?, '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
                sub, onboardingCompletedUtc == null ? "LIFESTYLE" : "DONE", onboardingCompletedUtc);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-10-01', '2026-10-01 00:00:00')", id);
        return id;
    }

    private void insertCbi(long user, String round, String personal, String work) {
        jdbc.update("INSERT INTO cbi_assessments (user_id, round, questionnaire_version, personal_score, work_score, "
                + "high_risk, taken_at) VALUES (?, ?, 'cbi-ko-2026.11.2', ?, ?, FALSE, '2026-10-16 01:00:00')",
                user, round, new java.math.BigDecimal(personal), new java.math.BigDecimal(work));
    }

    private void insertState(long user, LocalDate date, double ln, boolean confounded) {
        jdbc.update("INSERT INTO hrv_daily_states (user_id, state_date, daily_ln_rmssd, daily_rmssd_ms, "
                + "measurement_count, quality_level, confounded, baseline_established, baseline_valid_days, "
                + "baseline_sample_count, baseline_frozen, recovery_status, calculation_version, computed_at) "
                + "VALUES (?, ?, ?, ?, 1, 'GOOD', ?, FALSE, 1, 1, FALSE, 'BUILDING', 'recovery-v1', "
                + "'2026-11-12 00:00:00') ON DUPLICATE KEY UPDATE daily_ln_rmssd = VALUES(daily_ln_rmssd), "
                + "daily_rmssd_ms = VALUES(daily_rmssd_ms), confounded = VALUES(confounded)",
                user, date, ln, Math.exp(ln), confounded);
    }

    private SuggestionView propose(long user, LocalDate date) {
        Instant start = date.atTime(9, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return suggestions.propose(new NewSuggestion(user, "RECOVERY", date, start, start.plusSeconds(1200),
                "20분 걷기", "WALK", null, "LOW_RECOVERY", "recovery-v1", null, false, false, null, null,
                date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
    }

    private SuggestionView approve(long user, SuggestionView s) {
        approvals.beginApproval(user, s.id(), null, null);
        return approvals.confirmApproved(user, s.id(), s.registrationKey());
    }
}
