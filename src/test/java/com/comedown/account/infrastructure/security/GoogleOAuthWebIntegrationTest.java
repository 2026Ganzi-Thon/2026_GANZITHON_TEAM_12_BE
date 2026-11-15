package com.comedown.account.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comedown.support.FixedClockTestConfig;
import com.comedown.support.MySqlContainerTestConfig;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/** 더미 클라이언트 정보로 OAuth 시작 주소(구글로 보내는 리다이렉트)가 올바르게 만들어지는지 본다. 구글을 호출하지 않는다. */
@SpringBootTest(properties = {
        "comedown.google.client-id=test-client-id",
        "comedown.google.client-secret=test-client-secret"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({MySqlContainerTestConfig.class, FixedClockTestConfig.class})
class GoogleOAuthWebIntegrationTest {

    @Autowired
    MockMvc mvc;

    private URI redirectOf(String path) throws Exception {
        String location = mvc.perform(get(path)).andExpect(status().isFound()).andReturn()
                .getResponse().getHeader("Location");
        return URI.create(location);
    }

    @Test
    void 로그인은_기본_정보_범위만_요청하고_캘린더_범위는_요청하지_않는다() throws Exception {
        URI uri = redirectOf("/oauth2/authorization/google");
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        assertThat(params.getFirst("client_id")).isEqualTo("test-client-id");
        assertThat(java.net.URLDecoder.decode(params.getFirst("scope"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("openid email");
        assertThat(params.getFirst("redirect_uri")).contains("/login/oauth2/code/google");
        assertThat(params.getFirst("include_granted_scopes")).isEqualTo("true");
        assertThat(params.getFirst("access_type")).isNull();
        assertThat(params.getFirst("state")).isNotBlank();
    }

    @Test
    void 캘린더_연결은_일정_범위와_offline_동의_화면을_요청한다() throws Exception {
        URI uri = redirectOf("/oauth2/authorization/google-calendar");
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        assertThat(java.net.URLDecoder.decode(params.getFirst("scope"), java.nio.charset.StandardCharsets.UTF_8))
                .contains("https://www.googleapis.com/auth/calendar.events");
        assertThat(params.getFirst("access_type")).isEqualTo("offline");
        assertThat(params.getFirst("prompt")).isEqualTo("consent");
        assertThat(params.getFirst("include_granted_scopes")).isEqualTo("true");
        assertThat(params.getFirst("redirect_uri")).contains("/login/oauth2/code/google-calendar");
    }

    @Test
    void 로그인하지_않은_API_호출은_여전히_401() throws Exception {
        mvc.perform(get("/api/schedule/weeks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 등록하지_않은_registration은_구글로_보내지_않는다() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/unknown")).andReturn().getResponse()
                .getHeader("Location");

        assertThat(location == null || !location.contains("accounts.google.com")).isTrue();
    }
}
