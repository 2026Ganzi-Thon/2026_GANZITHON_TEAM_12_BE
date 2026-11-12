package com.comedown.shared;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.shared.error.ErrorResponseWriter;
import com.comedown.shared.security.ComeDownPrincipal;
import com.comedown.shared.security.CsrfController;
import com.comedown.shared.security.SecurityConfig;
import com.comedown.shared.time.ServiceTime;
import com.comedown.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = {SharedProbeController.class, CsrfController.class})
@Import({SecurityConfig.class, ErrorResponseWriter.class, ServiceTime.class, FixedClockTestConfig.class})
class SharedWebLayerTest {

    @Autowired
    MockMvc mvc;

    record TestPrincipal(long userId) implements ComeDownPrincipal {
    }

    private static RequestPostProcessor loginAs(long userId) {
        return authentication(new TestingAuthenticationToken(new TestPrincipal(userId), null, "ROLE_USER"));
    }

    // ---- 인증 ----

    @Test
    void 로그인하지_않으면_401_UNAUTHENTICATED() throws Exception {
        mvc.perform(get("/api/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void 로그인_사용자는_CurrentUser로_id를_받는다() throws Exception {
        mvc.perform(get("/api/test/me").with(loginAs(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7));
    }

    @Test
    void 서비스_사용자가_아닌_principal은_401() throws Exception {
        mvc.perform(get("/api/test/me")
                        .with(authentication(new TestingAuthenticationToken("someone", null, "ROLE_USER"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- CSRF·로그아웃 ----

    /**
     * spring-security-test의 csrf()는 공유 CsrfFilter의 저장소를 테스트용(세션)으로 바꿔 둔다.
     * 실제 쿠키 저장소를 검증하려면 이 테스트 전에 컨텍스트를 새로 만든다.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void csrf_조회는_로그인_없이_204와_XSRF_TOKEN_쿠키() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("XSRF-TOKEN=")));
    }

    @Test
    void csrf_토큰_없는_변경_요청은_403_CSRF_TOKEN_INVALID() throws Exception {
        mvc.perform(post("/api/test/nickname").with(loginAs(7))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"아리\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void 로그아웃은_204() throws Exception {
        mvc.perform(post("/api/auth/logout").with(loginAs(7)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---- 오류 응답 형식 ----

    @Test
    void 정상_요청() throws Exception {
        mvc.perform(post("/api/test/nickname").with(loginAs(7)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"아리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("아리"));
    }

    @Test
    void 검증_실패는_400_VALIDATION_FAILED와_fieldErrors() throws Exception {
        mvc.perform(post("/api/test/nickname").with(loginAs(7)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"" + "가".repeat(21) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("입력값을 확인하세요."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("1~20자여야 합니다."));
    }

    @Test
    void 잘못된_JSON은_400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/test/nickname").with(loginAs(7)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{nickname"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400() throws Exception {
        mvc.perform(post("/api/test/idempotent").with(loginAs(7)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ApiException은_코드의_상태와_본문으로_변환된다() throws Exception {
        mvc.perform(get("/api/test/conflict").with(loginAs(7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"))
                .andExpect(jsonPath("$.message").value("그 시간에 다른 일정이 생겼어요."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void 예상하지_못한_예외는_500_INTERNAL_ERROR() throws Exception {
        mvc.perform(get("/api/test/boom").with(loginAs(7)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void 없는_주소는_404_NOT_FOUND() throws Exception {
        mvc.perform(get("/api/test/nothing").with(loginAs(7)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_405() throws Exception {
        mvc.perform(get("/api/test/nickname").with(loginAs(7)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // ---- 시간 직렬화 ----

    @Test
    void 시각은_서울_오프셋_ISO_8601_날짜는_YYYY_MM_DD_빈값은_null() throws Exception {
        mvc.perform(get("/api/test/time").with(loginAs(7)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"now":"2026-11-12T07:30:00+09:00","today":"2026-11-12","weekStart":"2026-11-09","missing":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }
}
