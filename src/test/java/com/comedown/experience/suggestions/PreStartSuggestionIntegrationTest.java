package com.comedown.experience.suggestions;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventWriter;
import com.comedown.calendar.contract.CalendarEventWriter.DeleteResult;
import com.comedown.experience.suggestions.application.ApprovalRecheckJob;
import com.comedown.recommendation.contract.NewSuggestion;
import com.comedown.recommendation.contract.SuggestionApprovalCommands;
import com.comedown.recommendation.contract.SuggestionCommands;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MutableClock;
import com.comedown.support.MySqlContainerTestConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PRE_START 제안 생성·다른 시간·승인(구글 등록)·되돌리기. 구글은 대체 응답이다.
 * 기준 시각 2026-11-12(목) 07:30 서울. 위험 주 11-16~11-22, 과제 마감 11-18(수) 14:00 → 제안은 전날 11-17 07:00.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class PreStartSuggestionIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApprovalRecheckJob recheckJob;

    @Autowired
    SuggestionCommands suggestionCommands;

    @Autowired
    SuggestionApprovalCommands approvalCommands;

    @MockitoBean
    CalendarEventWriter writer;

    @MockitoBean
    CalendarCredentialProvider credentials;

    @BeforeEach
    void setUp() {
        clock.set(FixedClockTestConfig.DEFAULT_NOW);
        reset(writer, credentials);
        when(credentials.credentialFor(anyLong())).thenReturn(new CalendarCredential("token"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM suggestions WHERE user_id IN (SELECT id FROM users WHERE google_sub LIKE 'ps-%')");
        jdbc.update("DELETE FROM users WHERE google_sub LIKE 'ps-%'");
    }

    @Test
    void 위험_주의_과제_마감마다_전날_빈_시간에_제안을_하나_만든다() throws Exception {
        long user = riskUser();

        generate(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].type").value("PRE_START"))
                .andExpect(jsonPath("$.suggestions[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$.suggestions[0].targetDate").value("2026-11-17"))
                .andExpect(jsonPath("$.suggestions[0].start").value("2026-11-17T07:00:00+09:00"))
                .andExpect(jsonPath("$.suggestions[0].durationMin").value(30))
                .andExpect(jsonPath("$.suggestions[0].reasonCode").value("RISK_WEEK"))
                .andExpect(jsonPath("$.suggestions[0].riskWeekStart").value("2026-11-16"))
                .andExpect(jsonPath("$.suggestions[0].message.fallback").value(true));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM suggestions WHERE user_id = ?",
                java.sql.Timestamp.class, user).toLocalDateTime().toString()).isEqualTo("2026-11-17T15:00"); // 제안 날짜가 지나면 만료

        generate(user).andExpect(jsonPath("$.suggestions.length()").value(1)); // 다시 불러도 중복 없음
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    void 건너뛰거나_되돌린_마감은_다시_제안하지_않고_만료된_마감만_다시_제안한다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);

        jdbc.update("UPDATE suggestions SET status = 'DISMISSED' WHERE id = ?", id);
        generate(user).andExpect(jsonPath("$.suggestions.length()").value(0));
        jdbc.update("UPDATE suggestions SET status = 'UNDONE' WHERE id = ?", id);
        generate(user).andExpect(jsonPath("$.suggestions.length()").value(0));
        jdbc.update("UPDATE suggestions SET status = 'FAILED' WHERE id = ?", id); // 재시도할 수 있으므로 새로 만들지 않음
        generate(user).andExpect(jsonPath("$.suggestions.length()").value(0));
        assertThat(count(user)).isEqualTo(1);

        jdbc.update("UPDATE suggestions SET status = 'EXPIRED' WHERE id = ?", id);
        generate(user).andExpect(jsonPath("$.suggestions.length()").value(1));
        assertThat(count(user)).isEqualTo(2);
    }

    @Test
    void 일정이_있는_시간과_10분_간격을_피하고_RECOVERY는_아직_준비_중이다() throws Exception {
        long user = riskUser();
        event(user, "busy", "2026-11-16 22:00:00", "2026-11-16 23:00:00", "CLASS"); // 11-17 07:00~08:00 서울

        generate(user).andExpect(jsonPath("$.suggestions[0].start").value("2026-11-17T08:30:00+09:00"));

        mvc.perform(post("/api/suggestions/generate").with(loginAs(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RECOVERY\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CALCULATION_POLICY_PENDING"));
    }

    @Test
    void 위험_주가_아니거나_과제가_아니면_제안하지_않는다() throws Exception {
        long safe = newUser();
        weekLoad(safe, "WATCH");
        event(safe, "a1", "2026-11-18 05:00:00", "2026-11-18 06:00:00", "ASSIGNMENT");
        long exam = newUser();
        weekLoad(exam, "RISK");
        event(exam, "e1", "2026-11-18 05:00:00", "2026-11-18 06:00:00", "EXAM");

        generate(safe).andExpect(jsonPath("$.suggestions.length()").value(0));
        generate(exam).andExpect(jsonPath("$.suggestions.length()").value(0));
    }

    @Test
    void 다른_시간_보기는_같은_날_같은_길이로_최대_5개() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);

        mvc.perform(get("/api/suggestions/" + id + "/slots").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(5))
                .andExpect(jsonPath("$.slots[0].start").value("2026-11-17T07:00:00+09:00"))
                .andExpect(jsonPath("$.slots[0].end").value("2026-11-17T07:30:00+09:00"));
    }

    @Test
    void 승인하면_등록_키로_구글에_등록하고_APPROVED가_된다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        String key = registrationKey(id);
        when(writer.register(any(), any())).thenAnswer(i -> ((CalendarEventWriter.NewEvent) i.getArgument(1))
                .registrationKey());

        approve(user, id, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.googleEventId").value(key));
        approve(user, id, null).andExpect(status().isOk()) // 같은 승인 재시도는 원래 결과
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(writer).register(any(), eq(new CalendarEventWriter.NewEvent(key, "과제 미리 시작하기",
                OffsetDateTime.parse("2026-11-17T07:00:00+09:00").toInstant(),
                OffsetDateTime.parse("2026-11-17T07:30:00+09:00").toInstant())));
    }

    @Test
    void 시간을_바꿔_승인하려면_같은_날_같은_길이로_빈_시간이어야_한다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        when(writer.register(any(), any())).thenAnswer(i -> ((CalendarEventWriter.NewEvent) i.getArgument(1))
                .registrationKey());

        approve(user, id, range("2026-11-17T03:00:00+09:00", "2026-11-17T03:30:00+09:00"))
                .andExpect(status().isBadRequest()); // 수면 시간
        approve(user, id, range("2026-11-17T09:00:00+09:00", "2026-11-17T10:00:00+09:00"))
                .andExpect(status().isBadRequest()); // 길이가 다름
        approve(user, id, range("2026-11-18T09:00:00+09:00", "2026-11-18T09:30:00+09:00"))
                .andExpect(status().isBadRequest()); // 다른 날
        verify(writer, never()).register(any(), any());

        approve(user, id, range("2026-11-17T17:00:00+09:00", "2026-11-17T17:30:00+09:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.start").value("2026-11-17T17:00:00+09:00"));
    }

    @Test
    void 제안한_시간에_그_사이_일정이_생겼으면_SLOT_CONFLICT이고_PROPOSED로_남는다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user); // 11-17 07:00~07:30 서울
        registerSucceeds();
        // 제안을 만든 뒤 동기화된 일정: 11-17 07:15~08:00 서울
        event(user, "later-" + user, "2026-11-16 22:15:00", "2026-11-16 23:00:00", "CLASS");

        approve(user, id, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"));
        assertThat(statusOf(id)).isEqualTo("PROPOSED");
        verify(writer, never()).register(any(), any());

        // "다른 시간 보기"로 고른 빈 시간으로는 승인된다.
        approve(user, id, range("2026-11-17T17:00:00+09:00", "2026-11-17T17:30:00+09:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void 맞닿기만_한_일정과_시간을_막지_않는_일정은_제안한_시간의_충돌이_아니다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user); // 11-17 07:00~07:30 서울
        registerSucceeds();
        event(user, "touch-" + user, "2026-11-16 22:30:00", "2026-11-16 23:30:00", "CLASS"); // 07:30~08:30, 맞닿음
        event(user, "allday-" + user, "2026-11-16 15:00:00", "2026-11-17 15:00:00", true, "confirmed", "opaque");
        event(user, "cancelled-" + user, "2026-11-16 22:00:00", "2026-11-16 23:00:00", false, "cancelled", "opaque");
        event(user, "free-" + user, "2026-11-16 22:00:00", "2026-11-16 23:00:00", false, "confirmed", "transparent");

        approve(user, id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void 제안한_시간이_이미_지났으면_SLOT_CONFLICT이고_구글에_보내지_않는다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user); // 11-17 07:00~07:30 서울
        registerSucceeds();
        clock.set("2026-11-17T07:10:00+09:00"); // 시작 시각은 지났고 만료(그날 끝) 전

        approve(user, id, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"));
        assertThat(statusOf(id)).isEqualTo("PROPOSED");
        verify(writer, never()).register(any(), any());

        // 아직 남은 시간으로 바꾸면 승인된다.
        approve(user, id, range("2026-11-17T17:00:00+09:00", "2026-11-17T17:30:00+09:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void 확정_실패_뒤_재시도도_제안한_시간을_재확인한다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.FAILURE, "x"));
        approve(user, id, null).andExpect(status().isBadGateway());
        assertThat(statusOf(id)).isEqualTo("FAILED");

        reset(writer);
        registerSucceeds();
        event(user, "later-" + user, "2026-11-16 22:00:00", "2026-11-16 22:20:00", "CLASS"); // 07:00~07:20 서울
        approve(user, id, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"));
        assertThat(statusOf(id)).isEqualTo("FAILED");
        verify(writer, never()).register(any(), any());
    }

    @Test
    void 승인_중인_제안의_재승인은_시간을_재확인하지_않고_같은_등록_키로_이어_간다() throws Exception {
        long user = riskUser();
        long id = uncertainApproval(user);
        String key = registrationKey(id);
        // 이전 시도가 구글에 반영됐을 수 있다. 그 사이 일정이 생겼거나 시간이 지났어도 결과를 확정해야 한다.
        event(user, "later-" + user, "2026-11-16 22:15:00", "2026-11-16 23:00:00", "CLASS");
        clock.set("2026-11-17T07:10:00+09:00");
        registerSucceeds();

        approve(user, id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.googleEventId").value(key));
    }

    @Test
    void 등록이_거절되면_FAILED이고_다시_승인할_수_있다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.FAILURE, "x"));

        approve(user, id, null).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CALENDAR_API_ERROR"));
        assertThat(statusOf(id)).isEqualTo("FAILED");

        reset(writer);
        when(writer.register(any(), any())).thenAnswer(i -> ((CalendarEventWriter.NewEvent) i.getArgument(1))
                .registrationKey());
        approve(user, id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void 캘린더_자격이_없으면_FAILED로_확정하고_오류를_돌려준다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        doThrow(new ApiException(ErrorCode.CALENDAR_NOT_CONNECTED)).when(credentials).credentialFor(user);

        approve(user, id, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CALENDAR_NOT_CONNECTED"));
        assertThat(statusOf(id)).isEqualTo("FAILED");
        verify(writer, never()).register(any(), any());
    }

    @Test
    void 타임아웃이면_실패로_확정하지_않고_등록_키로_확인한다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        String key = registrationKey(id);

        // 1) 응답은 못 받았지만 구글에는 반영됨 → 조회로 확인해 APPROVED
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));
        when(writer.exists(any(), eq(key))).thenReturn(true);
        approve(user, id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.googleEventId").value(key));
    }

    @Test
    void 결과를_알_수_없으면_202_APPROVING으로_두고_재확인_작업이_끝낸다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));
        when(writer.exists(any(), any())).thenReturn(false);

        approve(user, id, null).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("APPROVING"));
        assertThat(statusOf(id)).isEqualTo("APPROVING");

        // 아직 오래되지 않았으면 건드리지 않는다.
        assertThat(recheckJob.recheckStale()).isZero();

        // 시간이 지나 재확인: 이번에는 같은 등록 키로 등록이 성공한다.
        clock.set(OffsetDateTime.parse(FixedClockTestConfig.DEFAULT_NOW).plusMinutes(3).toInstant());
        reset(writer);
        when(writer.register(any(), any())).thenAnswer(i -> ((CalendarEventWriter.NewEvent) i.getArgument(1))
                .registrationKey());
        assertThat(recheckJob.recheckStale()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        verify(writer).register(any(), org.mockito.ArgumentMatchers.argThat(
                e -> e.registrationKey().equals(registrationKey(id))));
    }

    @Test
    void 되돌리기는_앱이_만든_일정만_지우고_UNDONE이_된다() throws Exception {
        long user = riskUser();
        long id = suggestionId(user);
        when(writer.register(any(), any())).thenAnswer(i -> ((CalendarEventWriter.NewEvent) i.getArgument(1))
                .registrationKey());

        mvc.perform(post("/api/suggestions/" + id + "/undo").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        approve(user, id, null).andExpect(status().isOk());
        when(writer.deleteAppEvent(any(), any())).thenReturn(DeleteResult.NOT_APP_OWNED);
        mvc.perform(post("/api/suggestions/" + id + "/undo").with(loginAs(user)).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo("APPROVED");

        when(writer.deleteAppEvent(any(), any())).thenReturn(DeleteResult.ALREADY_GONE);
        mvc.perform(post("/api/suggestions/" + id + "/undo").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDONE"));
        mvc.perform(post("/api/suggestions/" + id + "/undo").with(loginAs(user)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNDONE")); // 반복은 같은 결과
    }

    @Test
    void 남의_제안은_404() throws Exception {
        long owner = riskUser();
        long id = suggestionId(owner);
        long other = newUser();

        approve(other, id, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/suggestions/" + id + "/slots").with(loginAs(other))).andExpect(status().isNotFound());
        mvc.perform(post("/api/suggestions/" + id + "/undo").with(loginAs(other)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---------- 도우미 ----------

    @Test
    void 재확인_때_캘린더_자격을_얻지_못해도_FAILED로_확정하지_않는다() throws Exception {
        long user = riskUser();
        long id = uncertainApproval(user);
        doThrow(new ApiException(ErrorCode.CALENDAR_REAUTH_REQUIRED)).when(credentials).credentialFor(user);

        assertThat(recheckJob.recheckStale()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("APPROVING");
        verify(writer, never()).register(any(), any());

        // 자격이 돌아오면 같은 등록 키로 이어서 끝낸다. 방금 재확인한 제안은 recheck-after(2분)가 지난 뒤에 다시 본다.
        reset(credentials);
        when(credentials.credentialFor(anyLong())).thenReturn(new CalendarCredential("token"));
        registerSucceeds();
        assertThat(recheckJob.recheckStale()).isZero();
        assertThat(statusOf(id)).isEqualTo("APPROVING");

        clock.advance(Duration.ofMinutes(3));
        assertThat(recheckJob.recheckStale()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    @Test
    void 풀리지_않는_승인_중_제안이_한_번의_재확인_수를_채워도_다음에는_그_뒤의_제안을_본다() throws Exception {
        long user = newUser();
        int batch = 20; // ApprovalRecheckJob.BATCH
        // 승인 중인 채 오래된 제안 21개. 갱신 시각 21:00~21:20 UTC(기준 시각 22:30 UTC), 번호가 클수록 최근이다.
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i <= batch; i++) {
            Instant start = Instant.parse("2026-11-17T00:00:00Z").plus(Duration.ofMinutes(30L * i));
            long id = suggestionCommands.propose(new NewSuggestion(user, "PRE_START", LocalDate.of(2026, 11, 17),
                    start, start.plus(Duration.ofMinutes(20)), "과제 미리 시작하기", null, null, "RISK_WEEK", "load-v1",
                    null, false, false, null, null, Instant.parse("2026-11-17T15:00:00Z"))).id();
            approvalCommands.startApproval(user, id, null, null);
            jdbc.update("UPDATE suggestions SET updated_at = ? WHERE id = ?",
                    "2026-11-11 21:%02d:00.000000".formatted(i), id);
            ids.add(id);
        }
        long newest = ids.get(batch);
        String newestKey = registrationKey(newest);
        // 가장 최근 제안만 구글 등록이 확인되고, 나머지는 계속 결과를 알 수 없다.
        when(writer.register(any(), any())).thenAnswer(i -> {
            String key = ((CalendarEventWriter.NewEvent) i.getArgument(1)).registrationKey();
            if (key.equals(newestKey)) {
                return key;
            }
            throw new ExternalServiceException(Kind.UNCERTAIN, "timeout");
        });
        when(writer.exists(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));

        assertThat(recheckJob.recheckStale()).isEqualTo(batch); // 오래된 20개: 모두 풀리지 않음
        assertThat(statusOf(newest)).isEqualTo("APPROVING");

        assertThat(recheckJob.recheckStale()).isEqualTo(1); // 방금 본 20개는 뒤로 밀려 남은 1개 차례가 온다
        assertThat(statusOf(newest)).isEqualTo("APPROVED");
        assertThat(recheckJob.recheckStale()).isZero();

        clock.advance(Duration.ofMinutes(3)); // 밀린 제안은 recheck-after가 지나면 다시 본다
        assertThat(recheckJob.recheckStale()).isEqualTo(batch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suggestions WHERE user_id = ? AND status = 'APPROVING'",
                Integer.class, user)).isEqualTo(batch);
    }

    @Test
    void 재확인_때_등록이_거절돼도_이전_시도가_만든_일정이_있으면_APPROVED() throws Exception {
        long user = riskUser();
        long id = uncertainApproval(user);
        String key = registrationKey(id);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.FAILURE, "rate limit"));
        when(writer.exists(any(), eq(key))).thenReturn(true);

        assertThat(recheckJob.recheckStale()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT google_event_id FROM suggestions WHERE id = ?", String.class, id))
                .isEqualTo(key);
    }

    @Test
    void 재확인_때_등록이_거절되고_일정이_없다고_확인되면_FAILED() throws Exception {
        long user = riskUser();
        long id = uncertainApproval(user);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.FAILURE, "rejected"));
        when(writer.exists(any(), any())).thenReturn(false);

        assertThat(recheckJob.recheckStale()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("FAILED");
    }

    @Test
    void 재확인_때_등록이_거절되고_일정_확인도_못_하면_APPROVING으로_둔다() throws Exception {
        long user = riskUser();
        long id = uncertainApproval(user);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.UNAUTHORIZED, "401"));
        when(writer.exists(any(), any())).thenThrow(new ExternalServiceException(Kind.UNAUTHORIZED, "401"));

        assertThat(recheckJob.recheckStale()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("APPROVING");
    }

    @Test
    void 승인_중인_제안을_다시_승인해도_확인_전에는_FAILED로_확정하지_않는다() throws Exception {
        long user = riskUser();
        long id = uncertainApproval(user);

        // 자격을 얻지 못하면 오류는 알리되 상태는 그대로다.
        doThrow(new ApiException(ErrorCode.CALENDAR_REAUTH_REQUIRED)).when(credentials).credentialFor(user);
        approve(user, id, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CALENDAR_REAUTH_REQUIRED"));
        assertThat(statusOf(id)).isEqualTo("APPROVING");

        // 등록이 거절되고 일정 확인도 못 하면 확인 중(202)으로 둔다.
        reset(credentials);
        when(credentials.credentialFor(anyLong())).thenReturn(new CalendarCredential("token"));
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.FAILURE, "rate limit"));
        when(writer.exists(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));
        approve(user, id, null).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("APPROVING"));
        assertThat(statusOf(id)).isEqualTo("APPROVING");
    }

    /** 첫 승인이 결과를 모르는 채(202) APPROVING으로 남고, 재확인 대상이 될 만큼 시간이 지난 상태를 만든다. */
    private long uncertainApproval(long user) throws Exception {
        long id = suggestionId(user);
        when(writer.register(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));
        when(writer.exists(any(), any())).thenThrow(new ExternalServiceException(Kind.UNCERTAIN, "timeout"));
        approve(user, id, null).andExpect(status().isAccepted());
        assertThat(statusOf(id)).isEqualTo("APPROVING");
        clock.set(OffsetDateTime.parse(FixedClockTestConfig.DEFAULT_NOW).plusMinutes(3).toInstant());
        reset(writer);
        return id;
    }

    private void registerSucceeds() {
        when(writer.register(any(), any())).thenAnswer(i -> ((CalendarEventWriter.NewEvent) i.getArgument(1))
                .registrationKey());
    }

    private ResultActions generate(long user) throws Exception {
        return mvc.perform(post("/api/suggestions/generate").with(loginAs(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"PRE_START\"}"));
    }

    private ResultActions approve(long user, long id, String body) throws Exception {
        var request = post("/api/suggestions/" + id + "/approve").with(loginAs(user)).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String range(String start, String end) {
        return "{\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
    }

    /** 위험 주(11-16~)에 과제 마감(11-18 14:00)이 있는 사용자. */
    private long riskUser() {
        long user = newUser();
        weekLoad(user, "RISK");
        event(user, "assign-" + user, "2026-11-18 05:00:00", "2026-11-18 06:00:00", "ASSIGNMENT");
        return user;
    }

    private long suggestionId(long user) throws Exception {
        generate(user).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM suggestions WHERE user_id = ?", Long.class, user);
    }

    private String registrationKey(long id) {
        return jdbc.queryForObject("SELECT registration_key FROM suggestions WHERE id = ?", String.class, id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM suggestions WHERE id = ?", String.class, id);
    }

    private int count(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM suggestions WHERE user_id = ?", Integer.class, user);
    }

    private long newUser() {
        String sub = "ps-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (google_sub, onboarding_step, onboarding_completed_at, created_at, "
                + "last_login_at) VALUES (?, 'DONE', '2026-11-01 00:00:00', '2026-09-01 00:00:00', "
                + "'2026-09-01 00:00:00')", sub);
        return jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
    }

    private void weekLoad(long user, String riskLevel) {
        jdbc.update("INSERT INTO weekly_loads (user_id, week_start, total_hours, load_score, deadline_count, "
                + "late_night_count, weekend_hours, rest_days, risk_level, risk_reasons, calculation_version, "
                + "computed_at) VALUES (?, '2026-11-16', 30, 30, 3, 0, 0, 2, ?, '[]', 'load-v1', "
                + "'2026-11-11 00:00:00')", user, riskLevel);
    }

    private void event(long user, String googleId, String startUtc, String endUtc, String category) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, category, synced_at) VALUES (?, ?, ?, ?, FALSE, "
                + "'confirmed', 'opaque', FALSE, ?, '2026-11-11 22:30:00.000000')",
                user, googleId, startUtc, endUtc, category);
    }

    /** 분류 전(category null) 일정을 종일·상태·표시 방식까지 정해 넣는다. */
    private void event(long user, String googleId, String startUtc, String endUtc, boolean allDay,
            String eventStatus, String transparency) {
        jdbc.update("INSERT INTO calendar_events (user_id, google_event_id, start_at, end_at, all_day, "
                + "event_status, transparency, created_by_app, synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, "
                + "'2026-11-11 22:30:00.000000')",
                user, googleId, startUtc, endUtc, allDay, eventStatus, transparency);
    }
}
