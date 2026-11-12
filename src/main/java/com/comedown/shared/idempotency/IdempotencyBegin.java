package com.comedown.shared.idempotency;

/**
 * {@link IdempotencyService#begin} 결과. 같은 키·다른 본문은 결과가 아니라 409 IDEMPOTENCY_CONFLICT 예외다.
 */
public sealed interface IdempotencyBegin {

    /** 처음 받은 키. PROCESSING 행을 만들었으니 작업 후 complete 또는 abandon을 호출한다. */
    record Started(long recordId) implements IdempotencyBegin {
    }

    /** 이미 끝난 같은 요청. 저장된 응답을 그대로 돌려준다. */
    record Replay(int status, String bodyJson) implements IdempotencyBegin {
    }

    /**
     * 같은 요청이 아직 PROCESSING. 동시에 들어온 중복 요청이거나, 외부 호출(추천 승인 등) 결과를 아직 확인하지 못한 작업이다.
     * 일반 작업은 409로 응답하고, 외부 등록 흐름은 등록 키로 결과를 확인해 이어서 처리한다.
     */
    record InProgress(long recordId) implements IdempotencyBegin {
    }
}
