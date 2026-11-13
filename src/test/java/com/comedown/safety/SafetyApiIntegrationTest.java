package com.comedown.safety;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.LocalDate;
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

/**
 * 안전 안내 조회·닫기와 2주 연속 낮음 평가. 기준 시각 2026-11-12 07:30 서울, 온보딩 2026-11-10.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class SafetyApiIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

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
        userId = insertUser();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'safety-%'");
    }

    // ---------- 조회·닫기 ----------

    @Test
    void 안내가_없으면_show는_false이고_지원_기관과_고지_문구를_준다() throws Exception {
        mvc.perform(get("/api/safety").with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"show":false,"alerts":[],
                         "resources":[{"id":"verified-support-resource","name":"공식 확인 후 등록한 지원 기관",
                                       "phone":null,"url":null,"verifiedAt":null}],
                         "disclaimer":"Come-down은 의료 서비스가 아니며 질병 유무를 판단하지 않아요."}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 열린_안내는_보여주고_닫으면_show만_꺼진다() throws Exception {
        long alertId = insertAlert(userId, "LOW_RECOVERY_2W", null);

        mvc.perform(get("/api/safety").with(loginAs(userId)))
                .andExpect(jsonPath("$.show").value(true))
                .andExpect(jsonPath("$.alerts[0].id").value(alertId))
                .andExpect(jsonPath("$.alerts[0].triggerType").value("LOW_RECOVERY_2W"))
                .andExpect(jsonPath("$.alerts[0].triggeredAt").value("2026-11-12T07:00:00+09:00"))
                .andExpect(jsonPath("$.alerts[0].message").value("최근 2주 동안 회복이 평소보다 낮게 이어졌어요."));

        acknowledge(userId, alertId).andExpect(status().isNoContent());
        acknowledge(userId, alertId).andExpect(status().isNoContent());

        mvc.perform(get("/api/safety").with(loginAs(userId)))
                .andExpect(jsonPath("$.show").value(false))
                .andExpect(jsonPath("$.alerts.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT acknowledged_at IS NOT NULL FROM safety_alerts WHERE id = ?",
                Boolean.class, alertId)).isTrue();
    }

    @Test
    void 조건이_풀린_안내는_목록에_없다() throws Exception {
        insertAlert(userId, "CBI_HIGH", "'2026-11-11 00:00:00'");

        mvc.perform(get("/api/safety").with(loginAs(userId)))
                .andExpect(jsonPath("$.show").value(false))
                .andExpect(jsonPath("$.alerts").isEmpty());
    }

    @Test
    void 다른_사용자의_안내는_닫을_수_없다() throws Exception {
        long other = insertUser();
        long othersAlert = insertAlert(other, "CBI_HIGH", null);

        acknowledge(userId, othersAlert)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT acknowledged_at IS NULL FROM safety_alerts WHERE id = ?",
                Boolean.class, othersAlert)).isTrue();
    }

    // ---------- 2주 연속 낮음 ----------

    @Test
    void 최근_14일_대표값_7일이_모두_LOW면_컨디션_저장_때_안내를_만들고_같은_구간에서는_다시_만들지_않는다()
            throws Exception {
        for (int daysAgo = 7; daysAgo >= 1; daysAgo--) {
            insertState(TODAY.minusDays(daysAgo), "LOW");
        }

        condition().andExpect(status().isOk());
        assertThat(lowAlerts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_ref FROM safety_alerts WHERE user_id = ?", String.class,
                userId)).isEqualTo(TODAY.minusDays(7).toString());

        long alertId = jdbc.queryForObject("SELECT id FROM safety_alerts WHERE user_id = ?", Long.class, userId);
        acknowledge(userId, alertId).andExpect(status().isNoContent());
        condition().andExpect(status().isOk());
        // 닫았어도 같은 연속 구간이면 새 안내를 만들지 않는다
        assertThat(lowAlerts()).isEqualTo(1);
        mvc.perform(get("/api/safety").with(loginAs(userId))).andExpect(jsonPath("$.show").value(false));
    }

    @Test
    void 조건이_풀리면_해제하고_다시_충족되면_새_안내를_만든다() throws Exception {
        for (int daysAgo = 7; daysAgo >= 1; daysAgo--) {
            insertState(TODAY.minusDays(daysAgo), "LOW");
        }
        condition();

        jdbc.update("UPDATE hrv_daily_states SET recovery_status = 'NORMAL' WHERE user_id = ? AND state_date = ?",
                userId, TODAY.minusDays(1));
        condition();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_alerts WHERE user_id = ? AND resolved_at IS NULL",
                Integer.class, userId)).isZero();

        jdbc.update("UPDATE hrv_daily_states SET recovery_status = 'LOW' WHERE user_id = ? AND state_date = ?",
                userId, TODAY.minusDays(1));
        condition();
        assertThat(lowAlerts()).isEqualTo(2);
        mvc.perform(get("/api/safety").with(loginAs(userId))).andExpect(jsonPath("$.show").value(true));
    }

    // ---------- 도우미 ----------

    private ResultActions condition() throws Exception {
        return mvc.perform(put("/api/morning-checks/today/condition").with(loginAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":2,\"alcohol\":false,\"sick\":false,\"caffeine\":false}"));
    }

    private ResultActions acknowledge(long user, long alertId) throws Exception {
        return mvc.perform(post("/api/safety/alerts/" + alertId + "/acknowledge").with(loginAs(user)).with(csrf()));
    }

    private int lowAlerts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM safety_alerts WHERE user_id = ? AND trigger_type = "
                + "'LOW_RECOVERY_2W'", Integer.class, userId);
    }

    private long insertUser() {
        String sub = "safety-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-10 01:00:00', '2026-11-01 00:00:00', "
                + "'2026-11-01 00:00:00')", sub);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-11-01', '2026-11-01 00:00:00')", id);
        return id;
    }

    private long insertAlert(long user, String trigger, String resolvedAtSql) {
        jdbc.update("INSERT INTO safety_alerts (user_id, trigger_type, triggered_at, resolved_at) VALUES (?, ?, "
                + "'2026-11-11 22:00:00', " + (resolvedAtSql == null ? "NULL" : resolvedAtSql) + ")", user, trigger);
        return jdbc.queryForObject("SELECT MAX(id) FROM safety_alerts WHERE user_id = ?", Long.class, user);
    }

    /** 과거 날짜의 회복 상태 행(대표값 있음). 아침 체크 기록 없이 넣어 오늘 재계산에 영향을 주지 않는다. */
    private void insertState(LocalDate date, String status) {
        jdbc.update("INSERT INTO hrv_daily_states (user_id, state_date, daily_ln_rmssd, daily_rmssd_ms, "
                + "measurement_count, quality_level, confounded, baseline_established, baseline_valid_days, "
                + "baseline_mean_ln, baseline_sd_ln, baseline_sample_count, baseline_frozen, trend_ln, change_pct, "
                + "recovery_status, calculation_version, computed_at) VALUES (?, ?, 3.4, 30.0, 1, 'GOOD', FALSE, "
                + "TRUE, 10, 3.75, 0.1, 10, FALSE, 3.5, -22.1, ?, 'recovery-v1', '2026-11-11 00:00:00')",
                userId, date, status);
    }
}
