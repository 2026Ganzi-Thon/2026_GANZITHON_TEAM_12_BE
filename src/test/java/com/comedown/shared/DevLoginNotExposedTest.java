package com.comedown.shared;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.experience.devseed.api.DevSeedController;
import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.ComeDownPrincipal;
import com.comedown.shared.security.SecurityConfig;
import com.comedown.shared.security.dev.DevLoginController;
import com.comedown.shared.security.dev.DevSecurityConfig;
import com.comedown.shared.time.ServiceTime;
import com.comedown.shared.time.dev.DevClockController;
import com.comedown.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/** local 프로필이 아니면 테스트 로그인·시계 이동·시드가 등록되지 않는다(운영 노출 방지). */
@WebMvcTest(controllers = {DevLoginController.class, DevClockController.class, DevSeedController.class,
        SharedProbeController.class})
@Import({SecurityConfig.class, DevSecurityConfig.class, ErrorResponseWriter.class, ServiceTime.class,
        FixedClockTestConfig.class})
class DevLoginNotExposedTest {

    @Autowired
    MockMvc mvc;

    record TestPrincipal(long userId) implements ComeDownPrincipal {
    }

    @Test
    void 비로그인_요청은_401() throws Exception {
        mvc.perform(post("/api/dev/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 로그인한_요청도_경로가_없어_404() throws Exception {
        mvc.perform(post("/api/dev/login").with(csrf())
                        .with(authentication(new TestingAuthenticationToken(new TestPrincipal(1), null, "ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 시계_이동과_시드도_경로가_없어_404() throws Exception {
        TestingAuthenticationToken login = new TestingAuthenticationToken(new TestPrincipal(1), null, "ROLE_USER");
        mvc.perform(post("/api/dev/clock").with(csrf()).with(authentication(login))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-12-01\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/dev/seed").with(csrf()).with(authentication(login))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"DEMO_OVERLOAD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
