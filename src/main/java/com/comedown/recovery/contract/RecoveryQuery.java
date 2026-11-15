package com.comedown.recovery.contract;

import java.time.LocalDate;
import java.util.Optional;

/**
 * recovery가 공개하는 회복 상태 읽기 계약.
 */
public interface RecoveryQuery {

    /** 그날 회복 상태. 측정·컨디션을 저장하지 않은 날은 비어 있다. */
    Optional<DailyRecoverySnapshot> findDay(long userId, LocalDate date);

    /** date 이하에서 가장 최근 회복 상태(기준선 진행 n/7·확정 여부 표시용). 기록이 없으면 비어 있다. */
    Optional<DailyRecoverySnapshot> findLatestOnOrBefore(long userId, LocalDate date);

    /**
     * 그날 하루 대표값이 된 측정의 id(첫 번째 적격 GOOD·FAIR 측정). 화면에 대표 측정의 심박수 등을 붙일 때 쓴다.
     */
    Optional<Long> findRepresentativeMeasurementId(long userId, LocalDate date);
}
