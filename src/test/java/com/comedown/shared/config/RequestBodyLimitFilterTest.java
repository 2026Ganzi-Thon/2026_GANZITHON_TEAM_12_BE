package com.comedown.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.comedown.shared.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(
            List.of(new RequestBodyLimit("/api/limited", 10)), new ErrorResponseWriter(JsonMapper.builder().build()));

    /** Content-Length를 모르는(chunked) 요청. */
    private static MockHttpServletRequest chunked(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void Content_Length가_상한을_넘으면_읽지_않고_400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/limited");
        request.setContent("12345678901".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new AssertionError("다음 필터로 넘어가면 안 된다");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("VALIDATION_FAILED").contains("요청 본문이 너무 커요.");
    }

    @Test
    void 길이를_모르는_요청은_읽는_도중_상한을_넘으면_실패한다() {
        MockHttpServletRequest request = chunked("/api/limited", "12345678901");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodyLimitFilter.BodyTooLargeException.class);
    }

    @Test
    void 상한_이내이거나_등록하지_않은_경로는_그대로_통과() throws Exception {
        byte[][] read = new byte[2][];
        filter.doFilter(chunked("/api/limited", "1234567890"), new MockHttpServletResponse(),
                (req, res) -> read[0] = req.getInputStream().readAllBytes());
        filter.doFilter(chunked("/api/other", "x".repeat(100)), new MockHttpServletResponse(),
                (req, res) -> read[1] = req.getInputStream().readAllBytes());

        assertThat(read[0]).hasSize(10);
        assertThat(read[1]).hasSize(100);
    }
}
