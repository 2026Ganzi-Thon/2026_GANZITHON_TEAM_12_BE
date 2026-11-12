package com.comedown.shared.error;

import java.util.List;

/**
 * Bean Validation 애너테이션으로 표현하기 어려운 요청 검증(필드 간 조건, 큰 배열 등)의 실패.
 * {@link GlobalExceptionHandler}가 400 VALIDATION_FAILED + fieldErrors로 변환한다.
 */
public class FieldValidationException extends RuntimeException {

    private final List<ErrorResponse.FieldError> fieldErrors;

    public FieldValidationException(List<ErrorResponse.FieldError> fieldErrors) {
        super(ErrorCode.VALIDATION_FAILED.defaultMessage());
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            throw new IllegalArgumentException("fieldErrors가 필요합니다");
        }
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static FieldValidationException of(String field, String message) {
        return new FieldValidationException(List.of(new ErrorResponse.FieldError(field, message)));
    }

    public List<ErrorResponse.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
