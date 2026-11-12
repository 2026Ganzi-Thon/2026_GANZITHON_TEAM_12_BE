package com.comedown.experience.morning;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.recovery.contract.RecoveryCommands;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import com.comedown.support.SyntheticPpg;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/morning-checks/today}. 기준 시각 2026-11-12(목) 07:30 서울, 온보딩 완료 2026-11-10 → 3일차.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class MorningTodayApiIntegrationTest {

    private static final String URL = "/api/morning-checks/today";
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
    RecoveryCommands recoveryCommands;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'today-%'");
    }

    @Test
    void 기록이_없으면_모든_단계가_미완료이고_체크를_만들지_않는다() throws Exception {
        today().andExpect(status().isOk())
                .andExpect(content().json("""
                        {"date":"2026-11-12","dayIndex":3,"phase":"BUILDING",
                         "steps":{"measurement":{"done":false,"measurementId":null,"qualityLevel":null},
                                  "condition":{"done":false},"breathing":{"done":false}},
                         "baseline":{"validDays":0,"required":7},
                         "result":null}
                        """, JsonCompareMode.STRICT));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM morning_checks WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    @Test
    void 측정만_했으면_측정_단계만_완료이고_결과는_없다() throws Exception {
        measureCamera(STARTED_AT, SyntheticPpg.clean(70_000, 30));
        long measurementId = latestMeasurementId();

        today().andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.measurement.done").value(true))
                .andExpect(jsonPath("$.steps.measurement.measurementId").value(measurementId))
                .andExpect(jsonPath("$.steps.measurement.qualityLevel").value("GOOD"))
                .andExpect(jsonPath("$.steps.condition.done").value(false))
                .andExpect(jsonPath("$.baseline.validDays").value(0))
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void 재측정이_필요한_측정만_있으면_미완료로_RETRY를_보여준다() throws Exception {
        measureCamera(STARTED_AT, SyntheticPpg.clean(40_000, 30));

        today().andExpect(jsonPath("$.steps.measurement.done").value(false))
                .andExpect(jsonPath("$.steps.measurement.measurementId").value(latestMeasurementId()))
                .andExpect(jsonPath("$.steps.measurement.qualityLevel").value("RETRY"));
    }

    @Test
    void 대표_측정이_없으면_수동_입력을_RETRY보다_먼저_보여준다() throws Exception {
        measure(Map.of("source", "MANUAL", "measuredAt", "2026-11-12T07:27:00+09:00", "rmssdMs", 42.5));
        long manualId = latestMeasurementId();
        measureCamera(STARTED_AT, SyntheticPpg.clean(40_000, 30));

        today().andExpect(jsonPath("$.steps.measurement.done").value(true))
                .andExpect(jsonPath("$.steps.measurement.measurementId").value(manualId))
                .andExpect(jsonPath("$.steps.measurement.qualityLevel").value("UNVERIFIED"));
    }

    @Test
    void 컨디션까지_끝났으면_저장_때와_같은_오늘_결과를_돌려준다() throws Exception {
        measureCamera(STARTED_AT, SyntheticPpg.clean(70_000, 30));
        String saved = mvc.perform(put("/api/morning-checks/today/condition").with(loginAs(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":4,\"alcohol\":false,\"sick\":false,\"caffeine\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String body = today().andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.condition.done").value(true))
                .andExpect(jsonPath("$.baseline.validDays").value(1))
                .andReturn().getResponse().getContentAsString();

        JsonNode result = jsonMapper.readTree(body).get("result");
        assertThat(result).isEqualTo(jsonMapper.readTree(saved));
    }

    @Test
    void 호흡을_마쳤으면_호흡_단계가_완료다() throws Exception {
        measureCamera(STARTED_AT, SyntheticPpg.clean(70_000, 30));
        jdbc.update("UPDATE morning_checks SET breathing_completed_at = '2026-11-11 22:35:00' WHERE user_id = ?",
                userId);

        today().andExpect(jsonPath("$.steps.breathing.done").value(true));
    }

    @Test
    void 기준선이_확정된_뒤_오늘_기록이_없어도_ESTABLISHED와_적격일_수를_보여준다() throws Exception {
        double[] ln = {3.60, 3.90, 3.70, 3.80, 3.65, 3.85, 3.75};
        for (int i = 0; i < ln.length; i++) {
            seedEligibleDay(LocalDate.of(2026, 11, 5).plusDays(i), ln[i]);
        }
        recoveryCommands.recalculate(userId, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 11));

        today().andExpect(jsonPath("$.phase").value("ESTABLISHED"))
                .andExpect(jsonPath("$.baseline.validDays").value(7))
                .andExpect(jsonPath("$.steps.measurement.done").value(false))
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void 다른_사용자의_기록은_보이지_않는다() throws Exception {
        long other = insertUser();
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, '2026-11-12', 3, 3, FALSE, FALSE, FALSE, "
                + "'2026-11-11 22:30:00', '2026-11-11 22:30:00')", other);

        today().andExpect(jsonPath("$.steps.condition.done").value(false))
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void 온보딩_전이면_403이고_로그인하지_않으면_401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE users SET onboarding_completed_at = NULL WHERE id = ?", userId);
        today().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    void 건강정보_동의가_없어도_조회는_된다() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);

        today().andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    private ResultActions today() throws Exception {
        return mvc.perform(get(URL).with(loginAs(userId)));
    }

    private void measureCamera(String startedAt, SyntheticPpg signal) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "CAMERA");
        body.put("startedAt", startedAt);
        body.put("torch", true);
        body.put("timeSource", "captureTime");
        body.put("t", Arrays.stream(signal.t).boxed().toList());
        body.put("r", Arrays.stream(signal.r).boxed().toList());
        body.put("g", Arrays.stream(signal.g).boxed().toList());
        measure(body);
    }

    private void measure(Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/hrv/measurements").with(loginAs(userId)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private long latestMeasurementId() {
        return jdbc.queryForObject("SELECT MAX(id) FROM hrv_measurements WHERE user_id = ?", Long.class, userId);
    }

    private long insertUser() {
        String sub = "today-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-10 01:00:00', '2026-11-10 00:00:00', "
                + "'2026-11-10 00:00:00')", sub);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, "
                + "'2026-11-10 00:00:00')", id);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-11-01', '2026-11-10 00:50:00')", id);
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
