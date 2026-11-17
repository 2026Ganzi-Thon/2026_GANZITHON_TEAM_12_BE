package com.comedown.integration.openai;

import com.comedown.ai.contract.AiCompletionPort;
import com.comedown.shared.error.ExternalServiceException;
import com.comedown.shared.error.ExternalServiceException.Kind;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * ai의 {@link AiCompletionPort}를 OpenAI Chat Completions API로 구현한다. 전송·타임아웃·응답 형식만 다루고 프롬프트나
 * 대체 문구 같은 제품 규칙은 갖지 않는다. API 키는 설정에서만 읽고 로그·예외 메시지에 넣지 않는다.
 * <p>
 * 화면이 5초 안에 결과를 보여 줘야 하므로(API 명세 6절) 읽기 타임아웃을 5초로 둔다.
 */
@Component
class OpenAiClient implements AiCompletionPort {

    static final String DEFAULT_MODEL = "gpt-4.1-mini";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 두 문장 안팎이면 충분한 길이. */
    static final int MAX_OUTPUT_TOKENS = 200;

    private final RestClient client;
    private final String apiKey;
    private final String model;

    @Autowired
    OpenAiClient(
            @Value("${comedown.openai.api-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${comedown.openai.api-key:}") String apiKey,
            @Value("${comedown.openai.model:}") String model) {
        this(timeoutBuilder(), baseUrl, apiKey, model);
    }

    /** 테스트에서 가짜 서버를 연결한 빌더를 넣을 때 쓴다. */
    OpenAiClient(RestClient.Builder builder, String baseUrl, String apiKey, String model) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
    }

    private static RestClient.Builder timeoutBuilder() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String complete(String system, String user) {
        if (!available()) {
            throw new ExternalServiceException(Kind.FAILURE, "OpenAI API 키가 설정되지 않았습니다.");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "max_completion_tokens", MAX_OUTPUT_TOKENS,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        Map<String, Object> response;
        try {
            response = client.post().uri("/chat/completions")
                    .headers(h -> h.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean uncertain = status == 429 || status >= 500;
            throw new ExternalServiceException(uncertain ? Kind.UNCERTAIN : Kind.FAILURE,
                    "OpenAI 요청 실패(HTTP " + status + ")");
        } catch (RestClientException e) {
            throw new ExternalServiceException(Kind.UNCERTAIN, "OpenAI 응답을 받지 못했습니다.");
        }
        String content = firstContent(response);
        if (content == null || content.isBlank()) {
            throw new ExternalServiceException(Kind.FAILURE, "OpenAI 응답에 문장이 없습니다.");
        }
        return content;
    }

    /** {@code choices[0].message.content}. 모양이 다르면 null. */
    private static String firstContent(Map<String, Object> response) {
        if (response != null && response.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") instanceof String content) {
            return content;
        }
        return null;
    }
}
