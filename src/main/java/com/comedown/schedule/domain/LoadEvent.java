package com.comedown.schedule.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 부하 계산 입력 일정. 제목은 받지 않는다.
 *
 * @param category 분류 전이면 null(가중치 1.0으로 집계)
 */
public record LoadEvent(
        Instant start,
        Instant end,
        LocalDate startDate,
        LocalDate endDateExclusive,
        boolean allDay,
        LoadCategory category,
        boolean deadline,
        boolean createdByApp,
        boolean transparent,
        boolean cancelled) {

    public LoadEvent {
        if (allDay) {
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDateExclusive, "endDateExclusive");
            if (!startDate.isBefore(endDateExclusive)) {
                throw new IllegalArgumentException("startDate must be before endDateExclusive");
            }
        } else {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("start must be before end");
            }
        }
    }

    public static LoadEvent timed(Instant start, Instant end, LoadCategory category) {
        return new LoadEvent(start, end, null, null, false, category, false, false, false, false);
    }

    public static LoadEvent allDay(LocalDate startDate, LocalDate endDateExclusive,
            LoadCategory category, boolean deadline) {
        return new LoadEvent(null, null, startDate, endDateExclusive, true, category, deadline,
                false, false, false);
    }

    /** 집계 대상 여부: 개인 카테고리·앱 생성·한가함·취소 일정은 제외. */
    boolean isCounted() {
        if (createdByApp || transparent || cancelled) {
            return false;
        }
        return category == null || category.isCounted();
    }

    boolean isDeadlineEvent() {
        return deadline || (category != null && category.isHeavy());
    }
}
