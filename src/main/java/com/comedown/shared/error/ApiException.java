package com.comedown.shared.error;

/**
 * 비즈니스 규칙 위반을 API 오류 응답으로 내려보내기 위한 예외.
 * 각 기능은 이 예외에 {@link ErrorCode}를 담아 던지고, HTTP 변환은 {@link GlobalExceptionHandler}가 맡는다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
