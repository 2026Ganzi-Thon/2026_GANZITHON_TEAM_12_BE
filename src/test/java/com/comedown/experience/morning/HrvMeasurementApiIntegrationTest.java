package com.comedown.experience.morning;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import com.comedown.support.SyntheticPpg;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/hrv/measurements} 전체 흐름(보안 → 접근 조건 → 검증 → 재시도 키 → checkin 저장)을 MySQL 8.4 위에서 검증한다.
 * 기준 시각: 2026-11-12 07:30 서울. 온보딩 완료: 2026-11-10 서울 → 오늘은 3일차.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class HrvMeasurementApiIntegrationTest {

    private static final String URL = "/api/hrv/measurements";
    private static final String SEOUL_NOW = "2026-11-12T07:30:00+09:00";
    private static final String STARTED_AT = "2026-11-12T07:28:50+09:00";
    private static final String ONBOARDED_UTC = "2026-11-10 01:00:00.000000"; // 서울 11-10 10:00
    private static final SyntheticPpg CLEAN = SyntheticPpg.clean(70_000, 30);

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
        clock.set(SEOUL_NOW);
        userId = insertUser(ONBOARDED_UTC);
        insertSettings(userId, true);
        insertConsent(userId, "2026-11-10 00:50:00.000000", null);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'measure-%'");
    }

    // ---------- 저장 ----------

    @Test
    void 깨끗한_카메라_신호는_서버가_RMSSD를_계산해_GOOD으로_저장한다() throws Exception {
        save(camera(STARTED_AT, CLEAN), UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurement.qualityLevel").value("GOOD"))
                .andExpect(jsonPath("$.measurement.retryRequired").value(false))
                .andExpect(jsonPath("$.measurement.retryReason").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.measurement.rmssdMs").value(Matchers.closeTo(40.0, 6.0)))
                .andExpect(jsonPath("$.measurement.meanHrBpm").value(Matchers.closeTo(66.7, 3.0)))
                .andExpect(jsonPath("$.morningCheckId").isNumber());

        Map<String, Object> check = jdbc.queryForMap(
                "SELECT check_date, day_index, completed_at FROM morning_checks WHERE user_id = ?", userId);
        assertThat(check.get("check_date").toString()).isEqualTo("2026-11-12");
        assertThat(check.get("day_index")).isEqualTo(3);
        assertThat(check.get("completed_at")).isNull();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source, quality_level, retry_reason, algorithm_version, channel, frame_count, fps,
                       duration_sec, valid_beats, rejected_beats, noise_ratio, ln_rmssd, rmssd_ms, torch, time_source,
                       DATE_FORMAT(measured_at, '%Y-%m-%d %H:%i:%s') AS measured_utc
                  FROM hrv_measurements WHERE user_id = ?""", userId);
        assertThat(row)
                .containsEntry("source", "CAMERA")
                .containsEntry("quality_level", "GOOD")
                .containsEntry("algorithm_version", "hrv-cam-v1")
                .containsEntry("time_source", "captureTime")
                .containsEntry("measured_utc", "2026-11-11 22:28:50");
        assertThat(row.get("retry_reason")).isNull();
        assertThat(row.get("channel")).isIn("R", "G");
        assertThat((Integer) row.get("frame_count")).isGreaterThan(1700);
        assertThat((Double) row.get("fps")).isBetween(29.0, 31.0);
        assertThat((Double) row.get("duration_sec")).isBetween(59.0, 61.0);
        assertThat((Integer) row.get("valid_beats")).isPositive();
        assertThat(row.get("rejected_beats")).isNotNull();
        assertThat((Double) row.get("noise_ratio")).isLessThanOrEqualTo(0.06);
        assertThat((Double) row.get("ln_rmssd")).isEqualTo(Math.log((Double) row.get("rmssd_ms")));
    }

    @Test
    void 측정이_짧으면_400이_아니라_RETRY로_저장하고_사유를_알려준다() throws Exception {
        save(camera(STARTED_AT, SyntheticPpg.clean(40_000, 30)), UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurement.qualityLevel").value("RETRY"))
                .andExpect(jsonPath("$.measurement.retryRequired").value(true))
                .andExpect(jsonPath("$.measurement.retryReason").value("TOO_SHORT"))
                .andExpect(jsonPath("$.measurement.rmssdMs").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.measurement.meanHrBpm").value(Matchers.nullValue()));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT rmssd_ms, ln_rmssd, retry_reason, frame_count, valid_beats FROM hrv_measurements "
                        + "WHERE user_id = ?", userId);
        assertThat(row.get("rmssd_ms")).isNull();
        assertThat(row.get("ln_rmssd")).isNull();
        assertThat(row.get("retry_reason")).isEqualTo("TOO_SHORT");
        assertThat((Integer) row.get("frame_count")).isPositive();
        assertThat(row.get("valid_beats")).isNull();
    }

    @Test
    void 재측정은_같은_날_아침_체크에_연결된다() throws Exception {
        long first = morningCheckId(save(camera(STARTED_AT, SyntheticPpg.clean(40_000, 30)), UUID.randomUUID()));
        clock.set("2026-11-12T07:32:00+09:00");
        long second = morningCheckId(save(camera("2026-11-12T07:30:40+09:00", CLEAN), UUID.randomUUID()));

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM morning_checks WHERE user_id = ?")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM hrv_measurements WHERE user_id = ?")).isEqualTo(2);
    }

    @Test
    void 자정_직전에_시작해_자정_뒤에_저장해도_시작한_날의_체크다() throws Exception {
        clock.set("2026-11-13T00:00:30+09:00");

        save(camera("2026-11-12T23:59:10+09:00", CLEAN), UUID.randomUUID()).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT check_date FROM morning_checks WHERE user_id = ?", String.class,
                userId)).isEqualTo("2026-11-12");
    }

    @Test
    void 수동_입력은_RMSSD만_받아_UNVERIFIED_참고값으로_저장한다() throws Exception {
        Map<String, Object> body = Map.of("source", "MANUAL", "measuredAt", STARTED_AT, "rmssdMs", 42.5);

        save(body, UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurement.qualityLevel").value("UNVERIFIED"))
                .andExpect(jsonPath("$.measurement.retryRequired").value(false))
                .andExpect(jsonPath("$.measurement.rmssdMs").value(42.5))
                .andExpect(jsonPath("$.measurement.meanHrBpm").value(Matchers.nullValue()));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source, algorithm_version, ln_rmssd, channel FROM hrv_measurements WHERE user_id = ?",
                userId);
        assertThat(row).containsEntry("source", "MANUAL").containsEntry("algorithm_version", "manual-v1");
        assertThat((Double) row.get("ln_rmssd")).isEqualTo(Math.log(42.5));
        assertThat(row.get("channel")).isNull();
    }

    // ---------- 재시도 키 ----------

    @Test
    void 같은_키로_다시_보내면_다시_저장하지_않고_같은_응답을_돌려준다() throws Exception {
        UUID key = UUID.randomUUID();
        String first = save(camera(STARTED_AT, CLEAN), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = save(camera(STARTED_AT, CLEAN), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM hrv_measurements WHERE user_id = ?")).isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_측정을_보내면_409() throws Exception {
        UUID key = UUID.randomUUID();
        save(camera(STARTED_AT, CLEAN), key).andExpect(status().isCreated());

        save(camera(STARTED_AT, SyntheticPpg.clean(40_000, 30)), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void Idempotency_Key가_없거나_UUID가_아니면_400() throws Exception {
        mvc.perform(post(URL).with(loginAs(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json(camera(STARTED_AT, CLEAN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(URL).with(loginAs(userId)).with(csrf()).header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(json(camera(STARTED_AT, CLEAN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 접근 조건 ----------

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(post(URL).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json(camera(STARTED_AT, CLEAN))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 온보딩을_마치지_않았으면_403_ONBOARDING_REQUIRED() throws Exception {
        jdbc.update("UPDATE users SET onboarding_completed_at = NULL WHERE id = ?", userId);

        expectError(camera(STARTED_AT, CLEAN), 403, "ONBOARDING_REQUIRED");
    }

    @Test
    void 건강정보_동의를_철회했으면_403_HRV_CONSENT_REQUIRED() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);

        expectError(camera(STARTED_AT, CLEAN), 403, "HRV_CONSENT_REQUIRED");
    }

    @Test
    void 철회_후_다시_동의했으면_가장_최근_동의로_판단한다() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);
        insertConsent(userId, "2026-11-11 01:00:00.000000", null);

        save(camera(STARTED_AT, CLEAN), UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void 측정이_꺼져_있거나_설정이_없으면_403_HRV_DISABLED() throws Exception {
        jdbc.update("UPDATE user_settings SET hrv_enabled = FALSE WHERE user_id = ?", userId);
        expectError(camera(STARTED_AT, CLEAN), 403, "HRV_DISABLED");

        jdbc.update("DELETE FROM user_settings WHERE user_id = ?", userId);
        expectError(camera(STARTED_AT, CLEAN), 403, "HRV_DISABLED");
    }

    @Test
    void 탈퇴_처리_중이면_409_INVALID_STATE() throws Exception {
        jdbc.update("UPDATE users SET deletion_status = 'DELETING' WHERE id = ?", userId);

        expectError(camera(STARTED_AT, CLEAN), 409, "INVALID_STATE");
    }

    @Test
    void 접근_조건은_요청_검증보다_먼저_본다() throws Exception {
        jdbc.update("UPDATE user_settings SET hrv_enabled = FALSE WHERE user_id = ?", userId);

        expectError(Map.of("source", "WATCH"), 403, "HRV_DISABLED");
    }

    // ---------- 요청 검증 ----------

    @Test
    void 배열_길이가_다르면_400() throws Exception {
        Map<String, Object> body = camera(STARTED_AT, CLEAN);
        body.put("g", Arrays.stream(CLEAN.g).limit(10).boxed().toList());

        expectError(body, 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("t"));
    }

    @Test
    void 밝기가_0에서_255를_벗어나면_400() throws Exception {
        Map<String, Object> body = camera(STARTED_AT, CLEAN);
        double[] r = CLEAN.r.clone();
        r[5] = 256;
        body.put("r", Arrays.stream(r).boxed().toList());

        expectError(body, 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("r"));
    }

    @Test
    void 배열이_10000개를_넘으면_400() throws Exception {
        Map<String, Object> body = camera(STARTED_AT, CLEAN);
        List<Double> many = java.util.Collections.nCopies(10_001, 1.0);
        body.put("t", many);
        body.put("r", many);
        body.put("g", many);

        expectError(body, 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[*].field").value(Matchers.contains("t", "r", "g")));
    }

    @Test
    void 미래_시각이나_서버와_5분_넘게_차이_나는_시각은_400() throws Exception {
        expectError(camera("2026-11-12T07:30:01+09:00", CLEAN), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startedAt"));
        expectError(camera("2026-11-12T07:24:59+09:00", CLEAN), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startedAt"));
        // 정확히 5분 전은 허용
        save(camera("2026-11-12T07:25:00+09:00", CLEAN), UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void source가_없거나_모르는_값이면_400() throws Exception {
        expectError(Map.of("source", "WATCH"), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("source"));
    }

    @Test
    void 수동_입력의_RMSSD가_0_이하면_400() throws Exception {
        expectError(Map.of("source", "MANUAL", "measuredAt", STARTED_AT, "rmssdMs", 0), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("rmssdMs"));
    }

    @Test
    void 요청_본문이_512KB를_넘으면_400() throws Exception {
        String big = "{\"source\":\"CAMERA\",\"pad\":\"" + "x".repeat(512 * 1024) + "\"}";

        mvc.perform(post(URL).with(loginAs(userId)).with(csrf()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청 본문이 너무 커요."));
        assertThat(count("SELECT COUNT(*) FROM hrv_measurements WHERE user_id = ?")).isZero();
    }

    // ---------- 도우미 ----------

    private Map<String, Object> camera(String startedAt, SyntheticPpg signal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "CAMERA");
        body.put("startedAt", startedAt);
        body.put("torch", true);
        body.put("timeSource", "captureTime");
        body.put("t", Arrays.stream(signal.t).boxed().toList());
        body.put("r", Arrays.stream(signal.r).boxed().toList());
        body.put("g", Arrays.stream(signal.g).boxed().toList());
        return body;
    }

    private ResultActions save(Map<String, Object> body, UUID key) throws Exception {
        return mvc.perform(post(URL).with(loginAs(userId)).with(csrf()).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions expectError(Map<String, Object> body, int status, String code) throws Exception {
        return save(body, UUID.randomUUID())
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private long morningCheckId(ResultActions result) throws Exception {
        String content = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(content).get("morningCheckId").asLong();
    }

    private String json(Object body) {
        return jsonMapper.writeValueAsString(body);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, userId);
    }

    private long insertUser(String onboardingCompletedUtc) {
        String sub = "measure-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', ?, '2026-11-10 00:00:00', '2026-11-10 00:00:00')",
                sub, onboardingCompletedUtc);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    private void insertSettings(long user, boolean hrvEnabled) {
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, ?, "
                + "'2026-11-10 00:00:00')", user, hrvEnabled);
    }

    private void insertConsent(long user, String agreedAtUtc, String withdrawnAtUtc) {
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at, withdrawn_at) "
                + "VALUES (?, 'SENSITIVE_HRV', '2026-11-01', ?, ?)", user, agreedAtUtc, withdrawnAtUtc);
    }
}
