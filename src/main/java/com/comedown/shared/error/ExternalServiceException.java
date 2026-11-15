package com.comedown.shared.error;

/**
 * 외부 서비스(Google 등) 호출 실패. 메시지에는 토큰·응답 본문을 담지 않는다.
 * 호출하는 기능이 {@link Kind}를 보고 API 오류 코드로 바꾼다.
 */
public class ExternalServiceException extends RuntimeException {

    public enum Kind {
        /** 토큰이 만료·철회돼 다시 연결해야 한다(401, invalid_grant). */
        UNAUTHORIZED,
        /** 권한 범위가 부족하다(403 insufficient scope). */
        INSUFFICIENT_SCOPE,
        /** 요청이 거절됐거나 실패했다고 확정된 경우(4xx 등). 재시도해도 같은 결과일 수 있다. */
        FAILURE,
        /** 결과를 알 수 없다(타임아웃·네트워크 오류·5xx). 쓰기 요청은 반영됐을 수 있으므로 실패로 확정하지 않는다. */
        UNCERTAIN
    }

    private final Kind kind;

    public ExternalServiceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ExternalServiceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
