package com.comedown.experience;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.cbi.contract.CbiDataDeletion;
import com.comedown.checkin.contract.CheckinDataDeletion;
import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionDataDeletion;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.recovery.contract.RecoveryDataDeletion;
import com.comedown.safety.contract.SafetyDataDeletion;
import com.comedown.shared.idempotency.IdempotencyService;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import com.comedown.support.SyntheticPpg;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 건강 기록 전체 삭제(API 명세 8절·ERD 6절)를 experience.accountlifecycle이 조율하는 순서대로 기능별 삭제 계약을 불러 검증한다.
 * 삭제 뒤 조회·재계산·재시도 캐시가 지운 값을 되살리지 않는지, 다른 사용자·비건강 기록은 남는지 본다.
 * 기준 시각 2026-11-12 07:30 서울, 온보딩 2026-10-16(오늘 28일차).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class HealthDataErasureIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
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
    TransactionTemplate transactionTemplate;

    @Autowired
    SuggestionDataDeletion suggestionDeletion;

    @Autowired
    SafetyDataDeletion safetyDeletion;

    @Autowired
    CbiDataDeletion cbiDeletion;

    @Autowired
    RecoveryDataDeletion recoveryDeletion;

    @Autowired
    CheckinDataDeletion checkinDeletion;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    SuggestionCommands suggestions;

    @Autowired
    SuggestionApprovalCommands approvals;

    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser();
        otherUserId = insertUser();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'erase-%'");
    }

    @Test
    void 건강_기록을_모두_지우고_비건강_기록과_승인_이력만_남긴다() throws Exception {
        UUID measurementKey = UUID.randomUUID();
        String measurementBody = cameraBody();
        long oldMeasurementId = measure(userId, measurementKey, measurementBody);
        condition(userId);
        breathe(userId);
        submitHighRiskCbi(userId);
        seedYesterdayWithoutBreathing(userId);
        Fixtures s = seedSuggestions(userId);
        // 다른 사용자 기록
        measure(otherUserId, UUID.randomUUID(), cameraBody());
        condition(otherUserId);
        submitHighRiskCbi(otherUserId);
        SuggestionView othersRecovery = propose(otherUserId, "RECOVERY", "2026-11-12T08:00:00Z", "OVERLOAD_LOW");
        List<Long> cbiIds = jdbc.queryForList("SELECT id FROM cbi_assessments WHERE user_id = ?", Long.class, userId);
        assertThat(count("cbi_answers WHERE assessment_id IN (" + join(cbiIds) + ")")).isEqualTo(13);

        // experience.accountlifecycle이 한 트랜잭션에서 조율할 순서
        transactionTemplate.executeWithoutResult(tx -> {
            SuggestionDataDeletion.Removed removed = suggestionDeletion.removeHealthBasis(userId);
            assertThat(removed.deleted()).isEqualTo(2);
            assertThat(removed.stripped()).isEqualTo(2);
            assertThat(safetyDeletion.deleteAll(userId)).isEqualTo(1);
            assertThat(cbiDeletion.deleteAll(userId)).isEqualTo(1);
            assertThat(recoveryDeletion.deleteAll(userId)).isEqualTo(2);
            CheckinDataDeletion.Erased erased = checkinDeletion.eraseHealthData(userId);
            assertThat(erased).isEqualTo(new CheckinDataDeletion.Erased(2, 1, 1));
            idempotency.deleteForUser(userId, "hrv-");
            idempotency.deleteForUser(userId, "cbi-");
        });

        // 건강 기록 원본·파생·캐시
        assertThat(countOf("hrv_measurements")).isZero();
        assertThat(countOf("hrv_daily_states")).isZero();
        assertThat(countOf("cbi_assessments")).isZero();
        assertThat(count("cbi_answers WHERE assessment_id IN (" + join(cbiIds) + ")")).isZero();
        assertThat(countOf("safety_alerts")).isZero();
        assertThat(countOf("api_idempotency_keys")).isZero();

        // 호흡 기록이 있는 오늘 체크는 건강 필드만 비우고 남긴다, 어제 체크는 지운다
        Map<String, Object> today = jdbc.queryForMap("SELECT check_date, condition_score, alcohol, sick, caffeine, "
                + "completed_at, breathing_completed_at FROM morning_checks WHERE user_id = ?", userId);
        assertThat(today.get("check_date").toString()).isEqualTo("2026-11-12");
        assertThat(today.get("condition_score")).isNull();
        assertThat(today.get("alcohol")).isNull();
        assertThat(today.get("completed_at")).isNull();
        assertThat(today.get("breathing_completed_at")).isNotNull();

        // 제안: 미승인 회복 추천 삭제, 승인된 회복 추천은 근거만 지움, PRE_START는 판단만 지움
        assertThat(exists(s.proposedRecovery.id())).isFalse();
        assertThat(exists(s.dismissedRecovery.id())).isFalse();
        assertThat(jdbc.queryForMap("SELECT status, reason_code, judgment_state, message, google_event_id, title "
                + "FROM suggestions WHERE id = ?", s.approvedRecovery.id()))
                .containsEntry("status", "APPROVED")
                .containsEntry("reason_code", "HEALTH_DATA_DELETED")
                .containsEntry("judgment_state", null)
                .containsEntry("message", null)
                .containsEntry("google_event_id", s.approvedRecovery.registrationKey())
                .containsEntry("title", "20분 걷기");
        assertThat(jdbc.queryForMap("SELECT reason_code, judgment_state FROM suggestions WHERE id = ?",
                s.preStart.id()))
                .containsEntry("reason_code", "RISK_WEEK")
                .containsEntry("judgment_state", null);

        // CBI로 통과한 온보딩 단계는 되돌리지 않는다
        assertThat(jdbc.queryForObject("SELECT onboarding_step FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("DONE");

        // 다른 사용자 기록은 그대로
        assertThat(count("hrv_measurements WHERE user_id = " + otherUserId)).isEqualTo(1);
        assertThat(count("hrv_daily_states WHERE user_id = " + otherUserId)).isEqualTo(1);
        assertThat(count("cbi_assessments WHERE user_id = " + otherUserId)).isEqualTo(1);
        assertThat(count("safety_alerts WHERE user_id = " + otherUserId)).isEqualTo(1);
        assertThat(exists(othersRecovery.id())).isTrue();

        // 조회는 빈 상태
        mvc.perform(get("/api/morning-checks/today").with(loginAs(userId)))
                .andExpect(jsonPath("$.phase").value("BUILDING"))
                .andExpect(jsonPath("$.steps.measurement.done").value(false))
                .andExpect(jsonPath("$.steps.condition.done").value(false))
                .andExpect(jsonPath("$.steps.breathing.done").value(true))
                .andExpect(jsonPath("$.baseline.validDays").value(0))
                .andExpect(jsonPath("$.result").isEmpty());
        mvc.perform(get("/api/reports/recovery").with(loginAs(userId)))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.unlock.validDays").value(0));
        mvc.perform(get("/api/cbi/assessments").with(loginAs(userId)))
                .andExpect(jsonPath("$.assessments").isEmpty());
        mvc.perform(get("/api/safety").with(loginAs(userId)))
                .andExpect(jsonPath("$.show").value(false))
                .andExpect(jsonPath("$.alerts").isEmpty());

        // 같은 재시도 키로 다시 보내도 지운 응답을 재생하지 않는다(새 요청으로 저장)
        long replayed = measure(userId, measurementKey, measurementBody);
        assertThat(replayed).isNotEqualTo(oldMeasurementId);
        // 재계산은 남은 원본(새 측정)만 쓴다: 컨디션 전이라 적격일 0
        assertThat(jdbc.queryForObject("SELECT baseline_valid_days FROM hrv_daily_states WHERE user_id = ? "
                + "AND state_date = ?", Integer.class, userId, TODAY)).isZero();
        assertThat(countOf("hrv_daily_states")).isEqualTo(1);
    }

    // ---------- 준비 ----------

    private record Fixtures(SuggestionView proposedRecovery, SuggestionView dismissedRecovery,
            SuggestionView approvedRecovery, SuggestionView preStart) {
    }

    private Fixtures seedSuggestions(long user) {
        SuggestionView proposed = propose(user, "RECOVERY", "2026-11-12T08:00:00Z", "OVERLOAD_LOW");
        SuggestionView dismissed = propose(user, "RECOVERY", "2026-11-12T09:00:00Z", "NORMAL_LOW");
        suggestions.dismiss(user, dismissed.id());
        SuggestionView approved = propose(user, "RECOVERY", "2026-11-12T10:00:00Z", "OVERLOAD_LOW");
        approvals.beginApproval(user, approved.id(), null, null);
        approved = approvals.confirmApproved(user, approved.id(), approved.registrationKey());
        SuggestionView preStart = propose(user, "PRE_START", "2026-11-12T11:00:00Z", "OVERLOAD_OK");
        return new Fixtures(proposed, dismissed, approved, preStart);
    }

    private SuggestionView propose(long user, String type, String startUtc, String judgment) {
        Instant start = Instant.parse(startUtc);
        boolean recovery = "RECOVERY".equals(type);
        return suggestions.propose(new NewSuggestion(user, type, TODAY, start, start.plusSeconds(1200),
                recovery ? "20분 걷기" : "발표 준비", recovery ? "WALK" : null, judgment,
                recovery ? "LOW_RECOVERY" : "RISK_WEEK", recovery ? "recovery-v1" : "load-v1",
                recovery ? "회복이 평소보다 낮아요. 걸어 볼까요?" : null, false, recovery, null, null,
                TODAY.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
    }

    private String cameraBody() {
        SyntheticPpg signal = SyntheticPpg.clean(70_000, 30);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "CAMERA");
        body.put("startedAt", STARTED_AT);
        body.put("torch", true);
        body.put("timeSource", "captureTime");
        body.put("t", Arrays.stream(signal.t).boxed().toList());
        body.put("r", Arrays.stream(signal.r).boxed().toList());
        body.put("g", Arrays.stream(signal.g).boxed().toList());
        return jsonMapper.writeValueAsString(body);
    }

    private long measure(long user, UUID key, String body) throws Exception {
        String response = mvc.perform(post("/api/hrv/measurements").with(loginAs(user)).with(csrf())
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(response).get("measurement").get("id").asLong();
    }

    private void condition(long user) throws Exception {
        mvc.perform(put("/api/morning-checks/today/condition").with(loginAs(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":3,\"alcohol\":false,\"sick\":false,\"caffeine\":true}"))
                .andExpect(status().isOk());
    }

    private void breathe(long user) throws Exception {
        mvc.perform(post("/api/morning-checks/today/breathing").with(loginAs(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durationSec\":60}"))
                .andExpect(status().isNoContent());
    }

    private void submitHighRiskCbi(long user) throws Exception {
        List<Map<String, Integer>> answers = new ArrayList<>();
        for (int no = 1; no <= 13; no++) {
            answers.add(Map.of("no", no, "option", 0));
        }
        mvc.perform(post("/api/cbi/assessments").with(loginAs(user)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("round", "INITIAL",
                                "questionnaireVersion", "cbi-ko-2026.11.2", "answers", answers))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.highRisk").value(true));
    }

    /** 어제: 측정·컨디션은 있고 호흡 기록은 없는 체크. */
    private void seedYesterdayWithoutBreathing(long user) {
        jdbc.update("INSERT INTO morning_checks (user_id, check_date, day_index, condition_score, alcohol, sick, "
                + "caffeine, completed_at, created_at) VALUES (?, '2026-11-11', 27, 4, FALSE, FALSE, FALSE, "
                + "'2026-11-10 22:40:00', '2026-11-10 22:30:00')", user);
        long checkId = jdbc.queryForObject("SELECT id FROM morning_checks WHERE user_id = ? AND check_date = "
                + "'2026-11-11'", Long.class, user);
        jdbc.update("INSERT INTO hrv_measurements (user_id, morning_check_id, measured_at, source, rmssd_ms, "
                + "ln_rmssd, quality_level, algorithm_version, created_at) VALUES (?, ?, '2026-11-10 22:30:00', "
                + "'CAMERA', 40.0, ?, 'GOOD', 'hrv-cam-v1', '2026-11-10 22:31:00')", user, checkId, Math.log(40.0));
        jdbc.update("INSERT INTO hrv_daily_states (user_id, state_date, daily_ln_rmssd, daily_rmssd_ms, "
                + "measurement_count, quality_level, confounded, baseline_established, baseline_valid_days, "
                + "baseline_sample_count, baseline_frozen, recovery_status, calculation_version, computed_at) "
                + "VALUES (?, '2026-11-11', ?, 40.0, 1, 'GOOD', FALSE, FALSE, 1, 1, FALSE, 'BUILDING', 'recovery-v1', "
                + "'2026-11-11 00:00:00')", user, Math.log(40.0));
    }

    private long insertUser() {
        String sub = "erase-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-10-16 01:00:00', '2026-10-01 00:00:00', "
                + "'2026-10-01 00:00:00')", sub);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_settings (user_id, hrv_enabled, updated_at) VALUES (?, TRUE, "
                + "'2026-10-01 00:00:00')", id);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-10-01', '2026-10-01 00:00:00')", id);
        return id;
    }

    // ---------- 확인 ----------

    private int countOf(String table) {
        return count(table + " WHERE user_id = " + userId);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
    }

    private boolean exists(long suggestionId) {
        return count("suggestions WHERE id = " + suggestionId) == 1;
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
    }
}
