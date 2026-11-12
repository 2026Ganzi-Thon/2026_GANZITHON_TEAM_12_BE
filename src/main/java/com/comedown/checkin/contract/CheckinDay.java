package com.comedown.checkin.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 하루 아침 체크의 읽기 스냅샷.
 *
 * @param conditionCompleted 컨디션·교란 요인 입력까지 끝났는지(completed_at 있음)
 * @param confounded         음주·아픔·카페인 중 하나라도 true. 입력 전이면 false
 * @param measurements       그날 측정(측정 시작 시각 순)
 */
public record CheckinDay(
        long morningCheckId,
        LocalDate date,
        int dayIndex,
        Integer conditionScore,
        Boolean alcohol,
        Boolean sick,
        Boolean caffeine,
        boolean conditionCompleted,
        boolean confounded,
        Instant breathingCompletedAt,
        List<MeasurementSnapshot> measurements) {

    public CheckinDay {
        measurements = List.copyOf(measurements);
    }
}
