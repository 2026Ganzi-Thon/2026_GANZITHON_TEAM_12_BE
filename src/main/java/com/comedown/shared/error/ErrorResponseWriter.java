package com.comedown.shared.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controller 밖(보안 필터 등)에서 명세 형식의 오류 응답을 쓴다.
 */
@Component
public class ErrorResponseWriter {

    private final JsonMapper jsonMapper;

    public ErrorResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
