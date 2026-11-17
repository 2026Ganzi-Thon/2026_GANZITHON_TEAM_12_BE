package com.comedown.integration.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiClientTest {

    private static final String BASE = "http://openai.test/v1";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiClient client = new OpenAiClient(builder, BASE, "test-key", "test-model");

    @Test
    void 규칙과_요청을_보내고_첫_번째_문장을_돌려준다() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.max_completion_tokens").value(200))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("규칙"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("사실"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"평소와 비슷해요."}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.complete("규칙", "사실")).isEqualTo("평소와 비슷해요.");
        server.verify();
    }

    @Test
    void 키가_없으면_쓸_수_없고_호출하지_않는다() {
        OpenAiClient noKey = new OpenAiClient(builder, BASE, "  ", "");

        assertThat(noKey.available()).isFalse();
        assertThat(noKey.model()).isEqualTo("gpt-4.1-mini"); // 모델을 비워 두면 기본값
        assertThatThrownBy(() -> noKey.complete("규칙", "사실"))
                .isInstanceOfSatisfying(ExternalServiceException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.FAILURE));
        server.verify(); // 요청 없음
        assertThat(client.available()).isTrue();
    }

    @Test
    void 사용량_제한과_서버_오류는_UNCERTAIN_그_외_거절은_FAILURE이고_키를_메시지에_넣지_않는다() {
        server.expect(requestTo(BASE + "/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(BASE + "/chat/completions")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BASE + "/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"error\":{\"message\":\"Incorrect API key provided: test-key\"}}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("규칙", "사실"))
                .isInstanceOfSatisfying(ExternalServiceException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNCERTAIN));
        assertThatThrownBy(() -> client.complete("규칙", "사실"))
                .isInstanceOfSatisfying(ExternalServiceException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNCERTAIN));
        assertThatThrownBy(() -> client.complete("규칙", "사실"))
                .isInstanceOfSatisfying(ExternalServiceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.FAILURE);
                    assertThat(e.getMessage()).doesNotContain("test-key");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void 응답에_문장이_없으면_FAILURE() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("규칙", "사실")).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> client.complete("규칙", "사실")).isInstanceOf(ExternalServiceException.class);
    }
}
