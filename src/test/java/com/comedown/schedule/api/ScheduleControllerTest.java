package com.comedown.schedule.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.schedule.application.WeeklyLoadService;
import com.comedown.schedule.domain.WeekLoad;
import com.comedown.schedule.domain.WeekLoad.RiskLevel;
import com.comedown.schedule.domain.WeekLoad.RiskReason;
import com.comedown.schedule.domain.WeekLoad.UnavailableReason;
import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.ComeDownPrincipal;
import com.comedown.shared.security.SecurityConfig;
import com.comedown.shared.time.ServiceTime;
import com.comedown.support.FixedClockTestConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = ScheduleController.class)
@Import({SecurityConfig.class, ErrorResponseWriter.class, ServiceTime.class, FixedClockTestConfig.class})
class ScheduleControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WeeklyLoadService service;

    record TestPrincipal(long userId) implements ComeDownPrincipal {
    }

    private static RequestPostProcessor loginAs(long userId) {
        return authentication(new TestingAuthenticationToken(new TestPrincipal(userId), null, "ROLE_USER"));
    }

    private static WeekLoad load(LocalDate weekStart) {
        return new WeekLoad(weekStart, new BigDecimal("41.5"), new BigDecimal("52.0"), new BigDecimal("25.0"), null,
                3, 2, new BigDecimal("6.0"), 0, RiskLevel.RISK,
                List.of(RiskReason.LOAD_HIGH, RiskReason.DEADLINES, RiskReason.NO_REST_DAY), "load-v1");
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").param("count", "4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    @Test
    void 인증_사용자의_주간_부하를_명세_형식으로_돌려준다() throws Exception {
        when(service.weeks(eq(7L), eq(LocalDate.of(2026, 11, 16)), eq(4)))
                .thenReturn(List.of(load(LocalDate.of(2026, 11, 16))));

        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").param("count", "4").with(loginAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks[0].weekStart").value("2026-11-16"))
                .andExpect(jsonPath("$.weeks[0].weekEnd").value("2026-11-22"))
                .andExpect(jsonPath("$.weeks[0].totalHours").value(41.5))
                .andExpect(jsonPath("$.weeks[0].loadScore").value(52.0))
                .andExpect(jsonPath("$.weeks[0].vsUsualPct").value(25.0))
                .andExpect(jsonPath("$.weeks[0].deadlineCount").value(3))
                .andExpect(jsonPath("$.weeks[0].lateNightCount").value(2))
                .andExpect(jsonPath("$.weeks[0].restDays").value(0))
                .andExpect(jsonPath("$.weeks[0].riskLevel").value("RISK"))
                .andExpect(jsonPath("$.weeks[0].riskReasons[0]").value("LOAD_HIGH"))
                .andExpect(jsonPath("$.weeks[0].vsUsualUnavailableReason").doesNotExist())
                .andExpect(jsonPath("$.weeks[0].weekendHours").doesNotExist());
    }

    @Test
    void 사용자_id는_본문이나_쿼리가_아니라_인증에서만_가져온다() throws Exception {
        when(service.weeks(anyLong(), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").param("count", "1")
                        .param("userId", "999").with(loginAs(7)))
                .andExpect(status().isOk());

        verify(service).weeks(eq(7L), eq(LocalDate.of(2026, 11, 16)), eq(1));
    }

    @Test
    void 계산_불가_사유를_그대로_내려준다() throws Exception {
        WeekLoad insufficient = new WeekLoad(LocalDate.of(2026, 11, 9), BigDecimal.ZERO, BigDecimal.ZERO, null,
                UnavailableReason.INSUFFICIENT_DATA, 0, 0, BigDecimal.ZERO, 7, RiskLevel.NONE, List.of(), "load-v1");
        when(service.weeks(eq(7L), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of(insufficient));

        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-09").param("count", "1").with(loginAs(7)))
                .andExpect(jsonPath("$.weeks[0].vsUsualPct").doesNotExist())
                .andExpect(jsonPath("$.weeks[0].vsUsualUnavailableReason").value("INSUFFICIENT_DATA"));
    }

    @Test
    void count가_범위를_벗어나면_400() throws Exception {
        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").param("count", "0").with(loginAs(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").param("count", "53").with(loginAs(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").with(loginAs(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(service);
    }

    @Test
    void count는_52주까지_받는다() throws Exception {
        when(service.weeks(eq(7L), eq(LocalDate.of(2026, 11, 16)), eq(52))).thenReturn(List.of());

        mvc.perform(get("/api/schedule/weeks").param("from", "2026-11-16").param("count", "52").with(loginAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks.length()").value(0));
    }

    @Test
    void 날짜_형식이_틀리거나_빠지면_400() throws Exception {
        mvc.perform(get("/api/schedule/weeks").param("from", "내일").param("count", "4").with(loginAs(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/schedule/weeks").param("count", "4").with(loginAs(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(service);
    }
}
