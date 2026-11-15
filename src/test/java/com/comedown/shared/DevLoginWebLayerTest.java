package com.comedown.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.CsrfController;
import com.comedown.shared.security.SecurityConfig;
import com.comedown.shared.security.dev.DevLoginController;
import com.comedown.shared.security.dev.DevSecurityConfig;
import com.comedown.shared.time.ServiceTime;
import com.comedown.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** local 프로필 전용 테스트 로그인. */
@WebMvcTest(controllers = {DevLoginController.class, SharedProbeController.class, CsrfController.class})
@Import({SecurityConfig.class, DevSecurityConfig.class, ErrorResponseWriter.class, ServiceTime.class,
        FixedClockTestConfig.class})
@ActiveProfiles("local")
class DevLoginWebLayerTest {

    @Autowired
    MockMvc mvc;

    private MockHttpSession login(long userId, MockHttpSession session) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/dev/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + userId + "}"))
                .andExpect(status().isNoContent())
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void 테스트_로그인_후_같은_세션으로_CurrentUser를_받는다() throws Exception {
        MockHttpSession session = login(42, new MockHttpSession());

        // 세션 principal 색인 이름은 users.id 문자열
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getName()).isEqualTo("42");
        mvc.perform(get("/api/test/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void 로그인하면_기존_세션_id를_바꾼다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        MockHttpSession after = login(7, session);

        assertThat(after.getId()).isNotEqualTo(before);
    }

    @Test
    void userId가_양수가_아니면_400() throws Exception {
        mvc.perform(post("/api/dev/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("userId"));
    }

    @Test
    void CSRF_토큰이_없으면_403() throws Exception {
        mvc.perform(post("/api/dev/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void 다른_개발_API는_로그인이_필요하다() throws Exception {
        mvc.perform(post("/api/dev/clock").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-12-01\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- 로그아웃(POST /api/auth/logout, 공통 체인) ----

    @Test
    void 로그아웃하면_204이고_세션이_끊겨_다시_로그인이_필요하다() throws Exception {
        MockHttpSession session = login(42, new MockHttpSession());

        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("SESSION", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/test/me").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 로그아웃도_CSRF_토큰이_없으면_403이고_세션을_유지한다() throws Exception {
        MockHttpSession session = login(42, new MockHttpSession());

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/test/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42));
    }

    @Test
    void 로그인하지_않았어도_로그아웃은_204() throws Exception {
        mvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void GET_로그아웃은_로그아웃하지_않는다() throws Exception {
        MockHttpSession session = login(42, new MockHttpSession());

        mvc.perform(get("/api/auth/logout").session(session));

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void 일반_API는_여전히_로그인이_필요하다() throws Exception {
        mvc.perform(get("/api/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
