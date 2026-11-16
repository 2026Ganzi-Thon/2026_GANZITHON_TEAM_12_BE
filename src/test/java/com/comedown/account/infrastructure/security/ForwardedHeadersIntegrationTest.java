package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 프록시(로드밸런서·Cloud Run 등) 뒤에서 실행할 때의 주소 복원. TLS는 프록시에서 끝나고 앱은 HTTP로 받으므로,
 * {@code server.forward-headers-strategy=framework}일 때만 {@code X-Forwarded-*} 헤더로 원래 주소(https·호스트)를 쓴다.
 * 구글에 보내는 리디렉션 URI와 쿠키의 Secure 여부가 여기에 달려 있다.
 */
class ForwardedHeadersIntegrationTest {

    private static final String PUBLIC_HOST = "come-down.example.com";

    private static String redirectUriOf(MockMvc mvc, String registration) throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/" + registration)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", PUBLIC_HOST)
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("https://accounts.google.com/");
        String raw = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("redirect_uri");
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    @Nested
    @SpringBootTest(properties = {
            "comedown.google.client-id=test-client-id",
            "comedown.google.client-secret=test-client-secret",
            "server.forward-headers-strategy=framework"})
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
    class 프록시_헤더를_쓰도록_켰을_때 {

        @Autowired
        MockMvc mvc;

        @Test
        void 구글에_보내는_리디렉션_URI는_프록시가_알려준_https_주소다() throws Exception {
            assertThat(redirectUriOf(mvc, "google"))
                    .isEqualTo("https://" + PUBLIC_HOST + "/login/oauth2/code/google");
            assertThat(redirectUriOf(mvc, "google-calendar"))
                    .isEqualTo("https://" + PUBLIC_HOST + "/login/oauth2/code/google-calendar");
        }

        @Test
        void https로_들어온_요청의_CSRF_쿠키는_Secure다() throws Exception {
            mvc.perform(get("/api/auth/csrf").header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", PUBLIC_HOST))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().secure("XSRF-TOKEN", true));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "comedown.google.client-id=test-client-id",
            "comedown.google.client-secret=test-client-secret"})
    @ActiveProfiles("test")
    @AutoConfigureMockMvc
    @Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
    class 기본값일_때 {

        @Autowired
        MockMvc mvc;

        @Test
        void 프록시_헤더를_믿지_않고_요청이_실제로_들어온_주소를_쓴다() throws Exception {
            assertThat(redirectUriOf(mvc, "google")).isEqualTo("http://localhost/login/oauth2/code/google");
        }

        @Test
        void http_요청의_CSRF_쿠키는_Secure가_아니다() throws Exception {
            mvc.perform(get("/api/auth/csrf").header("X-Forwarded-Proto", "https"))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().secure("XSRF-TOKEN", false));
        }
    }
}
