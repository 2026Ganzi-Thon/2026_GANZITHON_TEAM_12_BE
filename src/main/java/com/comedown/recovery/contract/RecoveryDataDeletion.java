package com.comedown.recovery.contract;

/**
 * recovery가 공개하는 건강 기록 삭제 계약. 일별 회복 상태(기준선·추세·카운터 포함)를 모두 지운다.
 * 원본(checkin 기록)도 함께 지워야 재계산이 값을 되살리지 않는다. 호출하는 쪽의 트랜잭션에 참여한다.
 */
public interface RecoveryDataDeletion {

    /** @return 지운 일별 회복 상태 수 */
    int deleteAll(long userId);
}
