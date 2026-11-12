package com.comedown.experience.assessments;

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
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * CBI 문항 조회·제출·이력. 기준 시각 2026-11-12 07:30 서울.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class CbiApiIntegrationTest {

    private static final String VERSION = "cbi-ko-2026.11.2";

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
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser("CBI", null);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'cbi-%'");
    }

    // ---------- 문항 ----------

    @Test
    void 문항은_버전_척도_13문항을_돌려준다() throws Exception {
        mvc.perform(get("/api/cbi/questions").with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(VERSION))
                .andExpect(jsonPath("$.scales.FREQUENCY[0]").value("항상"))
                .andExpect(jsonPath("$.scales.DEGREE[4]").value("거의 없음"))
                .andExpect(jsonPath("$.items.length()").value(13))
                .andExpect(jsonPath("$.items[6].scale").value("DEGREE"))
                .andExpect(jsonPath("$.items[12].subscale").value("WORK"))
                .andExpect(jsonPath("$.items[0].reverse").doesNotExist());
    }

    // ---------- 제출 ----------

    @Test
    void 가입_때_제출하면_채점해_저장하고_생활_정보_단계로_넘어간다() throws Exception {
        submit("INITIAL", mixedOptions(), UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.round").value("INITIAL"))
                .andExpect(jsonPath("$.takenAt").value("2026-11-12T07:30:00+09:00"))
                .andExpect(jsonPath("$.personal.score").value(62.5))
                .andExpect(jsonPath("$.personal.level").value("HIGH"))
                .andExpect(jsonPath("$.work.score").value(53.6))
                .andExpect(jsonPath("$.work.level").value("HIGH"))
                .andExpect(jsonPath("$.highRisk").value(false))
                .andExpect(jsonPath("$.safety.show").value(false))
                .andExpect(jsonPath("$.onboardingStep").value("LIFESTYLE"));

        Map<String, Object> row = jdbc.queryForMap("SELECT id, questionnaire_version, personal_score, work_score, "
                + "high_risk FROM cbi_assessments WHERE user_id = ?", userId);
        assertThat(row).containsEntry("questionnaire_version", VERSION);
        assertThat(row.get("personal_score").toString()).isEqualTo("62.50");
        assertThat(row.get("work_score").toString()).isEqualTo("53.57");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cbi_answers WHERE assessment_id = ?", Integer.class,
                row.get("id"))).isEqualTo(13);
        // 13번은 원래 선택 2를 그대로, 점수는 역채점 값으로 저장
        assertThat(jdbc.queryForMap("SELECT answer_option, answer_value FROM cbi_answers WHERE assessment_id = ? "
                + "AND item_no = 13", row.get("id")))
                .containsEntry("answer_option", 2).containsEntry("answer_value", 50);
        assertThat(jdbc.queryForObject("SELECT onboarding_step FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("LIFESTYLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_alerts WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    @Test
    void 어느_하위척도든_75_이상이면_안전_안내를_만든다() throws Exception {
        submit("INITIAL", allOptions(0), UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personal.level").value("VERY_HIGH"))
                .andExpect(jsonPath("$.highRisk").value(true))
                .andExpect(jsonPath("$.safety.show").value(true));

        long assessmentId = jdbc.queryForObject("SELECT id FROM cbi_assessments WHERE user_id = ?", Long.class,
                userId);
        assertThat(jdbc.queryForMap("SELECT trigger_type, source_ref, resolved_at FROM safety_alerts "
                + "WHERE user_id = ?", userId))
                .containsEntry("trigger_type", "CBI_HIGH")
                .containsEntry("source_ref", String.valueOf(assessmentId))
                .containsEntry("resolved_at", null);
    }

    @Test
    void 같은_회차를_두_번_제출하면_409_같은_키_재전송은_같은_응답() throws Exception {
        UUID key = UUID.randomUUID();
        String first = submit("INITIAL", mixedOptions(), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = submit("INITIAL", mixedOptions(), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);

        submit("INITIAL", mixedOptions(), UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CBI_ALREADY_TAKEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cbi_assessments WHERE user_id = ?", Integer.class,
                userId)).isEqualTo(1);
    }

    @Test
    void 프로필_단계를_마치기_전에는_409() throws Exception {
        jdbc.update("UPDATE users SET onboarding_step = 'PROFILE' WHERE id = ?", userId);

        submit("INITIAL", mixedOptions(), UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_INVALID"));
    }

    @Test
    void 이미_지난_단계에서_제출해도_단계를_되돌리지_않는다() throws Exception {
        jdbc.update("UPDATE users SET onboarding_step = 'SCHEDULE_REPORT' WHERE id = ?", userId);

        submit("INITIAL", mixedOptions(), UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboardingStep").value("SCHEDULE_REPORT"));
    }

    @Test
    void 이십팔일차_전에는_DAY28을_받지_않는다() throws Exception {
        long user = insertUser("DONE", "2026-10-17 01:00:00"); // 서울 10-17 → 오늘 27일차

        mvc.perform(post("/api/cbi/assessments").with(loginAs(user)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body("DAY28", mixedOptions())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CBI_NOT_DUE"));
    }

    @Test
    void 이십팔일차에_DAY28을_제출하면_이전_CBI_HIGH_안내는_해제된다() throws Exception {
        long user = insertUser("DONE", "2026-10-16 01:00:00"); // 서울 10-16 → 오늘 28일차
        submitAs(user, "INITIAL", allOptions(0)).andExpect(jsonPath("$.highRisk").value(true));

        submitAs(user, "DAY28", mixedOptions())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.round").value("DAY28"))
                .andExpect(jsonPath("$.safety.show").value(false))
                .andExpect(jsonPath("$.onboardingStep").value("DONE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_alerts WHERE user_id = ? AND resolved_at IS NULL",
                Integer.class, user)).isZero();
        mvc.perform(get("/api/cbi/assessments").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessments.length()").value(2))
                .andExpect(jsonPath("$.assessments[0].round").value("DAY28"))
                .andExpect(jsonPath("$.assessments[0].work.score").value(53.6))
                .andExpect(jsonPath("$.assessments[1].round").value("INITIAL"))
                .andExpect(jsonPath("$.assessments[1].personal.score").value(100.0));
    }

    @Test
    void 형식이_틀리면_400이고_저장하지_않는다() throws Exception {
        Map<Integer, Integer> twelve = mixedOptions();
        twelve.remove(13);
        submit("INITIAL", twelve, UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("answers"));
        submit("FOLLOW_UP", mixedOptions(), UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("round"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cbi_assessments WHERE user_id = ?", Integer.class,
                userId)).isZero();
    }

    @Test
    void 건강정보_동의가_없으면_403() throws Exception {
        jdbc.update("UPDATE user_consents SET withdrawn_at = '2026-11-11 00:00:00' WHERE user_id = ?", userId);

        submit("INITIAL", mixedOptions(), UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HRV_CONSENT_REQUIRED"));
    }

    @Test
    void 이력은_본인_것만_최신순으로_보여준다() throws Exception {
        long other = insertUser("CBI", null);
        submitAs(other, "INITIAL", allOptions(0)).andExpect(status().isCreated());

        mvc.perform(get("/api/cbi/assessments").with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessments").isEmpty());
    }

    // ---------- 도우미 ----------

    /** PERSONAL 62.5, WORK 53.57(표시 53.6), high_risk 아님. */
    private static Map<Integer, Integer> mixedOptions() {
        Map<Integer, Integer> options = allOptions(2);
        options.put(1, 1);
        options.put(2, 1);
        options.put(5, 1);
        options.put(7, 1);
        return options;
    }

    private static Map<Integer, Integer> allOptions(int option) {
        Map<Integer, Integer> options = new LinkedHashMap<>();
        for (int no = 1; no <= 13; no++) {
            options.put(no, option);
        }
        return options;
    }

    private String body(String round, Map<Integer, Integer> options) {
        List<Map<String, Integer>> answers = new ArrayList<>();
        options.forEach((no, option) -> answers.add(Map.of("no", no, "option", option)));
        return jsonMapper.writeValueAsString(Map.of(
                "round", round, "questionnaireVersion", VERSION, "answers", answers));
    }

    private ResultActions submit(String round, Map<Integer, Integer> options, UUID key) throws Exception {
        return mvc.perform(post("/api/cbi/assessments").with(loginAs(userId)).with(csrf())
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(round, options)));
    }

    private ResultActions submitAs(long user, String round, Map<Integer, Integer> options) throws Exception {
        return mvc.perform(post("/api/cbi/assessments").with(loginAs(user)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(round, options)));
    }

    private long insertUser(String step, String onboardingCompletedUtc) {
        String sub = "cbi-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, ?, ?, '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
                sub, step, onboardingCompletedUtc);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        jdbc.update("INSERT INTO user_consents (user_id, consent_type, version, agreed_at) VALUES (?, "
                + "'SENSITIVE_HRV', '2026-11-01', '2026-10-01 00:00:00')", id);
        return id;
    }
}
