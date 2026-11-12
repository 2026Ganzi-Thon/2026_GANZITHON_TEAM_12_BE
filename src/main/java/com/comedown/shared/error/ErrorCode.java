package com.comedown.shared.error;

import org.springframework.http.HttpStatus;

/**
 * API 명세(0. 공통 규칙 · 오류 응답)의 오류 코드.
 * <p>
 * 명세 표에 없는 코드(FORBIDDEN, CSRF_TOKEN_INVALID, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE, INTERNAL_ERROR)는
 * 프레임워크 수준 오류를 같은 형식으로 내려주기 위한 보완 코드다. 프론트와 합의 후 명세에 반영한다.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인하세요."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    CALENDAR_REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "캘린더를 다시 연결해 주세요."),

    ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "온보딩을 먼저 완료해 주세요."),
    CALENDAR_SCOPE_MISSING(HttpStatus.FORBIDDEN, "캘린더 권한이 필요해요."),
    HRV_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "건강정보 수집 동의가 필요해요."),
    HRV_DISABLED(HttpStatus.FORBIDDEN, "측정 기능이 꺼져 있어요."),
    PREMIUM_REQUIRED(HttpStatus.FORBIDDEN, "구독이 필요한 기능이에요."),

    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없어요."),

    CALENDAR_NOT_CONNECTED(HttpStatus.CONFLICT, "캘린더가 연결되어 있지 않아요."),
    SUGGESTION_NOT_PROPOSED(HttpStatus.CONFLICT, "승인할 수 없는 제안이에요."),
    SLOT_CONFLICT(HttpStatus.CONFLICT, "그 시간에 다른 일정이 생겼어요."),
    CBI_NOT_DUE(HttpStatus.CONFLICT, "아직 다시 응답할 시기가 아니에요."),
    CBI_ALREADY_TAKEN(HttpStatus.CONFLICT, "이미 응답한 회차예요."),
    ONBOARDING_STEP_INVALID(HttpStatus.CONFLICT, "이전 단계를 먼저 완료해 주세요."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없어요."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 재시도 키로 다른 요청을 보냈어요."),

    CALCULATION_POLICY_PENDING(HttpStatus.UNPROCESSABLE_CONTENT, "아직 준비 중인 기능이에요."),

    CALENDAR_API_ERROR(HttpStatus.BAD_GATEWAY, "구글 캘린더 요청에 실패했어요. 다시 시도해 주세요."),

    // ---- 명세 보완 코드 (프레임워크 수준 오류) ----
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없어요."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 만료됐어요. 새로고침 후 다시 시도해 주세요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식이에요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했어요.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
