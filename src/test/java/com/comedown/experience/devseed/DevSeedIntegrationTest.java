package com.comedown.experience.devseed;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * local 전용 시연 데이터 입력({@code POST /api/dev/seed}, DEMO_OVERLOAD).
 * 기능 contract와 실제 계산(hrv-cam-v1·recovery-v1)을 거쳐 4주 기록이 쌓이고, 화면 API가 그 기록을 읽는지 본다.
 * 기준 시각 2026-11-12 07:30 서울 → 온보딩 2026-10-16(오늘 28일차).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class DevSeedIntegrationTest {

    private static final String SEED = "{\"scenario\":\"DEMO_OVERLOAD\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertNewUser();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'seed-%'");
    }

    private ResultActions seed(long asUser, String body) throws Exception {
        return mvc.perform(post("/api/dev/seed").with(csrf()).with(loginAs(asUser))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 가입_직후_사용자를_28일차_시연_상태로_만든다() throws Exception {
        seed(userId, SEED)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("DEMO_OVERLOAD"))
                .andExpect(jsonPath("$.onboardingDate").value("2026-10-16"))
                .andExpect(jsonPath("$.today").value("2026-11-12"))
                .andExpect(jsonPath("$.dayIndex").value(28))
                .andExpect(jsonPath("$.seededDays").value(26))
                .andExpect(jsonPath("$.latestRecoveryStatus").value("LOW"))
                .andExpect(jsonPath("$.cbiRounds[0]").value("INITIAL"))
                .andExpect(jsonPath("$.notSeeded[0]").value("SCHEDULE"));

        // 계정(account 계약): 온보딩 완료 07:00 서울 = 전날 22:00 UTC, 측정 켜짐, 건강정보 동의
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT onboarding_step, onboarding_completed_at FROM users WHERE id = ?", userId);
        assertThat(user.get("onboarding_step")).isEqualTo("DONE");
        assertThat(user.get("onboarding_completed_at").toString()).isEqualTo("2026-10-15T22:00");
        assertThat(jdbc.queryForObject("SELECT hrv_enabled FROM user_settings WHERE user_id = ?",
                Boolean.class, userId)).isTrue();
        assertThat(count("user_consents WHERE consent_type = 'SENSITIVE_HRV' AND withdrawn_at IS NULL"))
                .isEqualTo(1);

        // CBI: 초기 회차만, 온보딩 10분 전 응답, 고위험 아님 → CBI_HIGH 안내 없음
        Map<String, Object> cbi = jdbc.queryForMap(
                "SELECT round, taken_at, high_risk FROM cbi_assessments WHERE user_id = ?", userId);
        assertThat(cbi.get("round")).isEqualTo("INITIAL");
        assertThat(cbi.get("taken_at").toString()).isEqualTo("2026-10-15T21:50");
        assertThat(cbi.get("high_risk")).isEqualTo(false);
        assertThat(count("cbi_answers a JOIN cbi_assessments s ON s.id = a.assessment_id WHERE s.user_id = "
                + userId)).isEqualTo(13);

        // 측정: 26일 모두 실제 계산을 거친 적격 카메라 측정, 오늘(28일차)·13일차는 비어 있음
        assertThat(count("hrv_measurements WHERE source = 'CAMERA' AND quality_level IN ('GOOD', 'FAIR')"))
                .isEqualTo(26);
        assertThat(count("morning_checks WHERE check_date IN ('2026-10-28', '2026-11-12')")).isZero();
        assertThat(count("morning_checks WHERE completed_at IS NOT NULL")).isEqualTo(26);
        assertThat(count("morning_checks WHERE alcohol = TRUE")).isEqualTo(1);

        // 회복: 7일차 기준선 확정, 15~18일차 정상, 마지막 날 낮음, 최근 14일이 모두 낮지는 않아 안전 안내 없음
        assertThat(recoveryStatus("2026-10-22")).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("SELECT baseline_established FROM hrv_daily_states "
                + "WHERE user_id = ? AND state_date = '2026-10-22'", Boolean.class, userId)).isTrue();
        assertThat(recoveryStatuses("2026-10-30", "2026-11-02")).containsOnly("NORMAL");
        assertThat(recoveryStatus("2026-11-11")).isEqualTo("LOW");
        assertThat(count("safety_alerts")).isZero();
    }

    @Test
    void 시드한_기록을_화면_API가_읽는다() throws Exception {
        seed(userId, SEED).andExpect(status().isCreated());

        // 28일차지만 DAY28 CBI 전 → 재응답 화면
        mvc.perform(get("/api/reports/four-week").with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.cbiDue").value(true));
        // 오늘 아침 체크는 비어 있어 시연에서 직접 측정
        mvc.perform(get("/api/morning-checks/today").with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayIndex").value(28))
                .andExpect(jsonPath("$.phase").value("ESTABLISHED"))
                .andExpect(jsonPath("$.steps.measurement.done").value(false))
                .andExpect(jsonPath("$.result").doesNotExist());
        mvc.perform(get("/api/reports/recovery").with(loginAs(userId)))
                .andExpect(status().isOk());
    }

    @Test
    void 철회된_건강정보_동의가_있으면_새_동의를_넣는다() throws Exception {
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at, withdrawn_at) "
                + "VALUES (?, 'SENSITIVE_HRV', '2026-10-01', '2026-10-01 00:00:00', '2026-10-02 00:00:00')", userId);

        seed(userId, SEED).andExpect(status().isCreated());

        assertThat(count("user_consents WHERE consent_type = 'SENSITIVE_HRV'")).isEqualTo(2);
        assertThat(count("user_consents WHERE consent_type = 'SENSITIVE_HRV' AND withdrawn_at IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void 이미_건강_기록이_있으면_409이고_아무것도_바꾸지_않는다() throws Exception {
        seed(userId, SEED).andExpect(status().isCreated());
        int measurements = count("hrv_measurements");

        seed(userId, SEED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(count("hrv_measurements")).isEqualTo(measurements);
        assertThat(count("cbi_assessments")).isEqualTo(1);
    }

    @Test
    void CBI만_있어도_409() throws Exception {
        jdbc.update("INSERT INTO cbi_assessments (user_id, round, questionnaire_version, personal_score, "
                + "work_score, high_risk, taken_at) VALUES (?, 'INITIAL', 'cbi-ko-2026.11.2', 50, 50, FALSE, "
                + "'2026-11-01 00:00:00')", userId);

        seed(userId, SEED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(jdbc.queryForObject("SELECT onboarding_step FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("PROFILE");
    }

    @Test
    void 사용자_행이_없으면_409() throws Exception {
        seed(999_999_999L, SEED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 탈퇴_진행_중이면_409() throws Exception {
        jdbc.update("UPDATE users SET deletion_status = 'DELETING' WHERE id = ?", userId);

        seed(userId, SEED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(count("hrv_measurements")).isZero();
    }

    @Test
    void 모르는_시나리오는_400() throws Exception {
        seed(userId, "{\"scenario\":\"UNKNOWN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scenario"));
        seed(userId, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scenario"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(post("/api/dev/seed").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(SEED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- 준비·확인 ----------

    /** 프로필 단계의 새 사용자(설정·동의 없음). */
    private long insertNewUser() {
        String sub = "seed-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, created_at, last_login_at) "
                + "VALUES (?, 'PROFILE', '2026-10-01 00:00:00', '2026-10-01 00:00:00')", sub);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    private int count(String fromWhere) {
        String where = fromWhere.contains(" WHERE ") ? fromWhere + " AND " : fromWhere + " WHERE ";
        if (fromWhere.contains(" JOIN ")) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + where + "user_id = " + userId, Integer.class);
    }

    private String recoveryStatus(String date) {
        return jdbc.queryForObject("SELECT recovery_status FROM hrv_daily_states WHERE user_id = ? AND state_date = ?",
                String.class, userId, date);
    }

    private List<String> recoveryStatuses(String from, String to) {
        return jdbc.queryForList("SELECT recovery_status FROM hrv_daily_states WHERE user_id = ? "
                + "AND state_date BETWEEN ? AND ?", String.class, userId, from, to);
    }
}
