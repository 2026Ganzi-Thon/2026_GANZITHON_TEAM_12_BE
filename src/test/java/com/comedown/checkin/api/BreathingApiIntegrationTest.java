package com.comedown.checkin.api;

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
import java.time.Duration;
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

/** {@code POST /api/morning-checks/today/breathing}. 기준 시각 2026-11-12 07:30 서울, 온보딩 2026-11-10. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class BreathingApiIntegrationTest {

    private static final String URL = "/api/morning-checks/today/breathing";

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
        String sub = "breathing-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-10 01:00:00', '2026-11-10 00:00:00', "
                + "'2026-11-10 00:00:00')", sub);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'breathing-%'");
    }

    @Test
    void 호흡을_마치면_204이고_오늘_체크에_완료_시각이_남는다() throws Exception {
        breathe("{\"durationSec\":60}").andExpect(status().isNoContent());

        Map<String, Object> check = jdbc.queryForMap("SELECT check_date, day_index, completed_at, "
                + "DATE_FORMAT(breathing_completed_at, '%Y-%m-%d %H:%i:%s') AS breathed FROM morning_checks "
                + "WHERE user_id = ?", userId);
        assertThat(check.get("check_date").toString()).isEqualTo("2026-11-12");
        assertThat(check).containsEntry("day_index", 3).containsEntry("breathed", "2026-11-11 22:30:00");
        // 호흡은 완료(DONE) 기준이 아니다
        assertThat(check.get("completed_at")).isNull();
        mvc.perform(get("/api/morning-checks/today").with(loginAs(userId)))
                .andExpect(jsonPath("$.steps.breathing.done").value(true))
                .andExpect(jsonPath("$.steps.condition.done").value(false));
    }

    @Test
    void 다시_불러도_처음_완료한_시각을_유지한다() throws Exception {
        breathe("{\"durationSec\":60}").andExpect(status().isNoContent());
        clock.advance(Duration.ofMinutes(5));

        breathe("{\"durationSec\":45}").andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(breathing_completed_at, '%H:%i') FROM morning_checks "
                + "WHERE user_id = ?", String.class, userId)).isEqualTo("22:30");
    }

    @Test
    void 호흡_시간은_1에서_3600초() throws Exception {
        breathe("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("durationSec"));
        breathe("{\"durationSec\":0}").andExpect(status().isBadRequest());
        breathe("{\"durationSec\":3601}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM morning_checks WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    @Test
    void 건강정보_동의_없이도_기록하지만_온보딩_전이면_403() throws Exception {
        breathe("{\"durationSec\":60}").andExpect(status().isNoContent());

        jdbc.update("UPDATE users SET onboarding_completed_at = NULL WHERE id = ?", userId);
        breathe("{\"durationSec\":60}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    private ResultActions breathe(String body) throws Exception {
        return mvc.perform(post(URL).with(loginAs(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
