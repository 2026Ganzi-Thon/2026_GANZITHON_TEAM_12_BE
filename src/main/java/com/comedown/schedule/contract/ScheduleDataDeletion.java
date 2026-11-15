package com.comedown.schedule.contract;

/** schedule 소유 데이터(주간 부하) 삭제 계약. 호출하는 쪽의 트랜잭션에 참여한다. */
public interface ScheduleDataDeletion {

    /** @return 지운 주 수 */
    int deleteAll(long userId);
}
