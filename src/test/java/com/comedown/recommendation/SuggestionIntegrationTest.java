package com.comedown.recommendation;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.recommendation.contract.SuggestionView;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * 제안 조회·건너뛰기·했어요 API와 승인 상태 전이 계약. 기준 시각 2026-11-12 07:30 서울.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class SuggestionIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SuggestionCommands commands;

    @Autowired
    SuggestionApprovalCommands approvals;

    private long userId;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        userId = insertUser("DONE", "2026-11-10 01:00:00");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'suggestion-%'");
    }

    // ---------- 조회 ----------

    @Test
    void 목록은_Suggestion_모양으로_시작_시각_순이며_등록_키는_내보내지_않는다() throws Exception {
        SuggestionView later = propose(userId, "RECOVERY", TODAY, "2026-11-12T08:00:00Z", 20, "걸어 볼까요?");
        SuggestionView earlier = propose(userId, "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 20, null);

        mvc.perform(get("/api/suggestions").with(loginAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].id").value(earlier.id()))
                .andExpect(jsonPath("$.suggestions[0].start").value("2026-11-12T16:00:00+09:00"))
                .andExpect(jsonPath("$.suggestions[0].end").value("2026-11-12T16:20:00+09:00"))
                .andExpect(jsonPath("$.suggestions[0].durationMin").value(20))
                .andExpect(jsonPath("$.suggestions[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$.suggestions[0].message").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.suggestions[0].relatedEvent").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.suggestions[0].registrationKey").doesNotExist())
                .andExpect(jsonPath("$.suggestions[1].id").value(later.id()))
                .andExpect(jsonPath("$.suggestions[1].message.text").value("걸어 볼까요?"))
                .andExpect(jsonPath("$.suggestions[1].message.aiGenerated").value(false))
                .andExpect(jsonPath("$.suggestions[1].message.fallback").value(true));
    }

    @Test
    void 종류_상태_날짜로_거를_수_있고_다른_사용자_것은_보이지_않는다() throws Exception {
        propose(userId, "PRE_START", TODAY, "2026-11-12T07:00:00Z", 60, null);
        SuggestionView recovery = propose(userId, "RECOVERY", TODAY, "2026-11-12T09:00:00Z", 20, null);
        propose(userId, "RECOVERY", TODAY.plusDays(1), "2026-11-13T07:00:00Z", 20, null);
        commands.dismiss(userId, propose(userId, "RECOVERY", TODAY, "2026-11-12T10:00:00Z", 20, null).id());
        propose(insertUser("DONE", "2026-11-10 01:00:00"), "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 20, null);

        mvc.perform(get("/api/suggestions?type=RECOVERY&status=PROPOSED&date=2026-11-12").with(loginAs(userId)))
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].id").value(recovery.id()));
        mvc.perform(get("/api/suggestions?type=PRE_START").with(loginAs(userId)))
                .andExpect(jsonPath("$.suggestions.length()").value(1));
    }

    @Test
    void 잘못된_조건은_400이고_RECOVERY는_온보딩_뒤에만_조회한다() throws Exception {
        mvc.perform(get("/api/suggestions?type=WALK").with(loginAs(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("type"));
        mvc.perform(get("/api/suggestions?date=11-12").with(loginAs(userId)))
                .andExpect(status().isBadRequest());

        long onboarding = insertUser("SCHEDULE_REPORT", null);
        mvc.perform(get("/api/suggestions?type=RECOVERY").with(loginAs(onboarding)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
        mvc.perform(get("/api/suggestions?type=PRE_START&status=PROPOSED").with(loginAs(onboarding)))
                .andExpect(status().isOk());
    }

    // ---------- 건너뛰기·했어요 ----------

    @Test
    void 건너뛰기는_PROPOSED에서만_반복은_같은_결과_남의_제안은_404() throws Exception {
        SuggestionView s = propose(userId, "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 20, null);

        mvc.perform(post("/api/suggestions/" + s.id() + "/dismiss").with(loginAs(userId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        mvc.perform(post("/api/suggestions/" + s.id() + "/dismiss").with(loginAs(userId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        long other = insertUser("DONE", "2026-11-10 01:00:00");
        mvc.perform(post("/api/suggestions/" + s.id() + "/dismiss").with(loginAs(other)).with(csrf()))
                .andExpect(status().isNotFound());

        SuggestionView approved = approve(propose(userId, "RECOVERY", TODAY, "2026-11-12T09:00:00Z", 20, null));
        mvc.perform(post("/api/suggestions/" + approved.id() + "/dismiss").with(loginAs(userId)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 했어요는_승인한_일정이_끝난_뒤에만_doneAt을_채운다() throws Exception {
        SuggestionView s = approve(propose(userId, "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 20, null));
        String url = "/api/suggestions/" + s.id() + "/done";

        mvc.perform(post(url).with(loginAs(userId)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        clock.set("2026-11-12T16:30:00+09:00");
        mvc.perform(post(url).with(loginAs(userId)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.doneAt").value("2026-11-12T16:30:00+09:00"))
                .andExpect(jsonPath("$.googleEventId").value(s.registrationKey()));
    }

    // ---------- 승인 계약 ----------

    @Test
    void 승인_시작은_등록_키를_주고_다른_예약과_겹치면_SLOT_CONFLICT() {
        SuggestionView first = propose(userId, "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 30, null);
        SuggestionView overlapping = propose(userId, "PRE_START", TODAY, "2026-11-12T07:15:00Z", 60, null);

        SuggestionView approving = approvals.beginApproval(userId, first.id(), null, null);
        assertThat(approving.status()).isEqualTo("APPROVING");
        assertThat(approving.registrationKey()).startsWith("cd").hasSize(34).matches("[0-9a-v]+");

        assertThatThrownBy(() -> approvals.beginApproval(userId, overlapping.id(), null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SLOT_CONFLICT));
        // 겹치지 않는 시간으로 바꾸면 가능
        SuggestionView moved = approvals.beginApproval(userId, overlapping.id(),
                Instant.parse("2026-11-12T09:00:00Z"), Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(moved.status()).isEqualTo("APPROVING");
        assertThat(moved.start()).isEqualTo(Instant.parse("2026-11-12T09:00:00Z"));
    }

    @Test
    void 확정_실패_뒤_재시도와_되돌리기() {
        SuggestionView s = propose(userId, "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 20, null);
        approvals.beginApproval(userId, s.id(), null, null);
        assertThat(approvals.markFailed(userId, s.id()).status()).isEqualTo("FAILED");
        assertThat(approvals.beginApproval(userId, s.id(), null, null).status()).isEqualTo("APPROVING");
        assertThat(approvals.confirmApproved(userId, s.id(), s.registrationKey()).status()).isEqualTo("APPROVED");

        assertThat(approvals.requireUndoable(userId, s.id()).googleEventId()).isEqualTo(s.registrationKey());
        assertThat(approvals.confirmUndone(userId, s.id()).status()).isEqualTo("UNDONE");
        assertThat(approvals.confirmUndone(userId, s.id()).status()).isEqualTo("UNDONE");
        assertThatThrownBy(() -> approvals.beginApproval(userId, s.id(), null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SUGGESTION_NOT_PROPOSED));
    }

    @Test
    void 만료_시각이_지난_PROPOSED만_EXPIRED로_바꾼다() {
        SuggestionView due = propose(userId, "RECOVERY", TODAY, "2026-11-12T07:00:00Z", 20, null);
        SuggestionView approved = approve(propose(userId, "RECOVERY", TODAY, "2026-11-12T09:00:00Z", 20, null));

        clock.set("2026-11-13T00:00:00+09:00"); // 만료 시각(오늘 끝) 이후
        assertThat(commands.expireDue()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM suggestions WHERE id = ?", String.class, due.id()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM suggestions WHERE id = ?", String.class, approved.id()))
                .isEqualTo("APPROVED");
    }

    // ---------- 도우미 ----------

    /** 만료 시각은 그날 끝(서울 24:00 = 15:00 UTC). */
    private SuggestionView propose(long user, String type, LocalDate date, String startUtc, int minutes,
            String message) {
        Instant start = Instant.parse(startUtc);
        return commands.propose(new NewSuggestion(user, type, date, start, start.plusSeconds(minutes * 60L),
                "PRE_START".equals(type) ? "발표 준비" : "20분 걷기", "PRE_START".equals(type) ? null : "WALK", null,
                "PRE_START".equals(type) ? "RISK_WEEK" : "LOW_RECOVERY",
                "PRE_START".equals(type) ? "load-v1" : "recovery-v1", message, false, message != null, null, null,
                date.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant()));
    }

    private SuggestionView approve(SuggestionView s) {
        approvals.beginApproval(s.userId(), s.id(), null, null);
        return approvals.confirmApproved(s.userId(), s.id(), s.registrationKey());
    }

    private long insertUser(String step, String onboardingCompletedUtc) {
        String sub = "suggestion-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, ?, ?, '2026-11-01 00:00:00', '2026-11-01 00:00:00')",
                sub, step, onboardingCompletedUtc);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }
}
