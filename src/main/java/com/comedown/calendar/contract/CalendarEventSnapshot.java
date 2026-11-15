package com.comedown.calendar.contract;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 다른 기능이 읽는 일정 스냅샷. 제목과 Entity는 담지 않는다.
 *
 * @param category EventCategory 이름. 분류 전이면 null
 * @param startDate 종일 일정일 때만 값이 있다(서울 날짜)
 * @param endDateExclusive 종일 일정일 때만 값이 있다
 */
public record CalendarEventSnapshot(
        Instant start,
        Instant end,
        LocalDate startDate,
        LocalDate endDateExclusive,
        boolean allDay,
        String category,
        boolean deadline,
        boolean createdByApp,
        boolean transparent,
        boolean cancelled) {
}
