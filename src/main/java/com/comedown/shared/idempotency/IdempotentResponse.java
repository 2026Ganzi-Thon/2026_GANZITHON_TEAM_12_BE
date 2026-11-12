package com.comedown.shared.idempotency;

/**
 * 재시도 키로 보호한 작업의 응답.
 *
 * @param status   HTTP 상태 코드(예: 201)
 * @param body     응답 본문
 * @param replayed 같은 키·같은 본문의 재시도라 작업을 다시 실행하지 않고 저장된 응답을 돌려줬으면 true
 */
public record IdempotentResponse<T>(int status, T body, boolean replayed) {

    /** 새로 실행한 작업의 응답. */
    public static <T> IdempotentResponse<T> of(int status, T body) {
        return new IdempotentResponse<>(status, body, false);
    }
}
