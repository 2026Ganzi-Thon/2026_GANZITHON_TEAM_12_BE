package com.comedown.checkin.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * checkin이 공개하는 아침 체크 읽기 계약.
 */
public interface CheckinQuery {

    /** 그날의 아침 체크. 아직 아무것도 저장하지 않았으면 비어 있다. */
    Optional<CheckinDay> findDay(long userId, LocalDate date);

    /** through 이하 모든 날의 아침 체크(날짜 오름차순). 회복 계산 재생에 쓴다. */
    List<CheckinDay> findDaysThrough(long userId, LocalDate through);
}
