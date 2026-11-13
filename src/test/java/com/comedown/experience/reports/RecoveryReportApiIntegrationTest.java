package com.comedown.experience.reports;

import static com.comedown.support.TestLogin.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.LocalDate;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** {@code GET /api/reports/recovery}. 기준 시각 2026-11-12 07:30 서울. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class RecoveryReportApiIntegrationTest {

    private static final double[] BASE = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85, 3.75};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RecoveryCommands recoveryCommands;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser("2026-11-10 01:00:00"); // 서울 11-10 → 3일차
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'report-%'");
    }

    @Test
    void 기준선_확정_전이면_잠금과_적격일_수만_준다() throws Exception {
        seedDay(LocalDate.of(2026, 11, 10), 3.7, false);
        seedDay(LocalDate.of(2026, 11, 11), 3.8, false);
        recoveryCommands.recalculate(userId, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11));

        report("").andExpect(status().isOk())
                .andExpect(content().json("""
                        {"locked":true,"unavailableReason":null,"unlock":{"requiredDays":7,"validDays":2},
                         "graph":null,"hrvVsUsualPct":null,"latestQuality":null,"loadVsUsualPct":null,
                         "interpretation":null,"rangePosition":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 건강정보_동의가_없으면_잠금과_CONSENT_REQUIRED() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);

        report("").andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.unavailableReason").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.graph").value(Matchers.nullValue()));
    }

    @Test
    void 기준선_확정_후_그래프와_요약과_규칙_기반_해석을_준다() throws Exception {
        seedDay(LocalDate.of(2026, 11, 4), 3.10, true); // 교란일
        for (int i = 0; i < BASE.length; i++) {
            seedDay(LocalDate.of(2026, 11, 5).plusDays(i), BASE[i], false);
        }
        recoveryCommands.recalculate(userId, LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 11));

        report("?days=14").andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.unlock.validDays").value(7))
                .andExpect(jsonPath("$.graph.from").value("2026-10-30"))
                .andExpect(jsonPath("$.graph.to").value("2026-11-12"))
                .andExpect(jsonPath("$.graph.points.length()").value(8))
                .andExpect(jsonPath("$.graph.points[0].date").value("2026-11-04"))
                .andExpect(jsonPath("$.graph.points[0].excluded").value(true))
                .andExpect(jsonPath("$.graph.points[1].rmssdMs").value(36.6))
                .andExpect(jsonPath("$.graph.points[1].excluded").value(false))
                .andExpect(jsonPath("$.graph.points[1].qualityLevel").value("GOOD"))
                // 추세는 적격 값 3개 이상인 11-07부터
                .andExpect(jsonPath("$.graph.trend.length()").value(5))
                .andExpect(jsonPath("$.graph.trend[0].date").value("2026-11-07"))
                .andExpect(jsonPath("$.graph.trend[4].rmssdMs").value(42.5))
                // 정상 범위 띠는 기준선이 확정된 11-11부터
                .andExpect(jsonPath("$.graph.normalBand.length()").value(1))
                .andExpect(jsonPath("$.graph.normalBand[0].lowerMs").value(38.2))
                .andExpect(jsonPath("$.graph.normalRange.lowerMs").value(38.2))
                .andExpect(jsonPath("$.graph.normalRange.upperMs").value(47.4))
                .andExpect(jsonPath("$.graph.loadWeeks").isEmpty())
                .andExpect(jsonPath("$.hrvVsUsualPct").value(0.0))
                .andExpect(jsonPath("$.latestQuality").value("GOOD"))
                .andExpect(jsonPath("$.loadVsUsualPct").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.interpretation.text").value("최근 7일 회복이 평소 범위 안에 있어요. 평소와 비슷해요."))
                .andExpect(jsonPath("$.interpretation.aiGenerated").value(false))
                .andExpect(jsonPath("$.interpretation.fallback").value(true))
                .andExpect(jsonPath("$.rangePosition.available").value(false))
                .andExpect(jsonPath("$.rangePosition.availableFrom").value("2026-12-07"))
                .andExpect(jsonPath("$.rangePosition.recovery").value(Matchers.nullValue()));
    }

    @Test
    void 이십팔일차_이후_추세_값이_20개_이상이면_내_범위_위치를_공개한다() throws Exception {
        long user = insertUser("2026-10-01 01:00:00"); // 서울 10-01 → 오늘 43일차
        LocalDate first = LocalDate.of(2026, 10, 13);
        for (int i = 0; i < 30; i++) {
            seedDayFor(user, first.plusDays(i), 3.7 + 0.1 * Math.sin(i), false);
        }
        recoveryCommands.recalculate(user, first, LocalDate.of(2026, 11, 11));

        mvc.perform(get("/api/reports/recovery").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rangePosition.available").value(true))
                .andExpect(jsonPath("$.rangePosition.availableFrom").value("2026-10-28"))
                .andExpect(jsonPath("$.rangePosition.recovery.position").value(
                        Matchers.allOf(Matchers.greaterThanOrEqualTo(0), Matchers.lessThanOrEqualTo(100))))
                .andExpect(jsonPath("$.rangePosition.recovery.low").value(Matchers.lessThan(45.0)))
                .andExpect(jsonPath("$.rangePosition.recoveryUnavailableReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.rangePosition.load").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.rangePosition.loadUnavailableReason").value("INSUFFICIENT_DATA"));
    }

    @Test
    void days는_7에서_90이고_온보딩_전이면_403() throws Exception {
        report("?days=6").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("days"));
        report("?days=91").andExpect(status().isBadRequest());

        long onboarding = insertUser(null);
        mvc.perform(get("/api/reports/recovery").with(loginAs(onboarding)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    // ---------- 도우미 ----------

    private ResultActions report(String query) throws Exception {
        return mvc.perform(get("/api/reports/recovery" + query).with(loginAs(userId)));
    }

    private long insertUser(String onboardingCompletedUtc) {
        String sub = "report-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, ?, ?, '2026-09-01 00:00:00', '2026-09-01 00:00:00')",
                sub, onboardingCompletedUtc == null ? "LIFESTYLE" : "DONE", onboardingCompletedUtc);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-09-01', '2026-09-01 00:00:00')", id);
        return id;
    }

    private void seedDay(LocalDate date, double ln, boolean confounded) {
        seedDayFor(userId, date, ln, confounded);
    }

    /** 컨디션까지 끝난 날(GOOD 측정 1건). */
    private void seedDayFor(long user, LocalDate date, double ln, boolean confounded) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, ?, 1, 3, ?, FALSE, FALSE, ?, ?)",
                user, date, confounded, date + " 00:30:00", date + " 00:00:00");
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = ?",
                Long.class, user, date);
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, mean_hr_bpm, quality_level, algorithm_version, created_at) VALUES (?, ?, ?, 'CAMERA', "
                + "?, ?, 60.0, 'GOOD', 'hrv-cam-v1', '2026-11-12 00:00:00')",
                user, checkId, date.minusDays(1) + " 22:00:00", Math.exp(ln), ln);
    }
}
