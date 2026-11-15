package com.comedown.experience.morning;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import com.comedown.support.SyntheticPpg;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PUT /api/morning-checks/today/condition} → 컨디션 저장 · 회복 재계산(hrv_daily_states) · TodayResult.
 * 기준 시각: 2026-11-12(목) 07:30 서울. 온보딩 완료: 2026-11-10 서울 → 오늘은 3일차.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class MorningConditionApiIntegrationTest {

    private static final String URL = "/api/morning-checks/today/condition";
    private static final String NOW = "2026-11-12T07:30:00+09:00";
    private static final String STARTED_AT = "2026-11-12T07:28:50+09:00";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    JsonMapper jsonMapper;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        String sub = "condition-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-10 01:00:00', '2026-11-10 00:00:00', "
                + "'2026-11-10 00:00:00')", sub);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, "
                + "'2026-11-10 00:00:00')", userId);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-11-01', '2026-11-10 00:50:00')", userId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'condition-%'");
    }

    // ---------- 기준선 만드는 중 ----------

    @Test
    void 측정_없이_컨디션만_저장해도_결과를_돌려준다() throws Exception {
        condition(3, false, false, false)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"date":"2026-11-12","phase":"BUILDING",
                         "recovery":{"status":"BUILDING","changePct":null,"trendRmssdMs":null,"normalRange":null},
                         "qualityLevel":null,"heartRateBpm":null,"excludedFromBaseline":true,
                         "baseline":{"validDays":0,"required":7,"established":false,"frozen":false},
                         "conditionScore":3,"judgment":null,"suggestions":[]}
                        """, JsonCompareMode.STRICT));

        Map<String, Object> check = jdbc.queryForMap("SELECT day_index, condition_score, alcohol, completed_at "
                + "FROM morning_checks WHERE user_id = ?", userId);
        assertThat(check.get("day_index")).isEqualTo(3);
        assertThat(check.get("completed_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT measurement_count, recovery_status, calculation_version "
                + "FROM hrv_daily_states WHERE user_id = ? AND state_date = '2026-11-12'", userId))
                .containsEntry("measurement_count", 0)
                .containsEntry("recovery_status", "BUILDING")
                .containsEntry("calculation_version", "recovery-v1");
    }

    @Test
    void 측정_후_컨디션을_저장하면_오늘이_적격일로_세어진다() throws Exception {
        measure(STARTED_AT, SyntheticPpg.clean(70_000, 30));

        condition(4, false, false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("BUILDING"))
                .andExpect(jsonPath("$.qualityLevel").value("GOOD"))
                .andExpect(jsonPath("$.heartRateBpm").value(Matchers.closeTo(66.7, 3.0)))
                .andExpect(jsonPath("$.excludedFromBaseline").value(false))
                .andExpect(jsonPath("$.baseline.validDays").value(1))
                .andExpect(jsonPath("$.conditionScore").value(4));
    }

    @Test
    void 교란_요인이_있으면_오늘은_계산에서_빠진다() throws Exception {
        measure(STARTED_AT, SyntheticPpg.clean(70_000, 30));

        condition(3, false, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualityLevel").value("GOOD"))
                .andExpect(jsonPath("$.excludedFromBaseline").value(true))
                .andExpect(jsonPath("$.baseline.validDays").value(0));

        assertThat(jdbc.queryForObject("SELECT confounded FROM hrv_daily_states WHERE user_id = ?",
                Boolean.class, userId)).isTrue();
    }

    // ---------- 기준선 확정 ----------

    @Test
    void 적격_7일째에_기준선이_확정되고_평소_대비와_정상_범위를_돌려준다() throws Exception {
        double[] ln = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85};
        for (int i = 0; i < ln.length; i++) {
            seedEligibleDay("2026-11-0" + (6 + i), ln[i]);
        }
        seedMeasurementToday(3.75);

        condition(3, false, false, false)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"date":"2026-11-12","phase":"ESTABLISHED",
                         "recovery":{"status":"NORMAL","changePct":0.0,"trendRmssdMs":42.5,
                                     "normalRange":{"lowerMs":38.2,"upperMs":47.4}},
                         "qualityLevel":"GOOD","heartRateBpm":60.0,"excludedFromBaseline":false,
                         "baseline":{"validDays":7,"required":7,"established":true,"frozen":false},
                         "conditionScore":3,"judgment":null,"suggestions":[]}
                        """, JsonCompareMode.STRICT));
    }

    // ---------- 변경·재계산 ----------

    @Test
    void 컨디션을_다시_저장하면_값과_결과가_바뀌고_완료_시각은_처음_그대로다() throws Exception {
        measure(STARTED_AT, SyntheticPpg.clean(70_000, 30));
        condition(4, false, false, false).andExpect(jsonPath("$.baseline.validDays").value(1));
        Object firstCompleted = jdbc.queryForObject(
                "SELECT completed_at FROM morning_checks WHERE user_id = ?", Object.class, userId);

        clock.advance(Duration.ofMinutes(3));
        condition(2, true, false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conditionScore").value(2))
                .andExpect(jsonPath("$.excludedFromBaseline").value(true))
                .andExpect(jsonPath("$.baseline.validDays").value(0));

        assertThat(jdbc.queryForObject("SELECT completed_at FROM morning_checks WHERE user_id = ?", Object.class,
                userId)).isEqualTo(firstCompleted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hrv_daily_states WHERE user_id = ?", Integer.class,
                userId)).isEqualTo(1);
    }

    @Test
    void 컨디션_저장_뒤_재측정하면_그날_회복_상태를_다시_계산한다() throws Exception {
        measure(STARTED_AT, SyntheticPpg.clean(40_000, 30)); // RETRY
        condition(3, false, false, false)
                .andExpect(jsonPath("$.qualityLevel").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.excludedFromBaseline").value(true))
                .andExpect(jsonPath("$.baseline.validDays").value(0));

        clock.set("2026-11-12T07:40:00+09:00");
        measure("2026-11-12T07:38:50+09:00", SyntheticPpg.clean(70_000, 30));

        Map<String, Object> state = jdbc.queryForMap("SELECT daily_ln_rmssd, measurement_count, quality_level, "
                + "baseline_valid_days FROM hrv_daily_states WHERE user_id = ?", userId);
        assertThat(state.get("daily_ln_rmssd")).isNotNull();
        assertThat(state).containsEntry("measurement_count", 1)
                .containsEntry("quality_level", "GOOD")
                .containsEntry("baseline_valid_days", 1);
    }

    @Test
    void 컨디션_저장_전_측정도_그날_회복_상태_행을_만든다() throws Exception {
        measure(STARTED_AT, SyntheticPpg.clean(70_000, 30));

        assertThat(jdbc.queryForMap("SELECT measurement_count, quality_level, baseline_valid_days, recovery_status "
                + "FROM hrv_daily_states WHERE user_id = ?", userId))
                .containsEntry("measurement_count", 1)
                .containsEntry("quality_level", "GOOD")
                // 컨디션 입력 전은 적격일이 아니다
                .containsEntry("baseline_valid_days", 0)
                .containsEntry("recovery_status", "BUILDING");
    }

    // ---------- 접근·검증 ----------

    @Test
    void 건강정보_동의가_없으면_403() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);

        condition(3, false, false, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HRV_CONSENT_REQUIRED"));
    }

    @Test
    void 온보딩_전이면_403() throws Exception {
        jdbc.update("UPDATE users SET onboarding_completed_at = NULL WHERE id = ?", userId);

        condition(3, false, false, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    void 측정이_꺼져_있어도_컨디션은_저장할_수_있다() throws Exception {
        jdbc.update("UPDATE user_settings SET hrv_enabled = FALSE WHERE user_id = ?", userId);

        condition(3, false, false, false).andExpect(status().isOk());
    }

    @Test
    void 컨디션은_1에서_5이고_교란_요인은_모두_필수() throws Exception {
        send("{\"score\":6,\"alcohol\":false,\"sick\":false,\"caffeine\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("score"));
        send("{\"score\":3,\"sick\":false,\"caffeine\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("alcohol"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM morning_checks WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    @Test
    void 접근_조건은_요청_검증보다_먼저_본다() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);

        send("{\"score\":9}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HRV_CONSENT_REQUIRED"));
    }

    // ---------- 도우미 ----------

    private ResultActions condition(int score, boolean alcohol, boolean sick, boolean caffeine) throws Exception {
        return send(jsonMapper.writeValueAsString(
                Map.of("score", score, "alcohol", alcohol, "sick", sick, "caffeine", caffeine)));
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(put(URL).with(loginAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void measure(String startedAt, SyntheticPpg signal) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "CAMERA");
        body.put("startedAt", startedAt);
        body.put("torch", true);
        body.put("timeSource", "captureTime");
        body.put("t", Arrays.stream(signal.t).boxed().toList());
        body.put("r", Arrays.stream(signal.r).boxed().toList());
        body.put("g", Arrays.stream(signal.g).boxed().toList());
        mvc.perform(post("/api/hrv/measurements").with(loginAs(userId)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    /** 컨디션까지 끝난 과거 적격일(GOOD 측정 1건). 시각은 그날 서울 07:00. */
    private void seedEligibleDay(String date, double ln) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, ?, 1, 3, FALSE, FALSE, FALSE, "
                + "TIMESTAMP(?, '00:00:00'), TIMESTAMP(?, '00:00:00'))", userId, date, date, date);
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = ?",
                Long.class, userId, date);
        insertMeasurement(checkId, "TIMESTAMP(DATE_SUB('" + date + "', INTERVAL 1 DAY), '22:00:00')", ln);
    }

    /** 오늘(컨디션 입력 전) 체크와 GOOD 측정 1건. */
    private void seedMeasurementToday(double ln) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, created_at) "
                + "VALUES (?, '2026-11-12', 3, '2026-11-11 22:29:00')", userId);
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = "
                + "'2026-11-12'", Long.class, userId);
        insertMeasurement(checkId, "'2026-11-11 22:28:50'", ln);
    }

    private void insertMeasurement(long checkId, String measuredAtSql, double ln) {
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, mean_hr_bpm, quality_level, algorithm_version, created_at) VALUES (?, ?, "
                + measuredAtSql + ", 'CAMERA', ?, ?, 60.0, 'GOOD', 'hrv-cam-v1', '2026-11-12 00:00:00')",
                userId, checkId, Math.exp(ln), ln);
    }
}
