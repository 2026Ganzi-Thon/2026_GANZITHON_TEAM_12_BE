package com.comedown.calendar.contract;

/**
 * calendar 소유 데이터 삭제 계약: 동기화한 일정과 분류 수정 규칙. 제안이 일정을 참조하고 있으면 지울 수 없으므로
 * 참조를 먼저 해제한 뒤 호출한다. 호출하는 쪽의 트랜잭션에 참여한다.
 */
public interface CalendarDataDeletion {

    /** @return 지운 일정 수 */
    int deleteAll(long userId);
}
