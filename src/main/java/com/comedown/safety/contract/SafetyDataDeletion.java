package com.comedown.safety.contract;

/**
 * safety가 공개하는 건강 기록 삭제 계약. 안전 안내 기록(LOW_RECOVERY_2W·CBI_HIGH 모두 건강 근거)을 지운다.
 * 호출하는 쪽의 트랜잭션에 참여한다.
 */
public interface SafetyDataDeletion {

    /** @return 지운 안내 수 */
    int deleteAll(long userId);
}
