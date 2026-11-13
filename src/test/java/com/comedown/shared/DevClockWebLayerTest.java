package com.comedown.shared;

import static com.comedown.support.TestLogin.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.SecurityConfig;
import com.comedown.shared.security.dev.DevSecurityConfig;
import com.comedown.shared.time.AdjustableClock;
import com.comedown.shared.time.ServiceTime;
import com.comedown.shared.time.dev.DevClockController;
import com.comedown.support.MutableClock;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** local 프로필 전용 서버 날짜 이동({@code POST /api/dev/clock}). 실제 시계 대신 고정 시계 위의 조정 시계를 쓴다. */
@WebMvcTest(controllers = DevClockController.class)
@Import({SecurityConfig.class, DevSecurityConfig.class, ErrorResponseWriter.class, ServiceTime.class,
        DevClockWebLayerTest.ClockConfig.class})
@ActiveProfiles("local")
class DevClockWebLayerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        AdjustableClock adjustableClock() {
            return new AdjustableClock(MutableClock.at("2026-11-12T07:30:00+09:00"));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AdjustableClock clock;

    @Autowired
    ServiceTime serviceTime;

    @BeforeEach
    void reset() {
        clock.moveTo(LocalDate.of(2026, 11, 12));
    }

    private ResultActions move(String body) throws Exception {
        return mvc.perform(post("/api/dev/clock").with(csrf()).with(loginAs(1))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 서버_기준_날짜를_옮기고_서비스_시간이_따라간다() throws Exception {
        move("{\"date\":\"2026-12-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-12-01"))
                .andExpect(jsonPath("$.now").value("2026-12-01T07:30:00+09:00"))
                .andExpect(jsonPath("$.offsetDays").value(19));

        assertThat(serviceTime.today()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void 실제_오늘을_보내면_원래대로_돌아간다() throws Exception {
        move("{\"date\":\"2026-12-01\"}").andExpect(status().isOk());

        move("{\"date\":\"2026-11-12\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetDays").value(0));
    }

    @Test
    void 날짜가_없으면_400() throws Exception {
        move("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"));
    }

    @Test
    void 날짜_형식이_틀리면_400() throws Exception {
        move("{\"date\":\"12/01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 로그인이_필요하다() throws Exception {
        mvc.perform(post("/api/dev/clock").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-12-01\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(clock.offsetDays()).isZero();
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mvc.perform(post("/api/dev/clock").with(loginAs(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-12-01\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        assertThat(clock.offsetDays()).isZero();
    }
}
