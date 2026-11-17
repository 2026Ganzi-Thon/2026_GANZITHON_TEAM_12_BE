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

    @Autowired
    com.comedown.recovery.contract.RecoveryCommands recoveryCommands;

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
                         "qualityLevel":null,"rmssdMs":null,"heartRateBpm":null,"excludedFromBaseline":true,
                         "baseline":{"validDays":0,"required":7,"established":false,"frozen":false},
                         "conditionScore":3,"judgment":"BUILDING","interpretation":null,"suggestions":[]}
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
                // 기준선 만드는 중에도 오늘 HRV 값은 표시용으로 준다(B6-1)
                .andExpect(jsonPath("$.rmssdMs").isNumber())
                .andExpect(jsonPath("$.heartRateBpm").value(Matchers.closeTo(66.7, 3.0)))
                .andExpect(jsonPath("$.interpretation").value(Matchers.nullValue()))
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
                         "qualityLevel":"GOOD","rmssdMs":42.5,"heartRateBpm":60.0,"excludedFromBaseline":false,
                         "baseline":{"validDays":7,"required":7,"established":true,"frozen":false},
                         "conditionScore":3,"judgment":"BUILDING",
                         "interpretation":{"text":"최근 7일 회복이 평소 범위 안에 있어요. 평소와 비슷해요.",
                                           "aiGenerated":false,"fallback":true},
                         "suggestions":[]}
                        """, JsonCompareMode.STRICT));
    }

    // ---------- 회복 추천·2축 판단 ----------

    @Test
    void 둘째_주부터_오늘_측정값이_지난주_평균보다_낮으면_활동을_오늘_빈_시간에_하나_추천한다() throws Exception {
        secondWeekWithPriorAverage(3.80, 6);
        seedMeasurementToday(3.60);
        preferredActivities("WALK");

        generateRecovery().andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(0)); // 컨디션 입력 전에는 만들지 않는다

        condition(3, false, false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgment").value("NORMAL_LOW"))
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].type").value("RECOVERY"))
                .andExpect(jsonPath("$.suggestions[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$.suggestions[0].targetDate").value("2026-11-12"))
                .andExpect(jsonPath("$.suggestions[0].start").value("2026-11-12T07:30:00+09:00"))
                .andExpect(jsonPath("$.suggestions[0].durationMin").value(20))
                .andExpect(jsonPath("$.suggestions[0].title").value("20분 걷기"))
                .andExpect(jsonPath("$.suggestions[0].activityCode").value("WALK"))
                .andExpect(jsonPath("$.suggestions[0].reasonCode").value("LOW_RECOVERY"))
                .andExpect(jsonPath("$.suggestions[0].judgmentState").value("NORMAL_LOW"))
                .andExpect(jsonPath("$.suggestions[0].message.text").value(
                        "오늘 측정값이 지난주 평균보다 많이 낮아요. 20분 걷기 어때요? 숨이 살짝 찰 정도로 편하게 걸어 보세요."))
                .andExpect(jsonPath("$.suggestions[0].message.aiGenerated").value(false))
                .andExpect(jsonPath("$.suggestions[0].message.fallback").value(true))
                .andExpect(jsonPath("$.suggestions[0].relatedEvent").value(Matchers.nullValue()));

        // 다시 저장하거나 다시 만들어도 하루 하나다.
        condition(2, false, false, false).andExpect(jsonPath("$.suggestions.length()").value(1));
        generateRecovery().andExpect(jsonPath("$.suggestions.length()").value(1));
        assertThat(suggestionCount()).isEqualTo(1);

        // 건너뛴 추천은 결과에서 빠지고, 같은 날 다시 만들지 않는다.
        long id = jdbc.queryForObject("SELECT id FROM suggestions WHERE user_id = ?", Long.class, userId);
        mvc.perform(post("/api/suggestions/" + id + "/dismiss").with(loginAs(userId)).with(csrf()))
                .andExpect(status().isOk());
        condition(2, false, false, false).andExpect(jsonPath("$.suggestions.length()").value(0));
        assertThat(suggestionCount()).isEqualTo(1);
    }

    @Test
    void 지난주_평균보다_낮아도_10퍼센트_미만이면_추천하지_않는다() throws Exception {
        secondWeekWithPriorAverage(3.80, 6);
        seedMeasurementToday(3.75); // 지난주 평균보다 약 5% 낮음
        preferredActivities("WALK");

        condition(3, false, false, false)
                .andExpect(jsonPath("$.judgment").value("OK"))
                .andExpect(jsonPath("$.suggestions.length()").value(0));
        assertThat(suggestionCount()).isZero();
    }

    @Test
    void 이번_주_일정도_평소보다_많으면_OVERLOAD_LOW이고_문구가_달라진다() throws Exception {
        secondWeekWithPriorAverage(3.80, 6);
        seedMeasurementToday(3.60);
        preferredActivities("MEDITATION");
        overloadedThisWeek();

        condition(3, false, false, false)
                .andExpect(jsonPath("$.judgment").value("OVERLOAD_LOW"))
                .andExpect(jsonPath("$.suggestions[0].judgmentState").value("OVERLOAD_LOW"))
                .andExpect(jsonPath("$.suggestions[0].reasonCode").value("LOW_RECOVERY"))
                .andExpect(jsonPath("$.suggestions[0].title").value("20분 명상"))
                .andExpect(jsonPath("$.suggestions[0].message.text").value(
                        "이번 주 일정이 평소보다 많고, 오늘 측정값이 지난주 평균보다 많이 낮아요. 20분 명상 어때요? "
                                + "편하게 앉아 천천히 숨을 고르며 쉬어 보세요."));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/home")
                        .with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgment").value("OVERLOAD_LOW"))
                .andExpect(jsonPath("$.todaySuggestions.length()").value(1));
    }

    @Test
    void 선호_활동이_없어도_운동_목록에서_골라_추천한다() throws Exception {
        secondWeekWithPriorAverage(3.80, 6);
        seedMeasurementToday(3.60);

        condition(3, false, false, false)
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].activityCode")
                        .value(Matchers.in(java.util.List.of("WALK", "STRETCH", "YOGA", "MEDITATION"))));
    }

    @Test
    void 지난주_측정이_3일_미만이거나_오늘_교란_요인이_있으면_판단하지_않고_추천하지_않는다() throws Exception {
        secondWeekWithPriorAverage(3.80, 2);
        seedMeasurementToday(3.60);
        preferredActivities("WALK");
        overloadedThisWeek();

        condition(3, false, false, false)
                .andExpect(jsonPath("$.judgment").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.suggestions.length()").value(0));

        seedEligibleDay("2026-11-08", 3.80); // 지난주 측정 3일째
        recoveryCommands.recalculate(userId, java.time.LocalDate.of(2026, 11, 5), java.time.LocalDate.of(2026, 11, 11));
        condition(3, true, false, false) // 음주: 오늘 값은 판단에 쓰지 않는다
                .andExpect(jsonPath("$.judgment").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.suggestions.length()").value(0));
        condition(3, false, false, false)
                .andExpect(jsonPath("$.judgment").value("OVERLOAD_LOW"))
                .andExpect(jsonPath("$.suggestions.length()").value(1));
    }

    @Test
    void 첫_주에는_회복을_판단하지_않고_이번_주_일정이_많을_때만_가볍게_움직이기를_추천한다() throws Exception {
        preferredActivities("WALK");
        measure(STARTED_AT, SyntheticPpg.clean(70_000, 30));

        condition(1, false, false, false)
                .andExpect(jsonPath("$.phase").value("BUILDING"))
                .andExpect(jsonPath("$.judgment").value("BUILDING"))
                .andExpect(jsonPath("$.suggestions.length()").value(0)); // 컨디션이 낮아도 일정이 많지 않으면 없음
        assertThat(suggestionCount()).isZero();

        overloadedThisWeek();
        condition(1, false, false, false)
                .andExpect(jsonPath("$.judgment").value("BUILDING"))
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].reasonCode").value("OVERLOAD"))
                .andExpect(jsonPath("$.suggestions[0].judgmentState").value("BUILDING"))
                .andExpect(jsonPath("$.suggestions[0].title").value("20분 걷기"))
                .andExpect(jsonPath("$.suggestions[0].message.text").value(
                        "이번 주 일정이 평소보다 많아요. 가볍게 움직여 볼까요? 20분 걷기 어때요? "
                                + "숨이 살짝 찰 정도로 편하게 걸어 보세요."));
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

    /**
     * 오늘(11-12)이 8일차(2주차 첫날)인 사용자. 지난주(11-05~11-11) 중 뒤에서부터 {@code days}일에 적격 측정을 넣고
     * 그 날들의 회복 상태를 계산해 둔다(지난주 평균 = {@code ln}).
     */
    private void secondWeekWithPriorAverage(double ln, int days) {
        jdbc.update("UPDATE users SET onboarding_completed_at = '2026-11-04 15:30:00' WHERE id = ?", userId);
        for (int day = 11; day > 11 - days; day--) {
            seedEligibleDay("2026-11-%02d".formatted(day), ln);
        }
        recoveryCommands.recalculate(userId, java.time.LocalDate.of(2026, 11, 5), java.time.LocalDate.of(2026, 11, 11));
    }

    /** 이번 주(11-09~)가 평소보다 일정이 많은 주. */
    private void overloadedThisWeek() {
        jdbc.update("INSERT INTO weekly_loads (user_id, week_start, total_hours, load_score, deadline_count, "
                + "late_night_count, weekend_hours, rest_days, risk_level, risk_reasons, calculation_version, "
                + "computed_at) VALUES (?, '2026-11-09', 40, 52, 0, 0, 0, 2, 'WATCH', '[\"LOAD_HIGH\"]', 'load-v1', "
                + "'2026-11-11 00:00:00')", userId);
    }

    private void preferredActivities(String... codes) {
        for (String code : codes) {
            jdbc.update("INSERT INTO user_preferred_activities (user_id, activity_code) VALUES (?, ?)", userId, code);
        }
    }

    private ResultActions generateRecovery() throws Exception {
        return mvc.perform(post("/api/suggestions/generate").with(loginAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RECOVERY\"}"));
    }

    private int suggestionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM suggestions WHERE user_id = ?", Integer.class, userId);
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
