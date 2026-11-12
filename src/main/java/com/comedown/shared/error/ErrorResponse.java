package com.comedown.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 명세의 오류 응답 본문. {@code fieldErrors}는 400 검증 실패일 때만 포함한다.
 */
public record ErrorResponse(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> fieldErrors
) {

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.defaultMessage(), null);
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null);
    }

    public static ErrorResponse validation(List<FieldError> fieldErrors) {
        return new ErrorResponse(
                ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.copyOf(fieldErrors));
    }

    public record FieldError(String field, String message) {
    }
}
