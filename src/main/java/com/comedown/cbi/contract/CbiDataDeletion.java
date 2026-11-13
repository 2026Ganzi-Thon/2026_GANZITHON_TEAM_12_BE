package com.comedown.cbi.contract;

/**
 * cbi가 공개하는 건강 기록 삭제 계약. CBI 응답 부모(cbi_assessments)와 문항별 답(cbi_answers, FK CASCADE)을 모두 지운다.
 * CBI로 이미 통과한 온보딩 단계는 account가 소유하며 되돌리지 않는다. 호출하는 쪽의 트랜잭션에 참여한다.
 */
public interface CbiDataDeletion {

    /** @return 지운 응답(회차) 수 */
    int deleteAll(long userId);
}
