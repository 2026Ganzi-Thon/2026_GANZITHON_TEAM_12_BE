package com.comedown.shared.error;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 Controller 오류를 명세 형식({@link ErrorResponse})으로 변환한다.
 * Spring MVC 표준 예외(잘못된 JSON, 누락 헤더, 405, 415 등)도 같은 형식으로 맞춘다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        ErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, ex.getMessage()));
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ErrorResponse> handleFieldValidation(FieldValidationException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.validation(ex.fieldErrors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.validation(errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.add(new ErrorResponse.FieldError(ge.getObjectName(), ge.getDefaultMessage())));
        return ResponseEntity.badRequest().headers(headers).body(ErrorResponse.validation(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(new ErrorResponse.FieldError(field, error.getDefaultMessage()));
            }
        });
        return ResponseEntity.badRequest().headers(headers).body(ErrorResponse.validation(errors));
    }

    /** 나머지 Spring MVC 표준 예외는 상태 코드만 유지하고 본문을 명세 형식으로 바꾼다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = switch (statusCode.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
        if (statusCode.is5xxServerError()) {
            log.error("Spring MVC error", ex);
        }
        return ResponseEntity.status(statusCode).headers(headers).body(ErrorResponse.of(code));
    }

    private static String lastNode(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
