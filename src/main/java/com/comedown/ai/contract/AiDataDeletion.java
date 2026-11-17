package com.comedown.ai.contract;

/** ai가 소유한 문구 캐시(ai_outputs) 삭제 계약. 건강 기록 삭제 흐름에서 호출한다. */
public interface AiDataDeletion {

    /**
     * 사용자의 AI 문구 캐시를 모두 지운다(회복 해석·4주 요약은 모두 건강 기록에서 나온 문구다).
     * 호출하는 쪽의 트랜잭션에 참여한다.
     *
     * @return 지운 행 수
     */
    int deleteAll(long userId);
}
